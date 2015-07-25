/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import java.io.IOException;
import java.io.Serializable;

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
   public String getClassPathFileName() {
      return pkg.fileName;
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
}
