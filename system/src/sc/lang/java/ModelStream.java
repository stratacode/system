/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.BindingContext;
import sc.dyn.DynUtil;
import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.js.JSRuntimeProcessor;
import sc.layer.LayeredSystem;
import sc.layer.SrcEntry;
import sc.obj.ScopeDefinition;
import sc.parser.ParseUtil;
import sc.sync.SyncLayer;
import sc.sync.SyncManager;
import sc.type.CTypeUtil;
import sc.util.StringUtil;

import java.util.HashMap;

/**
 * These can be craeted by parsing the ModelStream grammar node in the JavaLanguage.  Or it can be created in the code from UpdateInstanceInfo, the
 * data structure generated when performing a system update.  In thise case it represents the program model changes made from since the last refresh.
 *
 * When it is parsed, it (today) represents a sync update to/from the client.
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

   public CharSequence convertToJS(String destName, String defaultScope) {
      syncCtx = SyncManager.getSyncContext(destName, defaultScope, true);
      LayeredSystem sys = LayeredSystem.getCurrent();
      JSRuntimeProcessor jsProc = (JSRuntimeProcessor) sys.getRuntime("js");
      if (jsProc == null) {
         System.err.println("*** convertToJS called with no JS runtime present");
         return null;
      }
      return jsProc.processModelStream(this);
   }

   public void updateRuntime(String destName, String defaultScope, boolean resetSync) {
      syncCtx = SyncManager.getSyncContext(destName, defaultScope, true);

      SyncManager.SyncState oldSyncState = SyncManager.getSyncState();
      ParseUtil.initAndStartComponent(this);
      // After we've resolved all of the objects which are referenced on the client, we do a "reset" since this will line us
      // up with the state that's on the client.
      // TODO: maybe the client should send two different layers during a reset - for the committed and uncommited changes.
      // That way, all of the committed changes are applied before the resetSync.
      if (resetSync) {
         SyncManager.sendSync(destName, null, true);
      }
      for (JavaModel model:modelList) {
         if (model.hasErrors()) {
            System.err.println("*** Failed to update runtime for modelStream due to errors in the model");
         }
      }
      SyncManager.setSyncState(SyncManager.SyncState.ApplyingChanges);

      // Queuing up events so they are all fired after the sync has completed
      BindingContext ctx = new BindingContext();
      BindingContext oldBindCtx = BindingContext.getBindingContext();
      BindingContext.setBindingContext(ctx);

      try {
         // Don't need this now that we set syncInProgress.  This breaks down because we need to evaluate all expressions including those which
         // depend on things we haven't created yet.  Instead, we capture the previous values during the initial layer of setX calls.
         //for (JavaModel model:modelList)
         //   model.updatePreviousValues(syncCtx);
         for (JavaModel model:modelList)
            model.updateRuntimeModel(syncCtx);
      }
      finally {
         BindingContext.setBindingContext(oldBindCtx);
         ctx.dispatchEvents(null);
         SyncManager.setSyncState(oldSyncState);
      }
   }

   /** TODO: could relax this dependency so it only uses the class loader for basic object lookup and property setting. */
   public void setLayeredSystem(LayeredSystem sys) {
      system = sys;
      if (sys.hasDynamicLayers())
         useRuntimeResolution = false; // Need to turn off this optimization when we may have dynamic types since there are no .class files for them in general
      if (modelList != null) {
         for (JavaModel model:modelList) {
            // When we have a sync context, it does the resolving.  We are not updating types, but instances.
            // When there's no sync context, we're processing a model stream that was generated during the refreshSystem
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
            globalModel.setLayeredSystem(system);
            globalModel.setLayer(system.buildLayer);
            globalModel.addSrcFile(new SrcEntry(system.buildLayer, SyncLayer.GLOBAL_TYPE_NAME, SyncLayer.GLOBAL_TYPE_NAME));
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
         Object newType = system.getRuntimeTypeDeclaration(typeTypeName);
         if (newType != null)
            type = newType;
         if (type == null && currentPackage != null) {
            newType = system.getRuntimeTypeDeclaration(CTypeUtil.prefixPath(currentPackage, typeTypeName));
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
      Object inst = syncCtx.getObjectByName(name, unwrap);
      String fullPathName = null;
      if (inst == null && currentPackage != null)
         inst = syncCtx.getObjectByName(fullPathName = CTypeUtil.prefixPath(currentPackage, name), unwrap);
      if (inst == null) {
         inst = ScopeDefinition.resolveName(name, true);
         if (inst == null && fullPathName != null) {
            inst = ScopeDefinition.resolveName(fullPathName, true);
         }
      }
      if (inst != null) {
         return inst;
      }
      return null;
   }

   public VariableDefinition resolveFieldWithName(String pkgName, String typeName) {
      if (modelList == null)
         return null;
      String varName = CTypeUtil.getClassName(typeName);
      String rootName = CTypeUtil.getPackageName(typeName);
      if (rootName != null) {
         for (JavaModel model:modelList) {
            if (StringUtil.equalStrings(model.getPackagePrefix(), pkgName)) {
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
}
