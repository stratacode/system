/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.dyn.DynUtil;
import sc.layer.Layer;
import sc.layer.LayerComponent;
import sc.util.ClassResolverObjectInputStream;
import sc.util.FileUtil;
import sc.util.MessageHandler;
import sc.util.StringUtil;

import java.io.*;
import java.util.*;

/**
 * Represents a package or software component obtained from some source location - e.g. a git repository, maven, file system, etc.
 * Each package may come from one or more sources, where the source contains the specific version.  Ultimately a single currentSource
 * is selected and that's used to install or update the package.
 * Once installed, the package produces access to the assets such as the class path, src path, web path and config path entries added
 * by this package.
 * Packages can be installed in either source or binary form with different sources for the two types.
 */
public class RepositoryPackage extends LayerComponent implements Serializable {
   private static final long serialVersionUID = 7334042890983906329L;

   /** A unique name of this package within the layered system */
   public String packageName;

   public long installedTime;

   /** If an error occurred, the description of that error */
   public String installError;

   public boolean installed = false;
   /** Does this package define class files? */
   public boolean definesClasses = true;

   /** Does this package define src files? */
   public boolean definesSrc = false;

   /** Are we building it from src or download the compiled version? */
   public boolean buildFromSrc = false;

   private boolean includeTests = false;
   private boolean includeRuntime = false;

   /** List of possible sources for this package */
   public RepositorySource[] sources;
   public RepositorySource currentSource;

   /** Only set if this package is a module of another parent package */
   public String parentPkgURL;
   transient public RepositoryPackage parentPkg;
   /** If this is a package of packages, contains the sub-packages.  Otherwise null. */
   transient public ArrayList<RepositoryPackage> subPackages;
   public ArrayList<String> subPkgURLs;

   // Optional list of dependencies this package has on other packages
   transient public ArrayList<RepositoryPackage> dependencies;
   public ArrayList<String> depPkgURLs;

   public String installedRoot;

   public List<String> fileNames = new ArrayList<String>();

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
   public transient boolean packageInited = false;
   public transient String rebuildReason = null;
   /** Set to true when this package and it's dependencies have been successfully restored from the .ser file */
   public transient boolean preInstalled = false;
   /** The current source when this package was inited/installed for diagnostics */
   public transient ArrayList<RepositorySource> initedSources = new ArrayList<RepositorySource>();
   public transient RepositorySource installedSource = null;

   /** Lets you define a separate alias for this package */
   public String packageAlias;

   public RepositoryPackage() {
   }

   public RepositoryPackage(Layer layer) {
      super(layer);
   }

   public RepositoryPackage(IRepositoryManager mgr, String pkgName, RepositorySource src) {
      this(mgr, pkgName, pkgName, src, null);
   }

   public RepositoryPackage(IRepositoryManager mgr, String pkgName, String fileName, RepositorySource src, RepositoryPackage parentPkg) {
      addFileName(fileName);
      this.packageName = pkgName;
      this.sources = new RepositorySource[1];
      src.pkg = this;
      this.sources[0] = src;
      setParentPkg(parentPkg);
      updateInstallRoot(mgr);
   }

   public RepositoryPackage(IRepositoryManager mgr, String pkgName, RepositorySource[] srcs) {
      this.packageName = pkgName;
      addFileName(pkgName);
      this.sources = srcs;
      for (RepositorySource src:srcs)
         src.pkg = this;
      updateInstallRoot(mgr);
   }

   public void setParentPkg(RepositoryPackage pp) {
      parentPkg = pp;
      if (pp != null && pp.buildFromSrc) {
         buildFromSrc = true;
      }
   }

   public void addFileName(String fn) {
      fileNames.add(fn);
   }

