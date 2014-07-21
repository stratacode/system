/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.classfile.CFClass;
import sc.lang.ILanguageModel;
import sc.lang.SemanticNodeList;
import sc.lang.sc.SCModel;
import sc.lang.sc.PropertyAssignment;
import sc.lifecycle.ILifecycle;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.java.*;
import sc.obj.Constant;
import sc.obj.GlobalScopeDefinition;
import sc.obj.SyncMode;
import sc.parser.Language;
import sc.parser.ParseUtil;
import sc.repos.IRepositoryManager;
import sc.repos.RepositoryPackage;
import sc.repos.RepositorySource;
import sc.sync.SyncManager;
import sc.sync.SyncOptions;
import sc.sync.SyncProperties;
import sc.type.CTypeUtil;
import sc.type.RTypeUtil;
import sc.util.FileUtil;
import sc.util.IdentityWrapper;
import sc.util.PerfMon;
import sc.util.StringUtil;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 
 * The main implementation class for a Layer.  There's one instance of this class for
 * a given layer in a given LayeredSystem.  The LayeredSystem stores the current list of layers
 * that are active in the system in it's layers property.   
 * <p>
 * You define a new layer with a layer definition file, placed in the layer's directory in your
 * system's layerpath.  
 * <p>
 * At startup time, the LayeredSystem creates a Layer instance for each layer definition file.  
 * It sorts the list of layers based on dependencies.  It initializes all of the layers by calling
 * tbe initialize method in the layer definition file.  It then starts all Layers by calling the 
 * start method.  By setting properties in your Layer, implementing the initialize and start methods
 * you have a variety of ways to alter the behavior of how your project is built and run.
 */
public class Layer implements ILifecycle, LayerConstants {
   /** Any set of dependent classes code in this layer requires */
   public String classPath;

   /** Optional: if not set, the layer's top level folder is used as the one src directory. */
   public String srcPath;

   /** Optional: specifies a set of directories or zip files which are already compiled but can be accessed as src models in the system (e.g. for converting to Javascript) */
   public String preCompiledSrcPath;

   /** Prepend this prefix globally to all package names auto-generated */
   @Constant
   public String packagePrefix = "";

   /** Imports added to all Java, v, vj classes */
   public List<ImportDeclaration> imports;

   /** Set using the public or private modifier set on the layer definition itself */
   @Constant
   public String defaultModifier;

   /** True if this layer just sets annotations and does not generate classes */
   public boolean annotationLayer;

   private Map<String,ImportDeclaration> importsByName;

   private Map<String,Object> staticImportTypes;

   private List<String> globalPackages;

   /** The directory to put .class in */
   public String buildDir;

   /** The directory to put generated src in - defaults to buildDir if buildSrcDir is not set */
   public String buildSrcDir;

   public String sysBuildSrcDir; // Copies the system buildSrcDir when this layer is built

   /** Used when compiled source should be in a sub-directory of the build dir.  Sets the default value of buildSrcDir to buildDir/buildSrcSubDir.  This value is inherited form one layer to the next */
   public String buildSrcSubDir;

   /** Used when compiled classes should be in a sub-directory of the build dir (e.g. WEB-INF/classes).  Sets the default value of buildDir to buildDir/buildSubDir.  This value is inherited form one layer to the next */
   public String buildClassesSubDir;

   /** The layer where classes built from this layer should go.  It gets set to the buildSysClassesDir of the LayeredSystem and only adjusted as needed because types start modifying types in this layer. */
   public Layer origBuildLayer;

   /** The class directory where this layer's compiled files should go. */
   public String buildClassesDir;

   /** Is this a compiled or a dynamic layer */
   @Constant
   public boolean dynamic;

   /** Set this to true for any layers you do not want to show up in the UI */
   @Constant
   public boolean hidden = false;

   /** Set to true for layers that cannot be used in dynamic mode.  Default for them is to compile them. */
   @Constant
   public boolean compiledOnly = false;

   /** When true, any object instances of types in this layer are registered in a weak hash-table with their class as a key.  When you interactively add a property assignment to a layer that modifies these instances, we can apply those changes dynamically. */
   public boolean liveDynamicTypes = true;

   /** Controls the compilation process for implementing the liveDynamicTypes.  */
   public boolean compileLiveDynamicTypes = true;

   /** True when this layer has been successfully compiled - i.e. its buildDir is up-to-date */
   public boolean compiled = false;

   /** Set to true when a layer has been compiled and put into the classpath */
   public boolean compiledInClassPath = false;

   /** Set to true for layers who want to show all objects and properties visible in their extended layers */
   @Constant
   public boolean transparent = false;

   /**
    * Set this to true so that a given layer is compiled by itself - i.e. its build will not include source or
    * classes for any layers which it extends.  In that case, those layers will have to be built separately
    * before this layer can be run.
    */
   public boolean buildSeparate;

   /** Set to true for any layers which should be compiled individually.   Set to true when buildSeparate = true, this is the buildLayer (the last layer), or the layer was previous built */
   public boolean buildLayer = false;

   /** Set of patterns to ignore in any layer src or class directory, using Java's regex language */
   public List<String> excludedFiles = new ArrayList<String>(Arrays.asList(".git", ".*[\\(\\);@#$%\\^].*"));

   private List<Pattern> excludedPatterns; // Computed from the above

   /** Normalized paths relative to the layer directory that are excluded from processing */
   public List<String> excludedPaths = new ArrayList<String>(Arrays.asList(LayerConstants.DYN_BUILD_DIRECTORY, LayerConstants.BUILD_DIRECTORY, "out", "bin", "lib", "build-save"));

   /** Set of paths to which are included in the src cache but not processed */
   public List<String> skipStartPaths = new ArrayList<String>();

   /** Set of patterns to ignore in any layer src or class directory, using Java's regex language */
   public List<String> skipStartFiles = new ArrayList<String>(Arrays.asList(".*.sctd"));

   private List<Pattern> skipStartPatterns; // Computed from the above

   /** When this is true, the imports from this layer are used by the next layer if its inheritImports are true */
   public boolean exportImports = true;

   /** When this is true, we'll inherit the imports from any extended layer which has exportImports=true */
   public boolean inheritImports = true;

   /** Expose imports before us in the layer path even if we do not extend those layers */
   public boolean useGlobalImports = false;

   /** When this is true, the extended layer by default uses the package of the last extended layer in the list. */
   public boolean inheritPackage = true;

   /** Set this to false on framework layers which set a package that child layers should not inherit by default */
   public boolean exportPackage = true;

   // TODO: add inherit runtime - let you extend layers without picking up their runtime assignments
   /**
    * Controls whether or not a layer which extends this layer inherits the runtime dependencies of this layer.
    * For example, if this layer excludes JS will an extending layer also exclude JS?
    * Or if this layer is run on both JS and Java will extending layers run on both too?
    */
   public boolean exportRuntime = true;
   /**
    * Controls whether we inherit the runtime dependencies of our base classes.  By default, if you extend a layer that
    * only runs in Javascript or Java your layer will only run in Java or JS.  If you set this flag to false, it can
    * run on any runtime.
    */
   public boolean inheritRuntime = true;

   /** Set to true when this layer is removed from the system */
   public boolean removed = false;

   /** Set to false for layers which are not part of the running application */
   public boolean activated = true;

   public List<String> excludeRuntimes = null;
   public List<String> includeRuntimes = null;

   public boolean hasDefinedRuntime = false;
   public IRuntimeProcessor definedRuntime = null;

   /** Enable or disable the default sync mode for types which are defined in this layer. */
   public SyncMode defaultSyncMode = SyncMode.Automatic;

   /**
    * Set this to true to disallow modification to any types defined in this layer from upstream layers.  In other words, when a layer is final, it's the last layer to modify those types.
    * FinalLayer also makes it illegal to modify any types that a finalLayer type depends on so be careful in how you use it.   When you build a finalLayer separately, upstream layers can
    * load their class files directly, since they can't be changed by upstream layers.  This makes compiling makes must faster.
    */
   public boolean finalLayer = false;

   boolean processed = false;

   // For build layers, while the layer is being build this stores the build state - changed files, etc.
   LayeredSystem.BuildState buildState;

   /** Set to true when this layer has had all changed files detected.  If it's not changed, we will load it as source */
   public boolean changedModelsDetected = false;

   private ArrayList<String> modifiedLayerNames;

   /** Set to true for a given build layer which needs to build all files */
   public boolean buildAllFiles = false;

   /** Trace build src index operations to track generated file build process */
   public static boolean traceBuildSrcIndex = false;

   /** The list of third party packages to be installed or updated for this layer */
   public List<RepositoryPackage> repositoryPackages = null;

   /**
    * Add an explicitly dependency on layer.  This will ensure that if your layer and the otherLayerName are in the stack, that this layer will be compiled along with other layer.
    * One case you need this is if you override an annotation such as sc.html.URL so that it depends on classes generated by your layer.  Since that dependency is not reflected in the
    * code, there's no easy way to figure it out but the build system needs to be aware of this dependency.
    */
   public void addModifiedLayer(String otherLayerName) {
      if (modifiedLayerNames == null)
         modifiedLayerNames = new ArrayList();
      modifiedLayerNames.add(otherLayerName);
   }

   public void excludeRuntime(String name) {
      if (excludeRuntimes == null)
         excludeRuntimes = new ArrayList<String>();
      excludeRuntimes.add(name);
   }

   public void includeRuntime(String name) {
      if (includeRuntimes == null)
         includeRuntimes = new ArrayList<String>();
      includeRuntimes.add(name);
   }

   public void setLayerRuntime(IRuntimeProcessor proc) {
      definedRuntime = proc;
      hasDefinedRuntime = true;
   }

   public void addRuntime(IRuntimeProcessor proc) {
      setLayerRuntime(proc);
      LayeredSystem.addRuntime(proc);
   }

   /** Some layers do not extend a layer bound to a runtime platform and so can run in any layer. */
   public boolean getAllowedInAnyLayer() {
      if (hasDefinedRuntime)
         return false;

      if (excludeRuntimes != null && excludeRuntimes.size() > 0)
         return false;

      if (includeRuntimes != null && includeRuntimes.size() > 0)
         return false;

      if (baseLayers != null && inheritRuntime) {
         for (int i = 0; i < baseLayers.size(); i++) {
            Layer base = baseLayers.get(i);
            if (base.exportRuntime && !base.getAllowedInAnyLayer())
               return false;
         }
      }
      return true;
   }

   /** Use this method to decide whether to build a given assets.  The build layer accumulates all assets but when we do intermediate builds, we only include the layers which are directly in the extension graph. */
   public boolean buildsLayer(Layer layer) {
      return this == layeredSystem.buildLayer || this == layer || extendsLayer(layer);
   }

