/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.BindingContext;
import sc.dyn.DynUtil;
import sc.lang.SCLanguage;
import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.js.JSRuntimeProcessor;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.layer.SrcEntry;
import sc.obj.ScopeDefinition;
import sc.parser.ParseError;
import sc.parser.ParseUtil;
import sc.sync.SyncLayer;
import sc.sync.SyncManager;
import sc.type.CTypeUtil;
import sc.type.PTypeUtil;
import sc.util.StringUtil;

import java.util.HashMap;

/**
 * Stores a stream of changes that need to be made to a model.  This object is used both for synchronization and for updating
 * an application when the source code changes.  It's the main data structure that stores info used in a sync update to/from the client.
 *
 * When the serialization format is 'stratacode', you can create the model stream by parsing the input using the ModelStream parselet in the JavaLanguage.
 * Or a model stream can be created in the code from UpdateInstanceInfo - when changes are detected after the source code is refreshed (in this case, it represents
 * the changes to the code since the source was last refreshed).  The model stream can also be created from other serialization formats like JSON.
 */
public class ModelStream extends SemanticNode implements ICustomResolver {
   public SemanticNodeList<JavaModel> modelList;

   /** Set this to true if your sync is going against the sync manager - i.e. is updating instances.  Otherwise, it's updating types.  */
   public boolean isSyncStream = true;
   public SyncManager.SyncContext syncCtx;

   private LayeredSystem system;

   public boolean useRuntimeResolution = true;

   private HashMap<String,TypeDeclaration> lastTypeForName = new HashMap<String,TypeDeclaration>();

   public ModelStream() {
   }

   public ModelStream(boolean isSyncStream) {
      this.isSyncStream = isSyncStream;
   }

   public StringBuilder convertToJS(String destName, String defaultScope) {
      syncCtx = SyncManager.getSyncContext(destName, defaultScope, true);
      LayeredSystem sys = LayeredSystem.getCurrent();
      JSRuntimeProcessor jsProc = (JSRuntimeProcessor) sys.getRuntime("js");
      if (jsProc == null) {
         System.err.println("*** convertToJS called with no JS runtime present");
         return null;
      }
      return jsProc.processModelStream(this);
   }

   /** This is called to apply the model stream to the system using the supplied default scope. */
   public void updateRuntime(String destName, String defaultScope, boolean resetSync) {
      syncCtx = SyncManager.getSyncContext(destName, defaultScope, true);

      ParseUtil.initAndStartComponent(this);

      for (JavaModel model:modelList) {
         if (model.hasErrors()) {
            System.err.println("*** Failed to update runtime for modelStream due to errors in the model");
         }
      }
      for (JavaModel model:modelList)
         model.updateRuntimeModel(syncCtx);
   }

   public void setLayeredSystem(LayeredSystem sys) {
      system = sys;
      if (sys != null && sys.hasDynamicLayers())
         useRuntimeResolution = false; // Need to turn off this optimization when we may have dynamic types since there are no .class files for them in general
      if (modelList != null) {
         for (JavaModel model:modelList) {
            // When we have a sync context, it does the resolving.  We are not updating types, but instances.
            // When there's no sync context, we're processing a model stream that was generated during the refreshChangedModels
            // operation.
            if (isSyncStream) {
               model.customResolver = this;
               // Do not physically merge these types on transform.  Leave them as modified types so we can apply the deltas.
               model.setMergeDeclaration(false);
            }
            model.layeredSystem = sys;
         }
      }
   }

   public JavaModel globalModel;
   public ClassDeclaration globalType;

   /** Returns the type of the object with the given name - for compile time checks */
   public Object resolveType(String currentPackage, String typeName, boolean create, TypeDeclaration fromType) {
      if (CTypeUtil.getClassName(typeName).equals(SyncLayer.GLOBAL_TYPE_NAME)) {
         if (globalType == null) {
            globalModel = new JavaModel();
            globalType = ClassDeclaration.create("class", SyncLayer.GLOBAL_TYPE_NAME, null);
            Layer buildLayer = system == null ? null : system.buildLayer;
            globalModel.setLayeredSystem(system);
            globalModel.setLayer(buildLayer);
            globalModel.addSrcFile(new SrcEntry(buildLayer, SyncLayer.GLOBAL_TYPE_NAME, SyncLayer.GLOBAL_TYPE_NAME));
            globalType.setParentNode(globalModel);
            globalModel.addTypeDeclaration(globalType);
         }
         return globalType;
      }
      TypeDeclaration lastType = lastTypeForName.get(typeName);
      if (lastType == null && currentPackage != null)
         lastType = lastTypeForName.get(CTypeUtil.prefixPath(currentPackage, typeName));
      if (lastType != null) {
         if (fromType != null)
            lastTypeForName.put(typeName, fromType);
         return lastType;
      }
      Object inst = resolveObject(currentPackage, typeName, create, false);
      if (inst != null) {
         Object type;
         // TODO: remove this special case for TypeDeclarations - because those represent classes in the system, they got messed up in the logic below but that's fixed now with the ClientTypeDeclaration wrapper
         if (typeName.startsWith("sc_type_")) {
            if (DynUtil.isType(inst))
               return inst.getClass();
            else
               type = DynUtil.getType(inst);
         }
         else
            type = DynUtil.getType(inst);
         if (type != null && fromType != null) {
            lastTypeForName.put(typeName, fromType);
         }
         String typeTypeName = DynUtil.getTypeName(type, false);
         Object newType = system == null ? PTypeUtil.findType(typeTypeName) : system.getRuntimeTypeDeclaration(typeTypeName);
         if (newType != null)
            type = newType;
         if (type == null && currentPackage != null) {
            String packageTypeName = CTypeUtil.prefixPath(currentPackage, typeTypeName);
            newType = system == null ? PTypeUtil.findType(packageTypeName) : system.getRuntimeTypeDeclaration(packageTypeName);
            if (newType != null)
               type = newType;
         }
         return type;
      }
      VariableDefinition varDef = resolveFieldWithName(currentPackage, typeName);
      if (varDef != null)
         return varDef.getTypeDeclaration();
      return null;
   }

