/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.repos.mvn.MvnRepositorySource;
import sc.util.FileUtil;
import sc.util.StringUtil;

import java.io.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;

/**
 * Represents a third party package that's managed by a RepositoryManager
 */
public class RepositoryPackage implements Serializable {
   /** A unique name of this package within the layered system */
   public String packageName;

   public long installedTime;

   public boolean installed = false;
   public boolean definesClasses = true;
   public boolean includeTests = false;
   public boolean includeRuntime = false;

   public RepositorySource[] sources;
   public RepositorySource currentSource;

   // Optional list of dependencies this package has on other packages
   public ArrayList<RepositoryPackage> dependencies;

   public String installedRoot;

   public String fileName = null;

   public RepositoryPackage(IRepositoryManager mgr, String pkgName, RepositorySource src) {
      this(mgr, pkgName, pkgName, src);
   }

   public RepositoryPackage(IRepositoryManager mgr, String pkgName, String fileName, RepositorySource src) {
      this.fileName = fileName;
      this.packageName = pkgName;
      this.sources = new RepositorySource[1];
      src.pkg = this;
      this.sources[0] = src;
      updateInstallRoot(mgr);
   }

   public RepositoryPackage(IRepositoryManager mgr, String pkgName, RepositorySource[] srcs) {
      this.fileName = this.packageName = pkgName;
      this.sources = srcs;
      for (RepositorySource src:srcs)
         src.pkg = this;
      updateInstallRoot(mgr);
   }

   public String install(DependencyContext ctx) {
      installed = false;
      StringBuilder errors = null;
      for (RepositorySource src:sources) {
         if (src.repository.isActive()) {
            // Mark as installed to prevent recursive installs
            installed = true;
            String err = src.repository.install(src, ctx);
            if (err == null) {
               updateCurrentSource(src);
               break;
            }
            else {
               installed = false;
               if (errors == null)
                  errors = new StringBuilder();
               errors.append(err);
            }
         }
      }
      if (!installed && errors == null) {
         errors = new StringBuilder();
         errors.append("No active repository manager to install package: " + packageName);
      }
      return errors == null ? null : errors.toString();
   }

   public String update() {
      if (!installed || currentSource == null)
         return "Package: " + packageName + " not installed - skipping update";

      return currentSource.repository.update(currentSource);
   }

   public void updateInstallRoot(IRepositoryManager mgr) {
      String resName;
      if (fileName == null)
         resName = mgr.getPackageRoot();
      else
         resName = FileUtil.concat(mgr.getPackageRoot(), packageName);
      installedRoot = resName;
   }

   public void addNewSource(RepositorySource repoSrc) {
      if (repoSrc.equals(currentSource)) {
         repoSrc.ctx = DependencyContext.merge(repoSrc.ctx, currentSource.ctx);
         return;
      }

      int i = 0;
      for (RepositorySource oldSrc:sources) {
         if (repoSrc.equals(oldSrc)) {
            DependencyContext newCtx = DependencyContext.merge(repoSrc.ctx, oldSrc.ctx);
            if (newCtx != oldSrc.ctx) {
               oldSrc.ctx = newCtx;
               for (int j = 0; j < i; j++) {
                  if (DependencyContext.hasPriority(oldSrc.ctx, sources[j].ctx)) {
                     RepositorySource tmp = sources[j];
                     sources[j] = oldSrc;
                     oldSrc = tmp;
                  }
               }
               sources[i] = oldSrc;
            }
            return;
         }
         i++;
      }

      RepositorySource[] newSrcs = new RepositorySource[sources.length + 1];

      int j = 0;
      boolean found = false;
      for (i = 0; i < sources.length; i++) {
         if (!found && DependencyContext.hasPriority(repoSrc.ctx, sources[i].ctx)) {
            newSrcs[j] = repoSrc;
            j++;
            found = true;
         }
         newSrcs[j] = sources[i];
         j++;
      }
      if (j < newSrcs.length)
         newSrcs[j] = repoSrc;
      repoSrc.pkg = this;
      sources = newSrcs;
   }

   public String getClassPath() {
      StringBuilder sb = new StringBuilder();
      if (installed) {
         LinkedHashSet<String> cp = new LinkedHashSet<String>();
         addToClassPath(cp);
         return StringUtil.arrayToPath(cp.toArray());
      }
      return null;
   }