   public SrcEntry getInheritedSrcFileFromTypeName(String typeName, boolean srcOnly, boolean prependPackage, String subPath) {
      SrcEntry res = getSrcFileFromTypeName(typeName, srcOnly, prependPackage, subPath);
      if (res != null)
         return res;
      if (baseLayers != null) {
         for (Layer base:baseLayers) {
            res = base.getInheritedSrcFileFromTypeName(typeName, srcOnly, prependPackage, subPath);
            if (res != null)
               return res;
         }
      }
      return null;
   }

   public SrcEntry getSrcFileFromTypeName(String typeName, boolean srcOnly, boolean prependPackage, String subPath) {
      String packagePrefix = prependPackage ? this.packagePrefix : null;
      String relFilePath = subPath;
      if (packagePrefix != null /*&& packagePrefix.length() < relFilePath.length()*/) {
         if (!typeName.startsWith(packagePrefix))
            return null;

         // If this layer has a prefix
         if (packagePrefix.length() > 0) {

            // Too short to be a valid name in this namespace
            if (relFilePath.length() <= packagePrefix.length())
               return null;

            // Convert from absolute to relative names for this layer
            relFilePath = relFilePath.substring(packagePrefix.length()+1);
         }
      }

      SrcEntry srcEnt = findSrcEntry(relFilePath, prependPackage);
      if (srcEnt != null)
         return srcEnt;
      else if (!srcOnly) {
         relFilePath = subPath + ".class";
         File res = findClassFile(relFilePath, false);
         if (res != null)
            return new SrcEntry(this, res.getPath(), relFilePath, prependPackage);
      }
      return null;
   }

   public enum RuntimeEnabledState {
      Enabled, Disabled, NotSet
   }

   public RuntimeEnabledState isExplicitlyEnabledForRuntime(IRuntimeProcessor proc) {
      // If this layer explicitly defines a runtime, it's clearly enabled for that runtime
      if (hasDefinedRuntime && proc == definedRuntime)
         return RuntimeEnabledState.Enabled;

      // If it explicitly excludes the runtime, then it's excluded clearly.  If it excludes another runtime, it's also explicitly included for this runtime.
      String runtimeName = proc == null ? IRuntimeProcessor.DEFAULT_RUNTIME_NAME : proc.getRuntimeName();
      if (excludeRuntimes != null) {
         if (excludeRuntimes.contains(runtimeName))
            return RuntimeEnabledState.Disabled;
         else
            return RuntimeEnabledState.Enabled;
      }

      if (includeRuntimes != null) {
         if (includeRuntimes.contains(runtimeName))
            return RuntimeEnabledState.Enabled;
         else
            return RuntimeEnabledState.Disabled;
      }

      if (baseLayers != null && inheritRuntime) {
         RuntimeEnabledState baseState = RuntimeEnabledState.NotSet;
         for (int i = 0; i < baseLayers.size(); i++) {
            Layer base = baseLayers.get(i);
            if (!base.exportRuntime)
               continue;
            baseState = base.isExplicitlyEnabledForRuntime(proc);
            // If this layer extends any layer which is enabled, it is enabled.  So if you extend one layer that is disabled and
            // another that is enabled, you should be enabled.
            if (baseState == RuntimeEnabledState.Enabled)
               return baseState;
         }
         // TODO: if you do not extend an explicitly enabled layer should we disable you if you extend an explicitly disabled layer here?
         if (baseState != RuntimeEnabledState.NotSet)
            return baseState;
      }

      return RuntimeEnabledState.NotSet;
   }

   // TODO: "final" modifier on the layer should disallow modification of types.  Could give finer grained options like: enable/disable adding/removing fields, overriding methods or anything that would alter the Java .class
   // allowPropertyModification - enable/disable changing default property values for objects/types defined in this layer
   // exportPackage??

   /**
    * A name remapping applied to all classes in this layer - so you can substitute one class for another.
    * This remapping will also be applied to any derived layers
    *    import foo as bar
    * might be the syntax to define this.
   public Map classSubstitutionMap;
    */

   /** Contains the list of layers this layer extends */
   @Constant public List<String> baseLayerNames;

   public List<Layer> baseLayers;

   /** If a Java file uses no extensions, we can either compile it from the source dir or copy it to the build dir */
   public boolean copyPlainJavaFiles = true;

   /** Set by the system to the full path to the directory containing LayerName.sc (layerFileName = layerPathName + layerBaseName) */
   @Constant String layerPathName;

   /** Just the LayerName.sc part */
   @Constant public String layerBaseName;

   /** The name of the layer used to find it in the layer path dot separated, e.g. groupName.dirName */
   @Constant public String layerDirName;

   /** The unique name of the layer - prefix + dirName, e.g. sc.util.util */
   @Constant public String layerUniqueName;

   /** The integer position of the layer in the list of layers */
   @Constant
   int layerPosition;

   public LayeredSystem layeredSystem;

   // Cached list of top level src directories - usually just the layer's directory path
   private List<String> topLevelSrcDirs;

   // Cached list of all directories in this layer that contain source
   private List<String> srcDirs = new ArrayList<String>();

   private Map<String, TreeSet<String>> relSrcIndex = new TreeMap<String, TreeSet<String>>();

   // A cached index of the relative file names to File objects so we can quickly resolve whether a file is
   // there or not.  Also, this helps get the canonical case for the file name.
   HashMap<String,File> srcDirCache = new HashMap<String,File>();

   // TODO: right now this only stores ZipFiles in the preCompiledSrcPath.  We can't support zip entries for other than parsing models.
   // Stores a list of zip files which are in the srcDirCache for this layer.  Note that this may store src files which are not actually processed by this layer.
   ArrayList<ZipFile> srcDirZipFiles;

   public HashMap<String,SrcIndexEntry> buildSrcIndex;

   /** Stores the set of models in this layer, in the order in which we traversed the tree for consistency. */
   LinkedHashSet<IdentityWrapper<ILanguageModel>> layerModels = new LinkedHashSet<IdentityWrapper<ILanguageModel>>();

   List<String> classDirs;

   // Parallel to classDirs - caches zips/jars
   private ZipFile[] zipFiles;

   private boolean initialized = false;
   public boolean started = false;
   public boolean validated = false;

   /** Set to true if a base layer or start method failed to start */
   public boolean initFailed = false;

   private long lastModified = 0;

   public ILanguageModel model;

   public boolean needsIndexRefresh = false;   // If you generate files into the srcPath set this to true so they get picked up

   /** Set to true for layers that need to be saved the first time they are needed */
   public boolean tempLayer = false;

   /** Each build layer has a buildInfo which stores global project info for that layer */
   public BuildInfo buildInfo;

   /** Caches the typeNames of all dynamic types built in this build layer (if any) */
   public HashSet<String> dynTypeIndex = null;

   @Constant
   public CodeType codeType = CodeType.Application;

   @Constant
   public CodeFunction codeFunction = CodeFunction.Program;

   boolean newLayer = false;   // Set to true for layers which are created fresh

   private boolean buildSrcIndexNeedsSave = false; // TODO: performance - this gets saved way too often now right - need to implement this flag

   public boolean isInitialized() {
      return initialized;
   }
   
   public boolean isStarted() {
      return started;
   }

   /* Don't need this pass for layer init */
   public boolean isValidated() {
      return validated;
   }
   public void validate() {
      if (validated)
         return;

      validated = true;
      initImportCache();
   }

   public void process() {
   }
   
   public boolean isProcessed() {
      return isValidated();
   }

   public void initialize() {
      if (initialized)
         return;

      initialized = true;

      if (baseLayers != null && !activated) {
         for (Layer baseLayer:baseLayers)
            baseLayer.initialize();
      }

      callLayerMethod("initialize");

      if (liveDynamicTypes && compiledOnly)
         liveDynamicTypes = false;

      if (layeredSystem.layeredClassPaths && dynamic)
         buildLayer = true;

      // Create a map from class name to the full imported name
      if (imports != null) {
         importsByName = new HashMap<String,ImportDeclaration>();
         for (ImportDeclaration imp:imports) {
            if (!imp.staticImport) {
               String impStr = imp.identifier;
               String className = CTypeUtil.getClassName(impStr);
               if (className.equals("*")) {
                  String pkgName = CTypeUtil.getPackageName(impStr);
                  if (globalPackages == null)
                     globalPackages = new ArrayList<String>();
                  globalPackages.add(pkgName);
               }
               else {
                  importsByName.put(className, imp);
               }
            }
         }
      }
   }

   public String getDefaultBuildDir() {
      return LayerUtil.getLayerClassFileDirectory(this, layerPathName);
   }

   public void initBuildDir() {
      if (buildDir == null)
         buildDir = LayerUtil.getLayerClassFileDirectory(this, layerPathName);
      else // Translate configured relative paths to be relative to the layer's path on disk
         buildDir = FileUtil.getRelativeFile(layerPathName, buildDir);

      if (buildSrcDir == null)
         buildSrcDir = FileUtil.concat(buildDir, layeredSystem.getRuntimePrefix(), getBuildSrcSubDir());
      else
         buildSrcDir = FileUtil.getRelativeFile(layerPathName, buildSrcDir);

      // If there's an existing build dir in this layer, mark it as a build layer.  Do this as soon as possible so it shows up and we know we need to
      // build it.
      // TODO: remove this altogether?  We now explicitly mark intermediate build layers with buildLayer = true.  If a layer starts out as a final layer
      // so we make it a build layer, then it becomes an intermediate layer, we then only build the dependent layers for that build layer.  It will not
      // regenerate some files that are already there which might override previously built files.  Those get picked up and are stale... those intermediate
      // build dirs also slow things down so we are not going to try and not include them altogether.
      /*
      if ((!layeredSystem.options.buildAllLayers && new File(FileUtil.concat(buildSrcDir, layeredSystem.getBuildInfoFile())).canRead()) && !buildLayer) {
         buildLayer = true;
      }
      */
   }

   public String getBuildSrcSubDir() {
      if (buildSrcSubDir != null)
         return buildSrcSubDir;

      // Separate layers do not inherit this since their builds are self-contained
      if (buildSeparate)
         return null;

      // This gets inherited from any previous layers
      String res;
      for (int i = layerPosition - 1; i >= 0; i--)
         if ((res = layeredSystem.layers.get(i).buildSrcSubDir) != null)
            return res;
      return null;
   }

   public String getBuildClassesSubDir() {
      if (buildClassesSubDir != null)
         return buildClassesSubDir;

      // Separate layers do not inherit this since their builds are self-contained
      if (buildSeparate)
         return null;

      // This gets inherited from any previous layers
      String res;
      for (int i = layerPosition - 1; i >= 0; i--)
         if ((res = layeredSystem.layers.get(i).buildClassesSubDir) != null)
            return res;

      return null;
   }

   public String getBuildClassesDir() {
      String sd = getBuildClassesSubDir();
      return FileUtil.concat(buildDir, layeredSystem.getRuntimePrefix(), sd);
   }

