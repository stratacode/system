/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.layer.Layer;
import sc.layer.LayerComponent;
import sc.repos.mvn.MvnRepositorySource;
import sc.util.FileUtil;
import sc.util.MessageHandler;
import sc.util.StringUtil;

import java.io.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.TreeSet;

/**
 * Represents a third party package that's managed by a RepositoryManager
 */
public class RepositoryPackage extends LayerComponent implements Serializable {
   private static final long serialVersionUID = 7334042890983906329L;

   /** A unique name of this package within the layered system */
   public String packageName;

   public long installedTime;

   public boolean installed = false;
   public boolean definesClasses = true;
   private boolean includeTests = false;
   private boolean includeRuntime = false;

   public RepositorySource[] sources;
   public RepositorySource currentSource;

   // Optional list of dependencies this package has on other packages
   public ArrayList<RepositoryPackage> dependencies;

   public String installedRoot;

   public String fileName = null;

   public String url;
   public String type;

   public boolean unzip;

   public String[] srcPaths;
   public String[] webPaths;
   public String[] configPaths;
   public String[] testPaths;

   transient public IRepositoryManager mgr;
   transient String computedClassPath;
   transient RepositoryPackage replacedByPkg;

   public RepositoryPackage(Layer layer) {
      super(layer);
   }

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

   public void init() {
      super.init();
      if (definedInLayer != null) {
         RepositorySystem sys = definedInLayer.layeredSystem.repositorySystem;
         RepositoryPackage pkg = this;
         if (type != null) {
            mgr = sys.getRepositoryManager(type);

            if (url != null) {
               RepositorySource src = mgr.createRepositorySource(url, unzip);
               if (packageName == null)
                  packageName = src.getDefaultPackageName();
               if (fileName == null)
                  fileName = src.getDefaultFileName();
               // TODO: a null fileName also means to install in the packageRoot.  Do we need to support this case?
               // maybe that should be a separate flag
               if (fileName == null)
                  fileName = packageName;
               src.pkg = this;
               updateCurrentSource(src);
               updateInstallRoot(mgr);
               // Note: if a package with this name has already been added, we use that instance
               pkg = sys.addRepositoryPackage(this);
               if (pkg != this) {
                  replacedByPkg = pkg;
               }
            }
            if (definedInLayer.repositoryPackages == null)
               definedInLayer.repositoryPackages = new ArrayList<RepositoryPackage>();
            // We need to add 'this' not the canonical pkg here so the definedInLayer is set for this layer.  We'll dereference replacedByPkg
            // as needed to get the installed info from the package.
            definedInLayer.repositoryPackages.add(this);
            // TODO: are there any properties in this pkg which we need to copy over to the existing instance if pkg != this
         }
         else {
            MessageHandler.error(sys.msg, "Package ", packageName, " missing type property - should be 'git', 'mvn', 'ur', 'scp' etc.");
         }
      }
      else {
         if (url != null || type != null) {
            System.err.println("*** Warning - url and type properties set on RepositoryPackage which is not part of a layer");
         }
      }
   }

   public String install(DependencyContext ctx) {
      if (replacedByPkg != null)
         return replacedByPkg.install(ctx);

      DependencyCollection depCol = new DependencyCollection();
      String err = preInstall(ctx, depCol);
      if (err != null)
         return err;

      String depsRes = RepositorySystem.installDeps(depCol);
      return depsRes;
   }

   public void register() {
      if (definedInLayer != null && !definedInLayer.disabled && !definedInLayer.excluded) {
         String root = replacedByPkg != null ? replacedByPkg.installedRoot : installedRoot;
         if (root != null) {
            if (srcPaths != null) {
               for (String srcPath : srcPaths)
                  definedInLayer.addSrcPath(FileUtil.concat(root, srcPath), null);
            }
            if (webPaths != null) {
               for (String webPath:webPaths)
                  definedInLayer.addSrcPath(FileUtil.concat(root, webPath), "web");
            }
            if (configPaths != null) {
               for (String configPath:configPaths)
                  definedInLayer.addSrcPath(FileUtil.concat(root, configPath), "config");
            }
         }
      }
   }

