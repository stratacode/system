/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.*;
import sc.dyn.INameContext;
import sc.lang.*;
import sc.lang.js.JSRuntimeProcessor;
import sc.lang.sc.ModifyDeclaration;
import sc.obj.Constant;
import sc.sync.SyncManager;
import sc.type.CTypeUtil;
import sc.type.Type;
import sc.util.*;
import sc.layer.*;
import sc.parser.*;

import java.io.*;
import java.util.*;

/**
 * The semantic node corresponding to a Java file.  Look for "JavaModel" in the sc.lang.JavaLanguage grammar to find how this is created from code.
 * It's semantic properties (those set by the parser) are packageDef, imports, and types.
 * It's extended by other languages that can convert to Java (e.g. SCModel, JSModel, Template).
 * This class does a lot.  It manages it's dependencies on other files for faster incremental builds.  It can update a type index for IDEs to bootstrap type system
 * searches.  It supports a basic transformation capability to pre-process the Java code into a different format.  It can refresh itself from the file system -
 * updating any changes using the dynamic runtime.
 */
public class JavaModel extends JavaSemanticNode implements ILanguageModel, INameContext, IChangeable, IUserDataNode {
   static private final String[] SYSTEM_PACKAGE_PREFIX = {"java.", "javax."}; // Don't maintain dependencies on these system packages since those are not likely to change and require an incremental compile

   // Semantic properties - populated from the grammar:

   /** The package of the Java model if any */
   public Package packageDef;
   /** The list of imports */
   public SemanticNodeList<ImportDeclaration> imports;
   /** The set of types defined in this file.  Normally there's just one but Java does let you specify multiple types per file at the top-level. */
   public SemanticNodeList<TypeDeclaration> types;

   @Constant
   transient public LayeredSystem layeredSystem;

   @Constant
   transient public Layer layer;

   transient Set<String> externalReferences = new HashSet<String>();

   // Explicitly imported names map to the complete string
   transient HashMap<String,Object> importsByName = new HashMap<String,Object>();

   transient Map<String,Object> staticImportProperties;// Property name to beanmapper or Get/Set MethodDefinition
   transient Map<String,List<Object>> staticImportMethods;   // Method name, value is type object

   transient List<String> globalTypes = new ArrayList<String>();

   // Includes inner types as well as regular ones
   transient HashMap<String,TypeDeclaration> definedTypesByName = new HashMap<String,TypeDeclaration>();

   transient Map<String,Object> typeIndex = new HashMap<String,Object>();

   transient Object userData;

   /** A flag used in the IDE when the userData has been mapped */
   public transient boolean userDataMapped;

   /** During an add layer, this stores the old model - the one, this model replaced so we know how to do the refresh */
   transient public JavaModel replacesModel;

   /**
    * This is set just when we start reloading the new model to the new model.  We can't add the new types into the index
    * until they are initialized but in some cases we need to be aware of the new model before then, like in building
    * the templates properly
    */
   transient public JavaModel replacedByModel;

   transient public boolean removed;

   /** Has this model been reparsed since this flag was last checked */
   transient public boolean reparsed = false;

   // If not null, the package prefix we should set when we transform.  If it is null, package prefix
   // is defined as normal.
   transient private String computedPackagePrefix;

   transient public boolean disableTypeErrors;

   transient public boolean inTransform;

   transient public boolean hasErrors = false;

   transient public boolean isLayerModel;

   transient private boolean typeInfoInited = false;

   /** Set to true for models which are not part of the type system - i.e. documentation, etc. */
   transient public boolean temporary = false;

   public transient ReverseDependencies reverseDeps;

   public transient AbstractInterpreter commandInterpreter;

   /** Set to false to ignore the layer's package in determining the type (e.g. WEB-INF/web.xml) */
   public transient boolean prependLayerPackage = true;

   /** Stores contents of any files attached to this model, e.g. the gwt module templates associated with a module type */
   public transient List<ExtraFile> extraFiles = null;

   public transient List<SrcEntry> srcFiles;

   private transient boolean reverseDepsInited = false;

   private transient List<JavaModel> copyImportModels = null;

   /** Set so we can keep do incremental refreshes */
   public transient long lastModifiedTime = 0;

   /** Set to the timestamp of the time the model was last started */
   public transient long lastStartedTime = -1;

   /** Set to true during the build process if this model needs to stopped when it's reinitialized */
   public transient boolean needsRestart = false;

   public transient JavaModel modifiedModel = null;

   /** If you want to parse and start a model but insert your own name resolver which runs before the normal system's type look, set this property. */
   public transient ICustomResolver customResolver = null;

   protected transient boolean initPackage = false;

   // Set this to false for sync-layers and update-layers, i.e. which do not merge when they are transformed.
   public transient boolean mergeDeclaration = true;

   // When needsModelText is set to true,
   private transient String cachedModelText = null;

   private transient boolean needsModelText = false;

   // When needsGeneratedText is set to true,
   private transient String cachedGeneratedText = null;

   // When needsGeneratedText is set to true, this is populated
   private transient String cachedGeneratedJSText = null;

   // When needsGeneratedText is set to true, this is populated with any generated strata code (e.g. for schtml which is converted converted to sc, then to java)
   private transient String cachedGeneratedSCText = null;

   // When needsGeneratedText is set to true, this is populated with any generated strata code (e.g. for schtml which is converted converted to sc, then to java)
   private transient String cachedGeneratedClientJavaText = null;

   // For the main JavaModel, stores the one we cloned and transformed
   public transient JavaModel transformedModel;

   // Stores the current build layer at the time this layer was transformed.  We re-transform each type over again just in case
   // it depended on something that changed.  This is a place we could optimize by doing that only when it needs to be done.
   public transient Layer transformedInLayer = null;

   private transient Set<SrcEntry> dependentFiles = new HashSet<SrcEntry>();

   /** If this is a transformed model, this points back to the original */
   public transient JavaModel nonTransformedModel = null;

   /** Starts out true... if we find that we do not need the transform we set it to false. */
   private transient boolean cachedNeedsTransform = true;

   /** For models defined by a template, stores the suffix from the last template in the model.  Valid in the transformed model only. */
   public transient String resultSuffix;

   // The last started layer when this model was initialized.  Used to determine when we need to regenerate it when
   // dependent types are changed.
   public transient Layer initializedInLayer;

   /** Has this model been added to the type system */
   public transient boolean added = false;

   /** Is this a new model which has not yet been saved or a model whose memory representation is more recent than the file system */
   public transient boolean unsavedModel = false;

   /** Caches the line index for the generated file for this src model */
   private transient GenFileLineIndex fileLineIndex = null;

   /** Set to true during the stop operation so we can tell if someone tries to init or start us while being stopped */
   private transient boolean beingStopped = false;

   /** For models without a srcFile, the "a.b" name to append onto the layer package to get the package for the model */
   private transient String relDirPath;

   public void setLayeredSystem(LayeredSystem system) {
      layeredSystem = system;
   }

   public LayeredSystem getLayeredSystem() {
      return layeredSystem;
   }

   public void setLayer(Layer l) {
      if (l != null && l.layeredSystem != getLayeredSystem() && layeredSystem != null)
         System.out.println("*** Error - mismatching runtime for setLayer in Java model!");
      layer = l;
   }

   public void switchLayers(Layer l) {
      setLayer(l);

      // Need to potentially update the layer in the src files too - i.e. after we clone a model to parse it in more than one runtime.
      if (srcFiles != null) {
         for (SrcEntry srcEnt:srcFiles)
            srcEnt.layer = l;
      }

      if (types != null) {
         for (BodyTypeDeclaration type: types) {
            type.switchLayers(l);
         }
      }
   }

   public Layer getLayer() {
      return layer;
   }

   public void setDisableTypeErrors(boolean dte) {
      disableTypeErrors = dte;
   }

   public void addExternalReference(String type) {
      for (int i = 0; i < SYSTEM_PACKAGE_PREFIX.length; i++)
         if (type.startsWith(SYSTEM_PACKAGE_PREFIX[i]))
             return;

      externalReferences.add(type);
   }

   protected void initPackageAndImports() {
      if (initPackage)
         return;
      initPackage = true;
      String layerPackagePrefix;
      String relDirPath = getRelDirPath();
      if (layer != null && (layerPackagePrefix = layer.packagePrefix) != null) {
         // For layer model objects, the leading directories are not put into the prefix for the model - instead, they
         // go in the layer file as a prefix to the layer name.
         if (!isLayerModel) {
            if (layerPackagePrefix.length() != 0) {
               if (packageDef == null || packageDef.name == null) {
                  computedPackagePrefix = prependLayerPackage ? CTypeUtil.prefixPath(layerPackagePrefix, relDirPath) : relDirPath;
               }
               else {
                  // (the old way)The rules for when we rewrite the package:  If the package prefix is set for the layer containing
                  // these files, we'll always prepend it to any package definitions.  If the package definition is omitted
                  // entirely and this is not a top level file, we insert the package automatically.  If the package prefix
                  // is set, we'll insert it automatically no matter what.
                  //computedPackagePrefix = ModelUtil.prefixPath(layerPackagePrefix, packageDef.name);

                  // The new way: if the package is set, we just use that name but it is an error for it not to
                  // match the package prefix.  The mode above is so you can re-root a package but that seems like
                  // a special option and not something you'd do typically after thinking about it.
                  // The problem with the old way is that you could not mix plain Java files with V files in the same
                  // directory and use package prefixes.
                  if (!packageDef.name.startsWith(layerPackagePrefix))
                     displayError("Package definition: ", packageDef.name, " does not match package prefix: ", layerPackagePrefix, " for ");
               }
            }
            else if (packageDef == null && relDirPath != null) {
               computedPackagePrefix = relDirPath;
            }
         }
      }
      else if (layer == null && packageDef == null && relDirPath != null)
         computedPackagePrefix = relDirPath;

      // Create a map from class name to the full imported name.  Skip this for the layer objects, i.e.
      // before our layer is initialized.
      if (imports != null && !isLayerModel) {
         // used to test on this condition - && layer != null && layer.isInitialized()
         for (ImportDeclaration imp:imports) {
            addNonStaticImportInternal(imp, true);
         }
      }
   }

   public void init() {
      if (initialized)
         return;

      if (beingStopped) {
         System.err.println("*** Attempting to init model that's being stopped");
         return;
      }

      //initializedInLayer = layer != null ? layer.layeredSystem.lastModelsStartedLayer : null;
      // Used for setting fromPosition in the type cache - we want to mark which layers are included in the search
      // to know when the type cache need to be refreshed for a new layer
      initializedInLayer = layer != null ? layer.layeredSystem.lastStartedLayer : null;

      initPackageAndImports();

      // Initialize our types info before we do children as they need this info
      super.init();
   }

   public void addTypeDeclaration(TypeDeclaration td) {
      if (types == null) {
         setProperty("types", new SemanticNodeList(), false, true);
      }
      types.add(td);
      if (td.typeName != null)
         addTypeToIndex(td.typeName, td);
   }

   private void addTypeToIndex(String typeName, Object td) {
      typeIndex.put(typeName,td);
   }

   public String getChildTypeName(BodyTypeDeclaration bodyTypeDeclaration, String useTypeName) {
      return CTypeUtil.prefixPath(getPackagePrefix(), useTypeName);
   }

   private static class WildcardImport {
      public String typeName;
      public WildcardImport(String tn) {
         typeName = tn;
      }
   }

   private void addNonStaticImportInternal(ImportDeclaration imp, boolean checkErrors) {
      String impStr = imp.identifier;
      if (!imp.staticImport && impStr != null) {
         String className = CTypeUtil.getClassName(impStr);
         if (className.equals("*")) {
            String pkgName = CTypeUtil.getPackageName(impStr);
            Set<String> filesInPkg = layeredSystem == null ? null : layeredSystem.getFilesInPackage(pkgName);
            if (filesInPkg != null) {
               for (String impName:filesInPkg) {
                  // A wildcard import should not override an explicit one
                  if (importsByName.get(impName) == null)
                     importsByName.put(impName, new WildcardImport(CTypeUtil.prefixPath(pkgName, impName)));
               }
            }
            else {
               if (globalTypes == null)
                  globalTypes = new ArrayList<String>();
               globalTypes.add(CTypeUtil.getPackageName(impStr));
            }
         }
         /*
          * This happens too early to validate the imports so we do it later on
         else if (checkErrors && findTypeDeclaration(impStr, true) == null) {
            imp.displayTypeError("No type: " + className + " for: ");
         }
         */
         importsByName.put(className, impStr);
      }
   }

   public void start() {
      if (started) return;

      if (layeredSystem != null)
         lastStartedTime = layeredSystem.lastChangedModelTime;

      PerfMon.start("startJavaModel");

      // The layer model can't do this until the layer is started and its class path is set up.
      if (!isLayerModel)
         initTypeInfo();
      else {
         // When resolving a layer model in the IDE, we might start the layer modify type here and it does not resolve
         // correctly unless it knows it's a layer type.
         TypeDeclaration layerModelType = getLayerTypeDeclaration();
         if (layerModelType != null)
            layerModelType.isLayerType = true;
      }
      super.start();

      // Propagate the resultSuffix to the leaf model
      setResultSuffix(resultSuffix);
      // Then pull it back from the first model where it was set so it's consistent up and down the chain.
      if (resultSuffix == null)
         resultSuffix = getResultSuffix();

      // Need to initialize this before we transform as that will change things around
      modifiedModel = getModifiedModel();

      PerfMon.end("startJavaModel");

      //startReplacingTypes();
   }

   public void validate() {
      if (validated) return;

      // Doing this after the types have been started as doing it when imports are defined is too early to resolve stuff
      // we depend upon
      if (imports != null && !isLayerModel) {
         // used to test on this condition - && layer != null && layer.isInitialized()
         for (ImportDeclaration imp:imports) {
            if (imp.staticImport)
               continue;
            String impStr = imp.identifier;
            if (impStr == null)
               continue;
            String className = CTypeUtil.getClassName(impStr);
            if (className.equals("*"))
               continue;
            if (findTypeDeclaration(imp.identifier, true) == null) {
               String[] idents = imp.identifier.split("\\.");
               int rangeMax = idents.length - 1;
               int rangeMin = rangeMax;
               String ident = imp.identifier;
               while (rangeMin > 0) {
                  ident = CTypeUtil.getPackageName(ident);
                  if (layeredSystem == null || layeredSystem.isValidPackage(ident)) {
                     break;
                  }
                  rangeMin--;
               }

               imp.displayRangeError(rangeMin, rangeMax, true, "No type: " + imp.identifier + " for: ");
            }
         }
      }
      super.validate();
   }

   /**
    * For inactive types, once we start a model which has been modified, we need to find any models in downstream
    * layers that replace that model and start them.  Otherwise, we might end up resolving to a lower-level type
    * because the upper level model has not been started and has not set the replacedByType of the modified type.
    */
   /*
    * TODO: remove - the real bug that started this patch turned out to be that we were not starting the model
    * before returning it in the inactiveModelIndex.  Just in case this case crops up again...
   private void startReplacingTypes() {
      if (layer != null && !layer.activated) {
         String modelTypeName = getModelTypeName();
         if (modelTypeName != null && layeredSystem != null) {
            ArrayList<Layer> layers = layeredSystem.inactiveLayers;
            for (int i = layers.size() - 1; i > layer.layerPosition; i--) {
               layer.startReplacingTypes(modelTypeName);
            }
         }
      }
   }
   */