   // TODO: deal with build classes subdir here?
   public void setBuildClassesDir(String oldBuildDir) {
      buildDir = oldBuildDir;
   }

   public long getLastModifiedTime() {
      if (model == null)
         return -1;

      if (lastModified == 0)
         lastModified = model.getSrcFiles().get(0).getLastModified();
      return lastModified;
   }

   void initSrcCache() {
      for (String dir: topLevelSrcDirs) {
         if (dir.indexOf("${buildDir}") == -1)
            dir = FileUtil.getRelativeFile(layerPathName, dir);
         else
            dir = dir.replace("${buildDir}", layeredSystem.buildDir);
         if (dir.indexOf("${") != -1)
            System.err.println("Unrecognized variable in srcPath: " + dir);
         File dirFile = new File(dir);
         addSrcFilesToCache(dirFile, "");
      }
      if (globalPackages != null) {
         for (String pkg:globalPackages) {
            Set<String> filesInPackage = layeredSystem.getFilesInPackage(pkg);
            if (filesInPackage != null) {
               for (String impName:filesInPackage) {
                  importsByName.put(impName, ImportDeclaration.create(CTypeUtil.prefixPath(pkg, impName)));
               }
            }
            else {
               displayError("No files in global import: " + pkg);
            }
         }
      }
      if (preCompiledSrcPath != null) {
         String [] srcList = preCompiledSrcPath.split(FileUtil.PATH_SEPARATOR);
         List<String> preCompiledSrcDirs = Arrays.asList(srcList);
         for (String preCompiledDir:preCompiledSrcDirs) {
            String ext = FileUtil.getExtension(preCompiledDir);
            if (ext != null && (ext.equals("jar") || ext.equals("zip"))) {
               try {
                  ZipFile zipFile = new ZipFile(preCompiledDir);
                  if (srcDirZipFiles == null)
                     srcDirZipFiles = new ArrayList<ZipFile>();
                  srcDirZipFiles.add(zipFile);
               }
               catch (IOException exc) {
                  System.err.println("*** Unable to open preCompiledSrcPath zip file: " + preCompiledDir + " exc:" + exc.toString());
               }


            }
            else {
               File f = new File(preCompiledDir);
               if (!f.isDirectory())
                  System.err.println("*** Unable to open preCompiledSrcPath directory: " + preCompiledDir);
               else
                  addSrcFilesToCache(f, "");
            }
         }
      }
   }

   public void displayError(String... args) {
      StringBuilder sb = new StringBuilder();
      sb.append("Error in layer: ");
      sb.append(layerPathName);
      sb.append(": ");
      for (String arg:args)
         sb.append(arg);
      System.err.println(sb.toString());
   }

   private void addSrcFilesToCache(File dir, String prefix) {
      String srcDirPath = dir.getPath();
      if (!srcDirs.contains(srcDirPath))
         srcDirs.add(srcDirPath);

      String[] files = dir.list();
      if (files == null)
         return;
      if (excludedPaths != null && prefix != null) {
         for (String path:excludedPaths) {
            if (path.equals(prefix))
               return;
         }
      }

      TreeSet<String> dirIndex = getDirIndex(prefix);

      String absPrefix = FileUtil.concat(packagePrefix.replace('.', '/'), prefix);
      for (String fn:files) {
         File f = new File(dir, fn);
         // Do not index the layer file itself.  otherwise, it shows up in the SC type system as a parent type in some weird cases
         if (prefix.equals("") && fn.equals(layerBaseName))
            continue;
         if (Language.isParseable(fn)) {
            String srcPath = FileUtil.concat(prefix, fn);
            // Register under both the name with and without the suffix
            srcDirCache.put(srcPath, f);
            srcDirCache.put(FileUtil.removeExtension(srcPath), f);
            String rootPath = dir.getPath();
            layeredSystem.addToPackageIndex(rootPath, this, false, true, absPrefix, fn);

            dirIndex.add(FileUtil.removeExtension(fn));
         }
         else if (!excludedFile(fn, prefix) && f.isDirectory()) {
            addSrcFilesToCache(f, FileUtil.concat(prefix, f.getName()));
         }
      }
   }

   private TreeSet<String> getDirIndex(String prefix) {
      if (prefix == null)
         prefix = "";
      TreeSet<String> dirIndex = relSrcIndex.get(prefix);
      if (dirIndex == null) {
         dirIndex = new TreeSet<String>();
         relSrcIndex.put(prefix, dirIndex);
      }
      return dirIndex;
   }
   
   public void addNewSrcFile(SrcEntry srcEnt) {
      String relFileName = srcEnt.relFileName;
      File f = new File(srcEnt.absFileName);

      String relDir = srcEnt.getRelDir();
      TreeSet<String> dirIndex = getDirIndex(relDir);
      srcDirCache.put(FileUtil.removeExtension(relFileName), f);
      srcDirCache.put(relFileName, f);
      String absPrefix = FileUtil.concat(packagePrefix.replace('.', '/'), relDir);
      layeredSystem.addToPackageIndex(layerPathName, this, false, true, absPrefix, srcEnt.baseFileName);
      dirIndex.add(FileUtil.removeExtension(srcEnt.baseFileName));
   }

   public void removeSrcFile(SrcEntry srcEnt) {
      String relFileName = srcEnt.relFileName;
      File f = new File(srcEnt.absFileName);
      srcDirCache.remove(FileUtil.removeExtension(relFileName));
      srcDirCache.remove(relFileName);
      String absPrefix = FileUtil.concat(packagePrefix.replace('.', '/'), srcEnt.getRelDir());
      layeredSystem.removeFromPackageIndex(layerPathName, this, false, true, absPrefix, srcEnt.baseFileName);
      // TODO: any reason to remove the dirIndex entry?
   }

   public void start() {
      if (started)
         return;
      started = true;

      if (baseLayers != null && !activated) {
         for (Layer baseLayer:baseLayers)
            baseLayer.start();
      }

      if (isBuildLayer()) {
         loadBuildInfo();
      }

      // First start the model so that it can set up our paths etc.
      if (model != null)
         ParseUtil.startComponent(model);

      callLayerMethod("start");

      if (classPath != null) {
         classDirs = Arrays.asList(classPath.split(FileUtil.PATH_SEPARATOR));
         zipFiles = new ZipFile[classDirs.size()];
         for (int i = 0; i < classDirs.size(); i++) {
            String classDir = classDirs.get(i);
            // Make classpath entries relative to the layer directory for easy encapsulation
            classDir = FileUtil.getRelativeFile(layerPathName, classDir);
            classDirs.set(i, classDir); // Store the translated path
            String ext = FileUtil.getExtension(classDir);
            if (ext != null && (ext.equals("jar") || ext.equals("zip"))) {
               try {
                  zipFiles[i] = new ZipFile(classDir);
               }
               catch (IOException exc) {
                  System.err.println("*** Can't open layer: " + layerPathName + "'s classPath entry as a zip file: " + classDir);
               }
            }
         }
      }
      if (srcPath == null) {
         topLevelSrcDirs = Collections.singletonList(LayerUtil.getLayerSrcDirectory(layerPathName));
      }
      else {
         String [] srcList = srcPath.split(FileUtil.PATH_SEPARATOR);
         topLevelSrcDirs = Arrays.asList(srcList);
      }
      if (excludedFiles != null) {
         excludedPatterns = new ArrayList<Pattern>(excludedFiles.size());
         for (int i = 0; i < excludedFiles.size(); i++)
            excludedPatterns.add(Pattern.compile(excludedFiles.get(i)));
      }
      if (buildDir != null && buildDir.startsWith(layerPathName))
         excludedPaths.add(buildDir.substring(layerPathName.length()+1));
      if (buildSrcDir != null && buildSrcDir != buildDir && buildSrcDir.startsWith(layerPathName))
         excludedPaths.add(buildSrcDir.substring(layerPathName.length()+1));

      if (skipStartFiles != null) {
         skipStartPatterns = new ArrayList<Pattern>(skipStartFiles.size());
         for (int i = 0; i < skipStartFiles.size(); i++)
            skipStartPatterns.add(Pattern.compile(skipStartFiles.get(i)));
      }
      if (buildDir != null && buildDir.startsWith(layerPathName))
         excludedPaths.add(buildDir.substring(layerPathName.length()+1));
      if (buildSrcDir != null && buildSrcDir != buildDir && buildSrcDir.startsWith(layerPathName))
         excludedPaths.add(buildSrcDir.substring(layerPathName.length()+1));

      // Now go through any class dirs and register them in the index
      if (classDirs != null) {
         for (int j = 0; j < classDirs.size(); j++)
            layeredSystem.addPathToIndex(this, classDirs.get(j));
      }

      // Now init our index of the files managed by this layer
      initSrcCache();

      if (isBuildLayer())
         makeBuildLayer();
   }

   public boolean isBuildLayer() {
      return buildSeparate || layeredSystem.buildLayer == this || buildLayer;
   }

   public void makeBuildLayer() {
      buildLayer = true;
      if (buildSrcIndex == null)
         buildSrcIndex = readBuildSrcIndex();
   }

   public boolean markBuildSrcFileInUse(File genFile, String genRelFileName) {
      SrcIndexEntry lastValid = null;
      for (int i = layerPosition; i < layeredSystem.layers.size(); i++) {
         Layer l = layeredSystem.layers.get(i);
         // We're only adding src index entries if there's an actual dependency.  Otherwise, this ends up putting extra entries
         // into the buildSrcIndex so that when we run without some base layer, we get errors.
         if (l.isBuildLayer() && (l == this || l.extendsLayer(this))) {
            if (l.buildSrcIndex == null)
               l.buildSrcIndex = l.readBuildSrcIndex();
            SrcIndexEntry sie = l.buildSrcIndex.get(genRelFileName);
            if (sie == null) {
               if (lastValid == null) {
                  if (traceBuildSrcIndex)
                     System.out.println("No buildSrcEntry for: " + genRelFileName + " mark-in-use failed");
                  // TODO: could compute this by getting the hash from the file but why would buildSrcIndex drift out of sync
                  // with the deps?   In one case, we may have added a new layer so in that case save the last index entry
                  // and use that since by default the new layer inherits the src index entries from the previous one
                  return false;
               }
               else {
                  if (traceBuildSrcIndex)
                     System.out.println("Propagating buildSrcEntry to layer: " + l + " :" + genRelFileName + " : " + lastValid);

                  l.buildSrcIndex.put(genRelFileName, lastValid);
                  l.buildSrcIndexNeedsSave = true;
               }
            }
            else {
               lastValid = sie;
               sie.inUse = true;
               if (traceBuildSrcIndex)
                  System.out.println("Marking buildSrcEntry in use: " + l + " :" + genRelFileName + " : " + lastValid);
            }
         }
      }
      // If we did not find this file anyplace, we need to regenerate
      return lastValid != null;
   }

