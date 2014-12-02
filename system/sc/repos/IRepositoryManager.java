/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.lang.IMessageHandler;

/**
 */
public interface IRepositoryManager {
   /** Returns null if successful, otherwise an error message. */
   public String install(RepositorySource toInstall);
   /** Returns null if successful, otherwise an error message. */
   public String update(RepositorySource toInstall);
   public boolean isActive();
   public String getManagerName();

   /** Returns the root dir defining where these packages should be installed */
   public String getPackageRoot();

   public void setMessageHandler(IMessageHandler handler);
}
