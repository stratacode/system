/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.classfile.CFClass;
import sc.db.DBDataSource;
import sc.dyn.DynUtil;
import sc.dyn.IDynObject;
import sc.lang.*;
import sc.lang.sc.IScopeProcessor;
import sc.lang.sc.SCModel;
import sc.lang.sc.PropertyAssignment;
import sc.lifecycle.ILifecycle;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.java.*;
import sc.obj.*;
import sc.parser.Language;
import sc.parser.ParseUtil;
import sc.repos.*;
import sc.sync.SyncManager;
import sc.type.CTypeUtil;
import sc.type.RTypeUtil;
import sc.type.TypeUtil;
import sc.util.*;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static sc.layer.LayerUtil.opAppend;

/** 
 * The main implementation class for a Layer.  There's one instance of this class for
 * a given layer in a given LayeredSystem.  The LayeredSystem stores the current list of layers
 * that are active in the system in it's layers property for when it's used to model a set of layers
 * to build+run. When the LayeredSystem is used in the IDE, it maintains a list of inactiveLayers that
 * are also kept in a sorted order based on dependencies.
 * <p>
 * To create a new layer, add a layer definition file, placed in the layer's directory in your
 * system's layerpath (e.g. /home/StrataCode/bundles/example/example/unitConverter/clientServer/clientServer.sc)
 * </p>
 * <p>
 * At startup time, the LayeredSystem creates a Layer instance for each layer definition file.
 * It sorts the list of layers based on dependencies.  It initializes all of the layers by calling
 * tbe initialize method in the layer definition file.  It then starts all Layers by calling the 
 * start method.  The layer definition file can define new build properties, or instances of layered components to define
 * new features in the project. Add compiled libraries to the classPath or additional source directories to the source path.
 * Define new file types or a MvnRepositoryPackage component to automatically download and include a specific 3rd party package.
 * This component chooses a specific version that can be easily overridden by a downstream layer.
 * </p>
 * <p>
 * Even more control can be obtained by adding code to the init or start methods in the Layer definition component.
 * To use layers in the IDE, make sure not to start processes unless the 'activated' flag is true.
 * </p>
 */
@CompilerSettings(dynChildManager="sc.layer.LayerDynChildManager")
public class Layer implements ILifecycle, LayerConstants, IDynObject {
   public final static Layer ANY_LAYER = new Layer();
   public final static Layer ANY_INACTIVE_LAYER = new Layer();
   public final static Layer ANY_OPEN_INACTIVE_LAYER = new Layer();
   static {
      ANY_INACTIVE_LAYER.activated = false;
      ANY_OPEN_INACTIVE_LAYER.activated = false;
   }

   public final static String INVALID_LAYER_PATH = "invalid-layer-file";

   DynObject dynObj;

   /** The list of child components - e.g. file processors, repository packages, that are defined for this layer. */
   ArrayList<Object> children;

   /** The name of the layer used to find it in the layer path dot separated, e.g. groupName.dirName */
   @Constant public String layerDirName;

   public LayeredSystem layeredSystem;

   public ILanguageModel model;

   /** Contains the list of layers this layer extends */
   @Constant public List<String> baseLayerNames;

   public List<Layer> baseLayers;

   private boolean initialized = false;

   public boolean started = false;
   public boolean validated = false;

   boolean processed = false;

   /** Is this a compiled or a dynamic layer */
   @Constant
   public boolean dynamic;

   /** Set to true when this layer is removed from the system */
   public boolean removed = false;

   /** Set to false for layers which are not part of the running application */
   public boolean activated = true;

   /** Set to true when this layer should not be started for whatever reason. */
   public boolean disabled = false;

   /** Set to true for a baseLayer which has been excluded from this runtime */
   public boolean excluded = false;

   /** Set to true for any layers which should be compiled individually.   Set to true when buildSeparate = true, this is the buildLayer (the last layer), or the layer was previous built */
   public boolean buildLayer = false;

   /** Set to true for layers who want to show all objects and properties visible in their extended layers */
   @Constant
   public boolean transparent = false;

   /** For inactive layers, we can remove a layer from participating in the type system by marking it as closed. */
   public boolean closed = false;

   /**
    * Set this to true so that a given layer is compiled by itself - i.e. its build will not include source or
    * classes for any layers which it extends.  In that case, those layers will have to be built separately
    * before this layer can be run.
    */
   public boolean buildSeparate;

   /** The integer position of the layer in the list of layers */
   @Constant
   public int layerPosition;

   /** Any set of dependent classes code in this layer requires */
   public String classPath;

   /** Stores classPath entries added to this layer to avoid duplicates */
   public Set<String> classPathCache = null;

   /** Any set of dependent classes code in this layer requires which cannot be loaded into the normal ClassLoader */
   public String externalClassPath;

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

   /**
    * True if this layer configures it's base layers as it's primary goal.  It affects how layers
    * are sorted, so that this layer is placed as close to it's base layers as dependencies will allow.
    */
   public boolean configLayer;

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

   /** Set of patterns to ignore in any layer src or class directory, using Java's regex language */
   public List<String> excludedFiles = new ArrayList<String>(Arrays.asList(".git", ".*[\\(\\);@#$%\\^].*"));

   private List<Pattern> excludedPatterns; // Computed from the above

   /** Normalized paths relative to the layer directory that are excluded from processing */
   public List<String> excludedPaths = new ArrayList<String>(Arrays.asList(LayerConstants.DYN_BUILD_DIRECTORY, LayerConstants.BUILD_DIRECTORY, LayerConstants.SC_DIR, "out", "bin", "lib", "build-save"));

   /** Set of src-file pathnames to which are included in the src cache but not processed */
   public List<String> skipStartPaths = new ArrayList<String>();

   /** Set of patterns to ignore in any layer src or class directory, using Java's regex language */
   public List<String> skipStartFiles; // = new ArrayList<String>(Arrays.asList(".*.sctd"));  TODO: Remove all of the skipStart stuff - pretty sure it's not used anymore

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
   /* Same as exportRuntime but for the process name */
   public boolean exportProcess = true;
   /**
    * Controls whether we inherit the runtime dependencies of our base classes.  By default, if you extend a layer that
    * only runs in Javascript or Java your layer will only run in Java or JS.  If you set this flag to false, it can
    * run on any runtime.
    */
   public boolean inheritRuntime = true;
   /** Same as inheritRuntime but for the process name */
   public boolean inheritProcess = true;

   public List<String> excludeRuntimes = null;
   public List<String> includeRuntimes = null;

   public List<String> excludeProcesses = null;
   public List<String> includeProcesses = null;

   public boolean hasDefinedRuntime = false;
   public IRuntimeProcessor definedRuntime = null;
   String definedRuntimeName;

   public boolean hasDefinedProcess = false;
   public IProcessDefinition definedProcess = null;
   String definedProcessName;

   public DBDataSource defaultDataSource;

   /**
    * Set this to true for the layer to be included in the 'initialization runtime' - the server runtime when using client/server or the only runtime
    * in a single-process configuration
    */
   public boolean includeForInit = false;

   /** Enable or disable the default sync mode for types which are defined in this layer. TODO - deprecated.  Use @Sync on the layer */
   public SyncMode defaultSyncMode = SyncMode.Disabled;

   public SyncMode syncMode = null;

   /**
    * Set this to true to disallow modification to any types defined in this layer from upstream layers.  In other words, when a layer is final, it's the last layer to modify those types.
    * FinalLayer also makes it illegal to modify any types that a finalLayer type depends on so be careful in how you use it.   When you build a finalLayer separately, upstream layers can
    * load their class files directly, since they can't be changed by upstream layers.  This makes compiling makes must faster.
    */
   public boolean finalLayer = false;

   // For build layers, while the layer is being build this stores the build state - changed files, etc.
   BuildState buildState, lastBuildState;

   /** Set to true when this layer has had all changed files detected.  If it's not changed, we will load it as source */
   public boolean changedModelsDetected = false;

   private ArrayList<String> modifiedLayerNames;

   private ArrayList<String> replacesLayerNames;
   private ArrayList<Layer> replacedByLayers;

   /** Set to true for a given build layer which needs to build all files */
   public boolean buildAllFiles = false;

   /** Trace build src index operations to track generated file build process */
   public static boolean traceBuildSrcIndex = false;

   /** The list of third party packages to be installed or updated for this layer */
   public List<RepositoryPackage> repositoryPackages = null;

   public boolean errorsStarting = false;

   LayerTypeIndex layerTypeIndex = null;

   /** Keep track of new extensions added that correspond to files in this layer which must be indexed */
   private String[] langExtensions = null;

   /** Used to order the loading of layers in the layer type index.  We need to load it from the bottom up to be sure all extended types have been loaded before we load subsequent layers. */
   boolean layerTypesStarted = false;

   /** True if we've restored the complete layer type index */
   boolean typeIndexRestored = false;

   /** True if we've restored the type index, then updated it and so the saved version is stale */
   boolean typeIndexNeedsSave = false;
   /** True if we need to update the last modified time of the type index file. */
   long typeIndexFileLastModified = -1;

   /** If a Java file uses no extensions, we can either compile it from the source dir or copy it to the build dir */
   public boolean copyPlainJavaFiles = true;

   /** Set by the system to the full path to the directory containing LayerName.sc (layerFileName = layerPathName + layerBaseName + ".sc") (or INVALID_LAYER_FILE if we do not have a valid path) */
   public @Constant String layerPathName;

   /** Just the LayerName.sc part */
   @Constant public String layerBaseName;

   /** The unique name of the layer - prefix + dirName, e.g. sc.util.util */
   @Constant public String layerUniqueName;

   // Cached list of top level src directories - usually just the layer's directory path
   private List<String> topLevelSrcDirs;

   /** parallel list to topLevelSrcDirs that specifies the 'rootName' to identify a source root outside of the layer dir with a simple name */
   private List<String> srcRootNames = null;

   /**
    * Layers can call addSrcPath to register a source path directory that supports file types, and has a buildPrefix - for where
    * any files generated from the source files are place.
    */
   private List<SrcPathType> srcPathTypes;

   // Cached list of all directories in this layer that contain source
   private List<String> srcDirs = new ArrayList<String>();
   private List<String> srcDirRootNames = null; // If there are multiple external source roots, a parallel array to srcDirs that stores the names of the root dir for each srcDir in the tree

   private Map<String, TreeSet<String>> relSrcIndex = new TreeMap<String, TreeSet<String>>();

   // A cached index of the relative file names to File objects so we can quickly resolve whether a file is
   // there or not.  Also, this helps get the canonical case for the file name.
   HashMap<String,File> srcDirCache = new HashMap<String,File>();
   // Similar to the srcDirCache but stores non-parsed files, e.g. testScript.scr.  Used so we can quickly find the previous file of the same name in
   // the base layer.
   HashMap<String,String> layerFileCache = null;

   // TODO: right now this only stores ZipFiles in the preCompiledSrcPath.  We can't support zip entries for other than parsing models.
   // Stores a list of zip files which are in the srcDirCache for this layer.  Note that this may store src files which are not actually processed by this layer.
   ArrayList<ZipFile> srcDirZipFiles;

   public HashMap<String,SrcIndexEntry> buildSrcIndex;

   /** Stores the set of models in this layer, in the order in which we traversed the tree for consistency. */
   Set<IdentityWrapper<ILanguageModel>> layerModels = Collections.synchronizedSet(new LinkedHashSet<IdentityWrapper<ILanguageModel>>());

   List<String> classDirs;

   List<String> externalClassDirs;

   // Parallel to classDirs - caches zips/jars
   private ZipFile[] zipFiles;

   private ZipFile[] externalZipFiles;

   /** Set to true if a base layer or start method failed to start */
   public boolean initFailed = false;

   public boolean initCompleted = false; // For debug - possibly remove this but to help track down errors when the layer failed to initialize due to an IDE's cancelled exception

   private long lastModified = 0;

   public boolean needsIndexRefresh = false;   // If you generate files into the srcPath set this to true so they get picked up

   /** Set to true for layers that need to be saved the first time they are needed */
   public boolean tempLayer = false;

   public boolean indexLayer = false;

   /** Each build layer has a buildInfo which stores global project info for that layer */
   public BuildInfo buildInfo;

   /** Caches the typeNames of all dynamic types built in this build layer (if any) */
   public HashSet<String> dynTypeIndex = null;

   @Constant
   public CodeType codeType = CodeType.Application;

   boolean newLayer = false;   // Set to true for layers which are created fresh

   boolean buildSrcIndexNeedsSave = false; // TODO: performance - this gets saved way too often now right - need to implement this flag

   public HashMap<String, IScopeProcessor> scopeProcessors = null;

   private TreeMap<String,String> scopeAliases = null;

   public HashMap<String,IAnnotationProcessor> annotationProcessors = null;

   private ArrayList<ReplacedType> replacedTypes;

   /** Does this layer have any source files associated with it */
   private boolean hasSrc = true;

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

   /**
    * For fine-grained control over layer ordering, a downstream layer can mark that it replaces an update stream layer.
    * This is always going to be a layer which the downstream layer already extends.  When determining layer ordering, if a third layer
    * extends the upstream layer, it will also follow the downstream layer which replaces that upstream layer.
    * For example, if html.schtml replaces html.core, and you extend html.core, you'll be guaranteed to follow html.schtml in the layer list.
    *
    * Why this is necessary?  In most cases, you explicitly depend upon a feature of another layer so you usually extend the layers you depend upon.
    * But if another framework layer modifies that feature, and it follows your layer in the stack, supporting all of the invalidation and rebuilds necessary
    * so you end up recompiling all affected files is too much work.
    * The tagPackageList is an example of a feature that does not always respect the strict layering due to it's flexibility.  So classes put into that list
    * may need to replace the layer which defined the tagPackage entry.
    *
    * Essentially, your application layer may need to inherit a dependency on the last layer which replaces the feature to work properly.
    */
   public void replacesLayer(String otherLayerName) {
      if (replacesLayerNames == null)
         replacesLayerNames = new ArrayList<String>();
      replacesLayerNames.add(otherLayerName);
   }

   public void excludeRuntimes(String... names) {
      for (String name:names)
         excludeRuntime(name);
   }

   public void excludeRuntime(String name) {
      if (excludeRuntimes == null)
         excludeRuntimes = new ArrayList<String>();
      excludeRuntimes.add(name);
   }

   public void includeRuntimes(String... names) {
      for (String name:names)
         includeRuntime(name);
   }

   public void includeRuntime(String name) {
      if (includeRuntimes == null)
         includeRuntimes = new ArrayList<String>();
      includeRuntimes.add(name);
   }

   public void excludeProcesses(String... names) {
      for (String name:names)
         excludeProcess(name);
   }

   public void excludeProcess(String name) {
      if (excludeProcesses == null)
         excludeProcesses = new ArrayList<String>();
      excludeProcesses.add(name);
   }

   public void includeProcesses(String... names) {
      for (String name:names)
         includeProcess(name);
   }

   public void includeProcess(String name) {
      if (includeProcesses == null)
         includeProcesses = new ArrayList<String>();
      includeProcesses.add(name);
   }

   public void setLayerRuntime(IRuntimeProcessor proc) {
      definedRuntime = proc;
      hasDefinedRuntime = true;
      inheritRuntime = false;
      inheritProcess = false; // gwt.lib sets an explicit runtime to 'gwt' that should override both the Java_Server runtime and process it inherits by extending the servlet stuff.
   }

   public void setLayerProcess(IProcessDefinition proc) {
      definedProcess = proc;
      hasDefinedProcess = true;
      inheritProcess = false;
   }

   /** Creates a new java runtime which is incompatible with the standard Java runtime */
   public void createDefaultRuntime(String runtimeName, boolean useContextClassLoader) {
      LayeredSystem.createDefaultRuntime(this, runtimeName, useContextClassLoader);
      setLayerRuntime(LayeredSystem.getRuntime(runtimeName));
   }

   /** Called from the layer definition file to register a new runtime required by this layer.  */
   public void addRuntime(IRuntimeProcessor proc) {
      setLayerRuntime(proc);
      LayeredSystem.addRuntime(this, proc);
   }

   public void addProcess(IProcessDefinition proc) {
      setLayerProcess(proc);
      LayeredSystem.addProcess(this, proc);
   }

   /** Some layers do not extend a layer bound to a runtime platform and so can run in any layer. */
   public boolean getAllowedInAnyRuntime() {
      if (hasDefinedRuntime)
         return false;

      if (excludeRuntimes != null && excludeRuntimes.size() > 0)
         return false;

      if (includeRuntimes != null && includeRuntimes.size() > 0)
         return false;

      if (baseLayers != null && inheritRuntime) {
         for (int i = 0; i < baseLayers.size(); i++) {
            Layer base = baseLayers.get(i);
            if (base.exportRuntime && !base.getAllowedInAnyRuntime())
               return false;
         }
      }

      if (includeForInit)
         return false;
      return true;
   }

   public boolean getAllowedInAnyProcess() {
      if (!getAllowedInAnyRuntime())
         return false;

      if (hasDefinedProcess)
         return false;

      if (excludeProcesses != null && excludeProcesses.size() > 0)
         return false;

      if (includeProcesses != null && includeProcesses.size() > 0)
         return false;

      if (baseLayers != null) {
          if (inheritProcess) {
             for (int i = 0; i < baseLayers.size(); i++) {
                Layer base = baseLayers.get(i);
                if ((base.exportProcess && !base.getAllowedInAnyProcess()))
                   return false;
             }
          }
          if (inheritRuntime) {
              for (int i = 0; i < baseLayers.size(); i++) {
                  Layer base = baseLayers.get(i);
                  if ((base.exportRuntime && !base.getAllowedInAnyRuntime()))
                      return false;
              }

          }
      }
      if (includeForInit)
         return false;
      return true;
   }

   /** Use this method to decide whether to build a given assets.  The build layer accumulates all assets but when we do intermediate builds, we only include the layers which are directly in the extension graph. */
   public boolean buildsLayer(Layer layer) {
      return this == layeredSystem.buildLayer || this == layer || extendsLayer(layer);
   }

   public SrcEntry getInheritedSrcFileFromTypeName(String typeName, boolean srcOnly, boolean prependPackage, String subPath, IProcessDefinition proc, boolean layerResolve) {
      if (excludeForProcess(proc) || excluded)
         return null;
      SrcEntry res = getSrcFileFromTypeName(typeName, srcOnly, prependPackage, subPath, layerResolve);
      if (res != null)
         return res;

      return getBaseLayerSrcFileFromTypeName(typeName, srcOnly, prependPackage, subPath, proc, layerResolve);
   }