   private static String BUILD_SRC_INDEX_FILE = "buildSrcIndex.ser";

   private static void printBuildSrcIndex(HashMap<String,SrcIndexEntry> buildSrcIndex) {
      for (Map.Entry<String,SrcIndexEntry> ent:buildSrcIndex.entrySet()) {
         System.out.print("   ");
         System.out.print(ent.getKey());
         System.out.print(" = ");
         System.out.println(ent.getValue());
      }
   }

   private HashMap<String,SrcIndexEntry> readBuildSrcIndex() {
      File buildSrcFile = new File(buildSrcDir, BUILD_SRC_INDEX_FILE);
      if (buildSrcFile.canRead()) {
         try {
            ObjectInputStream ios = new ObjectInputStream(new FileInputStream(buildSrcFile));
            HashMap<String, SrcIndexEntry> res = (HashMap<String, SrcIndexEntry>) ios.readObject();
            if (traceBuildSrcIndex) {
               System.out.println("Read buildSrcIndex for: " + this + " runtime: " + this.layeredSystem.getRuntimeName());
               if (res != null) {
                  printBuildSrcIndex(res);
               }
            }
            if (res != null) {
               return res;
            }
         }
         catch (InvalidClassException exc) {
            System.out.println("buildSrcIndex - version changed: " + this);
            buildSrcFile.delete();
         }
         catch (IOException exc) {
            System.out.println("*** can't read build srcFile: " + exc);
         }
         catch (ClassNotFoundException exc) {
            System.out.println("*** can't read build srcFile: " + exc);
         }
      }
      return new HashMap<String,SrcIndexEntry>();
   }

   public void cleanBuildSrcIndex() {
      if (buildSrcIndex == null)
         return;

      ArrayList<String> unremovedFiles = null;
      ArrayList<String> removedFiles = null;
      ArrayList<String> inuseFiles = null;

      PerfMon.start("cleanBuildSrcIndex");

      if (traceBuildSrcIndex)
         System.out.println("Cleaning buildSrcIndex for: " + this);

      for (Iterator<Map.Entry<String, SrcIndexEntry>> it = buildSrcIndex.entrySet().iterator(); it.hasNext(); ) {
         Map.Entry<String, SrcIndexEntry> ent = it.next();
         SrcIndexEntry sie = ent.getValue();
         String path = ent.getKey();

         /** Some processors will store their generated files in a different directory (e.g. the doc html files).
          *  Note: do not pass in "this" for the fromLayer parameter.  To get the right thing, we should pass in the
          *  layer which contains the original source file.  We don't have that info, but better to consider the
          *  processor than ignore because it's a dynamic layer.
          */
         IFileProcessor proc = layeredSystem.getFileProcessorForFileName(path, null, BuildPhase.Process);
         String srcDir = proc == null ? buildSrcDir : proc.getOutputDirToUse(layeredSystem, buildSrcDir, buildDir);
         String fileName = FileUtil.concat(srcDir, path);
         if (!sie.inUse) {

            // Inner type
            if (path.indexOf(TypeDeclaration.INNER_STUB_SEPARATOR) != -1) {
               String dotPath = FileUtil.removeExtension(path.replace(FileUtil.FILE_SEPARATOR, "."));
               String[] typeNames = StringUtil.split(dotPath, TypeDeclaration.INNER_STUB_SEPARATOR);
               // Pick the outermost type to see if that is still in use
               String typeName = typeNames[0].replace('.', '/') + ".java";
               SrcIndexEntry outer = buildSrcIndex.get(typeName);
               if (outer != null && outer.inUse) {
                  byte[] currentHash;
                  try {
                     currentHash = StringUtil.computeHash(FileUtil.getFileAsBytes(fileName));
                  }
                  catch (IllegalArgumentException exc) {
                     currentHash = null;
                  }

                  if (currentHash != null && Arrays.equals(currentHash, sie.hash)) {
                     sie.inUse = true;
                     continue;
                  }
                  // Not on the file system or changed.  Don't leave the stale srcFileIndex hanging around or it will
                  // stop us from rebuilding it in generateInnerStub.
                  else {
                     if (traceBuildSrcIndex) {
                        if (currentHash == null)
                           System.out.println("   removing index for missing file : " + path + " = " + sie);
                        else
                           System.out.println("   removing index for mismatching file: " + path + " = " + sie + " != " + SrcIndexEntry.hashToString(currentHash));
                     }
                     it.remove();
                     continue;
                  }
               }
            }


            // If we inherit the source file, it is still there but renamed with the .inh suffix
            File srcFile = new File(fileName);
            if (!srcFile.canRead()) {
               String inhFileName = FileUtil.replaceExtension(fileName, Language.INHERIT_SUFFIX);
               File inhFile = new File(inhFileName);
               if (inhFile.canRead()) {
                  fileName = inhFileName;
               }
            }

            try {
               byte[] currentHash = StringUtil.computeHash(FileUtil.getFileAsBytes(fileName));
               if (!Arrays.equals(currentHash, sie.hash))
                  System.out.println("*** Warning generated file: " + fileName + " appears to have been changed.  Did you modify the generated file instead of the source file?");
               else {
                  LayerUtil.removeFileAndClasses(fileName);
                  LayerUtil.removeInheritFile(fileName);
                  if (layeredSystem.options.sysDetails) {
                     if (removedFiles == null)
                        removedFiles = new ArrayList<String>();
                     removedFiles.add(fileName);
                  }
               }
            }
            catch (IllegalArgumentException exc) {
               if (layeredSystem.options.verbose) {
                  if (unremovedFiles == null)
                     unremovedFiles = new ArrayList<String>();
                  unremovedFiles.add(fileName);
               }
            }
            // TODO: remove generated .java and .class files here, possibly compare the hash to be sure it hasn't changed?
            it.remove();
         }
         else {
            if (layeredSystem.options.sysDetails) {
               if (inuseFiles == null)
                  inuseFiles = new ArrayList<String>();
               inuseFiles.add(fileName);
            }
         }
      }

      PerfMon.end("cleanBuildSrcIndex");

      if (layeredSystem.options.sysDetails) {
         if (inuseFiles != null) {
            System.out.println("Build src index - in use files: " + inuseFiles);
         }
         if (removedFiles != null) {
            System.out.println("Build src index - removed these files: " + removedFiles);
         }
      }
      if (layeredSystem.options.verbose && unremovedFiles != null) {
         System.out.println("Build src index - out of sync - failed to remove these files: " + unremovedFiles);
      }
   }

   private void writeBuildSrcIndex() {
      if (buildSrcIndex == null)
         return;

      cleanBuildSrcIndex();

      saveBuildSrcIndex();
      if (traceBuildSrcIndex) {
         if (buildSrcIndex != null) {
            System.out.println("Write buildSrcIndex for: " + this);
            printBuildSrcIndex(buildSrcIndex);
         }
      }
   }

   public void saveBuildSrcIndex() {
      File buildSrcFile = new File(buildSrcDir, BUILD_SRC_INDEX_FILE);
      File buildSrcDirFile = new File(buildSrcDir);
      if (!buildSrcDirFile.isDirectory()) {
         if (!buildSrcDirFile.mkdirs()) {
            System.err.println("*** Can't make buildSrcDir: " + buildSrcDir);
            return;
         }
      }
      if (buildSrcIndex.size() == 0) {
         if (buildSrcFile.canRead())
            buildSrcFile.delete();
         return;
      }

      try {
         ObjectOutputStream os = new ObjectOutputStream(new FileOutputStream(buildSrcFile));
         os.writeObject(buildSrcIndex);

         buildSrcIndexNeedsSave = false;
      }
      catch (IOException exc) {
         System.out.println("*** can't write build srcFile: " + exc);
      }
   }

   public void markDynamicType(String typeName) {
      if (dynTypeIndex == null)
         dynTypeIndex = new HashSet<String>();
      dynTypeIndex.add(typeName);
   }

   public boolean isDynamicType(String typeName) {
      return dynTypeIndex != null && dynTypeIndex.contains(typeName);
   }

   private HashSet<String> readDynTypeIndex() {
      File dynTypeIndexFile = new File(buildSrcDir, layeredSystem.getDynTypeIndexFile());
      if (dynTypeIndexFile.canRead()) {
         try {
            ObjectInputStream ios = new ObjectInputStream(new FileInputStream(dynTypeIndexFile));
            HashSet<String> res = (HashSet<String>) ios.readObject();
            if (res != null)
               return res;
         }
         catch (InvalidClassException exc) {
            System.out.println("reaDynTypeIndex - version changed: " + this);
            dynTypeIndexFile.delete();
         }
         catch (IOException exc) {
            System.out.println("*** can't read dyn type index: " + exc);
         }
         catch (ClassNotFoundException exc) {
            System.out.println("*** can't read dyn type index: " + exc);
         }
      }
      return null;
   }

   public final static int DEFAULT_SORT_PRIORITY = 0;

   public int getSortPriority() {
      int ct = 0;
      // The current implemntation gives us three tiers of layers - compiled-only framework layers (e.g. js.sys)
      // compiled-only application layers, e.g. util and the rest - application layers which are always appended unless they
      // have a dependency.
      if (compiledOnly)
         ct--;
      if (codeType == CodeType.Framework)
         ct--;

      if (ct == 0)
         return DEFAULT_SORT_PRIORITY;

      return ct;
   }

   public void saveDynTypeIndex() {
      File dynTypeFile = new File(buildSrcDir, layeredSystem.getDynTypeIndexFile());
      File buildSrcDirFile = new File(buildSrcDir);
      if (buildSrcDir.contains("clientServer") && layeredSystem.getRuntimeName().contains("java")) {
         if (dynTypeIndex != null && !dynTypeIndex.contains("sc.html.index") && dynamic) {
            System.out.println("***");
            System.err.println("*** Error - saving clientServer index without a dynamic sc.html.index page!");
         }
      }
      if (!buildSrcDirFile.isDirectory()) {
         if (!buildSrcDirFile.mkdirs()) {
            System.err.println("*** Can't make buildSrcDir: " + buildSrcDir);
            return;
         }
      }
      if (dynTypeIndex == null || dynTypeIndex.size() == 0) {
         if (dynTypeFile.canRead())
            dynTypeFile.delete();
         return;
      }

      try {
         ObjectOutputStream os = new ObjectOutputStream(new FileOutputStream(dynTypeFile));
         os.writeObject(dynTypeIndex);
      }
      catch (IOException exc) {
         System.out.println("*** can't write dyn type index file: " + exc);
      }
   }

