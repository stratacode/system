/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.js;

import sc.bind.Bind;
import sc.dyn.DynUtil;
import sc.lang.*;
import sc.lang.html.Element;
import sc.lang.java.*;
import sc.lang.sc.SCModel;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.sc.PropertyAssignment;
import sc.lang.template.Template;
import sc.layer.*;
import sc.obj.ComponentImpl;
import sc.obj.IComponent;
import sc.parser.ParseUtil;
import sc.sync.SyncManager;
import sc.type.CTypeUtil;
import sc.type.RTypeUtil;
import sc.util.FileUtil;
import sc.util.PerfMon;
import sc.util.StringUtil;

import java.io.*;
import java.lang.reflect.TypeVariable;
import java.util.*;

/**
 * This class implements the Java to Javascript conversion process.  It defines a StrataCode runtime environment.
 *
 * The generation works in the following stages:
 *    - the start method is called for each type.  It computes the list of jsFiles - the javascript files we are either generating (jsGenFiles) or js library files (jsLibFiles).
 *    It also builds up the set of entryPoints.  These are types with MainInit or MainSettings annotations that are created when the application is loaded.   jsModuleFile types
 *    are also added to this list.  Essentially it is the set of types which are mapped as 'required' for a given jsFile.
 *    - the getProcessedFiles method is called by the build system.  It generates a .js snippet for each Java class which is stored in the web/js/types directory of the build dir.
 *    Each of these .js snippets is a direct conversion of the Java code for that class only.  It does not contain any dependency info and is not intended to be loaded directly.  Instead those files are
 *    rolled up in the postProcess method into the files to be loaded by the browser.
 *    - the postProcess method is called after all types have been processed.  It generates the jsGenFiles by rolling up the individual .js files in the proper order..  For each changed gen file, it iterates over all of the entrypoints
 *    that are defined to be in that file.  Each entry point first makes sure all of the extends classes which are in the same file get added to the file first.   A base class must be defined before the sub-class is defined.
 *    After the dependent types have been loaded, the entryPoint type is itself added to the file.
 */
public class JSRuntimeProcessor extends DefaultRuntimeProcessor {
   public final static boolean jsDebug = false;

   public static boolean traceSystemUpdates = false;

   public String templatePrefix;
   public String srcPathType;
   /** The prefix for generated individual .js files like Java "one-class-per-file".  This is a normalized path name for portability */
   public String genJSPrefix;

   public transient JSBuildInfo jsBuildInfo;

   private transient boolean entryPointsFrozen = false;
   private transient ArrayList<EntryPoint> queuedEntryPoints;

   public String typeTemplateName;
   public Template typeTemplate;

   /** The syncMergeTemplate nad updateMergeTemplates are used when sending incremental changes during the sync process or type update process (respectively) */
   public String syncMergeTemplateName, updateMergeTemplateName;
   public Template syncMergeTemplate, updateMergeTemplate;

   /** In addition to generating Javascript should we also compile the equivalent Java files?  Though it takes longer, the Java compiler performs more error detection.  Also to generate the initial .html files (when just running the client layers, not using a server), you need the .java files there and compiled so we can use them to evaluate the template. */
   public boolean compileJavaFiles = true;

   /** Set this option to true to generate one big file from your entry points that includes only the classes you use. */
   public boolean disableModules = false;

   public String scLib = "js/sc.js";
   public String jsCoreLib = "js/sccore.js";

   public String prototypeSuffix = "_c";

   private transient HashSet<String> changedJSFiles = new LinkedHashSet<String>();

   private transient boolean changedDefaultType = false;

   private transient HashSet<String> processedTypes = new HashSet<String>();

   private transient HashMap<String,LinkedHashMap<JSFileEntry,Boolean>> typesInFileMap = new HashMap<String,LinkedHashMap<JSFileEntry,Boolean>>();

   private transient HashMap<String,StringBuilder> jsFileBodyStore = new HashMap<String, StringBuilder>();

   /** Set to true in the post start phase - after that phase, any other start models are not part of the build process (e.g. we might load the type declarations because they are init types when generating the page dispatcher)  */
   private transient boolean postStarted = false;

   // For Javascript, we sync against the default runtime
   { syncRuntimes.add(null); }

   /** Used to generate the JS code snippet to prefix all class-based type references */
   public String classPrefix = "<%= typeName %>" + prototypeSuffix;

   /** Used to generate the JS code snippet to instantiate a type, to implement MainInit */
   public String instanceTemplate =
          "<%= needsSync ? \"sc_SyncManager_c.beginSyncQueue();\\n\" : \"\" %>" + // The sync queue is here because we need the children sync-insts to be registered with their parent's names.
          "<% if (objectType) { %>" +
             "var _inst = <%= accessorTypeName %>" + prototypeSuffix + ".get<%= upperBaseTypeName %>();\n<% " +
          "} " +
          "else if (componentType) { " +
             "%>var _inst;\n" +
             "sc_DynUtil_c.addDynObject(\"<%= javaTypeName %>\", _inst = <%= accessorTypeName %>" + prototypeSuffix + ".new<%= className %>());\n<% " +
          "} " +
          "else { " +
             "%>var _inst;\n" +
             "sc_DynUtil_c.addDynObject(\"<%= javaTypeName %>\", _inst = new <%= typeName %>());\n<% " +
          "} " +
          "if (needsSync) { %>" +
             "sc_SyncManager_c.flushSyncQueue();\n" +
             "sc_SyncManager_c.initChildren(_inst);\n" +
          "<% } %>\n";

   public String mainTemplate = "<%= typeName %>" + prototypeSuffix + ".main([]);\n";

   private transient ArrayList<SystemUpdate> systemUpdates = new ArrayList<SystemUpdate>();

   // Placeholder used to track dependencies for all "default types" - those that do not get put into their own module.
   public static final String defaultLib = "<default>";

   public final static String SyncInProgressStartCode = "sc_SyncManager_c.setSyncState(sc_clInit(sc_SyncManager_SyncState_c).ApplyingChanges);\n" +
                                                        "try {\n";


   public final static String SyncBeginCode = "var _ctx = sc_SyncManager_c.beginSync();\n" +
           "try {\n";
   public final static String SyncEndCode =  "}\ncatch (e) {\n" +
           "   console.error('error initializing objects: ' + e);\n" +
           "   e.printStackTrace();\n" +
           "}\nfinally {" +
           "   sc_SyncManager_c.endSync();\n   if (sc_refresh !== undefined) sc_refresh();\n" +
           "}";


   public JSRuntimeProcessor() {
      super("js");
   }

   class SystemUpdate {
      long updateTime;
      String jsUpdate;
   }

   public void addSystemUpdate(String jsUpdate) {
      SystemUpdate update = new SystemUpdate();
      update.jsUpdate = jsUpdate;
      update.updateTime = System.currentTimeMillis();
      systemUpdates.add(update);
   }

   /** Returns the system updates that have occurred since the fromTime */
   public StringBuilder getSystemUpdates(long fromTime) {
      if (systemUpdates.size() == 0)
         return null;
      String fromTimeStr = null;
      if (traceSystemUpdates)
         fromTimeStr = new Date(fromTime).toString();
      StringBuilder sysUpdates = new StringBuilder();
      for (int i = 0; i < systemUpdates.size(); i++) {
         SystemUpdate upd = systemUpdates.get(i);
         if (upd.updateTime >= fromTime) {
            if (traceSystemUpdates)
               System.out.println("Accepting system update applied at: " + new Date(upd.updateTime).toString() + ".  Update occurred after: " + fromTimeStr);
            sysUpdates.append(upd.jsUpdate);
         }
         else if (traceSystemUpdates)
            System.out.println("Skipping system update applied at: " + new Date(upd.updateTime).toString() + ".  Update occurred before: " + fromTimeStr);
      }
      if (sysUpdates.length() > 0) {
         if (traceSystemUpdates) {
            System.out.println("--- Updating client with Javascript for code changes: " + fromTimeStr + ":\n" + sysUpdates + "\n---\n");
         }
         return sysUpdates;
      }
      else if (traceSystemUpdates)
         System.out.println("No system updates after: " + fromTimeStr);

      return null;
   }

   public void applySystemUpdates(UpdateInstanceInfo info) {
      JSUpdateInstanceInfo jsUpdates = (JSUpdateInstanceInfo) info;
      CharSequence updateStr = jsUpdates.convertToJS();
      if (updateStr != null && updateStr.length() > 0)
         addSystemUpdate(updateStr.toString());
   }

   /** The JS runtime does not support enums so tell SC to convert enum's to classes in source */
   public boolean getNeedsEnumToClassConversion() {
      return true;
   }

   public boolean needsSrcForBuildAll(Object cl) {
      String jsModuleFile = getJSModuleFile(cl, false);
      return jsModuleFile != null && jsModuleFile.length() > 0;
   }

   public static abstract class JSLayerable implements Serializable {
      // These track the range of layers in which this gen file is defined
      // From is lower down on the layer stack, i.e. has a smaller value of layerPosition
      String fromLayerName, toLayerName;
      transient Layer fromLayer, toLayer;

      public boolean init(LayeredSystem sys) {
         if (fromLayer == null && fromLayerName != null)
            fromLayer = sys.getLayerByName(fromLayerName);
         if (toLayer == null && toLayerName != null)
            toLayer = sys.getLayerByName(toLayerName);

         if (fromLayer == null) {
            if (toLayer == null)
               return true;
            fromLayer = toLayer;
         }
         if (toLayer == null)
            toLayer = fromLayer;
         return false;
      }


      public void expandToLayer(Layer layer) {
         if (fromLayer == null || fromLayer.getLayerPosition() > layer.getLayerPosition()) {
            fromLayer = layer;
            fromLayerName = layer.getLayerUniqueName();
         }
         else if (toLayer == null || toLayer.getLayerPosition() < layer.getLayerPosition()) {
            toLayer = layer;
            toLayerName = layer.getLayerUniqueName();
         }
      }

      public boolean presentInLayer(Layer layer) {
         if (init(layer.getLayeredSystem()))
            return true;
         return fromLayer.getLayerPosition() < layer.getLayerPosition();
      }
   }

   public static class JSGenFile extends JSLayerable {
   }

   public static class JSTypeInfo extends JSLayerable {
      public boolean needsClassInit = true;
      public boolean hasLibFile = false;
      public String jsModuleFile = null;

      public LinkedHashSet<String> typesInSameFile = null;

      // For non-final types, we might start a modified type, in a subsequent build layer, but not restart the types
      // which depend on them.  For those types, keep the list of types which depend on this type so we can add the
      // file dependencies implied.
      transient public ArrayList<BodyTypeDeclaration> prevDepTypes;
   }

   /**
    * This is the info that is preserved for an incremental build.  For a full build, it gets created from scratch.
    *
    * TODO: currently does not support 'remove' of a type.  We'll need to force a complete build then for this to work.
    */
   public static class JSBuildInfo implements Serializable {
      /** Just library javascript files */
      public HashSet<String> jsLibFiles = new LinkedHashSet<String>();

      /** Just library generated files */
      public HashMap<String,JSGenFile> jsGenFiles = new LinkedHashMap<String,JSGenFile>();

      /** All javascript files - both libraries and generated */
      public ArrayList<String> jsFiles = new ArrayList<String>();

      public HashSet<JSFileDep> typeDeps = new HashSet<JSFileDep>();

      public HashMap<String,String> prefixAliases = new HashMap<String,String>();
      public HashMap<String,List<String>> aliasedPackages = new HashMap<String,List<String>>();

      public HashMap<String,String> replaceTypes = new HashMap<String,String>();

      public HashMap<String,String> jsModuleNames = new HashMap<String,String>();

      /** Map from Java type names to JSTypeInfo for that type */
      public HashMap<String,JSTypeInfo> jsTypeInfo = new HashMap<String,JSTypeInfo>();

      /** Map from JS type names to JSTypeInfo for that type */
      public HashMap<String,JSTypeInfo> jsTypeInfoByJS = new HashMap<String,JSTypeInfo>();

      {
         replaceTypes.put("java.lang.String", "String");
         replaceTypes.put("java.lang.CharSequence", "String");// TODO: ???
         replaceTypes.put("java.lang.Integer", "Number");
         replaceTypes.put("java.lang.Long", "Number");
         replaceTypes.put("java.lang.Short", "Number");
         replaceTypes.put("java.lang.Double", "Number");
         replaceTypes.put("java.lang.Number", "Number");
         replaceTypes.put("java.lang.Float", "Number");
         replaceTypes.put("java.lang.Boolean", "Boolean");
         replaceTypes.put("int", "Number");
         replaceTypes.put("float", "Number");
         replaceTypes.put("double", "Number");
         replaceTypes.put("char", "String");
         replaceTypes.put("boolean", "Boolean");
         replaceTypes.put("byte", "Number");
         replaceTypes.put("long", "Number");
         replaceTypes.put("short", "Number");
         replaceTypes.put("java.lang.Class", "jv_Object"); // TODO
         replaceTypes.put("sc.lang.JLineInterpreter", "sc_EditorContext");

         prefixAliases.put("java.util", "jv_");
      }

      /**
       * Entry points are used to gather only the necessary types for a JS mainInit type, standard Java main method, or a jsModuleFile annotation.
       * Each of these types bootstraps the code generation process... all of them are added to each of their files.
       * Since each type then recursively adds its dependent types as needed each file is generated in the right order.
       *
       * Dependencies are collected and the list of files sorted.  Any conflicts detected are displayed.
       */
      LinkedHashMap<EntryPoint, EntryPoint> entryPoints = new LinkedHashMap<EntryPoint, EntryPoint>();

      public void init(LayeredSystem sys) {
         for (Iterator<Map.Entry<String,JSGenFile>> it = jsGenFiles.entrySet().iterator(); it.hasNext(); ) {
            JSGenFile gf = it.next().getValue();
            // This gen file uses layers that don't exist - must be a stale build info entry so remove it
            if (gf.init(sys)) {
               it.remove();
            }
         }
      }

