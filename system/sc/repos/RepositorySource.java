/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

public class RepositorySource {
   public IRepositoryManager repository;
   public String url;
   public boolean unzip;

   public RepositoryPackage pkg;

   public RepositorySource(IRepositoryManager mgr, String url, boolean unzip) {
      this.repository = mgr;
      this.url = url;
      this.unzip = unzip;
   }
}
