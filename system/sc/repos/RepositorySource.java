/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

public class RepositorySource {
   IRepositoryManager repository;
   String url;
   boolean unzip;

   RepositoryPackage pkg;

   public RepositorySource(IRepositoryManager mgr, String url, boolean unzip) {
      this.repository = mgr;
      this.url = url;
      this.unzip = unzip;
   }
}