      public void addJSGenFile(String fileName, BodyTypeDeclaration type) {
         JSGenFile gf = jsGenFiles.get(fileName);
         Layer layer = type.getLayer();
         if (gf == null) {
            gf = new JSGenFile();
            gf.fromLayer = gf.toLayer = layer;
            gf.fromLayerName = gf.toLayerName = layer.getLayerUniqueName();

            jsGenFiles.put(fileName, gf);
         }
         else {
            if (gf.fromLayer == null || gf.toLayer == null)
               System.out.println("*** Uninitialized layers in addJSGenFile");
            gf.expandToLayer(layer);
         }
      }
   }


   static class JSFileDep implements Serializable {
      // The libfary file names involved in the dependency (for sorting purposes)
      String fromJSLib, toJSLib;

      // The information in the dependency for diagnostics and not always there for incremental builds - not part of equals/hashCode on purpose
      transient Object fromType, toType;

      String relation;

      public JSFileDep(String fromJSLib, String toJSLib) {
         this.fromJSLib = fromJSLib;
         this.toJSLib = toJSLib;
      }

      public JSFileDep(String fromJSLib, String toJSLib, Object fromType, Object toType, String relation) {
         this.fromType = fromType;
         this.toType = toType;
         this.fromJSLib = fromJSLib;
         this.toJSLib = toJSLib;
         this.relation = relation;
      }

      public boolean equals(Object o) {
         if (o instanceof JSFileDep) {
            JSFileDep other = (JSFileDep) o;
            return other.fromJSLib.equals(fromJSLib) && other.toJSLib.equals(toJSLib);
         }
         return false;
      }

      public int hashCode() {
         return fromJSLib.hashCode() + 3 * toJSLib.hashCode();
      }

      public String toString() {
         return "\n   " + fromJSLib + " -> " + toJSLib + " (" + (fromType != null ? ModelUtil.getTypeName(fromType) : "") + " " + relation + " " + (toType == null ? "" : ModelUtil.getTypeName(toType)) + ")";
      }
   }


   /** Stores a main type, mainInit type in the include module, etc. which drags in other JS classes. */
   static class EntryPoint implements Serializable {
      String jsFile;
      transient BodyTypeDeclaration type;
      transient Layer layer;
      String typeName; // needed for comparison and serialization
      String layerName; //
      boolean needsInit = true;
      boolean isMain;
      boolean isDefault; // Does this entry point collect default types - i.e. those not assigned to another file (true) or is it a module (false)

      public boolean equals(Object other) {
         if (other instanceof EntryPoint) {
            EntryPoint oe = (EntryPoint) other;
            if (oe == this || (oe.typeName.equals(typeName) && oe.jsFile.equals(jsFile)))
               return true;
         }
         return false;
      }

      public int hashCode() {
         return typeName.hashCode() + 13 * jsFile.hashCode();
      }

      public String toString() {
         return jsFile + ": " + typeName;
      }

      private void initLayer(LayeredSystem sys) {
         if (layer == null && layerName != null)
            layer = sys.getLayerByName(layerName);
      }

      private boolean initType(LayeredSystem sys) {
         if (type == null) {
            type = sys.getSrcTypeDeclaration(typeName, null, true);
            if (type != null) {
               layer = type.getLayer();
               layerName = layer.getLayerUniqueName();
               ParseUtil.initComponent(type);
               ParseUtil.startComponent(type);
            }
         }
         else
            type = type.resolve(true);

         return type != null;
      }

      private BodyTypeDeclaration getResolvedType(LayeredSystem sys) {
         initType(sys);
         return type;
      }

   }

   public List<SrcEntry> getProcessedFiles(IFileProcessorResult model, Layer genLayer, String buildSrcDir, boolean generate) {
      ArrayList<SrcEntry> resFiles = new ArrayList<SrcEntry>();
      if (model instanceof JavaModel) {
         JavaModel javaModel = (JavaModel) model;
         SrcEntry srcEnt = javaModel.getSrcFile();

         IFileProcessor proc = system.getFileProcessorForSrcEnt(srcEnt, BuildPhase.Process, false);

         // First transform to Java but the java files are not part of the build themselves so just ignore them here
         List<SrcEntry> javaFiles = model.getProcessedFiles(genLayer, buildSrcDir, generate);

         if (compileJavaFiles)
            resFiles.addAll(javaFiles);

         if (proc instanceof TemplateLanguage) {
            TemplateLanguage tl = (TemplateLanguage) proc;
            if (tl.processTemplate && tl.resultSuffix.equals("java"))
               return javaFiles;
            if (!tl.needsJavascript)
               return javaFiles;
         }

         if (generate && model.needsCompile()) {
            if (system.options.clonedTransform) {
               // Did we need to transform this model to process it?  If not, we use the regular model since it won't change.
               // If so, we use the transformed model - since that's in Java.
               if (javaModel.transformedModel != null)
                  javaModel = javaModel.transformedModel;
            }

            TypeDeclaration td = javaModel.getModelTypeDeclaration();

            if (td == null)
               return Collections.emptyList();

            // Since we transformed these types - we need to get the transformed type here, in Java.  The modify type will merge its definitions into the underlying type.
            td = (TypeDeclaration) td.getTransformedResult();

            PerfMon.start("transformJS");

            try {
               javaModel.disableTypeErrors = true;

               String[] jsFiles = addJSLibFiles(td, false, null, null, null);
               if (jsFiles == null) {
                  // Do not need annotation classes to be converted to JS for any use cases we support so far but still need to generate the Java.
                  if (td instanceof AnnotationTypeDeclaration) {
                     return model.getProcessedFiles(genLayer, buildSrcDir, generate);
                  }
                  saveJSTypeToFile(td, genLayer, buildSrcDir, resFiles);

                  return resFiles;
               }
            }
            finally {
               PerfMon.end("transformJS");
            }
         }
         return javaFiles;
      }
      else {
         return model.getProcessedFiles(genLayer, buildSrcDir, generate);
      }
   }

   // TODO: replace with btd.ensureTransformed
   private void ensureTransformed(BodyTypeDeclaration td) {
      PerfMon.yield(null); // used in both transformToJS and postProcessJS
      ParseUtil.initAndStartComponent(td);
      ParseUtil.processComponent(td);
      JavaModel model; // DEBUG
      // check and transform at the model level cause it's cached there.  It's better to always transform top-down.
      // This happens we pull in a source file from a layer's extra source mechanism (i.e. when we don't process the src)
      // This supports Java only but if there's an @Sync annotation for disabled or set on a base class, it currently fools needsTransform.   The annotation should have a way to reject an instance - like for Sync disabled.
      if (!td.isTransformed() && (model = td.getJavaModel()).needsTransform()) {
         // Always need to start the tranform from the most specific model
         BodyTypeDeclaration rootType = td.getRootType();
         if (rootType == null)
            rootType = td;
         rootType = rootType.resolve(true);
         JavaModel rootTypeModel = rootType.getJavaModel();
         if (rootTypeModel.nonTransformedModel == null) {
            if (rootTypeModel.transformedModel == null)
               rootTypeModel.transformModel();
            else if (!rootTypeModel.transformedModel.getTransformed()) {
               rootTypeModel.transformModel();
            }
         }
         else {
            // For some reason this model did not make it through the ordinary transform process and we hit it here first.  Always transform at the model level.
            rootTypeModel.nonTransformedModel.transformModel();
            //td.transform(ILanguageModel.RuntimeType.JAVA);
         }
      }
      PerfMon.resume(null);
   }

   /**
    * If necessary, saves the js/types/typeName.js file.  If this type has not been transformed,
    * this means the Java file did not change so we don't have to append it.  If we tried, it would fail
    * cause we only convert Java, not SC definitions to Javascript.    If that types file is gone
    * we should be doing a rebuild anyway?
    *
    * Note that this also returns the src entry to get processed files and must have the hash code filled in.
    */
   void saveJSTypeToFile(BodyTypeDeclaration td, Layer genLayer, String buildSrcDir, ArrayList<SrcEntry> resFiles) {
      StringBuilder result = new StringBuilder();

      // First look for an existing one in the build-path - if not found, then create a new one for this buildDir
      SrcEntry srcEnt = findJSSrcEntry(genLayer, td);
      SrcEntry resEnt = srcEnt;
      boolean needsSave = false;
      // No existing entry for this source file in this layer or previous
      if (srcEnt == null) {
         srcEnt = getJSSrcEntry(genLayer, buildSrcDir, td);
         resEnt = srcEnt;
         needsSave = true;
      }
      else {
         if (srcEnt.layer == genLayer) {
            needsSave = true;
            // TODO: is there any other way to ell if this type has changed?
         }
         else {
            // This type has been changed since the layer we are inheriting
            needsSave = td.getLayer().getLayerPosition() > srcEnt.layer.getLayerPosition();
            resEnt = getJSSrcEntryFromTypeName(genLayer, buildSrcDir, td.getFullTypeName());
         }
      }

      String resultStr = null;
      // The theory here is that we tranform all of the types that should have changed and so if this type is not
      // transformed, it hasn't changed so there's no need to save it.
      // TODO: Unfortunately we are not always transforming the Anon classes and those that come from the external source path.
      // Those types won't get updated on an incremental build as it stands now.   We should replace this with a more reliable change
      // detection algorithm, like compare the hashes or register changed types in the detection phase.
      if (!needsSave) {
         File typeFile = new File(srcEnt.absFileName);
         if (typeFile.canRead())
            resultStr = FileUtil.getFileAsString(srcEnt.absFileName);
      }

      if (resultStr == null) {
         if (appendJSType(td, result)) {
            resultStr = result.toString();
            FileUtil.saveStringAsFile(resEnt.absFileName, resultStr, true);
         }
      }
      else if (resEnt != srcEnt) {
         FileUtil.copyFile(srcEnt.absFileName, resEnt.absFileName, true);
      }

      if (resultStr != null) {
         resEnt.hash = StringUtil.computeHash(resultStr);

      // Note: this is done in LayeredSystem as this srcEnt gets returned via getProcessedFiles
      //genLayer.addSrcFileIndex(srcEnt.relFileName, );

         if (resFiles != null)
            resFiles.add(resEnt);

         List<Object> innerTypes = td.getAllInnerTypes(null, true);
         if (innerTypes != null) {
            for (Object innerTypeObj:innerTypes) {
               if (innerTypeObj instanceof BodyTypeDeclaration) {
                  if (innerTypeObj instanceof EnumConstant)
                     continue;
                  BodyTypeDeclaration innerType = (BodyTypeDeclaration) innerTypeObj;

                  saveJSTypeToFile(innerType, genLayer, buildSrcDir, resFiles);
               }
            }
         }
      }
   }

   public SrcEntry findJSSrcEntry(Layer startLayer, BodyTypeDeclaration type) {
      SrcEntry se = getJSSrcEntry(startLayer, startLayer.buildSrcDir, type);
      if (se != null && new File(se.absFileName).canRead())
         return se;
      for (Layer prevBuildLayer = startLayer.getPreviousLayer(); prevBuildLayer != null; prevBuildLayer = prevBuildLayer.getPreviousLayer()) {
         if (prevBuildLayer.isBuildLayer()) {
            se = getJSSrcEntry(prevBuildLayer, prevBuildLayer.buildSrcDir, type);
            if (se != null && new File(se.absFileName).canRead())
               return se;
         }
      }
      return null;
   }

   public SrcEntry findJSSrcEntryFromTypeName(Layer startLayer, String typeName) {
      SrcEntry se = getJSSrcEntryFromTypeName(startLayer, startLayer.buildSrcDir, typeName);
      if (se != null && new File(se.absFileName).canRead())
         return se;
      for (Layer prevBuildLayer = startLayer.getPreviousLayer(); prevBuildLayer != null; prevBuildLayer = prevBuildLayer.getPreviousLayer()) {
         if (prevBuildLayer.isBuildLayer()) {
            se = getJSSrcEntryFromTypeName(prevBuildLayer, prevBuildLayer.buildSrcDir, typeName);
            if (se != null && new File(se.absFileName).canRead())
               return se;
         }
      }
      return null;
   }

   public SrcEntry findSrcEntryForJSFile(Layer startLayer, String fileName) {
      SrcEntry se = getSrcEntryForJSFile(startLayer, fileName);
      if (se != null && new File(se.absFileName).canRead())
         return se;
      for (Layer prevBuildLayer = startLayer.getPreviousLayer(); prevBuildLayer != null; prevBuildLayer = prevBuildLayer.getPreviousLayer()) {
         if (prevBuildLayer.isBuildLayer()) {
            se = getSrcEntryForJSFile(prevBuildLayer, fileName);
            if (se != null && new File(se.absFileName).canRead())
               return se;
         }
      }
      return null;
   }

   public SrcEntry getJSSrcEntry(Layer genLayer, String buildSrcDir, BodyTypeDeclaration type) {
      return getJSSrcEntryFromTypeName(genLayer, buildSrcDir, type.getFullTypeName());
   }

   public SrcEntry getJSSrcEntryFromTypeName(Layer genLayer, String buildSrcDir, String fullTypeName) {
      LayeredSystem sys = getLayeredSystem();
      String fileName = FileUtil.addExtension(JSUtil.convertTypeName(sys, fullTypeName), "js");
      String genPrefix = FileUtil.unnormalize(genJSPrefix);
      // These go into the buildSrcDir because there's no use for them in the web root.  Also, they are put into the .deps file - if they don't exist, they force
      // a regenerate.  That's cause they are returned from getProcessedFiles.  Not entirely sure we need that but dep generated files must be in the buildSrcDir
      // They go into the generated layers buildSrcDir, not the system one so we support layered management of these files.
      SrcEntry srcEnt = new SrcEntry(genLayer, FileUtil.concat(FileUtil.concat(buildSrcDir, genPrefix), fileName), FileUtil.concat(genPrefix, fileName));
      return srcEnt;
   }

   public SrcEntry getSrcEntryForJSFile(Layer genLayer, String jsFile) {
      String prefix = getJSPathPrefix(genLayer);
      String absFilePath = FileUtil.concat(genLayer.buildDir, prefix, jsFile);
      // These go into the buildSrcDir because there's no use for them in the web root.  Also, they are put into the .deps file - if they don't exist, they force
      // a regenerate.  That's cause they are returned from getProcessedFiles.  Not entirely sure we need that but dep generated files must be in the buildSrcDir
      // They go into the generated layers buildSrcDir, not the system one so we support layered management of these files.
      SrcEntry srcEnt = new SrcEntry(genLayer, absFilePath, FileUtil.concat(prefix, jsFile));
      return srcEnt;
   }

