/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.lang.IMessageHandler;
import sc.layer.LayeredSystem;

import java.util.TreeMap;

/**
 * The global repository system.  It stores the list of repository managers and manages their activation.
 */
public class RepositorySystem {
   public String packageRoot;

   public RepositorySystem(String rootDir, IMessageHandler handler, boolean info) {
      packageRoot = rootDir;

      addRepositoryManager(new ScpRepositoryManager("scp", packageRoot, handler, info));
      addRepositoryManager(new GitRepositoryManager("git", packageRoot, handler, info));
      addRepositoryManager(new URLRepositoryManager("url", packageRoot, handler, info));
   }

   public IRepositoryManager[] repositories;
   public IRepositoryManager[] activeRepositories;

   public TreeMap<String, IRepositoryManager> repositoriesByName = new TreeMap<String, IRepositoryManager>();

   public IRepositoryManager getRepositoryManager(String repositoryTypeName) {
      return repositoriesByName.get(repositoryTypeName);
   }

   public IRepositoryManager addRepositoryManager(IRepositoryManager mgr) {
      return repositoriesByName.put(mgr.getManagerName(), mgr);
   }

   public void setMessageHandler(IMessageHandler handler) {
      for (IRepositoryManager mgr:repositoriesByName.values()) {
         mgr.setMessageHandler(handler);
      }
   }
}
