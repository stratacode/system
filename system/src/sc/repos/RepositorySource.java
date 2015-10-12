/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
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
   public boolean unzip;

   // Represents the state at which this source was generated when the source came from a dependency.
   // Right now, just stores the tree-depth of the reference
   public DependencyContext ctx;

   public RepositoryPackage pkg;

   public RepositorySource(IRepositoryManager mgr, String url, boolean unzip) {
      this.repository = mgr;
      this.managerName = mgr.getManagerName();
      this.url = url;
      this.unzip = unzip;
   }

   public boolean equals(Object other) {
      if (!(other instanceof RepositorySource))
         return false;
      return ((RepositorySource) other).url.equals(url);
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
      return url;
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
}