   public void start(BodyTypeDeclaration td) {
      // Don't start for model streams, i.e. where there's a custom resolver
      JavaModel model = td.getJavaModel();

      if (td.isLayerType || model.customResolver != null || model.temporary || !model.mergeDeclaration)
         return;
      if (td.typeName != null && td.typeName.equals("BuildInfo") && td.getDerivedTypeDeclaration() == BuildInfo.class)
         return;

      // We are not compiling and so should not be doing any JS processing.  If we load the models after build we are at least doing extra work here and might mess up the jsBuildInfo.  It needs to stay in sync with the compiled result.
      if (!model.layeredSystem.buildingSystem)
         return;

      // This type is being loaded after all of the changed models have been started so it must not be changed.
      if (postStarted)
         return;

      IFileProcessor proc = system.getFileProcessorForSrcEnt(model.getSrcFile(), BuildPhase.Process, false);
      if (proc instanceof TemplateLanguage) {
         TemplateLanguage tl = (TemplateLanguage) proc;
         if (!tl.needsJavascript)
            return;
      }

      PerfMon.start("startJS", false);

      if (td.getEnclosingType() == null) {
         String prefixStr = (String) ModelUtil.getTypeOrLayerAnnotationValue(system, td, "ls.js.JSSettings", "prefixAlias");
         if (prefixStr != null && prefixStr.length() > 0) {
            String pkgName = CTypeUtil.getPackageName(ModelUtil.getTypeName(td));
            String oldPrefix = jsBuildInfo.prefixAliases.put(pkgName, prefixStr);
            if (oldPrefix != null && !oldPrefix.equals(prefixStr)) {
               System.err.println("*** JSSettings.prefixAlias value on type: " + td + " set to: " + prefixStr + " which conflicts with the old prefixAlias for pkg: " + pkgName + " of: " + oldPrefix);
            }
            List<String> pkgs = jsBuildInfo.aliasedPackages.get(prefixStr);
            if (pkgs == null) {
               pkgs = new ArrayList<String>();
               jsBuildInfo.aliasedPackages.put(prefixStr, pkgs);
            }
            pkgs.add(pkgName);
         }
      }

      // Need to figure out what JS libraries are needed so that it can be used to generate the HTML template to include them
      //LinkedHashMap<JSFileEntry,Boolean> typesInFile = new LinkedHashMap<JSFileEntry,Boolean>();
      addTypeLibsToFile(td, getTypesInFile(td), null, null, null);

      PerfMon.end("startJS");
   }

   public LinkedHashMap<JSFileEntry,Boolean> getTypesInFile(BodyTypeDeclaration type) {
      String typeLibFilesStr = getJSLibFiles(type);
      String typeLibFile;
      if (typeLibFilesStr == null) {
         typeLibFile = getJSModuleFile(type, true);
      }
      else {
         String[] typeLibFiles = StringUtil.split(typeLibFilesStr,',');
         typeLibFile = typeLibFiles[0];
      }
      if (typeLibFile == null)
         typeLibFile = null;

      LinkedHashMap<JSFileEntry,Boolean> typesInFile = typesInFileMap.get(typeLibFile);
      if (typesInFile == null) {
         typesInFile = new LinkedHashMap<JSFileEntry,Boolean>();
         typesInFileMap.put(typeLibFile, typesInFile);
      }
      return typesInFile;
   }

   public void postStart(LayeredSystem sys, Layer genLayer) {
      sortJSFiles();

      int numOrigChangedJSFiles;
      entryPointsFrozen = true;
      do {
         numOrigChangedJSFiles = changedJSFiles.size();
         for (Iterator<EntryPoint> it = jsBuildInfo.entryPoints.values().iterator(); it.hasNext(); ) {
            EntryPoint ent = it.next();
            ent.initLayer(sys);
            if (ent.layer != null && !genLayer.buildsLayer(ent.layer))
               continue;

            if (isJSFileChanged(ent.jsFile)) {
               if (!ent.initType(sys)) {
                  if (system.options.verbose)
                     System.out.println("Type: " + ent.typeName + " removed from source since last build");
                  // In an incremental compile, if the type was removed since the last build, need to clean it out of the entry points.
                  // TODO: remove this from anyplace else in the JS build info, like the JS type info?
                  it.remove();
               }
            }
         }
         if (queuedEntryPoints != null) {
            for (EntryPoint ent: queuedEntryPoints)
               jsBuildInfo.entryPoints.put(ent, ent);
            queuedEntryPoints = null;
         }
      // Keep going as long as this process added any new JS files
      } while (changedJSFiles.size() != numOrigChangedJSFiles);

      if (genLayer == system.buildLayer)
         postStarted = true;

      if (jsDebug) {
         System.out.println("Sorted jsFiles: " + jsBuildInfo.jsFiles + " using dependencies:\n" + jsBuildInfo.typeDeps);
      }
   }

   private final static HashSet<String> warnedCompiledTypes = new HashSet<String>();

   /**
    * This walks through the dependency graph and adds all of the libraries to the list.  This is done during "start" because
    * we expose this list to templates which need to have it set before the "transform" phase.
    */
   void addTypeLibsToFile(BodyTypeDeclaration type, Map<JSFileEntry,Boolean> typesInFile, String rootLibFile, BodyTypeDeclaration parentType, String relation) {
      type = (BodyTypeDeclaration) resolveBaseType(type);

      JavaModel javaModel = type.getJavaModel();
      if (!javaModel.needsCompile())
         return;

      JSFileEntry jsEnt = new JSFileEntry();
      jsEnt.fullTypeName = type.getFullTypeName();
      Boolean state = typesInFile.get(jsEnt);
      String jsModuleFile = null;
      if (state == null) {
         String fullTypeName = type.getFullTypeName();

         String typeLibFilesStr = getJSLibFiles(type);
         String[] typeLibFiles = null;
         String typeLibFile;
         if (typeLibFilesStr == null) {
            typeLibFile = jsModuleFile = getJSModuleFile(type, true);
            if (typeLibFile == null)
               typeLibFile = rootLibFile;
            addLibFile(typeLibFile, rootLibFile, type, parentType, relation);

         }
         else {
            typeLibFiles = StringUtil.split(typeLibFilesStr,',');
            typeLibFile = typeLibFiles[0];
            for (String typeLib:typeLibFiles) {
               addLibFile(typeLib, rootLibFile, type, parentType, relation);
            }
         }
         boolean hasLibFile = typeLibFilesStr != null || hasAlias(type);

         JSTypeInfo jti = jsBuildInfo.jsTypeInfo.get(fullTypeName);
         if (jti == null) {
            jti = new JSTypeInfo();
            jti.needsClassInit = ModelUtil.needsClassInit(type);
            jti.fromLayerName = jti.toLayerName = type.layer.getLayerUniqueName();
            jti.hasLibFile = hasLibFile;
            jti.jsModuleFile = jsModuleFile;
            jsBuildInfo.jsTypeInfo.put(fullTypeName, jti);
            // Also register this type info using the JS type name, so we can detect different Java classes that map
            // to the same JS type (e.g. sc.js.bind and sc.bind
            jsBuildInfo.jsTypeInfoByJS.put(getJSTypeName(fullTypeName), jti);
         }
         else if (!DynUtil.equalObjects(jsModuleFile, jti.jsModuleFile)) {
            jti.expandToLayer(type.layer);
            ArrayList<BodyTypeDeclaration> prevDeps = jti.prevDepTypes;
            if (prevDeps != null && typeLibFile != null) {
               for (BodyTypeDeclaration prevDep:prevDeps) {
                  String prevFile = getJSModuleFile(prevDep, true);
                  if (prevFile != null)
                     addLibFile(typeLibFile, prevFile, type, prevDep, " type - updated by layer");
               }
            }
         }

         // Unless this is a final type, it could be modified layer on, without us restarting the parent types.  We
         // record these dependencies to be sure they get updated later on.
         if (!type.isFinalLayerType() && parentType != null) {
            ArrayList<BodyTypeDeclaration> prevDeps = jti.prevDepTypes;
            if (prevDeps == null) {
               prevDeps = new ArrayList<BodyTypeDeclaration>();
               jti.prevDepTypes = prevDeps;
            }
            prevDeps.add(parentType);
         }

         typesInFile.put(jsEnt, Boolean.FALSE);

         if (!hasLibFile) {

            if (typeLibFile != null) {
               changedJSFiles.add(typeLibFile);
            }
            else if (type.getEnclosingType() == null) {
               // Need to mark the fact that we changed a type which lives in the default module.  Any file which has a dependency from the <default> file will need to be changed since it may include this type.
               // Could be more accurate about this and figure out exactly which dependencies need to be invalidated but it seems like we are just assembling the files anyway.
               changedDefaultType = true;

               String modLibFile = getJSDefaultModuleFile(type);
               changedJSFiles.add(modLibFile);
            }

            addExtendsTypeLibsToFile(type, typesInFile, typeLibFile);

            Set<Object> depTypes = type.getDependentTypes();
            for (Object depType:depTypes) {
               depType = resolveBaseType(depType);

               if (filteredDepType(type, depType))
                  continue;

               if (!(depType instanceof BodyTypeDeclaration)) {
                  while (ModelUtil.isParameterizedType(depType)) {
                     Object nextDepType = ModelUtil.getParamTypeBaseType(depType);
                     if (nextDepType == depType)
                        break;
                     depType = nextDepType;
                  }
                  if (ModelUtil.isCompiledClass(depType)) {
                     if (!ModelUtil.isPrimitive(depType)) {
                        if (hasAlias(depType))
                           continue;

                        String depModuleFile = getJSModuleFile(depType, false);

                        // The dependent type is in the same file with us.  When pulling pre-compiled files from the source
                        // path, which we are not transforming initially this is the first time we end up pulling in the source.
                        // If we get
                        if (depModuleFile != null && depModuleFile.equals(typeLibFile)) {
                           depType = resolveSrcTypeDeclaration(depType);
                           if (!(depType instanceof BodyTypeDeclaration))
                              System.err.println("Warning: no source for type: " + ModelUtil.getTypeName(depType) + " to resolve dependency for type: " + type);
                        }

                        if (ModelUtil.isCompiledClass(depType)) {
                           if (addJSLibFiles(depType, true, typeLibFilesStr == null ? null : typeLibFiles[0], type, "uses") == null && !hasJSCompiled(type)) {
                              String warnTypeName = type.typeName;
                              if (!warnedCompiledTypes.contains(warnTypeName)) {
                                 System.err.println("*** Warning: js type: " + warnTypeName + " in layer: " + type.layer + " depends on compiled class: " + depType);
                                 warnedCompiledTypes.add(warnTypeName);
                              }
                           }
                        }
                     }
                  }
                  else if (!isCompiledInType(depType))
                     System.err.println("*** Warning: unrecognized js type: " + type.typeName + " depends on compiled thing: " + depType);
               }

               if (depType instanceof BodyTypeDeclaration) {
                  BodyTypeDeclaration depTD = (BodyTypeDeclaration) depType;
                  depTD = depTD.resolve(true);
                  ParseUtil.initComponent(depTD);
                  ParseUtil.startComponent(depTD);

                  // If depTD extends type, do not add it here.  Instead, we need to add type before depTD in this case
                  if (!type.isAssignableFrom(depTD, false) && depTD != type.getEnclosingType())
                     addTypeLibsToFile(depTD, typesInFile, typeLibFile, type, "uses");
               }

               if (ModelUtil.getCompileLiveDynamicTypes(type) && (type.isComponentType() || type.getDeclarationType() == DeclarationType.OBJECT)) {
                  addJSLibFiles(DynUtil.class, true, typeLibFile, type, "uses components");
               }
               if (type.needsDataBinding()) {
                  addJSLibFiles(Bind.class, true, typeLibFile, type, "uses data binding");
               }
               if (type.needsSync()) {
                  //type.needsSync(); // TODO: remove for debugging
                  addJSLibFiles(SyncManager.class, true, typeLibFile, type, "uses the sync features");
               }
            }
         }
         else {
            String depJSFilesStr = getDependentJSLibFiles(type);
            if (depJSFilesStr != null) {
               String[] depJSFiles = StringUtil.split(depJSFilesStr, ',');

               for (String depFile:depJSFiles)
                  addDependency(typeLibFile == null ? defaultLib : typeLibFile, depFile, type, null, "dependentJSLibFiles");
            }
            // TODO: should we recursively process this types dependencies and add any jsLibFiles we find along the way?  That seems safe, just computationally expensive.  Given the small number of lib files, manually adding them for now.
         }

         // Since MainInit is in src form here, need to skip checking on compiled types or the runtime method barfs
         boolean entryPointAdded = false;
         Object mainInit = type.getInheritedAnnotation("sc.html.MainInit", true, type.getLayer(), false);
         if (mainInit != null && !type.hasModifier("abstract")) {
            Boolean subTypesOnly = (Boolean) ModelUtil.getAnnotationValue(mainInit, "subTypesOnly");
            if (subTypesOnly == null || !subTypesOnly || type.getAnnotation("sc.html.MainInit") == null) {
               String jsFile = (String) ModelUtil.getAnnotationValue(mainInit, "jsFile");
               EntryPoint ent = new EntryPoint();
               ent.jsFile = jsFile == null || jsFile.isEmpty() ? getJSDefaultModuleFile(type) : jsFile;
               ent.type = type;
               ent.typeName = fullTypeName;

               EntryPoint oldEnt = jsBuildInfo.entryPoints.get(ent);
               if (oldEnt != null) {
                  ent = oldEnt;
                  // TODO: should we expand the layer range for this entry point by keeping a from and to layer?
               }
               else {
                  ent.layer = type.getLayer();
                  if (ent.layer != null)
                     ent.layerName = ent.layer.getLayerUniqueName();
                  ent.isMain = false;

                  addEntryPoint(ent);
               }
               ent.needsInit = true;
               ent.isDefault = jsFile == null && getJSModuleFile(type, true) == null;
               jsBuildInfo.addJSGenFile(ent.jsFile, type);
               if (!jsBuildInfo.jsFiles.contains(ent.jsFile))
                  jsBuildInfo.jsFiles.add(ent.jsFile);
               entryPointAdded = true;
            }
         }
         List<Object> mainMethods = type.getMethods("main", null);
         if (mainMethods != null) {
            for (Object mainMethod:mainMethods) {
               Object mainSettings;
               if ((mainSettings = ModelUtil.getAnnotation( mainMethod, "sc.obj.MainSettings")) != null) {
                  Boolean disabled = (Boolean) ModelUtil.getAnnotationValue(mainSettings, "disabled");
                  if (disabled == null || !disabled) {
                     EntryPoint ent = new EntryPoint();
                     ent.jsFile = jsModuleFile == null ? scLib : jsModuleFile;
                     ent.type = type;
                     ent.typeName = fullTypeName;
                     EntryPoint oldEnt = jsBuildInfo.entryPoints.get(ent);
                     if (oldEnt != null) {
                        ent = oldEnt;
                     }
                     else {
                        ent.layer = type.getLayer();
                        if (ent.layer != null)
                           ent.layerName = ent.layer.getLayerUniqueName();
                        addEntryPoint(ent);
                     }
                     ent.isMain = true;
                     ent.isDefault = jsModuleFile == null;
                     jsBuildInfo.addJSGenFile(ent.jsFile, type);
                     if (!jsBuildInfo.jsFiles.contains(ent.jsFile))
                        jsBuildInfo.jsFiles.add(ent.jsFile);
                  }
                  entryPointAdded = true;
               }
            }
         }
         // Top level classes in the module need to add the entry point, even if they do not have a main-init.  Otherwise, we do not generate this file.
         if (!entryPointAdded && jsModuleFile != null && type.getEnclosingType() == null) {
            EntryPoint ent = new EntryPoint();
            ent.jsFile = jsModuleFile;
            ent.type = type;
            ent.typeName = fullTypeName;
            EntryPoint oldEnt = jsBuildInfo.entryPoints.get(ent);
            if (oldEnt != null) {
               ent = oldEnt;
            }
            else {
               ent.layer = type.getLayer();
               if (ent.layer != null)
                  ent.layerName = ent.layer.getLayerUniqueName();
               addEntryPoint(ent);
               ent.needsInit = false;
               ent.isMain = false;
               ent.isDefault = false;
            }
            jsBuildInfo.addJSGenFile(ent.jsFile, type);
            if (!jsBuildInfo.jsFiles.contains(ent.jsFile))
               jsBuildInfo.jsFiles.add(ent.jsFile);
         }
         addJSLibFiles(type, true, null, null, null);

         typesInFile.put(jsEnt, Boolean.TRUE);

         String depJSFilesStr = getDependentJSLibFiles(type);
         if (depJSFilesStr != null) {
            String[] depJSFiles = StringUtil.split(depJSFilesStr, ',');
            for (String depJSFile:depJSFiles) {
               addDependency(typeLibFile == null ? defaultLib : typeLibFile, depJSFile, type, null, "dependentJSLibFiles");
            }
         }
      }
   }