   public SrcEntry getBaseLayerSrcFileFromTypeName(String typeName, boolean srcOnly, boolean prependPackage, String subPath, IProcessDefinition proc, boolean layerResolve) {
      SrcEntry res = null;
      if (baseLayers != null) {
         ArrayList<Layer> sortedBaseLayers = new ArrayList<Layer>(baseLayers);
         // Sort the base layers based on the reverse of their position in the layers list in case they were extended in the wrong order
         Collections.sort(sortedBaseLayers, new Comparator<Layer>() {
            @Override
            public int compare(Layer o1, Layer o2) {
               return -Integer.compare(o1.layerPosition, o2.layerPosition);
            }
         });
         SrcEntry lastRes = null;
         for (Layer base:sortedBaseLayers) {
            res = base.getInheritedSrcFileFromTypeName(typeName, srcOnly, prependPackage, subPath, proc, layerResolve);
            if (res != null) {
               // Since we sorted our dependencies, if we find a type in the layer itself just return it. But if we
               // find an inherited type, need to figure out which one is the most specific.
               if (res.layer == base)
                  return res;
               if (lastRes == null)
                  lastRes = res;
               else if (lastRes.layer.layerPosition < res.layer.layerPosition)
                  lastRes = res;
            }
         }
         return lastRes;
      }
      return res;
   }

   public SrcEntry getSrcFileFromTypeName(String typeName, boolean srcOnly, boolean prependPackage, String subPath, boolean layerResolve) {
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

      SrcEntry srcEnt = findSrcEntry(relFilePath, prependPackage, layerResolve);
      if (srcEnt != null)
         return srcEnt;
      else if (!srcOnly) {
         relFilePath = subPath + ".class";
         File res = findClassFile(relFilePath, false);
         if (res != null)
            return new SrcEntry(this, res.getPath(), relFilePath, prependPackage, null);
      }
      return null;
   }

   public boolean excludeForRuntime(IRuntimeProcessor proc) {
      LayerEnabledState layerState = isExplicitlyEnabledForRuntime(proc, true);
      if (layerState == LayerEnabledState.Disabled) {
         return true;
      }
      if (layerState == LayerEnabledState.NotSet) {
         return false;
      }
      return false;
   }

   public boolean includeForRuntime(IRuntimeProcessor proc) {
      LayerEnabledState layerState = isExplicitlyEnabledForRuntime(proc, true);
      if (layerState == LayerEnabledState.Enabled) {
         return true;
      }
      if (layerState == LayerEnabledState.NotSet) {
         return true;
      }
      return false;
   }

   public boolean excludeForProcess(IProcessDefinition proc) {
      IRuntimeProcessor rtProc = proc == null ? null : proc.getRuntimeProcessor();

      LayerEnabledState layerState = isExplicitlyEnabledForProcess(proc, true, true);
      if (layerState == LayerEnabledState.Disabled) {
         return true;
      }
      if (layerState == LayerEnabledState.Enabled)
         return false;
      if (excludeForRuntime(rtProc))
         return true;
      // If we are not prohibited from the process we are not considered excluded.
      return false;
   }

   public boolean includeForProcess(IProcessDefinition proc) {
      IRuntimeProcessor rtProc = proc == null ? null : proc.getRuntimeProcessor();
      LayerEnabledState layerState = isExplicitlyEnabledForProcess(proc, true, true);
      if (layerState == LayerEnabledState.Enabled) {
         return true;
      }

      if (layerState == LayerEnabledState.NotSet) {
         return true;
      }
      return false;
   }

   public IAnnotationProcessor getAnnotationProcessor(String annotName, boolean checkBaseLayers) {
      IAnnotationProcessor proc;
      if (annotationProcessors != null) {
         proc = annotationProcessors.get(annotName);
         if (proc != null)
            return proc;
      }
      if (baseLayers != null && checkBaseLayers) {
         for (Layer baseLayer:baseLayers) {
            proc = baseLayer.getAnnotationProcessor(annotName, true);
            if (proc != null)
               return proc;
         }
      }
      return null;
   }

   public IScopeProcessor getScopeProcessor(String annotName, boolean checkBaseLayers) {
      IScopeProcessor proc;
      if (scopeProcessors != null) {
         proc = scopeProcessors.get(annotName);
         if (proc != null)
            return proc;
      }
      if (scopeAliases != null && annotName != null) {
         String aliasName = scopeAliases.get(annotName);
         if (aliasName != null) {
            proc = layeredSystem.getScopeProcessor(this, aliasName);
            if (proc != null)
               return proc;
         }
      }
      if (baseLayers != null && checkBaseLayers) {
         for (Layer baseLayer:baseLayers) {
            proc = baseLayer.getScopeProcessor(annotName, true);
            if (proc != null)
               return proc;
         }
      }
      return null;
   }

   public String getScopeAlias(String scopeName, boolean checkBaseLayers) {
      if (scopeAliases != null) {
         String aliasName = scopeAliases.get(scopeName);
         if (aliasName != null) {
            return aliasName;
         }
      }
      if (baseLayers != null && checkBaseLayers) {
         for (Layer baseLayer:baseLayers) {
            String alias = baseLayer.getScopeAlias(scopeName, true);
            if (alias != null)
               return alias;
         }
      }
      return null;
   }

   public ArrayList<IDefinitionProcessor> addInheritedAnnotationProcessors(BodyTypeDeclaration type, ArrayList<IDefinitionProcessor> res, boolean checkBaseLayers) {
      if (annotationProcessors != null) {
         for (Map.Entry<String,IAnnotationProcessor> procEnt:annotationProcessors.entrySet()) {
            String annotTypeName = procEnt.getKey();
            IAnnotationProcessor proc = procEnt.getValue();
            if (proc.getInherited()) {
               Object annot = type.getInheritedAnnotation(annotTypeName);
               if (annot != null) {
                  if (res == null)
                     res = new ArrayList<IDefinitionProcessor>();
                  res.add(proc);
               }
            }
         }
      }
      if (baseLayers != null && checkBaseLayers) {
         for (Layer baseLayer:baseLayers) {
            res = baseLayer.addInheritedAnnotationProcessors(type, res, true);
         }
      }
      return res;
   }

   void initLayerModel(JavaModel model, LayerParamInfo lpi, String layerDirName, boolean inLayerDir, boolean baseIsDynamic, boolean markDyn) {
      TypeDeclaration modelType = model.getModelTypeDeclaration();
      // When a layer has buildSeparate it means we need to build it in order to resolve the types of the other layers.
      // Those layers always need to be built, even for the IDE so we can use the generated classes to resolve types of
      // the rest of the source.
      activated = lpi.activate;
      if (!lpi.enabled) {
         disabled = true;
      }
      imports = model.getImports();
      this.model = model;
      this.layerDirName = layerDirName == null ? (inLayerDir ? "." : null) : layerDirName.replace('/', '.');
      dynamic = !layeredSystem.getCompiledOnly() && !compiledOnly && (baseIsDynamic || modelType.hasModifier("dynamic") || markDyn ||
              (lpi.explicitDynLayers != null && (layerDirName != null && (lpi.explicitDynLayers.contains(layerDirName) || lpi.explicitDynLayers.contains("<all>")))));

      if (!liveDynamicTypes && dynamic && activated && !disabled && !excluded)
         liveDynamicTypes = true;

      //if (options.verbose && markDyn && layer.compiledOnly) {
      //   System.out.println("Compiling layer: " + layer.toString() + " with compiledOnly = true");
      //}

      if (modelType.hasModifier("public"))
         defaultModifier = "public";
      else if (modelType.hasModifier("private"))
         defaultModifier = "private";

      if (packagePrefix == null) {
         packagePrefix = "";
      }
      Object syncAnnot = ModelUtil.getLayerAnnotation(this, "sc.obj.Sync");
      if (syncAnnot != null) {
         syncMode = (SyncMode) ModelUtil.getAnnotationValue(syncAnnot, "syncMode");
         if (syncMode == null)
            syncMode = SyncMode.Enabled;
      }
      else // TODO: remove defaultSyncMode -  use the annotation instead
         syncMode = defaultSyncMode;

      if (definedRuntime != null)
         definedRuntimeName = definedRuntime.getRuntimeName();
      if (definedProcess != null)
         definedProcessName = definedProcess.getProcessName();
   }

   public void updateTypeIndex(TypeIndexEntry typeIndexEntry, long lastModified) {
      if (typeIndexEntry == null)
         return;
      String typeName = typeIndexEntry.typeName;
      if (typeName != null) {
         if (layerTypeIndex == null) {
            // Here we will be creating the layer model as part of creating the layer.  Don't put the layer into the type index until it's been restored.
            if (typeIndexEntry.isLayerType) {
               return;
            }
            if (!initialized)
               System.out.println("*** Error - updating layer type index before the layer has been initialized");
            layerTypeIndex = new LayerTypeIndex();
         }
         boolean entryChanged = false;
         boolean invalidateTypeNames = false;
         TypeIndexEntry oldTypeEnt = layerTypeIndex.layerTypeIndex.put(typeName, typeIndexEntry);
         // We can only have one type per file name so don't also register inner types here
         if (!typeIndexEntry.isInnerType)
            layerTypeIndex.fileIndex.put(typeIndexEntry.fileName, typeIndexEntry);
         if (typeIndexRestored) {
            if (oldTypeEnt == null || !oldTypeEnt.equals(typeIndexEntry)) {
               entryChanged = true;
               invalidateTypeNames = oldTypeEnt == null || oldTypeEnt.namesChanged(typeIndexEntry);
               if (layeredSystem.options.verbose && !typeIndexNeedsSave) {
                  verbose("Type index for layer: " + layerDirName + " needs save - first changed type: " + typeName);
               }
               typeIndexNeedsSave = true;
            }
            else if (lastModified > typeIndexFileLastModified)
               typeIndexFileLastModified = lastModified;
         }

         SysTypeIndex sysIndex = layeredSystem.typeIndex;
         if (sysIndex != null && entryChanged) {
            LayerListTypeIndex useTypeIndex = activated ? sysIndex.activeTypeIndex : sysIndex.inactiveTypeIndex;
            useTypeIndex.layerTypeIndexChanged(invalidateTypeNames, typeName, typeIndexEntry);
         }
      }
   }

   public TypeIndexEntry getTypeIndexEntry(String typeName) {
      if (layerTypeIndex != null)
         return layerTypeIndex.layerTypeIndex.get(typeName);
      return null;
   }

   // TODO: Note - there could potentially be multiple returns here - say 'desktop' and 'server' but I'm not sure we'll ever need two different 'java' runtimes activated at the same time
   public TypeDeclaration getActivatedType(IRuntimeProcessor proc, String typeName) {
      String layerName = getLayerName();
      TypeDeclaration resType;
      if (DefaultRuntimeProcessor.compareRuntimes(layeredSystem.runtimeProcessor, proc)) {
         resType = layeredSystem.getActivatedType(proc, layerName, typeName);
         if (resType != null)
            return resType;
      }

      for (LayeredSystem peerSys : layeredSystem.peerSystems) {
         resType = peerSys.getActivatedType(proc, layerName, typeName);
         if (resType != null)
            return resType;
      }
      return null;
   }

   public boolean getBaseLayerDisabled() {
      if (disabled)
         return true;
      if (baseLayers != null) {
         for (Layer baseLayer:baseLayers)
            if (baseLayer.getBaseLayerDisabled())
               return true;
      }
      return false;
   }

   /** If getBaseLayerDisabled returns true, this method returns the name of the first layer it encounters which is disabled (for diagnostic purposes). */
   public String getDisabledLayerName() {
      if (disabled)
         return getLayerName();
      if (baseLayers != null) {
         for (Layer baseLayer:baseLayers) {
            String disabledName = baseLayer.getDisabledLayerName();
            if (disabledName != null)
               return disabledName;
         }
      }
      return null;
   }

   /** Adds the top level src directories for  */
   public void addBuildDirs(BuildState bd) {
      if (topLevelSrcDirs == null || topLevelSrcDirs.size() == 0) {
         warn("No srcPath entries for layer: ", this.toString());
      }
      else {
         TreeSet<String> srcDirNames = new TreeSet<String>();
         int ct = 0;
         for (String topLevelSrcDir : topLevelSrcDirs) {
            String srcRootName = null;
            if (!FileUtil.isAbsolutePath(topLevelSrcDir))
               topLevelSrcDir = FileUtil.concat(layerPathName, topLevelSrcDir);
            else {
               if (!topLevelSrcDir.startsWith(layerPathName)) {
                  String dirName = FileUtil.getFileName(topLevelSrcDir);
                  String srcName = dirName;
                  int ix = 1;
                  while (srcDirNames.contains(srcName))
                     srcName = dirName+(ix++);
                  srcRootName = srcName;
                  srcDirNames.add(srcRootName);
               }
            }
            SrcEntry newSrcEnt = new SrcEntry(this, topLevelSrcDir, "", "", srcRootName);
            bd.addSrcEntry(-1, newSrcEnt);
            if (srcRootName != null) {
               if (srcRootNames == null) {
                  srcRootNames = new ArrayList<String>();
                  for (int j = 0; j < ct; j++)
                     srcRootNames.add(null);
               }
               srcRootNames.add(srcRootName);
            }
            ct++;
         }
      }
   }

   public boolean definesProperty(String propName) {
      initDynObj();
      if (dynObj != null) {
         if (dynObj.getDynType().isDynProperty(propName)) {
            return true;
         }
      }
      return false;
      //return TypeUtil.getPropertyMapping(Layer.class, propName) != null;
   }

   public boolean hasProperty(String propName) {
      if (definesProperty(propName))
         return true;
      if (baseLayers != null) {
         for (Layer baseLayer:baseLayers)
            if (baseLayer.hasProperty(propName))
               return true;
      }
      return false;
   }

   @Override
   public Object getProperty(String propName, boolean getField) {
      initDynObj();
      if (dynObj != null) {
         if (definesProperty(propName)) {
            return dynObj.getPropertyFromWrapper(this, propName, getField);
         }
         LayeredSystem sys = layeredSystem;
         // We use the current build layer to represent all layers in the current process and so should resolve any
         // properties even if we don't directly extend the base layer that defines in.
         if (activated && sys != null && sys.currentBuildLayer == this) {
            List<Layer> layersList = getLayersList();
            for (int i = getLayerPosition() - 1; i >= 0; i--) {
               Layer depLayer = layersList.get(i);
               if (depLayer.hasProperty(propName))
                  return depLayer.getProperty(propName, getField);
            }
         }
         else if (baseLayers != null) {
            for (Layer base:baseLayers) {
               if (base.hasProperty(propName))
                  return base.getProperty(propName, getField);
            }
         }
      }
      return TypeUtil.getPropertyValueFromName(this, propName, getField);
   }

   public Object getProperty(int propIndex, boolean getField) {
      initDynObj();
      if (dynObj == null)
         return null;
      return dynObj.getPropertyFromWrapper(this, propIndex, getField);
   }

   public <_TPROP> _TPROP getTypedProperty(String propName, Class<_TPROP> propType) {
      initDynObj();
      if (dynObj == null)
         return null;
      return (_TPROP) dynObj.getPropertyFromWrapper(this, propName, false);
   }
   public void addProperty(Object propType, String propName, Object initValue) {
      initDynObj();
      dynObj.addProperty(propType, propName, initValue);
   }

   public void setProperty(String propName, Object value, boolean setField) {
      initDynObj();
      if (dynObj == null)
         TypeUtil.setPropertyFromName(this, propName, value);
      else if (definesProperty(propName))
         dynObj.setPropertyFromWrapper(this, propName, value, setField);
      else {
         if (baseLayers != null) {
            for (Layer base : baseLayers) {
               if (base.hasProperty(propName)) {
                  base.setProperty(propName, value, setField);
                  return;
               }
            }
         }
         TypeUtil.setPropertyFromName(this, propName, value);
      }
   }
   public void setProperty(int propIndex, Object value, boolean setField) {
      initDynObj();
      if (dynObj == null) {
         if (propIndex == DynObject.OUTER_INSTANCE_SLOT) {
            // In this case parentNode should equal value.  It happens when we create a compiled DOM node class via the
            // dynamic runtime.  In this case, the parent node has already been defined via the compiled runtime.
            return;
         }
         else
            throw new IllegalArgumentException("No dynamic property: " + propIndex);
      }
      dynObj.setProperty(propIndex, value, setField);
   }
   public Object invoke(String methodName, String paramSig, Object... args) {
      initDynObj();
      return dynObj.invokeFromWrapper(this, methodName, paramSig, args);
   }
   public Object invoke(int methodIndex, Object... args) {
      initDynObj();
      return dynObj.invokeFromWrapper(this, methodIndex, args);
   }

   @Override
   public Object getDynType() {
      return model == null ? Layer.class : model.getModelTypeDeclaration();
   }

   @Override
   public void setDynType(Object type) {
      initDynObj();
      if (dynObj != null)
         dynObj.setTypeFromWrapper(this, type);
   }

   @Override
   public boolean hasDynObject() {
      return model != null;
   }

   /** Disable indexing of the layer's src directory */
   public void setHasSrc(boolean val) {
      hasSrc = val;
      if (!val)
         topLevelSrcDirs = Collections.EMPTY_LIST;
   }

   public enum LayerEnabledState {
      Enabled, Disabled, NotSet
   }

   public LayerEnabledState isExplicitlyEnabledForRuntime(IRuntimeProcessor proc, boolean checkPeers) {
      if (includeForInit) {
         if (LayeredSystem.isInitRuntime(proc))
            return LayerEnabledState.Enabled;
         else
            return LayerEnabledState.Disabled;
      }
      // If this layer explicitly defines a runtime, it's clearly enabled for that runtime
      if (hasDefinedRuntime) {
         if (proc == definedRuntime)
            return LayerEnabledState.Enabled;
         else
            return LayerEnabledState.Disabled;
      }

      // If it explicitly excludes the runtime, then it's excluded clearly.  If it excludes another runtime, it's also explicitly included for this runtime.
      String runtimeName = proc == null ? IRuntimeProcessor.DEFAULT_RUNTIME_NAME : proc.getRuntimeName();
      if (excludeRuntimes != null) {
         if (excludeRuntimes.contains(runtimeName))
            return LayerEnabledState.Disabled;
         else
            return LayerEnabledState.Enabled;
      }

      if (includeRuntimes != null) {
         if (includeRuntimes.contains(runtimeName))
            return LayerEnabledState.Enabled;
         else
            return LayerEnabledState.Disabled;
      }

      if (baseLayers != null && inheritRuntime && checkPeers) {
         LayerEnabledState baseState = LayerEnabledState.NotSet;
         for (int i = 0; i < baseLayers.size(); i++) {
            Layer base = baseLayers.get(i);
            if (!base.exportRuntime)
               continue;
            LayerEnabledState newBaseState = base.isExplicitlyEnabledForRuntime(proc, true);
            // If this layer extends any layer which is enabled, it is enabled.  So if you extend one layer that is disabled and
            // another that is enabled, you should be enabled.
            if (newBaseState == LayerEnabledState.Enabled)
               return newBaseState;
            else if (newBaseState == LayerEnabledState.Disabled)
               baseState = newBaseState;
         }
         // TODO: if you do not extend an explicitly enabled layer should we disable you if you extend an explicitly disabled layer here?
         if (baseState != LayerEnabledState.NotSet)
            return baseState;
      }

      return LayerEnabledState.NotSet;
   }