   private void addStaticImportInternal(ImportDeclaration imp) {
      String impStr = imp.identifier;
      if (imp.staticImport) {
         String className = CTypeUtil.getPackageName(impStr);
         String memberName = CTypeUtil.getClassName(impStr);
         Object importedType = findTypeDeclaration(className, false);
         if (importedType == null) {
            imp.displayTypeError("No static import class: " + className + " for: ");
         }
         else {
            Object[] methods;
            if (memberName.equals("*")) {
               boolean addedAny = false;
               methods = ModelUtil.getAllMethods(importedType, "static", true, false, false);
               if (methods != null) {
                  for (int i = 0; i < methods.length; i++) {
                     Object methObj = methods[i];
                     addStaticImportMethodType(ModelUtil.getMethodName(methObj), importedType);
                     addedAny = true;
                  }
               }
               Object[] properties = enableExtensions() ?
                       ModelUtil.getProperties(importedType, "static") :
                       ModelUtil.getFields(importedType, "static", true, false, false, false, true);
               if (properties != null) {
                  for (int i = 0; i < properties.length; i++) {
                     Object propObj = properties[i];
                     if (propObj == null)
                        continue;
                     String propName = ModelUtil.getPropertyName(propObj);
                     if (staticImportProperties == null)
                        staticImportProperties = new HashMap<String,Object>();
                     Object other = staticImportProperties.put(propName, propObj);
                     if (other != null && other != propObj) {
                        imp.displayError("Duplicate static imports with name: " + propName + " for: ");
                     }
                     addedAny = true;
                  }
               }
               // TODO: do we need to include inner types here?
               if (!addedAny)
                  imp.displayTypeError("No imported static fields, methods in type: " + impStr + " for: ");
            }
            else {
               boolean added = false;
               methods = ModelUtil.getMethods(importedType, memberName, "static");
               if (methods != null) {
                  addStaticImportMethodType(memberName, importedType);
                  added = true;
               }
               Object property = enableExtensions() ?
                       ModelUtil.definesMember(importedType, memberName, MemberType.PropertyGetSet, null, null, layeredSystem) :
                       ModelUtil.definesMember(importedType, memberName, MemberType.FieldEnumSet, null, null, layeredSystem);
               if (property == null && enableExtensions())
                  property = ModelUtil.definesMember(importedType, memberName, MemberType.PropertySetSet, null, null, layeredSystem);
               // You can also import static classes apparently
               if (property == null) {
                  property = ModelUtil.getInnerType(importedType, memberName, null);
               }
               // Not including the static check here - at least we need to allow enum's without the static prefix but maybe it's all classes?
               if (property != null /* && ModelUtil.hasModifier(property, "static") */) {
                  if (staticImportProperties == null)
                     staticImportProperties = new HashMap<String,Object>();
                  staticImportProperties.put(memberName, property);
                  added = true;
               }
               if (!added)
                  imp.displayTypeError("No imported static field, method, or inner type: " + memberName + " for: ");
            }
         }
      }
   }

   void addStaticImportMethodType(String methodName, Object newType) {
      if (staticImportMethods == null)
         staticImportMethods = new HashMap<String, List<Object>>();
      List<Object> staticImportTypes = staticImportMethods.get(methodName);
      if (staticImportTypes == null) {
         staticImportTypes = new ArrayList<Object>();
         staticImportMethods.put(methodName, staticImportTypes);
      }
      // Performance - check for duplicates here?
      //else {
      //}
      staticImportTypes.add(newType);
   }

   void initTypeInfo() {
      if (typeInfoInited)
         return;
      boolean canFullyInit = true;
      // For the layer model itself, we might be initializing the layer itself - i.e. resolving types in the layer model.  We can't init static
      // imports here yet because they will depend on source files in the layer itself which are not fully resolved yet.  But since we do not refer
      // to static imports in the layer definition file, we'll just init the model type again sometime when the layer is initialized to find the static
      // global imports
      if (imports != null && layer != null && layer.isInitialized()) {
         for (ImportDeclaration imp:imports) {
            if (imp.staticImport && !layer.isInitialized())
               canFullyInit = false;
            else
               addStaticImportInternal(imp);
         }
      }
      if (canFullyInit)
         typeInfoInited = true;

      // For inactive types, when starting the model we need to make sure we've started the most specific type in the model
      // so we don't rely on stale data.  We check by getting the current model type if it's in a layer after this one
      // we need to start it.
      /* This code becomes problematic in the compileDoc example where we are in initTypeInfo for test/js/unitConverter/uiRules/UnitConverter which get's the previous type uiOnly which then
         gets here and tries to start the jsui layer which refers to the model we have not yet initialized.   It's for the IDE only I think to eliminate the problem where we resolve a type
         which depends on a model that's not yet started for us to get the correct reference.   Maybe this should be handled in ExternalModelIndex by having a "doLater" to init the type
         and refresh any models which depend on the reference?
      if (layer != null && !layer.activated && !isLayerModel) {
         TypeDeclaration layerType = getLayerTypeDeclaration();
         if (layerType != null) {
            String modelTypeName = layerType.getFullTypeName();
            if (modelTypeName != null) {
               TypeDeclaration modelType = (TypeDeclaration) layeredSystem.getSrcTypeDeclaration(modelTypeName, null, prependLayerPackage, false, true, layer, isLayerModel);
               if (modelType != null && modelType.getLayer() != null && layerType.getLayer() != null) {
                  if (modelType.getLayer().layerPosition > layerType.getLayer().layerPosition) {
                     JavaModel modelTypeModel = modelType.getJavaModel();
                     if (modelTypeModel == this)
                        System.err.println("*** error in model type");
                     else {
                        if (!modelTypeModel.isStarted() && !typeInfoInited) {
                           ParseUtil.realInitAndStartComponent(modelTypeModel);
                        }
                     }
                  }
               }
            }
         }
      }
      */
   }

   public String getImportedName(String name) {
      Object importedObj = importsByName.get(name);
      if (importedObj != null) {
         if (importedObj instanceof WildcardImport)
            return ((WildcardImport) importedObj).typeName;
         return (String) importedObj;
      }

      // Next use the modified models imports - don't do this for layer models, in part because this gets called before the modified model's type info has been resolved.
      JavaModel modifiedModel = isLayerModel ? null : getUnresolvedModifiedModel();
      String imported;
      if (modifiedModel != null) {
         if (modifiedModel != this && modifiedModel.getUnresolvedModifiedModel() != this) {
            if (modifiedModel.replacedByModel != null)
               modifiedModel = modifiedModel.replacedByModel;

            imported = modifiedModel.getImportedName(name);
            if (imported != null)
               return imported;
         }
         else
            System.err.println("*** Recursive model detected in getImportedName");
         // else - once we've started transforming the types the type we are modifying may point back to us so just halt the lookup at this point.
      }

      // Now look for the system imports
      ImportDeclaration autoImported;
      String sysClassName = layeredSystem == null ? null : layeredSystem.getSystemClass(name);
      if (sysClassName != null) return sysClassName;

      Layer thisLayer = getLayer();

      if (layeredSystem != null && (autoImported = layeredSystem.getImportDecl(null, thisLayer, name)) != null) {
         if (!autoImported.staticImport) {
            // For styling snippets, there may be no src file and hence no type to be auto-imported
            String typeName = getModelTypeName();
            if (typeName != null) {
               Layer layer = getLayer();
               // We only have to keep track of the auto-import if we are going to do some code-generation of this type - ie. it's activated
               if (layer != null && layer.activated)
                  layeredSystem.addAutoImport(getLayer(), getModelTypeName(), autoImported);
            }
            return autoImported.identifier;
         }
      }
      if (isLayerModel && layeredSystem != null) {
         ImportDeclaration decl = layeredSystem.getLayerImport(name);
         if (decl != null && !decl.staticImport)
            return decl.identifier;
      }
      return null;
   }

   public boolean isWildcardImport(String name) {
      if (importsByName.get(name) instanceof WildcardImport)
         return true;
      if (layeredSystem != null && layeredSystem.getSystemClass(name) != null)
         return true;
      return false;
   }

   public boolean findMatchingGlobalNames(String prefix, Set<String> candidates, boolean annotTypes, int max) {
      return findMatchingGlobalNames(prefix, CTypeUtil.getPackageName(prefix), CTypeUtil.getClassName(prefix), candidates, annotTypes, max);
   }

   // TODO: should we use Pattern matching to make this more flexible?  Or an index to make it faster?
   public boolean findMatchingGlobalNames(String prefix, String prefixPkgName, String prefixBaseName, Set<String> candidates, boolean annotTypes, int max) {
      initTypeInfo();

      if (layeredSystem != null) {
         if (!layeredSystem.addGlobalImports(isLayerModel, prefixPkgName, prefixBaseName, candidates, max))
            return false;
      }
      for (String ent:importsByName.keySet()) {
         if (ent.startsWith(prefix)) {
            candidates.add(ent);
            if (candidates.size() >= max)
               return false;
         }
      }
      if (staticImportMethods != null) {
         for (String ent:staticImportMethods.keySet()) {
            if (ent.startsWith(prefix)) {
               List<Object> methTypes = staticImportMethods.get(ent);
               if (methTypes != null) {
                  for (Object methType:methTypes) {
                     String methTypeName = ModelUtil.getTypeName(methType);
                     if (prefixPkgName == null || methTypeName.equals(prefixPkgName)) {
                        candidates.add(ent + "()"); // TODO: look up the methods here and use getParameterString so we describe them properly in the IDE
                        if (candidates.size() >= max)
                           return false;
                        break;
                     }
                  }
               }
            }
         }
      }
      if (staticImportProperties != null) {
         for (String ent:staticImportProperties.keySet()) {
            Object propObj = staticImportProperties.get(ent);
            if (ent.startsWith(prefixBaseName) && (prefixPkgName == null || StringUtil.equalStrings(prefixPkgName, ModelUtil.getTypeName(ModelUtil.getEnclosingType(propObj))))) {
               candidates.add(ent);
               if (candidates.size() >= max)
                  return false;
            }
         }
      }
      JavaModel modModel = getModifiedModel();
      if (modModel != null) {
         if (!modModel.findMatchingGlobalNames(prefix, prefixPkgName, prefixBaseName, candidates, annotTypes, max))
            return false;
      }
      if (layeredSystem != null && layer != null) {
         if (!layeredSystem.findMatchingGlobalNames(null, layer, prefix, prefixPkgName, prefixBaseName, candidates, false, false, annotTypes, max))
            return false;
      }
      return true;
   }

   public void setComputedPackagePrefix(String prefix) {
      computedPackagePrefix = prefix;
   }

   public void copyImports(JavaModel model) {
      if (copyImportModels == null)
         copyImportModels = new ArrayList<JavaModel>();

      // We have to copy the imports of interfaces that contain ifields.  It's hard to track whether we've imported that
      // interface into a given model given the inner classes etc so just stop that problem here.
      if (copyImportModels.contains(model))
         return;
      copyImportModels.add(model);
      if (model.imports != null)
         for (int i = 0; i < model.imports.size(); i++)
            addImport(model.imports.get(i).identifier);
   }

   public void addImport(String name) {
      Object old = importsByName.put(CTypeUtil.getClassName(name), name);
      if (old != null) {
         if (old.equals(name))
            return;
      }
      List<ImportDeclaration> imp = imports;
      boolean setImports = false;
      if (imp == null) {
         imp = new SemanticNodeList<ImportDeclaration>();
         setImports = true;
      }
      // TODO: when adding at the end, we should ideally move the getComment str after the added import
      ImportDeclaration newImport = ImportDeclaration.create(name);
      if (imp.size() == 0)
         imp.add(newImport);
      else {
         int i;
         for (i = 0; i < imp.size(); i++) {
            int cmp = imp.get(i).identifier.compareTo(name);
            if (cmp == 0)
               return;
            if (cmp < 0)
               break;
         }
         imp.add(i, newImport);
      }

      // Imports can't be an empty list when we set it or the model is temporarily invalid
      if (setImports)
         setProperty("imports", imp);
   }

   public void addImport(ImportDeclaration imp) {
      // TODO: if we are replacing an import for a name we already have, we should remove that import
      // from the imports property.
      if (imp.staticImport)
         addStaticImportInternal(imp);
      else
         addNonStaticImportInternal(imp, false);

      List imps = imports;
      boolean setImports = false;
      if (imps == null) {
         imps = new SemanticNodeList<ImportDeclaration>();
         setImports = true;
      }
      imps.add(imp);

      // Imports can't be an empty list when we set it or the model is temporarily invalid
      if (setImports)
         setProperty("imports", imps);
   }

   public void changePackage(sc.lang.java.Package newPackage) {
      setProperty("packageDef", newPackage);
      initPackage = false;
   }

   /** Returns a type declaration defined in this model */
   public TypeDeclaration getTypeDeclaration(String typeName) {
      return definedTypesByName.get(typeName);
   }

   public void flushTypeCache() {
      typeIndex.clear();
   }

   public Object findTypeDeclaration(String typeName, boolean addExternalReference) {
      return findTypeDeclaration(typeName, addExternalReference, false);
   }