   private void addEntryPoint(EntryPoint ent) {
      if (!entryPointsFrozen)
         jsBuildInfo.entryPoints.put(ent, ent);
      else {
         if (queuedEntryPoints == null)
            queuedEntryPoints = new ArrayList<EntryPoint>();
         queuedEntryPoints.add(ent);
      }
   }

   private void addDependency(String fromJSLib, String toJSLib, Object fromType, Object toType, String relation) {
      //if (fromJSLib != null && toJSLib != null && !fromJSLib.equals(toJSLib) && ((fromJSLib.equals("js/sceditor.js") || fromJSLib.equals("js/editorApp.js")) && (toJSLib.equals("js/sceditor.js") || toJSLib.equals("js/editorApp.js"))))
      //   System.out.println("*** tracked dependency");
      JSFileDep fd = new JSFileDep(fromJSLib, toJSLib, fromType, toType, relation);
      // TODO: maybe we should be merging the information somehow to build the best diagnostics - right now just taking one of the fromType, toType and relation values since those are just for diagnostic purposes
      jsBuildInfo.typeDeps.add(fd);
   }

   private void addModuleEntryPoint(Object typeObj, String jsModuleStr) {
      BodyTypeDeclaration type = null;
      if (typeObj instanceof BodyTypeDeclaration)
         type = (BodyTypeDeclaration) typeObj;
      EntryPoint ent = new EntryPoint();
      ent.jsFile = jsModuleStr;
      ent.type = type;
      ent.typeName = ModelUtil.getTypeName(typeObj);

      ent.layer = ModelUtil.getLayerForType(system, type);
      if (ent.layer != null)
         ent.layerName = ent.layer.getLayerUniqueName();
      ent.needsInit = false;
      ent.isDefault = false;
      if (jsBuildInfo.entryPoints.get(ent) == null) {
         addEntryPoint(ent);
      }
      if (type != null)
         jsBuildInfo.addJSGenFile(jsModuleStr, type);
   }

   // TODO: should we check here if there's a main with MainSettings?
   public boolean isEntryPointType(Object type) {
      Object mainInit = ModelUtil.getInheritedAnnotation(system, type, "sc.html.MainInit", true, null, false);
      if (mainInit != null && !ModelUtil.hasModifier(type, "abstract")) {
         return true;
      }
      return false;
   }

   public String getJSDefaultModuleFile(BodyTypeDeclaration type) {
      String modFile = getJSModuleFile(type, true);
      if (modFile == null)
         return scLib;
      return modFile;
   }

   public String getCachedJSModuleFile(String typeName) {
      String res = jsBuildInfo.jsModuleNames.get(typeName);
      return res;
   }

   public String getJSModuleFile(Object type, boolean resolveSrc) {
      String typeName = ModelUtil.getTypeName(type);
      String res = jsBuildInfo.jsModuleNames.get(typeName);
      if (res != null)
         return res;
      PerfMon.start("getJSModuleFile");

      try {
         // We'll look at the module annotations only if modules are enabled, or this is an entry point type.
         boolean useModules = !disableModules || isEntryPointType(type);

         // Do not resolve the src here since we use this on the .class file to determine if we need to load the source
         String jsModuleStr = !useModules ? null : getJSSettingsStringValue(type, "jsModuleFile", false, resolveSrc);
         if (jsModuleStr != null) {

            addModuleEntryPoint(type, jsModuleStr);

            // Record the top-level type-name to module name mappings.  We use these to compute at runtime the JSFiles property without having to load the src
            jsBuildInfo.jsModuleNames.put(typeName, jsModuleStr);
            return jsModuleStr;
         }

         Object enclType = ModelUtil.getEnclosingType(type);
         if (enclType != null) {
            jsModuleStr = getJSModuleFile(enclType, resolveSrc);
            if (jsModuleStr != null)
               return jsModuleStr;
         }

         Layer lyr = ModelUtil.getLayerForType(system, type);
         if (lyr != null && lyr.model == null) {
            System.err.println("*** No model for layer: " + lyr);
            return null;
         }
         String layerModule = lyr != null && useModules ? getJSSettingsStringValue(lyr.model.getModelTypeDeclaration(), "jsModuleFile", false, false) : null;
         if (layerModule != null) {

            // Only add top-level types as entry points or it messes up the ordering in the file
            if (ModelUtil.getEnclosingType(type) == null) {
               addModuleEntryPoint(type, layerModule);
            }
            jsBuildInfo.jsModuleNames.put(typeName, layerModule);
            return layerModule;
         }

         jsModuleStr = useModules ? getJSSettingsStringValue(type, "jsModulePattern", true, false) : null;
         if (jsModuleStr != null) {
            if (ModelUtil.getEnclosingType(type) == null) {
               Element elem;

               // Don't apply the module pattern to an element with abstract = true
               if (type instanceof TypeDeclaration && (elem = ((TypeDeclaration) type).element) != null && elem.isAbstract())
                  return null;

               Object typeParams = new ObjectTypeParameters(system, type);
               String jsModuleRes = TransformUtil.evalTemplate(typeParams, jsModuleStr, true); // TODO: preparse these strings for speed
               addModuleEntryPoint(type, jsModuleRes);
               jsBuildInfo.jsModuleNames.put(typeName, jsModuleRes);
               return jsModuleRes;
            }
         }

         if (ModelUtil.isCompiledClass(type)) {
            return jsBuildInfo.jsModuleNames.get(typeName);
         }
      }
      finally {
         PerfMon.end("getJSModuleFile");
      }
      return null;
   }

   public void process(BodyTypeDeclaration td) {
      if (td.isLayerType)
         return;
      JavaModel model = td.getJavaModel();
      if (model.temporary)
         return;
      if (td.typeName != null && td.typeName.equals("BuildInfo") && td.getDerivedTypeDeclaration() == BuildInfo.class)
         return;
   }

   void addLibFile(String libFile, String beforeLib, Object type, Object depType, String relation) {
      if (beforeLib != null && !beforeLib.equals(libFile)) {
         String depFile = libFile;
         // When adding a dependcy from a type with no module file on a type which does have a module file, substitute in the default dependency
         if (depFile == null)
            depFile = defaultLib;
         addDependency(beforeLib, depFile, depType, type, relation);
      }
      else if (beforeLib == null && libFile != null && depType != null) {
         addDependency(defaultLib, libFile, depType, type, relation);
         // TODO: why are we adding this each time?
         addDependency(scLib, defaultLib, depType, type, relation);
      }

      int beforeIx = beforeLib != null ? jsBuildInfo.jsFiles.indexOf(beforeLib) : -1;
      int origIx = jsBuildInfo.jsFiles.indexOf(libFile);

      if (origIx != -1) {
         if (beforeIx == -1 || origIx <= beforeIx)
            return;
         jsBuildInfo.jsFiles.remove(origIx);
      }

      if (!jsBuildInfo.jsLibFiles.contains(jsCoreLib)) {
         jsBuildInfo.jsLibFiles.add(jsCoreLib);
         jsBuildInfo.jsFiles.add(jsCoreLib);
      }

      if (libFile == null) {
         if (!jsBuildInfo.jsFiles.contains(defaultLib))
            libFile = defaultLib;
         else
            return;
      }

      if (libFile.equals(jsCoreLib))
         return;

      jsBuildInfo.jsLibFiles.add(libFile);
      if (beforeLib != null && !beforeLib.equals(libFile)) {
         int ix = jsBuildInfo.jsFiles.indexOf(beforeLib);
         if (ix == -1) {
            jsBuildInfo.jsFiles.add(libFile);
         }
         else
            jsBuildInfo.jsFiles.add(ix, libFile);
      }
      else {
         jsBuildInfo.jsFiles.add(libFile);
      }
   }

   public String getJSSettingsStringValue(Object type, String attribute, boolean inherited, boolean resolve) {
      // Make sure we get the most specific type for this type.  We might have a dependency on java.lang.Object
      // for example when there's an annotation set on the annotation layer for java.lang.Object.
      if (resolve)
         type = ModelUtil.resolveSrcTypeDeclaration(system, type, false, true);
      Object settingsObj = inherited ? ModelUtil.getInheritedAnnotation(system, type, "sc.js.JSSettings") : ModelUtil.getAnnotation(type, "sc.js.JSSettings");
      if (settingsObj != null) {
         String jsFilesStr = (String) ModelUtil.getAnnotationValue(settingsObj, attribute);
         if (jsFilesStr != null && jsFilesStr.length() > 0) {
            return jsFilesStr;
         }
      }
      return null;
   }

   String getDependentJSLibFiles(Object type) {
      if (!(type instanceof PrimitiveType)) {
         return getJSSettingsStringValue(type, "dependentJSFiles", false, true);
      }
      return null;
   }

   String getJSLibFiles(Object type) {
      if (!(type instanceof PrimitiveType)) {
         return getJSSettingsStringValue(type, "jsLibFiles", false, true);
      }
      String alias = getAlias(type);
      if (alias != null)
         return null;
      return null;
   }

   boolean hasJSLibFiles(Object type) {
      if (!(type instanceof PrimitiveType)) {
         // Make sure we get the most specific type for this type.  We might have a dependency on java.lang.Object
         // for example when there's an annotation set on the annotation layer for java.lang.Object.
         type = ModelUtil.resolveSrcTypeDeclaration(system, type, false, false);
         Object settingsObj = ModelUtil.getAnnotation(type, "sc.js.JSSettings");
         if (settingsObj != null) {
            String jsFilesStr = (String) ModelUtil.getAnnotationValue(settingsObj, "jsLibFiles");
            return jsFilesStr != null && jsFilesStr.length() > 0;
         }
      }
      if (hasAlias(type))
         return true;
      Object parentType = ModelUtil.getEnclosingType(type);
      if (parentType != null)
         return hasJSLibFiles(parentType);
      return false;
   }

   public String getAlias(Object type) {
      String typeName = ModelUtil.getTypeName(type);
      return jsBuildInfo.replaceTypes.get(typeName);
   }

   JSTypeInfo getJSTypeInfo(String typeName, boolean tryJSTypeNames) {
      JSTypeInfo jti = jsBuildInfo.jsTypeInfo.get(typeName);
      if (jti != null || !tryJSTypeNames) {
         return jti;
      }

      // We also look up by the JS type name.  We might have registered the type info for sc.js.bind.Bind and need to
      // determine that sc.bind.Bind is going to be found in the JS compile.
      jti = jsBuildInfo.jsTypeInfoByJS.get(getJSTypeName(typeName));
      return jti;
   }

   boolean needsClassInit(Object type) {
      String typeName = ModelUtil.getTypeName(type);

      JSTypeInfo jti = getJSTypeInfo(typeName, true);
      if (jti != null)
         return jti.needsClassInit;

      return ModelUtil.needsClassInit(type);
   }

   boolean hasJSCompiled(Object type) {
      String typeName = ModelUtil.getTypeName(type);
      JSTypeInfo jti = getJSTypeInfo(typeName, true);
      return jti != null || hasJSLibFiles(type);
   }

