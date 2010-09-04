/*
 * Copyright (c) 2008. All rights reserved.
 */
package ro.isdc.wro.manager;

import java.beans.PropertyChangeListener;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ro.isdc.wro.WroRuntimeException;
import ro.isdc.wro.cache.CacheEntry;
import ro.isdc.wro.cache.CacheStrategy;
import ro.isdc.wro.cache.ContentHashEntry;
import ro.isdc.wro.config.Context;
import ro.isdc.wro.config.WroConfigurationChangeListener;
import ro.isdc.wro.http.HttpHeader;
import ro.isdc.wro.http.UnauthorizedRequestException;
import ro.isdc.wro.model.WroModel;
import ro.isdc.wro.model.factory.WroModelFactory;
import ro.isdc.wro.model.group.Group;
import ro.isdc.wro.model.group.GroupExtractor;
import ro.isdc.wro.model.group.processor.GroupsProcessor;
import ro.isdc.wro.model.resource.FingerprintCreator;
import ro.isdc.wro.model.resource.ResourceType;
import ro.isdc.wro.model.resource.locator.UriLocator;
import ro.isdc.wro.model.resource.processor.impl.css.CssUrlRewritingProcessor;
import ro.isdc.wro.util.StopWatch;
import ro.isdc.wro.util.WroUtil;


/**
 * Contains all the factories used by optimizer in order to perform the logic.
 *
 * @author Alex Objelean
 * @created Created on Oct 30, 2008
 */
