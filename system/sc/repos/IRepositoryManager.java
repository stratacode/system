/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

/**
 */
public interface IRepositoryManager {
   /** Returns null if successful, otherwise an error message. */
   public String install(RepositorySource toInstall);
   /** Returns null if successful, otherwise an error message. */
   public String update(RepositorySource toInstall);
   public boolean isActive();
   public String getManagerName();
}