   boolean hasAlias(Object type) {
      String typeName = ModelUtil.getTypeName(type);
      if (jsBuildInfo.replaceTypes.get(typeName) != null)
         return true;

      if (ModelUtil.isCompiledClass(type)) {
         Object newType = ModelUtil.findTypeDeclaration(system, ModelUtil.getTypeName(type), null, false);
         if (newType != null) {
            type = newType;
         }
         //type = ModelUtil.resolveSrcTypeDeclaration(system, type);
         if (ModelUtil.isCompiledClass(type)) {
            String pkgName = CTypeUtil.getPackageName(typeName);
            if (pkgName != null) {
               String className = CTypeUtil.getClassName(typeName);
               String prefix = jsBuildInfo.prefixAliases.get(pkgName);
               if (prefix != null) {
                  List<String> aliasedPkgs = jsBuildInfo.aliasedPackages.get(prefix);
                  if (aliasedPkgs != null) {
                     for (String aliasedPkg:aliasedPkgs) {
                        String testPath = CTypeUtil.prefixPath(aliasedPkg, className);
                        if (system.getSrcTypeDeclaration(testPath, null, true) != null) {
                           return true;
                        }
                     }
                  }
               }
            }
         }
      }
      return false;
   }

   /** This gets called in two modes - during start to collect the jsLibFiles for each type and populate the jsLibFiles in the build info.  This is with (append=true).
    * It is also called from the getProcessedFiles method (append=false) to copy the files for a given type. */
   String[] addJSLibFiles(Object type, boolean append, String rootTypeLibFile, Object depType, String relation) {
      // This prefix alias attribute does not work for inner classes.
      if (ModelUtil.getEnclosingType(type) == null) {
         String prefixStr = (String) ModelUtil.getTypeOrLayerAnnotationValue(system, type, "sc.js.JSSettings", "prefixAlias");
         if (append && prefixStr != null && prefixStr.length() > 0) {
            String pkgName = CTypeUtil.getPackageName(ModelUtil.getTypeName(type));
            String oldPrefix = jsBuildInfo.prefixAliases.put(pkgName, prefixStr);
            if (oldPrefix != null && !oldPrefix.equals(prefixStr)) {
               System.err.println("*** JSSettings.prefixAlias value on type: " + type + " set to: " + prefixStr + " which conflicts with the old prefixAlias for pkg: " + pkgName + " of: " + oldPrefix);
            }
         }
      }

      Object settingsObj = ModelUtil.getAnnotation(type, "sc.js.JSSettings");
      boolean handled = false;
      if (settingsObj != null) {

         String replaceWith = (String) ModelUtil.getAnnotationValue(settingsObj, "replaceWith");
         if (replaceWith != null && replaceWith.length() > 0) {
            jsBuildInfo.replaceTypes.put(ModelUtil.getTypeName(type), replaceWith);
            handled = true;
         }

         String jsFilesStr = (String) ModelUtil.getAnnotationValue(settingsObj, "jsLibFiles");
         if (jsFilesStr != null && jsFilesStr.length() > 0) {
            String[] newJsFiles = StringUtil.split(jsFilesStr, ',');
            for (int i = 0; i < newJsFiles.length; i++) {
               if (append) {
                  addLibFile(newJsFiles[i], rootTypeLibFile, type, depType, relation);
               }
               else if (!jsBuildInfo.jsFiles.contains(newJsFiles[i]))
                  System.err.println("*** jsFiles for type: " + type + " not added during process phase");
            }
            return newJsFiles;
         }

         //type = ModelUtil.resolveSrcTypeDeclaration(system, type);

         String jsModule = getJSModuleFile(type, true);
         if (jsModule != null && jsModule.length() > 0) {
            jsModule = jsModule.trim();
            if (append) {
               addLibFile(jsModule, rootTypeLibFile, type, depType, relation);
            }
            else if (!jsBuildInfo.jsFiles.contains(jsModule))
               System.err.println("*** jsFiles for type: " + type + " not added during process phase");
         }
      }
      if (!handled) {
         String typeName = ModelUtil.getTypeName(type, false);
         if (jsBuildInfo.replaceTypes.get(typeName) != null)
            return new String[0];
         return null;
      }
      return new String[0];
   }

   void appendJSMergeUpdateTemplate(BodyTypeDeclaration td, StringBuilder result) {
      // This is the case when the "td" here is the set of changes we're making to the model type, not the model type itself.
      // Now we need to get the transformed model type and re-generate any Javascript which will have been changed by this change.
      if (td.needsOwnClass(true)) {
         BodyTypeDeclaration currentType = system.getSrcTypeDeclaration(td.getFullTypeName(), null, true);

         if (currentType == null) {
            System.err.println("*** Unable to find type for JS update");
            return;
         }

         // Get these from the pre-transformed type
         TreeSet<String> changedMethods = currentType.changedMethods;

         // TODO: should we ensure we build here and do this right after the transform but before flushTypeInfo?  Or can we do this incrementally on the fly, without a flush (as long as we rebuild after...)
         currentType = transformJSType(currentType);
         currentType.changedMethods = changedMethods;
         JSTypeParameters updParams = new JSTypeParameters(currentType);
         updParams.updateTemplate = td instanceof ModifyDeclaration;
         // TODO: do we also need to include any type change in here?
         updParams.needsConstructor = !(td instanceof ModifyDeclaration) || changedMethods != null;

         ArrayList<BodyTypeDeclaration> innerTypes = new ArrayList<BodyTypeDeclaration>();

         if (td.body != null) {
            for (Statement st:td.body) {
               if (((st instanceof BlockStatement || st instanceof PropertyAssignment || st instanceof FieldDefinition) && !st.isStatic()) || st instanceof ConstructorDefinition)
                  updParams.needsConstructor = true;
               else if (st instanceof BodyTypeDeclaration) {
                  updParams.needsConstructor = true;
                  if (updParams.changedMethods == null)
                     updParams.changedMethods = new TreeSet<String>();
                  updParams.changedMethods.add("getObjChildren");
                  updParams.changedMethods.add("get" + CTypeUtil.capitalizePropertyName(((BodyTypeDeclaration)st).typeName));
                  innerTypes.add((BodyTypeDeclaration) st);
               }
               else if (st instanceof MethodDefinition) {
                  if (updParams.changedMethods == null)
                     updParams.changedMethods = new TreeSet<String>();
                  updParams.changedMethods.add(((MethodDefinition) st).name);
               }
            }
         }

         if (updParams.needsConstructor) {
            // Disabling for the errors introduced by for example the conversion to 'var'.   Because the transformed types share the same model
            // we are also turning off errors here for the main model... at this point it's been started and transformed though so that should be ok.
            currentType.getJavaModel().disableTypeErrors = true;

            String updateTypeResult = TransformUtil.evalTemplate(updParams, getJSTypeTemplate(currentType));
            result.append(updateTypeResult);
         }

         for (int i = 0; i < innerTypes.size(); i++) {
            appendJSMergeUpdateTemplate(innerTypes.get(i), result);
         }
      }
   }

   void appendJSMergeTemplate(BodyTypeDeclaration td, StringBuilder result, boolean syncTemplate) {
      if (td instanceof EnumDeclaration) {
         td = ((EnumDeclaration) td).transformEnumToJS();
         ensureTransformed(td);
      }

      /*
      if (!syncTemplate) {
         td = transformJSType(td);
      }
      */


      // Doing the update after any types updates since they may depend on the type being updated when the code is
      // executed on the client.
      JSTypeParameters typeParams = new JSTypeParameters(td);
      typeParams.mergeTemplate = true;
      typeParams.syncTemplate = syncTemplate;

      String rootTypeResult = TransformUtil.evalTemplate(typeParams, getMergeJSTemplate(td, syncTemplate));
      result.append(rootTypeResult);
   }

   BodyTypeDeclaration transformJSType(BodyTypeDeclaration td) {
      // If this type has already been transformed, we do not want the most specific type.  That's because during transform we merge the changes down the chain so the final type
      // we use is the original type.
      if (!td.isTransformedType() || !td.getTransformed())
         td = td.resolve(true);  // First resolve to get back to the most specific type

      ParseUtil.initAndStartComponent(td);

      ensureTransformed(td);

      // Should be done after the transform
      if (td instanceof EnumDeclaration) {
         td = ((EnumDeclaration) td).transformEnumToJS();
      }

      return td.getTransformedResult();  // Then walk back down to get the transformed result - the end Java version.  This is probably over kill...
   }

   void appendInnerJSMergeTemplate(BodyTypeDeclaration innerType, StringBuilder result, boolean syncTemplate) {
      /*
      if (!syncTemplate && innerType.needsTypeChange() && innerType instanceof ModifyDeclaration) {
         appendJSTypeTemplate(innerType.getModifiedType(), result);
      }
      */
      appendJSMergeTemplate(innerType, result, syncTemplate);
      /*
      if (syncTemplate || innerType instanceof ModifyDeclaration)
         appendJSMergeTemplate(innerType, result, syncTemplate);
      else
         appendJSTypeTemplate(innerType, result);
      */
   }

   void appendJSTypeTemplate(BodyTypeDeclaration td, StringBuilder result) {
      /*
      ParseUtil.initAndStartComponent(td);
      if (td instanceof EnumDeclaration) {
         td = ((EnumDeclaration) td.getTransformedResult()).transformEnumToJS();
         ensureTransformed(td);
      }
      */

      td = transformJSType(td);

      // If we optimize out the class in Java, do not generate it in JS either
      if (!td.needsOwnClass(true)) {
         return;
      }

      Object typeParams = new JSTypeParameters(td);

      String templatePath = getJSTypeTemplatePath(td);
      String rootTypeResult = system.evalTemplate(typeParams, templatePath, JSTypeParameters.class, td.getLayer(), td.isLayerType);
      result.append(rootTypeResult);

      processedTypes.add(td.getFullTypeName());
   }

   /** Returns the prefix to use for the type when we convert static type definitions.  For Javascript, use the classPrefix template to generate the code pattern to use. */
   public String getStaticPrefix(Object typeObj, JavaSemanticNode refNode) {
      JavaModel model = refNode != null ? refNode.getJavaModel() : null;

      boolean useRuntime = model != null && model.customResolver != null && model.customResolver.useRuntimeResolution();
      typeObj = ModelUtil.resolveSrcTypeDeclaration(system, typeObj, useRuntime, false);
      if (!useRuntime) {
         if (!(typeObj instanceof BodyTypeDeclaration)) {
            if (!hasJSCompiled(typeObj)) {
               typeObj = ModelUtil.resolveSrcTypeDeclaration(system, typeObj, useRuntime, true);
               if (typeObj == null) {
                  String err = "Static reference to compiled type: " + typeObj + " with no JSSettings annotation: ";
                  if (refNode != null)
                     refNode.displayError(err);
                  else
                     System.err.println(err + typeObj);
                  return "invalid";
               }
            }
         }
      }
      if (!(typeObj instanceof BodyTypeDeclaration)) {
         // TODO: convert JSTypeParameters to use Object types just for this?
         return JSUtil.convertTypeName(system, ModelUtil.getTypeName(typeObj)) + prototypeSuffix;
      }

      BodyTypeDeclaration td = (BodyTypeDeclaration) typeObj;

      Object typeParams = new JSTypeParameters(td);
      return TransformUtil.evalTemplate(typeParams, classPrefix, true);
   }

   public String getJSTypeName(String typeName) {
      return JSUtil.convertTypeName(system, typeName);
   }

   public String getInstanceTemplate(BodyTypeDeclaration td) {
      Object typeParams = new JSTypeParameters(td);
      return TransformUtil.evalTemplate(typeParams, instanceTemplate, true);
   }

   public String evalMainTemplate(BodyTypeDeclaration td) {
      Object typeParams = new JSTypeParameters(td);
      return TransformUtil.evalTemplate(typeParams, mainTemplate, true);
   }

   static class JSFileEntry {
      String fullTypeName;

      // Cases where we are comparing the EnumDeclaration and the ClassDeclaration we generate from it so do not do == type comparison
      public boolean equals(Object other) {
         if (!(other instanceof JSFileEntry))
            return false;
         return DynUtil.equalObjects(fullTypeName, ((JSFileEntry) other).fullTypeName);
      }

      public int hashCode() {
         return fullTypeName == null ? 0 : fullTypeName.hashCode();
      }

      public String toString() {
         if (fullTypeName == null)
            return "no type";
         return fullTypeName;
      }
   }

   private boolean isJSFileChanged(String jsFile) {
      boolean changed = changedJSFiles.contains(jsFile);

      // If this particular file did not have changed types assigned to it but it belongs to an entry point that
      // collects default types (those unassigned to any module or particular js file and so lazily dragged in as referenced)
      // we still need to recompute it in postProcess.  Skip that unless some types in the default type group have changed or we're
      // always rebuilding these types.
      if (!changed && changedDefaultType) {
         for (EntryPoint e:jsBuildInfo.entryPoints.values()) {
            if (e.jsFile.equals(jsFile)) {
               if (e.isDefault) {
                  changed = true;
                  break;
               }
            }
         }
      }
      return changed;
   }

   private String getJSPathPrefix(Layer buildLayer) {
      return templatePrefix != null ? templatePrefix : buildLayer.getSrcPathBuildPrefix(srcPathType);
   }

