/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.bind.BindingContext;
import sc.classfile.CFClass;
import sc.js.URLPath;
import sc.lang.js.JSLanguage;
import sc.lang.js.JSRuntimeProcessor;
import sc.lang.sc.SCModel;
import sc.dyn.*;
import sc.lang.sc.PropertyAssignment;
import sc.lang.template.Template;
import sc.layer.deps.*;
import sc.lifecycle.ILifecycle;
import sc.obj.*;
import sc.parser.*;
import sc.repos.RepositoryStore;
import sc.repos.RepositorySystem;
import sc.sync.SyncManager;
import sc.sync.SyncPropOptions;
import sc.sync.SyncProperties;
import sc.type.*;
import sc.util.*;
import sc.bind.IListener;
import sc.lang.*;
import sc.lang.sc.IScopeProcessor;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.java.*;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.regex.Pattern;

import static sc.type.RTypeUtil.systemClasses;

/**
 * The layered system manages the collection of layers or modules in an application.
 * The layers can be in one of two states: inactive, for reading and editing project files
 * or active, for compiling and running the system.  Each layer contains a slice
 * of application code - a single tree organized in a hierarchical name space of definitions.  Definitions
 * create classes, objects, or modify classes or objects.  These layers are merged together to create the
 * current application's program state.
 * <p>
 * The program state can be processed to produce Java code, then compiled to produce .class files.
 * This class has a main method which implements command line functionality to process and compile Java files.
 * </p>
 * <p>
 * The last layer in the layer stack is considered the primary 'build layer', which is used to store the compiled result.  You can also
 * specify additional layers to include in the application ahead of the primary layer.  All of the layers
 * extended by these layers are expanded, duplicates are removed and the list is sorted into dependency order
 * producing the list of layers that define the application's initial state.  Typically the last layer is the last one you specify
 * on the command line, unless you specify an additional layer which depends on that layer (in this case, specifying the last layer
 * is redundant anyway since it's picked up automatically when you include a layer which extends it).
 * </p>
 * <p>
 * The layered system is used at build time to
 * generate the class path for the compiler, find source files to be compiled, and manage the compilation
 * process.  Tools also use the layered system to read and manage program elements, and (eventually) interpret layers.
 * Generated applications typically do not depend on the layered system unless they need to interpret code,
 * use the command line interpreter, or other tools to edit the program.
 * </p>
 * <p>
 * The layered system has a main for processing and compiling applications.  The processing phase generates Java
 * code as needed for any language extensions encountered and places these files into the build directory.  At
 * any given time, the layered system is modifying files in a single build directory.  By default, this is the
 * directory named build in the top-level layer's directory.  This goes counter to standard Java convention which
 * is to have src and build directories at the same level.  In this approach, a layer is completely self-contained
 * in a single directory reducing the need for Jar files in making it easy to compartmentalize components.  For large
 * java projects, you can also build layers with traditional Java project file organizations.  The framework lets you
 * copy, override, and replace files and generates a corresponding traditional Java project.  It's straightforward to import
 * an existing project, and split it apart into more reusable layers which can then be reassembled into broader configurations.
 * </p>
 * <p>
 * If a Java file does not use any language extensions
 * it can optionally be copied to the build directory along with the generated code so that you have one complete
 * source directory with all source, or it can be compiled directly from where it lives in the source tree.
 * After generating any Java files, any modified files are then compiled using a standard java compiler.
 * <p/>
 * See the usage() method for details on options.
 * Note that the LayeredSystem is also used in two other contexts - in the dynamic runtime and when using a program editor such as an IDE or
 * dynamic application editing system.
 * <p>
 * When you run an application which uses the dynamic runtime, the LayeredSystem
 * serves the same purposes.  It compiles the compiled layers, and then manages the interpretation of the dynamic layers.  Dynamic layers are stored
 * at the end of the 'layers' list, always after the compiled layers.
 * </p>
 * For an IDE, typically the LayeredSystem is constructed by the IDE to form a StrataCode project.  The LayeredSystem provides the ability to
 * manage a set of inactiveLayers, which it lazily populates as layers are required to satisfy editing operations.  It maintains a type index
 * for all of the layers in the layer path so it can still do global structured editing operations without having to parse all of the source to satisfy
 * "find usages" type queries or walk backwards in the type inheritance tree.
 * <p>
 * LayeredSystems can optionally have "peer LayeredSystems" which represent the set of layers for a separate process, possibly in a separate runtime.
 * This is helpful both when using an IDE because we have models of all of the processes and can combine them or separate them as needed.
 * It's helpful in the code-generation phase because we have knowledge of the overlapping parts of the model, and can access the meta-data from both
 * versions when compiling the code - e.g. the initSyncProperties feature.
 * </p>
 *
 * <p>
 *  TODO: this is a large class with potential for improved organization
 *  BuildConfig - stored as 'config' in both LayeredSystem and Layers.  So change system.registerFilePatternProcessor to config.register... same for addPostBuildCommand etc.
 *  TypeIndex (buildReverseTypeIndex and maybe more code related to this feature),
 *  BuildState (preInitModels, initModels, etc),
 *  TypeCache (typesByName, innerTypeCache, subTypesByType, ...),
 *  RuntimeState (instancesByType, objectNameIndex, )
 *  PackageIndex (packageIndex)
 * </p>
 *
 * <p>
 *  Unfortunately it's probably never going to be small... there's a lot of code to customize and manage at the system level.
 *  Once you understand the keywords to search for, you can find the chunks of code dealing with that feature as it's somewhat
 *  modularized by location in the file.  So far, it's been ok to maintain this way except when IntelliJ gets slow during editing.
 * </p>
 *
 * <p>
 *  If we required scc to build, we could separate aspects of the LayeredSystem into layers (e.g. sync, data binding,
 *  schtml support, inactive, active) and use StrataCode to build itself into different versions that support the same or subsets of the current public API.
 *  We could build up functionality via sub-classes like CoreSystem (for all of the basic info and apis used in the layer definition file), LayeredSystem
 *  for the rest.
 * </p>
 */
@sc.js.JSSettings(jsModuleFile="js/sclayer.js", prefixAlias="sc_")
public class LayeredSystem implements LayerConstants, INameContext, IRDynamicSystem, IClassResolver {
   /** The list of runtimes required to execute this stack of layers (e.g. javascript and java).  If java is in the list, it will be the first one and represented by a "null" entry. */
   public static ArrayList<IRuntimeProcessor> runtimes;

   /** The list of processes to build for this stack of layers */
   public static ArrayList<IProcessDefinition> processes;

   private static boolean procInfoNeedsSave = false;

   /** When you are running with the source to StrataCode, instead of just with sc.jar point this to the source root - i.e. the dir which holds coreRuntime, fullRuntime, and sc */
   public static String scSourcePath = null;

   static ThreadLocal<LayerUtil.LayeredSystemPtr> currentLayeredSystem = new ThreadLocal<LayerUtil.LayeredSystemPtr>();

   static LayeredSystem defaultLayeredSystem;

   /** The LayeredSystem which is currently associated with the thread's ContextClassLoader. */
   static LayeredSystem contextLoaderSystem;

   /** If there's a specific layered system which should set the context class loader, set this property to it's process identity (see getProcessIdent()) */
   public static String contextLoaderSystemName = null;

   {
      setCurrent(this);
   }

   /** Stores the set of layers in the current application for build or run */
   @Constant
   public List<Layer> layers = new ArrayList<Layer>(16);

   /* Other layers which are in the layerPath which we are navigating or editing.  Used for tooling purposes */
   public ArrayList<Layer> inactiveLayers = new ArrayList<Layer>();

   @Constant
   public Options options;

   /** The list of other LayeredSystems that are part of the same application.  They are for managing code in a separate process that's part of the same application, possibly in another runtime like Javascript. */
   public ArrayList<LayeredSystem> peerSystems = null;
   /** For any group of LayeredSystems, there's always a 'main' one which kicks things off.  It first loads all of the layers, then creates the peerSystems required. */
   public LayeredSystem mainSystem = null;

   /** Set to true when this system is in a peer */
   public boolean peerMode = false;

   /** The list of layers originally specified by the user. */
   public List<Layer> specifiedLayers = new ArrayList<Layer>();

   public Layer buildLayer; // The last non-dynamic active layer
   public Layer currentBuildLayer; // When compiling layers in a sequence, this stores the layering currently being built
   public Layer lastCompiledLayer; // The last in the compiled layers list
   public Layer lastLayer;  // The last active layer
   public Map<String,Layer> layerIndex = new HashMap<String,Layer>(); // layer unique name
   public Map<String,Layer> layerFileIndex = new HashMap<String,Layer>(); // <layer-pkg> + layerName
   public Map<String,Layer> layerPathIndex = new HashMap<String,Layer>(); // layer-group name
   public String layerPath;
   public List<File> layerPathDirs;
   public String rootClassPath; /* The classpath passed to the layered system constructor - usually the system */
   public String classPath;  /* The complete external class path, used for javac, not for looking up internal classes */
   public String userClassPath;  /* The part of the above which is not part of the system classpath.  It is used to generate run scripts for example where we don't want to include the JRE or anything present when the script is run.  */

   /** Stores the latest representation of each model from the absolute file name.  When the externalModelIndex is set, this index shadows the external index, i.e. storing the last result we retreive from that index. */
   private Map<String,ILanguageModel> modelIndex = new HashMap<String,ILanguageModel>();

   /** Stores the inactive model types, separate from the active ones.  Here the layer stack is lazily formed and contains files we are not compiling... just loading for tooling purposes like the IDE or the doc styling */
   private HashMap<String,ILanguageModel> inactiveModelIndex = new HashMap<String,ILanguageModel>();

   /** During a refresh, keep track of the types which have been stopped so we can update instances based on these types when they are restarted (or remove them if they are not) */
   private LinkedHashMap<String,TypeDeclaration> stoppedTypes = null;

   /** As we are editing, we are only updating the model in one runtime.  Instead, we accumulate the set of models which are stale in the peer runtimes and flush them out periodically. */
   private Map<String,ILanguageModel> peerModelsToUpdate = new HashMap<String,ILanguageModel>();

   /** Works in parallel to modelIndex for the files which are not parsed, so we know when they were last modified to do incremental refreshes of them. */
   public Map<String,IFileProcessorResult> processedFileIndex = new HashMap<String,IFileProcessorResult>();

   public LinkedHashMap<String,ModelToPostBuild> modelsToPostBuild = new LinkedHashMap<String,ModelToPostBuild>();

   public int layerDynStartPos = -1; /* The index of the first dynamic layer (or -1 if all layers are compiled) */

   /** Have any errors occurred in this system since it's been running?  */
   public boolean anyErrors = false;

   public String runtimeLibsDir = null;   /* Frameworks like android which can't just include sc runtime classes from the classpath can specify this path, relative to the buildDir.  The build will look for the sc classes in its classpath.  If it finds scrt.jar, it copies it.   If it finds a buildDir, it generates it and places it in the lib dir.  */
   public String runtimeSrcDir = null;   /* Some frameworks also may need to deploy the src to the core runtime - for example, to convert it to Javascript.  Set this property to the directory where the scrt-core-src.jar file should go */
   public String strataCodeLibsDir = null; /* Or set this directory to have sc.jar either copied or built from the scSourcePath */

   public boolean systemCompiled = false;  // Set to true when the system has been fully compiled once
   public boolean buildingSystem = false;
   public boolean needsRefresh = false;  // Set to true when any build is completed.  after the first build, we need to potentially refresh files before we start the second
   public boolean initializingLayers = false; // Set to true when we are initializing layers
   public boolean runClassStarted = false;
   public boolean allTypesProcessed = false;

   public boolean useRuntimeReflection = true;  /* Frameworks like GWT do not support reflection.  In this case, we compile in wrapper code to access necessary properties of an object */

   public boolean usePropertyMappers = true;    /* Frameworks like Javascript don't need the property mapper optimization */

   public boolean useIndexSetForArrays = true;  // Should we generate a setX(int ix, Object elem) for an array property to allow element-by-element updates

   public boolean includeSrcInClassPath = false;  /* Frameworks like GWT require the generated src to be included in the classpath for compilation, DevMode etc. */

   public boolean needsDynamicRuntime = false; /* Set to true for processes that only work with the dynamicRuntime - i.e. can't be run directly with a normal java 'main' and no LayeredSystem class  */

   public IRuntimeProcessor runtimeProcessor = null; /** Hook to inject new runtimes like javascript, etc. which processor Java */

   /** Each LayeredSystem stores the layers for one process.  This holds the configuration info for that process.  */
   public IProcessDefinition processDefinition = null;

   public String serverName = "localhost";  /** The hostname for accessing this system */

   public int serverPort = 8080; /** The port for accessing this system */

   public boolean serverEnabled = false;

   //public String url; // The system URL

   /** If set, specifies the -source option to the javac compiler */
   public String javaSrcVersion;

   /** A user configurable list of runtime names which are ignored */
   public ArrayList<String> disabledRuntimes;

   /** An internal index of the layers which live only in disabledRuntimes (used only when peerMode = false) - those LayeredSystems are not created so we use these Layers as placeholders. */
   private HashMap<String,Layer> disabledLayersIndex;
   public ArrayList<Layer> disabledLayers;

   public String runtimePrefix; // If not set, defaults to the runtime name - 'java' or 'js' used for storing the src files.

   /** Set to true when we've detected that the layers have been installed properly. */
   public boolean systemInstalled;
   /** TODO - remove this */
   public boolean typesAreObjects = false;

   /** When processing more than one runtime, should the remote runtime be able to resolve methods against this system?  Typically true for servers, false for browsers. */
   public boolean enableRemoteMethods = true;

   /** Is synchronization enabled for this runtime? */
   public boolean syncEnabled = false;

   // Hook point for registering new file extensions into the build process.  Implement the process method
   // return IModelObj objects to register new types.
   public HashMap<String,IFileProcessor[]> fileProcessors = new HashMap<String,IFileProcessor[]>();
   public LinkedHashMap<Pattern,IFileProcessor> filePatterns = new LinkedHashMap<Pattern,IFileProcessor>();

   public List<TypeGroupDep> typeGroupDeps = new ArrayList<TypeGroupDep>();

   public RepositorySystem repositorySystem;

   // TODO: do we need something like this to filter the bundles directory?
   //List<String> excludedBundles = null;

   private Set<String> changedDirectoryIndex = new HashSet<String>();
   private Map<String,Object> otherClassCache; // Only used for CFClass - when crossCompile is true
   private Map<String,Class> compiledClassCache = new HashMap<String,Class>(); // Class
   {
      initClassCache();
   }

   public long lastRefreshTime = -1;
   public long lastChangedModelTime = -1;
   public long sysStartTime = -1;
   long buildStartTime = -1;

   /** Set to true for cases where we create a runtime purely for representing inactive types - e.g. the documentation.  In that case, no active layers are populated */
   public boolean inactiveRuntime = false;

   /** A global setting turned on when in the IDE.  If true the original runtime is always 'java' - the default.  In the normal build env, if there's only one runtime, we never create the default runtime. */
   public static boolean javaIsAlwaysDefaultRuntime = false;

   AbstractInterpreter cmd;

   public IExternalModelIndex externalModelIndex = null;

   /** Enable extra info in debugging why files are recompiled */
   static boolean traceNeedsGenerate = false;

   /** Java's URLClassLoader does not allow replacing of classes in a subsequent layer.  The good reason for this is that you under no circumstances want to load the same class twice.  You do not want to load incompatible versions
    * of a class by overriding them.  But with layers managed build system, we ideally want a different model.  One where you load classes in a two stage fashion:  - look at your parent loader, see if the class has been loaded.  If
    * so, return it.  But if not, load it from the outside/in - i.e. pick the most specific version of that class.  The layered type system should catch class incompatibility errors that might exist (or at least it can evolve to
    * eliminate those errors).  In the more flexible layeredClassPaths model, we build all build layers and build the last compiled layer in a merged manner.  All dynamic layers are then in separate buildDir's which can be added
    * removed from the class path as needed.  This model lets us also more accurately detect when a subsequent layer's class will override a previous layer.
    * <p/>
    * TODO: It seems like to finish this feature, we need to skip the root class loader entirely.  As it is, when a new layer of classes is added, we have to flush the class loaders
    * of the previously built layers so we start from scratch again when building the next layer.  It seems less efficient than it could be if we went ahead and implemented this feature.
    */
   final static boolean layeredClassPaths = false;

   /* Stores the models which have been detected as changed based on their type name since the start of this build.  Preserved across the entire buildSystem call, i.e. even over a layered builds. */
   HashMap<String,IFileProcessorResult> changedModels = new HashMap<String, IFileProcessorResult>();
   /**
    * Like changedModels but only stores the type names of the changed types for this build.  This is populated before we have the changedModels, since we need to know the changed models to decide whether
    * to stop/start a model when building a changed layer
    */
   HashSet<String> changedTypes = new HashSet<String>();

   /**
    * Stores the set of model names which have been through the getProcessedFile stage (i.e. transform)  Need this in the "clean stale entries" process.
    * When we determine a model has changed, we clean out any build info it creates so that we re-add the updated info when processing it.  But after that
    * type has been processed in one build layer, we should not clean out the entries in the next, because we do not re-start the model and won't add it back again.
    */
   HashSet<String> processedModels = new HashSet<String>();

   /** Stores the set of all files that we will or have generated since the build started.  We only clear the 'changed' state of a dependent file, the first time we see it generated for the build. */
   HashSet<SrcEntry> allToGenerateFiles = new HashSet<SrcEntry>();

   LinkedHashSet<String> viewedErrors = new LinkedHashSet<String>();

   /** Register an optional error handler for all system errors */
   public IMessageHandler messageHandler = null;

   /** Should we pick up the current class loader from the dynamic type system or used a fixed class loader (like in the plugin environment) */
   private boolean autoClassLoader = true;

   /**
    * For the master LayeredSystem only - the map from processident to TypeIndexEntry HashMap, used to bootstrap the TypeIndexes, before the
    * peerSystems are created.
    */
   HashMap<String, SysTypeIndex> typeIndexProcessMap = null;

   TreeSet<String> customSuffixes = new TreeSet<String>();

   public SysTypeIndex typeIndex;

   Set<String> allNames = null; // Cache of all of the system identifiers from the type index - used for IDE (required by intelliJ and needs to be cached)

   /**
    * The type index is enabled by calling initTypeIndex on the LayeredSystem right after constructing it.  It's used for tools like the IDE to maintain
    * the set of layers, types, base-classes, etc. in files that are loaded much more quickly than by parsing all of the source code.
    */
   boolean typeIndexEnabled = false;

   boolean typeIndexLoaded = false;

   String typeIndexDirName = null;

   public List<VMParameter> vmParameters;

   private LayerUtil.LayeredSystemPtr systemPtr;

   /** Internal flag set when initializing the type index to prevent loading src files during this time. */
   boolean startingTypeIndexLayers = false;

   /** Flag set when doing the postBuild process - for those cases where you might need different behavior when generating a static file versus one when the server loads */
   public boolean postBuildProcessing = false;

   /** Internal flag set when intializing the type index */
   boolean initializingTypeIndexes = false;

   boolean batchingModelUpdates = false;

   /** When restoring serialized layered components, we need to lookup a thread-local layered system and be able to know we are doing layerResolves - to swap the name space to that of the layer. */
   public boolean layerResolveContext = false;

   /** Stores up layers that we've found in checkRemovedDirectory that need to be removed (for the IDE to manage a two step process of identifying layers to remove) */
   private List<Layer> layersToRemove;

   public void buildReverseTypeIndex(boolean clear) {
      try {
         acquireDynLock(false);

         if (clear)
            typeIndex.clearReverseTypeIndex();

         typeIndex.buildReverseTypeIndex(this);

         if (!peerMode && peerSystems != null) {
            for (LayeredSystem peerSys: peerSystems) {
               peerSys.buildReverseTypeIndex(clear);
            }

            // If we've already created a layered system for these type indexes, this will just return without
            // doing it over again.
            for (SysTypeIndex sti:typeIndexProcessMap.values()) {
               sti.buildReverseTypeIndex(null);
            }
         }
      }
      finally {
         releaseDynLock(false);
      }
   }

   public void refreshReverseTypeIndex() {
      try {
         acquireDynLock(false);

         typeIndex.refreshReverseTypeIndex(this);

         if (!peerMode && peerSystems != null) {
            for (LayeredSystem peerSys: peerSystems) {
               peerSys.refreshReverseTypeIndex();
            }

            // If we've already created a layered system for these type indexes, this will just return without
            // doing it over again.
            for (SysTypeIndex sti:typeIndexProcessMap.values()) {
               sti.refreshReverseTypeIndex(null);
            }
         }
      }
      finally {
         releaseDynLock(false);
      }
   }

   public Set<String> getAllNames() {
      if (peerMode)
         return getMainLayeredSystem().getAllNames();

      try {
         acquireDynLock(false);
         if (externalModelIndex != null)
            externalModelIndex.checkForCancelledOperation();
         if (allNames != null)
            return allNames;
         allNames = new HashSet<String>();
         if (typeIndex != null)
            allNames.addAll(typeIndex.getAllNames());
         if (peerSystems != null) {
            for (int i = 0; i < peerSystems.size(); i++) {
               LayeredSystem peerSys = peerSystems.get(i);
               allNames.addAll(peerSys.typeIndex.getAllNames());
            }
         }
         return allNames;
      }
      finally {
         releaseDynLock(false);
      }
   }

   public void setMessageHandler(IMessageHandler handler) {
      messageHandler = handler;
      if (peerSystems != null && !peerMode) {
         for (LayeredSystem peerSys: peerSystems)
            peerSys.setMessageHandler(handler);
      }
      if (repositorySystem != null)
         repositorySystem.setMessageHandler(handler);
   }

   public boolean isErrorViewed(String error, SrcEntry srcEnt, ISemanticNode node) {
      if (messageHandler != null) {
         LayerUtil.reportMessageToHandler(messageHandler, error, srcEnt, node, MessageType.Error);
      }
      if (options.disableCommandLineErrors)
         return true;
      if (viewedErrors == null)
         return false;
      if (options.maxErrors != -1 && viewedErrors.size() >= options.maxErrors) {
         System.err.println(".... more than: " + options.maxErrors + " errors - disabling further console errors");
         options.disableCommandLineErrors = true;
         return true;
      }
      if (viewedErrors.add(error)) {
         if (peerSystems != null) {
            for (LayeredSystem peer:peerSystems)
               if (peer.viewedErrors.contains(error))
                  return true;
         }
         return false;
      }
      return true;
   }

   public boolean isWarningViewed(String warning, SrcEntry srcEnt, ISemanticNode node) {
      if (messageHandler != null) {
         LayerUtil.reportMessageToHandler(messageHandler, warning, srcEnt, node, MessageType.Warning);
      }
      if (options.disableCommandLineErrors)
         return true;
      // TODO: do we need viewedWarnings here?
      return false;
   }

   List<String> activatedLayerNames = null;
   List<String> activatedDynLayerNames = null;

   public void clearActiveLayers(boolean clearPeers) {
      if (clearPeers) {
         activatedLayerNames = null;
         activatedDynLayerNames = null;
      }

      if (layers != null) {
         for (Layer layer : layers) {
            layer.destroyLayer();
         }
         layers.clear();
      }
      cleanupLayerFileProcessors();
      Language.cleanupLanguages();
      layerDynStartPos = -1;
      specifiedLayers.clear();

      typesByName.clear();
      typesByRootName.clear();

      if (modelIndex != null) {
         modelIndex.clear();
      }

      innerTypeCache.clear();

      templateCache.clear();

      buildLayer = null;
      buildInfo = null;
      currentBuildLayer = null;
      commonBuildLayer = null;
      lastLayer = null;
      lastStartedLayer = null;
      lastModelsStartedLayer = null;
      lastBuiltLayer = null;
      lastCompiledLayer = null;

      layerIndex.clear();
      layerFileIndex.clear();
      layerPathIndex.clear();

      globalObjects.clear();

      changedModels.clear();
      changedTypes.clear();

      processedFileIndex.clear();
      changedDirectoryIndex.clear();

      resetClassCache();
      RTypeUtil.flushCaches();
      modelsToPostBuild.clear();

      typeGroupDeps.clear();

      loadedBuildLayers.clear();

      instancesByType.clear();
      innerToOuterIndex.clear();

      objectNameIndex.clear();

      subTypesByType.clear();

      if (buildClassLoader instanceof TrackingClassLoader) {
         ClassLoader newLoader = ((TrackingClassLoader) buildClassLoader).resetBuildLoader();
         Thread cur = Thread.currentThread();
         if (cur.getContextClassLoader() == buildClassLoader)
            cur.setContextClassLoader(newLoader);
         buildClassLoader = newLoader;
      }

      if (typeIndexProcessMap != null) {
         for (Map.Entry<String, SysTypeIndex> indexEnt : typeIndexProcessMap.entrySet()) {
            SysTypeIndex sysTypeIndex = indexEnt.getValue();
            sysTypeIndex.clearActiveLayers();
         }
      }

      generatedLayers.clear();

      if (runtimeProcessor != null)
         runtimeProcessor.clearRuntime();

      if (clearPeers && !peerMode && peerSystems != null) {
         for (LayeredSystem peerSys : peerSystems)
            peerSys.clearActiveLayers(false);
      }

      resetBuild(false);

      DynUtil.clearObjectIds();

      TransformUtil.clearTemplateCache();

      removeAllActivePackageIndexEntries();

      if (!peerMode && !options.disableAutoGC)
         System.gc();

   }

   /**
    * Provides the complete list of layer names and a separate list of 'recursive dyn layers' - i.e. the subset of allLayerNames
    * that should be explicitly made dynamic.
    */
   public void activateLayers(List<String> allLayerNames, List<String> dynLayers) {
      boolean completed = false;
      // TODO: if any of the layer names in the run configuration have changed, throw it all away and restart from scratch.
      // this is the simple approach.  It would not be too hard to figure out the differences, and peel-away unused layers, and
      // add in used ones.  That would make the builds a lot faster I think in some cases since you can save the compiled state of
      // the previous layers.  But maybe a good incremental compile will be fast enough?  And now that we are doing external builds, this is not
      // going to be helpful since these layers are not being built.
      try {
         if (activatedLayerNames == null || !activatedLayerNames.equals(allLayerNames) || !DynUtil.equalObjects(dynLayers, activatedDynLayerNames)) {
            if (activatedLayerNames != null) {
               clearActiveLayers(true);
            }

            activatedLayerNames = new ArrayList<String>(allLayerNames);
            activatedDynLayerNames = dynLayers == null ? null : new ArrayList<String>(dynLayers);
            // This will create layers for all referenced dependent layers. We use this layered system to store that list while
            // we figure out which runtimes and processes we want to include. After that, we mark the ones we will exclude then
            // initialize the runtimes. We also must accumulate the list of processes and runtimes that are actually defined. There
            // might be some layers which are included in a runtime or process, but that runtime or process is not referenced in this
            // set of layers. We remove those layers at the end from all layered systems.
            initLayersWithNames(allLayerNames, false, false, dynLayers, true, false);
            // First mark all layers that will be excluded
            removeExcludedLayers(true);

            // Init the runtimes, using the excluded layers as a guide
            initRuntimes(dynLayers, true, false, true);

            ArrayList<IProcessDefinition> procs = new ArrayList<IProcessDefinition>();
            ArrayList<IRuntimeProcessor> runtimes = new ArrayList<IRuntimeProcessor>();
            for (int i = 0; i < layers.size(); i++) {
               Layer l = layers.get(i);
               if (l.hasDefinedProcess)
                  procs.add(l.definedProcess);
               if (l.hasDefinedRuntime)
                  runtimes.add(l.definedRuntime);
            }
            // When no framework layers are included, no runtimes are defined, need to at least include the default runtime or else no runtime matches
            if (procs.size() == 0 && runtimes.size() == 0)
               runtimes.add(null);

            if (!procs.contains(processDefinition) && ((procs.size() > 0 && processDefinition.getProcessName() != null) || !runtimes.contains(runtimeProcessor))) {
               clearActiveLayers(false);
            }
            else {
               // Now actually remove the excluded layers.
               removeExcludedLayers(false);
            }

            for (int i = 0; i < peerSystems.size(); i++) {
               LayeredSystem peerSys = peerSystems.get(i);
               if (!procs.contains(peerSys.processDefinition) && (peerSys.processDefinition.getProcessName() != null || !runtimes.contains(peerSys.runtimeProcessor)))
                  peerSys.clearActiveLayers(false);
            }

            LayeredSystem runtimeSys = getActiveLayeredSystem(null);
            if (runtimeSys != null)
               runtimeSys.initBuildSystem(true, true);

            // Finally initialize the build system from scratch and we are ready to go.
            initBuildSystem(true, true);

            systemCompiled = false;

            // TODO: do we need to always do a new build when the layers have changed?  We should at least skip this the first time
            //if (!options.buildAllFiles)
            //   options.buildAllFiles = true;

         }
         completed = true;
      }
      catch (RuntimeException exc) {
         System.err.println("*** RuntimeException in activateLayers: " + exc);
         throw exc;
      }
      finally {
         if (!completed) {
            System.err.println("*** Clearing active layers - failed to complete");
            clearActiveLayers(true);
            activatedLayerNames = activatedDynLayerNames = null;
         }
      }
   }

   public JavaModel getTransformedModelForType(String typeName, IRuntimeProcessor proc) {
      acquireDynLock(false);
      Layer oldBuildLayer = currentBuildLayer;
      try {
         // This gets set normally during the generateAndCompile method but needs also to be defined during the transformation process if we need a transformedModel
         // say for debugging.
         currentBuildLayer = buildLayer;
         if (proc == runtimeProcessor) {
            TypeDeclaration origType = (TypeDeclaration) getSrcTypeDeclaration(typeName, null, true, false, true);
            if (origType != null) {
               JavaModel origModel = origType.getJavaModel();
               if (origModel != null) {
                  Layer modelLayer = origModel.getLayer();
                  if (modelLayer != null && modelLayer.annotationLayer) {
                     return null; // Should we check the previous layer here... I think it's always compiled so there is no transformed model for this type.
                  }
                  return origModel.getTransformedModel();
               }
            }
         }
         if (!peerMode && peerSystems != null) {
            for (LayeredSystem peerSys:peerSystems) {
               if (peerSys.runtimeProcessor == proc) {
                  JavaModel res = peerSys.getTransformedModelForType(typeName, proc);
                  if (res != null)
                     return res;
               }
            }
         }
         return null;
      }
      finally {
         currentBuildLayer = oldBuildLayer;
         releaseDynLock(false);
      }
   }

   /**
    * Performance Note: this is inside of a pretty tight inner loop - find all annotation processors that affect every type in a type hierarchy so we are careful not to
    * allocate too much memory.
    */
   public ArrayList<IDefinitionProcessor> getInheritedDefinitionProcessors(BodyTypeDeclaration type) {
      ArrayList<IDefinitionProcessor> res = null;

      Layer refLayer = type.getLayer();
      int startIx;
      if (searchActiveTypesForLayer(refLayer)) {
         // TODO: for inherited annotations in particular like @URL, we might be looking it up from a type in a base
         // layer like html/core/index and want it to match the servlet/core/URL annotation which is in a latter layer.
         // should maybe the servlet/core URL annotation register itself on html/core so it applies all the way up the stack?
         //startIx = refLayer == null ? layers.size() - 1 : refLayer.getLayerPosition();
         startIx = layers.size() - 1;
         for (int i = startIx; i >= 0; i--) {
            Layer layer = layers.get(i);
            if (layer.annotationProcessors != null) {
               for (Map.Entry<String,IAnnotationProcessor> procEnt:layer.annotationProcessors.entrySet()) {
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
         }
      }
      if (searchInactiveTypesForLayer(refLayer)) {
         res = refLayer.addInheritedAnnotationProcessors(type, res, true);
      }
      return res;
   }

   private final static String TYPE_INDEX_DIR_PREFIX = "types_";

   public String getTypeIndexDir() {
      // Organized by runtime name, not process ident because the process ident can change during initialization.
      return getTypeIndexDir(getTypeIndexIdent());
   }

   public String getTypeIndexBaseDir() {
      return getStrataCodeDir("idx");
   }

   public String getTypeIndexDir(String typeIndexIdent) {
      return FileUtil.concat(getTypeIndexBaseDir(), TYPE_INDEX_DIR_PREFIX + typeIndexIdent);
   }

   public String getProcessIdent() {
      String procName = getProcessName();
      String runtimeName = getRuntimeName();
      return procName == null ? runtimeName : runtimeName + "_" + procName;
   }

   /** LayeredSystems have the ability to potentially share a type index but for now we are using the per-process id. */
   public String getTypeIndexIdent() {
      return getProcessIdent();
   }

   /**
    * For the IDE specifically. when open types is true, returns TypeDeclaration or TypeIndexEntry instances. When openTypes is false,
    * rather than reading the file, it will return the SrcEntry if the file is not already in the cache.
    */
   public void addAllTypeDeclarations(String typeName, ArrayList<Object> res, boolean openAllLayers, boolean openTypes) {
      Object typeObj;
      if (openTypes)
         typeObj = getSrcTypeDeclaration(typeName, null, true, false, true, openAllLayers ? Layer.ANY_INACTIVE_LAYER : Layer.ANY_OPEN_INACTIVE_LAYER, false);
      else
         typeObj = getCachedTypeDeclaration(typeName, null, null, false, false, openAllLayers ? Layer.ANY_INACTIVE_LAYER : Layer.ANY_OPEN_INACTIVE_LAYER, false);
      if (typeObj != null)
         res.add(typeObj);
      else {
         if (!openTypes) {
            SrcEntry srcEnt = getSrcFileFromTypeName(typeName, true, null, true, null);
            if (srcEnt != null)
               res.add(srcEnt);
         }

         Layer layer = getLayerByDirName(typeName);
         if (layer != null)
             res.add(layer.model.getModelTypeDeclaration());
         else {
            layer = getInactiveLayer(typeName.replace('.', '/'), openAllLayers, true, true, true);
            if (layer != null && layer.model != null)
               res.add(layer.model.getModelTypeDeclaration());
         }
      }
      /*
      if (peerSystems != null) {
         for (LayeredSystem peerSys:peerSystems) {
            typeObj = peerSys.getSrcTypeDeclaration(typeName, null, true, false, true, Layer.ANY_LAYER, false);
            if (typeObj != null) {
               res.add(typeObj);
            }
         }
      }
      */
      if (typeIndexProcessMap != null) {
         for (Map.Entry<String, SysTypeIndex> indexEnt : typeIndexProcessMap.entrySet()) {
            SysTypeIndex sysIndex = indexEnt.getValue();
            ArrayList<TypeIndexEntry> typeIndexEntries = sysIndex.getTypeIndexes(typeName);
            if (typeIndexEntries != null) {
               for (TypeIndexEntry typeIndexEntry : typeIndexEntries) {
                  Layer layer = getInactiveLayerByPath(typeIndexEntry.layerName, null, openAllLayers, true);
                  if (layer != null) {
                     if (!layer.closed || openAllLayers) {
                        Object newTypeObj = layer.layeredSystem.getSrcTypeDeclaration(typeName, layer.getNextLayer(), true, false, true, layer, false);
                        if (newTypeObj != null)
                           res.add(newTypeObj);
                     }
                     else {
                        boolean found = false;
                        for (Object resEnt:res) {
                           if (typeIndexEntry.sameType(resEnt)) {
                              found = true;
                              break;
                           }
                        }
                        if (!found)
                           res.add(typeIndexEntry);
                     }
                  }
               }
            }
         }
      }
   }

   public TypeDeclaration getActivatedType(IRuntimeProcessor proc, String layerName, String typeName) {
      Layer resLayer = getLayerByDirName(layerName);
      TypeDeclaration resTD = null;
      if (resLayer != null) {
         resTD = resLayer.layeredSystem.getSrcTypeDeclaration(typeName, resLayer.getNextLayer(), true);
      }
      return resTD;
   }

   /** Moves the layer into the list of disabled layers. */
   public void disableLayer(Layer layer) {
      LayeredSystem mainSys = getMainLayeredSystem();
      if (peerMode && mainSys == this) {
         error("*** Error - invalid main layered system");
         return;
      }
      if (peerMode) {
         if (mainSys == null) {
            System.err.println("*** Attempt to disable layer: " + layer.getLayerName() + " in peer system: " + this + " with no main system:");
            return;
         }
         mainSys.disableLayer(layer);
      }
      else {
         if (disabledLayers == null)
            disabledLayers = new ArrayList<Layer>();
         if (!disabledLayers.contains(layer)) {
            disabledLayers.add(layer);
            Layer oldLayer = disabledLayersIndex.put(layer.getLayerName(), layer);
            if (layer.layeredSystem != this && oldLayer != null && oldLayer.layeredSystem == this) {
               // If we've already disabled this layer in this system, put back our system as it's preferred that they match
               disabledLayersIndex.put(layer.getLayerName(), oldLayer);
            }

            if (inactiveLayers.remove(layer)) {
               inactiveLayerIndex.remove(layer.getLayerName());
               renumberInactiveLayers(0);
            }

            // Disabled layers are always stored on the main layered system but sometimes, we'll find a disabled
            // layer in another layered system.  Need to make sure it's not considered an inactive layer for that system.
            LayeredSystem layerSys = layer.layeredSystem;
            if (layerSys != this) {
               if (layerSys.inactiveLayers.remove(layer)) {
                  layerSys.inactiveLayerIndex.remove(layer.getLayerName());
                  layerSys.renumberInactiveLayers(0);
               }
            }
            layer.layerPosition = disabledLayers.size() - 1;
         }
         checkLayerPosition();
      }
   }

   public void updateTypeNames(String packageName, Map<String,TypeDeclaration> oldDefinedTypes, Map<String,TypeDeclaration> newDefinedTypes) {
      for (String oldTypeName:oldDefinedTypes.keySet()) {
         if (newDefinedTypes.get(oldTypeName) == null) {
            removeTypeName(CTypeUtil.prefixPath(packageName, oldTypeName), true);
         }
      }
      // TODO: do we need to do more than this?  It seems like the new type will be added to the type index on it's own
   }

   public void updateTypeName(String oldFullName, String newFullName, boolean updatePeers) {
      TypeDeclarationCacheEntry tds = typesByName.remove(oldFullName);
      if (tds != null)
         typesByName.put(newFullName, tds);

      HashMap<String,Boolean> subTypes = subTypesByType.remove(oldFullName);
      if (subTypes != null) {
         subTypesByType.put(newFullName, subTypes);
      }

      Object inner = removeFromInnerTypeCache(oldFullName);
      if (inner != null)
         addToInnerTypeCache(newFullName, inner);

      typeIndex.updateTypeName(oldFullName, newFullName);

      if (updatePeers && peerSystems != null) {
         for (int i = 0; i < peerSystems.size(); i++) {
            LayeredSystem peer = peerSystems.get(i);
            peer.updateTypeName(oldFullName, newFullName, false);
         }
      }
   }

   public void removeTypeName(String oldFullName, boolean updatePeers) {
      typesByName.remove(oldFullName);

      subTypesByType.remove(oldFullName);

      Object inner = removeFromInnerTypeCache(oldFullName);

      typeIndex.removeTypeName(oldFullName);

      if (updatePeers && peerSystems != null) {
         for (int i = 0; i < peerSystems.size(); i++) {
            LayeredSystem peer = peerSystems.get(i);
            peer.removeTypeName(oldFullName,false);
         }
      }
   }

   public void fileRenamed(SrcEntry oldSrcEnt, SrcEntry newSrcEnt, boolean updatePeers) {
      typeIndex.fileRenamed(oldSrcEnt.absFileName, newSrcEnt.absFileName);

      ILanguageModel model = inactiveModelIndex.remove(oldSrcEnt.absFileName);
      if (model != null) {
         if (model.getLayer() != null && model.getLayer().activated)
            System.out.println("*** Error - renaming activated model to inactive index");
         if (model.getLayeredSystem() != this)
            System.out.println("*** Error - renaming model from wrong system");
         inactiveModelIndex.put(newSrcEnt.absFileName, model);
         Layer modelLayer = model.getLayer();
         if (modelLayer != null)
            modelLayer.fileRenamed(oldSrcEnt, newSrcEnt);
      }

      // TODO: for each layer in the inactive layers list - update srcDirCache
      // update model index?
      if (updatePeers && peerSystems != null) {
         for (int i = 0; i < peerSystems.size(); i++) {
            LayeredSystem peer = peerSystems.get(i);
            peer.fileRenamed(oldSrcEnt, newSrcEnt, false);
         }
      }
   }

   public ArrayList<PropertyAssignment> getAssignmentsToProperty(VariableDefinition varDef, Layer refLayer, boolean openLayers, boolean checkPeers) {
      TypeDeclaration enclType = varDef.getEnclosingType();
      if (enclType == null)
         return null;

      Iterator<BodyTypeDeclaration> subTypes = getSubTypesOfType(enclType, refLayer, openLayers, checkPeers, true, false);
      ArrayList<PropertyAssignment> res = null;
      if (subTypes != null) {
         while (subTypes.hasNext()) {
            BodyTypeDeclaration subType = subTypes.next();

            String varName = varDef.variableName;
            Object assign = varName == null ? null : subType.definesMember(varName, JavaSemanticNode.MemberType.AssignmentSet, null, null, false, false);
            if (assign instanceof PropertyAssignment) {
               if (res == null)
                  res = new ArrayList<PropertyAssignment>();
               PropertyAssignment pa = (PropertyAssignment) assign;
               if (!containsAssign(res, pa))
                  res.add(pa);
            }
         }
      }
      ArrayList<BodyTypeDeclaration> modTypes = getModifiedTypesOfType(enclType, false, checkPeers);
      if (modTypes != null && varDef.variableName != null) {
         for (BodyTypeDeclaration modType:modTypes) {
            Object assign = modType.definesMember(varDef.variableName, JavaSemanticNode.MemberType.AssignmentSet, null, null, false, false);
            if (assign instanceof PropertyAssignment) {
               if (res == null)
                  res = new ArrayList<PropertyAssignment>();
               PropertyAssignment pa = (PropertyAssignment) assign;
               if (!containsAssign(res, pa))
                  res.add(pa);
            }
         }
      }
      return res;
   }

   private static boolean containsAssign(ArrayList<PropertyAssignment> res, PropertyAssignment assign) {
      if (assign.propertyName == null)
         return false;
      for (PropertyAssignment pa:res) {
         if (pa.propertyName == null)
            continue;
         if (pa.propertyName.equals(assign.propertyName) && ModelUtil.sameTypesAndLayers(pa.getLayeredSystem(), pa.getEnclosingType(), assign.getEnclosingType()))
            return true;
      }
      return false;
   }


   public enum BuildCommandTypes {
      Pre, Post, Run, Test
   }

   private EnumMap<BuildPhase,List<BuildCommandHandler>> preBuildCommands = new EnumMap<BuildPhase,List<BuildCommandHandler>>(BuildPhase.class);
   private EnumMap<BuildPhase,List<BuildCommandHandler>> postBuildCommands = new EnumMap<BuildPhase,List<BuildCommandHandler>>(BuildPhase.class);
   private EnumMap<BuildPhase,List<BuildCommandHandler>> runCommands = new EnumMap<BuildPhase,List<BuildCommandHandler>>(BuildPhase.class);
   private EnumMap<BuildPhase,List<BuildCommandHandler>> testCommands = new EnumMap<BuildPhase,List<BuildCommandHandler>>(BuildPhase.class);

   private List<IModelListener> modelListeners = new ArrayList<IModelListener>();
   private List<ITypeChangeListener> typeChangeListeners = new ArrayList<ITypeChangeListener>();
   private final List<ICodeUpdateListener> codeUpdateListeners = new ArrayList<ICodeUpdateListener>();
   private List<IDynListener> dynListeners = null;
   private final List<ISystemExitListener> systemExitListeners = new ArrayList<ISystemExitListener>();

   EditorContext editorContext;

   public LinkedHashMap<String,LayerIndexInfo> allLayerIndex;   // Maps layer path names found to the package prefix of the layer

   public List<Layer> newLayers = null;

   /** For a layerUniqueName in this runtime which was a build layer, store the old buildDir - usually the layers buildDir unless we started the system with -d buildDir.  In that case, we need to reset the layer.buildDir back to the old value so things work right */
   private Map<String,String> loadedBuildLayers = new TreeMap<String,String>();

   String origBuildDir; // Track the original build directory, where we do the full compile.  After the orig build layer is removed, this guy will still need to go into the class path because it contains all of the non-build layer compiled assets.

   private static final String[] defaultGlobalImports = {"sc.obj.AddBefore", "sc.obj.AddAfter", "sc.obj.Component", "sc.obj.IComponent",
           "sc.obj.IAltComponent", "sc.obj.CompilerSettings", "sc.bind.Bindable"}; // TODO: we'd like to add "sc.bind.Bind" here but this breaks JS where we need to use sc.js.bind.Bind

   // These imports are used for StrataCode files during the code-generation phase.  They are inserted into the generated Java file when used, like the import statements in layer definition files.
   private Map<String,ImportDeclaration> globalImports = new HashMap<String,ImportDeclaration>();
   {
      for (String dgi:defaultGlobalImports)
         globalImports.put(CTypeUtil.getClassName(dgi), ImportDeclaration.create(dgi));
   }

   private static final String[] defaultGlobalLayerImports = {"sc.layer.LayeredSystem", "sc.util.FileUtil", "java.io.File",
      "sc.repos.RepositoryPackage", "sc.repos.mvn.MvnRepositoryPackage", "sc.layer.LayerFileProcessor", "sc.lang.TemplateLanguage",
      "sc.layer.BuildPhase", "sc.layer.CodeType", "sc.obj.Sync", "sc.obj.SyncMode", "sc.layer.LayerUtil"};

   // These are the set of imports used for resolving types in layer definition files.
   private Map<String,ImportDeclaration> globalLayerImports = new HashMap<String, ImportDeclaration>();
   {
      for (String dgi:defaultGlobalLayerImports)
         globalLayerImports.put(CTypeUtil.getClassName(dgi), ImportDeclaration.create(dgi));
   }

   // The globally scoped objects which have been defined.
   Map<String,Object> globalObjects = new HashMap<String,Object>();

   // These store Layer objects temporarily - they are used for moving the layer instance from 'findLayer' to 'resolveName'
   TreeMap<String,Layer> pendingActiveLayers = new TreeMap<String,Layer>();
   TreeMap<String,Layer> pendingInactiveLayers = new TreeMap<String,Layer>();

   Map<String,Layer> inactiveLayerIndex = new HashMap<String,Layer>();

   // Global dependencies - used to store things like jar files, the set of mains, the set of tests, etc.
   public BuildInfo buildInfo;

   // The global index which maps public fully qualified names to ClassDeclaration, Interface, etc.
   HashMap<String,TypeDeclarationCacheEntry> typesByName = new HashMap<String,TypeDeclarationCacheEntry>();

   HashMap<String,Object> innerTypeCache = new HashMap<String,Object>();

   // Do not try to load objects that are already being loaded.  In some rare cases, the type system will try to skip back to the original type when looking for an inner type.  Just return null for these incomplete objects until they are ready to be resolved.
   HashMap<String,ILanguageModel> beingLoaded = new HashMap<String,ILanguageModel>();

   HashMap<String,ArrayList<BodyTypeDeclaration>> typesByRootName = new HashMap<String, ArrayList<BodyTypeDeclaration>>();

   /** The current buildDir - i.e. the build dir for the last layer in the list */
   public String buildDir;
   /** The build dir is set to the last compiled layer in the list and not changed as new layers are added */
   public String commonBuildDir;
   public Layer commonBuildLayer;
   public String buildSrcDir;
   public File buildDirFile;
   public File buildSrcDirFile;
   /** Set to the directory where classes are stored */
   public String buildClassesDir;

   /** Used to hold dyn stubs we need to build to process and create the layers - i.e. before the build layer is defined */
   public Layer coreBuildLayer;

   public String newLayerDir = null; // Directory for creating new layers

   /** Set to true when we have identified the real StrataCode directory structure (as opposed to the older looser collection of layers) */
   boolean isStrataCodeDir = false;
   public String strataCodeMainDir = null; // Directory containing .stratacode dir.  It's either the StrataCode install dir or a layer dir if you are running a layer not in the context of the install dir.

   public String strataCodeInstallDir = null; // Configured to point to the install directory for StrataCode so we can find sc.jar and scrt-core-src.jar and files like that more easily

   /** Set to "layers" or "../layers" when we are running in the StrataCode main/bin directories.  In this case we look for and by default install the layers folder next to the bin directory  */
   public String layersFilePathPrefix = null;

   // Set and used after we successfully compile.  It exposes those newly compiled files to the class loader
   // so we can use them during any interpreting we'll do after that.
   private ClassLoader buildClassLoader;

   // Set once we start running a dynamic application to a single web app's class loader.
   private ClassLoader systemClassLoader;

   public Layer lastBuiltLayer, lastStartedLayer, lastModelsStartedLayer;  // When layers are built separately, this stores the last one built/started - its classes are in the sys class loader

   public Layer lastInitedInactiveLayer;  // For inactive layers store the last one inited

   private HashMap<String,Template> templateCache = new HashMap<String,Template>();

   // Stores zip files we use for resolving types
   private HashMap<String,ZipFile> zipFileCache = new HashMap<String,ZipFile>();

   public HashMap<String,IAnnotationProcessor> defaultAnnotationProcessors = new HashMap<String,IAnnotationProcessor>();
   {
      registerDefaultAnnotationProcessor("sc.obj.Sync", SyncAnnotationProcessor.getSyncAnnotationProcessor());
   }

   // TODO: RuntimeState class to hold the next several fields
   /**
    * Keeps track of all of the active instances for dynamic types (when enabled)
    */
   public HashMap<String, WeakIdentityHashMap<Object, Boolean>> instancesByType = new HashMap<String, WeakIdentityHashMap<Object, Boolean>>();

   public WeakIdentityHashMap<Object, Object> innerToOuterIndex = new WeakIdentityHashMap<Object, Object>();

   public WeakIdentityHashMap<Object, String> objectNameIndex = new WeakIdentityHashMap<Object, String>();

   /** Stores the type-name to sub-type map for the active types - used for runtime update etc.  Also see subTypeIndex - for IDE-level indexing that spans active and inactive types. */
   public HashMap<String,HashMap<String,Boolean>> subTypesByType = new HashMap<String, HashMap<String, Boolean>>();

   /** At least one change has been made to the compiled types since the system was recompiled - essentially means you need to restart to pick up those changes */
   public boolean staleCompiledModel;

   public ArrayList<String[]> staleCompiledInfo;

   private ArrayList<String> restartArgs;

   /** Set to true when the build itself was interrupted.  In this case, we may lose track of src files in the build and should do a clean build to ensure everything gets picked up. */
   public boolean buildInterrupted = false;

   /** Set of patterns to ignore any layer src or class directory, using Java's regex language */
   public List<String> excludedFiles = new ArrayList<String>(Arrays.asList(".git"));

   private List<Pattern> excludedPatterns; // Computed from the above

   /** Normalized paths relative to the layer directory that are excluded from processing */
   public List<String> excludedPaths = new ArrayList<String>(Arrays.asList(LayerConstants.DYN_BUILD_DIRECTORY, LayerConstants.BUILD_DIRECTORY, "out", "bin", "lib"));

   /** Set to true when we've removed layers.  Currently do not update the class loaders so once we remove a layer, we might not get the class we expect since the old layer's buildDir is in the way */
   public boolean staleClassLoader = false;

   /**
    * This is the lock we use to access the layered system.  It's static now because otherwise we'd need to synchronize the cross-talk between LayeredSystem classes
    * when dealing with more than one runtime.
    */
   public static ReentrantReadWriteLock globalDynLock = new ReentrantReadWriteLock();

   public static class TagPackageListEntry {
      public Layer layer;
      public String name;
      public int priority;

      TagPackageListEntry(String name, Layer layer, int priority) {
         this.name = name;
         this.layer = layer;
         this.priority = priority;
      }

      public String toString() {
         return "tagPackage: " + name + " defined in: " + layer + " (" + priority + ")";
      }
   }

   public void addTagPackageDirectory(String name, Layer definedInLayer, int priority) {
      TagPackageListEntry ent = new TagPackageListEntry(name, definedInLayer, priority);
      int ix;
      for (ix = 0; ix < tagPackageList.size(); ix++) {
         if (ent.priority >= tagPackageList.get(ix).priority)
            break;
      }
      if (ix == tagPackageList.size())
         tagPackageList.add(ent);
      else
         tagPackageList.add(ix, ent);
   }

   public ArrayList<TagPackageListEntry> tagPackageList = new ArrayList<TagPackageListEntry>();
   {
      tagPackageList.add(new TagPackageListEntry(Template.TAG_PACKAGE, null, 0));
   }

   public TreeSet<String> allOrNoneFinalPackages = new TreeSet<String>();
   {
      allOrNoneFinalPackages.add("java.util");
      allOrNoneFinalPackages.add("java.lang");
      allOrNoneFinalPackages.add("sc.lang");  // For EditorContext and ClientEditorContext
      allOrNoneFinalPackages.add("sc.lang.java");  // For TypeDeclaration and BodyTypeDeclaration
      allOrNoneFinalPackages.add("sc.lang.sc");  // For SCModel
      allOrNoneFinalPackages.add("sc.lang.template");  // For Template
   }

   public TreeSet<String> overrideFinalPackages = new TreeSet<String>();

   /** The current project directory */
   public String getStrataCodeDir(String dirName) {
      String dir = strataCodeMainDir;
      if (dir == null) {
         dir = System.getProperty("user.dir");
      }
      return FileUtil.concat(dir, dirName);
   }

   /** Used for storing information shared between projects */
   public String getStrataCodeHomeDir(String dirName) {
      String homeDir = LayerUtil.getDefaultHomeDir();
      if (homeDir != null)
         return FileUtil.concat(homeDir, dirName);
      return getStrataCodeDir(dirName);
   }

   public String getStrataCodeConfDir(String dirName) {
      return getStrataCodeDir(FileUtil.concat("conf", dirName));
   }

   public LayeredSystem(List<String> initLayerNames, List<String> explicitDynLayers, String layerPathNames, Options options, IProcessDefinition useProcessDefinition, LayeredSystem parentSystem, boolean startInterpreter, IExternalModelIndex extModelIndex) {
      String lastLayerName = options.buildLayerName;
      String rootClassPath = options.classPath;
      String mainDir = options.mainDir;
      String scInstallDir = options.scInstallDir;

      this.systemPtr = new LayerUtil.LayeredSystemPtr(this);
      this.options = options;
      this.peerMode = parentSystem != null;
      this.mainSystem = parentSystem;
      this.externalModelIndex = extModelIndex;
      this.systemInstalled = !options.installLayers;
      this.strataCodeInstallDir = scInstallDir;

      lastChangedModelTime = System.currentTimeMillis();

      if (!peerMode) {
         disabledLayersIndex = new HashMap<String, Layer>();
         disabledLayers = new ArrayList<Layer>();

         if (mainDir != null) {
            strataCodeMainDir = mainDir;
            isStrataCodeDir = true;
            if (options.verbose)
               verbose("StrataCode initialized with main directory: " + mainDir + " install directory: " + this.strataCodeInstallDir + " current directory: " + System.getProperty("user.dir"));
         }

         disabledRuntimes = options.disabledRuntimes;
         if (disabledRuntimes == null) {
            disabledRuntimes = new ArrayList<String>();
         }
         else if (options.verbose)
            verbose("Disable runtimes: " + disabledRuntimes);
      }
      else {
         disabledRuntimes = parentSystem.disabledRuntimes;
         strataCodeMainDir = parentSystem.strataCodeMainDir;
         isStrataCodeDir = parentSystem.isStrataCodeDir;
         typeIndexEnabled = parentSystem.typeIndexEnabled;
      }

      IRuntimeProcessor useRuntimeProcessor = useProcessDefinition == null ? null : useProcessDefinition.getRuntimeProcessor();

      // Need to set the runtime processor before we initialize the layers.  this lets us rely on getCompiledOnly and also seems like this will make bootstrapping easier.
      if (useRuntimeProcessor != null)
         runtimeProcessor = useRuntimeProcessor;
      if (useProcessDefinition != null)
         processDefinition = useProcessDefinition;

      if (rootClassPath == null)
         rootClassPath = System.getProperty("java.class.path");

      if (sysStartTime == -1)
         sysStartTime = System.currentTimeMillis();

      Language.registerLanguage(DependenciesLanguage.INSTANCE, "deps");
      Language.registerLanguage(JavaLanguage.INSTANCE, "java");
      Language.registerLanguage(JavaLanguage.INSTANCE, JavaLanguage.SCJ_SUFFIX);  // Alternate suffix for java files in the SC tree
      Language.registerLanguage(SCLanguage.INSTANCE, SCLanguage.DEFAULT_EXTENSION);
      TemplateLanguage objDefTemplateLang = new TemplateLanguage();
      objDefTemplateLang.compiledTemplate = false;
      objDefTemplateLang.runtimeTemplate = true;
      objDefTemplateLang.defaultExtendsType = "sc.lang.java.ObjectDefinitionParameters";
      objDefTemplateLang.needsJavascript = false; // Because these templates affect code-generation, don't need to transform them to JS code
      // This one is for object definition templates we process from Java code, i.e. as part of framework definitions from CompilerSettings, newTemplate, and objTemplate
      Language.registerLanguage(objDefTemplateLang, "sctd");
      TemplateLanguage dynTemplateLang = new TemplateLanguage();
      dynTemplateLang.compiledTemplate = false;
      dynTemplateLang.runtimeTemplate = true;
      dynTemplateLang.defaultExtendsType = "sc.lang.java.DynStubParameters";
      dynTemplateLang.needsJavascript = false; // Because these templates affect code-generation, don't need to transform them to JS code
      Language.registerLanguage(dynTemplateLang, "sctdynt");
      // This one is for object definition templates we process from Java code, i.e. as part of framework definitions from CompilerSettings, newTemplate, and objTemplate
      Language.registerLanguage(TemplateLanguage.INSTANCE, TemplateLanguage.SCT_SUFFIX);
      // TODO: Do not have a consistent defaultExtendsType based on how we use sctp but at least registering these as Templates for the IDE
      Language.registerLanguage(TemplateLanguage.INSTANCE, "sctp");
      Language.registerLanguage(CommandSCLanguage.INSTANCE, CommandSCLanguage.SCR_SUFFIX);

      // Not registering Javascript yet because it is not complete.  In most projects we just copy the JS files as well so don't need to parse them as a language
      JSLanguage.INSTANCE.initialize();

      layerPath = layerPathNames;

      // Make sure appGlobal and global scopes are available since those are defined in the both the compilation runtime and at runtime
      ScopeDefinition.initScopes();

      initLayerPath();

      // Sets the coreBuildLayer - used for storing dynamic stubs needed for the layer init process
      if (systemInstalled)
         LayerUtil.initCoreBuildLayer(this);

      // Do this before we init the layers so they can see the classes in the system layer
      initClassIndex(rootClassPath);
      initSysClassLoader(null, ClassLoaderMode.LIBS);
      if (coreBuildLayer != null)
         initSysClassLoader(coreBuildLayer, ClassLoaderMode.BUILD);

      // We only need to do this on Windows when run from IntelliJ or Cygwin.  It seems like jline works fine when you run from the cmd prompt
      // but not sure how to tell the difference.  The WindowsTerminal will hang on cygwin and when running in the debugger on windows.
      // TODO: validate these problems now with JLine2
      if (FileUtil.PATH_SEPARATOR_CHAR == ';' && System.getProperty("jline.terminal") == null)
         System.setProperty("jline.terminal", "jline.UnsupportedTerminal");

      if (startInterpreter) {
         initConsole();
      }

      if (newLayerDir == null) {
         // If the layer path is explicitly specified, by default we store new files in the last
         // directory int eh layer path
         if (layerPathNames != null && layerPathDirs != null && layerPathDirs.size() > 0) {
            newLayerDir = layerPathDirs.get(layerPathDirs.size() - 1).getPath();
         }
         else
            newLayerDir = mapLayerDirName(".");
      }
      // We could not find the real StrataCode install dir but we could find a layer so use that
      if (strataCodeMainDir == null)
         strataCodeMainDir = newLayerDir;

      if (!peerMode) {
         String scSourceConfig = getStrataCodeConfDir(SC_SOURCE_PATH);
         if (new File(scSourceConfig).canRead()) {
            scSourcePath = FileUtil.getFileAsString(scSourceConfig);
            if (scSourcePath != null) {
               scSourcePath = scSourcePath.trim();
               // For windows, fix this path to be file system specific
               if (FileUtil.FILE_SEPARATOR_CHAR != '/')
                  scSourcePath = FileUtil.unnormalize(scSourcePath);
            }
         }

         if (!systemInstalled) {
            if (initLayerNames != null) {
               // If at least one of the specified layers exist from the current directory consider this a valid layer directory
               // and don't try to install the system.
               for (int i = 0; i < initLayerNames.size(); i++) {
                  String layerDefFile = findLayerDefFileInPath(initLayerNames.get(i), null, null);
                  if (layerDefFile != null)
                     systemInstalled = true;
               }
            }
            if (!systemInstalled) {
               if (!installSystem())
                  System.exit(-1);
            }
         }
         if (typeIndex == null)
            typeIndex = new SysTypeIndex(this, getTypeIndexIdent());
      }

      if (!peerMode)
         this.repositorySystem = new RepositorySystem(new RepositoryStore(getStrataCodeHomeDir("pkgs")), messageHandler, this, options.verbose, options.reinstall, options.updateSystem, options.installExisting);
      else {
         messageHandler = parentSystem.messageHandler;
         this.repositorySystem = new RepositorySystem(parentSystem.repositorySystem.store, messageHandler, this, options.verbose, options.reinstall, options.updateSystem, options.installExisting);
      }

      if (initLayerNames != null) {
         initLayersWithNames(initLayerNames, options.dynamicLayers, options.allDynamic, explicitDynLayers, true, useProcessDefinition != null);
      }

      if (lastLayerName != null && !peerMode) {
         lastLayer = initLayer(lastLayerName, null, null, options.dynamicLayers, new LayerParamInfo());
         if (!specifiedLayers.contains(lastLayer))
            specifiedLayers.add(lastLayer);
         if (lastLayer == null) {
            throw new IllegalArgumentException("Can't initialize layers");
         }
      }

      // Since we changed the set of layers we need to update the command interpreter
      if (cmd != null) {
         cmd.updateLayerState();
      }

      // Init the runtimes from the main system if we are using the type index.
      if (!peerMode && (options.typeIndexMode == TypeIndexMode.Refresh || options.typeIndexMode == TypeIndexMode.Load)) {
         initTypeIndexRuntimes();
      }

      //boolean reusingDefaultRuntime = !javaIsAlwaysDefaultRuntime && runtimes != null && runtimes.size() == 1 && (processes == null || processes.size() == 0);
      boolean reusingDefaultRuntime = true;
      if (useProcessDefinition == null) {
         if (!reusingDefaultRuntime)
            removeExcludedLayers(true);
         initRuntimes(explicitDynLayers, true, false, true);
         if (!reusingDefaultRuntime)
            removeExcludedLayers(false);
      }
      else {
         runtimeProcessor = useRuntimeProcessor;
         processDefinition = useProcessDefinition;
         if (typeIndexDirName != null)
            initTypeIndexDir();
      }

      if (runtimeProcessor != null)
         runtimeProcessor.setLayeredSystem(this);

      setCurrent(this);

      // The main system will initialize all of the peer system build layers.  It's important to init the main
      // system first since it's the one which determines the buildDir in case one of the peer runtimes does not
      // have the same buildLayer. We still want to use the shared web folder in the main system's buildLayer and
      // that means we need to init the main system's buildLayer first.
      if (!peerMode || parentSystem.buildDir != null)
         initBuildSystem(!peerMode, true);

      if (excludedFiles != null) {
         excludedPatterns = new ArrayList<Pattern>(excludedFiles.size());
         for (int i = 0; i < excludedFiles.size(); i++)
            excludedPatterns.add(Pattern.compile(excludedFiles.get(i)));
      }
   }

   private void initConsole() {
      boolean consoleDisabled = false;
      String testInputName = null;
      // TODO: it would be nice to customize this in the layer def file but right now we create the interpreter before
      // we init the layers because we might run the "installSystem" wizard runs before we init the layers but that would be easy to fix
      // We could check systemInstalled here and create an interpreter, then destroy it and recreate it later if test-script-name is set
      if (System.console() == null) {
         jline.TerminalFactory.configure("off");
         try {
            // In.available an attempt to test for when redirected from stdin so turn off the prompt here.
            // Also turn off when testVerifyMode is on because the prompts just make output harder to read
            // and potentially interleave with other output and so mess up the 'diff'
            if (options.testVerifyMode || System.in.available() > 0) {
               consoleDisabled = true;
            }
         }
         catch (IOException exc) {
         }
      }

      // Need to fix the CommandInterpreter - it cannot handle empty package names in dialogs
      //cmd = System.console() != null ? new JLineInterpreter(this) : new CommandInterpreter(this, System.in);
      cmd = new JLineInterpreter(this, consoleDisabled, testInputName);
      if (options.testVerifyMode)
         cmd.noPrompt = true; // The prompt can appear at random times in the output so don't show it when in test-verify mode
   }

   public boolean getNeedsSync() {
      List<LayeredSystem> ss = getSyncSystems();
      return ss != null && ss.size() > 0;
   }

   public boolean hasSyncPeerTypeDeclaration(BodyTypeDeclaration type) {
      JavaModel typeModel = type.getJavaModel();

      if (typeModel == null || typeModel.isLayerModel)
         return false;

      List<LayeredSystem> syncSystems = getSyncSystems();
      if (syncSystems == null)
         return false;

      String typeName = ModelUtil.getTypeName(type);
      for (LayeredSystem syncSys:syncSystems) {
         Layer syncRefLayer = syncSys.getPeerLayerFromRemote(typeModel.getLayer());
         SrcEntry peerSrcEnt = syncSys.getSrcFileFromTypeName(typeName, true, null, true, null, syncRefLayer, false);
         if (peerSrcEnt != null)
            return true;
      }
      return false;
   }

   private void initBuildSystem(boolean initPeers, boolean fromScratch) {
      initBuildDir();
      initTypeIndexDir();

      Layer lastCompiled = lastLayer;
      while (lastCompiled != null && lastCompiled.dynamic)
         lastCompiled = lastCompiled.getPreviousLayer();

      lastCompiledLayer = lastCompiled;

      if (layeredClassPaths)
         lastCompiled.makeBuildLayer();

      boolean needsBuildAll = false;
      if (runtimeProcessor != null && buildLayer != null)
         needsBuildAll = runtimeProcessor.initRuntime(fromScratch);

      if (needsBuildAll)
         options.buildAllFiles = true;

      origBuildDir = buildDir;
      if (buildLayer != null)
         buildInfo = buildLayer.loadBuildInfo();

      for (int i = 0; i < layers.size(); i++) {
         Layer l = layers.get(i);
         if (l.isBuildLayer() && l.buildInfo == null) {
            if (l.buildSrcDir == null)
               l.initBuildDir();
            l.loadBuildInfo();
         }
      }

      if (initPeers && peerSystems != null) {
         for (LayeredSystem peerSys:peerSystems)
            peerSys.initBuildSystem(false, fromScratch);
      }
   }

   private void initRuntimes(List<String> explicitDynLayers, boolean active, boolean openLayer, boolean specifiedOnly) {
      LayeredSystem curSys = getCurrent();
      // If we have activated some layers and still don't have any runtimes, we create the default runtime
      if (runtimes == null && layers.size() != 0)
         addRuntime(null, null);

      boolean removeExcludedLayers = false;

      // Create a new LayeredSystem for each additional runtime we need to satisfy the active set of layers.
      // Then purge any layers from this LayeredSystem which should not be here.
      if (processes != null && processes.size() > 1 && (peerSystems == null || peerSystems.size() < processes.size() - 1)) {
         // We want all of the layered systems to use the same buildDir so pass it through options as though you had used the -da option.  Of course if you use -d, it will happen automatically.
         if (options.buildDir == null) {
            if (lastLayer != null && !lastLayer.buildSeparate && options.buildLayerAbsDir == null)
               options.buildLayerAbsDir = lastLayer.getDefaultBuildDir();
         }

         ArrayList<LayeredSystem> newPeers = new ArrayList<LayeredSystem>();

         for (int ix = 0; ix < processes.size(); ix++) {
            IProcessDefinition proc = processes.get(ix);

            // Skip the processor associated with the main layered system
            if (ProcessDefinition.compare(proc, processDefinition)) {
               // make the main layered system point to this process.
               if (processDefinition == null && proc != null)
                  updateProcessDefinition(proc);
               removeExcludedLayers(true);
               removeExcludedLayers = true;
               continue;
            }

            // If we have any peer systems, see if we have already created one for this runtime
            if (peerSystems != null) {
               LayeredSystem processPeer = null;
               for (LayeredSystem peer: peerSystems) {
                  if (ProcessDefinition.compare(peer.processDefinition, proc)) {
                     processPeer = peer;
                     break;
                  }
               }

               // Since we may have added layers, for each process see if those layers also need to go into the peer.
               if (processPeer != null) {
                  String runtimeName = processPeer.getRuntimeName();
                  String processIdent = processPeer.getProcessName();
                  if (active && !isRuntimeDisabled(runtimeName) && (isRuntimeActivated(runtimeName) || isProcessActivated(processIdent)))
                     updateSystemLayers(processPeer, specifiedOnly);
                  continue;
               }
            }

            if (proc != null && isRuntimeDisabled(proc.getRuntimeName())) {
               continue;
            }

            // Now create the new peer layeredSystem for this runtime.
            ArrayList<String> procLayerNames = new ArrayList<String>();
            ArrayList<String> dynLayerNames = new ArrayList<String>();

            if (active) {
               // It includes any layers in this runtime which belong in the new one
               addIncludedLayerNamesForProc(proc, procLayerNames, null, false);
               // We are adding a new runtime when we have existing runtimes.  This means that some layers in one of the existing peers
               // may also need to be in this runtime.  For any new layers, that have been added since the last initRuntimes call,
               // we have them in the core layered system at this stage, even if they do not belong there in the long term.
               if (peerSystems != null) {
                  for (LayeredSystem oldPeerSys : peerSystems) {
                     // As well as those layers in any peer runtimes that exist that as belong in the other runtime
                     oldPeerSys.addIncludedLayerNamesForProc(proc, procLayerNames, null, false);
                  }
               }
            }

            LayeredSystem peerSys = createPeerSystem(proc, procLayerNames, explicitDynLayers); if (!active) peerSys.inactiveRuntime = true;

            for (int i = 0; i < inactiveLayers.size(); i++) {
               Layer inactiveLayer = inactiveLayers.get(i);
               if (inactiveLayer.includeForProcess(proc)) {
                  Layer peerLayer = peerSys.getInactiveLayer(inactiveLayer.getLayerName(), openLayer, false, !inactiveLayer.disabled, false);
                  if (peerLayer == null)
                     System.err.println("*** failed to find peer layer");
               }
            }

            newPeers.add(peerSys);
         }

         initNewPeers(newPeers);

         if (modelListeners != null) {
            for (IModelListener ml: modelListeners) {
               for (LayeredSystem newPeer: newPeers) {
                  ml.runtimeAdded(newPeer);
               }
            }
         }

         if (removeExcludedLayers)
            removeExcludedLayers(false);
      }
      // We've added some layers to the this layered system through activate.  Now we need to go through the
      // other systems and include the layers in them if they are needed.
      else { // If there's only one runtime, we'll use this layered system for it.
         if (active && runtimeProcessor == null && processDefinition == null) {
            processDefinition = processes != null && processes.size() > 0 ? processes.get(0) : null;
            runtimeProcessor = processDefinition == null ? null : processDefinition.getRuntimeProcessor();
         }
         if (peerSystems != null && active) {  // This only initializes active layers so don't do it if we are not activating a layer - otherwise for doc we'll end up activating JS layers even if the JS runtime is not active
            for (LayeredSystem peerSys: peerSystems) {
               updateSystemLayers(peerSys, specifiedOnly);
            }
         }
         if (typeIndexDirName != null)
            initTypeIndexDir();
      }

      // This is set in the constructor for the new LayeredSystem so we need to restore it here
      setCurrent(curSys);
   }

   private void initNewPeers(ArrayList<LayeredSystem> newPeers) {
      if (peerSystems == null)
         peerSystems = newPeers;
      else
         peerSystems.addAll(newPeers);

      // Each layered system gets a list of the other systems
      for (LayeredSystem peer: peerSystems) {
         ArrayList<LayeredSystem> peerPeers = (ArrayList<LayeredSystem>) peerSystems.clone();
         peerPeers.remove(peer);
         peerPeers.add(this);
         peer.peerSystems = peerPeers;
      }
   }

   private LayeredSystem createPeerSystem(IProcessDefinition proc, ArrayList<String> procLayerNames, List<String> explicitDynLayers) {
      String peerLayerPath = layerPath;
      // When you run in the layer directory, the other runtime defines the root layer dir which we need as the layer path here since we specify all of the layers using absolute paths
      if (peerLayerPath == null && newLayerDir != null)
         peerLayerPath = newLayerDir;
      LayeredSystem peerSys = new LayeredSystem(procLayerNames, explicitDynLayers, peerLayerPath, options, proc, this, false, externalModelIndex);

      // The LayeredSystem needs at least the main layered system in its peer list to initialize the layers.  We'll reset this later to include all of the layeredSystems.
      if (peerSys.peerSystems == null) {
         ArrayList<LayeredSystem> tempPeerList = new ArrayList<LayeredSystem>();
         tempPeerList.add(this);
         peerSys.peerSystems = tempPeerList;
      }

      // Propagate any properties which directly go across to all peers
      if (!autoClassLoader)
         peerSys.setFixedSystemClassLoader(systemClassLoader);

      // Make sure the typeIndex and the processMap stay in sync.
      if (typeIndexProcessMap != null)
         peerSys.typeIndex = typeIndexProcessMap.get(peerSys.getTypeIndexIdent());
      if (peerSys.typeIndex == null) {
         peerSys.typeIndex = new SysTypeIndex(peerSys, peerSys.getTypeIndexIdent());
         if (typeIndexProcessMap != null)
            typeIndexProcessMap.put(peerSys.getTypeIndexIdent(), peerSys.typeIndex);
      }
      else
         peerSys.typeIndex.setSystem(peerSys);
      peerSys.disabledRuntimes = disabledRuntimes;

      return peerSys;
   }

   /**
    * Designed to be called from the main layered system
    */
   private void updateSystemLayers(LayeredSystem peerSys, boolean specifiedOnly) {
      if (peerMode)
         System.err.println("*** Warning - updating system layers from a peer system!");

      if (getNeedsProcess(peerSys.processDefinition)) {
         ArrayList<String> newPeerLayers = new ArrayList<String>();
         ArrayList<String> dynLayers = new ArrayList<String>();
         // Here only include layers that are included by a specified layer
         addIncludedLayerNamesForProc(peerSys.processDefinition, newPeerLayers, dynLayers, specifiedOnly);
         if (newPeerLayers.size() > 0) {
            peerSys.initLayersWithNames(newPeerLayers, false, false, dynLayers, true, true);
         }
      }
   }

   /**
    * Before we activate layers in a new runtime, make sure one of the specified layers requires that runtime.
    * otherwise, we drag in a lot of the overlapping layers, without the application layers being specified.
    */
   private boolean getNeedsProcess(IProcessDefinition proc) {
      for (Layer specLayer:specifiedLayers) {
         // Need to ignore the separate layers here since those get added as active layers even when it's pulled in by inactive layers.
         if (specLayer.includeForProcess(proc) && !specLayer.buildSeparate)
            return true;
      }
      return false;
   }

   private void addIncludedLayerNamesForProc(IProcessDefinition proc, List<String> procLayerNames, List<String> dynLayerNames, boolean specifiedLayers) {
      List<Layer> layersList = this.layers;
      for (int i = 0; i < layersList.size(); i++) {
         Layer layer = layersList.get(i);
         if (layer.includeForProcess(proc) && (!specifiedLayers || layer.isSpecifiedLayer())) {
            procLayerNames.add(layer.getLayerName());
            if (layer.dynamic && dynLayerNames != null)
               dynLayerNames.add(layer.getLayerName());
         }
      }
   }

   void removeExcludedLayers(boolean markOnly) {
      boolean resetLastLayer = false;
      // Remove any layers that don't belong in the runtime.  The other runtimes are configured with the right
      // layers from the start in the active set.
      if (!peerMode) {
         boolean needsDefaultProcess = getNeedsProcess(processDefinition);
         int activeRemoveIx = -1;
         for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);
            if (layer.isStarted())
               continue;
            // When we know we don't need the default process, we must automatically exclude the layer.  For other peer systems we only process the init layers if the process is needed, but for the main system, we use it to bootstrap all of the layers
            boolean included = (needsDefaultProcess || layer.buildSeparate) && layer.includeForProcess(processDefinition);
            if (!included || layer.excludeForProcess(processDefinition)) {
               layer.excluded = true;
               if (layer == lastLayer)
                  resetLastLayer = true;
               if (!markOnly) {
                  if (activeRemoveIx == -1)
                     activeRemoveIx = i;
                  layers.remove(i);
                  deregisterLayer(layer, false);
                  i--;
               }
            }
            else if (!markOnly)
               layer.layerPosition = i;
         }
         if (activeRemoveIx != -1) {
            for (int i = activeRemoveIx; i < layers.size(); i++) {
               layers.get(i).layerPosition = i;
            }
         }
      }

      if (resetLastLayer) {
         int sz = layers.size();
         if (sz > 0) {
            lastLayer = layers.get(sz - 1);
         }
         else
            lastLayer = null;
      }

      checkLayerPosition();

      int removeIx = -1;
      // Remove any layers that don't belong in this runtime
      for (int i = 0; i < inactiveLayers.size(); i++) {
         Layer inactiveLayer = inactiveLayers.get(i);
         if (inactiveLayer.excludeForProcess(processDefinition)) {
            inactiveLayer.markExcluded();
            if (!markOnly) {
               inactiveLayerIndex.remove(inactiveLayer.getLayerName());
               inactiveLayers.remove(i);
               if (removeIx == -1)
                  removeIx = i;
               i--;
            }
         }
      }
      if (removeIx != -1)
         renumberInactiveLayers(removeIx);

      checkLayerPosition();
      if (!markOnly && typeIndex != null)
         typeIndex.refreshLayerOrder(this);
   }

   public boolean installSystem() {
      System.out.println("No layers found in " + (layerPathDirs == null ? "current directory: " + System.getProperty("user.dir") : "layer path: " + layerPathDirs.toString()));
      if (cmd == null) {
         System.err.println("Warning - skipping install with no command interpreter.  Starting with no layers");
      }
      else {
         if (layersFilePathPrefix != null) {
            newLayerDir = FileUtil.concat(newLayerDir, layersFilePathPrefix);
         }
         String input = cmd.readLine("Enter path for an existing or new directory for layers: [" + newLayerDir + "]: ");
         if (input.startsWith("cmd.")) { // TODO: fix this up or remove it but for now if we try to install while running a test, we need to catch that right away.
            System.err.println("*** Error - trying to install during test script");
            System.exit(-1);
         }

         if (input.trim().length() > 0) {
            strataCodeMainDir = newLayerDir = input;

            if (isValidLayersDir(newLayerDir)) {
               systemInstalled = true;
               return true;
            }
         }

         input = cmd.readLine("No StrataCode layers found - install default layers into: " + newLayerDir + "? [y/n]: ");
         if (input != null && (input.equalsIgnoreCase("y") || input.equalsIgnoreCase("yes"))) {
            String err = LayerUtil.installDefaultLayers(newLayerDir, null, options.info, null);
            if (err != null)
               return false;
            newLayerDir = FileUtil.concat(newLayerDir, DEFAULT_LAYERS_PATH);
            systemInstalled = true;
            if (layerPath == null) {
               layerPath = newLayerDir;
               layerPathDirs = new ArrayList<File>();
               layerPathDirs.add(new File(layerPath));
            }
         }
         else if (options.verbose)
            verbose("Skipping install - starting with no layers");
      }
      return true;
   }

   public void performExitCleanup() {
      if (options.clearOnExit) {
         // Any mainMethods which implement IStoppable get stopped before we shutdown.  This should stop the server etc. and all requests that will come back in
         // to access the layered system after it's been stopped.
         buildInfo.stopMainInstances();

         for (ISystemExitListener exitListener:systemExitListeners)
            exitListener.systemExiting();

         destroySystem();
      }
   }

   private void destroySystemInternal() {
      clearActiveLayers(true);

      if (runtimes != null) {
         for (int i = 0; i < runtimes.size(); i++) {
            if (DefaultRuntimeProcessor.compareRuntimes(runtimes.get(i), runtimeProcessor)) {
               runtimes.remove(i);
               i--;
            }
         }
      }
      if (processes != null) {
         for (int i = 0; i < processes.size(); i++) {
            if (ProcessDefinition.compare(processes.get(i), processDefinition)) {
               processes.remove(i);
               i--;
            }
         }
      }

      setCurrent(null);
      if (!peerMode && peerSystems != null) {
         for (int i = 0; i < peerSystems.size(); i++) {
            LayeredSystem peerSys = peerSystems.get(i);
            peerSys.destroySystemInternal();
         }
         peerSystems = null; // cull the reference graph to these
      }
      lastInitedInactiveLayer = null;
      inactiveModelIndex = null;
      modelIndex = null;
      if (inactiveLayers != null) {
         for (Layer l:inactiveLayers) {
            l.destroyLayer();
         }
      }
      if (disabledLayers != null) {
         for (Layer l:disabledLayers) {
            l.destroyLayer();
         }
      }
      Language.cleanupLanguages();
      cleanupFileProcessors();
      if (buildClassLoader instanceof TrackingClassLoader)
         buildClassLoader = ((TrackingClassLoader) buildClassLoader).resetBuildLoader();
      inactiveLayers = null;
      inactiveLayerIndex = null;
      layers = null;
      fileProcessors = null;
      filePatterns = null;
      disabledLayers = null;
      disabledLayersIndex = null;

      // Various threads might still hold onto the systemPtr but let them GC the LayeredSystem object
      // Maybe a more robust way to do this would be to enumerate the thread local map and remove any refs to this system so we don't even leak those objects.
      systemPtr.system = null;

      if (typeIndexProcessMap != null) {
         for (Map.Entry<String, SysTypeIndex> indexEnt : typeIndexProcessMap.entrySet()) {
            SysTypeIndex sysTypeIndex = indexEnt.getValue();
            sysTypeIndex.clearInactiveLayers();
         }
      }

      if (!options.disableAutoGC)
         System.gc();
   }

   public void destroySystem() {
      acquireDynLock(false);
      try {
         destroySystemInternal();
      }
      finally {
         releaseDynLock(false);
      }
   }

   /**
    * We associate some languages - even those that are static, with a "defined in layer" so we can determine how that language is applied to files for a given system
    * When the system is destroyed, we need to remove that association.
    */
   private void cleanupFileProcessors() {
      for (Map.Entry<String,IFileProcessor[]> procEnt:fileProcessors.entrySet()) {
         IFileProcessor[] procList = procEnt.getValue();
         for (IFileProcessor proc:procList) {
            Layer l = proc.getDefinedInLayer();
            if (l != null && l.removed)
               proc.setDefinedInLayer(null);
         }
      }
      for (Map.Entry<Pattern,IFileProcessor> procEnt:filePatterns.entrySet()) {
         IFileProcessor proc = procEnt.getValue();
         Layer l = proc.getDefinedInLayer();
         if (l != null && l.removed)
            proc.setDefinedInLayer(null);
      }
   }

   /**
    * Called from a Layer's start method to install a new runtime which that layer requires.  A proc value of "null" means to require the default "java" runtime.  A LayeredSystem is created for each runtime.  Each LayeredSystem haa
    * one runtimeProcessor (or null).
    */
   public static void addRuntime(Layer fromLayer, IRuntimeProcessor proc) {
      if (runtimes == null) {
         runtimes = new ArrayList<IRuntimeProcessor>();
         if (proc != null && javaIsAlwaysDefaultRuntime)
            addRuntime(null, null);
      }

      IRuntimeProcessor existing = null;
      for (IRuntimeProcessor existingProc:runtimes) {
         if (proc == null && existingProc == null)
            return;
         else if (existingProc != null && proc != null && proc.getRuntimeName().equals(existingProc.getRuntimeName()))
            existing = existingProc;
      }
      // Replace the old runtime - allows a subsequent layer to redefine the parameters of the JSRuntimeProcessor or even subclass it
      if (existing != null) {
         int ix = runtimes.indexOf(existing);
         runtimes.set(ix, proc);
      }
      else {
         // Default is always the first one in the list if it's there.
         if (proc == null) {
            runtimes.add(0, null);
         }
         else
            runtimes.add(proc);

         int i;
         int sz = processes == null ? 0 : processes.size();
         for (i = 0; i < sz; i++) {
            IProcessDefinition procDef = processes.get(i);
            IRuntimeProcessor procProc = procDef == null ? null : procDef.getRuntimeProcessor();

            if (procProc == proc)
               break;
         }
         // Add a new process only if there isn't one already defined for this runtime.
         if (i == sz) {
            ProcessDefinition newProcDef = proc == null ? null : new ProcessDefinition();
            if (newProcDef != null)
               newProcDef.setRuntimeProcessor(proc);
            addProcess(fromLayer, newProcDef);
         }
      }
      procInfoNeedsSave = true;
   }

   private void updateProcessDefinition(IProcessDefinition procDef) {
      processDefinition = procDef;
      initTypeIndexDir();
   }

   private static void initProcessesList(IProcessDefinition procDef) {
      if (processes == null) {
         processes = new ArrayList<IProcessDefinition>();
         if (procDef != null && javaIsAlwaysDefaultRuntime)
            addProcess(null, null);
      }
   }

   public static void addProcess(Layer fromLayer, IProcessDefinition procDef) {
      initProcessesList(procDef);
      IProcessDefinition existing = null;
      int procIndex = 0;
      for (IProcessDefinition existingProc:processes) {
         if (procDef == null && existingProc == null)
            return;
         // If the existing proc is null it's a default runtime and no designated process.  If the new guy has a name and the same runtime process, just use it rather than creating a new one.
         else if (existingProc == null && procDef.getRuntimeProcessor() == null && getProcessDefinition(procDef.getProcessName(), procDef.getRuntimeName()) == null) {
            processes.set(procIndex, procDef);
            if (fromLayer != null && fromLayer.layeredSystem.processDefinition == null)
               fromLayer.layeredSystem.updateProcessDefinition(procDef);
            procInfoNeedsSave = true;
            return;
         }
         else if (existingProc != null && procDef != null && procDef.getRuntimeName().equals(existingProc.getRuntimeName()) && StringUtil.equalStrings(procDef.getProcessName(), existingProc.getProcessName()))
            existing = existingProc;
         procIndex++;
      }
      // Replace the old runtime - allows a subsequent layer to redefine the parameters of the JSRuntimeProcessor or even subclass it
      if (existing != null) {
         int ix = processes.indexOf(existing);
         processes.set(ix, procDef);
         if (fromLayer != null && fromLayer.layeredSystem.processDefinition == existing)
            fromLayer.layeredSystem.updateProcessDefinition(procDef);
      }
      else {
         // Default is always the first one in the list if it's there.
         if (procDef == null) {
            processes.add(0, null);
         }
         else
            processes.add(procDef);
      }
      procInfoNeedsSave = true;

      // Each process has a runtimeProcessor so make sure to add that runtime, if it's not here
      IRuntimeProcessor rtProc = procDef == null ? null : procDef.getRuntimeProcessor();
      if (runtimes == null || !runtimes.contains(rtProc))
         addRuntime(fromLayer, rtProc);
   }

   public static boolean createDefaultRuntime(Layer fromLayer, String name, boolean needsContextClassLoader) {
      if (runtimes != null) {
         for (IRuntimeProcessor proc:runtimes) {
            if (proc != null) {
               String procName = proc.getRuntimeName();
               if (procName.equals(name))
                  return false;
            }
         }
      }
      addRuntime(fromLayer, new DefaultRuntimeProcessor(name, needsContextClassLoader));
      return true;
   }

   /** Use this to find the LayeredSystem to debug for the given runtime (or null for the 'java' runtime0 */
   public LayeredSystem getActiveLayeredSystem(String runtimeName) {
      if (runtimeName == null)
         runtimeName = IRuntimeProcessor.DEFAULT_RUNTIME_NAME;
      boolean isDefault = runtimeName.equals(IRuntimeProcessor.DEFAULT_RUNTIME_NAME);
      if (layers.size() > 0 && ((runtimeProcessor == null && isDefault) || (runtimeProcessor != null && runtimeProcessor.getRuntimeName().equals(runtimeName))))
         return this;
      if (peerSystems != null) {
         for (int i = 0; i < peerSystems.size(); i++) {
            LayeredSystem peerSys = peerSystems.get(i);
            if (peerSys.getRuntimeName().equals(runtimeName) && peerSys.layers.size() > 0)
               return peerSys;
         }
      }
      return null;
   }

   public List<LayeredSystem> getActiveLayeredSystems() {
      ArrayList<LayeredSystem> res = new ArrayList<LayeredSystem>();
      if (layers.size() > 0)
         res.add(this);
      if (peerSystems != null) {
         for (int i = 0; i < peerSystems.size(); i++) {
            LayeredSystem peerSys = peerSystems.get(i);
            if (peerSys.layers.size() > 0)
               res.add(peerSys);
         }
      }
      return res;
   }

   public static IRuntimeProcessor getRuntime(String name) {
      if (runtimes == null)
         return null;
      for (IRuntimeProcessor proc:runtimes) {
         if (proc != null) {
            String procName = proc.getRuntimeName();
            if (procName.equals(name))
               return proc;
         }
      }
      return null;
   }

   public boolean hasActiveRuntime(String name) {
      String rtName = getRuntimeName();
      if (rtName != null && rtName.equals(name))
         return true;
      if (peerSystems != null) {
         for (LayeredSystem peerSys:peerSystems) {
            rtName = peerSys.getRuntimeName();
            if (rtName != null && rtName.equals(name) && peerSys.layers.size() > 0)
               return true;
         }
      }
      return false;
   }

   public static IProcessDefinition getProcessDefinition(String procName, String runtimeName) {
      if (processes != null) {
         for (IProcessDefinition proc : processes) {
            if (proc == null && procName == null && runtimeName == null)
               return null;

            if (proc != null && StringUtil.equalStrings(procName, proc.getProcessName()) && StringUtil.equalStrings(proc.getRuntimeName(), runtimeName))
               return proc;
         }
      }
      return null;
   }

   public IProcessDefinition getProcessDefinition(String runtimeName, String procName, boolean restore) {
      if (processes != null) {
         for (IProcessDefinition proc : processes) {
            if (proc == null && procName == null && runtimeName == null)
               return null;

            if (proc != null && StringUtil.equalStrings(procName, proc.getProcessName()) && StringUtil.equalStrings(proc.getRuntimeName(), runtimeName))
               return proc;
         }
      }
      if (restore) {
         ProcessDefinition proc = procName == null ? new ProcessDefinition() : ProcessDefinition.readProcessDefinition(this, runtimeName, procName);
         if (proc != null) {
            proc.runtimeProcessor = getOrRestoreRuntime(runtimeName);
            if (proc.runtimeProcessor == null && !runtimeName.equals(IRuntimeProcessor.DEFAULT_RUNTIME_NAME)) {
               error("Error - unable to restore cached definition of runtime: " + runtimeName);
               return INVALID_PROCESS_SENTINEL;
            }
            addProcess(null, proc);
         }
         return proc;
      }
      return null;
   }

   public IRuntimeProcessor getOrRestoreRuntime(String name) {
      IRuntimeProcessor proc = getRuntime(name);
      if (proc == null) {
         proc = DefaultRuntimeProcessor.readRuntimeProcessor(this, name);
         if (proc != null) {
            if (runtimes == null) {
               runtimes = new ArrayList<IRuntimeProcessor>();
               if (javaIsAlwaysDefaultRuntime)
                  addRuntime(null, null);
            }
            runtimes.add(proc);
         }
      }
      return proc;
   }

   /** When there's more than one runtime, need to prefix the src, classes with the runtime name */
   public String getRuntimePrefix() {
      if (runtimePrefix == null) {
         return getRuntimeName();
         // TODO: there's a problem using the process name which is that it can change from null to some new process name as we add the first layer which depends on a specific process.  We don't handle that
         // transition cleanly now...  One option is to leave only one runtime - say the web framework as the default process and not to change it at all for a given system.
         //return getProcessIdent();  // Returns 'java' or java_server if there's a defined process name
      }
      return runtimePrefix;
   }

   public String getRuntimeName() {
      return runtimeProcessor == null ? IRuntimeProcessor.DEFAULT_RUNTIME_NAME : runtimeProcessor.getRuntimeName();
   }

   public String getProcessName() {
      return processDefinition == null ? IProcessDefinition.DEFAULT_PROCESS_NAME : processDefinition.getProcessName();
   }

   public String getProcessLabel() {
      String procName = getProcessName();
      return procName == null ? "Runtime: " + getRuntimeName() : CTypeUtil.capitalizePropertyName(procName) + ": " + getRuntimeName();
   }

   public String getBuildInfoFile() {
      return BUILD_INFO_FILE;
   }

   public String getDynTypeIndexFile() {
      return DYN_TYPE_INDEX_FILE;
   }

   public boolean getServerEnabled() {
      LayeredSystem main = getMainLayeredSystem();
      if (main == this)
         return serverEnabled;
      return main.serverEnabled;
   }

   public String getServerURL() {
      LayeredSystem main = getMainLayeredSystem();
      if (main == this)
         return serverEnabled ? ("http://" + serverName + (serverPort == 80 ? "" : ":" + serverPort)) + "/" : "file:" + buildDir + "/web/";
      else
         return main.getServerURL();
   }

   public String getURLForPath(URLPath urlPath) {
      sc.lang.pattern.Pattern pattern = sc.lang.pattern.Pattern.initURLPattern(urlPath.pageType, urlPath.url);
      String url = pattern.evalPatternWithInst(null, null);
      if (url == null)
         return null;

      String res = url;
      if (res.equals("") && serverEnabled)
         res = "index.html";
      if (res.startsWith("/"))
         res = res.substring(1);
      if (serverEnabled)
         return FileUtil.concatNormalized(getServerURL(), res);
      else {
         for (int lix = layers.size() - 1; lix >= 0; lix--) {
            Layer curLayer = layers.get(lix);
            if (curLayer.isBuildLayer() && curLayer.buildDir != null) {
               String filePath = FileUtil.concatNormalized(curLayer.buildDir, "web", res);
               if (new File(filePath).canRead()) {
                  return "file://" + filePath;
               }
            }
         }
         // The file might be in the "modelsToPostBuild" which has not yet been built.
         // TODO: is there logic we could add to make sure the file will be built here?
         if (buildLayer != null)
            return "file://" + FileUtil.concatNormalized(buildLayer.buildDir, "web", res);
         System.err.println("*** Unable to find URL for path: " + res + " in web directory of the buildDirs");
         return null;
      }
   }

   public boolean testPatternMatches(String value) {
      if (options.testPattern == null)
         return true;

      Pattern p = Pattern.compile(options.testPattern);
      return p.matcher(value).matches();
   }

   // TODO, if the desktop and server versions both want to sync against JS, it should return both systems
   /** By default we sync against one layered system for each runtime, unless the process opts out of the default sync profile for that runtime. */
   public List<LayeredSystem> getSyncSystems() {
      ArrayList<LayeredSystem> res = new ArrayList<LayeredSystem>();
      List<LayeredSystem> peerSysList;
      List<String> syncProcessNames = null; // sync against the default process
      if (runtimeProcessor != null && runtimeProcessor.getLayeredSystem() != null) {
         peerSysList = runtimeProcessor.getLayeredSystem().peerSystems;
      }
      else
         peerSysList = peerSystems;

      syncProcessNames = processDefinition == null ?  runtimeProcessor == null ? null : runtimeProcessor.getSyncProcessNames(): processDefinition.getSyncProcessNames();

      if (peerSysList != null) {
         for (LayeredSystem peerSys:peerSysList) {
            // No active layers - just skip it.
            IProcessDefinition procDef = peerSys.processDefinition;
            List<String> peerSyncProcNames = peerSys.processDefinition == null ? null : peerSys.processDefinition.getSyncProcessNames();

            // By default, we do not sync against the same runtime unless there are two process which need to sync
            if ((syncProcessNames == null || !syncProcessNames.contains(peerSys.getProcessName())) &&
                 (peerSyncProcNames == null || !peerSyncProcNames.contains(getProcessName())))
               continue;

            res.add(peerSys);
         }
         return res;
      }
      else
         return null;
   }

   public LayeredSystem getMainLayeredSystem() {
      if (!peerMode)
         return this;
      if (mainSystem != null)
         return mainSystem;
      if (peerSystems != null) {
         for (LayeredSystem sys:peerSystems)
            if (!sys.peerMode)
               return sys;
      }
      System.err.println("*** Invalid LayeredSystem - no main layered system - peerMode is true but no other system which is acting as the main system");
      return null;
   }

   public boolean isMainSystem() {
      return !peerMode;
   }

   public LayeredSystem getPeerLayeredSystem(String processIdent) {
      if (peerSystems != null) {
         for (LayeredSystem sys:peerSystems)
            if (sys.getProcessIdent().equals(processIdent))
               return sys;
      }
      return null;
   }

   public boolean getNeedsAnonymousConversion() {
      return runtimeProcessor != null && runtimeProcessor.getNeedsAnonymousConversion();
   }

   private void initLayersWithNames(List<String> initLayerNames, boolean dynamicByDefault, boolean allDynamic, List<String> explicitDyn, boolean specifiedLayers, boolean explicitLayers) {
      try {
         acquireDynLock(false);
         PerfMon.start("initLayers");
         LayerParamInfo lpi = new LayerParamInfo();
         lpi.explicitLayers = explicitLayers;
         if (dynamicByDefault) {
            lpi.explicitDynLayers = new ArrayList<String>();
            // We support either . or / as separate for layer names so be sure to match both
            for (String initLayer : initLayerNames) {
               lpi.explicitDynLayers.add(initLayer.replace('.', '/'));
               lpi.explicitDynLayers.add(initLayer.replace('/', '.'));
               if (allDynamic)
                  lpi.explicitDynLayers.add("<all>");
            }
         }
         if (explicitDyn != null) {
            ArrayList<String> aliases = new ArrayList<String>();
            for (int i = 0; i < explicitDyn.size(); i++) {
               String exLayer = explicitDyn.get(i);
               exLayer = FileUtil.removeTrailingSlash(exLayer);
               aliases.add(exLayer.replace('.', '/'));
               aliases.add(exLayer.replace('/', '.'));
            }
            aliases.addAll(explicitDyn);
            if (options.recursiveDynLayers)
               lpi.recursiveDynLayers = aliases;
            if (lpi.explicitDynLayers == null)
               lpi.explicitDynLayers = aliases;
            else
               lpi.explicitDynLayers.addAll(aliases);
         }
         List<Layer> resLayers = initLayers(initLayerNames, null, null, dynamicByDefault, lpi, specifiedLayers);
         if (resLayers == null || resLayers.contains(null)) {
            anyErrors = true;
            throw new IllegalArgumentException("Can't initialize init layers: " + initLayerNames);
         }
         // In the first pass we create each layer, then once all are created we initialize them in order, then we start them in order
         initializeLayers(lpi);
         PerfMon.end("initLayers");
      }
      finally {
         releaseDynLock(false);
      }
   }

   private void initBuildDir() {
      if (layers.size() > 0) {
         // Since we may need to generate dynamic stubs, we'll use the last layer, even if it is dynamic.
         // The previous design was to make the buildDir the last-non-dynamic layer which would be nice but only
         // if it was built the same no matter what the other layers.  Since dynamic layers inject dependencies,
         // you can get a new build if you need to add a new stub.
         buildLayer = layers.get(layers.size()-1);

         // Initializing the buildDir.  Must be done after the runtime processors are set up because that affects
         // the choice of the buildDir for a given layer.
         for (int i = 0; i < layers.size(); i++) {
            Layer l = layers.get(i);
            if (l.isBuildLayer() && (l.buildDir == null || l.buildSrcDir == null))
               l.initBuildDir();
         }

         String oldBuildDir = loadedBuildLayers.get(buildLayer.layerUniqueName);
         if (oldBuildDir != null && !buildLayer.getBuildClassesDir().equals(oldBuildDir))
            buildLayer.setBuildClassesDir(oldBuildDir);

         if (buildLayer != null) {
            /*
            if (options.buildDir == null)
               buildDir = buildLayer.buildDir;
            else {
               buildLayer.buildDir = buildDir = FileUtil.concat(options.buildDir, buildLayer.getUnderscoreName());
            }
            */
            buildDir = buildLayer.buildDir;
            if (repositorySystem != null) {
               repositorySystem.pkgIndexRoot = FileUtil.concat(buildDir, "pkgIndex");
            }

            // Only set the first time
            if (commonBuildDir == null) {
               commonBuildDir = buildDir;
               commonBuildLayer = buildLayer;
            }

            /*
               String layerDirName = buildLayer.getUnderscoreName();

            if (options.buildSrcDir == null && options.buildDir == null)
               buildSrcDir = buildLayer.buildSrcDir;
            else {
               // If we're are computing the buildSrcDir from the buildDir, append the runtime prefix
               if (options.buildSrcDir == null)
                  buildLayer.buildSrcDir = buildSrcDir = FileUtil.concat(options.buildDir, layerDirName, getRuntimePrefix(), buildLayer.getBuildSrcSubDir());
               else
                  buildLayer.buildSrcDir = buildSrcDir = FileUtil.concat(options.buildSrcDir, layerDirName, getRuntimePrefix(), buildLayer.getBuildSrcSubDir());
               buildSrcDir = buildLayer.buildSrcDir = FileUtil.concat(LayerUtil.getLayerClassFileDirectory(buildLayer, buildLayer.layerPathName, true), getRuntimePrefix(), buildLayer.getBuildSrcSubDir());
            }
            */
            buildSrcDir = buildLayer.buildSrcDir;

            buildDirFile = initBuildFile(buildDir);
            buildSrcDirFile = initBuildFile(buildSrcDir);
            buildClassesDir = FileUtil.concat(buildDir, getRuntimePrefix(), buildLayer.getBuildClassesSubDir());
            initBuildFile(buildClassesDir);

            // Mark this as a build layer so we know to use its build directory in the classpath
            buildLayer.makeBuildLayer();
         }
      }
   }

   /** This should ideally be synchronized with coreRuntime's Bind classes CompilerSettings - the two ways we build this src jar file */
   private final static String[] runtimePackages = {"sc/util", "sc/type", "sc/bind", "sc/obj", "sc/dyn", "sc/js", "sc/sync"};

   private final static String[] layerModelPackages = {"java.lang", "java.util", "sc.lang", "sc.lang.sc", "sc.lang.html", "sc.lang.template",
           "sc.layer" /* For LayeredSystem in layerDef files */, "sc",  "java" /* need the root name of the package name in here */};

   /** These are packages which do not check for source definitions during the resolution of a layer definition file */
   public static HashSet<String> systemLayerModelPackages = new HashSet<String>(Arrays.asList(layerModelPackages));

   /** Once we've reloaded a type, we may have added dependencies on that type which were lost after we flushed the type cache.  On the positive, the reverse deps are now fresh so all we have to do is to reload them. */
   public boolean staleModelDependencies(String typeName) {
      return !options.buildAllFiles || typeWasReloaded(typeName);
   }

   public void flushClassCache(String typeName) {
      if (otherClassCache != null)
         otherClassCache.remove(typeName);
      compiledClassCache.remove(typeName);
      innerTypeCache.clear();
   }

   private class RuntimeRootInfo {
      String zipFileName;
      String buildDirName;
   }

   public String getStrataCodeRuntimePath(boolean core, boolean src) {
      // sc.core.src.path or sc.src.path
      String propName = "sc." + (core ? "core." : "") + (src ? "src." : "") + "path";
      String path;
      if ((path = System.getProperty(propName)) != null)
         return path;
      // We want scSourcePath to override the install directory if both are set
      if (!src || scSourcePath == null) {
         RuntimeRootInfo info = getRuntimeRootInfo();
         if (info.zipFileName != null) {
            String dir = FileUtil.getParentPath(info.zipFileName);
            String res = FileUtil.concat(dir, "scrt" + (core ? "-core" : "") + (src ? "-src" : "") + ".jar");
            if (new File(res).canRead())
               return res;
         }
         if (info.buildDirName != null) {
            String sysRoot;
            if ((sysRoot = getSystemBuildLayer(info.buildDirName)) != null) {
               if (!src)
                  return FileUtil.concat(sysRoot, core ? "coreRuntime" : "fullRuntime", !isIDEBuildLayer(info.buildDirName) ? "build" : null);
            }
         }
      }

      if (scSourcePath != null) {
         String scRuntimePath = FileUtil.concat(scSourcePath, core ? "coreRuntime": "fullRuntime", "src");
         File f = new File(scRuntimePath);
         if (!f.canRead())
            System.err.println("*** Unable to determine SCRuntimePath due to non-standard location of the sc.util, type, binding obj, and dyn packages - missing: " + scRuntimePath + " inside of scSourcePath: " + scSourcePath);
         else
            return scRuntimePath;
      }
      System.err.println("Error - sourcePath is not set in: " + getStrataCodeConfDir(SC_SOURCE_PATH));
      return null;
   }

   private static String getSystemBuildLayer(String buildDirName) {
      String parent;
      if (FileUtil.getFileName(buildDirName).equals("build") && (parent = FileUtil.getParentPath(FileUtil.getParentPath(buildDirName))) != null && FileUtil.getFileName(parent).equals("sc"))
         return parent;
      if (isIDEBuildLayer(buildDirName))
         return FileUtil.getParentPath(buildDirName);
      return null;
   }

   // The IntelliJ IDE stores files in modules in parallel directory trees.  We rely on that to cobble together locations
   // for the runtime files when building projects of various kinds.
   private static boolean isIDEBuildLayer(String buildDirName) {
      String file;
      if ((file = FileUtil.getFileName(buildDirName)).equals("coreRuntime") || file.equals("fullRuntime"))
         return true;
      return false;
   }

   /** Locates the StrataCode runtime in the system paths so that we can use this to ensure generated scripts pick up the sc libraries */
   public RuntimeRootInfo getRuntimeRootInfo() {
      RuntimeRootInfo rootInfo = new RuntimeRootInfo();
      String propName = "sc.core.path";
      String path;
      if ((path = System.getProperty(propName)) != null) {
         rootInfo.zipFileName = path;
         return rootInfo;
      }
      boolean warned = false;

      if (strataCodeInstallDir != null) {
         String mainJarFile = FileUtil.concat(strataCodeInstallDir, STRATACODE_LIBRARIES_FILE);
         if (!new File(mainJarFile).canRead()) {
            error("Install directory: " + strataCodeInstallDir + " missing main jar file: " + mainJarFile);
         }
         rootInfo.zipFileName = mainJarFile;
         return rootInfo;
      }
      /** We were not configured with an install dir so look to find sc.jar in the class index and create it from that */
      for (int i = 0; i < runtimePackages.length; i++) {
         String pkg = runtimePackages[i];
         pkg = FileUtil.unnormalize(pkg);
         HashMap<String,PackageEntry> pkgContents = packageIndex.get(pkg);
         if (pkgContents == null) {
            System.err.println("*** runtime package: " + pkg + " not found in the classpath - check classpath to be sure it contains a valid sc.jar");
            continue;
         }
         for (Map.Entry<String,PackageEntry> pkgMapEnt:pkgContents.entrySet()) {
            PackageEntry ent = pkgMapEnt.getValue();
            while (ent != null && ent.src)
               ent = ent.prev;
            if (ent == null)
               continue;  // Happens for sc.util classes
            // Also happens for sc.util - because after we've built the system we may have the util layer in the index.
            if (ent.fileName != null && ent.fileName.equals(buildDir))
               continue;
            else if (ent.zip) {
               if (rootInfo.zipFileName == null) {
                  if (rootInfo.buildDirName != null) {
                     if (!warned)
                        System.err.println("*** Warning - sc runtime files split across multiple build directories and zip file: " + rootInfo.buildDirName + " and: " + ent.fileName);
                     warned = true;
                  }
                  else
                     rootInfo.zipFileName = ent.fileName;
               }
               else if (!rootInfo.zipFileName.equals(ent.fileName)) {
                  if (!warned)
                     System.err.println("*** Warning - sc runtime files split across multiple zip files: " + rootInfo.zipFileName + " and: " + ent.fileName);
                  warned = true;
               }
            }
            else {
               if (rootInfo.buildDirName == null) {
                  if (rootInfo.zipFileName != null) {
                     if (!warned)
                        System.err.println("*** Warning - sc runtime files split across multiple build directories and zip file: " + rootInfo.zipFileName + " and: " + ent.fileName);
                     warned = true;
                  }
                  else {
                     rootInfo.buildDirName = ent.fileName;
                  }
               }
               else if (!rootInfo.buildDirName.equals(ent.fileName)) {
                  String oldSysRoot = getSystemBuildLayer(rootInfo.buildDirName);
                  String newSysRoot = getSystemBuildLayer(ent.fileName);
                  if (oldSysRoot == null || newSysRoot == null || !oldSysRoot.equals(newSysRoot)) {
                     if (!warned)
                        System.err.println("*** Warning - sc runtime files split across multiple build directories: " + rootInfo.buildDirName + " and: " + ent.fileName);
                     warned = true;
                  }
               }
            }
         }
      }
      return rootInfo;
   }

   private final static String STRATACODE_RUNTIME_FILE = "scrt.jar";

   private void syncRuntimeLibraries(String dir) {
      RuntimeRootInfo info = getRuntimeRootInfo();
      String outJarName = FileUtil.concat(dir, STRATACODE_RUNTIME_FILE);
      if (dir != null && dir.length() > 0) {
         File f = new File(dir);
         f.mkdirs();
      }
      if (info.buildDirName != null) {
         info("Building scrt.jar from class dir: " + info.buildDirName + " into: " + outJarName);
         String sysBuildDir = getSystemBuildLayer(info.buildDirName);
         if (sysBuildDir != null) {
            // Merge coreRuntime and fullRuntime and copy that to the runtime libs dir.

            String scSourcePath = FileUtil.concat(sysBuildDir, "coreRuntime") + FileUtil.PATH_SEPARATOR + FileUtil.concat(sysBuildDir, "fullRuntime");
            if (LayerUtil.buildJarFile(null, getRuntimePrefix(), outJarName, null, runtimePackages, null, scSourcePath, LayerUtil.CLASSES_JAR_FILTER, options.verbose) != 0)
               System.err.println("*** Unable to create jar of sc runtime files. dest path: " + outJarName + " from buildDir: " + info.buildDirName + " merging with path: " + scSourcePath);

         }
         else {
            if (LayerUtil.buildJarFile(info.buildDirName, getRuntimePrefix(), outJarName, null,  runtimePackages, /* userClassPath */ null, null, LayerUtil.CLASSES_JAR_FILTER, options.verbose) != 0)
               System.err.println("*** Unable to jar up sc runtime files into: " + outJarName + " from buildDir: " + info.buildDirName);

         }
      }
      else if (info.zipFileName != null) {
         String zipDirName = FileUtil.getParentPath(info.zipFileName);

         // We're only supposed to copy the runtime part.  Assume that scrt.jar is right next to sc.jar
         if (FileUtil.getFileName(info.zipFileName).equals(STRATACODE_LIBRARIES_FILE)) {
            info.zipFileName = FileUtil.concat(zipDirName, "scrt.jar");
         }

         info("Copying scrt.jar from: " + info.zipFileName + " to: " + outJarName);
         if (!FileUtil.copyFile(info.zipFileName, outJarName, true))
            error("*** Attempt to copy sc runtime files from: " + info.zipFileName + " to lib directory: " + dir + " failed");
      }
   }

   private final static String STRATACODE_RUNTIME_SRC_FILE = "scrt-core-src.jar";

   private void syncRuntimeSrc(String dir) {
      String srcDir = getStrataCodeRuntimePath(true, true);
      File srcDirFile = new File(srcDir);

      String outJarName = FileUtil.concat(dir, STRATACODE_RUNTIME_SRC_FILE);
      if (dir != null && dir.length() > 0) {
         File f = new File(dir);
         f.mkdirs();
      }

      if (srcDirFile.isDirectory()) {
         info("Building scrt-core-src.jar from src dir: " + srcDir + " into: " + outJarName);
         if (LayerUtil.buildJarFile(srcDir, null, outJarName, null,  runtimePackages, /* userClassPath */ null, null, LayerUtil.SRC_JAR_FILTER, options.verbose) != 0)
            System.err.println("*** Failed trying to jar sc runtime src files into: " + outJarName + " from buildDir: " + srcDir);
      }
      else {
         info("Copying scrt-core-src.jar from: " + srcDir + " to: " + outJarName);
         if (!FileUtil.copyFile(srcDir, outJarName, true))
            System.err.println("*** Failed to copy sc runtime files from: " + srcDir + " to: " + dir);
      }
   }

   private final static String STRATACODE_LIBRARIES_FILE = "sc.jar";

   private final static String[] SC_JAR_PATTERN = new String[] {".*jline.*\\.jar"};

   private void syncStrataCodeLibraries(String dir) {
      String fullRuntimeDir = getStrataCodeRuntimePath(false, false);
      File srcDirFile = new File(fullRuntimeDir);

      String outJarName = FileUtil.concat(dir, STRATACODE_LIBRARIES_FILE);
      if (dir != null && dir.length() > 0) {
         File f = new File(dir);
         f.mkdirs();
      }

      if (srcDirFile.isDirectory()) {
         String srcRoot = FileUtil.getParentPath(fullRuntimeDir);
         String[] srcSubDirs = new String[] {"coreRuntime", "fullRuntime", "sc"};
         File tempDir = FileUtil.createTempDir("scbuild");
         String tempDirPath = tempDir.getPath();
         for (String srcSubDir:srcSubDirs) {
            String srcDir = FileUtil.concat(srcRoot, srcSubDir);
            FileUtil.copyAllFiles(srcDir, tempDirPath, true, LayerUtil.CLASSES_JAR_FILTER);
         }
         if (options.info)
            info("Building sc.jar from src dirs: " + srcRoot + " into: " + outJarName);
         if (LayerUtil.buildJarFile(tempDir.getPath(), null, outJarName, null,  null, /* userClassPath */ null, null, LayerUtil.CLASSES_JAR_FILTER, options.verbose) != 0)
            System.err.println("*** Failed trying to jar sc runtime src files into: " + outJarName + " from buildDir: " + tempDirPath);
         else
            FileUtil.removeFileOrDirectory(tempDir);

         // This is not a typical case - we are building a project which needs the sc.jar but not from an sc install.
         // For example, we are building sc4idea from the IDE.  We are both generating sc.jar and the wrapper jar the same time.
         // To pick up the dependencies like jline which are included in sc.jar we need to at least copy the lib files here.
         String[] classPathEnts = userClassPath.split(FileUtil.PATH_SEPARATOR);
         for (String depJar:SC_JAR_PATTERN) {
            for (String classPathEnt:classPathEnts) {
               if (classPathEnt.matches(depJar)) {
                  if (options.info)
                     info("Including sc jar dependency: " + classPathEnt);
                  String fileName = FileUtil.getFileName(classPathEnt);
                  FileUtil.copyFile(classPathEnt, FileUtil.concat(dir, fileName), false);
               }
            }
         }
      }
      else {
         String srcDirPath = srcDirFile.getPath();
         info("Copying sc.jar from: " + srcDirPath + " to: " + outJarName);
         if (!FileUtil.copyFile(srcDirPath, outJarName, true))
            System.err.println("*** Failed to copy sc runtime files from: " + srcDirPath + " to: " + outJarName);
      }
   }

   public String getLayerPathNames() {
      StringBuilder sb = new StringBuilder();
      int last = layers.size()-1;
      for (int i = 0; i <= last; i++) {
         sb.append(layers.get(i).layerPathName);
         if (i != last)
            sb.append(" ");
      }
      return sb.toString();
   }

   public String getLayerNames() {
      StringBuilder sb = new StringBuilder();
      int last = layers.size()-1;
      for (int i = 0; i <= last; i++) {
         sb.append(layers.get(i).getLayerName());
         if (i != last)
            sb.append(" ");
      }
      return sb.toString();
   }

   void saveBuildInfo() {
      for (Layer l:layers)
         l.saveBuildInfo(l.processed);

      if (runtimeProcessor != null)
         runtimeProcessor.saveRuntime();
   }

   /** Called after any models are saved so we update the BuildInfo to correspond to the annotations or changes made in the models */
   public void saveModelInfo() {
      for (Layer l:layers)
         l.saveBuildInfo(false);
   }

   enum ClassLoaderMode {
      LIBS, BUILD, ALL;

      boolean doBuild() {
         return this == BUILD || this == ALL;
      }

      boolean doLibs() {
         return this == LIBS || this == ALL;
      }
   }


   public boolean isClassLoaded(String className) {
      if (buildClassLoader instanceof TrackingClassLoader)
         return ((TrackingClassLoader) buildClassLoader).isLoaded(className);
      // If there's no class loader, we have to assume the class has been loaded.
      //
      // Before calling this, you should ensure the layer which defines that class has compiledInClassPath set to true
      // to avoid false positives
      return true;
   }

   /**
    * Allows frameworks the ability to add to the system's classpath.  For example, GWT requires the core source libraries
    * in the system classpath so it uses this method to add a new directory
    */
   public void addSystemClassDir(String dir) {
      URL url = FileUtil.newFileURL(LayerUtil.appendSlashIfNecessary(dir));
      URL[] urls = new URL[1];
      urls[0] = url;

      updateBuildClassLoader(new TrackingClassLoader(null, urls, buildClassLoader, false));
   }

   public void updateBuildClassLoader(ClassLoader loader) {
      buildClassLoader = loader;
      if (getUseContextClassLoader())
         Thread.currentThread().setContextClassLoader(buildClassLoader);
      // We have just extended the class path and so need to flush out any null sentinels we may have cached for
      // these newly loaded classes.
      RTypeUtil.flushLoadedClasses();
   }

   void initSysClassLoader(Layer sysLayer, ClassLoaderMode mode) {
      int lastPos = 0;

      // We've reset the class loader so pick up everything before we start the runtime
      if (mode == ClassLoaderMode.ALL)
         lastPos = -1;
      else {
         // BuildSeparate layers are made available to the build system as they are built.  This means they can be used
         // in layer def files but that they cannot be modified by a subsequent layer due to the chicken-and-egg problem.
         //
         // the jvm tools api offers a few ways around this but requires management/debugging level access to the system.
         if (mode.doBuild()) {
            //lastPos = lastBuiltLayer == null ? -1 : lastBuiltLayer.layerPosition;
            lastPos = -1; // We always include all layers in each tracking class loader now so that we can disable the previous one to get the re-ordered class path behavior we want and Java does not want.
            if (sysLayer.buildSeparate || sysLayer == buildLayer)
               lastBuiltLayer = sysLayer;
            // In case we are inserting a layer before the last build layer
            if (lastPos >= sysLayer.layerPosition)
               lastPos = sysLayer.layerPosition - 1;
         }
         if (mode.doLibs()) {
            Layer lastLayer = sysLayer == null || sysLayer.activated ? lastStartedLayer : lastInitedInactiveLayer;
            lastPos = lastLayer == null ? -1 : lastLayer.layerPosition;
            if (sysLayer == null || sysLayer.activated)
               lastStartedLayer = sysLayer;
            else if (lastInitedInactiveLayer == null || sysLayer.layerPosition > lastInitedInactiveLayer.layerPosition)
               lastInitedInactiveLayer = sysLayer;
            // We may have inserted this layer... in that case, make sure to process it.
            if (sysLayer != null && lastPos >= sysLayer.layerPosition)
               lastPos = sysLayer.layerPosition - 1;
         }
      }

      // Used to only do this for the final build layer or build ssparate.  But if there's a final layer in there
      // we can load the classes as soon as we've finished building it
      if (mode.doLibs() || sysLayer.isBuildLayer()) {
         if (sysLayer != null) {

            // We use one class loader for both the active and inactive types.  The .class files loaded will be the same for both
            // so there's no point in registering them multiple times.  When we deactivate layers, we really can't unpeel them from
            // the class loader onion due to dependencies that might have formed.  At some point, we might need to flush the entire
            // inactive type system when we deactivate and reactivate layers.  Really the active layers should be in a separate
            // process which we communicate with to do the builds.  That way we have multiple of these build processes, one for each
            // bundle of layers.
            if (mode.doLibs() && buildClassLoader instanceof TrackingClassLoader) {
               if (((TrackingClassLoader) buildClassLoader).hasLibsForLayer(sysLayer))
                  return;
            }

            URL[] layerURLs = getLayerClassURLs(sysLayer, lastPos, mode);
            if (layerURLs != null && layerURLs.length > 0) {
               if (options.verbose) {
                  String fromLayer = lastPos == -1 || lastPos == sysLayer.layerPosition - 1 ? null : sysLayer.getLayersList().get(lastPos).getLayerName();
                  if (fromLayer != null)
                     verbose("Added to classpath for layers from: " + fromLayer + " to: " + sysLayer.getLayerName() + " runtime: " + getProcessIdent());
                  else
                     verbose("Added to classpath for layer: " + sysLayer.getLayerName() + " runtime: " + getProcessIdent());
                  for (URL url:layerURLs)
                     verbose("   " + url);
               }
               //buildClassLoader = URLClassLoader.newInstance(layerURLs, buildClassLoader);
               updateBuildClassLoader(new TrackingClassLoader(sysLayer, layerURLs, buildClassLoader, !mode.doLibs()));
            }
            if (sysLayer.activated) {
               // Since you can't unpeel the class loader onion in Java, track which layers have been loaded so we just accept
               // that we cannot rebuild or re-add them again later on.
               loadedBuildLayers.put(sysLayer.layerUniqueName, sysLayer.getBuildClassesDir());
            }
         }
         else {
            buildClassLoader = getClass().getClassLoader();
            if (getUseContextClassLoader())
               Thread.currentThread().setContextClassLoader(buildClassLoader);
         }
      }
   }

   static File initBuildFile(String name) {
      File f = new File(name);
      if (!f.exists())
         f.mkdirs();
      return f;
   }


   public static void setCurrent(LayeredSystem sys) {
      if (defaultLayeredSystem == null) // Pick the initial main layered system here by default
         defaultLayeredSystem = sys;
      DynUtil.dynamicSystem = sys;
      RDynUtil.dynamicSystem = sys;
      if (sys == null)
         currentLayeredSystem.remove();
      else
         currentLayeredSystem.set(sys.systemPtr);
   }

   public static LayeredSystem getCurrent() {
      LayerUtil.LayeredSystemPtr cur = currentLayeredSystem.get();
      if (cur == null || cur.system == null)
         return defaultLayeredSystem;
      return cur.system;
   }

   public String getSystemClass(String name) {
      if (systemClasses == null) 
         return null;
      return systemClasses.contains(name) ? "java.lang." + name : null;
   }

   public boolean findMatchingGlobalNames(Layer fromLayer, Layer refLayer, String prefix, Set<String> candidates, boolean retFullTypeName, boolean srcOnly, boolean annotTypes, int max) {
      String prefixPkg = CTypeUtil.getPackageName(prefix);
      String prefixBaseName = CTypeUtil.getClassName(prefix);

      return findMatchingGlobalNames(fromLayer, refLayer, prefix, prefixPkg, prefixBaseName, candidates, retFullTypeName, srcOnly, annotTypes, max);
   }

   boolean addMatchingCandidate(Set<String> candidates, String pkgName, String baseName, boolean retFullTypeName, int max) {
      if (retFullTypeName)
         candidates.add(CTypeUtil.prefixPath(pkgName, baseName));
      else
         candidates.add(baseName);
      return candidates.size() < max;
   }

   boolean addMatchingImportMap(Map<String,ImportDeclaration> importsByName, String prefix, Set<String> candidates, boolean retFullTypeName, int max) {
      if (importsByName != null) {
         for (Map.Entry<String,ImportDeclaration> ent:importsByName.entrySet()) {
            String baseName = ent.getKey();
            ImportDeclaration decl = ent.getValue();
            if (baseName.startsWith(prefix))
               return addMatchingCandidate(candidates, CTypeUtil.getPackageName(decl.identifier), baseName, retFullTypeName, max);
         }
      }
      return true;
   }

   /**
    * Returns the first TypeIndexEntry available for a given file path - does not initialize the type index or create layers.  Does not check active layers.  This is used
    * for determining the icons in the file menu which happens early on in the initialization of the IDE.
    */
   public TypeIndexEntry getTypeIndexEntry(String filePath) {
      acquireDynLock(false);
      try {
         if (inactiveLayers != null) {
            for (int i = 0; i < inactiveLayers.size(); i++) {
               Layer inactiveLayer = inactiveLayers.get(i);
               TypeIndexEntry ent = inactiveLayer.getTypeIndexEntryForPath(filePath);
               if (ent != null)
                  return ent;
            }
            if (peerSystems != null && !peerMode) {
               for (LayeredSystem peerSys:peerSystems) {
                  TypeIndexEntry ent = peerSys.getTypeIndexEntry(filePath);
                  if (ent != null)
                     return ent;
               }
            }
         }
         if (typeIndexProcessMap != null) {
            for (SysTypeIndex typeIndex:typeIndexProcessMap.values()) {
               TypeIndexEntry ent = typeIndex.getTypeIndexEntryForPath(filePath);
               if (ent != null)
                  return ent;
            }
         }
      }
      finally {
         releaseDynLock(false);
      }
      return null;
   }

   /** This method is used by the IDE to retrieve names for code-completion, name-lookup, etc.  */
   public boolean findMatchingGlobalNames(Layer fromLayer, Layer refLayer,
                                       String prefix, String prefixPkg, String prefixBaseName, Set<String> candidates, boolean retFullTypeName, boolean srcOnly, boolean annotTypes, int max) {
      acquireDynLock(false);
      try {
         if (prefixPkg == null || prefixPkg.equals("java.lang")) {
            if (systemClasses != null && !srcOnly) {
               for (String sysClass:systemClasses)
                  if (sysClass.startsWith(prefix)) {
                     if (!addMatchingCandidate(candidates, "java.lang", sysClass, retFullTypeName, max))
                        return false;
                  }
            }

            // For global lookups if no layers have been activated, just search all of the inactive layers.  This will
            // currently only correspond to the layers in files we've loaded so far.  Once an app has been run, we'll just
            // use the current app's layers.
            if (refLayer == null) {
               for (int i = 0; i < inactiveLayers.size(); i++) {
                  Layer inactiveLayer = inactiveLayers.get(i);
                  if (inactiveLayer.exportImports) {
                     if (!inactiveLayer.findMatchingGlobalNames(prefix, candidates, retFullTypeName, true, max))
                        return false;
                  }

                  // When we do not have any active layers, we cannot rely on the type cache for finding src file names.
                  // Instead, we go to the srcDir index in the layers.
                  if (!inactiveLayer.findMatchingSrcNames(prefix, candidates, retFullTypeName, max))
                     return false;
               }
               if (buildLayer != null) {
                  refLayer = buildLayer;
                  int startIx = fromLayer == null ? layers.size() - 1 : fromLayer.getLayerPosition();
                  for (int i = startIx; i >= 0; i--) {
                     Layer appLayer = layers.get(i);
                     if (!appLayer.findMatchingSrcNames(prefix, candidates, retFullTypeName, max))
                        return false;
                  }
               }
            }

            if (refLayer == null || !refLayer.activated || refLayer == Layer.ANY_INACTIVE_LAYER) {
               if (!typeIndex.addMatchingGlobalNames(prefix, candidates, retFullTypeName, refLayer, annotTypes, max))
                  return false;

               if (!peerMode && peerSystems != null) {
                  for (int i = 0; i < peerSystems.size(); i++) {
                     LayeredSystem peerSys = peerSystems.get(i);
                     Layer peerRefLayer = refLayer == null ? null : refLayer.layeredSystem == peerSys ? refLayer : peerSys.getPeerLayerFromRemote(refLayer);
                     if ((peerRefLayer != Layer.ANY_INACTIVE_LAYER && peerRefLayer != null) || refLayer == null) {
                        if (!peerSys.findMatchingGlobalNames(null, peerRefLayer, prefix, prefixPkg, prefixBaseName, candidates, retFullTypeName, srcOnly, annotTypes, max))
                           return false;
                     }
                  }
               }
               if (typeIndexProcessMap != null) {
                  for (Map.Entry<String,SysTypeIndex> typeIndexEntry:typeIndexProcessMap.entrySet()) {
                     SysTypeIndex idx = typeIndexEntry.getValue();
                     if (!idx.addMatchingGlobalNames(prefix, candidates, retFullTypeName, refLayer, annotTypes, max))
                        return false;
                  }
               }
            }

            if (!srcOnly) {
               if (refLayer != null && refLayer != Layer.ANY_LAYER && refLayer.inheritImports && refLayer != Layer.ANY_INACTIVE_LAYER && refLayer != Layer.ANY_OPEN_INACTIVE_LAYER) {
                  if (!refLayer.findMatchingGlobalNames(prefix, candidates, retFullTypeName, true, max))
                     return false;
               }

               // TODO - remove this part?  We definitely need the above for when are inactive but any reason to include these other names other times?
               int startIx = fromLayer == null ? layers.size() - 1 : fromLayer.getLayerPosition();
               for (int i = startIx; i >= 0; i--) {
                  Layer depLayer = layers.get(i);
                  if (depLayer.exportImports) {
                     if (!depLayer.findMatchingGlobalNames(prefix, candidates, retFullTypeName, false, max))
                        return false;
                  }
               }

               if (!addMatchingImportMap(globalImports, prefix, candidates, retFullTypeName, max))
                  return false;

               for (String prim: Type.getPrimitiveTypeNames()) {
                   if (prim.startsWith(prefix)) {
                      candidates.add(prim);
                      if (candidates.size() >= max)
                         return false;
                   }
               }
            }
         }

         if (!srcOnly) {
            for (Map.Entry<String,HashMap<String,PackageEntry>> piEnt:packageIndex.entrySet()) {
               String pkgName = piEnt.getKey();
               pkgName = pkgName == null ? null : pkgName.replace('/', '.');

               // If there's a pkg name for the prefix, and it matches the package, check the contents against
               if (pkgName == prefixPkg || prefixPkg == null || (pkgName != null && pkgName.equals(prefixPkg))) {
                  HashMap<String,PackageEntry> pkgTypes = piEnt.getValue();
                  for (Map.Entry<String,PackageEntry> pkgTypeEnt:pkgTypes.entrySet()) {
                     String typeInPkg = pkgTypeEnt.getKey();
                     if (typeInPkg.startsWith(prefixBaseName) && !typeInPkg.contains("$")) {
                        if (!addMatchingCandidate(candidates, pkgName, typeInPkg, retFullTypeName, max))
                           return false;
                        //candidates.add(CTypeUtil.prefixPath(prefixPkg, typeInPkg));
                        //candidates.add(typeInPkg);
                     }
                  }
               }

               if (pkgName == null)
                  continue;

               // Include the right part of the package name
               if (prefixPkg == null || pkgName.startsWith(prefix)) {
                  String rootPkg;
                  boolean includeFirst = StringUtil.equalStrings(prefix, prefixPkg);
                  // If the package prefix is "sc.uti" and the package name is "sc.util.zip" we need to walk up till we find pkgName = "sc.util"
                  do {
                     rootPkg = CTypeUtil.getPackageName(pkgName);
                     if (rootPkg != null && rootPkg.startsWith(prefix) && (!includeFirst || prefix.length() > rootPkg.length()))
                        pkgName = rootPkg;
                     else
                        break;
                  } while (true);

                  // We have "sc.util." and have hit the sc.util package - don't add it
                  if (includeFirst && pkgName.equals(prefix))
                     continue;

                  int len = prefix.length();
                  // If we are matching the prefixPkg we include the package name itself.  Otherwise, we skip it
                  int matchEndIx = pkgName.indexOf(".", len + (includeFirst ? 1 : 0));
                  boolean emptyBaseName = prefixBaseName.equals("");
                  String headName;
                  String tailName;
                  if (matchEndIx == -1) {
                     headName = CTypeUtil.getPackageName(pkgName);
                     tailName = CTypeUtil.getClassName(pkgName);
                  }
                  else {
                     int startIx;
                     if (includeFirst && emptyBaseName) {
                        if (prefixPkg == null) {
                           headName = "";
                           startIx = 0;
                        }
                        else {
                           headName = prefixPkg ;
                           startIx = prefixPkg.length() + 1;
                        }
                     }
                     else {
                        headName = pkgName.substring(0, len);
                        startIx = headName.lastIndexOf(".");
                        if (startIx == -1)
                           startIx = includeFirst ? len+1 : 0;
                     }
                     tailName = pkgName.substring(startIx, matchEndIx);
                  }
                  if (tailName.length() > 0 && tailName.startsWith(prefixBaseName)) {
                     if (!addMatchingCandidate(candidates, headName, tailName, retFullTypeName, max))
                        return false;
                  }
               }
            }
         }
      }
      finally {
         releaseDynLock(false);
      }
      return true;
   }

   /**
    * Adds commands that are executed just before the supplied phase.
    * The commands can include StrataCode template strings.  In that case, the current object is the LayeredSystem.
    * This gives you access to the buildInfo, buildDir, etc.  An annotation might add elements to a type group and
    * then your command can evaluate the file names for those types.
    */
   public void addPreBuildCommand(BuildPhase phase, Layer layer, String...args) {
      BuildCommandHandler bch = new BuildCommandHandler();
      bch.args = args;
      bch.definedInLayer = layer;
      addBuildCommand(phase, BuildCommandTypes.Pre, bch);
   }

   public void addPreBuildCommand(String checkTypeGroup, BuildPhase phase, Layer layer, String...args) {
      BuildCommandHandler bch = new BuildCommandHandler();
      bch.args = args;
      bch.definedInLayer = layer;
      bch.checkTypeGroup = checkTypeGroup;
      addBuildCommand(phase, BuildCommandTypes.Pre, bch);
   }

   public void addPreBuildCommand(BuildPhase phase, BuildCommandHandler handler) {
      addBuildCommand(phase, BuildCommandTypes.Pre, handler);
   }

   public void addPostBuildCommand(BuildPhase phase, BuildCommandHandler handler) {
      addBuildCommand(phase, BuildCommandTypes.Post, handler);
   }

   /**
    * Adds commands that are executed just after the supplied phase.  Args can be templates like addPreBuildCommand.
    */
   public void addPostBuildCommand(BuildPhase phase, Layer layer, String...args) {
      BuildCommandHandler bch = new BuildCommandHandler();
      bch.args = args;
      bch.definedInLayer = layer;
      addBuildCommand(phase, BuildCommandTypes.Post, bch);
   }

   /**
    * Adds commands that are executed just after the supplied phase.  Args can be templates like addPreBuildCommand.
    */
   public void addPostBuildCommand(String checkTypeGroup, Layer layer, BuildPhase phase, String...args) {
      BuildCommandHandler bch = new BuildCommandHandler();
      bch.args = args;
      bch.definedInLayer = layer;
      bch.checkTypeGroup = checkTypeGroup;
      addBuildCommand(phase, BuildCommandTypes.Post, bch);
   }

   public void addRunCommand(BuildCommandHandler handler) {
      addBuildCommand(BuildPhase.Process, BuildCommandTypes.Run, handler);
   }

   /**
    * Adds commands that are executed just after the supplied phase.  Args can be templates like addPreBuildCommand.
    */
   public void addRunCommand(String...args) {
      BuildCommandHandler bch = new BuildCommandHandler();
      bch.args = args;
      addBuildCommand(BuildPhase.Process, BuildCommandTypes.Run, bch);
   }

   public void addTestCommand(BuildCommandHandler handler) {
      addBuildCommand(BuildPhase.Process, BuildCommandTypes.Test, handler);
   }

   /**
    * Adds commands that are executed just after the supplied phase.  Args can be templates like addPreBuildCommand.
    */
   public void addTestCommand(String...args) {
      BuildCommandHandler bch = new BuildCommandHandler();
      bch.args = args;
      addBuildCommand(BuildPhase.Process, BuildCommandTypes.Test, bch);
   }

   private void addBuildCommand(BuildPhase phase, BuildCommandTypes bct, BuildCommandHandler hndlr) {
      if (hndlr.definedInLayer == null || hndlr.definedInLayer.activated) {
         EnumMap<BuildPhase,List<BuildCommandHandler>> buildCommands = null;
         switch (bct) {
            case Pre:
               buildCommands = preBuildCommands;
               break;
            case Post:
               buildCommands = postBuildCommands;
               break;
            case Run:
               buildCommands = runCommands;
               break;
            case Test:
               buildCommands = testCommands;
               break;
            default:
               throw new UnsupportedOperationException();
         }
         List<BuildCommandHandler> cmds = buildCommands.get(phase);
         if (cmds == null) {
            cmds = new ArrayList<BuildCommandHandler>();
            buildCommands.put(phase, cmds);
         }
         cmds.add(hndlr);
      }
   }

   public Object getInnerCFClass(String fullTypeName, String cfTypeName, String name) {
      //String parentClassPathName = fullTypeName.replace('.',FileUtil.FILE_SEPARATOR_CHAR);
      String parentClassPathName = cfTypeName;
      PackageEntry ent = getPackageEntry(parentClassPathName);
      if (ent == null) {
         System.err.println("*** can't find package entry for parent of inner type");
         return null;
      }
      while (ent != null && ent.src)
         ent = ent.prev;

      if (ent == null)
         return null;

      String innerTypeName = fullTypeName + "$" + name;
      Object res = otherClassCache.get(innerTypeName);
      if (res != null)
         return res;
      if (ent.zip)
         return CFClass.load(getCachedZipFile(ent.fileName), parentClassPathName + "$" + name + ".class", this);
      else
         return CFClass.load(FileUtil.concat(ent.fileName, parentClassPathName) + "$" + name + ".class", this);
   }

   public Class getCompiledClassWithPathName(String pathName) {
      Object res = compiledClassCache.get(pathName);
      if (res == NullClassSentinel.class)
         return null;
      if (res != null && res instanceof Class) {
         return (Class) res;
      }

      Class c = getCompiledClass(pathName);
      if (c != null) {
         compiledClassCache.put(pathName, c);
         if (c == NullClassSentinel.class)
            return null;
         return c;
      }

      String rootTypeName = pathName;
      int lix;
      // This is trying to handle "a.b.C.innerType" references.  Walking from the end of the type
      // name, keep peeling of name parts until we find a base type which matches.  Then go and
      // see if it defines those inner types.
      while ((lix = rootTypeName.lastIndexOf(".")) != -1) {
         String nextRoot = rootTypeName.substring(0, lix);
         String tail = pathName.substring(lix+1);
         Class rootClass = getCompiledClass(nextRoot);
         if (rootClass != null && (c = RTypeUtil.getInnerClass(rootClass, tail)) != null) {
            compiledClassCache.put(pathName, c);
            return c;
         }
         rootTypeName = nextRoot;
      }
      compiledClassCache.put(pathName, NullClassSentinel.class);
      return null;
   }

   /** Not only does this fetch the type, it guarantees the statics have been initialized. */
   public Object getRuntimeType(String fullTypeName) {
      Object td = getTypeDeclaration(fullTypeName);
      if (td instanceof Class) {
         ModelUtil.initType(td);
         return td;
      }
      else if (td instanceof ITypeDeclaration) {
         ModelUtil.ensureStarted(td, true);
         Object cl = ((ITypeDeclaration) td).getRuntimeType();
         if (cl != null) {
            ModelUtil.initType(cl);
         }
         return cl;
      }
      else
         return null;
   }

   public Class getCompiledClass(String fullTypeName) {
      Object res = compiledClassCache.get(fullTypeName);
      if (res == NullClassSentinel.class)
         return null;
      if (res instanceof Class)
         return (Class) res;
      return RTypeUtil.loadClass(getSysClassLoader(), fullTypeName, false);
   }

   public IBeanMapper getPropertyMapping(Object type, String dstPropName) {
      if (type instanceof ClientTypeDeclaration)
         type = ((ClientTypeDeclaration) type).getOriginal();
      return ModelUtil.getPropertyMapping(type, dstPropName);
   }

   /** Treats dynamic types as objects, not actual types.  Used to allow serializing types as normal objects across the wire in the dynamic runtime. */
   public boolean isSTypeObject(Object obj) {
      // Must test this case first - it extends TypeDeclaration but is used to serialize a type across the wire so is explicitly excluded
      if (obj instanceof ClientTypeDeclaration)
         return false;
      if (obj instanceof BodyTypeDeclaration && typesAreObjects)
         return false;
      return (obj instanceof ITypeDeclaration && !(obj instanceof Template)) || obj instanceof Class;
   }

   /** Part of the IDynamicModel interface - identifies the type we use to identify dynamic types */
   public boolean isTypeObject(Object obj) {
      // Must test this case first - it extends TypeDeclaration but is used to serialize a type across the wire so is explicitly excluded
      if (obj instanceof ClientTypeDeclaration)
         return false;
      return (obj instanceof ITypeDeclaration && !(obj instanceof Template)) || obj instanceof Class;
   }

   public Object getReturnType(Object method) {
      return ModelUtil.getReturnType(method, true);
   }

   public Object[] getParameterTypes(Object dynMethod) {
      return ModelUtil.getParameterTypes(dynMethod);
   }

   public boolean isAssignableFrom(Object type1, Object type2) {
      return ModelUtil.isAssignableFrom(type1, type2);
   }

   public Object getAnnotation(Object def, Class annotClass) {
      return ModelUtil.getAnnotation(def, ModelUtil.getTypeName(annotClass));
   }

   public Object getAnnotationByName(Object def, String annotName) {
      return ModelUtil.getAnnotation(def, annotName);
   }

   public Object[] getMethods(Object type, String methodName) {
      return ModelUtil.getMethods(type, methodName, null);
   }

   public Object getDeclaringClass(Object method) {
      return ModelUtil.getMethodDeclaringClass(method);
   }

   public Object invokeMethod(Object obj, Object method, Object[] argValues) {
      //return ModelUtil.invokeMethod(obj, method, argValues, new ExecutionContext(this));
      // Need to use callMethod here in order to set the current object, find the right virtual method etc.
      return ModelUtil.callMethod(obj, method, argValues);
   }

   public String getMethodName(Object method) {
      return ModelUtil.getMethodName(method);
   }

   public String getPropertyName(Object prop) {
      return ModelUtil.getPropertyName(prop);
   }

   public Object getPropertyType(Object prop) {
      return ModelUtil.getPropertyType(prop, this);
   }

   public String getMethodTypeSignature(Object method) {
      return ModelUtil.getTypeSignature(method);
   }

   public Object evalCast(Object type, Object value) {
      return ModelUtil.evalCast(type, value);
   }

   public boolean isNonCompiledType(Object obj) {
      return obj instanceof ITypeDeclaration;
   }

   public int getStaticPropertyCount(Object cl) {
      return ModelUtil.getPropertyCache(cl).staticPropertyCount;
   }

   public int getPropertyCount(Object obj) {
      Object type;
      if (obj instanceof IDynObject) {
         type = ((IDynObject) obj).getDynType();
         if (type instanceof TypeDeclaration)
            return ((TypeDeclaration) type).getPropertyCache().propertyCount;
      }
      else
         type = obj.getClass();
      if (type instanceof Class)
         return TypeUtil.getPropertyCache((Class) type).propertyCount;
      else
         throw new UnsupportedOperationException();
   }

   public IBeanMapper[] getProperties(Object typeObj) {
      return ModelUtil.getPropertyMappers(typeObj);
   }

   public IBeanMapper[] getStaticProperties(Object typeObj) {
      return ModelUtil.getStaticPropertyMappers(typeObj);
   }

   public boolean isDynamicObject(Object obj) {
      return obj instanceof IDynObject;
   }

   public boolean isInstance(Object typeObj, Object dstObj) {
      return ModelUtil.isInstance(typeObj, dstObj);
   }

   public Object getAnnotationValue(Object settings, String s) {
      return ModelUtil.getAnnotationValue(settings, s);
   }

   public Object getAnnotationValue(Object typeObj, String annotName, String valueName) {
      return ModelUtil.getAnnotationValue(typeObj, annotName, valueName);
   }

   public Object getInheritedAnnotationValue(Object typeObj, String annotName, String valueName) {
      return ModelUtil.getInheritedAnnotationValue(this, typeObj, annotName, valueName);
   }

   /** Returns the scope name for this class, inherited from base-classes.  Use this method for runtime types only, not inactive types */
   public String getInheritedScopeName(Object typeObj) {
      return ModelUtil.getInheritedScopeName(this, typeObj, null);
   }

   public void registerTypeChangeListener(ITypeChangeListener type) {
      typeChangeListeners.add(type);
   }

   public void registerCodeUpdateListener(ICodeUpdateListener type) {
      synchronized (codeUpdateListeners) {
         codeUpdateListeners.add(type);
      }
   }

   public void notifyCodeUpdateListeners() {
      ArrayList<ICodeUpdateListener> listenerList;
      synchronized (codeUpdateListeners) {
         listenerList = new ArrayList<ICodeUpdateListener>(codeUpdateListeners);
      }
      for (ICodeUpdateListener l:listenerList) {
         l.codeUpdated();
      }
   }

   public int getLayerPosition(Object type) {
      if (type instanceof ITypeDeclaration) {
         Layer l = ((ITypeDeclaration) type).getLayer();
         if (l != null)
            return l.layerPosition;
      }
      return -1;
   }

   public boolean applySyncLayer(String language, String destName, String scopeName, String codeString, boolean isReset, boolean allowCodeEval, BindingContext ctx) {
      if (language.equals("js")) {
         throw new IllegalArgumentException("javascript layers - only supported in the browser");
      }
      else if (language.equals("stratacode") ) {
         if (codeString == null || codeString.length() == 0)
            return false;

         ModelStream stream = ModelStream.convertToModelStream(codeString, ctx);

         if (stream != null) {
            boolean trace = SyncManager.trace;
            long startTime = trace ? System.currentTimeMillis() : 0;
            stream.updateRuntime(destName, "window", isReset);
            if (SyncManager.trace)
               System.out.println("Applied sync layer to system in: " + StringUtil.formatFloat((System.currentTimeMillis() - startTime)/1000.0) + " secs");
         }
      }
      return true;
   }

   @Override
   public Object newInnerInstance(Object typeObj, Object outerObj, String constrSig, Object[] params) {
      if (isComponentType(typeObj)) {
         if (ModelUtil.isDynamicType(typeObj)) {
            TypeDeclaration typeDecl = (TypeDeclaration) typeObj;

            ExecutionContext ctx = new ExecutionContext(typeDecl.getJavaModel());
            Object inst = typeDecl.constructInstance(ctx, outerObj, params, false);
            typeDecl.initDynInstance(inst, ctx, false, true, outerObj, params);
            return inst;
         }
         else {
            Class compClass = ModelUtil.getCompiledClass(typeObj);
            if (outerObj != null)
               return RTypeUtil.newInnerComponent(outerObj, ModelUtil.getCompiledClass(DynUtil.getType(outerObj)), compClass, ModelUtil.getTypeName(typeObj), params);
            else
               return RTypeUtil.newComponent(compClass, params);
         }
      }
      else
         return DynUtil.createInnerInstance(typeObj, outerObj, constrSig, params);
   }

   public boolean isComponentType(Object type) {
      return ModelUtil.isComponentType(type);
   }

   public boolean isArray(Object type) {
      if (type instanceof ArrayTypeDeclaration)
         return true;
      return PTypeUtil.isArray(type);
   }

   public Object getComponentType(Object arrayType) {
      if (arrayType instanceof ArrayTypeDeclaration) {
         return ((ArrayTypeDeclaration) arrayType).getComponentType();
      }
      return PTypeUtil.getComponentType(arrayType);
   }

   public Object getPropertyAnnotationValue(Object type, String propName, String annotName, String attName) {
      if (type instanceof ClientTypeDeclaration)
         type = ((ClientTypeDeclaration) type).getOriginal();
      Object prop = ModelUtil.getPropertyMapping(type, propName);
      if (prop != null) {
         Object annot = ModelUtil.getPropertyAnnotation(prop, annotName);
         if (annot != null) {
            return ModelUtil.getAnnotationValue(annot, attName);
         }
      }
      return null;
   }

   public void removeTypeChangeListener(ITypeChangeListener type) {
      typeChangeListeners.remove(type);
   }

   public List<ITypeChangeListener> getTypeChangeListeners() {
      return typeChangeListeners;
   }

   public void removeCodeUpdateListener(ICodeUpdateListener type) {
      synchronized (codeUpdateListeners) {
         codeUpdateListeners.remove(type);
      }
   }

   public Object getStaticProperty(Object object, String propertyName) {
      if (object instanceof Class)
         return TypeUtil.getStaticValue((Class) object, propertyName);
      TypeDeclaration type = (TypeDeclaration) object;
      return type.getStaticProperty(propertyName);
   }

   public void setStaticProperty(Object object, String propertyName, Object valueToSet) {
      // The "cmd" object in the command line inserts itself into defineType - thus it is a "static context" according to IdentifierExpression.
      if (object instanceof AbstractInterpreter)
         DynUtil.setProperty(object, propertyName, valueToSet);
      else
         DynObject.setStaticProperty((TypeDeclaration) object, propertyName, valueToSet);
   }

   public Object createInstance(Object typeObj, String constrSig, Object[] params) {
      if (typeObj instanceof TypeDeclaration) {
         if (ModelUtil.isDynamicType(typeObj) || ModelUtil.isDynamicNew(typeObj))
            return DynObject.create((TypeDeclaration) typeObj, constrSig, params);
         else
            return createInstance(ModelUtil.getCompiledClass(typeObj), constrSig, params);
      }
      // TODO: need to deal with constructor signature here
      else if (typeObj instanceof Class)
         return PTypeUtil.createInstance((Class) typeObj, constrSig, params);
      // Passed the constructor reference directly so we do not have to choose constructor by sig or param type
      else if (typeObj instanceof IMethodMapper) {
         return ((IMethodMapper) typeObj).invoke(null, params);
      }
      else if (typeObj instanceof ParamTypeDeclaration) {
         return createInstance(((ParamTypeDeclaration)typeObj).getBaseType(), constrSig, params);
      }
      else
         throw new IllegalArgumentException("Invalid type to createInstance: " + typeObj);
   }

   public Object createInnerInstance(Object typeObj, Object outerObj, String constrSig, Object[] params) {
      if (typeObj instanceof TypeDeclaration) {
         return DynObject.create((TypeDeclaration) typeObj, outerObj, constrSig, params);
      }
      else if (typeObj instanceof Class) {
         if (outerObj != null && !DynUtil.isType(outerObj)) {
            Object[] newParams = new Object[params.length + 1];
            newParams[0] = outerObj;
            System.arraycopy(params, 0, newParams, 1, params.length);
            params = newParams;
         }
         Class typeCL = (Class) typeObj;
         return PTypeUtil.createInstance((Class) typeObj, constrSig, params);
      }
      else
         throw new IllegalArgumentException("Invalid type to createInstance: " + typeObj);
   }

   public Object[] getAllMethods(Object typeObj, String modifier, boolean hasModifier) {
      return ModelUtil.getAllMethods(typeObj, modifier, hasModifier, false, false);
   }

   public Object getEnclosingType(Object memberType, boolean instOnly) {
      if (instOnly)
         return ModelUtil.getEnclosingInstType(memberType);
      else
         return ModelUtil.getEnclosingType(memberType);
   }

   @MainSettings(produceJar = true, produceScript = true, produceBAT = true, execName = "bin/scc", jarFileName="bin/sc.jar", debug = true, debugSuspend = true, maxMemory = 2048, defaultArgs = "-restartArgsFile <%= getTempDir(\"restart\", \"tmp\") %>")
   public static void main(String[] args) {
      Options options = new Options();

      options.parseOptions(args);

      PerfMon.start("main", true, true);

      LayeredSystem sys = null;
      try {
         if (options.classPath == null) {
            options.classPath = System.getProperty("java.class.path");
            if (options.classPath != null && options.sysDetails)
               System.out.println("Initial system: java.class.path: " + options.classPath);
         }
         if (options.layerPath == null) {
            options.layerPath = System.getProperty("sc.layer.path");
            if (options.layerPath != null && options.verbose)
               System.out.println("Found system property: sc.layer.path: " + options.layerPath);
         }
         if (options.sysDetails) {
            String systemHome = System.getProperty("user.dir");
            if (systemHome != null)
               System.out.println("  in working directory: " + systemHome);
         }
         /*
         if (buildLayerName == null) {
            System.err.println("Missing layer name to command: " + StringUtil.argsToString(Arrays.asList(args)));
            System.exit(-1);
         }
         */
         if (options.verbose) {
            Options.printVersion();
         }

         if (options.explicitDynLayers != null && options.explicitDynLayers.size() == 0) {
            String optName = options.recursiveDynLayers ? "dyn" : "dynone";
            Options.usage("The -" + optName + " option was provided without a list of layers.  The -" + optName + " option should be in front of the list of layer names you want to make dynamic.", args);
         }

         sys = new LayeredSystem(options.includeLayers, options.explicitDynLayers, options.layerPath, options, null, null, options.startInterpreter, null);
         if (defaultLayeredSystem == null)
            defaultLayeredSystem = sys;
         currentLayeredSystem.set(sys.systemPtr);
      }
      catch (IllegalArgumentException exc) {
         String message = exc.toString();
         if (!message.contains("Can't initialize init")) {
            System.err.println(exc.toString());
            if (sys == null || sys.options.verbose)
               exc.printStackTrace();
         }
         System.exit(-1);
      }

      if (options.sysDetails) {
         GenerateContext.debugError = true; // This slows things down!
         System.out.println("Classpath: " + options.classPath);
      }
      Bind.info = options.info;

      sys.restartArgs = options.restartArgs;

      // First we need to fully build any buildSeparate layers for the main system.  Before we added preBuild we could rely on
      // build to do that ahead of time but now that's not the case.  At least it's symmetric that we make the same 3 passes
      // over the layered systems.
      if (!sys.buildSystem(options.includeFiles, false, true)) {
         System.exit(-1);
      }

      // And the buildSeparate pass for each extra runtime, e.g. JS
      if (sys.peerSystems != null) {
         for (LayeredSystem peer:sys.peerSystems) {
            if (!peer.buildSystem(options.includeFiles, false, true)) {
               System.exit(-1);
            }
         }
      }

      // Next we have to pre-build all of the systems.  That will find all changed models, and start, validate, and process them.
      //if (!sys.preBuildSystem(includeFiles, false, false)) {
      //   System.exit(-1);
      //}

      // Prebuild for the peer systems.  Once we've prebuilt everything, we can start the transformation.  Right now transformation is destructive and
      // since runtimes can refer to each other (for synchronization) we need all of that to happen cleanly before the transform.
      //if (sys.peerSystems != null) {
      //   for (LayeredSystem peer:sys.peerSystems) {
      //      if (!peer.preBuildSystem(includeFiles, false, false)) {
      //         System.exit(-1);
      //      }
      //   }
      //}

      boolean success;
      do {
         success = sys.buildSystem(options.includeFiles, false, false);
         if (success) {
            if (sys.peerSystems != null) {
               for (LayeredSystem peer:sys.peerSystems) {
                  success = peer.buildSystem(options.includeFiles, false, false);
                  if (peer.anyErrors || !success)
                     sys.anyErrors = true;
               }
            }
         }
         sys.buildCompleted(true);

         if (!success) {
            if (!sys.promptUserRetry())
               System.exit(1);
            else {
               sys.refreshSystem(true, true);
               sys.resetBuild(true);
            }
         }
      } while (!success);

      setCurrent(sys);

      // Until this point, we have not started any threads or run anything else on the layered system but now we are about to
      // start the command interpreter so grab the global lock for write.   Before it prints it's prompt and starts looking
      // for commands, it will grab the global lock for read, just to do the prompt.   That ensures all of our main processing
      // is finished before we start running commands, and that the prompt is the last thing we output so it's visible.
      sys.acquireDynLock(false);

      if (sys.cmd != null)
         sys.cmd.buildComplete();

      if (options.needsClassLoaderReset) {
         // There are situations where we need to reset the class loader - i.e. we cannot use the layered class loaders
         // for a runtime application.   The problem is that some layers included Java classes at runtime look at their
         // own class loader to load other application classes that would be later in the classpath.  The compile time wants
         // to add jars as soon as possible and does it with layered classpaths.  That's fine for compiling but when, For example,
         // tomcat's connection pool implementation will load a jdbc driver that is in a subsequent layer's classpath.
         // TODO should we have some way for layers to turn this on or off?  Most SC frameworks do not do this injected class
         // dependency thing and work fine without the rest.  It means we have to reload all classes.
         sys.resetClassLoader();
         if (sys.getUseContextClassLoader())
            Thread.currentThread().setContextClassLoader(sys.getSysClassLoader());
      }

      // This will do any post-build processing such as generating static HTML files.  Only do it for the main runtime... otherwise, we'll do the .html files twice.
      // This has to be done after the peerSystems are built so we have enough information to check the other runtime for the list of JS files the app depends on.
      sys.initPostBuildModels();

      PerfMon.start("runMain");
      try {
         Thread commandThread = null;
         if (options.startInterpreter) {
            if (options.testMode || options.scriptMode) {
               // Change the default for testing
               sys.cmd.edit = false;
            }
            if (options.testScriptName != null && options.testMode && !sys.peerMode) {
               String pathName = options.testScriptName;
               SrcEntry layerFile = sys.buildLayer.getLayerFileFromRelName(pathName, true, true);
               System.out.println("Running test script: " + pathName + " from: " + sys.buildDir + " script layer: " + (layerFile == null ? "null" : layerFile.layer));
               Layer includeLayer = layerFile == null ? null : layerFile.layer;
               if (options.includeTestScript)
                  sys.cmd.pushIncludeScript(sys.buildDir, pathName, includeLayer);
               else
                  sys.cmd.loadScript(sys.buildDir, pathName, includeLayer);

               // We need to add the temp layer so the testScriptName is not evaluated in a compiled layer - it has to be dynamic
               options.editLayer = false;
            }

            // If we are adding a temporary layer, we can put it at the specified path.  If we are editing the
            // last layer, we can't switch the directory.  In this case, it is treated as relative to the layer's
            // directory.
            if (!options.editLayer && sys.cmd.edit) {
               sys.addTemporaryLayer(options.commandDirectory, false);
               options.commandDirectory = null;

               // Update the current layer to the temp layer so it's the default for scripts - we may need that to be a dynamic layer if we are doing test things in there
               sys.cmd.updateLayerState();
            }

            if (options.commandDirectory != null)
               sys.cmd.path = options.commandDirectory;

            // Share the editor context with the gui if one is installed
            sys.editorContext = sys.cmd;

            if (sys.options.recordFile != null)
               sys.cmd.record(sys.options.recordFile);

            if (sys.options.createNewLayer)
               sys.cmd.createLayer();
            else if (sys.layers.size() == 0) {
               sys.cmd.askCreateLayer();
            }
            // If we have stuff to do on this thread, we can't use it for the command interpreter so we spawn a new one.
            // Since we have the dyn lock, the prompt() method in the cmd object will block there until we're finished.
            if (options.runClass != null || options.testPattern != null) {
               commandThread = new Thread(sys.cmd);
               commandThread.setName("StrataCode command interpreter");
               commandThread.start();
            }
            else
               sys.cmd.readParseLoop();
         }

         boolean haveReset = false;

         if (options.testPattern != null) {
            // First run the unit tests which match (i.e. those installed with the @Test annotation)
            if (sys.buildInfo == null)
               sys.error("No build - unable to run tests with pattern: " + options.testPattern);
            else
               sys.buildInfo.runMatchingTests(options.testPattern);
         }

         if (options.verbose && !options.testVerifyMode) {
            sys.verbose("Prepared runtime: " + StringUtil.formatFloat((System.currentTimeMillis() - sys.sysStartTime)/1000.0));
         }

         if (options.runClass != null) {
            String runFromDir = sys.getRunFromDir();
            if (runFromDir != null) {
               System.setProperty("user.dir", runFromDir);
            }

            if (commandThread != null && sys.getUseContextClassLoader())
               commandThread.setContextClassLoader(sys.getSysClassLoader());

            // first initialize and start all initOnStartup and createOnStartup objects
            // Note: this should be done via code generation in the main methods
            //sys.initStartupTypes();
            // Run any main methods that are defined (e.g. start the server)
            sys.runMainMethods(-1);
            // Run any scripts that are added to run (e.g. open the web browser
            sys.runRunCommands();
         }

         if (options.verbose && !options.testVerifyMode) {
            sys.verbose("Run completed in: " + StringUtil.formatFloat((System.currentTimeMillis() - sys.sysStartTime)/1000.0) + " at: " + new Date().toString());
         }

      }
      catch (Throwable exc) {
         sys.anyErrors = true;
         System.err.println("*** Uncaught error in main thread: " + exc);
         exc.printStackTrace();
         System.exit(-1);
      }
      finally {
         sys.releaseDynLock(false);
         PerfMon.end("runMain");
      }

      try {
         // Run any test commands after starting servers and things like that
         if (options.testPattern != null) {
            // Then run any commands installed by any layers
            sys.runTestCommands();
         }
      }
      catch (Throwable exc) {
         sys.anyErrors = true;
         System.err.println("*** Uncaught error running test commands: " + exc);
         exc.printStackTrace();
         System.exit(-1);
      }

      // If the interpreter is started, it's responsible for the cleanup when it exits - which will be after this guy because it can't start till it gets the lock.
      if (!options.startInterpreter) {
         sys.performExitCleanup();
      }

      PerfMon.end("main");
      if (PerfMon.enabled) {
         PerfMon.dump();
      }
      if (Parser.ENABLE_STATS) {
         System.out.println(Parser.getStatInfo(JavaLanguage.INSTANCE.compilationUnit));
         System.out.println("Stats for StrataCode:");
         System.out.println(Parser.getStatInfo(SCLanguage.INSTANCE.compilationUnit));
         System.out.println("Stats for TemplateLanguage:");
         System.out.println(Parser.getStatInfo(TemplateLanguage.INSTANCE.getStartParselet()));
      }
      if (options.testExit) {
         // When there's a script, the EOF of the script will trigger the exit
         if (options.testMode && options.testScriptName == null)
            System.exit(sys.anyErrors ? -1 : 0);
      }
   }

   public boolean syncInited = false;

   private boolean promptUserRetry() {
      if (cmd == null || !options.retryAfterFailedBuild)
         return false;

      do {
         System.err.flush();
         anyErrors = true;
         String str = cmd.readLine((options.buildAllFiles ? "Full" : "Incremental") + " build failed - retry? [y, n, i=incremental,f=full]: ");
         if (str == null)
            return false;

         str = str.trim().toLowerCase();
         if (str.length() > 0) {
            switch (str.charAt(0)) {
               case 'f':
                  options.buildAllFiles = true;
                  return true;
               case 'i':
                  options.buildAllFiles = false;
               case 'y':
                  return true;
               case 'n':
                  return false;
               // Any script commands that come in here that are not valid should cause us to exit with an error
               default:
                  // TODO: prompt the user for another command?
                  anyErrors = true;
                  break;
            }
         }

      } while (true);
   }

   public boolean commandLineEnabled() {
      return cmd != null;
   }

   /**
    * For each addSyncType calls in the initSync method, we need to pre-register the type names which will be
    * synchronized automatically.  This is for the syncTypeFilter - so we can identify which types are
    * synchronized during code-processing - long before initSync is called which requires the sync manager to
    * be initialized.
    */
   public static HashSet<String> globalSyncTypeNames = new HashSet<String>(Arrays.asList("sc.layer.LayeredSystem",
           "sc.layer.Options", "sc.lang.sc.ModifyDeclaration", "sc.lang.java.EnumDeclaration",
           "sc.lang.java.InterfaceDeclaration", "sc.lang.java.AnnotationTypeDeclaration", "sc.lang.java.EnumConstant",
           "sc.lang.java.ClassDeclaration", "sc.lang.java.ClientTypeDeclaration", "sc.lang.java.VariableDefinition",
           "sc.lang.sc.PropertyAssignment", "sc.lang.java.JavaModel", "sc.lang.sc.SCModel",  "sc.lang.template.Template",
           "sc.layer.SrcEntry", "sc.lang.java.ParamTypedMember", "sc.lang.java.ParamTypeDeclaration",
           "java.lang.reflect.Field", "sc.lang.reflect.Method", "sc.lang.java.MethodDefinition",
           "sc.type.BeanMapper", "sc.type.BeanIndexMapper", "sc.layer.Layer", "sc.lang.java.Parameter",
           // From EditorContext (JLineInterpreter is replaced with EditorContext on the client so is implicitly sync'd)
           "sc.lang.JLineInterpreter", "sc.lang.EditorContext", "sc.lang.MemoryEditSession", "sc.sync.ClassSyncWrapper"));

   // Called by any clients who need to use the LayeredSystem as a sync object.  Because this class is not compiled by StrataCode, we define the sync mappings
   // explicitly through the apis.
   public void initSync() {
      // We don't pick these types up reliably because the compiled types are used for incremental compiles so just add them all to the global filter whenever the sync system is initialized.
      if (SyncManager.globalSyncTypeNames == null)
         SyncManager.globalSyncTypeNames = new HashSet<String>();
      SyncManager.globalSyncTypeNames.addAll(globalSyncTypeNames);

      syncInited = true;
      SyncManager.SyncState old = SyncManager.getOldSyncState();
      try {
         SyncManager.setInitialSync("jsHttp", null, 0, true);
         // This part was originally copied from the code generated from the js version of LayeredSystem.  Yes, we could add the meta-data here and introduce a dependency so SC is required to build SC but still not willing to take that leap of tooling complexity yet :)
         // See also the lines in the class Options inner class.
         int globalScopeId = GlobalScopeDefinition.getGlobalScopeDefinition().scopeId;
         SyncManager.addSyncType(LayeredSystem.class, new SyncProperties(null, null, new Object[] { "options" , "staleCompiledModel"}, null, SyncPropOptions.SYNC_INIT, globalScopeId));
         SyncManager.addSyncType(Options.class, new SyncProperties(null, null,
                  new Object[] { "buildAllFiles" , "buildAllLayers" , "noCompile" , "verbose" , "info" , "debug" , "crossCompile" , "runFromBuildDir" , "runScript" ,
                                 "createNewLayer" , "dynamicLayers" , "allDynamic" , "liveDynamicTypes" , "useCommonBuildDir" , "buildDir" , "buildSrcDir" ,
                                  "recordFile" , "restartArgsFile" , "compileOnly" }, null, SyncPropOptions.SYNC_INIT, globalScopeId));
         SyncProperties typeProps = new SyncProperties(null, null,
                   new Object[] { "typeName" , "fullTypeName", "layer", "packageName" , "dynamicType" , "isLayerType" ,
                                  "declaredProperties", "declarationType", "comment" , "existsInJSRuntime", "annotations", "modifierFlags", "extendsTypeName",
                                  "constructorParamNames", "editorCreateMethod"},
                                            null, SyncPropOptions.SYNC_INIT, globalScopeId);

         SyncManager.addSyncType(ModifyDeclaration.class, typeProps);
         SyncManager.addSyncHandler(ModifyDeclaration.class, LayerSyncHandler.class);

         SyncManager.addSyncType(EnumDeclaration.class, typeProps);
         SyncManager.addSyncHandler(EnumDeclaration.class, LayerSyncHandler.class);

         SyncManager.addSyncType(InterfaceDeclaration.class, typeProps);
         SyncManager.addSyncHandler(InterfaceDeclaration.class, LayerSyncHandler.class);

         SyncManager.addSyncType(AnnotationTypeDeclaration.class, typeProps);
         SyncManager.addSyncHandler(AnnotationTypeDeclaration.class, LayerSyncHandler.class);

         SyncManager.addSyncType(EnumConstant.class, typeProps);
         SyncManager.addSyncHandler(EnumConstant.class, LayerSyncHandler.class);

         SyncManager.addSyncType(ClassDeclaration.class, typeProps);
         SyncManager.addSyncHandler(ClassDeclaration.class, LayerSyncHandler.class);

         SyncManager.addSyncType(ClientTypeDeclaration.class, typeProps);
         SyncManager.addSyncHandler(ClientTypeDeclaration.class, LayerSyncHandler.class); // Need this so we can use restore to go back

         SyncManager.addSyncType(VariableDefinition.class, new SyncProperties(null, null, new Object[] { "variableName" , "initializerExprStr" , "operatorStr" , "layer", "comment", "variableTypeName", "indexedProperty", "annotations", "modifierFlags"},
                                 null, SyncPropOptions.SYNC_INIT| SyncPropOptions.SYNC_CONSTANT, globalScopeId));
         SyncManager.addSyncType(PropertyAssignment.class,
              new SyncProperties(null, null,
                                 new Object[] { "propertyName" , "operatorStr", "initializerExprStr", "layer" , "comment", "variableTypeName", "annotations", "modifierFlags" },
                                 null, SyncPropOptions.SYNC_INIT | SyncPropOptions.SYNC_CONSTANT, globalScopeId));

         SyncManager.addSyncType(MethodDefinition.class,
                 new SyncProperties(null, null,
                         new Object[] { "name" , "parameterList", "comment", "methodTypeName", "returnTypeName", "annotations", "modifierFlags" },
                         null, SyncPropOptions.SYNC_INIT | SyncPropOptions.SYNC_CONSTANT, globalScopeId));
         SyncManager.addSyncHandler(MethodDefinition.class, LayerSyncHandler.class);

         SyncManager.addSyncType(ConstructorDefinition.class,
                 new SyncProperties(null, null,
                         new Object[] { "name" , "parameterList", "comment", "methodTypeName", "annotations", "modifierFlags" },
                         null, SyncPropOptions.SYNC_INIT | SyncPropOptions.SYNC_CONSTANT, globalScopeId));

         SyncManager.addSyncType(Parameter.class,
                 new SyncProperties(null, null,
                         new Object[] { "variableName" , "parameterTypeName" },
                         null, SyncPropOptions.SYNC_INIT | SyncPropOptions.SYNC_CONSTANT, globalScopeId));

         SyncProperties modelProps = new SyncProperties(null, null, new Object[] {"layer", "srcFile", "needsModelText", "cachedModelText", "needsGeneratedText", "cachedGeneratedText", "cachedGeneratedJSText", "cachedGeneratedSCText", "cachedGeneratedClientJavaText", "existsInJSRuntime", "layerTypeDeclaration"},
                                             null, SyncPropOptions.SYNC_INIT, globalScopeId);
         SyncManager.addSyncType(JavaModel.class, modelProps);
         SyncManager.addSyncType(SCModel.class, modelProps);
         SyncManager.addSyncType(Template.class, modelProps);
         SyncManager.addSyncType(SrcEntry.class, new SyncProperties(null, null, new Object[] { "layer", "absFileName", "relFileName", "baseFileName", "prependPackage" },
                                 null, SyncPropOptions.SYNC_INIT | SyncPropOptions.SYNC_CONSTANT, globalScopeId));
         SyncManager.addSyncInst(options, true, true, null, null);
         SyncManager.addSyncInst(this, true, true, null, null);

         SyncManager.addSyncHandler(ParamTypedMember.class, LayerSyncHandler.class);
         SyncManager.addSyncHandler(ParamTypeDeclaration.class, LayerSyncHandler.class);
         SyncManager.addSyncHandler(Field.class, LayerSyncHandler.class);
         SyncManager.addSyncHandler(Method.class, LayerSyncHandler.class);
         SyncManager.addSyncHandler(BeanMapper.class, LayerSyncHandler.class);
         SyncManager.addSyncHandler(BeanIndexMapper.class, LayerSyncHandler.class);
         SyncManager.addSyncHandler(Template.class, LayerSyncHandler.class);
         SyncManager.addSyncHandler(Layer.class, LayerSyncHandler.class);

         SyncManager.addSyncType(Layer.class,
                 new SyncProperties(null, null,
                         new Object[]{"packagePrefix", "defaultModifier", "dynamic", "hidden", "compiledOnly", "transparent",
                                 "baseLayerNames", "layerName", "layerPathName", "layerBaseName", "layerDirName", "layerUniqueName",
                                 "layerPosition", "codeType", "dependentLayers"},
                         null, SyncPropOptions.SYNC_INIT,  globalScopeId));
         for (Layer l:layers)
            l.initSync();

         if (allLayerIndex != null) {
            initLayerIndexSync();
         }

         if (editorContext != null)
            editorContext.initSync();
      }
      finally {
         SyncManager.setInitialSync("jsHttp", null, 0, false);
         SyncManager.restoreOldSyncState(old);
      }
   }

   private void initLayerIndexSync() {
      for (LayerIndexInfo lii:allLayerIndex.values()) {
         lii.layer.initSync();
      }
   }

   private void runMainMethods(List<Layer> layers) {
      if (options.runScript) {
         if (buildInfo != null)
            buildInfo.runMatchingScripts(options.runClass, options.runClassArgs, layers);
      }
      else {
         if (buildInfo != null)
            buildInfo.runMatchingMainMethods(options.runClass, options.runClassArgs, layers);
      }
   }

   private void runMainMethods(int lowestLayer) {
      if (options.runScript) {
         if (buildInfo != null)
            buildInfo.runMatchingScripts(options.runClass, options.runClassArgs, lowestLayer);
      }
      else {
         Object rc = lowestLayer == 0 ? getClass(options.runClass, true) : null;
         if (rc instanceof CFClass)
            rc = ((CFClass)rc).getCompiledClass();

         if (rc == null) {
            if (buildInfo != null)
               buildInfo.runMatchingMainMethods(options.runClass, options.runClassArgs, lowestLayer);
         }
         else {
            runMainMethod(options.runClass, options.runClassArgs, lowestLayer);
         }
      }
   }

   private static String escapeCommandLineArgument(String arg) {
      if (arg.contains(" "))
         return '"' + arg + '"';
      return arg;
   }

   private String getDependentLayerNames(Layer layer) {
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (Layer dep:layers) {
         if (layer.extendsLayer(dep)) {
            if (!first)
               sb.append(", ");
            sb.append(dep.toString());
            first = false;
         }
      }
      return sb.toString();
   }

   /*
   GenerateCodeStatus preBuildLayer(Layer layer, List<String> includeFiles, boolean changedOnly, boolean separateLayersOnly) {
      currentBuildLayer = layer;
      boolean skipBuild = separateLayersOnly && !layer.buildSeparate;
      if (options.info) {
         String runtimeName = getRuntimeName();
         if (!skipBuild) {
            if (!layer.buildSeparate) {
               if (layer == buildLayer)
                  System.out.println("Starting the " + runtimeName + " runtime with buildDir: " + buildDir + " " + StringUtil.insertLinebreaks(LayerUtil.layersToNewlineString(layers), 120));
               else
                  System.out.println("Starting layer " + layer + " for the " + runtimeName + " runtime with buildDir: " + layer.buildDir + " using: " + StringUtil.insertLinebreaks(getDependentLayerNames(layer), 70));
            }
            else
               System.out.println("Starting the " + runtimeName +" runtime " + (layer.dynamic ? "dynamic " : "") + "layer: " + layer.layerUniqueName + " using " + LayerUtil.layersToString(layers.subList(0, layer.layerPosition)) + " with buildDir: " + layer.buildDir);
            if (includeFiles != null)
               System.out.println("   files: " + includeFiles);
         }
         else if (options.verbose) {
            System.out.println("Initializing the " + runtimeName + " runtime with buildDir: " + buildDir);
         }
      }
      GenerateCodeStatus masterResult = GenerateCodeStatus.NoFilesToCompile;
      for (BuildPhase phase:BuildPhase.values()) {
         GenerateCodeStatus result = GenerateCodeStatus.NoFilesToCompile;

         if ((!changedOnly || !layer.compiled) && (result = initChangedModels(layer, includeFiles, phase, separateLayersOnly)) != GenerateCodeStatus.Error) {
            if (phase == BuildPhase.Process && !skipBuild) {
               if (result == GenerateCodeStatus.ChangedCompiledFiles) {
                  setStaleCompiledModel(true, "Regeneration of compiled files that are in use by the system");
                  masterResult = result;
               }
               else if (masterResult == GenerateCodeStatus.NoFilesToCompile)
                  masterResult = result;

            }
         }
         else {
            masterResult = result;
            break;
         }
      }
      currentBuildLayer = null;
      return masterResult;

   }
   */

   GenerateCodeStatus generateAndCompileLayer(Layer layer, List<String> includeFiles, boolean newLayersOnly, boolean separateLayersOnly) {
      if (layer.disabled)
         return GenerateCodeStatus.NoFilesToCompile;
      currentBuildLayer = layer;
      boolean skipBuild = separateLayersOnly && !layer.buildSeparate;
      if (options.info) {
         String runtimeName = getProcessIdent();
         if (!peerMode && peerSystems != null)
            runtimeName = runtimeName + "(main)";

         if (!skipBuild && !systemCompiled) {
            if (!layer.buildSeparate) {
               if (!layer.compiled) {
                  if (layer == buildLayer)
                     info("Compiling build layer " + layer + " " + runtimeName + " runtime into: " + buildDir);
                  else
                     info("\nCompiling intermediate layer " + layer + " " + runtimeName + " runtime into: " + layer.buildDir + " using: " + StringUtil.insertLinebreaks(getDependentLayerNames(layer), 70));
               }
               else {
                  info("Rebuilding " + runtimeName + " runtime " + layer.layerUniqueName);
               }
            }
            else {
               if (!layer.compiled)
                  info("\nCompiling " + runtimeName + " runtime " + (layer.dynamic ? "dynamic " : "") + "separate layer: " + layer.layerUniqueName + (options.verbose || options.verboseLayers ? " using " + StringUtil.insertLinebreaks(LayerUtil.layersToNewlineString(layers.subList(0, layer.layerPosition)), 120) : "") + " into: " + layer.buildDir);
               else
                  info("Rebuilding " + runtimeName + " runtime " + (layer.dynamic ? "dynamic " : "") + "separate layer: " + layer.layerUniqueName);

            }
            if (includeFiles != null)
               System.out.println("   files: " + includeFiles);
         }
         if (skipBuild && (options.info || options.verbose) && !systemCompiled) {
            String op = options.buildAllFiles ? "Build all - " : (layer.buildAllFiles ? "Build all in layer - " : "Incremental build - ");
            if (layer == buildLayer)
               info(op + runtimeName + " classes into: " + layer.buildDir + " src into: " + layer.buildSrcDir + " " + (options.verbose || options.verboseLayers ? StringUtil.insertLinebreaks(LayerUtil.layersToNewlineString(layers), 120) : layers.size() + " layers"));
            else
               info(op + "pre build " + layer + " " + runtimeName + " classes into: " + layer.buildDir + " src into: " + layer.buildSrcDir + " " + (options.verbose || options.verboseLayers ? StringUtil.insertLinebreaks(getDependentLayerNames(layer), 70) : layers.size() + " layers"));
         }
      }
      GenerateCodeStatus masterResult = GenerateCodeStatus.NoFilesToCompile;
      for (BuildPhase phase:BuildPhase.values()) {
         GenerateCodeStatus result = GenerateCodeStatus.NoFilesToCompile;

         if (getCurrent() != this)
            System.out.println("*** Error mismatching layered system");

         if ((!newLayersOnly || !layer.compiled) && (!separateLayersOnly || layer.buildSeparate) &&
             (result = generateCode(layer, includeFiles, phase, separateLayersOnly)) != GenerateCodeStatus.Error) {
            if (phase == BuildPhase.Process && !skipBuild) {
               layer.compiled = true;
               // Mark all previous layers as compiled when we successfully compile the build layer
               for (int i = 0; i < layer.layerPosition; i++) {
                  Layer current = layers.get(i);

                  // Once we've compiled a layer that extends a previous layer, we'll mark that guy as compiled, meaning
                  // all of the generated source and class files should be up to date in the compiled layer.  If a subsequent
                  // layer modifies those types, it will stored overridden versions in its layer.
                  //
                  // Separate layers do not include the files defined in the extended layers so don't mark them as
                  // compiled.
                  if ((layer == buildLayer /* || layer == lastCompiledLayer */) || (!layer.buildSeparate && layer.extendsLayer(current))) {
                     current.compiled = true;
                  }
               }
               if (result == GenerateCodeStatus.ChangedCompiledFiles) {
                  internalSetStaleCompiledModel(true, "Regeneration of compiled files that are in use by the system");
                  masterResult = result;
               }
               else if (masterResult == GenerateCodeStatus.NoFilesToCompile)
                  masterResult = result;

            }
         }
         else {
            masterResult = result;
            break;
         }
      }
      return masterResult;
   }

   /** Builds the system.  The changedOnly option looks for changed files only.  separateLayersOnly will only build layers with the buildSeparate=true option.  You can specify a list of files to build if you know exactly what needs to be processed and transformed to rebuild the system (not recommended). */
   /*
   boolean preBuildSystem(List<String> includeFiles, boolean changedOnly, boolean separateLayersOnly) {
      setCurrent(this);
      // Reset the time just before we make a pass through all of the layers so we pick up any subsequent changes
      lastRefreshTime = System.currentTimeMillis();
      buildStartTime = lastRefreshTime;

      if (buildLayer == null) {
         if (options.verbose)
            System.out.println("No compiled layers");
      }
      else {
         // First go through all layers ahead of the build layer and compile any which have the buildSeparate
         // flag set to true.  We'll pick up the compiled version of this layer from its build directory.
         for (int i = 0; i < layers.size(); i++) {
            Layer l = layers.get(i);

            // First time, initialize the layers to their classes dir
            if (l.buildClassesDir == null) {
               l.buildClassesDir = buildClassesDir;
               l.sysBuildSrcDir = buildSrcDir;
            }

            if (l.origBuildLayer == null)
               l.origBuildLayer = buildLayer;

            if (l == buildLayer)
               break;

            // Should we inherit from build directories we find along the way?  If so, we should make sure their build directories are up to date and build them.
            if (l.isBuildLayer() && !l.compiled) {
               if (preBuildLayer(l, includeFiles, changedOnly, separateLayersOnly) == GenerateCodeStatus.Error) {
                  return false;
               }
            }
         }

         GenerateCodeStatus stat;
         if (preBuildLayer(buildLayer, includeFiles, changedOnly, separateLayersOnly) != GenerateCodeStatus.Error) {
         }
         else {
            if (options.verbose)
               System.out.println("Failed build took: " + StringUtil.formatFloat((System.currentTimeMillis() - buildStartTime)/1000.0));
            return false;
         }
      }
      return true;
   }
   */

   boolean buildLayersIfNecessary(int ix, List<String> includeFiles, boolean newLayersOnly, boolean separateLayersOnly) {
      // First go through all layers ahead of the build layer and compile any which have the buildSeparate
      // flag set to true.  We'll pick up the compiled version of this layer from its build directory.
      for (int i = 0; i < ix; i++) {
         Layer l = layers.get(i);

         // You might think if we see a compiled layer in here we can just return but some layers mark thesmelves as compiled
         // at start time - e.g. sys.sccore because they store no files that need compiling
         if (l.compiled)
            continue;

         // First time, initialize the layers to their classes dir
         if (l.buildClassesDir == null) {
            l.buildClassesDir = buildClassesDir;
            l.sysBuildSrcDir = buildSrcDir;
         }

         if (l.origBuildLayer == null)
            l.origBuildLayer = buildLayer;

         if (l == buildLayer)
            break;

         // Should we inherit from build directories we find along the way?  If so, we should make sure their build directories are up to date and build them.
         if (l.isBuildLayer() && (!separateLayersOnly || l.buildSeparate)) {
            if (generateAndCompileLayer(l, includeFiles, newLayersOnly, separateLayersOnly) == GenerateCodeStatus.Error) {
               return false;
            }

            if (!separateLayersOnly || l.buildSeparate) {

              initSysClassLoader(l, ClassLoaderMode.BUILD);

               /*
               * This is done to free up resources.  TODO: Maybe it's not necessary anymore?
               *
               * Must be done after we have set the sys class loader because of the refreshBoundType process.  That
               * used to be necessary to replace transformed models with their runtime types.
               */
               flushTypeCache();
            }
         }
      }
      return true;
   }

   /**
    * Builds the system.  The newLayersOnly option looks for changed files only.  separateLayersOnly will only build layers with the buildSeparate=true option.  That mode is typically run first - to build all
    * separate layers, so their compiled results can be used for building the normal layers.
    * You can specify the list of includeFiles to build a subset of files if you know exactly what needs to be processed and transformed but that mode is really only used for testing purposes and may not
    * generate an accurate build.
    */
   boolean buildSystem(List<String> includeFiles, boolean newLayersOnly, boolean separateLayersOnly) {
      setCurrent(this);

      if (buildStartTime == -1) {
         // Reset the time just before we make a pass through all of the layers so we pick up any subsequent changes
         lastRefreshTime = System.currentTimeMillis();
         buildStartTime = lastRefreshTime;
      }
      // Reset this before the build because we will refresh all files.  Any changes after this time, should trigger a refresh.
      if (lastChangedModelTime == -1)
         lastChangedModelTime = buildStartTime;

      try {
         buildingSystem = true;
         if (buildLayer == null) {
            if (options.verbose)
               System.out.println("No compiled layers");
         }
         else {
            if (!buildLayersIfNecessary(layers.size(), includeFiles, newLayersOnly, separateLayersOnly))
               return false;

            if (anyErrors)
               return false;

            if (getCurrent() != this)
               System.out.println("*** Error mismatching layered systems");

            GenerateCodeStatus stat;
            boolean firstCompile = !buildLayer.compiled;
            if (generateAndCompileLayer(buildLayer, includeFiles, newLayersOnly, separateLayersOnly) != GenerateCodeStatus.Error) {
               if (!separateLayersOnly || buildLayer.buildSeparate) {
                  if (firstCompile) {
                     /* Once we are done generating and compiling, add the buildDir to the class path */
                     initSysClassLoader(buildLayer, ClassLoaderMode.BUILD);
                  }

                  /*
                  * This is necessary both to free up resources and also because the transformed models no longer
                  * obey all of the StrataCode contracts.  For example, we transform bindings to method calls and can't detect
                  * cycles on the transformed model.  We do lose the ability to cache models across the compile to
                  * runtime phases which is a loss... maybe the transformed models should try to preserve all of the
                  * behavior of the original?  It would just require cloning some nodes at key spots such as the
                  * data binding initializer and likely other places.
                  *
                  * Must be done after we have set the sys class loader because of the refreshBoundType process.  That
                  * requires replacing transformed models with their runtime types.
                  */
                  flushTypeCache();

                  /* And init the build dir index */
                  initBuildDirIndex();

                  // These object references also need to get reloaded into the new class loader scheme
                  buildInfo.reload();

                  if (options.verbose && !options.testVerifyMode)
                     System.out.println("Build of the " + getRuntimeName() + " runtime completed in: " + StringUtil.formatFloat((System.currentTimeMillis() - buildStartTime)/1000.0));
                  if (options.verbose && Parser.ENABLE_STATS) {
                     System.out.println("Parser stats: tested: " + Parser.testedNodes + " matched: " + Parser.matchedNodes);
                     System.out.println("Generated: " + GenerateContext.generateCount + " false starts: " + GenerateContext.generateError);
                     System.out.println("Num classes cached: " + PTypeUtil.numClassesCached + " numFields: " + PTypeUtil.numFieldsCached + " numMethods: " + PTypeUtil.numMethodsCached + " numInterfacesPropsCached: " + PTypeUtil.numInterfacePropsCached + " numPropsInherited: " + PTypeUtil.numPropsInherited);
                  }

                  buildStartTime = -1;
               }
            }
            else {
               if (options.verbose) {
                  // Dump errors again because otherwise they are mixed into the verbose messages and harder to find
                  if (viewedErrors.size() > 0) {
                     System.err.println("Summary of errors compiling " + getRuntimeName() + " runtime");
                     System.err.println(LayerUtil.errorsToString(viewedErrors));
                  }
                  if (peerSystems != null) {
                     for (LayeredSystem peer:peerSystems) {
                        boolean anyForPeer = false;
                        for (String peerError:peer.viewedErrors) {
                           if (!viewedErrors.contains(peerError)) {
                              if (!anyForPeer) {
                                 System.err.println(peer.getRuntimeName() + " runtime errors: ");
                                 anyForPeer = true;
                              }
                              System.err.println(peerError);
                           }
                        }
                     }
                  }

                  System.err.println("Failed build took: " + StringUtil.formatFloat((System.currentTimeMillis() - buildStartTime)/1000.0));
               }
               return false;
            }
         }

      }
      finally {
         if (!separateLayersOnly)
            buildingSystem = false;
      }

      for (int i = 0; i < layers.size(); i++)
         if (layers.get(i).buildSrcIndexNeedsSave)
            System.out.println("*** Exiting build with unsaved buildSrcIndexes");

      return true;
   }

   void buildCompleted(boolean doPeers) {
      systemCompiled = true;
      needsRefresh = true;
      // Any models that are started after we need to mark as changed.
      lastStartedLayer = null;
      lastModelsStartedLayer = null;
      lastBuiltLayer = null;
      changedModels.clear();
      changedTypes.clear();
      processedModels.clear();
      allToGenerateFiles.clear();

      // Clear out any build state for any layers to reset for the next build
      for (int i = 0; i < layers.size(); i++) {
         Layer layer = layers.get(i);
         if (layer.buildState != null) {
            layer.lastBuildState = layer.buildState;
            layer.buildState = null;
         }
      }

      if (doPeers && peerSystems != null) {
         for (LayeredSystem peerSys: peerSystems) {
            peerSys.buildCompleted(false);
         }
      }
   }

   /** Models such as schtml templates need to run after the entire system has compiled - the postBuild phase. */
   private void initPostBuildModels() {
      if (getUseContextClassLoader())
         Thread.currentThread().setContextClassLoader(getSysClassLoader());

      // Turn off sync for the page objects when they are being rendered statically - just removes some errors because we may create synchronized instances here
      SyncManager.SyncState oldState = SyncManager.setSyncState(SyncManager.SyncState.Disabled);
      postBuildProcessing = true;
      try {
         PerfMon.start("postBuildFiles");
         for (Map.Entry<String,ModelToPostBuild> ent:modelsToPostBuild.entrySet()) {
            ModelToPostBuild m = ent.getValue();
            IFileProcessorResult model = m.model;
            if (options.verbose)
               System.out.println("PostBuild processing: " + model.getProcessedFileId());

            model.postBuild(m.genLayer, m.genLayer.buildSrcDir);
         }
         PerfMon.end("postBuildFiles");
      }
      finally {
         SyncManager.restoreOldSyncState(oldState);
         postBuildProcessing = false;
      }
   }

   private int runRunCommands() {
      List<BuildCommandHandler> cmds = runCommands.get(BuildPhase.Process);
      if (cmds != null) {
         List<ProcessBuilder> pbs = getProcessBuildersFromCommands(cmds, this, this, null);
         if (!LayerUtil.execCommands(pbs, buildDir, options.info)) {
            System.err.println("*** Error occurred during run command");
            return -1;
         }
      }
      return 0;
   }

   private int runTestCommands() {
      List<BuildCommandHandler> cmds = testCommands.get(BuildPhase.Process);
      if (cmds != null) {
         List<ProcessBuilder> pbs = getProcessBuildersFromCommands(cmds, this, this, null);
         if (!LayerUtil.execCommands(pbs, buildDir, options.info)) {
            System.err.println("*** Error occurred during test command");
            return -1;
         }
      }
      return 0;
   }

   private String[] processCommandArgs(String[] args) {
      ArrayList<String> result = new ArrayList<String>();
      if (args != null) {
         int i = 0;
         for (String arg:args) {
            if (arg.indexOf("<%") != -1) {
               String targ = TransformUtil.evalTemplate(buildLayer, arg, true);
               // Args which are surrounded by [ brackets are split into multiple args
               if (targ.startsWith("[")) {
                  targ = targ.substring(1, targ.length()-1);
                  String[] targs = StringUtil.split(targ, " ");
                  for (String ttarg:targs)
                     result.add(ttarg);
               }
               else
                  result.add(targ);
            }
            else
               result.add(arg);
            i++;
         }
      }
      return result.toArray(new String[result.size()]);
   }

   String runMainMethod(String runClass, String[] runClassArgs, int lowestLayer) {
      Object type = getRuntimeTypeDeclaration(runClass);
      if (lowestLayer != -1) {
         if (type instanceof TypeDeclaration) {
            Layer typeLayer = ((TypeDeclaration) type).getLayer();
            // Already ran this one before
            if (typeLayer.layerPosition <= lowestLayer)
               return null;
         }
         else
            return null;
      }
      return runMainMethod(type, runClass, runClassArgs);
   }

   String runStopMethod(String runClass, String stopMethod) {
      Object type = getRuntimeTypeDeclaration(runClass);
      return runStopMethod(type, runClass, stopMethod);
   }

   void stopMainInstances(String typeName) {
      Iterator insts = getInstancesOfType(typeName);
      while (insts.hasNext()) {
         Object inst = insts.next();
         if (inst instanceof IStoppable) {
            if (options.verbose)
               verbose("Stopping main instance of type: " + typeName);
            ((IStoppable) inst).stop();
         }
         else if (inst instanceof IAltStoppable) {
            if (options.verbose)
               verbose("Stopping main instance of type: " + typeName);
            ((IAltStoppable) inst)._stop();
         }
      }
   }

   String runMainMethod(String runClass, String[] runClassArgs, List<Layer> theLayers) {
      Object type = getRuntimeTypeDeclaration(runClass);
      if (theLayers != null) {
         if (type instanceof TypeDeclaration) {
            Layer typeLayer = ((TypeDeclaration) type).getLayer();
            // Already ran this one before or the layer is not part of the current layers
            if (!theLayers.contains(typeLayer) || !typeLayer.activated)
               return null;
         }
         else
            return null;
      }
      return runMainMethod(type, runClass, runClassArgs);
   }

   static private Class MAIN_ARG = sc.type.Type.get(String.class).getArrayClass(String.class, 1);

   String runMainMethod(Object type, String runClass, String[] runClassArgs) {
      if (runtimeProcessor != null) {
         return runtimeProcessor.runMainMethod(type, runClass, runClassArgs);
      }
      Object[] args = new Object[] {processCommandArgs(runClassArgs)};
      if (type != null && ModelUtil.isDynamicType(type)) {
         Object meth = ModelUtil.getMethod(this, type, "main", null, null, null, false, null, null, new Object[] {MAIN_ARG});
         if (meth == null)
            return "No method named: 'main' on type: " + ModelUtil.getTypeName(type);
         if (!ModelUtil.hasModifier(meth, "static"))
            return "Main method missing 'static' modifier: " + runClass;
         else {
            if (options.info)
               System.out.println("Running dynamic: " + runClass + "(" + StringUtil.arrayToString(args) + ")");
            runClassStarted = true;
            ModelUtil.callMethod(null, meth, args);
         }
      }
      else if (type instanceof Class && IDynObject.class.isAssignableFrom((Class) type)) {
      }
      else {
         Object rc = getCompiledClass(runClass);
         if (rc == null) {
            return "No main class to run: " + runClass;
         }
         Class rcClass = (Class) rc;
         Method meth = RTypeUtil.getMethod(rcClass, "main", MAIN_ARG);
         if (meth == null)
            return "No main(String[]) method to run on class: " + runClass;
         if (!PTypeUtil.hasModifier(meth, "static"))
            return "Main method missing 'static' modifier: " + runClass;
         else {
            if (options.info)
               System.out.println("Running compiled main for class: " + runClass + "(" + StringUtil.arrayToString(args) + ")");
            runClassStarted = true;
            PTypeUtil.invokeMethod(null, meth, args);
         }
      }
      return null;
   }

   String runStopMethod(Object type, String runClass, String stopMethod) {
      if (runtimeProcessor != null) {
         return runtimeProcessor.runStopMethod(type, runClass, stopMethod);
      }
      Object[] args = new Object[] {};
      if (type != null && ModelUtil.isDynamicType(type)) {
         Object meth = ModelUtil.getMethod(this, type, stopMethod, null, null, null, false, null, null, new Object[] {});
         if (meth == null)
            return "No stopMethod named: " + stopMethod + " on type: " + ModelUtil.getTypeName(type);
         if (!ModelUtil.hasModifier(meth, "static"))
            return "Main stopMethod missing 'static' modifier: " + runClass;
         else {
            if (options.info)
               System.out.println("Running dynamic stopMethod for: " + runClass);
            runClassStarted = true;
            ModelUtil.callMethod(null, meth, args);
         }
      }
      else if (type instanceof Class && IDynObject.class.isAssignableFrom((Class) type)) {
      }
      else {
         Object rc = getCompiledClass(runClass);
         if (rc == null) {
            return "No main class to run stopMethod: " + runClass + "." + stopMethod;
         }
         Class rcClass = (Class) rc;
         Method meth = RTypeUtil.getMethod(rcClass, stopMethod);
         if (meth == null) {
            return "No main method: " + stopMethod + " on type: " + runClass;
         }
         else if (!PTypeUtil.hasModifier(meth, "static"))
            return "Main stopMethod missing 'static' modifier: " + runClass + "." + stopMethod;
         else {
            if (options.info)
               System.out.println("Running compiled main stopMethod for class: " + runClass + "." + stopMethod + "()");
            runClassStarted = true;
            PTypeUtil.invokeMethod(null, meth, args);
         }
      }
      return null;
   }

   void runScript(String execName, String[] execArgs) {
      String[] allExecArgs = new String[execArgs.length+1];
      allExecArgs[0] = FileUtil.concat(buildDir, execName);
      System.arraycopy(execArgs, 0, allExecArgs, 1, execArgs.length);
      ProcessBuilder pb = new ProcessBuilder(allExecArgs);
      if (options.runFromBuildDir)
         pb.directory(new File(buildDir));
      if (options.verbose)
         System.out.println("Starting process: " + pb);
      int stat;
      if ((stat = LayerUtil.execCommand(pb, null, null, null)) != 0)
         System.err.println("Error - external process exited with: " + stat);
   }

   private boolean needsPhase(BuildPhase phase) {
      if (phase == BuildPhase.Process)
         return true;
      for (IFileProcessor[] fps:fileProcessors.values()) {
         for (IFileProcessor fp:fps)
            if (fp.getBuildPhase() == phase)
               return true;
      }
      return false;
   }

   private final static String TEMP_LAYER_BASE_NAME = "layer";

   public void addTemporaryLayer(String prefix, boolean runMain) {
      int ix = 0;
      String rootFile = getNewLayerDir();
      String pathName;
      String baseName;
      do {
         pathName = FileUtil.concat(rootFile, "temp", baseName = TEMP_LAYER_BASE_NAME + ix);
         File f = new File(pathName);
         if (!f.isDirectory() && !f.canRead())
            break;
         // Always use the same temp layer for test mode so we don't have different path names and layer names in
         // the output to mess up the test verification.   Also keeps these temp layers from piling up
         else if (options.testVerifyMode) {
            FileUtil.removeDirectory(pathName);
            break;
         }
         ix++;
      } while (true);
      Layer layer = new Layer();
      layer.layeredSystem = this;
      layer.layerDirName = "temp." + TEMP_LAYER_BASE_NAME + ix;
      layer.layerBaseName = baseName;
      layer.layerPathName = pathName;
      // We need the temp layer to be dynamic if we're loading a dynamic test script which creates objects and stuff.  TODO: should this always be true? There were maybe some advantages to having compiledOnly not have a dynamic layer at all?
      layer.dynamic = !getCompiledOnly() || (options.testMode && options.testScriptName != null);
      layer.defaultModifier = "public";
      layer.tempLayer = true;
      layer.newLayer = true;
      layer.useGlobalImports = true;  // It should inherit all of the imports (or we could have it extend all of the types?

      if (options.info)
         System.out.println("Adding temporary layer: " + layer.layerDirName);

      // TODO: should add extends?

      if (prefix == null && layer.inheritPackage && layers.size() > 0)
         layer.packagePrefix = layers.get(layers.size()-1).packagePrefix;
      else
         layer.packagePrefix = prefix == null ? "" : prefix;

      LayerParamInfo paramInfo = new LayerParamInfo();
      addLayer(layer, null, runMain, false, true, true, false, true, true, paramInfo);
   }

   public EditorContext getDefaultEditorContext() {
      if (editorContext == null) {
         editorContext = new EditorContext(this);
         if (syncInited)
            editorContext.initSync();
      }
      return editorContext;
   }

   private static class ModelToUpdate {
      ILanguageModel oldModel;
      SrcEntry srcEnt;
   }

   private void updateLayer(Layer newLayer, ArrayList<ModelToUpdate> res, ExecutionContext ctx) {
      for (IdentityWrapper<ILanguageModel> wrapper:newLayer.layerModels) {
         ILanguageModel model = wrapper.wrapped;

         if (model instanceof JavaModel) {
            JavaModel jmodel = (JavaModel) model;
            if (jmodel.isLayerModel)
               continue;
            // If when loading we replaced an existing model we may have some dynamic updates to do
            if (jmodel.replacesModel != null) {
               jmodel.replacesModel.updateModel(jmodel, ctx, TypeUpdateMode.Replace, true, null);
               // TODO: this should be moved till after the updateLayer is run on the other layered system
               jmodel.replacesModel.completeUpdateModel(jmodel, true);
               jmodel.replacesModel = null;
            }
            else if (jmodel.isLayerModel && jmodel.modifiesModel())
               jmodel.updateLayeredModel(ctx, true, null);
         }
      }

      // Iterate over the src files in each layer
      for (String cacheKey:newLayer.srcDirCache.keySet()) {
         // Since these are double registered, choose the one with the extension
         if (FileUtil.getExtension(cacheKey) != null) {
            String typeName = FileUtil.removeExtension(cacheKey).replace('/', '.');
            typeName = CTypeUtil.prefixPath(newLayer.packagePrefix, typeName);
            // Need to lookup the table from the previous layer since the cache's fromPosition won't know about this new layer yet
            // Checking for any type here...
            TypeDeclaration type = getCachedTypeDeclaration(typeName, null, null, false, true, null, false);

            // If'we've loaded this type, we need to refresh the system so that we get the new type
            // Need to do this all after the build though... oterhwise we can end up loading the modules twice.  Another option would be
            // code the build so it uses modelIndex first??
            if (type != null && type != INVALID_TYPE_DECLARATION_SENTINEL) {
               ModelToUpdate m = new ModelToUpdate();
               m.srcEnt = new SrcEntry(newLayer, FileUtil.concat(newLayer.layerPathName, cacheKey), cacheKey);
               m.oldModel = modelIndex.get(m.srcEnt.absFileName);
               res.add(m);
            }
         }
      }
   }

   private ArrayList<ModelToUpdate> updateLayers(List<Layer> theLayers, ExecutionContext ctx) {
      if (options.liveDynamicTypes && ctx != null) {
         batchingModelUpdates = true;
         ArrayList<ModelToUpdate> res = null;
         try {
            res = new ArrayList<ModelToUpdate>();
            // Iterate through all new layers (either this one or a new one extended by this one)
            // For any modified types, see if there are instances of that type.  If so, update them
            // based on the changes made by the modify declaration.
            for (int i = 0; i < theLayers.size(); i++) {
               Layer newLayer = theLayers.get(i);

               updateLayer(newLayer, res, ctx);
            }
         }
         finally {
            batchingModelUpdates = false;
         }

         return res;
      }
      return null;
   }

   private ArrayList<ModelToUpdate> updateLayers(int newLayerPos, ExecutionContext ctx) {
      if (options.liveDynamicTypes && ctx != null) {
         ArrayList<ModelToUpdate> res = null;
         try {
            batchingModelUpdates = true;
            res = new ArrayList<ModelToUpdate>();
            // Iterate through all new layers (either this one or a new one extended by this one)
            // For any modified types, see if there are instances of that type.  If so, update them
            // based on the changes made by the modify declaration.
            for (int i = newLayerPos; i < layers.size(); i++) {
               Layer newLayer = layers.get(i);

               updateLayer(newLayer, res, ctx);
            }
         }
         finally {
            batchingModelUpdates = false;
         }

         return res;
      }
      return null;
   }

   private void initStartupObjects(List<Layer> theLayers) {
      // First initialize any types in the init type group - thus all static properties for these types are defined before any instances are created
      Object[] initTypes = resolveTypeGroupMembers(BuildInfo.InitGroupName);

      // This is going through the entire list, not the list of objects that were just added but should not matter
      // for static objects.   Should this maybe be done through a hook in the annotation/scope handler?
      Object[] startupTypes = resolveTypeGroupMembers(BuildInfo.StartupGroupName);
      if (startupTypes != null) {
         for (int i = 0; i < startupTypes.length; i++) {
            Object st = startupTypes[i];
            if (DynUtil.isType(st) && ModelUtil.isDynamicType(st)) {
               String typeName = DynUtil.getTypeName(st, false);
               // Only do this once for a given type - this happens when you have @MainInit on a class.  During the compiled mode, we'll create these with object tags in main.
               // TODO: if there's a compiled instance of this guy already how will we know?  We can check if there are any instances of this type?
               if (globalObjects.get(typeName) == null) {
                  Object inst = DynUtil.createInstance(st, null);
                  globalObjects.put(typeName, inst);
               }
            }
         }
      }

      if (options.runClass != null) {
         // Do this in a separate thread since the main will block in most cases
         new RunMainLayersThread(theLayers).start();
      }
   }

   private void initStartupTypes() {
      resolveTypeGroupMembers(BuildInfo.InitGroupName);
      resolveTypeGroupMembers(BuildInfo.StartupGroupName);
   }

   private void initStartupObjects(int newLayerPos) {
      initStartupTypes();

      if (options.runClass != null) {
         // Do this in a separate thread since the main will block in most cases
         new RunMainThread(newLayerPos).start();
      }
   }

   Layer getPreviousBuildLayer(Layer layer) {
      for (int i = layer.layerPosition-1; i >= 0; i--) {
         Layer l = layers.get(i);
         if (l.isBuildLayer())
            return l;
      }
      return null;
   }

   private void updateBuildDir(Layer oldBuildLayer) {
      initBuildDir();

      // Reload the build info for the new build layer
      buildInfo = buildLayer.loadBuildInfo();
   }

   private void buildIfNecessary(Layer oldBuildLayer) {
      // If we added any non-dynamic layers, we'll need to adjust the build layer and recompile the world
      if (oldBuildLayer != buildLayer && buildLayer != null) {
         if (!buildSystem(null, true, false)) {
            System.err.println("Build failed");
         }
         buildCompleted(false);
      }
   }

   public void addLayers(String [] initLayerNames, boolean makeDynamic, ExecutionContext ctx) {
      int newLayerPos = lastLayer == null ? 0 : lastLayer.layerPosition + 1;
      Layer oldBuildLayer = buildLayer;

      List<Layer> invisLayers = getInvisLayers();

      List<String> layerNames = Arrays.asList(initLayerNames);

      newLayers = new ArrayList<Layer>();

      initLayersWithNames(layerNames, makeDynamic, true, null, true, false);

      List<Layer> layersToInit = newLayers;
      newLayers = null;

      lastLayer = layers.get(layers.size()-1);

      // If this layer is getting added back do not rebuild.  First of all it's likely that our runtime is already up to date or if we build, we can't reload anyway.  Secondly, this build will fail because it tries to be incremental off of the current buildDir which includes itself... the clean blows away stuff we need.  Need to grab this before initBuildDir which updates the loadedBuildLayers
      String oldBuildDir = loadedBuildLayers.get(lastLayer.layerUniqueName);

      updateBuildDir(oldBuildLayer);

      // Start these before we update them
      startLayers(layersToInit, false);

      // Needs to be done before the build loads the files.  Needs to be done after the layer itself has been initialized.
      // The layer needs to be initialized after we've called initSysClassLoader
      ArrayList<ModelToUpdate> res = updateLayers(layersToInit, ctx);

      // For the purposes of building the new layers, we have not started the run class yet.  Otherwise, during this compile
      // any intermediate files, say in a layer extended by the build layer, will find a prev version of the file and
      // generate the "compiled classes change" warning.  I don't think there's a chance we'll have loaded any of these
      // classes since we are adding the layers new.
      runClassStarted = false;

      if (oldBuildDir == null) {
         buildSystem(null, true, false);
         buildCompleted(false);
      }

      for (Layer newLayer:layersToInit) {
         if (newLayer.isBuildLayer())
            buildInfo.merge(newLayer.buildInfo);

         if (restartArgs != null) {
            restartArgs.add(newLayer.getLayerName());
         }
      }
      saveBuildInfo();

      UpdateInstanceInfo updateInfo = newUpdateInstanceInfo();

      if (res != null) {
         for (int i = 0; i < res.size(); i++) {
            ModelToUpdate mt = res.get(i);
            refresh(mt.srcEnt, mt.oldModel, ctx, updateInfo, mt.srcEnt.layer.activated);
         }
      }

      // Once all of the types are updated, it's safe to run all of the initialization code.  If we do this inline, we get errors where new properties refer to new fields that have not yet been added (for example)
      updateInfo.updateInstances(ctx);

      initStartupObjects(layersToInit);

      runClassStarted = true;

      processNowVisibleLayers(invisLayers);

      // TODO: remove?  is this redundant now?
      for (Layer layer:layersToInit) {
         notifyLayerAdded(layer);
      }
   }

   static class ModelToRemove {
      List<ILanguageModel> remModels;  // The model we need to remove
      ILanguageModel newModel;  // The model we are replacing it with (or null)
   }

   public void removeLayer(Layer toRemLayer, ExecutionContext ctx) {
      removeLayers(Collections.singletonList(toRemLayer), ctx);
   }

   public void removeLayers(List<Layer> toRemLayers, ExecutionContext ctx) {
      // Compute the list of modules to update by iterating over the layers and pulling out all models that are in the cache.
      // Build up a list of old/new layer pairs.
      LinkedHashMap<String, ModelToRemove> toRemModels = new LinkedHashMap<String, ModelToRemove>();

      int pos = -1;
      boolean resetBuildLayer = false;

      if (toRemLayers.size() == 0)
         throw new IllegalArgumentException("empty list to removeLayers");

      boolean activated = toRemLayers.get(0).activated;

      List<Layer> layersList = activated ? layers : inactiveLayers;

      // Assumes we are processing layers from base to last.  We first gather up the complete list of models
      // we need to replace - the one to remove and the new one (which is really the base one in this case)
      for (int li = 0; li < toRemLayers.size(); li++) {
         Layer toRem = toRemLayers.get(li);

         if (toRem.layerPosition < pos) {
            System.err.println("*** Error: remove layers received layers out of order!");
            return;
         }
         pos = toRem.layerPosition;

         if (toRem.layerPosition > layersList.size() || layersList.get(toRem.layerPosition) != toRem) {
            System.err.println("*** removeLayers called with layer which is not active: " + toRem);
            return;
         }

         if (activated != toRem.activated) {
            throw new IllegalArgumentException("*** Mix of inactive and active layers to removeLayers");
         }
      }

      int deletedCount = 0;
      for (int li = 0; li < toRemLayers.size(); li++) {
         Layer toRem = toRemLayers.get(li);
         for (IdentityWrapper<ILanguageModel> wrapper:toRem.layerModels) {
            ILanguageModel model = wrapper.wrapped;
            model = model.resolveModel();
            SrcEntry srcEnt = model.getSrcFile();
            ModelToRemove mtr = toRemModels.get(srcEnt.getTypeName());
            if (mtr == null) {
               mtr = new ModelToRemove();
               mtr.remModels = new ArrayList<ILanguageModel>();
               mtr.remModels.add(model);
               mtr.newModel = model.getModifiedModel();
               toRemModels.put(srcEnt.getTypeName(), mtr);
            }
            else {
               // If we did not just replace this one, we must have
               if (model.getModifiedModel() != mtr.remModels.get(mtr.remModels.size()-1)) {
                  mtr.newModel = model.getModifiedModel();
               }
               // else - leave newModel the same as it was since were removing both layers
               mtr.remModels.add(model);
            }
         }

         layersList.remove(toRem.layerPosition - deletedCount);
         deletedCount++;
         if (toRem == buildLayer) {
            resetBuildLayer = true;
         }
         deregisterLayer(toRem, true);

         // When we remove a layer that was specified on the command line, we need to replaced it with any base layers (unless those were also removed).  This, along with code that adds new layers to the command line preserves restartability.
         if (restartArgs != null) {
            for (int i = 0; i < restartArgs.size(); i++) {
               String arg = restartArgs.get(i);
               if (arg.endsWith("/"))
                  arg = arg.substring(0, arg.length()-1);
               String toRemName = toRem.getLayerName();
               if (arg.equals(toRemName) || arg.replace('/','.').equals(toRemName)) {
                  restartArgs.remove(i);
                  if (toRem.baseLayers != null) {
                     for (Layer toRemBase:toRem.baseLayers) {
                        if (!toRemLayers.contains(toRemBase)) {
                           restartArgs.add(i, toRemBase.getLayerName());
                        }
                     }
                  }
               }
            }
         }
      }
      // For now, we are not changing buildDir, buildSrcDir etc. and not recompiling things... maybe we just need to force a restart in this case and make sure it does not happen by not using
      // the buildDir for dynamic layers?
      if (resetBuildLayer && activated) {

         // Can't rely on TrackingClassLoader's to match the model because we are not removing the old guys class loader.
         // unless we unwind the class loaders as below
         staleClassLoader = !layeredClassPaths;

         buildLayer = layers.get(layers.size()-1);
         buildLayer.makeBuildLayer();

         // Without a custom class loader, you can't unload classes really so removeLayer does not remove the corresponding classpaths
         if (layeredClassPaths) {
            while (buildClassLoader instanceof TrackingClassLoader) {
               TrackingClassLoader cl = (TrackingClassLoader) buildClassLoader;
               if (cl.layer == buildLayer)
                  break;
               buildClassLoader = cl.parentTrackingLoader;
            }
         }

         // If any of the existing layers used this build layer need to reset them.
         for (int i = 0; i < layers.size(); i++) {
            Layer toCheck = layers.get(i);
            if (toCheck.origBuildLayer != null && toCheck.origBuildLayer.removed) {
               toCheck.origBuildLayer = buildLayer;
               toCheck.buildClassesDir = buildClassesDir;
               toCheck.sysBuildSrcDir = buildSrcDir;
            }
         }
      }

      // Renumber any layers which are after the one we removed
      for (int i = toRemLayers.get(0).layerPosition; i < layersList.size(); i++) {
         Layer movedLayer = layersList.get(i);
         movedLayer.layerPosition = i;
      }

      UpdateInstanceInfo updateInfo = newUpdateInstanceInfo();

      // Now go through and update each model
      for (Map.Entry<String, ModelToRemove> ent:toRemModels.entrySet()) {
         ModelToRemove mtr = ent.getValue();
         if (mtr.newModel != null) {
            ILanguageModel last = mtr.remModels.get(mtr.remModels.size()-1);
            if (last instanceof JavaModel) {
               JavaModel lastModel = (JavaModel) last;
               JavaModel newModel = (JavaModel) mtr.newModel;
               boolean updateLiveRuntime = activated;
               lastModel.updateModel(newModel, ctx, TypeUpdateMode.Remove, updateLiveRuntime, updateInfo);
               lastModel.completeUpdateModel(newModel, updateLiveRuntime);
            }

            // Need to remove all of the models except for the last one which we just updated.  UpdateType right now updates the types in these models.
            // Might be cleaner if we moved tht code out to updateModel.
            for (int i = 0; i < mtr.remModels.size()-1; i++)
               removeModel(mtr.remModels.get(i), true);
         }
         else {
            for (ILanguageModel remModel:mtr.remModels)
               removeModel(remModel, true);
         }
      }

      if (updateInfo != null)
         updateInfo.updateInstances(ctx);

      for (Layer l:toRemLayers) {
         for (IModelListener ml: modelListeners) {
            ml.layerRemoved(l);
         }
      }
   }

   public void addLayer(Layer layer, ExecutionContext ctx, boolean runMain, boolean setPackagePrefix, boolean saveModel, boolean makeBuildLayer, boolean build, boolean isActive, boolean cleanBuildDir, LayerParamInfo lpi) {
      try {
         acquireDynLock(false);
         Layer oldBuildLayer = null;
         int newLayerPos;
         if (isActive) {
            // Don't use the specified buildDir for the new layer
            options.buildLayerAbsDir = null;

            newLayerPos = lastLayer == null ? 0 : lastLayer.layerPosition + 1;
            oldBuildLayer = buildLayer;
         }
         else {
            newLayerPos = inactiveLayers.size();
         }

         /* Now that we have defined the base layers, we'll inherit the package prefix and dynamic state */
         boolean baseIsDynamic = false;

         layer.layerPosition = isActive ? layers.size() : inactiveLayers.size();

         layer.initModel();

         String pkgPrefix = null;
         if (setPackagePrefix && layer.baseLayers != null) {
            for (Layer baseLayer:layer.baseLayers) {
               if (baseLayer.dynamic)
                  baseIsDynamic = true;
               if (StringUtil.isEmpty(layer.packagePrefix) && !StringUtil.isEmpty(baseLayer.packagePrefix)) {
                  pkgPrefix = baseLayer.packagePrefix;
                  break;
               }
            }
            if (pkgPrefix != null) {
               layer.model.setComputedPackagePrefix(pkgPrefix);
               layer.packagePrefix = pkgPrefix;
               layer.layerUniqueName = CTypeUtil.prefixPath(layer.packagePrefix, layer.layerDirName);
            }

            layer.dynamic = !layer.compiledOnly && !getCompiledOnly() && (layer.dynamic || baseIsDynamic);
         }

         // Needs to be set before registerLayer but after we have a definite package prefix
         if (pkgPrefix == null)
            layer.layerUniqueName = CTypeUtil.prefixPath(layer.packagePrefix, layer.layerDirName);

         if (isActive) {
            registerLayer(layer);
            layers.add(layer);
            // Do not start the layer here.  If it is started, we won't get into initSysClassLoader from updateBuildDir and lastStartedLayer
            // does not get
            ParseUtil.initComponent(layer);

            lastLayer = layer;

            if (makeBuildLayer && isActive) {
               updateBuildDir(oldBuildLayer);
               // When creating temporary layers, particularly for tests, make sure we don't leave the old buildDir or buildSrcDir lying around as that can mess things up
               if (cleanBuildDir) {
                  if (new File(layer.buildDir).isDirectory())
                     FileUtil.removeDirectory(layer.buildDir);
                  if (new File(layer.buildSrcDir).isDirectory())
                     FileUtil.removeDirectory(layer.buildSrcDir);
               }
            }
         }
         else {
            registerInactiveLayer(layer);
         }

         // Do this before we start the layer so that the layer's srcDirCache includes the layer file itself
         if (saveModel)
            layer.model.saveModel();

         if (isActive) {
            initializeLayers(lpi);

            // Start these before we update them
            startLayers(layer);

            if (saveModel) {
               addNewModel(layer.model, null, null, null, true, true);
            }

            ArrayList<ModelToUpdate> res = updateLayers(newLayerPos, ctx);

            if (build)
               buildIfNecessary(oldBuildLayer);
            // For a newly created layer we should not have to build but do need to include the build dir in the sys class loader I think
            else if (makeBuildLayer) {
               layer.compiled = true;  // Since the layer is empty we can still consider it compiled... otherwise, won't get added to the sys class path in case we do put stuff there later
               initSysClassLoader(layer, ClassLoaderMode.BUILD);
            }

            UpdateInstanceInfo updateInfo = newUpdateInstanceInfo();

            if (res != null) {
               for (int i = 0; i < res.size(); i++) {
                  ModelToUpdate mt = res.get(i);
                  refresh(mt.srcEnt, mt.oldModel, ctx, updateInfo, isActive);
               }
            }

            // Now run initialization code gathered up during the update process
            updateInfo.updateInstances(ctx);

            if (runMain)
               initStartupObjects(newLayerPos);
         }

         lpi.createdLayers.add(layer);
      }
      finally {
         releaseDynLock(false);
      }
   }

   private void notifyLayerAdded(Layer layer) {
      for (IModelListener ml: modelListeners) {
         ml.layerAdded(layer);
      }
   }

   private List<Layer> getInvisLayers() {
      ArrayList<Layer> res = new ArrayList<Layer>();

      for (Layer layer:layers) {
         if (!layer.getVisibleInEditor())
            res.add(layer);
      }
      return res;
   }

   private void processNowVisibleLayers(List<Layer> invisLayers) {
      for (Layer layer:invisLayers) {
         if (layer.getVisibleInEditor()) {
            notifyLayerAdded(layer);
         }
      }
   }

   public Layer createLayer(String layerName, String layerPackage, String[] extendsNames, boolean isDynamic, boolean isPublic, boolean isTransparent, boolean isActive, boolean saveLayer) {
      Layer layer;
      try {
         acquireDynLock(false);
         String layerSlashName = LayerUtil.fixLayerPathName(layerName);
         layerName = layerName.replace(FileUtil.FILE_SEPARATOR_CHAR, '.');
         String rootFile = getNewLayerDir();
         String pathName;
         String baseName;
         pathName = FileUtil.concat(rootFile, layerSlashName);
         baseName = FileUtil.getFileName(pathName) + "." + SCLanguage.STRATACODE_SUFFIX;
         File file = new File(FileUtil.concat(pathName, baseName));
         if (file.exists())
            throw new IllegalArgumentException("Layer: " + layerName + " exists at: " + file);
         layer = new Layer();
         layer.layeredSystem = this;
         layer.activated = isActive;
         layer.layerDirName = layerName;
         layer.layerBaseName = baseName;
         layer.layerPathName = pathName;
         layer.dynamic = isDynamic && !getCompiledOnly();
         layer.defaultModifier = isPublic ? "public" : null;
         layer.transparent = isTransparent;
         layer.packagePrefix = layerPackage == null ? "" : layerPackage;
         List<Layer> baseLayers = null;

         LayerParamInfo paramInfo = new LayerParamInfo();
         paramInfo.activate = isActive;

         boolean buildBaseLayers = false;

         if (extendsNames != null) {
            layer.baseLayerNames = Arrays.asList(extendsNames);

            int beforeSize = isActive ? layers.size() : inactiveLayers.size();

            // TODO: should we expose a way to make these layers dynamic?
            layer.baseLayers = baseLayers = initLayers(layer.baseLayerNames, newLayerDir, CTypeUtil.getPackageName(layer.layerDirName), false, paramInfo, false);


            // If we added any layers which presumably are non-empty, we will need to do a build to be sure they are up to date
            if (isActive && beforeSize != layers.size())
               buildBaseLayers = true;

            if (baseLayers != null) {
               int li = 0;
               for (Layer l:baseLayers) {
                  // Add any base layers that are new... probably not reached?  Doesn't initLayers already add them?
                  if (l != null && !layers.contains(l)) {
                     addLayer(l, null, false, false, false, false, false, isActive, false, paramInfo);
                  }
                  else if (l == null) {
                     System.err.println("*** No base layer: " + layer.baseLayerNames.get(li));
                  }
                  li++;
               }
            }
         }

         if (options.info)
            System.out.println("Adding layer: " + layer.layerDirName + (baseLayers != null ? " extends: " + baseLayers : ""));

         // No need to build this layer but if we dragged in any base layers, we might need to build this one just to deal with them.
         addLayer(layer, null, true, true, saveLayer, true, buildBaseLayers, isActive, false, paramInfo);

      }
      finally {
         releaseDynLock(false);
      }
      return layer;
   }

   private void initializeLayers(LayerParamInfo paramInfo) {
      for (Layer initLayer:paramInfo.createdLayers) {
         // This is to be consistent with other code paths - but don't we always init inactive layers?
         if (initLayer.activated)
            ParseUtil.initComponent(initLayer);
         notifyLayerAdded(initLayer);
      }
   }

   public File getLayerFile(String layerName) {
      // defaults to using paths relative to the current directory
      if (layerPathDirs == null)
         return getLayerFileInDir(System.getProperty("user.dir"), layerName);

      for (File pathDir:layerPathDirs) {
         File f = getLayerFileInDir(pathDir.getPath(), layerName);
         if (f != null)
            return f;
      }
      return null;
   }

   public File getLayerFileInDir(String layerDir, String layerPathName) {
      layerDir = FileUtil.makeAbsolute(layerDir);

      String layerFileName;
      String layerTypeName;

      if (layerPathName.equals(".")) {
         layerFileName = System.getProperty("user.dir");
         layerTypeName = FileUtil.getFileName(layerFileName);
      }
      else {
         // Trim trailing slashes
         layerPathName = FileUtil.removeTrailingSlash(layerPathName);

         layerFileName = FileUtil.concat(layerDir, LayerUtil.fixLayerPathName(layerPathName));
         layerTypeName = layerPathName.replace(FileUtil.FILE_SEPARATOR_CHAR, '.');
         /* For flexibility, allow users to specify the package prefix or not */
      }

      String layerBaseName = CTypeUtil.getClassName(layerTypeName) + "." + SCLanguage.STRATACODE_SUFFIX;

      File layerFile = new File(FileUtil.concat(layerFileName, layerBaseName));
      if (layerFile.canRead())
         return layerFile;

      return null;
   }

   /**
    * Initializes the list of layers with the given layer names as specified by the user either on the command line
    * or in the extends of the parent layer.  When a parent layer is used, it provides possibly a relative directory
    * (i.e. to take you out of the layer directory) and a relative path, i.e. so you can resolve layers in the same
    * group without specifying the full name.
    */
   private List<Layer> initLayers(List<String> layerNames, String relDir, String relPath, boolean markDynamic, LayerParamInfo lpi, boolean specified) {
      List<Layer> layers = new ArrayList<Layer>();
      for (String layerName:layerNames) {
         Layer l = initLayer(layerName, relDir, relPath, markDynamic, lpi);
         layers.add(l);
         if (l != null && l.layeredSystem != this)
            System.out.println("*** initLayer returned mismatching runtime");
         if (specified && !specifiedLayers.contains(l) && l != null)
            specifiedLayers.add(l);
         lpi.createdLayers.add(l);
      }
      return layers;
   }

   private Layer findLayerInPath(String layerPathName, String relDir, String relPath, boolean markDynamic, LayerParamInfo lpi) {
      // defaults to using paths relative to the current directory
      if (layerPathDirs == null || relDir != null) {
         Layer res = findLayer(relDir == null ? "." : relDir, layerPathName, relDir, relPath, markDynamic, lpi);
         if (res != null)
            return res;
      }

      if (layerPathDirs != null) {
         for (File pathDir : layerPathDirs) {
            Layer l = findLayer(pathDir.getPath(), layerPathName, null, relPath, markDynamic, lpi);
            if (l != null)
               return l;
         }
      }
      return null;
   }

   /** This works like findLayerInPath but only returns the path name of a valid layerDef file. */
   public String findLayerDefFileInPath(String layerPathName, String relDir, String relPath) {
      // defaults to using paths relative to the current directory
      if (layerPathDirs == null || relDir != null)
         return findLayerDefFile(relDir == null ? "." : relDir, layerPathName, relDir, relPath);

      for (File pathDir:layerPathDirs) {
         String path = findLayerDefFile(pathDir.getPath(), layerPathName, null, relPath);
         if (path != null)
            return path;
      }
      return null;
   }

   private String mapLayerDirName(String layerDir) {
      if (layerDir.equals(".")) {
         layerDir = System.getProperty("user.dir");
      }
      else {
         layerDir = FileUtil.makeAbsolute(layerDir);
      }
      return layerDir;
   }

   /** This uses the same algorithm as findLayer but only computes and validates the layerDefFile's path name. */
   private String findLayerDefFile(String layerDir, String layerPathName, String relDir, String layerPrefix) {
      String layerFileName;
      String layerTypeName;

      layerDir = mapLayerDirName(layerDir);

      if (layerPathName.equals(".")) {
         layerFileName = System.getProperty("user.dir");
         layerTypeName = FileUtil.getFileName(layerFileName);
      }
      else if (FileUtil.isAbsolutePath(layerPathName)) {
         return null;
      }
      else {
         // Trim trailing slashes
         while (layerPathName.endsWith(FileUtil.FILE_SEPARATOR))
            layerPathName = layerPathName.substring(0, layerPathName.length()-1);

         layerTypeName = CTypeUtil.prefixPath(layerPrefix, layerPathName);
         String layerPathNamePath;
         layerFileName = FileUtil.concat(layerDir, layerPathNamePath = LayerUtil.fixLayerPathName(layerPathName));
      }

      String layerBaseName = CTypeUtil.getClassName(layerTypeName) + "." + SCLanguage.STRATACODE_SUFFIX;

      String layerDefFile = FileUtil.concat(layerFileName, layerBaseName);

      if (new File(layerDefFile).canRead())
         return layerDefFile;
      return null;
   }

   private Layer findLayer(String layerDir, String layerPathName, String relDir, String layerPrefix, boolean markDynamic, LayerParamInfo lpi) {
      Layer layer;
      String layerFileName;
      String layerTypeName;
      String layerPathPrefix;
      String layerGroup;
      boolean inLayerDir = false;

      layerDir = mapLayerDirName(layerDir);

      if (layerPathName.equals(".")) {
         layerFileName = System.getProperty("user.dir");
         if (layerDir != null && layerFileName.startsWith(layerDir)) {
            layerTypeName = layerFileName.substring(layerDir.length());
            while (layerTypeName.startsWith("/"))
               layerTypeName = layerTypeName.substring(1);
            layerTypeName = layerTypeName.replace('/', '.');
            layerGroup = CTypeUtil.getPackageName(layerTypeName);
            if (layerDir.equals(layerFileName))
               layerTypeName = CTypeUtil.prefixPath(layerTypeName, FileUtil.getFileName(layerFileName));
            layerPathPrefix = null;
         }
         else {
            return null;
            /*
            layerTypeName = FileUtil.getFileName(layerFileName);
            layerPathPrefix = null;
            layerGroup = "";
            */
         }
         inLayerDir = true;
      }
      else if (FileUtil.isAbsolutePath(layerPathName)) {
         System.err.println("*** Error: absolute path names for layers not yet supported.  Must specify -lp <layerRoot> <relLayerName>");
         return null;
      }
      else {
         // Trim trailing slashes
         while (layerPathName.endsWith(FileUtil.FILE_SEPARATOR))
            layerPathName = layerPathName.substring(0, layerPathName.length()-1);

         String layerPathNamePath;
         layerFileName = FileUtil.concat(layerDir, layerPathNamePath = LayerUtil.fixLayerPathName(layerPathName));
         File layerFile = new File(layerFileName);
         if (layerFile.isAbsolute()) {
            layerGroup = "";
         }
         else {
            layerGroup = FileUtil.getParentPath(layerPathNamePath);
         }
         layerPathName = layerPathName.replace(FileUtil.FILE_SEPARATOR_CHAR, '.');
         layerPathPrefix = CTypeUtil.getPackageName(layerPathName);
         layerTypeName = CTypeUtil.prefixPath(layerPrefix, layerPathName);
         /* For flexibility, allow users to specify the package prefix or not */
      }

      if (lpi.activate && (layer = layerPathIndex.get(layerPathName)) != null)
         return layer;

      if (!lpi.activate) {
         layer = lookupInactiveLayer(layerPathName, false, false);
         if (layer != null)
            return layer;
      }

      String layerBaseName = CTypeUtil.getClassName(layerTypeName) + "." + SCLanguage.STRATACODE_SUFFIX;

      String layerDefFile = FileUtil.concat(layerFileName, layerBaseName);

      layer = new Layer();
      layer.layeredSystem = this;
      layer.layerPathName = layerFileName;
      layer.layerBaseName = layerBaseName;
      layer.layerDirName = layerPathName;
      // Gets reset layer once we parse the layer define file and find the packagePrefix[
      layer.layerUniqueName = layerTypeName;

      Map<String, Layer> pendingLayers = lpi.activate ? pendingActiveLayers : pendingInactiveLayers;
      Object existingLayerObj;
      if ((existingLayerObj = pendingLayers.get(layerTypeName)) != null) {
         if (existingLayerObj instanceof Layer) {
            Layer existingLayer = (Layer) existingLayerObj;
            throw new IllegalArgumentException("Layer extends cycle detected: " + findLayerCycle(existingLayer));
         }
         else
            throw new IllegalArgumentException("Component/layer name conflict: " + layerTypeName + " : " + existingLayerObj + " and " + layerDefFile);
      }

      if (!lpi.activate) {
         Layer inactiveLayer = lookupInactiveLayer(layerPathName.replace('/', '.'), false, false);
         if (inactiveLayer != null)
            return inactiveLayer;
         layer.activated = false;
      }

      File defFile = new File(layerDefFile);
      if (defFile.canRead()) {
         // Initially register the global object under its type name before parsing
         // Do this for de-activated objects as well because it's needed for the 'resolveName' instance made
         // in the modify declaration when initializing the instance.
         pendingLayers.put(layerTypeName, layer);

         if (inLayerDir)
            layerGroup = "";

         SrcEntry defSrcEnt = new SrcEntry(layer, layerDefFile, FileUtil.concat(layerGroup, layerBaseName));

         try {
            // Parse the model, validate it defines an instance of a Layer
            Layer newLayer = (Layer) loadLayerObject(defSrcEnt, Layer.class, layerTypeName, layerPrefix, relDir, layerPathPrefix, inLayerDir, markDynamic, layerPathName, lpi);

            if (newLayer != null) {
               layer = newLayer;
               /*
               if (layerNamePrefix != null && !layerNamePrefix.equals(layer.packagePrefix))
                  System.err.println("*** Error: layer name prefix does not match package prefix in the layer definition file: " + layerNamePrefix + " != " + layer.packagePrefix);
               */
            }
            else
               return null;
         }
         finally {
            // No matter what, get it out since the name name have changed or maybe we did not look it up.
            pendingLayers.remove(layerTypeName);
         }
      }
      else {
         return null;
         /*
          * Require a layer definition file since otherwise, you can end up easily trying to build at the wrong
          * level in the layer hierarchy.
         File layerPathFile = new File(layerFileName);
         if (!layerPathFile.isDirectory()) {
            return null;
         }
         layerTypeName = TypeUtil.prefixPath(layer.packagePrefix, layerTypeName);
         layer.layerUniqueName = layerTypeName; // Resetting now that we have the prefix.
         */
      }

      if (layer.activated) {
         registerLayer(layer);

         // Our first dynamic layer goes on the end but we start tracking it there
         if (layer.dynamic && layerDynStartPos == -1) {
            layerDynStartPos = layers.size();
         }

         // We've already added a dynamic layer - now we're adding a compiled layer.  Because dynamic is inherited, we know
         // this layer does not depend on the dynamic layer so we'll add it just in front of the dynamic layer and slide
         // everyone else back.
         /*
         if (layerDynStartPos != -1 && !layer.dynamic) {
            layer.layerPosition = layerDynStartPos;

            layers.add(layerDynStartPos, layer);

            layerDynStartPos++;
            for (int i = layerDynStartPos; i < layers.size(); i++) {
               Layer prevLayer = layers.get(i);
               if (layer.extendsLayer(prevLayer))
                  System.err.println("Layer configuration error.  Compiled layer: " + layer.layerDirName + " depends on dynamic layer: " + prevLayer.layerDirName);
               prevLayer.layerPosition++;

               // if one of the moved dynamic layers was the last started or built, we need to reset it to the one just
               // before this layer.  That ensures we build these next time and build the classpath properly.
               if (lastStartedLayer == prevLayer)
                  lastStartedLayer = layer.getPreviousLayer();
               if (lastBuiltLayer == prevLayer)
                  lastBuiltLayer = layer.getPreviousLayer();
            }
         }
         else {
         */
            int numLayers = layers.size();

            // Make sure any layers which have features which this layer replaces are updated to know that this layer is present.
            layer.initReplacedLayers();

            int startIx = numLayers - 1;

            if (!layer.dynamic && layerDynStartPos != -1) {
               startIx = layerDynStartPos;
               layerDynStartPos++;
            }

            // This is just a nicety but since compiledLayers tend to be framework layers, it's best to put them
            // in front of those that can be made dynamic.  That also means they won't jump positions when going
            // from compiled to dynamic.  The stack is in general more readable when framework layers are at the base.
            int sortPriority = layer.getSortPriority();
            if (sortPriority != Layer.DEFAULT_SORT_PRIORITY) {
               int resortIx = -1;
               for (int prevIx = startIx; prevIx >= 0; prevIx--) {
                  Layer prevLayer = layers.get(prevIx);
                  // Should we move this layer up one past the prev layer - only if it does not extend it!
                  if (!layer.extendsLayer(prevLayer)) {
                     // If this layer extends some layer which is replaced by this layer - in other words, the new layer replaces a feature in some core layer extended so
                     // the new layer needs to go before this layer.
                     if (prevLayer.extendsReplacedLayer(layer)) {
                        resortIx = prevIx;
                     }
                     else if (sortPriority < prevLayer.getSortPriority()) {
                        resortIx = prevIx;
                     }
                  }
                  else
                     break;
               }
               if (resortIx != -1) {
                  layers.add(resortIx, layer);
                  numLayers++;
                  for (int i = resortIx; i < numLayers; i++) {
                     Layer prevLayer = layers.get(i);
                     prevLayer.layerPosition = i;
                     if (lastStartedLayer == prevLayer)
                        lastStartedLayer = layer.getPreviousLayer();
                     if (lastBuiltLayer == prevLayer)
                        lastBuiltLayer = layer.getPreviousLayer();

                     // We can't have any compiled layers extending dynamic layers - so enforce this with an error message here
                     if (i >= layerDynStartPos && !layer.dynamic) {
                        if (layer.extendsLayer(prevLayer))
                           error("Layer configuration error.  Compiled layer: " + layer.layerDirName + " depends on dynamic layer: " + prevLayer.layerDirName);
                     }
                  }
               }
               else {
                  layer.layerPosition = numLayers;
                  layers.add(layer);
               }
            }
            else {
               layer.layerPosition = numLayers;
               layers.add(layer);
            }
         //}
      }
      else if (!layer.disabled)
         registerInactiveLayer(layer);

      return layer;
   }

   public void registerLayer(Layer layer) {
      String layerName = layer.getLayerName();
      layerPathIndex.put(layerName, layer);
      // Double register if you use "." or something that gets translated to the real name
      if (layer.layerDirName != null && !layerName.equals(layer.layerDirName))
         layerPathIndex.put(layer.layerDirName, layer);
      layerIndex.put(layer.layerUniqueName, layer);
      layerFileIndex.put(layer.getLayerModelTypeName(), layer);

      // Need to be careful here: packagePrefix is what will make the directory names unique
      // so we need to avoid conflicting definitions and make sure this guy ends up registered
      // under the right name.
      //globalObjects.put(layer.layerUniqueName, layer);

      if (newLayers != null)
         newLayers.add(layer);
   }

   public void registerInactiveLayer(Layer layer) {
      inactiveLayerIndex.put(layer.getLayerName(), layer);
      int posIx = -1;
      int defaultIx = -1;
      boolean replace = false;
      int minIx = -1;
      for (int ix = 0; ix < inactiveLayers.size(); ix++) {
         Layer oldLayer = inactiveLayers.get(ix);
         if (oldLayer.getLayerName().equals(layer.getLayerName())) {
            posIx = ix;
            replace = true;
            break;
         }
         boolean after = layer.extendsLayer(oldLayer);
         boolean before = oldLayer.extendsLayer(layer);
         if (after && before) {
            System.err.println("*** Layer extends loop between: " + oldLayer + " and: " + layer);
            before = false;
         }
         if (before) {
            posIx = ix;
            break;
         }
         else if (!after) {
            if (layer.getSortPriority() < oldLayer.getSortPriority()) {
               // If there's no actual dependency between this layer and any others in the list,
               // we'll use the earlier priority position.
               if (defaultIx == -1 || (minIx != -1 && defaultIx < minIx))
                  defaultIx = ix;
            }
         }
         // Must put this entry after this position
         else
            minIx = ix;
      }
      if (replace) {
         layer.layerPosition = posIx;
         inactiveLayers.set(posIx, layer);
      }
      else {
         if (posIx == -1 && defaultIx != -1 && (minIx == -1 || defaultIx > minIx))
            posIx = defaultIx;
         if (posIx == -1) {
            layer.layerPosition = inactiveLayers.size();
            inactiveLayers.add(layer);
         }
         else {
            layer.layerPosition = posIx;
            inactiveLayers.add(posIx, layer);
            renumberInactiveLayers(posIx + 1);
         }
      }

      // Rebuild the index layers in any listeners like the LayersView
      typeIndex.inactiveTypeIndex.indexLayersCache = null;

      notifyLayerAdded(layer);

      checkLayerPosition();
   }

   public void initInactiveLayer(Layer layer) {
      completeNewInactiveLayer(layer, true);
   }

   private void checkLayerPosition() {
      int i = 0;
      for (Layer l:inactiveLayers) {
         if (l.layerPosition != i)
            System.err.println("*** Invalid inactive layer position");
         i++;
      }
      i = 0;
      for (Layer l:layers) {
         if (l.layerPosition != i)
            System.err.println("*** Invalid layer position");
         i++;
      }
   }
   private void renumberInactiveLayers(int fromIx) {
      for (int i = fromIx; i < inactiveLayers.size(); i++)
         inactiveLayers.get(i).layerPosition = i;
   }

   public Layer lookupActiveLayer(String fullTypeName, boolean checkPeers, boolean skipExcluded) {
      Layer res = getLayerByDirName(fullTypeName);
      if (res != null && (!skipExcluded || !res.excluded))
         return res;
      if (checkPeers && peerSystems != null) {
         for (LayeredSystem peer:peerSystems) {
            Layer peerRes = peer.lookupActiveLayer(fullTypeName, false, skipExcluded);
            if (peerRes != null)
               return peerRes;
         }
      }
      return null;
   }

   /** Provide skipExcluded = false unless you want to return null if the layer is excluded */
   public Layer lookupInactiveLayer(String layerName, boolean checkPeers, boolean skipExcluded) {
      Layer res = inactiveLayerIndex.get(layerName);
      if (res != null && (!skipExcluded || !res.excluded))
         return res;
      if (checkPeers && peerSystems != null) {
         for (LayeredSystem peer:peerSystems) {
            Layer peerRes = peer.lookupInactiveLayer(layerName, false, skipExcluded);
            if (peerRes != null)
               return peerRes;
         }
      }
      return null;
   }

   public Layer lookupLayerSync(String fullTypeName, boolean checkPeers, boolean skipExcluded) {
      Layer res;
      acquireDynLock(false);
      try {
         res = lookupActiveLayer(fullTypeName, checkPeers, skipExcluded);
         if (res == null)
            res = lookupInactiveLayer(fullTypeName, checkPeers, skipExcluded);
      }
      finally {
         releaseDynLock(false);
      }
      return res;
   }

   public Layer lookupLayer(String fullTypeName, boolean checkPeers, boolean skipExcluded) {
      Layer res = lookupActiveLayer(fullTypeName, checkPeers, skipExcluded);
      if (res == null)
         res = lookupInactiveLayer(fullTypeName, checkPeers, skipExcluded);
      return res;
   }

   public void deregisterLayer(Layer layer, boolean removeFromSpecified) {
      layer.removed = true;
      // Start by invoking the dynamic stop... if it overrides something it will have to call the super.stop which might be in the interfaces
      String layerName = layer.getLayerName();
      if (layer.activated) {
         layerPathIndex.remove(layerName);
         // Double register if you use "." or something that gets translated to the real name
         if (layer.layerDirName != null && !layerName.equals(layer.layerDirName))
            layerPathIndex.remove(layer.layerDirName);
         layerIndex.remove(layer.layerUniqueName);
         layerFileIndex.remove(layer.getLayerModelTypeName());

         // Need to be careful here: packagePrefix is what will make the directory names unique
         // so we need to avoid conflicting definitions and make sure this guy ends up registered
         // under the right name.
         globalObjects.remove(layer.layerUniqueName);
         //removeTypeByName(layer, layer.layerUniqueName, layer.model.getModelTypeDeclaration(), null);
         if (removeFromSpecified)
            specifiedLayers.remove(layer);
      }
      else {
         inactiveLayerIndex.remove(layerName);
      }
   }

   private List<Layer> findLayerCycle(Layer foundLayer) {
      List<String> baseLayers = foundLayer.baseLayerNames;
      ArrayList<Layer> cycleLayers = new ArrayList<Layer>();
      cycleLayers.add(foundLayer);
      Map<String, Layer> pendingLayers = foundLayer.activated ? pendingActiveLayers : pendingInactiveLayers;
      if (baseLayers != null) {
         for (int i = 0; i < baseLayers.size(); i++) {
            Object baseLayerObj = pendingLayers.get(baseLayers.get(i));
            if (baseLayerObj instanceof Layer) {
               Layer baseLayer = (Layer) baseLayerObj;
               if (baseLayer.extendsLayer(foundLayer)) {
                  cycleLayers.add(baseLayer);
                  break;
               }
            }
         }
      }
      return cycleLayers;
   }

   /**
    * The layer lookup is first done with the complete path name, i.e. sc.util,
    * If we can't find the layer in the sub-directory "sc/util", we then just look
    * for util.  This makes it optional to put the layer's package prefix onto directory
    * tree where the layer lives, avoiding those extra nearly empty directories.
    */
   private Layer initLayer(String layerPathName, String relDir, String relPath, boolean markDynamic, LayerParamInfo lpi) {
      String prefix = null;
      String origLayerPathName = layerPathName;
      Layer layer = findLayerInPath(layerPathName, relDir, null, markDynamic, lpi);
      int ix;
      while (layer == null && (ix = layerPathName.indexOf(".")) != -1) {
         prefix = CTypeUtil.prefixPath(prefix, layerPathName.substring(0, ix));
         layerPathName = layerPathName.substring(ix+1);
         layer = findLayerInPath(layerPathName, relDir, prefix, markDynamic, lpi);
      }

      if (layer == null && relPath != null) {
         // relPath NOT propagated here
         layer = findLayerInPath(CTypeUtil.prefixPath(relPath, origLayerPathName), relDir, null, markDynamic, lpi);
      }

      if (layer == null) {
         origLayerPathName = origLayerPathName.replace('.',FileUtil.FILE_SEPARATOR_CHAR);
         String layerBaseName = FileUtil.addExtension(FileUtil.getFileName(origLayerPathName), SCLanguage.STRATACODE_SUFFIX);
         String layerFileName = FileUtil.concat(origLayerPathName, layerBaseName);
         File layerPathFile = new File(layerFileName);
         if (lpi.activate)
            System.err.println("No layer definition file: " + layerPathFile.getPath() + (layerPathFile.isAbsolute() ? "" : " (at full path " + layerPathFile.getAbsolutePath() + ")") + " in layer path: " + layerPath);
      }
      else if (layer.initFailed && lpi.activate) {
         System.err.println("Layer: " + layer.getLayerName() + " failed to start");
         anyErrors = true;
         return null;
      }
      return layer;
   }

   public Layer findLayerByName(String relPath, String layerName) {
      for (int i = 0; i < layers.size(); i++) {
         Layer l = layers.get(i);
         if (l.getLayerName().equals(layerName))
            return l;
         if (relPath != null) {
            String fullPath = CTypeUtil.prefixPath(relPath, layerName);
            if (l.getLayerName().equals(fullPath))
               return l;
         }
      }
      return null;
   }

   public Layer findInactiveLayerByName(String relPath, String layerName, boolean openLayers) {
      for (int i = 0; i < inactiveLayers.size(); i++) {
         Layer l = inactiveLayers.get(i);
         if (l.getLayerName().equals(layerName))
            return l;
         if (relPath != null) {
            String fullPath = CTypeUtil.prefixPath(relPath, layerName);
            if (l.getLayerName().equals(fullPath))
               return l;
         }
      }
      Layer res = getInactiveLayer(layerName, openLayers, false, false, false);
      if (res != null)
         return res;
      if (relPath != null) {
         String fullLayerName = CTypeUtil.prefixPath(relPath, layerName);
         res = getInactiveLayer(fullLayerName, openLayers, false, false, false);
         if (res != null)
            return res;
      }
      return null;
   }

   public Layer getLayerByName(String layerName) {
      return layerIndex.get(layerName);
   }

   /** layer package prefix + layer name for the UI lookup */
   public Layer getLayerByTypeName(String layerTypeName) {
      return layerFileIndex.get(layerTypeName);
   }

    /** Uses the normalizes path name, i.e. "/" even on windows */
   public Layer getLayerByPath(String layerPath, boolean checkPeers) {
      String layerType = layerPath.replace('/', '.');
      Layer res = layerPathIndex.get(layerType);
      if (res == null && checkPeers && peerSystems != null) {
         for (LayeredSystem peerSys:peerSystems) {
            res = peerSys.layerPathIndex.get(layerType);
            if (res != null)
               break;
         }
      }
      return res;
   }

   /** Uses the "." name of the layer as we found it in the layer path.  Does not include the layer's package prefix like the full layerName */
   public Layer getLayerByDirName(String layerPath) {
      return layerPathIndex.get(layerPath);
   }

   /**
    * This is the classpath of the current external dependencies, including all class-path entries added for all layers.  Not including
    * the system classpath which StrataCode was started with (unless those directories are added again in a layer)
    */
   public String getDepsClassPath() {
      if (buildLayer == null)
         return null;
      return getClassPathForLayer(buildLayer, false, null, false);
   }

   public String getClassPathForLayer(Layer startLayer, boolean includeBuildDir, String useBuildDir, boolean addSysClassPath) {
      StringBuilder sb = new StringBuilder();
      boolean addOrigBuild = true;
      if (useBuildDir != null && includeBuildDir)
         sb.append(useBuildDir); // Our build dir overrides all other directories
      if (startLayer == coreBuildLayer) {
         // Need this for the IDE because the sc.jar files are not in the rootClasspath in that environment because we want to debug those.  They are when running scc though.
         if (strataCodeInstallDir != null) {
            LayerUtil.addQuotedPath(sb, getStrataCodeRuntimePath(false, false));
         }
         addOrigBuild = startLayer.appendClassPath(sb, includeBuildDir, useBuildDir, addOrigBuild);

      }
      else {
         for (int i = startLayer.layerPosition; i >= 0; i--) {
            Layer layer = layers.get(i);
            addOrigBuild = layer.appendClassPath(sb, includeBuildDir, useBuildDir, addOrigBuild);
         }
      }
      if (includeBuildDir && addOrigBuild && origBuildDir != null) {
         LayerUtil.addQuotedPath(sb, origBuildDir);
      }
      /*
      HashMap<String,PackageEntry> tempSystemClasses = packageIndex.get("java/lang");
      if (tempSystemClasses == null)
         System.err.println("*** Unable to find jar in classpath defining clasess in java.lang");
      else {
         // Generates the built-in list of classes
         for (String cl:tempSystemClasses.keySet()) {
            System.out.println("   \"" + cl + "\",");
         }
      }
      */
      if (addSysClassPath)
         return sb.toString() + FileUtil.PATH_SEPARATOR + rootClassPath;
      else
         return sb.toString();
   }

   /** TODO: can we remove this and instead rely on the stricter StrataCode directory organization (in initStratCodeDir0 for identifying a layer directory? */
   public boolean isValidLayersDir(String dirName) {
      String layerPathFileName = FileUtil.concat(dirName, LayerConstants.SC_DIR, LAYER_PATH_FILE);
      File layerPathFile = new File(layerPathFileName);
      return layerPathFile.canRead();
   }

   private static String getLayersDirFromBinDir() {
      String currentDir = System.getProperty("user.dir");
      if (currentDir != null) {
         String parentDir = FileUtil.getParentPath(currentDir);
         File f = new File(parentDir);
         return FileUtil.concat(parentDir, "layers");
      }
      return null;
   }

   public void addLayerPathDir(String dirName) {
      if (dirName.equals("."))
         dirName = System.getProperty("user.dir");
      if (dirName.length() == 0) {
         if (layerPathDirs.size() == 0)
            throw new IllegalArgumentException("Invalid empty layer path specified");
         else {
            System.err.println("*** Ignoring empty directory name in layer path: " + dirName);
         }
      }
      File f = new File(dirName);
      if (!f.exists() || !f.isDirectory())
         throw new IllegalArgumentException("*** Invalid layer path - Each path entry should be a directory: " + f);
         // TODO: error if path directories are nested
      else {
         String scDirName = FileUtil.concat(dirName, LayerConstants.SC_DIR);
         // TODO: picking the last directory in the layer path because right now that's how IntelliJ orders them and it seems
         // like a potentially 'too powerful' idea to let someone to modify a layer in an upstream path entry.
         // You can just replace the types to fix the layer if you need to in an incremental way.
         // Down the road we could reverse this decision to make it consistent.
         if (!isStrataCodeDir && new File(scDirName).isDirectory()) {
            strataCodeMainDir = dirName;
         }
         String layerPathFileName = FileUtil.concat(dirName, LayerConstants.SC_DIR, LAYER_PATH_FILE);
         File layerPathFile = new File(layerPathFileName);
         if (layerPathFile.canRead()) {
            // As soon as we find one .layerPath file, consider the system installed
            systemInstalled = true;
            // Include any new entries in the .layerPath file into the layer path
            String layerPath = FileUtil.getFileAsString(layerPathFileName);
            if (layerPath != null && !layerPath.trim().equals(".")) {
               String[] newLayerPaths = layerPath.split(FileUtil.PATH_SEPARATOR);
               for (String newLayerPath:newLayerPaths) {
                  if (!inLayerPath(newLayerPath)) {
                     File newDirFile = new File(newLayerPath);
                     if (!newDirFile.exists() || !newDirFile.isDirectory()) {
                        System.err.println("*** Ignoring invalid layerPath directory in: " + layerPathFileName + "  directory: " + newLayerPath + " does not exist");
                     }
                     else
                        layerPathDirs.add(newDirFile);
                  }
               }
            }
         }

         layerPathDirs.add(f);
      }
   }

   boolean inLayerPath(String newLayerPath) {
      for (File layerPath:layerPathDirs) {
         if (layerPath.getPath().equals(newLayerPath))
            return true;
      }
      return false;
   }

   private void initLayerPath() {
      if (layerPath != null) {
         // When you explicitly set the layerPath we assume everything is installed
         systemInstalled = true;

         String [] dirNames = layerPath.split(FileUtil.PATH_SEPARATOR);
         layerPathDirs = new ArrayList<File>(dirNames.length);
         for (String d:dirNames) {
            addLayerPathDir(d);
         }

         if (!peerMode)
            verbose("Using explicit layer path: " + layerPath);
      }
      else if (strataCodeMainDir == null) {
         String currentDir = System.getProperty("user.dir");
         if (initStrataCodeDir(currentDir))
            return;
         else {
            if (isValidLayersDir(".")) {
               systemInstalled = true;
            }
            else if (currentDir != null) {
               // If we are in the bin directory, check for the layers up and over one level
               if (FileUtil.getFileName(currentDir).equals("bin") && initStrataCodeDir(FileUtil.getParentPath(currentDir))) {
                  return;
               }
               else {
                  // Are we running from a StrataCodeDir that does not yet have layers or bundles?
                  File currentFile = new File(currentDir);
                  String parentName;
                  File binDir = new File(currentFile, "bin");
                  if (binDir.isDirectory()) {
                     File scJarFile = new File(binDir, STRATACODE_LIBRARIES_FILE);
                     // When running from the StrataCode dist directory, we put the results in 'layers' mostly because
                     // git needs an empty directory to start from.
                     if (scJarFile.canRead()) {
                        layersFilePathPrefix = "layers";
                     }
                  }
                  else {
                     // Perhaps running from the bin directory of a StrataCode install dir without layers
                     String parentDir = FileUtil.getParentPath(currentDir);
                     if (parentDir != null) {
                        binDir = new File(parentDir, "bin");
                        if (binDir.isDirectory()) {
                           File scJarFile = new File(binDir, STRATACODE_LIBRARIES_FILE);
                           if (scJarFile.canRead()) {
                              layersFilePathPrefix = ".." + FileUtil.FILE_SEPARATOR + "layers";
                           }
                        }
                     }
                  }

                  do {
                     parentName = currentFile.getParent();
                     if (parentName != null) {
                        if (initStrataCodeDir(parentName))
                           return;

                        /* We used to use .stratacode for storing layerPath in a layers or bundle but now we use it for the home directory - storing
                          build src, indexes etc.   Turns out these two uses conflict because we find the home directory .stratacode thinking it's a layer bundle
                           and initialize in the wrong location.  need to remove this one
                        File layerPathFile = new File(FileUtil.concat(parentName, LayerConstants.SC_DIR));
                        // There's a .stratacode here - it's a layer bundle - either layers or inside of bundles?
                        if (layerPathFile.isDirectory()) {
                           String parentParent = FileUtil.getParentPath(parentName);
                           // We are in the layers directory
                           if (parentParent != null) {
                              if (initStrataCodeDir(parentParent))
                                 return;
                              String ppp = FileUtil.getParentPath(parentParent);
                              // In the bundles directory
                              if (initStrataCodeDir(ppp))
                                 return;
                           }
                           // Otherwise, we are in a layer bundle directory that's not in the normal structure.  Just put this path
                           // in the layer path.
                           if (layerPath == null) {
                              strataCodeMainDir = newLayerDir = parentName;
                              layerPath = parentName;
                              layerPathDirs = new ArrayList<File>();
                              layerPathDirs.add(new File(layerPath));

                              systemInstalled = true;
                              return;
                           }
                        }
                        else
                        */
                           currentFile = new File(parentName);
                     }
                  } while (parentName != null);
               }
            }
         }
      }
   }

   private boolean initStrataCodeDir(String dir) {
      if (dir == null)
         return false;

      // Must either have a bin or .stratacode directory to be considered an install dir - otherwise, anything with layers or bundles in
      // them is considered an install dir.
      if (!new File(dir, "bin").isDirectory() && !new File(dir, LayerConstants.SC_DIR).isDirectory() && !new File(dir, "conf").isDirectory())
         return false;

      String bundlesFileName = FileUtil.concat(dir, "bundles");
      File bundlesFile = new File(bundlesFileName);
      boolean inited = false;
      StringBuilder layerPathBuf = new StringBuilder();
      if (bundlesFile.isDirectory()) {
         String[] bundleNames = bundlesFile.list();
         for (String bundleName:bundleNames) {
            String bundleDirName = FileUtil.concat(bundlesFileName, bundleName);
            File bundleDir = new File(bundleDirName);
            if (!bundleDir.isDirectory())
               continue;
            /*
            if (excludedBundles != null && excludedBundles.contains(bundleName)) {
               if (options.verbose)
                  System.out.println("Excluding bundle: " + bundleName);
               continue;
            }
            */
            if (layerPathBuf.length() > 0)
               layerPathBuf.append(FileUtil.PATH_SEPARATOR_CHAR);
            layerPathBuf.append(bundleDirName);
            if (layerPathDirs == null)
               layerPathDirs = new ArrayList<File>();
            layerPathDirs.add(bundleDir);
         }
         inited = true;
      }

      String layersFileName = FileUtil.concat(".", "layers");
      File layersFile = new File(layersFileName);
      if (layersFile.isDirectory()) {
         newLayerDir = mapLayerDirName(layersFileName);
         if (layerPathDirs == null)
            layerPathDirs = new ArrayList<File>();
         layerPathDirs.add(layersFile);
         if (layerPathBuf.length() > 0)
            layerPathBuf.append(FileUtil.PATH_SEPARATOR_CHAR);
         layerPathBuf.append(layersFileName);
         inited = true;
      }

      if (inited) {
         layerPath = layerPathBuf.toString();
         strataCodeMainDir = dir;
         systemInstalled = true;
         isStrataCodeDir = true;

         verbose("Initialized layer path: " + layerPath + " from StrataCode main dir: " + dir);
      }
      return inited;
   }

   public String getNewLayerDir() {
      if (newLayerDir != null)
         return newLayerDir;
      if (layerPath == null) {
         return System.getProperty("user.dir");
      }
      else
         return layerPathDirs.get(0).toString();
   }

   public String getUndoDirPath() {
      String undoDirPath = FileUtil.concat(strataCodeMainDir, ".undo");
      File undoDir = new File(undoDirPath);
      if (!undoDir.isDirectory())
         undoDir.mkdirs();
      return undoDir.getPath();
   }

   public String getLayerPath() {
      if (layerPath != null)
         return layerPath;
      else {
         String userDir = null;
         try {
            return new File(userDir = System.getProperty("user.dir")).getCanonicalPath();
         }
         catch (IOException exc) {
            System.err.println("*** Cannot evaluate user.dir: " + userDir);
            return ".";
         }
      }
   }

   /** This is a version of the classpath that we can stick into a script or something */
   private static String buildUserClassPath(String classpath) {
      String[] list = StringUtil.split(classpath, FileUtil.PATH_SEPARATOR_CHAR);
      if (list == null || list.length == 0)
         return null;

      StringBuilder sb = new StringBuilder();
      String javaHome = System.getProperty("java.home");
      for (int i = 0; i < list.length; i++) {
         // Skip any jar libs that are installed with the system
         if (javaHome != null && list[i].startsWith(javaHome))
            continue;
         File f = new File(list[i]);

         if (i != 0)
            sb.append(FileUtil.PATH_SEPARATOR);
         if (!f.isAbsolute())
            sb.append(f.getAbsolutePath());
         else
            sb.append(list[i]);
      }
      return sb.toString();
   }

   private static class PackageEntry {
      String fileName;
      boolean zip;
      boolean src;
      Layer layer;
      PackageEntry prev;     // When one definition overrides another, this stores the previous definition
      PackageEntry(String fileName, boolean zip, boolean src, Layer layer) {
         this.fileName = fileName;
         this.zip = zip;
         this.src = src;
         this.layer = layer;
      }
   }

   // Maps from packageName to a map of the PackageEntry objects in that package.   That map's key is the base-type-name (not including the package name).
   // Each PackageEntry stores the path to ZipFile or root-dir which defines this resource.  We build the packageIndex starting at the base layer and moving up
   // the layer stack.  When one entry replaces the other, it stores the "prev" value which points to the old entry.  Each PackageEntry stores whether it is in
   // .class format or src format.
   private HashMap<String,HashMap<String,PackageEntry>> packageIndex = new HashMap<String,HashMap<String,PackageEntry>>();
   public Set<String> getFilesInPackage(String packageName) {
      if (packageName != null) {
         if (packageName.length() == 0)
            packageName = null;
         else
            packageName = packageName.replace('.', FileUtil.FILE_SEPARATOR_CHAR);
      }
      HashMap<String,PackageEntry> pkgEnt = packageIndex.get(packageName);
      if (pkgEnt == null)
         return null;
      return pkgEnt.keySet();
   }

   public boolean isValidPackage(String packageName) {
      return packageIndex.containsKey(packageName.replace('.', '/'));
   }

   public Set<String> getPackageNames() {
      return packageIndex.keySet();
   }

   public Set<String> getSrcTypeNames(List<Layer> listOfLayers, boolean prependLayerPrefix, boolean includeInnerTypes, boolean restrictToLayer, boolean includeImports) {
      TreeSet<String> typeNames = new TreeSet<String>();

      for (int i = 0; i < listOfLayers.size(); i++) {
         Layer layer = listOfLayers.get(i);

         typeNames.addAll(layer.getSrcTypeNames(prependLayerPrefix, includeInnerTypes, restrictToLayer, includeImports));
      }

      return typeNames;
   }

   public Set<String> getSrcTypeNames(boolean prependLayerPrefix, boolean includeInnerTypes, boolean restrictToLayer, boolean includeImports, boolean activeLayers) {
      TreeSet<String> typeNames = new TreeSet<String>();

      List<Layer> layerList = activeLayers ? layers : inactiveLayers;

      for (int i = 0; i < layerList.size(); i++) {
         Layer layer = layerList.get(i);

         if (layer.getVisibleInEditor())
            typeNames.addAll(layer.getSrcTypeNames(prependLayerPrefix, includeInnerTypes, restrictToLayer, includeImports));
      }

      return typeNames;
   }

   private PackageEntry getPackageEntry(String classPathName) {
      String classRelPath = FileUtil.removeExtension(classPathName);
      String pkgName = FileUtil.getParentPath(classRelPath);
      if (pkgName == null)
         return null;
      HashMap<String,PackageEntry> pkgEnt = packageIndex.get(pkgName);
      if (pkgEnt == null)
         return null;
      return pkgEnt.get(FileUtil.getFileName(classRelPath));
   }

   /** Must be called after the layers have been initialized */
   private void initClassIndex(String rootCP) {
      rootClassPath = rootCP;
      addPathToIndex(null, rootCP);
      // Ordinarily the classes.jar is in the classpath.  But if not (like in the -jar option used in layerCake), we need to
      // find the classes in the boot class path.
      if (packageIndex.get("java" + FileUtil.FILE_SEPARATOR + "awt") == null) {
         String bootPath = System.getProperty("sun.boot.class.path");
         if (bootPath == null)
            System.err.println("*** No boot classpath found in properties: " + System.getProperties());
         String[] pathEntries = StringUtil.split(bootPath, FileUtil.PATH_SEPARATOR);
         boolean jdkFound = false;
         // Only add the JDK.  This is needed for importing "*" on system packages.
         for (String p:pathEntries) {
            if (p.contains("classes.jar") || p.contains("Classes.jar") || p.contains("rt.jar")) {
               jdkFound = true;
               addPathToIndex(null, p);
            }
         }
         if (!jdkFound)
            System.err.println("*** Did not find classes.jar or rt.jar in sun.booth.class.path for JDK imports: " + pathEntries);
      }
   }

   private void refreshLayerIndex() {
      for (int i = 0; i < layers.size(); i++) {
         Layer layer = layers.get(i);
         if (layer.needsIndexRefresh)
            layer.initSrcCache(null);
      }
   }

   private void initTypeIndexDir() {
      String newDirName = getTypeIndexDir();
      if (typeIndexDirName != null) {
          FileUtil.renameFile(typeIndexDirName, newDirName);
      }
      typeIndexDirName = newDirName;
      File typeIndexDir = new File(typeIndexDirName);
      typeIndexDir.mkdirs();
      switch (options.typeIndexMode) {
         case Rebuild:
            System.err.println("*** NOT REMOVING: " + typeIndexDir);
            //FileUtil.removeFileOrDirectory(typeIndexDir);
            typeIndexDir.mkdirs();
            break;
         case Refresh:
            break;
         case Load:
            break;
         case Lazy:
            break;
      }
      if (typeIndex == null)
         typeIndex = new SysTypeIndex(this, getTypeIndexIdent());

      if (!peerMode && peerSystems != null)
         for (LayeredSystem peerSys:peerSystems)
            peerSys.initTypeIndexDir();
   }

   private static final String MAIN_SYSTEM_MARKER_FILE = "MainSystem.txt";

   private void saveTypeIndexFiles() {
      acquireDynLock(false);
      try {
         for (int i = 0; i < inactiveLayers.size(); i++) {
            Layer inactiveLayer = inactiveLayers.get(i);
            // We have not fully initialized the type index until the layer is started
            if (inactiveLayer.started && inactiveLayer.typeIndexNeedsSave)
               inactiveLayer.saveTypeIndex();
         }
         // Save any the global information in the type index - i.e. the list of layers each time.
         typeIndex.saveToDir(getTypeIndexDir());

         if (!peerMode && peerSystems != null) {
            for (int i = 0; i < peerSystems.size(); i++) {
               LayeredSystem peerSys = peerSystems.get(i);
               peerSys.saveTypeIndexFiles();
            }

            if (procInfoNeedsSave) {
               File mainSysFile = new File(getTypeIndexDir(), MAIN_SYSTEM_MARKER_FILE);
               FileUtil.saveStringAsFile(mainSysFile, "Marker file for recognizing the main system", true);

               if (runtimes.size() > 0) {
                  for (IRuntimeProcessor runtime : runtimes) {
                     if (runtime != null && !isRuntimeDisabled(runtime.getRuntimeName())) {
                        DefaultRuntimeProcessor.saveRuntimeProcessor(this, runtime);
                     }
                  }
               }
               for (IProcessDefinition proc : processes) {
                  if (proc != null) {
                     ProcessDefinition.saveProcessDefinition(this, proc);
                  }
               }
               procInfoNeedsSave = false;
            }
         }
      }
      finally {
         releaseDynLock(false);
      }
      /*
      for (int i = 0; i < layers.size(); i++) {
         Layer layer = layers.get(i);
         layer.saveTypeIndex();
      }
      */
   }

   private LayeredSystem findSystemFromTypeIndexIdent(String typeIndexIdent) {
      if (typeIndexIdent.equals(getTypeIndexIdent()))
         return this;
      if (peerSystems != null) {
         int sz = peerSystems.size();
         for (int i = 0; i < sz; i++) {
            LayeredSystem peerSys = peerSystems.get(i);
            if (peerSys.getTypeIndexIdent().equals(typeIndexIdent))
               return peerSys;
         }
      }
      return null;
   }

   private String[] getRuntimeDirNames(boolean create) {
      File typeIndexMainDir = new File(getStrataCodeDir("idx"));

      if (!typeIndexMainDir.isDirectory()) {
         if (create)
            typeIndexMainDir.mkdirs();
         else
            return null;
      }

      return typeIndexMainDir.list();
   }

   public static final ProcessDefinition INVALID_PROCESS_SENTINEL = new ProcessDefinition();

   private void initTypeIndexRuntimes() {
      String[] runtimeDirNames = getRuntimeDirNames(false);

      if (runtimeDirNames == null)
         return;

      String thisRuntimeName = getRuntimeName();

      ArrayList<LayeredSystem> newPeers = new ArrayList<LayeredSystem>();

      // First we need to find the "main" system so we set our processIdent properly for this layered system
      for (String runtimeDirName:runtimeDirNames) {
         if (!runtimeDirName.startsWith(TYPE_INDEX_DIR_PREFIX))
            continue;

         String typeIndexIdent = runtimeDirName.substring(TYPE_INDEX_DIR_PREFIX.length());
         int sepIx = typeIndexIdent.indexOf('_');
         String newRuntimeName, newProcessName;
         if (sepIx == -1) {
            newRuntimeName = typeIndexIdent;
            newProcessName = null;
         }
         else {
            newRuntimeName = typeIndexIdent.substring(0, sepIx);
            newProcessName = typeIndexIdent.substring(sepIx + 1);
         }

         File typeIndexDir = new File(getTypeIndexDir(typeIndexIdent));

         String[] fileNames = typeIndexDir.list();
         if (fileNames == null) {
            System.err.println("*** Unable to access typeIndex directory: " + typeIndexDir.getPath());
            return;
         }
         // We may create types_java directories for this layered system before we've used it for a specific process
         if (fileNames.length == 0)
            continue;

         IProcessDefinition proc = getProcessDefinition(newRuntimeName, newProcessName, true);

         if (proc == INVALID_PROCESS_SENTINEL)
            continue;

         File mainIndexFile = new File(typeIndexDir, MAIN_SYSTEM_MARKER_FILE);
         if (mainIndexFile.exists() && processDefinition == null && newRuntimeName.equals(thisRuntimeName)) {
            processDefinition = proc;
         }
         else {
            LayeredSystem peerSys = createPeerSystem(proc, new ArrayList<String>(), Collections.<String>emptyList());
            newPeers.add(peerSys);
         }
      }

      initNewPeers(newPeers);
   }

   /**
    * The internal method to load the main type index.  In general, we do not need to be called with the dyn lock and
    * it's best if you avoid doing that because this can take a long time when rebuilding the index from scratch.
    */
   private void loadTypeIndex(Set<String> refreshedLayers) {
      String[] runtimeDirNames = getRuntimeDirNames(true);

      if (peerMode)
         System.err.println("*** TypeIndexEntry should only be loaded in the main layeredSystem");

      // A simple guard to prevent this from being done twice - the first one to create the process map should init the index.
      acquireDynLock(false);
      try {
         if (typeIndexProcessMap == null) {
            typeIndexProcessMap = new HashMap<String, SysTypeIndex>();
            if (typeIndex == null) {
               typeIndex = new SysTypeIndex(this, getTypeIndexIdent());
            }
         }
         else
            return;
      }
      finally {
         releaseDynLock(false);
      }

      for (String runtimeDirName:runtimeDirNames) {
         if (!runtimeDirName.startsWith(TYPE_INDEX_DIR_PREFIX))
            continue;

         String typeIndexIdent = runtimeDirName.substring(TYPE_INDEX_DIR_PREFIX.length());

         File typeIndexDir = new File(getTypeIndexDir(typeIndexIdent));
         String[] fileNames = typeIndexDir.list();
         if (fileNames == null) {
            error("*** Load typeIndex with no typeIndex directory: " + typeIndexDir.getPath());
            return;
         }
         // We may create types_java directories for this layered system before we've used it for a specific process
         if (fileNames.length == 0)
            continue;
         HashMap<String,Boolean> filesToProcess = new HashMap<String,Boolean>();
         for (String file:fileNames) {
            if (file.equals(MAIN_SYSTEM_MARKER_FILE))
               continue;
            filesToProcess.put(file, Boolean.FALSE);
         }

         LayeredSystem curSys = findSystemFromTypeIndexIdent(typeIndexIdent);

         SysTypeIndex curTypeIndex = typeIndexProcessMap.get(typeIndexIdent);
         if (curTypeIndex == null && curSys != null && curSys.typeIndex != null) {
            curTypeIndex = curSys.typeIndex;
            typeIndexProcessMap.put(typeIndexIdent, curTypeIndex);
         }
         if (curTypeIndex == null) {
            typeIndexProcessMap.put(typeIndexIdent, curTypeIndex = new SysTypeIndex(curSys, typeIndexIdent));
         }
         if (curSys != null && curSys.typeIndex == null)
            curSys.typeIndex = curTypeIndex;

         boolean foundIndex = curTypeIndex.loadFromDir(getTypeIndexDir(typeIndexIdent));
         if (!foundIndex) {
            warning("Failed to load type index order file - rebuilding the type index");
            options.typeIndexMode = TypeIndexMode.Rebuild;
         }

         RefreshTypeIndexContext refreshCtx = new RefreshTypeIndexContext(typeIndexIdent, filesToProcess, curTypeIndex, refreshedLayers, typeIndexDir);

         while (filesToProcess.size() > 0) {
            String indexFileName = filesToProcess.keySet().iterator().next();

            if (indexFileName.endsWith(Layer.TYPE_INDEX_SUFFIX)) {
               String uname = indexFileName.substring(0, indexFileName.length() - Layer.TYPE_INDEX_SUFFIX.length());
               String layerName = Layer.getLayerNameFromUniqueUnderscoreName(uname);

               Layer layer = curSys == null ? null : curSys.lookupLayerSync(layerName, false, true);
               if (layer != null) {
                  filesToProcess.remove(indexFileName);

                  // TODO: do we need to refresh this layer's type index here?   Or did it already get refreshed?
                  LayeredSystem layerSys = layer.layeredSystem;
                  layerSys.acquireDynLock(false);
                  try {
                     SysTypeIndex sysIdx = layerSys.typeIndex;
                     LayerListTypeIndex idx = layer.activated ? sysIdx.activeTypeIndex : sysIdx.inactiveTypeIndex;
                     if (writeLocked == 0) {
                        System.err.println("*** Modifying type index without write lock");
                        new Throwable().printStackTrace();
                     }
                     if (layer.layerTypeIndex == null)
                        layer.initTypeIndex();
                     idx.addLayerTypeIndex(layerName, layer.layerTypeIndex);
                     refreshedLayers.add(layerName);
                  }
                  finally {
                     layerSys.releaseDynLock(false);
                  }
               }
               else {
                  acquireDynLock(false);
                  try {
                     if (curSys != null)
                        curSys.refreshLayerTypeIndexFile(layerName, refreshCtx, false);
                     else
                        this.refreshLayerTypeIndexFile(layerName, refreshCtx, false);

                     // The process of getting a layer may have created this runtime so use it as soon as it becomes available.
                     if (curSys == null)
                        curSys = findSystemFromTypeIndexIdent(typeIndexIdent);
                  }
                  finally {
                     releaseDynLock(false);
                  }
               }
            }
            else {
               filesToProcess.remove(indexFileName);
            }
         }
         rebuildLayersTypeIndex(refreshCtx.layersToRebuild);

         for (LayerTypeIndex startIndex:refreshCtx.toStartLaterIndexes) {
            for (BodyTypeDeclaration toStartLater:startIndex.toStartLaterTypes) {
               JavaModel toStartModel = toStartLater.getJavaModel();
               Layer toStartLayer = toStartModel.getLayer();
               if (toStartLayer.closed)
                  toStartLayer.markClosed(false, false);
               ParseUtil.initAndStartComponent(toStartModel);
            }
         }
      }

      /*
      if (!peerMode && peerSystems != null) {
         for (LayeredSystem peerSys:peerSystems) {
            peerSys.loadTypeIndex(refreshedLayers);
         }
      }
      */
   }

   void refreshLayerTypeIndexFile(String layerName, RefreshTypeIndexContext refreshCtx, boolean optional) {
      // Already refreshed this one from some other super-type
      String indexFileName = FileUtil.getFileName(getTypeIndexFileName(refreshCtx.typeIndexIdent, layerName));
      HashMap<String,Boolean> ftp = refreshCtx.filesToProcess;
      // If we already processed this one, it will have been removed from the list so no need to refresh it again
      if (ftp.get(indexFileName) == null)
         return;

      LayerTypeIndex layerTypeIndex = readTypeIndexFile(refreshCtx.typeIndexIdent, layerName);
      if (layerTypeIndex == null) {
         // When refreshing the base layers for a layer, we might have jumped to a different layered system.  I don't think we have to enforce that dependency
         if (optional)
            return;

         // ?? huh we just listed out the file - rebuild it from scratch.
         verbose("Layer type index not found for layer: " + layerName + " rebuilding type index");

         addLayerToRebuildTypeIndex(layerName, refreshCtx.layersToRebuild);
         refreshCtx.refreshedLayers.add(layerName);
      } else {
         switch (options.typeIndexMode) {
            case Refresh:
               // need to put this here because we might initialize some layers during this process and need to make this index available to avoid a duplicate
               refreshCtx.curTypeIndex.inactiveTypeIndex.addLayerTypeIndex(layerName, layerTypeIndex);
               layerTypeIndex = layerTypeIndex.refreshLayerTypeIndex(this, layerName, new File(FileUtil.concat(refreshCtx.typeIndexDir.getPath(), indexFileName)).lastModified(), refreshCtx);
               // We found new or changed types during the refresh - it's too early to start them now but we need to do that to update the type index
               if (layerTypeIndex != null && layerTypeIndex.toStartLaterTypes != null)
                  refreshCtx.toStartLaterIndexes.add(layerTypeIndex);
               refreshCtx.refreshedLayers.add(layerName);
               break;
            default:
               break;
         }
      }
      if (writeLocked == 0) {
         System.err.println("*** Modifying type index without write lock");
         new Throwable().printStackTrace();
      }
      if (layerTypeIndex != null) {
         refreshCtx.curTypeIndex.inactiveTypeIndex.addLayerTypeIndex(layerName, layerTypeIndex);
      }
      else {
         // The layer was removed - remove the type index entry and the file behind it to avoid this problem next itme
         refreshCtx.curTypeIndex.inactiveTypeIndex.removeLayerTypeIndex(layerName);
         removeTypeIndexFile(refreshCtx.typeIndexIdent, layerName);
      }
      if (layerTypeIndex != null && layerTypeIndex.langExtensions != null)
         customSuffixes.addAll(Arrays.asList(layerTypeIndex.langExtensions));

      Boolean old = ftp.remove(indexFileName);
      if (old == null)
         System.err.println("*** Failed to remove the index file from files to process for: " + indexFileName);
   }

   /** To pick up any new source directories or layers added you can call this */
   public void refreshTypeIndex() {
      if (refreshTypeIndex(typeIndexProcessedLayers)) {
         buildReverseTypeIndex(false);

         saveTypeIndexFiles();
      }
   }

   private boolean refreshTypeIndex(Set<String> refreshedLayers) {
      TypeIndexMode mode = options.typeIndexMode;
      boolean any = false;

      // If we have Refresh or Rebuild modes - walk through all layers in each layer path.  If no index, build it.
      switch (mode) {
         case Refresh:
         case Rebuild:
            // First we batch up all layers we need to index - they are initialized via the getInactiveLayer method
            // and that way all of the layer components will override each other.  Then we rebuild all of the indexes
            // once the entire layer stack is formed.
            ArrayList<Layer> layersToRebuild = new ArrayList<Layer>();
            Map<String,LayerIndexInfo> allLayers = getAllLayerIndex();
            for (LayerIndexInfo idx : allLayers.values()) {
               String layerName = idx.layerDirName;
               if (idx.layer != null && !idx.layer.disabled && (mode == TypeIndexMode.Rebuild || !typeIndex.excludesLayer(layerName))) {
                  if (mode == TypeIndexMode.Rebuild || !refreshedLayers.contains(layerName)) {
                     addLayerToRebuildTypeIndex(layerName, layersToRebuild);
                     any = true;
                  }
               }
            }
            rebuildLayersTypeIndex(layersToRebuild);
            break;
      }
      return any;
   }

   private Set<String> typeIndexProcessedLayers = new TreeSet<String>();

   /** This is called on the main layered system.  It will enable the type index on all peer systems.  */
   public void initTypeIndex() {
      typeIndexEnabled = true;
      if (peerSystems != null) {
         for (LayeredSystem peerSys:peerSystems)
            peerSys.typeIndexEnabled = true;
      }

      typeIndexProcessedLayers.clear();

      loadTypeIndex(typeIndexProcessedLayers);

      refreshTypeIndex(typeIndexProcessedLayers);

      buildReverseTypeIndex(false);

      saveTypeIndexFiles();

      typeIndexLoaded = true;

      // TODO: after we rebuild the type index from scratch (at least) it's nice if we go through and cull out unused types
      // to save memory.  But need to test this again after after updating the code to handle modified types
      // previously the modified types were not reconciled and therefore updated when a new inactive layer was loaded
      // - this is a tricky case cause we need to update the modify chain or else references are not updated properly to point to the inserted type.
      //cleanInactiveCache();
   }

   public void saveTypeIndexChanges() {
      try {
         acquireDynLock(false);
         for (int i = 0; i < inactiveLayers.size(); i++) {
            Layer layer = inactiveLayers.get(i);
            if (layer.typeIndexNeedsSave)
               layer.saveTypeIndex();
            else if (layer.typeIndexFileLastModified != -1)
               layer.updateFileIndexLastModified();
         }
         if (typeIndex != null && typeIndex.needsSave)
            typeIndex.saveToDir(getTypeIndexDir());

         // Save type index for peers here as well
         if (!peerMode && peerSystems != null) {
            for (LayeredSystem peerSys: peerSystems) {
               peerSys.saveTypeIndexChanges();
            }
         }
      }
      finally {
         releaseDynLock(false);
      }
   }

   private boolean isInUse(ILanguageModel model) {
      if (externalModelIndex != null && externalModelIndex.isInUse(model))
         return true;
      if (model instanceof JavaModel) {
         JavaModel jModel = (JavaModel) model;
         JavaModel modByModel = jModel.getModifiedByModel();
         if (modByModel != null && isInUse(modByModel))
            return true;
      }
      return false;
   }

   private final static int INACTIVE_MODEL_CACHE_EXPIRE_TIME_MILLIS = 30000;

   public void cleanInactiveCache() {
      try {
         long cleanTime = System.currentTimeMillis();
         acquireDynLock(false);
         String processIdent = getProcessIdent();
         ArrayList<String> toCullList = new ArrayList<String>();
         for (Map.Entry<String, ILanguageModel> cacheEnt: inactiveModelIndex.entrySet()) {
            ILanguageModel model = cacheEnt.getValue();

            // Don't cull layer models since they are needed more often and tend to be small
            if (model instanceof JavaModel && ((JavaModel) model).isLayerModel)
               continue;
            if (model.getLastAccessTime() == 0) {
               System.out.println("*** Error - clean inactive cache - last access time not set for model"); // TODO: maybe restored models?
               model.setLastAccessTime(System.currentTimeMillis());
               continue;
            }

            if (cleanTime - model.getLastAccessTime() > INACTIVE_MODEL_CACHE_EXPIRE_TIME_MILLIS) {
               if (externalModelIndex == null || !externalModelIndex.isInUse(model)) {
                  toCullList.add(cacheEnt.getKey());
               }
            }
            else {
               if (options.verbose)
                  verbose("Not removing inactive model: " + model.getSrcFile() + " (accessed " + (cleanTime - model.getLastAccessTime()) + " millis ago) " + processIdent);
            }
         }
         for (String toCull:toCullList) {
            ILanguageModel removed = inactiveModelIndex.remove(toCull);
            if (removed != null) {
               if (options.verbose)
                  verbose("Removing inactive model: " + removed.getSrcFile() + " (accessed " + (cleanTime - removed.getLastAccessTime()) + " millis ago) " + processIdent);
               Layer layer = removed.getLayer();
               if (layer != null) {
                  layer.layerModels.remove(new IdentityWrapper(removed));

               }
               // TODO: should we cull layers which have no models open.  Check the layer's model?  Or should we just check this for all layers after processing all types.
            }
         }
      }
      finally {
         releaseDynLock(false);
      }
      if (!peerMode) {
         for (int i = 0; i < peerSystems.size(); i++) {
            LayeredSystem peerSys = peerSystems.get(i);
            peerSys.cleanInactiveCache();
         }
      }
   }

   void refreshLayerTypeIndexDir(File srcDirFile, String relDir, String layerName, LayerTypeIndex typeIndex, long lastModified) {
      File[] files = srcDirFile.listFiles();
      for (File subF:files) {
         String path = subF.getPath();

         if (excludedFile(subF.getName(), relDir)) {
            continue;
         }

         if (subF.isDirectory()) {
            // Refresh anything that might have changed
            refreshLayerTypeIndexDir(subF, FileUtil.concat(relDir, subF.getName()), layerName, typeIndex, lastModified);
         }
         else {
            TypeIndexEntry curTypeIndexEntry = typeIndex.fileIndex.get(path);
            if (curTypeIndexEntry == TypeIndexEntry.EXCLUDED_SENTINEL)
               continue;
            String ext = FileUtil.getExtension(path);
            if (curTypeIndexEntry != null || isParseable(path) || (ext != null && customSuffixes.contains(ext))) {
               if (curTypeIndexEntry == null || lastModified < subF.lastModified()) {
                  try {
                     acquireDynLock(false);
                     if (options.verbose) {
                        verbose("Refreshing type index for layer: " + layerName + " in: " + getProcessIdent() +
                                (curTypeIndexEntry == null ? ": no type index entry for: " : " file changed: ") + path);
                     }
                     Layer newLayer = getActiveOrInactiveLayerByPath(layerName, null, false, true, true);
                     if (newLayer == null) {
                        System.err.println("*** Warning unable to find layer in type index: " + layerName + " - skipping index entyr");
                     }
                     else {
                        if (newLayer.excludedFile(path, relDir)) {
                           System.err.println("*** Need to record exclusion in layer");
                           continue;
                        }

                        LayeredSystem curSys = newLayer.layeredSystem;

                        IFileProcessor proc = curSys.getFileProcessorForFileName(path, newLayer, BuildPhase.Process);
                        if (proc != null) {
                           SrcEntry srcEnt = new SrcEntry(newLayer, relDir == null ? newLayer.layerPathName : FileUtil.concat(newLayer.layerPathName, relDir), relDir == null ? "" : relDir, subF.getName(), proc == null || proc.getPrependLayerPackage());
                           String typeName = srcEnt.getTypeName();
                           TypeDeclaration newType = (TypeDeclaration) curSys.getSrcTypeDeclaration(typeName, newLayer.getNextLayer(), true, false, true, newLayer, curTypeIndexEntry != null && curTypeIndexEntry.isLayerType);
                           if (newType != null) {
                              typeIndex.addTypeToStartLater(newType);
                              // It should already be in the type index, because it gets loaded when we do this type reference check from the lookup here.  We don't want to start anything until we've at least set up
                              // all of the layers we are going to load, so that we can start the most specific type first, so we can avoid refreshing when we add a subsequent layer
                              /*
                              JavaModel model = newType.getJavaModel();
                              if (model != null)
                                 ParseUtil.initComponent(model);
                              else {
                                 ParseUtil.initComponent(newType);
                              }
                              */
                           }
                        }
                        else
                           System.err.println("*** Warning - initialized layer: " + layerName + " to find that file: " + path + " does not belong in type index");
                     }
                  }
                  finally {
                     releaseDynLock(false);
                  }
               }
            }
         }
      }
   }

   public LayerTypeIndex buildLayerTypeIndex(String layerName) {
      ArrayList<Layer> toBuild = new ArrayList<Layer>(1);
      addLayerToRebuildTypeIndex(layerName, toBuild);
      if (toBuild.size() == 0) {
         error("Failed to find layer type index: " + layerName);
         return null;
      }
      rebuildLayersTypeIndex(toBuild);
      return toBuild.get(0).layerTypeIndex;
   }

   LinkedHashSet<String> leafLayerNames = new LinkedHashSet<String>();

   private void markClosedLayers(boolean val) {
      for (Layer inactiveLayer:inactiveLayers)
         inactiveLayer.closed = val;

      if (!peerMode && peerSystems != null) {
         for (LayeredSystem peerSys:peerSystems) {
            peerSys.markClosedLayers(val);
         }
      }
   }

   public void rebuildLayersTypeIndex(ArrayList<Layer> rebuildLayers) {
      // Start out with all layers
      try {
         acquireDynLock(false);
         startingTypeIndexLayers = true;
         for (Layer layer : rebuildLayers) {
            layer.ensureStarted(true);
         }
         for (Layer layer : rebuildLayers) {
            layer.ensureValidated(true);
         }
         for (Layer layer : rebuildLayers) {
            if (layer.layerTypeIndex == null)
               layer.initTypeIndex();
         }
         startingTypeIndexLayers = false;
         // Collecting each layer in this list - it may contain layers from different layered system so we build up a list
         // in each system of the types which need to be rebuilt in that system.
         for (Layer layer:rebuildLayers) {
            layer.layeredSystem.leafLayerNames.add(layer.getLayerName());
         }
         // Note: we are doing this here but then later starting types. For now, we are re-opening the layer right before
         // we start the type so that it can properly resolve all dependencies.  It might make sense to wait to close them till
         // after we do the start to re-validate the type index.
         markClosedLayers(true);
         // Remove any layers which are extended by other layers
         for (Layer layer:rebuildLayers) {
            if (layer.baseLayerNames != null) {
               for (String baseLayerName : layer.baseLayerNames) {
                  layer.layeredSystem.leafLayerNames.remove(baseLayerName);
                  // Restrict the search for only layers in the parent's layered system.  We need leafLayerNames to represent
                  // all leaf layers in each process - i.e. each layered system
                  Layer baseLayer = layer.layeredSystem.getInactiveLayer(baseLayerName, false, false, true, true);
                  if (baseLayer != null) {
                     baseLayer.layeredSystem.leafLayerNames.remove(baseLayerName);
                  }
               }
            }
         }
      }
      finally {
         releaseDynLock(false);
      }

      initLeafTypeIndexes();
      if (peerSystems != null) {
         for (int i = 0; i < peerSystems.size(); i++) {
            LayeredSystem peerSys = peerSystems.get(i);
            peerSys.initLeafTypeIndexes();
         }
      }
   }

   private void initLeafTypeIndexes() {
      initializingTypeIndexes = true;
      for (String leafLayerName:leafLayerNames) {
         try {
            acquireDynLock(false);
            Layer leafLayer = lookupInactiveLayer(leafLayerName, false, true);
            if (leafLayer == null) {
               System.err.println("*** Can't find leaf layer: " + leafLayerName);
               continue;
            }
            if (options.verbose)
               verbose("Initializing type index for leaf layer: " + leafLayer.getLayerName());
            // Open all of these layers
            leafLayer.markClosed(false, false);
            // refreshBoundTypes on all classes in extends types of this leaf layer since this layer may have altered the structure of the dependent layers
            if (leafLayer.baseLayers != null) {
               HashSet<Layer> visited = new HashSet<Layer>();
               for (Layer baseLayer : leafLayer.baseLayers) {
                  baseLayer.flushTypeCache(visited);
               }
               visited = new HashSet<Layer>();
               for (Layer baseLayer : leafLayer.baseLayers) {
                  baseLayer.refreshBoundTypes(ModelUtil.REFRESH_TYPEDEFS, visited);
               }
            }
            leafLayer.initAllTypeIndex();
            // Now close them up again so we can process the next leaf layer type.  Need to use closePeers = true here
            // because we may have opened layers in peer runtimes while opening types in this runtime that also live
            // in those layers.
            leafLayer.markClosed(true, true);
         }
         finally {
            releaseDynLock(false);
         }
      }
      initializingTypeIndexes = false;
      /*
       * For a clean build, this helps us make sure we found all of the leafLayerNames properly
      for (Layer layer:inactiveLayers) {
         if (!layer.layerTypesStarted)
            System.out.println("*** Warning - did not initialize layer type index for layer: " + layer);
      }
      */
      // Clear these out so we don't keep re-initing the same layers over and over again.
      leafLayerNames = new LinkedHashSet<String>();
   }

   public void addLayerToRebuildTypeIndex(String layerName, ArrayList<Layer> layersToRebuild) {
      try {
         acquireDynLock(false);
         Layer layer = getInactiveLayer(layerName, false, false, true, false);
         if (layer == null) {
            System.err.println("*** Unable to index layer: " + layerName);
            return;
         }
         else if (layer.disabled) {
            typeIndex.addDisabledLayer(layerName);
            return;
         }
         else if (layer.excluded) {
            typeIndex.addExcludedLayer(layerName);
         }
         else if (layer.layeredSystem == this) {
            typeIndex.removeExcludedLayer(layerName);
            layersToRebuild.add(layer);
         }
         if (!peerMode && peerSystems != null) {
            for (LayeredSystem peerSys:peerSystems) {
               peerSys.addLayerToRebuildTypeIndex(layerName, layersToRebuild);
            }
         }
      }
      finally {
         releaseDynLock(false);
      }
   }

   public String getTypeIndexFileName(String layerName) {
      return getTypeIndexFileName(getTypeIndexIdent(), layerName);
   }

   public String getTypeIndexFileName(String typeIndexIdent, String layerName) {
      return FileUtil.concat(getTypeIndexDir(typeIndexIdent), Layer.getUniqueUnderscoreName(layerName) + Layer.TYPE_INDEX_SUFFIX);
   }

   public LayerTypeIndex readTypeIndexFile(String layerName) {
      return readTypeIndexFile(getTypeIndexIdent(), layerName);
   }

   public LayerTypeIndex readTypeIndexFile(String typeIndexIdent, String layerName) {
      File typeIndexFile = new File(getTypeIndexFileName(typeIndexIdent, layerName));
      ObjectInputStream ois = null;
      FileInputStream fis = null;
      try {
         ois = new ObjectInputStream(fis = new FileInputStream(typeIndexFile));
         Object res = ois.readObject();
         if (res instanceof LayerTypeIndex) {
            return (LayerTypeIndex) res;
         }
         else
            typeIndexFile.delete();
      }
      catch (InvalidClassException exc) {
         System.out.println("typeIndex - version changed: " + typeIndexFile);
         typeIndexFile.delete();
      }
      catch (IOException exc) {
         System.out.println("*** can't read typeIndex file: " + exc);
      }
      catch (ClassNotFoundException exc) {
         System.out.println("*** can't read typeIndex file: " + exc);
      }
      finally {
         FileUtil.safeClose(ois);
         FileUtil.safeClose(fis);
      }
      return null;
   }

   public void removeTypeIndexFile(String typeIndexIdent, String layerName) {
      File typeIndexFile = new File(getTypeIndexFileName(typeIndexIdent, layerName));
      typeIndexFile.delete();
   }

   private void initBuildDirIndex() {
      addDirToIndex(null, buildDir, null, buildDir);
   }

   void addToPackageIndex(String rootDir, Layer layer, boolean isZip, boolean isSrc, String relDir, String fileName) {
      fileName = FileUtil.removeExtension(fileName);

      HashMap<String,PackageEntry> packageEntry = packageIndex.get(relDir);
      if (packageEntry == null) {
         packageEntry = new HashMap<String,PackageEntry>();
         packageIndex.put(relDir, packageEntry);
         // Also create entries for any root packages - this is just so we can quickly identify a valid component of a package - i.e 'java' should not show 'red' in the IDE since we know it's is a valid package even though there are no classes in 'java' itself
         String parRelDir = FileUtil.getParentPath(relDir);
         while (parRelDir != null) {
            if (packageIndex.get(parRelDir) == null)
               packageIndex.put(parRelDir, new HashMap<String,PackageEntry>());
            parRelDir = FileUtil.getParentPath(parRelDir);
         }
      }
      PackageEntry cur = packageEntry.get(fileName);
      while (cur != null) {
         if (cur.src && layer != null && isSrc && cur.layer != null) {
            boolean sameLayer = cur.layer.getLayerName().equals(layer.getLayerName());
            if (sameLayer) {
               if (layer.activated && !cur.layer.activated)
                  return;
               if (cur.layer.activated && !layer.activated) {
                  cur.layer = layer;
                  return;
               }
            }
         }
         cur = cur.prev;
      }
      // NOTE: we do use this for file based lookups for inner classes with this index so adding those with $ in the name here
      PackageEntry newEnt = new PackageEntry(rootDir, isZip, isSrc, layer);
      newEnt.prev = packageEntry.put(fileName, newEnt);
   }

   /** Note: removes the last package entry added - for the case where add a new model, find that it's file does not exist, then remove it right away.  If someone adds another entry for the same file name we may not remove the right one. */
   void removeFromPackageIndex(String rootDir, Layer layer, boolean isZip, boolean isSrc, String relDir, String fileName) {
      fileName = FileUtil.removeExtension(fileName);

      HashMap<String,PackageEntry> packageEntry = packageIndex.get(relDir);
      if (packageEntry == null) {
         System.err.println("*** Cannot find package dir entry in remove");
         return;
      }
      PackageEntry curEnt = packageEntry.get(fileName);
      if (curEnt == null) {
         System.err.println("*** Cannot find package entry to remove: " + fileName);
         return;
      }
      if (curEnt.prev == null)
         packageEntry.remove(fileName);
      else
         packageEntry.put(fileName, curEnt.prev);
   }

   private void removeAllActivePackageIndexEntries() {
      for (Iterator<HashMap<String,PackageEntry>> packageIterator = packageIndex.values().iterator(); packageIterator.hasNext();) {
         HashMap<String,PackageEntry> packageEntry = packageIterator.next();
         HashMap<String,PackageEntry> toReplace = null;
         ArrayList<String> toRemove = null;
         for (Map.Entry<String,PackageEntry> pentEnt : packageEntry.entrySet()) {
            PackageEntry pent = pentEnt.getValue();
            PackageEntry cur = pent;
            PackageEntry newEnt = null;
            PackageEntry prevEnt = null;
            boolean removedAny = false;
            while (cur != null) {
               if (cur.src && cur.layer != null && cur.layer.activated) {
                  newEnt = cur.prev;
                  removedAny = true;
                  if (prevEnt != null)
                     prevEnt.prev = newEnt;
               }
               else
                  prevEnt = cur;
               cur = cur.prev;
            }
            if (removedAny) {
               if (newEnt == null) {
                  if (toRemove == null)
                     toRemove = new ArrayList<String>();
                  toRemove.add(pentEnt.getKey());
               }
               else {
                  if (toReplace == null)
                     toReplace = new HashMap<String, PackageEntry>();
                  toReplace.put(pentEnt.getKey(), newEnt);
               }
            }
         }
         if (toReplace != null) {
            for (Map.Entry<String,PackageEntry> toRepl:toReplace.entrySet()) {
               packageEntry.put(toRepl.getKey(), toRepl.getValue());
            }
         }
         if (toRemove != null) {
            for (String toRem: toRemove)
               packageEntry.remove(toRem);
         }
      }
   }

   private void addDirToIndex(Layer layer, String dirName, String relDir, String rootDir) {
      File dirFile = new File(dirName);
      String[] files = dirFile.list();
      if (files == null) return;
      for (String fn:files) {
         if (layer != null && layer.excludedFile(fn, null))
            continue;
         if ((isParseable(fn) || fn.endsWith(".class")) && !fn.contains("$"))
            addToPackageIndex(rootDir, layer, false, false, relDir, fn);
         else {
            File subDir = new File(dirFile, fn);
            if (subDir.isDirectory())
               addDirToIndex(layer, FileUtil.concat(dirName, fn), FileUtil.concat(relDir, fn), rootDir);
         }
      }
   }

   void addPathToIndex(Layer layer, String path) {
      String[] layerDirs = StringUtil.split(path, FileUtil.PATH_SEPARATOR_CHAR);

      for (int k = 0; k < layerDirs.length; k++) {
         String layerDirName = layerDirs[k];
         File ldir = new File(layerDirName);
         if (ldir.isDirectory()) {
            addDirToIndex(layer, layerDirName, "", layerDirName);
         }
         else if (ldir.canRead()) {
            try {
               ZipFile z = new ZipFile(layerDirName);
               for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                  ZipEntry ze = e.nextElement();
                  if (!ze.isDirectory()) { // Note: we will include file names with $ as they are used for type name lookups for CFClasses
                     String zipPath = FileUtil.unnormalize(ze.getName());
                     String dirName = FileUtil.getParentPath(zipPath);
                     addToPackageIndex(layerDirName, layer, true, false, dirName, FileUtil.getFileName(zipPath));
                  }
               }
               z.close();
            }
            catch (IOException exc) {
               System.err.println("*** Can't read zip file in classpath");
            }
         }
         else {
            if (layer == null) {
               // Fairly common to have invalid classpath entries but at least provide a verbose message
               if (options.verbose)
                  verbose("No classPath entry: " + ldir);
            }
            else
               layer.error("No classPath entry: " + ldir);
         }
      }
   }

   public void reportMessage(MessageType type, CharSequence... args) {
      StringBuilder sb = new StringBuilder();
      for (CharSequence arg:args) {
         sb.append(arg);
      }
      if (messageHandler != null)
         messageHandler.reportMessage(sb, null, -1, -1, type);
      else {
         if (type == MessageType.Error) {
            String err = sb.toString();
            // So this shows up in the summary of errors
            viewedErrors.add(err);
            System.err.println(err);
         }
         else {
            switch (type) {
               case Warning:
               case Info:
                  break;
               case Debug:
                  if (!options.verbose)
                     return;
                  break;
               case SysDetails:
                  if (!options.sysDetails)
                     return;
                  break;
            }
            System.out.println(sb);
         }
      }
   }

   public void sysDetails(CharSequence... args) {
      reportMessage(MessageType.SysDetails, args);
   }

   public void verbose(CharSequence... args) {
      reportMessage(MessageType.Debug, args);
   }

   public void info(CharSequence... args) {
      reportMessage(MessageType.Info, args);
   }

   public void error(CharSequence... args) {
      reportMessage(MessageType.Error, args);
   }

   public void warning(CharSequence... args) {
      reportMessage(MessageType.Warning, args);
   }

   private List<ProcessBuilder> getProcessBuildersFromCommands(List<BuildCommandHandler> cmds, LayeredSystem sys, Object templateArg, Layer definedInLayer) {
      ArrayList<ProcessBuilder> pbs = new ArrayList<ProcessBuilder>(cmds.size());
      for (BuildCommandHandler handler:cmds) {
         // Commands that specify a layer argument should only be run when building layers which extend that layer unless it's the final build layer
         if (definedInLayer == null || handler.definedInLayer == null || definedInLayer.extendsLayer(handler.definedInLayer) || definedInLayer == buildLayer) {
            String[] args = handler.getExecArgs(sys, templateArg);
            String inputFile = handler.redirInputFile;
            String outputFile = handler.redirOutputFile;
            boolean redirErrors = handler.redirErrors;
            if (args != null) {
               ProcessBuilder pb = new ProcessBuilder(args);
               if (inputFile != null)
                  pb.redirectInput(new File(inputFile));
               if (outputFile != null)
                  pb.redirectOutput(new File(outputFile));
               if (redirErrors)
                  pb.redirectErrorStream(true);
               pbs.add(pb);
            }
         }
      }
      return pbs;
   }

   public enum GenerateCodeStatus {
      Error, NoFilesToCompile, NewCompiledFiles, ChangedCompiledFiles
   }

   // Keeps track of layers which have been generated.  This way we can be sure to sweep up all non-generated layers
   // in the build layer.
   HashSet<Layer> generatedLayers = new HashSet<Layer>();

   public GenerateCodeStatus preInitPeerChangedModels(Layer genLayer, List<String> includeFiles, BuildPhase phase, boolean separateOnly) {
      // Before we start this model's files, make sure any build layers in peer systems have been started.  This happens when there's an
      // intermediate build layer in the peer before the one in the default runtime.
      // NOTE: peerMode = true should still iterate over this list.  We have the changedModelsPreInited flag to prevent recursion.  When we
      // build the peer system, it's the itiator and we need to make sure it coordinates with the other systems.
      if (peerSystems != null && !separateOnly) {
         for (LayeredSystem peer:peerSystems) {
            Layer srcLayer = genLayer;
            Layer peerLayer = getFirstMatchingPeerLayer(peer, srcLayer);
            if (peerLayer != null) {
               setCurrent(peer);

               boolean peerStarted;
               BuildState peerState = peerLayer.buildState;
               if (peerState == null)
                  peerStarted = false;
               else
                  peerStarted = phase == BuildPhase.Prepare ? peerState.prepPhase.preInited : peerState.processPhase.preInited;

               if (!peerStarted) {
                  for (int pl = 0; pl <= peerLayer.getLayerPosition(); pl++) {
                     Layer basePeerLayer = peer.layers.get(pl);
                     if (basePeerLayer.isBuildLayer()) {

                        // Make sure any build layers that precede the genLayer in this system are compiled before we start layers that follow it.  Otherwise, those types
                        // start referring to types in layers which are not compiled in that layer.
                        if (!peer.buildLayersIfNecessary(peerLayer.getLayerPosition(), includeFiles, false, false))
                           return GenerateCodeStatus.Error;

                        BuildState basePeerState = basePeerLayer.buildState;
                        boolean basePeerStarted = false;
                        if (basePeerState == null)
                           basePeerStarted = false;
                        else
                           basePeerStarted = phase == BuildPhase.Prepare ? basePeerState.prepPhase.preInited : basePeerState.processPhase.preInited;

                        if (!basePeerStarted) {
                           GenerateCodeStatus peerStatus = peer.preInitChangedModels(basePeerLayer, includeFiles, phase, false);
                           if (peerStatus == GenerateCodeStatus.Error) {
                              return GenerateCodeStatus.Error;
                           }
                        }
                     }
                  }
               }
               // At least need to start the peer layers so we can resolve types in them during the initSyncProperties method
               if (!peerLayer.started)
                  peer.startLayers(peerLayer);

               setCurrent(this);
            }
         }
      }
      return null;
   }

   private Layer getFirstMatchingPeerLayer(LayeredSystem peer, Layer srcLayer ) {
      Layer peerLayer;
      // Find the first layer in the peer system which matches (if any)
      do {
         peerLayer = peer.getLayerByDirName(srcLayer.getLayerName());
         if (peerLayer == null)
            srcLayer = srcLayer.getPreviousLayer();
      } while (peerLayer == null && srcLayer != null);

      return peerLayer;
   }

   public GenerateCodeStatus initPeerChangedModels(Layer genLayer, List<String> includeFiles, BuildPhase phase, boolean separateOnly) {
      if (peerSystems != null && !separateOnly) {
         for (LayeredSystem peer : peerSystems) {
            Layer peerLayer = getFirstMatchingPeerLayer(peer, genLayer);
            if (peerLayer != null) {
               setCurrent(peer);

               boolean peerStarted;
               BuildState peerState = peerLayer.buildState;
               if (peerState == null)
                  peerStarted = false;
               else
                  peerStarted = phase == BuildPhase.Prepare ? peerState.prepPhase.inited : peerState.processPhase.inited;

               if (!peerStarted) {
                  for (int pl = 0; pl <= peerLayer.getLayerPosition(); pl++) {
                     Layer basePeerLayer = peer.layers.get(pl);
                     if (basePeerLayer.isBuildLayer()) {
                        GenerateCodeStatus peerStatus = peer.initChangedModels(basePeerLayer, includeFiles, phase, separateOnly);
                        if (peerStatus == GenerateCodeStatus.Error) {
                           return GenerateCodeStatus.Error;
                        }
                     }
                  }
               }

               setCurrent(this);
            }
         }
      }
      return null;
   }

   public GenerateCodeStatus startPeerChangedModels(Layer genLayer, List<String> includeFiles, BuildPhase phase, boolean separateOnly) {
      if (peerSystems != null && !separateOnly) {
         for (LayeredSystem peer : peerSystems) {
            Layer peerLayer = getFirstMatchingPeerLayer(peer, genLayer);
            if (peerLayer != null) {
               setCurrent(peer);

               boolean peerStarted;
               BuildState peerState = peerLayer.buildState;
               if (peerState == null)
                  peerStarted = false;
               else
                  peerStarted = phase == BuildPhase.Prepare ? peerState.prepPhase.started : peerState.processPhase.started;

               if (!peerStarted) {
                  for (int pl = 0; pl <= peerLayer.getLayerPosition(); pl++) {
                     Layer basePeerLayer = peer.layers.get(pl);
                     if (basePeerLayer.isBuildLayer()) {
                        GenerateCodeStatus peerStatus = peer.startChangedModels(basePeerLayer, includeFiles, phase, separateOnly);
                        if (peerStatus == GenerateCodeStatus.Error) {
                           return GenerateCodeStatus.Error;
                        }
                     }
                  }
               }

               setCurrent(this);
            }
         }
      }
      return null;
   }

   private boolean modifiedByLayers(ArrayList<Layer> layers, Layer otherLayer) {
      for (Layer layer:layers)
         if (layer.modifiedByLayer(otherLayer))
            return true;
      return false;
   }

   private boolean systemDetailsDisplayed = false;

   /** Takes a srcEntry - a src file in a layer and parses the file and starts the component as needed.  The defaultReason is used to debug why this model is started.  Ordinarily this is because
    * a dependent file has changed, or the file was modified but if neither of these cases is true, the caller can provide a more specific reason. */
   private Object initModelForBuild(SrcEntry toGenEnt, Layer genLayer, boolean incrCompile, BuildState bd, BuildPhase phase, String defaultReason) {
      Object modelObj;
      IFileProcessor proc = getFileProcessorForSrcEnt(toGenEnt, phase, false);
      if (proc == null)
         return null; // possibly not processed in this phase
      if (proc.getProducesTypes()) {
         // We may have already parsed and initialized this component from a reference.
         TypeDeclaration cachedType = getCachedTypeDeclaration(proc.getPrependLayerPackage() ? toGenEnt.getTypeName() : toGenEnt.getRelTypeName(), toGenEnt.layer.getNextLayer(), null, false, false, null, false);
         // Make sure it's from the right layer
         if (cachedType != null && cachedType.getLayer() == toGenEnt.layer)
            modelObj = cachedType;
         else
            modelObj = null;
      }
      else
         modelObj = null;

      if (modelObj == null) {
         ILanguageModel langModel = getLanguageModel(toGenEnt);
         // Checking the model cache again for those cases where there are no types involved and the model was cloned from another runtime (faster than reparsing it again)
         modelObj = langModel;
         if (modelObj instanceof JavaModel) {
            JavaModel tempModel = (JavaModel) modelObj;
            if (tempModel.getLayer() != toGenEnt.layer)
               System.err.println("*** Mismatching layers in started model");
         }
         if (modelObj == null) {
            if (options.verbose) {
               String procReason = "";
               if (incrCompile) {
                  SrcEntry depEnt = bd.dependentFilesChanged.get(toGenEnt);
                  procReason = depEnt == null ? (bd.modifiedFiles.contains(toGenEnt) ? ": source file changed" : defaultReason) : ": dependent file changed: " + depEnt;
               }
               verbose("Preparing " + toGenEnt + procReason + ", runtime: " + getRuntimeName());
            }

            modelObj = parseSrcType(toGenEnt, genLayer.getNextLayer(), false, true);
            //modelObj = parseSrcType(toGenEnt, null);
         }
         else {
            if (!langModel.isInitialized())
               ParseUtil.initComponent(langModel);
            if (!langModel.isAdded())
               addNewModel(langModel, genLayer.getNextLayer(), null, null, langModel instanceof JavaModel && ((JavaModel) langModel).isLayerModel, false);

            verbose("Preparing from model cache " + toGenEnt + ", runtime: " + getRuntimeName());
         }
      }
      // Parsed it and there was an error
      else if (modelObj == INVALID_TYPE_DECLARATION_SENTINEL || modelObj == IFileProcessor.FILE_OVERRIDDEN_SENTINEL)
         return modelObj;
      else {
         TypeDeclaration modelType = (TypeDeclaration) modelObj;

         JavaModel model = modelType.getJavaModel();
         model.dependenciesChanged();
         modelObj = model;
         // If we are not starting anything, we won't be resolving the errors
         if (options.verbose && !modelType.isStarted()) {
            String modelTypeName = modelType.getFullTypeName();
            // This happens now that we build previous layers, put their classes in so they are there for the next layer
            //if (!model.getLayer().annotationLayer && !genLayer.layerUniqueName.equals("sc") && getClass(modelTypeName) != null)
            //   System.err.println("*** Warning - generated type: " + modelTypeName + " exists in the system classpath ");
            verbose("Preparing from cache " + toGenEnt);
         }
      }

      return modelObj;
   }

   private Object startModelForBuild(Object modelObj, SrcEntry toGenEnt, Layer genLayer, boolean incrCompile, BuildState bd, BuildPhase phase) {
      IFileProcessor proc = getFileProcessorForSrcEnt(toGenEnt, phase, false);
      if (proc == null)
         return null; // possibly not processed in this phase
      if (!toGenEnt.layer.skipStart(toGenEnt.baseFileName, toGenEnt.relFileName)) {
         PerfMon.start("startModel", false);
         if (options.verbose && modelObj instanceof ILifecycle) {
            ILifecycle modelComp = (ILifecycle) modelObj;
            if (!modelComp.isStarted()) {
               String action = "Starting" + (modelComp.isValidated() ? "" : " and validating");
               verbose(action + ": " + toGenEnt);
            }
         }
         ParseUtil.startComponent(modelObj);
         ParseUtil.validateComponent(modelObj);
         PerfMon.end("startModel");
      }

      if (modelObj instanceof JavaModel) {
         JavaModel jmodel = (JavaModel) modelObj;
         if (incrCompile)
            jmodel.readReverseDeps(genLayer);
         else {
            TypeDeclaration prevModelType = getCachedTypeDeclaration(proc.getPrependLayerPackage() ? toGenEnt.getTypeName() : toGenEnt.getRelTypeName(), toGenEnt.layer, null, false, false, null, false);
            if (prevModelType != null && prevModelType != INVALID_TYPE_DECLARATION_SENTINEL) {
               JavaModel prevModel = prevModelType.getJavaModel();
               ParseUtil.startComponent(prevModel);
               ParseUtil.validateComponent(prevModel);
               if (jmodel.reverseDeps == null)
                  jmodel.reverseDeps = prevModel.reverseDeps;
               else
                  jmodel.reverseDeps.addDeps(prevModel.reverseDeps);
            }
            // When building all files, remove the old reverse deps and start out from scratch. -
            // TODO: not sure if this is necessary more.  We do not read the reverse deps in read reverse deps if its buildAllFiles.
            // It breaks in layered builds because we end up cleaning out the reverse deps after the type has been started in the second layer
            // If we silently clean out the reverse deps when they are invalid, is it a problem if we continue to make properties bindable even when the
            // reference goes away?  It seems like missing mainInit, etc. types will be culled silently as well.
            //else
            //   jmodel.cleanReverseDeps(genLayer);
         }
      }
      return modelObj;
   }

   public boolean restartAllOnFirstChange(Layer genLayer) {
      if (!options.buildAllFiles && !genLayer.buildAllFiles && options.buildAllPerLayer) {
         // everything upstream gets rebuilt now
         options.buildAllFiles = true;
         return true;
      }
      return false;
   }

   public GenerateCodeStatus preInitChangedModels(Layer genLayer, List<String> includeFiles, BuildPhase phase, boolean separateOnly) {
      boolean skipBuild = !genLayer.buildSeparate && separateOnly;

      BuildState bd;
      if ((bd = genLayer.buildState) == null) {
         bd = new BuildState();
         genLayer.buildState = bd;
         if (genLayer.lastBuildState != null)
            genLayer.buildState.errorFiles = genLayer.lastBuildState.errorFiles;
      }

      BuildStepFlags flags = phase == BuildPhase.Prepare ? bd.prepPhase : bd.processPhase;
      if (flags.preInited)
         return GenerateCodeStatus.NewCompiledFiles;

      if (!separateOnly) {
         if (phase == BuildPhase.Process)
            bd.processPhase.preInited = true;
         else
            bd.prepPhase.preInited = true;
      }

      if (preInitPeerChangedModels(genLayer, includeFiles, phase, separateOnly) == GenerateCodeStatus.Error)
         return GenerateCodeStatus.Error;

      setCurrent(this);

      if (phase == BuildPhase.Prepare && !skipBuild) {
         genLayer.updateBuildInProgress(true);
      }
      else
         allTypesProcessed = false;

      startLayers(genLayer);

      // Wait till after all of the layers have been started, now we can compute the
      // system classpaths and sync the runtime libraries
      classPath = getClassPathForLayer(genLayer, true, buildClassesDir, true);
      userClassPath = buildUserClassPath(classPath);

      if (genLayer == buildLayer && phase == BuildPhase.Process) {
         if (runtimeLibsDir != null) {
            syncRuntimeLibraries(runtimeLibsDir);
         }
         if (runtimeSrcDir != null) {
            syncRuntimeSrc(runtimeSrcDir);
         }
         if (strataCodeLibsDir != null) {
            syncStrataCodeLibraries(strataCodeLibsDir);
         }
      }

      List<BuildCommandHandler> cmds = null;

      if (!skipBuild) {

         // Only do this the first time - if we are building just a separate layer, don't remove all of the generated files!
         if (genLayer.getBuildAllFiles() && phase == BuildPhase.Process && !systemCompiled)
            genLayer.cleanBuildSrcIndex();

         Options options = this.options;
         cmds = preBuildCommands.get(phase);
         if (cmds != null) {
            // TODO: should the genLayer argument to the template here be the layered system instead so it is consistent with the run commands?
            List<ProcessBuilder> pbs = getProcessBuildersFromCommands(cmds, this, genLayer, genLayer);
            if (!LayerUtil.execCommands(pbs, genLayer.buildDir, options.info))
               return GenerateCodeStatus.Error;
            else
               refreshLayerIndex();
         }
      }

      if (!needsPhase(phase)) {
         verbose("Skipping preInitChangedModels " + phase + " phase");
         return GenerateCodeStatus.NoFilesToCompile;
      }
      // Nothing to generate or compile
      if (genLayer == null)
         return GenerateCodeStatus.NoFilesToCompile;

      ArrayList<Layer> startedLayers = bd.startedLayers = new ArrayList<Layer>();
      if (!separateOnly)
         startedLayers.add(genLayer);

      if (!genLayer.buildSeparate) {

         if (separateOnly)
            return GenerateCodeStatus.NoFilesToCompile;

         // Add all of the top level layer src directories with the dependent layers first.  We'll process all
         // definitions in the build layer, then finally end up at the first layer.  Each layer looks at all of the
         // files but before it generates the code for it, it checks to see if we've already generated a model for
         // that path name.  When processing a base layer, we'll only process the files which have not been overridden.
         for (int i = genLayer.layerPosition; i >= 0; i--) {
            Layer layer = layers.get(i);

            // If this is the commonBuildDir and that is enabled, we build all layers even those already built so the common layer accumulates all of the files
            // For the final buildLayer, we include all files to be sure we catch everything.   For any buildLayers that are not separate, we automatically include any layers that
            // it explicitly depends upon.  This is not guaranteed to pick up all dependencies... we could really include all layers here and suffer lots of extra files in buildDir,
            // or look in BuildInfo and choose layers from that, probably the core layers this layer was compiled with.
            // For now, this seems like it will work since almost all dependencies are in extends anyway.
            // TODO: add dependencies even if they layer has already been compiled.   If not, we never process files that have been
            // modified indirectly by adding a new typeGroupMember for example.  Also, if a file gets generated in the previous layer
            // we still need to process it in this layer at least to remove a stale copy.   As long as the transformed/save model stuff
            // works correctly, we'll avoid the duplicate files when they are identical.
            //
            // If the layer is buildSeparate, we do not include it even for common build dirs... it will depend in that case on the lang files and
            // we do not want that part of the deployed project.
            // Using the "modifiedByLayer" to detect any dependency on that layer, whether direct or indirect - i.e. we might be example.todo.data which extends html.schtml but not jetty.schtml, but if it's in front of us in the stack, we need to build it because our compiled code will depend on it.
            if (!layer.buildSeparate && ((options.useCommonBuildDir && genLayer == commonBuildLayer) || (layer == buildLayer || /* layer == lastCompiledLayer || */ genLayer == layer || modifiedByLayers(startedLayers, layer)) || ((genLayer == buildLayer /*|| genLayer == lastCompiledLayer*/) /*&& (options.buildAllLayers || !bd.generatedLayers.contains(layer)) */))) {
               // Do we generate a layer more than once?   The first time we generate it, we add it to generatedLayers.
               // If this is the final build layer or it's the commond build layer we need to generate all of the files
               // again for accuracy.  It's possible that a type extends a type which was modified in a layer after the
               // type itself was built.   Ordinarily, the modified type will have a compatible class so does it matter if
               // we regenerate?  One case it breaks is for the schtml files, converting to an object when the extends
               // Element has changed.  We may need to regenerate the body of the extending element to account for the new
               // base element.
               // TODO: optimization.  When we see we are processing a layer the second time, it should effectively turn into
               // an incremental build.  Only pick up types where a dependency has changed since the previous build layer.
               if (!generatedLayers.contains(layer) || genLayer == buildLayer || genLayer == commonBuildLayer) {
                  if (!separateOnly) {
                     if (!startedLayers.contains(layer))
                        startedLayers.add(layer);
                  }
                  layer.addBuildDirs(bd);

                  // For the common build dir case, only consider the guy generated once we've built the common build layer
                  if (phase == BuildPhase.Process)
                     generatedLayers.add(layer);
               }
            }
         }
      }
      else {
         startedLayers.add(genLayer);
         SrcEntry layerSrcEnt = new SrcEntry(genLayer, genLayer.layerPathName, "", "");
         bd.addSrcEntry(-1, layerSrcEnt);
      }

      if (options.verbose && phase == BuildPhase.Process && !separateOnly) {

         if (!systemDetailsDisplayed) {
            systemDetailsDisplayed = true;

            verbose("Config - " + getRuntimeName() + " runtime ----");
            verbose("Languages: " + Language.languages);
            StringBuilder fileTypes = new StringBuilder();
            fileTypes.append("File types: ");

            boolean firstExt = true;
            for (Map.Entry<String,IFileProcessor[]> procEnt:fileProcessors.entrySet()) {
               String ext = procEnt.getKey();
               IFileProcessor[] procList = procEnt.getValue();
               if (!firstExt && !options.sysDetails)
                  fileTypes.append(", ");
               firstExt = false;
               fileTypes.append("" + ext + " (");
               boolean first = true;
               for (IFileProcessor ifp:procList) {
                  if (!first) fileTypes.append(", ");
                  if (options.sysDetails)
                     fileTypes.append(ifp);
                  else {
                     fileTypes.append(ifp.getDefinedInLayer());
                     String[] srcPathTypes = ifp.getSrcPathTypes();
                     if (srcPathTypes != null) {
                        fileTypes.append("[");
                        fileTypes.append(StringUtil.arrayToString(srcPathTypes));
                        fileTypes.append("]");
                     }
                  }
                  first = false;
               }
               fileTypes.append(")");
               if (options.sysDetails)
                  fileTypes.append(FileUtil.LINE_SEPARATOR);
            }
            for (Map.Entry<Pattern,IFileProcessor> procEnt:filePatterns.entrySet()) {
               Pattern pattern = procEnt.getKey();
               IFileProcessor proc = procEnt.getValue();
               fileTypes.append("   pattern: " + pattern + ":" + proc + FileUtil.LINE_SEPARATOR);
            }

            verbose(fileTypes);
            verbose("Tag packages: " + tagPackageList);
            verbose("----");
         }

         verbose("Finding changes: " + getRuntimeName() + " runtime -" + (genLayer == buildLayer ? " sys build layer: " : " pre build layer: ") + genLayer + " including: " + startedLayers);
      }

      // Because we pull in dependent files into the 'toGenerate' list make sure we only add each SrcEntry once so we don't try
      // and transform it more than once.
      HashSet<SrcEntry> allGenerated = new HashSet<SrcEntry>();
      HashSet<String> allGeneratedTypes = new HashSet<String>();

      // For each directory or src file we need to look at.  Top level directories are put into this list before we begin.
      // As we find a sub-directory we insert it into the list from inside the loop.
      // This loop will find all of the files that have changed across all srcDirs.  It builds a SrcDirEnt for each
      // directory containing source files, and for each of those a 'toGenerate' list - the models in that file that
      // have changed, or depend on a file which has changed (for incremental builds).
      for (int i = 0; i < bd.srcEnts.size(); i++) {
         SrcEntry srcEnt = bd.srcEnts.get(i);
         String srcDirName = srcEnt.absFileName;
         String srcPath = srcEnt.relFileName;
         LinkedHashSet<SrcEntry> toGenerate = new LinkedHashSet<SrcEntry>();

         File srcDir = new File(srcDirName);
         File depFile = new File(genLayer.buildSrcDir, FileUtil.concat(srcEnt.layer.getPackagePath(), srcPath, srcEnt.layer.getLayerName().replace('.', '-') + "-" + phase.getDependenciesFile()));
         boolean depsChanged = false;

         DependencyFile deps;

         long lastBuildTime;

         //boolean incrCompile = !options.buildAllFiles || srcEnt.layer.compiled;
         //boolean layeredCompile = srcEnt.layer.compiled;
         //boolean incrCompile = !genLayer.getBuildAllFiles();
         Layer prevSrcEntBuildLayer = srcEnt.layer.getNextBuildLayer();
         boolean incrCompile = LayerUtil.doIncrCompileForFile(srcEnt);
         boolean layeredCompile = false;

         boolean depFileExists = depFile.exists();

         // No dependencies or the layer def file changed - just add all of the files in this directory for generation
         if (!incrCompile || !depFileExists || (lastBuildTime = depFile.lastModified()) == 0 ||
                 (lastBuildTime < srcEnt.layer.getLastModifiedTime())) {
            addAllFiles(srcEnt.layer, toGenerate, allGenerated, allGeneratedTypes, srcDir, srcDirName, srcPath, phase, bd);
            // Do a clean build the first time.  The second and subsequent times we need to load the existing deps file because we do not stop and restart all components even when build all is true.
            if (!systemCompiled || !depFileExists)
               deps = DependencyFile.create();
            else {
               deps = readDependencies(depFile, srcEnt);
               if (deps == null)
                  deps = DependencyFile.create();
            }
            depsChanged = true;
         }
         else {
            deps = readDependencies(depFile, srcEnt);

            // Failed to read dependencies
            if (deps == null) {
               addAllFiles(srcEnt.layer, toGenerate, allGenerated, allGeneratedTypes, srcDir, srcDirName, srcPath, phase, bd);
               deps = new DependencyFile();
               deps.depList = new ArrayList<DependencyEntry>();
               depsChanged = true;
            }
            else {
               boolean dirChanged = srcDir.lastModified() > lastBuildTime;

               // Look for any newly added/removed files - those that exist in the directory and are not in the
               // deps file.  We do this when either this folder's LMT has changed or if any folder with the same
               // relative path behind us in the layer stack.  If you add or remove a file in a previous layer
               // that can affect which files in this layer are visible in the deps.
               if (dirChanged || changedDirectoryIndex.contains(srcPath)) {
                  if (dirChanged)
                     changedDirectoryIndex.add(srcPath);

                  for (int d = 0; d < deps.depList.size(); d++) {
                     DependencyEntry ent = deps.depList.get(d);
                     File entFile = new File(srcDir,ent.getEntryFileName());
                     if (!entFile.exists()) {
                        warning("source file: " + ent.fileName + " was removed.");
                        deps.removeDependencies(d);
                        depsChanged = true;
                        d--;
                     }
                  }

                  String [] allFiles = srcDir.list();
                  for (String newFile:allFiles) {
                     String newPath = FileUtil.concat(srcDirName, newFile);
                     IFileProcessor proc = getFileProcessorForFileName(newPath, srcEnt.layer, phase);
                     if (proc == null)
                        continue;
                     // If we don't have a record of the file, it's a new file
                     if (deps.getDependencies(newFile) == null) {
                        if (new File(newFile).isDirectory()) {
                           // Pick up new directories that were added
                           SrcEntry newSrcEnt = new SrcEntry(srcEnt.layer, srcDirName, srcPath, newFile, proc == null || proc.getPrependLayerPackage());
                           bd.addSrcEntry(i+1, newSrcEnt);
                           deps.addDirectory(newFile);
                           depsChanged = true;
                        }
                        else if (needsGeneration(newPath, srcEnt.layer, phase) &&
                                !srcEnt.layer.layerBaseName.equals(newFile) && !srcEnt.layer.excludedFile(newFile, srcDirName)) {
                           SrcEntry newSrcEnt = new SrcEntry(srcEnt.layer, srcDirName, srcPath, newFile, proc == null || proc.getPrependLayerPackage());
                           if (!allGenerated.contains(newSrcEnt)) {
                              addToGenerateList(toGenerate, newSrcEnt, newSrcEnt.getTypeName());
                              allGenerated.add(newSrcEnt);
                              allGeneratedTypes.add(newSrcEnt.getTypeName());
                              clearTransformedInLayer(newSrcEnt);
                           }
                        }
                     }
                  }
               }

               // Now add any files in the deps file which need to be regenerated or recompiled.  At this point we also add other
               // directories.
               for (DependencyEntry ent: deps.depList) {
                  String srcFileName = ent.getEntryFileName();

                  // We have a directory - add that to be processed next
                  if (ent.isDirectory) {
                     SrcEntry newSrcEnt = new SrcEntry(srcEnt.layer, srcDirName, srcPath, srcFileName);
                     bd.addSrcEntry(i+1, newSrcEnt);
                  }
                  else {
                     boolean needsGenerate = ent.isError;
                     boolean needsCompile = false;

                     if (needsGenerate && traceNeedsGenerate)
                        verbose("Generating: " + srcFileName + " because of an error last compile.");

                     File srcFile = new File(srcDir, srcFileName);
                     String absSrcFileName = FileUtil.concat(srcDirName, srcFileName);
                     IFileProcessor proc = getFileProcessorForFileName(absSrcFileName, srcEnt.layer, null); // Either phase - just using this to determine the layer package
                     if (proc == null) {
                        warning("No processor for file in dependencies file: " + srcFileName);
                        continue;
                     }
                     long srcLastModified = srcFile.lastModified();
                     long genFileLastModified = 0;
                     boolean isModified = false;

                     ArrayList<SrcEntry> missingGenFiles = null;

                     // We need to regenerate if any of the generated files are missing or earlier than the source
                     // We need to recompile if the .class file is earlier than the source
                     if (ent.genFileNames != null && ent.genFileNames.size() > 0) {

                        for (int gf = 0; gf < ent.genFileNames.size(); gf++) {
                           String genRelFileName = ent.getGeneratedFileName(gf);
                           File genFile = new File(genLayer.buildSrcDir, genRelFileName);
                           boolean genFileExists = genFile.exists();

                           // Gets set to true if we are testing the inherited file for LMT.  Since we physically remove the .java file
                           // when we inherit it, we leave around the .inh file so we can track whether or not we need to regenerate the
                           // file.
                           boolean inherited = false;
                           if (!genFileExists) {
                              genFile = new File(FileUtil.replaceExtension(genFile.getPath(), Language.INHERIT_SUFFIX));
                              genFileExists = genFile.exists();
                              if (genFileExists)
                                 inherited = true;

                              // Look for the file in any previous build layers.  In this case, we do not have the .inh file but for js/types files, we should rebuild anyway cause of the Java file dependency
                              if (!genFileExists) {
                                 for (Layer prevLayer = genLayer.getPreviousBuildLayer(); prevLayer != null; prevLayer = prevLayer.getPreviousBuildLayer()) {
                                    genFile = new File(prevLayer.buildSrcDir, genRelFileName);
                                    genFileExists = genFile.exists();
                                    if (genFileExists) {
                                       inherited = true;
                                       break;
                                    }
                                 }
                              }
                           }

                           if (!genFileExists) {
                              genFile = new File(genLayer.buildDir, genRelFileName);
                              genFileExists = genFile.exists();

                              // TODO: compare the canonical name to catch case insensitivity problems in the build

                              // Also need to check for the .inh files underneath the buildDir - we do this now for .js files
                              if (!genFileExists) {
                                 genFile = new File(FileUtil.replaceExtension(genFile.getPath(), Language.INHERIT_SUFFIX));
                                 genFileExists = genFile.exists();
                                 if (genFileExists)
                                    inherited = true;
                              }

                              if (!genFileExists) {
                                 genFile = new File(genLayer.buildClassesDir, genRelFileName);
                                 genFileExists = genFile.exists();
                              }
                           }

                           // Mark these entries in use so they don't get culled even if no re-generation is needed//
                           // If the table itself doesn't know about this file, regenerate so we re-add the table entry
                           // If this name is inherited, it's not in this layer's index but previously has already been marked as in use when we built that layer
                           boolean foundInIndex = inherited || genLayer.markBuildSrcFileInUse(genFile, genRelFileName);

                           if (options.generateAllFiles) {
                              needsGenerate = true;
                           }

                           if (!needsGenerate && (!foundInIndex || !genFileExists || srcLastModified > (genFileLastModified = genFile.lastModified()))) {
                              if (traceNeedsGenerate) {
                                 String reason;
                                 if (!foundInIndex)
                                    reason = " because generated file is not in the index";
                                 else if (!genFileExists)
                                    reason = " because generated file: " + genFile.getPath() + " is missing";
                                 else if (srcLastModified > genFileLastModified)
                                    reason = " because source file changed";
                                 else
                                    reason = " ???";
                                 verbose("Generating: " + srcFileName + reason);
                              }
                              needsGenerate = true;
                              inherited = false;
                              isModified = true;
                           }

                           // When we have layered builds our generated file needs to be after the layered generated file to be considered up to date.
                           if (!needsGenerate && layeredCompile && genFileExists && genFileLastModified != 0) {
                              for (Layer prevLayer = genLayer.getPreviousBuildLayer(); prevLayer != null; prevLayer = prevLayer.getPreviousBuildLayer()) {
                                 File prevGenFile = new File(prevLayer.buildSrcDir, genRelFileName);
                                 boolean prevGenFileExists = prevGenFile.exists();
                                 if (prevGenFileExists && prevGenFile.lastModified() > genFileLastModified) {
                                    needsGenerate = true;
                                 }
                              }
                           }

                           // Look at the processor for the generated file - for .js files, do not look for the class file but do for Java
                           IFileProcessor genProc = getFileProcessorForFileName(genRelFileName, genLayer, phase);

                           if (genProc != null && genProc.getNeedsCompile()) {
                              if (!inherited) {
                                 // The genFileLastModified has to be the newer of the srcFile or the genFile (which gets generated from the source file as source.
                                 if (genFileLastModified == 0)
                                    genFileLastModified = genFile.lastModified();
                                 if (genFileLastModified < srcLastModified)
                                    genFileLastModified = srcLastModified;
                                 // Class file is buildDir + relDirPath + srcFileName with ".class" on it
                                 File classFile = new File(FileUtil.concat(genLayer.getBuildClassesDir(), FileUtil.replaceExtension(genRelFileName, "class")));
                                 if (!needsGenerate && (!classFile.exists() || classFile.lastModified() < genFileLastModified)) {
                                    if (missingGenFiles == null)
                                       missingGenFiles = new ArrayList<SrcEntry>();
                                    missingGenFiles.add(new SrcEntry(srcEnt.layer, FileUtil.concat(genLayer.buildSrcDir, genRelFileName), genRelFileName));
                                 }
                              }
                              // Need to ensure there is a generated source file in a previous layer with this name.  If someone removes a build dir in the previous layer
                              // to recompile this file.
                              else {
                                 if (genLayer.getPrevSrcFileIndex(genRelFileName) == null) {
                                    verbose("Missing inherited file: " + genRelFileName + " recompiling");
                                    needsGenerate = true;
                                    inherited = false;
                                    genFile.delete();
                                 }
                              }
                           }
                        }
                     }
                     else {
                        if (FileUtil.getExtension(ent.fileName).equals("java")) {
                           File classFile = new File(FileUtil.concat(genLayer.getBuildClassesDir(), FileUtil.concat(srcEnt.layer.getPackagePath(), FileUtil.concat(srcPath, FileUtil.replaceExtension(srcFileName, "class")))));
                           // When there are no generated files, it's the dynamic type case with no dynamic stub.  Here we need to check if there is a class file and just remove it so it does not mess things up
                           if (classFile.exists()) {
                              FileUtil.removeFileOrDirectory(classFile);
                              /*
                               This strategy does not work because the file may go from having no generated files
                               to having generated files.  We need to update the dependencies anyway so re-gen
                               and compile if necessary.
                              if (srcEnt.layer.copyPlainJavaFiles)
                                 needsCompile = true;
                              // Otherwise, we compile the java file from the source location in its layer
                              else {
                                 SrcEntry newCompEnt = new SrcEntry(ent.layer, FileUtil.concat(ent.layer.layerPathName,
                                         srcPath), srcPath, srcFileName);
                                 toCompile.add(newCompEnt);
                              }
                              */
                              /*
                              if (traceNeedsGenerate) {
                                 System.out.println("Generating: " + srcFileName + " because class file is out of date");
                              }
                              needsGenerate = true;
                              */
                           }
                           if (srcLastModified > lastBuildTime) {
                              if (traceNeedsGenerate) {
                                 verbose("Generating: " + srcFileName + " because src changed since last build");
                              }
                              needsGenerate = true; // Need to reparse to update the dependencies - if copy is set, we should have a generated filei
                              isModified = true;
                           }
                        }
                     }

                     SrcEntry depSrcEnt = null;
                     if (!needsGenerate && ent.srcEntries != null && ent.genFileNames != null && ent.genFileNames.size() > 0) {
                        // For each file the current srcEnt depends upon
                        for (SrcEntry otherFileName:ent.srcEntries) {

                           // We are building all files in this build layer but inheriting a build of this file from a previous build layer.
                           // If any of the src files which we depend upon is after the previous build layer we need to rebuild this file here.
                           if (genLayer.buildAllFiles) {
                              if (otherFileName.layer.getLayerPosition() > prevSrcEntBuildLayer.getLayerPosition()) {
                                 needsGenerate = true;
                                 break;
                              }
                           }

                           // We keep track of all types which have a file that is being generated and if our dependent file has a type which is being generated
                           // we need to regenerate it.  Because we process layers top-down, we'll pick up the case where we're regenerating a file this type depends on - possibly making it
                           // dynamic in the process which would affect the code for this type.
                           if (allGeneratedTypes.contains(otherFileName.getTypeName())) {
                              if (traceNeedsGenerate) {
                                 verbose("Generating: " + srcFileName + " because dependent type: " + otherFileName.getTypeName() + " needs generation");
                              }
                              needsGenerate = true;
                              break;
                           }
                           else {
                              // If this file is more recent than our last build time, regenerate
                              File otherFile = new File(otherFileName.absFileName);
                              long otherFileLastModified = otherFile.lastModified();
                              // genFile is either the generate file's last modified time or the class file if
                              // we are compiling against the source directly.  Also may need to just pick up
                              // new dependencies.
                              if (otherFileLastModified > genFileLastModified || otherFileLastModified > lastBuildTime) {
                                 if (traceNeedsGenerate) {
                                    verbose("Generating: " + srcFileName + " because: " + otherFileName + " is more recent than class or build");
                                 }
                                 needsGenerate = true;
                                 depSrcEnt = otherFileName;
                                 break;
                              }
                           }
                        }
                     }
                     if (!needsGenerate && srcEnt.layer.compiled) {
                        ILanguageModel cachedModel = modelIndex.get(absSrcFileName);
                        if (cachedModel != null && cachedModel instanceof JavaModel && ((JavaModel) cachedModel).getDependenciesChanged(genLayer, incrCompile ? changedTypes : null, false)) {
                           needsGenerate = true;
                           if (traceNeedsGenerate) {
                              verbose("Generating: " + srcFileName + " - dependent type was overridden since the previous layered build");
                           }
                        }
                     }
                     SrcEntry newSrcEnt = new SrcEntry(ent.layer, srcDirName, srcPath, srcFileName, proc == null || proc.getPrependLayerPackage());
                     String srcTypeName = newSrcEnt.getTypeName();

                     if (!needsGenerate && bd.errorFiles.contains(newSrcEnt)) {
                        verbose("Recompiling: " + newSrcEnt + " because it had an error last time");
                        needsGenerate = true;
                     }
                     // Need to keep track of the first unchanged srcEnty for each type.  Later, if we decide we need
                     // to generate something, it needs to be the most specific version of that type.
                     if (!needsGenerate) {
                        if (bd.unchangedFiles.get(srcTypeName) == null)
                           bd.unchangedFiles.put(srcTypeName, newSrcEnt);
                     }
                     if (needsGenerate) {
                        // If we have the option set so that on the first change
                        if (restartAllOnFirstChange(genLayer)) {
                           if (options.verbose)
                              verbose("Detected changed file: " + srcFileName + " with build all per layer set - rebuilding all now.");
                           return preInitChangedModels(genLayer, includeFiles, phase, separateOnly);
                        }
                        SrcEntry prevEnt;
                        if ((prevEnt = bd.unchangedFiles.get(srcTypeName)) != null) {
                           newSrcEnt = prevEnt;
                        }
                        if (!allGenerated.contains(newSrcEnt)) {
                           addToGenerateList(toGenerate, newSrcEnt, srcTypeName);
                           clearTransformedInLayer(newSrcEnt);
                           allGenerated.add(newSrcEnt);
                           allGeneratedTypes.add(newSrcEnt.getTypeName());
                        }

                        if (isModified)
                           bd.modifiedFiles.add(newSrcEnt);
                        else if (depSrcEnt != null)
                           bd.dependentFilesChanged.put(newSrcEnt, depSrcEnt);
                     }
                     else if (needsCompile) {
                        SrcEntry newCompEnt = new SrcEntry(ent.layer, FileUtil.concat(genLayer.buildSrcDir,
                                FileUtil.concat(srcEnt.layer.getPackagePath(), srcPath)), srcPath, srcFileName);
                        bd.toCompile.add(newCompEnt);
                     }

                     // If we are generating this type don't add any missing gen-files to the compile list since we might be removing those files during the generate phase.
                     if (!needsGenerate && missingGenFiles != null)
                        bd.toCompile.addAll(missingGenFiles);

                     // If we've already loaded a version of this type in a previous layer, even if it's not changed we still need to
                     // start the overriding type so that it can update the previous types for the next build.
                     TypeDeclaration cachedType;
                     if (!needsGenerate && (cachedType = getCachedTypeDeclaration(srcTypeName, null, null, false, true, null, false)) != null) {
                        if (cachedType != INVALID_TYPE_DECLARATION_SENTINEL && cachedType.layer != null && cachedType.layer.getLayerPosition() < newSrcEnt.layer.getLayerPosition()) {
                           SrcEntry prevEnt;
                           if ((prevEnt = bd.unchangedFiles.get(srcTypeName)) != null) {
                              newSrcEnt = prevEnt;
                           }
                           if (!allGenerated.contains(newSrcEnt)) {
                              addToGenerateList(toGenerate, newSrcEnt, srcTypeName);
                              clearTransformedInLayer(newSrcEnt);
                              allGenerated.add(newSrcEnt);
                              allGeneratedTypes.add(newSrcEnt.getTypeName());
                           }
                        }
                     }
                     /*
                     else {
                        // Mark this guy via its typeName that it is up-to-date so that we don't parse it in a
                        // subsequent layer.
                        processedTypeNames.add(SrcEntry.getTypeNameFromRelDirAndBaseName(FileUtil.concat(ent.layer.getPackagePath(), srcPath), srcFileName));
                     }
                     */
                  }
               }
            }
         }

         // Now, loop over each file that was changed in this directory.
         for (SrcEntry toGenEnt:toGenerate) {
            File srcFile = new File(toGenEnt.absFileName);
            if (srcFile.isDirectory()) {
               // Put these after this entry so that all directories in a layer are processed
               bd.addSrcEntry(i+1, toGenEnt);
               if (deps.addDirectory(toGenEnt.baseFileName))
                  depsChanged = true;
            }
         }

         // Need to do this even if there are no models to transform to ensure deps get saved and
         // in case we later mark a file changed via a type group dependency.
         SrcDirEntry srcDirEnt = new SrcDirEntry();
         srcDirEnt.deps = deps;
         srcDirEnt.layer = srcEnt.layer;
         srcDirEnt.depFile = depFile;
         srcDirEnt.srcPath = srcPath;
         srcDirEnt.depsChanged = depsChanged;
         srcDirEnt.fileError = true;
         srcDirEnt.toGenerate = toGenerate;
         bd.srcDirs.add(srcDirEnt);
         ArrayList<SrcDirEntry> sdEnts = bd.srcDirsByPath.get(srcDirName);
         if (sdEnts == null) {
            sdEnts = new ArrayList<SrcDirEntry>();
            bd.srcDirsByPath.put(srcDirName, sdEnts);
         }
         sdEnts.add(srcDirEnt);
      }

      ArrayList<ILanguageModel> toStop = new ArrayList<ILanguageModel>();

      // Now that we have the complete list of models which are going to be processed, for any models which have already
      // been parsed and started, we'll stop them to reset their state to the initial state.  It's important that we
      // stop all models before we start initializing them because that will start to inject references to parts of code
      // models that we might throw away during 'stop'.
      for (int i = 0; i < bd.srcEnts.size(); i++) {
         SrcEntry srcEnt = bd.srcEnts.get(i);
         boolean incrCompile = LayerUtil.doIncrCompileForFile(srcEnt);
         ArrayList<SrcDirEntry> sdEnts = bd.srcDirsByPath.get(srcEnt.absFileName);
         if (sdEnts == null)
            continue;
         for (SrcDirEntry srcDirEnt:sdEnts) {
            LinkedHashSet<SrcEntry> toGenerate = srcDirEnt.toGenerate;

            for (SrcEntry toGenEnt:toGenerate) {
               ILanguageModel cachedModel = modelIndex.get(toGenEnt.absFileName);
               if (cachedModel != null && cachedModel.isStarted()) {
                  if (cachedModel instanceof JavaModel) {
                     JavaModel cachedJModel = (JavaModel) cachedModel;
                     // This one has not changed since we started this build so no need to stop it since the model is already up-to-date
                     /*
                     if (cachedJModel.lastStartedTime == lastChangedModelTime) {
                        continue;
                     }
                     */
                     // Only need to stop and restart models whose dependencies have changed.  This might be true either because we are
                     // in a new layered build and the model changed from the previous build layer, or because we started a model on a previous
                     // build and some model we depend upon has changed since the last build.
                     if (!isChangedModel(cachedJModel, genLayer, incrCompile, false)) {
                        if (options.verbose)
                           verbose("Reusing: " + toGenEnt + " for: " + getProcessIdent());
                        continue;
                     }
                  }
                  if (options.verbose)
                     verbose("Stopping: " + toGenEnt + " for: " + getProcessIdent());

                  toStop.add(cachedModel);
               }
            }
         }
      }


      for (int i = 0; i < toStop.size(); i++) {
         ILanguageModel cachedModel = toStop.get(i);
         cachedModel.stop();
         // When we reinit the model, we need to register the types.  for templates specifically they
         // destroy and recreate the types on the stop/init cycle so we need to call addTypesByName again
         // after we reinit.
         cachedModel.setAdded(false);
      }

      // TODO: There are cases where we may not have stopped a type which depends on a stopped type - e.g. when changing menuStyle.scss, we stop EditorFrame but not HtmlPage which
      // has an editorMixin tag tht extends EditorFrame.  Either we need to accurate find and stop all references or we do this refresh - so we remove references to potentially now stale
      // types that are created when we initialize the template.
      if (toStop.size() > 0)
         refreshBoundTypes(ModelUtil.REFRESH_TYPEDEFS, true);

      // Run the runtimeProcessor hook after stopping some models so it can clear out any cached types and reresolve them
      // after starting again.
      if (runtimeProcessor != null)
         runtimeProcessor.postStop(this, genLayer);

      // TODO: if we find no files to generate we could stop here right?
      return GenerateCodeStatus.NewCompiledFiles;
   }

   public boolean isChangedModel(JavaModel cachedModel, Layer genLayer, boolean incrCompile, boolean processJava) {
      // Only need to stop and restart models whose dependencies have changed.  This might be true either because we are
      // in a new layered build and the model changed from the previous build layer, or because we started a model on a previous
      // build and some model we depend upon has changed since the last build.
      if (!cachedModel.getDependenciesChanged(genLayer, incrCompile ? changedTypes : null, processJava)) {
         return false;
      }
      return true;
   }

   public GenerateCodeStatus initChangedModels(Layer genLayer, List<String> includeFiles, BuildPhase phase, boolean separateOnly) {
      BuildState bd = genLayer.buildState;

      BuildStepFlags flags = phase == BuildPhase.Prepare ? bd.prepPhase : bd.processPhase;
      if (flags.inited)
         return GenerateCodeStatus.NewCompiledFiles;

      flags.inited = true;

      if (initPeerChangedModels(genLayer, includeFiles, phase, separateOnly) == GenerateCodeStatus.Error)
         return GenerateCodeStatus.Error;

      // Now that all toGenerate models have been stopped, we iterate through them again and initialize the models.
      for (int i = 0; i < bd.srcEnts.size(); i++) {
         SrcEntry srcEnt = bd.srcEnts.get(i);
         boolean incrCompile = LayerUtil.doIncrCompileForFile(srcEnt);
         ArrayList<SrcDirEntry> sdEnts = bd.srcDirsByPath.get(srcEnt.absFileName);
         if (sdEnts == null)
            continue;
         for (SrcDirEntry srcDirEnt:sdEnts) {
            LinkedHashSet<ModelToTransform> modelsToTransform = new LinkedHashSet<ModelToTransform>();

            LinkedHashSet<SrcEntry> toGenerate = srcDirEnt.toGenerate;
            DependencyFile deps = srcDirEnt.deps;
            boolean depsChanged = srcDirEnt.depsChanged;

            for (SrcEntry toGenEnt:toGenerate) {
               File srcFile = new File(toGenEnt.absFileName);

               if (!srcFile.isDirectory()) {
                  boolean skipFile = includeFiles != null && !includeFiles.contains(toGenEnt.relFileName);
                  Object modelObj;
                  if (skipFile) {
                     if (options.verbose)
                        verbose("Skipping " + toGenEnt);
                     modelObj = null;
                  }
                  else {
                     modelObj = initModelForBuild(toGenEnt, genLayer, incrCompile, bd, phase, " new file?");
                     if (modelObj == INVALID_TYPE_DECLARATION_SENTINEL)
                        continue;
                     if (modelObj == IFileProcessor.FILE_OVERRIDDEN_SENTINEL) {
                        // TODO: do we need this or do we just use processedNames to determine when to skip generation?
                     }
                  }

                  boolean fileError = false;
                  if (modelObj instanceof IFileProcessorResult) {
                     IFileProcessorResult model = (IFileProcessorResult) modelObj;

                     if (model instanceof JavaModel && ((JavaModel) model).getLayeredSystem() != this)
                        error("*** Error model is in the wrong system!");

                     // Needs to have the extension so build.properties and build.xml go through but also needs type prefix
                     //String processedName = toGenEnt.getTypeName() + "." + FileUtil.getExtension(toGenEnt.baseFileName);
                     // Need to do this even if the model has errors so that if we restart the build, these are in the right order
                     String processedName = model.getProcessedFileId();
                     boolean generate = !bd.processedFileNames.contains(processedName);
                     if (generate) {
                        bd.processedFileNames.add(processedName);
                     }
                     ModelToTransform mtt = new ModelToTransform();
                     mtt.model = model;
                     mtt.toGenEnt = toGenEnt;
                     mtt.generate = generate;
                     modelsToTransform.add(mtt);
                     changedModels.put(toGenEnt.getTypeName(), model);
                     if (!changedTypes.contains(toGenEnt.getTypeName()))
                        System.err.println("*** Warning - changed types missing a changed model");

                     if (model.hasErrors())
                        fileError = true;
                  }
                  // Returns a ParseError if anything fails, null if we found nothing to compile in that file
                  else if (modelObj != null)
                     fileError = true;

                  // If we failed to generate this time, do not leave around any trash we might have generated
                  // last time.  You can end up with a false successful build.
                  if (fileError) {
                     List<IString> generatedFiles = deps.getGeneratedFiles(toGenEnt.baseFileName);
                     deps.addErrorDependency(toGenEnt.baseFileName);
                     depsChanged = true;
                     if (generatedFiles != null) {
                        for (IString genFile:generatedFiles) {
                           File gf = new File(genLayer.buildSrcDir, FileUtil.unnormalize(genFile.toString()));
                           gf.delete();
                        }
                     }
                     anyErrors = true;
                     bd.addErrorFile(toGenEnt);
                  }
               }
            }

            srcDirEnt.modelsToTransform = modelsToTransform;
            srcDirEnt.depsChanged = depsChanged;
            bd.numModelsToTransform += modelsToTransform.size();
         }
      }

      // Print some info that's useful for diagnosing what gets recompiled and why on a rebuild.
      if (!genLayer.getBuildAllFiles() && options.verbose) {
         if (bd.modifiedFiles.size() == 0) {
            verbose("No changed files detected");
            if (bd.dependentFilesChanged.size() > 0)
               verbose("but do have dependent files that are changed: " + bd.getDependentNamesString());
         }
         else {
            verbose("Changed files:" + bd.modifiedFiles);
            verbose("Dependent files: " + bd.getDependentNamesString());
         }
      }

      if (bd.numModelsToTransform > 0 && options.info) {
         info((options.testVerifyMode ? "Processing" : ("Processing: " + bd.numModelsToTransform)) + " files in the " + getRuntimeName() + " runtime for build layer: " + genLayer.getLayerName());
      }

      /** We also need to pre-compute the set of typeGroupChangedModels so that we can accurately determine the stale entries we have in the build info */
      /** TODO: This type group stuff is not quite right when dealing with incremental compiles
       *    Currently we are just skipping it when there are no changed models.  But the problem is that we have conflicting requirements right now;
       *       1) we need to know all of the models which have changed before we call cleanStaleEntries - including models which changed because of type-group dependencies
       *       2) we can't determine whether type-group deps have changed until starting/processing all changed models.
       *       I'm not sure why we are doing the cleanStaleEntries before we check the typed group membership.  It also seems like we should be able to read the reverse deps
       *       from the old type group model without parsing and starting it.  I think the bug is that we'll end up with duplicate entries in the BuildInfo because we did not
       *       clean out stuff which has changed.  Maybe we can just deal with that for now.  Type group members won't be removed reliably but at least get added reliably.
       *       I also skip this if nothing has changed so we don't end up parsing these files all the time (and things they depend on).
       * */
      if (typeGroupDeps != null && phase == BuildPhase.Process && changedTypes.size() > 0 && !separateOnly) {
         for (TypeGroupDep dep:typeGroupDeps) {
            // Skip the type group dependency if it's defined for a specific layer which is after this layer in the stack.  Maybe we should use
            // extendsLayer here for all except the final build layer?
            if (!dep.processForBuildLayer(genLayer))
               continue;
            IFileProcessor depProc = getFileProcessorForFileName(dep.relFileName, null, genLayer, null, false, true);
            if (depProc == null) {
               System.err.println("*** No file processor registered for type group dependency: " + dep.relFileName + " for group: " + dep.typeGroupName);
            }
            else if (depProc.enabledForPath(dep.relFileName, genLayer, false, false) != IFileProcessor.FileEnabledState.Disabled) {
               SrcEntry depFile = getSrcFileFromTypeName(dep.typeName, true, genLayer.getNextLayer(), depProc.getPrependLayerPackage(), null);
               if (depFile == null)
                  System.err.println("Warning: file: " + dep.relFileName + " not found in layer path but referenced via a typeGroup dependency on group: " + dep.typeGroupName);
               else {
                  Object depModelObj = bd.typeGroupChangedModels.get(dep.typeName);
                  if (depModelObj == null)
                     depModelObj = getCachedModel(depFile, false);
                  if (depModelObj == null) {
                     TypeDeclaration depType = getCachedTypeDeclaration(dep.typeName, genLayer, null, false, true, null, false);
                     if (depType != null && depType != INVALID_TYPE_DECLARATION_SENTINEL)
                        depModelObj = depType.getJavaModel();

                     if (depModelObj == null) {
                        if (depProc.getProducesTypes()) {
                           // Need to use parseSrcType here.  If we parse a src file, it needs to be registered into the model index so that we can
                           // tell if that model needs to be regenerated.
                           depModelObj = parseSrcType(depFile, genLayer, false, true);
                        }
                        else
                           depModelObj = parseSrcFile(depFile, true);
                     }
                  }
                  if (depModelObj instanceof IFileProcessorResult) {
                     bd.typeGroupChangedModels.put(dep.typeName, (IFileProcessorResult) depModelObj);
                     Integer oldCt = bd.typeGroupChangedModelsCount.get(dep.typeName);
                     if (oldCt == null)
                        oldCt = 0;
                     bd.typeGroupChangedModelsCount.put(dep.typeName, oldCt + 1);
                  }
                  else if (depModelObj == IFileProcessor.FILE_OVERRIDDEN_SENTINEL) {
                     // ?? todo here
                  }
               }
            }
         }
      }

      if (phase == BuildPhase.Process) {
         if (options.verbose && !options.testVerifyMode) {
            if (buildStartTime != -1)
               verbose("Parsed changes till layer: " + genLayer + " in: " + StringUtil.formatFloat((System.currentTimeMillis() - buildStartTime) / 1000.0));
         }

         if (bd.anyError)
            return GenerateCodeStatus.Error;

      }

      // We want to initialize the peer layer that corresponds to this one (if any).  This is to make the synchronization initialize more smoothly - so we init and process all types in both systems before we end up
      // transforming any models we might depend on.
      /*
      if (peerSystems != null && !separateOnly) {
         for (LayeredSystem peer:peerSystems) {
            Layer peerLayer = peer.getLayerByDirName(genLayer.layerDirName);
            // Make sure we only start/build build layers.
            while (peerLayer != null && !peerLayer.isBuildLayer())
               peerLayer = peerLayer.getNextLayer();
            if (peerLayer != null && (peerLayer.buildState == null || !peerLayer.buildState.changedModelsPreInited)) {

               // Before we can start the peer layers, make sure we've compiled any intermediate layers.  This would happen if the java runtime had no intermediates and the js runtime did, for example.
               // TODO: is this necessary now that we start intermediate build layers before?  Why are we compiling the intermediates here... shouldn't we just start them now that we start everything first?
               peer.buildLayersIfNecessary(peerLayer.getLayerPosition() - 1, includeFiles, false, false);

               GenerateCodeStatus peerStatus = peer.initChangedModels(peerLayer, includeFiles, phase, false);

               setCurrent(this); // Switch back to this layered system

               if (peerStatus == GenerateCodeStatus.Error) {
                  return GenerateCodeStatus.Error;
               }
            }
         }
      }
      */

      if (changedTypes.size() > 0)
         return GenerateCodeStatus.NewCompiledFiles;
      return GenerateCodeStatus.NoFilesToCompile;
   }

   public GenerateCodeStatus startChangedModels(Layer genLayer, List<String> includeFiles, BuildPhase phase, boolean separateOnly) {
      BuildState bd = genLayer.buildState;

      if (bd == null) {
         System.err.println("*** Build state not for startChangedModels!");
         return GenerateCodeStatus.Error;
      }

      BuildStepFlags flags = phase == BuildPhase.Process ? bd.processPhase : bd.prepPhase;
      if (flags.started)
         return GenerateCodeStatus.NewCompiledFiles;

      flags.started = true;

      if (options.verbose) {
         verbose("Starting models for build layer: " + genLayer + " runtime: " + getProcessIdent());
      }

      for (SrcDirEntry srcDirEnt : bd.srcDirs) {
         if (srcDirEnt == null || srcDirEnt.modelsToTransform == null) {
            System.out.println("*** Warning - null srcDirEnt!");
            continue;
         }
         for (ModelToTransform mtt : srcDirEnt.modelsToTransform) {
            IFileProcessorResult model = mtt.model;
            SrcEntry toGenEnt = mtt.toGenEnt;

            startModelForBuild(model, toGenEnt, genLayer, LayerUtil.doIncrCompileForFile(toGenEnt), bd, phase);
            if (model.hasErrors()) {
               bd.addErrorFile(toGenEnt);
               anyErrors = true;
            }
         }
      }
      if (startPeerChangedModels(genLayer, includeFiles, phase, separateOnly) == GenerateCodeStatus.Error)
         return GenerateCodeStatus.Error;

      if (phase == BuildPhase.Process) {
         if (bd.anyError)
            return GenerateCodeStatus.Error;
         else {
            // Run the runtimeProcessor hook for the post start sequence
            if (runtimeProcessor != null)
               runtimeProcessor.postStart(this, genLayer);

            // Doing this after postStart because by then we've detected all JS source we will load
            for (Layer stLayer : bd.startedLayers) {
               stLayer.changedModelsDetected = true;
            }

            bd.changedModelsDetected = true;
         }
      }

      if (phase == BuildPhase.Process)
         lastModelsStartedLayer = genLayer;
      if (changedTypes.size() > 0)
         return GenerateCodeStatus.NewCompiledFiles;
      return GenerateCodeStatus.NoFilesToCompile;
   }

   /**
    * This operation processes each file generating any Java files necessary for language extensions.
    * Various options can be provided to control how this generation is performed.  The list of includeFiles
    * specifies a subset of files to process - useful for debugging generator problems mainly.
    *
    * We run this method over the set of BuildPhases - prepare and process.   That gives each source file a chance
    * to be manipulated in each phase.   We might generate some additional files to process in the prepare
    * phase which are then processed in the process phase.
    *
    * Returns true if everything worked, and false if there were any errors.
    */
   public GenerateCodeStatus generateCode(Layer genLayer, List<String> includeFiles, BuildPhase phase, boolean separateOnly) {
      boolean skipBuild = !genLayer.buildSeparate && separateOnly;

      if (!needsPhase(phase)) {
         sysDetails("No " + phase + " phase for " + getRuntimeName() + " runtime");
         return GenerateCodeStatus.NoFilesToCompile;
      }
      // Nothing to generate or compile
      if (genLayer == null)
         return GenerateCodeStatus.NoFilesToCompile;

      BuildState buildState = genLayer.buildState;
      boolean alreadyPreInited;
      // The generateCode phase happens in two major steps.  First we start all of the changed models, then we transform them into Java code.
      // Ordinarily the first step will be done before we get here... since we have to start all runtimes before we begin transforming.  That's because
      // transforming is a destructive step right now.
      if (buildState == null)
         alreadyPreInited = false;
      else
         alreadyPreInited = phase == BuildPhase.Prepare ? buildState.prepPhase.preInited : buildState.processPhase.preInited;
      if (!alreadyPreInited) {
         // Now go through and find which source files have changed, reload those changes and populate the modelsToTransform
         GenerateCodeStatus startResult = preInitChangedModels(genLayer, includeFiles, phase, separateOnly);
         if (startResult != GenerateCodeStatus.NewCompiledFiles) {
            if (phase == BuildPhase.Process)
               genLayer.updateBuildInProgress(false);
            return startResult;
         }
      }


      BuildState bd = genLayer.buildState;
      BuildStepFlags flags = phase == BuildPhase.Prepare ? bd.prepPhase : bd.processPhase;
      if (!flags.inited) {
         // Now go through and find which source files have changed, reload those changes and populate the modelsToTransform
         GenerateCodeStatus startResult = initChangedModels(genLayer, includeFiles, phase, separateOnly);
         if (startResult != GenerateCodeStatus.NewCompiledFiles) {
            markBuildCompleted(genLayer, phase);
            return startResult;
         }
      }

      if (!flags.started) {
         GenerateCodeStatus startResult = startChangedModels(genLayer, includeFiles, phase, separateOnly);
         if (startResult != GenerateCodeStatus.NewCompiledFiles) {
            markBuildCompleted(genLayer, phase);
            return startResult;
         }
      }

      // All components will have been started and validated at this point.
      if (!bd.anyError) {
         // Clear out the build info for data for any models we know have changed.  Doing this only on the first build because we do not restart all of the models and so will just clean out everything that's not changed.
         // TODO: this is not 100% accurate as we may also change models based on the type group membership changing later on.
         if (!systemCompiled)
            buildInfo.cleanStaleEntries(changedTypes, processedModels, buildLayer == genLayer);

         for (SrcDirEntry srcDirEnt:bd.srcDirs) {
            for (ModelToTransform mtt:srcDirEnt.modelsToTransform) {
               IFileProcessorResult model = mtt.model;
               if (model instanceof ILifecycle) {
                  ILifecycle scModel = (ILifecycle) model;
                  if (scModel instanceof JavaModel)
                     ((JavaModel) scModel).cleanStaleEntries(changedTypes);
                  if (!scModel.isProcessed())
                     scModel.process();
                  if (scModel instanceof JavaModel) {
                     JavaModel jmodel = (JavaModel) scModel;
                     if (mtt.generate) // This should be set for the most specific model - i.e. with the one with the reverse deps for this build.
                        jmodel.saveReverseDeps(genLayer.buildSrcDir);
                  }
               }
            }
         }

         if (typeGroupDeps != null && phase == BuildPhase.Process && changedTypes.size() > 0 && !separateOnly) {
            for (TypeGroupDep dep:typeGroupDeps) {
               if (!dep.processForBuildLayer(genLayer))
                  continue;
               IFileProcessor depProc = getFileProcessorForFileName(dep.relFileName, null, genLayer, null, false, true);
               if (depProc == null) {
                  System.err.println("*** No file processor registered for type group dependency: " + dep.relFileName + " for group: " + dep.typeGroupName);
               }
               else if (depProc.enabledForPath(dep.relFileName, genLayer, false, false) != IFileProcessor.FileEnabledState.Disabled) {
                  SrcEntry depFile = getSrcFileFromTypeName(dep.typeName, true, genLayer.getNextLayer(), depProc.getPrependLayerPackage(), null);
                  if (depFile == null)
                      System.err.println("Warning: file: " + dep.relFileName + " not found in layer path but referenced via a typeGroup dependency on group: " + dep.typeGroupName);
                  else {
                     Object depModelObj = bd.typeGroupChangedModels.get(dep.typeName);
                     if (depModelObj instanceof JavaModel) {
                        JavaModel depModel = (JavaModel) depModelObj;
                        ParseUtil.validateComponent(depModel);
                        ParseUtil.processComponent(depModel);
                        List<String> prevTypeGroupMembers = depModel.getPrevTypeGroupMembers(genLayer, dep.typeGroupName);
                        if (genLayer.buildInfo == null) {
                           System.out.println("*** Error - layer: " + genLayer + " not initialized to build");
                        }
                        List<TypeGroupMember> newTypeGroupMembers = genLayer.buildInfo.getTypeGroupMembers(dep.typeGroupName);
                        boolean typeGroupChanged = false;
                        if (prevTypeGroupMembers != null || newTypeGroupMembers != null) {
                           if (prevTypeGroupMembers == null || newTypeGroupMembers == null ||
                               prevTypeGroupMembers.size() != newTypeGroupMembers.size() ||
                               !LayerUtil.sameTypeGroupLists(prevTypeGroupMembers, newTypeGroupMembers) ||
                               // Also regenerate if any of these files have changed, since annotations are typically used
                               // to determine the contents of the file, e.g. ApplicationPath
                               LayerUtil.anyTypesChanged(changedTypes, prevTypeGroupMembers)) {

                              // More than one directory might have this file.  Add this to the srcDir for the layer
                              // which contains the most specific type.  Not sure it's necessary but this ensures we
                              // generate these files at a consistent spot when traversing the directory hierarchy by
                              // just adding it to the modelsToTransform in the right directory.
                              String parentPath = FileUtil.getParentPath(depModel.getSrcFile().absFileName);
                              if (parentPath == null)
                                 parentPath = "";
                              ArrayList<SrcDirEntry> depDirs = bd.srcDirsByPath.get(parentPath);

                              // This forces us to retransform this model, even if we've already transformed it in a previous build layer (e.g. the PageInit class)
                              if (depModel.transformedModel != null)
                                 depModel.transformedInLayer = null;

                              typeGroupChanged = true;

                              // Might get to this when not processing this layer so we won't find the directory containing the file.  If this model has been changed but
                              // already processed in the previous layer, we still may need to transform it again in this layer.
                              if (depDirs != null && (!changedTypes.contains(dep.typeName) || processedModels.contains(dep.typeName))) {
                                 if (options.verbose && !options.buildAllFiles) {
                                    if (prevTypeGroupMembers != null && LayerUtil.anyTypesChanged(changedTypes, prevTypeGroupMembers))
                                       System.out.println("Type group dependency member type changed. Type: " + depModel.getModelTypeName() + " group: " + dep.typeGroupName + " members: " + prevTypeGroupMembers);
                                    else
                                       System.out.println("Type group dependency membership changed. Type: " + depModel.getModelTypeName() + " group: " + dep.typeGroupName + " old: " + prevTypeGroupMembers + " new: " + newTypeGroupMembers);
                                 }

                                 for (SrcDirEntry depDir:depDirs) {
                                    if (depDir.layer == depModel.getLayer()) {
                                       ModelToTransform mtt = new ModelToTransform();
                                       mtt.model = depModel;
                                       mtt.toGenEnt = depModel.getSrcFile();
                                       mtt.generate = true;
                                       // Might already be in there cause it changed normally.
                                       if (!depDir.modelsToTransform.contains(mtt))
                                          depDir.modelsToTransform.add(mtt);
                                       break;
                                    }
                                 }
                              }
                           }
                           // Need to set this
                           depModel.setPrevTypeGroupMembers(genLayer.buildSrcDir, dep.typeGroupName, newTypeGroupMembers);
                        }
                        if (!typeGroupChanged) {
                           // The same file can be in more than one type group dependency.  If none of them have changed, we remove it as a changed model.
                           Integer refCount = bd.typeGroupChangedModelsCount.get(dep.typeName);
                           refCount = refCount - 1;
                           if (refCount == 0)
                              bd.typeGroupChangedModels.remove(dep.typeName);
                           bd.typeGroupChangedModelsCount.put(dep.typeName, refCount);
                        }
                     }
                     // The same file can be registered with multiple deps.
                     else if (depModelObj != null) {
                        System.err.println("File with name: " + dep.relFileName + " expected to be a language type for type group dependency: " + dep.typeGroupName);
                     }
                  }
               }
            }
         }

         if (phase == BuildPhase.Process)
            allTypesProcessed = true;

         // Now we have gone through all of the source directories, collected all of the changed files/models,
         // and started, validated, and processed all of the changed types.  The above loops go through all files
         // because of the features like data binding usage may change to the models of classes they use.  These
         // reverse dependencies require building all changed files at once to pick up changes injected.  If this
         // becomes a memory problem, we might need to create a way to page types in and out as they get modified.
         // Final layers provide a way to know that no changes are pushed upstream into a layer so we can load in the
         // compiled classes and build them separately.
         for (SrcDirEntry srcDirEnt:bd.srcDirs) {
            DependencyFile deps = srcDirEnt.deps;
            File depFile = srcDirEnt.depFile;
            boolean depsChanged = srcDirEnt.depsChanged;

            if (getCurrent() != this)
               System.out.println("*** Error mismatching layered system");

            for (ModelToTransform mtt:srcDirEnt.modelsToTransform) {
               SrcEntry toGenEnt = mtt.toGenEnt;
               IFileProcessorResult model = mtt.model;
               boolean generate = mtt.generate;
               SrcEntry origSrcEnt = null;

               List<SrcEntry> runtimeFiles = runtimeProcessor == null ? model.getProcessedFiles(genLayer, genLayer.buildSrcDir, generate) : runtimeProcessor.getProcessedFiles(model, genLayer, genLayer.buildSrcDir, generate);
               List<SrcEntry> generatedFiles = runtimeFiles == null ? new ArrayList<SrcEntry>(0) : runtimeFiles;

               if (model.hasErrors()) {
                  // If this happens we should clean out the generated files
                  bd.addErrorFile(toGenEnt);
                  anyErrors = true;
               }
               else {
                  processedModels.add(toGenEnt.getTypeName());
                  if (!(model instanceof ILanguageModel)) // Storing the files which are not parsed separately so we can still rebuild them when they change
                     processedFileIndex.put(toGenEnt.absFileName, model);
               }

               // The first file may actually be the original source file.  To avoid considering this a generated
               // file, strip it out.
               if (runtimeFiles != null && runtimeFiles.size() > 0 && runtimeFiles.get(0).absFileName.equals(toGenEnt.absFileName)) {
                  origSrcEnt = toGenEnt;
                  generatedFiles = runtimeFiles.subList(1, runtimeFiles.size());
                  if (generatedFiles.size() == 0)
                     generatedFiles = null;
               }
               if (!model.hasErrors()) {
                  deps.addDependencies(toGenEnt.baseFileName, generatedFiles, model.getDependentFiles());
                  depsChanged = true;

                  // We might be generating a dynamic type which has no stub and so no runtimeFiles.
                  boolean needsCompile = generate && runtimeFiles != null && runtimeFiles.size() > 0 && model.needsCompile();

                  if (generate) {
                     for (SrcEntry genFile:generatedFiles) {
                        byte[] hash = genFile.hash;
                        // Getting the processor for the generated file.  Passing in null for the phase as we just need to know whether to inherit or compile the files
                        IFileProcessor genProc = getFileProcessorForSrcEnt(genFile, null, true);
                        if (genProc == null)
                           System.err.println("*** Missing file processor for generated file: " + genFile.relFileName);
                        if (hash != null) {
                           SrcIndexEntry sie = genLayer.getSrcFileIndex(genFile.relFileName);

                           // Just in case we previously inherited this file
                           LayerUtil.removeInheritFile(genFile.absFileName);

                           if (sie == null || !Arrays.equals(sie.hash, hash)) {
                              SrcIndexEntry prevSrc = getPrevSrcIndex(genLayer, genFile);

                              if (runClassStarted && isClassLoaded(toGenEnt.getTypeName())) {
                                 internalSetStaleCompiledModel(true, "Loaded compiled type: " + genFile.relFileName + " changed");
                                 bd.anyChangedClasses = true;
                              }

                              if (prevSrc != null && Arrays.equals(prevSrc.hash, hash)) {
                                 if (options.verbose)
                                    System.out.println("File: " + genFile.relFileName + " in layer: " + genLayer + " inheriting previous version at: " + genLayer.getPrevSrcFileLayer(genFile.relFileName));
                                 if (genProc != null && genProc.getInheritFiles()) {
                                    FileUtil.renameFile(genFile.absFileName, FileUtil.replaceExtension(genFile.absFileName, Language.INHERIT_SUFFIX));
                                    LayerUtil.removeFileAndClasses(genFile.absFileName);
                                 }
                              }
                              else if (needsCompile && (genProc != null && genProc.getNeedsCompile())) {
                                 bd.toCompile.add(genFile);

                                 // If we're overriding a definition from a previous "buildSeparate" layer with a different definition
                                 // this requires a restart.  buildSeparate layers get put into the system class path before we run
                                 // the application.  If we're just adding new types, we can load them the first time.
                                 // Ideally, we'd allow changes to any classes which have not yet been loaded but there's no easy
                                 // way to find that out with how ClassLoaders work (probably a loader agent could do it though?)
                                 // If it's a buildSeparate layer, it gets put into the class path right away so it can be used
                                 // by the build process itself.
                                 // If not and we haven't started any main methods, we won't have loaded the compiled version of
                                 // those classes.
                                 // If this is not the buildLayer... it's possible we are generating a previous version of a file which has not
                                 /* TODO: don't think we need this anymore because we have the tracking class loader
                                 Layer prevLayer = genLayer.getPrevSrcFileLayer(genFile.relFileName);
                                 if ((prevSrc != null && prevLayer != null && (prevLayer.buildSeparate || runClassStarted)))
                                    bd.anyChangedClasses = true;
                                 */
                              }

                              if (sie != null) {
                                 sie.hash = hash;
                                 sie.inUse = true;
                                 sie.updateDebugFileInfo(genFile.absFileName);
                              }
                              else
                                 genLayer.addSrcFileIndex(genFile.relFileName, hash, toGenEnt.getExtension(), genFile.absFileName);
                           }
                           else {
                              sie.inUse = true;
                              SrcIndexEntry prevSrc = getPrevSrcIndex(genLayer, genFile);
                              if (prevSrc != null && Arrays.equals(prevSrc.hash, hash)) {
                                 if (genProc.getInheritFiles()) {
                                    if (options.verbose)
                                       verbose("File: " + genFile.relFileName + " in layer: " + genLayer + " inheriting previous version at: " + genLayer.getPrevSrcFileLayer(genFile.relFileName));
                                    FileUtil.renameFile(genFile.absFileName, FileUtil.replaceExtension(genFile.absFileName, Language.INHERIT_SUFFIX));
                                    LayerUtil.removeFileAndClasses(genFile.absFileName);
                                    String classFileName = LayerUtil.getClassFileName(genLayer, genFile);
                                    LayerUtil.removeFileAndClasses(classFileName);
                                 }
                              }
                              else if (genProc != null && genProc.getNeedsCompile()) {
                                 sysDetails("  (unchanged generated file: " + genFile.relFileName + " in layer " + genLayer + ")");
                                 File classFile = LayerUtil.getClassFile(genLayer, genFile);
                                 File genAbsFile = new File(genFile.absFileName);
                                 if (needsCompile) {
                                    if (!classFile.canRead() || classFile.lastModified() < genAbsFile.lastModified()) {
                                       sysDetails("  (recompiling as class file is out of date: " + genFile.relFileName + " in layer " + genLayer + ")");
                                       bd.toCompile.add(genFile);
                                    }
                                 }
                                 else {
                                    // If the class file does not need to be regenerated mark it as up-to-date anyway
                                    //classFile.setLastModified(buildTime);
                                 }
                                 // Mark the generated file as up-to-date now since effectively we just regenerated it
                                 //genAbsFile.setLastModified(buildTime);
                              }
                           }
                        }
                        // else - has will be null when generate = false
                     }

                     // Using the postBuild file id here, not the processed file id.  That will be the java file name, with the package when there are types
                     // involved.  This will be the generated HTML file.
                     String postBuildFileId;
                     ModelToPostBuild pbModel;
                     if (model.needsPostBuild() && ((pbModel = modelsToPostBuild.get(postBuildFileId = model.getPostBuildFileId())) == null || pbModel.genLayer != genLayer)) {
                        if (pbModel == null || pbModel.genLayer.getLayerPosition() < genLayer.getLayerPosition()) {
                           ModelToPostBuild modelToPostBuild = new ModelToPostBuild();
                           modelToPostBuild.model = model;
                           modelToPostBuild.genLayer = genLayer;
                           modelsToPostBuild.put(postBuildFileId, modelToPostBuild);
                        }
                     }
                  }

                  if (needsCompile && origSrcEnt != null) {
                     bd.toCompile.add(origSrcEnt);
                  }
               }
            }

            if (depsChanged) {
               PerfMon.start("saveDeps");
               LayerUtil.saveDeps(deps, depFile, bd.buildTime);
               PerfMon.end("saveDeps");
            }
         }

         allTypesProcessed = false;

      }

      if (phase == BuildPhase.Process) {
         if (!bd.anyError) {
            // Run the runtimeProcessor hook, e.g. to roll up the java script files generated for each type into a file which can be loaded
            // Do this before we save the build info in case the hook needs to add more info.
            if (runtimeProcessor != null)
               runtimeProcessor.postProcess(this, genLayer);
         }

         genLayer.processed = true;
         if (genLayer == buildLayer) {
            saveBuildInfo();

            // Add any files not directly related to the src files in the layered system.  For example, external dyn type
            // wrappers.
            if (buildInfo.toCompile != null)
               bd.toCompile.addAll(buildInfo.toCompile);
         }
         else
            genLayer.saveBuildInfo(true);

         genLayer.updateBuildInProgress(false);
      }

      if (runtimeProcessor != null) {
         List<SrcEntry> errorFiles = runtimeProcessor.buildCompleted();
         if (errorFiles != null) {
            for (SrcEntry jsError:errorFiles)
               bd.addErrorFile(jsError);
         }
      }

      if (!bd.anyError) {
         boolean compileFailed = false;

         if (options.verbose && !options.testVerifyMode) {
            verbose("Generation completed in: " + StringUtil.formatFloat((System.currentTimeMillis() - buildStartTime)/1000.0));
         }
         if (phase == BuildPhase.Process && !skipBuild) {
            if (!options.noCompile) {
               int numToCompile = bd.toCompile.size();
               // Need to remove any null sentinels we might have registered
               for (SrcEntry compiledSrcEnt : bd.toCompile) {
                  String cSrcTypeName = compiledSrcEnt.getTypeName();
                  compiledClassCache.remove(cSrcTypeName);
                  if (otherClassCache != null)
                     otherClassCache.remove(cSrcTypeName);
               }
               if (options.verbose) {
                  verbose("Compiling Java into build dir: " + genLayer.getBuildClassesDir() + ": " + (options.sysDetails ? bd.toCompile + " with classpath: " + classPath : bd.toCompile.size() + " files"));
                  if (messageHandler != null)
                     messageHandler.reportMessage("Compiling Java: " + bd.toCompile.size() + " files into " + genLayer.getBuildClassesDir(), null, -1, -1, MessageType.Info);
               }
               else if (options.info) {
                  if (numToCompile == 0) {
                     if (!options.testVerifyMode)
                        info("No files to compile");
                  }
                  else if (options.testVerifyMode) // avoiding the # of files in the test verify output
                     info("Compiling Java files into " + genLayer.getBuildClassesDir());
                  else
                     info("Compiling Java: " + bd.toCompile.size() + " files into " + genLayer.getBuildClassesDir());
               }

               if (numToCompile > 0) {
                  PerfMon.start("javaCompile");
                  HashSet<String> errorFiles = new HashSet<String>();
                  if (LayerUtil.compileJavaFilesInternal(bd.toCompile, genLayer.getBuildClassesDir(), getClassPathForLayer(genLayer, true, genLayer.getBuildClassesDir(), true), options.debug, javaSrcVersion, messageHandler, errorFiles) == 0) {
                     if (!buildInfo.buildJars())
                        compileFailed = true;
                  }
                  else {
                     compileFailed = true;
                  }

                  if (compileFailed) {
                     anyErrors = true;
                     String fileExt;
                     for (String javaErrorFileName:errorFiles) {
                        if (javaErrorFileName != null && (fileExt = FileUtil.getExtension(javaErrorFileName)) != null && fileExt.equals("java") && buildLayer != null) {
                           String buildSrcDir = buildLayer.buildSrcDir;
                           if (javaErrorFileName.startsWith(buildSrcDir)) {
                              String errorTypeName = FileUtil.removeExtension(javaErrorFileName.substring(buildSrcDir.length() + 1).replace(FileUtil.FILE_SEPARATOR_CHAR, '.'));
                              if (errorTypeName != null) {
                                 TypeDeclaration td = (TypeDeclaration) getSrcTypeDeclaration(errorTypeName, null, true, false, true, buildLayer, false, true, false);
                                 if (td != null) {
                                    JavaModel model = td.getJavaModel();
                                    SrcEntry srcEnt = model.getSrcFile();
                                    if (srcEnt != null)
                                       bd.addErrorFile(srcEnt);
                                 }
                              }
                           }
                        }
                     }
                  }
                  if (options.info && numToCompile > 0)
                     info("Compile " + (compileFailed ? "failed" : "completed"));
                  PerfMon.end("javaCompile");
               }
            }
            else {
               info("Compilation disabled - not compiling: " + bd.toCompile.size() + " files");
            }
         }

         List<BuildCommandHandler> cmds = null;

         if (!compileFailed && !skipBuild) {
            cmds = postBuildCommands.get(phase);
            if (cmds != null) {
               List<ProcessBuilder> pbs = getProcessBuildersFromCommands(cmds, this, genLayer, genLayer);
               if (!LayerUtil.execCommands(pbs, genLayer.buildDir, options.info))
                  return GenerateCodeStatus.Error;
               else
                  refreshLayerIndex();
            }
         }

         return compileFailed ? GenerateCodeStatus.Error :
                 (bd.toCompile.size() == 0 ? GenerateCodeStatus.NoFilesToCompile :
                         (bd.anyChangedClasses ? GenerateCodeStatus.ChangedCompiledFiles : GenerateCodeStatus.NewCompiledFiles));
      }
      else if (bd.errorFiles.size() > 0) {
         if (!bd.fileErrorsReported) {
            String phaseType = "processing";
            if (phase == BuildPhase.Prepare)
               phaseType = "preparing";
            System.err.println("Errors " + phaseType + " build layer: " + genLayer + " runtime: " + getRuntimeName() + " files with errors: " + bd.errorFiles);
            bd.fileErrorsReported = true;
         }
         return GenerateCodeStatus.Error;
      }
      return GenerateCodeStatus.Error;
   }

   private void markBuildCompleted(Layer genLayer, BuildPhase phase) {
      if (phase == BuildPhase.Process) {
         genLayer.updateBuildInProgress(false);
         if (runtimeProcessor != null)
            runtimeProcessor.buildCompleted();
      }
   }

   public SrcIndexEntry getPrevSrcIndex(Layer genLayer, SrcEntry genFile) {
      return options.buildAllLayers || (options.useCommonBuildDir && genLayer == commonBuildLayer) ? null : genLayer.getPrevSrcFileIndex(genFile.relFileName);
   }

   /**
    * This gets called on the layer just before generateCode.  There are two cases... we make a first pass through
    * all "buildSeparate=true" layers that are not the buildLayer.  For those, we start and build the entire layer.
    * For the rest, we'll wait to start them till the second pass.  This way, those layers "start" methods can depend
    * on classes which were compiled during the first pass.  On the second pass, we are given the buildLayer and now
    * start anything not already started.
    */
   public void startLayers(Layer genLayer) {
      PerfMon.start("startLayers");
      /*
      if (genLayer != buildLayer) {
         genLayer.start();
         initSysClassLoader(genLayer, false);
         genLayer.validate();
      }
      else {
      */
      if (genLayer.layerPosition < (genLayer.activated ? layers.size() : inactiveLayers.size())) {
         for (int i = 0; i <= genLayer.layerPosition; i++) {
            Layer l = genLayer.activated ? layers.get(i) : inactiveLayers.get(i);
            l.ensureStarted(false);
         }
         for (int i = 0; i <= genLayer.layerPosition; i++) {
            Layer l = genLayer.activated ? layers.get(i) : inactiveLayers.get(i);
            l.ensureValidated(false);
         }
      }
      else
         System.err.println("*** Attempt to start layer that is out of range: " + genLayer.layerPosition + " >= " + layers.size());
      //}
      PerfMon.end("startLayers");
   }

   private void startLayers(List<Layer> theLayers, boolean checkBaseLayers) {
      for (int i = 0; i < theLayers.size(); i++) {
         Layer l = theLayers.get(i);
         l.ensureStarted(false);
      }
      for (int i = 0; i < theLayers.size(); i++) {
         Layer l = theLayers.get(i);
         l.ensureValidated(false);
      }
   }

   /**
    * Before we run any application code, we want to build a single canonical class loader used by the system.
    * We'll throw away the existing one and flush out any classes loaded for compilation.  The issue is that some
    * systems like openJPA use their class loader to load resources etc.  This class loader needs to have access to
    * stuff in any buildDir used by the layered system.  When there are multiple build layers at once, we need a
    * class loader which can find the compiled classes that does not contain any files we are building.
    * <p>
    * If this becomes problematic, another approach is to use the ClassFile's for compilation and only use the
    * ClassLoader scheme when we start running stuff.
    */
   private void resetClassLoader() {
      if (buildClassLoader instanceof TrackingClassLoader) {
         ((TrackingClassLoader) buildClassLoader).deactivate();
      }
      buildClassLoader = getClass().getClassLoader();
      RTypeUtil.flushCaches();
      resetClassCache();
      resetModelCaches();
      initSysClassLoader(buildLayer, ClassLoaderMode.ALL);

      if (getUseContextClassLoader())
         Thread.currentThread().setContextClassLoader(getSysClassLoader());
      refreshBoundTypes(ModelUtil.REFRESH_CLASSES, true);

      // Test processors and other instances need to be refreshed with the new class loader
      if (buildInfo != null)
         buildInfo.reload();
   }

   public void setSystemClassLoader(ClassLoader loader) {
      if (systemClassLoader == loader)
         return;

      // How do we track multiple system class loaders?   If it changes just need to flush the type caches
      // Maybe just need a releaseSystemClassLoader method and support only one at a time.  Could also
      // bootstrap all of StrataCode into the application class loader... would involve a restart after compile.
      if (systemClassLoader != null)
         warning("replacing system class loader!");
      systemClassLoader = loader;
   }

   public void setFixedSystemClassLoader(ClassLoader loader) {
      autoClassLoader = false;
      setSystemClassLoader(loader);
      // Also use this base loader as the core for the build loader
      if (buildClassLoader == null)
         buildClassLoader = loader;

      if (!peerMode && peerSystems != null) {
         for (LayeredSystem peerSys:peerSystems)
            peerSys.setFixedSystemClassLoader(loader);
      }
   }

   public void setAutoSystemClassLoader(ClassLoader loader) {
      if (autoClassLoader) {
         if (options.verbose && loader != systemClassLoader)
            verbose("Updating auto system class loader for thread: " + (options.testVerifyMode ? "<thread-name>" : Thread.currentThread().getName()));

         setSystemClassLoader(loader);
      }
   }

   public IBeanMapper getConstantPropertyMapping(Object type, String dstPropName) {
      return ModelUtil.getConstantPropertyMapping(type, dstPropName);
   }

   public Class loadClass(String className) {
      return getCompiledClassWithPathName(className);
   }

   public Object resolveMethod(Object type, String methodName, Object returnType, String paramSig) {
      return ModelUtil.getMethodFromSignature(type, methodName, paramSig, true);
   }

   public void cleanTypeCache() {
      for (Iterator<Map.Entry<String,TypeDeclarationCacheEntry>> it = typesByName.entrySet().iterator(); it.hasNext(); ) {
         Map.Entry<String,TypeDeclarationCacheEntry> mapEnt = it.next();
         TypeDeclarationCacheEntry tdEnt = mapEnt.getValue();
         int k;
         for (k = 0; k < tdEnt.size(); k++) {
            TypeDeclaration td = tdEnt.get(k);
            if (td == INVALID_TYPE_DECLARATION_SENTINEL) {
               tdEnt.remove(k);
               k--;
            }
         }
      }
   }

   public void flushTypeCache() {
      sysDetails("Flushing type cache");

      innerTypeCache.clear();

      // Don't need to do this when we clone the models before the transform.  When verbose is on, for now it's a diagnostic
      // TODO: we don't need this code with the clonedTransform mode
      //if (options.clonedTransform)
      //   return;

      // Remove just the transformed types and models.  Any dynamic types can stick around - in fact, must stick around since
      // we may have created live instances from them.
      for (Iterator<Map.Entry<String,TypeDeclarationCacheEntry>> it = typesByName.entrySet().iterator(); it.hasNext(); ) {
         Map.Entry<String,TypeDeclarationCacheEntry> mapEnt = it.next();
         TypeDeclarationCacheEntry tdEnt = mapEnt.getValue();
         int k;
         boolean transformed = false;
         // If any types are transformed, clear out this entry and start fresh.
         for (k = 0; k < tdEnt.size(); k++) {
            TypeDeclaration td = tdEnt.get(k);
            if (transformed = td.getTransformed())
               break;
            while (td instanceof ModifyDeclaration) {
               if (!td.isStarted())
                  break;
               if (transformed = td.getTransformed())
                  break;

               Object dObj = td.getDerivedTypeDeclaration();
               if (dObj instanceof TypeDeclaration)
                  td = (TypeDeclaration) dObj;
               else
                  break;
            }
            if (transformed)
               break;
         }
         // For cloned transform with the IDE we'll have autoImports in here so do not go and remove this entry even if there are not registered types.
         // It's because we have not loaded the type in the type cache if it's not in an active layer.
         if (k != tdEnt.size() || (tdEnt.size() == 0 && !options.clonedTransform)) {
            tdEnt.reloaded = true;
            for (k = 0; k < tdEnt.size(); k++) {
               TypeDeclaration td = tdEnt.get(k);
               subTypesByType.remove(td.getFullTypeName());
            }
            if (options.buildAllFiles)
               tdEnt.clear(); // Need to leave this around in this case so that we track the state of the reverseDeps "reloaded" flag
            else
               it.remove();
         }
      }
      for (Iterator<Map.Entry<String,ILanguageModel>> it = modelIndex.entrySet().iterator(); it.hasNext(); ) {
         ILanguageModel model = it.next().getValue();
         TypeDeclaration mtype = model.getModelTypeDeclaration();
         if (mtype == null)
            continue;
         boolean transformed = mtype.getTransformed();
         if (model instanceof JavaModel)
            ((JavaModel) model).flushTypeCache();
         while (!transformed && mtype instanceof ModifyDeclaration) {
            ModifyDeclaration md = (ModifyDeclaration) mtype;
            // If we are not started, we cannot be transformed.  Don't try to get the derived type declaration if
            // we are not started as that starts things up in a weird place.
            if (!md.isStarted())
                break;
            transformed = md.getTransformed();
            if (transformed)
               break;
            // Just like above, need to check if any modified types here are transformed... Seems like the problem is
            // that we can load a model from a layer that follows the one we built?  That guy won't get transformed
            // but sticks in the type map so if we fix that, we could get rid of this messy code.
            Object dObj = md.getDerivedTypeDeclaration();
            if (dObj instanceof TypeDeclaration) {
               mtype = (TypeDeclaration) dObj;
               if (transformed = mtype.getTransformed())
                  break;
            }
            else
               break;
         }
         if (transformed || (!mtype.isStarted() && typesByName.get(mtype.getFullTypeName()) == null)) {
            it.remove();
         }
      }

      refreshBoundTypes(ModelUtil.REFRESH_TYPEDEFS | ModelUtil.REFRESH_TRANSFORMED_ONLY, true);

      // Need to clear this before we do refreshBoundType.   This was stale after the last recompile which means new classes
      // won't be visible yet - shadowed by cached nulls.  when we validate the models in refreshBoundType we need the new
      // compiled representation so that the "stale" model is kept in sync.
      resetClassCache();

      //typesByName.clear();
      //modelIndex.clear();
   }

   private void resetModelCaches() {
      for (Iterator<Map.Entry<String,ILanguageModel>> it = modelIndex.entrySet().iterator(); it.hasNext(); ) {
         ILanguageModel model = it.next().getValue();
         model.flushTypeCache();
      }
   }

   public void refreshBoundTypes(int flags, boolean active) {
      if (active) {
         // Need to clone here because we'll be adding new types to this map during the refreshBoundType process below - i.e.
         // remapping transformed types to their untransformed representations
         Map<String, TypeDeclarationCacheEntry> oldTypesByName = (Map<String, TypeDeclarationCacheEntry>) typesByName.clone();

         // Now that we've purged the cache of transformed types, go through any remaining types and do the refreshBoundType
         // operation.  That will drag in new versions of any referenced transformed types.
         for (Iterator<Map.Entry<String, TypeDeclarationCacheEntry>> it = oldTypesByName.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, TypeDeclarationCacheEntry> mapEnt = it.next();
            TypeDeclarationCacheEntry tdEnt = mapEnt.getValue();
            for (int k = 0; k < tdEnt.size(); k++) {
               TypeDeclaration td = tdEnt.get(k);
               if (td.isStarted())
                  td.refreshBoundTypes(flags);
            }
         }
      }
      else {
         Map<String, ILanguageModel> oldInactiveModels = (Map<String, ILanguageModel>) inactiveModelIndex.clone();
         for (ILanguageModel oldModel:oldInactiveModels.values()) {
            if (oldModel.isStarted() && !oldModel.isReplacedModel())
               oldModel.refreshBoundTypes(flags);
         }
      }
   }

   /**
    * When we are using the ClassFile stuff, it is easier to use Java native classes for these types.  Keeps us
    * from having to special case Integer to int etc. everyplace. 
    */
   private void initClassCache() {
      compiledClassCache.put("java.lang.Object", Object.class);
      compiledClassCache.put("java.lang.Byte", Byte.class);
      compiledClassCache.put("java.lang.Short", Short.class);
      compiledClassCache.put("java.lang.Integer", Integer.class);
      compiledClassCache.put("java.lang.Float", Float.class);
      compiledClassCache.put("java.lang.Double", Double.class);
      compiledClassCache.put("java.lang.Long", Long.class);
      compiledClassCache.put("java.lang.String", String.class);
      compiledClassCache.put("java.lang.Character", Character.class);
      compiledClassCache.put("java.lang.Boolean", Boolean.class);
      compiledClassCache.put("java.lang.Void", Void.class);
      compiledClassCache.put("java.lang.Enum", Enum.class);
   }

   private void resetClassCache() {
      if (otherClassCache != null)
         otherClassCache.clear();
      compiledClassCache.clear();
      initClassCache();
   }

   public DependencyFile readDependencies(File depFile, SrcEntry srcEnt) {
      Object result;
      DependencyFile deps = null;
      try {
         result = DependenciesLanguage.INSTANCE.parse(depFile);
         if (result instanceof ParseError) {
            System.err.println("*** Ignoring corrupt dependencies file: " + depFile + " " +
                               ((ParseError) result).errorStringWithLineNumbers(depFile));
            return null;
         }
         else
            deps = (DependencyFile) ParseUtil.getParseResult(result);

         for (DependencyEntry dent: deps.depList) {
            dent.layer = srcEnt.layer;
            if (!dent.isDirectory && dent.fileDeps != null) {
               for (LayerDependencies layerEnt: dent.fileDeps) {
                  Layer currentLayer = getLayerByName(layerEnt.layerName);
                  // Something in the layers we are using changed so recompute all
                  if (currentLayer == null) {
                     System.err.println("Warning: layer: " + layerEnt.layerName + " referenced in dependencies: " + depFile + " but is no longer a system layer.");
                     continue;
                  }

                  // We keep these sorted by the layer position for clarity in reading
                  layerEnt.position = currentLayer.layerPosition;
                  for (IString dependent:layerEnt.fileList) {
                     String dependentStr = dependent.toString();
                     File absSrcFile = currentLayer.findSrcFile(dependentStr, true);
                     if (absSrcFile == null)
                        dent.invalid = true;
                     else {
                        if (dent.srcEntries == null)
                           dent.srcEntries = new ArrayList<SrcEntry>();
                        dent.srcEntries.add(new SrcEntry(currentLayer, absSrcFile.getPath(), dependentStr));
                     }
                  }
               }
            }
         }
      }
      catch (IllegalArgumentException exc) {
         System.err.println("*** Error reading dependencies: " + exc);
      }
      return deps;
   }

   public void addAllFiles(Layer layer, Set<SrcEntry> toGenerate, Set<SrcEntry> allGenerated, Set<String> allGeneratedTypes, File srcDir, String srcDirName, String srcPath, BuildPhase phase, BuildState bd) {
      String [] fileNames = srcDir.list();
      if (fileNames == null) {
         error("Invalid src directory: " + srcDir);
         return;
      }
      for (int f = 0; f < fileNames.length; f++) {
         String fileName = fileNames[f];
         if (layer.excludedFile(fileName, srcPath))
            continue;
         IFileProcessor proc = getFileProcessorForFileName(FileUtil.concat(srcPath, fileName), FileUtil.concat(srcDirName, fileName), layer, phase, false, false);
         if (proc != null && !fileName.equals(layer.layerBaseName)) {
            SrcEntry prevEnt;
            SrcEntry newSrcEnt = new SrcEntry(layer, srcDirName, srcPath,  fileName, proc.getPrependLayerPackage());
            String srcTypeName = newSrcEnt.getTypeName();
            // Just in case we've previously skipped over a file for this type, need to add the most specific one.
            if ((prevEnt = bd.unchangedFiles.get(srcTypeName)) != null) {
               newSrcEnt = prevEnt;
            }
            if (!allGenerated.contains(newSrcEnt)) {
               allGenerated.add(newSrcEnt);
               allGeneratedTypes.add(newSrcEnt.getTypeName());
               addToGenerateList(toGenerate, newSrcEnt, srcTypeName);
            }
         }
         else {
            File subFile = new File(srcDirName, fileName);
            if (subFile.isDirectory()) {
               SrcEntry newSrcDirEnt = new SrcEntry(layer, srcDirName, srcPath, fileName, proc == null || proc.getPrependLayerPackage());
               if (!allGenerated.contains(newSrcDirEnt)) {
                  toGenerate.add(newSrcDirEnt); // Adding the directory here
                  allGenerated.add(newSrcDirEnt);
                  allGeneratedTypes.add(newSrcDirEnt.getTypeName());
               }
            }
         }
      }
   }

   private void addToGenerateList(Set<SrcEntry> toGenerate, SrcEntry newSrcEnt, String typeName) {
      toGenerate.add(newSrcEnt);
      changedTypes.add(typeName);
   }
   private void clearTransformedInLayer(SrcEntry newSrcEnt) {
      // The first time in the set of layered builds that we see a cached model which has been transformed before, clear that status
      // so that we only re-transform it once (unless something it depends on changes in subsequent layers).
      if (!allToGenerateFiles.contains(newSrcEnt)) {
         allToGenerateFiles.add(newSrcEnt);
         ILanguageModel cachedModel = modelIndex.get(newSrcEnt.absFileName);
         if (cachedModel instanceof JavaModel) {
            JavaModel jmodel = (JavaModel) cachedModel;
            if (jmodel.transformedInLayer != null)
               jmodel.transformedInLayer = null; // Forces this model to be viewed as changed - so it's stopped and restarted
         }
      }
   }

   public boolean needsGeneration(String fileName, Layer fromLayer, BuildPhase phase) {
      return getFileProcessorForFileName(fileName, fromLayer, phase) != null;
   }

   public void registerPatternFileProcessor(String pattern, IFileProcessor processor) {
      Layer layer = processor.getDefinedInLayer();
      registerPatternFileProcessor(pattern, processor, layer);
   }

   public void removeFileProcessor(String ext, Layer fromLayer) {
      LayerFileProcessor proc = new LayerFileProcessor();
      proc.definedInLayer = fromLayer;
      proc.disableProcessing = true;
      registerFileProcessor(ext, proc, fromLayer);
   }

   public void registerPatternFileProcessor(String pat, IFileProcessor processor, Layer fromLayer) {
      processor.setDefinedInLayer(fromLayer);
      processor.validate();
      filePatterns.put(Pattern.compile(pat), processor);
   }

   public void registerFileProcessor(String ext, IFileProcessor processor, Layer fromLayer) {
      processor.setDefinedInLayer(fromLayer);
      processor.validate();
      IFileProcessor[] procs = fileProcessors.get(ext);
      if (procs == null || fromLayer == null) {
         procs = new IFileProcessor[1];
         procs[0] = processor;
         fileProcessors.put(ext, procs);
      }
      else {
         IFileProcessor[] newProcs = new IFileProcessor[procs.length+1];
         int k = 0;
         boolean added = false;
         for (int i = 0; i < newProcs.length; i++) {
            if (!added && (k >= procs.length || fromLayer.layerPosition > procs[k].getLayerPosition())) {
               newProcs[i] = processor;
               added = true;
            }
            else
               newProcs[i] = procs[k++];
         }
         fileProcessors.put(ext, newProcs);
      }
      systemDetailsDisplayed = false;
   }

   public void cleanupLayerFileProcessors() {
      HashMap<String,IFileProcessor[]> newMap = new HashMap<String,IFileProcessor[]>();
      if (fileProcessors != null) {
         for (Map.Entry<String, IFileProcessor[]> procEnt : fileProcessors.entrySet()) {
            ArrayList<IFileProcessor> newProcs = null;
            IFileProcessor[] procs = procEnt.getValue();
            for (IFileProcessor proc : procs) {
               Layer l = proc.getDefinedInLayer();
               if (l == null || !l.removed) {
                  if (newProcs == null)
                     newProcs = new ArrayList<IFileProcessor>();
                  newProcs.add(proc);
               } else
                  proc.setDefinedInLayer(null);
            }
            if (newProcs != null)
               newMap.put(procEnt.getKey(), newProcs.toArray(new IFileProcessor[newProcs.size()]));
         }
      }
      fileProcessors = newMap;

      LinkedHashMap<Pattern,IFileProcessor> newFilePatterns = new LinkedHashMap<Pattern,IFileProcessor>();
      if (filePatterns != null && filePatterns.size() > 0) {
         for (Map.Entry<Pattern, IFileProcessor> ent : filePatterns.entrySet()) {
            Pattern patt = ent.getKey();
            IFileProcessor fproc = ent.getValue();
            Layer l = fproc.getDefinedInLayer();
            if (l == null || !l.activated)
               newFilePatterns.put(patt, fproc);
         }
      }
      filePatterns = newFilePatterns;
   }

   public IFileProcessor getFileProcessorForExtension(String ext) {
      return getFileProcessorForExtension(ext, null, false, null, null, false, false);
   }

   public IFileProcessor getFileProcessorForExtension(String ext, String fileName, boolean abs, Layer srcLayer, BuildPhase phase, boolean generatedFile, boolean includeDisabled) {
      IFileProcessor[] procs = fileProcessors.get(ext);
      if (procs != null) {
         for (IFileProcessor proc:procs) {
            if (phase == null || phase == proc.getBuildPhase()) {
               if (fileName != null) {
                  Layer procLayer = proc.getDefinedInLayer();
                  // Never return processors from excluded layers since they are not active in this runtime
                  if (procLayer != null && procLayer.excluded)
                     continue;
                  if (includeDisabled)
                     return proc;
                  switch (proc.enabledForPath(fileName, srcLayer, abs, generatedFile)) {
                     case Enabled:
                        return proc;
                     case Disabled:
                        return null;
                     case NotEnabled:
                        continue;
                  }
               }
               if (includeDisabled)
                  return proc;
               switch (proc.enabledFor(srcLayer)) {
                  case Enabled:
                     return proc;
                  case Disabled:
                     return null;
                  case NotEnabled:
                     // Try the next one
               }
            }
         }
      }
      // The standard languages are processed during the process phase by default
      if (phase == null || phase == BuildPhase.Process) {
         IFileProcessor proc = Language.getLanguageByExtension(ext);
         if (proc == null || (!includeDisabled && fileName != null && proc.enabledForPath(fileName, srcLayer, abs, generatedFile) == IFileProcessor.FileEnabledState.Disabled))
            return null;
         return proc;
      }
      return null;
   }

   public IFileProcessor getFileProcessorForSrcEnt(SrcEntry srcEnt, BuildPhase phase, boolean generatedFile) {
      return getFileProcessorForFileName(srcEnt.relFileName, srcEnt.absFileName, srcEnt.layer, phase, generatedFile, false);
   }

   public IFileProcessor getFileProcessorForFileName(String fileName, Layer fromLayer, BuildPhase phase) {
      return getFileProcessorForFileName(fileName, null, fromLayer, phase, false, false);
   }

   public IFileProcessor getFileProcessorForFileName(String fileName, String absFileName, Layer fromLayer, BuildPhase phase, boolean generatedFile, boolean includeDisabled) {
      IFileProcessor res;
      if (filePatterns.size() > 0) {
         for (Map.Entry<Pattern,IFileProcessor> ent:filePatterns.entrySet()) {
            Pattern patt = ent.getKey();
            // If either just the file name part or the entire directory matches use this pattern
            if (patt.matcher(fileName).matches() || patt.matcher(FileUtil.getFileName(fileName)).matches()) {
               res = ent.getValue();
               Layer resLayer = res.getDefinedInLayer();
               if (resLayer == null || !resLayer.excluded) {
                  if (phase == null || res.getBuildPhase() == phase)
                     return res;
               }
            }
         }
      }
      String ext = FileUtil.getExtension(fileName);
      if (absFileName != null)
         return getFileProcessorForExtension(ext, absFileName, true, fromLayer, phase, generatedFile, includeDisabled);
      else
         return getFileProcessorForExtension(ext, fileName, false, fromLayer, phase, generatedFile, includeDisabled);
   }

   public Object parseSrcFile(SrcEntry srcEnt, boolean reportErrors) {
      return parseSrcFile(srcEnt, srcEnt.isLayerFile(), true, false, reportErrors, false);
   }

   /**
    * For IDE-like operations where we need to parse a file but using the current-in memory copy for fast model validation.  This method
    * should not alter the runtime code model
    */
   public Object parseSrcBuffer(SrcEntry srcEnt, boolean enablePartialValues, String buffer, boolean dummy) {
      long modTimeStart = srcEnt.getLastModified();
      IFileProcessor processor = getFileProcessorForSrcEnt(srcEnt, null, false);
      if (processor == null && srcEnt.layer != null && !srcEnt.layer.isStarted()) {
         // For the sctjs extension, we need to start the layer before the processor is registered
         srcEnt.layer.ensureStarted(false);
         processor = getFileProcessorForSrcEnt(srcEnt, null, false);
      }
      if (processor instanceof Language) {
         Language lang = (Language) processor;
         Object result = lang.parseString(srcEnt.absFileName, buffer, enablePartialValues);
         if (result instanceof ParseError) {
            if (enablePartialValues) {
               Object val = ((ParseError) result).getRootPartialValue();
               initNewBufferModel(srcEnt, val, modTimeStart, dummy, false);
            }
            return result;
         }
         else {
            Object modelObj = ParseUtil.nodeToSemanticValue(result);

            if (!(modelObj instanceof IFileProcessorResult))
               return modelObj;

            initNewBufferModel(srcEnt, modelObj, modTimeStart, dummy, false);

            return modelObj;
         }
      }
      throw new IllegalArgumentException(("No language processor for file: " + srcEnt.relFileName));
   }

   /**
    * For IDE-like operations where we need to parse a file but using the current-in memory copy for fast model validation.  This method
    * should not alter the runtime code model
    */
   public Object reparseSrcBuffer(SrcEntry srcEnt, ILanguageModel oldModel, boolean enablePartialValues, String buffer, boolean dummy) {
      IParseNode pnode = oldModel.getParseNode();
      if (pnode == null)
         return parseSrcBuffer(srcEnt, enablePartialValues, buffer, dummy);

      long modTimeStart = srcEnt.getLastModified();
      IFileProcessor processor = getFileProcessorForSrcEnt(srcEnt, null, false);
      if (processor instanceof Language) {
         Language lang = (Language) processor;
         Object result = ParseUtil.reparse(oldModel.getParseNode(), buffer);
         if (result instanceof ParseError) {
            if (enablePartialValues) {
               Object val = ((ParseError) result).getRootPartialValue();
               initNewBufferModel(srcEnt, val, modTimeStart, dummy, true);
            }
            return result;
         }
         else {
            Object modelObj = ParseUtil.nodeToSemanticValue(result);

            if (!(modelObj instanceof IFileProcessorResult))
               return modelObj;

            if (modelObj instanceof JavaModel) {
               TypeDeclaration td = ((JavaModel) modelObj).getModelTypeDeclaration();
               if (td != null && td.typeName == null)
                  System.err.println("**** Reparsed buffer has null type name!");
            }

            initNewBufferModel(srcEnt, modelObj, modTimeStart, dummy, true);

            return modelObj;
         }
      }
      throw new IllegalArgumentException(("No language processor for file: " + srcEnt.relFileName));
   }

   private void initNewBufferModel(SrcEntry srcEnt, Object modelObj, long modTimeStart, boolean dummy, boolean incremental) {
      if (modelObj instanceof IFileProcessorResult) {
         IFileProcessorResult res = (IFileProcessorResult) modelObj;
         SrcEntry oldSrcEnt = res.getSrcFile();
         if (oldSrcEnt == null)
            res.addSrcFile(srcEnt);
         else if (!oldSrcEnt.absFileName.equals(srcEnt.absFileName))
            System.out.println("*** error mismatching files");

         if (res instanceof ILanguageModel) {
            ILanguageModel newModel = (ILanguageModel) res;
            if (!dummy)
               markBeingLoadedModel(srcEnt, newModel);
            else
               newModel.markAsTemporary();

            // We want to immediate clone and update the model in the other systems as well - reduces parsing
            // overhead but also the types in the other layered systems need to be updated eventually
            ArrayList<JavaModel> clonedModels = null;
            boolean activated = srcEnt.layer != null && srcEnt.layer.activated;
            if (!dummy && !incremental && options.clonedParseModel && peerSystems != null) {
               for (LayeredSystem peerSys:peerSystems) {
                  Layer peerLayer = activated ? peerSys.getLayerByName(srcEnt.layer.layerUniqueName) : srcEnt.layer == null ? null : peerSys.lookupInactiveLayer(srcEnt.layer.getLayerName(), false, true);
                  // does this layer exist in the peer runtime and is it started and still open.  Don't do this if we are initializing the type index for a specific runtime since we have not refreshed the bound types in the other runtime after opening the layers
                  if (peerLayer != null && peerLayer.started && !peerLayer.closed && !initializingTypeIndexes) {
                     if (peerSys.beingLoaded.get(srcEnt.absFileName) == null) {
                        ILanguageModel oldModel = activated ? peerSys.modelIndex.get(srcEnt.absFileName) : peerSys.inactiveModelIndex.get(srcEnt.absFileName);
                        // Only clone a model if there's no cached model or the cached model is older
                        if (oldModel == null || oldModel.getLastModifiedTime() < modTimeStart) {
                           PerfMon.start("cloneModelBuffer");
                           if (clonedModels == null)
                              clonedModels = new ArrayList<JavaModel>();
                           clonedModels.add(peerSys.cloneModel(peerLayer, (JavaModel) newModel));
                           PerfMon.end("cloneModelBuffer");
                        }
                     }
                  }
               }
            }

            // Need to be careful because when we call 'init' on the JavaModel it can throw a process cancelled exception.
            // In that case, we still need to set the layer and add the model to the index. We'll try again later to call initModel
            // on that model before we use it but if this stuff is not set, we won't reset it.
            try {
               if (clonedModels != null)
                  initClonedModels(clonedModels, srcEnt, modTimeStart, true);
            }
            finally {
               try {
                  initModel(srcEnt.layer, modTimeStart, newModel, srcEnt.isLayerFile(), false);
               }
               finally {
                  if (!dummy)
                     beingLoaded.remove(srcEnt.absFileName);

                  // If initModel is aborted, we still want to register the models into the name space so things are not left in a half-finished situation
                  if (clonedModels != null)
                     initClonedModels(clonedModels, srcEnt, modTimeStart, false);
               }
            }
         }
      }
   }

   public Object parseSrcFile(SrcEntry srcEnt, boolean isLayer, boolean checkPeers, boolean enablePartialValues, boolean reportErrors, boolean temporary) {
      // Once we parse one source file that's not an annotation layer in java.util or java.lang we cannot by-pass the src mechanism for the final js.sys java.util or java.lang classes - they have interdependencies.
      if (srcEnt.layer != null && !srcEnt.layer.annotationLayer) {
         /*
         if (srcEnt.absFileName.contains("java/util"))
            System.out.println("*** debug point for loading java util classes as source ");
         if (srcEnt.absFileName.contains("java/lang"))
            System.out.println("*** debug point for loading java lang classes as source");
         */

         String srcEntPkg = CTypeUtil.getPackageName(srcEnt.getTypeName());
         if (srcEntPkg != null && !temporary) {
            if (allOrNoneFinalPackages.contains(srcEntPkg)) {
               overrideFinalPackages.addAll(allOrNoneFinalPackages);
            }
         }
      }

      IFileProcessor processor = getFileProcessorForSrcEnt(srcEnt, null, false);
      if (processor != null) {
         /*
         if (srcEnt.absFileName.contains("coreRuntime") && srcEnt.absFileName.contains("Bind"))
            System.out.println("---");
         */

         long modTimeStart = srcEnt.getLastModified();
         Object modelObj = options.modelCacheEnabled && processor instanceof Language ? LayerUtil.restoreModel(this, (Language) processor, srcEnt, modTimeStart) : null;

         boolean restored = modelObj != null;
         Object result = restored && !options.lazyParseNodeCache ? LayerUtil.restoreParseNodes(this, (Language) processor, srcEnt, modTimeStart, (ISemanticNode) modelObj) : null;

         if (options.verbose && !isLayer && !srcEnt.relFileName.equals("sc/layer/BuildInfo.sc") && (processor instanceof Language || options.sysDetails))
            verbose((restored ? "Restored: " : "Reading: ") + srcEnt.absFileName + " for runtime: " + getProcessIdent());

         try {
            if (modelObj == null) {
               result = processor.process(srcEnt, enablePartialValues);
               if (result instanceof ParseError) {
                  if (reportErrors) {
                     error("File: " + srcEnt.absFileName + ": " + ((ParseError) result).errorStringWithLineNumbers(new File(srcEnt.absFileName)));

                     if (currentBuildLayer != null && currentBuildLayer.buildState != null) {
                        BuildState bd = currentBuildLayer.buildState;
                        anyErrors = true;
                        bd.addErrorFile(srcEnt);
                     }
                  }
                  return result;
               }
               else if (result == IFileProcessor.FILE_OVERRIDDEN_SENTINEL) {
                  return IFileProcessor.FILE_OVERRIDDEN_SENTINEL;
               }
               modelObj = ParseUtil.nodeToSemanticValue(result);
            }

            if (!(modelObj instanceof IFileProcessorResult)) {
               System.err.println("*** Error - invalid model restored from modelCache");
               return null;
            }
            IFileProcessorResult res = (IFileProcessorResult) modelObj;
            res.addSrcFile(srcEnt);
            if (srcEnt.getLastModified() != modTimeStart) {
               System.err.println("File: " + srcEnt.absFileName + " changed during parsing");
               return new ParseError("File changed during parsing", null, 0, 0);
            }
            
            if (modelObj instanceof ILanguageModel && !temporary) {
               ILanguageModel model = (ILanguageModel) modelObj;

               if (!restored && options.modelCacheEnabled && processor instanceof Language) {
                  LayerUtil.saveModelCache(this, srcEnt, model, (Language) processor);
               }

               markBeingLoadedModel(srcEnt, model);

               try {
                  boolean activatedLayer = srcEnt.layer != null && srcEnt.layer.activated;

                  ILanguageModel oldModel = activatedLayer ? modelIndex.get(srcEnt.absFileName) : inactiveModelIndex.get(srcEnt.absFileName);
                  if (oldModel instanceof JavaModel && oldModel != model && model instanceof JavaModel) {
                     ((JavaModel) oldModel).replacedByModel = (JavaModel) model;
                  }

                  ArrayList<JavaModel> clonedModels = null;

                  if (options.clonedParseModel && peerSystems != null && !isLayer && model instanceof JavaModel && checkPeers && srcEnt.layer != null) {
                     for (LayeredSystem peerSys : peerSystems) {
                        Layer peerLayer = peerSys.getLayerByName(srcEnt.layer.layerUniqueName);
                        // does this layer exist in the peer runtime
                        if (peerLayer != null) {
                           // If the peer layer is not started, we can't clone
                           if (!peerSys.isModelLoaded(srcEnt.absFileName)) {
                              if (peerLayer.started) {
                                 if (options.verbose)
                                    verbose("Copying for runtime: " + peerSys.getRuntimeName());
                                 if (clonedModels == null)
                                    clonedModels = new ArrayList<JavaModel>();
                                 PerfMon.start("cloneForRuntime");
                                 clonedModels.add(peerSys.cloneModel(peerLayer, (JavaModel) model));
                                 PerfMon.end("cloneForRuntime");
                              } else if (options.verbose)
                                 verbose("Not copying: " + srcEnt.absFileName + " for runtime: " + peerSys.getRuntimeName() + " peer layer not started.");
                           }
                        }
                     }
                  }

                  if (clonedModels != null)
                     initClonedModels(clonedModels, srcEnt, modTimeStart, true);

                  initModel(srcEnt.layer, modTimeStart, model, isLayer, false);

                  if (clonedModels != null)
                     initClonedModels(clonedModels, srcEnt, modTimeStart, false);
               }
               finally {
                  beingLoaded.remove(srcEnt.absFileName);
               }
            }
            else if (modelObj instanceof ILanguageModel && temporary) {
               initModel(srcEnt.layer, modTimeStart, (ILanguageModel) modelObj, isLayer, false);
            }
            return modelObj;
         }
         catch (IllegalArgumentException exc) { // file not found - this happens for unsaved models which get added to the layer index before they have been saved.
            if (new File(srcEnt.absFileName).canRead()) {
               error("Exception initializing model: " + exc + ": " + srcEnt);
               exc.printStackTrace();
            }
            else if (options.verbose) {
               verbose("Exception initializing model: " + exc + ": " + srcEnt);
               exc.printStackTrace();
            }
            //error("SrcFile no longer exists: " + srcEnt + ": " + exc + "");
         }
      }
      return null;
   }

   public ILanguageModel getCachedModel(SrcEntry srcEnt, boolean activeOnly) {
      ILanguageModel m;
      String fn = srcEnt.absFileName;
      if (activeOnly || (srcEnt.layer != null && srcEnt.layer.activated))
         m = modelIndex.get(fn);
      else
         m = inactiveModelIndex.get(fn);

      if (m == null) {
         m = beingLoaded.get(srcEnt.absFileName);
         if (m != null) {
            if (m.getLayer() != srcEnt.layer)
               m = null;
         }
      }
      return m;
   }

   public ILanguageModel getCachedModelByPath(String absFileName, boolean active) {
      ILanguageModel m = beingLoaded.get(absFileName);
      if (m != null && m.getLayer() != null && m.getLayer().activated == active)
         return m;
      return active ? modelIndex.get(absFileName) : inactiveModelIndex.get(absFileName);
   }

   /** Returns the cached active model for the specified runtime processor */
   public ILanguageModel getActiveModel(SrcEntry srcEnt, IRuntimeProcessor proc) {
      ILanguageModel m;
      String fn = srcEnt.absFileName;
      if (DefaultRuntimeProcessor.compareRuntimes(proc, runtimeProcessor)) {
         m = modelIndex.get(fn);
         if (m != null)
            return m;
      }
      if (!peerMode && peerSystems != null) {
         for (LayeredSystem peerSys:peerSystems) {
            if (DefaultRuntimeProcessor.compareRuntimes(peerSys.runtimeProcessor, proc)) {
               m = peerSys.modelIndex.get(fn);
               if (m != null)
                  return m;
            }
         }
      }
      return null;
   }

   public boolean hasAnnotatedModel(SrcEntry srcEnt) {
      String fn = srcEnt.absFileName;
      ILanguageModel m = modelIndex.get(fn);
      if (m != null && m.getUserData() != null)
         return true;
      m = inactiveModelIndex.get(fn);
      if (m != null && m.getUserData() != null)
         return true;
      return false;
   }

   private ILanguageModel getCachedAnnotatedModel(SrcEntry srcEnt, boolean checkPeers) {
      String fn = srcEnt.absFileName;
      ILanguageModel m = modelIndex.get(fn);
      if (m != null && m.getUserData() != null) {
         if (externalModelIndex != null && !externalModelIndex.isValidModel(m))
            m = null;
         else
            return m;
      }
      m = inactiveModelIndex.get(fn);
      if (m != null && m.getUserData() != null) {
         if (externalModelIndex != null && !externalModelIndex.isValidModel(m)) {
            m = null;
         }
         else
            return m;
      }
      if (checkPeers && peerSystems != null) {
         for (LayeredSystem peerSys:peerSystems) {
            m = peerSys.getCachedAnnotatedModel(srcEnt, false);
            if (m != null)
               return m;
         }
      }
      return null;
   }

   public ILanguageModel getAnnotatedModel(SrcEntry srcEnt) {
      ILanguageModel m;
      acquireDynLock(false);
      try {
         m = getCachedAnnotatedModel(srcEnt, true);
         if (m != null)
            return m;
         // Look up an annotated version through the external model index - we don't care if it's active or inactive when using this api since
         // the editor might be displaying the wrong one
         m = getAnnotatedLanguageModel(srcEnt, true);
         if (m != null) {
            return m;
         }
         return null;
      }
      finally {
         releaseDynLock(false);
      }
   }

   public void markBeingLoadedModel(SrcEntry srcEnt, ILanguageModel model) {
      beingLoaded.put(srcEnt.absFileName, model);
   }

   public void clearBeingLoadedModel(SrcEntry srcEnt) {
      beingLoaded.remove(srcEnt.absFileName);
   }

   public void addCachedModel(ILanguageModel model) {
      SrcEntry srcFile = model.getSrcFile();
      if (srcFile != null) {
         String fn = srcFile.absFileName;
         Layer layer = srcFile.layer;
         beingLoaded.remove(fn);
         updateModelIndex(layer, model, fn);
      }
      else
         warning("no src file for model");
   }

   boolean isModelLoaded(String absFileName) {
      return modelIndex.get(absFileName) != null || beingLoaded.get(absFileName) != null;
   }

   void initModel(Layer srcEntLayer, long modTimeStart, ILanguageModel model, boolean isLayer, boolean switchLayers) {
      model.setLayeredSystem(this);
      if (switchLayers)
         ((JavaModel) model).switchLayers(srcEntLayer);
      else
         model.setLayer(srcEntLayer);
      model.setLastModifiedTime(modTimeStart);
      model.setLastAccessTime(System.currentTimeMillis());

      if (isLayer && model instanceof JavaModel)
         ((JavaModel) model).isLayerModel = true;

      // initialize the model
      ParseUtil.initComponent(model);
   }

   JavaModel cloneModel(Layer peerLayer, JavaModel orig) {
      JavaModel copy = (JavaModel) orig.deepCopy(ISemanticNode.CopyParseNode,  null);
      copy.setLayeredSystem(this);
      copy.switchLayers(peerLayer);

      return copy;
   }

   void initCloneModel(JavaModel copy, SrcEntry origSrcEnt, long modTimeStart) {
      Layer peerLayer = copy.layer;
      SrcEntry copySrcEnt = new SrcEntry(peerLayer, origSrcEnt.absFileName, origSrcEnt.relFileName, origSrcEnt.prependPackage);

      if (beingLoaded.get(copySrcEnt.absFileName) != null || isModelLoaded(copySrcEnt.absFileName)) {
         if (options.verbose)
            verbose("  - discarding cloned model - loaded lazily in order to process the base type");
         return;
      }

      if (peerLayer.started) {
         beingLoaded.put(copySrcEnt.absFileName, copy);

         try {
            initModel(peerLayer, modTimeStart, copy, false, true);
         }
         finally {
            beingLoaded.remove(copySrcEnt.absFileName);

            // Even if the init model throws a cancelled exception, make sure to add the model so all we need to do is to re-init it later
            addNewModel(copy, peerLayer.getNextLayer(), null, null, false, false);
         }
      }
      else
         System.err.println("*** Error cloned model in layer not started");
   }

   void initClonedModels(ArrayList<JavaModel> copyModels, SrcEntry origSrcEnt, long modTimeStart, boolean preStart) {
      // Need to initialize these in their own layered system.
      for (JavaModel copy:copyModels) {
         LayeredSystem sys = copy.layeredSystem;
         if (sys.enableRemoteMethods == preStart)
            sys.initCloneModel(copy, origSrcEnt, modTimeStart);
      }
   }

   private boolean refreshScheduled = false;

   public void scheduleRefresh() {
      if (DynUtil.frameworkScheduler == null) {
         System.out.println("No framework scheduler - not performing scheduleRefresh()");
         return;
      }
      if (peerMode) {
         getMainLayeredSystem().scheduleRefresh();
         return;
      }
      if (refreshScheduled)
         return;
      refreshScheduled = true;
      DynUtil.invokeLater(new Runnable() {
         public void run() {
            refreshRuntimes(false); // TODO: should we also refresh the active runtimes here/
         }
      }, 0);
   }

   /**
    * This call will update the models in all of the runtimes for either active or inactive types.
    *  Even if you specify active, this will not rebuild the active layers.
    */
   public SystemRefreshInfo refreshRuntimes(boolean active) {
      updatePeerModels(true);

      acquireDynLock(false);
      SystemRefreshInfo sysInfo;
      try {
         sysInfo = refreshSystem(false, active);
         ArrayList<SystemRefreshInfo> peerChangedInfos = new ArrayList<SystemRefreshInfo>();
         if (peerSystems != null) {
            int sz = peerSystems.size();
            for (int i = 0; i < sz; i++) {
               LayeredSystem peer = peerSystems.get(i);
               setCurrent(peer);
               // TODO: right now, we ignore the change models in other systems.  These are used by EditorContext to do
               // updating of any errors detected in the models.  Currently EditorContext only knows about one layered system
               // but will need to know about all of them to manage errors, switching views etc. correctly.
               peerChangedInfos.add(peer.refreshSystem(false, active));
            }
            setCurrent(this);
         }
         completeRefresh(sysInfo, peerChangedInfos, active, true);
      }
      finally {
         releaseDynLock(false);
      }
      return sysInfo;
   }

   public SystemRefreshInfo refreshSystem() {
      return refreshSystem(true, true);
   }

   public SystemRefreshInfo refreshSystem(boolean refreshPeers) {
      return refreshSystem(refreshPeers, true);
   }

   /**
    * For CFClasses and cached null references we can quickly refresh the contents.  Not currently trying to refresh
    * java.lang.Class instances in the compiledClassCache since those are synchronized with the class loader and so cannot
    * be as easily refreshed
    */
   public void refreshClassCache() {
      if (otherClassCache == null)
         return;
      Iterator<Map.Entry<String,Object>> otherIt = otherClassCache.entrySet().iterator();
      while (otherIt.hasNext()) {
         Map.Entry<String,Object> otherEnt = otherIt.next();
         Object val = otherEnt.getValue();
         if (val == NullClassSentinel.class)
            otherIt.remove();
         else if (val instanceof CFClass) {
            if (((CFClass) val).fileChanged()) {
               otherIt.remove();
               if (options.verbose)
                  verbose("Removing changed class from class cache: " + val);
            }
         }
      }
   }

   public SystemRefreshInfo refreshSystem(boolean refreshPeers, boolean active) {
      // Before we parse any files, need to clear out any invalid models
      // TODO: remove this cleanTypeCache call unless we are not using clonedTransformedModels
      if (active)
         cleanTypeCache();

      // Clear out any compiled classes which might have changed as a result of being compiled elsewhere - this currently does not support java.lang.Class since that requires messing with class loaders and instances.  It only works with CFClass's.
      refreshClassCache(); // TODO: don't think this works

      // We may start some new models here so reset this flag
      allTypesProcessed = false;

      UpdateInstanceInfo updateInfo = newUpdateInstanceInfo();
      List<Layer.ModelUpdate> changedModels = refreshChangedModels(updateInfo, active);

      SystemRefreshInfo sysInfo = new SystemRefreshInfo();
      sysInfo.updateInfo = updateInfo;
      sysInfo.changedModels = changedModels;

      if (refreshPeers && peerSystems != null) {
         for (LayeredSystem peerSys:peerSystems) {
            peerSys.refreshSystem(false, active);
         }
      }

      return sysInfo;
   }

   /**
    * Refreshes the system for the javascript client.  Returns the JS to eval on the client to do the refresh over there.
    * This will at the same time update the server.
    */
   public CharSequence refreshJS(long fromTime) {
      JSRuntimeProcessor proc = (JSRuntimeProcessor) getRuntime("js");
      if (proc == null) // no javascript layers here - nothing to refresh?
         return "";

      // Let the JS engine initiate the refresh since it knows how to convert it to Javascript, needed to do the refresh of the client.
      return proc.getSystemUpdates(fromTime);
   }

   public void rebuildAll() {
      acquireDynLock(false);
      try {
         options.buildAllFiles = true;
         refreshSystem(true, true);
         resetBuild(true);
         buildSystem(null, false, false);
         if (peerSystems != null) {
            for (LayeredSystem peer:peerSystems) {
               peer.buildSystem(null, false, false);
               if (peer.anyErrors)
                  anyErrors = true;
            }
         }
         buildCompleted(true);
         setCurrent(this);
      }
      finally {
         releaseDynLock(false);
      }
   }

   @Remote(remoteRuntimes="js")
   public boolean rebuild() {
      return rebuild(false);
   }

   public void clearBuildAllFiles() {
      options.buildAllFiles = false;

      // We may have set buildAllFiles in the layer on the first run because the BuildInfo was bad or whatever.
      for (Layer layer:layers)
         layer.buildAllFiles = false;

      if (peerSystems != null) {
         for (LayeredSystem peerSystem:peerSystems) {
            for (Layer layer:peerSystem.layers)
               layer.buildAllFiles = false;
         }
      }
   }

   @Remote(remoteRuntimes="js")
   public boolean rebuild(boolean forceBuild) {
      acquireDynLock(false);
      boolean any = false;

      if (systemCompiled && !options.rebuildAllFiles) {
         clearBuildAllFiles();
      }
      buildingSystem = true;
      if (peerSystems != null && !peerMode) {
         for (LayeredSystem peer:peerSystems) {
            peer.buildingSystem = true;
         }
      }

      boolean initialBuild;
      SystemRefreshInfo sysInfo = null;
      try {
         PerfMon.start("rebuildSystem");
         List<Layer.ModelUpdate> changes = null;
         ArrayList<SystemRefreshInfo> peerRefreshInfos = null;

         // If we've been through here at least once...
         if (needsRefresh || anyErrors) {
            initialBuild = false;

            // When using an IDE, it will handle the refresh for us.  We learn about changes in the getLanguageModel method and apply
            // them there.
            // First we have to refresh the models in all systems so we have a consistent view of the new types across all
            // runtimes.  That way synchronization can be computed accurately for the new changes.
            sysInfo = refreshSystem(false, true);
            changes = sysInfo.changedModels;
            any = changes != null && changes.size() > 0;
            peerRefreshInfos = !peerMode ? new ArrayList<SystemRefreshInfo>() : null;
            if (peerSystems != null && !peerMode) {
               for (LayeredSystem peer:peerSystems) {
                  peer.buildingSystem = true;
                  peerRefreshInfos.add(peer.refreshSystem(false, true));
               }
            }

            // Now we need to validate and process the changed models - not refreshing the instances yet because they
            // need to be transformed.
            completeRefresh(sysInfo, peerRefreshInfos, true, false);
         }
         else {
            initialBuild = true;
         }

         boolean reset = false;
         if (initialBuild || anyCompiledChanged(changes) || forceBuild) {
            if (!initialBuild) {
               reset = true;
               resetBuild(true);
            }
            buildSystem(null, false, false);
         }
         else if (!options.testVerifyMode) {
            verbose("No changed files detected - skipping buildSystem");
         }

         if (peerSystems != null && !peerMode) {
            int i = 0;
            for (LayeredSystem peer:peerSystems) {
               peer.buildingSystem = true;
               try {
                  SystemRefreshInfo peerSysInfo = peerRefreshInfos == null ? null : peerRefreshInfos.get(i);
                  List<Layer.ModelUpdate> peerChangedModels = peerSysInfo == null ? null : peerSysInfo.changedModels;
                  any = any || (peerChangedModels != null && peerChangedModels.size() > 0);
                  if (initialBuild || peer.anyCompiledChanged(peerChangedModels) || forceBuild) {
                     if (!reset && !initialBuild)
                        peer.resetBuild(false);
                     peer.buildSystem(null, false, false);
                     if (peer.anyErrors)
                        anyErrors = true;
                  }
               }
               finally {
                  peer.buildingSystem = false;
               }
               i++;
            }
         }
         // Now that we've finished transforming and rebuilding all of the models, we can update the types and instances
         // by calling out to the runtime
         updateRuntime(sysInfo, peerRefreshInfos);
         buildCompleted(true);
         setCurrent(this);
      }
      finally {
         PerfMon.end("rebuildSystem");
         buildingSystem = false;
         releaseDynLock(false);
      }
      return any;
   }

   private void completeRefresh(SystemRefreshInfo sysInfo, ArrayList<SystemRefreshInfo> peerChangedInfos, boolean active, boolean updateRuntime) {
      List<Layer.ModelUpdate> changes = sysInfo.changedModels;
      if (changes == null)
         return;

      for (Layer.ModelUpdate upd:changes) {
         ILanguageModel oldModel = upd.oldModel;
         Object newModel = upd.changedModel;
         if (oldModel instanceof JavaModel && newModel instanceof JavaModel) {
            ((JavaModel) oldModel).completeUpdateModel((JavaModel) newModel, updateRuntime);
         }
         else if (upd.removed) {
            removeModel(upd.oldModel, true);
         }
      }
      if (peerSystems != null && peerChangedInfos != null) {
         int sz = peerSystems.size();
         for (int i = 0; i < sz; i++) {
            LayeredSystem peer = peerSystems.get(i);
            setCurrent(peer);
            if (i < peerChangedInfos.size())
               peer.completeRefresh(peerChangedInfos.get(i), null, active, false);
         }
         setCurrent(this);
      }

      if (changes.size() > 0) {
         // Once we've refreshed some of the models in the system, we now need to go and update the type references globally to point to the new references
         // TODO: we are doing this twice now if we need to rebuild the system as well as just refreshing the types - maybe eliminate this one in that case?
         refreshBoundTypes(ModelUtil.REFRESH_TYPEDEFS, active);
      }

      if (updateRuntime && active && !options.compileOnly)
         updateRuntime(sysInfo, peerChangedInfos);
   }


   private void processStoppedTypes(SystemRefreshInfo sysInfo, ArrayList<SystemRefreshInfo> peerRefreshInfos) {
      if (stoppedTypes != null) {
         if (sysInfo == null) {
            return;
         }
         for (Map.Entry<String,TypeDeclaration> ent:stoppedTypes.entrySet()) {
            String typeName = ent.getKey();
            TypeDeclaration oldType = ent.getValue();
            TypeDeclaration newType = (TypeDeclaration) getSrcTypeDeclaration(typeName, null, true, false, true, oldType.getLayer(), false);
            if (newType != null && newType != oldType) {
               sysInfo.updateInfo.typeChanged(oldType, newType);
            }
            else if (newType == null) {
               sysInfo.updateInfo.typeRemoved(oldType);
            }
         }
         stoppedTypes = null;
      }
   }
   private void updateRuntime(SystemRefreshInfo sysInfo, ArrayList<SystemRefreshInfo> peerRefreshInfos) {
      processStoppedTypes(sysInfo, peerRefreshInfos);

      ExecutionContext ctx = new ExecutionContext(this);
      //allTypesProcessed = true;
      if (sysInfo == null || sysInfo.updateInfo == null)
         return;
      setCurrent(this);
      sysInfo.updateInfo.updateInstances(ctx);

      if (peerSystems != null && peerRefreshInfos != null) {
         int sz = peerSystems.size();
         for (int i = 0; i < sz; i++) {
            LayeredSystem peer = peerSystems.get(i);
            if (i < peerRefreshInfos.size()) {
               setCurrent(peer);
               peer.updateRuntime(peerRefreshInfos.get(i), null);
            }
         }
         setCurrent(this);
      }
   }

   public void rebuildSystem() {
      acquireDynLock(false);
      try {
         buildSystem(null, false, false);
         buildCompleted(false);
      }
      finally {
         releaseDynLock(false);
      }
   }

   private boolean anyCompiledChanged(List<Layer.ModelUpdate> changes) {
      if (changes == null)
         return false;
      for (Layer.ModelUpdate change:changes) {
         if (change.changedModel instanceof JavaModel) {
            JavaModel changedModel = (JavaModel) change.changedModel;
            if (!changedModel.isDynamicType())
               return true;
         }
         else if (change.changedModel == null && change.removed && (change.oldModel instanceof JavaModel) && ((JavaModel) change.oldModel).isDynamicType())
            return true;
      }
      return false;
   }

   public void resetBuild(boolean doPeers) {
      anyErrors = false;
      viewedErrors.clear();
      systemCompiled = false;
      changedDirectoryIndex.clear();
      // Need to restart all models that were previously loaded since we are building all files.
      if (options.buildAllFiles)
         lastChangedModelTime = System.currentTimeMillis();

      // When resetting the build if we are building all files and there are cached models, restart them all once.  We could clean out the model index
      // but this way, we avoid having to reparse everything but still process the build the same way as a clean build.
      if (options.buildAllFiles) {
         for (ILanguageModel oldModel : modelIndex.values()) {
            if (oldModel instanceof JavaModel)
               ((JavaModel) oldModel).needsRestart = true;
         }
      }

      if (layers != null) {
         for (int i = 0; i < layers.size(); i++) {
            Layer l = layers.get(i);
            BuildState bd = l.buildState;
            if (bd != null) {
               bd.processPhase.reset();
               bd.prepPhase.reset();
               bd.errorFiles = l.lastBuildState != null ? l.lastBuildState.errorFiles : new ArrayList<SrcEntry>();
               bd.anyError = false;
            }
            l.compiled = false;
         }
      }
      if (fileProcessors != null) {
         for (IFileProcessor[] fps : fileProcessors.values()) {
            for (IFileProcessor fp : fps) {
               fp.resetBuild();
            }
         }
      }

      if (filePatterns  != null) {
         for (IFileProcessor fp : filePatterns.values()) {
            fp.resetBuild();
         }
      }

      if (doPeers && peerSystems != null) {
         for (LayeredSystem peer:peerSystems) {
            peer.resetBuild(false);
         }
      }
   }

   /**
    * Looks for changes to any source files.  This is a global operation and is pretty slow... eventually we'll hook into
    * a file system watcher like Java7 or the fsnotifier program from intelliJ which lets you monitor file system changes
    * incrementally.
    * TODO: instead of using a file watcher, how about just calling this at most once every few seconds?
    */
   public List<Layer.ModelUpdate> refreshChangedModels(UpdateInstanceInfo updateInfo, boolean active) {
      ExecutionContext ctx = new ExecutionContext(this);
      long changedModelStartTime = System.currentTimeMillis();
      ArrayList<Layer.ModelUpdate> refreshedModels = new ArrayList<Layer.ModelUpdate>();
      if (active) {
         for (int i = 0; i < layers.size(); i++) {
            Layer l = layers.get(i);
            // NOTE: l.refresh here can remove the layer from layers
            l.refresh(lastRefreshTime, ctx, refreshedModels, updateInfo, true);
         }
      }
      else {
         for (int i = 0; i < inactiveLayers.size(); i++) {
            Layer l = inactiveLayers.get(i);
            l.refresh(lastRefreshTime, ctx, refreshedModels, updateInfo, false);
         }
      }
      if (refreshedModels.size() > 0)
         lastChangedModelTime = changedModelStartTime;
      // We also have to coordinate management of all of the errorModels for both layered systems in EditorContext.
      return refreshedModels;
   }

   public UpdateInstanceInfo newUpdateInstanceInfo() {
      return runtimeProcessor == null ? new UpdateInstanceInfo() : runtimeProcessor.newUpdateInstanceInfo();
   }

   /** Refreshes one file and applies the changes immediately */
   public Object refresh(SrcEntry srcEnt, ExecutionContext ctx) {
      // Not using the language model here since that will automatically load the new file
      ILanguageModel oldModel = modelIndex.get(srcEnt.absFileName);
      UpdateInstanceInfo info = newUpdateInstanceInfo();
      Object res = refresh(srcEnt, oldModel, ctx, info, true);
      info.updateInstances(ctx);
      return res;
   }

   /** Refreshes one file and batches the changes into the supplied UpdateInstanceInfo arg */
   public Object refresh(SrcEntry srcEnt, ExecutionContext ctx, UpdateInstanceInfo info, boolean active) {
      // Not using the getLanguageModel here cause we really need the old model
      ILanguageModel oldModel = active ? modelIndex.get(srcEnt.absFileName) : inactiveModelIndex.get(srcEnt.absFileName);
      return refresh(srcEnt, oldModel, ctx, info, active);
   }

   public Object refresh(SrcEntry srcEnt, ILanguageModel oldModel, ExecutionContext ctx, UpdateInstanceInfo updateInfo, boolean active) {
      if (oldModel != null) {
         File f = new File(srcEnt.absFileName);
         if (!f.canRead()) {
            if (options.verbose)
               verbose("No file: " + srcEnt.absFileName);
            return null;
         }
         if (f.lastModified() == oldModel.getLastModifiedTime()) {
            if (oldModel.hasErrors())
               oldModel.reinitialize();


            //if (options.verbose)
            //   System.out.println("No change: " + srcEnt.absFileName);

            // This model might have been copied from another runtime which has already called refresh on this file or reinitialized here - either way, it's a changed model
            if (!oldModel.isStarted())
               return oldModel;

            return null;
         }
      }
      else {
         // TODO: Just remove this?   If a file has failed to compile, it leaves an invalid entry in the index.
         // Need to remove that from the type cache before we init the new model.  It also seems we need to load
         // everything right?

         // No type has been loaded.  If we're defining a new type here, maybe we want to process but only if
         // there's some global main init annotation?   The key is to pick up a new modify of an existing type.
         // Make sure we return any type in any layer since we may have added new layers since we checked the cache
         //if (getCachedTypeDeclaration(srcEnt.getTypeName(), null, null, false, true) == null) {
         //   return null;
         //}
      }

      // Skip layer files for now.  For one, they don't go into the modelIndex so we get here each time.
      if (oldModel instanceof JavaModel && ((JavaModel) oldModel).isLayerModel) {
         Layer layer = oldModel.getLayer();
         if (!layer.newLayer) {
            System.err.println("*** Warning: layer file changed - not refreshing the changes");
         }
         return null;
      }

      if (options.verbose) {
         if (oldModel == null) {
            IFileProcessorResult processedFile = processedFileIndex.get(srcEnt.absFileName);
            if (processedFile == null)
               verbose("Processing new file: " + srcEnt.absFileName);
            else
               verbose("Refreshing changed simple file: " + srcEnt.absFileName);
         }
         else
            verbose("Refreshing changed src file: " + srcEnt.absFileName);
      }

      // When we do an update, we may first build the layer.  In that case, the model will have been loaded in the build.
      // If not, the model could be null or still the old model which needs to be replaced.
      ILanguageModel indexModel = getLanguageModel(srcEnt);
      if (indexModel == oldModel)
         indexModel = null;

      boolean addModel = indexModel == null;

      Object result = indexModel == null ? parseSrcFile(srcEnt, true) : indexModel;

      boolean activated = srcEnt.layer == null || srcEnt.layer.activated;

      if (result instanceof JavaModel) {
         JavaModel newModel = (JavaModel) result;
         if (!newModel.isInitialized()) {
            ParseUtil.initComponent(newModel);
         }
         if (!newModel.hasErrors) {
            if (oldModel != null && oldModel instanceof JavaModel) {
               JavaModel oldJavaModel = (JavaModel) oldModel;
               if (oldJavaModel.isLayerModel) // Can't update layers for now
                  return null;

               // We need to mark the model as changed before we start it, so that the JSRuntime and other hooks can
               // detect changed types as they are started.
               if (!oldJavaModel.equals(newModel)) {
                  String oldTypeName = oldJavaModel.getModelTypeName();
                  changedModels.put(oldTypeName, newModel);
                  changedTypes.add(oldTypeName);
               }
               oldJavaModel.updateModel(newModel, ctx, TypeUpdateMode.Replace, activated, updateInfo);
            }
            else if (newModel.modifiesModel()) {
               if (addModel)
                  addNewModel(newModel, srcEnt.layer, null, updateInfo, false, true);
               newModel.updateLayeredModel(ctx, activated, updateInfo);
            }
            // Make sure this gets added so we don't get keep doing it over and over again
            else if (addModel) {
               addNewModel(newModel, srcEnt.layer, null, updateInfo, false, true);
               // Register the new type here so we can notify listeners when the type has been fully initialized
               if (updateInfo != null) {
                  Map<String,TypeDeclaration> types = newModel.getDefinedTypes();
                  for (TypeDeclaration newType:types.values()) {
                     updateInfo.typeCreated(newType);
                  }
               }
            }
         }
         return newModel;
      }
      else if (result instanceof ParseError) {
         ModelParseError mpe = new ModelParseError();
         mpe.parseError = (ParseError) result;
         mpe.srcFile = srcEnt;
         return mpe;
      }
      return null;
   }

   public void refreshFile(SrcEntry srcEnt, Layer fromLayer, boolean active) {
      if (!active)
         return;
      IFileProcessor proc = getFileProcessorForFileName(srcEnt.absFileName, fromLayer, BuildPhase.Process);
      if (proc != null) {
         Object res = proc.process(srcEnt, false);
         if (res instanceof IFileProcessorResult) {
            IFileProcessorResult procRes = (IFileProcessorResult) res;
            procRes.addSrcFile(srcEnt);
            // TODO: do we need to set do this only if generate = true?
            List<SrcEntry> runtimeFiles = runtimeProcessor == null ?
                                             procRes.getProcessedFiles(buildLayer, buildLayer.buildSrcDir, true) :
                                             runtimeProcessor.getProcessedFiles(procRes, buildLayer, buildLayer.buildSrcDir, true);
            processedFileIndex.put(srcEnt.absFileName, procRes);

            // TODO: update the dependencies, clean out any other files, update the buildSrcIndex?
         }
         // else - a file that's overridden by another file
      }
   }


   public Object parseSrcType(SrcEntry srcEnt, Layer fromLayer, boolean isLayer, boolean reportErrors) {
      Object result = parseSrcFile(srcEnt, isLayer, true, false, reportErrors, false);
      if (result instanceof ILanguageModel) {
         ILanguageModel model = (ILanguageModel) result;

         addNewModel(model, fromLayer, null, null, isLayer, false);
      }
      else if (result instanceof ParseError) {
         addTypeByName(srcEnt.layer, srcEnt.getTypeName(), INVALID_TYPE_DECLARATION_SENTINEL, fromLayer);
      }
      return result;
   }

   public void addNewModel(ILanguageModel model, Layer fromLayer, ExecutionContext ctx, UpdateInstanceInfo updateInfo, boolean isLayer, boolean updateInstances) {
      model.setAdded(true);
      SrcEntry srcEnt = model.getSrcFile();
      Layer srcEntLayer = srcEnt.layer;
      if (ctx == null && model instanceof JavaModel) {
         // TODO: if we set the ctx here, it messes things up when initializing layers
         //ctx = new ExecutionContext((JavaModel) model);
      }
      boolean active = false;
      if (srcEntLayer != null) {
         if (!isLayer && srcEntLayer.activated) {
            // Now we can get its types and info.
            addTypesByName(srcEntLayer, model.getPackagePrefix(), model.getDefinedTypes(), fromLayer);
            active = true;
         }
         // Also register it in the layer model index
         srcEntLayer.layerModels.add(new IdentityWrapper(model));
         srcEntLayer.addNewSrcFile(srcEnt, true);
      }

      if (updateInfo == null && active && updateInstances)
         updateInfo = new UpdateInstanceInfo();

      updateModelIndex(srcEnt, model, ctx, updateInfo);

      if (updateInfo != null && ctx != null)
         updateInfo.updateInstances(ctx);
   }

   public void addNewDirectory(String dirPath) {
      File f = new File(dirPath);
      if (f.isDirectory()) {
         String[] subFiles = f.list();
         if (LayerUtil.isLayerDir(dirPath)) {
            for (String subFile:subFiles) {
               if (isParseable(subFile)) {
                  SrcEntry srcEnt = getSrcEntryForPath(FileUtil.concat(dirPath, subFile), false, false);
                  if (srcEnt != null && srcEnt.layer != null)
                     srcEnt.layer.addNewSrcFile(srcEnt, true);
               }

            }
         }
         else {
            for (String subFile:subFiles) {
               if (new File(subFile).isDirectory()) {
                  addNewDirectory(subFile);
               }
            }
         }
      }
      else {
         error("Invalid new directory: " + dirPath);
      }
   }

   /** Checks if the directory has been removed and if so, updates the indexes */
   public boolean checkRemovedDirectory(String dirPath) {
      try {
         acquireDynLock(false);
         File dir = new File(dirPath);
         if (!dir.isDirectory()) {
            boolean needsRefresh = false;
            for (File layerPathDir:layerPathDirs) {
               String layerPathFile = layerPathDir.getPath();
               if (dirPath.startsWith(layerPathFile)) {
                  needsRefresh = true;
                  break;
               }
            }
            if (!peerMode && peerSystems != null) {
               for (LayeredSystem peerSys:peerSystems) {
                  needsRefresh = peerSys.checkRemovedDirectory(dirPath) || needsRefresh;
               }
            }
            if (needsRefresh) {
               boolean dirRemoved;
               dirRemoved = checkRemovedDirectoryList(layers, dirPath);
               dirRemoved |= checkRemovedDirectoryList(inactiveLayers, dirPath);
               return dirRemoved;
            }
         }
         else
            System.out.println("*** checkRemovedDirectory called with directory that exists: " + dirPath);

      }
      finally {
         releaseDynLock(false);
      }
      return false;
   }

   private boolean checkRemovedDirectoryList(List<Layer> layersList, String dirPath) {
      ArrayList<Layer> toRemove = new ArrayList<Layer>();
      for (Layer layer:layersList) {
         if (!layer.checkRemovedDirectory(dirPath))
            toRemove.add(layer);
      }
      if (toRemove.size() > 0) {
         // This gets called from the fileDeleted event in IntelliJs VirtualFileListener.  It's not the right time to make changes or even to load the psi.
         // We'll let the caller know some layers have been removed and it can then layer call doRemoveLayers().
         if (layersToRemove == null)
            layersToRemove = new ArrayList<Layer>();
         layersToRemove.addAll(toRemove);
         return true;
      }
      return false;
   }

   public void doRemoveLayers() {
      if (layersToRemove != null) {
         removeLayers(layersToRemove, null);
         layersToRemove = null;
      }
   }

   public void notifyModelAdded(JavaModel model) {
      if (model.removed) {
         warning("Ignoring call to notify model added with removed model");
         return;
      }
      for (IModelListener ml: modelListeners) {
         ml.modelAdded(model);
      }
   }

   public void notifyInnerTypeAdded(BodyTypeDeclaration innerType) {
      if (innerType.getJavaModel().removed) {
         warning("Ignoring call to notify inner type added with removed model");
         return;
      }
      for (IModelListener ml: modelListeners) {
         ml.innerTypeAdded(innerType);
      }
      removeFromInnerTypeCache(innerType.getFullTypeName());
   }

   public void notifyInnerTypeRemoved(BodyTypeDeclaration innerType) {
      if (innerType.getJavaModel().removed) {
         warning("Ignoring call to notify inner type removed with removed model");
         return;
      }
      for (IModelListener ml: modelListeners) {
         ml.innerTypeRemoved(innerType);
      }
      removeFromInnerTypeCache(innerType.getFullTypeName());
   }

   /*
   public void notifyInstancedAdded(BodyTypeDeclaration innerType, Object inst) {
      for (IModelListener ml: modelListeners) {
         ml.instanceAdded(innerType, inst);
      }
      removeFromInnerTypeCache(innerType.getFullTypeName());
   }
   */

   public void addNewModelListener(IModelListener l) {
      modelListeners.add(l);
   }

   public boolean removeNewModelListener(IModelListener l) {
      return modelListeners.remove(l);
   }

   public boolean hasNewModelListener(IModelListener l) {
      return modelListeners.contains(l);
   }

   /**
    * Creates an object instance from a .sc file that is not part of a layer, i.e. the global dependencies object
    * Parses the file, inits and starts the component, creates an instance with those properties and returns
    * that instance.  This instance is not put into any type index - it is not referenceable by other code
    */
   public Object loadInstanceFromFile(String fileName, String relFileName) {
      File f = new File(fileName);
      if (!f.exists())
         return null;
      SrcEntry srcEnt = new SrcEntry(null, fileName, relFileName);

      Object res = parseSrcFile(srcEnt, false, false, false, true, false);
      if (res instanceof JavaModel) {
         JavaModel model = (JavaModel) res;
         ParseUtil.startComponent(model);
         ParseUtil.validateComponent(model);
         if (model.getModelTypeDeclaration() == null) {
            return null;
         }
         return model.getModelTypeDeclaration().createInstance();
      }
      else {
         System.err.println("*** model file did not contain valid java model: " + fileName);
         return null;
      }
   }

   private void updateModelIndex(Layer layer, ILanguageModel model, String absName) {
      if (layer != null && layer.activated) {
         modelIndex.put(absName, model);
      }
      else {
         if (model.getLayer() != null && model.getLayer().activated)
            System.err.println("*** Invalid attempt to add active model into inactive index");
         if (model.getLayeredSystem() != this)
            System.err.println("*** Invalid attempt to add model from another system to index");
         ILanguageModel oldModel = inactiveModelIndex.put(absName, model);
         if (oldModel != null && model instanceof JavaModel && oldModel != model) {
            if (layer != null)
               layer.layerModels.remove(new IdentityWrapper(oldModel));

            JavaModel javaModel = (JavaModel) model;
            // We only do this if replacing the inactive model.  The first time, we will have parsed an unannotated layer model so don't process the update for that.
            if (layer != null && oldModel.getUserData() != null) {
               boolean modelChanged;
               if (javaModel.isLayerModel)
                  modelChanged = (oldModel.getLastModifiedTime() != javaModel.getLastModifiedTime() || !oldModel.sameModel(model)) && layer.updateModel(javaModel);
               // If it's a regular file, not a layer, we mark the layer as having changed if the last modified time is different or the models have physically different contents.
               // the last modified time for a file edited in the IDE does not get updated until after we've updated the model here.  It is not cheap to identical models but it will
               // save a refresh of all open files when you have only changed comments or whitespace.
               else
                  modelChanged = oldModel.getLastModifiedTime() != model.getLastModifiedTime() || !oldModel.sameModel(model);

               if (externalModelIndex != null) {
                  externalModelIndex.modelChanged(javaModel, modelChanged, layer);
               }
            }
         }
         // In replaceModel, we'll have updated the index but not the layerModel so need to take care of that here.
         else if (model instanceof JavaModel) {
            JavaModel javaModel = (JavaModel) model;
            if (javaModel.isLayerModel)
               layer.updateModel(javaModel);
         }
         if (layer != null)
            layer.layerModels.add(new IdentityWrapper(model));
      }
   }

   private void updateModelIndex(SrcEntry srcEnt, ILanguageModel model, ExecutionContext ctx, UpdateInstanceInfo updateInfo) {
      String absName = srcEnt.absFileName;
      ILanguageModel oldModel = getCachedModel(srcEnt, false);
      // getLanguageModel might load the model into the cache but not put it into the type system, even if it's active.  We call this again from addNew
      if (oldModel == model) {
         if (beingLoaded.get(srcEnt.absFileName) != oldModel)
            return;
      }
      if (oldModel != null && options.verbose && oldModel.getLayer() != null && oldModel.getLayer().activated) {
         System.err.println("Replacing model " + absName + " in layer: " + oldModel.getLayer() + " with: " + model.getLayer());
      }

      Layer layer = model.getLayer();
      updateModelIndex(layer, model, absName);
      // We need to call updateModel even for inactive types so that types which modify this type are updated to point to the new version
      // We originally added that test for performance - overhead in the first time editing a given file, starting types and stuff so need to keep
      // an eye on that that.
      if (model instanceof JavaModel && layer != null /* && layer.activated */) {
         JavaModel newModel = (JavaModel) model;
         if (oldModel != null) {
            // TODO: should we be calling updateModel here?
            //newModel.replacesModel = (JavaModel) oldModel;
            if (!newModel.isLayerModel) {
               if (oldModel instanceof JavaModel && !batchingModelUpdates)
                  ((JavaModel) oldModel).updateModel(newModel, ctx, TypeUpdateMode.Replace, false, updateInfo);
               else
                  newModel.replacesModel = (JavaModel) oldModel;
            }
         }
         else if (newModel.modifiesModel()) {
            if (ctx != null) {
               // We've found a change to a model not inside of a managed "addLayers" ooperation.  Maybe we're compiling a layer and we see a change at that point or maybe we've just created a new empty type from the command line.  Probably should change this to somehow batch up the UpdateInstanceInfo and apply that change when we hit the addLayer operation that triggered the build.  But in the empty case, there's no changes to apply
               newModel.updateLayeredModel(ctx, true, updateInfo);
            }
         }
      }
   }

   /*
   public void updateInactiveModels() {
      if (externalModelIndex != null) {
         for (ILanguageModel inactiveModel:inactiveModelIndex.values()) {
            ILanguageModel newModel = modelIndex.get(inactiveModel.getSrcFile().absFileName);

            externalModelIndex.replaceModel(inactiveModel, newModel);
         }
      }
   }
   */

   public void replaceModel(ILanguageModel oldModel, ILanguageModel newModel) {
      SrcEntry srcEnt = oldModel.getSrcFile();
      updateModelIndex(srcEnt.layer, newModel, srcEnt.absFileName);
   }

   /** When making changes to a model which don't actually create a new model, we may need to update the layer - e.g. update the imports in the layer which is done here */
   public void modelUpdated(ILanguageModel newModel) {
      SrcEntry srcEnt = newModel.getSrcFile();
      updateModelIndex(srcEnt.layer, newModel, srcEnt.absFileName);
   }

   public void removeModel(ILanguageModel model, boolean removeTypes) {
      SrcEntry srcEnt = model.getSrcFile();

      model.setAdded(false);

      Object old;
      if (srcEnt.layer != null && srcEnt.layer.activated)
         old = modelIndex.remove(srcEnt.absFileName);
      else
         old = inactiveModelIndex.remove(srcEnt.absFileName);

      if (old == null) {
         System.err.println("*** removeModel called with model that is not active");
         return;

      }

      if (model instanceof JavaModel) {
         JavaModel javaModel = (JavaModel) model;
         // If we are removing a model which modifies another model we do not dispose the instances
         if (!javaModel.isModifyModel())
            javaModel.disposeInstances();

         if (removeTypes && !javaModel.isLayerModel)
            removeTypesByName(srcEnt.layer, model.getPackagePrefix(), model.getDefinedTypes(), model.getLayer());
      }

      for (IModelListener ml: modelListeners) {
         ml.modelRemoved(model);
      }

      if (typeChangeListeners.size() > 0) {
         Map<String, TypeDeclaration> typesByName = model.getDefinedTypes();
         for (TypeDeclaration remType : typesByName.values()) {
            for (ITypeChangeListener typeChangeListener:typeChangeListeners)
               typeChangeListener.typeRemoved(remType);
         }
      }
   }

   private boolean isActivated(Layer layer) {
      return layer != null && layer.activated;
   }

   public ILanguageModel getAnnotatedLanguageModel(SrcEntry srcEnt, boolean activeOrInactive) {
      ILanguageModel m = getLanguageModel(srcEnt, activeOrInactive, null, true);
      if (m != null && m.getUserData() != null)
         return m;
      if (peerSystems != null) {
         for (LayeredSystem peerSys:peerSystems) {
            SrcEntry peerEnt = peerSys.getPeerSrcEntry(srcEnt);
            if (peerEnt != null) {
               m = peerSys.getLanguageModel(peerEnt, activeOrInactive, null, true);
               if (m != null && m.getUserData() != null)
                  return m;
            }
         }
      }
      return null;
   }

   /** Used to map a SrcEntry from one layered system to another */
   private SrcEntry getPeerSrcEntry(SrcEntry peerEnt) {
      Layer peerLayer = peerEnt.layer;
      if (peerLayer == null)
         return null;
      Layer ourLayer = peerLayer.activated ? getLayerByName(peerLayer.layerUniqueName) : lookupInactiveLayer(peerLayer.getLayerName(), false, true);
      if (ourLayer == null)
         return null;
      return new SrcEntry(ourLayer, peerEnt.absFileName, peerEnt.relFileName, peerEnt.prependPackage);
   }

   public ILanguageModel getLanguageModel(SrcEntry srcEnt) {
      return getLanguageModel(srcEnt, false, null, true);
   }

   public ILanguageModel getLanguageModel(SrcEntry srcEnt, boolean activeOrInactive, List<Layer.ModelUpdate> changedModels, boolean loadIfUnloaded) {
      Layer layer = srcEnt.layer;

      // If we are in the midst of loading this model via createFile, we cannot try to go back to the file manager and get the same file as it will recurse endlessly
      ILanguageModel cachedModel = beingLoaded.get(srcEnt.absFileName);
      if (cachedModel != null && cachedModel.getLayer() == srcEnt.layer)
         return cachedModel;
      if (externalModelIndex != null && (layer != null && !layer.activated) /* && !activeOrInactive */) {
         ILanguageModel extModel = externalModelIndex.lookupJavaModel(srcEnt, loadIfUnloaded);
         // We may end up storing two copies of the model - activated and inactivated.  The editor can be out of sync
         // with what we are requesting here, in which case we may need to load a new copy that's in the right state.
         if (extModel != null) {
            /* This used to be necessary when we used activated models from the IDE but now that we do not, loading a file will never update a model.
            ILanguageModel oldModel = getCachedModel(srcEnt, false);
            if (oldModel != extModel && extModel instanceof JavaModel) {
               if (oldModel instanceof JavaModel) {
                  JavaModel oldJavaModel = (JavaModel) oldModel;

                  Layer extModelLayer = extModel.getLayer();

                  if (!oldJavaModel.isLayerModel && extModelLayer != null && extModelLayer.activated && layer.activated) {
                     if (changedModels != null)
                        changedModels.add(new Layer.ModelUpdate(oldModel, extModel));
                     oldJavaModel.updateModel((JavaModel) extModel, null, TypeUpdateMode.Replace, false, null);
                  }
               }
            }
            */
            if (isActivated(srcEnt.layer)) {
               System.err.println("*** not reached!!!");
               if (!extModel.isAdded())
                  addNewModel(extModel, srcEnt.layer.getNextLayer(), null, null, extModel instanceof JavaModel && ((JavaModel) extModel).isLayerModel, false);
            }
            else
               updateModelIndex(extModel.getLayer(), extModel, srcEnt.absFileName);
            if (activeOrInactive || isActivated(srcEnt.layer) == isActivated(extModel.getLayer()))
               return extModel;
         }
      }
      return getCachedModel(srcEnt, false);
   }

   /** Adds the types defined in the specified layer to a global index.  If the type is overridden
    * by and the overridding type is there, it is skipped.
    */
   public void addTypesByName(Layer layer, String packagePrefix, Map<String,TypeDeclaration> newTypes, Layer fromLayer) {
      for (Map.Entry<String,TypeDeclaration> ent: newTypes.entrySet()) {
         TypeDeclaration td = ent.getValue();
         String typeName;
         if (td.isLayerType)
            typeName = layer.layerUniqueName;
         else
            typeName = CTypeUtil.prefixPath(packagePrefix, ent.getKey());

         addTypeByName(layer, typeName, td, fromLayer);
      }
   }

   public void removeTypesByName(Layer layer, String packagePrefix, Map<String,TypeDeclaration> newTypes, Layer fromLayer) {
      for (Map.Entry<String,TypeDeclaration> ent: newTypes.entrySet()) {
         removeTypeByName(layer, CTypeUtil.prefixPath(packagePrefix, ent.getKey()), ent.getValue(), fromLayer);
      }
   }

   public boolean typeWasReloaded(String fullTypeName) {
      TypeDeclarationCacheEntry tds = typesByName.get(fullTypeName);
      return tds != null && tds.reloaded;
   }

   public void addToRootNameIndex(BodyTypeDeclaration type) {
      String rootName = type.getInnerTypeName();
      ArrayList<BodyTypeDeclaration> types = typesByRootName.get(rootName);
      if (types == null) {
         types = new ArrayList<BodyTypeDeclaration>();
         typesByRootName.put(rootName, types);
      }
      // For some reason these are getting added twice...
      if (!types.contains(type))
         types.add(type);
   }

   public void removeFromRootNameIndex(BodyTypeDeclaration type) {
      String rootName = type.getInnerTypeName();
      ArrayList<BodyTypeDeclaration> types = typesByRootName.get(rootName);
      if (types == null) {
         if (type instanceof AnonClassDeclaration)
            return;
          return;
      }
      else {
         if (!types.remove(type)) {
            if (type instanceof AnonClassDeclaration)
               return;
            // For some reason, when restarting a model we only add the most specified type to this list
            // but when stopping the model we remove all of them and so get a bunch of these errors (and those
            // in removeTypeByName too)
            //System.err.println("*** Can't find entry in root name list to remove: " + rootName);
         }
         else if (types.size() == 0)
            typesByRootName.remove(rootName);
      }
   }

   public List<BodyTypeDeclaration> findTypesByRootName(String innerTypeName) {
      return typesByRootName.get(innerTypeName);
   }

   public List<BodyTypeDeclaration> findTypesByRootMatchingPrefix(String prefix) {
      ArrayList<BodyTypeDeclaration> types = new ArrayList<BodyTypeDeclaration>();
      for (Map.Entry<String,ArrayList<BodyTypeDeclaration>> ent:typesByRootName.entrySet()) {
         String rootName = ent.getKey();
         if (rootName.startsWith(prefix))
            types.addAll(ent.getValue());
      }
      return types;
   }

   public void addTypeDeclaration(String fullTypeName, BodyTypeDeclaration toAdd) {
      addTypeByName(toAdd.getLayer(), fullTypeName, (TypeDeclaration)toAdd, null);
   }

   public void addTypeByName(Layer layer, String fullTypeName, TypeDeclaration toAdd, Layer fromLayer) {
      if (toAdd.isTransformed())
         warning("*** Adding transformed type to type system");
      if (!layer.activated || (toAdd.getLayer() != null && !toAdd.getLayer().activated))
         warning("*** Error adding inactivated type to type system");
      addToRootNameIndex(toAdd);
      TypeDeclarationCacheEntry tds = typesByName.get(fullTypeName);
      if (tds != null) {
         int i;
         for (i = 0; i < tds.size(); i++) {
            TypeDeclaration inList = tds.get(i);
            if (inList == INVALID_TYPE_DECLARATION_SENTINEL) {
               if (toAdd == INVALID_TYPE_DECLARATION_SENTINEL)
                  return;
               tds.set(i, toAdd);
               break;
            }

            ILanguageModel model = inList.getLanguageModel();
            Layer modelLayer = model.getLayer();

            // If a model is defined without a layer, it is a transient model and doesn't go into the type system
            if (layer == null)
               return;

            // In case we are reloading the same layer.  For inner types, we return a sub-type's version of the class
            // but later might need to replace that one.
            if (modelLayer.layerPosition == layer.layerPosition) {
               tds.set(i, toAdd);
               break;
            }

            // If this definition is overridden, do not register it globally
            // higher layer positions go first so the first item in the list is the one we use for this system
            // In some cases, we looked for files in a higher layer than this one and so we want to update the
            // position from the "fromLayer" - where we started searching from.
            if (modelLayer.layerPosition < layer.layerPosition) {
               tds.add(i, toAdd);
               int newFrom = fromLayer != null ? fromLayer.layerPosition : getLastStartedPosition();
               if (newFrom > tds.fromPosition) { // Don't lower the from position... only move it up the layer stack as new guys are added
                  tds.fromPosition = newFrom;
               }
               break;
            }
         }
         if (i == tds.size()) {
            if (i == 0) {
               tds.fromPosition = fromLayer != null ? fromLayer.layerPosition : getLastStartedPosition();
            }
            tds.add(toAdd);
         }
      }
      else {
         TypeDeclarationCacheEntry types = new TypeDeclarationCacheEntry(3);
         types.add(toAdd);
         types.fromPosition = fromLayer != null ? fromLayer.layerPosition : getLastStartedPosition();
         typesByName.put(fullTypeName, types);
      }
   }

   public void removeTypeByName(Layer layer, String fullTypeName, TypeDeclaration toRem, Layer fromLayer) {
      if (toRem.layer != null && toRem.layer.activated) {
         removeFromRootNameIndex(toRem);
         TypeDeclarationCacheEntry tds = typesByName.get(fullTypeName);
         if (tds != null) {
            int i;
            for (i = 0; i < tds.size(); i++) {
               TypeDeclaration inList = tds.get(i);
               if (inList == toRem) {
                  tds.remove(i);
                  // Next time we search for this type, we need to look at all files here.  Technically we could probably set this to
                  // the layerPosition of the next type in the list (if there is one) but it's a minor performance thing.
                  tds.fromPosition = 0;
                  subTypesByType.remove(toRem.getFullTypeName());
                  if (tds.size() == 0)
                     typesByName.remove(fullTypeName);

                  // Once we've started the system, we need to keep track of any stopped types so we can update them.
                  if (!options.compileOnly && runClassStarted) {
                     TypeDeclaration oldStoppedType = stoppedTypes == null ? null : stoppedTypes.get(fullTypeName);
                     if (oldStoppedType != null && oldStoppedType != toRem) {
                        Layer oldLayer = oldStoppedType.getLayer();
                        Layer newLayer = toRem.getLayer();
                        // If we've already stopped a type declaration for this type, pick the last one in the stack
                        if (oldLayer == null || (newLayer != null && newLayer.getLayerPosition() >= oldLayer.getLayerPosition()))
                           oldStoppedType = null;
                     }
                     if (oldStoppedType == null) {
                        if (stoppedTypes == null)
                           stoppedTypes = new LinkedHashMap<String, TypeDeclaration>();
                        stoppedTypes.put(fullTypeName, toRem);
                     }
                  }
                  return;
               }
            }
         }
         else {
            // We don't consistently add the anonymous types to this list so don't warn when we try to remove them.
            //if (!(toRem instanceof AnonClassDeclaration))
               // And when restarting a model we don't consistently add all modified types but we do try to remove all of them - see refreshStyle test
               //warning("*** remove type by name not in list");
         }
      }
   }

   // Using lastStartedLayer's position here because we do not init the srcDirCache until we start the
   // layer.  With a null layer, we could improperly think we had find all of the files but then did not.
   public int getLastStartedPosition() {
      return lastStartedLayer == null ? 0 : lastStartedLayer.layerPosition+1;
   }

   public void replaceTypeDeclaration(TypeDeclaration oldType, TypeDeclaration newType) {
      removeFromRootNameIndex(oldType);
      addToRootNameIndex(newType);
      // Update the types by name index.  This strategy should ensure we don't mess up any following layers
      // should we be replacing an intermediate layer.
      TypeDeclarationCacheEntry tds = typesByName.get(oldType.getFullTypeName());
      if (tds != null) {
         for (int i = 0; i < tds.size(); i++) {
            TypeDeclaration curr = tds.get(i);
            if (curr == oldType)
               tds.set(i, newType);
         }
      }
      replaceSubTypes(oldType, newType);
   }

   public void replaceSubTypes(TypeDeclaration oldType, TypeDeclaration newType) {
      String oldTypeName = oldType.getFullTypeName();
      String newTypeName = newType.getFullTypeName();
      if (oldTypeName.equals(newTypeName))
         return;
      // This may not get called in order when we add more than one layer at the same time.  To workaround that, we find the entry we need to replace and
      // replace it.  The next call then won't find anything to replace.
      HashMap<String,Boolean> subTypeMap;
      do {
         subTypeMap = subTypesByType.get(oldTypeName);
         if (subTypeMap == null) {
            oldType = (TypeDeclaration) oldType.getPureModifiedType();
         }
      } while (subTypeMap == null && oldType != null);

      if (subTypeMap != null) {
         subTypesByType.put(newTypeName, subTypeMap);
         // Note: we have already updated the sub-types for the new super-type in TypeDeclaration.updateBaseType
         subTypesByType.remove(oldTypeName);
      }
   }

   public List<SrcEntry> getFilesForType(String typeName) {
      TypeDeclarationCacheEntry decls = typesByName.get(typeName);
      if (decls == null || decls.size() == 0)
         return null;
      TypeDeclaration decl = decls.fromPosition == -1 || decls.fromPosition == layers.size()-1 ? decls.get(0) : null;
      if (decl != null) {
         return decl.getJavaModel().getSrcFiles();
      }
      else {
         SrcEntry srcFileEnt = getSrcFileFromTypeName(typeName, false, null, true, null);
         if (srcFileEnt != null)
            return Collections.singletonList(srcFileEnt);
      }
      // If there's no entry, cache nothing here so we don't keep looking up.
      if (decls == null) {
         decls = new TypeDeclarationCacheEntry(0);
         decls.fromPosition = getLastStartedPosition();
         typesByName.put(typeName, decls);
      }
      return null;
   }

   public final static TypeDeclaration INVALID_TYPE_DECLARATION_SENTINEL = new ClassDeclaration();
   {
      INVALID_TYPE_DECLARATION_SENTINEL.typeName = "<invalid>";
   }

   public TypeDeclaration getCachedTypeDeclaration(String typeName, Layer fromLayer, Layer srcLayer, boolean updateFrom, boolean anyTypeInLayer, Layer refLayer, boolean innerTypes) {
      if (cacheForRefLayer(refLayer)) {
         TypeDeclarationCacheEntry decls = typesByName.get(typeName);
         if (decls != null && decls.size() != 0) {
            int fromPosition = anyTypeInLayer ? -1 : fromLayer == null ? getLastStartedPosition() : fromLayer.layerPosition;

            // If we have searched from this position before we can use the cache entry.
            if (fromPosition == -1 || decls.fromPosition >= fromPosition) {
               if (fromLayer == null)
                  return decls.get(0);
               else {
                  for (int i = 0; i < decls.size(); i++) {
                     TypeDeclaration ldec = decls.get(i);

                     if (ldec == INVALID_TYPE_DECLARATION_SENTINEL) {
                        if (i == decls.size() - 1)
                           return ldec;
                        else
                           continue;
                     }

                     // In case we are reloading the same layer though not sure why that would happen.
                     if (ldec.getLayer().layerPosition < fromLayer.layerPosition) {
                        return decls.get(i);
                     }
                  }
               }
            }
            else if (decls.fromPosition == -1)
               return decls.get(0);
            else {
               int origFrom = decls.fromPosition;
               // Hack note: when updateFrom = true, we are updating the search position here because after returning null
               // we will immediately search for it from this position.  For coding convenience, this
               // is updated here.  This would not be threadsafe and is a little ugly.
               if (updateFrom) {
                  if (fromPosition > decls.fromPosition) {
                     decls.fromPosition = fromPosition;
                  }
               }

               // If the caller provides the current src file with no layer, we can avoid reloading it
               // and just update the cache.
               if (srcLayer != null) {
                  if (srcLayer.layerPosition < origFrom)
                     return decls.get(0);
               }
            }
         }
      }
      else {
         SrcEntry srcEnt = getSrcFileFromTypeName(typeName, true, null, true, null, refLayer, false);
         if (srcEnt != null) {
            ILanguageModel model = inactiveModelIndex.get(srcEnt.absFileName);
            if (model != null)
               return model.getModelTypeDeclaration();
         }
      }
      if (innerTypes) {
         String tailName = null;
         do {
            String rootTypeName = CTypeUtil.getPackageName(typeName);
            tailName = CTypeUtil.prefixPath(CTypeUtil.getClassName(typeName), tailName);
            if (rootTypeName != null) {
               SrcEntry srcEnt = getSrcFileFromTypeName(rootTypeName, true, null, true, null, refLayer, false);
               if (srcEnt != null) {
                  TypeDeclaration rootType = getCachedTypeDeclaration(rootTypeName, fromLayer, srcLayer, updateFrom, anyTypeInLayer, refLayer, false);
                  if (rootType != null) {
                     Object res = rootType.getInnerType(tailName, null, true, false, true, false);
                     if (res instanceof TypeDeclaration) {
                        return (TypeDeclaration) res;
                     }
                  }
               }
            }
            typeName = rootTypeName;
         } while (typeName != null);
      }
      return null;
   }

   public boolean getLiveDynamicTypes(String typeName) {
      if (!options.liveDynamicTypes)
         return false;
      String parentName = CTypeUtil.getPackageName(typeName);
      // If it's a layer component it's always dynamic
      if (parentName != null && parentName.equals(Layer.LAYER_COMPONENT_FULL_TYPE_NAME))
         return true;

      Object type = getSrcTypeDeclaration(typeName, null, true, false, false, null, false);
      if (type != null)
         return ModelUtil.getLiveDynamicTypes(type);

      // TODO: I'm not sure this part is right
      SrcEntry srcEnt = getSrcFileFromTypeName(typeName, true, null, true, null);
      if (srcEnt == null) {
         if (parentName == null)
            return true;
         return getLiveDynamicTypes(parentName);
      }
      if (srcEnt.layer == null)
         return false;
      return srcEnt.layer.liveDynamicTypes;
   }

   public boolean isCompiledFinalLayerType(String typeName) {
      SrcEntry srcEnt = getSrcFileFromTypeName(typeName, true, null, true, null);
      boolean res = srcEnt != null && srcEnt.layer != null && srcEnt.layer.getCompiledFinalLayer();
      if (res) {
         // For packages like java.util, once we've loaded one source type, we cannot return any more compiled types
         // since the compiled version is the standard java.util package, not the source version we are loading based on
         // apache.  To compile the java.util you need to consistently use the same classes and we can't reload the
         // JS versions as compiled classes cause of the classloader security restriction placed on that package.
         if (overrideFinalPackages.size() > 0 && !srcEnt.layer.annotationLayer) {
            String pkgName = CTypeUtil.getPackageName(srcEnt.getTypeName());
            if (pkgName != null && overrideFinalPackages.contains(pkgName))
               return false;
         }
         return true;
      }
      return false;
   }

   public boolean getCanUseCompiledType(String typeName) {
      return buildLayer != null && !buildLayer.isDynamicType(typeName) && getUseCompiledForFinal() && !changedTypes.contains(typeName) && isCompiledFinalLayerType(typeName);
   }

   /** This is an optimization on top of getSrcTypeDeclaration - if the model is not changed and we are not going from a layer use the compiled version */
   public Object getTypeForCompile(String typeName, Layer fromLayer, boolean prependPackage, boolean notHidden, boolean srcOnly, Layer refLayer, boolean layerResolve) {
      if (getUseCompiledForFinal() && fromLayer == null) {
         // First if we've loaded the src we need to return that.
         if (cacheForRefLayer(refLayer)) {
            TypeDeclaration decl = getCachedTypeDeclaration(typeName, null, null, true, false, null, false);
            if (decl != null) {
               if (decl == INVALID_TYPE_DECLARATION_SENTINEL)
                  return null;
               return decl;
            }
         }

         // Now since the model is not changed, we'll use the class.  If at some point we need to change the compiled version - ie. to make a property bindable, we'll load the src at that time.
         Object cl = getCompiledType(typeName);
         if (cl != null)
            return cl;
      }
      return getSrcTypeDeclaration(typeName, fromLayer, prependPackage, notHidden, srcOnly, refLayer, layerResolve);
   }

   public static int nestLevel = 0;

   /**
    * This is a simple version of this method to use for getting the inactive or active TypeDeclaration for the given refLayer.
    * You'll get the most specific one if there's more than one layer.
    */
   public TypeDeclaration getSrcTypeDeclaration(String typeName, Layer refLayer) {
      Object res = getSrcTypeDeclaration(typeName, null, true, false, true, refLayer, false);
      if (res instanceof TypeDeclaration)
         return (TypeDeclaration) res;
      return null;
   }

   public TypeDeclaration getSrcTypeDeclaration(String typeName, Layer fromLayer, boolean prependPackage) {
      return getSrcTypeDeclaration(typeName, fromLayer, prependPackage, false);
   }

   public TypeDeclaration getSrcTypeDeclaration(String typeName, Layer fromLayer, boolean prependPackage, boolean notHidden) {
      Object res = getSrcTypeDeclaration(typeName, fromLayer, prependPackage, notHidden, true, null, false);
      if (res instanceof CFClass) {
         System.out.println("*** Warning non-source class found when expecting source");
         res = getSrcTypeDeclaration(typeName, fromLayer, prependPackage, notHidden, true, null, false);
      }
      return (TypeDeclaration) res;
   }

   public Object getSrcTypeDeclaration(String typeName, Layer fromLayer, boolean prependPackage, boolean notHidden, boolean srcOnly) {
      return getSrcTypeDeclaration(typeName, fromLayer, prependPackage, notHidden, srcOnly, null, false);
   }

   public TypeDeclaration getTypeFromCache(String typeName, Layer fromLayer, boolean prependPackage) {
      if (cacheForRefLayer(fromLayer)) {
         TypeDeclaration decl = getCachedTypeDeclaration(typeName, fromLayer, null, true, false, null, false);
         if (decl != null)
            return decl;
      }

      SrcEntry srcFile = getSrcFileFromTypeName(typeName, true, fromLayer, prependPackage, null, fromLayer, true);

      if (srcFile != null) {
         ILanguageModel model;
         if (fromLayer.activated)
            model = modelIndex.get(srcFile.absFileName);
         else
            model = inactiveModelIndex.get(srcFile.absFileName);
         if (model != null)
            return model.getModelTypeDeclaration();
      }
      return null;
   }

   public Object getSrcTypeDeclaration(String typeName, Layer fromLayer, boolean prependPackage, boolean notHidden, boolean srcOnly, Layer refLayer, boolean layerResolve) {
      return getSrcTypeDeclaration(typeName, fromLayer, prependPackage, notHidden, srcOnly, refLayer, layerResolve, false, false);
   }

   /** Retrieves a type declaration, usually the source definition with a given type name.  If fromLayer != null, it retrieves only types
    * defines below fromLayer (not including fromLayer).
    *
    * If prependPackage is true, the name is resolved like a Java type name.  If
    * it is false, it is resolved like a file system path - where the type's package is not used in the type name.
    *
    * If not hidden is true, do not return any types which are marked as hidden - i.e. not visible in the editor.
    * In some configurations this mode is used for the addDyn calls so that we do not bother loading for source you are not going to change.
    *
    * The srcOnly flag is t if you need a TypeDeclaration - and cannot deal with a final class.
    *
    * If refLayer is supplied, it refers to the referring layer.  It's not ordinarily used in an active application but for an inactive layer, it
    * changes how the lookup is performed.
    *
    * rootTypeOnly = true to skip inner types
    *
    * includeEnums = true to return enumerated constant values - i.e. the EnumConstant instance used for when we modify enums
    */
   public Object getSrcTypeDeclaration(String typeName, Layer fromLayer, boolean prependPackage, boolean notHidden, boolean srcOnly, Layer refLayer, boolean layerResolve, boolean rootTypeOnly, boolean includeEnums) {
      SrcEntry srcFile = null;
      TypeDeclaration decl;
      Object skippedDecl = null;

      // Do not resolve source files in the system layer package from the layer definition files.  Otherwise LayeredSystem and other classes overridden in the layer can mess up the layer's definition
      if (layerResolve) {
         String pkgName = CTypeUtil.getPackageName(typeName);
         if (pkgName == null || systemLayerModelPackages.contains(pkgName))
            return null;
      }

      if (cacheForRefLayer(refLayer)) {
         decl = getCachedTypeDeclaration(typeName, fromLayer, null, true, false, refLayer, false);
         if (decl != null) {
            if (decl == INVALID_TYPE_DECLARATION_SENTINEL)
               return null;

            // We may not have loaded all of the files for this type.  Currently we load all types we need to process during the build but then may just grab the most specific one when resolving a global type.  If there are gaps created the fromPosition thing does not protect us from returning a stale entry.
            if (decl.getEnclosingType() == null) {
               srcFile = getSrcFileFromTypeName(typeName, true, fromLayer, prependPackage, null);
               // Added the canRead test here because the command line interpreter first adds the file to the layer before it saves the actual file.  We end up with the pending srcFile here which does not match the cached one so we return null rather than the one we want
               // which is the current version of the type in the cache.
               if (srcFile == null || (decl.getLayer() != srcFile.layer && new File(srcFile.absFileName).canRead())) {
                  skippedDecl = decl; // We know there's a file more specific than this decl
                  decl = null;
               }
            }

            if (decl != null) {
               JavaModel model = decl.getJavaModel();
               if (model.replacedByModel == null)
                  return decl;
            }
         }
      }

      if (srcFile == null) {
         srcFile = getSrcFileFromTypeName(typeName, true, fromLayer, prependPackage, null, refLayer, layerResolve);
         if (srcFile != null && srcFile.layer != null && srcFile.layer.layeredSystem != this) {
            System.err.println("*** SrcFile returned from wrong layeredSystem!");
            srcFile = getSrcFileFromTypeName(typeName, true, fromLayer, prependPackage, null, refLayer, layerResolve);
         }
      }

      if (srcFile == null && layerResolve) {
         Layer layer;
         if (refLayer == null || refLayer.activated) {
            layer = getLayerByDirName(typeName);
            if (layer != null && layer.model != null) {
               decl = layer.model.getModelTypeDeclaration();
               if (decl != null)
                  return decl;
            }
         }
         layer = refLayer == null || refLayer.activated ? pendingActiveLayers.get(typeName) : pendingInactiveLayers.get(typeName);
         if (layer != null && layer.model != null) {
            decl = layer.model.getModelTypeDeclaration();
            if (decl != null)
               return decl;
         }
      }

      if (srcFile != null) {
         if (layerResolve && !srcFile.layer.buildSeparate)
            return null;
         // When notHidden is set, we do not load types which are in hidden layers
         if (notHidden && !srcFile.layer.getVisibleInEditor())
            return null;

         if (!srcOnly && (refLayer == null || refLayer.activated)) {
            // Now since the model is not changed, we'll use the class.  If at some point we need to change the compiled version - ie. to make a property bindable, we'll load the src at that time.
            Object cl = getCompiledType(typeName);
            if (cl != null)
               return cl;
         }

         // Do not try to load a file which in the midst of being loaded.  This happens in some weird case where you are initializing a Template, which
         // then needs to init the type info for its base type to create the object definition.  In that process, we start looking up an inner class using
         // no layer to try and find the modified type of some inner class.  We should not let it find this type in that case but instead look up the previous definition
         ILanguageModel loaded = beingLoaded.get(srcFile.absFileName);
         if (loaded != null) {
            TypeDeclaration loadedModelType = loaded.getLayerTypeDeclaration();
            if (loadedModelType != null)
               return loadedModelType;
            return getSrcTypeDeclaration(typeName, srcFile.layer, prependPackage, notHidden);
         }

         Object modelObj = getLanguageModel(srcFile);

         if (modelObj == null && srcFile.layer != null && !srcFile.layer.activated)
            return getInactiveTypeDeclaration(srcFile);

         if (modelObj == null && (srcFile.layer == null || srcFile.layer.activated)) {
            nestLevel++;
            //System.out.println(StringUtil.indent(nestLevel) + "resolving " + typeName);
            try {
               modelObj = parseSrcType(srcFile, fromLayer, false, true);
            }
            finally {
               //System.out.println(StringUtil.indent(nestLevel) + "done " + typeName);
              nestLevel--;
            }
         }

         // The cached for types only looks for active types so don't use it if we are not looking for an activated layer
         if (cacheForRefLayer(srcFile.layer)) {
            //else
            //   System.out.println(StringUtil.indent(nestLevel) + "using cached model " + typeName);
            decl = getCachedTypeDeclaration(typeName, fromLayer, fromLayer == null ? srcFile.layer : null, false, false, srcFile.layer, false);
            if (decl == INVALID_TYPE_DECLARATION_SENTINEL)
               return null;
            // Because we updated the fromPosition in the previous getCachedTypeDeclaration we might return the wrong
            // model file here - if so, just ignore and get it from the srcFile.
            if (decl != null && decl.getLayer() != srcFile.layer)
               decl = null;
         }
         else
            decl = null;

         if ((decl == null || decl == skippedDecl) && srcFile.layer != null && modelObj instanceof JavaModel) {
            JavaModel javaModel = (JavaModel) modelObj;
            if (!javaModel.isInitialized())
               ParseUtil.initComponent(javaModel);
            if (javaModel.isLayerModel) {
               if (!layerResolve)
                  return null;
            }
            if (javaModel.getModelTypeName().equals(typeName))
               return javaModel.getLayerTypeDeclaration();
         }
         return decl;
         // else - we found this file in the model index but did not find it from this type name.  This happens
         // when the type name matches, but the package prefix does not.
      }
      else if (!rootTypeOnly) {
         return getInnerClassDeclaration(typeName, fromLayer, notHidden, srcOnly, refLayer, layerResolve, includeEnums);
      }
      else
         return null;
   }

   /** Returns false if there's no entry with this name - explicitly without parsing it if it's not already loaded */
   public boolean hasAnyTypeDeclaration(String typeName) {
      TypeDeclaration decl = getCachedTypeDeclaration(typeName, null, null, true, false, null, false);
      if (decl != null)
         return true;
      if (getCanUseCompiledType(typeName) && getClassWithPathName(typeName) != null)
         return true;
      if (getSrcFileFromTypeName(typeName, true, null, true, null) != null)
         return true;
      return false;
   }

   /** When buildAllFiles is true, do not bother with the optimization for using compiled types */
   public boolean getUseCompiledForFinal() {
      // TODO: Even if buildAllFiles is true we want this optimization.  Otherwise, source will be loaded for the sc.bind classes
      // in the sys/sccore layer.  It sets compiled=true before we've even built so we should always load those files as binary.
      return options.useCompiledForFinal;
   }

   public Object getTypeDeclaration(String typeName) {
      return getTypeDeclaration(typeName, false, null, false);
   }

   // TODO: can we just remove this whole method now that there's a srcOnly parameter to getSrcTypeDeclaration - just call that?
   // First if we've loaded the src we need to return that.
   public Object getTypeDeclaration(String typeName, boolean srcOnly, Layer refLayer, boolean layerResolve) {
      boolean beingReplaced = false;
      if (cacheForRefLayer(refLayer)) {
         TypeDeclaration decl = getCachedTypeDeclaration(typeName, null, null, true, false, refLayer, false);
         if (decl != null) {
            if (decl == INVALID_TYPE_DECLARATION_SENTINEL)
               return null;
            JavaModel model = decl.getJavaModel();
            // Skip the cache as we are replacing this model so we can resolve the "beingLoaded" model instead
            if (model.replacedByModel == null) {

               // We may not have loaded all of the files for this type.  Currently we load all types we need to process during the build but then may just grab the most specific one when resolving a global type.  If there are gaps created the fromPosition thing does not protect us from returning a stale entry.
               if (decl.getEnclosingType() == null) {
                  SrcEntry srcFile = getSrcFileFromTypeName(typeName, true, null, true, null);
                  if (srcFile == null || decl.getLayer() != srcFile.layer) {
                     decl = null;
                  }
               }
               if (decl != null)
                  return decl;
            }
            else {
               beingReplaced = true;
            }
         }
      }

      if (getUseCompiledForFinal() && !srcOnly && !beingReplaced) {
         // Now since the model is not changed, we'll use the class.  If at some point we need to change the compiled version - ie. to make a property bindable, we'll load the src at that time.
         Object cl = getCompiledType(typeName);
         if (cl != null)
            return cl;
      }
      Object td = getSrcTypeDeclaration(typeName, null, true, false, srcOnly, refLayer, layerResolve);
      if (td == null && !srcOnly)
         return getClassWithPathName(typeName, refLayer, layerResolve, false, false, layerResolve);
      return td;
   }

   public Object getRuntimeTypeDeclaration(String typeName) {
      Object td;
      // If the system is compiled, first look for a runtime compiled class so we don't go loading the src all of the time if we don't have to.
      if (systemCompiled && getLoadClassesInRuntime() && (runtimeProcessor == null || runtimeProcessor.usesThisClasspath())) {
         if (buildLayer != null && !buildLayer.isDynamicType(typeName)) {
            td = getClassWithPathName(typeName);
            if (td != null) {
               // If the class is dynamic, we still want to return the source version of the class.  It seems fixable but right now you can't go from the DynObject Class to the TypeDeclaration easily and the static state is stored with the type declaration.
            //   if (!IDynObject.class.isAssignableFrom((Class) td))
               return td;
            }
         }
      }
      td = getTypeDeclaration(typeName);
      // Don't init the static variables until we are able to init them - after the system is compiled.
      if (td != null && (td instanceof TypeDeclaration) && systemCompiled && getLoadClassesInRuntime())
         ((TypeDeclaration) td).staticInit();
      return td;
   }

   private final boolean useCompiledTemplates = true;

   public String evalTemplate(Object paramObj, String typeName, Class paramClass, Layer refLayer, boolean layerResolve) {
      if (useCompiledTemplates) {
         Object templType = getClassWithPathName(typeName);
         if (templType != null) {
            PerfMon.start("evalCompiledTemplate");
            if (ModelUtil.isAssignableFrom(paramClass, templType)) {
               Object templInst = null;
               String res = null;
               try {
                  templInst = DynUtil.createInstance(templType, null);
                  if (templInst == null)
                     System.err.println("No instance for template: " + templType);
                  else {
                     if (templInst instanceof ITemplateInit)
                        ((ITemplateInit) templInst).initTemplate(paramObj);
                     CharSequence sb = (CharSequence) ModelUtil.callMethod(templInst, "output");
                     res = sb == null ? null : sb.toString();
                  }

               }
               catch (RuntimeException exc) {
                  System.err.println("*** error evaluating template: " + templType + ": " + exc);
                  exc.printStackTrace();
               }
               PerfMon.end("evalCompiledTemplate");
               return res;
            }
            else
               System.err.println("*** Template class: " + typeName + " must be of type: " + paramClass);
         }
      }

      PerfMon.start("evalDynTemplate");
      Template template = getTemplate(typeName, null, paramClass, null, refLayer, layerResolve);
      if (template == null) {
         System.err.println("*** No template named: " + typeName);
         return "<error evaluating template>";
      }
      // TODO: for JSTypeTemplate we now should be creating JSTypeTemplateBase, the initTemplate so we are recording line numbers for debugging
      // TODO: should we lazy compile the template here?  It does not seem to make enough of a performance difference
      // to be worth it now.
      String res = TransformUtil.evalTemplate(paramObj, template);
      PerfMon.end("evalDynTemplate");
      return res;
   }

   public Template getTemplate(String typeName, String suffix, Class paramClass, Layer fromLayer, Layer refLayer, boolean layerResolve) {
      Template template;
      if (cacheForRefLayer(refLayer)) {
         template = templateCache.get(typeName);
         if (template != null)
             return template;
      }

      SrcEntry srcFile = getSrcFileFromTypeName(typeName, true, fromLayer, true, suffix, refLayer, layerResolve);

      if (srcFile != null) {
         String type = FileUtil.getExtension(srcFile.baseFileName);

         IFileProcessor fileProc = getFileProcessorForExtension(type);
         if (fileProc == null)
            fileProc = Language.getLanguageByExtension(type);

         if (fileProc instanceof TemplateLanguage) {
            Object result = ((TemplateLanguage) fileProc).parse(srcFile.absFileName, false);
            if (result instanceof ParseError)
               System.err.println("Template file: " + srcFile.absFileName + ": " +
                       ((ParseError) result).errorStringWithLineNumbers(new File(srcFile.absFileName)));
            else {
               template = (Template) ParseUtil.nodeToSemanticValue(result);
               template.defaultExtendsType = paramClass;
               template.layer = srcFile.layer;
               template.setLayeredSystem(this);
               template.setSrcFile(srcFile);

               // Force this to false for templates that are evaluated without converting to Java first
               template.generateOutputMethod = false;

               // initialize and start that model
               ParseUtil.initAndStartComponent(template);

               if (cacheForRefLayer(refLayer))
                  templateCache.put(typeName, template);
               return template;
            }
         }
         else {
            System.err.println("*** Incompatible file processor for template found: " + srcFile + " found: " + fileProc + " which is not a TemplateLanguage");
         }
      }
      // Look in the class path for a resource if there's nothing in the layered system.
      return TransformUtil.parseTemplateResource(typeName, paramClass, getSysClassLoader());
   }

   private boolean hasAnySrcForRootType(String typeName, Layer refLayer) {
      do {
         String rootTypeName = CTypeUtil.getPackageName(typeName);
         if (rootTypeName != null) {
            if (getSrcFileFromTypeName(rootTypeName, true, null, true, null, refLayer, false) != null)
               return true;
         }
         typeName = rootTypeName;
      } while (typeName != null);
      return false;
   }

   int loopCt = 0;

   private static boolean cacheForRefLayer(Layer refLayer) {
      if (refLayer == null)
         return true;
      return refLayer.cacheForRefLayer();
   }

   private static boolean searchActiveTypesForLayer(Layer refLayer) {
      return refLayer == null || refLayer.activated || refLayer == Layer.ANY_LAYER;
   }

   private static boolean searchInactiveTypesForLayer(Layer refLayer) {
      return refLayer != null && !refLayer.disabled && (!refLayer.activated || refLayer == Layer.ANY_LAYER || refLayer == Layer.ANY_INACTIVE_LAYER || refLayer == Layer.ANY_OPEN_INACTIVE_LAYER);
   }

   /** Try peeling away the last part of the type name to find an inner type */
   Object getInnerClassDeclaration(String typeName, Layer fromLayer, boolean notHidden, boolean srcOnly, Layer refLayer, boolean layerResolve, boolean includeEnums) {
      String rootTypeName = typeName;
      String subTypeName = "";
      TypeDeclaration decl;

      if (fromLayer == null && cacheForRefLayer(refLayer)) {
         Object res = innerTypeCache.get(typeName);
         if (res != null) {
            if (srcOnly && ((res instanceof Class) || res instanceof CFClass)) {
               if (!hasAnySrcForRootType(typeName, refLayer))
                  return null;
               else
                  removeFromInnerTypeCache(typeName);
            }
            else {
               if (res == INVALID_TYPE_DECLARATION_SENTINEL)
                  return null;

               // Don't use the cache while the model is being reloaded since we want to get the new types we are loading, not the old ones
               if (res instanceof BodyTypeDeclaration) {
                  JavaModel resModel = ((BodyTypeDeclaration) res).getJavaModel();
                  if (resModel.replacedByModel == null)
                     return res;
               }
               else
                  return res;
            }
         }
      }

      int ix;
      do {
         ix = rootTypeName.lastIndexOf(".");
         if (ix != -1) {
            rootTypeName = typeName.substring(0, ix);
            subTypeName = typeName.substring(ix+1);

            // Need to back the root up one layer so it includes the current layer of the child.
            Layer rootFrom = fromLayer == null ? null : fromLayer.getNextLayer();

            Object rootType = null;
            if (rootFrom == null && !srcOnly) {
               Object rootTypeObj = getTypeDeclaration(rootTypeName, false, refLayer, layerResolve);
               if (rootTypeObj != null)
                  rootType = rootTypeObj;
            }

            // Only look for the root type here - do not look for inner types.  Otherwise we do the same lookups repeatedly and we keep walking the rootFromLayer back each time.
            if (rootType == null)
               rootType = getSrcTypeDeclaration(rootTypeName, rootFrom, true, notHidden, true, refLayer == null ? fromLayer : refLayer, layerResolve, true, false);

            if (rootType == null && layerResolve && rootTypeName.equals(LayerConstants.LAYER_COMPONENT_FULL_TYPE_NAME)) {
               List<Layer> layersList = refLayer == null || refLayer == Layer.ANY_LAYER ? layers : (refLayer == Layer.ANY_INACTIVE_LAYER ? inactiveLayers : refLayer.getLayersList());
               // If we are initializing the fromLayer's layer def objects it is not in the list yet
               int pos = fromLayer == null || !fromLayer.isInitialized() ? layersList.size()-1 : fromLayer.layerPosition - 1;

               for (int l = pos; l >= 0; l--) {
                  Layer searchLayer = layersList.get(l);
                  if (searchLayer.model != null) {
                     TypeDeclaration layerType = searchLayer.model.getModelTypeDeclaration();
                     if (layerType != null) {
                        Object declObj = ModelUtil.getInnerType(layerType, subTypeName, null);
                        if (declObj != null)
                           return declObj;
                     }
                  }
               }
            }

            if (rootType != null) {
               String rootTypeActualName = ModelUtil.getTypeName(rootType);
               boolean rootIsSameType = rootTypeActualName.equals(rootTypeName);
               Object declObj = rootType instanceof BodyTypeDeclaration ? ((BodyTypeDeclaration) rootType).getInnerType(subTypeName, fromLayer != null ? new TypeContext(fromLayer, rootIsSameType) : null, true, false, srcOnly, includeEnums) :
                                                            ModelUtil.getInnerType(rootType, subTypeName, null);
               //if (declObj == null && fromLayer != null) {
               //   declObj = rootType.getInnerType(subTypeName, null, true, false);
               //   if (declObj != null)
               //      System.out.println("*** Found the inner object without a frome layer that didn't exist with the from layer.");
               //}
               if (declObj instanceof TypeDeclaration) {
                  decl = (TypeDeclaration) declObj;

                  // Only register this inner type if it's part of the direct type name space.  We might have inherited this from a base type
                  if (decl.getEnclosingType() == rootType) {
                     // Replace this type if it has been modified by a component in a subsequent layer unless we have specified a 'fromLayer' filter in this search
                     if (decl.replacedByType != null && (decl.replaced || fromLayer == null))
                        decl = (TypeDeclaration) decl.replacedByType;
                     // For type declarations that are in templates, the layer may not be initialized so do it here before
                     // we put it into the type system.
                     Layer declLayer = decl.getJavaModel().getLayer();
                     if (declLayer == null)
                        warning("*** Warning - adding inner type without a layer");

                     if (declLayer == null || declLayer.activated)
                        addTypeByName(declLayer, CTypeUtil.prefixPath(rootTypeActualName, subTypeName), decl, fromLayer);
                  }
                  if (fromLayer == null && cacheForRefLayer(refLayer))
                     addToInnerTypeCache(typeName, decl);
                  return decl;
               }
               else if (declObj != null) {
                  if (includeEnums && declObj instanceof EnumConstant)
                     return declObj;
                  if (!srcOnly) {
                     if (fromLayer == null && cacheForRefLayer(refLayer))
                        addToInnerTypeCache(typeName, declObj);
                     return declObj;
                  }
                  // We need source but we got back a compiled inner type.  If it's inherited, maybe we can load the source.  Need to be careful here
                  // not to do this if we already have the src because there are cases where we have the source for the outer class but not the inner.
                  else if (!ModelUtil.sameTypes(rootType, ModelUtil.getEnclosingType(declObj)) && subTypeName.indexOf(".") == -1) {
                     // Otherwise, this can infinite loop
                     if (hasAnySrcForRootType(typeName, refLayer)) {
                        // TODO: remove this once we've figured out all of the cases properly.  At some point we need to verify that we've got the
                        // source for the parent types of declObj and tried to find the inner type on that.  When that returns null, we bail.
                        // One weird case is where the annotation layer is source but we don't have the source for the inner class.  That's handled by the
                        // subTypeName.indexOf(".") test above.
                        if (++loopCt > 20) {
                           warning("loop finding inner type.");
                           loopCt = 0;
                           return null;
                        }
                        Object newDeclObj = ModelUtil.resolveSrcTypeDeclaration(this, declObj, false, true, refLayer);
                        loopCt--;
                        if (newDeclObj instanceof TypeDeclaration)
                           return newDeclObj;
                     }
                  }
               }

               // ??? Need to do anything with Class's we inherit here?  They should get picked up in the class search I think
            }
         }
      } while (ix != -1);

      if (fromLayer == null && cacheForRefLayer(refLayer)) {
         addToInnerTypeCache(typeName, INVALID_TYPE_DECLARATION_SENTINEL);
      }
      return null;
   }

   public Object removeFromInnerTypeCache(String typeName) {
      return innerTypeCache.remove(typeName);
   }

   private void addToInnerTypeCache(String typeName, Object type) {
      innerTypeCache.put(typeName, type);
   }

   public Object getCompiledType(String fullTypeName) {
      /*
      if (fullTypeName.equals("sc.bind.Bind") && runtimeProcessor != null)
         System.out.println("---");
      if (fullTypeName.contains("SyncMode") && runtimeProcessor != null && getSrcFileFromTypeName(fullTypeName, false, null, true, null) != null)
         System.out.println(---;
      */

      // When doing an incremental compile, we don't want to force all source to be loaded.  Only the changed files at first
      if (getCanUseCompiledType(fullTypeName)) {
         // Now since the model is not changed, we'll use the class.  If at some point we need to change the compiled version - ie. to make a property bindable, we'll load the src at that time.
         Object cl = getClassWithPathName(fullTypeName);

         // If the class has a jsModuleFile attribute and we have build-all we need to load the source so that we include all source files in the module.
         if (cl != null && (!options.buildAllFiles || runtimeProcessor == null || !runtimeProcessor.needsSrcForBuildAll(cl))) {
            return cl;
         }
      }
      return null;
   }

   public Object getRelativeTypeDeclaration(String typeName, String packagePrefix, Layer fromLayer, boolean prependPackage, Layer refLayer, boolean layerResolve, boolean srcOnly) {
      // TODO: Should we first be trying packagePrefix+typeName in the global cache?
      SrcEntry srcFile = getSrcFileFromRelativeTypeName(typeName, packagePrefix, true, fromLayer, prependPackage, refLayer, layerResolve);
      
      if (srcFile != null) {
         // When prependLayerPackage is false, lookup the type without the layer's package (e.g. doc/util.vdoc)
         String fullTypeName = srcFile.getTypeName();
         ILanguageModel loading = beingLoaded.get(srcFile.absFileName);
         if (cacheForRefLayer(refLayer)) {
            // If we did not specify a limit to the layer, use the current file's layer.  If we add a new layer we'll
            // only reload if there is a file for this type in that new layer.
            TypeDeclaration decl = getCachedTypeDeclaration(fullTypeName, fromLayer, fromLayer == null ? srcFile.layer : null, true, false, refLayer, false);
            if (decl != null) {
               if (decl == INVALID_TYPE_DECLARATION_SENTINEL)
                  return null;
               if (loading == null)
                  return decl;
            }
         }

         // When doing an incremental compile, we don't want to force all source to be loaded.  Only the changed files at first
         Object cl = getCompiledType(fullTypeName);
         if (cl != null && loading == null)
            return cl;

         // Do not try to load a file which in the midst of being loaded.  This happens in some weird case where you are initializing a Template, which
         // then needs to init the type info for its base type to create the object definition.  In that process, we start looking up an inner class using
         // no layer to try and find the modified type of some inner class.  We should not let it find this type in that case but instead look up the previous definition
         if (loading != null) {
            TypeDeclaration loadedModelType = loading.getModelTypeDeclaration();
            if (loadedModelType != null)
               return loadedModelType;
            return getRelativeTypeDeclaration(typeName, packagePrefix, srcFile.layer, prependPackage, refLayer, layerResolve, srcOnly);
         }

         if (srcFile.layer != null && !srcFile.layer.activated)
            return getInactiveTypeDeclaration(srcFile);

         TypeDeclarationCacheEntry decls;

         // May have parsed this file as a different type name, i.e. a layer object.  If so, just return null since
         // in any case, its type did not get added to the cache.
         ILanguageModel langModel = getLanguageModel(srcFile);
         if (langModel == null) {
            Object modelObj = parseSrcType(srcFile, fromLayer, false, true);
            if (modelObj instanceof ILanguageModel) {
               decls = typesByName.get(fullTypeName);
               if (decls == null || decls.size() == 0)
                  return null;
               return decls.get(0);
            }
         }
         else {
            if (!langModel.isInitialized())
               ParseUtil.initComponent(langModel);
            // When we load the model through the IDE, it does not get put into the type cache reliably
            ITypeDeclaration modelType = langModel.getModelTypeDeclaration();
            if (modelType != null && modelType.getFullTypeName().equals(fullTypeName))
               return modelType;
         }
         return null;
      }
      return getInnerClassDeclaration(CTypeUtil.prefixPath(packagePrefix, typeName), fromLayer, false, srcOnly, refLayer, layerResolve, false);
   }

   public Object parseInactiveFile(SrcEntry srcEnt) {
      // First try to find the JavaModel cached outside of the system.
      if (externalModelIndex != null) {
         ILanguageModel model = externalModelIndex.lookupJavaModel(srcEnt, true);
         if (model != null)
            return model;
      }
      return parseSrcFile(srcEnt, srcEnt.isLayerFile(), true, false, true, false);
   }

   public Object parseActiveFile(SrcEntry srcEnt) {
      ILanguageModel model = getCachedModel(srcEnt, true);
      if (model == null) {
         return parseSrcFile(srcEnt, srcEnt.isLayerFile(), true, false, true, false);
      }
      return model;
   }

   public JavaModel parseInactiveModel(SrcEntry srcEnt) {
      Object res = parseInactiveFile(srcEnt);
      if (res instanceof JavaModel)
         return ((JavaModel) res);
      else
         System.err.println("*** srcEnt is not a JavaModel: " + res);
      return null;
   }

   public TypeDeclaration getInactiveTypeDeclaration(SrcEntry srcEnt) {
      ILanguageModel model;
      if (srcEnt.layer != null && srcEnt.layer.activated)
         System.err.println("*** Using active layer for inactive lookup!");
      // First try to find the JavaModel cached outside of the system.
      if (externalModelIndex != null) {
         // If we are loading it don't try to load it again
         model = beingLoaded.get(srcEnt.absFileName);
         if (model == null) {
            model = externalModelIndex.lookupJavaModel(srcEnt, true);
            if (model != null) {
               if (!model.isInitialized())
                  System.err.println("*** Error - external index returns model that's not initialized!");

               //ParseUtil.initAndStartComponent(model);
               if (model.getLayer() != null && model.getLayer().activated)
                  System.out.println("*** Error - renaming activated model to inactive index");
               if (model.getLayeredSystem() != this) {
                  if (model.getLayeredSystem().inactiveModelIndex.put(srcEnt.absFileName, model) != model)
                     System.out.println("*** Updating model index in model's layered system for: " + srcEnt.absFileName + " to: " + System.identityHashCode(model));
               }
               if (inactiveModelIndex.put(srcEnt.absFileName, model) != model)
                  System.out.println("*** Updating model index for: " + srcEnt.absFileName + " to: " + System.identityHashCode(model));
            }
         }
         if (model instanceof JavaModel)
            return ((JavaModel) model).getModelTypeDeclaration();
      }
      model = inactiveModelIndex.get(srcEnt.absFileName);
      if (model == null) {
         Object result = parseSrcFile(srcEnt, srcEnt.isLayerFile(), true, false, !options.disableCommandLineErrors, false);
         // We might have to load files for inactive types which the IDE is not maintaining.  So we always cache these
         // models in the local index so we avoid loading them over and over again.
         if (result instanceof ILanguageModel) {
            model = (ILanguageModel) result;
            if (model.getLayer() != null && model.getLayer().activated)
               System.out.println("*** Error - adding activated model to inactive index");
            addNewModel(model, null, null, null, (model instanceof JavaModel && ((JavaModel) model).isLayerModel), false);
         }
         else if (result != null) {
            error("Parsing inactive type: " + srcEnt + ": " + ((ParseError) result).errorStringWithLineNumbers(srcEnt.absFileName));
         }
         else
            error("Parsing type: " + srcEnt + ": returned null");
      }
      else {
         model.setLastAccessTime(System.currentTimeMillis());
      }
      if (model instanceof JavaModel)
         return ((JavaModel) model).getModelTypeDeclaration();
      return null;
   }

   /** Auto imports are resolved by searching through the layer hierarchy from the layer which originates the reference */
   public ImportDeclaration getImportDecl(Layer fromLayer, Layer refLayer, String typeName) {
      ImportDeclaration importDecl = null;
      if (refLayer != null && !refLayer.activated) {
         importDecl = refLayer.getImportDecl(typeName, true);
         if (importDecl != null)
            return importDecl;
      }
      else if (searchActiveTypesForLayer(refLayer)) {
         if (refLayer == null || refLayer == Layer.ANY_LAYER || refLayer.inheritImports) {
            int startIx = fromLayer == null ? layers.size() - 1 : fromLayer.getLayerPosition();
            for (int i = startIx; i >= 0; i--) {
               Layer depLayer = layers.get(i);
               // We only inherit imports from a layer which we directly extend.
               if (depLayer.exportImportsTo(refLayer))
                  importDecl = depLayer.getImportDecl(typeName, false);
               if (importDecl != null)
                  return importDecl;
            }
         }
         // When inheritImports is false, we only use this layer's imports and don't allow overriding of imports
         else {
            return refLayer.getImportDecl(typeName, false);
         }
      }
      return globalImports.get(typeName);
   }

   public ImportDeclaration getLayerImport(String typeName) {
      return globalLayerImports.get(typeName);
   }

   /**
    * Use this to add an import that will only be valid for processing all layer definition files.
    */
   public void addLayerImport(String importedName, boolean isStatic) {
      ImportDeclaration impDecl = ImportDeclaration.create(importedName);
      impDecl.staticImport = isStatic;
      globalLayerImports.put(CTypeUtil.getClassName(importedName), impDecl);
   }

   public boolean isImported(String srcTypeName) {
      String pkg = CTypeUtil.getPackageName(srcTypeName);
      if (pkg != null && pkg.equals("java.lang"))
         return true;
      return getImportDecl(null, null, CTypeUtil.getClassName(srcTypeName)) != null;
   }

   public Object getImportedStaticType(String name, Layer fromLayer, Layer refLayer) {
      if (fromLayer != null && fromLayer.disabled)
         return null;
      // TODO: I believe we should only be including layers explicitly extended by refLayer in this search
      List<Layer> layerList = fromLayer == null ? refLayer == null ? layers : refLayer.getLayersList() : fromLayer.getLayersList();
      if (layerList == null) {
         System.err.println("*** No layers list!");
         return null;
      }
      int startIx = fromLayer == null ? layerList.size() - 1 : fromLayer.getLayerPosition();
      if (startIx >= layerList.size())
         startIx = layerList.size() - 1;
      for (int i = startIx; i >= 0; i--) {
         Object m = layerList.get(i).getStaticImportedType(name);
         if (m != null)
            return m;
      }
      return null;
   }

   /**
    * The autoImports are created each time we reference a type that is not explicitly imported during the
    * start process on the original model.  Because of the lazy way in which models get loaded and resolved
    * I don't think we can rely on imports being added in the right order.  And yet, it will be important that
    * a later layer be able to override the import in a previous layer.
    */
   public void addAutoImport(Layer srcLayer, String typeName, ImportDeclaration importDecl) {
      TypeDeclarationCacheEntry decls = typesByName.get(typeName);

      if (decls == null) {
         decls = new TypeDeclarationCacheEntry(1);
         typesByName.put(typeName, decls);
      }

      String baseTypeName = CTypeUtil.getClassName(importDecl.identifier);
      if (baseTypeName == null) {
         System.err.println("*** Invalid import name: " + typeName);
         return;
      }

      AutoImportEntry aie = decls.autoImportsIndex == null ? null : decls.autoImportsIndex.get(baseTypeName);
      if (aie == null || srcLayer == null || srcLayer.getLayerPosition() > aie.layerPosition) {
         if (aie != null) {
            decls.autoImports.remove(aie.importDecl);
         }
         else {
            aie = new AutoImportEntry();
            if (decls.autoImportsIndex == null)
               decls.autoImportsIndex = new TreeMap<String,AutoImportEntry>();
            decls.autoImportsIndex.put(baseTypeName, aie);
         }
         aie.importDecl = importDecl;
         aie.layerPosition = srcLayer == null ? layers.size()-1 : srcLayer.layerPosition;
         if (decls.autoImports == null)
            decls.autoImports = new SemanticNodeList<ImportDeclaration>();
         decls.autoImports.add(importDecl);
      }
   }

   public SemanticNodeList<ImportDeclaration> getAutoImports(String typeName) {
      TypeDeclarationCacheEntry decls = typesByName.get(typeName);
      if (decls == null) return null;
      return decls.autoImports;
   }

   public SrcEntry getSrcFileFromTypeName(String typeName, boolean srcOnly, Layer fromLayer, boolean prependPackage, String suffix) {
      return getSrcFileFromTypeName(typeName, srcOnly, fromLayer, prependPackage, suffix, null, false);
   }

   /**
    * Returns the Java file that defines this type.  If srcOnly is false, it will also look for the file
    * which defines the class if no Java file is found.  You can use that mode to get the file which
    * defines the type for dependency purposes.
    */
   public SrcEntry getSrcFileFromTypeName(String typeName, boolean srcOnly, Layer fromLayer, boolean prependPackage, String suffix, Layer refLayer, boolean layerResolve) {
      String subPath = typeName.replace('.', FileUtil.FILE_SEPARATOR_CHAR);
      if (suffix != null)
         subPath = FileUtil.addExtension(subPath, suffix);

      int startIx;
      if (searchActiveTypesForLayer(refLayer)) {
         // In general we search from most recent to original
         startIx = layers.size() - 1;

         // Only look at layers which precede the supplied layer
         if (fromLayer != null && fromLayer.activated)
            startIx = fromLayer.layerPosition - 1;
         for (int i = startIx; i >= 0; i--) {
            Layer layer = layers.get(i);

            SrcEntry res = layer.getSrcFileFromTypeName(typeName, srcOnly, prependPackage, subPath, layerResolve);
            if (res != null)
               return res;
         }
      }

      // Should we check the inactiveLayers?  Only if we original the search from an inactive layer.  By default, don't look at inactiveLayers when we are building
      if (searchInactiveTypesForLayer(refLayer) || (fromLayer != null && !fromLayer.activated)) {
         startIx = inactiveLayers.size() - 1;
         if (fromLayer != null && !fromLayer.activated && fromLayer.layerPosition - 1 < inactiveLayers.size()) {
            if (!layerResolve) {
               // First we are going to check the baseLayers of the fromLayer - rather than just picking up the next layer in the stack.  That way, the type reference here is
               // the one specified in the code dependencies.  Right now we are facing a problem where inserting 'unitConverter.coreui' inbetween unitConverter.extendedModel and model
               // does not cause the UC in extendedModel to refresh it's modified type and so the jsui layer that's inserted after gets the extendedModel without the coreui layer
               // in the stack.
               SrcEntry ent = fromLayer.getBaseLayerSrcFileFromTypeName(typeName, srcOnly, prependPackage, subPath, processDefinition, layerResolve);
               if (ent != null)
                  return ent;
            }
            startIx = fromLayer.layerPosition - 1;
         }
         for (int i = startIx; i >= 0; i--) {
            Layer inactiveLayer = inactiveLayers.get(i);
            //SrcEntry ent = inactiveLayer.getInheritedSrcFileFromTypeName(typeName, srcOnly, prependPackage, subPath, processDefinition, layerResolve);
            if (includeInactiveLayerInSearch(inactiveLayer, refLayer)) {
               SrcEntry ent = inactiveLayer.getSrcFileFromTypeName(typeName, srcOnly, prependPackage, subPath, layerResolve);
               if (ent != null) {
                  if (ent.layer.closed)
                     ent.layer.markClosed(false, false);
                  return ent;
               }
            }
         }
      }

      return null;
   }

   private boolean includeInactiveLayerInSearch(Layer inactiveLayer, Layer refLayer) {
      if (refLayer == Layer.ANY_OPEN_INACTIVE_LAYER)
         return !inactiveLayer.closed;
      if (refLayer == Layer.ANY_LAYER || refLayer == Layer.ANY_INACTIVE_LAYER || refLayer == null || refLayer == inactiveLayer)
         return true;
      if (refLayer.layeredSystem != inactiveLayer.layeredSystem)
         System.err.println("*** Mismatching runtimes for type lookup!");
      return true; // Need to include closed layers here - might need to open it to resolve the right reference
   }

   /**
    * Returns the Java file that defines this type.  If srcOnly is false, it will also look for the file
    * which defines the class if no Java file is found.  You can use that mode to get the file which
    * defines the type for dependency purposes.
    */
   public SrcEntry getSrcFileFromRelativeTypeName(String typeName, String packagePrefix, boolean srcOnly, Layer fromLayer, boolean prependPackage, Layer refLayer, boolean layerResolve) {
      String relDir = packagePrefix != null ? packagePrefix.replace('.', FileUtil.FILE_SEPARATOR_CHAR) : null;
      String subPath = typeName.replace('.', FileUtil.FILE_SEPARATOR_CHAR);

      if (searchInactiveTypesForLayer(refLayer)) {
         int startIx = inactiveLayers.size() - 1;

         // Only look at layers which precede the supplied layer
         if (fromLayer != null) {
            if (fromLayer.disabled)
               return null;
            startIx = fromLayer.layerPosition - 1;
         }
         for (int i = startIx; i >= 0; i--) {
            Layer inactiveLayer = inactiveLayers.get(i);
            if (layerResolve || !inactiveLayer.excludeForProcess(processDefinition)) {
               if (includeInactiveLayerInSearch(inactiveLayer, refLayer)) {
                  SrcEntry ent = inactiveLayer.getSrcFileFromRelativeTypeName(relDir, subPath, packagePrefix, srcOnly, false, layerResolve);
                  if (ent != null) {
                     if (ent.layer.closed)
                        ent.layer.markClosed(false, false);
                     return ent;
                  }
               }
            }
         }
      }

      if (searchActiveTypesForLayer(refLayer)) {
         // In general we search from most recent to original
         int startIx = layers.size() - 1;

         // Only look at layers which precede the supplied layer
         if (fromLayer != null)
            startIx = fromLayer.layerPosition - 1;
         for (int i = startIx; i >= 0; i--) {
            Layer layer = layers.get(i);

            SrcEntry layerEnt = layer.getSrcFileFromRelativeTypeName(relDir, subPath, packagePrefix, srcOnly, false, layerResolve);
            if (layerEnt != null)
               return layerEnt;
         }
      }
      return null;
   }

   public Object getClassWithPathName(String pathName) {
      return getClassWithPathName(pathName, null, false, false, false, true);
   }

   public Object getClassWithPathName(String pathName, Layer refLayer, boolean layerResolve, boolean alwaysCheckInnerTypes, boolean forceClass) {
      return getClassWithPathName(pathName, refLayer, layerResolve, alwaysCheckInnerTypes, forceClass, true);
   }

   public Object getClassWithPathName(String pathName, Layer refLayer, boolean layerResolve, boolean alwaysCheckInnerTypes, boolean forceClass, boolean compiledOnly) {
      if (!compiledOnly && !forceClass && refLayer != null && !refLayer.activated && externalModelIndex != null) {
         Object extClass = externalModelIndex.getTypeDeclaration(pathName);
         if (extClass != null)
            return extClass;
      }
      Object c = getClass(pathName, layerResolve || forceClass);
      if (c != null)
         return c;

      String rootTypeName = pathName;
      int lix;
      // This is trying to handle "a.b.C.innerType" references.  Walking from the end of the type
      // name, keep peeling of name parts until we find a base type which matches.  Then go and
      // see if it defines those inner types.
      while ((lix = rootTypeName.lastIndexOf(".")) != -1) {
         String nextRoot = rootTypeName.substring(0, lix);
         String tail = pathName.substring(lix+1);
         if (alwaysCheckInnerTypes || !buildingSystem || !toBeCompiled(nextRoot, refLayer, layerResolve)) {
            Object rootClass = getClass(nextRoot, layerResolve);
            if (rootClass != null && (c = ModelUtil.getInnerType(rootClass, tail, null)) != null)
               return c;
         }
         rootTypeName = nextRoot;
      }
      return null;
   }

   /**
    * Returns true for any types which are defined by src in layers that have yet to be compiled.  We can't load these
    * as class files before they are compiled or we'll blow up.  A better way to fix this would be to eliminate all
    * getClass(typeName) calls for a type which exists in this state but that may be difficult so this is a stop-gap
    * way of avoiding the same thing.  Right now, the key problem is JavaModel.findTypeDeclaration call to getClass
    * when it can't find it as a relative type.
    */
   public boolean toBeCompiled(String typeName, Layer refLayer, boolean layerResolve) {
      SrcEntry srcEnt = getSrcFileFromTypeName(typeName, true, null, true, null, refLayer, layerResolve);
      return srcEnt != null && srcEnt.layer != null && !srcEnt.layer.getCompiledFinalLayer() && (!srcEnt.layer.compiled || !srcEnt.layer.buildSeparate);
   }

   public ClassLoader getSysClassLoader() {
      // This is the servlet web-app class loader case - at runtime with a class loader that will include our buildDir
      //if (systemClassLoader != null && !autoClassLoader)
      //   return systemClassLoader;

      if (buildClassLoader == null) {
         if (systemClassLoader != null)
            return systemClassLoader;
         return getClass().getClassLoader();
      }
      else
         return buildClassLoader;
   }

   public boolean isEnumConstant(Object obj) {
      return obj instanceof java.lang.Enum || obj instanceof DynEnumConstant;
   }

   public boolean isEnumType(Object type) {
      return ModelUtil.isEnumType(type);
   }

   public Object getEnumConstant(Object typeObj, String enumConstName) {
      if (typeObj instanceof Class) {
         Object[] enums = ((Class) typeObj).getEnumConstants();
         for (Object theEnum:enums) {
            String enumName = DynUtil.getEnumName(theEnum);
            if (enumName.equals(enumConstName)) {
               return theEnum;
            }
         }
      }
      else if (typeObj instanceof TypeDeclaration) {
         return ((TypeDeclaration) typeObj).getEnumConstant(enumConstName);
      }
      return null;
   }

   public Object getExtendsType(Object type) {
      Object superType = ModelUtil.getSuperclass(type);
      // Unwrapping this here because SyncManager uses this to map the real .class or type declaration into a list of sync properties
      // If we return ParamTypeDeclaration, it has no api to unwrap it right now that also works in the browser.  Since getSuperclass
      // returns the Class.getSuperclass not getGenericSuperclass, it seems like maybe getSuperclass should do this unwrapping itself?
      if (ModelUtil.hasTypeParameters(superType))
         return ModelUtil.getParamTypeBaseType(superType);
      return superType;
   }

   private ZipFile getCachedZipFile(String pathName) {
      ZipFile zf;
      if ((zf = zipFileCache.get(pathName)) == null) {
         // Tried this and it failed
         if (zipFileCache.containsKey(pathName))
            return null;

         try {
            zf = new ZipFile(pathName);
            zipFileCache.put(pathName, zf);
         }
         catch (IOException exc) {
            System.err.println("Unable to open zip file in path: " + pathName);
            zipFileCache.put(pathName, null);
         }
      }
      return zf;
   }

   /** The core method to use to retrieve a compiled class from the system.
    * It will use either of two mechanisms to get the class definition - read it using the ClassLoader or
    * it will find the file and parse the .class file into a lightweight data structure - CFClass.  If you require
    * a true runtime class, provide forceClass=true.
    */
   public Object getClass(String className, boolean forceClass) {
      Class res;

      res = compiledClassCache.get(className);
      if (res == NullClassSentinel.class)
         return null;
      if (res != null)
         return res;

      if (otherClassCache != null) {
         Object resObj = otherClassCache.get(className);
         if (resObj == NullClassSentinel.class)
            return null;
         if (resObj != null) {
            if (!forceClass || (resObj instanceof Class))
               return resObj;
         }
      }

      if (!options.crossCompile || forceClass) {
         // Check the system class loader first
         res = RTypeUtil.loadClass(getSysClassLoader(), className, false);
         if (res != null) {
            compiledClassCache.put(className, res);
            return res;
         }
      }

      String classFileName = className.replace('.', FileUtil.FILE_SEPARATOR_CHAR) + ".class";
      if (!forceClass) {
         Object resObj = getClassFromClassFileName(classFileName, className);
         if (resObj != null) {
            if (resObj == NullClassSentinel.class)
               return null;
            return resObj;
         }

         // If we are cross compiling and did not find a CFClass, as a last resort lookup for a class - such as [B etc.
         if (options.crossCompile) {
            res = RTypeUtil.loadClass(getSysClassLoader(), className, false);
            if (res != null) {
               compiledClassCache.put(className, res);
               return res;
            }
         }
      }


      /*
      for (int i = layers.size()-1; i >= 0; i--) {
         Layer layer = layers.get(i);
         if ((res = layer.getClass(classFileName, className)) != null) {
            classCache.put(className, res);
            return res;
         }
      }
      */
      // I believe this is now handled by the class index so this code was redundant
      //if (layers.size() > 0) {
         // The layer class loaders are chained so we just need to look for it in the
         // leaf layer.
      //   Layer layer = layers.get(layers.size()-1);
      //   if ((res = layer.getClass(classFileName, className)) != null) {
      //      classCache.put(className, res);
      //      return res;
      //   }
      //}
      if (otherClassCache == null)
         otherClassCache = new HashMap<String, Object>();
      otherClassCache.put(className, NullClassSentinel.class);
      return null;
   }

   /** Note: cfName here is normalized since it comes from the class file */
   public Object getClassFromCFName(String cfName, String className) {
      if (className == null) {
         className = cfName.replace('/', '.');
         className = className.replace('$', '.');
      }
      Object res = otherClassCache.get(className);
      if (res == NullClassSentinel.class)
         return null;
      if (res != null)
         return res;
      
      String classFileName = FileUtil.unnormalize(cfName) + ".class";
      return getClassFromClassFileName(classFileName, className);
   }

   public Object getClassFromClassFileName(String classFileName, String className) {
      PackageEntry ent = getPackageEntry(classFileName);

      Object res;

      while (ent != null && ent.src)
         ent = ent.prev;

      if (ent != null) {
         if (ent.layer != null) {
            if (className == null)
               className = FileUtil.removeExtension(classFileName.replace('$','.'));
            res = ent.layer.getClass(classFileName, className);
            if (res == null) {
               res = NullClassSentinel.class;
            }
            otherClassCache.put(className, res);
            return res;
         }
         else {
            do {
               if (ent.zip) {
                  // We've already opened this zip file during the class path package scan.  My thinking is that
                  // will hold lots of classes in memory if we leave those zip files open since folks tend to have
                  // a lot of crap in their classpath they never use.  Instead, wait for us
                  // to first reference a file in that actual zip before we cache and hold it open.  This does mean
                  // that we are caching files twice.
                  ZipFile zip = getCachedZipFile(ent.fileName);
                  res = CFClass.load(zip, classFileName, this);
                  if (res != null && options.verboseClasses)
                     verbose("Loaded CFClass: " + classFileName + " from jar: " + ent.fileName);
               }
               else {
                  res = CFClass.load(ent.fileName, classFileName, this);
                  if (res != null && options.verboseClasses)
                     verbose("Loaded CFClass: " + classFileName);
               }
               if (res == null && ent.prev != null) {
                  ent = ent.prev;
               }
               else
                  break;
            } while (true);

            if (res == null)
               res = NullClassSentinel.class;
            if (className == null)
               className = FileUtil.removeExtension(classFileName.replace('$','.'));
            if (otherClassCache == null)
               otherClassCache = new HashMap<String,Object>();
            otherClassCache.put(className, res);
            return res;
         }
      }
      return null;
   }

   public static class NullClassSentinel {
   }


   public Object resolveRuntimeName(String name, boolean create, boolean returnTypes) {
      if (systemCompiled && returnTypes) {
         Object c = getClassWithPathName(name);
         if (c != null) {
            if (create && c instanceof Class) {
               Class cl = (Class) c;

               // Need to make sure this class is initialized here
               RTypeUtil.loadClass(cl.getClassLoader(), name, true);

               if (IDynObject.class.isAssignableFrom((Class) cl)) {
                  Object srcName = resolveName(name, create, true);
                  if (srcName != null)
                     return srcName;
               }
            }
            return c;
         }
      }
      return resolveName(name, create, returnTypes);
   }

   public Object resolveName(String name, boolean create) {
      return resolveName(name, create, true);
   }

   public Object resolveName(String name, boolean create, boolean returnTypes) {
      // Using last layer here as the refLayer so we pick up active objects
      return resolveName(name, create, lastLayer, false, returnTypes);
   }

   public Object resolveName(String name, boolean create, Layer refLayer, boolean layerResolve, boolean returnTypes) {
      Object val = globalObjects.get(name);
      if (val != null)
         return val;

      if (layerResolve) {
         Map<String,Layer> pendingLayers = refLayer != null && refLayer.activated ? pendingActiveLayers : pendingInactiveLayers;
         Layer pendingLayer = pendingLayers.get(name);
         if (pendingLayer != null)
            return pendingLayer;
      }

      TypeDeclaration td = refLayer == null ? null : (TypeDeclaration) getSrcTypeDeclaration(name, null, true, false, true, refLayer, layerResolve);
      if (td != null && td.replacedByType != null)
         td = (TypeDeclaration) td.replacedByType;

      // getDeclarationType needs to init/start its extended type - we'll need to disambiguate this later on as
      // the layer code is doing resolveName's before initing the layers.
      if (td != null && (!td.isInitialized() || td.getDeclarationType() == DeclarationType.OBJECT)) {
         int nestLevels;
         String rootName = name;

         // Walk up the name until we can no longer resolve parent.  We need to find the top-most defined type in
         // this path chain and it needs to be the most specific type we can refer to in that chain.  
         TypeDeclaration rootType = td, nextRootType = rootType;
         while (nextRootType != null && rootName.indexOf(".") != -1) {
            String nextRootName = CTypeUtil.getPackageName(rootName);
            nextRootType = getSrcTypeDeclaration(nextRootName, null, true);
            if (nextRootType != null) {
               rootType = nextRootType;
               rootName = nextRootName;
            }
         }

         String pathName = rootName;
         assert name.startsWith(pathName);

         Object nextObj = null;
         Object nextClassObj;

         // Don't resolve the Class for a dynamic stub or a dynamic type without a stub.  It won't have the top-level getX we're looking for,
         // or if there's no stub, we may get a previous compiled version of the class.
         if (td.isDynamicNew() || td.isDynamicStub(true)) {
            nextClassObj = rootType;

            if (create) {
               if (rootType.getDeclarationType() == DeclarationType.OBJECT) {
                  String scopeName = rootType.getInheritedScopeName();
                  if (scopeName == null || scopeName.equals("global")) {
                     nextObj = globalObjects.get(rootType.getFullTypeName());
                     if (nextObj == null) {
                        nextObj = rootType.createInstance();
                        if (nextObj != null)
                           globalObjects.put(rootType.getFullTypeName(), nextObj);
                     }
                  }
                  else {
                     String typeName = rootType.getFullTypeName();
                     ScopeDefinition scope = ScopeDefinition.getScopeByName(scopeName);
                     ScopeContext scopeCtx = scope == null ? null : scope.getScopeContext(true);
                     if (scopeCtx == null)
                        return null;
                     else {
                        Object inst = scopeCtx.getValue(typeName);
                        if (inst == null) {
                           inst = ModelUtil.getObjectInstance(rootType);
                           scopeCtx.setValue(typeName, inst);
                           // Register this instance by name but don't initialize it.
                           SyncManager.registerSyncInst(inst, typeName, scope.scopeId, false);
                        }
                        nextObj = inst;
                     }
                  }
                  if (nextObj == null)
                     return null;
               }
               else // When create is true, we need to initialize the statics of the type - this is for PageDispatcher's (initType's) use of resolveName(true) - where it must initialize the class so we can call addPage in the @URL code-gen.
                  rootType.staticInit();
            }
            else
               return null;
         }
         else {
            nextClassObj = getClassWithPathName(rootName);
            Class nextClass = nextClassObj instanceof Class ? (Class) nextClassObj : null;
            // Look for pkg.Type.getType() if it exists
            if (nextClass != null) {
               try {
                  nextObj = TypeUtil.getPossibleStaticValue(nextClass, CTypeUtil.decapitalizePropertyName(CTypeUtil.getClassName(rootName)));
                  if (nextObj != TypeUtil.NO_PROPERTY_SENTINEL)
                     return nextObj;
                  else
                     nextObj = null;
               }
               // Not a static property
               catch (IllegalArgumentException exc) {
               }
            }
            if (nextClass == null) {
               if (create) {
                  // Only try to create the instance if the root type is an object.
                  if (rootType.getDeclarationType() == DeclarationType.OBJECT) {
                     nextObj = rootType.createInstance();
                     if (nextObj == null)
                        return null;
                  }
               }
               else
                  return null;
            }
         }

         if (rootType != td) {
            int pathIx = pathName.length()+1;
            int nextIx;
            do {
               nextIx = name.indexOf(".", pathIx);
               String nextProp;
               if (nextIx != -1)
                  nextProp = name.substring(pathIx, nextIx);
               else
                  nextProp = name.substring(pathIx);

               if (nextObj == null) {
                  IBeanMapper mapper = DynUtil.getPropertyMapping(nextClassObj, nextProp);
                  if (mapper != null) {
                     // Can't evaluate an instance property anyway
                     if (mapper.getStaticPropertyPosition() != -1)
                        nextObj = mapper.getPropertyValue(null, false, false);
                     else
                        return null;
                  }
                  else {
                     nextClassObj = ModelUtil.getInnerType(nextClassObj, nextProp, null);
                     if (nextClassObj == null)
                        return null;
                  }
               }
               else {
                  Object childObj = null;
                  if (nextObj instanceof INamedChildren) {
                     childObj = ((INamedChildren) nextObj).getChildForName(nextProp);
                  }
                  if (childObj != null)
                     nextObj = childObj;
                  else
                     nextObj = DynUtil.getProperty(nextObj, nextProp);
               }

               pathIx = nextIx+1;
            } while (nextIx != -1);
         }

         Object result = nextObj != null ? nextObj : (returnTypes ? nextClassObj : null);

         ScopeDefinition scope = td.getScopeDefinition();
         if (scope == null || scope.isGlobal())
            globalObjects.put(name, result);

         return result;
      }

      if (td != null) {
         if (create) {
            td.staticInit();
         }
         return td;
      }


      Object c = getClassWithPathName(name, refLayer, layerResolve, false, false, true);
      if (c != null)
         return c;

      return null;
   }

   public Object findType(String typeName) {
      return getRuntimeType(typeName);
   }

   public boolean isObjectType(Object type) {
      return ModelUtil.isObjectType(type);
   }


   public boolean isObject(Object obj) {
      if (objectNameIndex.get(obj) != null)
         return true;
      // Layers are declared as Object type but for sync we need to treat them as on demand
      return ModelUtil.isObjectType(DynUtil.getSType(obj)) && !(obj instanceof Layer);
   }

   public String getObjectName(Object obj) {
      String objName = objectNameIndex.get(obj);
      Object outer = DynUtil.getOuterObject(obj);
      if (objName == null) {
         if (outer == null) {
            Object typeObj = DynUtil.getSType(obj);
            if (ModelUtil.isObjectType(typeObj))
               return ModelUtil.getTypeName(typeObj);
            return DynUtil.getInstanceId(obj);
         }
         // If the instance was created as an inner instance of an object, we need to use
         // the parent object's name so we can find the enclosing instance of the new guy.
         else {
            String outerName = getObjectName(outer);
            String typeClassName = ModelUtil.getClassName(DynUtil.getType(obj));
            String objTypeName = outerName + "." + typeClassName;
            if (ModelUtil.isObjectType(DynUtil.getSType(obj))) {
               return objTypeName;
            }
            if (obj instanceof IObjectId)
               return outerName + "." + DynUtil.getInstanceId(obj);

            // Let the parent provide the name for the child - used for repeating components or others that
            // will have dynamically created child objects
            if (outer instanceof INamedChildren) {
               String childName = ((INamedChildren) outer).getNameForChild(obj);
               if (childName != null)
                  return outerName + "." + childName;
            }
            return DynUtil.getObjectId(obj, null, objTypeName);
         }
      }
      if (outer == null) // Top level object - objectNameIndex stores the component type name.
         return objName;
      else {
         return getObjectName(outer) + "." + objName;
      }
   }

   /** Can we reach this instance via a path name from a root type. */
   public boolean isRootedObject(Object obj) {
      if (obj instanceof java.lang.Enum || obj instanceof DynEnumConstant)
         return true;
      if (objectNameIndex.get(obj) == null) {
         // Also check for a compiled object annotation - needed for when liveDynamicTypes is disabled and we are not tracking the objects
         Object typeObj = DynUtil.getSType(obj);
         // Layers define themselves as of type Object but are not rooted objects since we can't access them from a static reference.
         if (!ModelUtil.isObjectType(typeObj) || ((typeObj instanceof ModifyDeclaration) && ((ModifyDeclaration) typeObj).isLayerType))
            return false;
      }
      Object outer = DynUtil.getOuterObject(obj);
      if (outer == null)
         return true;
      return isRootedObject(outer);
   }

   public void addGlobalObject(String name, Object obj) {
      globalObjects.put(name, obj);
   }

   JavaModel parseLayerModel(SrcEntry defFile, String expectedName, boolean addType, boolean reportErrors) {
      Object modelObj = addType ? parseSrcType(defFile, null, true, reportErrors) : parseSrcFile(defFile, true, false, false, reportErrors, false);
      if (!(modelObj instanceof JavaModel))
         return null;

      JavaModel model = (JavaModel) modelObj;

      TypeDeclaration modelType = model.getModelTypeDeclaration();
      if (modelType == null) {
         model.displayError("Layer definition: " + defFile + " did not define type: " + expectedName);
      }

      // Layer models behave a little differently in terms of how they are initialized so mark this up front
      model.isLayerModel = true;
      return model;
   }

   private Layer getPendingLayer(boolean activated, String layerName) {
      return activated ? pendingActiveLayers.get(layerName) : pendingInactiveLayers.get(layerName);
   }

   private Layer getStubLayer(String expectedName, String layerPathName, String layerDirName) {
      Layer stubLayer = getPendingLayer(false, expectedName);
      if (stubLayer == null)
         stubLayer = new Layer();
      stubLayer.activated = false;
      stubLayer.layeredSystem = this;
      if (stubLayer.layerPathName == null)
         stubLayer.layerPathName = layerPathName;
      //stubLayer.layerBaseName = ...
      stubLayer.layerDirName = layerDirName;
      if (stubLayer.layerUniqueName == null)
         stubLayer.layerUniqueName = expectedName;
      return stubLayer;
   }

   private Object loadLayerObject(SrcEntry defFile, Class expectedClass, String expectedName, String layerPrefix, String relDir, String relPath,
                                  boolean inLayerDir, boolean markDyn, String layerDirName, LayerParamInfo lpi) {
      // Layers are no longer in the typesByName list
      //if (typesByName.get(expectedName) != null)
      //   throw new IllegalArgumentException("Component: " + expectedName + " conflicts with definition of a layer object in: " + defFile);

      JavaModel model = null;
      // For inactive layers, we may have already loaded it.  To avoid reloading the layer model, just get it out of the index rather than reparsing it.
      if (lpi != null && !lpi.activate) {
         model = (JavaModel) inactiveModelIndex.get(defFile.absFileName);
         if (model != null)
            return model.layer;
         // If this layer has already been disabled return the disabled layer
         /* This causes us to get the wrong layered system for a styled file in the compile doc - and was being called even when we had created an android layered system */
         Layer disabledLayer = null; // lookupDisabledLayer(expectedName);
         if (disabledLayer != null)
            return disabledLayer;
      }
      model = parseLayerModel(defFile, expectedName, true, lpi.activate);

      if (model == null) {
         // There's a layer definition file that cannot be parsed - just create a stub layer that represents that file
         if (!lpi.activate && defFile.canRead()) {
            return getStubLayer(expectedName, defFile.absFileName, layerDirName);
         }
         return null;
      }
      TypeDeclaration modelType = model.getModelTypeDeclaration();
      if (modelType == null) {
         // Just fill in a stub if one can't be created
         modelType = new ModifyDeclaration();
         modelType.typeName = expectedName;
         modelType.isLayerType = true;
         model.addTypeDeclaration(modelType);
         if (model.isInitialized())
            ParseUtil.initComponent(modelType);
         String err = "No layer definition in file: " + defFile + " expected file to contain definition for type: " + expectedName + " {  }";
         if (lpi.activate)
            throw new IllegalArgumentException(err);
         modelType.displayError(err);
      }
      else
         modelType.isLayerType = true;

      String prefix = model.getPackagePrefix();

      // We can get the model's package prefix in a few ways.  It can be set explicitly or it can be derived from
      // the relative path of the file.  We need to strip out those components which are relative to the file
      // and only pre-pend any prefix explicitly defined in the package definition.
      boolean isRelDirPrefix = false;
      if (layerPrefix == null) {
         layerPrefix = model.getRelDirPath();
         isRelDirPrefix = true;
         if (relPath == null)
            relPath = layerPrefix;
      }

      if (prefix != null && layerPrefix != null) {
         if (!prefix.startsWith(layerPrefix) && !layerPrefix.startsWith(prefix)) {
            if (!isRelDirPrefix)
               System.err.println("*** Mismatching layer package tag and directory layout for definition: " + expectedName + " " + prefix + " != " + layerPrefix);
         }
         else {
            if (layerPrefix.length() > prefix.length())
               prefix = null;
            else {
               // TODO: this corrupts the error message in some cases at least
               // It is needed for the address example which uses the full package name of the layer's extends
               // to find the layer, we need to pull off the front part of the package since its not stored under
               // that name.
               prefix = prefix.substring(0,prefix.length()-layerPrefix.length());
            }
         }
      }

      if (relDir == null && inLayerDir) {
         // Don't include the package prefix here - it comes in below
         String modelTypeName = model.getModelTypeDeclaration().typeName;
         relDir = "../";
         int ix = 0;
         while ((ix = modelTypeName.indexOf(".", ix+1)) != -1)
            relDir = FileUtil.concat("..", relDir);
         if (newLayerDir == null || newLayerDir.equals(mapLayerDirName("."))) {
            // Need to make this absolute before we start running the app - which involves switching the current directory sometimes.
            newLayerDir = mapLayerDirName(relDir);
            if (strataCodeMainDir == null)
               strataCodeMainDir = newLayerDir;
         }
         //globalObjects.remove(defFile.layer.layerUniqueName);
         defFile.layer.layerUniqueName = modelTypeName;
         //globalObjects.put(defFile.layer.layerUniqueName, defFile.layer);
      }

      // At the top-level, we might need to pull the prefix out of the model's type, like when you
      // from inside of the top-level layer directory.
      if (relPath == null && layerPrefix == null) {
         String modelTypeName = modelType.typeName;
         if (modelTypeName.indexOf('.') != -1) {
            relPath = CTypeUtil.getPackageName(modelTypeName);
            // Don't include the package prefix here - it comes in below
            //expectedName = CTypeUtil.prefixPath(relPath, expectedName);
         }
      }

      if (modelType != null && !modelType.typeName.equals(expectedName)) {
         //if (layerPrefix != null)
         //   return null;
         String errMessage = "Layer definition file: " + model.getSrcFile().absFileName + " expected to modify type name: " + expectedName + " but found: " + modelType.typeName + " instead";
         if (lpi.activate) {
            // We stripped off some part of the name to find this file - maybe we found the wrong layer even though it's proper in the path name - e.g. if we include
            // test.editor2.editor.swing.main - we find "editor.swing.main" with a layerPrefix = test.editor2.  We want to report back null here so that the error is
            // that we did not find test.editor2.editor.swing.main.
            throw new IllegalArgumentException(errMessage);
         }
         else {
            model.displayError(errMessage);
         }
      }

      expectedName = CTypeUtil.prefixPath(prefix, expectedName);

      /*
      TypeDeclarationCacheEntry decls = typesByName.get(expectedName);
      if (decls == null || decls.size() == 0) {
         throw new IllegalArgumentException("Definition file: " + defFile + " expected to contain a definition for: " + expectedName + " instead found: " + model.getModelTypeDeclaration().typeName);
      }

      TypeDeclaration decl = decls.get(0);
      */

      TypeDeclaration decl = modelType;

      List<String> baseLayerNames = null;
      List<JavaType> extendLayerTypes = null;

      if (decl instanceof ModifyDeclaration) {
         ModifyDeclaration layerModel = (ModifyDeclaration) decl;
         extendLayerTypes = layerModel.extendsTypes;
         if (extendLayerTypes != null) {
            baseLayerNames = layerModel.getExtendsTypeNames();
         }
      }
      else {
         if (lpi.activate)
            throw new IllegalArgumentException("Layer definition file: " + defFile + " should not have an operator like 'class' or 'object'.  Just a name (i.e. a modify definition)");
         return getStubLayer(expectedName, defFile.absFileName, layerDirName);
      }

      Class runtimeClass = decl.getCompiledClass();
      if (runtimeClass != null && !expectedClass.isAssignableFrom(runtimeClass)) {
         if (lpi.activate)
            throw new IllegalArgumentException("Layer component file should modify the layer class with: '" + expectedName + " {'.  Found class: " + runtimeClass + " after parsing: " + defFile);
         return getStubLayer(expectedName, defFile.absFileName, layerDirName);
      }

      List<Layer> baseLayers = null;
      boolean initFailed = false;
      List<Layer> allBaseLayers;
      // This comes from the extends operation
      if (baseLayerNames != null) {
         // This is the "stub" layer used to load the real layer. Need some place to hold these so we can display
         // them in the cycle error
         defFile.layer.baseLayerNames = baseLayerNames;

         lpi.markExtendsDynamic = (lpi.recursiveDynLayers != null && lpi.recursiveDynLayers.contains(layerDirName)) || lpi.markExtendsDynamic;

         if (!lpi.explicitLayers) {
            defFile.layer.baseLayers = baseLayers = initLayers(baseLayerNames, relDir, relPath, lpi.markExtendsDynamic, lpi, false);
         }
         else {
            // When we are processing only the explicit layers do not recursively init the layers.  Just init the layers specified.
            baseLayers = mapLayerNamesToLayers(relPath, baseLayerNames, lpi.activate, false);
         }

         // If some of the base layers were excluded, we'll compute a separate base layers list to compute the package prefix which includes all of them.
         // Otherwise, the package prefix will not match in the two systems which is just unhelpful.
         if (baseLayers != null && baseLayerNames.size() > baseLayers.size() && peerMode) {
            allBaseLayers = mapLayerNamesToAnySystemLayers(relPath, baseLayerNames);
         }
         else
            allBaseLayers = baseLayers;
         /*
         if (baseLayers != null) {
            int li = 0;
            for (Layer bl:baseLayers) {
               if (bl == null) {
                  if (extendLayerTypes != null && extendLayerTypes.size() > li) {
                     extendLayerTypes.get(li).error("No layer named: ", baseLayerNames.get(li), " in layer path: ", layerPath);
                  }
               }
               li++;
            }
         }
         */

         initFailed = cleanupLayers(baseLayers) || initFailed;
      }
      else
         allBaseLayers = null;

      /* Now that we have defined the base layers, we'll inherit the package prefix and dynamic state */
      boolean baseIsDynamic = Layer.getBaseIsDynamic(baseLayers);
      boolean inheritedPrefix = Layer.getInheritedPrefix(allBaseLayers, prefix, model);

      prefix = model.getPackagePrefix();
      //if (!lpi.activate)
      //  model.setDisableTypeErrors(true);

      // Now that we've started the base layers and updated the prefix, it is safe to start the main layer
      boolean clearInitLayers = !initializingLayers;
      try {
         initializingLayers = true;
         Layer layer = ((ModifyDeclaration)decl).createLayerInstance(prefix, inheritedPrefix);

         // Need to set the excluded flag here for buildSeparate layers
         if (!lpi.activate && layer.buildSeparate) {
            layer.excluded = !layer.includeForProcess(processDefinition);
         }

         layer.initFailed = initFailed;
         layer.baseLayerNames = baseLayerNames;
         layer.baseLayers = baseLayers;
         layer.initLayerModel(model, lpi, layerDirName, inLayerDir, baseIsDynamic, markDyn);

         return layer;
      }
      catch (RuntimeException exc) {
         if (externalModelIndex != null && externalModelIndex.isCancelledException(exc)) {
            System.err.println("*** Rethrowing ProcessCanceledException - caught during loadLayerObject");
            throw exc;
         }
         if (!lpi.activate) {
            System.err.println("*** Failed to initialize inactive layer due to error initializing the layer: " + exc);
            if (options.verbose) exc.printStackTrace();
            return getStubLayer(expectedName, Layer.INVALID_LAYER_PATH, layerDirName);
         }
         System.err.println("*** Failed to initialize layer: " + expectedName + " due to runtime error: " + exc);
         if (options.verbose)
            exc.printStackTrace();
      }
      finally {
         if (clearInitLayers)
            initializingLayers = false;
         //if (!lpi.activate)
         //   model.setDisableTypeErrors(false);
      }
      return null;
   }

   List<Layer> mapLayerNamesToLayers(String relPath, List<String> baseLayerNames, boolean activated, boolean openLayers) {
      List<Layer> baseLayers = new ArrayList<Layer>();
      if (baseLayerNames == null)
         return null;
      for (int li = 0; li < baseLayerNames.size(); li++) {
         String baseLayerName = baseLayerNames.get(li);
         Layer bl = activated ? findLayerByName(relPath, baseLayerName) : findInactiveLayerByName(relPath, baseLayerName.replace('/', '.'), openLayers);
         if (bl != null) {
            baseLayers.add(bl);
         }
         else {
            if (options.sysDetails)
               System.out.println("No base layer: " + baseLayerName + " for: " + relPath + " in: " + getProcessIdent());
            // For this case we may be in the JS runtime and this layer is server specific...
            //baseLayers.add(null);
         }
      }
      return baseLayers;
   }

   List<Layer> mapLayerNamesToAnySystemLayers(String relPath, List<String> baseLayerNames) {
      List<Layer> baseLayers = new ArrayList<Layer>();
      if (baseLayerNames == null)
         return null;
      for (int li = 0; li < baseLayerNames.size(); li++) {
         String baseLayerName = baseLayerNames.get(li);
         Layer bl = findLayerByName(relPath, baseLayerName);
         if (bl == null) {
            // Go to the main system where we will have this excluded layer
            bl = mainSystem.findLayerByName(relPath, baseLayerName);
         }
         if (bl != null) {
            baseLayers.add(bl);
         }
      }
      return baseLayers;
   }

   boolean cleanupLayers(List<Layer> baseLayers) {
      // One of our base layers failed so we fail too
      boolean initFailed = baseLayers == null || baseLayers.contains(null);

      if (baseLayers != null) {
         while (baseLayers.contains(null))
            baseLayers.remove(null);
      }

      return initFailed;
   }

   private void addClassURLs(List<URL> urls, Layer layer, boolean checkBaseLayers) {
      List<String> classDirs = layer.classDirs;
      if (classDirs != null && layer.includeForProcess(processDefinition)) {
         for (int j = 0; j < classDirs.size(); j++) {
            urls.add(FileUtil.newFileURL(LayerUtil.appendSlashIfNecessary(classDirs.get(j))));
         }
      }
      if (checkBaseLayers) {
         List<Layer> baseLayers = layer.baseLayers;
         if (baseLayers != null) {
            for (Layer baseLayer:baseLayers) {
               addClassURLs(urls, baseLayer, true);
            }
         }
      }
   }

   URL[] getLayerClassURLs(Layer startLayer, int endPos, ClassLoaderMode mode) {
      ArrayList<URL> urls = new ArrayList<URL>();
      if (!startLayer.activated) {
         if (mode.doBuild())
            return null;

         int startPos = startLayer.layerPosition;
         if (startPos >= inactiveLayers.size()) {
            warning("*** Invalid layer position: " + startPos + " when starting layer: " + startLayer);
            return null;
         }
         for (int i = startPos; i > endPos; i--) {
            Layer layer = inactiveLayers.get(i);
            // Check if there's an active layer with this name... if so we'll use the active layer's classpath.
            if (getLayerByDirName(layer.getLayerName()) == null)
               addClassURLs(urls, layer, false);
         }
      }
      else {
         if (startLayer == coreBuildLayer) {
            if (mode.doLibs())
               addClassURLs(urls, startLayer, false);
            if (mode.doBuild())
               startLayer.appendBuildURLs(urls);
         }
         else {
            int startPos = startLayer.layerPosition;
            for (int i = startPos; i > endPos; i--) {
               Layer layer = layers.get(i);
               if (mode.doBuild()) {
                  // Only include the buildDir for build layers that have been compiled.  If the startLayer
                  // uses "buildSeparate", we only include this build layer if it is directly extended by
                  // the buildSeparate layer.
                  if (layer.buildDir != null && layer.isBuildLayer() && layer.compiled &&
                      (!startLayer.buildSeparate || layer == startLayer || startLayer.extendsLayer(layer))) {

                     if (layer == buildLayer || !options.buildAllLayers) {
                        // Don't include the buildDir for the IDE and other environments when we are compiling only and not running unless it's a final layer like js.prebuild where
                        // we want the compilation process to load the compiled classes for speed.
                        if (options.includeBuildDirInClassPath || layer.finalLayer)
                           layer.appendBuildURLs(urls);
                     }
                  }
               }
               if (mode.doLibs()) {
                  addClassURLs(urls, layer, false);
               }
            }
         }
      }
      return urls.toArray(new URL[urls.size()]);
   }

   public volatile static int readLocked, writeLocked;
   public volatile static String debugLockStack;
   public volatile static String currentLockThreadName;

   public void acquireDynLock(boolean readOnly) {
      long startTime = 0;
      if (options.verbose || options.verboseLocks)
         startTime = System.currentTimeMillis();

      Lock lock = (!readOnly ? globalDynLock.writeLock() : globalDynLock.readLock());
      if (options.superVerboseLocks) {
         System.out.println("Acquiring system dyn lock" + (readOnly ? " (readOnly)" : "") + " thread: " + Thread.currentThread().getName());
      }

      lock.lock();

      if (options.superVerboseLocks) {
         System.out.println("Acquired system dyn lock" + (readOnly ? " (readOnly)" : "") + " thread: " + Thread.currentThread().getName());
      }

      if (!readOnly)
         writeLocked++;
      else
         readLocked++;

      if (options.verboseLocks && writeLocked == 1)
         debugLockStack = PTypeUtil.getStackTrace(new Throwable());

      currentLockThreadName = Thread.currentThread().getName();

      if (options.verboseLocks || options.verbose) {
         long duration = System.currentTimeMillis() - startTime;
         if (duration > 1000) {
            System.err.println("Warning: waited: " + duration + " millis for lock on thread: " + DynUtil.getCurrentThreadString());
            if (debugLockStack != null)
               System.err.println(" Stack trace of thread that acquired lock: " + debugLockStack);
            if (duration > 2000 && Thread.currentThread().getName().contains("AWT-Event")) {
               System.err.println("*** Stack of waiting thread: ");
               new Throwable().printStackTrace(System.err);
            }
         }
         else if (duration > 100 && options.verboseLocks)
            System.out.println("Acquired system dyn lock " + (readOnly ? "(readOnly)" : "") + " after waiting: " + duration + " millis" + " thread: " + DynUtil.getCurrentThreadString());
      }
   }

   public void releaseDynLock(boolean readOnly) {
      Lock lock = (!readOnly ? globalDynLock.writeLock() : globalDynLock.readLock());

      if (options.superVerboseLocks) {
         System.out.println("Releasing system dyn lock" + (readOnly ? "(readOnly)" : "") + " thread: " + Thread.currentThread());
      }
      if (!readOnly)
         writeLocked--;
      else
         readLocked--;

      if (writeLocked == 0)
         currentLockThreadName = null;

      lock.unlock();
   }

   public void ensureLocked() {
      if (writeLocked == 0) {
         System.err.println("*** Supposed to be locked at this code point");
         new Throwable().printStackTrace();
      }
   }

   public Lock getDynReadLock() {
      return globalDynLock.readLock();
   }

   public Lock getDynWriteLock() {
      return globalDynLock.writeLock();
   }

   public void addDynInnerObject(String typeName, Object inst, Object outer) {
      if (getLiveDynamicTypes(typeName)) {
         // For inner objects, we just store the name of the object in its parent
         objectNameIndex.put(inst, CTypeUtil.getClassName(typeName));
         addDynInnerInstance(typeName, inst, outer);
      }
   }

   public void addDynInnerInstance(String typeName, Object inst, Object outer) {
      if (inst == outer) {
         System.out.println("*** addDynInnerInstance called with outer = inner!");
         return;
      }
      if (getLiveDynamicTypes(typeName)) {
         addDynInstance(typeName, inst);
         innerToOuterIndex.put(inst, outer);
      }
   }

   public Object[] resolveTypeGroupMembers(String typeGroupName) {
      List<TypeGroupMember> tgms = buildInfo.getTypeGroupMembers(typeGroupName);
      if (tgms == null)
         return null;
      Object[] res = new Object[tgms.size()];
      for (int i = 0; i < res.length; i++) {
         res[i] = resolveName(tgms.get(i).typeName, true, true);
      }
      return res;
   }

   public String getTypeName(Object type, boolean includeDims) {
      return ModelUtil.getTypeName(type, includeDims);
   }

   public Class getCompiledClass(Object type) {
      return ModelUtil.getCompiledClass(type);
   }

   public Object getOuterInstance(Object inst) {
      return innerToOuterIndex.get(inst);
   }

   // This method is used to add an inner object which for this runtime is just done by adding an instance.  In Javascript, we need to mark the instance in this method as an object and store it's object name.
   public void addDynObject(String typeName, Object inst) {
      if (getLiveDynamicTypes(typeName)) {
         objectNameIndex.put(inst, typeName);
         addDynInstance(typeName, inst);
      }
   }

   public void addDynInstance(String typeName, Object inst) {
      if (getLiveDynamicTypes(typeName)) {
         addDynInstanceInternal(typeName, inst, lastLayer);
      }
   }

   public void addDynInstanceInternal(String typeName, Object inst, Layer refLayer) {
      WeakIdentityHashMap<Object,Boolean> instMap = instancesByType.get(typeName);
      if (instMap == null) {
         instMap = new WeakIdentityHashMap<Object, Boolean>(2);
         instancesByType.put(typeName, instMap);

         if (refLayer != null) {
            // Make sure we've cached and started the dynamic type corresponding to these instances.  If this file later changes
            // we can do an incremental update, or at least detect when the model is stale.  Also need to make sure the
            // sub-types get registered for someone to query for instances of this type from a base type.
            TypeDeclaration type = (TypeDeclaration) getSrcTypeDeclaration(typeName, null, true, true, true, refLayer, false);
            if (type != null && !type.isLayerType && !type.isStarted()) {
               JavaModel typeModel = type.getJavaModel();
               // Need to start here so the sub-types get registered
               ParseUtil.initAndStartComponent(typeModel);
               //notifyInstancedAdded(type, inst);
            }
         }
      }
      instMap.put(inst, Boolean.TRUE);

      if (dynListeners != null) {
         for (IDynListener listener:dynListeners)
            listener.instanceAdded(inst);
      }
   }

   public boolean removeDynInstance(String typeName, Object inst) {
      if (getLiveDynamicTypes(typeName)) {
         WeakIdentityHashMap<Object,Boolean> instMap = instancesByType.get(typeName);
         if (instMap != null) {
            Object res = instMap.remove(inst);
            if (res == null)
               warning("*** Can't find dyn instance to remove for type: " + typeName);
            else {
               if (instMap.size() == 0)
                  instancesByType.remove(typeName);

               if (dynListeners != null) {
                  for (IDynListener listener:dynListeners)
                     listener.instanceRemoved(inst);
               }
            }
            return res != null;
         }
         // else - we do not add the dynInstance for regular classes that are not components so this happens a lot
      }
      return false;
   }

   /**
    * Called to remove the object from the dynamic type system.  Though we use weak storage for these instances,
    * it's faster to get rid of them when you are done.  Use DynUtil.dispose to also remove the bindings.
    */
   public void dispose(Object obj) {
      if (options.liveDynamicTypes) {
         if (ModelUtil.getLiveDynamicTypes(this)) {
            Object type = DynUtil.getType(obj);
            if (type != null) {
               String typeName = ModelUtil.getTypeName(type);
               removeDynInstance(typeName, obj);
            }
         }
      }
      innerToOuterIndex.remove(obj);
      objectNameIndex.remove(obj);
      if (obj instanceof IDynObject) {
         IDynObject dynObj = (IDynObject) obj;
         Object type = dynObj.getDynType();
         if (type instanceof BodyTypeDeclaration) {
            ((BodyTypeDeclaration) type).stopDynComponent(dynObj);
         }
      }
      else {
         if (obj instanceof IComponent)
            ((IComponent) obj).stop();
         if (obj instanceof IAltComponent)
            ((IAltComponent) obj)._stop();
      }

   }

   public String getInnerTypeName(Object type) {
      return ModelUtil.getInnerTypeName(type);
   }

   /** Walks up the object hierarchy until we hit a class or go off the top. */
   public int getNumInnerTypeLevels(Object type) {
      int ct = 0;
      do {
         Object enclType = ModelUtil.getEnclosingInstType(type);
         if (enclType == null)
            break;
         ct++;
         type = enclType;
      } while (true);
      return ct;
   }

   public Object getOuterObject(Object obj) {
      Object outer = innerToOuterIndex.get(obj);
      if (outer != null)
         return outer;
      return DynObject.getParentInstance(obj);
   }

   public String getPackageName(Object type) {
      return ModelUtil.getPackageName(type);
   }

   /** Returns true if there are any instances of this type or instances of sub-types */
   public boolean hasInstancesOfType(String typeName) {
      Iterator insts = getInstancesOfType(typeName);
      if (insts.hasNext())
         return true;

      TypeDeclaration type = getSrcTypeDeclaration(typeName, null, true, true);
      if (type != null) {
         do {
            Iterator<String> subTypes = getSubTypeNamesOfType(type);
            while (subTypes.hasNext()) {
               String subTypeName = subTypes.next();
               // Only check new types, not modified versions of the same type to avoid a recursive loop
               if (!subTypeName.equals(typeName) && hasInstancesOfType(subTypeName))
                  return true;
            }
            // Go down and grab the leaf type.  We check the sub-types so will pick up sub-types because we register
            // modified types in the sub-types table.
            if (type instanceof ModifyDeclaration)
               type = (TypeDeclaration) type.getPureModifiedType();
            else
               type = null;
         } while (type != null);
      }
      return false;
   }

   public Iterator<Object> getInstancesOfType(String typeName) {
      WeakIdentityHashMap<Object,Boolean> instMap = instancesByType.get(typeName);
      if (instMap == null)
         return EmptyIterator.EMPTY_ITERATOR;
      return instMap.keySet().iterator();
   }

   public Iterator<Object> getInstancesOfTypeAndSubTypes(String typeName) {
      Set<Object> res = null;
      WeakIdentityHashMap<Object,Boolean> insts = instancesByType.get(typeName);
      TypeDeclaration td = getSrcTypeDeclaration(typeName, null, true, true);
      if (td == null) {
         return insts == null ? EmptyIterator.EMPTY_ITERATOR : insts.keySet().iterator();
      }
      res = addSubTypes(td, insts, res);
      if (res != null)
         return res.iterator();
      else if (insts == null)
         return EmptyIterator.EMPTY_ITERATOR;
      else
         return insts.keySet().iterator();
   }

   private Set<Object> addSubTypes(BodyTypeDeclaration td, WeakIdentityHashMap<Object,Boolean> insts, Set<Object> res) {
      Iterator<BodyTypeDeclaration> subTypes = getSubTypesOfType(td);
      if (subTypes != null) {
         while (subTypes.hasNext()) {
            BodyTypeDeclaration subType = subTypes.next();

            WeakIdentityHashMap<Object,Boolean> subInsts = instancesByType.get(subType.getFullTypeName());
            if (subInsts != null) {
               if (insts == null)
                  insts = subInsts;
               else {
                  if (res == null) {
                     res = new LinkedHashSet<Object>();
                     res.addAll(insts.keySet());
                  }
                  else if (!(res instanceof HashSet)) {
                     LinkedHashSet<Object> newRes = new LinkedHashSet<Object>();
                     newRes.addAll(res);
                     newRes.addAll(insts.keySet());
                     res = newRes;
                  }
                  res.addAll(subInsts.keySet());
               }
            }

            if (subType == td)
               error("*** Recursive type!");
            else {
               // Now recursively add the sub-types of this type
               res = addSubTypes(subType, subInsts, res);
            }
         }
      }
      return res == null ? insts == null ? null : insts.keySet() : res;
   }

   private static final EmptyIterator<BodyTypeDeclaration> NO_TYPES = new EmptyIterator<BodyTypeDeclaration>();
   private static final EmptyIterator<String> NO_TYPE_NAMES = new EmptyIterator<String>();

   public void addSubType(TypeDeclaration superType, TypeDeclaration subType) {
      if (options.liveDynamicTypes) {
         String superTypeName = superType.getFullTypeName();
         HashMap<String,Boolean> subTypeMap = subTypesByType.get(superTypeName);
         if (subTypeMap == null) {
            subTypeMap = new HashMap<String, Boolean>();
            subTypesByType.put(superTypeName, subTypeMap);
         }
         subTypeMap.put(subType.getFullTypeName(), Boolean.TRUE);
      }
   }

   public boolean removeSubType(TypeDeclaration superType, TypeDeclaration subType) {
      if (options.liveDynamicTypes) {
         String superTypeName = superType.getFullTypeName();
         HashMap<String,Boolean> subTypeMap = subTypesByType.get(superTypeName);
         if (subTypeMap != null) {
            Boolean res = subTypeMap.remove(subType.getFullTypeName());
            if (res == null)
               return false;
            return res;
         }
      }
      return false;
   }

   public Iterator<String> getSubTypeNamesOfType(TypeDeclaration type) {
      HashMap<String,Boolean> subTypesMap = subTypesByType.get(type.getFullTypeName());
      if (subTypesMap == null)
         return NO_TYPE_NAMES;
      return subTypesMap.keySet().iterator();
   }

   public Iterator<BodyTypeDeclaration> getSubTypesOfType(BodyTypeDeclaration type) {
      return getSubTypesOfType(type, type.getLayer(), true, false, false, false);
   }

   /**
    * Returns the sub-types of the specified type.  If activeOnly is true, only those types active in this system are checked.  If activeOnly is false
    * and checkPeers is true, the type name is used to find sub-types in the peer systems as well.
    */
   public Iterator<BodyTypeDeclaration> getSubTypesOfType(BodyTypeDeclaration type, Layer refLayer, boolean openLayers, boolean checkPeers, boolean includeModifiedTypes, boolean cachedOnly) {
      if (!type.isRealType())
         return NO_TYPES;
      String typeName = type.getFullTypeName();

      Layer typeLayer = type.getLayer();

      ArrayList<BodyTypeDeclaration> result = new ArrayList<BodyTypeDeclaration>();
      boolean checkThisSystem = type.isLayerType || typeLayer == null || typeLayer.layeredSystem == this || getSameLayerFromRemote(typeLayer) != null ||
              typeIndex.getLayerTypeIndex(typeLayer.getLayerName(), typeLayer.activated) != null;
      // Check sub-types in this layered system only if this type is from this system or the layer is also in that layer
      if (checkThisSystem && (typeLayer == null || typeLayer.activated)) {
            HashMap<String,Boolean> subTypesMap = subTypesByType.get(typeName);
            if ((refLayer == null || refLayer.activated) && subTypesMap == null)
               return NO_TYPES;

         if (subTypesMap != null) {
            for (String subTypeName:subTypesMap.keySet()) {
               if (type.isLayerType()) {
                  Layer layer = getActiveOrInactiveLayerByPath(subTypeName.replace('.', '/'), null, openLayers, true, true);
                  if (layer != null && layer.model != null) {
                     TypeDeclaration layerType = layer.model.getModelTypeDeclaration();
                     if (layerType != null)
                        result.add(layerType);
                  }
               }
               else {
                  if (refLayer == null)
                     System.err.println("*** Error type without a ref layer");
                  if (refLayer != null && refLayer.layeredSystem != this)
                     refLayer = getPeerLayerFromRemote(refLayer);
                  // For the cachedOnly case, we do not want to load a type which is not yet loaded - i.e. we are invalidating caches for that type
                  TypeDeclaration res = cachedOnly ?
                                       getCachedTypeDeclaration(subTypeName, null, null, false, false, refLayer, true) :
                                       (TypeDeclaration) getSrcTypeDeclaration(subTypeName, null, true, false, true, refLayer, type.isLayerType);
                  // Check the resulting class name.  getSrcTypeDeclaration may find the base-type of an inner type as it looks for inherited inner types under the parent type's name
                  if (res != null && res.getFullTypeName().equals(subTypeName))
                     result.add(res);
               }
            }
         }
      }

      if (refLayer != null && !refLayer.activated) {
         if (!cachedOnly)
            typeIndex.refreshReverseTypeIndex(this);
         if (checkPeers && !peerMode && peerSystems != null) {
            for (int i = 0; i < peerSystems.size(); i++) {
               LayeredSystem peerSys = peerSystems.get(i);
               Iterator<BodyTypeDeclaration> peerRes = peerSys.getSubTypesOfType(type, refLayer, openLayers, false, includeModifiedTypes, cachedOnly);
               if (peerRes != null) {
                  while (peerRes.hasNext())
                     result.add(peerRes.next());
               }
            }
         }
         if (checkThisSystem) {
            LinkedHashMap<String,TypeIndexEntry> subTypes = typeIndex.inactiveTypeIndex.subTypeIndex.get(typeName);
            if (subTypes != null) {
               for (Map.Entry<String,TypeIndexEntry> subTypeEnt:subTypes.entrySet()) {
                  String subTypeName = subTypeEnt.getKey();
                  TypeIndexEntry subTypeIndexEntry = subTypeEnt.getValue();

                  if (type.isLayerType) {
                     Layer subLayer = getActiveOrInactiveLayerByPath(subTypeName.replace('.', '/'), null, openLayers, true, true);
                     if (subLayer != null && subLayer.model != null)
                        result.add(subLayer.model.getModelTypeDeclaration());
                  }
                  else {
                     if (refLayer != null && refLayer.layeredSystem != this)
                        refLayer = getPeerLayerFromRemote(refLayer);
                     TypeDeclaration res = cachedOnly ?
                               getCachedTypeDeclaration(subTypeName, null, null, false, false, refLayer, true) :
                               (TypeDeclaration) getSrcTypeDeclaration(subTypeName, null, true, false, true, refLayer, type.isLayerType);
                     // Check the resulting class name.  getSrcTypeDeclaration may find the base-type of an inner type as it looks for inherited inner types under the parent type's name
                     if (res != null) {
                        // Make sure we don't add any sub-types which are from the same layer as this one.
                        if (res == type || ModelUtil.sameTypesAndLayers(this, type, res))
                           continue;
                        if (res.getFullTypeName().equals(subTypeName)) {
                           ModelUtil.addUniqueLayerType(this, result, res);

                           // When we have A extends B - should we return all of the layered types that make up B or just the most specific
                           // But skip the modify inherited case, where we have an inner type which extends an inner type of the base class... it's a different class and
                           // if we include them it causes us to include the original type
                           if (includeModifiedTypes && res.getModifiedExtendsTypeDeclaration() == null) {
                              BodyTypeDeclaration modType = res;
                              while ((modType = modType.getModifiedType()) != null && modType instanceof TypeDeclaration) {
                                 // Stop once we run into this type - so we don't return a recursive result and don't keep going returning base-types of the current type
                                 if (modType == type || ModelUtil.sameTypesAndLayers(this, type, modType))
                                    break;
                                 ModelUtil.addUniqueLayerType(this, result, modType);
                              }
                           }
                        }
                     } else {
                        if (subTypeIndexEntry != null) {
                           Layer subTypeLayer = getInactiveLayer(subTypeIndexEntry.layerName, openLayers, true, true, true);

                           if (subTypeLayer != null) {
                              if (!subTypeLayer.closed) {
                                 // We may not find this sub-type in this system because we share the same type index across all processes in the runtime.  It might be
                                 // a different runtime.
                                 res = cachedOnly ?
                                         getCachedTypeDeclaration(subTypeName, null, null, false, false, refLayer, true) :
                                         (TypeDeclaration) getSrcTypeDeclaration(subTypeName, null, true, false, true, refLayer, type.isLayerType);
                                 if (res != null && res.getFullTypeName().equals(subTypeName) && !ModelUtil.sameTypesAndLayers(this, type, res))
                                    result.add(0, res);
                              }
                           } else {
                              // TODO: else cull this from the index?
                              System.err.println("*** Warning: Layer removed: " + subTypeIndexEntry.layerName);
                           }
                        }
                     }
                  }
               }
            }
         }
      }
      return result.iterator();
   }

   public ArrayList<BodyTypeDeclaration> getModifiedTypesOfType(BodyTypeDeclaration type, boolean before, boolean checkPeers) {
      TreeSet<String> checkedTypes = new TreeSet<String>();
      ArrayList<BodyTypeDeclaration> res = new ArrayList<BodyTypeDeclaration>();

      typeIndex.addModifiedTypesOfType(getProcessIdent(), this, type, before, checkedTypes, res);

      if (checkPeers && !peerMode && peerSystems != null) {
         ArrayList<LayeredSystem> peerSystemsCopy = (ArrayList<LayeredSystem>) peerSystems.clone();
         for (LayeredSystem peerSys:peerSystemsCopy) {
            checkedTypes.add(peerSys.getProcessIdent());
            ArrayList<BodyTypeDeclaration> peerTypes = peerSys.getModifiedTypesOfType(type, before, false);
            if (peerTypes != null)
               res.addAll(peerTypes);
         }
      }

      // Now check the TypeIndexEntry entries for any processes for which we have not yet created layeredSystems.  Create them if
      // lazily if there is a match to load the types.
      if (typeIndexProcessMap != null) {
         for (Map.Entry<String,SysTypeIndex> indexEnt:typeIndexProcessMap.entrySet()) {
            String processIdent = indexEnt.getKey();
            if (!checkedTypes.contains(processIdent)) {
               SysTypeIndex sysTypeIndex = indexEnt.getValue();
               sysTypeIndex.addModifiedTypesOfType(processIdent, this, type, before, checkedTypes, res);
            }
         }
      }
      return res;
   }

   private static class ModelToPostBuild {
      IFileProcessorResult model;
      Layer genLayer;
   }

   private class RunMainLayersThread extends Thread {
      List<Layer> theLayers;
      private RunMainLayersThread(List<Layer> lyrs) {
         super();
         this.theLayers = lyrs;
      }
      public void run() {
         runMainMethods(theLayers);
      }
   }

   private class RunMainThread extends Thread {
      int startLayerPos;
      private RunMainThread(int layerPos) {
         super();
         startLayerPos = layerPos;
      }
      public void run() {
         runMainMethods(startLayerPos);
      }
   }

   /**
    * Some files depend on the contents of a type group.
    * The layer which defines this file right now must manually add
    * the file to this list during layer initialization.  Unless we are building all files, we'll check
    * each entry in this list to see if the type group contents have changed since we last built the file.
    * If so, we add the file to the list of files that need to be processed.
    */
   public void addTypeGroupDependency(Layer forLayer, String relFileName, String typeName, String typeGroupName) {
      typeGroupDeps.add(new TypeGroupDep(forLayer, relFileName, typeName, typeGroupName));
   }

   public boolean isDynamicRuntime() {
      // TODO: generalize when we do another dynamic runtime
      return runtimeProcessor instanceof JSRuntimeProcessor;
   }

   public void setStaleCompiledModel(boolean val, String... reason) {
      // When we are building the system, we rebuild the models as they are changed on disk, then compare the generated source file.
      // If it's different and we've loaded that type, it's a stale compiled model.
      if (buildingSystem)
         return;

      internalSetStaleCompiledModel(val, reason);
   }

   private void internalSetStaleCompiledModel(boolean val, String... reason) {
      // No need for this for the JS runtime since we can just reload the classes
      if (isDynamicRuntime())
         return;

      // Only print the first for info, print all for debug
      boolean printInfo = (!staleCompiledModel && options.info);
      if (printInfo || options.verbose) {
         StringBuilder msg = new StringBuilder();
         if (printInfo)
           msg.append("* Warning: ");
         for (String r:reason)
            msg.append(r);
         msg.append(FileUtil.LINE_SEPARATOR);
         if (printInfo)
            info(msg);
         else
            verbose(msg);
      }

      if (staleCompiledInfo == null)
         staleCompiledInfo = new ArrayList<String[]>();
      if (staleCompiledInfo.size() < 16 && !infoContains(reason))
         staleCompiledInfo.add(reason);

      staleCompiledModel = val;

      Bind.sendDynamicEvent(IListener.VALUE_CHANGED, this, "staleCompiledModel");
      Bind.sendDynamicEvent(IListener.VALUE_CHANGED, this, "staleInfo");
      Bind.sendDynamicEvent(IListener.VALUE_CHANGED, this, "shortStaleInfo");
   }

   private boolean infoContains(String[] reason) {
      if (staleCompiledInfo == null)
         return false;
      for (String[] res:staleCompiledInfo) {
         if (res.length == reason.length && DynUtil.equalArrays(res, reason))
            return true;
      }
      return false;
   }

   @Bindable(manual=true)
   public boolean getStaleCompiledModel() {
      return staleCompiledModel;
   }

   @Bindable(manual=true)
   public String getStaleInfo() {
      if (staleCompiledInfo == null)
         return null;
      StringBuffer sb = new StringBuffer();
      for (String[] msgs:staleCompiledInfo) {
         for (String msg:msgs)
            sb.append(msg);
         sb.append("\n");
      }
      return sb.toString();
   }

   @Bindable(manual=true)
   public String getShortStaleInfo() {
      if (staleCompiledInfo == null)
         return null;
      StringBuffer sb = new StringBuffer();
      for (String[] msgs:staleCompiledInfo) {
         for (String msg:msgs)
            sb.append(msg);
         sb.append("\n");
      }
      if (sb.length() < 50)
         return sb.toString();
      else
         return sb.toString().substring(0, 50) + "...";
   }

   public String[] getDynSrcDirs() {
      ArrayList<String> res = new ArrayList<String>();
      for (int i = layers.size()-1; i != 0; i--) {
         Layer layer = layers.get(i);
         if (!layer.dynamic)
            break;
         res.add(layer.layerPathName);
      }
      return res.toArray(new String[res.size()]);
   }

   public String[] getDynSrcPrefixes() {
      ArrayList<String> res = new ArrayList<String>();
      for (int i = layers.size()-1; i != 0; i--) {
         Layer layer = layers.get(i);
         if (!layer.dynamic)
            break;
         res.add(layer.packagePrefix);
      }
      return res.toArray(new String[res.size()]);
   }

   public boolean hasDynamicLayers() {
      for (int i = layers.size()-1; i != 0; i--) {
         Layer layer = layers.get(i);
         if (layer.dynamic)
            return true;
      }
      return false;
   }

   public void refreshType(String typeName) {
      TypeDeclaration toRefresh = getSrcTypeDeclaration(typeName, null, true);
      if (toRefresh == null) {
         if (options.verbose)
              verbose("No type: " + typeName + " to refresh");
         return;
      }

      // As long as there is a dynamic type which we're loading, to be sure we really need to refresh
      // everything.  Eventually a file system watcher will make this more incremental.
      refreshRuntimes(true);

      //JavaModel model = toRefresh.getJavaModel();
      //ExecutionContext ctx = new ExecutionContext();

      // First refresh the most specific model
      //refresh(model.getSrcFile(), ctx);
      // No refresh any possible base layers
      //model.refreshBaseLayers(ctx);
   }

   public Object[] getObjChildren(Object inst, String scopeName, boolean create) {
      Object type = DynUtil.getType(inst);
      if (type instanceof TypeDeclaration) // TODO: the create parameter
         return ((TypeDeclaration) type).getObjChildren(inst, scopeName, false, false, true);
      // This does not feel entirely right... for scope children we add this interface in the compiled version
      // and use it to retrieve the set of children from the parent component (i.e. ListView).  Either we should
      // always add this interface or maybe we need a differnet method.  This method seems specific to dynamic children
      // as currently defined.
      else if (inst instanceof IObjChildren)
         return ((IObjChildren) inst).getObjChildren(create);
      return null;
   }

   public String[] getObjChildrenNames(Object typeObj, String scopeName) {
      if (typeObj instanceof TypeDeclaration)
         return ((TypeDeclaration) typeObj).getObjChildrenNames(scopeName, false, false, true);
      return null;
   }

   public Object[] getObjChildrenTypes(Object typeObj, String scopeName) {
      if (typeObj instanceof TypeDeclaration)
         return ((TypeDeclaration) typeObj).getObjChildrenTypes(scopeName, false, false, true);
      return null;
   }

   public boolean hasModifier(Object def, String modifier) {
      return ModelUtil.hasModifier(def, modifier);
   }

   public Object getReverseBindingMethod(Object method) {
      return ModelUtil.getReverseBindingMethod(method);
   }

   public boolean needsExtDynType(Object typeObj) {
      return buildInfo.getExternalDynType(typeObj, false) != null;
   }

   final static int RESTART_CODE = 33;

   /** Use this to an 'scc' started process to restart - exits with an exit code that signals the script to re-run the command using the arguments passed to this 'scc' process uing the restart file command-line arg passed in by the scc script */
   public void restart() {
      if (options.restartArgsFile == null)
         throw new IllegalArgumentException("System was not started with the shell script to restart not available");
      FileUtil.saveStringAsFile(options.restartArgsFile, getRestartArgs(), true);
      System.exit(RESTART_CODE);
   }

   public String getRestartArgs() {
      if (restartArgs == null)
         return null;

      StringBuilder args = new StringBuilder();

      for (int i = 0; i < restartArgs.size(); i++) {
         if (i != 0)
            args.append(" ");
         args.append(escapeCommandLineArgument(restartArgs.get(i)));
      }
      return args.toString();
   }

   @Constant
   public boolean getCanRestart() {
      return options.restartArgsFile != null;
   }

   public Layer getInactiveLayerSync(String layerPath, boolean openLayer, boolean checkPeers, boolean enabled, boolean skipExcluded) {
      try {
         acquireDynLock(false);

         return getInactiveLayer(layerPath, openLayer, checkPeers, enabled, skipExcluded);
      }
      finally {
         releaseDynLock(false);
      }
   }

   private Layer lookupDisabledLayer(String layerName) {
      if (!peerMode) {
         if (disabledLayersIndex != null)
            return disabledLayersIndex.get(layerName);
         return null;
      }
      else {
         LayeredSystem mainSys = getMainLayeredSystem();
         return mainSys.lookupDisabledLayer(layerName);
      }
   }

   /**
    * Retrieves a Layer instance given the layer name - i.e. group.name (it also accepts the path group/name).
    * If checkPeers = true, you may receive a layer in a runtime (that is lazily created the first time it's needed).
    * For enabled: Pass in true unless you explicitly do not want to enable the layer.  Enabling the layer adds it to to the classpath so types in the layer can be starte so types in the layer can be started
    * For skipExcluded: pass in true unless you want to get the excluded layer from this runtime which
    * we create temporarily to bootstrap layers in other runtimes.   We first create the Layer in the original runtime to see if it's excluded or not.  If so, we remove from the list.
    */
   public Layer getInactiveLayer(String layerPath, boolean openLayer, boolean checkPeers, boolean enabled, boolean skipExcluded) {
      String layerName = layerPath.replace('/', '.');
      Layer inactiveLayer = lookupInactiveLayer(layerPath, checkPeers, skipExcluded);
      if (inactiveLayer != null) {
         if (inactiveLayer.layeredSystem == this || (inactiveLayer.excludeForProcess(processDefinition) || !inactiveLayer.includeForProcess(processDefinition))) {
            if (openLayer && inactiveLayer.closed)
               inactiveLayer.markClosed(false, false);
            return inactiveLayer;
         }
      }

      if (disabledLayersIndex != null) {
         inactiveLayer = disabledLayersIndex.get(layerName);
         // Be careful not to return a disabled layer in another system since it may be disabled in android but enabled here
         if (inactiveLayer != null && inactiveLayer.layeredSystem == this)
            return inactiveLayer;
      }

      LayerParamInfo lpi = new LayerParamInfo();
      lpi.activate = false;
      lpi.enabled = enabled;

      boolean clearInitLayers = !initializingLayers;
      try {
         initializingLayers = true;

         Layer layer = initLayer(layerPath, null, null, false, lpi);
         if (layer != null) {
            return completeNewInactiveLayer(layer, openLayer);
         }
         else if (getNewLayerDir() != null) {
            Layer res = initLayer(layerPath, getNewLayerDir(), null, false, lpi);
            if (res != null) {
               return completeNewInactiveLayer(res, openLayer);
            }
         }
      }
      finally {
         if (clearInitLayers)
            initializingLayers = false;
      }
      return null;
   }

   private Layer completeNewInactiveLayer(Layer layer, boolean openLayer) {
      layer.ensureInitialized(true);

      // First mark any excluded layers with the excluded flag so we know they do not belong in this runtime.
      // We leave the excluded layers in place through initRuntimes though so we can use these excluded layers
      // to find the runtimes in which they belong.
      removeExcludedLayers(true);

      // We just created a new layer so now go and re-init the runtimes in case it is the first layer in
      // a new runtime or this layer needs to move to the runtime before it's started.
      if (!peerMode) {
         initRuntimes(null, false, openLayer, true);
      }

      removeExcludedLayers(false);

      checkLayerPosition();

      boolean foundInPeer = false;

      if (!peerMode && peerSystems != null) {
         for (int i = 0; i < peerSystems.size(); i++) {
            LayeredSystem peerSys = peerSystems.get(i);

           // Need to also load this layer and any dependent layers as an inactive layers into the other runtimes if they are needed there.
            if (layer.includeForProcess(peerSys.processDefinition)) {
               // Note: used to pass layer.disabled here for 'enabled' but that could lead to dependent layers of the disabled layer being
               // also disabled.
               Layer peerRes = peerSys.getInactiveLayer(layer.getLayerName(), openLayer, false, true, false);
               if (peerRes != null)
                  foundInPeer = true;
            }
         }
      }

      for (Layer activeLayer:layers) {
         if (activeLayer.buildSeparate && !activeLayer.compiled && !activeLayer.disabled) {
            // We need to reset the buildDir's in case we added new separate layers for all peers.  This should not init the build system from scratch in case there's already build state like jsFiles
            initBuildSystem(true, false);
            break;
         }
      }

      for (Layer activeLayer:layers) {
         if (activeLayer.buildSeparate && !activeLayer.compiled && !activeLayer.disabled) {
            // then build the separate layers so the compiled results are available for parsing the types - again for all peers
            buildSystem(null, true, true);

            setCurrent(this);
            break;
         }
      }

      // This could be a compiled separate layer that we just ran across.  We just activate them and throw them into layers whenever they are first accessed by the IDE since we need to build them in order to start other layers.
      if (layer.activated)
         return layer;

      // If this layer moved, now find it in the proper runtime
      Layer newRes = lookupInactiveLayer(layer.getLayerName(), true, true);

      // Make sure all of our baseLayers are open if we are also open
      if (newRes != null) {
         if (openLayer)
            newRes.markClosed(false, false);
         else
            newRes.closed = true;
      }

      if (newRes != layer) {
         if (newRes != null)
            return newRes;
         else {
            /*
            LayeredSystem mainSys = getMainLayeredSystem();
             * We used to do this but the disabledLayersIndex is on the main runtime - shared.  here the layer may only be excluded in this runtime.
            mainSys.disabledLayersIndex.put(layer.getLayerName(), layer);
            layer.layerPosition = mainSys.disabledLayers.size();
            mainSys.disabledLayers.add(layer);
            */
         }
            /*
         else {
            newRes = lookupActiveLayer(layer.getLayerName(), true, true);
            if (newRes == null) {
               if (!layer.disabled)
                  System.err.println("*** Can't find newly created layers: " + layer.getLayerName());
            }
            else
               return newRes;
         }
               */
         // else - error -
      }
      return layer;
   }

   public Layer getActiveOrInactiveLayerByPathSync(String layerPath, String prefix, boolean openLayer, boolean checkPeers, boolean enabled) {
      try {
         acquireDynLock(false);
         return getActiveOrInactiveLayerByPath(layerPath, prefix, openLayer, checkPeers, enabled);
      }
      finally {
         releaseDynLock(false);
      }
   }

   public Layer getActiveOrInactiveLayerByPath(String layerPath, String prefix, boolean openLayer, boolean checkPeers, boolean enabled) {
      Layer layer;
      String origPrefix = prefix;
      String usePath = layerPath;
      do {
         layer = getLayerByPath(usePath, true);
         if (layer == null) {
            // Layer does not have to be active here - this lets us parse the code in the layer but not really start, transform or run the modules because the layer itself is not started
            layer = getInactiveLayer(usePath, true, checkPeers, enabled, false);
         }

         if (prefix != null) {
            usePath = CTypeUtil.prefixPath(prefix, layerPath);
            prefix = CTypeUtil.getPackageName(prefix);
         }
         else
            break;
      } while (layer == null);

      return layer;
   }

   public Layer getInactiveLayerByPath(String layerPath, String prefix, boolean openLayer, boolean enabled) {
      Layer layer;
      String origPrefix = prefix;
      String usePath = layerPath;
      do {
         // Layer does not have to be active here - this lets us parse the code in the layer but not really start, transform or run the modules because the layer itself is not started
         layer = getInactiveLayer(usePath, openLayer, true, enabled, false);

         if (prefix != null) {
            usePath = CTypeUtil.prefixPath(prefix, layerPath);
            prefix = CTypeUtil.getPackageName(prefix);
         }
         else
            break;
      } while (layer == null);

      return layer;
   }

   public JavaModel getAnnotatedLayerModel(String layerPath, String prefix) {
      if (externalModelIndex != null) {
         try {
            acquireDynLock(false);
            Layer layer = getInactiveLayerByPath(layerPath, prefix, true, true);
            if (layer != null && layer.model != null) {  // NOTE: this layer may not be from this layered system
               JavaModel annotModel;
               if ((annotModel = layer.layeredSystem.parseInactiveModel(layer.model.getSrcFile())) == null)
                  System.out.println("*** Error null annotated model!");
               return annotModel;
            }
         }
         finally {
            releaseDynLock(false);
         }
      }
      return null;
   }

   public Map<String,LayerIndexInfo> getAllLayerIndex() {
      if (allLayerIndex == null)
         initAllLayerIndex();
      return allLayerIndex;
   }

   void initAllLayerIndex() {
      allLayerIndex = new LinkedHashMap<String, LayerIndexInfo>();
      // defaults to using paths relative to the current directory
      if (layerPathDirs == null) {
         addLayerPathToAllIndex(System.getProperty("user.dir"));
         if (inactiveLayers.size() > 0) {
            for (Layer inactiveLayer:inactiveLayers) {
               LayerIndexInfo lii = new LayerIndexInfo();
               lii.layerPathRoot = inactiveLayer.layerPathName;
               lii.layerDirName = inactiveLayer.layerDirName;
               lii.packageName = inactiveLayer.packagePrefix;
               lii.system = this;
               lii.layer = inactiveLayer;
               allLayerIndex.put(inactiveLayer.layerDirName, lii);
            }
         }
      }
      else {
         for (File pathDir:layerPathDirs) {
            addLayerPathToAllIndex(pathDir.getPath());
         }
      }
      if (syncInited)
         initLayerIndexSync();
   }

   void addLayerPathToAllIndex(String pathName) {
      // First populate all of the LayerIndexInfo's by walking the directory tree
      addLayerPathToAllIndex(pathName, null, new LayerUtil.DirectoryFilter());

      // Now lookup the package names for any which inherit them from the extends type
      for (LayerIndexInfo lii:allLayerIndex.values()) {
         if (StringUtil.isEmpty(lii.packageName) && lii.extendsLayers != null) {
            lii.packageName = lii.getExtendsPackageName();
         }
      }
   }

   private void addLayerPathToAllIndex(String pathName, String prefix, FilenameFilter filter) {
      String fullPathName = prefix == null ? pathName : FileUtil.concat(pathName, prefix);
      File pathNameFile = new File(fullPathName);
      String[] files = pathNameFile.list(filter);
      List<String> filesList = new ArrayList<String>(Arrays.asList(files));
      Collections.sort(filesList);
      if (files != null) {
         for (int i = 0; i < filesList.size(); i++) {
            String fileName = filesList.get(i);
            if (excludedFile(fileName, prefix))
               continue;
            String filePath = FileUtil.concat(fullPathName, fileName);
            if (externalModelIndex != null && externalModelIndex.isExcludedFile(filePath))
               continue;
            if (LayerUtil.isLayerDir(filePath)) {
               String layerName = FileUtil.concat(prefix, fileName);
               String expectedName = layerName.replace(FileUtil.FILE_SEPARATOR_CHAR, '.');
               try {
                  String relFile = fileName + "." + SCLanguage.STRATACODE_SUFFIX;
                  String absFile = FileUtil.concat(filePath, relFile);
                  Layer layer = new Layer();
                  layer.layeredSystem = this;
                  layer.layerPathName = filePath;
                  layer.layerBaseName = relFile;
                  layer.layerDirName = expectedName;
                  SrcEntry srcEnt = new SrcEntry(layer, absFile, relFile);

                  JavaModel layerModel = parseLayerModel(srcEnt, expectedName, false, false);
                  if (layerModel != null) {
                     LayerIndexInfo lii = new LayerIndexInfo();
                     lii.layerPathRoot = pathName;
                     lii.layerDirName = layer.layerDirName;
                     lii.packageName = layerModel.getPackagePrefix();
                     lii.system = this;
                     lii.layer = layer;

                     TypeDeclaration decl = layerModel.getModelTypeDeclaration();

                     if (decl instanceof ModifyDeclaration) {
                        List<JavaType> extendLayerTypes = ((ModifyDeclaration) decl).extendsTypes;
                        if (extendLayerTypes != null) {
                           lii.extendsLayers = new String[extendLayerTypes.size()];
                           int extIx = 0;
                           for (JavaType extType:extendLayerTypes)
                              lii.extendsLayers[extIx++] = extType.getFullTypeName();
                        }
                        // This forces the layer's extends types to be resolved... not sure we want to do that much initialization to just grab the layer index?
                        //decl.initDynamicProperty(layer, "disabled");
                     }
                     else {
                        throw new IllegalArgumentException("Layer definition file: " + absFile + " should contain a modify definition e.g.: " + expectedName + " {}");
                     }
                     allLayerIndex.put(layer.layerDirName, lii);
                  }
               }
               catch (IllegalArgumentException exc) {
                  System.err.println("*** Warning: invalid layer definition file: " + filePath + " should contain definition for type: " + expectedName);
               }
            }
            else // possibly a layer group so recursively add layers from here
               addLayerPathToAllIndex(pathName, FileUtil.concat(prefix, fileName), filter);
         }
      }
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
            if (excludedPatterns.get(i).matcher(fileName).matches())
               return true;
      }
      return false;
   }

   public Object removeGlobalObject(String typeName) {
      return globalObjects.remove(typeName);
   }

   public boolean getCompiledOnly() {
      return runtimeProcessor != null && runtimeProcessor.getCompiledOnly();
   }

   public String toString() {
      return "LayeredSystem: " + getProcessIdent() + (!peerMode ? " (main)" : "") + (anyErrors ? " (errors)" : "");
   }


   public boolean isGlobalScope(String scopeName) {
      // TODO: generalize this as an attribute of the scope
      if (runtimeProcessor instanceof JSRuntimeProcessor && (scopeName.equals("session") || scopeName.equals("window") || scopeName.equals("appSession")))
         return true;
      return false;
   }

   private SrcEntry getSrcEntryForPathDir(String layerPath, String pathName, boolean activeLayers, boolean openLayer) {
      String layerAndFileName = pathName.substring(layerPath.length());
      while (layerAndFileName.startsWith(FileUtil.FILE_SEPARATOR))
         layerAndFileName = layerAndFileName.substring(FileUtil.FILE_SEPARATOR.length());

      boolean isDir = FileUtil.getExtension(pathName) == null && new File(pathName).isDirectory();

      if (layerAndFileName.length() > 0) {
         String pathFileName = FileUtil.getFileName(layerAndFileName);

         String parentDirPath = isDir ? layerAndFileName : FileUtil.getParentPath(layerAndFileName);
         String dirName = parentDirPath == null ? null : FileUtil.getFileName(parentDirPath);
         Layer layer;
         if (dirName == null) {
            System.err.println("*** Invalid path: " + pathName);
            return new SrcEntry(null, pathName, pathFileName);
         }
         if (isDir || (pathFileName.endsWith(SCLanguage.DEFAULT_EXTENSION) && FileUtil.removeExtension(pathFileName).equals(dirName))) {
            layer = activeLayers ? getLayerByPath(parentDirPath, true) : getInactiveLayerByPath(parentDirPath, null, openLayer, true);
            // This path is for the layer itself
            if (layer != null) {
               if (!activeLayers)
                  layer.markClosed(false, false);
               return getSrcEntryForLayerPaths(layer, pathName, pathFileName, isDir);
            }
         }

         String layerName = null;
         String fileName;
         do {
            int slashIx = layerAndFileName.indexOf(FileUtil.FILE_SEPARATOR);
            if (slashIx != -1) {
               String nextPart = layerAndFileName.substring(0, slashIx);
               fileName = layerAndFileName.substring(slashIx+1);
               layerName = FileUtil.concat(layerName, nextPart);
               layer = activeLayers ? getLayerByPath(layerName, true) : getInactiveLayerByPath(layerName, null, openLayer, true);
               if (layer != null) {
                  if (!activeLayers)
                     layer.markClosed(false, false);
                  // TODO: validate that we found this layer under the right root?
                  return getSrcEntryForLayerPaths(layer, pathName, fileName, isDir);
               }
               layerAndFileName = fileName;
            }
            else
               break;
         } while(true);
      }
      return null;
   }

   private SrcEntry getSrcEntryForLayerPaths(Layer layer, String relPath, String fileName, boolean isDir) {
      if (isDir) {
         String parentPath = FileUtil.getParentPath(fileName);
         if (parentPath == null)
            parentPath = "";
         return new SrcEntry(layer, FileUtil.getParentPath(relPath), parentPath);
      }
      else
         return new SrcEntry(layer, relPath, fileName);
   }

   public SrcEntry getSrcEntryForPath(String pathName, boolean activeLayers, boolean openLayer) {
      pathName = FileUtil.makeAbsolute(pathName);

      acquireDynLock(false);

      try {
         List<Layer> layersList = activeLayers ? layers : inactiveLayers;
         for (Layer layer:layersList) {
            SrcEntry layerEnt = layer.getSrcEntry(pathName);
            if (layerEnt != null) {
               if (!activeLayers && openLayer)
                  layer.markClosed(false, false);
               return layerEnt;
            }
         }
         if (layerPathDirs != null) {
            for (File layerPathDir:layerPathDirs) {
               String layerPath = layerPathDir.getPath();
               if (pathName.startsWith(layerPath)) {
                  SrcEntry ent = getSrcEntryForPathDir(layerPath, pathName, activeLayers, openLayer);
                  if (ent != null)
                     return ent;
               }

               // In case we crossed over a sym-link, try it as absolute since we often canonicalize the layerPathDirs
               String origPathName = pathName;
               try {
                  pathName = FileUtil.makeCanonical(pathName);
                  if (pathName.startsWith(layerPath)) {
                     SrcEntry ent = getSrcEntryForPathDir(layerPath, pathName, activeLayers, openLayer);
                     if (ent != null)
                        return ent;
                  }
               }
               catch (IOException exc) {
                  System.err.println("*** Warning unable to map layer path name: " + origPathName + " error: " + exc);
               }
               // Do not catch the ProcessCanceledException from IntelliJ which bubbles up through IExternalModelIndex call
               //catch (RuntimeException exc) {
               //   System.err.println("*** LayeredSystem.getSrcEntryForPath failed with: " + exc);
               //   exc.printStackTrace();
               //}
            }
         }
      }
      finally {
         releaseDynLock(false);
      }
      // The BuildInfo.sc file is the only SC file that should be in the buildSrc directory - not in a layer.
      //if (!(pathName.endsWith("BuildInfo.sc")) && !activeOnly)
      //   System.err.println("*** Warning - unable to find layer for path name: " + pathName);
      return new SrcEntry(null, pathName, FileUtil.getFileName(pathName));
   }

   /** Avoid loading classes or initing dynamic types for the JS runtime because it's not really active */
   public boolean getLoadClassesInRuntime() {
      return runtimeProcessor == null || runtimeProcessor.getLoadClassesInRuntime();
   }

   public boolean getUseContextClassLoader() {
      if (options.disableContextClassLoader)
         return false;
      if (processDefinition != null && !processDefinition.getUseContextClassLoader())
         return false;
      if (layers.size() == 0) {
         if (contextLoaderSystem == null) {
            contextLoaderSystem = this;
            return true;
         }
         return contextLoaderSystem == this; // This is an inactive layered system - no need to set the context class loader unless we're the default one.  This avoids setting this to java_Desktop for the documentation where we want it always to be java_Server
      }
      boolean res = runtimeProcessor == null || runtimeProcessor.getUseContextClassLoader();
      if (res) {
         if (contextLoaderSystem != null) {
            if (contextLoaderSystem.layers.size() == 0)
               contextLoaderSystem = this;
            else if (contextLoaderSystem != this)
               System.err.println("*** Warning: multiple runtimes trying to set context classLoader");
         }
         else
            contextLoaderSystem = this;
      }
      return res;
   }

   public void registerDefaultAnnotationProcessor(String annotationTypeName, IAnnotationProcessor processor) {
      IAnnotationProcessor old = defaultAnnotationProcessors.put(annotationTypeName, processor);
      if (old != null && options.verbose) {
         verbose("Default annotation processor for: " + annotationTypeName + " replaced: " + old + " with: " + processor);
      }
   }

   public IAnnotationProcessor getAnnotationProcessor(Layer refLayer, String annotName) {
      int startIx;
      if (searchActiveTypesForLayer(refLayer)) {
         // In general we search from most recent to original
         startIx = refLayer == null ? layers.size() - 1 : refLayer.getLayerPosition();

         if (refLayer != null && refLayer.removed) {
            warning("*** removed layer in getAnnotationProcessor!");
            return null;
         }

         if (startIx >= layers.size()) {
            warning("*** Out of range layer index in getAnnotationProcessor");
            return null;
         }

         // Only look at layers which precede the supplied layer
         for (int i = startIx; i >= 0; i--) {
            Layer layer = layers.get(i);
            if (layer.annotationProcessors != null) {
               IAnnotationProcessor res = layer.annotationProcessors.get(annotName);
               if (res != null)
                  return res;
            }
         }
      }
      if (searchInactiveTypesForLayer(refLayer)) {
         return refLayer.getAnnotationProcessor(annotName, true);
      }
      return null;
   }

   public IScopeProcessor getScopeProcessor(Layer refLayer, String scopeName) {
      int startIx;

      // We can substitute a different scope on a runtime-by-runtime basis - e.g. for the client, often many scopes collapse into one (e.g. window, session => global)
      if (scopeName != null) {
         String scopeAlias = getScopeAlias(refLayer, scopeName);
         if (scopeAlias != null)
            scopeName = scopeAlias;
      }

      if (searchActiveTypesForLayer(refLayer)) {
         // In general we search from most recent to original
         startIx = refLayer == null ? layers.size() - 1 : refLayer.getLayerPosition();

         // During layer init we may not have been added yet
         if (startIx >= layers.size())
            return null;

         // Only look at layers which precede the supplied layer
         for (int i = startIx; i >= 0; i--) {
            Layer layer = layers.get(i);

            if (layer.scopeProcessors != null) {
               IScopeProcessor res = layer.scopeProcessors.get(scopeName);
               if (res != null)
                  return res;
            }
         }
      }
      if (searchInactiveTypesForLayer(refLayer)) {
         return refLayer.getScopeProcessor(scopeName, true);
      }
      return null;
   }

   public ScopeDefinition getScopeByName(String scopeName) {
      if (buildLayer != null) {
         String alias = getScopeAlias(buildLayer, scopeName);
         if (alias != null && !alias.equals(scopeName))
            return ScopeDefinition.getScopeByName(alias);
      }
      return ScopeDefinition.getScopeByName(scopeName);
   }

   public boolean needsSync(Object type) {
      if (type instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) type).needsSync();
      else  // TODO: this needs to look at sub-objects and types
         return SyncManager.getSyncProperties(type, null) != null;
   }

   public String getScopeAlias(Layer refLayer, String scopeName) {
      int startIx;
      if (searchActiveTypesForLayer(refLayer)) {
         // In general we search from most recent to original
         startIx = refLayer == null ? layers.size() - 1 : refLayer.getLayerPosition();

         // During layer init we may not have been added yet
         if (startIx >= layers.size())
            return null;

         // Only look at layers which precede the supplied layer
         for (int i = startIx; i >= 0; i--) {
            Layer layer = layers.get(i);

            String aliasName = layer.getScopeAlias(scopeName, false);
            if (aliasName != null)
               return aliasName;
         }
      }
      if (searchInactiveTypesForLayer(refLayer)) {
         return refLayer.getScopeAlias(scopeName, true);
      }
      return null;
   }

   public boolean getHasSystemErrors() {
      if (anyErrors)
         return true;
      if (peerSystems != null) {
         for (LayeredSystem peerSys:peerSystems)
            if (peerSys.anyErrors)
               return true;
      }
      return false;
   }

   /**
    * Add parameters to the virtual machine to run this program.  Each parameter is given a name so that it can
    * be overridden.  For options like -mx the name should be the option itself as I'm sure Java doesn't want more than one
    * of those.
    */
   public void addVMParameter(String paramName, String value) {
      if (vmParameters == null)
         vmParameters = new ArrayList<VMParameter>(1);
      else {
         for (int i = 0; i < vmParameters.size(); i++) {
            VMParameter p = vmParameters.get(i);
            if (p.parameterName.equals(paramName)) {
               p.parameterValue = value;
               return;
            }
         }
      }
      vmParameters.add(new VMParameter(paramName, value));
   }

   public String getVMParameters() {
      if (vmParameters == null)
         return "";
      else {
         StringBuilder sb = new StringBuilder();
         sb.append(" ");
         for (VMParameter vmp:vmParameters) {
            sb.append(vmp.parameterValue);
            sb.append(" ");
         }
         return sb.toString();
      }
   }

   public boolean isRuntimeDisabled(String runtimeName) {
      return disabledRuntimes.contains(runtimeName);
   }

   public boolean isRuntimeActivated(String runtimeName) {
      // Default runtime does not have to be activated
      if (runtimeName == null)
         return true;
      // For these other runtimes, make sure the layer which defined the runtime is included.
      for (int i = 0; i < layers.size(); i++) {
         Layer layer = layers.get(i);
         if (layer.definedRuntime != null && StringUtil.equalStrings(layer.definedRuntime.getRuntimeName(), runtimeName))
            return true;
      }
      return false;
   }

   public boolean isProcessActivated(String processName) {
      // For these other runtimes, make sure the layer which defined the runtime is included.
      for (int i = 0; i < layers.size(); i++) {
         Layer layer = layers.get(i);
         if (layer.definedProcess != null && StringUtil.equalStrings(layer.definedProcess.getProcessName(), processName))
            return true;
      }
      return false;
   }

   public boolean isDisabled() {
      return isRuntimeDisabled(getRuntimeName());
   }

   public boolean isParseable(String fileName) {
      IFileProcessor proc = getFileProcessorForFileName(fileName, null, BuildPhase.Process);
      if (proc != null)
         return proc.isParsed();
      return Language.isParseable(fileName);
   }

   public boolean addGlobalImports(boolean isLayerModel, String prefixPackageName, String prefixBaseName, Set<String> candidates, int max) {
      Map<String,ImportDeclaration> importMap = isLayerModel ? globalLayerImports : globalImports;
      for (String impName:importMap.keySet()) {
         ImportDeclaration impDecl = importMap.get(impName);
         if ((prefixPackageName == null || impDecl.identifier.startsWith(prefixPackageName)) && impName.startsWith(prefixBaseName)) {
            String impPkg = CTypeUtil.getPackageName(impDecl.identifier);
            if (StringUtil.equalStrings(prefixPackageName, impPkg)) {
               candidates.add(impName);
               if (candidates.size() >= max)
                  return false;
            }
         }
      }
      return true;
   }

   public Layer getSameLayerFromRemote(Layer layer) {
      if (layer.layeredSystem == this)
         System.err.println("*** Not from a remote system");
      return layer.activated ? getLayerByName(layer.getLayerUniqueName()) : lookupInactiveLayer(layer.getLayerName(), false, true);
   }

   public Layer getPeerLayerFromRemote(Layer layer) {
      Layer res = getSameLayerFromRemote(layer);
      if (res != null)
         return res;
      for (Layer nextLayer = layer.getNextLayer(); nextLayer != null; nextLayer = nextLayer.getNextLayer()) {
         res = getLayerByName(nextLayer.getLayerUniqueName());
         if (res != null)
            return res;
      }
      return Layer.ANY_INACTIVE_LAYER;
   }

   public Layer getCoreBuildLayer() {
      if (coreBuildLayer == null) {
         LayerUtil.initCoreBuildLayer(this);
         initSysClassLoader(coreBuildLayer, ClassLoaderMode.BUILD);
      }
      return coreBuildLayer;
   }

   public StringBuilder dumpCacheStats() {
      StringBuilder sb = new StringBuilder();
      dumpCacheSummary(sb, false);
      sb.append(" numActiveFiles: ");
      sb.append(processedFileIndex.size());
      sb.append("\n");
      if (typeIndex != null)
         sb.append(typeIndex.dumpCacheStats());
      int pkgIndexSize = packageIndex.size();
      for (HashMap<String,PackageEntry> pkgMap:packageIndex.values()) {
         pkgIndexSize += pkgMap.size();
      }
      sb.append("   Num entries in package index: " + pkgIndexSize + "\n\n");
      sb.append("   Active model index - total: " + LayerUtil.dumpModelIndexSummary(modelIndex));
      sb.append(LayerUtil.dumpModelIndexStats(modelIndex));

      sb.append("   Inactive model index - total: " + LayerUtil.dumpModelIndexSummary(inactiveModelIndex));
      sb.append(LayerUtil.dumpModelIndexStats(inactiveModelIndex));

      sb.append(LayerUtil.dumpLayerListStats("active", layers));
      sb.append(LayerUtil.dumpLayerListStats("inactive", inactiveLayers));

      sb.append("\n   viewedErrors: " + viewedErrors.size());
      sb.append(" globalObjects: " + globalObjects.size());
      sb.append(" pendingActiveLayers: " + pendingActiveLayers.size());
      sb.append(" pendingInActiveLayers: " + pendingInactiveLayers.size());
      sb.append(" inactiveLayerIndex: " + inactiveLayerIndex.size() + "\n");
      sb.append("   typesByName: " + typesByName.size());
      sb.append(" innerTypeCache: " + innerTypeCache.size());
      sb.append(" beingLoaded: " + beingLoaded.size());
      sb.append(" typesByRootName: " + typesByRootName.size());
      sb.append(" templateCache: " + templateCache.size());
      sb.append(" zipFileCache: " + zipFileCache.size() + "\n");

      sb.append("   instancesByTypes: " + instancesByType.size());
      sb.append(" innerToOuterIndex: " + innerToOuterIndex.size());
      sb.append(" objectNameIndex: " + objectNameIndex.size());
      sb.append(" subTypesByType: " + subTypesByType.size());


      if (!peerMode && typeIndexProcessMap != null) {
         sb.append("System type indexes:\n");
         for (Map.Entry<String,SysTypeIndex> ent:typeIndexProcessMap.entrySet()) {
            sb.append("  index for: " + ent.getKey());
            sb.append(": ");
            sb.append(ent.getValue().dumpCacheStats());
         }
      }

      if (!peerMode && peerSystems != null) {
         sb.append("Peer systems --------- \n");
         for (LayeredSystem peerSys:peerSystems)
            sb.append(peerSys.dumpCacheStats());
      }

      return sb;
   }

   public void dumpCacheSummary(StringBuilder sb, boolean includePeers) {
      sb.append("LayeredSystem: ");
      sb.append(getProcessIdent());
      if (!peerMode)
         sb.append(" (master)");
      sb.append("\n   active layers: " + layers.size() + " files: " + modelIndex.size());
      sb.append("\n   inactive layers: " + inactiveLayers.size() + " files: " + inactiveModelIndex.size() + "\n\n");
      if (includePeers && !peerMode && peerSystems != null) {
         for (LayeredSystem peerSys:peerSystems) {
            peerSys.dumpCacheSummary(sb, false);
         }
      }
   }

   /** Returns the total number of models currently in the cache - as an estimate to determine when to clean the cache */
   public int getNumModelsInCache() {
      int res = layers.size() + inactiveLayers.size() + inactiveModelIndex.size() + modelIndex.size();
      if (!peerMode && peerSystems != null) {
         for (LayeredSystem peerSys:peerSystems)
            res += peerSys.getNumModelsInCache();
      }
      return res;
   }

   /** Returns the prefix to prepend onto files generated for the given srcPathType (e.g. 'web', 'config', etc.) */
   public String getSrcPathBuildPrefix(String srcPathType) {
      for (int i = layers.size() - 1; i >= 0; i--) {
         Layer layer = layers.get(i);
         String res = layer.getSrcPathBuildPrefix(srcPathType);
         if (res != null)
            return res;
      }
      return null;
   }

   public void applyModelChange(ILanguageModel model, boolean changed) {
      if (externalModelIndex != null)
         externalModelIndex.modelChanged(model, changed, model.getLayer());

      peerModelsToUpdate.put(model.getSrcFile().absFileName, model);
   }

   public void updatePeerModels(boolean doPeers) {
      try {
         acquireDynLock(false);
         for (ILanguageModel peerModel : peerModelsToUpdate.values()) {
            updateModelInPeers(peerModel);
         }
         peerModelsToUpdate.clear();
      }
      finally {
         releaseDynLock(false);
      }

      if (doPeers && peerSystems != null) {
         for (int i = 0; i < peerSystems.size(); i++) {
            LayeredSystem peerSys = peerSystems.get(i);
            peerSys.updatePeerModels(false);
         }
      }
   }

   public void updateModelInPeers(ILanguageModel model) {
      if (peerSystems != null) {
         String absFileName = model.getSrcFile().absFileName;
         for (int i = 0; i < peerSystems.size(); i++) {
            LayeredSystem peerSys = peerSystems.get(i);
            ILanguageModel otherModel = peerSys.inactiveModelIndex.get(absFileName);
            if (otherModel instanceof JavaModel) {
               JavaModel otherJavaModel = (JavaModel) otherModel;
               if (otherJavaModel.layeredSystem != peerSys)
                  System.err.println("*** Mismatching system in index");

               Layer otherLayer = otherModel.getLayer();
               ILanguageModel clonedModel = peerSys.cloneModel(otherLayer, (JavaModel) model);
               peerSys.addNewModel(clonedModel, null, null, null, otherJavaModel.isLayerModel, false);
            }
         }
      }
   }

   public String getRunFromDir() {
      if (options.runFromBuildDir) {
         if (options.useCommonBuildDir)
            return commonBuildDir;
         return buildDir;
      }
      return null;
   }

   public void addDynListener(IDynListener listener) {
      if (dynListeners == null)
         dynListeners = new ArrayList<IDynListener>();
      dynListeners.add(listener);
   }

   public void removeDynListener(IDynListener listener) {
      if (dynListeners == null || !dynListeners.remove(listener))
         error("No dyn listener to remove");
   }

   public void fetchRemoteTypeDeclaration(String typeName, IResponseListener resp) {
      // Ignore this call on the server - we'll already have called resolveSrcTypeDeclaration which will synchronously fetch the type there
   }

   @sc.obj.Remote(remoteRuntimes="js")
   public BodyTypeDeclaration getSrcTypeDeclaration(String typeName) {
      return getSrcTypeDeclaration(typeName, null, true);
   }

   private Object runtimeInitLock = new Object();
   private boolean runtimeInited = false;

   // Once the SyncManager and destinations have been registered, we might need to init the sync types for the command interpreter
   public void runtimeInitialized() {
      if (cmd != null)
         cmd.runtimeInitialized();
      synchronized (runtimeInitLock) {
         runtimeInited = true;
         runtimeInitLock.notify();
      }
   }

   public boolean waitForRuntime(long timeout) {
      long startTime = System.currentTimeMillis();
      do {
         synchronized (runtimeInitLock) {
            long now = System.currentTimeMillis();
            if (runtimeInited)
               return true;
            if (now - startTime > timeout)
               return false;
            try {
               runtimeInitLock.wait(timeout - (now - startTime));
            }
            catch (InterruptedException exc) {}
         }
      } while (true);
   }

   public void addSystemExitListener(ISystemExitListener listener) {
      systemExitListeners.add(listener);
   }

   public boolean isDefaultSystem() {
      return defaultLayeredSystem == this;
   }


   /** Returns the complete set of inactive layers - returns the more complete inactiveLayer entry or the one from the type index if the inactive layer is not loaded yet */
   public List<Layer> getInactiveIndexLayers() {
      return typeIndex.inactiveTypeIndex.getIndexLayers(this);
   }

   public void clearCaches() {
      String baseIndexDir = getTypeIndexBaseDir();
      File f = new File(baseIndexDir);
      if (f.isDirectory()) {
         FileUtil.removeFileOrDirectory(f);
      }
   }

   /** Returns true for the IRuntimeProcessor which defines the process which should initialize the other processes. */
   public static boolean isInitRuntime(IRuntimeProcessor proc) {
      // TODO: might need to improve the logic here... this is called early on, before the peerSystems are defined so
      // need to use the runtimes and processes lists to determine which is the process should initialize the other one. It's used
      // for layers like the example.todo.data layer which should not run in both the client and the server, but should run in
      // client-only mode when there's only a client.
      return runtimes.indexOf(proc) == 0;
   }

}