   private void initImportCache() {
      // Resolve any imports in this layer
      if (imports != null) {
         for (int i = 0;i < imports.size(); i++) {
            ImportDeclaration imp = imports.get(i);
            if (imp.staticImport) {
               String typeName = CTypeUtil.getPackageName(imp.identifier);
               Object typeObj = layeredSystem.getSrcTypeDeclaration(typeName, getNextLayer(), true);
               if (typeObj == null)
                  typeObj = layeredSystem.getClassWithPathName(typeName);
               if (imp.hasWildcard()) {
                  boolean addedAny = false;
                  if (typeObj != null) {
                     Object[] methods = ModelUtil.getAllMethods(typeObj, "static", true, false, false);
                     if (methods != null) {
                        for (int j = 0; j < methods.length; j++) {
                           Object methObj = methods[j];
                           addStaticImportType(ModelUtil.getMethodName(methObj), typeObj);
                           addedAny = true;
                        }
                     }
                     Object[] properties = ModelUtil.getProperties(typeObj, "static");
                     if (properties != null) {
                        for (int j = 0; j < properties.length; j++) {
                           Object propObj = properties[j];
                           if (propObj != null) {
                              String propName = ModelUtil.getPropertyName(propObj);
                              Object other = addStaticImportType(propName, typeObj);
                              if (other != null && other != propObj) {
                                 System.err.println("Duplicate static imports with name: " + propName + " for: ");
                              }
                              addedAny = true;
                           }
                        }
                     }
                     if (!addedAny)
                        System.err.println("Can't find any imported static fields or methods in type: " + imp.identifier + " for: ");
                  }
               }
               else if (typeObj != null) {
                  boolean added = false;
                  String memberName = CTypeUtil.getClassName(imp.identifier);
                  Object[] methods = ModelUtil.getMethods(typeObj, memberName, "static");
                  if (methods != null) {
                     addStaticImportType(memberName, typeObj);
                     added = true;
                  }
                  Object property = ModelUtil.definesMember(typeObj, memberName, JavaSemanticNode.MemberType.PropertyGetSet, null, null);
                  if (property == null)
                     property = ModelUtil.definesMember(typeObj, memberName, JavaSemanticNode.MemberType.PropertySetSet, null, null);
                  if (ModelUtil.hasModifier(property, "static")) {
                     addStaticImportType(memberName, typeObj);
                     added = true;
                  }
                  if (!added)
                     System.err.println("Can't find imported static field or method: " + memberName + " for: ");
               }
               else {
                  System.err.println("Can't resolve static import object: " + typeName);
               }
            }
         }
      }
   }

   void callLayerMethod(String methodName) {
      if (model == null)
         return;

      if (!model.hasErrors()) {
         ExecutionContext ctx = new ExecutionContext();
         ctx.resolver = layeredSystem;
         Object startMethObj = model.getModelTypeDeclaration().findMethod(methodName, null, null, null);
         if (startMethObj instanceof MethodDefinition) {
            ctx.pushCurrentObject(this);
            try {
               MethodDefinition meth = (MethodDefinition) startMethObj;
               meth.invoke(ctx, null);
            }
            catch (Exception exc) {
               displayError("Exception in layer's start method: ", exc.toString());
               if (layeredSystem.options.verbose)
                  exc.printStackTrace();
            }
         }
      }
   }

   /** Note, this stores the type containing the statically imported name, not the field or method. */
   private Object addStaticImportType(String name, Object obj) {
      if (staticImportTypes == null)
         staticImportTypes = new HashMap<String,Object>();
      return staticImportTypes.put(name, obj);
   }

   public void stop() {
      initialized = false;
      started = false;
   }

   public String getLayerPathName() {
      return layerPathName;
   }

   /** The vanilla name of the layer - no package prefix but does include the layer group */
   @Constant
   public String getLayerName() {
      String base = layerDirName;
      if (base == null || base.equals(".")) {
         if (packagePrefix != null && packagePrefix.length() > 1)
            base = layerUniqueName.substring(packagePrefix.length()+1);
         else
            base = layerUniqueName;
      }
      return base;

   }

   /** Just the layer group */
   public String getLayerGroupName() {
      return CTypeUtil.getPackageName(layerDirName);
   }

   public String getLayerUniqueName() {
      return layerUniqueName;
   }

   /** The layered system which owns this layer  */
   public LayeredSystem getLayeredSystem() {
      return layeredSystem;
   }

   public int getLayerPosition() {
      return layerPosition;
   }

   public File findSrcFile(String srcName) {
      if (!isStarted() && !activated)
         ParseUtil.realInitAndStartComponent(this);
      return srcDirCache.get(srcName);
   }

   /** List of suffixes to search for src files.  Right now this is only Java because zip files are only used for precompiled src. */
   private final String[] zipSrcSuffixes = {"java"};

   public SrcEntry findSrcEntry(String srcName, boolean prependPackage) {
      File f = srcDirCache.get(srcName);
      if (f != null) {
         String path = f.getPath();
         String ext = FileUtil.getExtension(path);
         return new SrcEntry(this, path, srcName + "." + ext, prependPackage);
      }
      else if (srcDirZipFiles != null) {
         for (ZipFile zf:srcDirZipFiles) {
            for (String zipSrcSuffix:zipSrcSuffixes) {
               String fileInZip = FileUtil.addExtension(srcName,zipSrcSuffix);
               ZipEntry zipEntry = zf.getEntry(fileInZip);
               if (zipEntry != null) {
                  String path = zf.getName();
                  return new ZipSrcEntry(this, path + "@" + fileInZip, srcName + "." + zipSrcSuffix, prependPackage, path, zf, zipEntry);
               }
            }
         }
      }
      return null;
   }

   public Iterator<String> getSrcFiles() {
      return srcDirCache.keySet().iterator();
   }

   public Set<SrcEntry> getSrcEntries() {
      HashSet<SrcEntry> srcEntries = new HashSet<SrcEntry>();
      for (Iterator<String> srcFiles = getSrcFiles(); srcFiles.hasNext(); )  {
         String srcFile = srcFiles.next();
         IFileProcessor depProc = layeredSystem.getFileProcessorForFileName(srcFile, this, BuildPhase.Process);
         // srcFile entries come back without suffixes... just ignore them.
         if (depProc != null) {
            File f = srcDirCache.get(srcFile);
            srcEntries.add(new SrcEntry(this, f.getPath(), srcFile,  depProc.getPrependLayerPackage()));
         }
      }
      return srcEntries;
   }

   public Set<String> getSrcTypeNames(boolean prependLayerPrefix, boolean includeInnerTypes, boolean restrictToLayer, boolean includeImports) {
      TreeSet<String> typeNames = new TreeSet<String>();
      for (Iterator<String> srcFiles = getSrcFiles(); srcFiles.hasNext(); )  {
         String srcFile = srcFiles.next();
         IFileProcessor depProc = layeredSystem.getFileProcessorForFileName(srcFile, this, BuildPhase.Process);
         String typeName;
         // srcFile entries come back without suffixes... just ignore them.
         if (depProc != null) {
            String fullTypeName = CTypeUtil.prefixPath(packagePrefix, FileUtil.removeExtension(srcFile).replace(FileUtil.FILE_SEPARATOR, "."));
            if (depProc.getPrependLayerPackage() && prependLayerPrefix) {
               typeName = fullTypeName;
            }
            else {
               typeName = FileUtil.removeExtension(srcFile).replace(FileUtil.FILE_SEPARATOR, ".");
            }
            typeNames.add(typeName);

            if (includeInnerTypes) {
               BodyTypeDeclaration td = layeredSystem.getSrcTypeDeclaration(fullTypeName, null, depProc.getPrependLayerPackage());
               if (td == null) {
                  Layer l = layeredSystem.getLayerByTypeName(fullTypeName);
                  if (l != null)
                     td = l.model.getModelTypeDeclaration();
               }
               else {
                  addChildTypes(typeNames, typeName, td, restrictToLayer);
               }
            }
         }
      }
      if (includeImports) {
         if (importsByName != null) {
            for (Map.Entry<String,ImportDeclaration> imp:importsByName.entrySet()) {
               ImportDeclaration decl = imp.getValue();
               typeNames.add(decl.identifier);
            }
         }
      }
      return typeNames;
   }

   public Set<String> getImportedTypeNames() {
      TreeSet<String> typeNames = new TreeSet<String>();
      if (importsByName != null) {
         for (Map.Entry<String,ImportDeclaration> imp:importsByName.entrySet()) {
            ImportDeclaration decl = imp.getValue();
            typeNames.add(decl.identifier);
         }
      }
      return typeNames;
   }

   public Set<String> getInnerTypeNames(String relTypeName, Object type, boolean restrictToLayer) {
      TreeSet<String> typeNames = new TreeSet<String>();
      addChildTypes(typeNames, relTypeName, type, restrictToLayer);
      return typeNames;
   }

   private void addChildTypes(Set<String> typeNames, String typeName, Object type, boolean restrictToLayer) {
      Object[] innerTypes = ModelUtil.getAllInnerTypes(type, null, true);
      if (innerTypes == null)
         return;

      for (Object innerType:innerTypes) {
         // Only the declaring type... otherwise an inner class which extends the main class which show stuff forever.
         // also could solve that with a "visited" list to see a more complete view of subobjects that are available
         // or just build the list lazily as we visit.
         if (ModelUtil.sameTypes(ModelUtil.getEnclosingType(innerType), type) && (!restrictToLayer || ModelUtil.definedInLayer(innerType, this))) {
            // Skipping the generated anon classes and enum constants.  Enum constants can't be found from getSrcTypeDeclaration which causes problems in the editor
            if (!(innerType instanceof AnonClassDeclaration) && !(innerType instanceof EnumConstant)) {
               String subTypeFullName = ModelUtil.getTypeName(innerType, false);
               String subTypeName = typeName + "." + CTypeUtil.getClassName(subTypeFullName);
               typeNames.add(subTypeName);

               addChildTypes(typeNames, subTypeName, innerType, restrictToLayer);
            }
         }
      }
   }

   /** Returns the layer after this one in the list, i.e. the one that overrides this layer */
   public Layer getNextLayer() {
      int size = layeredSystem.layers.size();
      if (layerPosition == size-1 || !activated)
         return null;
      return layeredSystem.layers.get(layerPosition+1);
   }

   /** Returns the layer just before this one in the list, i.e. the one this layer overrides */
   public Layer getPreviousLayer() {
      if (layerPosition == 0 || !activated)
         return null;
      return layeredSystem.layers.get(layerPosition - 1);
   }

   public Layer getPreviousBuildLayer() {
      Layer prev;
      for (prev = getPreviousLayer(); prev != null && !prev.isBuildLayer(); prev = prev.getPreviousLayer())
         ;
      return prev;
   }

   public Layer getNextBuildLayer() {
      return getNextBuildLayer(this);
   }

   /** Return the build layer for this layer - this layer if it's a buildLayer */
   public Layer getNextBuildLayer(Layer forLayer) {
      if (isBuildLayer() && (forLayer == this || extendsLayer(forLayer)))
         return this;
      int size = layeredSystem.layers.size();
      if (layerPosition == size-1 || this == layeredSystem.buildLayer)
         return this;
      return layeredSystem.layers.get(layerPosition+1).getNextBuildLayer(forLayer);
   }