   public void postProcess(LayeredSystem sys, Layer genLayer) {
      List<BuildInfo.MainMethod> mainMethods = genLayer.buildInfo.mainMethods;
      if (jsBuildInfo.jsGenFiles.size() == 0 && (mainMethods == null || mainMethods.size() == 0)) {
         if (genLayer == sys.buildLayer)
            System.out.println("Warning: No @MainInit or @MainSettings tags for JS classes for build layer.  Need at least one entry point to define a .js file to execute.");
         return;
      }

      // As we start unstarted entry points here, we'll be adding more entry points.  But don't do them if they are not changed, otherwise
      // we end up starting stuff which we don't have to for an incremental compile.
      // TODO: remove this - we now start all models earlier than this.
      /*
      ArrayList<EntryPoint> initEntryPoints = new ArrayList<EntryPoint>(jsBuildInfo.entryPoints.values());
      for (EntryPoint e:initEntryPoints) {
         if (e.type == null) {
            continue;

            e.initLayer(sys);
            // Haven't hit this entry point yet in the build sequence
            if (e.layer != null && !genLayer.buildsLayer(e.layer))
               continue;
            if (!isJSFileChanged(e.jsFile))
               continue;
            // TODO: because these have not been started yet, there's no way they have changed or any of their dependencies.  To avoid starting them, we should record a ".js" file for each entry point that includes it and all of its deps.  Then we can just add that file in one big block (I think).
            System.out.println("*** Initializing type: " + e.typeName + " in postProcess");
            e.initType(sys);
         }
         if (!e.type.isStarted()) {
            System.out.println("*** starting in post process");
            ParseUtil.initAndStartComponent(e.type);
         }
      }
      */

      PerfMon.start("postProcessJS");

      for (Map.Entry<String,JSGenFile>ent:jsBuildInfo.jsGenFiles.entrySet()) {
         String jsFile = ent.getKey();

         JSGenFile jsg = ent.getValue();
         LinkedHashMap<JSFileEntry,Boolean> typesInFile = new LinkedHashMap<JSFileEntry,Boolean>();
         String absFilePath = FileUtil.concat(genLayer.buildDir, getJSPathPrefix(genLayer), FileUtil.unnormalize(jsFile));

         // This gen file has not been added yet in the layer stack so no processing for it.
         if (jsg.fromLayer.getLayerPosition() > genLayer.getLayerPosition() || (genLayer != sys.buildLayer && !genLayer.extendsLayer(jsg.fromLayer) && !genLayer.extendsLayer(jsg.toLayer)))
            continue;

         boolean changed = isJSFileChanged(jsFile);

         // We may have added to jsChangedFiles or set changedDefaultType after postStart for this layer (i.e. cause the change is in a downstream build layer)
         if (changed && genLayer != system.buildLayer) {
            boolean unchangedInLayer = false;
            for (EntryPoint e:jsBuildInfo.entryPoints.values()) {
               if (e.jsFile.equals(jsFile) && e.type == null)
                  unchangedInLayer = true;
            }
            if (unchangedInLayer)
               changed = false;
         }

         // Reassemble files explicitly marked as changed or if the file contains default types, if any default types have changed, we need to reassemble them.
         if (!changed) {
            Layer prev = genLayer.getPreviousLayer();
            boolean inherited = false;
            if (prev != null) {
               SrcEntry baseSrc = findSrcEntryForJSFile(prev, jsFile);
               if (baseSrc != null) {
                  File thisGenJSFile = new File(absFilePath);
                  if (jsg.toLayer.getLayerPosition() < genLayer.getLayerPosition()) {
                     File baseFile = new File(baseSrc.absFileName);
                     // TODO: For incremental builds, do we need to keep track of which generated JS files are inherited?  It can get tricky to figure out
                     // because of a case where the module entry point's class extends a class which is overridden in the new layer.  That changes the JS file
                     // but currently does not modify the entry point so we don't know it's modified in the subsequent layer.
                     if (!thisGenJSFile.canRead() || thisGenJSFile.lastModified() < baseFile.lastModified()) {
                        if (sys.options.verbose)
                           System.out.println("Inheriting js file: " + jsFile + " from build layer: " + baseSrc.layer + " to: " + genLayer);

                        inherited = true;

                        // Track the min and max layers for each gen file.  If we find the layer for the file and it includes the last type, just copy from there to here.
                        // If not, our version must be more up to date if it's not changed so do not copy.
                        FileUtil.copyFile(baseSrc.absFileName, absFilePath, true);
                     }
                  }
               }
            }
            if (sys.options.verbose && !inherited)
               System.out.println("Unchanged js file: " + jsFile + " in build layer: " + genLayer);

            continue;
         }

         if (sys.options.verbose)
            System.out.println("Generating js file: " + jsFile + " for build layer: " + genLayer);

         StringBuilder jsFileBody = new StringBuilder();
         jsFileBodyStore.put(jsFile, jsFileBody);
         entryPointsFrozen = true;
         try {
            // First define the types for each entry point that goes in this file
            for (EntryPoint e:jsBuildInfo.entryPoints.values()) {
               e.initLayer(sys);
               if (e.layer != null && !genLayer.buildsLayer(e.layer))
                  continue;

               if (e.jsFile.equals(jsFile)) {
                  /*
                  if (e.type == null) {
                     System.out.println("*** Initializing type (2): " + e.typeName + " in postProcess");
                     e.initType(sys);
                  }
                  */
                  addTypeToFile(e.getResolvedType(sys), typesInFile, jsFile, genLayer, null);
               }
            }
         }
         finally {
            entryPointsFrozen = false;
            if (queuedEntryPoints != null) {
               System.err.println("*** Entry points added during addTypeToFile: " + queuedEntryPoints) ;
               queuedEntryPoints = null;
            }
         }

         boolean needsSync = hasDependency(jsFile, "js/sync.js");
         StringBuilder initBody = new StringBuilder();
         for (EntryPoint e:jsBuildInfo.entryPoints.values()) {
            if (e.jsFile.equals(jsFile)) {
               if (e.needsInit) {
                  BodyTypeDeclaration initType = e.getResolvedType(sys);
                  if (sys.options.verbose)
                     initBody.append("console.log(\"Init type: " + initType.typeName + "\");\n");

                  if (!e.isMain)
                     initBody.append(getInstanceTemplate(initType));
                  else
                     initBody.append(evalMainTemplate(initType));
               }
            }
         }
         if (needsSync && initBody.length() > 0) {
            // Now generate the main object/method call to kick things off, wrapped around a start/end sync call.
            jsFileBody.append(JSRuntimeProcessor.SyncBeginCode);
            jsFileBody.append(initBody);
            jsFileBody.append(JSRuntimeProcessor.SyncEndCode);
         }
         else
            jsFileBody.append(initBody);
         FileUtil.saveStringAsFile(absFilePath, jsFileBody.toString(), true);
      }

      // Reset the build after the build layer has processed it.  The start phase happens for all build layers up front so we need to preserve the
      // changed state from one build layer to the next.
      if (genLayer == system.buildLayer)
         resetBuild();
      // For regular build layers we need to clear the processedTypes or we can't resave the same types again (though maybe we don't need to?)
      else
         resetBuildLayerState();

      PerfMon.end("postProcessJS");
   }

   public void resetBuildLayerState() {
      processedTypes.clear();
      typesInFileMap.clear();
   }

   public void resetBuild() {
      resetBuildLayerState();
      changedJSFiles.clear();
      changedDefaultType = false;
      jsFileBodyStore.clear();
      postStarted = false;

      if (system.options.buildAllFiles) {
         // TODO: should we reset anything in the JSBuildInfo?  Since we don't reliably run the start process, I don't think so
      }
   }

   public boolean getCompiledOnly() {
      return true;
   }

   public boolean getNeedsAnonymousConversion() {
      return true;
   }

   public CharSequence processModelStream(ModelStream stream) {
      StringBuilder sb = new StringBuilder();

      if (stream.modelList == null)
         return "";

      stream.setLayeredSystem(system);

      for (JavaModel model: stream.modelList) {
         // Disable the 'merge' so modifies do not stuff themselves into the base type - just transform the statements to Java.  Do this before we start the model
         // so the JS runtime knows not to look at this model in its processing in JSRuntimeProcessor.start.
         model.setMergeDeclaration(false);
         ParseUtil.initAndStartComponent(model);
         model.transform(ILanguageModel.RuntimeType.JAVA);

         boolean syncTemplate = stream.syncCtx != null;
         for (TypeDeclaration modelType:model.types) {
            // if updating the system types, first append any updates to this type, then the code to update any instances which will depend on the type being updated
            if (!syncTemplate)
               appendJSMergeUpdateTemplate(modelType, sb);
            // When we are updating the newly created type, not part of the sync context (where that's really just creating an instance)
            // NOTE: this same logic is also in appendInnerJSMergeTemplate
            appendJSMergeTemplate(modelType, sb, syncTemplate);
            /*
            if (syncTemplate || modelType instanceof ModifyDeclaration)
               appendJSMergeTemplate(modelType, sb, syncTemplate);
            else
               appendJSTypeTemplate(modelType, sb);
            */
         }
      }

      return sb;
   }

   public void sortJSFiles() {
      int sz = jsBuildInfo.jsFiles.size();
      int lastMax = -1;
      String lastFile = null;
      int swapCount = 0;
      int maxCount = sz * sz + 5;
      for (int of = 0; of < sz-1; of++) {
         String thisFile = jsBuildInfo.jsFiles.get(of);
         for (int df = of+1; df < sz; df++) {
            String nextFile = jsBuildInfo.jsFiles.get(df);
            if (hasDependency(thisFile, nextFile)) {
               if (hasDependency(nextFile, thisFile)) {
                  System.err.println("*** Warning conflicting dependency between javascript libraries: " + thisFile + " and: " + nextFile + " with dependencies: " + jsBuildInfo.typeDeps);
                  break;
               }
               else {
                  jsBuildInfo.jsFiles.set(of, nextFile);
                  jsBuildInfo.jsFiles.set(df, thisFile);
                  //if (lastMax != -1 && lastMax == df && (thisFile.equals(lastFile) || nextFile.equals(lastFile))) {
                  //   System.err.println("*** Warning conflicting dependency between javascript libraries: " + getJSFilesRange(of, df) + " from dependencies: " + typeDeps);
                  //   break;
                  //}
                  // Sorting a partially connected graph is not as easy as it seems!  I feel like this is slow, hopefully thorough and with this check not going to cause any production problems.  I'm sure there's a better way :)
                  if (swapCount > maxCount) {
                     System.err.println("*** Aborting JS file sort - circular reference in dependencies: " + jsBuildInfo.typeDeps);
                     break;
                  }
                  of--; // Need to sort "of" to be sure it is sorted
                  lastMax = df;
                  lastFile = thisFile;
                  swapCount++;
                  break;
               }
            }
         }
      }
      // This is a placeholder we need to store dependencies to the default file.  Once we've sorted them, we can remove this one.
      jsBuildInfo.jsFiles.remove(defaultLib);
   }

   String getJSFilesRange(int from, int to) {
      StringBuilder sb = new StringBuilder();
      for (int i = from; i < to; i++) {
         if (i != from)
            sb.append(", ");
         sb.append(jsBuildInfo.jsFiles.get(i));
      }
      return sb.toString();
   }

   public boolean hasDependency(String fromJSFile, String toJSFile) {
      return jsBuildInfo.typeDeps.contains(new JSFileDep(fromJSFile, toJSFile));
   }

   Object resolveBaseType(Object depType) {
      // First get the most specific definition of this type
      if (ModelUtil.isCompiledClass(depType)) {
         if (ModelUtil.isPrimitive(depType))
            return null;
         //depType = ModelUtil.resolveSrcTypeDeclaration(system, depType);
         // But do not force the load of the src at this time.
         Object newDepType = ModelUtil.findTypeDeclaration(system, ModelUtil.getTypeName(depType), null, false);
         if (newDepType != null)
            depType = newDepType;
         else if (!ModelUtil.isArray(depType) && !ModelUtil.isAnonymousClass(depType))
            System.out.println("*** diagnostic code: remove!");

      }


      if (ModelUtil.isCompiledClass(depType)) {
         if (ModelUtil.isArray(depType)) {
            Object newDepType = resolveBaseType(ModelUtil.getArrayComponentType(depType));
            if (newDepType == null)
               return null;
         }

         Object enclType;
         if ((enclType = ModelUtil.getEnclosingType(depType)) != null)
            depType = resolveBaseType(enclType);
      }
      if (depType instanceof TypeParameter) {
         JavaType extType = ((TypeParameter) depType).extendsType;
         if (extType != null)
            depType = resolveBaseType(extType.getTypeDeclaration());
         else
            return null;
      }
      if (depType instanceof ArrayTypeDeclaration)
         depType = resolveBaseType(((ArrayTypeDeclaration) depType).getComponentType());
      if (depType instanceof ParamTypeDeclaration)
         depType = resolveBaseType(((ParamTypeDeclaration) depType).getBaseType());

      // In case we have updated the types since the dependent types were computed
      if (depType instanceof BodyTypeDeclaration)
         depType = ((BodyTypeDeclaration) depType).resolve(false);

      ParseUtil.realInitAndStartComponent(depType);

      return depType;
   }

   Object resolveSrcTypeDeclaration(Object typeObj) {
      Object newTypeObj;
      // Force load the source
      newTypeObj = ModelUtil.resolveSrcTypeDeclaration(system, typeObj, false, true);
      // Need to resolve this again
      if (newTypeObj instanceof BodyTypeDeclaration)
         newTypeObj = resolveBaseType(newTypeObj);
       return newTypeObj;
   }

   void addExtendsTypeLibsToFile(BodyTypeDeclaration td, Map<JSFileEntry,Boolean> typesInFile, String rootLibFile) {
      Object derivedType = td.getDerivedTypeDeclaration();
      Object extType = td.getExtendsTypeDeclaration();
      if (false && derivedType != extType) {
         derivedType = resolveBaseType(derivedType);
         if (derivedType instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration extTD = (BodyTypeDeclaration) derivedType;
            addTypeLibsToFile(extTD, typesInFile, rootLibFile, td, "modifies");
         }
      }
      if (extType != null ) {
         extType = resolveBaseType(extType);
         boolean needsCompile = false;
         if (!hasJSLibFiles(extType) && !hasJSCompiled(extType)) {
            needsCompile = true;
            extType = resolveSrcTypeDeclaration(extType);
         }
         if (extType instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration extTD = (BodyTypeDeclaration) extType;
            addTypeLibsToFile(extTD, typesInFile, rootLibFile, td, "extends");
         }
         else {
            addJSLibFiles(extType, true, rootLibFile, td, "extends");
            if (needsCompile) {
               System.err.println("*** Warning: no source for Javascript conversion for base class: " + ModelUtil.getTypeName(extType) + " of: " + td);
            }
         }
      }
      Object[] implTypes = td.getImplementsTypeDeclarations();
      if (implTypes != null) {
         for (Object implObj:implTypes) {
            implObj = resolveBaseType(implObj);
            boolean needsCompile = false;
            if (!hasJSLibFiles(implObj) && !hasJSCompiled(implObj)) {
               needsCompile = true;
               implObj = resolveSrcTypeDeclaration(implObj);
            }
            if (implObj instanceof BodyTypeDeclaration)
               addTypeLibsToFile((BodyTypeDeclaration) implObj, typesInFile, rootLibFile, td, "implements");
            else if (implObj != null) {
               addJSLibFiles(implObj, true, rootLibFile, td, "implements");

               if (needsCompile) {
                  System.err.println("*** Warning: no source for Javascript conversion for interface: " + ModelUtil.getTypeName(implObj) + " of: " + td);
               }
            }
         }
      }
      if (td.isComponentType())
         addJSLibFiles(IComponent.class, true, rootLibFile, td, "implements");
   }

