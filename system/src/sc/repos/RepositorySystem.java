/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.util.IMessageHandler;
import sc.repos.mvn.MvnRepositoryManager;
import sc.util.MessageHandler;

import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeMap;

/**
 * The global repository system.  It stores the list of repository managers and manages their activation.
 * It keeps a cache of the set of packages we know about and the sources for each package.
 */
public class RepositorySystem {
   public RepositoryStore store;

   public boolean updateSystem;
   public boolean reinstallSystem;
   public boolean installExisting;

   public HashSet<String> classPath = new HashSet<String>();

   public IMessageHandler msg;

   public RepositorySystem(RepositoryStore store, IMessageHandler handler, boolean info, boolean reinstall, boolean update, boolean installExisting) {
      this.store = store;

      msg = handler;
      reinstallSystem = reinstall;
      updateSystem = update;
      this.installExisting = installExisting;

      addRepositoryManager(new ScpRepositoryManager(this, "scp", store.packageRoot, handler, info));
      addRepositoryManager(new GitRepositoryManager(this, "git", store.packageRoot, handler, info));
      addRepositoryManager(new URLRepositoryManager(this, "url", store.packageRoot, handler, info));
      addRepositoryManager(new MvnRepositoryManager(this, "mvn", store.packageRoot, handler, info));
      addRepositoryManager(new MvnRepositoryManager(this, "git-mvn", store.packageRoot, handler, info, new GitRepositoryManager(this, "git-mvn", store.packageRoot, handler, info)));
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
      RepositoryPackage oldPkg = store.packages.get(pkg.packageName);
      if (oldPkg != null) {
         oldPkg.addNewSource(pkg.sources[0]);
         if (oldPkg.installed)
            return oldPkg;
         else
            pkg = oldPkg;
      }
      else
         store.packages.put(pkg.packageName, pkg);
      if (install) {
         installPackage(pkg, null);
      }
      return pkg;
   }

   public RepositoryPackage addRepositoryPackage(RepositoryPackage pkg) {
      RepositoryPackage existingPkg = store.packages.get(pkg.packageName);
      if (existingPkg != null) {
         pkg.installedRoot = existingPkg.installedRoot;
         pkg.currentSource = existingPkg.currentSource;
         pkg.definesClasses = existingPkg.definesClasses;
         pkg.installed = existingPkg.installed;
         pkg.sources = new RepositorySource[1];
         pkg.sources[0] = pkg.currentSource;
         return existingPkg;
      }
      else {
         store.packages.put(pkg.packageName, pkg);
         return pkg;
      }
   }

   public RepositoryPackage addPackageSource(IRepositoryManager mgr, String pkgName, String fileName, RepositorySource repoSrc, boolean install) {
      RepositoryPackage pkg;
      pkg = store.packages.get(pkgName);
      if (pkg == null) {
         pkg = mgr.createPackage(mgr, pkgName, fileName, repoSrc);
         pkg.currentSource = repoSrc;
         store.packages.put(pkgName, pkg);
      }
      else {
         pkg.addNewSource(repoSrc);
         // We may first encounter a package from a Maven module reference - where we are not installing the package.  That's a weaker reference
         // than if we are installing it so use this new reference.
         if (!pkg.installed && install) {
            pkg.currentSource = repoSrc;
            pkg.fileName = repoSrc.getClassPathFileName();
         }
      }

      if (install) {
         installPackage(pkg, null);
      }
      return pkg;
   }

   public RepositoryPackage addPackage(IRepositoryManager mgr, RepositoryPackage newPkg, boolean install, DependencyContext ctx) {
      RepositoryPackage pkg;
      String pkgName = newPkg.packageName;
      pkg = store.packages.get(pkgName);
      if (pkg == null) {
         pkg = newPkg;
         store.packages.put(pkgName, pkg);
      }
      else {
         for (RepositorySource src : newPkg.sources)
            pkg.addNewSource(src);
         // We may first encounter a package from a Maven module reference - where we are not installing the package.  That's a weaker reference
         // than if we are installing it so use this new reference.
         if (!pkg.installed && install) {
            RepositorySource newSrc = newPkg.currentSource == null ? newPkg.sources[0] : newPkg.currentSource;
            pkg.updateCurrentSource(newSrc);
         }
      }

      if (install) {
         installPackage(pkg, ctx);
      }
      return pkg;
   }

   public void preInstallPackage(RepositoryPackage pkg, DependencyContext ctx, DependencyCollection depCol) {
      if (!pkg.installed) {
         // We do the install and update immediately after they are added so that the layer definition file has
         // access to the installed state, to for example, list the contents of the lib directory to get the jar files
         // to add to the classpath.
         String err = pkg.preInstall(ctx, depCol);
         if (err != null) {
            MessageHandler.error(msg, "Failed to install repository package: " + pkg.packageName + " error: " + err);
         }
         else if (updateSystem) {
            pkg.update();
         }
      }
   }

   public static String installDeps(DependencyCollection instDeps) {
      do {
         DependencyCollection nextDeps = new DependencyCollection();
         for (PackageDependency pkgDep : instDeps.neededDeps) {
            if (!pkgDep.pkg.installed)
               pkgDep.pkg.preInstall(pkgDep.ctx, nextDeps);
         }
         if (nextDeps.neededDeps.size() == 0)
            return null;
         instDeps = nextDeps;
      } while (true);
   }


   public void installPackage(RepositoryPackage pkg, DependencyContext ctx) {
      if (!pkg.installed) {
         // We do the install and update immediately after they are added so that the layer definition file has
         // access to the installed state, to for example, list the contents of the lib directory to get the jar files
         // to add to the classpath.
         String err = pkg.install(ctx);
         if (err != null) {
            MessageHandler.error(msg, "Failed to install repository package: " + pkg.packageName + " error: " + err);
         }
         else if (updateSystem) {
            pkg.update();
         }
      }
   }
}
