/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.util.IMessageHandler;
import sc.repos.mvn.MvnRepositoryManager;

import java.util.HashMap;
import java.util.TreeMap;

/**
 * The global repository system.  It stores the list of repository managers and manages their activation.
 * It keeps a cache of the set of packages we know about and the sources for each package.
 */
public class RepositorySystem {
   public String packageRoot;

   public boolean updateSystem;

   public HashMap<String,RepositoryPackage> packages = new HashMap<String,RepositoryPackage>();

   public RepositorySystem(String rootDir, IMessageHandler handler, boolean info) {
      packageRoot = rootDir;

      addRepositoryManager(new ScpRepositoryManager(this, "scp", packageRoot, handler, info));
      addRepositoryManager(new GitRepositoryManager(this, "git", packageRoot, handler, info));
      addRepositoryManager(new URLRepositoryManager(this, "url", packageRoot, handler, info));
      addRepositoryManager(new MvnRepositoryManager(this, "mvn", packageRoot, handler, info));
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

   public RepositoryPackage addPackageSource(IRepositoryManager mgr, String pkgName, String fileName, RepositorySource repoSrc, boolean enabled) {
      RepositoryPackage pkg;
      pkg = packages.get(pkgName);
      if (pkg == null) {
         pkg = new RepositoryPackage(mgr, pkgName, fileName, repoSrc);
         packages.put(pkgName, pkg);
      }
      else {
         pkg.addNewSource(repoSrc);
      }

      if (enabled && !pkg.installed) {
         // We do the install and update immediately after they are added so that the layer definition file has
         // access to the installed state, to for example, list the contents of the lib directory to get the jar files
         // to add to the classpath.
         String err = pkg.install();
         if (err != null) {
            System.err.println("Failed to install repository package: " + pkg.packageName + " for layer: " + this + " error: " + err);
         }
         else if (updateSystem) {
            pkg.update();
         }
      }
      return pkg;
   }
}