   public Object findTypeDeclaration(String typeName, boolean addExternalReference, boolean srcOnly) {
      Object td = typeIndex.get(typeName);
      if (td != null && (!(td instanceof ModifyDeclaration) || !((ModifyDeclaration) td).isLayerType) && (!srcOnly || !ModelUtil.isCompiledClass(td)))
         return td;

      String importedName = null;

      boolean skipSrc = false;
      if (customResolver != null) {
         Object res = customResolver.resolveType(getPackagePrefix(), typeName, false, null);
         if (res != null)
            return res;
         skipSrc = customResolver.useRuntimeResolution();
      }

      String pkgName;
      // Parsing the layer definition, need to skip over Java src in the standard packages: java.util and java.lang
      if (isLayerModel) {
         pkgName = CTypeUtil.getPackageName(typeName);
         if (pkgName == null || LayeredSystem.systemLayerModelPackages.contains(pkgName))
            skipSrc = true;
      }

      LayeredSystem sys = layeredSystem;

      // Need to check relative references in this package before we do the imports cause that's
      // how Java does it.
      if (sys != null && !skipSrc) {
         td = sys.getRelativeTypeDeclaration(typeName, getPackagePrefix(), null, prependLayerPackage, layer, isLayerModel, srcOnly);
      }

      importedName = getImportedName(typeName);
      if (importedName != null && td != null) {
         if (!ModelUtil.getTypeName(td).equals(importedName)) {
            // If you do import foo.* that does not override a relative type name.  But if you import an explicit type name it does.
            if (!isWildcardImport(typeName))
               td = null;
         }
      }

      boolean imported;
      if (td == null) {
         imported = true;

         if (isLayerModel && importedName != null) {
            pkgName = CTypeUtil.getPackageName(importedName);
            if (pkgName != null && LayeredSystem.systemLayerModelPackages.contains(pkgName))
               skipSrc = true;
         }
      }
      else {
         imported = false;
         importedName = null; // ???
      }

      if (importedName == null) {
         if (td == null) {
            Type primType = Type.getPrimitiveType(typeName);

            if (primType != null) {
               Class pc;
               if ((pc = primType.primitiveClass) != null)
                  return pc;
               else if (primType == Type.Void) {
                  if (typeName.equals("void"))
                     return Void.TYPE;
                  else
                     return Void.class;
               }
            }
         }
         importedName = typeName;
         imported = false;
      }

      if (layeredSystem != null) {
         // Look for a name relative to this path prefix
         if (importedName != typeName && !skipSrc) // intending to use != here to avoid the equals method
            td = layeredSystem.getRelativeTypeDeclaration(importedName, getPackagePrefix(), null, true, layer, isLayerModel, srcOnly);
         if (td == null) {
            if (!skipSrc) {
               // Look for an absolute name in both source and class form
               td = layeredSystem.getTypeForCompile(importedName, null, true, false, srcOnly, layer, isLayerModel);

               // Check for the inner class source def before looking for compiled definitions
               if (td == null) {
                  int lix;
                  String rootTypeName = importedName;
                  // This is trying to handle "a.b.C.innerType" references.  Walking from the end of the type
                  // name, keep peeling of name parts until we find a base type which matches.  Then go and
                  // see if it defines those inner types.
                  while ((lix = rootTypeName.lastIndexOf(".")) != -1) {
                     String nextRoot = rootTypeName.substring(0,lix);
                     String tail = importedName.substring(lix+1);
                     if (nextRoot.equals(typeName)) {
                        System.err.println("*** finTypeDeclaration - avoiding infinite loop - somehow came up with the same type name as the one we were passed - not good: " + typeName + " rootTypeName: " + rootTypeName);
                        break;
                     }
                     try {
                        Object baseTD = findTypeDeclaration(nextRoot, addExternalReference, srcOnly);

                        if (baseTD != null && (td = ModelUtil.getInnerType(baseTD, tail, null)) != null)
                           break;
                     }
                     catch (StackOverflowError exc) {
                        System.err.println("*** Error - findTypeDeclaration loop");
                     }
                     rootTypeName = nextRoot;
                  }
               }
            }
            if (td == null) {
               /* Handles .* imports in this package */
               if (globalTypes != null) {
                  for (String gpkg:globalTypes) {
                     String globalName = CTypeUtil.prefixPath(gpkg, typeName);
                     if (!skipSrc) {
                        if ((td = layeredSystem.getTypeForCompile(globalName, null, true, false, srcOnly, layer, isLayerModel)) != null) {
                           typeName = globalName;
                           break;
                        }
                     }
                     if ((td = getClass(globalName, false, layer, isLayerModel, false, isLayerModel)) != null) {
                        typeName = globalName;
                        break;
                     }
                  }
               }
               // TODO:  Very likely simplify this inner class stuff with the
               // code in LayeredSystem as it seems like there is redundancy.
               if (td == null && !srcOnly)
                  td = getClass(importedName, false, layer, isLayerModel, false, isLayerModel);
            }
            /* Only if we have not imported a class, try looking for a class in this package */
            if (td == null && importedName == typeName && !srcOnly) {
               String pref = getPackagePrefix();
               if (pref != null) {
                  String prefTypeName = pref + "." + typeName;
                  td = getClass(prefTypeName, false, layer, isLayerModel, false, isLayerModel);
               }
            }
         }
      }
      if (td != null) {
         if (!skipSrc) {
            addTypeToIndex(typeName, td);
         }
         
         // The system resolved absolute references of all kinds and so might have resolved one defined here.
         if (td instanceof TypeDeclaration) {
            TypeDeclaration typeDecl = (TypeDeclaration) td;

            // Don't resolve layer names through the normal type system
            if (typeDecl.isLayerType)
               return null;

            if (definedTypesByName.get(typeDecl.typeName) != null)
               return td;
         }
      }
      else if (imported) {
         displayError("Imported type: " + importedName + " does not exist for ");
      }

      // Don't add external references during the transform process - we'll be pushing dependencies down into base
      // models which causes problems later on!
      if (addExternalReference && !processed) {
         // Even if we can't resolve this, it is an external reference.  It can be a Java class we don't
         // know about such as java.util.xxx
         addExternalReference(importedName);
      }
      return td;
   }

   public Object getClass(String className, boolean useImports, boolean compiledOnly) {
      return getClass(className, useImports, null, false, false, compiledOnly);
   }

   public Object getClass(String className, boolean useImports, Layer refLayer, boolean layerResolve, boolean forceClass, boolean compiledOnly) {
      if (layeredSystem != null) {
         Object cl = layeredSystem.getClassWithPathName(className, refLayer, layerResolve, false, forceClass, compiledOnly);
         if (cl == null && useImports) {
            String impName = getImportedName(className);
            if (impName != null)
               return layeredSystem.getClassWithPathName(impName, refLayer, layerResolve, false, forceClass, compiledOnly);
         }
         return cl;
      }
      return null;
   }

   public String getPackagePrefix() {
      if (computedPackagePrefix != null)
         return computedPackagePrefix;
      if (packageDef != null)
         return packageDef.name;
      else
         return null;
   }

   public String getPackagePrefixDir() {
      String prefix = getPackagePrefix();
      if (prefix == null)
         return null;
      return prefix.replace(".", FileUtil.FILE_SEPARATOR);
   }

   public List<ImportDeclaration> getImports() {
      return imports;
   }

   public Map<String,TypeDeclaration> getDefinedTypes() {
      return definedTypesByName;
   }

   public void addTypeDeclaration(String typeName, TypeDeclaration type) {
      if (typeName == null)
         return;
      if (!isLayerModel && !typeName.equals("BuildInfo") && (types == null || types.size() == 0)) {
         SrcEntry srcFile = getSrcFile();
         if (srcFile != null) {
            String modelTypeName = FileUtil.removeExtension(srcFile.baseFileName);
            if (modelTypeName != null && !typeName.startsWith(modelTypeName)) {
               displayError("Type named: " + typeName + " not allowed in file: " + srcFile);
               return;
            }
         }
      }

      // This can happen in particular when a type has been replaced, stopped and is getting restarted because it's still in use.
      // It would be easy to avoid restarting the object, but this will help us catch places where we used these replaced objects.
      // Doing a resolve before using it will fix the problem.
      if (type.replaced) {
         System.err.println("*** Error - re-adding replaced type");
         return;
      }
      definedTypesByName.put(typeName, type);
      // Not using the typeIndex for layer models or sync models which use a custom resolver.  Maybe we just need to check
      // the custom resolver before the typeIndex, but with this, we register a Layer type which ends up extending itself.
      if (!isLayerModel && customResolver == null) {
         // Also register this for the package prefix
         if (getPackagePrefix() != null) {
            String fullTypeName = type.getFullTypeName();
            if (fullTypeName != null) {
               addTypeToIndex(fullTypeName, type);
            }
         }
         // TODO: should we be adding this to the types member?  Not sure they always belong in the language model.
         addTypeToIndex(typeName, type);
      }
   }

   public Set<String> getExternalReferences() {
      return externalReferences;
   }

   public Object definesType(String typeName, TypeContext ctx) {
      // In the custom resolver case, if we have something like object log4j extends log4j we need to resolve log4j to exclude
      // the current type.  If there's more than one component in the name this is not a problem but for serialization we should
      // allow the base type name to be the same as the id.  We also could work around this in the serialization - by picking a new
      // obj name if the names are the same?
      if (customResolver != null) {
         Object res = customResolver.resolveType(getPackagePrefix(), typeName, false, null);
         if (res != null)
            return res;
      }
      // This is a "get" not a "find" because we are only looking for types defined in this model.
      Object res = getTypeDeclaration(typeName);
      if (res != null)
         return res;

      // Yikes - this returns an instance when we are expecting a type!
      // TODO: can we fold this into a definesMember for cmdObject and just configure it's 'getInstance' method to return the interpreter?
      if (commandInterpreter != null && typeName.equals("cmd"))
         return commandInterpreter;

      if (!isLayerModel)
         initTypeInfo();

      // It may be a statically imported type
      if (staticImportProperties != null) {
         res = staticImportProperties.get(typeName);
         if (res != null && (res instanceof ITypeDeclaration || res instanceof Class))
            return res;
      }

      return null;
   }

   public Object findType(String name, Object refType, TypeContext ctx) {
      return definesType(name, ctx);
   }

   public void addDependentFiles(List<SrcEntry> ent) {
      dependentFiles.addAll(ent);
   }

   public List<SrcEntry> getDependentFiles() {
      assert started;

      if ((externalReferences == null || externalReferences.size() == 0) && dependentFiles.size() == 0)
         return null;

      Set<SrcEntry> resSet = new LinkedHashSet<SrcEntry>(dependentFiles);

      for (String ref:externalReferences) {
         List<SrcEntry> fres = layeredSystem.getFilesForType(ref);
         if (fres != null)
            resSet.addAll(fres);
         else {
            String pkgPrefix = getPackagePrefix();
            if (pkgPrefix != null) {
               fres = layeredSystem.getFilesForType(pkgPrefix + "." + ref);
               if (fres != null)
                  resSet.addAll(fres);
            }
         }
      }
      return new ArrayList<SrcEntry>(resSet);
   }

   public void addSrcFile(SrcEntry ent) {
      if (srcFiles == null)
         srcFiles = new ArrayList(1);

      srcFiles.add(ent);
   }

   /** Returns the type name plus the suffix of the generated file for uniqueness */
   public String getProcessedFileId() {
      SrcEntry file = getSrcFile();
      if (file == null)
         return null;
      return file.getTypeName() + ".java";
   }

   public String getPostBuildFileId() {
      return getProcessedFileId();
   }

   /** Called for templates after the build has been completed. */
   public void postBuild(String buildDir) {
   }

   public void removeSrcFiles() {
      srcFiles = null;
   }

   public List<SrcEntry> getSrcFiles() {
      return srcFiles;
   }

   public List<SrcEntry> cloneSrcFiles() {
      if (srcFiles == null)
         return null;
      ArrayList<SrcEntry> res = new ArrayList<SrcEntry>();
      for (SrcEntry src:srcFiles) {
         //res.add(new SrcEntry(src.layer, src.absFileName, src.relFileName, src.prependPackage));
         res.add(src.clone());
      }
      return res;
   }

   public void setSrcFile(SrcEntry srcFile) {
      if (srcFiles != null)
         srcFiles = null;
      addSrcFile(srcFile);
   }

   /** Returns the main source file for this model */
   @Constant
   public SrcEntry getSrcFile() {
      if (srcFiles == null) return null;
      return srcFiles.get(0);
   }

   public void setRelDirPath(String relDirPath) {
      this.relDirPath = relDirPath;
      initPackage = false;
      initPackageAndImports();
   }

   /**
    * Returns the directory containing this file in a path format (i.e. "a.b").
    */
   public String getRelDirPath() {
      if (srcFiles == null)
         return relDirPath;
      String s = srcFiles.get(0).getRelDir();
      if (s != null) return s.replace(FileUtil.FILE_SEPARATOR, ".");
      return null;
   }

   public String getPathInfo() {
      String pathInfo = getSrcFile().relFileName; // TODO - should this use the pattern in the URL annotation above?  What if it has parameters?
      if (resultSuffix != null)
         pathInfo = FileUtil.replaceExtension(pathInfo, resultSuffix);
      return pathInfo;
   }

   public TypeDeclaration getUnresolvedModelTypeDeclaration() {
      if (types == null || types.size() == 0)
         return null;
      return types.get(0);
   }

   @Constant
   public TypeDeclaration getLayerTypeDeclaration() {
      if (types == null || types.size() == 0)
         return null;
      return (TypeDeclaration) types.get(0).resolve(false);
   }

   @Constant
   public ClientTypeDeclaration getClientLayerTypeDeclaration() {
      if (types == null || types.size() == 0)
         return null;
      TypeDeclaration td = (TypeDeclaration) types.get(0).resolve(false);
      return td.getClientTypeDeclaration();
   }

   /** Dummy for the sync process */
   public void setLayerTypeDeclaration(Object td) {
      throw new UnsupportedOperationException();
   }

   @Constant
   public TypeDeclaration getModelTypeDeclaration() {
      if (types == null || types.size() == 0)
         return null;
      return (TypeDeclaration) types.get(0).resolve(true);
   }

   public Object getModelType() {
      return getModelTypeDeclaration();
   }

   public TypeDeclaration getImplicitTypeDeclaration() {
      return null;
   }

   public String getModelTypeName() {
      if (srcFiles == null)
         return null;

      return srcFiles.get(0).getTypeName();
   }

   public TypeDeclaration getPreviousDeclaration(String fullClassName) {
      if (layeredSystem == null)
         return null;
      Layer layer = getLayer();
      return (TypeDeclaration) layeredSystem.getSrcTypeDeclaration(fullClassName, layer, prependLayerPackage, false, true, layer, isLayerModel);
   }

   private final static List<SrcEntry> emptySrcEntriesList = Collections.emptyList();

   public void initReverseDeps() {
      if (reverseDepsInited)
         return;

      reverseDepsInited = true;

      // No need to process the reverseDeps if we are building all files
      TypeDeclaration modelType = getModelTypeDeclaration();
      if (modelType == null) // Template models in annotation layers do not have types
         return;

      // Make sure we tried to read in the reverseDeps
      if (reverseDeps == null) {
         readReverseDeps(getBuildLayer());
      }

      if (reverseDeps != null && getLayeredSystem().staleModelDependencies(modelType.getFullTypeName())) {
         for (Iterator<Map.Entry<String,ReverseDependencies.PropertyDep[]>> it = reverseDeps.bindableDeps.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String,ReverseDependencies.PropertyDep[]> ent = it.next();
            String propName = ent.getKey();

            String typeName = CTypeUtil.getPackageName(propName);
            propName = CTypeUtil.getClassName(propName);
            ReverseDependencies.PropertyDep[] pdeps = ent.getValue();
            TypeDeclaration type;
            if (typeName == null)
               type = modelType;
            else
               type = (TypeDeclaration) ModelUtil.getInnerType(modelType, typeName, null);
            if (type == null) {
               // This happens when someone removes a type from a class file, particularly when the types were defined in the same file
               //System.err.println("*** Missing inner type referenced by dependencies: " + origPropName + " for type: " + modelType);
               it.remove();
            }
            else {
               boolean needsBindable = ReverseDependencies.needsBindable(pdeps);
               // The property could have been removed since we save the reverse deps.  In that case, just remove the dependency
               if (type.definesMember(propName, JavaSemanticNode.MemberType.PropertyAnySet, null, null) != null)
                  ModelUtil.makeBindable(type, propName, needsBindable);
               else
                  it.remove();
            }
         }
         // Mark any dynamic methods found in the cached reverse dependencies so we properly generate this stuff on
         // incremental compiles.
         if (reverseDeps.dynMethods != null) {
            for (Iterator<Map.Entry<MethodKey,int[]>> it = reverseDeps.dynMethods.entrySet().iterator(); it.hasNext(); ) {
               Map.Entry<MethodKey,int[]> ent = it.next();
               MethodKey key = ent.getKey();
               String typeName = CTypeUtil.getPackageName(key.methodName);
               TypeDeclaration type;
               if (typeName == null)
                  type = modelType;
               else
                  type = (TypeDeclaration) ModelUtil.getInnerType(modelType, typeName, null);
               if (type == null) {
                  // This happens when someone removes a type from a class file, particularly when the types were defined in the same file
                  //System.err.println("*** Missing inner type referenced by dependencies: " + origPropName + " for type: " + modelType);
                  it.remove();
               }
               else {
                  Object member = type.getMethodFromSignature(key.methodName, key.paramSig, true);
                  if (member != null)
                     addDynMethod(type, key.methodName, key.paramSig, null);
               }
            }
         }
      }
   }