   /**
    * Returns a file name relative to the layer directory.
    * This filename uses / as the file separator so it is platform independent
    */
   public String getRelativeFile(String fileName) {
      return FileUtil.getRelativeFile(layerPathName, fileName);
   }

   /**
    * This method looks in the class path and find the file which defines this class (for dependency purposes).
    * If it is a zip file, we can just return the zip file.  We first look in the build directory.
    */
   public File findClassFile(String classFileName, boolean includePrevious) {
      File result;

      if (buildDir != null && compiled) {
         result = new File(buildDir, classFileName);
         if (result.canRead())
            return result;
      }

      if (classDirs != null) {
         for (int i = 0; i < classDirs.size(); i++) {
            String classDir = classDirs.get(i);
            if (zipFiles[i] != null) {
               if (zipFiles[i].getEntry(classFileName) != null)
                  return new File(classDir);
            }
            else {
               result = new File(classDir, classFileName);
               if (result.canRead())
                  return result;
            }
         }
      }
      if (includePrevious) {
         Layer prevLayer = getPreviousLayer();
         if (prevLayer != null)
            return prevLayer.findClassFile(classFileName, true);
      }
      return null;
   }

   public Object getClass(String classFileName, String className) {
      if (!layeredSystem.options.crossCompile) {
         return RTypeUtil.loadClass(layeredSystem.getSysClassLoader(), className, false);
      }
      else
         return getCFClass(classFileName);
   }

   public Object getCFClass(String classFileName) {
      if (classDirs == null)
         return null;
      for (int i = 0; i < classDirs.size(); i++) {
         CFClass cl;
         if (zipFiles[i] != null) {
            cl = CFClass.load(zipFiles[i], classFileName, this);
         }
         else
            cl = CFClass.load(FileUtil.concat(classDirs.get(i), classFileName), this);
         if (cl != null)
            return cl;
      }
      return null;
   }

   public Object getInnerCFClass(String fullTypeName, String name) {
      String classFileName = fullTypeName.replace('.','/') + "$" + name + ".class";
      return getCFClass(classFileName);
   }

   public ImportDeclaration getImportDecl(String name, boolean checkBaseLayers) {
      if (importsByName != null) {
         ImportDeclaration res = importsByName.get(name);
         if (res != null)
            return res;
      }
      if (checkBaseLayers && baseLayers != null) {
         for (Layer base:baseLayers) {
            if (base.exportImportsTo(this)) {
               ImportDeclaration baseRes = base.getImportDecl(name, true);
               if (baseRes != null)
                  return baseRes;
            }
         }
      }
      return null;
   }

   public boolean exportImportsTo(Layer refLayer) {
      return exportImports && (refLayer == null || refLayer == this || refLayer.useGlobalImports || refLayer.extendsLayer(this));
   }

   public void findMatchingGlobalNames(String prefix, Set<String> candidates) {
      if (!exportImports)
         return;

      if (importsByName != null) {
         for (String str:importsByName.keySet())
            if (str.startsWith(prefix))
               candidates.add(str);
      }
      if (staticImportTypes != null) {
         for (String str:staticImportTypes.keySet())
            if (str.startsWith(prefix))
               candidates.add(str);
      }
   }

   public String getPackagePath() {
      if (packagePrefix == null || packagePrefix.length() == 0)
         return null;
      return packagePrefix.replace(".", FileUtil.FILE_SEPARATOR);
   }

   public boolean skipStart(String fileName, String prefix) {
      if (skipStartPaths != null && prefix != null) {
         for (String path: skipStartPaths) {
            if (path.startsWith(prefix) && path.equals(FileUtil.concat(prefix,fileName)))
               return true;
         }
      }
      if (skipStartPatterns != null) {
         for (int i = 0; i < skipStartPatterns.size(); i++)
            if (skipStartPatterns.get(i).matcher(fileName).matches())
               return true;
      }
      return false;
   }

   public boolean excludedFile(String fileName, String prefix) {
      if (excludedPaths != null && prefix != null) {
         for (String path:excludedPaths) {
            if (path.startsWith(prefix) && path.equals(FileUtil.concat(prefix,fileName)))
               return true;
         }
      }
      if (excludedPatterns != null) {
         for (int i = 0; i < excludedPatterns.size(); i++)
            if (excludedPatterns.get(i).matcher(fileName).matches()) {
               return true;
            }
      }
      if (skipStart(fileName, prefix))
         return true;
      return false;
   }

   public void setLayerUniqueName(String s) {
      layerUniqueName = s;
   }

   public String getLayerTypeName() {
      return CTypeUtil.prefixPath(packagePrefix, getLayerUniqueName());
   }

   /** Returns the type name of the layer's definition file. This is just the package prefix plus the name of the layer */
   public String getLayerModelTypeName() {
      return CTypeUtil.prefixPath(packagePrefix, FileUtil.removeExtension(layerBaseName));
   }

   /** Note, this returns the type containing the statically imported name, not the field or method. */
   public Object getStaticImportedType(String name) {
      if (staticImportTypes != null)
         return staticImportTypes.get(name);
      return null;
   }

   public InputStream getClassInputStream(String classPathName) throws IOException {
      InputStream is;
      if (buildDir != null && compiled) {
         is = getClassInputStreamFromBuildDir(classPathName, buildDir);
         if (is != null)
            return is;
      }

      if (classDirs != null) {
         for (int i = 0; i < classDirs.size(); i++) {
            String classDir = classDirs.get(i);
            if (zipFiles[i] != null) {
               ZipFile zipFile = zipFiles[i];
               ZipEntry zipEnt = zipFile.getEntry(classPathName);
               if (zipEnt != null) {
                  return zipFile.getInputStream(zipEnt);
               }
            }
            else if ((is = getClassInputStreamFromBuildDir(classPathName, classDir)) != null)
               return is;
         }
      }
      return null;
   }

   private InputStream getClassInputStreamFromBuildDir(String classPathName, String classDir) throws IOException {
      File classFile = new File(classDir, classPathName);
      if (classFile.canRead())
         return new FileInputStream(classFile);
      return null;
   }

   public String toString() {
      String base = getLayerName();
      if (packagePrefix == null || packagePrefix.length() == 0)
         return base;
      else
         return base + "(" + packagePrefix + ")";
   }

   public void saveLayer() {
      FileUtil.saveStringAsFile(model.getSrcFile().absFileName, model.getParseNode().toString(), true);
   }

   public void initModel() {
      ModifyDeclaration modType = ModifyDeclaration.create(layerDirName);
      if (dynamic)
         modType.addModifier("dynamic");
      if (defaultModifier != null)
         modType.addModifier(defaultModifier);
      if (baseLayerNames != null && baseLayerNames.size() > 0) {
         SemanticNodeList<JavaType> baseTypes = new SemanticNodeList<JavaType>();
         for (String baseName:baseLayerNames)
            baseTypes.add(ClassType.create(baseName));
         modType.setProperty("extendsTypes", baseTypes);
      }

      SCModel m = SCModel.create(packagePrefix, modType);
      m.layer = this;
      m.isLayerModel = true;
      m.layeredSystem = layeredSystem;
      m.addSrcFile(new SrcEntry(this, FileUtil.concat(layerPathName, layerBaseName), layerBaseName));

      if (transparent)
         modType.addBodyStatementIndent(PropertyAssignment.create("transparent", BooleanLiteral.create(true), "="));

      // initialize the model
      ParseUtil.initComponent(m);
      model = m;
   }

   public void refresh(long lastRefreshTime, ExecutionContext ctx, List<ModelUpdate> changedModels, UpdateInstanceInfo updateInfo) {
      for (int i = 0; i < srcDirs.size(); i++) {
         String srcDir = srcDirs.get(i);
         String relDir = null;
         int pathLen;
         if (srcDir.startsWith(layerPathName) && (pathLen = layerPathName.length()) + 1 < srcDir.length()) {
            relDir = srcDir.substring(pathLen+1);
         }
         refreshDir(srcDir, relDir, lastRefreshTime, ctx, changedModels, updateInfo);
      }
      lastRefreshTime = System.currentTimeMillis();
   }

   public class ModelUpdate {
      public ILanguageModel oldModel;
      public Object changedModel;

      public ModelUpdate(ILanguageModel oldM, Object newM) {
         oldModel = oldM;
         changedModel = newM;
      }

      public String toString() {
         return changedModel.toString();
      }
   }

   public void refreshDir(String srcDir, String relDir, long lastRefreshTime, ExecutionContext ctx, List<ModelUpdate> changedModels, UpdateInstanceInfo updateInfo) {
      File f = new File(srcDir);
      long newTime = -1;
      String prefix = relDir == null ? "" : relDir;
      if (lastRefreshTime == -1 || (newTime = f.lastModified()) > lastRefreshTime) {
         // First update the src cache to pick up any new files, refresh any models we find in there when ctx is not null
         addSrcFilesToCache(f, prefix);
      }

      File[] files = f.listFiles();
      for (File subF:files) {
         String path = subF.getPath();

         IFileProcessor proc = null;
         if (subF.isDirectory()) {
            if (!excludedFile(subF.getName(), prefix)) {
               // Refresh anything that might have changed
               refreshDir(path, FileUtil.concat(relDir, subF.getName()), lastRefreshTime, ctx, changedModels, updateInfo);
            }
         }
         else if (Language.isParseable(path) || (proc = layeredSystem.getFileProcessorForFileName(path, this, BuildPhase.Process)) != null) {
            SrcEntry srcEnt = new SrcEntry(this, relDir == null ? layerPathName : FileUtil.concat(layerPathName, relDir), relDir == null ? "" : relDir, subF.getName(), proc == null || proc.getPrependLayerPackage());
            ILanguageModel oldModel = layeredSystem.getLanguageModel(srcEnt);
            long newLastModTime = new File(srcEnt.absFileName).lastModified();
            if (oldModel == null) {
               // The processedFileIndex only holds entries we processed.  If this file did not change from when we did the build, we just have to
               // decide to rebuild it.
               IFileProcessorResult oldFile = layeredSystem.processedFileIndex.get(srcEnt.absFileName);
               long lastTime = oldFile == null ?
                       layeredSystem.lastRefreshTime == -1 ? layeredSystem.buildStartTime : layeredSystem.lastRefreshTime :
                       oldFile.getLastModifiedTime();
               if (lastTime == -1 || newLastModTime > lastTime) {
                  layeredSystem.refreshFile(srcEnt, this);
               }
            }

            // We are refreshing any models which have changed on disk or had errors last time.  Technically for the error models, we could just restart them perhaps
            // but we need to clear old all of the references to anything else which has changed.  Seems like this might be more reliable now though obviously a bit slower.
            if (newLastModTime > lastRefreshTime || (oldModel != null && oldModel.hasErrors())) {
               Object res = layeredSystem.refresh(srcEnt, ctx, updateInfo);
               if (res != null)
                  changedModels.add(new ModelUpdate(oldModel, res));
            }
         }
      }
   }

