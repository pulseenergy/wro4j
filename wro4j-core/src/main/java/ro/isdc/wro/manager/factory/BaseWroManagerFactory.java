/*
 * Copyright (c) 2008. All rights reserved.
 */
package ro.isdc.wro.manager.factory;

import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ro.isdc.wro.cache.CacheEntry;
import ro.isdc.wro.cache.CacheStrategy;
import ro.isdc.wro.cache.ContentHashEntry;
import ro.isdc.wro.cache.impl.LruMemoryCacheStrategy;
import ro.isdc.wro.config.WroConfigurationChangeListener;
import ro.isdc.wro.manager.CacheChangeCallbackAware;
import ro.isdc.wro.manager.WroManager;
import ro.isdc.wro.manager.WroManagerFactory;
import ro.isdc.wro.model.WroModel;
import ro.isdc.wro.model.factory.FallbackAwareWroModelFactory;
import ro.isdc.wro.model.factory.InMemoryCacheableWroModelFactory;
import ro.isdc.wro.model.factory.ModelTransformerFactory;
import ro.isdc.wro.model.factory.WroModelFactory;
import ro.isdc.wro.model.factory.XmlModelFactory;
import ro.isdc.wro.model.group.DefaultGroupExtractor;
import ro.isdc.wro.model.group.GroupExtractor;
import ro.isdc.wro.model.group.processor.Injector;
import ro.isdc.wro.model.resource.locator.factory.DefaultUriLocatorFactory;
import ro.isdc.wro.model.resource.locator.factory.UriLocatorFactory;
import ro.isdc.wro.model.resource.processor.factory.DefaultProcesorsFactory;
import ro.isdc.wro.model.resource.processor.factory.ProcessorsFactory;
import ro.isdc.wro.model.resource.util.HashBuilder;
import ro.isdc.wro.model.resource.util.NamingStrategy;
import ro.isdc.wro.model.resource.util.NoOpNamingStrategy;
import ro.isdc.wro.model.resource.util.SHA1HashBuilder;
import ro.isdc.wro.model.transformer.WildcardExpanderModelTransformer;
import ro.isdc.wro.util.DestroyableLazyInitializer;
import ro.isdc.wro.util.ObjectFactory;
import ro.isdc.wro.util.Transformer;


/**
 * Default implementation of {@link WroManagerFactory} which creates default locators and processors and handles the
 * injection logic by creating an {@link Injector} and injecting where it is appropriate.
 *
 * @author Alex Objelean
 * @created Created on Dec 30, 2009
 */