   public void process() {
      if (processed)
         return;

      initReverseDeps();

      super.process();
   }

   public boolean isAnnotationModel() {
      return layer != null && layer.annotationLayer;
   }

   public boolean changedSinceLayer(Layer initLayer, Layer buildLayer) {
      TypeDeclaration modelType = getModelTypeDeclaration();
      if (modelType == null)
         return true;

      if (modelType.changedSinceLayer(initLayer, buildLayer, false, null, null, false))
         return true;
      return false;
   }

   public boolean transformModel() {
      boolean didTransform = false;
      LayeredSystem sys = getLayeredSystem();

      //String modelTypeName = getModelTypeName();

      PerfMon.start("transform");

      boolean topLevelTransform = false;
      if (!sys.allTypesProcessed) {
         sys.allTypesProcessed = true;
         topLevelTransform = true;
      }

      try {
         if (sys.options.clonedTransform) {
            // This returns true for types that either need a transform call made here in Java or do a transform of the model for JS for enumerated types.
            if (needsTransform()) {
               restoreParseNode();
               // TODO: during rebuild, are there any cases where we need to transform, even if our model did not change?
               /*
               if (transformedModel != null && transformedModel.getTransformed())
                  return true;
               if (transformedModel == null)
               */
               // Need to reset the transformed model each time we transform.  If we changed this model, it would have been
               // reloaded, but the transformed model may be affected by other layers, unless it is final.
               // Sometimes we clone the transformed model without transforming.  In that case, we can just use that model.
               if (transformedModel == null || transformedInLayer == null ||
                      (transformedModel.getTransformed() && transformedInLayer != layeredSystem.currentBuildLayer) && changedSinceLayer(transformedInLayer, layeredSystem.currentBuildLayer))
                  cloneTransformedModel();

               // Already transformed
               if (transformedModel.getTransformed()) {
                  PerfMon.end("transform");
                  return true;
               }

               prepareModelTypeForTransform(true, transformedModel.getModelTypeDeclaration());

               if (sys.options.verbose)
                  System.out.println("Transforming: " + getSrcFile() + " runtime: " + layeredSystem.getRuntimeName());

               didTransform = transformedModel.transform(RuntimeType.JAVA);
               if (!didTransform && sys.options.sysDetails)
                  System.out.println("   (no code changes)");
            }
            else {
               didTransform = false;
               cachedNeedsTransform = false;
               if (transformedInLayer == null && sys.options.verbose)
                  System.out.println("Plain Java: " + getSrcFile() + " runtime: " + layeredSystem.getRuntimeName());

               // Just setting this so that we do not print the Plain Java message over and over again
               transformedInLayer = layeredSystem.currentBuildLayer;

               // TODO: DEBUG: remove - this will mess up the flushTypeCache - by marking all these objects as transformed, even if they do not change.
               //boolean testTransformed = transform(RuntimeType.JAVA);
               //if (testTransformed != didTransform) {
               //   System.err.println("*** needsTransform bug - did not predict that the model was transformed!");
               //}
               // TODO: end remove
            }
         }
         else {
            prepareModelTypeForTransform(true, getModelTypeDeclaration());

            didTransform = transform(RuntimeType.JAVA);
         }

         PerfMon.end("transform");
         return didTransform;
      }
      finally {
         if (topLevelTransform)
            sys.allTypesProcessed = false;
      }
   }

   public boolean isTheTransformedModel() {
      return nonTransformedModel != null;
   }

   /** Returns the transformed model for this model. */
   public JavaModel getTransformedModel() {
      if (nonTransformedModel != null || getTransformed()) // If we asking for the transformed model from the transformed model - just return this
         return this;
      if (transformedModel != null)
         return transformedModel;

      if (!isProcessed()) { // TODO: this code needs work!  We need to set allTypesProcess = true before we start and false afterwards.  for now we are trying to avoid the need for this code path by building everything up front the first time.
         ParseUtil.initAndStartComponent(this);
         ParseUtil.processComponent(this);
      }

      if (!needsTransform())
         return this;

      boolean didTransform = transformModel();
      if (didTransform) {
         if (transformedModel != null)
            transformedModel.validateParseNode(true);
         return transformedModel;
      }
      return this;
   }

   /** Hook to reinitiualize any state after one of your base types gets modified after this type has been processed. */
   public boolean dependenciesChanged() {
      if (!isInitialized()) {
         ParseUtil.initComponent(this);
         return true;
      }
      /*
      TypeDeclaration modelType = getUnresolvedModelTypeDeclaration();
      if (modelType != null && initializedInLayer != null) {
         if (modelType.changedSinceLayer(initializedInLayer, false, null, null))
            return true;
      }
      */
      /*
      if (isStarted() && layeredSystem != null && (layeredSystem.lastChangedModelTime != lastStartedTime || needsRestart)) {
         reinitialize();
         return true;
      }
      */
      return false;
   }

   public void reinitialize() {
      if (started) {
         if (layeredSystem != null && layeredSystem.options.verbose && getSrcFile() != null)
            layeredSystem.verbose("Reinitializing: " + getSrcFile());

         stop();
         initialized = false;
         started = false;
         validated = false;
         processed = false;
         hasErrors = false;
         initPackage = false;
         clearTransformed();

         ParseUtil.initComponent(this);
      }
   }

   /** Returns the list of statements generated from this statement that are used when setting breakpoints on this statement. */
   public List<ISrcStatement> getBreakpointStatements(ISrcStatement srcStatement) {
      ArrayList<ISrcStatement> res = new ArrayList<ISrcStatement>();
      TypeDeclaration modelType = getUnresolvedModelTypeDeclaration();
      modelType.addBreakpointNodes(res, srcStatement);
      if (res.size() == 0)
         return null;
      return res;
   }

   private class ExtraFile {
      String relFilePath, fileBody;
   }

   public void addExtraFile(String relFilePath, String fileBody) {
      if (extraFiles == null)
         extraFiles = new ArrayList<ExtraFile>(1);
      else {
         for (int i = 0; i < extraFiles.size(); i++) {
            ExtraFile ent = extraFiles.get(i);
            if (ent.relFilePath.equals(relFilePath)) {
               extraFiles.remove(i);
               break;
            }
         }
      }
      ExtraFile newEF = new ExtraFile();
      newEF.relFilePath = relFilePath;
      newEF.fileBody = fileBody;
      extraFiles.add(newEF);
   }

   /**
    * Returns the set of files generated by this model.  This includes the pass through or transformed Java file,
    * any dynamic stubs for the main type or inner types, and any extra files added by extra features defined on this
    * model (e.g. the GWT module file)
    */
   public List<SrcEntry> getProcessedFiles(Layer buildLayer, String buildSrcDir, boolean generate) {
      PerfMon.start("getProcessedFiles");
      try {
         List<SrcEntry> computedFiles = getComputedProcessedFiles(buildLayer, buildSrcDir, generate);
         if (extraFiles == null)
            return computedFiles;
         if (computedFiles == null)
            return null;

         // If any additional files were attached to this model, save them to the buildDir and buildLayer
         ArrayList<SrcEntry> result = new ArrayList<SrcEntry>(computedFiles);
         for (ExtraFile f:extraFiles) {
            String absPath = FileUtil.concat(buildSrcDir, f.relFilePath);
            byte[] hash = StringUtil.computeHash(f.fileBody);

            SrcIndexEntry srcIndex = buildLayer.getSrcFileIndex(f.relFilePath);

            // Avoid rewriting unchanged files
            if (srcIndex == null || !Arrays.equals(srcIndex.hash, hash)) {
               FileUtil.saveStringAsReadOnlyFile(absPath, f.fileBody, true);
               buildLayer.addSrcFileIndex(f.relFilePath, hash, null, absPath);
            }

            SrcEntry srcEnt = new SrcEntry(buildLayer, absPath, f.relFilePath);
            result.add(srcEnt);
         }
         return result;
      }
      finally {
         PerfMon.end("getProcessedFiles");
      }
   }

   public void postBuild(Layer buildLayer, String buildDir) {
      // If we modified a template, that guy will handle the postBuild (to generate say an html file)
      JavaModel modModel = getModifiedModel();
      modModel.postBuild(buildLayer, buildDir);
   }

   // If necessary, perform any transformations and return the source files to compile.
   // If nothing changed, just return the source files for this model.
   private List<SrcEntry> getComputedProcessedFiles(Layer buildLayer, String buildSrcDir, boolean generate) {
      // TODO: If we generate additional classes during the transformation, they need to get returned here
      assert getSrcFiles().size() == 1;

      // this happens for all nodes, dynamic or generated.  transformation is only done to compiled nodes.  Probably not needed now as we do process in a separate step
      if (!processed) {
         System.out.println("*** Error transforming unprocessed node");
         process();
      }

      SrcEntry src = getSrcFiles().get(0);

      // Don't try to transform if we have errors or we processed an element in an annotation layer.
      if (src.layer.annotationLayer || hasErrors()) {
         TypeDeclaration modelType = getModelTypeDeclaration();
         if (!(modelType instanceof ModifyDeclaration))
            return Collections.emptyList();
         ModifyDeclaration modType = (ModifyDeclaration) modelType;
         if (!(modType.getDerivedTypeDeclaration() instanceof TypeDeclaration)) {
            return Collections.emptyList();
         }
         // else - we are modifying a source type from an annotation layer so treat this normally
      }
      boolean didTransform;
      String transformedResult = null;
      List<SrcEntry> innerObjStubs = null;
      TypeDeclaration modelType = getModelTypeDeclaration();

      if (modelType != null && modelType.excluded) {
         LayeredSystem sys = getLayeredSystem();
         if (sys.options.verbose) {
            if (modelType.excludedStub == null)
               sys.verbose("Excluded: " + modelType.typeName + " in: " + sys.getProcessIdent());
            else
               sys.verbose("Excluded: " + modelType.typeName + " replaced by stub in: " + sys.getProcessIdent());
         }
         return Collections.emptyList();
      }

      if (modelType != null && (modelType.isDynamicNew())) {
         // Save this as a dynamic type in this layer so we know to not load it as a regular class in an incremental build
         buildLayer.markDynamicType(modelType.getFullTypeName());

         innerObjStubs = modelType.getInnerDynStubs(buildSrcDir, buildLayer, generate);
         // No code to generate when the type is dynamic unless we are extending a compiled class for the first
         // time.  We need some class to act as the barrier between weak/strong types and so generate a stub
         // in that case.
         if (!modelType.isDynamicStub(false)) {
            if (modelType.replacedByType == null && generate) { // only remove if we are the last layer and supposed to be saving models
               String procName = getProcessedFileName(buildSrcDir);
               LayerUtil.removeFileAndClasses(procName); // make sure a previous compiled version is not there
               LayerUtil.removeInheritFile(procName);
               if (!buildLayer.buildDir.equals(buildSrcDir)) {
                  String buildDirName = getProcessedFileName(buildLayer.buildClassesDir);
                  LayerUtil.removeFileAndClasses(buildDirName); // remove the classes from the buildDir if it's different
               }
            }
            return innerObjStubs == null ? emptySrcEntriesList : innerObjStubs;
         }
         didTransform = true;
         if (generate)
            transformedResult = modelType.generateDynamicStub(true);
      }
      else {
         if (generate) {
            didTransform = transformModel();
         }
         else
            didTransform = needsTransform();
      }

      transformedInLayer = getLayeredSystem().currentBuildLayer;

      if (!didTransform && !layer.copyPlainJavaFiles) {
         return Collections.singletonList(src);
      }
      else {
         if (transformedResult == null && generate)
            transformedResult = getTransformedResult();
         String newFile = getProcessedFileName(buildSrcDir);
         File newFileFile = new File(newFile);

         // The layered system processes hidden layer files backwards.  So generate will be true the for the
         // final layer's objects but an overriden component comes in afterwards... don't overwrite the new file
         // with the previous one.  We really don't need to transform this but I think it is moot because it will
         // have been transformed anyway.
         SrcEntry mainSrcEnt = new SrcEntry(src.layer, newFile, FileUtil.replaceExtension(FileUtil.concat(getPackagePrefixDir(), src.baseFileName), "java"));
         if (generate) {
            mainSrcEnt.hash = StringUtil.computeHash(transformedResult);
            SrcIndexEntry thisEntry = src.layer.getSrcFileIndex(mainSrcEnt.relFileName);
            boolean isChanged = thisEntry == null || !Arrays.equals(thisEntry.hash, mainSrcEnt.hash) ||
                    !newFileFile.canRead() || !Arrays.equals(thisEntry.hash, StringUtil.computeHash(FileUtil.getFileAsBytes(newFile)));
            SrcIndexEntry prevEntry = layeredSystem.options.useCommonBuildDir && layeredSystem.commonBuildLayer == buildLayer ? null : src.layer.getPrevSrcFileIndex(mainSrcEnt.relFileName);

            // If there's a previous entry which is the same, we'll skip saving altogether.
            if ((prevEntry == null || !Arrays.equals(prevEntry.hash, mainSrcEnt.hash))) {
               if (isChanged) {
                  FileUtil.saveStringAsReadOnlyFile(newFile, transformedResult, true);

                  if (layeredSystem.options.genDebugInfo)
                     updateFileLineIndex(transformedResult, buildSrcDir);
                  // TODO: else - remove the the file if it does not exist?
               }
               else {
                  File classFile = LayerUtil.getClassFile(buildLayer, mainSrcEnt);
                  // As long as the class file is up to date before, we'll make sure it stays up to date after.
                  // This handles the common case where the source file does change but the stub and class file do not change.
                  // Should not need a compile then but need to reflect that in the last modified times of the files themselves or
                  // else we need to fix how we decide to process files.
                  boolean touchClassFile = classFile.lastModified() >= newFileFile.lastModified();
                  FileUtil.touchFile(newFile);
                  if (touchClassFile)
                     FileUtil.touchFile(classFile.getPath());

                  /*
                  if (innerObjStubs != null) {
                     for (SrcEntry innerSrcEnt:innerObjStubs) {
                        classFile = LayerUtil.getClassFile(buildLayer, innerSrcEnt);
                        File innerSrcFile = new File(innerSrcEnt.absFileName);
                        touchClassFile = classFile.lastModified() >= innerSrcFile.lastModified();
                     }
                  }
                  */
               }
            }
         }
         if (innerObjStubs == null)
            return Collections.singletonList(mainSrcEnt);
         else {
            innerObjStubs.add(mainSrcEnt);
            return innerObjStubs;
         }
      }
   }