   void addExtendsJSTypeToFile(BodyTypeDeclaration td, Map<JSFileEntry,Boolean> typesInFile, String rootLibFile, Layer genLayer, Set<String> typesInSameFile) {
      Object derivedType = td.getDerivedTypeDeclaration();
      Object extType = td.getExtendsTypeDeclaration();
      if (false && derivedType != extType) {
         derivedType = resolveBaseType(derivedType);
         if (derivedType instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration extTD = (BodyTypeDeclaration) derivedType;
            addTypeToFile(extTD, typesInFile, rootLibFile, genLayer, null);
         }
      }
      if (extType != null ) {
         extType = resolveBaseType(extType);
         if (extType instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration extTD = (BodyTypeDeclaration) extType;
            addTypeToFile(extTD, typesInFile, rootLibFile, genLayer, null);
         }
         else if (!hasJSLibFiles(extType) && !hasJSCompiled(extType))
            System.err.println("*** Can't convert compiled type: " + ModelUtil.getTypeName(extType) + " to JS");
      }
      Object[] implTypes = td.getImplementsTypeDeclarations();
      if (implTypes != null) {
         for (Object implObj:implTypes) {
            implObj = resolveBaseType(implObj);
            if (implObj instanceof BodyTypeDeclaration)
               addTypeToFile((BodyTypeDeclaration) implObj, typesInFile, rootLibFile, genLayer, null);
         }
      }
   }

   private boolean filteredDepType(Object type, Object depType) {
      if (depType == null)
         return true;
      if (type == depType)
         return true;
      if (depType instanceof EnumConstant)
         return true;
      return false;
   }

   private boolean inSameFile(String rootLibFile, Object type) {
      String typeLibFilesStr = getJSLibFiles(type);
      String[] typeLibFiles;
      String typeLibFile;
      String parentLibFile;
      boolean inParentFile = true;
      if (typeLibFilesStr == null) {
         parentLibFile = rootLibFile;
         typeLibFile = getJSModuleFile(type, true);
         if (typeLibFile == null)
            typeLibFile = parentLibFile;
         inParentFile = typeLibFile.equals(rootLibFile);
      }
      else {
         typeLibFiles = StringUtil.split(typeLibFilesStr,',');
         parentLibFile = typeLibFiles[0];
         typeLibFile = parentLibFile;
         inParentFile = false;
      }
      return inParentFile;
   }

   /** Adds the type to the jsFileBody for the lib file registered for this type (or the default lib file)  */
   void addTypeToFile(BodyTypeDeclaration type, Map<JSFileEntry,Boolean> typesInFile, String rootLibFile, Layer genLayer, Set<String> typesInSameFile) {
      BodyTypeDeclaration origType = type;
      ModelUtil.ensureStarted(type, true); // Coming from dependent types, we may not be started.
      boolean transformed;

      BodyTypeDeclaration txtype = type.getTransformedResult();
      if (txtype != null && txtype != type) {
         if (txtype.isTransformed()) {
            type = txtype;
            transformed = true;
         }
         else
            transformed = false;
      }
      else {
         transformed = type.isTransformed();
      }

      String typeLibFilesStr = getJSLibFiles(type);
      String[] typeLibFiles;
      String typeLibFile;
      String parentLibFile;
      boolean notInParentFile = false;
      if (typeLibFilesStr == null) {
         parentLibFile = rootLibFile;
         typeLibFile = getJSModuleFile(type, true);
         if (typeLibFile == null)
            typeLibFile = parentLibFile;
         notInParentFile = !typeLibFile.equals(rootLibFile);
      }
      else {
         typeLibFiles = StringUtil.split(typeLibFilesStr,',');
         parentLibFile = typeLibFiles[0];
         typeLibFile = parentLibFile;
         notInParentFile = true;
      }

      // Check if this type is replaced or overridden in an aliased package.  If so don't include it in this file either.
      if (!notInParentFile)
         notInParentFile = hasAlias(type);

      //addLibFile(typeLibFile, rootLibFile);
      StringBuilder jsFileBody = jsFileBodyStore.get(typeLibFile);

      String fullTypeName = type.getFullTypeName();
      JSFileEntry jsEnt = new JSFileEntry();
      jsEnt.fullTypeName = fullTypeName;
      Boolean state = typesInFile.get(jsEnt);
      // Already initializing this type
      if (state != null) {
         if (!notInParentFile && typesInSameFile != null) {
            typesInSameFile.add(type.getFullTypeName());
         }
         return;
      }

      if (state == null) {
         typesInFile.put(jsEnt, Boolean.FALSE);

         if (!notInParentFile) {
            addExtendsJSTypeToFile(type, typesInFile, rootLibFile, genLayer, typesInSameFile);

            boolean needsSave = false;
            // Is there an existing srcEnt in a previous build dir?
            SrcEntry srcEnt = findJSSrcEntry(genLayer, type);
            if (srcEnt == null) {
               srcEnt = getJSSrcEntry(genLayer, genLayer.buildSrcDir, type);
               needsSave = true;
            }
            // If so, we need to save this guy if it's not already been processed and it's in the same layer
            else
               needsSave = srcEnt.layer == genLayer && !processedTypes.contains(fullTypeName);

            if (typesInSameFile != null)
               typesInSameFile.add(fullTypeName);

            // If we load java files through the layer's preCompiledSrcPath, getProcessFile is not called so we just need to save that type here.
            if (needsSave) {
               //if (!transformed && type.needsTransform())
               //   System.err.println("*** No transformed type for: " + type.typeName + " in convert to JS");

               // The process of converting to JS will introduce references to types like 'var' that are not defined so we turn off error reporting.  The errors will come out in the browser anyway :)
               type.getJavaModel().disableTypeErrors = true;

               saveJSTypeToFile(type, genLayer, genLayer.buildSrcDir, null);
            }

            try {
               String fileBody = FileUtil.getFileAsString(srcEnt.absFileName);
               jsFileBody.append(fileBody);
            }
            catch (IllegalArgumentException exc) {
               System.err.println("*** No dependent js file: " + srcEnt.absFileName);
            }

            BodyTypeDeclaration enclType = type.getEnclosingType();
            JSTypeInfo typeInfo = jsBuildInfo.jsTypeInfo.get(fullTypeName);

            // When we have not transformed the type and we've cached the type to file membership, we can take a faster path and just gather up the files.
            // We can't use the untransformed model to reliably get the list of inner types cause it mises the __Anon classes and anything which is generated.
            // The order is also messed up.
            if (needsSave || (enclType == null && typeInfo.typesInSameFile == null) || processedTypes.contains(fullTypeName)) {
               if (enclType == null) {
                  // Preserve the order in which we add them but make it quick to reject duplicates
                  typesInSameFile = new LinkedHashSet<String>();
                  if (typeInfo == null)
                     System.err.println("*** no type info for: " + fullTypeName);
                  else
                     typeInfo.typesInSameFile = (LinkedHashSet<String>) typesInSameFile;
               }
               // Because the inner types can extend the outer type (but not the other way around) we need to define them after the parent type.
               addInnerTypesToFile(type, typesInFile, parentLibFile, genLayer, typesInSameFile);

               if (enclType == null) {
                  // Since the dependent types for the parent type include the inner type we are only processing them for the outer type
                  Set<Object> depTypes = type.getDependentTypes();

                  if (depTypes.contains(Bind.class))
                     depTypes.add(system.getSrcTypeDeclaration("sc.js.bind.Bind", null, true, false, false));

                  for (Object depType:depTypes) {
                     // This represents a dependency on the @Component types we inject via code gen, not a real dependency on this class.
                     // TODO: change this to a private sentinel class as this will likely break ComponentImpl as a real class!
                     if (depType == ComponentImpl.class)
                        continue;
                     depType = resolveBaseType(depType);

                     if (filteredDepType(type, depType))
                        continue;
                     if (ModelUtil.isParameterizedType(depType))
                        depType = ModelUtil.getParamTypeBaseType(depType);
                     if (!(depType instanceof BodyTypeDeclaration)) {
                        if (ModelUtil.isCompiledClass(depType)) {
                           if (!ModelUtil.isPrimitive(depType)) {
                              String depTypeName = ModelUtil.getTypeName(depType);
                              JSTypeInfo depJTI = getJSTypeInfo(depTypeName, false);
                              if (depJTI != null) {
                                 // If it's an unassigned class it's in the same file, or if the files match.
                                 if (depJTI.jsModuleFile == null || (typeLibFile != null && depJTI.jsModuleFile.equals(typeLibFile))) {
                                    addCompiledTypesToFile(depTypeName, typesInFile, parentLibFile, genLayer, jsFileBody, typesInSameFile);
                                 }
                                 continue;
                              }
                              else if (hasJSLibFiles(depType))
                                 continue;
                              // TODO: remove this as it's redundant now since that logic is folded in above.
                              /*
                              if (hasJSCompiled(depClass)) {
                                 continue;
                              }
                              */
                              //if (addJSLibFiles(depClass, false, typeLibFile) == null)
                              //   System.err.println("*** Warning: js type: " + type.typeName + " depends on compiled class: " + depType);
                           }
                        }
                        else if (!isCompiledInType(depType))
                           System.err.println("*** Warning: unrecognized js type: " + type.typeName + " depends on compiled thing: " + depType);
                     }
                     else {
                        BodyTypeDeclaration depTD = (BodyTypeDeclaration) depType;
                        ModelUtil.ensureStarted(depType, true);
                        depTD = depTD.resolve(true);

                        // If depTD extends type, do not add it here.  Instead, we need to add type before depTD in this case
                        if (!type.isAssignableFrom(depTD, false))
                           addTypeToFile(depTD, typesInFile, parentLibFile, genLayer, typesInSameFile);
                     }
                  }
               }
            }
            else if (enclType == null) {
               if (typeInfo.typesInSameFile != null) {
                  for (String subTypeName:typeInfo.typesInSameFile) {
                     addCompiledTypesToFile(subTypeName, typesInFile, rootLibFile, genLayer, jsFileBody, typeInfo.typesInSameFile);
                  }
               }
            }
         }
         typesInFile.put(jsEnt, Boolean.TRUE);
      }
   }

   private boolean isCompiledInType(Object depType) {
      return ((depType instanceof PrimitiveType) || (depType instanceof TypeVariable) || (ModelUtil.isWildcardType(depType)) || ModelUtil.isUnboundSuper(depType));
   }

   private void addCompiledTypesToFile(String typeName, Map<JSFileEntry,Boolean> typesInFile, String rootLibFile, Layer genLayer, StringBuilder jsFileBody, Set<String> typesInSameFile) {
      JSTypeInfo subTypeInfo = jsBuildInfo.jsTypeInfo.get(typeName);
      if (subTypeInfo != null && !subTypeInfo.presentInLayer(genLayer))
         return;

      Object type = ModelUtil.findTypeDeclaration(system, typeName, genLayer, false);
      if (type instanceof BodyTypeDeclaration) {
         addTypeToFile((BodyTypeDeclaration) type, typesInFile, rootLibFile, genLayer, typesInSameFile);
      }
      else {
         SrcEntry subSrcEnt = findJSSrcEntryFromTypeName(genLayer, typeName);

         JSFileEntry subFileEnt = new JSFileEntry();
         subFileEnt.fullTypeName = typeName;
         if (typesInFile.get(subFileEnt) != null)
            return;

         if (subSrcEnt == null) {
            return;
         }
         try {
            String fileBody = FileUtil.getFileAsString(subSrcEnt.absFileName);
            jsFileBody.append(fileBody);

            if (subTypeInfo == null)
               System.err.println("*** Missing JS type info");
            else if (subTypeInfo.typesInSameFile != null) {
               for (String subTypeName:subTypeInfo.typesInSameFile) {
                  if (typeName.equals(subTypeName)) {
                     System.err.println("*** Invalid sub-type name - same as main type: " + subTypeName);
                     continue;
                  }
                  addCompiledTypesToFile(subTypeName, typesInFile, rootLibFile, genLayer, jsFileBody, subTypeInfo.typesInSameFile);
               }
            }

            typesInFile.put(subFileEnt, Boolean.TRUE);
         }
         catch (IllegalArgumentException exc) {
            System.err.println("*** No sub js file: " + subSrcEnt.absFileName);
         }
      }

   }

   Template getMergeJSTemplate(BodyTypeDeclaration td, boolean isSync) {
      Template useTemplate = isSync ? syncMergeTemplate : updateMergeTemplate;
      if (useTemplate == null) {
         String templName = isSync ? syncMergeTemplateName : updateMergeTemplateName;
         if (templName == null) {
            System.err.println("*** mergeTemplateName property on JSRuntimeProcessor not set");
         }
         else {
            useTemplate = td.findTemplatePath(templName, "JSRuntimeProcessor " + (isSync ? "syncMergeTemplate" : "updateMergeTemplate"), JSTypeParameters.class);
            if (useTemplate == null)
               System.err.println("*** Can't process as a valid template for JSRuntiemProcessor: " + templName);
            else {
               if (isSync)
                  syncMergeTemplate = useTemplate;
               else
                  updateMergeTemplate = useTemplate;
            }
         }
      }
      return useTemplate;
   }

   String getJSTypeTemplatePath(BodyTypeDeclaration td) {
      Object settingsObj = td.getInheritedAnnotation(("sc.js.JSSettings"));
      Template toUseTemplate = typeTemplate;
      if (settingsObj != null) {
         String path = (String) ModelUtil.getAnnotationValue(settingsObj, "typeTemplate");
         if (path != null && path.length() > 0)
            return path;
      }
      return typeTemplateName;
   }

   Template getJSTypeTemplate(BodyTypeDeclaration td) {
      Object settingsObj = td.getInheritedAnnotation(("sc.js.JSSettings"));
      if (typeTemplate == null) {
         if (typeTemplateName == null) {
            System.err.println("*** typeTemplateName property on JSRuntimeProcessor not set");
         }
         else {
            typeTemplate = td.findTemplatePath(typeTemplateName, "JSRuntimeProcessor typeTemplateName", JSTypeParameters.class);
            if (typeTemplate == null)
               System.err.println("*** Can't process typeTemplateName as a valid template for JSRuntiemProcessor: " + typeTemplateName);
         }
      }
      Template toUseTemplate = typeTemplate;
      if (settingsObj != null) {
         Template customTemplate = td.findTemplate(settingsObj, "typeTemplate", JSTypeParameters.class);
         if (customTemplate != null)
            toUseTemplate = customTemplate;
      }
      return toUseTemplate;
   }