   private void addToClassPath(HashSet<String> classPath) {
      String fileToUse = getClassPathFileName();
      if (fileToUse != null && definesClasses) {
         String entry = FileUtil.concat(getVersionRoot(), fileToUse);
         classPath.add(entry);
      }

      if (dependencies != null) {
         for (RepositoryPackage depPkg : dependencies) {
            depPkg.addToClassPath(classPath);
         }
      }
   }

   public void init(IRepositoryManager mgr) {
      if (sources != null) {
         for (RepositorySource src:sources)
            src.init(mgr.getRepositorySystem());
      }
      if (dependencies != null) {
         for (RepositoryPackage pkg:dependencies)
            pkg.init(mgr);
      }
      // Clearing this out since we need to potentially reinstall this package
      installed = false;
   }

   public static RepositoryPackage readFromFile(File f, IRepositoryManager mgr) {
      ObjectInputStream ios = null;
      FileInputStream fis = null;
      try {
         ios = new ObjectInputStream(fis = new FileInputStream(f));
         RepositoryPackage res = (RepositoryPackage) ios.readObject();
         res.init(mgr);
         return res;
      }
      catch (InvalidClassException exc) {
         System.err.println("RepositoryPackage saved info - version changed: " + exc);
         f.delete();
      }
      catch (IOException exc) {
         System.err.println("Failed to read RepositoryPackage info file: " + exc);
         f.delete();
      }
      catch (ClassNotFoundException exc) {
         System.err.println("Error reading RepositoryPackage info file: " + exc);
         f.delete();
      }
      finally {
         FileUtil.safeClose(fis);
         FileUtil.safeClose(ios);
      }
      return null;
   }

   // Check for any changes in the package - if so, we'll re-install.  Otherwise, we'll
   // update this package with the saved info.
   public boolean updateFromSaved(IRepositoryManager mgr, RepositoryPackage oldPkg, boolean install, DependencyContext ctx) {
      if (!packageName.equals(oldPkg.packageName))
         return false;
      if (!StringUtil.equalStrings(fileName, oldPkg.fileName))
         return false;

      if (sources.length > oldPkg.sources.length)
         return false;

      // Just check that the original sources match.  sources right now only has those added in the layer def files - not those that will be added
      // via dependencies if we install.
      for (int i = 0; i < sources.length; i++)
         if (!sources[i].equals(oldPkg.sources[i]))
            return false;

      // These fields are computed during the install so we update them here when we skip the install
      ArrayList<RepositoryPackage> oldDeps = oldPkg.dependencies;
      if (oldDeps != null) {
         RepositorySystem sys = mgr.getRepositorySystem();
         for (int i = 0; i < oldDeps.size(); i++) {
            RepositoryPackage oldDep = oldDeps.get(i);
            RepositoryPackage canonDep = sys.addPackage(mgr, oldDep, install, ctx);
            if (canonDep != oldDep)
               oldDeps.set(i, canonDep);
         }
      }
      dependencies = oldDeps;

      updateCurrentSource(oldPkg.currentSource);
      definesClasses = oldPkg.definesClasses;

      return true;
   }

   public void saveToFile(File tagFile) {
      ObjectOutputStream os = null;
      FileOutputStream fos = null;
      try {
         os = new ObjectOutputStream(fos = new FileOutputStream(tagFile));
         os.writeObject(this);
      }
      catch (IOException exc) {
         System.err.println("Unable to write RepositoryPackage info file: " + tagFile + ": " + exc);
      }
      finally {
         FileUtil.safeClose(os);
         FileUtil.safeClose(fos);
      }
   }

   public String toString() {
      return packageName;
   }

   public String getVersionRoot() {
      return installedRoot;
   }

   /**
    * There's a file name that's set as part of the package but sometimes this name will
    * change based on the source.  If we have two different versions of the same component.
    *
    * If this method returns null, this package has no class path entry.
    */
   public String getClassPathFileName() {
      return currentSource != null ? currentSource.getClassPathFileName() : fileName;
   }

   public void updateCurrentSource(RepositorySource src) {
      currentSource = src;
      if (src != null)
         fileName = src.getClassPathFileName();
   }
}