   public void cloneTransformedModel() {
      if (!isProcessed()) {
         ParseUtil.initAndStartComponent(this);
         ParseUtil.processComponent(this);
      }
      transformedModel = (JavaModel) this.deepCopy(CopyAll | CopyTransformed, null);
      transformedModel.nonTransformedModel = this;
      transformedInLayer = layeredSystem.currentBuildLayer;
      JavaModel modModel = getModifiedModel();
      if (modModel != null)
         modModel.cloneTransformedModel();
   }

   // Overridden in Template to clear the parse tree before we start the transform
   protected void prepareModelTypeForTransform(boolean generate, TypeDeclaration modelType) {
   }

   public String getProcessedFileName(String buildSrcDir) {
      SrcEntry srcFile = getSrcFile();
      String newFile = FileUtil.concat(buildSrcDir, FileUtil.concat(getPackagePrefixDir(), srcFile.baseFileName));
      // TODO: registry of suffixes for each runtime type?
      newFile = FileUtil.replaceExtension(newFile, "java");
      return newFile;
   }

   public String getClassFileName(String buildDir) {
      return FileUtil.replaceExtension(getProcessedFileName(buildDir), "class");
   }

   /**
    * Save the model source into its original location.  We get the layer's main directory and write the model into
    * the source file at that location
    */
   public void saveModel() {
      if (transformed) {
         System.err.println("*** Error: saveModel should not be used to save a transformed model");
         return;
      }

      validateSavedModel(false);

      SrcEntry src = getSrcFile();
      if (src == null || src.absFileName == null)
         System.err.println("*** No source file for model");
      else
         FileUtil.saveStringAsFile(src.absFileName, toLanguageString(), true);
   }

   public void saveModelTextToFile(String text) {
      SrcEntry src = getSrcFile();
      FileUtil.saveStringAsFile(src.absFileName, text, true);
   }

   /**
    * Reload this model from a saved model in a file.  Any changes found either will mark the compiled system as "stale"
    * or will update the running application.
    */
   public void updateModel(JavaModel newModel, ExecutionContext ctx, TypeUpdateMode updateMode, boolean updateInstances, UpdateInstanceInfo updateInfo) {
      if (!newModel.isInitialized()) {
         // We do not want to start the new type until we've replaced it in the type system.  That will happen
         ParseUtil.initComponent(newModel);
         // Do not want to validate this (and compute the sync properties), until we've updated the models in other runtimes
         // ParseUtil.processComponent(newModel);
      }
      if (updateMode == TypeUpdateMode.Replace) {
         layeredSystem.replaceModel(this, newModel);
      }

      // Use the unresolved type here... otherwise, when we update a model which is not the most specific type, we'll update the wrong type.
      TypeDeclaration oldType = getUnresolvedModelTypeDeclaration();
      TypeDeclaration newType = newModel.getUnresolvedModelTypeDeclaration();

      // Inherit the command interpreter from the old type
      if (commandInterpreter != null)
         newModel.commandInterpreter = commandInterpreter;

      if (newModel.layer != null && oldType != null && newType != null)
         oldType.updateType(newType, ctx, updateMode, updateInstances, updateInfo);

      if (updateMode == TypeUpdateMode.Remove)
         layeredSystem.removeModel(this, false); // pass in false here because types were removed in updateType

      // Doing this after removeModel since we do not deliver modelListeners to removed models and we do want to deliver this one
      if (updateMode == TypeUpdateMode.Remove || updateMode == TypeUpdateMode.Replace)
         removed = true;

      // If we are batching updates (updateInfo != null), we might be updating more than one model so don't start it here.
      if (!newModel.isStarted() && updateInfo == null) {
         ParseUtil.realInitAndStartComponent(newModel);
      }
   }

   public void completeUpdateModel(JavaModel newModel, boolean updateRuntime) {
      if (updateRuntime) {
         if (!newModel.isValidated()) {
            ParseUtil.validateComponent(newModel);
         }
         newModel.readReverseDeps(layeredSystem.buildLayer);
         // We only process activated components
         if (!newModel.isProcessed() && getLayer() != null && getLayer().activated) {
            ParseUtil.processComponent(newModel);
         }
      }

      newModel.setNeedsModelText(needsModelText);
      newModel.setNeedsGeneratedText(getNeedsGeneratedText());

      // If this instance is synchronized, replace it in the sync system with the newModel
      //SyncManager.replaceSyncInst(this, newModel);

      // Need to notify any listeners on the old model that it has changed.  The oldModel.replacedBy points to the newModel so they will have to "resolve" to get the right model in the refresh
      markChanged();
   }

   /*
    * TODO: remove this - was an initial pass before we did the sync update to record the old value.  Now we use a thread-local variable
   public void updatePreviousValues(SyncManager.SyncContext syncCtx) {
      if (types == null)
         return;

      for (TypeDeclaration modelType:types) {
         if (modelType instanceof ModifyDeclaration) {
            ModifyDeclaration modType = (ModifyDeclaration) modelType;


            String typeName = modType.getTypeName();
            Object inst = syncCtx.getObjectByName(typeName);

            if (inst == null) {
               typeName = modType.getFullTypeName();
               inst = ScopeDefinition.resolveName(typeName, true);
            }
            if (inst != null) {
               ExecutionContext eCtx = new ExecutionContext(this);

               eCtx.pushCurrentObject(inst);

               try {
                  modType.updatePreviousValues(inst, syncCtx, eCtx);
               }
               finally {
                  eCtx.popCurrentObject();
               }
            }
            else {
               System.err.println("*** Unable to resolve instance with name: " + typeName);
            }
         }
      }
   }
   */

   public void updateRuntimeModel(SyncManager.SyncContext syncCtx, BindingContext bindCtx) {
      if (types == null)
         return;

      SyncExecutionContext ctx = new SyncExecutionContext(this, syncCtx, bindCtx);

      // Currently the grammar lets you put more than one type in a model so it's only the package that separates the model
      // definitions.  Seems like this is not a problem?
      for (TypeDeclaration modelType:types) {
         modelType.updateRuntimeType(ctx, syncCtx, null);
      }
   }

   /** Adds a new layer onto an existing model. */
   public void updateLayeredModel(ExecutionContext ctx, boolean updateInstances, UpdateInstanceInfo updateInfo) {
      if (!isStarted())
         ParseUtil.initAndStartComponent(this);
      // Need to get the per-layer model type here as we need to merge the models one at a time when adding multiple layers at the same time.
      BodyTypeDeclaration modelType = getLayerTypeDeclaration();
      BodyTypeDeclaration prevType = modelType.getModifiedType();
      if (prevType != null)
         prevType.updateType(modelType, ctx, TypeUpdateMode.Add, updateInstances, updateInfo);
      else
         System.err.println("*** No modified type for updateLayeredModel: " + modelType);
   }


   /** Returns true if this model modifies a type, instead of defining a new type */
   public boolean modifiesModel() {
      return getModelTypeDeclaration() instanceof ModifyDeclaration;
   }

   public void refreshBaseLayers(ExecutionContext ctx) {
      TypeDeclaration modelType = getModelTypeDeclaration();
      if (modelType instanceof ModifyDeclaration) {
         ModifyDeclaration mt = (ModifyDeclaration) modelType;

         SrcEntry ent = mt.getJavaModel().getSrcFile();
         if (ent != null && ent.canRead()) {
            layeredSystem.refresh(ent, ctx);
         }

         BodyTypeDeclaration td = mt.getModifiedType();
         if (td != null)
            td.getJavaModel().refreshBaseLayers(ctx);
      }
   }

   public String getTransformedResult() {
      if (transformedModel != null)
         return transformedModel.getTransformedResult();

      if (parseNode == null) {
         // We may not have restored the parse node here - no need to generate it just to return the string
         if (!needsTransform()) {
            return FileUtil.getFileAsString(getSrcFile().absFileName);
         }
         Parselet compUnit = JavaLanguage.INSTANCE.compilationUnit;
         PerfMon.start("newModelGenerate");
         Object res = compUnit.generate(JavaLanguage.INSTANCE.newGenerateContext(compUnit, this), this);
         PerfMon.end("newModelGenerate");
         if (res instanceof IParseNode)
            parseNode = (IParseNode) res;
         else
            System.err.println("*** Model generation failed: " + res);
      }

      validateSavedModel(true);

      return getParseNode().formatString(null, null, -1, true).toString();
   }

   public void validateSavedModel(boolean finalGen) {
      // If we were tracking changes all along, the parse node should be valid at this point.  If not, we need to make a pass and generate
      // any invalid nodes
      if (parseNode != null && !parseNode.getParselet().language.trackChanges) {
         // Do this as a "final generation" which means we just accept any string properties instead of regenerating them.  The downside
         // of not regenerating all string properties is that we lose validation that they conform to the model and we cannot incrementally
         // update them after this because the child parse nodes no longer include the parselets for those subnodes.
         validateParseNode(finalGen);
      }
   }

   public String toLanguageString() {
      if (parseNode == null) {
         Object genRes = JavaLanguage.INSTANCE.generate(this, false);
         if (genRes instanceof IParseNode)
            parseNode = (IParseNode) genRes;
         else
            System.out.println("Generation error for model: " + toModelString());
      }
      return super.toLanguageString();
   }

   public Object resolveName(String name, boolean create, boolean returnTypes) {
      return resolveName(name, create, create, returnTypes);
   }

   /**
    * This is called when evaluating an expression and we want to evaluate an identifier expression.  It first
    * looks for a local type in this file, then will go resolve a global one from the system.
    */
   public Object resolveName(String name, boolean create, boolean addExternalReference, boolean returnTypes) {
      if (!initialized)
         return null;
      Object type = findType(name);
      if (type != null) {
         if (type instanceof TypeDeclaration) {
            TypeDeclaration td = (TypeDeclaration) type;
            if (!td.isLayerType) {
               if (ModelUtil.isObjectType(td)) {
                  Object sysObj = layeredSystem != null ? layeredSystem.resolveName(name, create, getLayer(), isLayerModel, returnTypes) : null;
                  if (sysObj != null)
                     return sysObj;
                  else
                     System.err.println("*** Object type has no globally registered instance");
               }
               if (returnTypes) {
                  Class cl = td.getCompiledClass();
                  if (cl != null)
                     return cl;
               }
            }
         }
      }
      Object sysObj = layeredSystem != null ? layeredSystem.resolveName(name, create, getLayer(), isLayerModel, returnTypes) : null;
      if (sysObj != null)
         return sysObj;

      // Could be a little cleaner - only allocate the full name if at least the package matches.
      if (layer != null) {
         String prefix = getPackagePrefix();
         if (prefix == null) {
            if (name.equals(layer.getLayerUniqueName()))
               return layer;
         }
         else {
            if (name.startsWith(getPackagePrefix()) && name.equals(CTypeUtil.prefixPath(getPackagePrefix(), layer.getLayerUniqueName())))
               return layer;
         }
      }
      if (!returnTypes && commandInterpreter == null)
         return null;
      type = findTypeDeclaration(name, addExternalReference);

      // This implements the ability to refer to a non-static object in the command interpreter - either we select
      // a singleton or the wizard may have chosen a default instance for this type.
      if (commandInterpreter != null && type != null) {
         sysObj = commandInterpreter.getDefaultCurrentObj(type);
         if (sysObj != null)
            return sysObj;
      }
      return type;
   }

   public void addGlobalObject(String name, Object obj) {
      layeredSystem.addGlobalObject(name, obj);
   }

   public boolean transformPackage() {
      if (computedPackagePrefix != null) {
         if (packageDef == null) {
            Package pkg = new Package();
            // Needs to be set before we set this property so the parse node gets generated properly
            pkg.name = computedPackagePrefix;
            setProperty("packageDef", pkg);
         }
         else
            packageDef.setProperty("name", computedPackagePrefix);
         return true;
      }
      return false;
   }

   // Inject a comment that we are a source file in merging into this file.  Also add our base type.
   public void addModelMergingComment(ParentParseNode baseCommentNode) {
      // Not necessary when processing a model stream and breaks cause there's no src file.
      if (!mergeDeclaration)
         return;

      int ix = baseCommentNode.children == null || baseCommentNode.children.size() == 0 ? 0 : 1;
      baseCommentNode.addGeneratedNodeAt(ix, "//   merging: " + getSrcFile() + FileUtil.LINE_SEPARATOR);
      ParentParseNode ppn = (ParentParseNode) parseNode;
      if (ppn != null) {
         Object child = ppn.children.get(0);
         if (child instanceof ParentParseNode) {
            ParentParseNode myCommentNode = ppn.children != null ? (ParentParseNode) child : null;
            if (myCommentNode != null && myCommentNode.length() > 0) {
               int insIx = Math.min(2, baseCommentNode.children.size());
               baseCommentNode.addGeneratedNodeAt(insIx, myCommentNode.toString());
            }
         }
         else if (child != null)
            System.out.println("*** Unable to add model merging comment - unhandled case");
      }
      JavaModel modifiedModel = getModifiedModel();
      if (modifiedModel != null) {
         modifiedModel.addModelMergingComment(baseCommentNode);
      }
   }

   public void setResultSuffix(String suffix) {
      if (suffix != null)
         resultSuffix = suffix;
      JavaModel modModel = getModifiedModel();
      if (modModel != null) {
         if (modModel.transformedModel != null)
            modModel = modModel.transformedModel;
         if (modModel == this) {
            System.err.println("*** Modified model is same as this model?");
            return;
         }
         modModel.setResultSuffix(resultSuffix);
      }
   }

   public String getResultSuffix() {
      if (resultSuffix == null) {
         JavaModel modModel = getModifiedModel();
         if (modModel != null) {
            if (modModel.transformedModel != null)
               modModel = modModel.transformedModel;
            resultSuffix = modModel.getResultSuffix();
         }
      }
      return resultSuffix;
   }

   public JavaModel getModifiedModel() {
      if (modifiedModel != null) {
         JavaModel newModel = modifiedModel.resolveModel();
         if (newModel == this) {
            System.err.println("*** recursive model definition");
            return null;
         }
         return modifiedModel = newModel;
      }

      if (types == null || types.size() == 0)
         return null;

      BodyTypeDeclaration modifiedModelType = types.get(0).getModifiedType();
      if (modifiedModelType == null)
         return null;
      JavaModel toRet = modifiedModelType.getJavaModel();
      return toRet;
   }

   public JavaModel getUnresolvedModifiedModel() {
      if (modifiedModel != null) {
         return modifiedModel;
      }

      if (types == null || types.size() == 0)
         return null;

      BodyTypeDeclaration modifiedModelType = types.get(0).getUnresolvedModifiedType();
      if (modifiedModelType == null)
         return null;
      JavaModel toRet = modifiedModelType.getJavaModel();
      return toRet;
   }

