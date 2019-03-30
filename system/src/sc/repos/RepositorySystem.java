/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.util.IClassResolver;
import sc.util.IMessageHandler;
import sc.repos.mvn.MvnRepositoryManager;
import sc.util.MessageHandler;
import sc.util.StringUtil;

import java.util.ArrayList;
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
   public boolean debug;

   public HashSet<String> classPath = new HashSet<String>();

   IClassResolver resolver;

   public IMessageHandler msg;

   public String pkgIndexRoot;

   public RepositorySystem(RepositoryStore store, IMessageHandler handler, IClassResolver resolver, boolean info, boolean reinstall, boolean update, boolean installExisting) {
      this.store = store;

      msg = handler;
      this.resolver = resolver;
      reinstallSystem = reinstall;
      updateSystem = update;
      this.installExisting = installExisting;
      if (info)
         debug = true;

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

   public IRepositoryManager getManagerFromURL(String url) {
      int ix = url.indexOf(":");
      if (ix <= 0) {
         MessageHandler.error(msg, "addRepoitoryPackage - invalid URL - missing type://values");
         return null;
      }
      String repositoryTypeName = url.substring(0, ix);
      IRepositoryManager mgr = getRepositoryManager(repositoryTypeName);
      if (mgr == null) {
         MessageHandler.error(msg, "No repository with name: " + repositoryTypeName + " for add package");
         return null;
      }
      return mgr;
   }

   public RepositoryPackage addPackage(String url, boolean install) {
      return addPackage(url, install, null);
   }

   public RepositoryPackage addPackage(String url, boolean install, DependencyContext ctx) {
      IRepositoryManager mgr = getManagerFromURL(url);

      // TODO: use getOrCreatePackage here?
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
         if (ctx != null)
            ctx.fromPkg = pkg;
         installPackage(pkg, ctx);
      }
      return pkg;
   }

   public RepositoryPackage getRepositoryPackage(String pkgName) {
      RepositoryPackage res = store.packages.get(pkgName);
      return res;
   }

   public RepositoryPackage addRepositoryPackage(RepositoryPackage pkg) {
      RepositoryPackage existingPkg = store.packages.get(pkg.packageName);
      // If we are replacing a package defined in a layer that has been removed, might as well replace it.
      if (existingPkg != null && existingPkg.definedInLayer != null && (existingPkg.definedInLayer.excluded || existingPkg.definedInLayer.layeredSystem == null))
         existingPkg = null;
      if (existingPkg != null) {
         pkg.installedRoot = existingPkg.installedRoot;
         if (existingPkg.currentSource != null)
            pkg.setCurrentSource(existingPkg.currentSource);
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

   public RepositoryPackage registerAlternateName(RepositoryPackage pkg, String altName) {
      if (!StringUtil.equalStrings(altName, pkg.packageAlias) && pkg.packageAlias != null)
         System.err.println("*** Warning - replacing existing package alias: " + pkg.packageAlias + altName);
      pkg.packageAlias = altName;
      if (altName != null) {
         RepositoryPackage oldPkg = store.packages.get(altName);
         if (oldPkg == null) {
            store.packages.put(altName, pkg);
         }
         else
            pkg = oldPkg;
      }
      return pkg;
   }

   public RepositoryPackage addPackageSource(IRepositoryManager mgr, String pkgName, String fileName, RepositorySource repoSrc, boolean install, RepositoryPackage parentPkg) {
      RepositoryPackage pkg;

      pkg = store.packages.get(pkgName);
      if (pkg == null) {
         pkg = mgr.createPackage(mgr, pkgName, fileName, repoSrc, parentPkg);
         pkg.setCurrentSource(repoSrc);
         store.packages.put(pkgName, pkg);
      }
      else {
         repoSrc = pkg.addNewSource(repoSrc);
         // If there's a dependency on a module that's also a sub-module it will have created
         // the package without the parent and set the installRoot as though it's an independent module, not a child
         if (parentPkg != null && pkg.parentPkg == null) {
            pkg.setParentPkg(parentPkg);
            pkg.updateInstallRoot(mgr);
         }
         // We may first encounter a package from a Maven module reference - where we are not installing the package.  That's a weaker reference
         // than if we are installing it so use this new reference.
         if (!pkg.installed && install) {
            pkg.setCurrentSource(repoSrc);
            pkg.fileNames = repoSrc.getClassPathFileNames();
         }
      }

      if (install) {
         installPackage(pkg, null);
      }
      return pkg;
   }

   public RepositoryPackage addPackage(IRepositoryManager mgr, RepositoryPackage newPkg, RepositoryPackage parentPkg, boolean install, DependencyContext ctx) {
      RepositoryPackage pkg;
      String pkgName = newPkg.packageName;
      pkg = store.packages.get(pkgName);
      if (pkg == null) {
         pkg = newPkg;
         pkg.setParentPkg(parentPkg);
         store.packages.put(pkgName, pkg);
      }
      else {
         if (parentPkg != null)
            System.err.println("*** Warning - child package that was already added");
         for (RepositorySource src : newPkg.sources)
            pkg.addNewSource(src);
         // We may first encounter a package from a Maven module reference - where we are not installing the package.  That's a weaker reference
         // than if we are installing it so use this new reference.
         if (!pkg.installed && install) {
            RepositorySource newSrc = newPkg.currentSource == null ? newPkg.sources[0] : newPkg.currentSource;
            pkg.updateCurrentSource(newSrc, true);
         }
         // We are adding a new package instance but the canonical package of that name has been added already.
         // We point this package at the canonical one so that we always refer to the right one.
         newPkg.replacedByPkg = pkg;
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
         String err = pkg.preInstall(ctx, depCol, true);
         if (err != null) {
            MessageHandler.error(msg, "Failed to install repository package: " + pkg.packageName + " error: " + err);
         }
         else if (updateSystem) {
            pkg.update();
         }
      }
   }

   public static String installDeps(DependencyCollection instDeps, ArrayList<RepositoryPackage> allDeps) {
      do {
         DependencyCollection nextDeps = new DependencyCollection();
         for (PackageDependency pkgDep : instDeps.neededDeps) {
            if (!pkgDep.pkg.installed)
               pkgDep.pkg.preInstall(pkgDep.ctx, nextDeps, true);
            else
               pkgDep.pkg.register();
            allDeps.add(pkgDep.pkg);
         }
         if (nextDeps.neededDeps.size() == 0)
            return null;
         instDeps = nextDeps;
      } while (true);
   }

   public static void completeInstallDeps(ArrayList<RepositoryPackage> allDeps) {
      for (RepositoryPackage pkg : allDeps) {
         pkg.mgr.completeInstall(pkg);
      }
   }

   public void installPackage(RepositoryPackage pkg, DependencyContext ctx) {
      if (!pkg.installed) {
         // We do the install and update immediately after they are added so that the layer definition file has
         // access to the installed state, to for example, list the contents of the lib directory to get the jar files
         // to add to the classpath.
         String err = pkg.install(ctx);
         if (err != null) {
            MessageHandler.error(msg, "Failed to install repository package: " + pkg.packageName + " error: " + err);
            return;
         }
      }
      else if (updateSystem) {
         pkg.update();
      }
      pkg.register();
   }

   public IClassResolver getClassResolver() {
      return resolver;
   }
}
