/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.util.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public abstract class AbstractRepositoryManager implements IRepositoryManager {
   public String managerName;
   public String packageRoot;

   public RepositorySystem system;
   public IMessageHandler msg;
   public boolean info;

   public boolean active = true;

   /** Is this by default a src repository to build or one for runtime files like classes */
   public boolean srcRepository = false;

   public final static String REPLACED_DIR_NAME = ".replacedPackages";

   public boolean isActive() {
      return active;
   }

   public String getManagerName() {
      return managerName;
   }

   public String getPackageRoot() {
      return packageRoot;
   }

   public RepositorySystem getRepositorySystem() {
      return system;
   }

   public RepositorySource createRepositorySource(String url, boolean unzip, RepositoryPackage parent) {
      return new RepositorySource(this, url, unzip, parent);
   }

   public AbstractRepositoryManager(RepositorySystem sys, String mn, String reposRoot, IMessageHandler handler, boolean info) {
      this.system = sys;
      this.managerName = mn;
      packageRoot = reposRoot;

      this.msg = handler;
      this.info = info;
   }


   public String install(RepositorySource src, DependencyContext ctx) {
      DependencyCollection depColl = new DependencyCollection();
      ArrayList<RepositoryPackage> allDeps = new ArrayList<RepositoryPackage>();
      String err = preInstall(src, ctx, depColl);
      if (err == null) {
         err = RepositorySystem.installDeps(depColl, allDeps);
      }
      completeInstall(src.pkg);
      RepositorySystem.completeInstallDeps(allDeps);
      return err;
   }

   // Putting this into the version-specific installed root so it more reliably gets removed if the folder itself is removed
   // and so that for versioned packaged, we can store more than one version in the repository at the same time.
   private File getTagFile(RepositoryPackage pkg) {
      String rootPath = getRepositorySystem().pkgIndexRoot;
      // When we are initializing the layers, before we've set up a build layer, we may encounter some packages in
      // the layer's start method.  For now, storing them in the shared package directory.
      if (rootPath == null) {
         return new File(pkg.getVersionRoot(), pkg.getIndexFileName());
      }
      File tagFile = new File(rootPath, pkg.getIndexFileName());
      return tagFile;
   }

   public String getDepsInfo(DependencyContext ctx) {
      return (ctx == null || !info ? "" : " from: " + ctx.toString());
   }

   protected void preInstallPackage(RepositoryPackage pkg, DependencyContext ctx) {
      RepositorySource src = pkg.currentSource;
      if (src == null) {
         error("No source for package init: " + pkg.packageName);
         return;
      }

      if (pkg.initedSources == null)
         pkg.initedSources = new ArrayList<RepositorySource>();
      else if (pkg.initedSources.contains(src))
         return;

      boolean preInstalled = pkg.initedSources.size() == 0 ? true : pkg.preInstalled;
      pkg.initedSources.add(src);

      File tagFile = getTagFile(pkg);
      // Though it would be nice to only have one version of each component, maven makes that awkward.  We at least need
      // to download the pom.xml files from different versions - load x, then x -> parent, then parent -> modules[i] -> y <version>
      // We can in many cases disable downloading the pom file modules of parents (and in many cases they don't resolve)
      // but in some cases I believe the parent module
      File rootFile = new File(pkg.getVersionRoot());

      // TODO: right now, we are only installing directories but would we ever install files as well?
      boolean rootDirExists = rootFile.canRead() || rootFile.isDirectory();
      String rootParent = FileUtil.getParentPath(pkg.getVersionRoot());
      if (rootParent != null)
         new File(rootParent).mkdirs();
      long installedTime = -1;
      if (rootDirExists && tagFile != null && tagFile.canRead()) {
         if (pkg.definedInLayer != null)
            pkg.definedInLayer.layeredSystem.layerResolveContext = true;
         RepositoryPackage oldPkg = RepositoryPackage.readFromFile(tagFile, this);
         if (pkg.definedInLayer != null)
            pkg.definedInLayer.layeredSystem.layerResolveContext = false;

         /* TODO: is it possible to restore a package which was not installed and then think it is installed?
         if (oldPkg != null && !oldPkg.installed) {
            // If we were not installed when we were saved don't make us installed now
            preInstalled = false;
         }
         */

         if (oldPkg == null)
            pkg.rebuildReason = "failed to read scPkgCachedInfo.ser file";
         else if (system.reinstallSystem)
            pkg.rebuildReason = "reinstalling system - clean install";
         else if (system.installExisting)
            pkg.rebuildReason = "reinitializing from previous install";
         else if (!pkg.updateFromSaved(this, oldPkg, false, ctx))
            pkg.rebuildReason = "package description changed";
         if (pkg.rebuildReason == null) {
            installedTime = oldPkg.installedTime;
         }
      }
      else
         pkg.rebuildReason = "No cached package info for: " + system.pkgIndexRoot;
      long packageTime = getLastModifiedTime(src);

      // No last modified time for this source... assume it's up to date unless it's not installed
      if (packageTime == -1) {
         if (installedTime != -1) {
            if (!system.installExisting) {
               if (info)
                  info("Package: " + pkg.packageName + " up-to-date: " + getDepsInfo(ctx));
               pkg.preInstalled = preInstalled;
            }
            else
               pkg.rebuildReason += ": found existing directory";
         }
      }
      else if (installedTime > packageTime) {
         if (!system.installExisting) {
            if (info)
               info("Package: " + pkg.packageName + "  up-to-date: " + getDepsInfo(ctx));
            pkg.preInstalled = preInstalled;
         }
         else if (info) {
            if (pkg.rebuildReason == null)
               pkg.rebuildReason = "";
            pkg.rebuildReason = ": forced reinstall from existing directory";
         }
      }
      else
         pkg.rebuildReason += ": files out of date";

      // If we are installing on top of an existing directory, rename the old directory in the backup folder.  Checking tagFile because it could be version specific and don't want to back up one version to install another one
      if (!pkg.preInstalled && rootDirExists && !isEmptyDir(rootFile) && system.reinstallSystem && pkg.parentPkg == null && !mismatchedCase(rootFile) && !pkg.getReusePackageDirectory()) {
         Date curTime = new Date();
         String backupDir = FileUtil.concat(packageRoot, REPLACED_DIR_NAME, pkg.packageName + "." + curTime.getHours() + "." + curTime.getMinutes());
         new File(backupDir).mkdirs();
         if (info)
            info("Backing up package: " + pkg.packageName + " into: " + backupDir);
         FileUtil.renameFile(pkg.getVersionRoot(), backupDir);
         rootFile.mkdirs();
      }
   }

   /**
    * This works around a weird case - when you have to try and install a module from a parent which does not exist but has the same name (ignoring case) as
    * an existing package.  We just need to not back up the package... then downloading the POM will fail and we'll stop trying to init the package (e.g jooq and jOOq).
    * We could really use a better way to identify these inaccessible modules included from the parent so they do not cause problems.
    */
   private boolean mismatchedCase(File f) {
      try {
         String canonicalPath = f.getCanonicalPath();
         if (!canonicalPath.equals(f.getPath())) {
            if (canonicalPath.equalsIgnoreCase(f.getPath()))
               return true;
            else
               System.out.println("*** Unhandled case with mismatching file names: " + canonicalPath + " != " + f.getPath());
         }
      }
      catch (IOException exc) {
         System.err.println("*** Unable to get canonical file for: " + f + ": " + exc);
      }
      return false;
   }

   public String preInstall(RepositorySource src, DependencyContext ctx, DependencyCollection deps) {
      RepositoryPackage pkg = src.pkg;

      // Must set this so getVersionRoot returns the right value
      if (pkg.currentSource == null)
         pkg.setCurrentSource(src);

      preInstallPackage(pkg, ctx);

      pkg.installedSource = pkg.currentSource;

      if (pkg.preInstalled) {
         // Do these first since we need to define the parent/child hierarchy in the packages to know whether everything
         // lives when the storage of the sub-package is nested inside the parent.
         if (pkg.subPackages != null) {
            for (RepositoryPackage subPkg:pkg.subPackages)
               deps.addDependency(subPkg, ctx);
         }
         if (pkg.dependencies != null) {
            for (RepositoryPackage depPkg:pkg.dependencies)
               deps.addDependency(depPkg, ctx);
         }
         return null;
      }

      if (info)
         info(StringUtil.indent(DependencyContext.val(ctx)) + "Installing package: " + pkg.packageName + (pkg.rebuildReason == null ? "" : ": " + pkg.rebuildReason) + " src url: " + src.toString() + getDepsInfo(ctx));
      pkg.installError = doInstall(src, ctx, deps);
      if (pkg.installError != null)
         return pkg.installError;

      return null;
   }

   /**
    * Called after the dependencies have been installed.  Can use pkg.installError to determine if the install was
    * successfull.
    */
   public void completeInstall(RepositoryPackage pkg) {
      File tagFile = getTagFile(pkg);
      if (pkg.installError != null) {
         if (tagFile != null)
            tagFile.delete();
         System.err.println("Installing package: " + pkg.packageName + " failed: " + pkg.installError);
      }
      else {
         pkg.installedTime = System.currentTimeMillis();
         if (tagFile != null) {
            // Make the version specific directory if necessary
            new File(tagFile.getParent()).mkdirs();
            pkg.saveToFile(tagFile);
         }
      }
      ArrayList<RepositoryPackage> subPackages = pkg.subPackages;
      if (subPackages != null) {
         for (RepositoryPackage subPkg:subPackages) {
            completeInstall(subPkg);
         }
      }

   }

   static boolean isEmptyDir(File f) {
      String[] contents = f.list();
      if (contents != null) {
         for (String fileName : contents)
            if (!fileName.startsWith("."))
               return false;
      }
      return true;
   }

   public abstract String doInstall(RepositorySource src, DependencyContext ctx, DependencyCollection deps);

   public void info(String infoMessage) {
      if (msg != null) {
         msg.reportMessage(infoMessage, null, -1, -1, MessageType.Info);
      }
      if (info)
         System.out.println(infoMessage);
   }

   public void error(String infoMessage) {
      if (msg != null) {
         msg.reportMessage(infoMessage, null, -1, -1, MessageType.Error);
      }
      System.err.println(infoMessage);
   }

   public long getLastModifiedTime(RepositorySource src) {
      return -1;
   }

   public String update(RepositorySource src) {
      // TODO - not yet implemented
      return null;
   }

   protected String argsToString(List<String> args) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < args.size(); i++) {
         if (i != 0)
            sb.append(" ");
         sb.append(args.get(i));
      }
      return sb.toString();
   }

   public void setMessageHandler(IMessageHandler handler) {
      this.msg = handler;
   }

   // TODO: can we support this URL scheme for other repositories?
   public RepositoryPackage getOrCreatePackage(String url, RepositoryPackage parent, boolean install) {
      MessageHandler.error(msg, "URL based packages not supported for repository type: " + getClass().getName());
      return null;
   }
   public RepositoryPackage createPackage(String url) {
      MessageHandler.error(msg, "URL based packages not supported for repository type: " + getClass().getName());
      return null;
   }

   public RepositoryPackage createPackage(IRepositoryManager mgr, String packageName, String fileName, RepositorySource src) {
      return new RepositoryPackage(mgr, packageName, fileName, src, null);
   }

   public RepositoryPackage createPackage(IRepositoryManager mgr, String packageName, String fileName, RepositorySource src, RepositoryPackage parentPkg) {
      return new RepositoryPackage(mgr, packageName, fileName, src, parentPkg);
   }

   public String toString() {
      return managerName;
   }

   public boolean isSrcRepository() {
      return srcRepository;
   }

   public IClassResolver getClassResolver() {
      return system.getClassResolver();
   }
}