   public boolean isModifyModel() {
      return types != null && types.size() > 0 && types.get(0) instanceof ModifyDeclaration;
   }

   /** The autoImports get registered by all layered models with the same type.  Since we can only resolve imports in the layered structure, during transform we need to aggregate them all in the model so it can find any types that get merged into it */
   public void syncAutoImports() {
      List<ImportDeclaration> autoImports;
      if ((autoImports = getAutoImports()) != null) {
         for (ImportDeclaration ai:autoImports)
            addNonStaticImportInternal(ai, false);
      }
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      // During the transformation process, we create new elements and attach them to the tree.
      // since the tree is started, these components are also started which means they do type
      // resolution.  But we can't ensure we do the transformations so all types are bound as that
      // would require multiple passes.  Ultimately we don't need type checking on the post-transformed
      // result if we do it on the pre-transformed tree so this is ok.
      disableTypeErrors = true;
      inTransform = true;

      boolean any = transformPackage();

      List<ImportDeclaration> autoImports;

      if (parseNode != null) {
         ParentParseNode ppnode = (ParentParseNode) parseNode;
         IParseNode myCommentNode = ppnode.children != null && ppnode.children.size() > 0 ? (IParseNode) ppnode.children.get(0) : null;
         if (myCommentNode == null || myCommentNode instanceof SpacingParseNode) {
            myCommentNode = ((NestedParselet) ppnode.getParselet()).parselets.get(0).newGeneratedParseNode(null);
            ppnode.children.set(0, myCommentNode);
         }
         ParentParseNode myCommentParent = (ParentParseNode) myCommentNode;
         // For now at least suppress this message for "pass through" type files.  Otherwise, it messes up line numbers
         // in stack traces and stuff unnecessarily
         JavaModel modifiedModel = getModifiedModel();
         if (any) {
            StringBuilder mergeComment = new StringBuilder();
            mergeComment.append("// Generated by StrataCode from " + getSrcFile() + FileUtil.LINE_SEPARATOR);
            if (modifiedModel != null) {
               String modComments = modifiedModel.getComment();
               mergeComment.append(modComments);
            }
            myCommentParent.addGeneratedNodeAt(0, mergeComment.toString());
         }
         if (modifiedModel != null) {
            modifiedModel.addModelMergingComment(myCommentParent);
         }
      }

      // Need to process our body before we process the imports.  Copying over interface definitions may add additional auto-imports
      if (super.transform(runtime))
         any = true;

      // For any layer-global imports that were referenced in this layer, add them here
      if ((autoImports = getAutoImports()) != null) {
         any = true;
         if (imports == null) {
            // Define first, then set so it can be generated
            SemanticNodeList<ImportDeclaration> newImports = new SemanticNodeList<ImportDeclaration>();
            for (int i = 0; i < autoImports.size(); i++) {
               ImportDeclaration aimp = autoImports.get(i);
               Object impType = getLayeredSystem().getTypeDeclaration(aimp.identifier);
               // Only process this import if it corresponds to a real generated class - i.e. dynamic types should not be here
               // unless they have a dynamic stub.
               if (impType instanceof TypeDeclaration) {
                  TypeDeclaration td = (TypeDeclaration) impType;
                  if (td.isDynamicType() && !td.isDynamicStub(false))
                     continue;
               }
               newImports.add((ImportDeclaration) aimp.deepCopy(CopyNormal, null));
            }
            setProperty("imports", newImports);
         }
         else {
            for (ImportDeclaration is:autoImports)
               imports.add((ImportDeclaration) is.deepCopy(CopyNormal, null));
         }
      }

      // Make sure we always generate any file which is not a .java file originally
      if (!any && srcFiles != null) {
         for (SrcEntry ent:srcFiles)
            if (!FileUtil.getExtension(ent.baseFileName).equals("java"))
               any = true;
      }

      inTransform = false;
      return any;
   }

   private List<ImportDeclaration> getAutoImports() {
      return layeredSystem == null ? null : layeredSystem.getAutoImports(getModelTypeName());
   }

   public boolean needsTransform() {
      // This is called before you do a transform on the model.  If we're already transformed we won't call it.  If
      // we already called needsTransform and it returned false, we cache that state so repeated calls here do not traverse the entire object model.
      // We don't have to cache the true state since we immediately get transformed in that case so we're saving an "is cached" bit here.
      if (!cachedNeedsTransform)
         return false;
      return computedPackagePrefix != null || super.needsTransform() || enableExtensions() || getAutoImports() != null;
   }

   /** Overridden in SCModel to true */
   public boolean enableExtensions() {
      return false;
   }

   public boolean hasErrors() {
      return hasErrors;
   }

   public void setHasErrors(boolean val) {
      hasErrors = val;
   }

   public Object findMember(String name, EnumSet<MemberType> mtype, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      Object v;

      if ((v = definesMember(name, mtype, refType, ctx, skipIfaces, nonTransformedModel != null)) != null)
         return v;

      return super.findMember(name, mtype, fromChild, refType, ctx, skipIfaces);
   }

   public Object definesMember(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      Object type = null;

      initTypeInfo();

      if (staticImportProperties != null) {
         type = staticImportProperties.get(name);
      }
      Layer modelLayer = getLayer();
      if (type == null && modelLayer != null) {

         Layer toFilterLayer = isLayerModel ? null : modelLayer;

         // This returns the type object containing the member.
         if ((type = layeredSystem.getImportedStaticType(name, toFilterLayer, modelLayer)) != null) {
            layeredSystem.addAutoImport(getLayer(), getModelTypeName(), ImportDeclaration.createStatic(CTypeUtil.prefixPath(ModelUtil.getTypeName(type), name)));

            // Now convert it to the member itself.
            type = ModelUtil.definesMember(type, name, mtype, refType, ctx, skipIfaces, isTransformed, layeredSystem);

            assert type != null;
         }
      }
      if (type != null) {
         boolean ckField, ckGet, ckSet, enumSet;
         ckField = mtype.contains(MemberType.Field);
         ckGet = mtype.contains(MemberType.GetMethod);
         ckSet = mtype.contains(MemberType.SetMethod);
         enumSet = mtype.contains(MemberType.Enum);
         if (ckField || ckGet || ckSet || enumSet) {
            if (ckField && ModelUtil.isField(type))
               return type;
            if (ckGet && ModelUtil.hasGetMethod(type))
               return type;
            if (ckSet && ModelUtil.hasSetMethod(type))
               return type;
            if (enumSet && ModelUtil.isEnum(type))
               return type;
         }
      }
      Object res = super.definesMember(name, mtype, refType, ctx, skipIfaces, isTransformed);
      if (res != null)
         return res;
      if (commandInterpreter != null) {
         res = commandInterpreter.cmdObject.definesMember(name, mtype, refType, ctx, skipIfaces, nonTransformedModel != null);
         if (res != null)
            return res;
      }
      return null;
   }

   public Object findMethod(String name, List<? extends Object> params, Object fromChild, Object refType, boolean staticOnly, Object inferredType) {
      Object v;

      if ((v = definesMethod(name, params, null, refType, nonTransformedModel != null, staticOnly, inferredType, null)) != null)
         return v;

      // If this is an inner type, we still need to check the parent
      return super.findMethod(name, params, this, refType, staticOnly, inferredType);
   }

   public Object definesMethod(String name, List<?> types, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs) {
      Object v;
      List<Object> importedMethodTypes = null;

      initTypeInfo();

      if (staticImportMethods != null) {
         importedMethodTypes = staticImportMethods.get(name);
      }
      if (importedMethodTypes == null) {
         // TODO: shouldn't we restrict this layer arg to the "next layer" - same for getImportDecl
         Object type = null;
         if (layeredSystem != null && (type = layeredSystem.getImportedStaticType(name, null, getLayer())) != null) {
            layeredSystem.addAutoImport(getLayer(), getModelTypeName(), ImportDeclaration.createStatic(CTypeUtil.prefixPath(ModelUtil.getTypeName(type), name)));
         }
         if (type != null) {
            v = ModelUtil.definesMethod(type, name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs, layeredSystem);
            if (v != null && ModelUtil.hasModifier(v, "static"))
               return v;
         }
      }
      else {
         for (Object importedType:importedMethodTypes) {
            v = ModelUtil.definesMethod(importedType, name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs, layeredSystem);
            if (v != null && ModelUtil.hasModifier(v, "static"))
               return v;
         }
      }
      Object res = super.definesMethod(name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs);
      if (res != null)
         return res;
      if (commandInterpreter != null)
         return commandInterpreter.cmdObject.definesMethod(name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs);
      return null;
   }

   public boolean needsCompile() {
      TypeDeclaration modelType = getModelTypeDeclaration();
      return modelType != null && modelType.needsCompile();
   }

   public boolean needsPostBuild() {
      JavaModel modModel = getModifiedModel();
      if (modModel == this) {
         return false;
      }
      // There might be a modified template somewhere in the modify hierarchy that needs to be post-built.
      if (modModel != null)
         return modModel.needsPostBuild();
      return false;
   }

   public String toString() {
      if (JavaSemanticNode.debugDisablePrettyToString)
         return toModelString();
      LayeredSystem sys = getLayeredSystem();
      String runtime = sys != null ? " (runtime: " + sys.getProcessIdent() + ")" : "";
      String isTransformed = nonTransformedModel != null ? " (transformed)" : "";
      return toLocationString(null, true, false, false) + runtime + isTransformed;
   }

   public long getLastModifiedTime() {
      return lastModifiedTime;
   }

   public void setLastModifiedTime(long t) {
      lastModifiedTime = t;
   }

   /** Used to create unique names for methods/properties on inner classes. */
   private String getInnerTypeMemberName(TypeDeclaration toType, String property) {
      TypeDeclaration encType = toType.getEnclosingType();
      if (encType == null)
         return property;
      String res = property;
      do {
         res = toType.typeName + "." + res;
         toType = encType;
         encType = encType.getEnclosingType();
      } while (encType != null);
      return res;
   }

   public void addBindDependency(TypeDeclaration toType, String property, TypeDeclaration fromType, boolean referenceOnly) {
      if (reverseDeps == null) {
         Layer myBuildLayer = getBuildLayer();
         if (myBuildLayer == null || getSrcFile() == null)
            return;
         if (reverseDepsInited || !readReverseDeps(myBuildLayer)) {
            reverseDeps = new ReverseDependencies();
         }
      }

      reverseDeps.addBindDependency(ModelUtil.getTypeName(fromType), getInnerTypeMemberName(toType, property), referenceOnly);
   }

   public void addDynMethod(TypeDeclaration toType, String methodName, String paramSig, TypeDeclaration fromType) {
      if (reverseDeps == null) {
         if (reverseDepsInited || !readReverseDeps(getBuildLayer()))
            reverseDeps = new ReverseDependencies();
      }

      reverseDeps.addDynMethod(ModelUtil.getTypeName(fromType), getInnerTypeMemberName(toType, methodName), paramSig);
   }

   public void addReverseDeps(ReverseDependencies rds) {
      if (reverseDeps == null)
         reverseDeps = rds;
      else if (rds != null) {
         reverseDeps.addDeps(rds);
      }
   }

   public void cleanStaleEntries(HashSet<String> changedTypes) {
      if (reverseDeps != null) {
         reverseDeps.cleanStaleEntries(changedTypes);
      }
   }

   public String getReverseDepsName(String buildSrcDir) {
      return FileUtil.replaceExtension(getProcessedFileName(buildSrcDir), ReverseDependencies.REVERSE_DEPENDENCIES_EXTENSION);
   }

   public void saveReverseDeps(String buildSrcDir) {
      TypeDeclaration modelType = getUnresolvedModelTypeDeclaration();

      // TODO: remove this commented out code - fixed this problem by only calling saveReverseDeps when generate=true - that way, it matches the generated source code.
      // Only save the reverse deps for the most specific type.  We merge all of the reverse deps up
      // into that type during the process so it will include any added to modified types.
      //if (modelType != null && modelType.replacedByType != null && modelType.replacedByType.getLayer().getLayerPosition() > modelType.getLayeredSystem().currentBuildLayer.getLayerPosition())
      //    return;

      String revFileName = getReverseDepsName(buildSrcDir);

      ReverseDependencies.saveReverseDeps(reverseDeps, revFileName);
   }

   /**
    * When determining which reverse deps we read, we need to find the layer which this model was built in. Start out
    * at this model's layer and choose the first build layer which extends the layer which defines this model.  That's the
    * build layer where this model should have last been built.  Finally, assume the current build layer is the layer.
    */
   /*
   Layer getBuildLayer() {

      Layer thisLayer = getLayer();
      if (thisLayer.isBuildLayer())
         return thisLayer;

      Layer l = thisLayer;
      while ((l = l.getNextLayer()) != null) {
         if (l.isBuildLayer() && l.extendsLayer(thisLayer)) {
            return l;
         }
      }
      return getLayeredSystem().buildLayer;
   }
   */


   Layer getBuildLayer() {
      LayeredSystem sys = getLayeredSystem();
      Layer thisLayer = getLayer();

      if (sys == null || thisLayer == null || sys.buildLayer == null || !thisLayer.activated)
         return null;

      TypeDeclaration modelType = getModelTypeDeclaration();

      if (modelType != null) {
         // First approach is to use the buildSrcIndex to find the most specific file for this guy.
         for (int i = sys.buildLayer.getLayerPosition(); i >= thisLayer.getLayerPosition(); i--) {
            Layer layer = sys.layers.get(i);
            String srcIndexName = getModelTypeDeclaration().getSrcIndexName();
            if (layer.getSrcFileIndex(srcIndexName) != null)
               return layer;
         }
      }

      // Secondly, pick the most specific build layer after this type is defined
      if (thisLayer.isBuildLayer())
         return thisLayer;

      Layer l = thisLayer;
      while ((l = l.getNextLayer()) != null) {
         if (l.isBuildLayer() && l.extendsLayer(thisLayer)) {
            return l;
         }
      }

      // OK, just use the build layer
      return getLayeredSystem().buildLayer;
   }

   public void cleanReverseDeps(Layer buildLayer) {
      TypeDeclaration modelType = getModelTypeDeclaration();
      Layer thisLayer = getLayer();

      // Only process the reverse deps for the most specific type or the types which corresponds
      // to the buildDir.  We don't want to read in an extended layer's deps for base layer's type
      // or it won't be able to resolve stuff.
      if (modelType.replacedByType != null && buildLayer != thisLayer)
         return;

      String buildSrcDir = layer.buildSrcDir;

      String revDepsFileName = getReverseDepsName(buildSrcDir);
      File revDepsFile = new File(revDepsFileName);
      if (revDepsFile.canRead())
         revDepsFile.delete();
      reverseDeps = new ReverseDependencies();
      reverseDepsInited = true;
   }

