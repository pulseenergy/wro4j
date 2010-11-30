/**
 * Copyright Alex Objelean
 */
package ro.isdc.wro.model.resource.locator.wildcard;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.FalseFileFilter;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Default implementation of {@link WildcardStreamLocator}.
 *
 * @author Alex Objelean
 * @created May 8, 2010
 */
public class DefaultWildcardStreamLocator
  implements WildcardStreamLocator {
  /**
   * Logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(DefaultWildcardStreamLocator.class);
  /**
   * Character to distinguish wildcard inside the uri. If the file name contains '*' or '?' character, it is considered
   * a wildcard.
   * <p>
   * A string is considered to contain wildcard if it doesn't start with http(s) and contains at least one of the
   * following characters: [?*].
   */
  private static final String WILDCARD_REGEX = "^(?:(?!http))(.)*[\\*\\?]+(.)*";
  /**
   * Character to distinguish wildcard inside the uri.
   */
  private static final String RECURSIVE_WILDCARD = "**";
  /**
   * Comparator used to sort files in alphabetical ascending order.
   */
  public static final Comparator<File> ASCENDING_ORDER = new Comparator<File>() {
    public int compare(final File o1, final File o2) {
      return o1.getPath().compareToIgnoreCase(o2.getPath());
    }
  };
  /**
   * Comparator used to sort files in alphabetical descending order.
   */
  public static final Comparator<File> DESCENDING_ORDER = new Comparator<File>() {
    public int compare(final File o1, final File o2) {
      return o2.getPath().compareToIgnoreCase(o1.getPath());
    }
  };

  /**
   * {@inheritDoc}
   */
  public boolean hasWildcard(final String uri) {
    return uri.matches(WILDCARD_REGEX);
  }


  /**
   * {@inheritDoc}
   */
  @SuppressWarnings("unchecked")
  public InputStream locateStream(final String uri, final File folder)
    throws IOException {
    if (uri == null || folder == null || !folder.isDirectory()) {
      final StringBuffer message = new StringBuffer("Invalid folder provided");
      if (folder != null) {
        message.append(", with path: " + folder.getPath());
      }
      message.append(", with uri: " + uri);
      throw new IOException(message.toString());
    }

    final String wildcard = FilenameUtils.getName(uri);
    LOG.debug("wildcard: " + wildcard);
    final WildcardFileFilter fileFilter = new WildcardFileFilter(wildcard);
    final IOFileFilter folderFilter = getFolderFilter(wildcard);
    final List<File> files = new ArrayList<File>(FileUtils.listFiles(folder, fileFilter, folderFilter));
    sortFiles(files);

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (files.isEmpty()) {
      final String message = "No files found insinde the " + folder.getPath() + " for wildcard: " + wildcard;
      LOG.warn(message);
    }
    for (final File file : files) {
      LOG.debug("file: " + file.getName());
      final InputStream is = new FileInputStream(file);
      IOUtils.copy(is, out);
    }
    out.close();
    return new ByteArrayInputStream(out.toByteArray());
  }


  /**
   * Sort the files collection using by default alphabetical order. Override this method to provide a different type of
   * sorting. Or do nothing to leave it with its natural order.
   *
   * @param files - the collection to sort.
   */
  protected void sortFiles(final List<File> files) {
    Collections.sort(files, ASCENDING_ORDER);
  }


  /**
   * @param wildcard to use to determine if the folder filter should be recursive or not.
   * @return filter to be used for folders.
   */
  private IOFileFilter getFolderFilter(final String wildcard) {
    final boolean recursive = wildcard.contains(RECURSIVE_WILDCARD);
    return recursive ? TrueFileFilter.INSTANCE : FalseFileFilter.INSTANCE;
  }

  // public static void main(final String[] args)
  // throws Exception {
  // final WildcardStreamLocator locator = new DefaultWildcardStreamLocator();
  // final File folder = new File("C:\\jde\\projects\\github\\jquery\\jquery\\");
  // final InputStream is = locator.locateStream("*", folder);
  // IOUtils.copy(is, System.out);
  // is.close();
  // }

}