   public String preInstall(DependencyContext ctx, DependencyCollection depCol) {
      installed = false;
      StringBuilder errors = null;
      if ((sources == null || sources.length == 0) && currentSource != null) {
         sources = new RepositorySource[1];
         sources[0] = currentSource;
      }
      if (sources != null) {
         for (RepositorySource src : sources) {
            if (src.repository.isActive()) {
               // Mark as installed to prevent recursive installs
               installed = true;
               String err = src.repository.preInstall(src, ctx, depCol);
               if (err == null) {
                  updateCurrentSource(src);
                  break;
               } else {
                  installed = false;
                  if (errors == null)
                     errors = new StringBuilder();
                  errors.append(err);
               }
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
      if (replacedByPkg != null)
         return replacedByPkg.update();
      if (!installed || currentSource == null)
         return "Package: " + packageName + " not installed - skipping update";

      return currentSource.repository.update(currentSource);
   }

   public void updateInstallRoot(IRepositoryManager mgr) {
      this.mgr = mgr;
      String resName;
      if (fileName == null)
         resName = mgr.getPackageRoot();
      else
         resName = FileUtil.concat(mgr.getPackageRoot(), packageName);
      installedRoot = resName;
   }

   public void addNewSource(RepositorySource repoSrc) {
      if (repoSrc.equals(currentSource)) {
         // Need to reinit the dependencies if the exclusions change
         if (currentSource.mergeExclusions(repoSrc))
            dependencies = null;
         repoSrc.ctx = DependencyContext.merge(repoSrc.ctx, currentSource.ctx);
         return;
      }

      if (sources == null)
         sources = new RepositorySource[0];
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
      if (replacedByPkg != null)
         return replacedByPkg.getClassPath();
      // Because addToClassPath stores away an entry in global class path, we can't call that each time.
      // instead, cache the first time so we get the accurate list of classpath entries this package adds.
      // This is a transient field so we compute it once each time so global class path gets updated too.
      if (computedClassPath != null)
         return computedClassPath;
      StringBuilder sb = new StringBuilder();
      if (installed) {
         LinkedHashSet<String> cp = new LinkedHashSet<String>();
         addToClassPath(cp);
         computedClassPath = StringUtil.arrayToPath(cp.toArray());
         return computedClassPath;
      }
      return null;
   }

   private void addToClassPath(HashSet<String> classPath) {
      String fileToUse = getClassPathFileName();
      if (fileToUse != null && definesClasses) {
         String entry = FileUtil.concat(getVersionRoot(), fileToUse);

         if (mgr != null) {
            HashSet<String> globalClassPath = mgr.getRepositorySystem().classPath;
            boolean newGlobal = false;
            if (!globalClassPath.contains(entry)) {
               globalClassPath.add(entry);
               classPath.add(entry);
               newGlobal = true;
            }
            if (definedInLayer != null) {
               if (!definedInLayer.hasClassPathEntry(entry)) {
                  definedInLayer.addClassPathEntry(entry);
                  // It's rare but possible that we've defined this class path entry but at a layer higher in the stack
                  // that does not depend on this layer.  This layer will need to duplicate the classpath entry as we are not
                  // always going to search it when searching for types from this layer.
                  if (!newGlobal)
                     classPath.add(entry);
               }
            }
         }
         else
            System.err.println("*** No manager for addToClassPath");
      }

      if (dependencies != null) {
         for (RepositoryPackage depPkg : dependencies) {
            depPkg.addToClassPath(classPath);
         }
      }
   }

   public void init(IRepositoryManager mgr) {
      this.mgr = mgr;
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

      if (oldPkg.sources == null)
         return false;

      if (sources == null)
         sources = new RepositorySource[0];

      if (sources.length > oldPkg.sources.length)
         return false;

      // These aspects of a package can change causing us to need to reconfigure a package
      if (includeTests != oldPkg.includeTests)
         return false;

      if (includeRuntime != oldPkg.includeRuntime)
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

   public boolean equals(Object other) {
      if (!(other instanceof RepositoryPackage))
         return false;
      return ((RepositoryPackage) other).packageName.equals(packageName);
   }

   public int hashCode() {
      return packageName.hashCode();
   }

   public void setIncludeTests(boolean newVal) {
       if (includeTests != newVal && newVal) {
          // Need to reinstall if we've already installed without the tests
          if (installed)
             installed = false;
       }
      includeTests = newVal;
   }

   public boolean getIncludeTests() {
      return includeTests;
   }

   public void setIncludeRuntime(boolean newVal) {
      if (includeRuntime != newVal && newVal) {
         if (installed)
            installed = false;
      }
      includeRuntime = newVal;
   }

   public boolean getIncludeRuntime() {
      return includeRuntime;
   }

   public void addSrcPath(String srcPath) {
      if (srcPaths == null)
         srcPaths = new String[] {srcPath};
      else {
         int len = srcPaths.length;
         String[] newSrcPaths = new String[len+1];
         System.arraycopy(srcPaths, 0, newSrcPaths, 0, len);
         newSrcPaths[len] = srcPath;
         srcPaths = newSrcPaths;
      }
   }
}
