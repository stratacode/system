/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.util.URLUtil;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

/**
 * The RepositorySource helps in the definition of the RepositoryPackage.  You can have more than one source
 * for the same package from different managers for example and then try them on after the other.  You might
 * have two sources from the same package with different versions of the package, and the package manager
 * is responsible for picking the right version to use.
 *
 * TODO: should this be folded back into the RepositoryPackage?  When we configure RepositoryPackages as components
 * in the layer, is less useful to define multiple sources.  Rather, you might instead let the layer stacking order
 * refine one instance of the RepositoryPackage to allow customization and refinement that way.
 */
public class RepositorySource implements Serializable {
   public transient IRepositoryManager repository;
   public String managerName;
   public String url;
   public String srcURL;
   public transient RepositoryPackage parentPkg; // For sources defined inside of a sub-directory.  This is the path from the package root to the parent's folder, then url is the modulePath (or path to the package's directory)
   public boolean unzip;
   /** Set this to true if we need to put the contents of the zip file in a directory with the same name as the base of the zip file (e.g. pgsql) */
   public boolean unwrapZip;

   // Represents the state at which this source was generated when the source came from a dependency.
   // Right now, just stores the tree-depth of the reference
   public DependencyContext ctx;

   public RepositoryPackage pkg;

   public RepositorySource(IRepositoryManager mgr, String url, boolean unzip, boolean unwrapZip, RepositoryPackage parentPkg) {
      this.repository = mgr;
      this.managerName = mgr.getManagerName();
      this.url = url;
      this.srcURL = url;
      this.unzip = unzip;
      this.unwrapZip = unwrapZip;
      this.parentPkg = parentPkg;
   }

   public boolean equals(Object other) {
      if (!(other instanceof RepositorySource))
         return false;
      RepositorySource otherSrc = (RepositorySource) other;
      // Sometimes the url will not be set to the canonical URL but the srcURL might be set properly
      return otherSrc.url.equals(url) || (srcURL != null && otherSrc.srcURL != null && srcURL.equals(otherSrc.srcURL));
   }

   /** If the version number is in the file name, it will be different for each source */
   public List<String> getClassPathFileNames() {
      return pkg.fileNames;
   }

   public void init(RepositorySystem sys) {
      if (repository == null)
         repository = sys.getRepositoryManager(managerName);
   }

   public String toString() {
      if (srcURL != null && url != null && !url.equals(srcURL)) {
         return url + "(" + srcURL + ")";
      }
      return url;
   }

   public boolean mergeSource(RepositorySource other) {
      return mergeExclusions(other);
   }

   public boolean mergeExclusions(RepositorySource other) {
      return false;
   }

   /** When creating a package from a URL only, this can extract the package name to use from the URL. */
   public String getDefaultPackageName() {
      return url == null ? null : URLUtil.getFileName(url);
   }

   public String getDefaultFileName() {
      return null;
   }

   public void updateAfterRestore(RepositoryPackage pkg) {
      this.pkg = pkg;
      if (ctx != null) {
         ctx.updateAfterRestore(repository);
      }
   }

   public String getPackageSrcURL() {
      if (srcURL != null)
         return srcURL;
      return url;
   }
}
