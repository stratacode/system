/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import java.io.Serializable;

public class RepositorySource implements Serializable {
   public transient IRepositoryManager repository;
   public String url;
   public boolean unzip;

   public RepositoryPackage pkg;

   public RepositorySource(IRepositoryManager mgr, String url, boolean unzip) {
      this.repository = mgr;
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
}