   public boolean readReverseDeps(Layer buildLayer) {
      TypeDeclaration modelType = getModelTypeDeclaration();

      if (modelType == null)
         return false;

      LayeredSystem sys = modelType.getLayeredSystem();

      // If buildAllFiles is true, and we are loading the model for the first time, skip the reverse deps - since we want
      // clean builds to start out clean.  But if we are reloading the model, the original version might have had reverse deps we need.
      if (temporary || buildLayer == null || !sys.staleModelDependencies(modelType.getFullTypeName()))
         return false;

      Layer thisLayer = getLayer();

      // Only process the reverse deps for the most specific type or the types which corresponds
      // to the buildDir.  We don't want to read in an extended layer's deps for base layer's type
      // or it won't be able to resolve stuff.
      if (modelType.replacedByType != null && buildLayer != thisLayer)
         return false;

      ReverseDependencies deps;

      // Need to skip over any .inh files where there is no rdps.
      for (int i = buildLayer.getLayerPosition(); i >= thisLayer.getLayerPosition(); i--) {
         if (i >= sys.layers.size())
            System.out.println("*** invalid layer index!");
         Layer layer = sys.layers.get(i);
         deps = readLayerReverseDeps(layer);
         if (deps != null) {
            reverseDeps = deps;
            return true;
         }
      }

      deps = readLayerReverseDeps(sys.buildLayer);
      if (deps != null) {
         reverseDeps = deps;
         return true;
      }

      return false;
   }

   ReverseDependencies readLayerReverseDeps(Layer layer) {
      if (!layer.isBuildLayer())
         return null;
      String buildSrcDir = layer.buildSrcDir;

      String revDepsFileName = getReverseDepsName(buildSrcDir);
      ReverseDependencies revDeps = ReverseDependencies.readReverseDeps(revDepsFileName, reverseDeps);
      if (revDeps != null)
         revDeps.layer = layer;
      return revDeps;
   }

   public List<String> getPrevTypeGroupMembers(Layer buildLayer, String typeGroupName) {
      if (reverseDeps == null || reverseDeps.layer != buildLayer)
         readReverseDeps(buildLayer);
      if (reverseDeps != null) {
         if (reverseDeps.typeGroupDeps != null)
            return reverseDeps.typeGroupDeps.get(typeGroupName);
      }
      return null;
   }

   public void setPrevTypeGroupMembers(String buildSrcDir, String typeGroupName, List<TypeGroupMember> newTypeGroupMembers) {
      if (reverseDeps == null) {
         if (reverseDepsInited || !readReverseDeps(getBuildLayer()))
            reverseDeps = new ReverseDependencies();
      }
      if (reverseDeps.typeGroupDeps == null)
         reverseDeps.typeGroupDeps = new HashMap<String,ArrayList<String>>();
      ArrayList<String> memberNames = new ArrayList<String>(newTypeGroupMembers.size());
      for (TypeGroupMember memb:newTypeGroupMembers)
         memberNames.add(memb.typeName);
      reverseDeps.typeGroupDeps.put(typeGroupName, memberNames);
      saveReverseDeps(buildSrcDir);
   }

   /**
    * Associates this model with a command interpreter for resolving the cmd object.
    * Must propagate this up the modified hierarchy
    * since the defineType call gets redirected by replacedByType.
    */
   public void setCommandInterpreter(AbstractInterpreter interp) {
      commandInterpreter = interp;
      // We will just use the latest model here as the root
      interp.cmdObject.parentNode = this;
      ParseUtil.initAndStartComponent(interp.cmdObject);
      JavaModel replacedModel = resolveModel();
      if (replacedModel != this)
         replacedModel.setCommandInterpreter(interp);
   }

   /** Resolves through any deleted copies of the model only.  Does not go up the layer stack and return the most specific model for this type. */
   public JavaModel resolveModel() {
      // Once we've been transformed, the parentNode of the type becomes the transformed model so this logic no longer works.
      if (transformed)
         return this;
      BodyTypeDeclaration modelType = getLayerTypeDeclaration();
      if (modelType == null)
         return this;
      return modelType.getJavaModel();
   }

   public JavaModel resolveLastModel() {
      // Once we've been transformed, the parentNode of the type becomes the transformed model so this logic no longer works.
      if (transformed)
         return this;
      BodyTypeDeclaration modelType = getModelTypeDeclaration();
      if (modelType == null)
         return this;

      // In the IDE context, we do not always load the types in reverse order like we during the build.  So from the IDE, we may need
      // to find and populate the most specific type here.
      String fullTypeName = modelType.getFullTypeName();
      TypeDeclaration lastType = (TypeDeclaration) layeredSystem.getSrcTypeDeclaration(fullTypeName, null, true, false, true, null, isLayerModel);
      if (lastType != null)
         return lastType.getJavaModel();

      return modelType.getJavaModel();
   }

   public boolean getPrependPackage() {
      return prependLayerPackage;
   }

   public void addDependentTypes(Set<Object> resultTypes, DepTypeCtx mode) {
      if (types == null)
         return;
      for (TypeDeclaration type:types)
         type.addDependentTypes(resultTypes, mode);
   }

   public String getUserVisibleName() {
      return "java file: ";
   }

   transient IMessageHandler errorHandler = null;
   public transient StringBuilder errorMessages = null;
   public transient StringBuilder warningMessages = null;

   public void setErrorHandler(IMessageHandler errorHandler) {
      this.errorHandler = errorHandler;
   }

   public IMessageHandler getErrorHandler() {
      return errorHandler;
   }

   public void reportError(String error, ISemanticNode source) {
      if (layer != null && layer.getBaseLayerDisabled())
         error = "Layer disabled: " + error;
      if (errorHandler != null) {
         LayerUtil.reportMessageToHandler(errorHandler, error, getSrcFile(), source, MessageType.Error);
      }
      else {
         if (errorMessages == null)
            errorMessages = new StringBuilder();
         errorMessages.append(error);
      }
      hasErrors = true;
      if (layeredSystem != null) {
         // Skip errors when the layer failed to start since they are most likely due to those errors
         // TODO: should check for base layers and move this up so we don't report them in the IDE either?
         if (layer != null && layer.errorsStarting)
            return;
         // Already seen this error in this build
         if (layeredSystem.isErrorViewed(error, getSrcFile(), source)) {
            return;
         }
      }
      System.err.println(error);
   }

   public void reportWarning(String error, ISemanticNode source) {
      boolean treatWarningsAsErrors = layeredSystem != null && layeredSystem.options.treatWarningsAsErrors;
      if (treatWarningsAsErrors)
         hasErrors = true;

      if (errorHandler != null) {
         LayerUtil.reportMessageToHandler(errorHandler, error, getSrcFile(), source, MessageType.Warning);
      }
      else {
         if (treatWarningsAsErrors) {
            if (errorMessages == null)
               errorMessages = new StringBuilder();
            errorMessages.append(error);
         }
         else {
            if (warningMessages == null)
               warningMessages = new StringBuilder();
            warningMessages.append(error);
         }
      }
      if (layeredSystem != null) {
         // Already seen this error in this build
         if (layeredSystem.isWarningViewed(error, getSrcFile(), source)) {
            return;
         }
      }
      if (!treatWarningsAsErrors)
         System.out.println(error);
      else
         System.err.println(error);
   }

   public void clearTransformed() {
      if (replacedByModel != null)
         replacedByModel.clearTransformed();
      if (transformed) {
         transformed = false;
         if (types != null) {
            for (TypeDeclaration type:types)
               type.clearTransformed();
         }
      }

      transformedModel = null;
      transformedInLayer = null;
      fileLineIndex = null;

      // Any models that modify this one in the layer stack also need to be re-transformed
      JavaModel modifiedByModel = getModifiedByModel();
      if (modifiedByModel != null)
         modifiedByModel.clearTransformed();
   }

   public JavaModel getModifiedByModel() {
      if (types == null || types.size() == 0)
         return null;
      BodyTypeDeclaration modifiedModelType = types.get(0).getRealReplacedByType();
      if (modifiedModelType == null)
         return null;
      JavaModel toRet = modifiedModelType.getJavaModel();
      if (toRet == this)
         return null;
      return toRet;
   }

   public void refreshModelText() {
      setCachedModelText(getHTMLModelText());
   }

   public void refreshGeneratedText() {
      setCachedGeneratedText(getGeneratedText());
      setCachedGeneratedJSText(getGeneratedJSText());
      setCachedGeneratedSCText(getGeneratedSCText());
      setCachedGeneratedClientJavaText(getGeneratedClientJavaText());
   }

   public void clearModelText() {
      setCachedModelText(null);
   }

   public void clearGeneratedText() {
      setCachedGeneratedText(null);
      setCachedGeneratedJSText(null);
      setCachedGeneratedSCText(null);
      setCachedGeneratedClientJavaText(null);
   }

   /**
    * Sends a value changed event using the data binding system.  This lets code which edits models add listeners directly on a
    * model and receive notifications when they change.
    */
   public void markChanged() {
      if (needsModelText)
         refreshModelText();
      else
         clearModelText();
      if (getNeedsGeneratedText())
         refreshGeneratedText();
      else
         clearGeneratedText();
      Bind.sendEvent(IListener.VALUE_CHANGED, this, null);
      Bind.sendChangedEvent(this, "cachedGeneratedJSText");
      Bind.sendChangedEvent(this, "cachedGeneratedText");
      Bind.sendChangedEvent(this, "cachedModelText");
      Bind.sendChangedEvent(this, "cachedGeneratedSCText");
      Bind.sendChangedEvent(this, "cachedGeneratedClientJavaText");
      if (types != null && !removed) {
         for (BodyTypeDeclaration btd:types)
            btd.refreshClientTypeDeclaration();
      }
      clearTransformed();
   }

   public void setParseNodeValid(boolean val) {
      super.setParseNodeValid(val);
   }

   /** After a JavaModel is replaced, this resolves the model which replaced it */
   public JavaModel resolve() {
      return getLayerTypeDeclaration().resolve(false).getJavaModel();
   }

   /** Find all instances for all types in this model and dispose of them */
   public void disposeInstances() {
      if (!isLayerModel) {
         Map<String, TypeDeclaration> types = getDefinedTypes();
         // TODO: do we need to do anything to removbe children from parents?  It seems like we are throwing them all away so should not have to.
         for (TypeDeclaration td:types.values()) {
            td.removeTypeInstances();
         }
      }
   }

   public JavaLanguage getLanguage() {
      return JavaLanguage.getJavaLanguage();
   }

   public ISemanticNode deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      JavaModel copy = (JavaModel) super.deepCopy(options, oldNewMap);
      copy.layeredSystem = layeredSystem;
      copy.layer = layer;
      copy.isLayerModel = isLayerModel;

      // Need to clone this because we may change it when moving the model from one layered system to another
      copy.srcFiles = cloneSrcFiles();

      if ((options & CopyInitLevels) != 0) {
         // For transform at least, these are data structures that should be immutable so we are copying by reference.
         copy.externalReferences = externalReferences;

         // Register just the imports before the transform  During transform, importsByName gets updated to include imports in merged types.
         // so we need to make a copy.
         copy.importsByName = (HashMap<String,Object>) importsByName.clone();

         copy.definedTypesByName = (HashMap<String,TypeDeclaration>)definedTypesByName.clone();
         copy.typeIndex = typeIndex;
         copy.computedPackagePrefix = computedPackagePrefix;
         //copy.temporary = true;
         copy.reverseDeps = reverseDeps;

         copy.prependLayerPackage = prependLayerPackage;
         copy.extraFiles = extraFiles;
         copy.reverseDepsInited = reverseDepsInited;

         // This gets set during transform
         //copy.copyImportModels = copyImportModels;

         copy.lastModifiedTime = lastModifiedTime;

         copy.modifiedModel = modifiedModel; // TODO; for transform should this be the modifiedModel.transformedModel?

         copy.initPackage = initPackage;

         copy.dependentFiles = dependentFiles;
         copy.layer = layer;
         copy.resultSuffix = resultSuffix;
      }

