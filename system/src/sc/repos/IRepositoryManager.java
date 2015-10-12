/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.util.IMessageHandler;

/**
 */
public interface IRepositoryManager {
   /** Returns null if successful, otherwise an error message. */
   public String preInstall(RepositorySource toInstall, DependencyContext ctx, DependencyCollection deps);

   /** Performs the complete install */
   public String install(RepositorySource toInstall, DependencyContext ctx);

   void completeInstall(RepositoryPackage pkg);

   /** Returns null if successful, otherwise an error message. */
   public String update(RepositorySource toInstall);
   public boolean isActive();
   public String getManagerName();

   /** Returns the root dir defining where these packages should be installed */
   public String getPackageRoot();

   public void setMessageHandler(IMessageHandler handler);

   public RepositoryPackage createPackage(String url);

   public RepositoryPackage getOrCreatePackage(String url, RepositoryPackage parent, boolean install);

   public RepositoryPackage createPackage(IRepositoryManager mgr, String packageName, String fileName, RepositorySource src, RepositoryPackage parentPkg);

   public RepositorySystem getRepositorySystem();

   RepositorySource createRepositorySource(String url, boolean unzip);

}