public class BaseWroManagerFactory
  implements WroManagerFactory, WroConfigurationChangeListener, CacheChangeCallbackAware, ObjectFactory<WroManager> {
  private static final Logger LOG = LoggerFactory.getLogger(BaseWroManagerFactory.class);
  /**
   * A callback to be notified about the cache change.
   */
  private PropertyChangeListener cacheChangeCallback;

  private GroupExtractor groupExtractor;
  private WroModelFactory modelFactory;
  private CacheStrategy<CacheEntry, ContentHashEntry> cacheStrategy;
  private HashBuilder hashBuilder;
  /**
   * A list of model transformers. Allows manager to mutate the model before it is being parsed and
   * processed.
   */
  private List<Transformer<WroModel>> modelTransformers;
  private UriLocatorFactory uriLocatorFactory;
  private ProcessorsFactory processorsFactory;
  private NamingStrategy namingStrategy;
  /**
   * Handles the lazy synchronized creation of the manager
   */
  private DestroyableLazyInitializer<WroManager> managerInitializer = new DestroyableLazyInitializer<WroManager>() {
    @Override
    protected WroManager initialize() {
      final WroManager manager = new WroManager();
      if (modelFactory == null) {
        modelFactory = newModelFactory();
      }
      if (groupExtractor == null) {
        groupExtractor = newGroupExtractor();
      }
      if (cacheStrategy == null) {
        cacheStrategy = newCacheStrategy();
      }
      if (hashBuilder == null) {
        hashBuilder = newHashBuilder();
      }
      if (modelTransformers == null) {
        modelTransformers = newModelTransformers();
      }
      if (processorsFactory == null) {
        processorsFactory = newProcessorsFactory();
      }
      if (uriLocatorFactory == null) {
        uriLocatorFactory = newUriLocatorFactory();
      }
      //use NoOp as default naming strategy
      if (namingStrategy == null) {
        namingStrategy = new NoOpNamingStrategy();
      }

      manager.setGroupExtractor(groupExtractor);
      manager.setCacheStrategy(cacheStrategy);
      manager.setHashBuilder(hashBuilder);
      manager.registerCallback(cacheChangeCallback);
      manager.setUriLocatorFactory(uriLocatorFactory);
      manager.setProcessorsFactory(processorsFactory);
      manager.setNamingStrategy(namingStrategy);
      //wrap modelFactory with several useful decorators
      manager.setModelFactory(new ModelTransformerFactory(new InMemoryCacheableWroModelFactory(new FallbackAwareWroModelFactory(
          modelFactory))).setTransformers(modelTransformers));

      final Injector injector = new Injector(manager);
      injector.inject(modelFactory);
      //transformers also require injection
      for (final Transformer<WroModel> transformer : modelTransformers) {
        injector.inject(transformer);
      }

      return manager;
    }
  };

  /**
   * Creates default singleton instance of manager, by initializing manager dependencies with default values
   * (processors).
   */
  public final WroManager create() {
    return managerInitializer.get();
  }

  /**
   * @param namingStrategy the namingStrategy to set
   */
  public BaseWroManagerFactory setNamingStrategy(final NamingStrategy namingStrategy) {
    this.namingStrategy = namingStrategy;
    return this;
  }


  /**
   * @return the namingStrategy
   */
  public NamingStrategy getNamingStrategy() {
    return namingStrategy;
  }

  /**
   * @return default implementation of modelTransformers.
   */
  protected List<Transformer<WroModel>> newModelTransformers() {
    addModelTransformer(new WildcardExpanderModelTransformer());
    return this.modelTransformers;
  }


  /**
   * Override to provide a different or modified default factory implementation.
   *
   * @return {@link ProcessorsFactory} object.
   */
  protected ProcessorsFactory newProcessorsFactory() {
    return new DefaultProcesorsFactory();
  }


  /**
   * Override to provide a different or modified factory.
   *
   * @return {@link UriLocatorFactory} object.
   */
  protected UriLocatorFactory newUriLocatorFactory() {
    return new DefaultUriLocatorFactory();
  }


  /**
   * @return {@link HashBuilder} instance.
   */
  protected HashBuilder newHashBuilder() {
    return new SHA1HashBuilder();
  }


  /**
   * {@inheritDoc}
   */
  public void registerCallback(final PropertyChangeListener callback) {
    this.cacheChangeCallback = callback;
  }


  /**
   * {@inheritDoc}
   */
  public void onCachePeriodChanged(final long period) {
    managerInitializer.get().onCachePeriodChanged(period);
  }


  /**
   * {@inheritDoc}
   */
  public void onModelPeriodChanged(final long period) {
    managerInitializer.get().onModelPeriodChanged(period);
    // update cache too.
    managerInitializer.get().getCacheStrategy().clear();
  }


  /**
   * @return {@link CacheStrategy} instance for resources' group caching.
   */
  protected CacheStrategy<CacheEntry, ContentHashEntry> newCacheStrategy() {
    return new LruMemoryCacheStrategy<CacheEntry, ContentHashEntry>();
  }


  /**
   * @return {@link GroupExtractor} implementation.
   */
  protected GroupExtractor newGroupExtractor() {
    return new DefaultGroupExtractor();
  }


  /**
   * @param servletContext {@link ServletContext} which could be useful for creating dynamic {@link WroModel}.
   * @return {@link WroModelFactory} implementation
   */
  protected WroModelFactory newModelFactory() {
    try {
      LOG.info("Trying to use SmartWroModelFactory as default model factory");
      final Class<? extends WroModelFactory> smartFactoryClass = Class.forName(
        "ro.isdc.wro.extensions.model.factory.SmartWroModelFactory").asSubclass(WroModelFactory.class);
      return smartFactoryClass.newInstance();
    } catch (final Exception e) {
      LOG.info("[FAIL] SmartWroModelFactory is not available. Using default model factory.");
      LOG.debug("Reason: {}", e.toString());
    }
    return new XmlModelFactory();
  }


  /**
   * @param groupExtractor the groupExtractor to set
   */
  public BaseWroManagerFactory setGroupExtractor(final GroupExtractor groupExtractor) {
    this.groupExtractor = groupExtractor;
    return this;
  }


  /**
   * @param modelFactory the modelFactory to set
   */
  public BaseWroManagerFactory setModelFactory(final WroModelFactory modelFactory) {
    this.modelFactory = modelFactory;
    return this;
  }


  /**
   * @param hashBuilder the hashBuilder to set
   */
  public BaseWroManagerFactory setHashBuilder(final HashBuilder hashBuilder) {
    this.hashBuilder = hashBuilder;
    return this;
  }


  /**
   * @param modelTransformers the modelTransformers to set
   */
  public BaseWroManagerFactory setModelTransformers(final List<Transformer<WroModel>> modelTransformers) {
    this.modelTransformers = modelTransformers;
    return this;
  }

  /**
   * Add a single model transformer.
   */
  public BaseWroManagerFactory addModelTransformer(final Transformer<WroModel> modelTransformer) {
    if (modelTransformers == null) {
      modelTransformers = new ArrayList<Transformer<WroModel>>();
    }
    this.modelTransformers.add(modelTransformer);
    return this;
  }

  /**
   * @param cacheStrategy the cacheStrategy to set
   */
  public BaseWroManagerFactory setCacheStrategy(final CacheStrategy<CacheEntry, ContentHashEntry> cacheStrategy) {
    this.cacheStrategy = cacheStrategy;
    return this;
  }


  /**
   * @param uriLocatorFactory the uriLocatorFactory to set
   */
  public BaseWroManagerFactory setUriLocatorFactory(final UriLocatorFactory uriLocatorFactory) {
    this.uriLocatorFactory = uriLocatorFactory;
    return this;
  }

  /**
   * @param processorsFactory the processorsFactory to set
   */
  public void setProcessorsFactory(final ProcessorsFactory processorsFactory) {
    this.processorsFactory = processorsFactory;
  }

  /**
   * {@inheritDoc}
   */
  public void destroy() {
    managerInitializer.destroy();
  }
}