      return copy;
   }

   public void setMergeDeclaration(boolean val) {
      mergeDeclaration = val;
   }

   public void stop() {
      stop(true);
   }

   public void stop(boolean stopModified) {
      if (beingStopped) {
         System.err.println("*** Stopping a model that's beingStopped");
         return;
      }
      beingStopped = true;
      if (stopModified && modifiedModel != null) {
         modifiedModel.stop();
         // This gets reset lazily from the modified type, but during a rebuild if we leave this value around, it could be stale
         modifiedModel = null;
      }

      SrcEntry srcEnt = getSrcFile();

      if (srcEnt != null)
         layeredSystem.removeTypesByName(srcEnt.layer, getPackagePrefix(), getDefinedTypes(), getLayer());

      super.stop();

      typeInfoInited = false;
      initPackage = false;
      typeIndex.clear();
      externalReferences.clear();
      importsByName.clear();
      if (staticImportProperties != null)
         staticImportProperties = null;
      if (staticImportMethods != null)
         staticImportMethods = null;
      definedTypesByName.clear();
      needsRestart = false;
      clearTransformed();
      beingStopped = false;
   }

   @Bindable(manual=true)
   public void setNeedsModelText(boolean val) {
      needsModelText = val;
      // We need to explicit tell the sync system this is to be recorded for the case where we are applying changes.  We use nestedBindingCount currently but it would
      // good to find a cleaner way to differentiate properties we set which are part of the previous state versus the recorded state.
      SyncManager.SyncState oldState = SyncManager.setSyncState(SyncManager.SyncState.RecordingChanges);
      if (val)
         setCachedModelText(getHTMLModelText());
      else
         setCachedModelText(null);
      SyncManager.setSyncState(oldState);
   }

   public boolean getNeedsModelText() {
      return needsModelText;
   }

   @Bindable(manual=true)
   public String getModelText() {
      return toLanguageString((getLanguage()).getStartParselet()).toString();
   }

   @Bindable(manual=true)
   public String getHTMLModelText() {
      return ParseUtil.styleSemanticValue(this, ((getLanguage()).getStartParselet())).toString();
   }

   @Bindable(manual=true)
   public void setCachedModelText(String str) {
      cachedModelText = str;
      Bind.sendChangedEvent(this, "cachedModelText");
   }

   @sc.obj.HTMLSettings(returnsHTML=true)
   public String getCachedModelText() {
      return cachedModelText;
   }

   @Bindable(manual=true)
   public void setCachedGeneratedSCText(String str) {
      cachedGeneratedSCText = str;
      Bind.sendChangedEvent(this, "cachedGeneratedSCText");
   }

   @sc.obj.HTMLSettings(returnsHTML=true)
   public String getCachedGeneratedSCText() {
      return cachedGeneratedSCText;
   }

   @Bindable(manual=true)
   public void setCachedGeneratedClientJavaText(String str) {
      cachedGeneratedClientJavaText = str;
      Bind.sendChangedEvent(this, "cachedGeneratedClientJavaText");
   }

   @sc.obj.HTMLSettings(returnsHTML=true)
   public String getCachedGeneratedClientJavaText() {
      return cachedGeneratedClientJavaText;
   }

   @Bindable(manual=true)
   public void setNeedsGeneratedText(boolean val) {
      // We need to explicit tell the sync system this is to be recorded for the case where we are applying changes.  We use nestedBindingCount currently but it would
      // good to find a cleaner way to differentiate properties we set which are part of the previous state versus the recorded state.
      SyncManager.SyncState oldState = SyncManager.setSyncState(SyncManager.SyncState.RecordingChanges);
      if (val) {
         refreshGeneratedText();
      }
      else {
         clearGeneratedText();
      }
      SyncManager.setSyncState(oldState);
   }

   public boolean getNeedsGeneratedText() {
      return cachedGeneratedText != null || cachedGeneratedJSText != null;
   }

   @Bindable(manual=true)
   @sc.obj.HTMLSettings(returnsHTML=true)
   public String getGeneratedText() {
      String fileName = getProcessedFileName(layeredSystem.buildLayer.buildSrcDir);
      try {
         Object res = JavaLanguage.getJavaLanguage().styleNoTypeErrors(FileUtil.getFileAsString(fileName));
         return res.toString();
      }
      catch (IllegalArgumentException exc) {
         if (isDynamicType())
            return "Dynamic type - no Java generated";
         if (layer != null && layer.annotationLayer)
            return "Annotation layer file - no Java generated";
         if (isLayerModel)
            return "Layer definition file - no Java generated";
        return "No generated file for this type (path: " + fileName + ")";
      }
   }

   @Bindable(manual=true)
   @sc.obj.HTMLSettings(returnsHTML=false)
   public String getGeneratedJSText() {
      LayeredSystem sys = getLayeredSystem();
      JSRuntimeProcessor runtime = (JSRuntimeProcessor) sys.getRuntime("js");
      if (runtime == null)
         return "<no js runtime - no js source available>";
      BodyTypeDeclaration modelType = getModelTypeDeclaration();
      //Object res = JSLanguage.getJSLanguage().style(FileUtil.getFileAsString(jsEnt.absFileName));
      try {
         StringBuilder res = new StringBuilder();
         appendTypeJS(modelType, res, runtime);
         return res.toString();
      } catch (IllegalArgumentException exc) {
         return "No javascript file for this type: " + exc.toString();
      }
   }

   @Bindable(manual=true)
   @sc.obj.HTMLSettings(returnsHTML=true)
   public String getGeneratedSCText() {
      return null;
   }

   private BodyTypeDeclaration getJSRuntimeModelType() {
      LayeredSystem sys = getLayeredSystem();
      JSRuntimeProcessor runtime = (JSRuntimeProcessor) sys.getRuntime("js");
      if (runtime == null)
         return null;

      LayeredSystem jsSys = runtime.getLayeredSystem();
      String typeName = getModelTypeName();
      if (typeName == null)
         return null;
      BodyTypeDeclaration type = jsSys.getSrcTypeDeclaration(typeName, null, true, true);
      return type;
   }

   @Bindable(manual=true)
   @sc.obj.HTMLSettings(returnsHTML=true)
   public String getGeneratedClientJavaText() {
      BodyTypeDeclaration jsModelType = getJSRuntimeModelType();
      if (jsModelType == null)
         return "<no js runtime src for type>";
      return jsModelType.getJavaModel().getGeneratedText();
   }

   @Constant
   public boolean getExistsInJSRuntime() {
      return getJSRuntimeModelType() != null;
   }

   public void setExistsInJSRuntime(boolean b) {
      throw new UnsupportedOperationException(); // Here for client/server sync cause JavaModel gets used in it's compiled form, even when compiling the client we need this method to exist for it to compile in this mode
   }

   private void appendTypeJS(BodyTypeDeclaration type, StringBuilder sb, JSRuntimeProcessor runtime) {
      SrcEntry jsEnt = runtime.findJSSrcEntry(runtime.getLayeredSystem().buildLayer, type);
      if (jsEnt == null) {
         System.err.println("*** Unable to find JS src file for: " + type.typeName);
         return;
      }
      // Some types like enums don't end up as files so just skip them
      if (new File(jsEnt.absFileName).canRead()) {
         sb.append(FileUtil.getFileAsString(jsEnt.absFileName));
      }
      List<Object> innerTypes = type.getAllInnerTypes(null, true);
      if (innerTypes != null) {
         for (Object innerType:innerTypes) {
            if (innerType instanceof BodyTypeDeclaration && !(innerType instanceof EnumConstant))
               appendTypeJS((BodyTypeDeclaration) innerType, sb, runtime);
         }
      }
   }

   @Bindable(manual=true)
   public void setCachedGeneratedText(String str) {
      cachedGeneratedText = str;
      Bind.sendChangedEvent(this, "cachedGeneratedText");
   }

   @sc.obj.HTMLSettings(returnsHTML=true)
   public String getCachedGeneratedText() {
      return cachedGeneratedText;
   }

   @Bindable(manual=true)
   public void setCachedGeneratedJSText(String str) {
      cachedGeneratedJSText = str;
      Bind.sendChangedEvent(this, "cachedGeneratedJSText");
   }

   public String getCachedGeneratedJSText() {
      return cachedGeneratedJSText;
   }

   public boolean getDependenciesChanged(Layer genLayer, Set<String> changedTypes, boolean processJava) {
      if (needsRestart) {  // Has this model been explicitly marked for a 'restart' (e.g. during a second buildAll build) - if so, consider it as changed even if it has not actually changed.
         return true;
      }
      if (types != null) {
         for (TypeDeclaration td:types) {
            if (td.changedSinceLayer(transformedInLayer, genLayer, false, null, changedTypes, processJava))
               return true;
         }
      }
      return false;
   }

   public void setUserData(Object v)  {
      userData = v;
   }

   public Object getUserData() {
      return userData;
   }

   public JavaModel refreshNode() {
      if (layeredSystem == null)
         return this;
      // Here we pick the latest annotated model
      return (JavaModel) layeredSystem.getAnnotatedModel(getSrcFile());
   }

   public void setAdded(boolean v) {
      added = v;
   }

   /**
    * Called for models which are not part of the type system for the runtime
    * (e.g. documentation files, temporary nodes created by the interpreter or those created during the editing process to apply a batch of changes)
    * Sets temporaryType = true on all children types
    */
   public void markAsTemporary() {
      temporary = true;
      if (types != null) {
         for (TypeDeclaration type:types)
            type.markAsTemporary();
      }
   }

   public boolean isAdded() {
      return added;
   }

   public boolean sameModel(ILanguageModel other) {
      return other instanceof JavaModel && ((JavaModel) other).deepEquals(this);
   }

   public void refreshBoundTypes(int flags) {
      if (types != null) {
         for (TypeDeclaration type:types)
            type.refreshBoundTypes(flags);
      }
   }

   public boolean displayTypeError(String...args) {
      if (!disableTypeErrors) {
         reportError(getMessageString(args), this);
         return true;
      }
      return false;
   }

   public boolean getDisableTypeErrors() {
      if (disableTypeErrors)
         return true;
      if (nonTransformedModel != null)
         return nonTransformedModel.getDisableTypeErrors();
      return false;
   }

   public void updateTypeName(String oldTypeName, String newTypeName, boolean renameFile) {
      TypeDeclaration oldType = definedTypesByName.remove(oldTypeName);
      if (oldType != null)
         definedTypesByName.put(newTypeName, oldType);
      Object oldObj = typeIndex.remove(oldTypeName);
      if (oldObj != null) {
         addTypeToIndex(newTypeName, oldObj);
      }

      String pkgName = getPackagePrefix();
      String oldFullName = CTypeUtil.prefixPath(pkgName, oldTypeName);
      String newFullName = CTypeUtil.prefixPath(pkgName, newTypeName);
      SrcEntry srcFile = getSrcFile();
      if (srcFile != null) {
         String oldFileName = srcFile.absFileName;
         SrcEntry oldSrcEnt = srcFile.clone();
         srcFile.setTypeName(newTypeName, renameFile);
         String newFileName = srcFile.absFileName;
         if (layeredSystem != null)
            layeredSystem.fileRenamed(oldSrcEnt, srcFile, true);
      }
      if (layeredSystem != null) {
         layeredSystem.updateTypeName(oldFullName, newFullName, true);
      }
   }

   /**
    * Called when we detect that a source file has changed ffom outside the system.
    * This handles updating the srcFile on this model and updating the indexes in teh layered system that
    * depend on the file name.  NOTE: this does not change the type name of the model type which typically
    * must correspond with the type but during the editing process, sometimes these become different so we need to
    * handle the update independently.
    */
   public void fileRenamed(SrcEntry oldSrcEnt, SrcEntry newSrcEnt) {
      if (srcFiles != null && srcFiles.size() > 0) {
         srcFiles.remove(0);
         srcFiles.add(0, newSrcEnt);
      }
      if (layeredSystem != null)
         layeredSystem.fileRenamed(oldSrcEnt, newSrcEnt, true);
   }

   public String getComment() {
      StringBuilder res = new StringBuilder();
      if (parseNode != null) {
         ParentParseNode pn = (ParentParseNode) parseNode;
         if (pn.children != null) {
            // Spacing before package
            Object child = pn.children.get(0);
            if (child != null)
               res.append(child.toString());
            // Spacing after package
            child = pn.children.get(1);
            if (child != null)
               res.append(ParseUtil.getTrailingSpaceForNode(child));
            // Spacing after imports
            child = pn.children.get(2);
            if (child != null)
               res.append(ParseUtil.getTrailingSpaceForNode(child));
         }
      }
      return res.toString();
   }

   public String getClassComment() {
      StringBuilder res = new StringBuilder();
      if (parseNode != null) {
         ParentParseNode pn = (ParentParseNode) parseNode;
         if (pn.children != null) {
            // Spacing after imports
            Object child = pn.children.get(2);
            if (child != null)
               res.append(ParseUtil.getTrailingSpaceForNode(child));
            else {
               // Spacing after package
               child = pn.children.get(1);
               if (child != null)
                  res.append(ParseUtil.getTrailingSpaceForNode(child));
               else {
                  // Spacing before package
                  child = pn.children.get(0);
                  if (child != null)
                     res.append(child.toString());
               }
            }
         }
      }
      return res.toString();
   }

   public boolean isUnsavedModel() {
      return unsavedModel;
   }

   /** This is a way for code to determine this model has been created from the command-line */
   public EditorContext getEditorContext() {
      return commandInterpreter;
   }

   public File getLineIndexFile(String buildSrcDir) {
      return GenFileLineIndex.getLineIndexFile(getProcessedFileName(buildSrcDir));
   }

   public void updateFileLineIndex(String transformedResult, String buildSrcDir) {
      if (transformedModel != null) {
         // The generation process does not set these so we need to reset them before doing the line number processing
         ParseUtil.resetStartIndexes(transformedModel);
         GenFileLineIndex idx = transformedModel.generateFileLineIndex(transformedResult, buildSrcDir);

         /* For debugging you can do something like this
         if (idx.genFileName.contains("UnitConverter.java")) {
            System.out.println(idx.dump(0, 100));
         }
         */

         fileLineIndex = transformedModel.fileLineIndex = idx;

         // Should this file have a '.' in front of it to hide it by default?
         File lineIndexFile = getLineIndexFile(buildSrcDir);

         idx.saveLineIndexFile(lineIndexFile);
      }
      else {
         // TODO: remove an old file line index?
      }
   }


   public GenFileLineIndex readFileLineIndex(String buildSrcDir) {
      if (fileLineIndex != null && fileLineIndex.buildSrcDir.equals(buildSrcDir))
         return fileLineIndex;
      fileLineIndex = GenFileLineIndex.readFileLineIndexFile(getProcessedFileName(buildSrcDir));
      return fileLineIndex;
   }

   public List<Integer> getGenLinesForSrcStatement(ISrcStatement st, String buildDir) {
      GenFileLineIndex idx = readFileLineIndex(buildDir);
      if (idx != null) {
         return idx.getGenLinesForSrcLine(getSrcFile().absFileName, ParseUtil.getLineNumberForNode(getParseNode(), st.getParseNode()));
      }
      return null;
   }

   public ISrcStatement getSrcStatementForGenLine(int lineNum, String buildDir) {
      GenFileLineIndex idx = readFileLineIndex(buildDir);
      if (idx != null) {
         FileRangeRef ref = idx.getSrcFileForGenLine(lineNum);
         if (ref != null) {
            LayeredSystem sys = getLayeredSystem();

            // Used to use 'active' here but for the IDE when not using the internal build, we do not have logic to refresh the active models anymore.  There might
            // a way that we could use the active models to represent the version that was last compiled so it's more accurate but that would require probably caching
            // all active models at the time we active the layers which will take up a lot of memory.  Right now, I'm not sure we should bring in any active models unless
            // using the internal build.
            ILanguageModel model = sys.getCachedModelByPath(ref.absFileName, false);
            if (model == null) {
               SrcEntry srcEnt = sys.getSrcEntryForPath(ref.absFileName, false, true);
               if (srcEnt != null) {
                  Object obj = sys.parseSrcFile(srcEnt, srcEnt.isLayerFile(), true, true, false, false);
                  if (obj instanceof ILanguageModel)
                     model = (ILanguageModel) obj;
                  else
                     System.out.println("*** Errors in model to get src statement for line?");
               }
            }
            if (model != null) {
               ISemanticNode res = ParseUtil.getNodeAtLine(model.getParseNode(), ref.startLine);
               if (res instanceof ISrcStatement)
                  return ((ISrcStatement) res);
               else if (res != null)
                  res = ModelUtil.getTopLevelStatement(res);
               if (res instanceof ISrcStatement)
                  return ((ISrcStatement) res);
            }
         }
      }
      return null;
   }

   public GenFileLineIndex generateFileLineIndex(String transformedResult, String buildSrcDir) {
      String genFileName = getProcessedFileName(buildSrcDir);
      GenFileLineIndex idx = new GenFileLineIndex(genFileName, transformedResult, buildSrcDir);
      if (idx.verbose)
         System.out.println("*** Starting genFileIndex for: " + getSrcFile() + " -> " + genFileName);
      if (types != null) {
         for (TypeDeclaration type:types) {
            SemanticNodeList<Statement> body = type.body;
            if (body != null) {
               for (Statement st:body) {
                  st.addToFileLineIndex(idx, -1);
               }
            }
         }
      }
      if (idx.verbose)
         System.out.println("*** Completed genFileIndex for: " + getSrcFile() + " index: " + idx);
      idx.cleanUp();
      return idx;
   }

   public Object getModelDefaultModifier() {
      return null;
   }

   public boolean isReplacedModel() {
      if (replacedByModel != null)
         return true;
      if (types != null) {
         for (TypeDeclaration type:types)
            if (type.replaced)
               return true;
      }
      return false;
   }

   public void restoreParseNode() {
      if (parseNode == null) {
         LayeredSystem sys = getLayeredSystem();
         SrcEntry srcEnt = getSrcFile();
         if (srcEnt == null)
            return;
         IFileProcessor processor = sys.getFileProcessorForSrcEnt(srcEnt, null, false);
            LayerUtil.restoreParseNodes(sys, (Language) processor, srcEnt, getLastModifiedTime(), this);
      }
      if (parseNode == null)
         System.err.println("*** Failed to restore parse node!");
   }
}

