/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.classfile.CFClass;
import sc.js.URLPath;
import sc.lang.html.Element;
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
import sc.repos.RepositoryPackage;
import sc.repos.RepositoryStore;
import sc.repos.RepositorySystem;
import sc.sync.SyncManager;
import sc.sync.SyncOptions;
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
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

//import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The layered system manages the collection of layers which make up the system.  Each layer contains a slice
 * of application code - a single tree organized in a hierarchical name space of definitions.  Definitions
 * create classes, objects, or modify classes or objects.  These layers are merged together to create the
 * current application's program state.
 * <p/>
 * The program state can be processed to produce Java code, then compiled to produce .class files.
 * This class has a main method which implements command line functionality to process and compile Java files.
 * <p/>
 * The layered system is provided with a primary layer which is used as the most specific layer.  You can also
 * specify additional layers to include in the application ahead of the primary layer.  All of the layers
 * extended by these layers are expanded, duplicates are removed and the list is sorted into dependency order
 * producing the list of layers that define the application's initial state.
 * <p/>
 * The layered system is used at build time to
 * generate the class path for the compiler, find source files to be compiled, and manage the compilation
 * process.  Tools also use the layered system to read and manage program elements, and (eventually) interpret layers.
 * Generated applications typically do not depend on the layered system unless they need to interpret code,
 * use the command line interpreter, or ohter tools to edit the program.
 * <p/>
 * The layered system has a main for processing and compiling applications.  The processing phase generates Java
 * code as needed for any language extensions encountered and places these files into the build directory.  At
 * any given time, the layered system is modifying files in a single build directory.  By default, this is the
 * directory named build in the top-level layer's directory.  This goes counter to standard Java convention which
 * is to have src and build directories at the same level.  In this approach, a layer is completely self-contained
 * in a single directory reducing the need for Jar files in making it easy to compartmentalize components.  Because
 * these packages are exposed to higher level programmers, the Java programmers make a concession in cleanliness for
 * the ease of use of all.
 * <p/>
 * If a Java file does not use any language extensions
 * it can optionally be copied to the build directory along with the generated code so that you have one complete
 * source directory with all source, or it can be compiled directly from where it lives in the source tree.
 * After generating any Java files, any modified files are then compiled using a standard java compiler.
 * <p/>
 * java v.layer.LayeredSystem [-a -dyn -i -nc -bo -cp <classpath> -lp <layerpath> ] [<includeLayerDir1> ... <includeLayerDirN-1>] <includeLayerN> [ -f <file-list> ]");
 * You must provide at least one layer name or directory.
 * Options:
 * <br>
 * -cp: System class path.  By default, the layered system uses the current classpath to search for regular Java
 * classes that can be used by the code in the specified layers.  You can override this with the -cp option
 * or by constructing your own LayeredSystem.
 * <p/>
 * -lp: Layer path.  Specifies a list of directories to search in order for layers.  Each directory you specify
 * should contain one or more layer directories.  Keep in mind that layer names are themselves hierarchical - the layer
 * path should contain the root of the layer tree.  So if your layer name is "foo.bar" the directory tree would look like:
 * layerDir/foo/bar where layerDir is in the layerPath.  If this option is not set, the value of the system property
 * sc.layer.path is used and if that is not set, the current directory is the only directory consulted for layers.
 * <p/>
 * -dyn: treat layers following this option and those they extend as dynamic - i.e. any layers dragged in by this dependency that does not have compiledOnly=true are made dynamic.
 * <p/>
 * -dynall: treat all layers named on the command line and all layers they include as dynamic even if they are not marked with the dynamic keyword.  Layers with compiledOnly=true are always compiled.
 * <p/>
 * -dynone: treat layers named on the command line as dynamic even if they are not marked with the dynamic keyword.
 * <p/>
 * -nc: Skip the compilation.  Useful when you are compiling the Java files with an IDE
 * <p/>
 * -bo: Generate code for the build-layer only.  There are two ways you can manage the compilation.  By default,
 * all layers are merged and compiled into the buildDir of the last layer which is not interpreted.  With this
 * mode, only the last layer in the list is compiled - it assumes all of the extended layers have already been
 * compiled and so only generates and compiles any files added or modified in this layer.  This could greatly
 * speed compilation time in some cases.  Question: do we need a more flexible way to specify which layers
 * are compiled individually and merged via classpath versus recursively included and put into one directory?
 * <p/>
 * -f <file1> <file2>.. files names following this argument are interpreted as names of files to be
 * compiled rather than layer names to be included.
 * <p/>
 * -a: Process all files, update all dependency info.
 * The layered system generated dependency information which it uses to optimize future compiles unless
 * you specify the -a option during the compile.  The dependencies take into account all normal Java dependencies
 * which means that if you change a base class, the subclass will be recompiled to ensure the contracts are maintained.
 * Similarly if you modify an interface any classes implementing that interface are recompiled.  This is good news
 * as it eliminates runtime errors caused by stale code and catches more errors at compile time.  These dependency
 * files are stored in each src directory and are named "process.dep".  You can read these files yourself to understand
 * what files each file in that directory depends upon for debugging problems.
 * <p/>
 * -i: Start the command line interpreter editing a temporary layer.  Without -i, the command line interpreter where it edits the last layer.
 * <p/>
 * -ni: Disable the command interpreter
 * <p/>
 * -t <test-class-patterh>:  Run test classes matching this pattern.
 * <p/>
 * -ta: Run all tests
 * <p/>
 * -r <main-class-pattern>:  Run main classses matching this pattern.  All -r options pass remaining args to the main program.
 * <p/>
 * -rs <main-class-pattern>:  Run main classses matching this pattern by executing the script in a new process
 * <p/>
 * -ra: Run all main classes.
 * <p/>
 * -d, -db, -ds:  Override the buildDir and buildSrcDir values.
 * <p/>
 * -v, -vb, -vs, -vsa, -vba:  Turn on verbose info globally, for sync, for data binding, and more verbose versions of sync and data binding
 * <p/>
 * -vh, -vha - verbose HTML and very verbose HTML
 * <p/>
 * -vl - show the initial layers as in verbose
 */
public class LayeredSystem implements LayerConstants, INameContext, IRDynamicSystem {
   {
      setCurrent(this);
   }

   @Constant
   public List<Layer> layers = new ArrayList<Layer>(16);

   // Index for layers which are not part of the actively executing layers list
   public ArrayList<Layer> inactiveLayers = new ArrayList<Layer>();

   @Constant
   public Options options;

   /** The list of other layered systems for other runtimes. */
   public ArrayList<LayeredSystem> peerSystems = null;

   /** Set to true when this system is in a peer */
   public boolean peerMode = false;

   /** The list of layers originally specified by the user. */
   public List<Layer> specifiedLayers = new ArrayList<Layer>();

   public Layer buildLayer; // The last non-dynamic layer
   public Layer currentBuildLayer; // When compiling layers in a sequence, this stores the layering currently being built
   public Layer lastCompiledLayer; // The last in the compiled layers list
   public Layer lastLayer;  // The last layer
   public Map<String,Layer> layerIndex = new HashMap<String,Layer>(); // layer unique name
   public Map<String,Layer> layerFileIndex = new HashMap<String,Layer>(); // <layer-pkg> + layerName
   public Map<String,Layer> layerPathIndex = new HashMap<String,Layer>(); // layer-group name
   public String layerPath;
   public String rootClassPath; /* The classpath passed to the layered system constructor - usually the system */
   public String classPath;  /* The complete external class path, used for javac, not for looking up internal classes */
   public String userClassPath;  /* The part of the above which is user specified - i.e. to be used for scripts */

   /** Stores the latest representation of each model from the absolute file name.  When the externalModelIndex is set, this index shadows the external index, i.e. storing the last result we retreive from that index. */
   private Map<String,ILanguageModel> modelIndex = new HashMap<String,ILanguageModel>();

   /** Stores the inactive model types, separate from the active ones.  Here the layer stack is lazily formed and contains files we are not compiling... just loading for tooling purposes like the IDE or the doc styling */
   private Map<String,ILanguageModel> inactiveModelIndex = new HashMap<String,ILanguageModel>();

   /** Works in parallel to modelIndex for the files which are not parsed, so we know when they were last modified to do incremental refreshes of them. */
   public Map<String,IFileProcessorResult> processedFileIndex = new HashMap<String,IFileProcessorResult>();

   public LinkedHashMap<String,ModelToPostBuild> modelsToPostBuild = new LinkedHashMap<String,ModelToPostBuild>();

   public int layerDynStartPos = -1; /* The index of the first dynamic layer (or -1 if all layers are compiled) */

   /** Have any errors occurred in this system since it's been running?  */
   public boolean anyErrors = false;

   // TODO: Sadly this does not work at least on MacOSX
   public boolean updateSystemClassLoader = false;  // After compiling, do we add buildDir and layer classpath to the sys classpath?

   public String runtimeLibsDir = null;   /* Frameworks like android which can't just include sc runtime classes from the classpath can specify this path, relative to the buildDir.  The build will look for the sc classes in its classpath.  If it finds scrt.jar, it copies it.   If it finds a buildDir, it generates it and places it in the lib dir.  */
   public String runtimeSrcDir = null;   /* Some frameworks also may need to deploy the src to the core runtime - for example, to convert it to Javascript.  Set this property to the directory where the scrt-core-src.jar file should go */
   public String strataCodeLibsDir = null; /* Or set this directory to have sc.jar either copied or built from the scSourcePath */

   public boolean systemCompiled = false;  // Set to true when the system has been fully compiled once
   public boolean buildingSystem = false;
   public boolean initializingLayers = false; // Set to true when we are initializing layers
   public boolean runClassStarted = false;
   public boolean allTypesProcessed = false;

   public boolean useRuntimeReflection = true;  /* Frameworks like GWT do not support reflection.  In this case, we compile in wrapper code to access necessary properties of an object */

   public boolean usePropertyMappers = true;    /* Frameworks like Javascript don't need the property mapper optimization */

   public boolean useIndexSetForArrays = true;  // Should we generate a setX(int ix, Object elem) for an array property to allow element-by-element updates

   public boolean includeSrcInClassPath = false;  /* Frameworks like GWT require the generated src to be included in the classpath for compilation, DevMode etc. */

   public IRuntimeProcessor runtimeProcessor = null; /** Hook to inject new runtimes like javascript, etc. which processor Java */

   /** Each LayeredSystem stores the layers for one process.  This holds the configuration info for that process.  */
   public IProcessDefinition processDefinition = null;

   public String serverName = "localhost";  /** The hostname for accessing this system */

   public int serverPort = 8080; /** The port for accessing this system */

   public boolean serverEnabled = false;

   public String url; // The system URL

   /** The list of runtimes required to execute this stack of layers (e.g. javascript and java).  If java is in the list, it will be the first one and represented by a "null" entry. */
   public static ArrayList<IRuntimeProcessor> runtimes;

   /** The list of processes to build for this stack of layers */
   public static ArrayList<IProcessDefinition> processes;

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

   // Hook point for registering new file extensions into the build process.  Implement the process method
   // return IModelObj objects to register new types.
   public HashMap<String,IFileProcessor[]> fileProcessors = new HashMap<String,IFileProcessor[]>();
   public LinkedHashMap<Pattern,IFileProcessor> filePatterns = new LinkedHashMap<Pattern,IFileProcessor>();

   public List<TypeGroupDep> typeGroupDeps = new ArrayList<TypeGroupDep>();

   public RepositorySystem repositorySystem;

   public boolean useCanonicalPaths = false;

   private Set<String> changedDirectoryIndex = new HashSet<String>();
   private List<File> layerPathDirs;
   private Map<String,Object> otherClassCache; // Only used for CFClass - when crossCompile is true
   private Map<String,Class> compiledClassCache = new HashMap<String,Class>(); // Class
   {
      initClassCache();
   }

   static ThreadLocal<LayerUtil.LayeredSystemPtr> currentLayeredSystem = new ThreadLocal<LayerUtil.LayeredSystemPtr>();

   static LayeredSystem defaultLayeredSystem;
   long lastRefreshTime = -1;
   public long sysStartTime = -1;
   long buildStartTime = -1;

   /** A global setting turned on when in the IDE.  If true the original runtime is always 'java' - the default.  In the normal build env, if there's only one runtime, we never create the default runtime. */
   public static boolean javaIsAlwaysDefaultRuntime = false;

   JLineInterpreter cmd;

   public IExternalModelIndex externalModelIndex = null;

   /** Enable extra info in debugging why files are recompiled */
   private static boolean traceNeedsGenerate = false;

   /** Java's URLClassLoader does not allow replacing of classes in a subsequent layer.  The good reason for this is that you under no circumstances want to load the same class twice.  You do not want to load incompatible versions
    * of a class by overriding them.  But with layers managed build system, we ideally want a different model.  One where you load classes in a two stage fashion:  - look at your parent loader, see if the class has been loaded.  If
    * so, return it.  But if not, load it from the outside/in - i.e. pick the most specific version of that class.  The layered type system should catch class incompatibility errors that might exist (or at least it can evolve to
    * eliminate those errors).  In the more flexible layeredClassPaths model, we build all build layers and build the last compiled layer in a merged manner.  All dynamic layers are then in separate buildDir's which can be added andlet's be sure we have proper version - active or inactive
    * removed from the class path as needed.  This model lets us also more accurately detect when a subsequent layer's class will override a previous layer.
    * <p/>
    * TODO: So we need to implement our own class loading to do this which I have not done.  Setting this to "true" gets part of the way.
    */
   final static boolean layeredClassPaths = false;

   /* Stores the models which have been detected as changed based on their type name since the start of this build.  Preserved across the entire buildSystem call, i.e. even over a layered builds. */
   HashMap<String,IFileProcessorResult> changedModels = new HashMap<String, IFileProcessorResult>();

   /**
    * Stores the set of model names which have been through the getProcessedFile stage (i.e. transform)  Need this in the "clean stale entries" process.
    * When we determine a model has changed, we clean out any build info it creates so that we re-add the updated info when processing it.  But after that
    * type has been processed in one build layer, we should not clean out the entries in the next, because we do not re-start the model and won't add it back again.
    */
   HashSet<String> processedModels = new HashSet<String>();

   LinkedHashSet<String> viewedErrors = new LinkedHashSet<String>();

   /** Register an optional error handler for all system errors */
   public IMessageHandler messageHandler = null;

   /** Should we pick up the current class loader from the dynamic type system or used a fixed class loader (like in the plugin environment) */
   private boolean autoClassLoader = true;

   /**
    * For the master LayeredSystem only - the map from processident to TypeIndex HashMap, used to bootstrap the TypeIndexes, before the
    * peerSystems are created.
    */
   HashMap<String, SysTypeIndex> typeIndexProcessMap = null;

   TreeSet<String> customSuffixes = new TreeSet<String>();

   SysTypeIndex typeIndex;

   public List<VMParameter> vmParameters;

   /** When you are running with the source to StrataCode, instead of just with sc.jar point this to the source root - i.e. the dir which holds coreRuntime, fullRuntime, and sc */
   public static String scSourcePath = null;

   private LayerUtil.LayeredSystemPtr systemPtr;

   private void clearReverseTypeIndex() {
      typeIndex.clearReverseTypeIndex();
   }