   public void init() {
      if (_initState > 1)
         return;
      super.init();

      // Supports being inside of a layer and inside of another package.  There's no constructor for the package case
      // so just using the dyn api to find the outer object in the dyn context.  We could add an interface in common between
      // layer and LayerComponent and use the same constructor or add propagateConstructors to add a new one for this case.
      if (definedInLayer == null) {
         Object obj = DynUtil.getOuterObject(this);
         if (obj instanceof RepositoryPackage)
            parentPkg = (RepositoryPackage) obj;
      }

      RepositorySystem sys = getRepositorySystem();
      if (sys != null) {
         if (type != null)
            mgr = sys.getRepositoryManager(type);
         else {
            MessageHandler.error(sys.msg, "Package ", packageName, " missing type property - should be 'git', 'mvn', 'url', 'scp' etc.");
         }
         RepositoryPackage pkg = this;

         if (url != null) {
            RepositorySource src = mgr.createRepositorySource(url, unzip, parentPkg);
            if (packageName == null)
               packageName = src.getDefaultPackageName();
            String defaultFile = src.getDefaultFileName();
            if (fileNames.size() == 0 && defaultFile != null)
               addFileName(defaultFile);
            // TODO: a null fileName also means to install in the packageRoot.  Do we need to support this case?
            // maybe that should be a separate flag
            if (fileNames.size() == 0)
               addFileName(packageName);
            src.pkg = this;
            // Since we just set the file name above, do not reset them... for root level projects we do not install
            // a file.
            updateCurrentSource(src, false);
            updateInstallRoot(mgr);
            // Note: if a package with this name has already been added, we use that instance
            pkg = sys.addRepositoryPackage(this);
            if (pkg != this) {
               replacedByPkg = pkg;
            }
         }
         if (parentPkg != null) {
            parentPkg.addSubPackage(pkg);
         }
         if (definedInLayer != null) {
            if (definedInLayer.repositoryPackages == null)
               definedInLayer.repositoryPackages = new ArrayList<RepositoryPackage>();
            // We need to add 'this' not the canonical pkg here so the definedInLayer is set for this layer.  We'll dereference replacedByPkg
            // as needed to get the installed info from the package.
            definedInLayer.repositoryPackages.add(this);
            // TODO: are there any properties in this pkg which we need to copy over to the existing instance if pkg != this
         }
      }
      else {
         System.err.println("*** RepositoryPackage component defined outside of a RepositorySystem context");
      }
   }

   void addSubPackage(RepositoryPackage pkg) {
      if (subPackages == null)
         subPackages = new ArrayList<RepositoryPackage>();
      subPackages.add(pkg);
   }

   public Layer getDefinedInLayer() {
      if (definedInLayer != null)
         return definedInLayer;
      if (parentPkg != null)
         return parentPkg.getDefinedInLayer();
      return null;
   }

   public RepositorySystem getRepositorySystem() {
      Layer layer = getDefinedInLayer();
      if (layer != null)
         return layer.layeredSystem.repositorySystem;
      return null;
   }

   public String install(DependencyContext ctx) {
      if (replacedByPkg != null)
         return replacedByPkg.install(ctx);

      RepositorySystem sys = mgr.getRepositorySystem();
      RepositoryPackage oldPkg = sys.getRepositoryPackage(packageName);
      if (oldPkg != this)
         System.err.println("*** Warning - installing package not registered");

      ArrayList<RepositoryPackage> allDeps = new ArrayList<RepositoryPackage>();
      DependencyCollection depCol = new DependencyCollection();
      String err = preInstall(ctx, depCol);
      if (err == null) {
         installSubPackages(mgr, ctx);
         err = RepositorySystem.installDeps(depCol, allDeps);
      }
      mgr.completeInstall(this);
      RepositorySystem.completeInstallDeps(allDeps);
      return err;
   }

   public void register() {
      Layer defLayer = getDefinedInLayer();
      if (defLayer != null && !defLayer.disabled && !defLayer.excluded) {
         registerForLayer(defLayer);
      }
   }

   public void registerForLayer(Layer layer) {
      String root = getInstalledRoot();
      if (root != null) {
         // Propagate any srcPaths, webPaths, etc. properties down to the sub-packages
         if (srcPaths != null) {
            if (definesSrc) {
               for (String srcPath : srcPaths)
                  layer.addSrcPath(FileUtil.concat(root, srcPath), null);
            }
         }
         addSrcPathsForSubPackages(layer, srcPaths);
         if (webPaths != null) {
            for (String webPath:webPaths)
               layer.addSrcPath(FileUtil.concat(root, webPath), "web");
         }
         if (configPaths != null) {
            for (String configPath:configPaths)
               layer.addSrcPath(FileUtil.concat(root, configPath), "config");
         }
      }
   }

   private void addSrcPathsForSubPackages(Layer layer, String[] srcPaths) {
      if (subPackages != null) {
         for (RepositoryPackage subPackage:subPackages) {
            if (srcPaths != null) {
               for (String srcPath : srcPaths)
                  layer.addSrcPath(FileUtil.concat(subPackage.getInstalledRoot(), srcPath), null);
            }

            subPackage.addSrcPathsForSubPackages(layer, srcPaths);
         }
      }
   }