   /** Register the source file in any build layers including or following this one */
   public void addSrcFileIndex(String relFileName, byte[] hash, String ext) {
      for (int i = layerPosition; i < layeredSystem.layers.size(); i++) {
         Layer l = layeredSystem.layers.get(i);
         if (l.isBuildLayer()) {
            SrcIndexEntry sie;
            // Layer may not have been started yet - if we are building one layer to produce classes needed to
            // start the next layer
            if (l.buildSrcIndex == null)
               l.buildSrcIndex = l.readBuildSrcIndex();
            else {
               sie = l.buildSrcIndex.get(relFileName);
               if (sie != null) {
                  sie.inUse = true;
                  continue;
               }
            }
            sie = new SrcIndexEntry();
            sie.hash = hash;
            sie.inUse = true;
            sie.extension = ext;

            if (traceBuildSrcIndex)
               System.out.println("Adding buildSrcIndex " + relFileName + " : " + sie + " runtime: " + layeredSystem.getRuntimeName());

            l.buildSrcIndex.put(relFileName, sie);

            buildSrcIndexNeedsSave = true;
         }
      }
   }

   public SrcIndexEntry getPrevSrcFileIndex(String relFileName) {
      for (int i = layerPosition-1; i >= 0; i--) {
         Layer prev = layeredSystem.layers.get(i);
         SrcIndexEntry prevEnt;
         if (prev.buildSrcIndex != null && (prevEnt = prev.buildSrcIndex.get(relFileName)) != null)
            return prevEnt;
      }
      return null;
   }

   public Layer getPrevSrcFileLayer(String relFileName) {
      for (int i = layerPosition-1; i >= 0; i--) {
         Layer prev = layeredSystem.layers.get(i);
         if (prev.compiled && prev.buildSrcIndex != null && prev.buildSrcIndex.get(relFileName) != null)
            return prev;
      }
      return null;
   }

   public SrcIndexEntry getSrcFileIndex(String relFileName) {
      for (int i = layerPosition; i < layeredSystem.layers.size(); i++) {
         Layer l = layeredSystem.layers.get(i);
         if (l.isBuildLayer()) {
            // A build layer that hasn't been built yet
            if (l.buildSrcIndex == null) {
               return null;
            }
            else
               return l.buildSrcIndex.get(relFileName);
         }
      }
      return null;
   }

   public boolean extendsLayer(Layer other) {
      if (baseLayers == null)
         return false;
      for (int i = 0; i < baseLayers.size(); i++) {
         Layer base = baseLayers.get(i);
         if (base == other || base.extendsLayer(other))
            return true;
      }
      return false;
   }

   public boolean modifiedByLayer(Layer other) {
      if (extendsLayer(other))
         return true;

      boolean packagesOverlap = false;

      String pkg = other.packagePrefix;
      if (pkg == null || pkg.length() == 0)
         packagesOverlap = true;
      else
         packagesOverlap = modifiesPackage(pkg);

      if (packagesOverlap) {
         return nestedSrcFilesOverlap(other);
      }

      return false;
   }

   private boolean explicitlyModifies(Layer other) {
      if (modifiedLayerNames != null) {
         for (String modLayerName:modifiedLayerNames)
            if (other.layerDirName.equals(modLayerName))
               return true;
      }
      return false;
   }

   private boolean nestedSrcFilesOverlap(Layer other) {
      if (srcFilesOverlap(other))
         return true;

      if (explicitlyModifies(other) || other.explicitlyModifies(this))
         return true;

      if (baseLayers != null) {
         // Look for any layers that are in front of this layer in the chain which modify layers this
         // layer depends on - i.e. any overlaps
         for (int i = 0; i < baseLayers.size(); i++) {
            Layer base = baseLayers.get(i);
            if (base.removed)
               continue;
            if (base.nestedSrcFilesOverlap(other)) {
               return true;
            }
         }
      }
      return false;
   }

   private boolean srcFilesOverlap(Layer other) {
      if (!started || !other.started)
         throw new IllegalArgumentException("Layer must be started to determine overlaps");

      String thisPrefix = packagePrefix;
      String otherPrefix = other.packagePrefix;

      String prependPrefix = "";
      Map<String,TreeSet<String>> enclDirs = null, subDirs = null;

      // Figure out if these two layers overlap and if so, which is inside the other (sub versus enclosing)
      if (thisPrefix == null || thisPrefix.length() == 0) {
         prependPrefix = otherPrefix;
         subDirs = other.relSrcIndex;
         enclDirs = this.relSrcIndex;
      }
      // If the other is empty, this is inside of it
      else if (otherPrefix == null || otherPrefix.length() == 0) {
         prependPrefix = thisPrefix;
         enclDirs = other.relSrcIndex;
         subDirs = this.relSrcIndex;
      }
      else {
         // Other has a long name - if it overlaps with this, it's inside
         if (otherPrefix.length() > thisPrefix.length()) {
            if (!otherPrefix.startsWith(thisPrefix))
               return false;
            prependPrefix = otherPrefix.substring(thisPrefix.length() + 1);
            subDirs = other.relSrcIndex;
            enclDirs = this.relSrcIndex;
         }
         // If this has a longer name, it's on the inside and we start at where the otherPrefix begins.
         else if (thisPrefix.length() > otherPrefix.length()) {
            if (!thisPrefix.startsWith(otherPrefix))
               return false;
            prependPrefix = thisPrefix.substring(otherPrefix.length() + 1);
            enclDirs = other.relSrcIndex;
            subDirs = this.relSrcIndex;
         }
         else {
            enclDirs = other.relSrcIndex;
            subDirs = this.relSrcIndex;
            if (!otherPrefix.equals(thisPrefix))
               return false;
         }
      }

      prependPrefix = prependPrefix.replace('.', '/');

      // Go through the sub layer's file index and figure which directories have src directories that correspond in the encl layer
      for (Map.Entry<String,TreeSet<String>> subDirEnt:subDirs.entrySet()) {
         String subDirPrefix = subDirEnt.getKey();
         String enclDirPrefix = FileUtil.concat(prependPrefix, subDirPrefix);
         if (enclDirPrefix == null)
            enclDirPrefix = "";

         TreeSet<String> rootDirFiles = enclDirs.get(enclDirPrefix);
         if (rootDirFiles != null) {
            TreeSet<String> subDirFiles = subDirEnt.getValue();

            // For those return true if there are any overlapping files.
            // TODO: do we need to account for the prependPackagePrefix flag here?  It means any two layers can overlap for those files since the package is not used.
            if (!Collections.disjoint(subDirFiles, rootDirFiles))
               return true;
         }
      }

      return false;
   }

   public boolean modifiesPackage(String pkg) {
      if (packagePrefix == null || packagePrefix.length() == 0)
         return true;

      if (packagePrefix.startsWith(pkg) || pkg.startsWith(packagePrefix))
         return true;

      if (baseLayers == null)
         return false;
      // Look for any layers that are in front of this layer in the chain which modify layers this
      // layer depends on - i.e. any overlaps
      for (int i = 0; i < baseLayers.size(); i++) {
         Layer base = baseLayers.get(i);
         if (base.modifiesPackage(pkg)) {
            return true;
         }
      }

      return false;
   }

   /**
    * This property is not settable but this is needed to suppress a bogus error when we are sync'ing this property to the client.
    * If we don't get the js specific source for this class we do not find the setDependentLayers method and get an error parsing.
    * We don't want to load the source for performance reasons.
    * */
   public void setDependentLayers(List<Layer> layers) {
      throw new UnsupportedOperationException();
   }

   /** Returns the set of layers which extend this layer in the same order as they originally appeared in the list */
   @Constant public List<Layer> getDependentLayers() {
      List<Layer> theLayers = layeredSystem.layers;
      List<Layer> res = new ArrayList<Layer>();
      for (int i = layerPosition + 1; i < theLayers.size(); i++) {
         Layer other = theLayers.get(i);
         if (other.extendsLayer(this))
            res.add(other);
      }
      return res;
   }

   /** Returns the set of layers which this layer depends on - including all layers for the build layer and the current layer */
   private String getDependentLayerNames() {
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (Layer l:layeredSystem.layers) {
         if (this == layeredSystem.buildLayer || extendsLayer(l) || l == this) {
            if (!first) {
               sb.append(" ");
            }
            else
               first = false;
            // If a layer changes from dynamic to compiled we need to compile all
            // Maybe we could optimize this so we only mark the files in this layer as changed?
            if (l.dynamic)
              sb.append("dyn:");
            sb.append(l.layerPathName);
         }
      }
      return sb.toString();
   }

   public boolean getBuildAllFiles() {
      return buildAllFiles || layeredSystem.options.buildAllFiles;
   }