public class WroManager
    implements WroConfigurationChangeListener, CacheChangeCallbackAware {
  /**
   * Logger for this class.
   */
  private static final Logger LOG = LoggerFactory.getLogger(WroManager.class);
  /**
   * wro API mapping path. If request uri contains this, exposed API method will be invoked.
   */
  public static final String PATH_API = "wroAPI";
  /**
   * API - reload cache method call
   */
  public static final String API_RELOAD_CACHE = "reloadCache";
  /**
   * API - reload model method call
   */
  public static final String API_RELOAD_MODEL = "reloadModel";
  /**
   * ResourcesModel factory.
   */
  private WroModelFactory modelFactory;

  /**
   * GroupExtractor.
   */
  private GroupExtractor groupExtractor;

  /**
   * Groups processor.
   */
  private GroupsProcessor groupsProcessor;
  /**
   * Content digester.
   */
  private FingerprintCreator fingerprintCreator;

  /**
   * A cacheStrategy used for caching processed results. <GroupName, processed result>.
   */
  private CacheStrategy<CacheEntry, ContentHashEntry> cacheStrategy;
  /**
   * A callback to be notified about the cache change.
   */
  private PropertyChangeListener cacheChangeCallback;
  /**
   * Scheduled executors service, used to update the output result.
   */
  private ScheduledExecutorService scheduler;

  /**
   * Perform processing of the uri.
   *
   * @param request
   *          {@link HttpServletRequest} to process.
   * @param response HttpServletResponse where to write the result content.
   * @throws IOException when any IO related problem occurs.
   */
  public final void process(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
    //TODO return true if the request was processed in order to continue filter chain processing
    LOG.debug("processing: " + request.getRequestURI());
    validate();
    InputStream is = null;
    // create model
    final WroModel model = modelFactory.getInstance();
    //TODO move API related checks into separate class and determine filter mapping for better mapping
    if (isApiRequest(request)) {
      //must be case insensitive
      if (request.getRequestURI().contains(API_RELOAD_CACHE)) {
        Context.getConfig().reloadCache();
        return;
      }
      if (request.getRequestURI().contains(API_RELOAD_MODEL)) {
        Context.getConfig().reloadModel();
        return;
      }
    } else if (isProxyResourceRequest(request)) {
      is = locateInputeStream(request);
    } else {
      is = buildGroupsInputStream(model, request, response);
    }
    // use gziped response if supported
    final OutputStream os = getGzipedOutputStream(response);
    IOUtils.copy(is, os);
    is.close();
    os.close();
  }


  /**
   * Check if this is an API call (used to call some of the operations exposed by wro). API is exposed only in DEBUG mode.
   */
  private boolean isApiRequest(final HttpServletRequest request) {
    return Context.getConfig().isDebug() && request.getRequestURI().contains(PATH_API);
  }

  /**
   * Check if this is a request for a proxy resource - a resource which url is overwritten by wro4j.
   */
  private boolean isProxyResourceRequest(final HttpServletRequest request) {
    return request.getRequestURI().contains(CssUrlRewritingProcessor.PATH_RESOURCES);
  }


  /**
   * Add gzip header to response and wrap the response {@link OutputStream} with {@link GZIPOutputStream}.
   *
   * @param response
   *          {@link HttpServletResponse} object.
   * @return wrapped gziped OutputStream.
   * @throws IOException when Gzip operation fails.
   */
  private OutputStream getGzipedOutputStream(final HttpServletResponse response) throws IOException {
    if (Context.getConfig().isGzipEnabled() && isGzipSupported()) {
      // add gzip header and gzip response
      response.setHeader(HttpHeader.CONTENT_ENCODING.toString(), "gzip");
      // Create a gzip stream
      return new GZIPOutputStream(response.getOutputStream());
    }
    LOG.debug("Gziping outputStream response");
    return response.getOutputStream();
  }


  /**
   * @param model the model used to build stream.
   * @param request {@link HttpServletRequest} for this request cycle.
   * @param response {@link HttpServletResponse} used to set content type.
   * @return {@link InputStream} for groups found in requestURI or null if the request is not as expected.
   */
  private InputStream buildGroupsInputStream(final WroModel model, final HttpServletRequest request,
      final HttpServletResponse response) {
    final StopWatch stopWatch = new StopWatch();
    stopWatch.start("buildGroupsStream");
    InputStream is = null;
    // find names & type
    final ResourceType type = groupExtractor.getResourceType(request);
    final String groupName = groupExtractor.getGroupName(request);
    final boolean minimize = groupExtractor.isMinimized(request);
    if (groupName == null || type == null) {
      throw new WroRuntimeException("No groups found for request: " + request.getRequestURI());
    }
    initScheduler(model);

    final CacheEntry cacheEntry = new CacheEntry(groupName, type, minimize);

    LOG.debug("Searching cache entry: " + cacheEntry);
    // Cache based on uri
    ContentHashEntry result = cacheStrategy.get(cacheEntry);
    if (result == null) {
      LOG.debug("Cache is empty. Perform processing...");
      // process groups & put result in the cache
      // find processed result for a group
      final List<Group> groupAsList = new ArrayList<Group>();
      final Group group = model.getGroupByName(groupName);
      groupAsList.add(group);

      final String content = groupsProcessor.process(groupAsList, type, minimize);
      result = getContentHashEntry(content);
      cacheStrategy.put(cacheEntry, result);
    }
    if (result.getContent() != null) {
      is = new ByteArrayInputStream(result.getContent().getBytes());
    }
    if (type != null) {
      response.setContentType(type.getContentType());
    }
    //set ETag header
    response.setHeader(HttpHeader.ETAG.toString(), result.getHash());

    stopWatch.stop();
    LOG.debug("WroManager process time: " + stopWatch.toString());
    return is;
  }


  /**
   * Creates a {@link ContentHashEntry} for a given content.
   */
  private ContentHashEntry getContentHashEntry(final String content) {
    String hash = null;
    if (content != null) {
      hash = fingerprintCreator.create(content);
    }
    final ContentHashEntry entry = ContentHashEntry.valueOf(content, hash);
    LOG.debug("computed entry: " + entry);
    return entry;
  }


  /**
   * @param model
   *          {@link WroModel} object.
   */
  private void initScheduler(final WroModel model) {
    if (scheduler == null) {
      final long period = Context.getConfig().getCacheUpdatePeriod();
      LOG.debug("runing thread with period of " + period);
      if (period > 0) {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        // Run a scheduled task which updates the model
        // here a scheduleWithFixedDelay is used instead of scheduleAtFixedRate because the later can cause a problem
        // (thread tries to make up for lost time in some situations)
        scheduler.scheduleWithFixedDelay(getSchedulerRunnable(model), 0, period, TimeUnit.SECONDS);
      }
    }
  }


  /**
   * @param model
   *          Model containing
   * @return a {@link Runnable} which will update the cache content with latest data.
   */
  private Runnable getSchedulerRunnable(final WroModel model) {
    return new Runnable() {
      public void run() {
        try {
          if (cacheChangeCallback != null) {
            // invoke cacheChangeCallback
            cacheChangeCallback.propertyChange(null);
          }
          LOG.info("reloading cache");
          // process groups & put update cache
          final Collection<Group> groups = model.getGroups();
          // update cache for all resources
          for (final Group group : groups) {
            for (final ResourceType resourceType : ResourceType.values()) {
              if (group.hasResourcesOfType(resourceType)) {
                final Collection<Group> groupAsList = new HashSet<Group>();
                groupAsList.add(group);
                // TODO notify the filter about the change - expose a callback
                // TODO check if request parameter can be fetched here without errors.
                // groupExtractor.isMinimized(Context.get().getRequest())
                final Boolean[] minimizeValues = new Boolean[] {
                    true, false
                };
                for (final boolean minimize : minimizeValues) {
                  final String content = groupsProcessor.process(groupAsList, resourceType, minimize);
                  cacheStrategy.put(new CacheEntry(group.getName(), resourceType, minimize), getContentHashEntry(content));
                }
              }
            }
          }
        } catch (final Exception e) {
          // Catch all exception in order to avoid situation when scheduler runs out of threads.
          LOG.error("Exception occured: ", e);
        }
      }
    };
  }


  /**
   * {@inheritDoc}
   */
  public final void registerCallback(final PropertyChangeListener callback) {
    this.cacheChangeCallback = callback;
  }


  /**
   * Allow subclasses to turn off gzipping.
   *
   * @return true if Gzip is Supported
   */
  protected boolean isGzipSupported() {
    return WroUtil.isGzipSupported(Context.get().getRequest());
  }


  /**
   * Resolve the stream for a request.
   *
   * @param request
   *          {@link HttpServletRequest} object.
   * @return {@link InputStream} not null object if the resource is valid and can be accessed
   * @throws IOException
   *           if no stream could be resolved.
   */
  private InputStream locateInputeStream(final HttpServletRequest request) throws IOException {
    final String resourceId = request.getParameter(CssUrlRewritingProcessor.PARAM_RESOURCE_ID);
    LOG.debug("locating stream for resourceId: " + resourceId);
    final UriLocator uriLocator = groupsProcessor.getUriLocatorFactory().getInstance(resourceId);
    final CssUrlRewritingProcessor processor = groupsProcessor.findPreProcessorByClass(CssUrlRewritingProcessor.class);
    if (processor != null && !processor.isUriAllowed(resourceId)) {
      throw new UnauthorizedRequestException("Unauthorized resource request detected! " + request.getRequestURI());
    }
    final InputStream is = uriLocator.locate(resourceId);
    if (is == null) {
      throw new WroRuntimeException("Could not Locate resource: " + resourceId);
    }
    return is;
  }


  /**
   * {@inheritDoc}
   */
  public final void onCachePeriodChanged() {
    LOG.info("CacheChange event triggered!");
    if (scheduler != null) {
      scheduler.shutdown();
      scheduler = null;
    }
    // flush the cache by destroying it.
    cacheStrategy.clear();
  }


  /**
   * {@inheritDoc}
   */
  public final void onModelPeriodChanged() {
    LOG.info("ModelChange event triggered!");
    // update the cache also when model is changed.
    onCachePeriodChanged();
    if (modelFactory instanceof WroConfigurationChangeListener) {
      ((WroConfigurationChangeListener)modelFactory).onModelPeriodChanged();
    }
  }


  /**
   * Called when {@link WroManager} is being taken out of service.
   */
  public final void destroy() {
    LOG.debug("WroManager destroyed");
    cacheStrategy.destroy();
    modelFactory.destroy();
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }


  /**
   * Check if all dependencies are set.
   */
  private void validate() {
    if (this.groupExtractor == null) {
      throw new WroRuntimeException("GroupExtractor was not set!");
    }
    if (this.modelFactory == null) {
      throw new WroRuntimeException("ModelFactory was not set!");
    }
    if (this.groupsProcessor == null) {
      throw new WroRuntimeException("GroupsProcessor was not set!");
    }
    if (this.cacheStrategy == null) {
      throw new WroRuntimeException("cacheStrategy was not set!");
    }
    if (this.fingerprintCreator == null) {
      throw new WroRuntimeException("fingerprintCreator was not set!");
    }
  }


  /**
   * @param groupExtractor
   *          the uriProcessor to set
   */
  public final void setGroupExtractor(final GroupExtractor groupExtractor) {
    if (groupExtractor == null) {
      throw new IllegalArgumentException("GroupExtractor cannot be null!");
    }
    this.groupExtractor = groupExtractor;
  }


  /**
   * @param groupsProcessor
   *          the groupsProcessor to set
   */
  public final void setGroupsProcessor(final GroupsProcessor groupsProcessor) {
    if (groupsProcessor == null) {
      throw new IllegalArgumentException("GroupsProcessor cannot be null!");
    }
    this.groupsProcessor = groupsProcessor;
  }


  /**
   * @param modelFactory
   *          the modelFactory to set
   */
  public final void setModelFactory(final WroModelFactory modelFactory) {
    if (modelFactory == null) {
      throw new IllegalArgumentException("WroModelFactory cannot be null!");
    }
    this.modelFactory = modelFactory;
  }


  /**
   * @param cacheStrategy
   *          the cache to set
   */
  public final void setCacheStrategy(final CacheStrategy<CacheEntry, ContentHashEntry> cacheStrategy) {
    if (cacheStrategy == null) {
      throw new IllegalArgumentException("cacheStrategy cannot be null!");
    }
    this.cacheStrategy = cacheStrategy;
  }


  /**
   * @param contentDigester the contentDigester to set
   */
  public void setFingerprintCreator(final FingerprintCreator contentDigester) {
    this.fingerprintCreator = contentDigester;
  }


  /**
   * @return the modelFactory
   */
  public final WroModelFactory getModelFactory() {
    return this.modelFactory;
  }


  /**
   * @return the groupExtractor
   */
  public final GroupExtractor getGroupExtractor() {
    return this.groupExtractor;
  }
}