   public String preInstall(DependencyContext ctx, DependencyCollection depCol) {
      installed = false;
      StringBuilder errors = null;
      if ((sources == null || sources.length == 0) && currentSource != null) {
         sources = new RepositorySource[1];
         sources[0] = currentSource;
         updateCurrentFileNames(currentSource);
      }
      if (sources != null) {
         for (RepositorySource src : sources) {
            if (src.repository.isActive()) {
               // Mark as installed to prevent recursive installs
               installed = true;
               String err = src.repository.preInstall(src, ctx, depCol);
               if (err == null) {
                  // This version of the currentSource may have been restored on an incremental build and have info like the classifier which is not part of the URL so only replace it if
                  // we have a new and incompatible definition of the source.
                  // Warning - when we install and restore from a saved file the src we use in currentSource may match
                  // the one we tried to install but will have more info.  So we're careful to use the restored one
                  // in that case.
                  if (currentSource == null || !currentSource.equals(src))
                     updateCurrentSource(src, true);
                  else
                     updateCurrentFileNames(currentSource);

                  if (subPackages != null) {
                     for (RepositoryPackage subPkg:subPackages) {
                        if (!subPkg.installed)
                           subPkg.preInstall(ctx, depCol);
                     }
                  }
                  if (dependencies != null) {
                     for (RepositoryPackage depPkg:dependencies) {
                        if (!depPkg.installed)
                           depPkg.preInstall(ctx, depCol);
                     }
                  }
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
      // Nested packages are stored with their module name inside of the parent's installed root.
      if (parentPkg != null && buildFromSrc) {
         resName = FileUtil.concat(parentPkg.installedRoot, getModuleBaseName());
      }
      else {
         if (fileNames.size() == 0)
            resName = mgr.getPackageRoot();
         else
            resName = FileUtil.concat(mgr.getPackageRoot(), FileUtil.unnormalize(packageName));
      }
      installedRoot = resName;
   }

   public String getIndexFileName() {
      String resName = packageName;
      // Nested packages are stored with their module name inside of the parent's installed root.
      String versionSuffix = getVersionSuffix();
      if (versionSuffix != null)
         resName = resName + "-" + versionSuffix;
      return FileUtil.addExtension(resName.replace("/", "__"), "ser");
   }

   public String getModuleBaseName() {
      int ix = packageName.lastIndexOf("/");
      if (ix != -1)
         return packageName.substring(ix+1);
      return packageName;
   }

   public RepositorySource addNewSource(RepositorySource repoSrc) {
      if (currentSource != null && repoSrc.equals(currentSource)) {
         // Need to reinit the dependencies if the exclusions change and merge modulePath, parentPath and artifactId or any other
         // information that might be different from one source to the next.
         if (currentSource.mergeSource(repoSrc))
            dependencies = null;
         repoSrc.ctx = DependencyContext.merge(repoSrc.ctx, currentSource.ctx);
         return currentSource;
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
            return repoSrc;
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
      return repoSrc;
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
         computedClassPath = StringUtil.arrayToPath(cp.toArray(), false);
         return computedClassPath;
      }
      return null;
   }

   private void addToClassPath(HashSet<String> classPath) {
      List<String> filesToUse = getClassPathFileNames();
      if (filesToUse != null && definesClasses) {
         for (String fileToUse:filesToUse) {
            String entry = FileUtil.concat(getVersionRoot(), fileToUse);

            if (mgr != null) {
               HashSet<String> globalClassPath = mgr.getRepositorySystem().classPath;
               boolean newGlobal = false;
               if (!globalClassPath.contains(entry)) {
                  globalClassPath.add(entry);
                  classPath.add(entry);
                  newGlobal = true;
               }
               Layer defLayer = getDefinedInLayer();
               if (defLayer != null) {
                  if (!defLayer.hasClassPathEntry(entry)) {
                     defLayer.addClassPathEntry(entry);
                     // It's rare but possible that we've defined this class path entry but at a layer higher in the stack
                     // that does not depend on this layer.  This layer will need to duplicate the classpath entry as we are not
                     // always going to search it when searching for types from this layer.
                     if (!newGlobal)
                        classPath.add(entry);
                  }
               }
            } else
               System.err.println("*** No manager for addToClassPath");
         }
      }

      if (subPackages != null) {
         for (RepositoryPackage depPkg : subPackages) {
            depPkg.addToClassPath(classPath);
         }
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
      /*
      if (parentPkgURL != null) {
         parentPkg = mgr.getOrCreatePackage(parentPkgURL, null, false);
      }
      if (subPkgURLs != null) {
         ArrayList<RepositoryPackage> subs = new ArrayList<RepositoryPackage>(subPkgURLs.size());
         for (String sub:subPkgURLs) {
            subs.add(mgr.getOrCreatePackage(sub, this, false));
         }
         subPackages = subs;
      }
      // need to do the subPackages before the dependencies - otherwise, we might find one of them in a dependency
      // and not know it's a child of this package - then we try to install it from scratch.
      if (subPackages != null) {
         for (RepositoryPackage subPkg:subPackages) {
            subPkg.init(mgr);
         }
      }
      if (depPkgURLs != null) {
         ArrayList<RepositoryPackage> deps = new ArrayList<RepositoryPackage>(depPkgURLs.size());
         for (String depPkgURL:depPkgURLs) {
            deps.add(mgr.getOrCreatePackage(depPkgURL, null, false));
         }
         dependencies = deps;
      }
      if (dependencies != null) {
         for (RepositoryPackage pkg:dependencies)
            pkg.init(mgr);
      }
      */
      // Clearing this out since we need to potentially reinstall this package
      installed = false;
   }

   public static RepositoryPackage readFromFile(File f, IRepositoryManager mgr) {
      ObjectInputStream ios = null;
      FileInputStream fis = null;
      try {
         ios = new ClassResolverObjectInputStream(fis = new FileInputStream(f), mgr.getClassResolver());
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
      if (!samePackages(oldPkg))
         return false;

      // Note: We used to also compare fileNames but we can at least add to that list based on the contents of the POM
      // so we need to restore that value instead

      if (oldPkg.sources == null)
         return false;

      if (sources == null)
         sources = new RepositorySource[0];

      // We might have added a source but if the current sources are the same we do not have to reinstall
      if (sources.length > oldPkg.sources.length) {
         if (currentSource == null || oldPkg.currentSource == null || !currentSource.equals(oldPkg.currentSource))
            return false;
      }

      // If the packet went from false to true, we need to re-install to pick up the test dependencies.  If it goes from
      // true to false we'll accept that as already being installed.
      if (includeTests && !oldPkg.includeTests)
         return false;

      if (includeRuntime && !oldPkg.includeRuntime)
         return false;

      // Just check that the original sources match.  sources right now only has those added in the layer def files - not those that will be added
      // via dependencies if we install.
      if (currentSource == null || oldPkg.currentSource == null) {
         for (int i = 0; i < sources.length; i++)
            if (!sources[i].equals(oldPkg.sources[i]))
               return false;
      }

      fileNames = oldPkg.fileNames;
      definesClasses = oldPkg.definesClasses;
      definesSrc = oldPkg.definesSrc;
      buildFromSrc = oldPkg.buildFromSrc;

      RepositorySystem sys = mgr.getRepositorySystem();

      if (oldPkg.parentPkgURL != null) {
         if (parentPkg != null) {
            if (!parentPkg.sameURL(oldPkg.parentPkgURL)) {
               System.err.println("*** parentPkgURL changed - reinstalling");
               return false;
            }
         }
         else {
            IRepositoryManager parentMgr = sys.getManagerFromURL(oldPkg.parentPkgURL);
            if (parentMgr == null) {
               parentMgr = mgr;
            }
            setParentPkg(parentMgr.getOrCreatePackage(oldPkg.parentPkgURL, null, false));
         }
      }

      initSubPackages(oldPkg, install);
      if (oldPkg.depPkgURLs != null) {
         ArrayList<RepositoryPackage> deps = new ArrayList<RepositoryPackage>(oldPkg.depPkgURLs.size());
         for (String depPkgURL:oldPkg.depPkgURLs) {
            deps.add(mgr.getOrCreatePackage(depPkgURL, null, install));
         }
         dependencies = deps;
      }

      // Restore any package aliases we migh ahve saved so those are properly resolved in the dependency mechanism
      if (oldPkg.packageAlias != null)
         mgr.getRepositorySystem().registerAlternateName(this, oldPkg.packageAlias);

      // Move over the restored RepositorySource objects since they will have more info than
      // these source objects that were maybe created from the URL of some reference.  Need to
      // update the backpointer as well.
      sources = oldPkg.sources;
      if (sources != null) {
         for (RepositorySource nsrc:sources) {
            nsrc.updateAfterRestore(this);
         }
      }
      updateCurrentSource(oldPkg.currentSource, true);

      if (install) {
         installSubPackages(mgr, ctx);
      }

      return true;
   }

   private void initSubPackages(RepositoryPackage oldPkg, boolean install) {
      if (oldPkg.subPkgURLs != null) {
         ArrayList<RepositoryPackage> newSubs = new ArrayList<RepositoryPackage>(oldPkg.subPkgURLs.size());
         RepositorySystem repoSys = getRepositorySystem();
         for (String sub:oldPkg.subPkgURLs) {
            if (subPackages != null) {
               boolean found = false;
               for (RepositoryPackage curSubPkg : subPackages) {
                  if (curSubPkg.sameURL(sub)) {
                     found = true;
                     break;
                  }
               }
               if (found) {
                  if (install)
                     System.err.println("*** Not installing!"); // Not reached as we do not use this option anymore
                  continue;
               }
            }
            IRepositoryManager subMgr = repoSys.getManagerFromURL(sub);
            if (subMgr == null) {
               System.err.println("*** Failed to find manager for sub package URL: " + sub);
               continue;
            }
            RepositoryPackage subPkg = subMgr.getOrCreatePackage(sub, this, install);
            if (subPkg != null) {
               newSubs.add(subPkg);
               // This happens when we try to install the sub-pkg
               //subPkg.initSubPackages(oldPkg, install);
            }
            else {
               System.err.println("*** Failed to create subPackage: " + sub);
            }
         }
         if (subPackages == null)
            subPackages = newSubs;
         // TODO: if the subPackageURLs have changed should we just reinstall?
         else
            subPackages.addAll(newSubs);
      }
   }

   private void installSubPackages(IRepositoryManager mgr, DependencyContext ctx) {
      RepositorySystem rsys = mgr.getRepositorySystem();
      if (subPackages != null) {
         for (RepositoryPackage subPkg:subPackages) {
            rsys.installPackage(subPkg, ctx);
            subPkg.installSubPackages(mgr, ctx);
         }
      }
   }

   private void preSave() {
      if (dependencies != null) {
         ArrayList<String> depPkgs = new ArrayList<String>(dependencies.size());
         for (int i = 0; i < dependencies.size(); i++) {
            String depURL = dependencies.get(i).getPackageURL();
            depPkgs.add(depURL);
         }
         depPkgURLs = depPkgs;
      }
      if (parentPkg != null)
         parentPkgURL = parentPkg.getPackageURL();
      preSaveSubPackages();
   }

   private void preSaveSubPackages() {
      if (subPackages != null) {
         ArrayList<String> subPkgs = new ArrayList<String>(subPackages.size());
         for (int i = 0; i < subPackages.size(); i++) {
            RepositoryPackage subPkg = subPackages.get(i);
            subPkgs.add(subPkg.getPackageURL());
            // Each package will have it's preSave called before saving the package file so no need to recurse
            //subPkg.preSaveSubPackages();
         }
         subPkgURLs = subPkgs;
      }
   }

   public void saveToFile(File tagFile) {
      ObjectOutputStream os = null;
      FileOutputStream fos = null;
      try {
         preSave();
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

   public String getVersionSuffix() {
      return null;
   }

   public String getVersionRoot() {
      return getInstalledRoot();
   }

   public String getInstalledRoot() {
      if (replacedByPkg != null)
         return replacedByPkg.getInstalledRoot();
      return installedRoot;
   }

   /**
    * There's a file name that's set as part of the package but sometimes this name will
    * change based on the source.  If we have two different versions of the same component.
    *
    * If this method returns null, this package has no class path entry.
    */
   public List<String> getClassPathFileNames() {
      return currentSource != null ? currentSource.getClassPathFileNames() : fileNames;
   }

   public void updateCurrentSource(RepositorySource src, boolean resetFileNames) {
      src.pkg = this;
      setCurrentSource(src);
      if (resetFileNames)
         updateCurrentFileNames(src);
   }

   public void updateCurrentFileNames(RepositorySource src) {
      if (src != null) {
         List<String> cpFileNames = src.getClassPathFileNames();
         for (String cpFileName:cpFileNames) {
            if (!fileNames.contains(cpFileName))
               addFileName(cpFileName);
         }
      }
   }

   public void setCurrentSource(RepositorySource src) {
      currentSource = src;
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

   public String getPackageURL() {
      if (replacedByPkg != null)
         return replacedByPkg.getPackageURL();
      if (currentSource == null)
         return packageName;
      return currentSource.url;
   }

   /** Should we skip backing up this project directory?  For maven, if there's only a pom file we do not want to redownload
    * that file each time and yet we never save the .ser file so we can't determine that it's pre-installed. */
   public boolean getReusePackageDirectory() {
      return false;
   }

   public boolean samePackages(RepositoryPackage other) {
      if (other.packageName.equals(packageName) || StringUtil.equalStrings(other.packageAlias, packageName) ||
          StringUtil.equalStrings(packageAlias, other.packageName) || StringUtil.equalStrings(packageAlias, other.packageAlias)) {
         return true;
      }
      return false;
   }

   public boolean sameURL(String url) {
      return getPackageURL().equals(url);
   }
}