   public LayerEnabledState isExplicitlyEnabledForProcess(IProcessDefinition proc, boolean checkBaseLayers, boolean checkRuntime) {
      IRuntimeProcessor runtimeProc = proc == null ? null : proc.getRuntimeProcessor();

      // If this layer explicitly defines a runtime, it's clearly enabled for that runtime
      if (hasDefinedProcess) {
         if (ProcessDefinition.compare(proc, definedProcess)) {
            return LayerEnabledState.Enabled;
         }
         else
            return LayerEnabledState.Disabled;
      }

      // If it explicitly excludes the runtime, then it's excluded clearly.  If it excludes another runtime, it's also explicitly included for this runtime.
      String procName = proc == null ? IProcessDefinition.DEFAULT_PROCESS_NAME : proc.getProcessName();
      if (excludeProcesses != null) {
         if (excludeProcesses.contains(procName))
            return LayerEnabledState.Disabled;
      }

      if (includeProcesses != null) {
         if (includeProcesses.contains(procName))
            return LayerEnabledState.Enabled;
         else {
            // If we have includeRuntime("js") and includeProcess("Server") it should still allow any default layered system to match at the
            // runtime level and return Enabled, not Disabled here.
            if (StringUtil.equalStrings(procName, IProcessDefinition.DEFAULT_PROCESS_NAME) && includeRuntimes != null && checkRuntime && proc != null) {
               if (includeRuntimes.contains(proc.getRuntimeName()))
                  return LayerEnabledState.Enabled;
            }
            return LayerEnabledState.Disabled;
         }
      }
      LayerEnabledState baseState = LayerEnabledState.NotSet;
      boolean runtimeBaseState = false;
      boolean runtimeNewState;

      // Not set explicitly on the process, look for direct config on this layer for the runtime to include/reject it on that basis alone.
      if (checkRuntime) {
         LayerEnabledState runtimeState = isExplicitlyEnabledForRuntime(runtimeProc, false);
         if (runtimeState != LayerEnabledState.NotSet)
            return runtimeState;
      }
      else {
         baseState = isExplicitlyEnabledForRuntime(runtimeProc, false);
         runtimeBaseState = true;
      }

      /*
      LayerEnabledState runtimeState = isExplicitlyEnabledForRuntime(runtimeProc);
      if (runtimeState != LayerEnabledState.NotSet) {
         if (runtimeState == LayerEnabledState.Disabled)
            return runtimeState;
         else {
            if (!isExplicitlyDisabledForProcess(proc))
               return runtimeState;
         }
         return runtimeState;
      }
      */

      if (baseLayers != null && inheritProcess && checkBaseLayers) {
         for (int i = 0; i < baseLayers.size(); i++) {
            Layer base = baseLayers.get(i);
            if (!base.exportProcess)
               continue;
            LayerEnabledState newBaseState = base.isExplicitlyEnabledForProcess(proc, true, false);
            runtimeNewState = false;
            // If this layer extends any layer which is enabled, it is enabled.  So if you extend one layer that is disabled and
            // another that is enabled, you should be enabled.  This rule works at the process level only - we are not considering runtime rules yet.
            if (newBaseState == LayerEnabledState.Enabled)
               return newBaseState;
            // When a layer has no state for the process, check the runtime and use it's status
            else if (newBaseState == LayerEnabledState.NotSet && inheritRuntime && checkRuntime) {
               runtimeNewState = true;
               newBaseState = base.isExplicitlyEnabledForRuntime(runtimeProc, true);
            }

            // If the new state has a value for this base layer and either the current state is not set or...
            if (newBaseState != LayerEnabledState.NotSet &&
                    (baseState == LayerEnabledState.NotSet ||
                            // always override runtime with process state or if we are both runtime or both process, enabled trumps Disabled.
                            (!runtimeNewState && runtimeBaseState) || (/*runtimeNewState == runtimeBaseState && */ baseState == LayerEnabledState.Disabled && newBaseState == LayerEnabledState.Enabled))) {

               // Now the tricky case - should disabled at the process level trump runtime enabled - sometimes yes, sometimes no.
               if (baseState != LayerEnabledState.NotSet && baseState != newBaseState && newBaseState == LayerEnabledState.Disabled) {
                  // assert baseState = Enabled

                  // If the base is disabled at the process but enabled at the runtime, it means our runtime match was superceeded by the process mismatch.
                  if (runtimeBaseState && base.isExplicitlyEnabledForRuntime(runtimeProc, true) == LayerEnabledState.Enabled) {
                     baseState = newBaseState;
                     runtimeBaseState = runtimeNewState;
                  }
               }
               // Do not overwrite a process base state with one computed at the runtime level
               else if (!runtimeNewState || runtimeBaseState || baseState == LayerEnabledState.NotSet) {
                  baseState = newBaseState;
                  runtimeBaseState = runtimeNewState;
               }
            }
         }
         // This is some tricky logic to get right.  What's the state for a layer where there's no explicit process affinity but
         // there is affinity at the runtime level.
         if (baseState != LayerEnabledState.NotSet) {
            if (!runtimeBaseState || checkRuntime)
               return baseState;
            else if (baseState == LayerEnabledState.Disabled)
               return baseState;
            // If this proc has no explicit process defined we will accept that we are enabled for the runtime.
            else if (proc == null || proc.getProcessName() == IProcessDefinition.DEFAULT_PROCESS_NAME)
               return baseState;
         }
      }
      /*
      if (checkBaseLayers && checkRuntime && baseLayers != null) {
         LayerEnabledState baseState = LayerEnabledState.NotSet;
         for (int i = 0; i < baseLayers.size(); i++) {
            Layer base = baseLayers.get(i);
            if (!base.exportRuntime)
               continue;
            LayerEnabledState newBaseState = base.isExplicitlyEnabledForRuntime(runtimeProc, true);
            // If this layer extends any layer which is enabled, it is enabled.  So if you extend one layer that is disabled and
            // another that is enabled, you should be enabled.
            if (newBaseState == LayerEnabledState.Enabled)
               return newBaseState;

            // We extend a disabled layer so we are disabled by default unless we also extend an enabled layer
            if (newBaseState == LayerEnabledState.Disabled && baseState == LayerEnabledState.NotSet) {
               baseState = newBaseState; // Disabled
            }
         }
         // TODO: if you do not extend an explicitly enabled layer should we disable you if you extend an explicitly disabled layer here?
         if (baseState != LayerEnabledState.NotSet)
            return baseState;
      }
      */

      return LayerEnabledState.NotSet;
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

   public boolean isInitialized() {
      return initialized;
   }
   
   public boolean isStarted() {
      return started;
   }

   public boolean isValidated() {
      return validated;
   }
   public void validate() {
      if (validated)
         return;

      validated = true;

      if (excluded)
         return;

      if (baseLayers != null && !activated) {
         for (Layer baseLayer:baseLayers)
            baseLayer.ensureValidated(true);
      }

      if (scopeAliases != null) {
         for (Map.Entry<String,String> aliasEnt:scopeAliases.entrySet()) {
            String scopeName = aliasEnt.getValue();
            // It might be a custom scope or global or appGobal
            if (layeredSystem.getScopeProcessor(this, scopeName) == null && ScopeDefinition.getScopeByName(scopeName) == null) {
               error("No definition for scope: " + aliasEnt.getValue() + " to register alias: " + aliasEnt.getKey());
            }
         }
      }

      initImportCache();
      callLayerMethod("validate");
   }

   /** Called right before the code processing phase starts - an opportunity for the layer to generate a build file */
   public void process() {
      processed = true;
      callLayerMethod("process");
   }
   
   public boolean isProcessed() {
      return processed;
   }

   public void init() {
      if (initialized)
         return;

      // Initialize the children after all layer types have been merged in so the modifications have taken effect
      TypeDeclaration td;
      if (model != null && (td = model.getModelTypeDeclaration()) != null) {
         //ParseUtil.startComponent(model);

         try {
            // This will go and create all of the dynamic objects defined in this layer's definition file
            Object[] children = td.getObjChildren(this, null, false, true, true);
            if (children != null) {
               if (this.children == null)
                  this.children = new ArrayList<Object>(Arrays.asList(children));
               else
                  this.children.addAll(Arrays.asList(children));

               // Because all LayerComponent types do not have a DynChildManager, the children are not created when the parent is
               // created.  So we need to go through and just access the children here to lazily create them.  This handles nested
               // RepositoryPackages like those used for the ticketmonster.core integration test.
               for (Object child:children) {
                  initLayerComponent(child);
               }
            }
         }
         catch (RuntimeException exc) {
            error("Application error initializing layer: " + getLayerName() + ": " + exc);
            if (layeredSystem.options.verbose)
               exc.printStackTrace();
         }
      }

      initialized = true;

      if (excluded)
         return;

      // Don't initialize the core build layer - it has no indexed types anyway and this causes us to init the
      // per-process index before we've defined the process.
      if (layeredSystem.typeIndexEnabled) {
         if (hasSrc)
            initTypeIndex();
         else
            layerTypeIndex = new LayerTypeIndex();
      }

      if (baseLayers != null && !activated) {
         for (Layer baseLayer:baseLayers)
            baseLayer.ensureInitialized(true);
      }

      callLayerMethod("init");

      if (disabled) {
         disableLayer();
      }

      if (liveDynamicTypes && compiledOnly)
         liveDynamicTypes = false;

      if (layeredSystem.layeredClassPaths && dynamic)
         buildLayer = true;

      // Create a map from class name to the full imported name
      initImports();
   }

   private static void initLayerComponent(Object child) {
      Object[] children = DynUtil.getObjChildren(child, null, true); // This will just create the children and add them to their parents (e.g. RepositoryPackage.addSubPackage will be called because it's a LayerComponent)
   }

   public boolean updateModel(JavaModel newModel) {
      boolean reInit = model != null && model != newModel;
      model = newModel;
      TypeDeclaration modelType = model.getModelTypeDeclaration();
      if (modelType != null) {
         modelType.isLayerType = true;
      }
      // For reparsed models, the model instance will not be different but it's internals will have changed so removing the 'reInit' flag here
      if (/*reInit &&*/ initialized && modelType instanceof ModifyDeclaration && !excluded) {
        ModifyDeclaration layerModel = (ModifyDeclaration) modelType;
         baseLayerNames = layerModel.getExtendsTypeNames();

         String layerPathPrefix = CTypeUtil.getPackageName(layerDirName);
         baseLayers = layeredSystem.mapLayerNamesToLayers(layerPathPrefix, baseLayerNames, activated, !closed);
         LayerParamInfo lpi = new LayerParamInfo();
         initFailed = layeredSystem.cleanupLayers(baseLayers) || initFailed;
         lpi.activate = activated;
         // TODO: we are resetting the Layer properties based on the new model but really need to first reset them to the defaults.
         // Reinit the dynamic object's state
         //if (dynObj != null)
         //   DynUtil.dispose(dynObj);
         dynObj = null;
         initDynObj();
         // or should we just create a new Layer instance and then update the Layer object references in all of the dependent models, or use a "removeLayer" and "removed" flag?
         String prefix = model.getPackagePrefix();
         boolean inheritedPrefix = getInheritedPrefix(baseLayers, prefix, newModel);
         prefix = model.getPackagePrefix();
         if (!model.hasErrors()) {
            errorsStarting = false;
            try {
               layerModel.initLayerInstance(this, prefix, inheritedPrefix);
            }
            catch (IllegalArgumentException exc) {
               System.err.println("*** Error updating layer mode: " + exc); // This can happen for certain syntax errors in the model
               errorsStarting();
            }
            catch (RuntimeException exc) {
               System.err.println("*** Runtime exception updating layer mode: " + exc); // This also can happen - e.g. a NullPointerException trying to evaluating an expression that failed to init
               errorsStarting();
            }
         }
         else
            errorsStarting();
         //modelType.initDynamicInstance(this);
         initLayerModel((JavaModel) model, lpi, layerDirName, false, false, dynamic);
         initImports();
         initGlobalImports();
         initImportCache();

         updateExcludedStatus();
      }
      return reInit;
   }

   private void updateExcludedStatus() {
      boolean oldExcluded = excluded;
      excluded = !includeForProcess(layeredSystem.processDefinition);
      if (oldExcluded != excluded) {
         System.err.println("*** Not processing exclusion change for layer: " + this + " restart - required");
         if (!excluded)
            markExcluded();
         /*
         if (excluded) {
            layeredSystem.removeExcludedLayers(true);
         }
         else {
            if (!activated)
               layeredSystem.registerInactiveLayer(this);
         }
         */
      }
   }

   public void markExcluded() {
      if (excluded)
         return;
      excluded = true;
      removeFromTypeIndex();
   }

   public static boolean getBaseIsDynamic(List<Layer> baseLayers) {
      /* Now that we have defined the base layers, we'll inherit the package prefix and dynamic state */
      boolean baseIsDynamic = false;
      if (baseLayers != null) {
         for (Layer baseLayer:baseLayers) {
            if (baseLayer.dynamic)
               baseIsDynamic = true;
         }
      }
      return baseIsDynamic;
   }

   public static boolean getInheritedPrefix(List<Layer> baseLayers, String prefix, JavaModel model) {
      if (prefix != null) {
         return false;
      }
      boolean inheritedPrefix = false;
      if (baseLayers != null) {
         for (Layer baseLayer:baseLayers) {
            if (baseLayer.packagePrefix != null && baseLayer.exportPackage) {
               prefix = baseLayer.packagePrefix;
               model.setComputedPackagePrefix(prefix);
               inheritedPrefix = true;
               break;
            }
         }
      }
      return inheritedPrefix;
   }

   private void initImports() {
      if (imports != null) {
         importsByName = new HashMap<String,ImportDeclaration>();
         for (ImportDeclaration imp:imports) {
            if (!imp.staticImport) {
               String impStr = imp.identifier;
               if (impStr == null) {
                  continue;
               }
               String className = CTypeUtil.getClassName(impStr);
               if (className.equals("*")) {
                  String pkgName = CTypeUtil.getPackageName(impStr);
                  if (globalPackages == null)
                     globalPackages = new ArrayList<String>();
                  if (!globalPackages.contains(pkgName))
                     globalPackages.add(pkgName);
               }
               else {
                  importsByName.put(className, imp);
               }
            }
         }
      }
      else
         importsByName = null;
   }

   private void initGlobalImports() {
      if (globalPackages != null) {
         for (String pkg:globalPackages) {
            Set<String> filesInPackage = layeredSystem.getFilesInPackage(pkg);
            if (filesInPackage != null && filesInPackage.size() > 0) {
               for (String impName:filesInPackage) {
                  importsByName.put(impName, ImportDeclaration.create(CTypeUtil.prefixPath(pkg, impName)));
               }
            }
            else {
               error("No files in global import: " + pkg);
            }
         }
      }
   }

   public void ensureInitialized(boolean checkBaseLayers) {
      if (!isInitialized()) {
         init();
      }
      if (checkBaseLayers) {
         if (baseLayers != null) {
            for (Layer baseLayer:baseLayers) {
               baseLayer.ensureInitialized(true);
            }
         }
      }
   }

   public String getDefaultBuildDir() {
      return LayerUtil.getLayerClassFileDirectory(this, layerPathName, false);
   }

   public void initBuildDir() {
      if (buildDir == null)
         buildDir = LayerUtil.getLayerClassFileDirectory(this, layerPathName, false);
      else // Translate configured relative paths to be relative to the layer's path on disk
         buildDir = FileUtil.getRelativeFile(layerPathName, buildDir);

      if (buildSrcDir == null) {
         buildSrcDir = FileUtil.concat(LayerUtil.getLayerClassFileDirectory(this, layerPathName, true), layeredSystem.getRuntimePrefix(), getBuildSrcSubDir());
      }
      else
         buildSrcDir = FileUtil.getRelativeFile(layerPathName, buildSrcDir);

      // Is this right?
      buildClassesDir = FileUtil.concat(buildDir, layeredSystem.getRuntimePrefix(), getBuildClassesSubDir());

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
      if (buildSeparate || layerPosition == -1 || !activated)
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
      String res = FileUtil.concat(buildDir, layeredSystem.getRuntimePrefix(), sd);
      return res;
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

   public static boolean isBuildDirPath(String dir) {
      return dir.contains("${buildDir}");
   }

   void initSrcCache(ArrayList<ReplacedType> replacedTypes) {
      for (int i = 0; i < topLevelSrcDirs.size(); i++) {
         String dir = topLevelSrcDirs.get(i);
         if (!isBuildDirPath(dir))
            dir = FileUtil.getRelativeFile(layerPathName, dir);
         else {
            // Can only process buildDir paths when we are activated
            // TODO: Need to fix this for android because we need to build R.java in the prebuild stage so it is available to the SC source
            // Instead of trying to get prebuild to work for inactive layers, we could try to run the android tool against the inactive source, storing the result in a
            // directory we include only when looking up the inactive layers.
            if (!activated)
               continue;
            dir = dir.replace("${buildDir}", layeredSystem.buildDir);
         }
         if (dir.contains("${"))
            System.err.println("Layer: " + getLayerName() + ": Unrecognized variable in srcPath: " + dir);
         File dirFile = new File(dir);
         if (!addSrcFilesToCache(dirFile, "", replacedTypes, srcRootNames == null ? null : srcRootNames.get(i))) {
            // For hierarchical projects it's easier to specify all possible src dirs, even if some do not exist.  This is thus a warning, not an error
            warn("Missing src dir: " + dir);
            // Actually remove this so it does not end up in the index
            topLevelSrcDirs.remove(i);
            if (srcRootNames != null)
               srcRootNames.remove(i);
            i--;
         }
      }
      initGlobalImports();
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
                  addSrcFilesToCache(f, "", replacedTypes, null);
            }
         }
      }
   }

   public void info(String... args) {
      reportMessage(MessageType.Info, args);
   }

   public void error(String... args) {
      reportMessage(MessageType.Error, args);
   }

   public void warn(String...args) {
      reportMessage(MessageType.Warning, args);
   }

   public void verbose(String...args) {
      StringBuilder sb = new StringBuilder();
      for (String arg:args)
         sb.append(arg);
      if (layeredSystem != null)
         layeredSystem.reportMessage(MessageType.Debug, sb);
      else
         System.out.println(sb);
   }

   public void reportMessage(MessageType type, String... args) {
      StringBuilder sb = new StringBuilder();
      sb.append(type);
      sb.append(" in layer: ");
      sb.append(layerPathName);
      sb.append(": ");
      for (String arg:args)
         sb.append(arg);
      if (layeredSystem != null)
         layeredSystem.reportMessage(type, sb);
      else
         System.err.println(sb);
   }

   private boolean addSrcFilesToCache(File dir, String prefix, ArrayList<ReplacedType> replacedTypes, String rootName) {
      String srcDirPath = dir.getPath();
      if (!srcDirs.contains(srcDirPath)) {
         srcDirs.add(srcDirPath);
         if (srcDirRootNames == null && rootName != null) {
            srcDirRootNames = new ArrayList<String>(srcDirs.size());
            for (int i = 0; i < srcDirs.size()-1; i++)
               srcDirRootNames.add(null);
         }
         if (srcDirRootNames != null)
            srcDirRootNames.add(rootName);
      }

      String[] files = dir.list();
      if (files == null) {
         return false;
      }
      if (excludedPaths != null && prefix != null) {
         for (String path:excludedPaths) {
            if (path.equals(prefix))
               return true;
         }
      }

      TreeSet<String> dirIndex = getDirIndex(prefix);
      String absPrefix = FileUtil.concat(packagePrefix.replace('.', FileUtil.FILE_SEPARATOR_CHAR), prefix);
      for (String fn:files) {
         File f = new File(dir, fn);

         // Do not index the layer file itself.  otherwise, it shows up in the SC type system as a parent type in some weird cases
         if (prefix.equals("") && fn.equals(layerBaseName))
            continue;
         String ext = FileUtil.getExtension(fn);
         String srcPath = FileUtil.concat(prefix, fn);
         IFileProcessor proc = ext == null ? null : layeredSystem.getFileProcessorForExtension(ext, f.getPath(), true, this, null, false, false);

         // This isParsed test is also used for properly setting langExtensions for the type index
         if (proc != null && proc.isParsed()) {
            // Register under both the name with and without the suffix
            srcDirCache.put(srcPath, f);
            srcDirCache.put(FileUtil.removeExtension(srcPath), f);
            String rootPath = dir.getPath();
            layeredSystem.addToPackageIndex(rootPath, this, false, true, absPrefix, fn);

            String srcRelType = srcPath.replace(FileUtil.FILE_SEPARATOR_CHAR, '.');

            // Has this type already been loaded in a previous layer?  If so, we need to record that we need to apply this type in this layer after the
            // layer has been started.
            if (replacedTypes != null && proc.getProducesTypes()) {
               boolean prepend = proc.getPrependLayerPackage();
               String typeName =  prepend ? CTypeUtil.prefixPath(packagePrefix, FileUtil.removeExtension(srcRelType)) : srcRelType;

               TypeDeclaration prevType = layeredSystem.getTypeFromCache(typeName, this, prepend);
               if (prevType != null)
                  replacedTypes.add(new ReplacedType(typeName, proc.getPrependLayerPackage()));
            }

            dirIndex.add(FileUtil.removeExtension(fn));

            // If the file is excluded but is a source file, we'll need to mark it as excluded in the type index so we do not think it's a new file.
            if (layerTypeIndex != null && excludedFile(fn, prefix)) {
               layerTypeIndex.fileIndex.put(FileUtil.concat(rootPath, srcPath), TypeIndexEntry.EXCLUDED_SENTINEL);
            }
         }
         else if (!excludedFile(fn, prefix)) {
            if (f.isDirectory()) {
               if (!addSrcFilesToCache(f, FileUtil.concat(prefix, f.getName()), replacedTypes, rootName)) {
                  warn("Invalid child src directory: " + f);
               }
            }
            else if (proc != null) {
               if (layerFileCache == null)
                  layerFileCache = new HashMap<String,String>();
               layerFileCache.put(srcPath, f.getPath());
            }
         }
      }
      return true;
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
   
   public void addNewSrcFile(SrcEntry srcEnt, boolean checkPeers) {
      String relFileName = srcEnt.relFileName;
      File f = new File(srcEnt.absFileName);

      String relDir = srcEnt.getRelDir();
      TreeSet<String> dirIndex = getDirIndex(relDir);
      srcDirCache.put(FileUtil.removeExtension(relFileName), f);
      srcDirCache.put(relFileName, f);
      String absPrefix = FileUtil.concat(packagePrefix.replace('.', FileUtil.FILE_SEPARATOR_CHAR), relDir);
      layeredSystem.addToPackageIndex(FileUtil.normalize(layerPathName), this, false, true, FileUtil.normalize(absPrefix), srcEnt.baseFileName);
      dirIndex.add(FileUtil.removeExtension(srcEnt.baseFileName));
      // If there's an import packageName.* for this type, make sure we add the import
      if (globalPackages != null) {
         String typeName = srcEnt.getTypeName();
         if (typeName != null) {
            String packageName = CTypeUtil.getPackageName(typeName);
            String className = CTypeUtil.getClassName(typeName);
            for (String globalPackage : globalPackages) {
               if (globalPackage.equals(packageName)) {
                  importsByName.put(className, ImportDeclaration.create(typeName));
                  break;
               }
            }
         }
      }
      if (checkPeers && layeredSystem.peerSystems != null) {
         for (int i = 0; i < layeredSystem.peerSystems.size(); i++) {
            LayeredSystem peerSys = layeredSystem.peerSystems.get(i);
            Layer peerLayer = activated ? peerSys.getLayerByName(getLayerUniqueName()) : peerSys.lookupInactiveLayer(getLayerName(), false, true);
            if (peerLayer != null)
               peerLayer.addNewSrcFile(srcEnt, false);
         }
      }
   }

   public void removeSrcFile(SrcEntry srcEnt) {
      String relFileName = srcEnt.relFileName;
      File f = new File(srcEnt.absFileName);
      srcDirCache.remove(FileUtil.removeExtension(relFileName));
      srcDirCache.remove(relFileName);
      String absPrefix = FileUtil.concat(packagePrefix.replace('.', '/'), srcEnt.getRelDir());
      layeredSystem.removeFromPackageIndex(layerPathName, this, false, true, absPrefix, srcEnt.baseFileName);
      // TODO: any reason to remove the dirIndex entry?  and the globalPackages entry if there's an import packageName.* which matches this class?  We should validate each entry still exists before returning it
   }

   /**
    * The SrcPathType object is used to register one or more different types of src files - e.g. source files, files in the web directory, test files etc.  
    * Most of the time you can use the default src path type - 'null' and just register a processor or language by the extension.  But when you need to treat
    * files with the same suffix differently in different contexts, you register a new SrcPathType.  Assign a path-prefix of where to find these files.
    * You can also assign a buildPrefix for a path type which is used in computing where the generated files go.  
    */
   private static class SrcPathType {
      /** The path name of the src files - if relative is true, relative to this layer - e.g. 'web' or 'src/main/tests.  If absolute, the abs path name to find the src of this type' */
      String srcPath;
      boolean relative;
      /** The name of the path-type - e.g. 'web' or 'testFiles' */
      String pathTypeName;
      /** Optionally inherit file processors from an existing type but using this new buildPrefix  */
      String fromPathTypeName;
      /**
       * The build prefix to use when building files of this type.  The value which is used is relative to the buildLayer, allowing you to
       * reorganize files found in base-layers by adding a new layer and changing the buildPrefix for that file type..
       */
      String buildPrefix;

      public String toString() {
         if (pathTypeName == null)
            return "<null src path type>";
         if (srcPath == null && buildPrefix == null)
            return pathTypeName;
         String buildPrefixStr = buildPrefix == null ? "" : '@' + buildPrefix + (fromPathTypeName == null ? "" : " inherits from: " + fromPathTypeName);
         String srcPathStr = srcPath == null ? "" : ':' + srcPath;
         return pathTypeName + srcPathStr + buildPrefixStr;
      }

      public boolean inheritsFrom(Layer l, String pathTypeName) {
         if (StringUtil.equalStrings(pathTypeName, fromPathTypeName))
            return true;
         if (fromPathTypeName != null) {
            SrcPathType fromPathType = l.getSrcPathTypeByName(fromPathTypeName, true);
            if (fromPathType != null) {
               if (fromPathType.fromPathTypeName != null)
                  return inheritsFrom(l, fromPathType.fromPathTypeName);
            }
         }
         return false;
      }
   }

   private static class ReplacedType {
      String typeName;
      boolean prependPackage;

      ReplacedType(String tn, boolean pp) {
         this.typeName = tn;
         this.prependPackage = pp;
      }
   }

   public void start() {
      if (started)
         return;
      started = true;

      if (excluded)
         return;

      if (baseLayers != null && !activated) {
         for (Layer baseLayer:baseLayers)
            baseLayer.ensureStarted(true);
      }

      if (isBuildLayer() && activated && !disabled) {
         if (buildSrcDir == null) {
            initBuildDir();
         }
         if (buildSrcDir == null)
            System.err.println("*** Warning - activated build layer started without build dirs initialized: " + this);
         else {
            loadBuildInfo();
            LayeredSystem.initBuildFile(buildSrcDir);
            LayeredSystem.initBuildFile(buildClassesDir);
         }
      }

      if (repositoryPackages != null && !disabled) {
         for (RepositoryPackage pkg:repositoryPackages) {
            DependencyContext pkgCtx = new DependencyContext(pkg, "Layer: " + toString() + " package tree");
            layeredSystem.repositorySystem.installPackage(pkg, pkgCtx);
            if (layeredSystem.options.verbose) {
               verbose(pkgCtx.dumpContextTree().toString());
            }
         }
         installPackages(repositoryPackages.toArray(new RepositoryPackage[repositoryPackages.size()]));
      }

      // First start the model so that it can set up our paths etc.
      if (model != null)
         ParseUtil.startComponent(model);

      if (disabled) {
         System.out.println("Layer: " + getLayerName() + " is disabled");
         disableLayer();
         return;
      }

      callLayerMethod("start");

      if (disabled) {
         disableLayer();
         return;
      }

      if (classPath != null) {
         classDirs = Arrays.asList(classPath.split(FileUtil.PATH_SEPARATOR));
         zipFiles = new ZipFile[classDirs.size()];
         for (int i = 0; i < classDirs.size(); i++) {
            String classDir = classDirs.get(i);

            // skip empty directories
            if (classDir.length() == 0)
               continue;

            // Replace "." with the layerPathName - allowing classpath's to be encapsulated in the layer dir
            classDir = FileUtil.getRelativeFile(layerPathName, classDir);
            classDirs.set(i, classDir); // Store the translated path
            String ext = FileUtil.getExtension(classDir);
            if (ext != null && (ext.equals("jar") || ext.equals("zip"))) {
               try {
                  zipFiles[i] = new ZipFile(classDir);
               }
               catch (IOException exc) {
                  error("*** Can't open zip file: " + classDir + " in classPath for layer: " + layerPathName);
               }
            }
         }
      }
      if (externalClassPath != null) {
         externalClassDirs = Arrays.asList(externalClassPath.split(FileUtil.PATH_SEPARATOR));
         externalZipFiles = new ZipFile[externalClassDirs.size()];
         for (int i = 0; i < externalClassDirs.size(); i++) {
            String classDir = externalClassDirs.get(i);
            // Make classpath entries relative to the layer directory for easy encapsulation
            classDir = FileUtil.getRelativeFile(layerPathName, classDir);
            externalClassDirs.set(i, classDir); // Store the translated path
            String ext = FileUtil.getExtension(classDir);
            if (ext != null && (ext.equals("jar") || ext.equals("zip"))) {
               try {
                  externalZipFiles[i] = new ZipFile(classDir);
               }
               catch (IOException exc) {
                  System.err.println("*** Can't open layer: " + layerPathName + "'s classPath entry as a zip file: " + classDir);
               }
            }
         }
      }

      if (topLevelSrcDirs == null)
         initSrcDirs();

      if (layeredSystem.typeIndexEnabled) {
         if (layerTypeIndex == null)
            layerTypeIndex = new LayerTypeIndex();
         layerTypeIndex.initFrom(this);
         if (layerTypeIndex.layerPathName == null)
            System.err.println("*** Missing layer path name for type index");
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
      if (externalClassDirs != null) {
         for (int j = 0; j < externalClassDirs.size(); j++)
            layeredSystem.addPathToIndex(this, externalClassDirs.get(j));
      }

      // When the layer is activated, we load types in the proper order but when it's not activated, we lazily populate
      // the type system.  This means that as each layer is loaded, it's important that we find any types which we replace
      // and replace them so the model is in sync with this layer.  When the layer is active, this operation should be fine
      // but we end up loading types in the wrong order - particularly when there are multiple runtimes.
      // We'd need to move this getSrcTypeDeclaration call until after we've started the corresponding peer layers.
      replacedTypes = !activated ? new ArrayList<ReplacedType>() : null;
      // Now init our index of the files managed by this layer
      initSrcCache(replacedTypes);

      // Need to save the filtered list of topLevelSrcDirs in the index so we know when this particular index is out of date.
      if (layerTypeIndex != null)
         layerTypeIndex.topLevelSrcDirs = topLevelSrcDirs.toArray(new String[topLevelSrcDirs.size()]);

      if (isBuildLayer())
         makeBuildLayer();

      initReplacedTypes();

      layeredSystem.initSysClassLoader(this, LayeredSystem.ClassLoaderMode.LIBS);

      // If there are layer component children that have not initialized themselves, we can do it here.
      if (children != null) {
         for (int i = 0; i < children.size(); i++) {
            Object child = children.get(i);

            // These types don't implement the layer component because they are part of the 'full runtime' - to keep the
            // DB runtime libraries separate from the parser, and dynamic runtime. But we still want to add them to the layered
            // systen at build time so special casing the logic here.
            if (child instanceof DBDataSource) {
               DBDataSource childDS = (DBDataSource) child;
               layeredSystem.addDataSource(childDS, this);
               if (childDS.makeDefaultDataSource) {
                  defaultDataSource = childDS;
                  if (layeredSystem.defaultDataSource == null)
                     layeredSystem.defaultDataSource = childDS;
               }
            }
         }
      }
   }

   private void initReplacedTypes() {
      // This is the list of types defined in this layer which were already loaded into the type cache.  To keep them from being stale, when this inactive layer is opened
      // we need to replace them with the ones  in this layer now that this layer is open - loading the type should do it.
      // We need to skip this when we are starting the layers for the type index - need to avoid all normal src lookups in this phase
      if (replacedTypes != null && !closed  && !layeredSystem.startingTypeIndexLayers) {
         for (ReplacedType replacedType:replacedTypes) {
            TypeDeclaration newType = (TypeDeclaration) layeredSystem.getSrcTypeDeclaration(replacedType.typeName, null, replacedType.prependPackage, false, true, this, false);
            if (newType == null)
               System.out.println("*** Error - did not find type: " + replacedType.typeName + " after adding layer: " + this);
         }
      }
   }

   public void startReplacingTypes(String modelTypeName) {
      SrcEntry srcEnt = getSrcEntryForType(modelTypeName);
      if (srcEnt != null) {
         TypeDeclaration td = layeredSystem.getInactiveTypeDeclaration(srcEnt);
         if (td != null)
            ParseUtil.realInitAndStartComponent(td);
         else
            System.err.println("*** Unable to find replaced type: " + modelTypeName);
      }
   }


   private void initSrcDirs() {
      if (srcPath == null) {
         topLevelSrcDirs = new ArrayList<String>(Collections.singletonList(LayerUtil.getLayerSrcDirectory(layerPathName)));
      }
      else {
         String [] srcList = srcPath.split(FileUtil.PATH_SEPARATOR);
         topLevelSrcDirs = new ArrayList<String>(Arrays.asList(srcList));
         for (int i = 0; i < topLevelSrcDirs.size(); i++) {
            if (topLevelSrcDirs.get(i).equals("."))
               topLevelSrcDirs.set(i, layerPathName);
         }
         if (layeredSystem.options.verbose) {
            verbose("Layer: " + this + " custom src path:");
            for (String srcDir:topLevelSrcDirs) {
               String srcPathType = getSrcPathTypeName(srcDir, true);
               verbose("   " + srcDir + (srcPathType != null ? " srcPathType: " + srcPathType : ""));
            }
         }
      }
   }

   public void ensureStarted(boolean checkBaseLayers) {
      if (!isStarted()) {
         start();
      }
      if (checkBaseLayers && !excluded) {
         if (baseLayers != null) {
            for (Layer baseLayer:baseLayers) {
               baseLayer.ensureStarted(true);
            }
         }
      }
   }

   public void ensureValidated(boolean checkBaseLayers) {
      if (!isValidated()) {
         validate();
      }
      if (checkBaseLayers && !excluded) {
         if (baseLayers != null) {
            for (Layer baseLayer:baseLayers) {
               baseLayer.ensureValidated(true);
            }
         }
      }
   }

   public void ensureProcessed(boolean checkBaseLayers) {
      if (!isProcessed()) {
         process();
      }
      if (checkBaseLayers && !excluded) {
         if (baseLayers != null) {
            for (Layer baseLayer:baseLayers) {
               baseLayer.ensureProcessed(true);
            }
         }
      }
   }

   public boolean isBuildLayer() {
      return buildSeparate || (layeredSystem != null && layeredSystem.buildLayer == this) || buildLayer;
   }

   public void makeBuildLayer() {
      buildLayer = true;
      if (buildSrcIndex == null)
         buildSrcIndex = readBuildSrcIndex();
   }

   public boolean markBuildSrcFileInUse(File genFile, String genRelFileName) {
      SrcIndexEntry lastValid = null;
      if (!activated)
         throw new UnsupportedOperationException();
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
         ObjectInputStream ois = null;
         FileInputStream fis = null;
         try {
            ois = new ObjectInputStream(fis = new FileInputStream(buildSrcFile));
            HashMap<String, SrcIndexEntry> res = (HashMap<String, SrcIndexEntry>) ois.readObject();
            //SrcIndexEntry tmp = res.get("js/types/sce_TypeTreeModel_TreeEnt.js");
            //if (tmp != null)
               //System.out.println("*** Reading TreeEnt srcIndexEntry: modified: " + new Date(tmp.lastModified) + ": " + tmp.fileBytes.length + " srcFile: " + buildSrcFile);
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
         finally {
            FileUtil.safeClose(ois);
            FileUtil.safeClose(fis);
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
          // TODO: NULL is no good for the layer here - should store the layer in the build src index so we can accurately
         // compute the info below.  Maybe we put the layer name in the SrcIndexEntry
         String layerName = sie.layerName;
         Layer srcLayer = null;
         if (layerName != null) {
            srcLayer = layeredSystem.getLayerByDirName(layerName);
         }
         IFileProcessor proc = layeredSystem.getFileProcessorForFileName(path, srcLayer, BuildPhase.Process);
         String srcDir = proc == null ? buildSrcDir : proc.getOutputDirToUse(layeredSystem, buildSrcDir, this);
         String fileName = FileUtil.concat(srcDir, path);
         if (!sie.inUse) {

            // Inner type
            if (path.indexOf(TypeDeclaration.INNER_STUB_SEPARATOR) != -1) {
               String dotPath = FileUtil.removeExtension(path.replace(FileUtil.FILE_SEPARATOR_CHAR, '.'));
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
               if (!Arrays.equals(currentHash, sie.hash)) {
                  System.out.println("*** Warning generated file: " + fileName + " appears to have been changed.  Did you modify the generated file instead of the source file?");
                  if (SrcIndexEntry.debugSrcIndexEntry) {
                     byte[] newFileBytes = FileUtil.getFileAsBytes(fileName);
                     if (Arrays.equals(newFileBytes, sie.fileBytes))
                        System.out.println("*** Contents are the same!");
                     else
                        System.out.println("*** Index lastModified: " + new Date(sie.lastModified) + " size: " + sie.fileBytes.length + " current lastModified: " + new Date(new File(fileName).lastModified()) + " size: " + newFileBytes.length);
                  }
               }
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

   private void writeBuildSrcIndex(boolean clean) {
      if (buildSrcIndex == null)
         return;

      // TODO: used to do cleanBuildSrcIndex here but that actually removes the files.  We call saveBuildInfo on
      // each build, even when we've just added a new layer so that did not work

      if (clean)
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
         buildSrcIndexNeedsSave = false;
         return;
      }

      ObjectOutputStream os = null;
      try {
         os = new ObjectOutputStream(new FileOutputStream(buildSrcFile));
         os.writeObject(buildSrcIndex);

         //SrcIndexEntry tmp = buildSrcIndex.get("js/types/sce_TypeTreeModel_TreeEnt.js");
         //if (tmp != null)
         //   System.out.println("*** Writing TreeEnt srcIndexEntry: modified: " + new Date(tmp.lastModified) + ": " + tmp.fileBytes.length + " path: " + buildSrcFile);

         buildSrcIndexNeedsSave = false;
      }
      catch (IOException exc) {
         System.err.println("*** can't write build srcFile: " + exc);
      }
      finally {
         FileUtil.safeClose(os);
      }
   }

   public final static String TYPE_INDEX_SUFFIX = "__typeIndex.ser";

   public void initTypeIndex() {
      File typeIndexFile = new File(layeredSystem.getTypeIndexFileName(getLayerName()));
      SysTypeIndex sysIndex = layeredSystem.typeIndex;
      if (sysIndex == null)
         sysIndex = layeredSystem.typeIndex = new SysTypeIndex(layeredSystem, layeredSystem.getTypeIndexIdent());
      LayerListTypeIndex useTypeIndex = activated ? sysIndex.activeTypeIndex : sysIndex.inactiveTypeIndex;
      String layerName = getLayerName();

      // In case we're in the midst of reading the type index for this layer, use that one instead of reading a duplicate and getting out of sync.
      layerTypeIndex = useTypeIndex.typeIndex.get(layerName);
      boolean addIndexEntry = true;
      if (layerTypeIndex != null) {
         typeIndexRestored = true;
         addIndexEntry = false;
      }

      // A clean build of everything will reset the layerTypeIndex
      if (layerTypeIndex == null && typeIndexFile.canRead() && (!activated || !getBuildAllFiles())) {
         layerTypeIndex = layeredSystem.readTypeIndexFile(getLayerName());
         typeIndexRestored = true;
         typeIndexFileLastModified = new File(getTypeIndexFileName()).lastModified();
      }
      if (layerTypeIndex == null) {
         layerTypeIndex = new LayerTypeIndex();
         layerTypeIndex.langExtensions = langExtensions;
      }
      layerTypeIndex.initFrom(this);
      if (layerTypeIndex.layerPathName == null)
         System.err.println("*** Missing layer path name for type index");
      // Always add the layer's type and layer components here since we've already started the layer's model and type.
      // For most types they are updated when they are started but the layer component starts before the type index has
      // been initialized.
      if (model != null) {
         TypeDeclaration modelType = model.getModelTypeDeclaration();
         modelType.initTypeIndex();
      }
      // The core build layer is created in the constructor so don't do this test for it.
      if (this != layeredSystem.coreBuildLayer && layeredSystem.writeLocked == 0) {
         System.err.println("Updating type index without write lock: ");
         new Throwable().printStackTrace();;
      }

      if (layerName != null && addIndexEntry) {
         useTypeIndex.addLayerTypeIndex(layerName, layerTypeIndex);
      }
      layerTypeIndex.baseLayerNames = baseLayerNames == null ? null : baseLayerNames.toArray(new String[baseLayerNames.size()]);
   }

   public void removeFromTypeIndex() {
      SysTypeIndex sysIndex = layeredSystem.typeIndex;
      if (sysIndex != null) {
         LayerListTypeIndex useTypeIndex = activated ? sysIndex.activeTypeIndex : sysIndex.inactiveTypeIndex;
         String layerName = getLayerName();
         useTypeIndex.removeLayerTypeIndex(layerName);
      }
   }

   private String getTypeIndexFileName() {
      return layeredSystem.getTypeIndexFileName(getLayerName());
   }

   public void saveTypeIndex() {
      // For activated layers, we might not have a complete type index so we cannot save it.
      // For inactivated layers, we only want to save this if we've fully initialized it.
      if (!activated && (layerTypesStarted || typeIndexRestored))  {
         if (!started || layerTypeIndex.layerPathName == null)
            System.err.println("*** Invalid type index during save");
         else if (layerTypeIndex.layerPathName.equals(layerUniqueName))
            System.out.println("*** Invalid layer path index name");
         File typeIndexFile = new File(getTypeIndexFileName());
         File typeIndexDir = typeIndexFile.getParentFile();
         ObjectOutputStream os = null;
         try {
            typeIndexDir.mkdirs();
            os = new ObjectOutputStream(new FileOutputStream(typeIndexFile));
            os.writeObject(layerTypeIndex);
            typeIndexNeedsSave = false;
            typeIndexFileLastModified = System.currentTimeMillis();

            if (layeredSystem.options.verbose)
               verbose("Saved type index for layer: " + layerDirName + " in runtime: " + layeredSystem.getProcessIdent() + " saved with: " + layerTypeIndex.layerTypeIndex.size() + " entries");
         }
         catch (IOException exc) {
            System.err.println("*** Unable to write typeIndexFile: " + exc);
         }
         finally {
            FileUtil.safeClose(os);
         }
      }
   }

   public void updateFileIndexLastModified() {
      if (typeIndexFileLastModified != -1) {
         File typeIndexFile = new File(getTypeIndexFileName());
         if (typeIndexFile.lastModified() < typeIndexFileLastModified)
            typeIndexFile.setLastModified(typeIndexFileLastModified);
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
         ObjectInputStream ois = null;
         FileInputStream fis = null;
         try {
            ois = new ObjectInputStream(fis = new FileInputStream(dynTypeIndexFile));
            HashSet<String> res = (HashSet<String>) ois.readObject();
            if (res != null) {
               return res;
            }
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
         finally {
            FileUtil.safeClose(ois);
            FileUtil.safeClose(fis);
         }
      }
      return null;
   }

   public final static int DEFAULT_SORT_PRIORITY = 0;

   public int getSortPriority() {
      int ct = 0;
      // The current implementation gives us three tiers of layers - compiled-only framework layers (e.g. js.sys)
      // compiled-only application layers, e.g. util and the rest - application layers which are always appended unless they
      // have a dependency.
      if (compiledOnly)
         ct--;
      if (codeType == CodeType.Framework)
         ct--;
      // Helps to place config layers close to the layers they are configuring so downstream layers see a consistent view of those types and makes the layer stack easier to read
      if (configLayer)
         ct--;

      if (ct == 0) {
         // for inactive layers, when we built the type index we created a master index of "an order" that satisfies dependencies.  As we restore layers, we want
         // them to remain in this consistent order so we can display them cleanly in the UI.
         if (!activated) {
            LayeredSystem sys = layeredSystem;
            if (sys.typeIndex != null && sys.typeIndexLoaded) {
               int oldPos = sys.typeIndex.inactiveTypeIndex.orderIndex.inactiveLayerNames.indexOf(getLayerName());
               if (oldPos != -1)
                  return oldPos;
            }
         }
         return DEFAULT_SORT_PRIORITY;
      }

      return ct;
   }

   public void saveDynTypeIndex() {
      File dynTypeFile = new File(buildSrcDir, layeredSystem.getDynTypeIndexFile());
      File buildSrcDirFile = new File(buildSrcDir);
      /* Some old debug code... not sure why this was here
      if (buildSrcDir.contains("clientServer") && layeredSystem.getRuntimeName().contains("java")) {
         if (dynTypeIndex != null && !dynTypeIndex.contains("sc.html.index") && dynamic) {
            System.err.println("*** Error - saving clientServer index without a dynamic sc.html.index page!");
         }
      }
      */
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

      ObjectOutputStream os = null;
      try {
         os = new ObjectOutputStream(new FileOutputStream(dynTypeFile));
         os.writeObject(dynTypeIndex);
      }
      catch (IOException exc) {
         System.out.println("*** can't write dyn type index file: " + exc);
      }
      finally {
         FileUtil.safeClose(os);
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
               // Forcing it to always check for inner types here since we may be directly importing an inner type.
               // There's an optimization to avoid the getClass call for inner types in the general case.
               //
               // Not using layerResolve here since this type is resolved for runtime, not for layer init time.
               if (typeObj == null)
                  typeObj = layeredSystem.getClassWithPathName(typeName, this, false, true, false, false);
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
                  Object property = ModelUtil.definesMember(typeObj, memberName, JavaSemanticNode.MemberType.PropertyGetSet, null, null, null);
                  if (property == null)
                     property = ModelUtil.definesMember(typeObj, memberName, JavaSemanticNode.MemberType.PropertySetSet, null, null, null);
                  if (property != null && ModelUtil.hasModifier(property, "static")) {
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
         Object startMethObj = model.getModelTypeDeclaration().findMethod(methodName, null, null, null, false, null);
         if (startMethObj instanceof MethodDefinition) {
            ctx.pushCurrentObject(this);
            try {
               MethodDefinition meth = (MethodDefinition) startMethObj;
               meth.invoke(ctx, null);
            }
            catch (RuntimeException exc) {
               error("Exception in layer's start method: ", exc.toString());
               if (layeredSystem.options.verbose)
                  exc.printStackTrace();
               errorsStarting();
            }
            catch (Throwable exc) {
               error("Error in layer's start method: ", exc.toString());
               if (layeredSystem.options.verbose)
                  exc.printStackTrace();
               errorsStarting();
            }
         }
      }
      else
         errorsStarting();
   }

   private void errorsStarting() {
      errorsStarting = true;
      if (activated)
         layeredSystem.anyErrors = true; // Need to stop the build right away if the errors don't start cleanly
   }

   /** Note, this stores the type containing the statically imported name, not the field or method. */
   private Object addStaticImportType(String name, Object obj) {
      if (staticImportTypes == null)
         staticImportTypes = new HashMap<String,Object>();
      return staticImportTypes.put(name, obj);
   }

   private static void closeZips(ZipFile[] fileList) {
      if (fileList == null)
         return;
      for (ZipFile zipFile : fileList) {
         try {
            if (zipFile != null)
               zipFile.close();
         } catch (IOException e) {}
      }
   }

   public void stop() {
      initialized = false;
      started = false;
      errorsStarting = false;
      closeZips(externalZipFiles);
      externalZipFiles = null;
      closeZips(zipFiles);
      zipFiles = null;
      if (repositoryPackages != null) {
         for (RepositoryPackage pkg:repositoryPackages) {
            RepositorySystem reposSys = layeredSystem.repositorySystem;
            reposSys.removeRepositoryPackage(pkg);
         }
         repositoryPackages = null;
      }
   }

   public String getLayerPathName() {
      return layerPathName;
   }

   public String getLayerDefFilePath() {
      return FileUtil.concat(getLayerPathName(), layerBaseName);
   }

   /** The vanilla name of the layer - no package prefix but does include the layer group */
   @Constant
   public String getLayerName() {
      String base = layerDirName;
      if (base == null || base.equals(".")) {
         if (packagePrefix != null && packagePrefix.length() > 1 && packagePrefix.length() < layerUniqueName.length())
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

   // TODO: almost the same as ensureStarted but this is specific for the lazy check when we encounter a non-started
   // layer in findSrcFile etc.  For activated layers, we need to rely on the initial startLayers calls for layered
   // builds so this method skips activated layers entirely unlike ensureStarted.
   public boolean checkIfStarted() {
      if (!isStarted() && !activated) {
         if (baseLayers != null && !excluded && !disabled) {
            for (Layer base:baseLayers)
               base.checkIfStarted();
         }
         if (initialized && !disabled) {
            // If we are in the midst of initializing layers we should not start this layer, unless it's a separate
            // layer in which case we might need to look up src files in it.
            if (layeredSystem.initializingLayers && !buildSeparate)
               return false;
            // Should do init, start, and validate
            ParseUtil.initAndStartComponent(this);
         }
         else {
            return false;
         }
      }
      return true;
   }

   public File findSrcFile(String srcName, boolean layerResolve) {
      // For buildSeparate layers, we need to lazily initialize the src cache so that we can find src files defined
      // in this layer which are used by other layer definition files downstream.  Before, we used to try and build
      // the separate layers so we could resolve downstream layers but that means dealing with separate layers entirely
      // before we even initialize the layer stack (which we don't do).  So we now just init the src cache as soon as
      // we need it for these layers.  Before we run the start method on an activated layer we'll have compiled the
      // files.  That means we do not have to compile for inactive layers which is a nice simplification - otherwise,
      // those separate layers were always active.
      if (layerResolve && buildSeparate) {
         ensureInitialized(true);
         ensureStarted(true);
      }
      else if (!layerResolve && !checkIfStarted())
         return null;
      File res = srcDirCache.get(srcName);
      return res;
   }

   public SrcEntry getSrcEntry(String absFileName) {
      // Cannot lazily initialize the layer to find the src file here... layers like jool.lib need to be
      // started before the topLevelSrcDirs is set.
      //if (topLevelSrcDirs == null)
      //   initSrcDirs();
      if (topLevelSrcDirs != null) {
         int ix = 0;
         for (String dir : topLevelSrcDirs) {
            if (absFileName.startsWith(dir)) {
               checkIfStarted();

               String rest = absFileName.substring(dir.length());
               while (rest.startsWith(FileUtil.FILE_SEPARATOR))
                  rest = rest.substring(1);
               File f = srcDirCache.get(rest);
               if (f != null)
                  return new SrcEntry(this, absFileName, rest, true, srcRootNames == null ? null : srcRootNames.get(ix));
            }
            ix++;
         }
      }
      // else - we can get here in some cases where we are not started... e.g. looking for source files in separate layers when we are not a separate layer.
      return null;
   }

   public TypeIndexEntry getTypeIndexEntryForPath(String absFileName) {
      if (layerTypeIndex != null) {
         SrcEntry srcEnt = getSrcEntry(absFileName);
         if (srcEnt != null)
            return getTypeIndexEntry(srcEnt.getTypeName());
      }
      return null;
   }

   /** List of suffixes to search for src files.  Right now this is only Java because zip files are only used for precompiled src. */
   private final String[] zipSrcSuffixes = {"java"};

   public SrcEntry findSrcEntry(String srcName, boolean prependPackage, boolean layerResolve) {
      if (!layerResolve) {
         // Could be a reference from a template or something that is parsed while we are generating this layer.  As long as we
         // are activated we won't start anything.
         checkIfStarted();
      }
      File f = srcDirCache.get(srcName);
      if (f != null) {
         String path = f.getPath();
         String ext = FileUtil.getExtension(path);
         // TODO: need a new index to get the srcRootName for this srcEntry? Right now we only use it for directories so it's probably ok?
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
      checkIfStarted();
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

   public void startAllTypes() {
      for (Iterator<String> srcFiles = getSrcFiles(); srcFiles.hasNext(); )  {
         try {
            layeredSystem.acquireDynLock(false);
            String srcFile = srcFiles.next();
            IFileProcessor depProc = layeredSystem.getFileProcessorForFileName(srcFile, this, BuildPhase.Process);
            String typeName;
            // srcFile entries come back without suffixes... just ignore them.
            if (depProc != null) {
               String fullTypeName = CTypeUtil.prefixPath(depProc.getPrependLayerPackage() ? packagePrefix : null, FileUtil.removeExtension(srcFile).replace(FileUtil.FILE_SEPARATOR_CHAR, '.'));
               TypeDeclaration td = (TypeDeclaration) layeredSystem.getSrcTypeDeclaration(fullTypeName, this.getNextLayer(), depProc.getPrependLayerPackage(), false, true, this, false);
               if (td != null) {
                  // We always need to begin the start process at the model level
                  JavaModel model = td.getJavaModel();
                  ParseUtil.initAndStartComponent(model);
               } else {
                  String subPath = fullTypeName.replace(".", FileUtil.FILE_SEPARATOR);
                  SrcEntry srcEnt = getSrcFileFromTypeName(fullTypeName, true, depProc.getPrependLayerPackage(), subPath, false);
                  if (srcEnt != null) {
                     if (srcEnt.isLayerFile()) {
                        // This happens for Template's which do not transform into a type - e.g. sctd files.  It's important that we record something for this source file so we don't re-parse the layer etc. next time.
                        TypeIndexEntry layerIndex = new TypeIndexEntry();
                        layerIndex.typeName = fullTypeName;
                        layerIndex.layerName = getLayerName();
                        layerIndex.processIdent = layeredSystem.getProcessIdent();
                        layerIndex.fileName = srcEnt.absFileName;
                        layerIndex.declType = DeclarationType.OBJECT; // Is it always a template?
                        layerIndex.isLayerType = true;
                        updateTypeIndex(layerIndex, typeIndexFileLastModified);
                     }
                     else {
                        // This happens for Template's which do not transform into a type - e.g. sctd files.  It's important that we record something for this source file so we don't re-parse the layer etc. next time.
                        TypeIndexEntry dummyIndex = new TypeIndexEntry();
                        dummyIndex.typeName = fullTypeName;
                        dummyIndex.layerName = getLayerName();
                        dummyIndex.processIdent = layeredSystem.getProcessIdent();
                        dummyIndex.fileName = srcEnt.absFileName;
                        dummyIndex.declType = DeclarationType.TEMPLATE; // Is it always a template?
                        updateTypeIndex(dummyIndex, typeIndexFileLastModified);
                     }
                  } else {
                     System.err.println("*** No type or src file found for index entry for source file: " + srcFile);
                  }
               }
            }
         }
         finally {
            layeredSystem.releaseDynLock(false);
         }
      }
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
      Object[] innerTypes = ModelUtil.getAllInnerTypes(type, null, true, false);
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

   public List<Layer> getLayersList() {
      if (layeredSystem == null) {
         System.out.println("*** Invalid layer: " + this);
      }
      if (disabled)
         return layeredSystem.disabledLayers;
      return activated ? layeredSystem.layers : layeredSystem.inactiveLayers;
   }

   /** Returns the layer after this one in the list, i.e. the one that overrides this layer */
   public Layer getNextLayer() {
      if (disabled) // The layers list is not really ordered for disabled layers so just return null.
         return null;
      List<Layer> layersList = getLayersList();
      if (layersList == null)
         return null;
      int size = layersList.size();
      if (layerPosition == size-1)
         return null;
      if (layerPosition + 1 >= layersList.size()) {
         System.out.println("*** Error - invalid layer position");
         return null;
      }
      return layersList.get(layerPosition + 1);
   }

   /** Returns the layer just before this one in the list, i.e. the one this layer overrides */
   public Layer getPreviousLayer() {
      if (layerPosition == 0)
         return null;
      List<Layer> layersList = getLayersList();
      return layersList.get(layerPosition - 1);
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
      if (!activated)
         return null;
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
      File res = findClassFile(classFileName, includePrevious, false);
      if (res == null)
         res = findClassFile(classFileName, includePrevious, true);
      return res;
   }

   public File findClassFile(String classFileName, boolean includePrevious, boolean external) {
      File result;
      if (buildDir != null && compiled && !external) {
         result = new File(buildDir, classFileName);
         if (result.canRead())
            return result;
      }

      List<String> cdirs = external ? externalClassDirs : classDirs;
      ZipFile[] zfiles = external ? externalZipFiles : zipFiles;

      if (cdirs != null) {
         for (int i = 0; i < cdirs.size(); i++) {
            String classDir = cdirs.get(i);
            if (zfiles[i] != null) {
               if (zfiles[i].getEntry(classFileName) != null)
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
            return prevLayer.findClassFile(classFileName, true, external);
      }
      return null;
   }

   public Object getClass(String classFileName, String className) {
      Object res = getClass(classFileName, className, false);
      if (res == null)
         res = getClass(classFileName, className, true);
      return res;
   }

   public Object getClass(String classFileName, String className, boolean external) {
      if (layeredSystem == null) {
         System.err.println("*** No layered system for layer!"); // Could have been removed here - but why is a removed layer being used for a lookup?
         return null;
      }
      if (!external && !layeredSystem.options.crossCompile) {
         return RTypeUtil.loadClass(layeredSystem.getSysClassLoader(), className, false);
      }
      else
         return getCFClass(classFileName, external);
   }

   public Object getCFClass(String classFileName) {
      Object res = getCFClass(classFileName, false);
      if (res == null)
         res = getCFClass(classFileName, true);
      return res;
   }

   // TODO: Performance - can we use the package index to avoid this search through all of the jar files?
   public Object getCFClass(String classFileName, boolean external) {
      List<String> cdirs = external ? externalClassDirs : classDirs;
      ZipFile[] zfiles = external ? externalZipFiles : zipFiles;
      if (cdirs == null)
         return null;
      for (int i = 0; i < cdirs.size(); i++) {
         CFClass cl;
         if (zfiles[i] != null) {
            cl = CFClass.load(zfiles[i], classFileName, this);
         }
         else
            cl = CFClass.load(FileUtil.concat(cdirs.get(i), classFileName), this);
         if (cl != null) {
            if (layeredSystem.options.verboseClasses) {
               verbose("Loaded CFClass: " + classFileName);
            }
            return cl;
         }
      }
      return null;
   }

   public Object getInnerCFClass(String fullTypeName, String cfTypeName, String name) {
      String classFileName = cfTypeName + "$" + name + ".class";
      return getCFClass(classFileName);
   }

   public ImportDeclaration getImportDecl(String name, boolean checkBaseLayers) {
      if (importsByName != null) {
         ImportDeclaration res = importsByName.get(name);
         if (res != null)
            return res;
      }
      if (checkBaseLayers && baseLayers != null && !excluded) {
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

   public boolean findMatchingSrcNames(String prefix, Set<String> candidates, boolean retFullTypeName, int max) {
      if (layerDirName.startsWith(prefix)) {
         if (!layeredSystem.addMatchingCandidate(candidates, "", layerDirName, retFullTypeName, max))
            return false;
      }
      for (Iterator<String> srcFiles = getSrcFiles(); srcFiles.hasNext(); )  {
         String srcFile = srcFiles.next();
         String fileName = FileUtil.getFileName(srcFile);
         if (fileName.startsWith(prefix)) {
            IFileProcessor depProc = layeredSystem.getFileProcessorForFileName(srcFile, this, BuildPhase.Process);
            // srcFile entries come back without suffixes... just ignore them.
            if (depProc != null) {
               String fullTypeName;
               if (!depProc.getPrependLayerPackage()) {
                  fullTypeName = FileUtil.removeExtension(srcFile).replace(FileUtil.FILE_SEPARATOR, ".");
               }
               else
                  fullTypeName = CTypeUtil.prefixPath(packagePrefix, FileUtil.removeExtension(srcFile).replace(FileUtil.FILE_SEPARATOR, "."));
               if (!layeredSystem.addMatchingCandidate(candidates, CTypeUtil.getPackageName(fullTypeName), CTypeUtil.getClassName(fullTypeName), retFullTypeName, max))
                  return false;
            }
         }
      }
      return true;
   }

   public boolean findMatchingGlobalNames(String prefix, Set<String> candidates, boolean retFullTypeName, boolean checkBaseLayers, int max) {
      if (!exportImports || excluded || disabled)
         return true;

      if (importsByName != null) {
         for (Map.Entry<String,ImportDeclaration> ent:importsByName.entrySet()) {
            String baseName = ent.getKey();
            ImportDeclaration decl = ent.getValue();
            if (baseName.startsWith(prefix)) {
               if (!layeredSystem.addMatchingCandidate(candidates, CTypeUtil.getPackageName(decl.identifier), baseName, retFullTypeName, max))
                  return false;
            }
         }
      }
      if (staticImportTypes != null) {
         for (Map.Entry<String,Object> ent:staticImportTypes.entrySet()) {
            String baseName = ent.getKey();
            if (baseName.startsWith(prefix)) {
               if (!layeredSystem.addMatchingCandidate(candidates, ModelUtil.getPackageName(ent.getValue()), baseName, retFullTypeName, max))
                  return false;
            }
         }
      }
      if (checkBaseLayers) {
         if (baseLayers != null) {
            for (Layer baseLayer:baseLayers) {
               if (!baseLayer.findMatchingGlobalNames(prefix, candidates, retFullTypeName, checkBaseLayers, max))
                  return false;
            }
         }
      }
      return true;
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
      StringBuilder sb = new StringBuilder();
      if (indexLayer)
         sb.append(" *index");
      else if (closed)
         sb.append(" *closed");
      if (excluded)
         sb.append(" *excluded");
      if (disabled)
         sb.append(" *disabled");
      if (packagePrefix == null || packagePrefix.length() == 0)
         return base + sb;
      else
         return base + "(" + packagePrefix + ")" + sb;
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

   public void refresh(long lastRefreshTime, ExecutionContext ctx, List<ModelUpdate> changedModels, UpdateInstanceInfo updateInfo, boolean active) {
      if (layerPathName == INVALID_LAYER_PATH)
         return;

      File layerDir = new File(layerPathName);
      if (!layerDir.isDirectory()) {
         layeredSystem.removeLayer(this, ctx);
         return;
      }
      for (int i = 0; i < srcDirs.size(); i++) {
         String srcDir = srcDirs.get(i);
         String relDir = null;
         int pathLen;
         if (srcDir.startsWith(layerPathName) && (pathLen = layerPathName.length()) + 1 < srcDir.length()) {
            relDir = srcDir.substring(pathLen+1);
         }
         refreshDir(srcDir, relDir, lastRefreshTime, ctx, changedModels, updateInfo, active, srcDirRootNames == null ? null : srcDirRootNames.get(i));
      }
   }

   public static class ModelUpdate {
      public ILanguageModel oldModel;
      public Object changedModel;
      public boolean removed;

      public ModelUpdate(ILanguageModel oldM, Object newM) {
         oldModel = oldM;
         changedModel = newM;
      }

      public String toString() {
         if (removed && oldModel != null)
            return "removed: " + oldModel.toString();
         if (changedModel != null)
            return changedModel.toString();
         return "<null>";
      }
   }

   public void refreshDir(String srcDir, String relDir, long lastRefreshTime, ExecutionContext ctx, List<ModelUpdate> changedModels, UpdateInstanceInfo updateInfo, boolean active, String srcRootName) {
      File f = new File(srcDir);
      long newTime = -1;
      String prefix = relDir == null ? "" : relDir;

      // Is this srcDir itself still there?  If not, remove the cached info from it
      if (!f.isDirectory()) {
         info("Layer src directory removed: " + this + " dir: " + f);
         removeSrcDir(srcDir, changedModels);
         return;
      }

      if (lastRefreshTime == -1 || (newTime = f.lastModified()) > lastRefreshTime) {
         // First update the src cache to pick up any new files, refresh any models we find in there when ctx is not null
         addSrcFilesToCache(f, prefix, null, srcRootName);
         findRemovedFiles(changedModels);
      }

      File[] files = f.listFiles();
      for (File subF:files) {
         String path = subF.getPath();

         IFileProcessor proc = null;
         if (subF.isDirectory()) {
            if (!excludedFile(subF.getName(), prefix)) {
               // Refresh anything that might have changed
               refreshDir(path, FileUtil.concat(relDir, subF.getName()), lastRefreshTime, ctx, changedModels, updateInfo, active, srcRootName);
            }
         }
         else if (Language.isParseable(path) || (proc = layeredSystem.getFileProcessorForFileName(path, this, BuildPhase.Process)) != null) {
            SrcEntry srcEnt = new SrcEntry(this, srcDir, relDir == null ? "" : relDir, subF.getName(), proc == null || proc.getPrependLayerPackage(), srcRootName);
            ILanguageModel oldModel = layeredSystem.getLanguageModel(srcEnt, active, null, active);
            long newLastModTime = new File(srcEnt.absFileName).lastModified();
            if (oldModel == null) {
               // The processedFileIndex only holds entries we processed.  If this file did not change from when we did the build, we just have to
               // decide to rebuild it.
               IFileProcessorResult oldFile = layeredSystem.processedFileIndex.get(srcEnt.absFileName);
               long lastTime = oldFile == null ?
                       layeredSystem.lastRefreshTime == -1 ? layeredSystem.buildStartTime : layeredSystem.lastRefreshTime :
                       oldFile.getLastModifiedTime();
               if (lastTime == -1 || newLastModTime > lastTime) {
                  if (model != null && model.isUnsavedModel())
                     System.out.println("*** Should we be refreshing an unsaved model?");
                  layeredSystem.refreshFile(srcEnt, this, active); // For non parseable files - do the file copy since the source file changed
               }
            }

            // We are refreshing any models which have changed on disk.  We used to also refresh error models here, but then any model which has an error gets refreshed all of the time
            // and that caused performance problems.  We should be restarting all open files to clear up any indirect references here now
            if ((lastRefreshTime != -1 && newLastModTime > lastRefreshTime) || (oldModel != null && ((newLastModTime > oldModel.getLastModifiedTime() && oldModel.getLastModifiedTime() != 0)))) {
               Object res = layeredSystem.refresh(srcEnt, ctx, updateInfo, active);
               if (res != null)
                  changedModels.add(new ModelUpdate(oldModel, res));
            }
         }
      }
   }

   /** Register the source file in any build layers including or following this one */
   public void addSrcFileIndex(String relFileName, byte[] hash, String ext, String absFileName) {
      if (!activated)
         throw new UnsupportedOperationException();
      for (int i = layerPosition; i < layeredSystem.layers.size(); i++) {
         Layer l = layeredSystem.layers.get(i);
         if (l.isBuildLayer()) {
            SrcIndexEntry sie = null;
            // Layer may not have been started yet - if we are building one layer to produce classes needed to
            // start the next layer
            if (l.buildSrcIndex == null)
               l.buildSrcIndex = l.readBuildSrcIndex();
            else {
               sie = l.buildSrcIndex.get(relFileName);
               if (sie != null) {
                  if (Arrays.equals(hash, sie.hash)) {
                     sie.inUse = true;
                     continue;
                  }
                  else {
                     if (layeredSystem.options.verboseLayerTypes && SrcIndexEntry.debugSrcIndexEntry) {
                        System.out.println("*** Adding mismatching entry in the same build layer?");
                        String oldVersion = new String(sie.fileBytes);
                        String newVersion = FileUtil.getFileAsString(absFileName);
                     }
                  }
               }
            }
            if (sie == null)
               sie = new SrcIndexEntry();
            sie.hash = hash;
            sie.inUse = true;
            sie.extension = ext;
            sie.layerName = getLayerName();
            sie.srcIndexLayer = l;

            if (SrcIndexEntry.debugSrcIndexEntry) {
               sie.updateDebugFileInfo(absFileName);
               if (sie.fileBytes == null)
                  System.err.println("*** No data for index entry for: " + absFileName);
               else if (!Arrays.equals(hash, StringUtil.computeHash(sie.fileBytes)))
                  System.err.println("*** internal error - hashes of file do no match from the start!");
            }

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
      if (!activated)
         throw new UnsupportedOperationException();
      for (int i = layerPosition; i < layeredSystem.layers.size(); i++) {
         Layer l = layeredSystem.layers.get(i);
         if (l.isBuildLayer()) {
            // A build layer that hasn't been built yet
            if (l.buildSrcIndex == null) {
               return null;
            }
            else {
               SrcIndexEntry ent = l.buildSrcIndex.get(relFileName);
               if (ent != null) {
                  ent.srcIndexLayer = l;
               }
               return ent;
            }
         }
      }
      return null;
   }

   /** Does this layer have an explicit extends on the other layer */
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

   public boolean extendsOrIsLayer(Layer other) {
      return this == other || this.extendsLayer(other);
   }

   void initReplacedLayers() {
      if (replacesLayerNames != null && activated) {
         for (String modLayerName:replacesLayerNames) {
            Layer modLayer = layeredSystem.getLayerByDirName(modLayerName);
            if (modLayer == null)
               System.err.println("*** Invalid replacesLayer call: replaces layer: " + modLayerName + " not found for downstream layer: " + getLayerName());
            else {
               if (modLayer.replacedByLayers == null)
                  modLayer.replacedByLayers = new ArrayList<Layer>();
               modLayer.replacedByLayers.add(this);
            }
         }
      }
   }

   /** Does this layer extend any layers which replaced features of layers we do extend */
   public boolean extendsReplacedLayer(Layer other) {
      initReplacedLayers();
      if (replacedByLayers != null) {
         for (Layer replacedByLayer:replacedByLayers) {
            if (other == replacedByLayer) {
               return true;
            }
         }
      }
      if (baseLayers == null)
         return false;
      for (int i = 0; i < baseLayers.size(); i++) {
         Layer base = baseLayers.get(i);
         if (base == other || base.extendsReplacedLayer(other))
            return true;
      }
      return false;
   }

   /** Does this layer modify any types which are in the other layer. */
   public boolean modifiedByLayer(Layer other) {
      if (extendsLayer(other))
         return true;

      if (extendsReplacedLayer(other))
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
   @Constant public List<Layer> getUsedByLayers() {
      List<Layer> theLayers = layeredSystem.layers;
      List<Layer> res = new ArrayList<Layer>();
      for (int i = layerPosition + 1; i < theLayers.size(); i++) {
         Layer other = theLayers.get(i);
         if (other.extendsLayer(this))
            res.add(other);
      }
      return res;
   }

   /** Returns the set of layers which extend this layer in the same order as they originally appeared in the list */
   @Constant public List<String> getUsedByLayerNames() {
      List<Layer> usedByLayers = getUsedByLayers();
      List<String> res = new ArrayList<String>();
      for (Layer l:usedByLayers)
         res.add(l.getLayerName());
      return res;
   }

   /** Returns a String identifying the set of layers which this layer depends on - including all layers for the build layer and the current layer */
   private String getDependentLayerNames() {
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (Layer l:layeredSystem.layers) {
         if (this == layeredSystem.buildLayer || extendsLayer(l) || l == this) {
            // Separate layers can be added non-disruptively since they store their build in a separate build-dir and the types cannot be modified by the other layers.
            if (l.buildSeparate)
               continue;
            if (!first) {
               sb.append(" ");
            }
            else
               first = false;
            // If a layer changes from dynamic to compiled we need to compile all
            // Maybe we could optimize this so we only mark the files in this layer as changed?
            if (l.dynamic)
              sb.append("dyn:");
            sb.append(l.getLayerName());
         }
      }
      return sb.toString();
   }

   private boolean needsRebuildForLayersChange(String newDependentNames, String oldDependentLayerNames) {
      if (!newDependentNames.equals(oldDependentLayerNames))
         return true;
      /* Otherwise - do a fuzzy overlap test here where we create sets for each group and walk each list looking for
         for non-additive combinations.
      String[] layerNames = StringUtil.split(oldDependentLayerNames, " ");
      TreeSet<String> oldNames = new TreeSet<String>(Arrays.asList(layerNames));
      for (Layer l:layeredSystem.layers) {
         if (this == layeredSystem.buildLayer || extendsLayer(l) || l == this) {

         }
      }
      */
      return false;
   }

   public boolean getBuildAllFiles() {
      return buildAllFiles || layeredSystem.options.buildAllFiles;
   }

   BuildInfo loadBuildInfo() {
      if (buildInfo != null)
         return buildInfo;

      if (this == layeredSystem.coreBuildLayer)
         return null;

      BuildInfo gd = null;

      String bfFileName = layeredSystem.getBuildInfoFile();
      String buildInfoFile = FileUtil.concat(buildSrcDir, bfFileName);

      String currentLayerNames = getDependentLayerNames();
      // Reset this file when you are compiling everything in case it gets corrupted
      if (!getBuildAllFiles()) {
         gd = (BuildInfo) layeredSystem.loadInstanceFromFile(buildInfoFile, FileUtil.concat("sc", "layer", bfFileName));
         if (gd == null) {
            if (layeredSystem.options.verbose)
               System.out.println("Missing BuildInfo file: " + buildInfoFile + " for runtime: " + layeredSystem.getRuntimeName());
            buildAllFiles = true;
         }
         //  (!StringUtil.equalStrings(currentLayerNames, gd.layerNames))
         else if (needsRebuildForLayersChange(currentLayerNames, gd.layerNames)) {
            if (layeredSystem.options.verbose)
               System.out.println("Layer names changed for build layer: " + getLayerName() + "\n   from: " + gd.layerNames + "\n   to:   " + currentLayerNames);
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
         if (!srcFile.equals(buildInfoFile) && !FileUtil.copyFile(srcFile, buildInfoFile, true))
            missingBuildInfo = true;

         if (prevBuildLayer.dynTypeIndex != null)
            dynTypeIndex = (HashSet<String>) prevBuildLayer.dynTypeIndex.clone();
         else
            dynTypeIndex = null;

         // Don't reset the file if -a is used when the buildInfo is copied to downstream build layer.
         copied = true;

         // For new layers, we do not have to build all files since there's nothing there
         if (missingBuildInfo && !newLayer) {
            if (layeredSystem.options.info && !layeredSystem.options.testVerifyMode)
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
         gd = (BuildInfo) layeredSystem.loadInstanceFromFile(FileUtil.concat(buildSrcDir, bfFileName), FileUtil.concat("sc", "layer", bfFileName));

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

      File buildDataFile = new File(FileUtil.concat(buildSrcDir, LayerConstants.BUILD_INFO_DATA_FILE));
      if (buildDataFile.canRead()) {
         if (getBuildAllFiles()) {
            buildDataFile.delete();
            gd.buildInfoData = null;
         }
         else {
            ObjectInputStream ois = null;
            FileInputStream fis = null;
            try {
               ois = new ObjectInputStream(fis = new FileInputStream(buildDataFile));
               BuildInfoData buildData = (BuildInfoData) ois.readObject();
               if (buildData != null) {
                  gd.buildInfoData = buildData;
               }
            }
            catch (InvalidClassException exc) {
               System.out.println("BuildInfoData.ser - version changed: " + this);
               buildDataFile.delete();
            }
            catch (IOException exc) {
               System.out.println("*** can't read BuildInfoData.ser: " + exc);
            }
            catch (ClassNotFoundException exc) {
               System.out.println("*** invalid BuildInfoData.ser file: " + exc);
            }
            finally {
               FileUtil.safeClose(ois);
               FileUtil.safeClose(fis);
            }
         }
      }
      else
         gd.buildInfoData = null;
      return gd;
   }

   public void saveBuildInfo(boolean writeBuildSrcIndex) {
      if (isBuildLayer()) {
         if (writeBuildSrcIndex)
            writeBuildSrcIndex(!layeredSystem.systemCompiled);

         if (buildInfo != null && buildInfo.changed) {
            LayerUtil.saveTypeToFixedTypeFile(FileUtil.concat(buildSrcDir, layeredSystem.getBuildInfoFile()), buildInfo,
                    "sc.layer.BuildInfo");
            File buildDataFile = new File(FileUtil.concat(buildSrcDir, LayerConstants.BUILD_INFO_DATA_FILE));
            if (buildInfo.buildInfoData == null) {
               if (buildDataFile.canRead())
                  buildDataFile.delete();
            }
            else {
               ObjectOutputStream os = null;
               try {
                  os = new ObjectOutputStream(new FileOutputStream(buildDataFile));
                  os.writeObject(buildInfo.buildInfoData);
               }
               catch (IOException exc) {
                  System.err.println("*** Can't write build data file: " + exc);
               }
               finally {
                  FileUtil.safeClose(os);
               }
            }
         }

         if (buildSrcDir != null)
            saveDynTypeIndex();
      }
   }

   public boolean matchesFilter(Collection<CodeType> codeTypes) {
      return (codeTypes == null || codeTypes.contains(codeType));
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

   public boolean transparentToLayer(Layer other, int mergeLayersCt) {
      if (other == this)
         return true;

      if (!transparent && mergeLayersCt == 0)
         return false;

      int nextMergeLayersCt = mergeLayersCt - 1;
      if (transparent)
         nextMergeLayersCt++;

      if (baseLayers == null)
         return false;
      for (int i = 0; i < baseLayers.size(); i++) {
         Layer base = baseLayers.get(i);
         if (base.transparentToLayer(other, nextMergeLayersCt))
            return true;
      }
      return false;
   }

   /** Returns the set of layers which are transparent onto this layer.  This is fairly brute force and obviously could be sped up a lot by just pre-computing the list */
   public List<Layer> getTransparentLayers() {
      List<Layer> res = null;
      List<Layer> layersList = getLayersList();
      for (int i = layerPosition+1; i < layersList.size(); i++) {
         Layer other = layersList.get(i);
         if (other.transparent && other.extendsLayer(this)) {
            if (res == null)
               res = new ArrayList<Layer>();
            res.add(other);
         }
      }
      return res;
   }

   // When this layer is selected return the set of layers that should be marked as 'current' and included in the editors view
   public List<Layer> getSelectedLayers() {
      ArrayList<Layer> res = new ArrayList<Layer>();
      res.add(this);
      if (transparent && baseLayers != null) {
         res.addAll(baseLayers);
      }
      return res;
   }

   /** To be visible in the editor, we cannot be extended only from hidden layers.  We need at least one visible, non-hidden layer to extend this layer */
   public boolean getVisibleInEditor() {
      if (hidden)
         return false;

      // TODO: call isSpecifiedLayer instead... even though its slightly different it probably will work the same.
      if (layeredSystem != null) {
         for (int i = 0; i < layeredSystem.specifiedLayers.size(); i++) {
            Layer specLayer = layeredSystem.specifiedLayers.get(i);
            if (specLayer == this)
               return true;
            if (!specLayer.hidden && specLayer.extendsLayer(this))
               return true;
         }
      }
      return false;
   }

   /** Is this a layer which was specified by the user (true) or a layer which was dragged in by a dependency (false) */
   public boolean isSpecifiedLayer() {
      for (int i = 0; i < layeredSystem.specifiedLayers.size(); i++) {
         Layer specLayer = layeredSystem.specifiedLayers.get(i);
         if (specLayer == this)
            return true;
         if (specLayer.extendsLayer(this))
            return true;
      }
      return false;
   }

   public void initSync() {
      int globalScopeId = GlobalScopeDefinition.getGlobalScopeDefinition().scopeId;
      SyncManager.addSyncInst(this, true, true, true, null, null);
   }

   public String toDetailString() {
      StringBuilder sb = new StringBuilder();
      if (dynamic)
         sb.append("dynamic ");
      if (hidden)
         sb.append("hidden ");
      if (configLayer)
         sb.append("configLayer ");
      if (defaultModifier != null) {
         sb.append(defaultModifier);
         sb.append(" ");
      }
      sb.append(toString());

      if (codeType != null) {
         sb.append(" codeType: ");
         sb.append(codeType);
      }

      sb.append("[");
      sb.append(layerPosition);
      sb.append("]");

      List depLayers = getUsedByLayers();
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
      // For annotation layers, there's meta-data such as JComponent's @Constant for size that we need to load to avoid warnings about data binding.
      // We also could fix that by adding the constant to ExtDynType info maybe via a constProps thing we add to the type and ExtDynType and @TypeSettings
      return !annotationLayer && ((finalLayer && (compiled || changedModelsDetected)) || (!liveDynamicTypes && layeredSystem.systemCompiled));
   }

   public RepositoryPackage getRepositoryPackage(String pkgName) {
      return layeredSystem.repositorySystem.getRepositoryPackage(pkgName);
   }

   public RepositoryPackage addRepositoryPackage(String url) {
      RepositorySystem repoSys = layeredSystem.repositorySystem;
      DependencyContext rootCtx = new DependencyContext(null, "Layer: " + toString() + " package tree");
      RepositoryPackage pkg = repoSys.addPackage(url, !disabled, rootCtx);
      if (repositoryPackages == null)
         repositoryPackages = new ArrayList<RepositoryPackage>();
      if (rootCtx.fromPkg != null && layeredSystem.options.verbose)
         verbose(rootCtx.dumpContextTree().toString());
      repositoryPackages.add(pkg);
      pkg.definedInLayer = this;
      return pkg;
   }

   public void installPackage(String url) {
      installPackages(new String[] {url});
   }

   public void installPackages(String[] urlList) {
      RepositoryPackage[] pkgList = new RepositoryPackage[urlList.length];
      int i = 0;
      for (String url:urlList) {
         pkgList[i] = addRepositoryPackage(url);
         i++;
      }
      if (!disabled) {
         installPackages(pkgList);
      }
   }

   public void installPackages(RepositoryPackage[] pkgList) {
      for (RepositoryPackage pkg : pkgList) {
         if (pkg.installedRoot != null) {
            String cp = pkg.getClassPath();
            if (cp == null || cp.trim().length() == 0) {
               continue;
            }
            if (classPath == null)
               classPath = cp;
            else
               classPath = classPath + FileUtil.PATH_SEPARATOR_CHAR + cp;
         }
         // TODO: should we also let a package add to the src path?  What about directory prefixes and file types?
      }
   }

   public RepositoryPackage addRepositoryPackage(String pkgName, String repositoryTypeName, String url, boolean unzip) {
      return addRepositoryPackage(pkgName, pkgName, repositoryTypeName, url, unzip);
   }

   /** Adds this repository package to the system.  If you are calling this from the start method, the repository is installed right
    * away.  If you call it in the initialize method, downstream layers have a chance to modify the package before it's installed - right
    * before the start method of the layer is called. */
   public RepositoryPackage addRepositoryPackage(String pkgName, String fileName, String repositoryTypeName, String url, boolean unzip) {
      RepositorySystem repoSys = layeredSystem.repositorySystem;
      IRepositoryManager mgr = repoSys.getRepositoryManager(repositoryTypeName);
      if (mgr != null) {
         if (repositoryPackages == null)
            repositoryPackages = new ArrayList<RepositoryPackage>();

         RepositorySource repoSrc = mgr.createRepositorySource(url, unzip, null);
         // Add this as a new source.  This will create the package if this is the first definition or add it
         // as a new source if it already exists.
         RepositoryPackage pkg = repoSys.addPackageSource(mgr, pkgName, fileName, repoSrc, started && !disabled, null);
         repositoryPackages.add(pkg);

         return pkg;
      }
      else
         error("Failed to add repository package: " + pkgName + " no RepositoryManager named: " + repositoryTypeName);
      return null;
   }

   public SrcEntry getSrcFileFromRelativeTypeName(String relDir, String subPath, String pkgPrefix, boolean srcOnly, boolean checkBaseLayers, boolean layerResolve) {
      if (excluded || disabled || subPath.length() == 0)
         return null;

      String relFilePath = relDir == null ? subPath : FileUtil.concat(relDir, subPath);
      boolean packageMatches;

      if (packagePrefix.length() > 0) {
         if (pkgPrefix == null || !pkgPrefix.startsWith(packagePrefix)) {
            relFilePath = subPath; // go back to non-prefixed version
            packageMatches = false;
         }
         else {
            int pkgLen = packagePrefix.length();
            if (pkgLen == relFilePath.length())
               return null;
            relFilePath = relFilePath.substring(pkgLen + 1);
            packageMatches = true;
         }
      }
      else
         packageMatches = true;

      File res = findSrcFile(relFilePath, layerResolve);
      LayeredSystem sys = getLayeredSystem();
      if (res != null) {
         String path = res.getPath();
         IFileProcessor proc = sys.getFileProcessorForFileName(path, this, BuildPhase.Process);
         // Some file types (i.e. web.xml, vdoc) do not prepend the package.  We still want to look up these
         // types by their name in the layer path tree, but only return them if the type name should match
         // If the package happens to match, it is also a viable match
         if ((proc == null && packageMatches) || (proc != null && proc.getPrependLayerPackage() == packageMatches) || packageMatches) {
            SrcEntry ent = new SrcEntry(this, path, relFilePath + "." + FileUtil.getExtension(path));
            ent.prependPackage = proc != null && proc.getPrependLayerPackage();
            return ent;
         }
      }
      else if (!srcOnly && packageMatches) {
         relFilePath = subPath + ".class";
         res = findClassFile(relFilePath, false, false);
         if (res == null)
            res = findClassFile(relFilePath, false, true);
         if (res != null)
            return new SrcEntry(this, res.getPath(), relFilePath);
      }

      if (checkBaseLayers) {
         if (baseLayers != null) {
            for (Layer baseLayer:baseLayers) {
               SrcEntry baseEnt = baseLayer.getSrcFileFromRelativeTypeName(relDir, subPath, pkgPrefix, srcOnly, true, layerResolve);
               if (baseEnt != null)
                  return baseEnt;
            }
         }
      }
      return null;
   }

   SrcEntry getSrcEntryFromFile(File res, String relFilePath) {
      String path = res.getPath();
      IFileProcessor proc = layeredSystem.getFileProcessorForFileName(path, this, BuildPhase.Process);
      if (proc != null) {
         SrcEntry ent = new SrcEntry(this, path, relFilePath + "." + FileUtil.getExtension(path));
         ent.prependPackage = proc.getPrependLayerPackage();
         return ent;
      }
      return null;
   }

   public String getUnderscoreName() {
      return getLayerName().replace('.', '_');
   }

   public static String getLayerNameFromUniqueUnderscoreName(String uname) {
      return uname.replace("__", ".");
   }
   public static String getUniqueUnderscoreName(String layerName) {
      return layerName.replace(".", "__");
   }

   public void registerAnnotationProcessor(String annotationTypeName, IAnnotationProcessor processor) {
      if (annotationProcessors == null)
         annotationProcessors = new HashMap<String,IAnnotationProcessor>();
      IAnnotationProcessor old = annotationProcessors.put(annotationTypeName, processor);
      if (processor.getProcessorName() == null)
         processor.setProcessorName("@" + CTypeUtil.getClassName(annotationTypeName));
      if (old != null && layeredSystem.options.verbose) {
         verbose("Annotation processor for: " + annotationTypeName + " replaced: " + old + " with: " + processor);
      }
   }

   /**
    * When encountering scope&lt;name&gt; in the code as an annotation for a type, the IScopeProcessor will allow
    * the framework developer the ability to customize how the getX, setX, methods of the object or property are
    * generated.  You can inject additional interfaces and fields into the type.  Additional parameters to the
    * constructor which are obtained in the current context in which this scope is valid.  Scopes let you flexibly
    * separate the names from how they are implemented in code - supporting iteration and other advanced constructs
    * at both the runtime and compile time contexts.
    */
   public void registerScopeProcessor(String scopeName, IScopeProcessor processor) {
      if (scopeProcessors == null)
         scopeProcessors = new HashMap<String,IScopeProcessor>();
      IScopeProcessor old = scopeProcessors.put(scopeName, processor);
      if (old != null && layeredSystem.options.verbose) {
         info("Scope processor for: " + scopeName + " replaced: " + old + " with: " + processor);
      }
      String scopeAlias = scopeAliases == null ? null : scopeAliases.remove(scopeName);
      if (scopeAlias != null) {
         verbose("Scope processor replaced scope alias: " + scopeName + ": " + scopeAlias);
      }
   }

   public void registerScopeAlias(String newScopeName, String aliasedToName) {
      if (scopeAliases == null)
         scopeAliases = new TreeMap<String,String>();
      String old = scopeAliases.put(newScopeName, aliasedToName);
      if (old != null && !old.equals(aliasedToName))
         verbose("Scope alias: " + aliasedToName + " replaced old alias: " + old + " for scope: " + newScopeName);
   }

   public void registerFileProcessor(IFileProcessor proc, String ext) {
      layeredSystem.registerFileProcessor(ext, proc, this);
   }

   public void registerLanguage(Language lang, String ext) {
      if (ext == null)
         throw new IllegalArgumentException("Null extension pass to registerLanguage");
      //Language.registerLanguage(lang, ext);
      layeredSystem.registerFileProcessor(ext, lang, this);
      // Files that are not parsed are not put into the srcDirCache and so don't get put into the type index
      if (lang.isParsed()) {
         if (langExtensions == null) {
            langExtensions = new String[]{ext};
         } else {
            String[] oldList = langExtensions;
            String[] newList = new String[oldList.length + 1];
            System.arraycopy(oldList, 0, newList, 0, oldList.length);
            newList[oldList.length] = ext;
            langExtensions = newList;
         }
         if (layerTypeIndex != null)
            layerTypeIndex.langExtensions = langExtensions;
      }
   }

   public String getScopeNames() {
      if (scopeProcessors == null) return "";
      return scopeProcessors.keySet().toString();
   }

   public void initAllTypeIndex() {
      if (layerTypesStarted || disabled || excluded)
         return;
      layerTypesStarted = true;
      if (baseLayers != null) {
         for (Layer base : baseLayers) {
            base.initAllTypeIndex();
         }
      }
      checkIfStarted();
      if (layerTypeIndex == null)
         initTypeIndex();
      // Just walk through and start each of the types in this layer  TODO - include inner types in the type index?
      startAllTypes();
      saveTypeIndex();
      LayerListTypeIndex listTypeIndex = activated ? layeredSystem.typeIndex.activeTypeIndex : layeredSystem.typeIndex.inactiveTypeIndex;
      String name = getLayerName();
      if (name != null) {
         listTypeIndex.addLayerTypeIndex(name, layerTypeIndex);
      }
   }

   public boolean hasDefinitionForType(String typeName) {
      return getSrcEntryForType(typeName) != null;
   }

   public SrcEntry getSrcEntryForType(String typeName) {
      String subPath = typeName.replace(".", FileUtil.FILE_SEPARATOR);
      return getSrcFileFromTypeName(typeName, true, true, subPath, false);
   }

   public void disableLayer() {
      layeredSystem.disableLayer(this);
   }

   public void destroyLayer() {
      removed = true;
      stop();
      layerModels = null;
      if (baseLayers != null)
         baseLayers.clear();
      relSrcIndex = null;
      srcDirs = null;
      srcDirCache = null;
      layeredSystem = null;
      model = null;
      importsByName = null;
      staticImportTypes = null;
      globalPackages = null;
      layerTypeIndex = null;
      origBuildLayer = null;
      buildInfo = null;
      typeIndexRestored = false;
      layerTypesStarted = false;
   }

   /** Called from within a layer definition file to associate a SrcPathType (e.g. web, or resource) with a given layer directory. */
   public void addSrcPath(String srcPath, String srcPathType) {
      addSrcPath(srcPath, srcPathType, null, null);
   }

   public void setPathTypeBuildPrefix(String pathTypeName, String buildPrefix) {
      addSrcPath(null, pathTypeName, buildPrefix, null);
   }

   /**
    * Adds a new src directory to be searched for source files to an existing srcPathType or optionally creates a new srcPathType
    * when called with a srcPathType, buildPrefix, and fromPathType.
    * The srcPathType is a name that defines rules for processing src files in paths of that type.
    * For example for src files in the web directory, the srcPathType is 'web'.  If you want your files to be treated as ordinary source files
    * like Java files using the default build dir use null for the srcPathType.  Each srcPathType has an optional buildPrefix - prepended
    * onto the path name of the file, relative to the srcPath directory.
    * Specify a fromPathType to inherit the processors from a previous type, but to set a new buildPrefix for a given srcPath directory.
    */
   public void addSrcPath(String srcPath, String srcPathType, String buildPrefix, String fromPathType) {
      boolean abs;
      // A null srcPath just sets the buildPrefix
      if (srcPath != null) {
         abs = FileUtil.isAbsolutePath(srcPath);
         // Relative paths will already be found in the default src path (layerPathName) - so no need to add them here
         if (abs) {
            if (this.srcPath != null) {
               this.srcPath = this.srcPath + FileUtil.PATH_SEPARATOR_CHAR + srcPath;
            } else
               this.srcPath = layerPathName + FileUtil.PATH_SEPARATOR_CHAR + srcPath;
         }
      }
      else
          abs = false;
      if (srcPathType != null || buildPrefix != null) {
         SrcPathType type = new SrcPathType();
         type.pathTypeName = srcPathType;
         type.srcPath = srcPath;
         type.buildPrefix = buildPrefix;
         type.relative = !abs;
         type.fromPathTypeName = fromPathType;
         if (srcPathTypes == null)
            srcPathTypes = new ArrayList<SrcPathType>();
         srcPathTypes.add(type);
      }
   }
   public String getSrcPathTypeName(String fileName, boolean abs) {
      SrcPathType type = getSrcPathType(fileName, abs);
      return type == null ? null : type.pathTypeName;
   }

   public String getSrcPathBuildPrefix(String pathTypeName) {
      SrcPathType type = getSrcPathTypeByName(pathTypeName, true);
      return type == null ? null : type.buildPrefix;
   }

   public SrcPathType getSrcPathTypeByName(String pathTypeName, boolean buildPrefix) {
      if (srcPathTypes != null) {
         for (SrcPathType srcPathType : srcPathTypes) {
            if (buildPrefix) {
               if (srcPathType.buildPrefix == null)
                  continue;
            }
            // else - return the first matching entry
            if (StringUtil.equalStrings(srcPathType.pathTypeName, pathTypeName)) {
               return srcPathType;
            }
         }
      }
      if (baseLayers != null) {
         for (Layer baseLayer : baseLayers) {
            SrcPathType res = baseLayer.getSrcPathTypeByName(pathTypeName, buildPrefix);
            if (res != null)
               return res;
         }
      }
      // If we are the final build layer, we are built with all of the layers in the stack and so
      // need to be able to recognize the path types
      if (activated && buildLayer && this == layeredSystem.buildLayer) {
         List<Layer> layers = layeredSystem.layers;
         if (layerPosition != layers.size() - 1)
            System.out.println("*** Warning - build layer is not the last layer");
         if (layerPosition >= layers.size())
            return null;
         for (int i = layerPosition - 1; i >= 0; i--) {
            Layer baseLayer = layers.get(i);
            if (baseLayer == this)
               continue;
            SrcPathType res = baseLayer.getSrcPathTypeByName(pathTypeName, buildPrefix);
            if (res != null)
               return res;
         }
      }
      return null;
   }

   public SrcPathType getSrcPathType(String fileName, boolean abs) {
      if (srcPathTypes != null) {
         for (SrcPathType srcPathType : srcPathTypes) {
            // This entry just sets the buildPrefix
            if (srcPathType.srcPath == null)
               continue;
            if (srcPathType.relative) {
               if (abs) {
                  if (fileName.startsWith(layerPathName)) {
                     if (prefixMatches(srcPathType.srcPath, fileName.substring(layerPathName.length() + 1)))
                        return srcPathType;
                  }
               } else {
                  if (prefixMatches(srcPathType.srcPath, fileName))
                     return srcPathType;
               }
            } else {
               if (abs) {
                  if (prefixMatches(srcPathType.srcPath, fileName))
                     return srcPathType;
               }
            }
         }
      }
      if (baseLayers != null) {
         //boolean converted = false;
         // If this file is in this layer, before we check the base layers, make sure to strip turn it into a relative search
         if (abs && fileName.startsWith(layerPathName) && !fileName.equals(layerPathName)) {
            fileName = fileName.substring(layerPathName.length() + 1);
            abs = false;
         }
         for (Layer baseLayer:baseLayers) {
            SrcPathType res = baseLayer.getSrcPathType(fileName, abs);
            if (res != null) {
               return res;
            }
         }
      }
      return null;
   }

   public String getSrcPathTypeName(SrcEntry srcEnt) {
      for (SrcPathType srcPathType:srcPathTypes) {
         if (srcPathType.relative) {
            if (prefixMatches(srcPathType.srcPath, srcEnt.getRelDir()))
               return srcPathType.pathTypeName;
         }
         else {
            if (prefixMatches(srcPathType.srcPath, srcEnt.absFileName))
               return srcPathType.pathTypeName;
         }
      }
      if (baseLayers != null) {
         for (Layer baseLayer:baseLayers) {
            String res = baseLayer.getSrcPathTypeName(srcEnt);
            if (res != null)
               return res;
         }
      }
      return null;
   }

   private boolean prefixMatches(String prefix, String path) {
      // Assumes prefix has a trailing '/' and we are not matching directories
      return path.startsWith(prefix);
   }

   private void initDynObj() {
      if (dynObj == null) {
         if (model != null)
            dynObj = new DynObject(model.getModelTypeDeclaration());
      }
   }

   boolean appendClassPath(StringBuilder sb, boolean appendBuildDir, String useBuildDir, boolean addOrigBuild) {
      if (classPath != null && !disabled && !excluded) {
         for (int j = 0; j < classDirs.size(); j++) {
            String dir = classDirs.get(j);
            LayerUtil.addQuotedPath(sb, dir);
         }
      }
      String layerClasses = getBuildClassesDir();
      if (appendBuildDir && isBuildLayer() && !layerClasses.equals(useBuildDir)) {
         LayerUtil.addQuotedPath(sb, layerClasses);
         if (layerClasses.equals(layeredSystem.origBuildDir))
            addOrigBuild = false;
      }
      return addOrigBuild;
   }

   void appendBuildURLs(ArrayList<URL> urls) {
      // Add the main build dir for this layer
      urls.add(FileUtil.newFileURL(LayerUtil.appendSlashIfNecessary(getBuildClassesDir())));
      compiledInClassPath = true;
      // Add the buildSrcDir to the classPath if the framework requires it (e.g. gwt)
      if (!buildDir.equals(buildSrcDir) && layeredSystem.includeSrcInClassPath)
         urls.add(FileUtil.newFileURL(LayerUtil.appendSlashIfNecessary(buildSrcDir)));
   }

   public void addClassPathEntry(String entry) {
      if (classPathCache == null)
         classPathCache = new TreeSet<String>();
      classPathCache.add(entry);
   }

   public boolean hasClassPathEntry(String entry) {
      if (classPathCache != null && classPathCache.contains(entry))
         return true;
      if (baseLayers != null) {
         for (Layer baseLayer:baseLayers) {
            if (baseLayer.hasClassPathEntry(entry))
               return true;
         }
      }
      return false;
   }

   public void markClosed(boolean val, boolean closePeers) {
      boolean changed = closed != val;
      closed = val;
      // In some cases, when we close a layer, we also need to close the same layer in other runtimes (if it exists there)
      // That's because we need to open the layers in other runtimes when starting a type so we can resolve any remote methods.
      if (closePeers) {
         List<LayeredSystem> peerSystems = layeredSystem.peerSystems;
         if (peerSystems != null) {
            for (LayeredSystem peer:peerSystems) {
               Layer peerLayer = peer.lookupInactiveLayer(getLayerName(), false, true);
               if (peerLayer != null)
                  peerLayer.markClosed(val, false);
            }
         }
      }
      if (baseLayers != null) {
         for (Layer base:baseLayers)
            base.markClosed(val, closePeers);
      }
      if (changed && !val) {
         initReplacedTypes();
      }
   }

   void refreshBoundTypes(int flags, HashSet<Layer> visited) {
      if (visited.contains(this))
         return;
      visited.add(this);
      if (layerModels != null) {
         // Need to refresh all layerModels currently cached.  As we refresh them, we might add to this list so
         // we make a copy of the list upfront.
         ArrayList<IdentityWrapper<ILanguageModel>> modelList = new ArrayList<IdentityWrapper<ILanguageModel>>();
         modelList.addAll(layerModels);
         for (IdentityWrapper<ILanguageModel> wrap:modelList) {
            wrap.wrapped.refreshBoundTypes(flags);
         }
      }
      if (baseLayers != null) {
         for (Layer baseLayer:baseLayers) {
            baseLayer.refreshBoundTypes(flags, visited);
         }
      }
   }

   void flushTypeCache(HashSet<Layer> visited) {
      if (visited.contains(this))
         return;
      visited.add(this);
      if (layerModels != null) {
         for (IdentityWrapper<ILanguageModel> wrap:layerModels) {
            wrap.wrapped.flushTypeCache();
         }
      }
      if (baseLayers != null) {
         for (Layer baseLayer:baseLayers) {
            baseLayer.flushTypeCache(visited);
         }
      }
   }

   public void fileRenamed(SrcEntry oldFile, SrcEntry newFile) {
      File ent = srcDirCache.remove(oldFile.relFileName);
      if (ent != null) {
         srcDirCache.remove(FileUtil.removeExtension(oldFile.relFileName));
         File newFileRef = new File(newFile.absFileName);
         srcDirCache.put(newFile.relFileName, newFileRef);
         srcDirCache.put(FileUtil.removeExtension(newFile.relFileName), newFileRef);
      }
   }

   void findRemovedFiles(List<ModelUpdate> changedModels) {
      ArrayList<IdentityWrapper<ILanguageModel>> toRem = new ArrayList<IdentityWrapper<ILanguageModel>>();
      for (IdentityWrapper<ILanguageModel> layerWrapper:layerModels) {
         ILanguageModel model = layerWrapper.wrapped;
         SrcEntry srcFile = model.getSrcFile();
         if (srcFile != null && !srcFile.canRead() && !model.isUnsavedModel()) {
            if (changedModels != null) {
               ModelUpdate removedModel = new ModelUpdate(model, null);
               removedModel.removed = true;
               changedModels.add(removedModel);
            }
            toRem.add(layerWrapper);
            removeSrcFile(model.getSrcFile());

            verbose("Model file removed: " + srcFile);
         }
      }
      for (IdentityWrapper<ILanguageModel> remModel:toRem)
         layerModels.remove(remModel);
   }

   boolean cacheForRefLayer() {
      return this != Layer.ANY_LAYER && this != Layer.ANY_INACTIVE_LAYER && this != Layer.ANY_OPEN_INACTIVE_LAYER && this.activated;
   }

   public void addTypeGroupDependency(String relFileName, String typeName, String typeGroupName) {
      if (activated) {
         layeredSystem.addTypeGroupDependency(this, relFileName, typeName, typeGroupName);
      }
   }

   /**
    * In case there's a file which generates an error that's not part of the project - i.e. gets included via a src-path
    * entry like scrt-core-src.jar for Javascript code gen, we still need to report that file as one having a syntax error
    */
   public void addErrorFile(SrcEntry srcEnt) {
      if (buildState != null && !buildState.errorFiles.contains(srcEnt))
         buildState.errorFiles.add(srcEnt);
   }

   HashMap<String,Properties> propertiesCache;

   /**
    * Called with propertiesFile="build" to return propName in the layer path build.properties.
    * Returns the layer property of any layer under this one in the stack.  Note that this searches all layers, not just base layers because
    * we only use it now for 'build properties' that need to do a merge of everything.   In some cases, we only use baseLayers because of the
    * desire for 'static typing' and support of resolution in the IDE where there is no build layer.
    */
   public String getLayerProperty(String propertiesFile, String propName) {
      Map<String,Properties> globalProps = layeredSystem.options.layerProps;
      if (globalProps != null) {
         Properties gfp = globalProps.get(propertiesFile);
         if (gfp != null) {
            String propVal = gfp.getProperty(propName);
            if (propVal != null)
               return propVal;
         }
      }
      List<Layer> layersList = getLayersList();
      for (int i = layerPosition; i >= 0; i--) {
         Layer layer = layersList.get(i);
         String res = layer.getProperty(propertiesFile, propName);
         if (res != null)
            return res;
      }
      return null;
   }

   public String getProperty(String propertiesFile, String propName) {
      SrcEntry propSrcEnt = getLayerFileFromRelName(FileUtil.addExtension(propertiesFile, "properties"), false, true);
      if (propSrcEnt != null) {
         if (propertiesCache == null)
            propertiesCache = new HashMap<String,Properties>();
         String fileName = propSrcEnt.absFileName;
         Properties props = propertiesCache.get(fileName);
         if (props == null) {
            props = new Properties();
            try {
               props.load(new InputStreamReader(new FileInputStream(fileName)));
            }
            catch (IOException exc) {
               error("*** Unable to read properties file: " + propSrcEnt.absFileName + " exc: " + exc);
            }
            propertiesCache.put(fileName, props);
         }
         String res = props.getProperty(propName);
         if (res != null)
            return res;
      }
      return null;
   }

   public String findSrcFileNameFromRelName(String relName) {
      SrcEntry ent = getLayerFileFromRelName(relName, true, true);
      if (ent == null)
         return null;
      return ent.absFileName;
   }

   public SrcEntry getLayerFileFromRelName(String relName, boolean checkBaseLayers, boolean byPosition) {
      if (layerFileCache != null) {
         String absName = layerFileCache.get(relName);
         if (absName != null) {
            return new SrcEntry(this, absName, relName, false);
         }
      }
      if (checkBaseLayers)
         return getBaseLayerFileFromRelName(relName, byPosition);
      return null;
   }

   public SrcEntry getBaseLayerFileFromRelName(String relName, boolean byPosition) {
      SrcEntry res;
      List<Layer> layersList = getLayersList();
      if (byPosition) {
         if (layerPosition < layersList.size()) {
            // Pick any layer before this one in the stack - used for testScripts etc. which need to be merged based on the layers stack, not the baseLayers so a mixin layer can be inserted
            for (int i = layerPosition - 1; i >= 0; i--) {
               Layer baseLayer = layersList.get(i);
               res = baseLayer.getLayerFileFromRelName(relName, false, true);
               if (res != null)
                  return res;
            }
         }
      }
      else if (baseLayers != null) {
         for (Layer baseLayer:baseLayers) {
            res = baseLayer.getLayerFileFromRelName(relName, true, false);
            if (res != null)
               return res;
         }
      }
      return null;
   }

   public SrcEntry getSubLayerFileFromRelName(String relName, boolean byPosition) {
      SrcEntry res;
      List<Layer> layersList = getLayersList();
      if (layerPosition < layersList.size()) {
         // Pick any layer after this one in the stack - used for testScripts etc. which need to be merged based on the layers stack, not the baseLayers so a mixin layer can be inserted
         for (int i = layerPosition + 1; i < layersList.size(); i++) {
            Layer subLayer = layersList.get(i);
            res = subLayer.getLayerFileFromRelName(relName, false, true);
            if (res != null) {
               if (byPosition || subLayer.extendsLayer(this))
                  return res;
            }
         }
      }
      return null;
   }

   public boolean checkRemovedDirectory(String dirPath) {
      File layerDir = new File(layerPathName);
      // The layer itself was removed so let's start there :)
      if (!layerDir.isDirectory()) {
         return false;
      }
      if (srcDirs.contains(dirPath)) {
         layeredSystem.scheduleRefresh();
      }
      return true;
   }

   public void removeSrcDir(String srcDir, List<ModelUpdate> changedModels) {
      int srcDirIx = topLevelSrcDirs.indexOf(srcDir);
      if (srcDirIx != -1) {
         topLevelSrcDirs.remove(srcDir);
         if (srcRootNames != null)
            srcRootNames.remove(srcDirIx);
      }
      findRemovedFiles(changedModels);
   }


   /** Used by both the command line and layers view to append a description for the layer based on what you are interested in */
   public boolean appendDetailString(StringBuilder sb, boolean first, boolean details, boolean runtime, boolean sync) {
      if (details) {
         //boolean useHidden = !getVisibleInEditor(); // This is called from LayersView without the dynLock - not safe to call extendsLayer. It would be find to do this from the
         // command line though
         boolean useHidden = hidden;
         if (useHidden)
            first = opAppend(sb, "hidden", first);
         if (buildSeparate)
            first = opAppend(sb, "build separate", first);
         if (isBuildLayer())
            first = opAppend(sb, "build", first);
         if (annotationLayer)
            first = opAppend(sb, "annotation", first);
         if (finalLayer)
            first = opAppend(sb, "finalLayer", first);
         if (codeType != CodeType.Application)
            first = opAppend(sb, "codeType=" + codeType, first);
      }
      if (runtime) {
         if (excludeRuntimes != null)
            first = opAppend(sb, " excludes: " + excludeRuntimes, first);
         if (includeRuntimes != null)
            first = opAppend(sb, " includes: " + includeRuntimes, first);
         if (hasDefinedRuntime)
            first = opAppend(sb, " only: " + (definedRuntime == null ? "java" : definedRuntime), first);
         if (excludeProcesses != null)
            first = opAppend(sb, " excludes: " + excludeProcesses, first);
         if (includeProcesses != null)
            first = opAppend(sb, " includes: " + includeProcesses, first);
         if (hasDefinedProcess)
            first = opAppend(sb, " only: " + (definedProcess == null ? "<default>" : definedProcess), first);
         if (includeForInit)
            first = opAppend(sb, " includeForInit", first);
      }
      if (sync) {
         if (syncMode != SyncMode.Disabled && syncMode != null)
            first = opAppend(sb, " syncMode=" + syncMode.toString(), first);
      }
      return first;
   }

   IFileProcessor.FileEnabledState processorEnabledForPath(IFileProcessor proc, String pathName, boolean abs) {
      String[] srcPathTypes = proc.getSrcPathTypes();

      SrcPathType filePathType = getSrcPathType(pathName, abs);
      if (srcPathTypes != null) {
         String filePathTypeName = filePathType == null ? null : filePathType.pathTypeName;
         for (int i = 0; i < srcPathTypes.length; i++) {
            String procSrcTypeName = srcPathTypes[i];
            boolean res = StringUtil.equalStrings(srcPathTypes[i], filePathTypeName);
            if (res)
               return IFileProcessor.FileEnabledState.Enabled;
            if (filePathType != null && filePathType.inheritsFrom(this, procSrcTypeName)) {
               return IFileProcessor.FileEnabledState.Enabled;
            }
         }
      }
      else if (filePathType == null)
         return IFileProcessor.FileEnabledState.Enabled;
      return IFileProcessor.FileEnabledState.NotEnabled;
   }

   public DBDataSource getDefaultDataSource() {
      if (defaultDataSource != null)
         return defaultDataSource;
      if (baseLayers != null) {
         for (int i = 0; i < baseLayers.size(); i++) {
            DBDataSource res = baseLayers.get(i).getDefaultDataSource();
            if (res != null)
               return res;
         }
      }
      return layeredSystem.defaultDataSource;
   }
}
