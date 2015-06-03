/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.util.IMessageHandler;
import sc.repos.mvn.MvnRepositoryManager;
import sc.util.MessageHandler;

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

   public IMessageHandler msg;

   public RepositorySystem(String rootDir, IMessageHandler handler, boolean info) {
      packageRoot = rootDir;

      msg = handler;

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

   public RepositoryPackage addPackage(String url, boolean install) {
      int ix = url.indexOf(":");
      if (ix == 0) {
         MessageHandler.error(msg, "addRepoitoryPackage - invalid URL - missing type://values");
         return null;
      }
      String repositoryTypeName = url.substring(0, ix);
      IRepositoryManager mgr = getRepositoryManager(repositoryTypeName);
      if (mgr == null) {
         MessageHandler.error(msg, "No repository with name: " + repositoryTypeName + " for add package");
         return null;
      }

      RepositoryPackage pkg = mgr.createPackage(url);
      if (pkg == null)
         return null;
      RepositoryPackage oldPkg = packages.get(pkg.packageName);
      if (oldPkg != null) {
         oldPkg.addNewSource(pkg.currentSource);
         return oldPkg;
      }
      packages.put(pkg.packageName, pkg);
      if (install) {
         installPackage(pkg);
      }
      return pkg;
   }

   public RepositoryPackage addPackageSource(IRepositoryManager mgr, String pkgName, String fileName, RepositorySource repoSrc, boolean install) {
      RepositoryPackage pkg;
      pkg = packages.get(pkgName);
      if (pkg == null) {
         pkg = new RepositoryPackage(mgr, pkgName, fileName, repoSrc);
         packages.put(pkgName, pkg);
      }
      else {
         pkg.addNewSource(repoSrc);
      }

      if (install) {
         installPackage(pkg);
      }
      return pkg;
   }

   public void installPackage(RepositoryPackage pkg) {
      if (!pkg.installed) {
         // We do the install and update immediately after they are added so that the layer definition file has
         // access to the installed state, to for example, list the contents of the lib directory to get the jar files
         // to add to the classpath.
         String err = pkg.install();
         if (err != null) {
            MessageHandler.error(msg, "Failed to install repository package: " + pkg.packageName + " for layer: " + this + " error: " + err);
         }
         else if (updateSystem) {
            pkg.update();
         }
      }
   }
}