   public String getResolveMethodName() {
      return "sc.sync.SyncManager.resolveSyncInst";
   }

   /** Returns the object instance with the given name - for runtime lookup. */
   public Object resolveObject(String currentPackage, String name, boolean create, boolean unwrap) {
      if (syncCtx == null)
         return null;
      return syncCtx.resolveObject(currentPackage, name, create, unwrap);
   }

   public VariableDefinition resolveFieldWithName(String pkgName, String typeName) {
      if (modelList == null)
         return null;
      String varName = CTypeUtil.getClassName(typeName);
      String rootName = CTypeUtil.getPackageName(typeName);
      if (rootName != null) {
         for (JavaModel model:modelList) {
            String prefix = model.getPackagePrefix();
            if (StringUtil.equalStrings(prefix, pkgName)) {
               TypeDeclaration modelType = model.getUnresolvedModelTypeDeclaration();
               if (modelType != null && modelType.body != null && modelType.typeName.equals(rootName)) {
                  for (Statement st:modelType.body) {
                     if (st instanceof FieldDefinition) {
                        FieldDefinition fd = (FieldDefinition) st;
                        for (VariableDefinition varDef:fd.variableDefinitions) {
                           if (varDef.variableName.equals(varName))
                              return varDef;
                        }
                     }
                  }
               }
            }
            // This is the static field case, where we are matching the modelPrefix.variableName against the incoming typeName
            // the model type is the type of the variable which right now is part of the name - i.e. typeName_instanceId
            else if (StringUtil.equalStrings(prefix,rootName)) {
               TypeDeclaration modelType = model.getUnresolvedModelTypeDeclaration();
               if (modelType != null && modelType.body != null && modelType.typeName != null && varName.startsWith(modelType.typeName)) {
                  for (Statement st:modelType.body) {
                     if (st instanceof FieldDefinition) {
                        FieldDefinition fd = (FieldDefinition) st;
                        for (VariableDefinition varDef:fd.variableDefinitions) {
                           if (varDef.variableName.equals(varName))
                              return varDef;
                        }
                     }
                  }
               }
            }
         }
      }
      return null;
   }

   public String getRegisterInstName() {
      return "sc.sync.SyncManager.registerSyncInst";
   }

   public String getResolveOrCreateInstName() {
      return "sc.sync.SyncManager.resolveOrCreateSyncInst";
   }

   /** Return true for the sync system to look up .class files only.  We only use this optimization when all of the classes are compiled. */
   public boolean useRuntimeResolution() {
      return useRuntimeResolution;
   }

   public void addModel(JavaModel model) {
      if (modelList == null)
         setProperty("modelList", new SemanticNodeList());
      modelList.add(model);
   }

   public static ModelStream convertToModelStream(String layerDef) {
      SCLanguage lang = SCLanguage.getSCLanguage();
      boolean trace = SyncManager.trace;
      long startTime;
      startTime = trace ? System.currentTimeMillis() : 0;
      Object streamRes = lang.parseString(layerDef, lang.modelStream);
      if (streamRes instanceof ParseError) {
         ParseError perror = (ParseError) streamRes;
         System.err.println("*** Failed to parse sync layer def: " + perror.errorStringWithLineNumbers(layerDef));
         return null;
      }
      else {
         ModelStream stream = (ModelStream) ParseUtil.nodeToSemanticValue(streamRes);
         stream.setLayeredSystem(LayeredSystem.getCurrent());

         if (trace && layerDef.length() > 2048)
            System.out.println("Parsed sync layer in: " + StringUtil.formatFloat((System.currentTimeMillis() - startTime)/1000.0) + " secs");
         return stream;
      }
   }

}