   private void buildReverseTypeIndex() {
      try {
         acquireDynLock(false);

         typeIndex.buildReverseTypeIndex();

         if (!peerMode && peerSystems != null) {
            for (LayeredSystem peerSys: peerSystems) {
               peerSys.buildReverseTypeIndex();
            }
         }
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
      if (viewedErrors.size() == 50) {
         System.err.println(".... too many errors - disabling command line layers");
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

   public void clearActiveLayers() {
      activatedLayerNames = null;
      for (Layer layer: layers) {
         layer.destroyLayer();
      }
      layers.clear();
      cleanupLayerFileProcessors();
      Language.cleanupLanguages();
      layerDynStartPos = -1;
      specifiedLayers.clear();

      typesByName.clear();
      typesByRootName.clear();
      modelIndex.clear();
      innerTypeCache.clear();

      templateCache.clear();

      buildLayer = null;
      buildInfo = null;
      currentBuildLayer = null;
      commonBuildLayer = null;
      lastLayer = null;
      lastBuiltLayer = null;
      lastStartedLayer = null;
      lastCompiledLayer = null;

      layerIndex.clear();
      layerFileIndex.clear();
      layerPathIndex.clear();

      globalObjects.clear();

      changedModels.clear();

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

      if (!peerMode && peerSystems != null) {
         for (LayeredSystem peerSys : peerSystems)
            peerSys.clearActiveLayers();
      }

      resetBuild(false);

      DynUtil.clearObjectIds();

      TransformUtil.clearTemplateCache();

      removeAllActivePackageIndexEntries();

      if (!peerMode)
         System.gc();

   }

   public void activateLayers(List<String> layerNames) {
      // TODO: if any of the layer names in the run configuration have changed, throw it all away and restart from scratch.
      // this is the simple approach.  It would not be too hard to figure out the differences, and peel-away unused layers, and
      // add in used ones.  That would make the builds a lot faster I think in some cases since you can save the compiled state of
      // the previous layers.  But maybe a good incremental compile will be fast enough?
      if (activatedLayerNames == null || !activatedLayerNames.equals(layerNames)) {
         if (activatedLayerNames != null) {
            clearActiveLayers();
         }

         activatedLayerNames = new ArrayList<String>(layerNames);
         initLayersWithNames(layerNames, false, false, null, true, false);
         // First mark all layers that will be excluded
         removeExcludedLayers(true);

         // Init the runtimes, using the excluded layers as a guide
         initRuntimes(null, true);

         // Now actually remove the excluded layers.
         removeExcludedLayers(false);

         // Finally initialize the build system from scratch and we are ready to go.
         initBuildSystem(true, true);

         systemCompiled = false;

         // TODO: do we need to always do a new build when the layers have changed?
         if (!options.buildAllFiles)
            options.buildAllFiles = true;
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
         if (refLayer == null) {
            startIx = inactiveLayers.size() - 1;
            for (int i = startIx; i >= 0; i--) {
               Layer inactiveLayer = inactiveLayers.get(i);
               res = inactiveLayer.addInheritedAnnotationProcessors(type, res, false);
            }
         }
         else {
            res = refLayer.addInheritedAnnotationProcessors(type, res, true);
         }
      }
      return res;
   }

   private final static String TYPE_INDEX_DIR_PREFIX = "types_";

   public String getTypeIndexDir() {
      // Organized by runtime name, not process ident because the process ident can change during initialization.
      return getTypeIndexDir(getTypeIndexIdent());
   }

   public String getTypeIndexDir(String typeIndexIdent) {
      return FileUtil.concat(getStrataCodeDir("idx"), TYPE_INDEX_DIR_PREFIX + typeIndexIdent);
   }

   public String getProcessIdent() {
      String procName = getProcessName();
      String runtimeName = getRuntimeName();
      return procName == null ? runtimeName : runtimeName + "_" + procName;
   }

   public String getTypeIndexIdent() {
      return getRuntimeName(); // TODO: sometimes this should be getProcessIdent for true type uniqueness - for now we are sharing the type index across runtimes that have overlapping layers
   }

   /** For the IDE specifically find or create any TypeDeclaration for the type specified. */
   public void addAllTypeDeclarations(String typeName, ArrayList<Object> res) {
      Object typeObj = getSrcTypeDeclaration(typeName, null, true, false, true, Layer.ANY_INACTIVE_LAYER, false);
      if (typeObj != null)
         res.add(typeObj);
      else {
         Layer layer = getLayerByDirName(typeName);
         if (layer != null)
             res.add(layer.model.getModelTypeDeclaration());
         else {
            layer = getInactiveLayer(typeName.replace('.', '/'), true, true, true);
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
            ArrayList<TypeIndex> typeIndexes = sysIndex.getTypeIndexes(typeName);
            if (typeIndexes != null) {
               for (TypeIndex typeIndex : typeIndexes) {
                  Layer layer = getInactiveLayerByPath(typeIndex.layerName, null, true);
                  if (layer != null) {
                     Object newTypeObj = layer.layeredSystem.getSrcTypeDeclaration(typeName, null, true, false, true, Layer.ANY_INACTIVE_LAYER, false);
                     if (newTypeObj != null)
                        res.add(newTypeObj);
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
            disabledLayersIndex.put(layer.getLayerName(), layer);
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
      }
   }


   public enum BuildCommandTypes {
      Pre, Post, Run, Test
   }

   private EnumMap<BuildPhase,List<BuildCommandHandler>> preBuildCommands = new EnumMap<BuildPhase,List<BuildCommandHandler>>(BuildPhase.class);
   private EnumMap<BuildPhase,List<BuildCommandHandler>> postBuildCommands = new EnumMap<BuildPhase,List<BuildCommandHandler>>(BuildPhase.class);
   private EnumMap<BuildPhase,List<BuildCommandHandler>> runCommands = new EnumMap<BuildPhase,List<BuildCommandHandler>>(BuildPhase.class);
   private EnumMap<BuildPhase,List<BuildCommandHandler>> testCommands = new EnumMap<BuildPhase,List<BuildCommandHandler>>(BuildPhase.class);

   private List<IModelListener> modelListeners = new ArrayList<IModelListener>();

   EditorContext editorContext;

   public LinkedHashMap<String,LayerIndexInfo> allLayerIndex;   // Maps layer path names found to the package prefix of the layer

   public List<Layer> newLayers = null;

   /** For a layerUniqueName in this runtime which was a build layer, store the old buildDir - usually the layers buildDir unless we started the system with -d buildDir.  In that case, we need to reset the layer.buildDir back to the old value so things work right */
   private Map<String,String> loadedBuildLayers = new TreeMap<String,String>();

   String origBuildDir; // Track the original build directory, where we do the full compile.  After the orig build layer is removed, this guy will still need to go into the class path because it contains all of the non-build layer compiled assets.

   private Map<String,ImportDeclaration> globalImports = new HashMap<String,ImportDeclaration>();
   {
      globalImports.put("AddBefore", ImportDeclaration.create("sc.obj.AddBefore"));
      globalImports.put("AddAfter", ImportDeclaration.create("sc.obj.AddAfter"));
      globalImports.put("Component", ImportDeclaration.create("sc.obj.Component"));
      globalImports.put("IComponent", ImportDeclaration.create("sc.obj.IComponent"));
      globalImports.put("IAltComponent", ImportDeclaration.create("sc.obj.IAltComponent"));
      globalImports.put("CompilerSettings", ImportDeclaration.create("sc.obj.CompilerSettings"));
      globalImports.put("Bindable", ImportDeclaration.create("sc.bind.Bindable"));
   }

   private Map<String,ImportDeclaration> globalLayerImports = new HashMap<String, ImportDeclaration>();
   {
      globalLayerImports.put("LayeredSystem", ImportDeclaration.create("sc.layer.LayeredSystem"));
      globalLayerImports.put("FileUtil", ImportDeclaration.create("sc.util.FileUtil"));
      globalLayerImports.put("RepositoryPackage", ImportDeclaration.create("sc.repos.RepositoryPackage"));
      globalLayerImports.put("MvnRepositoryPackage", ImportDeclaration.create("sc.repos.mvn.MvnRepositoryPackage"));
      globalLayerImports.put("LayerFileProcessor", ImportDeclaration.create("sc.layer.LayerFileProcessor"));
      globalLayerImports.put("TemplateLanguage", ImportDeclaration.create("sc.lang.TemplateLanguage"));
      globalLayerImports.put("BuildPhase", ImportDeclaration.create("sc.layer.BuildPhase"));
      globalLayerImports.put("CodeType", ImportDeclaration.create("sc.layer.CodeType"));
      globalLayerImports.put("CodeFunction", ImportDeclaration.create("sc.layer.CodeFunction"));
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

   public String mainLayerDir = null; // Directory containing .stratacode dir be using by this stack - this is the last .stratacode dir found in the layer path.

   /** Set to "layers" or "../layers" when we are running in the StrataCode main/bin directories.  In this case we look for and by default install the layers folder next to the bin directory  */
   public String layersFilePathPrefix = null;

   // Set and used after we successfully compile.  It exposes those newly compiled files to the class loader
   // so we can use them during any interpreting we'll do after that.
   private ClassLoader buildClassLoader;

   // Set once we start running a dynamic application to a single web app's class loader.
   private ClassLoader systemClassLoader;

   public Layer lastBuiltLayer, lastStartedLayer;  // When layers are built separately, this stores the last one built/started - its classes are in the sys class loader

   public Layer lastInitedInactiveLayer;  // For inactive layers store the last one inited

   private HashMap<String,Template> templateCache = new HashMap<String,Template>();

   // Stores zip files we use for resolving types
   private HashMap<String,ZipFile> zipFileCache = new HashMap<String,ZipFile>();

   public HashMap<String,IAnnotationProcessor> defaultAnnotationProcessors = new HashMap<String,IAnnotationProcessor>();
   {
      registerDefaultAnnotationProcessor("sc.obj.Sync", SyncAnnotationProcessor.getSyncAnnotationProcessor());
   }

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

   public String getStrataCodeDir(String dirName) {
      String dir = mainLayerDir;
      if (dir == null) {
         dir = System.getProperty("user.dir");
      }
      return FileUtil.concat(dir, SC_DIR, dirName);
   }

   public LayeredSystem(String lastLayerName, List<String> initLayerNames, List<String> explicitDynLayers, String layerPathNames, String rootClassPath, Options options, IProcessDefinition useProcessDefinition, LayeredSystem parentSystem, boolean startInterpreter, IExternalModelIndex extModelIndex) {
      this.systemPtr = new LayerUtil.LayeredSystemPtr(this);
      this.options = options;
      this.peerMode = parentSystem != null;
      this.externalModelIndex = extModelIndex;
      this.systemInstalled = !options.installLayers;

      if (!peerMode) {
         disabledLayersIndex = new HashMap<String, Layer>();
         disabledLayers = new ArrayList<Layer>();

         disabledRuntimes = options.disabledRuntimes;
         if (disabledRuntimes == null) {
            disabledRuntimes = new ArrayList<String>();
         }
         else if (options.verbose)
            verbose("Disable runtimes: " + disabledRuntimes);
      }
      else
         disabledRuntimes = parentSystem.disabledRuntimes;

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
      Language.registerLanguage(JavaLanguage.INSTANCE, "scj");  // Alternate suffix for java files in the SC tree
      Language.registerLanguage(SCLanguage.INSTANCE, SCLanguage.DEFAULT_EXTENSION);
      Language.registerLanguage(TemplateLanguage.INSTANCE, "sctd");      Language.registerLanguage(TemplateLanguage.INSTANCE, "sct");

      // Not registering Javascript yet because it is not complete.  In most projects we just copy the JS files as well so don't need to parse them as a language
      JSLanguage.INSTANCE.initialize();

      layerPath = layerPathNames;
      initLayerPath();

      // Sets the coreBuildLayer - used for storing dynamic stubs needed for the layer init process
      LayerUtil.initCoreBuildLayer(this);

      // Do this before we init the layers so they can see the classes in the system layer
      initClassIndex(rootClassPath);
      initSysClassLoader(null, ClassLoaderMode.LIBS);
      initSysClassLoader(coreBuildLayer, ClassLoaderMode.BUILD);

      if (startInterpreter)
         cmd = new JLineInterpreter(this);

      if (newLayerDir == null) {
         // If the layer path is explicitly specified, by default we store new files in the last
         // directory int eh layer path
         if (layerPathNames != null && layerPathDirs != null && layerPathDirs.size() > 0) {
            newLayerDir = layerPathDirs.get(layerPathDirs.size() - 1).getPath();
         }
         else
            newLayerDir = mapLayerDirName(".");
      }
      if (mainLayerDir == null)
         mainLayerDir = newLayerDir;

      if (!peerMode) {
         String scSourceConfig = getStrataCodeDir(SC_SOURCE_PATH);
         if (new File(scSourceConfig).canRead()) {
            scSourcePath = FileUtil.getFileAsString(scSourceConfig);
            if (scSourcePath != null)
               scSourcePath = scSourcePath.trim();
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
            typeIndex = new SysTypeIndex(this);
      }

      if (!peerMode)
         this.repositorySystem = new RepositorySystem(new RepositoryStore(getStrataCodeDir("pkgs")), messageHandler, options.verbose, options.reinstall, options.update, options.installExisting);
      else {
         messageHandler = parentSystem.messageHandler;
         this.repositorySystem = new RepositorySystem(parentSystem.repositorySystem.store, messageHandler, options.verbose, options.reinstall, options.update, options.installExisting);
      }

      if (initLayerNames != null) {
         initLayersWithNames(initLayerNames, options.dynamicLayers, options.allDynamic, explicitDynLayers, true, useProcessDefinition != null);
      }

      if (lastLayerName != null) {
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

      boolean reusingDefaultRuntime = !javaIsAlwaysDefaultRuntime && runtimes != null && runtimes.size() == 1;
      if (useProcessDefinition == null) {
         if (!reusingDefaultRuntime)
            removeExcludedLayers(true);
         initRuntimes(explicitDynLayers, true);
         if (!reusingDefaultRuntime)
            removeExcludedLayers(false);
      }
      else {
         runtimeProcessor = useRuntimeProcessor;
         processDefinition = useProcessDefinition;
      }

      if (runtimeProcessor != null)
         runtimeProcessor.setLayeredSystem(this);

      setCurrent(this);

      initBuildSystem(false, true);

      if (excludedFiles != null) {
         excludedPatterns = new ArrayList<Pattern>(excludedFiles.size());
         for (int i = 0; i < excludedFiles.size(); i++)
            excludedPatterns.add(Pattern.compile(excludedFiles.get(i)));
      }
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

   private void initRuntimes(List<String> explicitDynLayers, boolean active) {
      LayeredSystem curSys = getCurrent();
      // If we have activated some layers and still don't have any runtimes, we create the default runtime
      if (runtimes == null && layers.size() != 0)
         addRuntime(null, null);

      // Create a new LayeredSystem for each additional runtime we need to satisfy the active set of layers.
      // Then purge any layers from this LayeredSystem which should not be here.
      if (processes != null && processes.size() > 1 && (peerSystems == null || peerSystems.size() < processes.size() - 1)) {
         // We want all of the layered systems to use the same buildDir so pass it through options as though you had used the -d option.  Of course if you use -d, it will happen automatically.
         if (options.buildDir == null) {
            if (lastLayer != null && !lastLayer.buildSeparate)
               options.buildLayerAbsDir = lastLayer.getDefaultBuildDir();
         }

         ArrayList<LayeredSystem> newPeers = new ArrayList<LayeredSystem>();

         for (int ix = 0; ix < processes.size(); ix++) {
            IProcessDefinition proc = processes.get(ix);

            // Skip the processor associated with the main layered system
            if (ProcessDefinition.compare(proc, processDefinition)) {
               // make the main layered system point to this process.
               if (processDefinition == null && proc != null)
                  processDefinition = proc;
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
                  updateSystemLayers(processPeer);
                  continue;
               }
            }

            if (proc != null && isRuntimeDisabled(proc.getRuntimeName())) {
               continue;
            }

            // Now create the new peer layeredSystem for this runtime.
            ArrayList<String> procLayerNames = new ArrayList<String>();

            if (active) {
               // It includes any layers in this runtime which belong in the new one
               addIncludedLayerNamesForProc(proc, procLayerNames, false);
               // We are adding a new runtime when we have existing runtimes.  This means that some layers in one of the existing peers
               // may also need to be in this runtime.  For any new layers, that have been added since the last initRuntimes call,
               // we have them in the core layered system at this stage, even if they do not belong there in the long term.
               if (peerSystems != null) {
                  for (LayeredSystem oldPeerSys : peerSystems) {
                     // As well as those layers in any peer runtimes that exist that as belong in the other runtime
                     oldPeerSys.addIncludedLayerNamesForProc(proc, procLayerNames, false);
                  }
               }
            }

            String peerLayerPath = layerPath;
            // When you run in the layer directory, the other runtime defines the root layer dir which we need as the layer path here since we specify all of the layers using absolute paths
            if (peerLayerPath == null && newLayerDir != null)
               peerLayerPath = newLayerDir;
            LayeredSystem peerSys = new LayeredSystem(null, procLayerNames, explicitDynLayers, peerLayerPath, rootClassPath, options, proc, this, false, externalModelIndex);

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
               peerSys.typeIndex = new SysTypeIndex(peerSys);
               if (typeIndexProcessMap != null)
                  typeIndexProcessMap.put(peerSys.getTypeIndexIdent(), peerSys.typeIndex);
            }
            else
               peerSys.typeIndex.setSystem(peerSys);
            peerSys.disabledRuntimes = disabledRuntimes;

            for (int i = 0; i < inactiveLayers.size(); i++) {
               Layer inactiveLayer = inactiveLayers.get(i);
               if (inactiveLayer.includeForProcess(proc)) {
                  Layer peerLayer = peerSys.getInactiveLayer(inactiveLayer.getLayerName(), false, !inactiveLayer.disabled, false);
                  if (peerLayer == null)
                     System.err.println("*** failed to find peer layer");
               }
            }

            newPeers.add(peerSys);
         }

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

         if (modelListeners != null) {
            for (IModelListener ml: modelListeners) {
               for (LayeredSystem newPeer: newPeers) {
                  ml.runtimeAdded(newPeer);
               }
            }
         }

      }
      // We've added some layers to the this layered system through activate.  Now we need to go through the
      // other systems and include the layers in them if they are needed.
      else { // If there's only one runtime, we'll use this layered system for it.
         processDefinition = processes != null && processes.size() > 0 ? processes.get(0) : null;
         runtimeProcessor = processDefinition == null ? null : processDefinition.getRuntimeProcessor();
         if (peerSystems != null) {
            for (LayeredSystem peerSys: peerSystems) {
               updateSystemLayers(peerSys);
            }
         }
      }

      // This is set in the constructor for the new LayeredSystem so we need to restore it here
      setCurrent(curSys);
   }

   /**
    * Designed to be called from the main layered system
    */
   private void updateSystemLayers(LayeredSystem peerSys) {
      if (peerMode)
         System.err.println("*** Warning - updating system layers from a peer system!");

      if (getNeedsProcess(peerSys.processDefinition)) {
         ArrayList<String> newPeerLayers = new ArrayList<String>();
         // Here only include layers that are included by a specified layer
         addIncludedLayerNamesForProc(peerSys.processDefinition, newPeerLayers, true);
         if (newPeerLayers.size() > 0) {
            peerSys.initLayersWithNames(newPeerLayers, false, false, null, true, true);
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

   private void addIncludedLayerNamesForProc(IProcessDefinition proc, List<String> procLayerNames, boolean specifiedLayers) {
      List<Layer> layersList = this.layers;
      for (int i = 0; i < layersList.size(); i++) {
         Layer layer = layersList.get(i);
         if (layer.includeForProcess(proc) && (!specifiedLayers || layer.isSpecifiedLayer()))
            procLayerNames.add(layer.getLayerName());
      }
   }

   private void startSeparateLayers() {
      ArrayList<Layer> toRemove = null;
      for (Layer layer:inactiveLayers) {
         // Build separate layers have to be activated when they are not excluded.
         if (layer.buildSeparate && !layer.excluded && !layers.contains(layer)) {
            if (layer.isStarted())
               error("*** Error - started layer found that needs to be activated");
            layer.activated = true;
            layer.layerPosition = layers.size();
            registerLayer(layer);
            layers.add(layer);
            // Separate layers should not be used in 'specifing' the process since they get started.
            //specifiedLayers.add(layer);
            lastLayer = layer;
            if (toRemove == null)
               toRemove = new ArrayList<Layer>();
            toRemove.add(layer);
         }
      }
      if (toRemove != null) {
         int remIx = -1;
         for (Layer remLayer:toRemove) {
            int ix = inactiveLayers.indexOf(remLayer);
            if (ix != -1) {
               inactiveLayers.remove(ix);
               if (remIx == -1)
                  remIx = ix;
            }
         }
         renumberInactiveLayers(remIx);
         checkLayerPosition();
      }
   }

   void removeExcludedLayers(boolean markOnly) {
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

      checkLayerPosition();

      int removeIx = -1;
      // Remove any layers that don't belong in this runtime
      for (int i = 0; i < inactiveLayers.size(); i++) {
         Layer inactiveLayer = inactiveLayers.get(i);
         if (inactiveLayer.excludeForProcess(processDefinition)) {
            inactiveLayer.excluded = true;
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
            mainLayerDir = newLayerDir = input;

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
         acquireDynLock(false);
         try {
            destroySystem();
         }
         finally {
            releaseDynLock(false);
         }
      }
   }

   public void destroySystem() {
      clearActiveLayers();

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
            peerSys.destroySystem();
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

      System.gc();
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
   }

   private void updateProcessDefinition(IProcessDefinition procDef) {
      processDefinition = procDef;
      initTypeIndexDir();
   }

   public static void addProcess(Layer fromLayer, IProcessDefinition procDef) {
      if (processes == null) {
         processes = new ArrayList<IProcessDefinition>();
         if (procDef != null && javaIsAlwaysDefaultRuntime)
            addProcess(null, null);
      }
      IProcessDefinition existing = null;
      int procIndex = 0;
      for (IProcessDefinition existingProc:processes) {
         if (procDef == null && existingProc == null)
            return;
         // If the existing proc is null it's a default runtime and no designated process.  If the new guy has a name and the same runtime process, just use it rather than creating a new one.
         else if (existingProc == null && procDef.getRuntimeProcessor() == null) {
            processes.set(procIndex, procDef);
            if (fromLayer.layeredSystem.processDefinition == null)
               fromLayer.layeredSystem.updateProcessDefinition(procDef);
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
         if (fromLayer.layeredSystem.processDefinition == existing)
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

      // Each process has a runtimeProcessor so make sure to add that runtime, if it's not here
      IRuntimeProcessor rtProc = procDef == null ? null : procDef.getRuntimeProcessor();
      if (runtimes == null || !runtimes.contains(rtProc))
         addRuntime(fromLayer, rtProc);
   }

   public static boolean createDefaultRuntime(Layer fromLayer, String name) {
      if (runtimes != null) {
         for (IRuntimeProcessor proc:runtimes) {
            if (proc != null) {
               String procName = proc.getRuntimeName();
               if (procName.equals(name))
                  return false;
            }
         }
      }
      addRuntime(fromLayer, new DefaultRuntimeProcessor(name));
      return true;
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
      return  main.serverEnabled;
   }

   public String getServerURL() {
      LayeredSystem main = getMainLayeredSystem();
      if (main == this)
         return serverEnabled ? ("http://" + serverName + (serverPort == 80 ? "" : ":" + serverPort)) + "/" : "file:" + buildDir + "/web/";
      else
         return main.getServerURL();
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
      if (runtimeProcessor != null) {
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
      if (peerSystems != null) {
         for (LayeredSystem sys:peerSystems)
            if (!sys.peerMode)
               return sys;
      }
      System.err.println("*** Invalid LayeredSystem - no main layered system - peerMode is true but no other system which is acting as the main system");
      return null;
   }

   public boolean getNeedsAnonymousConversion() {
      return runtimeProcessor != null && runtimeProcessor.getNeedsAnonymousConversion();
   }


   private void initLayersWithNames(List<String> initLayerNames, boolean dynamicByDefault, boolean allDynamic, List<String> recursiveDyn, boolean specifiedLayers, boolean explicitLayers) {
      try {
         acquireDynLock(false);
         PerfMon.start("initLayers");
         LayerParamInfo lpi = new LayerParamInfo();
         lpi.explicitLayers = explicitLayers;
         if (dynamicByDefault) {
            lpi.explicitDynLayers = new ArrayList<String>();
            // We support either . or / as separate for layer names so be sure to match both
            for (String initLayer : initLayerNames) {
               lpi.explicitDynLayers.add(initLayer.replace(".", "/"));
               lpi.explicitDynLayers.add(initLayer.replace("/", "."));
               if (allDynamic)
                  lpi.explicitDynLayers.add("<all>");
            }
         }
         if (recursiveDyn != null) {
            ArrayList<String> aliases = new ArrayList<String>();
            for (int i = 0; i < recursiveDyn.size(); i++) {
               String exLayer = recursiveDyn.get(i);
               exLayer = FileUtil.removeTrailingSlash(exLayer);
               aliases.add(exLayer.replace(".", "/"));
               aliases.add(exLayer.replace("/", "."));
            }
            aliases.addAll(recursiveDyn);
            lpi.recursiveDynLayers = aliases;
            if (lpi.explicitDynLayers == null) // Recursive layers should also be explicitly made dynamic
               lpi.explicitDynLayers = lpi.recursiveDynLayers;
            else
               lpi.explicitDynLayers.addAll(lpi.recursiveDynLayers);
         }
         List<Layer> resLayers = initLayers(initLayerNames, null, null, dynamicByDefault, lpi, specifiedLayers);
         if (resLayers == null || resLayers.contains(null)) {
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
            if (l.buildDir == null || l.buildSrcDir == null) // TODO: only for buildLayers?
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

   /** These are packages which do not check for source definitions during the resolution of a layer definition file */
   public static HashSet<String> systemLayerModelPackages = new HashSet<String>();
   static {
      systemLayerModelPackages.add("java.lang");
      systemLayerModelPackages.add("java.util");
      systemLayerModelPackages.add("sc.lang");
      systemLayerModelPackages.add("sc.lang.sc");
      systemLayerModelPackages.add("sc.lang.html");
      systemLayerModelPackages.add("sc.lang.template");
      systemLayerModelPackages.add("sc.layer"); // For LayeredSystem in the layerDef files
      systemLayerModelPackages.add("sc"); // For looking up components of paths
      systemLayerModelPackages.add("java"); // For looking up components of paths
   }

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
      boolean buildSubDir;
   }

   public String getStrataCodeRuntimePath(boolean core, boolean src) {
      String propName = "sc." + (core ? "core." : "") + (src ? "src." : "") + "path";
      String path;
      if ((path = System.getProperty(propName)) != null)
         return path;
      RuntimeRootInfo info = getRuntimeRootInfo();
      if (info.zipFileName != null) {
         String dir = FileUtil.getParentPath(info.zipFileName);
         return FileUtil.concat(dir, "scrt" + (core ? "-core" : "") + (src ? "-src" : "") + ".jar");
      }
      else if (info.buildDirName != null) {
         String sysRoot;
         if ((sysRoot = getSystemBuildLayer(info.buildDirName)) != null) {
            if (!src)
               return FileUtil.concat(sysRoot, core ? "coreRuntime" : "fullRuntime", !isIDEBuildLayer(info.buildDirName) ? "build" : null);
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
      return "Error - sourcePath is not set in layers/.stratacode/scSourcePath";
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
      for (int i = 0; i < runtimePackages.length; i++) {
         String pkg = runtimePackages[i];
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
         if (getSystemBuildLayer(info.buildDirName) != null) {
            // TODO: need to do the merge of coreRuntime and fullRuntime and copy that to the runtime libs dir.
            // Make sure fullRuntime overrides coreRuntime.
            System.err.println("*** Unable to build scrt.jar file from standard build configuration yet");
         }
         else {
            if (LayerUtil.buildJarFile(info.buildDirName, getRuntimePrefix(), outJarName, null,  runtimePackages, /* userClassPath */ null, LayerUtil.CLASSES_JAR_FILTER, options.verbose) != 0)
               System.err.println("*** Unable to jar up sc runtime files into: " + outJarName + " from buildDir: " + info.buildDirName);

         }
      }
      else if (info.zipFileName != null) {
         String zipDirName = FileUtil.getParentPath(info.zipFileName);

         // We're only supposed to copy the runtime part.  Assume that scrt.jar is right next to sc.jar
         if (FileUtil.getFileName(info.zipFileName).equals("sc.jar")) {
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
         if (LayerUtil.buildJarFile(srcDir, null, outJarName, null,  runtimePackages, /* userClassPath */ null, LayerUtil.SRC_JAR_FILTER, options.verbose) != 0)
            System.err.println("*** Failed trying to jar sc runtime src files into: " + outJarName + " from buildDir: " + srcDir);
      }
      else {
         info("Copying scrt-core-src.jar from: " + srcDir + " to: " + outJarName);
         if (!FileUtil.copyFile(srcDir, outJarName, true))
            System.err.println("*** Failed to copy sc runtime files from: " + srcDir + " to: " + dir);
      }
   }

   private final static String STRATACODE_LIBRARIES_FILE = "sc.jar";

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
         if (LayerUtil.buildJarFile(tempDir.getPath(), null, outJarName, null,  null, /* userClassPath */ null, LayerUtil.CLASSES_JAR_FILTER, options.verbose) != 0)
            System.err.println("*** Failed trying to jar sc runtime src files into: " + outJarName + " from buildDir: " + tempDirPath);
         else
            FileUtil.removeFileOrDirectory(tempDir);
      }
      else {
         String srcDirPath = srcDirFile.getPath();
         info("Copying scr.jar from: " + srcDirPath + " to: " + outJarName);
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
      Thread.currentThread().setContextClassLoader(buildClassLoader);
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
      //if (mode.doLibs() || sysLayer.buildSeparate || sysLayer == buildLayer) {
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
            if (layerURLs.length > 0) {
               if (options.verbose) {
                  String fromLayer = lastPos == sysLayer.layerPosition - 1 ? null : sysLayer.getLayersList().get(lastPos).getLayerName();
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
            Thread.currentThread().setContextClassLoader(buildClassLoader);
         }

         /*
         try {
            buildClassLoader = new URLClassLoader(new URL[] { buildDirFile.toURL() }, getClass().getClassLoader());

         }
         catch (MalformedURLException exc) {
            System.err.println("*** Unable to create build class loader" + exc);
         }
         */

         if (updateSystemClassLoader) {
            // Once we've finished compiling, we can add the buildDir to the classpath.  At this point we also
            // add the layer directories afterwards in the classpath so we get the right precedence order.
            //addToSystemClassPath(buildDir);

            for (int i = sysLayer.layerPosition; i >= 0; i--) {
               Layer layer = layers.get(i);
               if (layer.isBuildLayer() && layer.compiled)
                  addToSystemClassPath(LayerUtil.appendSlashIfNecessary(layer.buildDir));
               if (layer.classDirs != null) {
                  for (int j = 0; j < layer.classDirs.size(); j++) {
                     String dir = layer.classDirs.get(j);
                     addToSystemClassPath(LayerUtil.appendSlashIfNecessary(dir));
                  }
               }
            }
         }
      }
   }

   private static Class[] ADD_URL_PARAMETERS = {URL.class};
   private void addToSystemClassPath(String dir) {
      ClassLoader sysLoaderTemp = ClassLoader.getSystemClassLoader();
      if (sysLoaderTemp instanceof URLClassLoader) {
         URLClassLoader sysloader = (URLClassLoader) sysLoaderTemp;
         Class sysclass = URLClassLoader.class;

         try {
            URL url = new URL("file", null, 0, dir);
            Method method = sysclass.getDeclaredMethod("addURL", ADD_URL_PARAMETERS);
            method.setAccessible(true);
            method.invoke(sysloader, url);
         }
         catch (Throwable t) {
            t.printStackTrace();
            throw new IllegalArgumentException("Error, could not add URL to system classloader");
         }
      }
      else {
         if (options.verbose)
            verbose("Unable to modify system class loader - some runtime features may not work");
      }
   }

   static File initBuildFile(String name) {
      File f = new File(name);
      if (!f.exists())
         f.mkdirs();
      return f;
   }


   public static void setCurrent(LayeredSystem sys) {
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


   public void findMatchingGlobalNames(Layer fromLayer, Layer refLayer, String prefix, Set<String> candidates, boolean retFullTypeName, boolean srcOnly) {
      String prefixPkg = CTypeUtil.getPackageName(prefix);
      String prefixBaseName = CTypeUtil.getClassName(prefix);

      findMatchingGlobalNames(fromLayer, refLayer, prefix, prefixPkg, prefixBaseName, candidates, retFullTypeName, srcOnly);
   }

   void addMatchingCandidate(Set<String> candidates, String pkgName, String baseName, boolean retFullTypeName) {
      if (retFullTypeName)
         candidates.add(CTypeUtil.prefixPath(pkgName, baseName));
      else
         candidates.add(baseName);
   }

   void addMatchingImportMap(Map<String,ImportDeclaration> importsByName, String prefix, Set<String> candidates, boolean retFullTypeName) {
      if (importsByName != null) {
         for (Map.Entry<String,ImportDeclaration> ent:importsByName.entrySet()) {
            String baseName = ent.getKey();
            ImportDeclaration decl = ent.getValue();
            if (baseName.startsWith(prefix))
               addMatchingCandidate(candidates, CTypeUtil.getPackageName(decl.identifier), baseName, retFullTypeName);
         }
      }
   }

   /** This method is used by the IDE to retrieve names for code-completion, name-lookup, etc.  */
   public void findMatchingGlobalNames(Layer fromLayer, Layer refLayer,
                                       String prefix, String prefixPkg, String prefixBaseName, Set<String> candidates, boolean retFullTypeName, boolean srcOnly) {
      acquireDynLock(false);
      try {
         if (prefixPkg == null) {
            if (systemClasses != null && !srcOnly) {
               for (String sysClass:systemClasses)
                  if (sysClass.startsWith(prefix))
                     addMatchingCandidate(candidates, "java.lang", sysClass, retFullTypeName);
            }

            // For global lookups if no layers have been activated, just search all of the inactive layers.  This will
            // currently only correspond to the layers in files we've loaded so far.  Once an app has been run, we'll just
            // use the current app's layers.
            if (refLayer == null) {
               for (int i = 0; i < inactiveLayers.size(); i++) {
                  Layer inactiveLayer = inactiveLayers.get(i);
                  if (inactiveLayer.exportImports)
                     inactiveLayer.findMatchingGlobalNames(prefix, candidates, retFullTypeName, true);

                  // When we do not have any active layers, we cannot rely on the type cache for finding src file names.
                  // Instead, we go to the srcDir index in the layers.
                  inactiveLayer.findMatchingSrcNames(prefix, candidates, retFullTypeName);
               }
               if (buildLayer != null) {
                  refLayer = buildLayer;
                  int startIx = fromLayer == null ? layers.size() - 1 : fromLayer.getLayerPosition();
                  for (int i = startIx; i >= 0; i--) {
                     Layer appLayer = layers.get(i);
                     appLayer.findMatchingSrcNames(prefix, candidates, retFullTypeName);
                  }
               }
            }

            if (refLayer == null || !refLayer.activated || refLayer == Layer.ANY_INACTIVE_LAYER) {
               typeIndex.addMatchingGlobalNames(prefix, candidates, retFullTypeName);

               if (!peerMode && peerSystems != null) {
                  for (int i = 0; i < peerSystems.size(); i++) {
                     LayeredSystem peerSys = peerSystems.get(i);
                     peerSys.findMatchingGlobalNames(null, null, prefix, prefixPkg, prefixBaseName, candidates, retFullTypeName, srcOnly);
                  }
               }
               if (typeIndexProcessMap != null) {
                  for (Map.Entry<String,SysTypeIndex> typeIndexEntry:typeIndexProcessMap.entrySet()) {
                     SysTypeIndex idx = typeIndexEntry.getValue();
                     idx.addMatchingGlobalNames(prefix, candidates, retFullTypeName);
                  }
               }
            }

            if (!srcOnly) {
               if (refLayer != null && refLayer != Layer.ANY_LAYER && refLayer.inheritImports && refLayer != Layer.ANY_INACTIVE_LAYER) {
                  refLayer.findMatchingGlobalNames(prefix, candidates, retFullTypeName, true);
               }

               // TODO - remove this part?  We definitely need the above for when are inactive but any reason to include these other names other times?
               int startIx = fromLayer == null ? layers.size() - 1 : fromLayer.getLayerPosition();
               for (int i = startIx; i >= 0; i--) {
                  Layer depLayer = layers.get(i);
                  if (depLayer.exportImports)
                     depLayer.findMatchingGlobalNames(prefix, candidates, retFullTypeName, false);
               }

               addMatchingImportMap(globalImports, prefix, candidates, retFullTypeName);

               for (String prim: Type.getPrimitiveTypeNames())
                   if (prim.startsWith(prefix))
                      candidates.add(prim);
            }
         }

         if (!srcOnly) {
            for (Map.Entry<String,HashMap<String,PackageEntry>> piEnt:packageIndex.entrySet()) {
               String pkgName = piEnt.getKey();
               pkgName = pkgName == null ? null : pkgName.replace("/", ".");

               // If there's a pkg name for the prefix, and it matches the package, check the contents against
               if (pkgName == prefixPkg || (prefixPkg != null && pkgName != null && pkgName.equals(prefixPkg))) {
                  HashMap<String,PackageEntry> pkgTypes = piEnt.getValue();
                  for (Map.Entry<String,PackageEntry> pkgTypeEnt:pkgTypes.entrySet()) {
                     String typeInPkg = pkgTypeEnt.getKey();
                     if (typeInPkg.startsWith(prefixBaseName)) {
                        addMatchingCandidate(candidates, prefixPkg, typeInPkg, retFullTypeName);
                        //candidates.add(CTypeUtil.prefixPath(prefixPkg, typeInPkg));
                        //candidates.add(typeInPkg);
                     }
                  }
               }

               // Include the right part of the package name
               if (pkgName != null && pkgName.startsWith(prefix)) {
                  int len = prefix.length();
                  boolean includeFirst = StringUtil.equalStrings(prefix, prefixPkg);
                  // If we are matching the prefixPkg we include the package name itself.  Otherwise, we skip it
                  int matchEndIx = pkgName.indexOf(".", len + (includeFirst ? 1 : 0));
                  String headName;
                  String tailName;
                  if (matchEndIx == -1) {
                     headName = CTypeUtil.getPackageName(pkgName);
                     tailName = CTypeUtil.getClassName(pkgName);
                  }
                  else {
                     headName = pkgName.substring(0, len);
                     int startIx = headName.lastIndexOf(".");
                     if (startIx == -1)
                        startIx = includeFirst ? len+1 : 0;
                     tailName = pkgName.substring(startIx, matchEndIx);
                  }
                  if (tailName.length() > 0) {
                     addMatchingCandidate(candidates, headName, tailName, retFullTypeName);
                  }
               }
            }
         }
      }
      finally {
         releaseDynLock(false);
      }
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

   public Object getInnerCFClass(String fullTypeName, String name) {
      String parentClassPathName = fullTypeName.replace('.','/');
      PackageEntry ent = getPackageEntry(parentClassPathName);
      if (ent == null) {
         System.err.println("*** can't find package entry for parent of inner type");
         return null;
      }
      while (ent != null && ent.src)
         ent = ent.prev;

      if (ent == null)
         return null;
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

   public boolean isSTypeObject(Object obj) {
      // Must test this case first - it extends TypeDeclaration but is used to serialize a type across the wire so is explicitly excluded
      if (obj instanceof ClientTypeDeclaration)
         return false;
      if (obj instanceof BodyTypeDeclaration && typesAreObjects)
         return false;
      return (obj instanceof ITypeDeclaration && !(obj instanceof Template)) || obj instanceof Class;
   }

   /** Part of the IDyamicModel interface - identifies the type we use to identifify dynamic types */
   public boolean isTypeObject(Object obj) {
      // Must test this case first - it extends TypeDeclaration but is used to serialize a type across the wire so is explicitly excluded
      if (obj instanceof ClientTypeDeclaration)
         return false;
      return (obj instanceof ITypeDeclaration && !(obj instanceof Template)) || obj instanceof Class;
   }

   public Object getReturnType(Object method) {
      return ModelUtil.getReturnType(method);
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

   public Object[] getMethods(Object type, String methodName) {
      return ModelUtil.getMethods(type, methodName, null);
   }

   public Object getDeclaringClass(Object method) {
      return ModelUtil.getMethodDeclaringClass(method);
   }

   public Object invokeMethod(Object obj, Object method, Object[] argValues) {
      return ModelUtil.callMethod(obj, method, argValues);
   }

   public String getMethodName(Object method) {
      return ModelUtil.getMethodName(method);
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

   public String getInheritedScopeName(Object typeObj) {
      return ModelUtil.getInheritedScopeName(this, typeObj);
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
      if (typeObj instanceof TypeDeclaration)
         return DynObject.create((TypeDeclaration) typeObj, constrSig, params);
      // TODO: need to deal with constructor signature here
      else if (typeObj instanceof Class)
         return PTypeUtil.createInstance((Class) typeObj, constrSig, params);
      // Passed the constructor reference directly so we do not have to choose constructor by sig or param type
      else if (typeObj instanceof IMethodMapper) {
         return ((IMethodMapper) typeObj).invoke(null, params);
      }
      else
         throw new IllegalArgumentException("Invalid type to createInstace: " + typeObj);
   }

   public Object createInnerInstance(Object typeObj, Object outerObj, String constrSig, Object[] params) {
      if (typeObj instanceof TypeDeclaration) {
         return DynObject.create((TypeDeclaration) typeObj, outerObj, constrSig, params);
      }
      else if (typeObj instanceof Class) {
         Object[] newParams = new Object[params.length+1];
         newParams[0] = outerObj;
         System.arraycopy(params, 0, newParams, 1, params.length);
         return PTypeUtil.createInstance((Class) typeObj, constrSig, newParams);
      }
      else
         throw new IllegalArgumentException("Invalid type to createInstace: " + typeObj);
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

   public static class Options {
      /** Re-generate all source files when true.  The default is to use dependencies to only generate changed files. */
      @Constant public boolean buildAllFiles;
      /** When true, do not inherit files from previous layers.  The buildDir will have all java files, even from layers that are already compiled */
      @Constant public boolean buildAllLayers;
      @Constant public boolean noCompile;
      /** Controls debug level verbose messages */
      @Constant public boolean verbose = false;
      /** Print the basic layer stack info at startup.  Very useful for seeing which runtimes and layers are created for your app. */
      @Constant public boolean verboseLayers = false;
      /** Diagnose issues finding classes (e.g. to trace adding entries to the package index) */
      @Constant public boolean verboseClasses = false;
      @Constant public boolean verboseLocks = false;
      @Constant public boolean info = true;
      /** Controls whether java files compiled by this system debuggable */
      @Constant public boolean debug = true;
      @Constant public boolean crossCompile = false;
      /** Change to the buildDir before running the command */
      @Constant public boolean runFromBuildDir = false;
      @Constant public boolean runScript = false;
      @Constant public boolean createNewLayer = false;
      @Constant public boolean dynamicLayers = false;
      @Constant public boolean anyDynamicLayers = false;
      /** -dynall: like -dyn but all layers included by the specified layers are also made dynamic */
      @Constant public boolean allDynamic = false;
      /** When true, we maintain the reverse mapping from type to object so that when certain type changes are made, we can propagate those changes to all instances.  This is set to true by default when the editor is enabled.  Turn it on with -dt  */
      @Constant public boolean liveDynamicTypes = true;
      /** When true, we compile in support for the ability to enable liveDynamicTypes */
      @Constant public boolean compileLiveDynamicTypes = true;
      /** When you have multiple build layers, causes each subsequent layer to get all source/class files from the previous. */
      @Constant public boolean useCommonBuildDir = false;
      @Constant public String buildDir;
      @Constant public String buildLayerAbsDir;
      @Constant public String buildSrcDir;
      /** By default run all main methods defined with no arguments */
      @Constant public String runClass = ".*";
      @Constant public String[] runClassArgs = null;
      /** File used to record script by default */
      @Constant public String recordFile;
      @Constant public String restartArgsFile;
      /** Enabled with the -c option - only compile, do not run either main methods or runCommands. */
      @Constant public boolean compileOnly = false;

      /** Do a rebuild automatically when a page is refreshed.  Good for development since each page refresh will recompile and update the server and javascript code.  If a restart is required you are notified.  Bad for production because it's very expensive. */
      @Constant public boolean autoRefresh = true;

      @Constant public boolean retryAfterFailedBuild = false;

      @Constant String testPattern = null;

      /** Argument to control what happens after the command is run, e.g. it can specify the URL of the page to open. */
      @Constant String openPattern = null;

      /** Controls whether or not the start URL is opened */
      @Constant boolean openPageAtStartup = true;

      @Constant /** An internal option for how we implement the transform.  When it's true, we clone the model before doing the transform.  This is the new way and makes for a more robust system.  false is deprecated and probably should be removed */
      public boolean clonedTransform = true;

      @Constant /** An internal option to enable use of the clone operation to re-parse the same file in a separate peer system */
      public boolean clonedParseModel = true;

      @Constant /** Additional system diagnostic information in verbose mode */
      public boolean sysDetails = false;

      // TODO: add a "backup option" just in case build files are edited
      @Constant /** Removes the existing build directories */
      public boolean clean = false;

      // Enable editing of the program editor itself
      @Constant public boolean editEditor = false;

      // As soon as any file in a build-dir is changed, build all files.  A simpler alternative to incremental compile that performs better than build all
      @Constant public boolean buildAllPerLayer = false;

      /** Should dependent packages be updated using the repository package system */
      @Constant public boolean updateSystem = false;

      @Constant public TypeIndexMode typeIndexMode = TypeIndexMode.Lazy;

      @Constant public ArrayList<String> disabledRuntimes;

      @Constant public boolean disableCommandLineErrors = false;

      /** When doing an incremental build, this optimization allows us to load the compiled class for final types. */
      @Constant public boolean useCompiledForFinal = true;

      /** Should we clear up all data structured after running the program (for better heap diagnostics) */
      @Constant public boolean clearOnExit = true;

      /** Should we install default layers if we can't find them in the layer path? */
      @Constant public boolean installLayers = true;

      /** Should we reinstall all packages */
      @Constant public boolean reinstall;

      /** Should we reinstall all packages but reusing existing downloads */
      @Constant public boolean installExisting;

      /** Should we update all packages */
      @Constant public boolean update;
   }

   @MainSettings(produceJar = true, produceScript = true, execName = "bin/sc", debug = false, maxMemory = 1024, defaultArgs = "-restartArgsFile /tmp/restart$$.tmp")
   public static void main(String[] args) {
      String buildLayerName = null;
      List<String> includeLayers = null;
      Options options = new Options();
      List<String> includeFiles = null;  // List of files to process
      String classPath = null;
      String layerPath = null;
      boolean includingFiles = false;    // The -f option changes the context so we are reading file names
      boolean startInterpreter = true;
      boolean editLayer = true;
      String commandDirectory = null;
      boolean restartArg;
      ArrayList<String> restartArgs = new ArrayList<String>();
      int lastRestartArg = -1;
      ArrayList<String> recursiveDynLayers = null;

      for (int i = 0; i < args.length; i++) {
         restartArg = true;
         if (args[i].length() == 0)
            usage("Invalid empty option", args);
         if (args[i].charAt(0) == '-') {
            if (args[i].length() == 1)
               usage("Invalid option: " + args[i], args);

            String opt = args[i].substring(1);
            switch (opt.charAt(0)) {
               case 'a':
                  if (opt.equals("a")) {
                     // The thinking by setting both here is that if you are building all files, there's no point in trying to inherit from other layers.  It won't be any fasteruu
                     options.buildAllFiles = true;
                     restartArg = false;
                     break;
                  }
                  else if (opt.equals("al")) {
                     options.buildAllPerLayer = true;
                     break;
                  }
                  else
                     usage("Unrecognized option: " + opt, args);
               case 'A':
                  if (opt.equals("A")) {
                     options.buildAllLayers = true;
                     restartArg = false;
                     break;
                  }
                  else
                     usage("Unrecognized option: " + opt, args);
               case 'd':
                  if (opt.equals("d") || opt.equals("ds") || opt.equals("db") || opt.equals("da")) {
                     if (i == args.length - 1)
                        System.err.println("*** Missing buildDir argument to -d option");
                     else {
                        String buildArg = args[++i];
                        if (opt.equals("d") || opt.equals("db"))
                           options.buildDir = buildArg;
                        if (opt.equals("ds"))
                           options.buildSrcDir = buildArg;
                        if (opt.equals("da"))
                           options.buildLayerAbsDir = buildArg;
                     }
                  }
                  else if (opt.equals("dynone")) {
                     options.dynamicLayers = true;
                     options.anyDynamicLayers = true;
                  }
                  else if (opt.equals("dynall")) {
                     options.dynamicLayers = true;
                     options.allDynamic = true;
                     options.anyDynamicLayers = true;
                  }
                  else if (opt.equals("dyn")) {
                     recursiveDynLayers = new ArrayList<String>();
                     options.anyDynamicLayers = true;
                  }
                  else if (opt.equals("dt"))
                     options.liveDynamicTypes = true;
                  else
                     usage("Unrecognized option: " + opt, args);
                  break;
               case 'e':
                  if (opt.equals("ee"))
                     options.editEditor = true;
                  else
                     usage("Unrecognized option: " + opt, args);
                  break;
               case 'i':
                  if (opt.equals("ie"))
                     options.installExisting = true;
                  else {
                     startInterpreter = true;
                     editLayer = false;
                  }
                  break;
               case 'h':
                  usage("", args);
                  break;
               case 'n':
                  if (opt.equals("nc")) {
                     options.noCompile = true;
                     break;
                  }
                  else if (opt.equals("nd"))
                     options.liveDynamicTypes = false;
                  else if (opt.equals("n")) {
                     options.createNewLayer = true;
                     break;
                  }
                  else if (opt.equals("nw")) {
                     options.openPageAtStartup = false;
                     break;
                  }
                  else if (opt.equals("ni"))
                     startInterpreter = false;
                  else if (opt.equals("ndbg")) {
                     options.debug = false;
                     break;
                  }
                  else
                     usage("Unrecognized option: " + opt, args);
                  break;
               case 'o':
                  if (opt.equals("o")) {
                     if (i == args.length - 1)
                        System.err.println("*** missing pattern arg to run -o option");
                     else {
                        options.openPattern = args[++i];
                     }
                  }
                  else if (opt.startsWith("opt:")) {
                     if (opt.equals("opt:disableFastGenExpressions"))
                        JavaLanguage.fastGenExpressions = false;
                     else if (opt.equals("opt:disableFastGenMethods"))
                        JavaLanguage.fastGenMethods = false;
                     else if (opt.equals("opt:perfMon"))
                        PerfMon.enabled = true;
                     else
                        System.err.println("*** Unrecognized option: " + opt);
                  }
                  break;
               case 'f':
                  includingFiles = true;
                  break;
               case 'l':
                  if (opt.equals("lp")) {
                     if (i == args.length - 1)
                        layerPath = "";
                     else
                        layerPath = args[++i];
                  }
                  else if (opt.equals("l"))
                     includingFiles = false;
                  else
                     usage("Unrecognized option: " + opt, args);
                  break;
               case 'c':
                  // Compile only
                  if (opt.equals("c")) {
                     options.runClass = null;
                     startInterpreter = false;
                     options.compileOnly = true;
                  }
                  else if (opt.equals("cp")) {
                     if (i == args.length - 1)
                        classPath = "";
                     else
                        classPath = args[++i];
                  }
                  else if (opt.equals("cd")) {
                     if (i == args.length - 1)
                        System.err.println("*** missing arg to command directory -cd option");
                     else
                        commandDirectory = args[++i];
                  }
                  else if (opt.equals("cc")) {
                     options.crossCompile = true;
                  }
                  else if (opt.equals("clean"))
                     options.clean = true;
                  else
                     usage("Unrecognized option: " + opt, args);
                  break;
               case 'r':
                  if (opt.equals("rb")) {
                     options.runFromBuildDir = true;
                     opt = "r";
                  }
                  else if (opt.equals("rs")) {
                     options.runScript = true;
                     opt = "r";
                  }
                  else if (opt.equals("ra")) {
                     options.runClass = ".*";
                     options.runClassArgs = new String[args.length-i-1];
                     int k = 0;
                     for (int j = i+1; j < args.length; j++)
                        options.runClassArgs[k++] = args[j];
                     i = args.length;
                  }
                  else if (opt.equals("rec")) {
                     if (i == args.length - 1)
                        System.err.println("*** missing record file to -rec option");
                     options.recordFile = args[++i];
                  }
                  else if (opt.equals("restartArgsFile")) {
                     options.restartArgsFile = args[++i];
                     restartArg = false;
                  }
                  else if (opt.equals("restart")) {
                     // Start over again with the arguments saved from the program just before restarting
                     if (options.restartArgsFile == null) {
                        usage("-restart requires the use of the restartArgsFile option - typically only used from the generated shell script: ", args);
                     }
                     else {
                        File restartFile = new File(options.restartArgsFile);
                        args = StringUtil.splitQuoted(FileUtil.readFirstLine(restartFile));
                        restartFile.delete();
                     }
                     restartArg = false;
                     i = -1;
                  }
                  else if (opt.equals("ri"))
                     options.reinstall = true;
                  else if (opt.equals("r")) {
                     if (i == args.length - 1)
                        System.err.println("*** missing arg to run -r option");
                     else {
                        options.runClass = args[++i];
                        options.runClassArgs = new String[args.length-i-1];
                        int k = 0;
                        for (int j = i+1; j < args.length; j++)
                           options.runClassArgs[k++] = args[j];
                        i = args.length;
                     }
                  }
                  else
                     usage("Unrecognized options: " + opt, args);
                  break;
               case 't':
                  if (opt.equals("ta")) {
                     options.testPattern = ".*";
                  }
                  else if (opt.equals("t")) {
                     if (i == args.length - 1)
                        System.err.println("*** missing arg to run -t option");
                     else {
                        options.testPattern = args[++i];
                     }
                  }
                  else
                     usage("Unrecognized options: " + opt, args);
                  break;
               case 'u':
                  if (opt.equals("u"))
                     options.updateSystem = true;
                  else
                     usage("Unrecognized options: " + opt, args);
                  break;
               case 'v':
                  if (opt.equals("vb"))
                     Bind.trace = true;
                  else if (opt.equals("vv")) {
                     options.sysDetails = true;
                     options.verbose = true;
                  }
                  else if (opt.equals("vba")) {
                     Bind.trace = true;
                     Bind.traceAll = true;
                  }
                  else if (opt.equals("vl"))
                     options.verboseLayers = true;
                  else if (opt.equals("vh") || opt.equals("vha"))
                     Element.trace = true;
                  else if (opt.equals("vs"))
                     SyncManager.trace = true;
                  else if (opt.equals("vlck"))
                     options.verboseLocks = true;
                  else if (opt.equals("vsa")) {
                     SyncManager.trace = true;
                     SyncManager.traceAll = true;
                     // Includes JS that is sent to the browser due to changed source files
                     JSRuntimeProcessor.traceSystemUpdates = true;
                  }
                  else if (opt.equals("vsv")) {
                     SyncManager.trace = true;
                     SyncManager.traceAll = true;
                     // includes the page content and javascript output
                     SyncManager.verbose = true;
                     JSRuntimeProcessor.traceSystemUpdates = true;
                  }
                  else if (opt.equals("vp"))
                     PerfMon.enabled = true;
                  else if (opt.equals("v"))
                     traceNeedsGenerate = options.verbose = true;
                  else
                     usage("Unrecognized options: " + opt, args);
                  break;
               default:
                  usage("Unrecognized option: " + opt, args);
                  break;
            }
         }
         else {
            if (!includingFiles) {
               if (includeLayers == null)
                  includeLayers = new ArrayList<String>(args.length-1);

               includeLayers.add(args[i]);


               if (recursiveDynLayers != null)
                  recursiveDynLayers.add(args[i]);
            }
            else {
               if (includeFiles == null)
                  includeFiles = new ArrayList<String>();
               includeFiles.add(args[i]);
            }
         }
         if (restartArg) {
            int max;
            if (i == args.length)
               max = i - 1;
            else
               max = i;
            for (int j = lastRestartArg+1; j <= max; j++) {
               restartArgs.add(args[j]);
            }
         }
         lastRestartArg = i;
      }

      PerfMon.start("main", true, true);

      // Build layer is always the last layer in the list
      if (includeLayers != null) {
         buildLayerName = includeLayers.get(includeLayers.size()-1);
      }

      if (options.info) {
         StringBuilder sb = new StringBuilder();
         sb.append("Running: sc ");
         for (String varg:args) {
            sb.append(varg);
            sb.append(" ");
         }
         System.out.println(sb);
      }

      LayeredSystem sys = null;
      try {
         if (classPath == null) {
            classPath = System.getProperty("java.class.path");
            if (classPath != null && options.sysDetails)
               System.out.println("Initial system: java.class.path: " + classPath);
         }
         if (layerPath == null) {
            layerPath = System.getProperty("sc.layer.path");
            if (layerPath != null && options.verbose)
               System.out.println("Found system property: sc.layer.path: " + layerPath);
         }
         /*
         if (buildLayerName == null) {
            System.err.println("Missing layer name to command: " + StringUtil.argsToString(Arrays.asList(args)));
            System.exit(-1);
         }
         */
         if (recursiveDynLayers != null && recursiveDynLayers.size() == 0) {
            usage("The -dyn option was provided without a list of layers.  The -dyn option should be in front of the list of layer names you want to make dynamic.", args);
         }

         sys = new LayeredSystem(buildLayerName, includeLayers, recursiveDynLayers, layerPath, classPath, options, null, null, startInterpreter, null);
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
         System.out.println("Classpath: " + classPath);
      }
      Bind.info = options.info;

      sys.restartArgs = restartArgs;

      // First we need to fully build any buildSeparate layers for the main system.  Before we added preBuild we could rely on
      // build to do that ahead of time but now that's not the case.  At least it's symmetric that we make the same 3 passes
      // over the layered systems.
      if (!sys.buildSystem(includeFiles, false, true)) {
         System.exit(-1);
      }

      // And the buildSeparate pass for each extra runtime, e.g. JS
      if (sys.peerSystems != null) {
         for (LayeredSystem peer:sys.peerSystems) {
            if (!peer.buildSystem(includeFiles, false, true)) {
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
         success = sys.buildSystem(includeFiles, false, false);
         if (success) {
            if (sys.peerSystems != null) {
               for (LayeredSystem peer:sys.peerSystems) {
                  success = peer.buildSystem(includeFiles, false, false);
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
               sys.refreshSystem();
               if (sys.peerSystems != null) {
                  for (LayeredSystem peer:sys.peerSystems) {
                     peer.refreshSystem();
                  }
               }
               if (sys.peerSystems != null) {
                  for (LayeredSystem peer:sys.peerSystems) {
                     peer.refreshSystem();
                  }
               }
               sys.resetBuild(true);
            }
         }
      } while (!success);

      setCurrent(sys);

      // This will do any post-build processing such as generating static HTML files.  Only do it for the main runtime... otherwise, we'll do the .html files twice.
      // This has to be done after the peerSystems are built so we have enough information to peak into that runtime to get the list of JS files our app depends on.
      sys.initPostBuildModels();

      // Until this point, we have not started any threads or run anything else on the layered system but now we are about to
      // start the command interpreter so grab the global lock for write.   Before it prints it's prompt and starts looking
      // for commands, it will grab the global lock for read, just to do the prompt.   That ensures all of our main processing
      // is finished before we start running commands, and that the prompt is the last thing we output so it's visible.
      sys.acquireDynLock(false);

      PerfMon.start("runMain");
      try {
         Thread commandThread = null;
         if (startInterpreter) {

            // If we are adding a temporary layer, we can put it at the specified path.  If we are editing the
            // last layer, we can't switch the directory.  In this case, it is treated as relative to the layer's
            // directory.
            if (!editLayer) {
               sys.addTemporaryLayer(commandDirectory, false);
               commandDirectory = null;
            }

            if (commandDirectory != null)
               sys.cmd.path = commandDirectory;

            // Share the editor context with the gui if one is installed
            sys.editorContext = sys.cmd;

            if (sys.options.recordFile != null)
               sys.cmd.record(sys.options.recordFile);

            if (sys.options.createNewLayer)
               sys.cmd.createLayer();
            else if (sys.layers.size() == 0) {
               sys.cmd.askCreateLayer();
            }
            // TODO: we should hold up this thread until after the main thread has tripped a sentinel that it's been fully initialized
            if (options.runClass != null || options.testPattern != null) {
               commandThread = new Thread(sys.cmd);
               commandThread.setName("StrataCode command interpreter");
               commandThread.start();
            }
            else
               sys.cmd.readParseLoop();
         }

         // Run any unit tests before starting the program
         if (options.testPattern != null) {
            // First run the unit tests which match (i.e. those installed with the @Test annotation)
            Thread.currentThread().setContextClassLoader(sys.getSysClassLoader());
            sys.buildInfo.runMatchingTests(options.testPattern);
         }

         if (options.verbose) {
            sys.verbose("Prepared runtime: " + StringUtil.formatFloat((System.currentTimeMillis() - sys.sysStartTime)/1000.0));
         }

         if (options.runClass != null) {
            if (options.runFromBuildDir) {
               if (options.useCommonBuildDir)
                  System.setProperty("user.dir", sys.commonBuildDir);
               else
                  System.setProperty("user.dir", sys.buildDir);
            }
            // There are situations where we need to reset the class loader - i.e. we cannot use the layered class loaders
            // for a runtime application.   The problem is that some layers included Java classes at runtime look at their
            // own class loader to load other application classes that would be later in the classpath.  The compile time wants
            // to add jars as soon as possible and does it with layered classpaths.  That's fine for compiling but when, For example, 
            // tomcat's connection pool implementation will load a jdbc driver that is in a subsequent layer's classpath.
            // TODO should we have some way for layers to turn this on or off?  Most SC frameworks do not do this injected class
            // dependency thing and work fine without the rest.  It means we hav eto reload all classes.
            sys.resetClassLoader();
            if (commandThread != null)
               commandThread.setContextClassLoader(sys.getSysClassLoader());

            // first initialize and start all initOnStartup and createOnStartup objects
            // Note: this should be done via code generation in the main methods
            //sys.initStartupTypes();
            // Run any main methods that are defined (e.g. start the server)
            sys.runMainMethods(-1);
            // Run any scripts that are added to run (e.g. open the web browser
            sys.runRunCommands();
         }

         if (options.verbose) {
            sys.verbose("Run completed in: " + StringUtil.formatFloat((System.currentTimeMillis() - sys.sysStartTime)/1000.0));
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
      if (!startInterpreter) {
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
   }

   private boolean syncInited = false;

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
                  cmd.processCommand(str);
                  anyErrors = true;
                  break;
            }
         }

      } while (true);
   }

   // Called by any clients who need to use the LayeredSystem as a sync object
   public void initSync() {
      syncInited = true;
      SyncManager.SyncState old = SyncManager.getOldSyncState();
      try {
         SyncManager.setInitialSync("jsHttp", null, 0, true);
         // This part was originally copied from the code generated from the js version of LayeredSystem.  Yes, we could add the meta-data here and introduce a dependency so SC is required to build SC but still not willing to take that leap of tooling complexity yet :)
         // See also the lines in the class Options innner class.
         int globalScopeId = GlobalScopeDefinition.getGlobalScopeDefinition().scopeId;
         SyncManager.addSyncType(LayeredSystem.class, new SyncProperties(null, null, new Object[] { "options" , "staleCompiledModel"}, null, SyncOptions.SYNC_INIT_DEFAULT, globalScopeId));
         SyncManager.addSyncType(LayeredSystem.Options.class, new SyncProperties(null, null,
                  new Object[] { "buildAllFiles" , "buildAllLayers" , "noCompile" , "verbose" , "info" , "debug" , "crossCompile" , "runFromBuildDir" , "runScript" ,
                                 "createNewLayer" , "dynamicLayers" , "allDynamic" , "liveDynamicTypes" , "useCommonBuildDir" , "buildDir" , "buildSrcDir" ,
                                  "recordFile" , "restartArgsFile" , "compileOnly" }, null, SyncOptions.SYNC_INIT_DEFAULT, globalScopeId));
         SyncProperties typeProps = new SyncProperties(null, null, new Object[] { "typeName" , "fullTypeName", "layer", "packageName" , "dynamicType" , "isLayerType" ,
                                                                                  "declaredProperties", "declarationType", "comment" , "clientModifiers", "existsInJSRuntime"},
                                                       null, SyncOptions.SYNC_INIT_DEFAULT, globalScopeId);

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

         SyncManager.addSyncType(VariableDefinition.class, new SyncProperties(null, null, new Object[] { "variableName" , "initializerExprStr" , "operatorStr" , "layer", "comment", "variableTypeName"}, null, SyncOptions.SYNC_INIT_DEFAULT | SyncOptions.SYNC_CONSTANT, globalScopeId));
         SyncManager.addSyncType(PropertyAssignment.class, new SyncProperties(null, null, new Object[] { "propertyName" , "operatorStr", "initializerExprStr", "layer" , "comment" }, null, SyncOptions.SYNC_INIT_DEFAULT | SyncOptions.SYNC_CONSTANT, globalScopeId));

         SyncProperties modelProps = new SyncProperties(null, null, new Object[] {"layer", "srcFile", "needsModelText", "cachedModelText", "needsGeneratedText", "cachedGeneratedText", "cachedGeneratedJSText", "cachedGeneratedSCText", "cachedGeneratedClientJavaText", "existsInJSRuntime", "layerTypeDeclaration"}, null, SyncOptions.SYNC_INIT_DEFAULT, globalScopeId);
         SyncManager.addSyncType(JavaModel.class, modelProps);
         SyncManager.addSyncType(SCModel.class, modelProps);
         SyncManager.addSyncType(Template.class, modelProps);
         SyncManager.addSyncType(SrcEntry.class, new SyncProperties(null, null, new Object[] { "layer", "absFileName", "relFileName", "baseFileName", "prependPackage" }, null, SyncOptions.SYNC_INIT_DEFAULT | SyncOptions.SYNC_CONSTANT, globalScopeId));
         SyncManager.addSyncInst(this, true, true, null);
         SyncManager.addSyncInst(options, true, true, null);

         SyncManager.addSyncHandler(ParamTypedMember.class, LayerSyncHandler.class);
         SyncManager.addSyncHandler(ParamTypeDeclaration.class, LayerSyncHandler.class);
         SyncManager.addSyncHandler(Field.class, LayerSyncHandler.class);
         SyncManager.addSyncHandler(Method.class, LayerSyncHandler.class);
         SyncManager.addSyncHandler(MethodDefinition.class, LayerSyncHandler.class);
         SyncManager.addSyncHandler(BeanMapper.class, LayerSyncHandler.class);
         SyncManager.addSyncHandler(BeanIndexMapper.class, LayerSyncHandler.class);
         SyncManager.addSyncHandler(Template.class, LayerSyncHandler.class);
         SyncManager.addSyncHandler(Layer.class, LayerSyncHandler.class);

         SyncManager.addSyncType(Layer.class,
                 new SyncProperties(null, null,
                         new Object[]{"packagePrefix", "defaultModifier", "dynamic", "hidden", "compiledOnly", "transparent",
                                 "baseLayerNames", "layerPathName", "layerBaseName", "layerDirName", "layerUniqueName",
                                 "layerPosition", "codeType", "codeFunction", "dependentLayers"},
                         null, SyncOptions.SYNC_INIT_DEFAULT,  globalScopeId));
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
         lii.layer.initSync();;
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

         if ((!changedOnly || !layer.compiled) && (result = startChangedModels(layer, includeFiles, phase, separateLayersOnly)) != GenerateCodeStatus.Error) {
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

         if ((!newLayersOnly || !layer.compiled) && (!separateLayersOnly || layer.buildSeparate) && (result = generateCode(layer, includeFiles, phase, separateLayersOnly)) != GenerateCodeStatus.Error) {
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
            }
         }
      }
      return true;
   }

   /** Builds the system.  The newLayersOnly option looks for changed files only.  separateLayersOnly will only build layers with the buildSeparate=true option.  You can specify a list of files to build if you know exactly what needs to be processed and transformed to rebuild the system (not recommended). */
   boolean buildSystem(List<String> includeFiles, boolean newLayersOnly, boolean separateLayersOnly) {
      setCurrent(this);

      if (buildStartTime == -1) {
         // Reset the time just before we make a pass through all of the layers so we pick up any subsequent changes
         lastRefreshTime = System.currentTimeMillis();
         buildStartTime = lastRefreshTime;
      }

      try {
         buildingSystem = true;
         if (buildLayer == null) {
            if (options.verbose)
               System.out.println("No compiled layers");
         }
         else {
            if (!buildLayersIfNecessary(layers.size(), includeFiles, newLayersOnly, separateLayersOnly))
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

                  if (options.verbose)
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

      return true;
   }

   void buildCompleted(boolean doPeers) {
      systemCompiled = true;
      changedModels.clear();
      processedModels.clear();

      // Clear out any build state for any layers to reset for the next build
      for (int i = 0; i < layers.size(); i++) {
         Layer layer = layers.get(i);
         layer.buildState = null;
      }

      if (doPeers && peerSystems != null) {
         for (LayeredSystem peerSys: peerSystems) {
            peerSys.buildCompleted(false);
         }
      }
   }

   /** Models such as schtml templates need to run after the entire system has compiled - the postBuild phase. */
   private void initPostBuildModels() {
      Thread.currentThread().setContextClassLoader(getSysClassLoader());

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

   void runMainMethod(String runClass, String[] runClassArgs, int lowestLayer) {
      Object type = getRuntimeTypeDeclaration(runClass);
      if (lowestLayer != -1) {
         if (type instanceof TypeDeclaration) {
            Layer typeLayer = ((TypeDeclaration) type).getLayer();
            // Already ran this one before
            if (typeLayer.layerPosition <= lowestLayer)
               return;
         }
         else
            return;
      }
      runMainMethod(type, runClass, runClassArgs);
   }

   void runMainMethod(String runClass, String[] runClassArgs, List<Layer> theLayers) {
      Object type = getRuntimeTypeDeclaration(runClass);
      if (theLayers != null) {
         if (type instanceof TypeDeclaration) {
            Layer typeLayer = ((TypeDeclaration) type).getLayer();
            // Already ran this one before or the layer is not part of the current layers
            if (!theLayers.contains(typeLayer) || !typeLayer.activated)
               return;
         }
         else
            return;
      }
      runMainMethod(type, runClass, runClassArgs);
   }

   void runMainMethod(Object type, String runClass, String[] runClassArgs) {
      if (runtimeProcessor != null) {
         runtimeProcessor.runMainMethod(type, runClass, runClassArgs);
         return;
      }
      Object[] args = new Object[] {processCommandArgs(runClassArgs)};
      if (type != null && ModelUtil.isDynamicType(type)) {
         Object meth = ModelUtil.getMethod(type, "main", null, null, false, RTypeUtil.MAIN_ARG);
         if (!ModelUtil.hasModifier(meth, "static"))
            System.err.println("*** Main method missing 'static' modifier: " + runClass);
         else {
            if (options.info)
               System.out.println("Running dynamic: " + runClass);
            runClassStarted = true;
            ModelUtil.callMethod(null, meth, args);
         }
      }
      else if (type instanceof Class && IDynObject.class.isAssignableFrom((Class) type)) {
      }
      else {
         Object rc = getCompiledClass(runClass);
         if (rc == null) {
            System.err.println("*** Can't find main class to run: " + runClass);
            return;
         }
         Class rcClass = (Class) rc;
         Method meth = RTypeUtil.getMethod(rcClass, "main", RTypeUtil.MAIN_ARG);
         if (!PTypeUtil.hasModifier(meth, "static"))
            System.err.println("*** Main method missing 'static' modifier: " + runClass);
         else {
            if (options.info)
               System.out.println("Running compiled: " + runClass);
            runClassStarted = true;
            TypeUtil.invokeMethod(null, meth, args);
         }
      }
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
      if ((stat = LayerUtil.execCommand(pb, null)) != 0)
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
         ix++;
      } while (true);
      Layer layer = new Layer();
      layer.layeredSystem = this;
      layer.layerDirName = "temp." + TEMP_LAYER_BASE_NAME + ix;
      layer.layerBaseName = baseName;
      layer.layerPathName = pathName;
      layer.dynamic = !getCompiledOnly();
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
      addLayer(layer, null, runMain, false, true, true, false, true, paramInfo);
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
               jmodel.replacesModel.completeUpdateModel(jmodel);
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
            TypeDeclaration type = getCachedTypeDeclaration(typeName, null, null, false, true);

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
         ArrayList<ModelToUpdate> res = new ArrayList<ModelToUpdate>();
         // Iterate through all new layers (either this one or a new one extended by this one)
         // For any modified types, see if there are instances of that type.  If so, update them
         // based on the changes made by the modify declaration.
         for (int i = 0; i < theLayers.size(); i++) {
            Layer newLayer = theLayers.get(i);

            updateLayer(newLayer, res, ctx);
         }

         return res;
      }
      return null;
   }

   private ArrayList<ModelToUpdate> updateLayers(int newLayerPos, ExecutionContext ctx) {
      if (options.liveDynamicTypes && ctx != null) {
         ArrayList<ModelToUpdate> res = new ArrayList<ModelToUpdate>();
         // Iterate through all new layers (either this one or a new one extended by this one)
         // For any modified types, see if there are instances of that type.  If so, update them
         // based on the changes made by the modify declaration.
         for (int i = newLayerPos; i < layers.size(); i++) {
            Layer newLayer = layers.get(i);

            updateLayer(newLayer, res, ctx);
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
            refresh(mt.srcEnt, mt.oldModel, ctx, updateInfo);
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

      // Assumes we are processing layers from base to last.  We first gather up the complete list of models
      // we need to replace - the one to remove and the new one (which is really the base one in this case)
      for (int li = 0; li < toRemLayers.size(); li++) {
         Layer toRem = toRemLayers.get(li);

         if (toRem.layerPosition < pos) {
            System.err.println("*** Error: remove layers received layers out of order!");
            return;
         }
         pos = toRem.layerPosition;

         if (toRem.layerPosition > layers.size() || layers.get(toRem.layerPosition) != toRem) {
            System.err.println("*** removeLayers called with layer which is not active: " + toRem);
            return;
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

         layers.remove(toRem.layerPosition - deletedCount);
         deletedCount++;
         if (toRem == buildLayer) {
            resetBuildLayer = true;
         }
         deregisterLayer(toRem, true);

         // When we remove a layer that was specified on the command line, we need to replacd it with any base layers (unless those were also removed).  This, along with code that adds new layers to the command line preserves restartability.
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
      if (resetBuildLayer) {

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
            if (toCheck.origBuildLayer.removed) {
               toCheck.origBuildLayer = buildLayer;
               toCheck.buildClassesDir = buildClassesDir;
               toCheck.sysBuildSrcDir = buildSrcDir;
            }
         }
      }

      // Renumber any layers which are after the one we removed
      for (int i = toRemLayers.get(0).layerPosition; i < layers.size(); i++) {
         Layer movedLayer = layers.get(i);
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
               lastModel.updateModel(newModel, ctx, TypeUpdateMode.Remove, true, updateInfo);
               lastModel.completeUpdateModel(newModel);
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

      updateInfo.updateInstances(ctx);

      for (Layer l:toRemLayers) {
         for (IModelListener ml: modelListeners) {
            ml.layerRemoved(l);
         }
      }
   }

   public void addLayer(Layer layer, ExecutionContext ctx, boolean runMain, boolean setPackagePrefix, boolean saveModel, boolean makeBuildLayer, boolean build, boolean isActive, LayerParamInfo lpi) {
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

            if (makeBuildLayer && isActive)
               updateBuildDir(oldBuildLayer);
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
               addNewModel(layer.model, null, null, true);
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
                  refresh(mt.srcEnt, mt.oldModel, ctx, updateInfo);
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
         layerName = layerName.replace(FileUtil.FILE_SEPARATOR, ".");
         String rootFile = getNewLayerDir();
         String pathName;
         String baseName;
         pathName = FileUtil.concat(rootFile, layerSlashName);
         baseName = FileUtil.getFileName(pathName) + SCLanguage.STRATACODE_SUFFIX;
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
                     addLayer(l, null, false, false, false, false, false, isActive, paramInfo);
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
         addLayer(layer, null, true, true, saveLayer, true, buildBaseLayers, isActive, paramInfo);

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


   private static void usage(String reason, String[] args) {
      if (reason.length() > 0)
         System.err.println(reason);
      usage(args);
   }

   private static void usage(String[] args) {
      System.err.println("Command line overview:\n" + "sc [-a -i -ni -nc -dyn -cp <classpath> -lp <layerPath>]\n" +
                         "   [ -cd <defaultCommandDir/Path> ] [<layer1> ... <layerN-1>] <buildLayer>\n" +
                         "   [ -f <file-list> ] [-r <main-class-regex> ...app options...] [-t <test-class-regex>]\n" +
                         "   [ -d/-ds/-db buildOrSrcDir]\n\nOption details:");
      System.err.println("   [ -a ]: build all files\n   [ -i ]: create temporary layer for interpreter\n   [ -nc ]: generate but don't compile java files");
      System.err.println("   <buildLayer>:  The build layer is the last layer in your stack.\n" +
                         "   [ -dyn ]: Layers specified after -dyn (and those they extend) are made dynamic unless they are marked: 'compiledOnly'\n" +
                         "   [ -c ]: Generate and compile only - do not run any main methods\n" +
                         "   [ -v ]: Verbose info about the layered system.  [-vb ] [-vba] Trace data binding (or trace all) [-vs] [-vsa] [-vsv] Trace the sync system (or verbose and trace all)\n" +
                         "   [ -vh ]: verbose html [ -vl ]: display initial layers [ -vp ] turn on performance monitoring " +
                         "   [ -f <file-list>]: Process/compile only these files\n" +
                         "   [ -cp <classPath>]: Use this classpath for resolving compiled references.\n" +
                         "   [ -lp <layerPath>]: Set of directories to search in order for layer directories.\n" +
                         "   [ -db,-ds,-d <buildOrSrcDir> ]: override the buildDir, buildSrcDir, or both of the buildLayer's buildDir and buildSrcDir properties \n" +
                         "   [ -r/-rs <main-class-pattern> ...all remaining args...]: After compilation, run all main classes matching the java regex with the rest of the arguments.  -r: run in the same process.  -rs: exec the generated script in a separate process.\n" +
                         "   [ -ra ... remaining args passed to main methods... ]: Run all main methods\n" +
                         "   [ -t <test-class-pattern>]: After compilation, run matching tests.\n" +
                         "   [ -o <pattern> ]: Sets the openPattern, used by frameworks to choose which page to open after startup.\n" +
                         "   [ -ta ]: Run all tests.\n" +
                         "   [ -nw ]: For web frameworks, do not open the default browser window.\n" +
                         "   [ -n ]: Start 'create layer' wizard on startup.\n" +
                         "   [ -ni ]: Disable command interpreter\n" +
                         "   [ -ndbg ]: Do not compile Java files with debug enabled\n" +
                         "   [ -dt ]: Enable the liveDynamicTypes option - so that you can modify types at runtime.  This is turned when the editor is enabled by default but you can turn it on with this option.\n" +
                         "   [ -nd ]: Disable the liveDynamicTypes option - so that you cannot modify types at runtime.  This is turned when the editor is enabled by default but you can turn it on with this option.\n" +
                         "   [ -ee ]: Edit the editor itself - when including the program editor, do not exclude it's source from editing.\n" +
                         "   [ -cd <default-interpreter-type>]: For -i, sets the root-type the interpreter can access\n\n" +
                         StringUtil.insertLinebreaks(AbstractInterpreter.USAGE, 80));
      System.exit(-1);
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
         layerTypeName = layerPathName.replace(FileUtil.FILE_SEPARATOR, ".");
         /* For flexibility, allow users to specify the package prefix or not */
      }

      String layerBaseName = CTypeUtil.getClassName(layerTypeName) + SCLanguage.STRATACODE_SUFFIX;

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
         if (specified && !specifiedLayers.contains(l) && l != null)
            specifiedLayers.add(l);
         lpi.createdLayers.add(l);
      }
      return layers;
   }

   private Layer findLayerInPath(String layerPathName, String relDir, String relPath, boolean markDynamic, LayerParamInfo lpi) {
      // defaults to using paths relative to the current directory
      if (layerPathDirs == null || relDir != null)
         return findLayer(relDir == null ? "." : relDir, layerPathName, relDir, relPath, markDynamic, lpi);

      for (File pathDir:layerPathDirs) {
         Layer l = findLayer(pathDir.getPath(), layerPathName, null, relPath, markDynamic, lpi);
         if (l != null)
            return l;
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

      String layerBaseName = CTypeUtil.getClassName(layerTypeName) + SCLanguage.STRATACODE_SUFFIX;

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
            layerTypeName = layerTypeName.replace("/", ".");
            layerGroup = CTypeUtil.getPackageName(layerTypeName);
            if (layerDir.equals(layerFileName))
               layerTypeName = CTypeUtil.prefixPath(layerTypeName, FileUtil.getFileName(layerFileName));
            layerPathPrefix = null;
         }
         else {
            layerTypeName = FileUtil.getFileName(layerFileName);
            layerPathPrefix = null;
            layerGroup = "";
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
         layerPathName = layerPathName.replace(FileUtil.FILE_SEPARATOR, ".");
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

      String layerBaseName = CTypeUtil.getClassName(layerTypeName) + SCLanguage.STRATACODE_SUFFIX;

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
         Layer inactiveLayer = lookupInactiveLayer(layerPathName.replace("/", "."), false, false);
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

         SrcEntry defSrcEnt = new SrcEntry(layer, layerDefFile, FileUtil.concat(layerGroup, layerBaseName));

         // Parse the model, validate it defines an instance of a Layer
         Layer newLayer = (Layer) loadLayerObject(defSrcEnt, Layer.class, layerTypeName, layerPrefix, relDir, layerPathPrefix, inLayerDir, markDynamic, layerPathName, lpi);

         // No matter what, get it out since the name name have changed or maybe we did not look it up.
         pendingLayers.remove(layerTypeName);

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
            int numLayers = layers.size();

            // Make sure any layers which have features which this layer replaces are updated to know that this layer is present.
            layer.initReplacedLayers();

            // This is just a nicety but since compiledLayers tend to be framework layers, it's best to put them
            // in front of those that can be made dynamic.  That also means they won't jump positions when going
            // from compiled to dynamic.  The stack is in general more readable when framework layers are at the base.
            int sortPriority = layer.getSortPriority();
            if (sortPriority != Layer.DEFAULT_SORT_PRIORITY) {
               int resortIx = -1;
               for (int prevIx = numLayers - 1; prevIx >= 0; prevIx--) {
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
         }
      }
      else {
         registerInactiveLayer(layer);
      }

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
      notifyLayerAdded(layer);

      checkLayerPosition();
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
         origLayerPathName = origLayerPathName.replace(".",FileUtil.FILE_SEPARATOR);
         String layerBaseName = FileUtil.addExtension(FileUtil.getFileName(origLayerPathName), SCLanguage.STRATACODE_SUFFIX.substring(1));
         String layerFileName = FileUtil.concat(origLayerPathName, layerBaseName);
         File layerPathFile = new File(layerFileName);
         if (lpi.activate)
            System.err.println("No layer definition file: " + layerPathFile.getPath() + (layerPathFile.isAbsolute() ? "" : " (at full path " + layerPathFile.getAbsolutePath() + ")"));
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

   public Layer getLayerByName(String layerName) {
      return layerIndex.get(layerName);
   }

   /** layer package prefix + layer name for the UI lookup */
   public Layer getLayerByTypeName(String layerTypeName) {
      return layerFileIndex.get(layerTypeName);
   }

    /** Uses the normalizes path name, i.e. "/" even on windows */
   public Layer getLayerByPath(String layerPath, boolean checkPeers) {
      String layerType = layerPath.replace("/", ".");
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


   public String getClassPathForLayer(Layer startLayer, String useBuildDir) {
      StringBuilder sb = new StringBuilder();
      boolean addOrigBuild = true;
      sb.append(useBuildDir); // Our build dir overrides all other directories
      if (startLayer == coreBuildLayer) {
         addOrigBuild = startLayer.appendClassPath(sb, useBuildDir, addOrigBuild);
      }
      else {
         for (int i = startLayer.layerPosition; i >= 0; i--) {
            Layer layer = layers.get(i);
            addOrigBuild = layer.appendClassPath(sb, useBuildDir, addOrigBuild);
         }
      }
      if (addOrigBuild && origBuildDir != null) {
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
      return sb.toString() + FileUtil.PATH_SEPARATOR + rootClassPath;
   }

   public boolean isValidLayersDir(String dirName) {
      String layerPathFileName = FileUtil.concat(dirName, SC_DIR, LAYER_PATH_FILE);
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
         String scDirName = FileUtil.concat(dirName, SC_DIR);
         // TODO: picking the last directory in the layer path because right now that's how IntelliJ orders them and it seems
         // like a potentially 'too powerful' idea to let someone replace a layer in a subsequent layer path, for the same reasons Java makes it so hard to replace a class
         // You can just replace the types to fix the layer if you need to in an incremental way.
         // Down the road we could reverse this decision to make it consistent.
         if (new File(scDirName).isDirectory()) {
            mainLayerDir = dirName;
         }
         String layerPathFileName = FileUtil.concat(dirName, SC_DIR, LAYER_PATH_FILE);
         File layerPathFile = new File(layerPathFileName);
         if (layerPathFile.canRead()) {
            // As soon as we find one .layerPath file, consider the system installed
            systemInstalled = true;
            // Include any new entries in the .layerPath file into the layer path
            String layerPath = FileUtil.getFileAsString(layerPathFileName);
            if (layerPath != null && !layerPath.trim().equals(".")) {
               String[] newLayerPaths = layerPath.split(":");
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
         List dirNameList = Arrays.asList(dirNames);
         layerPathDirs = new ArrayList<File>(dirNames.length);
         for (String d:dirNames) {
            addLayerPathDir(d);
         }
      }
      else {
         String layerPathFileName = FileUtil.concat(".", SC_DIR, LAYER_PATH_FILE);
         File layerPathFile = new File(layerPathFileName);
         if (layerPathFile.canRead()) {
            systemInstalled = true;
         }
         else {
            layerPathFileName = FileUtil.concat("layers", SC_DIR, LAYER_PATH_FILE);
            layerPathFile = new File(layerPathFileName);
            // This is the case where have installed into the StrataCode dist directory
            if (layerPathFile.canRead()) {
               systemInstalled = true;
               if (layerPath == null) {
                  String currentDir = System.getProperty("user.dir");
                  mainLayerDir = newLayerDir = FileUtil.concat(currentDir, "layers");
                  layerPath = newLayerDir;
                  layerPathDirs = new ArrayList<File>();
                  layerPathDirs.add(new File(layerPath));
               }
            }
            else {
               String currentDir = System.getProperty("user.dir");
               if (currentDir != null) {
                  // If we are in the bin directory, check for the layers up and over one level
                  if (FileUtil.getFileName(currentDir).equals("bin")) {
                     layerPathFileName = FileUtil.concat("..", "layers", ".layerPath");
                     layerPathFile = new File(layerPathFileName);
                     if (layerPathFile.canRead()) {
                        systemInstalled = true;
                        if (layerPath == null) {
                           // newLayerDir needs to be absolute
                           String newDir = getLayersDirFromBinDir();
                           if (newDir != null) {
                              mainLayerDir = newLayerDir = newDir;
                              layerPath = newLayerDir;
                              layerPathDirs = new ArrayList<File>();
                              layerPathDirs.add(new File(layerPath));
                           }
                        }
                     }
                  }
                  // We may be inside of a layer directory in the layer path.  We'll figure that out later on but for now just
                  // find the .layerPath above us so we do not install incorrectly.
                  else {
                     File currentFile = new File(currentDir);
                     String parentName;
                     File binDir = new File(currentFile, "bin");
                     if (binDir.isDirectory()) {
                        File scJarFile = new File(binDir, "sc.jar");
                        // When running from the StrataCode dist directory, we put the results in 'layers' mostly because
                        // git needs an empty directory to start from.
                        if (scJarFile.canRead()) {
                           layersFilePathPrefix = "layers";
                        }
                     }
                     else {
                        // Perhaps running from the bin directory
                        String parentDir = FileUtil.getParentPath(currentDir);
                        if (parentDir != null) {
                           binDir = new File(parentDir, "bin");
                           if (binDir.isDirectory()) {
                              File scJarFile = new File(binDir, "sc.jar");
                              if (scJarFile.canRead()) {
                                 layersFilePathPrefix = ".." + FileUtil.FILE_SEPARATOR + "layers";
                              }
                           }
                        }
                     }

                     do {
                        parentName = currentFile.getParent();
                        if (parentName != null) {
                           layerPathFile = new File(FileUtil.concat(parentName, SC_DIR));
                           if (layerPathFile.isDirectory()) {
                              systemInstalled = true;
                              // Need to at least install it in the right place
                              if (layerPath == null) {
                                 mainLayerDir = newLayerDir = parentName;
                                 layerPath = parentName;
                                 layerPathDirs = new ArrayList<File>();
                                 layerPathDirs.add(new File(layerPath));
                              }
                           }
                           else
                              currentFile = new File(parentName);
                        }
                     } while (parentName != null && !systemInstalled);
                  }
               }
            }
         }
      }
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
      String undoDirPath = FileUtil.concat(mainLayerDir, ".undo");
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
         if (list[i].startsWith(javaHome))
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
   private static Set<String> systemClasses = new HashSet<String>();
   /* Generated from a code snippet above */
   private static String [] systemClassNames = {
      "CharSequence",
      "EnumConstantNotPresentException",
      "SecurityException",
      "Runnable",
      "Math",
      "Short",
      "Comparable",
      "InternalError",
      "Compiler",
      "IncompatibleClassChangeError",
      "Thread",
      "InterruptedException",
      "Long",
      "ProcessImpl",
      "Cloneable",
      "ProcessEnvironment",
      "UNIXProcess",
      "InstantiationError",
      "StackTraceElement",
      "Byte",
      "ClassNotFoundException",
      "Class",
      "IndexOutOfBoundsException",
      "Throwable",
      "Double",
      "NoSuchMethodException",
      "StackOverflowError",
      "Deprecated",
      "StringIndexOutOfBoundsException",
      "ThreadGroup",
      "NumberFormatException",
      "Object",
      "UnsatisfiedLinkError",
      "Float",
      "StringCoding",
      "IllegalStateException",
      "StringValue",
      "CharacterDataUndefined",
      "Character",
      "LinkageError",
      "Terminator",
      "VerifyError",
      "ClassCircularityError",
      "IllegalAccessError",
      "NoSuchFieldException",
      "ClassFormatError",
      "NoClassDefFoundError",
      "Enum",
      "Process",
      "UnsupportedClassVersionError",
      "TypeNotPresentException",
      "AbstractMethodError",
      "StringBuilder",
      "Shutdown",
      "Number",
      "Runtime",
      "SystemClassLoaderAction",
      "RuntimeException",
      "InheritableThreadLocal",
      "AbstractStringBuilder",
      "UnsupportedOperationException",
      "String",
      "ArithmeticException",
      "ThreadDeath",
      "ClassCastException",
      "IllegalThreadStateException",
      "Exception",
      "IllegalArgumentException",
      "ExceptionInInitializerError",
      "StrictMath",
      "Void",
      "CloneNotSupportedException",
      "VirtualMachineError",
      "Iterable",
      "CharacterData0E",
      "SecurityManager",
      "ConditionalSpecialCasing",
      "Package",
      "System",
      "ApplicationShutdownHooks",
      "ThreadLocal",
      "ClassLoader",
      "RuntimePermission",
      "ArrayStoreException",
      "AssertionError",
      "SuppressWarnings",
      "IllegalMonitorStateException",
      "AssertionStatusDirectives",
      "NegativeArraySizeException",
      "CharacterData02",
      "CharacterDataLatin1",
      "OutOfMemoryError",
      "CharacterData01",
      "CharacterData00",
      "Boolean",
      "ProcessBuilder",
      "Error",
      "NoSuchMethodError",
      "NullPointerException",
      "InstantiationException",
      "UnknownError",
      "IllegalAccessException",
      "Readable",
      "Override",
      "StringBuffer",
      "CharacterDataPrivateUse",
      "Integer",
      "NoSuchFieldError",
      "Appendable",
      "ArrayIndexOutOfBoundsException",
      "FunctionalInterface",
      "SafeVarargs",
      "ClassValue",
      "AutoCloseable",
      "BootstrapMethodError",
      "ReflectiveOperationException"
   };
   static {
      for (String cl:systemClassNames)
         systemClasses.add(cl);
   }

   public Set<String> getFilesInPackage(String packageName) {
      if (packageName == null)
         packageName = "";
      packageName = packageName.replace(".", FileUtil.FILE_SEPARATOR);
      HashMap<String,PackageEntry> pkgEnt = packageIndex.get(packageName);
      if (pkgEnt == null)
         return null;
      return packageIndex.get(packageName).keySet();
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
      if (packageIndex.get("java/awt") == null) {
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
      File typeIndexDir = new File(getTypeIndexDir());
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
         typeIndex = new SysTypeIndex(this);

      if (!peerMode && peerSystems != null)
         for (LayeredSystem peerSys:peerSystems)
            peerSys.initTypeIndexDir();
   }

   private void saveTypeIndexFiles() {
      for (int i = 0; i < inactiveLayers.size(); i++) {
         Layer inactiveLayer = inactiveLayers.get(i);
         inactiveLayer.saveTypeIndex();
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

   /**
    * The internal method to load the main type index.  In general, we do not need to be called with the dyn lock and
    * it's best if you avoid doing that because this can take a long time when rebuilding the index from scratch.
    */
   private void loadTypeIndex(Set<String> refreshedLayers) {
      File typeIndexMainDir = new File(getStrataCodeDir("idx"));

      if (!typeIndexMainDir.isDirectory()) {
         typeIndexMainDir.mkdirs();
         return;
      }

      String[] runtimeDirNames = typeIndexMainDir.list();

      if (peerMode)
         System.err.println("*** TypeIndex should only be loaded in the main layeredSystem");

      // A simple guard to prevent this from being done twice - the first one to create the process map should init the index.
      acquireDynLock(false);
      try {
         if (typeIndexProcessMap == null) {
            typeIndexProcessMap = new HashMap<String, SysTypeIndex>();
            if (typeIndex == null) {
               typeIndex = new SysTypeIndex(this);
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
            System.err.println("*** Unable to access typeIndex directory: " + typeIndexDir.getPath());
            return;
         }
         HashMap<String,Boolean> filesToProcess = new HashMap<String,Boolean>();
         for (String file:fileNames)
            filesToProcess.put(file,Boolean.FALSE);

         LayeredSystem curSys = findSystemFromTypeIndexIdent(typeIndexIdent);

         SysTypeIndex curTypeIndex = typeIndexProcessMap.get(typeIndexIdent);
         if (curTypeIndex == null && curSys != null && curSys.typeIndex != null) {
            curTypeIndex = curSys.typeIndex;
            typeIndexProcessMap.put(typeIndexIdent, curTypeIndex);
         }
         if (curTypeIndex == null) {
            typeIndexProcessMap.put(typeIndexIdent, curTypeIndex = new SysTypeIndex(curSys));
         }
         if (curSys != null && curSys.typeIndex == null)
            curSys.typeIndex = curTypeIndex;

         ArrayList<Layer> layersToRebuild = new ArrayList<Layer>();
         for (String indexFileName:filesToProcess.keySet()) {
            if (!filesToProcess.get(indexFileName)) {
               if (indexFileName.endsWith(Layer.TYPE_INDEX_SUFFIX)) {
                  String uname = indexFileName.substring(0, indexFileName.length() - Layer.TYPE_INDEX_SUFFIX.length());
                  String layerName = Layer.getLayerNameFromUniqueUnderscoreName(uname);

                  Layer layer = curSys == null ? null : curSys.lookupLayerSync(layerName, false, true);
                  if (layer != null) {
                     LayeredSystem layerSys = layer.layeredSystem;
                     layerSys.acquireDynLock(false);
                     try {
                        SysTypeIndex sysIdx = layerSys.typeIndex;
                        LayerListTypeIndex idx = layer.activated ? sysIdx.activeTypeIndex : sysIdx.inactiveTypeIndex;
                        if (writeLocked == 0) {
                           System.err.println("*** Modifying type index without write lock");
                           new Throwable().printStackTrace();
                        }
                        idx.typeIndex.put(layerName, layer.layerTypeIndex);
                        refreshedLayers.add(layerName);
                     }
                     finally {
                        layerSys.releaseDynLock(false);
                     }
                  }
                  else {
                     acquireDynLock(false);
                     try {
                        LayerTypeIndex layerTypeIndex = readTypeIndexFile(typeIndexIdent, layerName);
                        if (layerTypeIndex == null) {
                           // ?? huh we just listed out the file - rebuild it from scratch.
                           addLayerToRebuild(layerName, layersToRebuild);
                           refreshedLayers.add(layerName);
                        } else {
                           switch (options.typeIndexMode) {
                              case Refresh:
                                 layerTypeIndex = refreshLayerTypeIndex(layerName, layerTypeIndex, new File(FileUtil.concat(typeIndexDir.getPath(), indexFileName)).lastModified());
                                 refreshedLayers.add(layerName);
                                 break;
                              default:
                                 break;
                           }
                        }
                        if (writeLocked == 0) {
                           System.err.println("*** Modifying type index without write lock");
                           new Throwable().printStackTrace();
                        }
                        if (layerTypeIndex != null)
                           curTypeIndex.inactiveTypeIndex.typeIndex.put(layerName, layerTypeIndex);
                        else
                           curTypeIndex.inactiveTypeIndex.typeIndex.remove(layerName);
                        if (layerTypeIndex != null && layerTypeIndex.langExtensions != null)
                           customSuffixes.addAll(Arrays.asList(layerTypeIndex.langExtensions));

                        // The process of getting a layer may have created this runtime so use it as soon as it becomes available.
                        if (curSys == null)
                           curSys = findSystemFromTypeIndexIdent(typeIndexIdent);
                     }
                     finally {
                        releaseDynLock(false);
                     }
                  }
               }
            }
            rebuildLayersTypeIndex(layersToRebuild);
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

   private void refreshTypeIndex(Set<String> refreshedLayers) {
      // If we have Refresh or Rebuild modes - walk through all layers in each layer path.  If no index, build it.
      switch (options.typeIndexMode) {
         case Refresh:
         case Rebuild:
            // First we batch up all layers we need to index - they are initialized via the getInactiveLayer method
            // and that way all of the layer components will override each other.  Then we rebuild all of the indexes
            // once the entire layer stack is formed.
            ArrayList<Layer> layersToRebuild = new ArrayList<Layer>();
            Map<String,LayerIndexInfo> allLayers = getAllLayerIndex();
            for (LayerIndexInfo idx : allLayers.values()) {
               String layerName = idx.layerDirName;
               if (idx.layer != null && !idx.layer.disabled) {
                  if (options.typeIndexMode == TypeIndexMode.Rebuild || !refreshedLayers.contains(layerName))
                     addLayerToRebuild(layerName, layersToRebuild);
               }
            }
            rebuildLayersTypeIndex(layersToRebuild);
            break;
      }
   }

   /** This is called on the main layered system.  It will enable the type index on all peer systems.  */
   public void initTypeIndex() {
      Set<String> refreshedLayers = new TreeSet<String>();

      loadTypeIndex(refreshedLayers);

      refreshTypeIndex(refreshedLayers);

      buildReverseTypeIndex();

      saveTypeIndexFiles();

      cleanInactiveCache();
   }

   private void cleanInactiveCache() {
      try {
         acquireDynLock(false);
         ArrayList<String> toCullList = new ArrayList<String>();
         for (Map.Entry<String, ILanguageModel> cacheEnt: inactiveModelIndex.entrySet()) {
            ILanguageModel model = cacheEnt.getValue();

            Object userData = model.getUserData();

            if (externalModelIndex == null || !externalModelIndex.isInUse(model)) {
               toCullList.add(cacheEnt.getKey());
            }
         }
         for (String toCull:toCullList) {
            ILanguageModel removed = inactiveModelIndex.remove(toCull);
            if (removed != null) {
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
   }

   /**
    * Refresh the layer type index - look for files that exist on the file system that are not in the index and
    * files which have changed.  This is called on the main layered system since the peer system which owns this layer
    * may not have been created yet.  If we need to create the layer, be careful to use the layer's layeredSystem so
    * we get the one which is managing this index (since each type index is per-layered system).
    */
   private LayerTypeIndex refreshLayerTypeIndex(String layerName, LayerTypeIndex layerTypeIndex, long lastModified) {
      File layerFile = getLayerFile(layerName);
      if (layerFile == null) {
         warning("Layer found in type index - not in the layer path: " + layerName + ": " + layerPathDirs);
         return null;
      }
      String pathName = layerFile.getParentFile().getPath();

      // Excluded in the project - do not refresh it
      if (externalModelIndex != null && externalModelIndex.isExcludedFile(pathName))
         return null;

      if (!pathName.equals(layerTypeIndex.layerPathName)) {
         return buildLayerTypeIndex(layerName);
      }
      for (String srcDir:layerTypeIndex.topLevelSrcDirs) {
         if (!Layer.isBuildDirPath(srcDir)) {
            File srcDirFile = new File(srcDir);

            if (!srcDirFile.isDirectory()) {
               System.err.println("**** Warning: srcDir removed for layer: " + layerName + ": " + srcDir + " rebuilding index");
               return buildLayerTypeIndex(layerName);
            }

            refreshLayerTypeIndexDir(srcDirFile, "", layerName, layerTypeIndex, lastModified);
         }
         // else - TODO: do we need to handle this case?
      }
      return layerTypeIndex;
   }

   private void refreshLayerTypeIndexDir(File srcDirFile, String relDir, String layerName, LayerTypeIndex typeIndex, long lastModified) {
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
            TypeIndex curTypeIndex = typeIndex.fileIndex.get(path);
            if (curTypeIndex == TypeIndex.EXCLUDED_SENTINEL)
               continue;
            String ext = FileUtil.getExtension(path);
            if (curTypeIndex != null || isParseable(path) || (ext != null && customSuffixes.contains(ext))) {
               if (curTypeIndex == null || lastModified < subF.lastModified()) {
                  try {
                     acquireDynLock(false);
                     Layer newLayer = getActiveOrInactiveLayerByPath(layerName, null, true);
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
                           TypeDeclaration newType = (TypeDeclaration) curSys.getSrcTypeDeclaration(typeName, newLayer.getNextLayer(), true, false, true, newLayer, curTypeIndex != null && curTypeIndex.isLayerType);
                           if (newType != null) {
                              // This should add it to the type index
                              ParseUtil.initAndStartComponent(newType);
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
      addLayerToRebuild(layerName, toBuild);
      rebuildLayersTypeIndex(toBuild);
      return toBuild.get(0).layerTypeIndex;
   }

   public static void rebuildLayersTypeIndex(ArrayList<Layer> rebuildLayers) {
      for (Layer layer:rebuildLayers) {
         layer.ensureStarted(true);
      }
      for (Layer layer:rebuildLayers) {
         layer.initAllTypeIndex();
         layer.layeredSystem.typeIndex.inactiveTypeIndex.typeIndex.put(layer.getLayerName(), layer.layerTypeIndex);
      }
   }

   public void addLayerToRebuild(String layerName, ArrayList<Layer> layersToRebuild) {
      try {
         acquireDynLock(false);
         Layer layer = getInactiveLayer(layerName, true, true, false);
         if (layer == null) {
            System.err.println("*** Unable to index layer: " + layerName);
            return;
         } else if (layer.disabled) {
            return;
         } else if (!layer.excluded && layer.layeredSystem == this) {
            layersToRebuild.add(layer);
         } else { // For excluded layers, hand it off to the layer's layered system
            if (layer.layeredSystem != this) {
               layer.layeredSystem.addLayerToRebuild(layerName, layersToRebuild);
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
         System.out.println("typeIndex - version changed: " + this);
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

   private void initBuildDirIndex() {
      addDirToIndex(null, buildDir, null, buildDir);
   }

   void addToPackageIndex(String rootDir, Layer layer, boolean isZip, boolean isSrc, String relDir, String fileName) {
      fileName = FileUtil.removeExtension(fileName);

      HashMap<String,PackageEntry> packageEntry = packageIndex.get(relDir);
      if (packageEntry == null) {
         packageEntry = new HashMap<String,PackageEntry>();
         packageIndex.put(relDir, packageEntry);
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
         System.err.println("*** Cannot find package entry to remove");
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
         if (isParseable(fn) || fn.endsWith(".class"))
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
                  if (!ze.isDirectory()) {
                     String dirName = FileUtil.getParentPath(ze.getName());
                     addToPackageIndex(layerDirName, layer, true, false, dirName, FileUtil.getFileName(ze.getName()));
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
         if (type == MessageType.Error)
            System.err.println(sb);
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
            if (args != null)
               pbs.add(new ProcessBuilder(args));
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

   static class BuildState {
      int numModelsToTransform = 0;
      boolean changedModelsPrepared = false;
      boolean changedModelsStarted = false;
      boolean changedModelsDetected = false;
      Set<String> processedFileNames = new HashSet<String>();

      HashSet<SrcEntry> srcEntSet = new HashSet<SrcEntry>();
      ArrayList<SrcEntry> srcEnts = new ArrayList<SrcEntry>();

      boolean anyError = false;
      boolean fileErrorsReported = false;
      LinkedHashSet<SrcEntry> toCompile = new LinkedHashSet<SrcEntry>();
      List errorFiles = new ArrayList();
      long buildTime = System.currentTimeMillis();
      LinkedHashSet<SrcDirEntry> srcDirs = new LinkedHashSet<SrcDirEntry>();
      HashMap<String,ArrayList<SrcDirEntry>> srcDirsByPath = new HashMap<String,ArrayList<SrcDirEntry>>();

      // Models which contribute to typeGroup dependencies, e.g. @MainInit, must be processed
      HashMap<String,IFileProcessorResult> typeGroupChangedModels = new HashMap<String, IFileProcessorResult>();
      HashMap<String,SrcEntry> unchangedFiles = new HashMap<String, SrcEntry>();

      // The list of actually changed files we found when we are incrementally compiling
      ArrayList<SrcEntry> modifiedFiles = new ArrayList<SrcEntry>();

      HashMap<SrcEntry,SrcEntry> dependentFilesChanged = new HashMap<SrcEntry,SrcEntry>();
      boolean anyChangedClasses = false;

      HashMap<String,Integer> typeGroupChangedModelsCount = new HashMap<String,Integer>();

      void addSrcEntry(int ix, SrcEntry newSrcEnt) {
         if (!srcEntSet.contains(newSrcEnt)) {
            if (ix == -1)
               srcEnts.add(newSrcEnt);
            else
               srcEnts.add(ix, newSrcEnt);
            srcEntSet.add(newSrcEnt);
         }
      }
   }

   public GenerateCodeStatus startPeerChangedModels(Layer genLayer, List<String> includeFiles, BuildPhase phase, boolean separateOnly) {
      // Before we start this model's files, make sure any build layers in peer systems have been started.  This happens when there's an
      // intermediate build layer in the peer before the one in the default runtime.
      // NOTE: peerMode = true should still iterate over this list.  We have the changedModelsStarted flag to prevent recursion.  When we
      // build the peer system, it's the itiator and we need to make sure it coordinates with the other systems.
      if (peerSystems != null && !separateOnly) {
         for (LayeredSystem peer:peerSystems) {
            Layer srcLayer = genLayer;
            Layer peerLayer;
            // Find the first layer in the peer system which matches (if any)
            do {
               peerLayer = peer.getLayerByDirName(srcLayer.layerDirName);
               if (peerLayer == null)
                  srcLayer = srcLayer.getPreviousLayer();
            } while (peerLayer == null && srcLayer != null);
            if (peerLayer != null) {

               setCurrent(peer);

               boolean peerStarted;
               BuildState peerState = peerLayer.buildState;
               if (peerState == null)
                  peerStarted = false;
               else
                  peerStarted = phase == BuildPhase.Prepare ? peerState.changedModelsPrepared : peerState.changedModelsStarted;

               if (!peerStarted) {
                  for (int pl = 0; pl < peerLayer.getLayerPosition(); pl++) {
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
                           basePeerStarted = phase == BuildPhase.Prepare ? basePeerState.changedModelsPrepared : basePeerState.changedModelsStarted;

                        if (!basePeerStarted) {
                           GenerateCodeStatus peerStatus = peer.startChangedModels(basePeerLayer, includeFiles, phase, false);
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

   private boolean modifiedByLayers(ArrayList<Layer> layers, Layer otherLayer) {
      for (Layer layer:layers)
         if (layer.modifiedByLayer(otherLayer))
            return true;
      return false;
   }

   private boolean systemDetailsDisplayed = false;

   /** Takes a srcEntry - a src file in a layer and parses the file and starts the component as needed.  The defaultReason is used to debug why this model is started.  Ordinarily this is because
    * a dependent file has changed, or the file was modified but if neither of these cases is true, the caller can provide a more specific reason. */
   private Object startModel(SrcEntry toGenEnt, Layer genLayer, boolean incrCompile, BuildState bd, BuildPhase phase, String defaultReason) {
      Object modelObj;
      IFileProcessor proc = getFileProcessorForSrcEnt(toGenEnt, phase, false);
      if (proc == null)
         return null; // possibly not processed in this phase
      if (proc.getProducesTypes()) {
         // We may have already parsed and initialized this component from a reference.
         TypeDeclaration cachedType = getCachedTypeDeclaration(proc.getPrependLayerPackage() ? toGenEnt.getTypeName() : toGenEnt.getRelTypeName(), toGenEnt.layer.getNextLayer(), null, false, false);
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
            if (!langModel.isAdded())
               addNewModel(langModel, genLayer.getNextLayer(), null, langModel instanceof JavaModel && ((JavaModel) langModel).isLayerModel);

            verbose("Preparing from model cache " + toGenEnt + ", runtime: " + getRuntimeName());
         }
      }
      // Parsed it and there was an error
      else if (modelObj == INVALID_TYPE_DECLARATION_SENTINEL)
         return INVALID_TYPE_DECLARATION_SENTINEL;
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

      if (!toGenEnt.layer.skipStart(toGenEnt.baseFileName, toGenEnt.relFileName)) {
         PerfMon.start("startModel", false);
         ParseUtil.startComponent(modelObj);
         ParseUtil.validateComponent(modelObj);
         PerfMon.end("startModel");
      }

      if (modelObj instanceof JavaModel) {
         JavaModel jmodel = (JavaModel) modelObj;
         if (incrCompile)
            jmodel.readReverseDeps(genLayer);
         else {
            TypeDeclaration prevModelType = getCachedTypeDeclaration(proc.getPrependLayerPackage() ? toGenEnt.getTypeName() : toGenEnt.getRelTypeName(), toGenEnt.layer, null, false, false);
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

   public GenerateCodeStatus startChangedModels(Layer genLayer, List<String> includeFiles, BuildPhase phase, boolean separateOnly) {
      boolean skipBuild = !genLayer.buildSeparate && separateOnly;

      BuildState bd;
      if ((bd = genLayer.buildState) == null) {
         bd = new BuildState();
         genLayer.buildState = bd;
      }

      if (!separateOnly) {
         if (phase == BuildPhase.Process)
            bd.changedModelsStarted = true;
         else
            bd.changedModelsPrepared = true;
      }

      if (startPeerChangedModels(genLayer, includeFiles, phase, separateOnly) == GenerateCodeStatus.Error)
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
      classPath = getClassPathForLayer(genLayer, buildClassesDir);
      userClassPath = buildUserClassPath(classPath);

      if (genLayer == buildLayer) {
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
         verbose("Skipping startChangedModels " + phase + " phase");
         return GenerateCodeStatus.NoFilesToCompile;
      }
      // Nothing to generate or compile
      if (genLayer == null)
         return GenerateCodeStatus.NoFilesToCompile;

      ArrayList<Layer> startedLayers = new ArrayList<Layer>();
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

         verbose("Find changes: " + getRuntimeName() + " runtime -" + (genLayer == buildLayer ? " sys build layer: " : " pre build layer: ") + genLayer + " including: " + startedLayers);
      }

      // For each directory or src file we need to look at.  Top level directories are put into this list before we begin.
      // As we find a sub-directory we insert it into the list from inside the loop.
      for (int i = 0; i < bd.srcEnts.size(); i++) {
         SrcEntry srcEnt = bd.srcEnts.get(i);
         String srcDirName = srcEnt.absFileName;
         String srcPath = srcEnt.relFileName;
         LinkedHashSet<SrcEntry> toGenerate = new LinkedHashSet<SrcEntry>();
         ArrayList<SrcEntry> toStartModels = null;

         File srcDir = new File(srcDirName);
         File depFile = new File(genLayer.buildSrcDir, FileUtil.concat(srcEnt.layer.getPackagePath(), srcPath, srcEnt.layer.getLayerName().replace(".", "-") + "-" + phase.getDependenciesFile()));
         boolean depsChanged = false;

         DependencyFile deps;

         long lastBuildTime;

         //boolean incrCompile = !options.buildAllFiles || srcEnt.layer.compiled;
         //boolean layeredCompile = srcEnt.layer.compiled;
         //boolean incrCompile = !genLayer.getBuildAllFiles();
         // If we are dealing with a source file that's already built in a previous build layer, use that layer's "buildAllFiles" so we do the build-all one clump of files at a time.
         Layer prevSrcEntBuildLayer = srcEnt.layer.getNextBuildLayer();
         boolean incrCompile = !prevSrcEntBuildLayer.getBuildAllFiles();
         boolean layeredCompile = false;

         boolean depFileExists = depFile.exists();

         // No dependencies or the layer def file changed - just add all of the files in this directory for generation
         if (!incrCompile || !depFileExists || (lastBuildTime = depFile.lastModified()) == 0 ||
                 (lastBuildTime < srcEnt.layer.getLastModifiedTime())) {
            addAllFiles(srcEnt.layer, toGenerate, srcDir, srcDirName, srcPath, phase, bd);
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
               addAllFiles(srcEnt.layer, toGenerate, srcDir, srcDirName, srcPath, phase, bd);
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
                     if (deps.getDependencies(newFile) == null) {
                        if (new File(newFile).isDirectory()) {
                           // Pick up new directories that were added
                           SrcEntry newSrcEnt = new SrcEntry(srcEnt.layer, srcDirName, srcPath, newFile, proc == null || proc.getPrependLayerPackage());
                           bd.addSrcEntry(i+1, newSrcEnt);
                           deps.addDirectory(newFile);
                           depsChanged = true;
                        }
                        else if (needsGeneration(newPath, srcEnt.layer, phase) &&
                                !srcEnt.layer.layerBaseName.equals(newFile) && !srcEnt.layer.excludedFile(newFile, srcDirName))
                           toGenerate.add(new SrcEntry(srcEnt.layer, srcDirName, srcPath, newFile, proc == null || proc.getPrependLayerPackage()));
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
                           }

                           // Mark these entries in use so they don't get culled even if no re-generation is needed//
                           // If the table itself doesn't know about this file, regenerate so we re-add the table entry
                           // If this name is inherited, it's not in this layer's index but previously has already been marked as in use when we built that layer
                           boolean foundInIndex = inherited || genLayer.markBuildSrcFileInUse(genFile, genRelFileName);

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
                                    bd.toCompile.add(new SrcEntry(srcEnt.layer,
                                            FileUtil.concat(genLayer.buildSrcDir, genRelFileName), genRelFileName));
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

                           // If we are already generating this, skip the file system check.
                           if (toGenerate.contains(otherFileName)) {
                              if (traceNeedsGenerate) {
                                 verbose("Generating: " + srcFileName + " because dependent file: " + otherFileName + " needs generation");
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
                        if (cachedModel != null && cachedModel instanceof JavaModel && ((JavaModel) cachedModel).getDependenciesChanged(incrCompile ? changedModels : null)) {
                           needsGenerate = true;
                           if (traceNeedsGenerate) {
                              verbose("Generating: " + srcFileName + " - dependent type was overridden since the previous layered build");
                           }
                        }
                     }
                     SrcEntry newSrcEnt = new SrcEntry(ent.layer, srcDirName, srcPath, srcFileName, proc == null || proc.getPrependLayerPackage());
                     String srcTypeName = newSrcEnt.getTypeName();
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
                           return startChangedModels(genLayer, includeFiles, phase, separateOnly);
                        }
                        SrcEntry prevEnt;
                        if ((prevEnt = bd.unchangedFiles.get(srcTypeName)) != null) {
                           newSrcEnt = prevEnt;
                        }
                        toGenerate.add(newSrcEnt);

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

                     // If we've already loaded a version of this type in a previous layer, even if it's not changed we still need to
                     // start the overriding type so that it can update the previous types for the next build.
                     TypeDeclaration cachedType;
                     if (!needsGenerate && (cachedType = getCachedTypeDeclaration(srcTypeName, null, null, false, true)) != null) {
                        if (cachedType != INVALID_TYPE_DECLARATION_SENTINEL && cachedType.layer != null && cachedType.layer.getLayerPosition() < newSrcEnt.layer.getLayerPosition()) {
                           /*
                            * TODO: do we need these "toStartModels" - they are guaranteed to be started but won't be transformed.  Also, any types which depend on these will not be generated.  Originally it seemed like this case should not require the model to be transformed since
                            * it only change the dependencies on the other models.  But if we do not put this model into the "changedModels" list, other dependent models won't know there's a change to their behavior and so they won't get to the transform stage where they pick up that change.
                            * The case is where HtmlPage in editor/mixin modifies a base class.  We detect that here and put this model into the "toTransform" stage.  That will ensure the index file also gets re-processed to pick up the change.
                           if (toStartModels == null)
                              toStartModels = new ArrayList<SrcEntry>();
                           toStartModels.add(newSrcEnt);
                           */
                           SrcEntry prevEnt;
                           if ((prevEnt = bd.unchangedFiles.get(srcTypeName)) != null) {
                              newSrcEnt = prevEnt;
                           }
                           toGenerate.add(newSrcEnt);
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

         LinkedHashSet<ModelToTransform> modelsToTransform = new LinkedHashSet<ModelToTransform>();

         // Now, loop over each file that was changed in this directory.
         for (SrcEntry toGenEnt:toGenerate) {
            File srcFile = new File(toGenEnt.absFileName);
            if (srcFile.isDirectory()) {
               // Put these after this entry so that all directories in a layer are processed
               bd.addSrcEntry(i+1, toGenEnt);
               if (deps.addDirectory(toGenEnt.baseFileName))
                  depsChanged = true;
            }
            else {
               boolean skipFile = includeFiles != null && !includeFiles.contains(toGenEnt.relFileName);
               Object modelObj;
               if (skipFile) {
                  if (options.verbose)
                     verbose("Skipping " + toGenEnt);
                  modelObj = null;
               }
               else {
                  modelObj = startModel(toGenEnt, genLayer, incrCompile, bd, phase, " new file?");
                  if (modelObj == INVALID_TYPE_DECLARATION_SENTINEL)
                     continue;
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

                  if (model.hasErrors())
                     fileError = true;
               }
               // Returns a ParseError if anything fails, null if we found nothing to compile in that file
               else if (modelObj != null)
                  fileError = true;

               // If we failed to generate this time, do not leave around any trash we might have generated
               // last time.  You can end up with a false successful build.
               if (fileError) {
                  bd.anyError = true;
                  anyErrors = true;
                  List<IString> generatedFiles = deps.getGeneratedFiles(toGenEnt.baseFileName);
                  deps.addErrorDependency(toGenEnt.baseFileName);
                  depsChanged = true;
                  if (generatedFiles != null) {
                     for (IString genFile:generatedFiles) {
                        File gf = new File(genLayer.buildSrcDir, FileUtil.unnormalize(genFile.toString()));
                        gf.delete();
                     }
                  }
                  bd.errorFiles.add(toGenEnt);
                  bd.fileErrorsReported = false;
               }
            }
         }

         if (toStartModels != null) {
            for (SrcEntry toStartEnt:toStartModels) {
               startModel(toStartEnt, genLayer, incrCompile, bd, phase, " modifies type already loaded");
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
         srcDirEnt.modelsToTransform = modelsToTransform;
         bd.srcDirs.add(srcDirEnt);
         ArrayList<SrcDirEntry> sdEnts = bd.srcDirsByPath.get(srcPath);
         if (sdEnts == null)
            sdEnts = new ArrayList<SrcDirEntry>();
         sdEnts.add(srcDirEnt);
         bd.srcDirsByPath.put(srcPath, sdEnts);
         bd.numModelsToTransform += modelsToTransform.size();
      }

      /*
      for (Layer stLayer:startedLayers) {
         stLayer.changedModelsDetected = true;
      }

      bd.changedModelsDetected = true;
      */

      // Print some info that's useful for diagnosing what gets recompiled and why on a rebuild.
      if (!genLayer.getBuildAllFiles() && options.verbose) {
         if (bd.modifiedFiles.size() == 0) {
            verbose("No changed files detected");
            if (bd.dependentFilesChanged.size() > 0)
               verbose("but do have dependent files that are changed: " + bd.dependentFilesChanged);
         }
         else {
            verbose("Changed files:" + bd.modifiedFiles);
            verbose("Dependent files: " + bd.dependentFilesChanged);
         }
      }

      if (bd.numModelsToTransform > 0 && options.info)
         info("Processing: " + bd.numModelsToTransform + " files in the " + getRuntimeName() + " runtime for build layer: " + genLayer.getLayerName());

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
      if (typeGroupDeps != null && phase == BuildPhase.Process && changedModels.size() > 0 && !separateOnly) {
         for (TypeGroupDep dep:typeGroupDeps) {
            IFileProcessor depProc = getFileProcessorForFileName(dep.relFileName, genLayer, null);
            if (depProc == null) {
               System.err.println("*** No file processor registered for type group dependency: " + dep.relFileName + " for group: " + dep.typeGroupName);
            }
            else {
               SrcEntry depFile = getSrcFileFromTypeName(dep.typeName, true, genLayer.getNextLayer(), depProc.getPrependLayerPackage(), null);
               if (depFile == null)
                  System.err.println("Warning: file: " + dep.relFileName + " not found in layer path but referenced via a typeGroup dependency on group: " + dep.typeGroupName);
               else {
                  Object depModelObj = bd.typeGroupChangedModels.get(dep.typeName);
                  if (depModelObj == null)
                     depModelObj = getCachedModel(depFile, false);
                  if (depModelObj == null) {
                     TypeDeclaration depType = getCachedTypeDeclaration(dep.typeName, genLayer, null, false, true);
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
               }
            }
         }
      }

      if (phase == BuildPhase.Process) {

         if (options.verbose) {
            if (buildStartTime != -1)
               verbose("Parsed changes till layer: " + genLayer + " in: " + StringUtil.formatFloat((System.currentTimeMillis() - buildStartTime) / 1000.0));
         }

         if (bd.anyError)
            return GenerateCodeStatus.Error;
         else {
            // Run the runtimeProcessor hook for the post start sequence
            if (runtimeProcessor != null)
               runtimeProcessor.postStart(this, genLayer);
         }

         // Doing this after postStart because by then we've detected all JS source we will load
         for (Layer stLayer : startedLayers) {
            stLayer.changedModelsDetected = true;
         }

         bd.changedModelsDetected = true;
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
            if (peerLayer != null && (peerLayer.buildState == null || !peerLayer.buildState.changedModelsStarted)) {

               // Before we can start the peer layers, make sure we've compiled any intermediate layers.  This would happen if the java runtime had no intermediates and the js runtime did, for example.
               // TODO: is this necessary now that we start intermediate build layers before?  Why are we compiling the intermediates here... shouldn't we just start them now that we start everything first?
               peer.buildLayersIfNecessary(peerLayer.getLayerPosition() - 1, includeFiles, false, false);

               GenerateCodeStatus peerStatus = peer.startChangedModels(peerLayer, includeFiles, phase, false);

               setCurrent(this); // Switch back to this layered system

               if (peerStatus == GenerateCodeStatus.Error) {
                  return GenerateCodeStatus.Error;
               }
            }
         }
      }
      */

      if (changedModels.size() > 0)
         return GenerateCodeStatus.NewCompiledFiles;
      return GenerateCodeStatus.NoFilesToCompile;
   }

   /**
    * This operation processes each file generating any Java files necessary for language extensions.
    * Various options can be provided to control how this generation is performed.  The list of includeFiles
    * specifies a subset of files to process - useful for debugging generator problems mainly.
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
      boolean alreadyStarted;
      // The generateCode phase happens in two major steps.  First we start all of the changed models, then we transform them into Java code.
      // Ordinarily the first step will be done before we get here... since we have to start all runtimes before we begin transforming.  That's because
      // transforming is a destructive step right now.
      if (buildState == null)
         alreadyStarted = false;
      else
         alreadyStarted = phase == BuildPhase.Prepare ? buildState.changedModelsPrepared : buildState.changedModelsStarted;
      if (!alreadyStarted) {
         GenerateCodeStatus startResult = startChangedModels(genLayer, includeFiles, phase, separateOnly);
         if (startResult != GenerateCodeStatus.NewCompiledFiles) {
            if (phase == BuildPhase.Process)
               genLayer.updateBuildInProgress(false);
            return startResult;
         }
      }

      BuildState bd = genLayer.buildState;


      // All components will have been started and validated at this point.
      if (!bd.anyError) {
         // Clear out the build info for data for any models we know have changed.  Doing this only on the first build because we do not restart all of the models and so will just clean out everything that's not changed.
         // TODO: this is not 100% accurate as we may also change models based on the type group membership changing later on.
         if (!systemCompiled)
            buildInfo.cleanStaleEntries(changedModels, processedModels, buildLayer == genLayer);

         for (SrcDirEntry srcDirEnt:bd.srcDirs) {
            for (ModelToTransform mtt:srcDirEnt.modelsToTransform) {
               IFileProcessorResult model = mtt.model;
               if (model instanceof ILifecycle) {
                  ILifecycle scModel = (ILifecycle) model;
                  if (scModel instanceof JavaModel)
                     ((JavaModel) scModel).cleanStaleEntries(changedModels);
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

         if (typeGroupDeps != null && phase == BuildPhase.Process && changedModels.size() > 0 && !separateOnly) {
            for (TypeGroupDep dep:typeGroupDeps) {
               IFileProcessor depProc = getFileProcessorForFileName(dep.relFileName, genLayer, null);
               if (depProc == null) {
                  System.err.println("*** No file processor registered for type group dependency: " + dep.relFileName + " for group: " + dep.typeGroupName);
               }
               else {
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
                               !sameTypeGroupLists(prevTypeGroupMembers, newTypeGroupMembers) ||
                               // Also regenerate if any of these files have changed, since annotations are typically used
                               // to determine the contents of the file, e.g. ApplicationPath
                               anyTypesChanged(changedModels, prevTypeGroupMembers)) {

                              // More than one directory might have this file.  Add this to the srcDir for the layer
                              // which contains the most specific type.  Not sure it's necessary but this ensures we
                              // generate these files at a consistent spot when traversing the directory hierarchy by
                              // just adding it to the modelsToTransform in the right directory.
                              String parentPath = FileUtil.getParentPath(dep.relFileName);
                              if (parentPath == null)
                                 parentPath = "";
                              ArrayList<SrcDirEntry> depDirs = bd.srcDirsByPath.get(parentPath);

                              // This forces us to retransform this model, even if we've already transformed it in a previous build layer (e.g. the PageInit class)
                              if (depModel.transformedModel != null)
                                 depModel.transformedInLayer = null;

                              typeGroupChanged = true;

                              // Might get to this when not processing this layer so we won't find the directory containing the file.  If this model has been changed but
                              // already processed in the previous layer, we still may need to transform it again in this layer.
                              if (depDirs != null && (changedModels.get(dep.typeName) == null || processedModels.contains(dep.typeName))) {
                                 if (options.verbose && !options.buildAllFiles) {
                                    if (prevTypeGroupMembers != null && anyTypesChanged(changedModels, prevTypeGroupMembers))
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
                  bd.anyError = true;
                  bd.errorFiles.add(toGenEnt);
                  bd.fileErrorsReported = false;
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

                  boolean needsCompile = generate && model.needsCompile();

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

                              if (systemCompiled && isClassLoaded(toGenEnt.getTypeName())) {
                                 setStaleCompiledModel(true, "Loaded compiled type: " + genFile.relFileName + " changed");
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
                                 Layer prevLayer = genLayer.getPrevSrcFileLayer(genFile.relFileName);
                                 if (prevSrc != null && prevLayer != null && (prevLayer.buildSeparate || runClassStarted))
                                    bd.anyChangedClasses = true;
                              }

                              if (sie != null) {
                                 sie.hash = hash;
                                 sie.inUse = true;
                              }
                              else
                                 genLayer.addSrcFileIndex(genFile.relFileName, hash, toGenEnt.getExtension());
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
               saveDeps(deps, depFile, bd.buildTime);
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

      if (!bd.anyError) {
         boolean compileFailed = false;

         if (options.verbose) {
            verbose("Generation completed in: " + StringUtil.formatFloat((System.currentTimeMillis() - buildStartTime)/1000.0));
         }
         if (phase == BuildPhase.Process && !options.noCompile && !skipBuild) {
            // Need to remove any null sentinels we might have registered
            for (SrcEntry compiledSrcEnt:bd.toCompile) {
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
            else if (options.info)
               info("Compiling Java: " + bd.toCompile.size() + " files into " + genLayer.getBuildClassesDir());

            PerfMon.start("javaCompile");
            if (LayerUtil.compileJavaFilesInternal(bd.toCompile, genLayer.getBuildClassesDir(), getClassPathForLayer(genLayer, genLayer.getBuildClassesDir()), options.debug, messageHandler) == 0) {
               if (!buildInfo.buildJars())
                  compileFailed = true;
            }
            else
               compileFailed = true;
            if (options.info)
               info("Compile " + (compileFailed ? "failed" : "completed"));
            PerfMon.end("javaCompile");
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

   public SrcIndexEntry getPrevSrcIndex(Layer genLayer, SrcEntry genFile) {
      return options.buildAllLayers || (options.useCommonBuildDir && genLayer == commonBuildLayer) ? null : genLayer.getPrevSrcFileIndex(genFile.relFileName);
   }

   private boolean anyTypesChanged(HashMap<String, IFileProcessorResult> changedModels, List<String> typeNameList) {
      for (String typeName:typeNameList)
         if (changedModels.get(typeName) != null)
            return true;
      return false;
   }

   private boolean sameTypeGroupLists(List<String> prevTypeGroupMembers, List<TypeGroupMember> newTypeGroupMembers) {
      if (prevTypeGroupMembers == null && newTypeGroupMembers == null)
         return true;
      else if (prevTypeGroupMembers == null || newTypeGroupMembers == null)
         return false;
      int prevSz = prevTypeGroupMembers.size();
      int newSz = newTypeGroupMembers.size();
      if (prevSz != newSz)
         return false;

      for (int i = 0; i < newSz; i++) {
         String newTypeName = newTypeGroupMembers.get(i).typeName;
         if (!newTypeName.equals(prevTypeGroupMembers.get(i))) {
            // For some reason these are getting reordered
            if (prevTypeGroupMembers.indexOf(newTypeName) == -1)
               return false;
         }
      }
      return true;
   }


   private void saveDeps(DependencyFile deps, File depFile, long buildTime) {
      try {
         depFile.delete();
         File depDir = depFile.getParentFile();
         depDir.mkdirs();
         DependenciesLanguage.INSTANCE.saveSemanticValue(deps, depFile);

         // Reset the deps file back to the start so we do not miss any changes made while
         // we were compiling on the next build.
         depFile.setLastModified(buildTime);
      }
      catch (IllegalArgumentException exc) {
         System.err.println("Unable to write dependencies file: " + exc);
      }
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
    * TODO - maybe remove this?  It's not used anymore.
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
      buildClassLoader = getClass().getClassLoader();
      RTypeUtil.flushCaches();
      resetClassCache();
      initSysClassLoader(buildLayer, ClassLoaderMode.ALL);

      Thread.currentThread().setContextClassLoader(getSysClassLoader());
      refreshBoundTypes(ModelUtil.REFRESH_CLASSES);
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
            verbose("Updating auto system class loader for thread: " + Thread.currentThread().getName());

         setSystemClassLoader(loader);
      }
   }

   public IBeanMapper getConstantPropertyMapping(Object type, String dstPropName) {
      return ModelUtil.getConstantPropertyMapping(type, dstPropName);
   }

   public Class loadClass(String className) {
      return getCompiledClassWithPathName(className);
   }

   public Object resolveMethod(Object type, String methodName, String paramSig) {
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

      refreshBoundTypes(ModelUtil.REFRESH_TYPEDEFS);

      // Need to clear this before we do refreshBoundType.   This was stale after the last recompile which means new classes
      // won't be visible yet - shadowed by cached nulls.  when we validate the models in refreshBoundType we need the new
      // compiled representation so that the "stale" model is kept in sync.
      resetClassCache();

      //typesByName.clear();
      //modelIndex.clear();
   }

   public void refreshBoundTypes(int flags) {
      // Need to clone here because we'll be adding new types to this map during the refreshBoundType process below - i.e.
      // remapping transformed types to their untransformed representations
      Map<String,TypeDeclarationCacheEntry> oldTypesByName = (Map<String,TypeDeclarationCacheEntry>) typesByName.clone();

      // Now that we've purged the cache of transformed types, go through any remaining types and do the refreshBoundType
      // operation.  That will drag in new versions of any referenced transformed types.
      for (Iterator<Map.Entry<String,TypeDeclarationCacheEntry>> it = oldTypesByName.entrySet().iterator(); it.hasNext(); ) {
         Map.Entry<String,TypeDeclarationCacheEntry> mapEnt = it.next();
         TypeDeclarationCacheEntry tdEnt = mapEnt.getValue();
         for (int k = 0; k < tdEnt.size(); k++) {
            TypeDeclaration td = tdEnt.get(k);
            td.refreshBoundTypes(flags);
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

   public void addAllFiles(Layer layer, Set<SrcEntry> toGenerate, File srcDir, String srcDirName, String srcPath, BuildPhase phase, BuildState bd) {
      String [] fileNames = srcDir.list();
      if (fileNames == null) {
         error("Invalid src directory: " + srcDir);
         return;
      }
      for (int f = 0; f < fileNames.length; f++) {
         String fileName = fileNames[f];
         if (layer.excludedFile(fileName, srcPath))
            continue;
         IFileProcessor proc = getFileProcessorForFileName(FileUtil.concat(srcPath, fileName), FileUtil.concat(srcDirName, fileName), layer, phase, false);
         if (proc != null && !fileName.equals(layer.layerBaseName)) {
            SrcEntry prevEnt;
            SrcEntry newSrcEnt = new SrcEntry(layer, srcDirName, srcPath,  fileName, proc.getPrependLayerPackage());
            // Just in case we've previously skipped over a file for this type, need to add the most specific one.
            if ((prevEnt = bd.unchangedFiles.get(newSrcEnt.getTypeName())) != null) {
               newSrcEnt = prevEnt;
            }
            toGenerate.add(newSrcEnt);
         }
         else {
            File subFile = new File(srcDirName, fileName);
            if (subFile.isDirectory())
               toGenerate.add(new SrcEntry(layer, srcDirName, srcPath,  fileName, proc == null || proc.getPrependLayerPackage()));
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
      for (Map.Entry<String,IFileProcessor[]> procEnt:fileProcessors.entrySet()) {
         ArrayList<IFileProcessor> newProcs = null;
         IFileProcessor[] procs = procEnt.getValue();
         for (IFileProcessor proc:procs) {
            Layer l = proc.getDefinedInLayer();
            if (l == null || !l.removed) {
               if (newProcs == null)
                  newProcs = new ArrayList<IFileProcessor>();
               newProcs.add(proc);
            }
            else
               proc.setDefinedInLayer(null);
         }
         if (newProcs != null)
            newMap.put(procEnt.getKey(), newProcs.toArray(new IFileProcessor[newProcs.size()]));
      }
      fileProcessors = newMap;

      LinkedHashMap<Pattern,IFileProcessor> newFilePatterns = new LinkedHashMap<Pattern,IFileProcessor>();
      if (filePatterns.size() > 0) {
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
      return getFileProcessorForExtension(ext, null, false, null, null, false);
   }

   public IFileProcessor getFileProcessorForExtension(String ext, String fileName, boolean abs, Layer srcLayer, BuildPhase phase, boolean generatedFile) {
      IFileProcessor[] procs = fileProcessors.get(ext);
      if (procs != null) {
         for (IFileProcessor proc:procs) {
            if (phase == null || phase == proc.getBuildPhase()) {
               if (fileName != null) {
                  switch (proc.enabledForPath(fileName, srcLayer, abs, generatedFile)) {
                     case Enabled:
                        return proc;
                     case Disabled:
                        return null;
                     case NotEnabled:
                        continue;
                  }
               }
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
      if (phase == null || phase == BuildPhase.Process)
         return Language.getLanguageByExtension(ext);
      return null;
   }

   public IFileProcessor getFileProcessorForSrcEnt(SrcEntry srcEnt, BuildPhase phase, boolean generatedFile) {
      return getFileProcessorForFileName(srcEnt.relFileName, srcEnt.absFileName, srcEnt.layer, phase, generatedFile);
   }

   public IFileProcessor getFileProcessorForFileName(String fileName, Layer fromLayer, BuildPhase phase) {
      return getFileProcessorForFileName(fileName, null, fromLayer, phase, false);
   }

   public IFileProcessor getFileProcessorForFileName(String fileName, String absFileName, Layer fromLayer, BuildPhase phase, boolean generatedFile) {
      IFileProcessor res;
      if (filePatterns.size() > 0) {
         for (Map.Entry<Pattern,IFileProcessor> ent:filePatterns.entrySet()) {
            Pattern patt = ent.getKey();
            // If either just the file name part or the entire directory matches use this pattern
            if (patt.matcher(fileName).matches() || patt.matcher(FileUtil.getFileName(fileName)).matches()) {
               res = ent.getValue();
               if (phase == null || res.getBuildPhase() == phase)
                  return res;
            }
         }
      }
      String ext = FileUtil.getExtension(fileName);
      if (absFileName != null)
         return getFileProcessorForExtension(ext, absFileName, true, fromLayer, phase, generatedFile);
      else
         return getFileProcessorForExtension(ext, fileName, false, fromLayer, phase, generatedFile);
   }

   public Object parseSrcFile(SrcEntry srcEnt, boolean reportErrors) {
      return parseSrcFile(srcEnt, srcEnt.isLayerFile(), true, false, reportErrors);
   }

   /**
    * For IDE-like operations where we need to parse a file but using the current-in memory copy for fast model validation.  This method
    * should not alter the runtime code model
    */
   public Object parseSrcBuffer(SrcEntry srcEnt, boolean enablePartialValues, String buffer, boolean dummy) {
      long modTimeStart = srcEnt.getLastModified();
      IFileProcessor processor = getFileProcessorForSrcEnt(srcEnt, null, false);
      if (processor instanceof Language) {
         Language lang = (Language) processor;
         Object result = lang.parseString(srcEnt.absFileName, buffer, enablePartialValues);
         if (result instanceof ParseError) {
            if (enablePartialValues) {
               Object val = ((ParseError) result).getRootPartialValue();
               initNewBufferModel(srcEnt, val, modTimeStart, dummy);
            }
            return result;
         }
         else {
            Object modelObj = ParseUtil.nodeToSemanticValue(result);

            if (!(modelObj instanceof IFileProcessorResult))
               return modelObj;

            initNewBufferModel(srcEnt, modelObj, modTimeStart, dummy);

            return modelObj;
         }
      }
      throw new IllegalArgumentException(("No language processor for file: " + srcEnt.relFileName));
   }

   private void initNewBufferModel(SrcEntry srcEnt, Object modelObj, long modTimeStart, boolean dummy) {
      if (modelObj instanceof IFileProcessorResult) {
         IFileProcessorResult res = (IFileProcessorResult) modelObj;
         res.addSrcFile(srcEnt);

         if (res instanceof ILanguageModel) {
            ILanguageModel newModel = (ILanguageModel) res;
            if (!dummy)
               markBeingLoadedModel(srcEnt, newModel);
            else
               newModel.setTemporary(true);
            try {
               initModel(srcEnt.layer, modTimeStart, newModel, srcEnt.isLayerFile(), false);
            }
            finally {
               if (!dummy)
                  beingLoaded.remove(srcEnt.absFileName);
            }
         }
      }
   }

   public Object parseSrcFile(SrcEntry srcEnt, boolean isLayer, boolean checkPeers, boolean enablePartialValues, boolean reportErrors) {
      // Once we parse one source file that's not an annotation layer in java.util or java.lang we cannot by-pass the src mechanism for the final js.sys java.util or java.lang classes - they have interdependencies.
      if (srcEnt.layer != null && !srcEnt.layer.annotationLayer) {
         /*
         if (srcEnt.absFileName.contains("java/util"))
            System.out.println("*** debug point for loading java util classes as source ");
         if (srcEnt.absFileName.contains("java/lang"))
            System.out.println("*** debug point for loading java lang classes as source");
         */

         String srcEntPkg = CTypeUtil.getPackageName(srcEnt.getTypeName());
         if (srcEntPkg != null) {
            if (allOrNoneFinalPackages.contains(srcEntPkg)) {
               overrideFinalPackages.addAll(allOrNoneFinalPackages);
            }
         }
      }

      IFileProcessor processor = getFileProcessorForSrcEnt(srcEnt, null, false);
      if (processor != null) {
         if (options.verbose && !isLayer && !srcEnt.relFileName.equals("sc/layer/BuildInfo.sc") && (processor instanceof Language || options.sysDetails))
            verbose("Reading: " + srcEnt.absFileName + " for runtime: " + getProcessIdent());

         /*
         if (srcEnt.absFileName.contains("coreRuntime") && srcEnt.absFileName.contains("Bind"))
            System.out.println("---");
         */

         long modTimeStart = srcEnt.getLastModified();
         Object result = processor.process(srcEnt, enablePartialValues);
         if (result instanceof ParseError) {
            if (reportErrors) {
               System.err.println("File: " + srcEnt.absFileName + ": " +
                       ((ParseError) result).errorStringWithLineNumbers(new File(srcEnt.absFileName)));

               if (currentBuildLayer != null && currentBuildLayer.buildState != null) {
                  BuildState bd = currentBuildLayer.buildState;
                  bd.anyError = true;
                  anyErrors = true;
                  bd.errorFiles.add(srcEnt);
               }
            }
            return result;
         }
         else {
            Object modelObj = ParseUtil.nodeToSemanticValue(result);

            // TODO: Template file - compile these?
            if (!(modelObj instanceof IFileProcessorResult))
               return null;

            IFileProcessorResult res = (IFileProcessorResult) modelObj;
            res.addSrcFile(srcEnt);
            if (srcEnt.getLastModified() != modTimeStart) {
               System.err.println("File: " + srcEnt.absFileName + " changed during parsing");
               return new ParseError("File changed during parsing", null, 0, 0);
            }

            if (modelObj instanceof ILanguageModel) {
               ILanguageModel model = (ILanguageModel) modelObj;

               markBeingLoadedModel(srcEnt, model);

               try {
                  boolean activatedLayer = srcEnt.layer != null && srcEnt.layer.activated;

                  ILanguageModel oldModel = activatedLayer ? modelIndex.get(srcEnt.absFileName) : inactiveModelIndex.get(srcEnt.absFileName);
                  if (oldModel instanceof JavaModel && oldModel != model && model instanceof JavaModel) {
                     ((JavaModel) oldModel).replacedByModel = (JavaModel) model;
                  }

                  ArrayList<JavaModel> clonedModels = null;

                  if (options.clonedParseModel && peerSystems != null && !isLayer && model instanceof JavaModel && checkPeers && srcEnt.layer != null) {
                     for (LayeredSystem peerSys:peerSystems) {
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
                              }
                              else if (options.verbose)
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
            return modelObj;
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
         m = getAnyLanguageModel(srcEnt, true);
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

         initModel(peerLayer, modTimeStart, copy, false, true);

         beingLoaded.remove(copySrcEnt.absFileName);

         addNewModel(copy, peerLayer.getNextLayer(), null, false);
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

   public SystemRefreshInfo refreshRuntimes() {
      SystemRefreshInfo sysInfo = refreshSystem();
      ArrayList<SystemRefreshInfo> peerChangedInfos = new ArrayList<SystemRefreshInfo>();
      if (peerSystems != null) {
         for (LayeredSystem sys:peerSystems) {
            setCurrent(sys);
            // TODO: right now, we ignore the change models in other systems.  These are used by EditorContext to do
            // updating of any errors detected in the models.  Currently EditorContext only knows about one layered system
            // but will need to know about all of them to manage errors, switching views etc. correctly.
            peerChangedInfos.add(sys.refreshSystem());
         }
         setCurrent(this);
      }
      completeRefresh(sysInfo);
      if (peerSystems != null) {
         int i = 0;
         for (LayeredSystem peer:peerSystems) {
            peer.completeRefresh(peerChangedInfos.get(i));
            i++;
         }
      }
      return sysInfo;
   }

   public class SystemRefreshInfo {
      public UpdateInstanceInfo updateInfo;
      public List<Layer.ModelUpdate> changedModels;

      public String toString() {
         if (changedModels == null)
            return "<no changes>";
         else
            return changedModels.toString();
      }
   }

   public SystemRefreshInfo refreshSystem() {
      // Before we parse any files, need to clear out any invalid models
      // TODO: remove this unless we are not using clonedTransformedModels
      cleanTypeCache();

      // We may start some new models here so reset this flag
      allTypesProcessed = false;

      UpdateInstanceInfo updateInfo = newUpdateInstanceInfo();
      List<Layer.ModelUpdate> changedModels = refreshSystem(updateInfo);

      // Once we've refreshed some of the models in the system, we now need to go and update the type references globally to point to the new references
      refreshBoundTypes(ModelUtil.REFRESH_TYPEDEFS);

      SystemRefreshInfo sysInfo = new SystemRefreshInfo();
      sysInfo.updateInfo = updateInfo;
      sysInfo.changedModels = changedModels;

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
         refreshSystem();
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
   public void rebuild() {
      acquireDynLock(false);

      // TODO: buildAllFiles does not always work so at some point we need to either fix this or turn it into buildIfAnyChanges
      if (systemCompiled) {
         options.buildAllFiles = false;
         if (buildLayer != null)
            buildLayer.buildAllFiles = false;
      }
      buildingSystem = true;
      if (peerSystems != null && !peerMode) {
         for (LayeredSystem peer:peerSystems) {
            peer.buildingSystem = true;
         }
      }

      boolean initialBuild;
      try {
         List<Layer.ModelUpdate> changes = null;
         ArrayList<SystemRefreshInfo> peerChanges = null;

         // If we've been through here at least once...
         if (systemCompiled || anyErrors) {
            initialBuild = false;
            // When using an IDE, it will handle the refresh for us.  We learn about changes in the getLanguageModel method and apply
            // them there.
            // First we have to refresh the models in all systems so we have a consistent view of the new types across all
            // runtimes.  That way synchronization can be computed accurately for the new changes.
            SystemRefreshInfo sysInfo = refreshSystem();
            changes = sysInfo.changedModels;
            peerChanges = new ArrayList<SystemRefreshInfo>();
            if (peerSystems != null && !peerMode) {
               for (LayeredSystem peer:peerSystems) {
                  peer.buildingSystem = true;
                  peerChanges.add(peer.refreshSystem());
               }
            }

            // Now we need to validate and process the changed models and update any instances in each system.
            completeRefresh(sysInfo);
            if (peerSystems != null && !peerMode) {
               for (int i = 0; i < peerSystems.size(); i++) {
                  peerSystems.get(i).completeRefresh(peerChanges.get(i));
               }
            }
         }
         else {
            initialBuild = true;
         }

         boolean reset = false;
         if (initialBuild || anyCompiledChanged(changes)) {
            if (!initialBuild) {
               reset = true;
               resetBuild(true);
            }
            buildSystem(null, false, false);
         }

         if (peerSystems != null && !peerMode) {
            int i = 0;
            for (LayeredSystem peer:peerSystems) {
               peer.buildingSystem = true;
               try {
                  SystemRefreshInfo peerSysInfo = peerChanges == null ? null : peerChanges.get(i);
                  List<Layer.ModelUpdate> peerChangedModels = peerSysInfo == null ? null : peerSysInfo.changedModels;
                  if (initialBuild || peer.anyCompiledChanged(peerChangedModels)) {
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
         buildCompleted(true);
         setCurrent(this);
      }
      finally {
         buildingSystem = false;
         releaseDynLock(false);
      }
   }

   private void completeRefresh(SystemRefreshInfo sysInfo) {
      List<Layer.ModelUpdate> changes = sysInfo.changedModels;
      if (changes == null)
         return;

      for (Layer.ModelUpdate upd:changes) {
         ILanguageModel oldModel = upd.oldModel;
         Object newModel = upd.changedModel;
         if (oldModel instanceof JavaModel && newModel instanceof JavaModel) {
            ((JavaModel) oldModel).completeUpdateModel((JavaModel) newModel);
         }
      }
      ExecutionContext ctx = new ExecutionContext(this);
      //allTypesProcessed = true;
      sysInfo.updateInfo.updateInstances(ctx);
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
      }
      return false;
   }

   public void resetBuild(boolean doPeers) {
      anyErrors = false;
      viewedErrors.clear();
      systemCompiled = false;
      for (int i = 0; i < layers.size(); i++) {
         Layer l = layers.get(i);
         BuildState bd = l.buildState;
         if (bd != null) {
            bd.changedModelsStarted = false;
            bd.changedModelsPrepared = false;
            bd.errorFiles = new ArrayList();
            bd.anyError = false;
         }
         l.compiled = false;
      }
      for (IFileProcessor[] fps:fileProcessors.values()) {
         for (IFileProcessor fp:fps) {
            fp.resetBuild();
         }
      }
      for (IFileProcessor fp:filePatterns.values()) {
         fp.resetBuild();
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
   public List<Layer.ModelUpdate> refreshSystem(UpdateInstanceInfo updateInfo) {
      ExecutionContext ctx = new ExecutionContext(this);
      ArrayList<Layer.ModelUpdate> refreshedModels = new ArrayList<Layer.ModelUpdate>();
      for (Layer l:layers) {
         l.refresh(lastRefreshTime, ctx, refreshedModels, updateInfo);
      }
      // We also have to coordinate manaagement of all of the errorModels for both layered systems in EditorContext.
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
      Object res = refresh(srcEnt, oldModel, ctx, info);
      info.updateInstances(ctx);
      return res;
   }

   /** Refreshes one file and batches the changes into the supplied UpdateInstanceInfo arg */
   public Object refresh(SrcEntry srcEnt, ExecutionContext ctx, UpdateInstanceInfo info) {
      // Not using the getLanguageModel here cause we really need the old model
      ILanguageModel oldModel = modelIndex.get(srcEnt.absFileName);
      return refresh(srcEnt, oldModel, ctx, info);
   }

   public Object refresh(SrcEntry srcEnt, ILanguageModel oldModel, ExecutionContext ctx, UpdateInstanceInfo updateInfo) {
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
         if (oldModel == null)
            verbose("Processing new file: " + srcEnt.absFileName);
         else
            verbose("Refreshing changed file: " + srcEnt.absFileName);
      }

      // When we do an update, we may first build the layer.  In that case, the model will have been loaded in the build.
      // If not, the model could be null or still the old model which needs to be replaced.
      ILanguageModel indexModel = getLanguageModel(srcEnt);
      if (indexModel == oldModel)
         indexModel = null;

      boolean addModel = indexModel == null;

      Object result = indexModel == null ? parseSrcFile(srcEnt, true) : indexModel;

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
               oldJavaModel.updateModel(newModel, ctx, TypeUpdateMode.Replace, true, updateInfo);
            }
            else if (newModel.modifiesModel()) {
               if (addModel)
                  addNewModel(newModel, srcEnt.layer, null, false);
               newModel.updateLayeredModel(ctx, true, updateInfo);
            }
            // Make sure this gets added so we don't get keep doing it over and over again
            else if (addModel)
               addNewModel(newModel, srcEnt.layer, null, false);

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

   public void refreshFile(SrcEntry srcEnt, Layer fromLayer) {
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
      Object result = parseSrcFile(srcEnt, isLayer, true, false, reportErrors);
      if (result instanceof ILanguageModel) {
         ILanguageModel model = (ILanguageModel) result;

         addNewModel(model, fromLayer, null, isLayer);
      }
      else if (result instanceof ParseError) {
         addTypeByName(srcEnt.layer, srcEnt.getTypeName(), INVALID_TYPE_DECLARATION_SENTINEL, fromLayer);
      }
      return result;
   }

   public void addNewModel(ILanguageModel model, Layer fromLayer, ExecutionContext ctx, boolean isLayer) {
      model.setAdded(true);
      SrcEntry srcEnt = model.getSrcFile();
      Layer srcEntLayer = srcEnt.layer;
      if (!isLayer && srcEntLayer != null && srcEntLayer.activated) {
         // Now we can get its types and info.
         addTypesByName(srcEntLayer, model.getPackagePrefix(), model.getDefinedTypes(), fromLayer);
      }
      // Also register it in the layer model index
      srcEntLayer.layerModels.add(new IdentityWrapper(model));

      updateModelIndex(srcEnt, model, ctx);
   }

   public void notifyModelListeners(JavaModel model) {
      if (model.removed) {
         warning("notifying removed listener");
         return;
      }
      for (IModelListener ml: modelListeners) {
         ml.modelAdded(model);
      }
   }

   public void notifyInnerTypeAdded(BodyTypeDeclaration innerType) {
      if (innerType.getJavaModel().removed) {
         warning("*** notifying removed inner listener");
         return;
      }
      for (IModelListener ml: modelListeners) {
         ml.innerTypeAdded(innerType);
      }
      innerTypeCache.remove(innerType.getFullTypeName());
   }

   public void notifyInnerTypeRemoved(BodyTypeDeclaration innerType) {
      if (innerType.getJavaModel().removed) {
         warning("*** notifying removed inner listener");
         return;
      }
      for (IModelListener ml: modelListeners) {
         ml.innerTypeRemoved(innerType);
      }
      innerTypeCache.remove(innerType.getFullTypeName());
   }

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
    * Creates an object instance from a layerCake file that is not part of a layer, i.e. the global dependencies object
    * Reads the layerCake file, inits and starts the component, creates an instance with those properties and returns
    * that instance.  This instance is not put into any type index and so you can't resolve layerCake references to this
    * object.
    */
   public Object loadInstanceFromFile(String fileName, String relFileName) {
      File f = new File(fileName);
      if (!f.exists())
         return null;
      SrcEntry srcEnt = new SrcEntry(null, fileName, relFileName);

      Object res = parseSrcFile(srcEnt, false, false, false, true);
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

   public void saveTypeToFixedTypeFile(String fileName, Object instance, String typeName) {
      String packageName = CTypeUtil.getPackageName(typeName);
      String baseName = CTypeUtil.getClassName(typeName);
      SCModel model = new SCModel();
      model.addSrcFile(new SrcEntry(null, fileName, packageName.replace(".",FileUtil.FILE_SEPARATOR)));
      ModifyDeclaration modDecl = new ModifyDeclaration();
      modDecl.typeName = baseName;
      model.setProperty("types", new SemanticNodeList(1));
      model.types.add(modDecl);

      IBeanMapper[] props = RTypeUtil.getPersistProperties(instance.getClass());

      for (int i = 0; i < props.length; i++) {
         IBeanMapper prop = props[i];
         String pname = prop.getPropertyName();
         Object value = TypeUtil.getPropertyValue(instance, pname);
         if (value != null) {
            Expression expr = Expression.createFromValue(value, true);
            modDecl.addBodyStatement(PropertyAssignment.create(pname, expr, null));
         }
      }
      Language l = Language.getLanguageByExtension(FileUtil.getExtension(fileName));
      Object res = l.generate(model, false);
      if (res instanceof GenerateError) {
         System.err.println("*** Error saving type: " + typeName + " :" + res);
      }
      else {
         String genResult = res.toString();
         FileUtil.saveStringAsFile(fileName, genResult, true);
      }
   }

   private void updateModelIndex(Layer layer, ILanguageModel model, String absName) {
      if (layer != null && layer.activated) {
         modelIndex.put(absName, model);
      }
      else {
         ILanguageModel oldModel = inactiveModelIndex.put(absName, model);
         if (oldModel != null && model instanceof JavaModel && oldModel != model) {
            JavaModel javaModel = (JavaModel) model;
            // We only do this if replacing the inactive model.  The first time, we will have parsed an unannotated layer model so don't process the update for that.
            if (layer != null && oldModel.getUserData() != null) {
               boolean layerModelChanged;
               if (javaModel.isLayerModel)
                  layerModelChanged = (oldModel.getLastModifiedTime() != javaModel.getLastModifiedTime() || !oldModel.sameModel(model)) && layer.updateModel(javaModel);
               // If it's a regular file, not a layer, we mark the layer as having changed if the last modified time is different or the models have physically different contents.
               // the last modified time for a file edited in the IDE does not get updated until after we've updated the model here.  It is not cheap to identical models but it will
               // save a refresh of all open files when you have only changed comments or whitespace.
               else
                  layerModelChanged = oldModel.getLastModifiedTime() != model.getLastModifiedTime() || !oldModel.sameModel(model);
               if (layerModelChanged && externalModelIndex != null)
                  externalModelIndex.layerChanged(layer);
            }
         }
      }
   }

   private void updateModelIndex(SrcEntry srcEnt, ILanguageModel model, ExecutionContext ctx) {
      String absName = srcEnt.absFileName;
      ILanguageModel oldModel = getCachedModel(srcEnt, false);
      // getLanguageModel might load the model into the cache but not put it into the type system, even if it's active.  We call this again from addNew
      if (oldModel == model)
         return;
      if (oldModel != null && options.verbose && oldModel.getLayer().activated) {
         System.err.println("Replacing model " + absName + " in layer: " + oldModel.getLayer() + " with: " + model.getLayer());
      }

      Layer layer = model.getLayer();
      updateModelIndex(layer, model, absName);
      if (model instanceof JavaModel) {
         JavaModel newModel = (JavaModel) model;
         if (oldModel != null) {
            // TODO: should we be calling updateModel here?
            newModel.replacesModel = (JavaModel) oldModel;
         }
         else if (newModel.modifiesModel()) {
            if (ctx != null) {
               // We've found a change to a model not inside of a managed "addLayers" ooperation.  Maybe we're compiling a layer and we see a change at that point or maybe we've just created a new empty type from the command line.  Probably should change this to somehow batch up the UpdateInstanceInfo and apply that change when we hit the addLayer operation that triggered the build.  But in the empty case, there's no changes to apply
               newModel.updateLayeredModel(ctx, true, null);
            }
         }
      }
   }

   public void updateInactiveModels() {
      if (externalModelIndex != null) {
         for (ILanguageModel inactiveModel:inactiveModelIndex.values()) {
            ILanguageModel newModel = modelIndex.get(inactiveModel.getSrcFile().absFileName);

            externalModelIndex.replaceModel(inactiveModel, newModel);
         }
      }
   }

   public void replaceModel(ILanguageModel oldModel, ILanguageModel newModel) {
      SrcEntry srcEnt = oldModel.getSrcFile();
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
         if (javaModel.getModifiedModel() == null)
            javaModel.disposeInstances();

         if (removeTypes && !javaModel.isLayerModel)
            removeTypesByName(srcEnt.layer, model.getPackagePrefix(), model.getDefinedTypes(), model.getLayer());
      }

      for (IModelListener ml: modelListeners) {
         ml.modelRemoved(model);
      }

   }

   private boolean isActivated(Layer layer) {
      return layer != null && layer.activated;
   }

   public ILanguageModel getAnyLanguageModel(SrcEntry srcEnt, boolean activeOrInactive) {
      ILanguageModel m = getLanguageModel(srcEnt, activeOrInactive, null);
      if (m != null)
         return m;
      if (peerSystems != null) {
         for (LayeredSystem peerSys:peerSystems) {
            m = peerSys.getLanguageModel(srcEnt);
            if (m != null)
               return m;
         }
      }
      return null;
   }

   public ILanguageModel getLanguageModel(SrcEntry srcEnt) {
      return getLanguageModel(srcEnt, false, null);
   }

   public ILanguageModel getLanguageModel(SrcEntry srcEnt, boolean activeOrInactive, List<Layer.ModelUpdate> changedModels) {
      Layer layer = srcEnt.layer;

      // If we are in the midst of loading this model via createFile, we cannot try to go back to the file manager and get the same file as it will recurse endlessly
      ILanguageModel cachedModel = beingLoaded.get(srcEnt.absFileName);
      if (cachedModel != null && cachedModel.getLayer() == srcEnt.layer)
         return cachedModel;
      // We only inactive models to go through the external model index.
      if (externalModelIndex != null && (layer != null && !layer.activated)) {
         ILanguageModel extModel = externalModelIndex.lookupJavaModel(srcEnt);
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
            if (isActivated(srcEnt.layer)) { System.err.println("*** not reached!!!");
               if (!extModel.isAdded())
                  addNewModel(extModel, srcEnt.layer.getNextLayer(), null, extModel instanceof JavaModel && ((JavaModel) extModel).isLayerModel);
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

   private void addToRootNameIndex(BodyTypeDeclaration type) {
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

   private void removeFromRootNameIndex(BodyTypeDeclaration type) {
      String rootName = type.getInnerTypeName();
      ArrayList<BodyTypeDeclaration> types = typesByRootName.get(rootName);
      if (types == null) {
          return;
      }
      else {
         if (!types.remove(type))
            System.err.println("*** Can't find entry in root name list to remove: " + rootName);
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

   public void addTypeByName(Layer layer, String fullTypeName, TypeDeclaration toAdd, Layer fromLayer) {
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
               if (newFrom > tds.fromPosition) // Don't lower the from position... only move it up the layer stack as new guys are added
                  tds.fromPosition = newFrom;
               break;
            }
         }
         if (i == tds.size()) {
            if (i == 0)
               tds.fromPosition = fromLayer != null ? fromLayer.layerPosition : getLastStartedPosition();
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
      removeFromRootNameIndex(toRem);
      TypeDeclarationCacheEntry tds = typesByName.get(fullTypeName);
      if (tds != null) {
         int i;
         for (i = 0; i < tds.size(); i++) {
            TypeDeclaration inList = tds.get(i);
            if (inList == toRem) {
               tds.remove(i);
               subTypesByType.remove(toRem.getFullTypeName());
               if (tds.size() == 0)
                  typesByName.remove(fullTypeName);
               return;
            }
         }
      }
      else {
         warning("*** remove type by name not in list");
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

   public static TypeDeclaration INVALID_TYPE_DECLARATION_SENTINEL = new ClassDeclaration();
   {
      INVALID_TYPE_DECLARATION_SENTINEL.typeName = "<invalid>";
   }

   public TypeDeclaration getCachedTypeDeclaration(String typeName, Layer fromLayer, Layer srcLayer, boolean updateFrom, boolean anyTypeInLayer) {
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
               if (fromPosition > decls.fromPosition)
                  decls.fromPosition = fromPosition;
            }

            // If the caller provides the current src file with no layer, we can avoid reloading it
            // and just update the cache. 
            if (srcLayer != null) {
               if (srcLayer.layerPosition < origFrom)
                  return decls.get(0);
            }
         }
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
      return buildLayer != null && !buildLayer.isDynamicType(typeName) && getUseCompiledForFinal() && changedModels.get(typeName) == null && isCompiledFinalLayerType(typeName);
   }

   /** This is an optimization on top of getSrcTypeDeclaration - if the model is not changed and we are not going from a layer use the compiled version */
   public Object getTypeForCompile(String typeName, Layer fromLayer, boolean prependPackage, boolean notHidden, boolean srcOnly, Layer refLayer, boolean layerResolve) {
      if (getUseCompiledForFinal() && fromLayer == null) {
         // First if we've loaded the src we need to return that.
         TypeDeclaration decl = getCachedTypeDeclaration(typeName, null, null, true, false);
         if (decl != null) {
            if (decl == INVALID_TYPE_DECLARATION_SENTINEL)
               return null;
            return decl;
         }

         // Now since the model is not changed, we'll use the class.  If at some point we need to change the compiled version - ie. to make a property bindable, we'll load the src at that time.
         Object cl = getCompiledType(typeName);
         if (cl != null)
            return cl;
      }
      return getSrcTypeDeclaration(typeName, fromLayer, prependPackage, notHidden, srcOnly, refLayer, layerResolve);
   }

   public static int nestLevel = 0;

   public TypeDeclaration getSrcTypeDeclaration(String typeName, Layer fromLayer, boolean prependPackage) {
      return getSrcTypeDeclaration(typeName, fromLayer, prependPackage, false);
   }

   public TypeDeclaration getSrcTypeDeclaration(String typeName, Layer fromLayer, boolean prependPackage, boolean notHidden) {
      return (TypeDeclaration) getSrcTypeDeclaration(typeName, fromLayer, prependPackage, notHidden, true, null, false);
   }

   public Object getSrcTypeDeclaration(String typeName, Layer fromLayer, boolean prependPackage, boolean notHidden, boolean srcOnly) {
      return getSrcTypeDeclaration(typeName, fromLayer, prependPackage, notHidden, srcOnly, null, false);
   }

   public TypeDeclaration getTypeFromCache(String typeName, Layer fromLayer, boolean prependPackage) {
      TypeDeclaration decl = getCachedTypeDeclaration(typeName, fromLayer, null, true, false);
      if (decl != null)
         return decl;

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
      return getSrcTypeDeclaration(typeName, fromLayer, prependPackage, notHidden, srcOnly, refLayer, layerResolve, false);
   }

   /** Retrieves a type declaration, usually the source definition with a given type name.  If fromLayer != null, it retrieves only types
    * defines below fromLayer (not including fromLayer).  If prependPackage is true, the name is resolved like a Java type name.  If
    * it is false, it is resolved like a file system path - where the type's package is not used in the type name.  If not hidden is true,
    * do not return any types which are marked as hidden - i.e. not visible in the editor.  In some configurations this mode is used for the addDyn calls so that
    * we do not bother loading for source you are not going to change.
    *
    * The srcOnly flag is t if you need a TypeDeclaration - and cannot deal with a final class.
    *
    * If refLayer is supplied, it refers to the referring layer.  It's not ordinarily used in an active application but for an inactive layer, it
    * changes how the lookup is performed.
    */
   public Object getSrcTypeDeclaration(String typeName, Layer fromLayer, boolean prependPackage, boolean notHidden, boolean srcOnly, Layer refLayer, boolean layerResolve, boolean rootTypeOnly) {
      SrcEntry srcFile = null;
      TypeDeclaration decl;
      Object skippedDecl = null;

      // Do not resolve source files in the system layer package from the layer definition files.  Otherwise LayeredSystaem and other classes overridden in the layer can mess up the layer's definition
      if (layerResolve) {
         String pkgName = CTypeUtil.getPackageName(typeName);
         if (pkgName == null || systemLayerModelPackages.contains(pkgName))
            return null;
      }

      if (cacheForRefLayer(refLayer)) {
         decl = getCachedTypeDeclaration(typeName, fromLayer, null, true, false);
         if (decl != null) {
            if (decl == INVALID_TYPE_DECLARATION_SENTINEL)
               return null;

            // We may not have loaded all of the files for this type.  Currently we load all types we need to process during the build but then may just grab the most specific one when resolving a global type.  If there are gaps created the fromPosition thing does not protect us from returning a stale entry.
            if (decl.getEnclosingType() == null) {
               srcFile = getSrcFileFromTypeName(typeName, true, fromLayer, prependPackage, null);
               if (srcFile == null || decl.getLayer() != srcFile.layer) {
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

      if (srcFile == null)
         srcFile = getSrcFileFromTypeName(typeName, true, fromLayer, prependPackage, null, refLayer, layerResolve);

      if (srcFile == null && layerResolve) {
         Layer layer = getLayerByDirName(typeName);
         if (layer != null && layer.model != null) {
            decl = layer.model.getModelTypeDeclaration();
            if (decl != null)
               return decl;
         }
         layer = refLayer == null || refLayer.activated ? pendingActiveLayers.get(typeName) : pendingInactiveLayers.get(typeName);
         if (layer != null && layer.model != null) {
            decl = layer.model.getModelTypeDeclaration();
            if (decl != null)
               return decl;
         }
      }

      if (srcFile != null) {
         // When notHidden is set, we do not load types which are in hidden layers
         if (notHidden && !srcFile.layer.getVisibleInEditor())
            return null;

         if (!srcOnly) {
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
         //else
         //   System.out.println(StringUtil.indent(nestLevel) + "using cached model " + typeName);
         decl = getCachedTypeDeclaration(typeName, fromLayer, fromLayer == null ? srcFile.layer : null, false, false);
         if (decl == INVALID_TYPE_DECLARATION_SENTINEL)
            return null;

         if ((decl == null || decl == skippedDecl) && srcFile.layer != null && modelObj instanceof JavaModel) {
            JavaModel javaModel = (JavaModel) modelObj;
            if (javaModel.getModelTypeName().equals(typeName))
               return javaModel.getLayerTypeDeclaration();
         }
         return decl;
         // else - we found this file in the model index but did not find it from this type name.  This happens
         // when the type name matches, but the package prefix does not.
      }
      else if (!rootTypeOnly) {
         return getInnerClassDeclaration(typeName, fromLayer, notHidden, srcOnly, refLayer, layerResolve);
      }
      else
         return null;
   }

   /** Returns false if there's no entry with this name - explicitly without parsing it if it's not already loaded */
   public boolean hasAnyTypeDeclaration(String typeName) {
      TypeDeclaration decl = getCachedTypeDeclaration(typeName, null, null, true, false);
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

   // TODO: can we just remove this whole method now that there's a srcOnly parameter to getSrcTypeDclaration - just call that?
   // First if we've loaded the src we need to return that.
   public Object getTypeDeclaration(String typeName, boolean srcOnly, Layer refLayer, boolean layerResolve) {
      boolean beingReplaced = false;
      if (cacheForRefLayer(refLayer)) {
         TypeDeclaration decl = getCachedTypeDeclaration(typeName, null, null, true, false);
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
      if (td == null)
         return getClassWithPathName(typeName, refLayer, layerResolve, false);
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

               // Force this to false for templates that are evaluated without converting to Java first
               template.generateOutputMethod = false;

               // initialize and start that model
               ParseUtil.initComponent(template);
               ParseUtil.startComponent(template);
               ParseUtil.validateComponent(template);

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

   private boolean hasAnySrcForRootType(String typeName) {
      do {
         String rootTypeName = CTypeUtil.getPackageName(typeName);
         if (rootTypeName != null) {
            if (getSrcFileFromTypeName(rootTypeName, true, null, true, null) != null)
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
      return refLayer != Layer.ANY_LAYER && refLayer != Layer.ANY_INACTIVE_LAYER && refLayer.activated;
   }

   private static boolean searchActiveTypesForLayer(Layer refLayer) {
      return refLayer == null || refLayer.activated || refLayer == Layer.ANY_LAYER;
   }

   private static boolean searchInactiveTypesForLayer(Layer refLayer) {
      return refLayer != null && !refLayer.disabled && (!refLayer.activated || refLayer == Layer.ANY_LAYER || refLayer == Layer.ANY_INACTIVE_LAYER);
   }

   /** Try peeling away the last part of the type name to find an inner type */
   Object getInnerClassDeclaration(String typeName, Layer fromLayer, boolean notHidden, boolean srcOnly, Layer refLayer, boolean layerResolve) {
      String rootTypeName = typeName;
      String subTypeName = "";
      TypeDeclaration decl;

      if (fromLayer == null && cacheForRefLayer(refLayer)) {
         Object res = innerTypeCache.get(typeName);
         if (res != null) {
            if (srcOnly && res instanceof Class) {
               if (!hasAnySrcForRootType(typeName))
                  return null;
               else
                  innerTypeCache.remove(typeName);
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
               rootType = getSrcTypeDeclaration(rootTypeName, rootFrom, true, notHidden, true, refLayer == null ? fromLayer : refLayer, layerResolve, true);

            if (rootType == null && layerResolve && rootTypeName.equals(LayerConstants.LAYER_COMPONENT_FULL_TYPE_NAME)) {
               List<Layer> layersList = refLayer == null ? layers : refLayer.getLayersList();
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
               Object declObj = rootType instanceof BodyTypeDeclaration ? ((BodyTypeDeclaration) rootType).getInnerType(subTypeName, fromLayer != null ? new TypeContext(fromLayer, rootIsSameType) : null, true, false, srcOnly) :
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
                     // Replace this type if it has been modified by a component in a subsequent layer
                     if (decl.replacedByType != null)
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
                     innerTypeCache.put(typeName, decl);
                  return decl;
               }
               else if (declObj != null) {
                  if (!srcOnly) {
                     if (fromLayer == null && cacheForRefLayer(refLayer))
                        innerTypeCache.put(typeName, declObj);
                     return declObj;
                  }
                  // We need source but we got back a compiled inner type.  If it's inherited, maybe we can load the source.  Need to be careful here
                  // not to do this if we already have the src because there are cases where we have the source for the outer class but not the inner.
                  else if (!ModelUtil.sameTypes(rootType, ModelUtil.getEnclosingType(declObj)) && subTypeName.indexOf(".") == -1) {
                     // Otherwise, this can infinite loop
                     if (hasAnySrcForRootType(typeName)) {
                        // TODO: remove this once we've figured out all of the cases properly.  At some point we need to verify that we've got the
                        // source for the parent types of declObj and tried to find the inner type on that.  When that returns null, we bail.
                        // One weird case is where the annotation layer is source but we don't have the source for the inner class.  That's handled by the
                        // subTypeName.indexOf(".") test above.
                        if (++loopCt > 20) {
                           warning("loop finding inner type.");
                           loopCt = 0;
                           return null;
                        }
                        Object newDeclObj = ModelUtil.resolveSrcTypeDeclaration(this, declObj);
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
         innerTypeCache.put(typeName, INVALID_TYPE_DECLARATION_SENTINEL);
      }
      return null;
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

   public Object getRelativeTypeDeclaration(String typeName, String packagePrefix, Layer fromLayer, boolean prependPackage, Layer refLayer, boolean layerResolve) {
      // TODO: Should we first be trying packagePrefix+typeName in the global cache?
      SrcEntry srcFile = getSrcFileFromRelativeTypeName(typeName, packagePrefix, true, fromLayer, prependPackage, refLayer, layerResolve);
      
      if (srcFile != null) {
         // When prependLayerPackage is false, lookup the type without the layer's package (e.g. doc/util.vdoc)
         String fullTypeName = srcFile.getTypeName();
         ILanguageModel loading = beingLoaded.get(srcFile.absFileName);
         // If we did not specify a limit to the layer, use the current file's layer.  If we add a new layer we'll
         // only reload if there is a file for this type in that new layer.
         TypeDeclaration decl = getCachedTypeDeclaration(fullTypeName, fromLayer, fromLayer == null ? srcFile.layer : null, true, false);
         if (decl != null) {
            if (decl == INVALID_TYPE_DECLARATION_SENTINEL)
               return null;
            if (loading == null)
               return decl;
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
            return getRelativeTypeDeclaration(typeName, packagePrefix, srcFile.layer, prependPackage, refLayer, layerResolve);
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
            // When we load the model through the IDE, it does not get put into the type cache reliably
            ITypeDeclaration modelType = langModel.getModelTypeDeclaration();
            if (modelType != null && modelType.getFullTypeName().equals(fullTypeName))
               return modelType;
         }
         return null;
      }
      return getInnerClassDeclaration(typeName, fromLayer, false, false, refLayer, layerResolve);
   }

   public Object parseInactiveFile(SrcEntry srcEnt) {
      // First try to find the JavaModel cached outside of the system.
      if (externalModelIndex != null) {
         ILanguageModel model = externalModelIndex.lookupJavaModel(srcEnt);
         if (model != null)
            return model;
      }
      return parseSrcFile(srcEnt, srcEnt.isLayerFile(), true, false, true);
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
      // First try to find the JavaModel cached outside of the system.
      if (externalModelIndex != null) {
         // If we are loading it don't try to load it again
         model = beingLoaded.get(srcEnt.absFileName);
         if (model == null) {
            model = externalModelIndex.lookupJavaModel(srcEnt);
            if (model != null) {
               inactiveModelIndex.put(srcEnt.absFileName, model);
            }
         }
         if (model instanceof JavaModel)
            return ((JavaModel) model).getModelTypeDeclaration();
      }
      model = inactiveModelIndex.get(srcEnt.absFileName);
      if (model == null) {
         Object result = parseSrcFile(srcEnt, srcEnt.isLayerFile(), true, false, !options.disableCommandLineErrors);
         // We might have to load files for inactive types which the IDE is not maintaining.  So we always cache these
         // models in the local index so we avoid loading them over and over again.
         if (result instanceof ILanguageModel) {
            model = (ILanguageModel) result;
            inactiveModelIndex.put(srcEnt.absFileName, model);
         }
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
      List<Layer> layerList = fromLayer == null ? refLayer == null ? layers : refLayer.getLayersList() : fromLayer.getLayersList();
      int startIx = fromLayer == null ? layerList.size() - 1 : fromLayer.getLayerPosition();
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
      String subPath = typeName.replace(".", FileUtil.FILE_SEPARATOR);
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
         if (fromLayer != null && !fromLayer.activated && fromLayer.layerPosition - 1 < inactiveLayers.size())
            startIx = fromLayer.layerPosition - 1;
         for (int i = startIx; i >= 0; i--) {
            Layer inactiveLayer = inactiveLayers.get(i);
            //SrcEntry ent = inactiveLayer.getInheritedSrcFileFromTypeName(typeName, srcOnly, prependPackage, subPath, processDefinition, layerResolve);
            SrcEntry ent = inactiveLayer.getSrcFileFromTypeName(typeName, srcOnly, prependPackage, subPath, layerResolve);
            if (ent != null) {
               return ent;
            }
         }
      }

      return null;
   }

   /**
    * Returns the Java file that defines this type.  If srcOnly is false, it will also look for the file
    * which defines the class if no Java file is found.  You can use that mode to get the file which
    * defines the type for dependency purposes.
    */
   public SrcEntry getSrcFileFromRelativeTypeName(String typeName, String packagePrefix, boolean srcOnly, Layer fromLayer, boolean prependPackage, Layer refLayer, boolean layerResolve) {
      String relDir = packagePrefix != null ? packagePrefix.replace(".", FileUtil.FILE_SEPARATOR) : null;
      String subPath = typeName.replace(".", FileUtil.FILE_SEPARATOR);

      if (searchInactiveTypesForLayer(refLayer)) {
         SrcEntry lastEnt = null;
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
               SrcEntry ent = inactiveLayer.getSrcFileFromRelativeTypeName(relDir, subPath, packagePrefix, srcOnly, false, layerResolve);
               if (ent != null) {
                  return ent;
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
      return getClassWithPathName(pathName, null, false, false);
   }

   public Object getClassWithPathName(String pathName, Layer refLayer, boolean layerResolve, boolean alwaysCheckInnerTypes) {
      Object c = getClass(pathName, layerResolve);
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
         if (alwaysCheckInnerTypes || systemCompiled || !toBeCompiled(nextRoot, refLayer, layerResolve)) {
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
      return ModelUtil.getSuperclass(type);
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
    * it will find the file and parse the .class file into a lightweight data structure.
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
         if (resObj != null)
            return resObj;
      }

      if (!options.crossCompile || forceClass) {
         // Check the system class loader first
         res = RTypeUtil.loadClass(getSysClassLoader(), className, false);
         if (res != null) {
            compiledClassCache.put(className, res);
            return res;
         }
      }

      String classFileName = className.replace(".", FileUtil.FILE_SEPARATOR) + ".class";
      if (!forceClass) {
         Object resObj = getClassFromClassFileName(classFileName, className);
         if (resObj != null)
            return resObj;
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

   public Object getClassFromCFName(String cfName, String className) {
      Object res = otherClassCache.get(className);
      if (res == NullClassSentinel.class)
         return null;
      if (res != null)
         return res;
      
      String classFileName = cfName + ".class";
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
            if (res == null)
               res = NullClassSentinel.class;
            otherClassCache.put(className, res);
            return res;
         }
         else {
            if (ent.zip) {
               // We've already opened this zip file during the class path package scan.  My thinking is that
               // will hold lots of classes in memory if we leave those zip files open since folks tend to have
               // a lot of crap in their classpath they never use.  Instead, wait for us
               // to first reference a file in that actual zip before we cache and hold it open.  This does mean
               // that we are caching files twice.
               ZipFile zip = getCachedZipFile(ent.fileName);
               res = CFClass.load(zip, classFileName, this);
            }
            else {
               res = CFClass.load(ent.fileName, classFileName, this);
            }
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

   private static class NullClassSentinel {
   }

   public Object resolveRuntimeName(String name, boolean create) {
      if (systemCompiled) {
         Object c = getClassWithPathName(name);
         if (c != null) {
            if (create && c instanceof Class) {
               Class cl = (Class) c;

               // Need to make sure this class is initialized here
               RTypeUtil.loadClass(cl.getClassLoader(), name, true);

               if (IDynObject.class.isAssignableFrom((Class) cl)) {
                  Object srcName = resolveName(name, create);
                  if (srcName != null)
                     return srcName;
               }
            }
            return c;
         }
      }
      return resolveName(name, create);
   }

   public Object resolveName(String name, boolean create) {
      // Using last layer here as the refLayer so we pick up active objects
      return resolveName(name, create, lastLayer, false);
   }

   public Object resolveName(String name, boolean create, Layer refLayer, boolean layerResolve) {
      Object val = globalObjects.get(name);
      if (val != null)
         return val;

      if (layerResolve) {
         Map<String,Layer> pendingLayers = refLayer.activated ? pendingActiveLayers : pendingInactiveLayers;
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
         if (td.isDynamicType() || td.isDynamicStub(true)) {
            nextClassObj = rootType;

            if (create) {
               if (rootType.getDeclarationType() == DeclarationType.OBJECT) {
                  nextObj = rootType.createInstance();
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
            if (nextClass != null)
               nextObj = TypeUtil.getPossibleStaticValue(nextClass, CTypeUtil.decapitalizePropertyName(CTypeUtil.getClassName(rootName)));
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
                        nextObj = mapper.getPropertyValue(null);
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
                  nextObj = DynUtil.getProperty(nextObj, nextProp);
               }

               pathIx = nextIx+1;
            } while (nextIx != -1);
         }

         Object result = nextObj != null ? nextObj : nextClassObj;

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


      Object c = getClassWithPathName(name, refLayer, layerResolve, false);
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
      Object modelObj = addType ? parseSrcType(defFile, null, true, reportErrors) : parseSrcFile(defFile, true, false, false, reportErrors);
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
      }
      model = parseLayerModel(defFile, expectedName, true, lpi.activate);

      if (model == null) {
         // There's a layer definition file that cannot be parsed - just create a stub layer that represents that file
         if (!lpi.activate && defFile.canRead()) {
            Layer stubLayer = lpi.activate ? pendingActiveLayers.get(expectedName) : pendingInactiveLayers.get(expectedName);
            if (stubLayer == null)
               stubLayer = new Layer();
            stubLayer.activated = false;
            stubLayer.layeredSystem = this;
            stubLayer.layerPathName = expectedName;
            //stubLayer.layerBaseName = ...
            stubLayer.layerDirName = layerDirName;
            return stubLayer;
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
         String err = "No layer definition in file: " + defFile + " exepcted file to contain: " + expectedName + " {  }";
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
            mainLayerDir = newLayerDir = mapLayerDirName(relDir);
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
         String errMessage = "Layer definition file: " + model.getSrcFile() + " expected to modify type name: " + expectedName + " but found: " + modelType.typeName + " instead";
         if (lpi.activate)
            throw new IllegalArgumentException(errMessage);
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
         throw new IllegalArgumentException("Layer definition file: " + defFile + " should not have an operator like 'class' or 'object'.  Just a name (i.e. a modify definition)");
      }

      Class runtimeClass = decl.getCompiledClass();
      if (runtimeClass != null && !expectedClass.isAssignableFrom(runtimeClass))
         throw new IllegalArgumentException("Layer component file should modify the layer class with: '" + expectedName + " {'.  Found class: " + runtimeClass + " after parsing: " + defFile);

      List<Layer> baseLayers = null;
      boolean initFailed = false;
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
            baseLayers = mapLayerNamesToLayers(relPath, baseLayerNames, lpi.activate);
         }
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

      /* Now that we have defined the base layers, we'll inherit the package prefix and dynamic state */
      boolean baseIsDynamic = Layer.getBaseIsDynamic(baseLayers);
      boolean inheritedPrefix = Layer.getInheritedPrefix(baseLayers, prefix, model);

      prefix = model.getPackagePrefix();
      if (!lpi.activate)
         model.setDisableTypeErrors(true);

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
         if (!lpi.activate) {
            Layer stubLayer = lpi.activate ? pendingActiveLayers.get(expectedName) : pendingInactiveLayers.get(expectedName);
            if (stubLayer == null)
               stubLayer = new Layer();
            stubLayer.activated = false;
            stubLayer.layeredSystem = this;
            stubLayer.layerPathName = expectedName;
            //stubLayer.layerBaseName = ...
            stubLayer.layerDirName = layerDirName;
            System.err.println("*** failed to initialize inactive layer: ");
            exc.printStackTrace();
            return stubLayer;
         }
         System.err.println("*** Failed to initialize layer: " + expectedName + " due to runtime error: " + exc);
         if (options.verbose)
            exc.printStackTrace();
      }
      finally {
         if (clearInitLayers)
            initializingLayers = false;
         if (!lpi.activate)
            model.setDisableTypeErrors(false);
      }
      return null;
   }

   List<Layer> mapLayerNamesToLayers(String relPath, List<String> baseLayerNames, boolean activated) {
      List<Layer> baseLayers = new ArrayList<Layer>();
      if (baseLayerNames == null)
         return null;
      for (int li = 0; li < baseLayerNames.size(); li++) {
         String baseLayerName = baseLayerNames.get(li);
         Layer bl = activated ? findLayerByName(relPath, baseLayerName) : getInactiveLayer(baseLayerName.replace("/", "."), false, false, false);
         if (bl != null)
            baseLayers.add(bl);
         else {
            // For this case we may be in the JS runtime and this layer is server specific...
            //baseLayers.add(null);
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

   static int readLocked, writeLocked;

   public void acquireDynLock(boolean readOnly) {
      long startTime = 0;
      if (options.verbose || options.verboseLocks)
         startTime = System.currentTimeMillis();
      if (options.verboseLocks) {
         System.out.println("Acquiring system dyn lock for read-only: " + readOnly + " thread: " + Thread.currentThread().getName() + ": runtime: " + getRuntimeName());
      }
      if (!readOnly) {
         globalDynLock.writeLock().lock();
         writeLocked++;
      }
      else {
         globalDynLock.readLock().lock();
         readLocked++;
      }
      if (options.verboseLocks || options.verbose) {
         long duration = System.currentTimeMillis() - startTime;
         if (duration > 1000) {
            System.err.println("Warning: waited: " + duration + " millis for lock on thread: " + Thread.currentThread().getName());
            if (duration > 2000 && Thread.currentThread().getName().contains("AWT-Event")) {
               System.err.println("*** stack of waiting thread: ");
               new Throwable().printStackTrace(System.err);
            }
         }
      }
   }

   public void releaseDynLock(boolean readOnly) {
      if (options.verboseLocks)
         System.out.println("Releasing system dyn lock for read-only: " + readOnly + " thread: " + Thread.currentThread().getName() + ": runtime: " + getRuntimeName());
      if (!readOnly) {
         writeLocked--;
         globalDynLock.writeLock().unlock();
      }
      else {
         readLocked--;
         globalDynLock.readLock().unlock();
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
         res[i] = resolveName(tgms.get(i).typeName, true);
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
            }
         }
      }
      instMap.put(inst, Boolean.TRUE);
   }

   public boolean removeDynInstance(String typeName, Object inst) {
      if (getLiveDynamicTypes(typeName)) {
         WeakIdentityHashMap<Object,Boolean> instMap = instancesByType.get(typeName);
         if (instMap != null) {
            Object res = instMap.remove(inst);
            if (res == null)
               warning("*** Can't find dyn instance to remove for type: " + typeName);
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
         Object type = DynUtil.getType(obj);
         if (type != null) {
            String typeName = ModelUtil.getTypeName(type);
            removeDynInstance(typeName, obj);
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
   public int getNumInnerObjectLevels(Object obj) {
      //if (!objectNameIndex.containsKey(obj))
      //   return 0; // Not an outer object
      Object outer = DynUtil.getOuterObject(obj);
      if (outer == null) {
         return 0; // Top level object - also not an inner object
      }
      return 1 + getNumInnerObjectLevels(outer);
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

   private Set<Object> addSubTypes(TypeDeclaration td, WeakIdentityHashMap<Object,Boolean> insts, Set<Object> res) {
      Iterator<TypeDeclaration> subTypes = getSubTypesOfType(td);
      if (subTypes != null) {
         while (subTypes.hasNext()) {
            TypeDeclaration subType = subTypes.next();

            WeakIdentityHashMap<Object,Boolean> subInsts = instancesByType.get(subType.getFullTypeName());
            if (subInsts != null) {
               if (insts == null)
                  insts = subInsts;
               else {
                  if (res == null) {
                     res = new HashSet<Object>();
                     res.addAll(insts.keySet());
                  }
                  else if (!(res instanceof HashSet)) {
                     HashSet<Object> newRes = new HashSet<Object>();
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

   private static final EmptyIterator<TypeDeclaration> NO_TYPES = new EmptyIterator<TypeDeclaration>();
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
         HashMap<String,Boolean> subTypeMap = subTypesByType.get(superType.getFullTypeName());
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

   public Iterator<TypeDeclaration> getSubTypesOfType(TypeDeclaration type) {
      return getSubTypesOfType(type, true, false);
   }

   /**
    * Returns the sub-types of the specified type.  If activeOnly is true, only those types active in this system are checked.  If activeOnly is false
    * and checkPeers is true, the type name is used to find sub-types in the peer systems as well.
    */
   public Iterator<TypeDeclaration> getSubTypesOfType(TypeDeclaration type, boolean activeOnly, boolean checkPeers) {
      String typeName = type.getFullTypeName();
      HashMap<String,Boolean> subTypesMap = subTypesByType.get(typeName);
      if (activeOnly && subTypesMap == null)
         return NO_TYPES;
      ArrayList<TypeDeclaration> result = new ArrayList<TypeDeclaration>(subTypesMap == null ? 0 : subTypesMap.size());

      if (subTypesMap != null) {
         for (String subTypeName:subTypesMap.keySet()) {
            if (type.isLayerType()) {
               Layer layer = getActiveOrInactiveLayerByPath(subTypeName.replace(".", "/"), null, true);
               if (layer != null) {
                  result.add(layer.model.getModelTypeDeclaration());
               }
            }
            else {
               TypeDeclaration res = (TypeDeclaration) getSrcTypeDeclaration(subTypeName, null, true, false, true, type.getLayer(), type.isLayerType);
               // Check the resulting class name.  getSrcTypeDeclaration may find the base-type of an inner type as it looks for inherited inner types under the parent type's name
               if (res != null && res.getFullTypeName().equals(subTypeName))
                  result.add(res);
            }
         }
      }

      if (!activeOnly) {
         if (checkPeers && !peerMode && peerSystems != null) {
            for (int i = 0; i < peerSystems.size(); i++) {
               Iterator<TypeDeclaration> peerRes = getSubTypesOfType(type, false, false);
               if (peerRes != null) {
                  while (peerRes.hasNext())
                     result.add(peerRes.next());
               }
            }
         }
         LinkedHashMap<String,TypeIndex> subTypes = typeIndex.inactiveTypeIndex.subTypeIndex.get(typeName);
         if (subTypes != null) {
            for (Map.Entry<String,TypeIndex> subTypeEnt:subTypes.entrySet()) {
               String subTypeName = subTypeEnt.getKey();
               TypeIndex subTypeIndex = subTypeEnt.getValue();

               if (type.isLayerType) {
                  Layer subLayer = getActiveOrInactiveLayerByPath(subTypeName.replace(".", "/"), null, true);
                  if (subLayer != null && subLayer.model != null)
                     result.add(subLayer.model.getModelTypeDeclaration());
               }
               else {
                  TypeDeclaration res = (TypeDeclaration) getSrcTypeDeclaration(subTypeName, null, true, false, true, type.getLayer(), type.isLayerType);
                  // Check the resulting class name.  getSrcTypeDeclaration may find the base-type of an inner type as it looks for inherited inner types under the parent type's name
                  if (res != null) {
                     if (res.getFullTypeName().equals(subTypeName))
                        result.add(res);
                  } else {
                     if (subTypeIndex != null) {
                        Layer subTypeLayer = getInactiveLayer(subTypeIndex.layerName, true, true, true);

                        if (subTypeLayer != null) {
                           // We may not find this sub-type in this system because we share the same type index across all processes in the runtime.  It might be
                           // a different runtime.
                           res = (TypeDeclaration) getSrcTypeDeclaration(subTypeName, null, true, false, true, type.getLayer(), type.isLayerType);
                           if (res != null && res.getFullTypeName().equals(subTypeName))
                              result.add(res);
                        } else {
                           // TODO: else cull this from the index?
                           System.err.println("*** Warning: Layer removed?" + subTypeIndex.layerName);
                        }
                     }
                  }
               }
            }
         }
      }
      return result.iterator();
   }

   public ArrayList<TypeDeclaration> getModifiedTypesOfType(TypeDeclaration type, boolean before, boolean checkPeers) {
      TreeSet<String> checkedTypes = new TreeSet<String>();
      ArrayList<TypeDeclaration> res = new ArrayList<TypeDeclaration>();

      typeIndex.addModifiedTypesOfType(getProcessIdent(), this, type, before, checkedTypes, res);

      if (checkPeers && !peerMode && peerSystems != null) {
         ArrayList<LayeredSystem> peerSystemsCopy = (ArrayList<LayeredSystem>) peerSystems.clone();
         for (LayeredSystem peerSys:peerSystemsCopy) {
            checkedTypes.add(peerSys.getProcessIdent());
            ArrayList<TypeDeclaration> peerTypes = peerSys.getModifiedTypesOfType(type, before, false);
            if (peerTypes != null)
               res.addAll(peerTypes);
         }
      }

      // Now check the TypeIndex entries for any processes for which we have not yet created layeredSystems.  Create them if
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

   private static class TypeDeclarationCacheEntry extends ArrayList<TypeDeclaration> {
      int fromPosition;
      boolean reloaded = false;
      SemanticNodeList<ImportDeclaration> autoImports;
      Map<String,AutoImportEntry> autoImportsIndex;

      public TypeDeclarationCacheEntry(int size) {
         super(size);
      }

      public void clear() {
          super.clear();
          fromPosition = -1;
      }
   }

   // Keeps track of which layer a particular auto-import was derived from so that as we add layers we
   // keep the most recent import in case the base names of the import conflict.
   private static class AutoImportEntry {
      int layerPosition;
      ImportDeclaration importDecl;
   }

   private static class SrcDirEntry {
      DependencyFile deps;
      File depFile;
      Layer layer;
      boolean depsChanged;
      boolean fileError;
      boolean anyError;
      LinkedHashSet<ModelToTransform> modelsToTransform;
      public String srcPath;

      public String toString() {
         return "layer: " + layer + "/" + srcPath;
      }
   }

   private static class ModelToPostBuild {
      IFileProcessorResult model;
      Layer genLayer;
   }

   private static class ModelToTransform {
      IFileProcessorResult model;
      SrcEntry toGenEnt;
      boolean generate;

      public boolean equals(Object other) {
         if (!(other instanceof ModelToTransform))
            return false;
         ModelToTransform om = (ModelToTransform) other;
         if (toGenEnt == null || om.toGenEnt == null)
            return false;
         return om.toGenEnt.absFileName.equals(toGenEnt.absFileName);
      }

      public int hashCode() {
         return toGenEnt == null ? 0 : toGenEnt.absFileName.hashCode();
      }
   }

   private static class TypeGroupDep {
      String relFileName;
      String typeName;
      String typeGroupName;

      private TypeGroupDep(String relFn, String tn, String tgn) {
         relFileName = relFn;
         typeName = tn;
         typeGroupName = tgn;
      }
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
   public void addTypeGroupDependency(String relFileName, String typeName, String typeGroupName) {
      typeGroupDeps.add(new TypeGroupDep(relFileName, typeName, typeGroupName));
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
      // everything.  Evenetually a file system watcher will make this more incremental.
      refreshRuntimes();

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

   public Layer getInactiveLayerSync(String layerPath, boolean checkPeers, boolean enabled, boolean skipExcluded) {
      try {
         acquireDynLock(false);

         return getInactiveLayer(layerPath, checkPeers, enabled, skipExcluded);
      }
      finally {
         releaseDynLock(false);
      }
   }

   /**
    * Retrieves a Layer instance given the layer name - i.e. group.name.  If checkPeers = true, you may receive a layer in a runtime (that is lazily created the first time it's needed).
    * Pass in true unless you explicitly do not want to enable the layer.  Enabling the layer adds it to to the classpath so types in the layer can be starte so types in the layer can be started
    * Pass in true unless you want to get the excluded layer from this runtime which
    * we create temporarily to bootstrap layers in other runtimes.   We first create the Layer in the original runtime to see if it's excluded or not.  If so, we remove from the list.
    */
   public Layer getInactiveLayer(String layerPath, boolean checkPeers, boolean enabled, boolean skipExcluded) {
      String layerName = layerPath.replace("/", ".");
      Layer inactiveLayer = lookupInactiveLayer(layerPath, checkPeers, skipExcluded);
      if (inactiveLayer != null) {
         if (inactiveLayer.layeredSystem == this || (inactiveLayer.excludeForProcess(processDefinition) || !inactiveLayer.includeForProcess(processDefinition)))
            return inactiveLayer;
      }

      if (disabledLayersIndex != null) {
         inactiveLayer = disabledLayersIndex.get(layerName);
         if (inactiveLayer != null)
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
            return completeNewInactiveLayer(layer, checkPeers);
         }
         else if (getNewLayerDir() != null) {
            Layer res = initLayer(layerPath, getNewLayerDir(), null, false, lpi);
            if (res != null) {
               return completeNewInactiveLayer(res, checkPeers);
            }
         }
      }
      finally {
         if (clearInitLayers)
            initializingLayers = false;
      }
      return null;
   }

   private Layer completeNewInactiveLayer(Layer layer, boolean doBuild) {
      layer.ensureInitialized(true);

      // First mark any excluded layers with the excluded flag so we know they do not belong in this runtime.
      // We leave the excluded layers in place through initRuntimes though so we can use these excluded layers
      // to find the runtimes in which they belong.
      removeExcludedLayers(true);

      // For any buildSeparate layers that are needed in this runtime, add them over to 'layers'
      // TODO: remove this - we used to do this but now instead resolve src refs for layerResolve = true for buildSeparate layers
      //startSeparateLayers();

      // We just created a new layer so now go and re-init the runtimes in case it is the first layer in
      // a new runtime or this layer needs to move to the runtime before it's started.
      if (!peerMode) {
         initRuntimes(null, false);
      }

      removeExcludedLayers(false);

      checkLayerPosition();

      boolean foundInPeer = false;

      if (!peerMode && peerSystems != null) {
         for (int i = 0; i < peerSystems.size(); i++) {
            LayeredSystem peerSys = peerSystems.get(i);

           // Need to also load this layer and any dependent layers as an inactive layers into the other runtimes if they are needed there.
            if (layer.includeForProcess(peerSys.processDefinition)) {
               Layer peerRes = peerSys.getInactiveLayer(layer.getLayerName(), false, !layer.disabled, false);
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
      if (newRes != layer) {
         if (newRes != null)
            return newRes;
         else {
            LayeredSystem mainSys = getMainLayeredSystem();
            mainSys.disabledLayersIndex.put(layer.getLayerName(), layer);
            layer.layerPosition = mainSys.disabledLayers.size();
            mainSys.disabledLayers.add(layer);
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

   public Layer getActiveOrInactiveLayerByPathSync(String layerPath, String prefix, boolean enabled) {
      try {
         acquireDynLock(false);
         return getActiveOrInactiveLayerByPath(layerPath, prefix, enabled);
      }
      finally {
         releaseDynLock(false);
      }
   }

   public Layer getActiveOrInactiveLayerByPath(String layerPath, String prefix, boolean enabled) {
      Layer layer;
      String origPrefix = prefix;
      String usePath = layerPath;
      do {
         layer = getLayerByPath(usePath, true);
         if (layer == null) {
            // Layer does not have to be active here - this lets us parse the code in the layer but not really start, transform or run the modules because the layer itself is not started
            layer = getInactiveLayer(usePath, true, enabled, false);
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

   public Layer getInactiveLayerByPath(String layerPath, String prefix, boolean enabled) {
      Layer layer;
      String origPrefix = prefix;
      String usePath = layerPath;
      do {
         // Layer does not have to be active here - this lets us parse the code in the layer but not really start, transform or run the modules because the layer itself is not started
         layer = getInactiveLayer(usePath, true, enabled, false);

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

         Layer layer = getInactiveLayerByPath(layerPath, prefix, true);
         if (layer != null && layer.model != null)
            return parseInactiveModel(layer.model.getSrcFile());
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
      if (layerPathDirs == null)
         addLayerPathToAllIndex(System.getProperty("user.dir"));
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
      addLayerPathToAllIndex(pathName, null, new DirectoryFilter());

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
               String expectedName = layerName.replace(FileUtil.FILE_SEPARATOR, ".");
               try {
                  String relFile = fileName + SCLanguage.STRATACODE_SUFFIX;
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

   public class DirectoryFilter implements FilenameFilter {
      public DirectoryFilter() {
      }

      public boolean accept(File dir, String fileInDir) {
         return new File(dir, fileInDir).isDirectory();
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

   private void addInitTypes(List<InitTypeInfo> res, String groupName, boolean doStartup) {
      sc.layer.BuildInfo bi = currentBuildLayer.buildInfo;
      List<TypeGroupMember> initTypes = bi.getTypeGroupMembers(groupName);
      if (initTypes != null) {
         for (TypeGroupMember memb:initTypes) {
            Object type = memb.getType();
            Integer priority = 0;
            if (type != null) {
               priority = (Integer) ModelUtil.getAnnotationValue(type, "sc.obj.CompilerSettings", "startPriority");
               if (priority == null)
                  priority = 0;
            }
            InitTypeInfo it = new InitTypeInfo();
            it.initType = memb;
            it.priority = priority;
            it.doStartup = doStartup;
            res.add(it);
         }
      }
   }

   /** Returns the types that need to be started in the proper order */
   public List<InitTypeInfo> getInitTypes() {
      ArrayList<InitTypeInfo> res = new ArrayList<InitTypeInfo>();

      addInitTypes(res, "_init", false);
      addInitTypes(res, "_startup", true);

      // Want them to go from high to low as higher priority should go first right?
      Collections.reverse(res);
      return res;
   }

   public boolean getCompiledOnly() {
      return runtimeProcessor != null && runtimeProcessor.getCompiledOnly();
   }

   public String toString() {
      return "LayeredSystem: " + getProcessIdent() + (!peerMode ? " (main)" : "") + (anyErrors ? " (errors)" : "");
   }

   /** Returns all of the declared URL top-level system types, including those in the mainInit and URLTypes type groups. */
   public List<URLPath> getURLPaths() {
      List<TypeGroupMember> mainInitMembers = buildInfo.getTypeGroupMembers("mainInit");
      List<TypeGroupMember> urlTypes = buildInfo.getTypeGroupMembers("URLTypes");
      if (mainInitMembers == null && urlTypes == null)
         return null;
      ArrayList<URLPath> res = new ArrayList<URLPath>();
      if (mainInitMembers != null) {
         for (TypeGroupMember memb:mainInitMembers) {
            // Skip mainInit types which also have URL since we'll process them below
            if (memb.hasAnnotation("sc.html.URL"))
               continue;
            URLPath path = new URLPath(CTypeUtil.getClassName(memb.typeName));
            if (!res.contains(path))
               res.add(path);
         }
      }
      if (urlTypes != null) {
         for (TypeGroupMember memb:urlTypes) {
            // Skip the Html and Page types
            Object sto = memb.getAnnotationValue("sc.html.URL", "subTypesOnly");
            if (sto != null && (Boolean) sto)
               continue;
            // Skip CSS types
            Object resource = memb.getAnnotationValue("sc.html.URL", "resource");
            if (resource != null && (Boolean) resource)
               continue;
            // Skip types which may not be loaded yet
            if (memb.getType() == null)
               continue;
            URLPath path = new URLPath(CTypeUtil.getClassName(memb.typeName));
            // TODO: when the pattern is in the pattern language, we can init the pattern, find the variables and build
            // a form for testing the URL?
            String annotURL = (String) memb.getAnnotationValue("sc.html.URL", "pattern");
            if (annotURL != null)
               path.url = annotURL;
            if (!res.contains(path))
               res.add(path);
         }
      }
      return res;
   }

   public boolean isGlobalScope(String scopeName) {
      // TODO: generalize this as an attribute of the scope
      if (runtimeProcessor instanceof JSRuntimeProcessor && (scopeName.equals("session") || scopeName.equals("window")))
         return true;
      return false;
   }

   private SrcEntry getSrcEntryForPathDir(String layerPath, String pathName, boolean activeLayers) {
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
            layer = activeLayers ? getLayerByPath(parentDirPath, true) : getInactiveLayerByPath(parentDirPath, null, true);
            // This path is for the layer itself
            if (layer != null) {
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
               layer = activeLayers ? getLayerByPath(layerName, true) : getActiveOrInactiveLayerByPath(layerName, null, true);
               if (layer != null) {
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

   public SrcEntry getSrcEntryForPath(String pathName, boolean activeLayers) {
      acquireDynLock(false);

      pathName = FileUtil.makeAbsolute(pathName);

      try {
         List<Layer> layersList = activeLayers ? layers : inactiveLayers;
         for (Layer layer:layersList) {
            SrcEntry layerEnt = layer.getSrcEntry(pathName);
            if (layerEnt != null)
               return layerEnt;
         }
         if (layerPathDirs != null) {
            for (File layerPathDir:layerPathDirs) {
               String layerPath = layerPathDir.getPath();
               if (pathName.startsWith(layerPath)) {
                  SrcEntry ent = getSrcEntryForPathDir(layerPath, pathName, activeLayers);
                  if (ent != null)
                     return ent;
               }

               // In case we crossed over a sym-link, try it as absolute since we often canonicalize the layerPathDirs
               String origPathName = pathName;
               try {
                  pathName = FileUtil.makeCanonical(pathName);
                  if (pathName.startsWith(layerPath)) {
                     SrcEntry ent = getSrcEntryForPathDir(layerPath, pathName, activeLayers);
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
         if (refLayer == null) {
            startIx = inactiveLayers.size() - 1;
            for (int i = startIx; i >= 0; i--) {
               Layer inactiveLayer = inactiveLayers.get(i);

               if (inactiveLayer.annotationProcessors != null) {
                  IAnnotationProcessor res = inactiveLayer.annotationProcessors.get(annotName);
                  if (res != null)
                     return res;
               }
            }
         }
         else {
            return refLayer.getAnnotationProcessor(annotName, true);
         }
      }
      return null;
   }

   public IScopeProcessor getScopeProcessor(Layer refLayer, String scopeName) {
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

            if (layer.scopeProcessors != null) {
               IScopeProcessor res = layer.scopeProcessors.get(scopeName);
               if (res != null)
                  return res;
            }
         }
      }
      if (searchInactiveTypesForLayer(refLayer)) {
         if (refLayer == null) {
            startIx = inactiveLayers.size() - 1;
            for (int i = startIx; i >= 0; i--) {
               Layer inactiveLayer = inactiveLayers.get(i);

               if (inactiveLayer.scopeProcessors != null) {
                  IScopeProcessor res = inactiveLayer.scopeProcessors.get(scopeName);
                  if (res != null)
                     return res;
               }
            }
         }
         else {
            return refLayer.getScopeProcessor(scopeName, true);
         }
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

   public boolean isDisabled() {
      return isRuntimeDisabled(getRuntimeName());
   }

   public boolean isParseable(String fileName) {
      IFileProcessor proc = getFileProcessorForFileName(fileName, null, BuildPhase.Process);
      if (proc != null)
         return proc.isParsed();
      return Language.isParseable(fileName);
   }

   public void addGlobalImports(boolean isLayerModel, String prefix, Set<String> candidates) {
      Map<String,ImportDeclaration> importMap = isLayerModel ? globalLayerImports : globalImports;
      for (String impName:importMap.keySet()) {
         if (impName.startsWith(prefix))
            candidates.add(impName);
      }
   }

}