   BuildInfo loadBuildInfo() {
      if (buildInfo != null)
         return buildInfo;

      BuildInfo gd = null;

      String bfFileName = layeredSystem.getBuildInfoFile();
      String buildInfoFile = FileUtil.concat(buildSrcDir, bfFileName);

      String currentLayerNames = getDependentLayerNames();
      // Reset this file when you are compiling everything in case it gets corrupted
      if (!getBuildAllFiles()) {
         gd = (BuildInfo) layeredSystem.loadInstanceFromFile(buildInfoFile, "sc/layer/" + bfFileName);
         if (gd == null) {
            if (layeredSystem.options.verbose)
               System.out.println("Missing BuildInfo file: " + buildInfoFile + " for runtime: " + layeredSystem.getRuntimeName());
            buildAllFiles = true;
         }
         else if (!StringUtil.equalStrings(currentLayerNames, gd.layerNames)) {
            if (layeredSystem.options.verbose)
               System.out.println("Layer names changed from: " + gd.layerNames + " to: " + currentLayerNames);
            else if (layeredSystem.options.info)
               System.out.println("Layer names changed - building all");
            // Mark this layer as requiring "buildAllFiles" - not globally since downstream layers are not affected
            buildAllFiles = true;
            gd = null; // This build info is no good to use - we'll copy the previous one below.
         }
      }

      // We need to start out copying the original buildInfo file since we'll inherit
      Layer prevBuildLayer = layeredSystem.getPreviousBuildLayer(this);
      boolean copied = false;
      boolean missingBuildInfo = false;
      if (gd == null && prevBuildLayer != null && (getBuildAllFiles() || (missingBuildInfo = !new File(buildInfoFile).canRead()))) {
         prevBuildLayer.saveBuildInfo(false);
         File dstFile = new File(FileUtil.getParentPath(buildInfoFile));
         dstFile.mkdirs();
         String srcFile = FileUtil.concat(prevBuildLayer.buildSrcDir, layeredSystem.getBuildInfoFile());
         if (srcFile.equals(buildInfoFile))
            System.out.println("*** Two layers sharing the same BuildInfo: " + srcFile + " for: " + prevBuildLayer + " and: " + this);
         else if (!FileUtil.copyFile(srcFile, buildInfoFile, true))
            missingBuildInfo = true;

         if (prevBuildLayer.dynTypeIndex != null)
            dynTypeIndex = (HashSet<String>) prevBuildLayer.dynTypeIndex.clone();
         else
            dynTypeIndex = null;

         // Don't reset the file if -a is used when the buildInfo is copied to downstream build layer.
         copied = true;

         // For new layers, we do not have to build all files since there's nothing there
         if (missingBuildInfo && !newLayer) {
            if (layeredSystem.options.info)
               System.out.println("Missing BuildInfo file - building all files");
            buildAllFiles = true;
         }
      }
      else {
         // Note: if just this layer's buildAllFiles is set, but a previous build layer is not, some of those files may be made dynamic by this layer - e.g. HtmlPage is made
         // dynamic in editorMixin and then index.schtml is now dynamic.  Because the index holds info we store per-layer but we don't build the previous build layers we won't pick this up.
         // The other way to fix this is to find a way to force index.schtml to be rebuilt when HtmlPage is modified.
         if (layeredSystem.options.buildAllFiles)
            dynTypeIndex = null;
         else
            dynTypeIndex = readDynTypeIndex();
      }

      // Reset this file when you are compiling everything in case it gets corrupted
      if (gd == null && (copied || !getBuildAllFiles()))
         gd = (BuildInfo) layeredSystem.loadInstanceFromFile(FileUtil.concat(buildSrcDir, bfFileName), "sc/layer/" + bfFileName);

      if (gd == null) {
         gd = new BuildInfo(this);
         gd.layerNames = currentLayerNames;
         gd.changed = true;
         buildAllFiles = true;
      }
      else {
         if (!StringUtil.equalStrings(currentLayerNames, gd.layerNames)) {
            // For a newly added layer, we will have just copied over the current buildInfo from the previous build
            // layer which should be up to date.   We do have to update the layer names so it points to the current
            // set since this was not done during the copy.
            gd.layerNames = currentLayerNames;
            gd.changed = true;
         }
      }
      gd.system = layeredSystem;
      gd.layer = this;
      gd.init();
      buildInfo = gd;
      return gd;
   }

   public void saveBuildInfo(boolean writeBuildSrcIndex) {
      if (isBuildLayer()) {
         if (writeBuildSrcIndex)
            writeBuildSrcIndex();

         if (buildInfo != null && buildInfo.changed)
            layeredSystem.saveTypeToFixedTypeFile(FileUtil.concat(buildSrcDir, layeredSystem.getBuildInfoFile()), buildInfo,
                    "sc.layer.BuildInfo");

         saveDynTypeIndex();
      }
   }

   public boolean matchesFilter(Collection<CodeType> codeTypes, Collection<CodeFunction> codeFunctions) {
      return (codeTypes == null || codeTypes.contains(codeType)) && (codeFunctions == null || codeFunctions.contains(codeFunction));
   }

   public final static String BUILD_STATUS_FILE_BASE = "buildStatus.txt";

   public final static String COMPLETE_STATUS = "Build completed:";

   /**
    * Called for a build layer before the first phase with true and after the last phase with false.  Decides whether the build
    * was interrupted or not.
    */
   public void updateBuildInProgress(boolean start) {
      String rtName = layeredSystem.getRuntimeName();
      String fileBase = BUILD_STATUS_FILE_BASE;
      if (rtName != null)
         fileBase = "." + rtName + "_" + fileBase;
      String buildInProgressFile = FileUtil.concat(buildDir, fileBase);

      if (start) {
         String status;
         try {
            status = new File(buildInProgressFile).canRead() ? FileUtil.getFileAsString(buildInProgressFile) : null;
         }
         catch (IllegalArgumentException exc) {
            status = null;
         }
         if (status == null || !status.startsWith(COMPLETE_STATUS)) {
            buildAllFiles = true;
            if (status != null) {
               layeredSystem.buildInterrupted = true;
               if (layeredSystem.options.info)
                  System.out.print("Rebuilding build layer: " + this + " from scratch - last build interrupted! " + status);
            }
            else {
               if (layeredSystem.options.info)
                  System.out.println("New build for build layer: " + this);
            }
         }
         FileUtil.saveStringAsFile(buildInProgressFile, "Build started: " + new Date() + FileUtil.LINE_SEPARATOR, true);
      }
      else {
         FileUtil.saveStringAsFile(buildInProgressFile, COMPLETE_STATUS + new Date() + FileUtil.LINE_SEPARATOR, true);
      }
   }

   public boolean transparentToLayer(Layer other) {
      if (other == this)
         return true;

      if (!transparent)
         return false;

      if (baseLayers == null)
         return false;
      for (int i = 0; i < baseLayers.size(); i++) {
         Layer base = baseLayers.get(i);
         if (base.transparentToLayer(other))
            return true;
      }
      return false;
   }

   /** Returns the set of layers which are transparent onto this layer.  This is fairly brute force and obviously could be sped up a lot by just pre-computing the list */
   public List<Layer> getTransparentLayers() {
      List<Layer> res = null;
      for (int i = layerPosition+1; i < layeredSystem.layers.size(); i++) {
         Layer other = layeredSystem.layers.get(i);
         if (other.transparent && other.extendsLayer(this)) {
            if (res == null)
               res = new ArrayList<Layer>();
            res.add(other);
         }
      }
      return res;
   }


   /** To be visible in the editor, we cannot be extended only from hidden layers.  We need at least one visible, non-hidden layer to extend this layer */
   public boolean getVisibleInEditor() {
      if (hidden)
         return false;

      for (int i = 0; i < layeredSystem.specifiedLayers.size(); i++) {
         Layer specLayer = layeredSystem.specifiedLayers.get(i);
         if (specLayer == this)
            return true;
         if (!specLayer.hidden && specLayer.extendsLayer(this))
            return true;
      }
      return false;
   }

   public void initSync() {
      int globalScopeId = GlobalScopeDefinition.getGlobalScopeDefinition().scopeId;
      SyncManager.addSyncType(Layer.class,
             new SyncProperties(null, null,
                     new Object[]{"packagePrefix", "defaultModifier", "dynamic", "hidden", "compiledOnly", "transparent",
                                  "baseLayerNames", "layerPathName", "layerBaseName", "layerDirName", "layerUniqueName",
                                  "layerPosition", "codeType", "codeFunction", "dependentLayers"},
                     null, SyncOptions.SYNC_INIT_DEFAULT,  globalScopeId));
      SyncManager.addSyncInst(this, true, true, null);
   }

   public String toDetailString() {
      StringBuilder sb = new StringBuilder();
      if (dynamic)
         sb.append("dynamic ");
      if (hidden)
         sb.append("hidden ");
      sb.append(toString());

      if (codeType != null) {
         sb.append(" codeType: ");
         sb.append(codeType);
      }

      if (codeFunction != null) {
         sb.append(" codeFunction: ");
         sb.append(codeType);
      }
      sb.append("[");
      sb.append(layerPosition);
      sb.append("]");

      List depLayers = getDependentLayers();
      if (depLayers != null) {
         sb.append(" used by: ");
         sb.append(depLayers);
      }
      return sb.toString();
   }

   public boolean getCompiledFinalLayer() {
      // A final layer is final once it's compiled.  After the entire system has been compiled, if we disable dynamic types
      // for this layer, those types won't change once the system is compiled.  This prevents us from loading those types as
      // source even when they are static.
      return (finalLayer && (compiled || changedModelsDetected)) || (!liveDynamicTypes && layeredSystem.systemCompiled);
   }

   public RepositoryPackage addRepositoryPackage(String pkgName, String repositoryTypeName, String url, boolean unzip) {
      if (repositoryPackages == null)
         repositoryPackages = new ArrayList<RepositoryPackage>();

      IRepositoryManager mgr = layeredSystem.repositorySystem.getRepositoryManager(repositoryTypeName);
      if (mgr != null) {
         RepositoryPackage pkg = new RepositoryPackage(pkgName, new RepositorySource(mgr, url, unzip));
         repositoryPackages.add(pkg);

         // We do the install and update immediately after they are added so that the layer definition file has
         // access to the installed state, to for example, list the contents of the lib directory to get the jar files
         // to add to the classpath.
         String err = pkg.install();
         if (err != null) {
            System.err.println("Failed to install repository package: " + pkg.packageName + " for layer: " + this + " error: " + err);
         }
         else if (layeredSystem.options.updateSystem) {
            pkg.update();
         }
         return pkg;
      }
      else
         System.err.println("*** Failed to add repository package: " + pkgName + " no repositoryManager named: " + repositoryTypeName);
      return null;
   }

   public SrcEntry getSrcFileFromRelativeTypeName(String relDir, String subPath, String pkgPrefix, boolean srcOnly, boolean checkBaseLayers) {
      String relFilePath = relDir == null ? subPath : FileUtil.concat(relDir, subPath);
      boolean packageMatches;

      if (packagePrefix.length() > 0) {
         if (pkgPrefix == null || !pkgPrefix.startsWith(packagePrefix)) {
            relFilePath = subPath; // go back to non-prefixed version
            packageMatches = false;
         }
         else {
            relFilePath = relFilePath.substring(packagePrefix.length() + 1);
            packageMatches = true;
         }
      }
      else
         packageMatches = true;

      File res = findSrcFile(relFilePath);
      LayeredSystem sys = getLayeredSystem();
      if (res != null) {
         String path = res.getPath();
         IFileProcessor proc = sys.getFileProcessorForFileName(path, this, BuildPhase.Process);
         // Some file types (i.e. web.xml, vdoc) do not prepend the package.  We still want to look up these
         // types by their name in the layer path tree, but only return them if the type name should match
         // If the package happens to match, it is also a viable match
         if ((proc == null && packageMatches) || proc.getPrependLayerPackage() == packageMatches || packageMatches) {
            SrcEntry ent = new SrcEntry(this, path, relFilePath + "." + FileUtil.getExtension(path));
            ent.prependPackage = proc.getPrependLayerPackage();
            return ent;
         }
      }
      else if (!srcOnly && packageMatches) {
         relFilePath = subPath + ".class";
         res = findClassFile(relFilePath, false);
         if (res != null)
            return new SrcEntry(this, res.getPath(), relFilePath);
      }

      if (checkBaseLayers) {
         if (baseLayers != null) {
            for (Layer baseLayer:baseLayers) {
               SrcEntry baseEnt = baseLayer.getSrcFileFromRelativeTypeName(relDir, subPath, pkgPrefix, srcOnly, true);
               if (baseEnt != null)
                  return baseEnt;
            }
         }
      }
      return null;
   }
}