   BodyTypeDeclaration ensureTransformedResult(BodyTypeDeclaration td) {
      td = td.resolve(true);  // First resolve to get back to the most specific type
      td = td.getTransformedResult();  // Then walk back down to get the transformed result - the end Java version.  This is probably over kill...
      return td;
   }

   boolean appendJSType(BodyTypeDeclaration td, StringBuilder result) {
      td = ensureTransformedResult(td);
      if (td == null) {
         System.err.println("*** No trnasformed result in appendJSType: ");
         return false;
      }
      if (!processedTypes.contains(td.getFullTypeName()) && !hasJSLibFiles(td)) {
         td.getJavaModel().disableTypeErrors = true;
         appendJSTypeTemplate(td, result);
         //appendInnerJSTypes(td, result);
         return true;
      }
      return false;
   }

   void addInnerTypesToFile(BodyTypeDeclaration td, Map<JSFileEntry,Boolean> typesInFile, String rootLibFile, Layer genLayer, Set<String> typesInSameFile) {
      td = ensureTransformedResult(td);
      if (td instanceof EnumDeclaration) {
         EnumDeclaration ed = (EnumDeclaration) td;
         td = ed.transformEnumToJS();
      }
      List<Object> innerTypes = td.getAllInnerTypes(null, true);
      if (innerTypes != null) {
         for (Object innerTypeObj:innerTypes) {
            if (innerTypeObj instanceof BodyTypeDeclaration) {
               if (innerTypeObj instanceof EnumConstant)
                  continue;
               BodyTypeDeclaration innerType = (BodyTypeDeclaration) innerTypeObj;

               addTypeToFile(innerType, typesInFile, rootLibFile, genLayer, typesInSameFile);
            }
            else
               System.err.println("*** Warning: can't convert class: " + innerTypeObj + " into Javascript - .class but no source code found");
         }
      }

   }

   public String[] getJsFiles() {
      return jsBuildInfo.jsFiles.toArray(new String[jsBuildInfo.jsFiles.size()]);
   }

   public int getExecMode() {
      return Element.ExecClient;
   }

   public String replaceMethodName(LayeredSystem sys, Object methObj, String name) {
      methObj = ModelUtil.resolveSrcMethod(sys, methObj, false, false);

      Object jsMethSettings = ModelUtil.getAnnotation(methObj, "sc.js.JSMethodSettings");
      if (jsMethSettings != null) {
         String replaceWith = (String) ModelUtil.getAnnotationValue(jsMethSettings, "replaceWith");
         if (replaceWith != null && replaceWith.length() > 0) {
            RTypeUtil.addMethodAlias(ModelUtil.getTypeName(ModelUtil.getEnclosingType(methObj)), name, replaceWith);
            return replaceWith;
         }
      }
      return name;
   }

   public void setLayeredSystem(LayeredSystem sys) {
      super.setLayeredSystem(sys);
      sys.enableRemoteMethods = false; // Don't look for remote methods in the JS runtime.  Changing this to true is a bad idea, even if you wanted the server to call into the browser.  We have some initialization dependency problems to work out.
   }

   public LayeredSystem getLayeredSystem() {
      return system;
   }

   public void runMainMethod(Object type, String runClass, String[] runClassArgs) {
      if (system.options.verbose)
         System.out.println("Warning: JSRuntime - not running main method for: " + runClass + " - this will run in the browser");
   }



   // TODO: remove this in favor of loadClassesInRuntime?
   /** This runtime is not using the standard system class loader - so runtime types do not look in the class loader */
   public boolean usesThisClasspath() {
      return false;
   }

   public List<String> getJSFiles(Object type) {
      String typeName = ModelUtil.getTypeName(type);
      String modFile = getCachedJSModuleFile(typeName);
      if (modFile == null) {
         // Get the src file for this type in this runtime.  The type given may be from another runtime.
         // Do not look up the type unless we need to because otherwise we load stuff into the JS runtime that may not otherwise be needed.
         type = system.getRuntimeTypeDeclaration(typeName);
         // If there's no type name in the javascript runtime, no need for any JS files.
         if (type == null)
            return null;

         modFile = getJSModuleFile(type, true);
         if (modFile == null)
            return jsBuildInfo.jsFiles;
      }
      int startIx = jsBuildInfo.jsFiles.indexOf(modFile);
      ArrayList<String> resFiles = new ArrayList<String>();
      resFiles.add(modFile);

      for (int i = startIx-1; i >= 0; i--) {
         String curFile = jsBuildInfo.jsFiles.get(i);
         for (int j = 0; j < resFiles.size(); j++) {
            if (hasDependency(resFiles.get(j), curFile)) {
               resFiles.add(0, curFile);
               break;
            }
         }
      }
      return resFiles;
   }

   private final static String JS_BUILD_INFO_FILE = "jsBuildInfo.ser";

   /** Returns true if we need to rebuild all.  If fromScratch is true, we reset for a new full build */
   public boolean initRuntime(boolean fromScratch) {
      if (!fromScratch || jsBuildInfo != null)
         return false;
      if (system.options.buildAllFiles) {
         jsBuildInfo = new JSBuildInfo();
         return true;
      }
      else {
         String jsBuildInfoFile = FileUtil.concat(system.buildDir, JS_BUILD_INFO_FILE);
         File f = new File(jsBuildInfoFile);
         ObjectInputStream ois = null;
         FileInputStream fis = null;
         if (f.canRead()) {
            try {
               ois = new ObjectInputStream(fis = new FileInputStream(f));
               JSBuildInfo res = (JSBuildInfo) ois.readObject();
               if (res != null) {
                  jsBuildInfo = res;

                  res.init(system);
               }

               // Were able to restore the incremental build info so don't rebuild all
               return false;
            }
            catch (InvalidClassException exc) {
               System.out.println("JS dependency type info - version changed: " + this);
               f.delete();
            }
            catch (IOException exc) {
               System.out.println("*** can't read js build info file: " + exc);
               f.delete();
            }
            catch (ClassNotFoundException exc) {
               System.out.println("*** can't read js build info file: " + exc);
               f.delete();
            }
            finally {
               try {
                  if (ois != null)
                     ois.close();
                  if (fis != null)
                     fis.close();
               }
               catch (IOException exc) {}
            }
         }
         else if (system.options.verbose)
            System.out.println("*** Missing jsBuildInfoFile: " + jsBuildInfoFile + " building all files");
         if (jsBuildInfo == null)
            jsBuildInfo = new JSBuildInfo();
         return true;
      }
   }

   public void saveRuntime() {
      File jsBuildInfoFile = new File(system.buildDir, JS_BUILD_INFO_FILE);

      ObjectOutputStream os = null;
      FileOutputStream fos = null;
      try {
         os = new ObjectOutputStream(fos = new FileOutputStream(jsBuildInfoFile));
         os.writeObject(jsBuildInfo);
      }
      catch (IOException exc) {
         System.out.println("*** can't write js build info file for incremental builds: " + jsBuildInfo + ":" + exc);
      }
      finally {
         try {
            if (os != null)
               os.close();
            if (fos != null)
               fos.close();
         }
         catch (IOException exc) {}
      }
   }

   /** Called after we clear all of the layers to reset the JSBuildInfo state */
   public void clearRuntime() {
      jsBuildInfo = null;
      resetBuild();
      srcPathType = null;
      templatePrefix = null;
      genJSPrefix = null;
      typeTemplateName = syncMergeTemplateName = updateMergeTemplateName = null;
   }

   public class JSUpdateInstanceInfo extends UpdateInstanceInfo {
      ModelStream updateStream = new ModelStream(false);
      HashMap<String,TypeDeclaration> typeIndex = new HashMap<String,TypeDeclaration>();

      public CharSequence convertToJS() {
         return processModelStream(updateStream);
      }

      public void updateInstances(ExecutionContext ctx) {
         super.updateInstances(ctx);
         applySystemUpdates(this);
      }

      /** Returns a modify declaration in the model stream for  */
      public TypeDeclaration getOrCreateModifyDeclaration(String fullTypeName, String modelTypeName, String modelPackageName) {
         TypeDeclaration res = typeIndex.get(fullTypeName);
         if (res != null)
            return res;

         // An inner type - need to get the parent type
         if (modelTypeName != null && !modelTypeName.equals(fullTypeName)) {
            String parentTypeName = CTypeUtil.getPackageName(fullTypeName);
            String baseTypeName = CTypeUtil.getClassName(fullTypeName);
            TypeDeclaration rootType = getOrCreateModifyDeclaration(parentTypeName, modelTypeName, modelPackageName);
            res = ModifyDeclaration.create(baseTypeName);
            rootType.addSubTypeDeclaration(res);
         }
         // The root type - need to create a new model and root type
         else {
            res = ModifyDeclaration.create(CTypeUtil.getClassName(fullTypeName));
            SCModel model = SCModel.create(modelPackageName, res);
            updateStream.addModel(model);
         }
         typeIndex.put(fullTypeName, res);
         return res;
      }

      public class JSExecBlock extends ExecBlock {
         public void doAction(ExecutionContext ctx) {
            super.doAction(ctx);

            JavaModel model = baseType.getJavaModel();
            TypeDeclaration modifyType = getOrCreateModifyDeclaration(baseType.getFullTypeName(), model.getModelTypeName(), model.getPackagePrefix());
            modifyType.addBodyStatement(blockStatement.deepCopy(ISemanticNode.CopyNormal | ISemanticNode.CopyInitLevels, null));
         }
      }

      public JSExecBlock newExecBlock() {
         return new JSExecBlock();
      }

      public class JSUpdateProperty extends UpdateProperty {
         public void doAction(ExecutionContext ctx) {
            super.doAction(ctx);

            JavaModel model = baseType.getJavaModel();
            TypeDeclaration modifyType = getOrCreateModifyDeclaration(baseType.getFullTypeName(), model.getModelTypeName(), model.getPackagePrefix());

            if (overriddenAssign instanceof VariableDefinition || overriddenAssign instanceof PropertyAssignment) {
               IVariableInitializer assign = (IVariableInitializer) overriddenAssign;
               Expression expr = assign.getInitializerExpr();
               if (expr != null)
                  expr = expr.deepCopy(ISemanticNode.CopyNormal | ISemanticNode.CopyInitLevels, null);
               String propName = ModelUtil.getPropertyName(overriddenAssign);
               PropertyAssignment pa = PropertyAssignment.create(propName, expr, assign.getOperatorStr());

               modifyType.addBodyStatement(pa);

               if (baseType.changedMethods == null)
                  baseType.changedMethods = new TreeSet<String>();
               String upperPropName = CTypeUtil.capitalizePropertyName(propName);
               // TODO: should probably validate this VarDef has get set but this is not the definitive list, it just specifies matching methods get put into the output
               baseType.changedMethods.add("get" + upperPropName);
               baseType.changedMethods.add("set" + upperPropName);
            }
            else if (overriddenAssign instanceof TypeDeclaration) {
               String opName = "add";
               switch (initType) {
                  case Init:
                     TypeDeclaration newType;
                     newType = (TypeDeclaration) overriddenAssign;
                     TypeDeclaration newTypeCopy = newType.deepCopy(ISemanticNode.CopyNormal | ISemanticNode.CopyInitLevels, null);
                     modifyType.addBodyStatement(newTypeCopy);
                     // Initialize the field to null
                     modifyType.addBodyStatement(PropertyAssignment.createStarted(CTypeUtil.decapitalizePropertyName(newType.typeName), NullLiteral.create(), "=", newType));
                     break;
                  case Remove:
                     opName = "remove";
                  case Add:
                     newType = (TypeDeclaration) overriddenAssign;
                     SemanticNodeList addArgs = new SemanticNodeList();
                     if (opName.equals("add")) {
                        Object[] children = baseType.getObjChildrenTypes(null, false, false, false);
                        if (children != null) {
                           int ix = Arrays.asList(children).indexOf(newType);
                           if (ix != -1)
                              addArgs.add(IntegerLiteral.create(ix));
                        }
                     }
                     addArgs.add(IdentifierExpression.create(new NonKeywordString("this")));
                     SemanticNodeList resolveArgs = new SemanticNodeList();
                     resolveArgs.add(StringLiteral.create(newType.getFullTypeName()));
                     resolveArgs.add(BooleanLiteral.create(true));
                     addArgs.add(IdentifierExpression.createMethodCall(resolveArgs, "sc.dyn.DynUtil.resolveName"));
                     // DynUtil.addChild and DynUtil.removeChild here
                     IdentifierExpression addChildCall = IdentifierExpression.createMethodCall(addArgs, "sc.dyn.DynUtil." + opName + "Child");
                     BlockStatement bs = new BlockStatement();
                     bs.addStatementAt(0, addChildCall);
                     modifyType.addBodyStatement(bs);
                     break;
               }
            }
         }
      }

      public JSUpdateProperty newUpdateProperty() {
         return new JSUpdateProperty();
      }

      /** For the default operation, removes are run as they are processed because it's too late to retrieve them after the field has been removed from the type. */
      public boolean queueRemoves() {
         return true;
      }

      public void typeChanged(BodyTypeDeclaration type) {
         JavaModel model = type.getJavaModel();
         // Once this model is changed in the modelStream, it will cause the type and instances to be updated when this is
         // pushed to the client.  No need to touch modType in this case.
         TypeDeclaration modType = getOrCreateModifyDeclaration(type.getFullTypeName(), model.getModelTypeName(), model.getPackagePrefix());
      }

      public boolean needsChangedMethods() {
         return true;
      }

      public void methodChanged(AbstractMethodDefinition methChanged) {
      }
   }

   public UpdateInstanceInfo newUpdateInstanceInfo() {
      return new JSUpdateInstanceInfo();
   }

   /*
   public CharSequence refreshSystem() {
      JSUpdateInstanceInfo info = new JSUpdateInstanceInfo();
      system.refreshSystem(info);
      system.completeRefreshSystem(info);
      return info.convertToJS();
   }
   */

   public boolean getLoadClassesInRuntime() {
      return false;
   }
}
