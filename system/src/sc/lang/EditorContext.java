/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.bind.IListener;
import sc.dyn.DynUtil;
import sc.lang.sc.PropertyAssignment;
import sc.lang.sc.SCModel;
import sc.layer.*;
import sc.obj.GlobalScopeDefinition;
import sc.obj.Remote;
import sc.parser.*;
import sc.sync.SyncManager;
import sc.sync.SyncOptions;
import sc.type.CTypeUtil;
import sc.type.TypeUtil;
import sc.util.FileUtil;
import sc.util.IMessageHandler;
import sc.util.MessageHandler;
import sc.util.StringUtil;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.sc.OverrideAssignment;
import sc.lang.java.*;
import sc.type.IBeanMapper;

import java.io.File;
import java.io.StringReader;
import java.util.*;

public class EditorContext extends ClientEditorContext {
   static IBeanMapper canUndoProperty = TypeUtil.getPropertyMapping(EditorContext.class, "canUndo");
   static IBeanMapper canRedoProperty = TypeUtil.getPropertyMapping(EditorContext.class, "canRedo");
   static IBeanMapper needsSaveProperty = TypeUtil.getPropertyMapping(EditorContext.class, "needsSave");
   static IBeanMapper errorsChangedProperty = TypeUtil.getPropertyMapping(EditorContext.class, "errorsChanged");

   static protected CommandSCLanguage cmdlang = CommandSCLanguage.INSTANCE;

   static boolean syncPeerSystems = true;

   /**
    * Lets frameworks replace the code which processes a command statement.  Specifically you can ensure all commands are processed
    * on a specific thread, e.g. the swing event dispatcher thread
    */
   public static StatementProcessor statementProcessor = new StatementProcessor();

   public ExecutionContext execContext = new ExecutionContext();

   HashMap<String,ArrayList<IEditorSession>> editSessions = new HashMap<String,ArrayList<IEditorSession>>();

   /** The path name of the current file - currently only used by the command line interpreter though maybe should be tied to current package? */
   public String path;

   public static final String NO_CURRENT_OBJ_SENTINEL = "<NoSelectedObj>";

   // Since we rebuild the class view structure, we cannot store the instances selected for a given type
   // in the view itself.  Instead, we set up a binding so that the instance is kept in a hashmap based on
   // the type name.
   //
   // TODO: need to make the references to these instances "weak" so we don't hang onto them
   HashMap<String,Object> selectedInstances = new HashMap<String,Object>();

   public Object getDefaultCurrentObj(Object type) {
      Object obj = selectedInstances.get(ModelUtil.getTypeName(type));
      if (obj == null) {
         // Also check the sub-types.  When inherit is true in particular, it makes sense to see if we've navigated
         // to a type from a sub-type.  If so, the current instance of that sub-type is the most relevant one for this
         // base type.
         if (type instanceof TypeDeclaration) {
            Iterator<TypeDeclaration> subTypes = system.getSubTypesOfType((TypeDeclaration) type);
            while (obj == null && subTypes.hasNext()) {
               TypeDeclaration subType = subTypes.next();
               String subTypeName = subType.getFullTypeName();
               obj = selectedInstances.get(subTypeName);
            }
         }
      }

      // By-passes any default selection
      if (obj == NO_CURRENT_OBJ_SENTINEL)
         return null;

      return obj;
   }

   public Object getCurrentObject() {
      Object obj = execContext.getCurrentObject();
      return obj;
   }

   public void setDefaultCurrentObj(Object type, Object inst) {
      // Keep the execContext in sync with what's done in the UI.  So we are editing the same instance
      if (currentTypes.size() > 0 && type != null && ModelUtil.sameTypes(type, currentTypes.get(currentTypes.size()-1)) && inst != execContext.getCurrentObject()) {
         execContext.popCurrentObject();
         execContext.pushCurrentObject(inst);
      }
      selectedInstances.put(ModelUtil.getTypeName(type), inst);
   }

   public List<InstanceWrapper> getInstancesOfType(Object type, int max, boolean addNull) {
      if (type instanceof ClientTypeDeclaration)
         type = ((ClientTypeDeclaration) type).getOriginal();
      ArrayList<InstanceWrapper> ret = new ArrayList<InstanceWrapper>();
      if (type == null)
         return ret;
      String typeName = ModelUtil.getTypeName(type);
      Iterator it = system.getInstancesOfTypeAndSubTypes(typeName);
      int i = 0;
      // Add a null entry at the front to represent the <type> selection
      if (addNull)
         ret.add(new InstanceWrapper(this, null, typeName));
      while (i < max && it.hasNext()) {
         Object inst = it.next();
         ret.add(new InstanceWrapper(this, inst, typeName));
      }

      /*
      if (ret.size() == 1 && ModelUtil.getEnclosingType(type) == null && ModelUtil.isObjectType(type)) {
         ret.add(new InstanceWrapper(this, ModelUtil.getRuntimeType(type) != null, typeName)); // A dummy wrapper which creates the instance when it is selected
      }
      */

      if (ModelUtil.isEnum(type)) {
         ret.add(new InstanceWrapper(this, ModelUtil.getRuntimeEnum(type), typeName));
      }
      return ret;
   }


   public EditorContext(LayeredSystem sys) {
      system = sys;
      execContext.system = sys;
      updateLayerState();
   }

   public void updateLayerState() {
      currentLayer = system.lastLayer;
      layerPrefix = currentLayer != null ? currentLayer.packagePrefix : null;
   }

   synchronized void clearPendingModel() {
      // Can only save once we have a type
      if (pendingModel != null && pendingModel.getSrcFile() != null) {
         Layer lyr = pendingModel.getLayer();
         if (lyr.tempLayer) {
            lyr.model.saveModel();
            lyr.tempLayer = false;
         }
      }
      // TODO: save it in the current edit layer unless that is an in-memory layer
      pendingModel = null;
      execContext.resolver = null;
   }

   synchronized void addChangedModel(JavaModel model) {
      model.validateSavedModel(false);
      // Notify code that this model has changed by sending a binding event.
      model.markChanged();

      if (!changedModels.contains(model)) {
         changedModels.add(model);
         model.unsavedModel = true;
         if (changedModels.size() == 1)
            Bind.sendEvent(IListener.VALUE_CHANGED, this, needsSaveProperty);
      }
   }

   @Remote(remoteRuntimes="js")
   public synchronized void save() {
      for (JavaModel model:changedModels) {
         if (model.getSrcFile() != null) {
            model.saveModel();
            model.layer.addNewSrcFile(model.getSrcFile(), true);
            model.unsavedModel = false;
         }
      }
      changedModels.clear();
      Bind.sendEvent(IListener.VALUE_CHANGED, this, needsSaveProperty);
   }

   void saveModel(JavaModel model) {
      model.saveModel();
      model.layer.addNewSrcFile(model.getSrcFile(), true);
   }

   protected JavaModel getModel() {
      if (pendingModel == null) {
         pendingModel = new SCModel();
         pendingModel.setLayeredSystem(system);
         pendingModel.setLayer(currentLayer);
         execContext.resolver = pendingModel;
      }
      return pendingModel;
   }

   void refreshEditSessions() {
      for (JavaModel model:changedModels) {
         ArrayList<IEditorSession> sessions = editSessions.get(model.getModelTypeDeclaration().getFullTypeName());
         if (sessions != null && sessions.size() > 0) {
            for (IEditorSession session : sessions)
               session.refreshModel(model);
         }
      }
   }

   public void addEditSession(String sessionName, IEditorSession session) {
      ArrayList<IEditorSession> sessions = editSessions.get(sessionName);
      if (sessions == null) {
         sessions = new ArrayList<IEditorSession>();
         editSessions.put(sessionName, sessions);
      }
      sessions.add(session);
   }

   public void removeEditSession(String sessionName, IEditorSession session) {
      ArrayList<IEditorSession> sessions = editSessions.get(sessionName);
      if (sessions == null)
         System.err.println("*** Can't find edit session to remove");
      else
         sessions.remove(session);
   }

   @Bindable(manual=true)
   public void setCurrentLayer(Layer newLayer) {
      // TODO: should we try to preserve imports here and only switch the layer of the pending model if there's no type?
      if (currentLayer != newLayer) {
         clearPendingModel();
         currentLayer = newLayer;
         if (currentLayer != null)
            layerPrefix = currentLayer.packagePrefix;
         else
            layerPrefix = null;
         Bind.sendChangedEvent(this, "currentLayer");
      }
   }

   public Layer getCurrentLayer() {
      return currentLayer;
   }

   ArrayList<IUndoOp> undoStack = new ArrayList<IUndoOp>();

   int lastUndone = -1;

   public void addOp(IUndoOp op) {
      undoStack.add(op);
      lastUndone = undoStack.size();
      undoRedoChanged();
   }

   @Remote(remoteRuntimes="js")
   public void undo() {
      if (getCanUndo()) {
         lastUndone = lastUndone - 1;
         undoStack.get(lastUndone).undo();
         undoRedoChanged();
      }
      else {
         System.err.println("Nothing undo");
      }
   }

   @Remote(remoteRuntimes="js")
   public void redo() {
      if (getCanRedo()) {
         undoStack.get(lastUndone).redo();
         lastUndone = lastUndone + 1;
         undoRedoChanged();
      }
      else
         System.err.println("Nothing undo");
   }

   void undoRedoChanged() {
      Bind.sendEvent(IListener.VALUE_CHANGED, this, canUndoProperty);
      Bind.sendEvent(IListener.VALUE_CHANGED, this, canRedoProperty);
   }

   @Bindable(manual=true)
   public boolean getCanRedo() {
      return lastUndone >= 0 && lastUndone < undoStack.size();
   }

   public void setCanRedo(boolean val) {
      throw new UnsupportedOperationException();
   }

   @Bindable(manual=true)
   public boolean getCanUndo() {
      return lastUndone > 0;
   }

   public void setCanUndo(boolean val) {
      throw new UnsupportedOperationException();
   }

   public void restart() {
      save();
      system.restart();
   }

   class AddStatementOp implements IUndoOp {
      Object type;
      Statement newStatement;
      Statement oldStatement;

      public AddStatementOp (Object type, Statement newSt, Statement repl) {
         this.type = type;
         this.newStatement = newSt;
         this.oldStatement = repl;
      }

      public void undo() {
         if (oldStatement == null)
            removeStatement(type, newStatement);
         else
            addStatement(type, oldStatement, false);
      }

      public void redo() {
         addStatement(type, newStatement, false);
      }
   }

   class RemoveStatementOp extends AddStatementOp {
      public RemoveStatementOp(Object type, Statement toRem) {
         super(type, toRem, null);
      }

      // Switch these up so undo does the add and redo does the remove
      public void undo() {
         super.redo();
      }

      public void redo() {
         super.undo();
      }
   }

   class RemoveVariableOp implements IUndoOp {
      FieldDefinition field;
      VariableDefinition var;
      int ix;
      public RemoveVariableOp(FieldDefinition field, VariableDefinition var, int ix) {
         this.field = field;
         this.var = var;
         this.ix = ix;
      }

      // Switch these up so undo does the add and redo does the remove
      public void undo() {
         field.addVariable(ix, var, execContext, true);
      }

      public void redo() {
         field.removeVariable(var, execContext, true);
      }
   }

   class ValueChangedOp implements IUndoOp {
      Object type, inst, elem, newElem;
      String text, oldText;
      boolean updateType, updateInstances, valueIsExpr;

      public ValueChangedOp (Object type, Object inst, Object elem, Object newElem, String text, String oldText, boolean updateType, boolean updateInstances, boolean valueIsExpr) {
         this.type = type;
         this.inst = inst;
         this.elem = elem;
         this.newElem = newElem;
         this.text = text;
         this.oldText = oldText;
         this.updateType = updateType;
         this.updateInstances = updateInstances;
         this.valueIsExpr = valueIsExpr;
      }

      public void undo() {
         // If we either updated the element or replaced it, just replace it back
         if (newElem == null || ModelUtil.getEnclosingType(newElem) == ModelUtil.getEnclosingType(elem))
            setElementValueOnPeers(type, inst, elem, oldText, updateType, updateInstances, valueIsExpr, false);
         // If we had to create a new node, remove it then reset it.
         else {
            removeProperty((BodyTypeDeclaration) type, (JavaSemanticNode) newElem, false);
         }
      }

      public void redo() {
         setElementValueOnPeers(type, inst, elem, text, updateType, updateInstances, valueIsExpr, false);
      }
   }

   class AddLayersOp implements IUndoOp {
      String[] layerNames;
      boolean isDynamic;

      public AddLayersOp(String[] lns, boolean dyn) {
         layerNames = lns;
         isDynamic = dyn;
      }

      public void undo() {
         removeLayers(layerNames);
      }

      public void redo() {
         addLayers(layerNames, isDynamic, false);
      }
   }

   class RemoveLayersOp implements IUndoOp {
      String[] layerNames;
      boolean isDynamic;

      public RemoveLayersOp(List<Layer> lys) {
         layerNames = new String[lys.size()];
         int i = 0;
         isDynamic = lys.get(0).dynamic;
         for (Layer l:lys) {
            layerNames[i++] = l.getLayerName();
            isDynamic |= l.dynamic;
         }
      }

      public void undo() {
         addLayers(layerNames, isDynamic, false);
      }

      public void redo() {
         removeLayers(layerNames);
      }
   }

   class UndoRemoveFileOp implements IUndoOp {
      SrcEntry oldFile;
      String backupFile;
      BodyTypeDeclaration type;

      public UndoRemoveFileOp (SrcEntry oldF, String backupF) {
         this.oldFile = oldF;
         this.backupFile = backupF;
      }

      public void undo() {
         FileUtil.renameFile(backupFile, oldFile.absFileName);
         system.refresh(oldFile, execContext);
         // Get hold of the type in case we need to redo
         type = system.getSrcTypeDeclaration(oldFile.getTypeName(), oldFile.layer.getNextLayer(), true);
      }

      public void redo() {
         removeType(type, false);
      }
   }

   public String propertyValueString(Object type, Object instance, Object elem) {
      ExecutionContext ctx = new ExecutionContext(system);
      try {
         if (instance != null)
            ctx.pushCurrentObject(instance);
         else
            ctx.pushStaticFrame(type);
         return instance == null ?
                 ModelUtil.elementValueString(elem) :
                 ModelUtil.instanceValueString(instance, elem, ctx);
      }
      catch (RuntimeException exc) {
         System.err.println("*** error getting property value: " + exc.toString());
         exc.printStackTrace();
         return "<error: " + exc.toString() + ">";
      }
      finally {
         if (instance != null)
            ctx.popCurrentObject();
         else
            ctx.popStaticFrame();
      }
   }

   public String setElementValueOnPeers(Object type, Object inst, Object elem, String text, boolean updateType, boolean updateInstances, boolean valueIsExpr, boolean addOp) {
      String oldText = propertyValueString(type, inst, elem);
      String error = null;
      Object newElem = null;
      system.resetBuild(true);
      try {
         newElem = ModelUtil.setElementValue(type, inst, elem, text, updateType, updateInstances, valueIsExpr);
         if (syncPeerSystems && type instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration td = (BodyTypeDeclaration) type;
            LayeredSystem sys = td.getLayeredSystem();
            if (sys.peerSystems != null) {
               for(LayeredSystem peer:sys.peerSystems) {
                  BodyTypeDeclaration peerType = peer.getSrcTypeDeclaration(td.getFullTypeName(), null, true);
                  if (peerType != null && peerType.getLayer().getLayerName().equals(td.getLayer().getLayerName())) {
                     String propName = ModelUtil.getPropertyName(elem);
                     Object peerElem = peerType.declaresMember(propName, JavaSemanticNode.MemberType.PropertyAnySet, null, null);
                     if (peerElem != null) {
                        Object newPeerElem = ModelUtil.setElementValue(peerType, inst, peerElem, text, updateType, updateInstances, valueIsExpr);
                        typeChanged(peerType);
                     }
                     else
                        System.err.println("*** Unable to find peer element with property: " + propName);
                  }
               }
            }
         }
      }
      catch (IllegalArgumentException exc) {
         if (system.options.verbose) {
            System.err.println("*** Error updating property: " + exc);
            exc.printStackTrace();
         }
         error = exc.getMessage();
      }

      typeChanged(type);

      if (addOp) {
         addOp(new ValueChangedOp(type, inst, elem, newElem, text, oldText, updateType, updateInstances, valueIsExpr));
      }
      return error;
   }

   public String setElementValue(Object type, Object inst, Object elem, String text, boolean updateType, boolean updateInstances, boolean valueIsExpr) {
      return setElementValueOnPeers(type, inst, elem, text, updateType, updateInstances, valueIsExpr, true);
   }

   public String addProperty(Object currentType, String propType, String propName, String op, String propValue) {
      if (!(currentType instanceof BodyTypeDeclaration)) {
         return "Unable to add properties to compiled types";
      }

      Object existingType = ModelUtil.getInnerType(currentType, propName, null);
      if (existingType != null) {
         if (!StringUtil.isEmpty(propType) || !StringUtil.isEmpty(propValue))
            return "Object named: " + propName + " already exists in type: " + currentType;

         // Lazily create a modify object even from add property since it's a pain to switch modes
         return addInnerType("Object", currentType, propName, null);
      }

      SCLanguage lang = SCLanguage.getSCLanguage();
      Parselet expr = lang.optExpression;
      Expression initializer;
      Object res = StringUtil.isEmpty(propValue) ? null : lang.parseString(propValue, expr);
      if (res instanceof ParseError) {
         return "Bad value: " + res.toString();
      }
      else if (res == null)
         initializer = null;
      else
         initializer = (Expression) ParseUtil.nodeToSemanticValue(res);

      if (propType != null && propType.trim().length() > 0) {
         JavaType propertyType;
         res = lang.parseString(propType, lang.type);
         if (res instanceof ParseError) {
            return "Bad value: " + res.toString();
         }
         else if (res == null)
            return "No type specified";

         propertyType = (JavaType) ParseUtil.nodeToSemanticValue(res);

         FieldDefinition field = FieldDefinition.createFromJavaType(propertyType, propName, op, initializer);

         return addStatement(currentType, field, true);
      }
      else if (ModelUtil.definesMember(currentType, propName, JavaSemanticNode.MemberType.PropertyAnySet, null, null, null) != null) {
         PropertyAssignment pa = initializer == null ? OverrideAssignment.create(propName) : PropertyAssignment.create(propName, initializer, op);
         return addStatement(currentType, pa, true);
      }
      else {
         return "No property named: " + propName + " in type: " + currentType + "  To create a new property, provide a type";
      }
   }

   public String addInnerType(String mode, Object currentType, String name, String extType) {
      Object existingType = ModelUtil.getInnerType(currentType, name, null);

      // Turn this into a modify type
      if (existingType != null)
         mode = null;
      Object cdObj = createClass(mode, name, extType);

      if (cdObj instanceof String)
         return (String) cdObj;
      TypeDeclaration cd = (TypeDeclaration) cdObj;
      String err = addStatement(currentType, cd, true);
      if (err == null) {
         Layer currentLayer = ModelUtil.getLayerForType(system, currentType);
         system.addTypeByName(currentLayer, cd.getFullTypeName(), cd, currentLayer.getNextLayer());
      }
      return err;
   }

   public void removeInnerType(BodyTypeDeclaration enclType, BodyTypeDeclaration childType) {
      enclType.removeBodyStatement(childType, execContext, true, null);
   }

   public void removeType(BodyTypeDeclaration type, boolean addOp) {
      BodyTypeDeclaration enclType = type.getEnclosingType();
      if (enclType == null) {
         JavaModel model = type.getJavaModel();
         SrcEntry srcFile = model.getSrcFile();
         Date now = new Date();

         String backupFile = FileUtil.addExtension(FileUtil.concat(system.getUndoDirPath(), FileUtil.removeExtension(srcFile.absFileName), "-" + now.toString()), FileUtil.getExtension(srcFile.absFileName));
         // Move the file into the undo directory
         FileUtil.renameFile(srcFile.absFileName, backupFile);

         system.removeModel(model, true);

         if (addOp) {
            // Create an UndoRemoveFileOp which can restore the file from the undo directory
            addOp(new UndoRemoveFileOp(srcFile, backupFile));
         }
      }
      else {
         removeInnerType(enclType, type);

         if (addOp) {
            addOp(new RemoveStatementOp(enclType, type));
            typeChanged(type);
         }
      }
   }

   Object parseExtType(String extType) {
      SCLanguage lang = SCLanguage.getSCLanguage();

      JavaType extJavaType = null;
      if (extType != null && extType.trim().length() > 0) {
         Object res = lang.parseString(extType, lang.type);
         if (res instanceof ParseError) {
            return "Bad value: " + res.toString();
         }
         else if (res == null)
            return "No type specified";

         extJavaType = (JavaType) ParseUtil.nodeToSemanticValue(res);
      }
      return extJavaType;
   }

   Object createClass(String mode, String name, String extType) {
      String operator = mode == null ? null : mode.toLowerCase();

      Object extObj = parseExtType(extType);
      JavaType extJavaType = null;
      if (extObj instanceof JavaType)
         extJavaType = (JavaType) extObj;
      else if (extObj != null)
         return extObj;

      if (operator == null)
         return ModifyDeclaration.create(name, extJavaType);
      else
         return ClassDeclaration.create(operator, name, extJavaType);
   }

   public Object addTopLevelType(String mode, String currentPackage, Layer layer, String name, String extType) {
      if (layer == null) {
         int ix = system.layers.size()-1;
         if (ix < 0)
            return "Create a layer before adding a type.";

         for (Layer l = system.layers.get(ix); ix >= 0; ) {
            if (currentPackage.startsWith(l.packagePrefix)) {
               layer = l;
               break;
            }
            ix--;
            l = system.layers.get(ix);
         }
         if (layer == null)
            return "No layer found matching package: " + currentPackage + ". Create a layer that includes this package to add this type.";
      }

      String layerPrefix = layer.packagePrefix;
      if (!currentPackage.startsWith(layerPrefix))
         return "Bad package for layer!";
      String relType = currentPackage.equals(layerPrefix) ? null : layerPrefix.length() == 0 ? currentPackage : currentPackage.substring(layerPrefix.length()+1);
      String relPath = relType == null ? null : relType.replace(".", FileUtil.FILE_SEPARATOR);

      String relFile = FileUtil.concat(relPath, name + "." + SCLanguage.STRATACODE_SUFFIX);
      String absFile = FileUtil.concat(layer.getLayerPathName(), relFile);
      File abs = new File(absFile);
      if (abs.canRead())
         return "Type named: " + name + " already exists for layer: " + layer;

      Object existingType = system.getSrcTypeDeclaration(CTypeUtil.prefixPath(currentPackage, name), layer.getNextLayer(), true);
      TypeDeclaration td;
      if (existingType != null) {
         // Just create a modify if there's already a type by tbat name... should we ensure mode matches the existing type?
         mode = null;
      }

      Object cdObj = createClass(mode, name, extType);
      if (cdObj instanceof String)
         return cdObj;
      td = (TypeDeclaration) cdObj;

      JavaModel newModel = new SCModel();
      newModel.setLayeredSystem(system);
      newModel.setLayer(layer);
      newModel.addSrcFile(new SrcEntry(layer, absFile, relFile));
      newModel.addTypeDeclaration(td);

      // Need to generate the code for this the first time
      SCLanguage.getSCLanguage().generate(newModel, false);

      addChangedModel(newModel);

      if (!newModel.isStarted()) // make sure the model
         ParseUtil.initAndStartComponent(newModel);

      // Make sure others can resolve this new type
      system.addNewModel(newModel, null, execContext, null, false, true);

      system.notifyModelListeners(newModel);

      return td;
   }

   public String removeStatement(Object currentType, Statement st) {
      BodyTypeDeclaration currentTD = (BodyTypeDeclaration) currentType;
      ExecutionContext ctx = new ExecutionContext(system);
      ctx.pushStaticFrame(currentType);
      currentTD.removeBodyStatement(st, ctx, true, null);
      ctx.popStaticFrame();
      typeChanged(currentType);

      return null;
   }

   public String addStatement(Object currentType, Statement st, boolean addOp) {
      BodyTypeDeclaration currentTD = (BodyTypeDeclaration) currentType;
      JavaModel model = currentTD.getJavaModel();

      MessageHandler handler = new MessageHandler();
      IMessageHandler oldHandler = model.getErrorHandler();
      ExecutionContext ctx = new ExecutionContext(system);
      Statement replaced = null;
      try {
         model.setErrorHandler(handler);

         ctx.pushStaticFrame(currentType);
         if (st instanceof PropertyAssignment) {
            Object replacedObj = currentTD.updateProperty((PropertyAssignment) st, ctx, true, null);
            if (replacedObj instanceof VariableDefinition)
               replaced = ((VariableDefinition) replacedObj).getDefinition();
            else
               replaced = (Statement) replacedObj;
         }
         else
            replaced = currentTD.updateBodyStatement(st, ctx, true, null);
         if (handler.err != null)
            return handler.err;
      }
      finally {
         ctx.popStaticFrame();
         model.setErrorHandler(oldHandler);
      }

      typeChanged(currentType);

      if (addOp)
         addOp(new AddStatementOp(currentType, replaced, st));

      return null;
   }


   public Layer createLayer(String layerName, String layerPackage, String[] extendsNames, boolean isDynamic, boolean isPublic, boolean isTransparent, boolean addOp) {
      Layer layer = system.createLayer(layerName, layerPackage, extendsNames, isDynamic, isPublic, isTransparent, true, true);
      if (addOp) {
         addOp(new AddLayersOp(Collections.singletonList(layerName).toArray(new String[1]), isDynamic));
      }
      return layer;
   }

   public void addLayers(String[] addNames, boolean isDynamic, boolean addOp) {
      system.addLayers(addNames, isDynamic, execContext);
      if (addOp) {
         addOp(new AddLayersOp(addNames, isDynamic));
      }
   }

   public void removeLayers(String[] layerNames) {
      ArrayList<Layer> layers = new ArrayList<Layer>();
      for (int i = 0; i < layerNames.length; i++)
         layers.add(system.getLayerByDirName(layerNames[i]));
      removeLayers(layers);
   }

   public void removeLayers(List<Layer> layers) {
      system.removeLayers(layers, execContext);
   }

   public void removeLayer(Layer layer, boolean addOp) {
      system.removeLayer(layer, execContext);

      if (addOp) {
         addOp(new RemoveLayersOp(Collections.singletonList(layer)));
      }
   }

   public void removeProperty(BodyTypeDeclaration td, JavaSemanticNode prop, boolean addOp) {
      Statement origStatement = null;
      boolean removeVar = false;
      if (prop instanceof PropertyAssignment) {
         origStatement = (PropertyAssignment) prop;
         td.removeBodyStatement(origStatement, execContext, true, null);

         if (addOp)
            addOp(new RemoveStatementOp(td, origStatement));
      }
      else if (prop instanceof VariableDefinition) {
         VariableDefinition varDef = (VariableDefinition) prop;
         FieldDefinition field = (FieldDefinition) varDef.getDefinition();
         origStatement = (FieldDefinition) field.deepCopy(ISemanticNode.CopyNormal, null);
         if (field.variableDefinitions.size() == 1) {
            td.removeBodyStatement(field, execContext, true, null);
            if (addOp)
               addOp(new RemoveStatementOp(td, origStatement));
         }
         else {
            int ix = field.variableDefinitions.indexOf(varDef);
            field.removeVariable(varDef, execContext, true);
            removeVar = true;
            if (addOp)
               addOp(new RemoveVariableOp(field, varDef, ix));
         }
      }
      else  {
         System.err.println("*** Unrecognized args to remove property");
         return;
      }
      typeChanged(td);
   }

   // TODO: should we be using an event listener mechanism instead of this manual call in EditorContext?
   public void typeChanged(Object typeObj) {
      if (typeObj instanceof BodyTypeDeclaration) {
         addChangedModel(((BodyTypeDeclaration) typeObj).getJavaModel());
      }
   }

   public void setPendingModelType(BodyTypeDeclaration newType) {
      pendingModel = newType.getJavaModel();
   }

   void refreshModel(JavaModel model) {
      if (model == null)
         System.out.println("No current type to refresh");
      else {
         if (!model.isStarted())
            ParseUtil.initAndStartComponent(model);

         if (model.hasErrors()) {
            System.out.println("Model has errors - refresh cancelled");
            return;
         }

         SrcEntry srcEnt = model.getSrcFile();
         if (srcEnt == null) {
            // The pending model has not been assigned a type yet
            system.refreshRuntimes(true);
            return;
         }
         changedModels.remove(model);
         JavaModel origModel = model;
         model.unsavedModel = false;
         SrcEntry srcFile = model.getSrcFile();
         if (srcFile.canRead()) {
            // TODO: we are using execContext here even if model != pendingModel?   Shouldn't we be building an execContext from model
            // but need to take into account the current object if one has been selected.
            Object modelObj = system.refresh(srcFile, execContext);
            if (modelObj instanceof JavaModel) {
               JavaModel newModel = (JavaModel) modelObj;
               if (!newModel.hasErrors()) {
                  if (this instanceof AbstractInterpreter)
                     newModel.setCommandInterpreter((AbstractInterpreter) this);

                  Object currentObj = execContext.getCurrentObject();
                  Object currentType = execContext.getCurrentStaticType();
                  if (currentObj == null) {
                     if (currentType != null) {
                        execContext.popStaticFrame();
                        execContext.pushStaticFrame(newModel.findTypeDeclaration(ModelUtil.getTypeName(currentType), false, true));
                     }
                  }
                  // Remap the types in the current types list as well.  Maybe these could be folded into frames in
                  // exec context?
                  for (int i = 0; i < currentTypes.size(); i++) {
                     currentType = currentTypes.get(i);
                     currentTypes.set(i, system.getSrcTypeDeclaration(ModelUtil.getTypeName(currentType), null, false));
                  }
                  if (pendingModel == origModel)
                     pendingModel = newModel;

                  newModel.refreshBaseLayers(execContext);
               }
               else {
                  errorModels.put(srcFile, newModel);
               }
            }
            else if (modelObj instanceof ModelParseError) {
               String errorStr = ((ModelParseError)modelObj).parseError.errorStringWithLineNumbers(new File(srcFile.absFileName));
               errorModels.put(srcFile, errorStr);
            }
         }
      }
   }

   public boolean modelChanged(JavaModel model) {
      return changedModels.contains(model);
   }

   public LayeredSystem.SystemRefreshInfo refresh() {
      return system.refreshRuntimes(true);
   }

   void updateCurrentModelStale() {
      if (system.staleCompiledModel && !currentModelStale) {
         System.out.println("* Warning: changes made require an application restart");
         // TODO: prompt for [y] and run cmd.restart if 'y'
         currentModelStale = true;
      }
   }

   public boolean isChangedModel(Layer layer, String relFileName) {
      for (JavaModel model:changedModels) {
         SrcEntry ent = model.getSrcFile();
         if (ent != null && model.layer == layer && ent.relFileName.equals(relFileName))
            return true;
      }
      return false;
   }

   @Bindable(manual=true)
   public boolean getNeedsSave() {
      return changedModels.size() > 0;
   }

   // Needed for sync
   public void setNeedsSave(boolean x) {
      throw new UnsupportedOperationException();
   }


   public String getImportedPropertyType(Object type) {
      if (type == null)
         return null;
      Object propType = ModelUtil.getPropertyType(type);
      Object rootType = ModelUtil.getRootType(propType);
      String rootName = ModelUtil.getTypeName(rootType);

      Object curType = getCurrentType();
      if (system.isImported(rootName))
         rootName = CTypeUtil.getClassName(rootName);

      // Just the leaf if we're in the context of the class (should really check all types in the enclosing type hierarchy?)
      if (ModelUtil.sameTypes(curType, propType) || ModelUtil.sameTypes(curType, rootType))
         return CTypeUtil.getClassName(ModelUtil.getTypeName(propType));

      if (ModelUtil.sameTypes(rootType, propType))
         return rootName;
      return CTypeUtil.prefixPath(rootName, CTypeUtil.getClassName(ModelUtil.getInnerTypeName(propType)));
   }


   public String[] validateExtends(String exts) {
      exts = exts.replace(',', ' '); // Allow comma separators ala extends itself
      if (exts.length() == 0)
         return null;
      exts = exts.trim();
      String[] arr = StringUtil.split(exts, ' ');
      ArrayList<String> list = new ArrayList<String>(arr.length);
      for (int i = 0; i < arr.length; i++) {
         String str = arr[i].trim();
         if (str.length() == 0)
            continue;
         else {
            str = LayerUtil.getLayerTypeName(validateLayerPath(str));
            File defFile = system.getLayerFile(str);
            if (defFile == null) {
               throw new IllegalArgumentException("Invalid layer name - no layer definition file at: " + defFile);
            }
         }

         list.add(str);
      }
      if (list.size() == 0)
         return null;
      return list.toArray(list.toArray(new String[list.size()]));
   }

   public String validateNewLayerPath(String str) {
      String res = validateLayerPath(str);
      String pathName = FileUtil.concat(system.getNewLayerDir(), res);
      String baseName = FileUtil.getFileName(pathName) + "." + SCLanguage.STRATACODE_SUFFIX;
      pathName = FileUtil.concat(pathName, baseName);
      if (new File(pathName).canRead())
         throw new IllegalArgumentException("Layer already exists: " + pathName);
      return res;
   }

   public String validateIdentifier(String id) {
      JavaLanguage jl;
      jl = JavaLanguage.INSTANCE;
      id = id.trim();
      Object res = jl.parseString(id, jl.qualifiedIdentifier);
      if (res instanceof ParseError)
         throw new IllegalArgumentException("Not a valid identifier: " + id + " :" + res);
      return id;
   }

   public String validateLayerPath(String str) {
      String dotName = str.replace(FileUtil.FILE_SEPARATOR, ".");
      if (FileUtil.FILE_SEPARATOR_CHAR != '/')
         dotName = dotName.replace('/', '.');
      dotName = validateIdentifier(dotName);
      // Allows either "." or file separator in the names
      return LayerUtil.fixLayerPathName(dotName);
   }

   void doRefresh() {
      LayeredSystem.SystemRefreshInfo info = refresh();
      List<Layer.ModelUpdate> changedModels = info.changedModels;
      if (changedModels != null) {
         for (Layer.ModelUpdate modelUpdate:changedModels) {
            Object modelObj = modelUpdate.changedModel;
            if (modelObj != null) { // Could not parse the model
               if (modelObj instanceof ModelParseError) {
                  ModelParseError err = (ModelParseError) modelObj;
                  String errorStr = err.parseError.errorStringWithLineNumbers(new File(err.srcFile.absFileName));
                  errorModels.put(err.srcFile, errorStr);
               }
               else if (modelObj instanceof JavaModel) {
                  JavaModel model = (JavaModel) modelObj;
                  if (model.hasErrors()) {
                     errorModels.put(model.getSrcFile(), model);
                     MemoryEditSession sess = memSessions.get(model.getSrcFile());
                     if (sess != null && sess.model != null)
                        sess.model.markChanged();
                  }
                  else {
                     memSessions.remove(model.getSrcFile());
                     errorModels.remove(model.getSrcFile());
                  }
               }
               setErrorsChanged(errorsChanged + 1);
            }
            else if (modelUpdate.removed) {

            }
         }
      }
   }

   public void commitMemorySessionChanges() {
      // For now, just save all of the files, then refresh from the files.  We could only refresh the memory models but
      // that's a lot more code.
      // TODO: deal with file write errors here
      for (MemoryEditSession mes:memSessions.values()) {
         mes.saved = true;
         mes.model.saveModelTextToFile(mes.text);
      }
      doRefresh();

      if (memSessions.size() == 0)
         setMemorySessionChanged(false);
   }

   private static void convertCollectorToCandidates(Set<String> collector, List<String> candidates) {
      candidates.addAll(collector);
      Collections.sort(candidates);
      int sz = candidates.size();
      if (sz == 0)
         return;

      String last = candidates.get(0);
      for (int i = 1; i < sz; i++) {
         String next = candidates.get(i);
         if (last.equals(next)) {
            candidates.remove(i);
            i--;
            sz--;
         }
         last = next;
      }
   }

   public int complete(String command, int cursor, List<String> candidates) {
      return complete(command, cursor, candidates, cmdlang.completionCommands, null, null);
   }

   public int complete(String command, int cursor, List<String> candidates, String ctxText, JavaModel fileModel) {
      return complete(command, cursor, candidates, cmdlang.completionCommands, ctxText, fileModel);
   }

   public int completeType(String command, List<String> candidates) {
      int relPos = complete(command, 0, candidates, null, null);
      if (candidates.size() == 0) {
         List<BodyTypeDeclaration> res = system.findTypesByRootMatchingPrefix(command);
         if (res != null) {
            BodyTypeDeclaration last = null;
            for (int i = 0; i < res.size(); i++) {
               BodyTypeDeclaration btd = res.get(i);
               // Eliminate dupes
               if (last == null || (last != btd && !last.getFullTypeName().equals(btd.getFullTypeName())))
                  candidates.add(btd.getFullTypeName());
               last = btd;
            }
         }
         relPos = 0;
      }
      return relPos;
   }

   static String getStatementCompleteStart(String input) {
      for (int i = input.length()-1; i >= 0; i--) {
         switch (input.charAt(i)) {
            case ';':
            case ' ':
            case ':':
            case '}':
            case '{':
            case '=':
               return input.substring(i+1).trim();
         }
      }
      return input.trim();
   }

   public int completeTextInFile(String codeText, int cursor, List<String> candidates, JavaModel fileModel) {
      String codeSnippet = codeText.substring(0, cursor);

      // First we try to complete by parsing the fragment from the start of the file till the cursor
      Object res = completeFullTypeContext(codeSnippet, fileModel.getLanguage().getStartParselet(), cursor, candidates, fileModel);
      // This method returns an integer if it completed something meaningful or the best context value it could find... used to find the current type.
      if (!(res instanceof Integer)) {
         String command = getStatementCompleteStart(codeSnippet);
         if (command == null || command.length() == 0)
            return -1;

         BodyTypeDeclaration currentType = getCurrentTypeFromCtxValue(res, fileModel);

         // Sometimes, there's not enough context for us to match a completable element e.g. when you are starting a new statement at the class body level,
         // In this cases, we take a simpler approach - just complete the last identifier as an expression (e.g. a.bc will parse into an identifier expression)
         return completePartialContext(command, cursor, candidates, cmdlang.completionCommands, codeSnippet, fileModel, currentType);
      }

      return (Integer) res;
   }

   public Object completeFullTypeContext(String codeSnippet, Parselet completeParselet, int cursor, List<String> candidates, JavaModel fileModel) {
      Language lang = completeParselet.getLanguage();
      Parser p = new Parser(lang, new StringReader(codeSnippet));
      p.enablePartialValues = true;
      // Turn the command string into a parse-tree using the special "completionCommands" grammar
      Object parseTree = p.parseStart(completeParselet);

      lang.postProcessResult(parseTree, null);

      Object completedResult = null;

      // We can either fail out right or parse a subset of the grammar... in the latter case, the stored errors
      // will reflect why we could not parse the rest.
      if (parseTree instanceof ParseError || !p.atEOF()) {
         for (int i = 0; i < p.currentErrors.size(); i++) {
            ParseError err = p.currentErrors.get(i);
            Object parseNodeValue = err.partialValue;
            // We can only complete structured parse nodes but there should be some structure in this context object.
            // The value we want to complete will be the last parse node we retrieve... from there we can properly get the context
            // of the code to complete
            if (parseNodeValue instanceof IParseNode) {
               Object result = completeParsedValue(parseNodeValue, codeSnippet, cursor, candidates, fileModel, err.continuationValue);

               if (result instanceof Integer)
                  return result;

               // Here we are picking the first completed result at random
               if (completedResult == null && result != null)
                  completedResult = result;
               // else - we are skipping the processing of this error.  It will either match another error or we default to a more primitive code completion that's based on just finding the next identifier
            }
         }
         return completedResult;
      }
      else {
         return completeParsedValue(parseTree, codeSnippet, cursor, candidates, fileModel, null);
      }
   }

   private Object completeParsedValue(Object parseNodeValue, String codeSnippet, int cursor, List<String> candidates, JavaModel fileModel, Object continuationValue) {
      IParseNode result = ParseUtil.findClosestParseNode((IParseNode) parseNodeValue, codeSnippet.length());
      Object semValue = result.getSemanticValue();
      // TODO: since right now we are only using this as a context to find the current type, it should be ok
      // to just pick the first error and use that parse node.  Long term, we probably should run all of these
      // semantic values through the suggestCompletion method and collect candidates to fix any error.
      // That's an easy fix as soon as there's a case that needs it.
      if (semValue instanceof SemanticNodeList)
         semValue = ((SemanticNodeList) semValue).getParentNode();
      if (semValue instanceof JavaSemanticNode) {
         int resPos;

         // First we are going to try and complete the closest parse-node to the complete location
         resPos = completeCommand(fileModel.getPackagePrefix(), semValue, codeSnippet, cursor, candidates, getCurrentTypeFromCtxValue(result, fileModel), continuationValue);

         if (resPos != -1)
            return resPos;
      }
      // Returns the closest parse-node if there's no match.   This could be used to find the current context for a more primitive type of completion.
      return result;
   }

   public BodyTypeDeclaration getCurrentTypeFromCtxValue(Object ctxValue, JavaModel fileModel) {
      BodyTypeDeclaration currentType = null;
      if (ctxValue instanceof IParseNode) {
         IParseNode pn = (IParseNode) ctxValue;
         Object semValue = pn.getSemanticValue();
         if (semValue instanceof SemanticNodeList)
            semValue = ((SemanticNodeList) semValue).getParentNode();
         if (semValue instanceof JavaSemanticNode) {
            JavaSemanticNode javaNode = (JavaSemanticNode) semValue;
            BodyTypeDeclaration enclFragmentType =
                    javaNode instanceof BodyTypeDeclaration ? (BodyTypeDeclaration) javaNode :
                                                               javaNode.getStructuralEnclosingType();
            // This is the type name of the parsed fragment.  Convert it to the real type before we use it in the
            // completion process
            String typeName = fileModel.getModelTypeName();
            if (typeName == null)
               return null;
            if (enclFragmentType != null && enclFragmentType.getEnclosingType() != null)
               typeName = CTypeUtil.prefixPath(typeName, CTypeUtil.getTailType(enclFragmentType.getInnerTypeName()));
            Object enclType = fileModel.layeredSystem.getTypeDeclaration(typeName, false, fileModel.getLayer(), fileModel.isLayerModel);
            if (enclType instanceof BodyTypeDeclaration)
               currentType = (BodyTypeDeclaration) enclType;
            else {
               Object typeObj = fileModel.findTypeDeclaration(typeName, false);
               if (typeObj instanceof BodyTypeDeclaration)
                  currentType = (BodyTypeDeclaration) typeObj;
            }
            if (currentType == null && fileModel.isLayerModel) {
               currentType = fileModel.getModelTypeDeclaration();
               if (enclFragmentType != null) {
                  String innerName = enclFragmentType.getTypeName();
                  TypeDeclaration fragEncl = enclFragmentType.getEnclosingType();
                  while (fragEncl != null) {
                     TypeDeclaration nextEncl = fragEncl.getEnclosingType();
                     if (nextEncl == null)
                        break;
                     innerName = fragEncl.typeName + '.' + innerName;
                     fragEncl = nextEncl;
                  }

                  Object newType = currentType.getInnerType(innerName, null, true, false, true);
                  if (newType instanceof TypeDeclaration)
                     currentType = (TypeDeclaration) newType;
               }
            }
         }
      }
      return currentType;
   }

   public int completeParseNode(Parser p, Object parseTree, String command, int cursor, List<String> candidates, BodyTypeDeclaration currentType, String packagePrefix) {
      int resPos = -1;
      // We can either fail out right or parse a subset of the grammar... in the latter case, the stored errors
      // will reflect why we could not parse the rest.
      if (parseTree instanceof ParseError || !p.atEOF()) {
         for (int i = 0; i < p.currentErrors.size(); i++) {
            ParseError err = p.currentErrors.get(i);
            Object sv = ParseUtil.nodeToSemanticValue(err.partialValue);
            if (sv != null) {
               // Need to accumulate all of them
               int newResPos = completeCommand(packagePrefix, sv, command, cursor, candidates, currentType, err.continuationValue);
               if (newResPos != -1)
                  resPos = newResPos;
               // -1 nothing matched but we keep going
            }
         }
         // The parser did not complete parsing all of the input.  None of the errors by themselves were
         // complete enough context to suggest any completions so we'll try one more thing.  Each model
         // object can implement the applyPartialValue method to merge in the model fragments - i.e.
         // we can add the trailing "." or argument fragment for the identifier expression.
         if (!p.atEOF()) {
            char nextC = p.peekInputChar(0);
            Object parsedValue = ParseUtil.nodeToSemanticValue(parseTree);
            if (parsedValue instanceof JavaSemanticNode) {
               JavaSemanticNode parsedNode = (JavaSemanticNode) parsedValue;

               for (int i = 0; i < p.currentErrors.size(); i++) {
                  ParseError err = p.currentErrors.get(i);
                  Object sv = ParseUtil.nodeToSemanticValue(err.partialValue);
                  if (sv != null) {
                     if (parsedNode.applyPartialValue(sv)) {
                        int stat = completeCommand(packagePrefix, parsedNode, command, cursor, candidates, currentType, err.continuationValue);
                        if (stat != -1)  // Any reason we should try all of them?  Could they return different offsets?
                           return stat;
                     }
                  }
               }
            }
         }
      }
      else
         return completeCommand(packagePrefix, ParseUtil.nodeToSemanticValue(parseTree), command, cursor, candidates, currentType, false);

      return resPos;
   }

   public int completePartialContext(String command, int cursor, List<String> candidates, Parselet completeParselet, String ctxText, JavaModel fileModel, BodyTypeDeclaration currentType) {
      if (!cmdlang.typeCommands.initialized) {
         ParseUtil.initAndStartComponent(completeParselet);
      }
      HashSet<String> collector = new HashSet<String>();

      // Empty string is a special case - suggest all names available here
      if (command.trim().length() == 0) {
         Object curObj = getCurrentType();
         if (curObj != null) {
            ModelUtil.suggestMembers(getModel(), curObj, "", collector, true, true, true, true);
            convertCollectorToCandidates(collector, candidates);
            return command.length() + (ctxText != null ? ctxText.length() - command.length() : 0);
         }
      }

      Parser p = new Parser(cmdlang, new StringReader(command));
      p.enablePartialValues = true;
      JavaModel model = getModel();
      int resPos = -1;
      try {
         model.disableTypeErrors = true;
         // Turn the command string into a parse-tree using the special "completionCommands" grammar
         Object parseTree = p.parseStart(completeParselet);

         resPos = completeParseNode(p, parseTree, command, cursor, candidates, currentType, getPrefix());

         if (resPos != -1)
            resPos += (ctxText != null ? ctxText.length() - command.length() : 0);
      }
      finally {
         model.disableTypeErrors = false;
      }
      return resPos;

   }

   public int complete(String command, int cursor, List<String> candidates, Parselet completeParselet, String ctxText, JavaModel fileModel) {
      if (!cmdlang.typeCommands.initialized) {
         ParseUtil.initAndStartComponent(completeParselet);
      }

      BodyTypeDeclaration currentType = null;
      if (ctxText != null) {
         Object ctxValue = completeFullTypeContext(ctxText, fileModel.getLanguage().getStartParselet(), cursor, candidates, fileModel);
         if (ctxValue instanceof Integer)
            return (Integer) ctxValue;
         currentType = getCurrentTypeFromCtxValue(ctxValue, fileModel);
      }

      return completePartialContext(command, cursor, candidates, completeParselet, ctxText, fileModel, currentType);
   }

   public int completeCommand(String defaultPackage, Object semanticValue, String command, int cursor, List candidates, BodyTypeDeclaration currentType, Object continuationValue) {
      if (currentType == null)
         currentType = getCurrentType();
      JavaSemanticNode defaultParent = currentType;
      if (currentType == null)
         defaultParent = currentTypes.size() == 0 ? getModel() : currentTypes.get(currentTypes.size()-1);
      return completeCommand(defaultPackage, semanticValue, command, cursor, candidates, currentType, execContext, defaultParent, continuationValue);
   }

   public static int completeCommand(String defaultPackage, Object semanticValue, String command, int cursor, List candidates, BodyTypeDeclaration currentType, ExecutionContext execContext, JavaSemanticNode defaultParent, Object continuationValue) {
      if (semanticValue instanceof JavaSemanticNode) {
         JavaSemanticNode node = (JavaSemanticNode) semanticValue;

         // Because node has not been attached to a model only it's innerTypeName is accurate
         boolean nodeIsCurrentType = node instanceof BodyTypeDeclaration && currentType != null &&
                 StringUtil.equalStrings(currentType.getInnerTypeName(), ((BodyTypeDeclaration) node).getInnerTypeName());

         // We want to set the parent node of the element from the model fragment to point to the
         // currentType - in case we have a more complete version of that type.  We also need to preserve
         // as much info as possible - e.g. if a ClassType is inside of a CastExpression or on its own.
         // so that means picking the highest node in the fragment until we hit a type.
         ISemanticNode toReparent = node, parentNode;
         do {
            parentNode = toReparent.getParentNode();
            if (parentNode == null || parentNode instanceof BodyTypeDeclaration)
               break;
            toReparent = parentNode;
         }
         while (true);

         if (!(toReparent instanceof JavaModel)) {
            if (currentType == null)
               toReparent.setParentNode(defaultParent);
            else {
               // The node we completed is the current type - need to reparent it up one-level
               if (nodeIsCurrentType) {
                  BodyTypeDeclaration nextType = currentType.getEnclosingType();
                  if (nextType != null)
                     toReparent.setParentNode(nextType);
                  else
                     toReparent.setParentNode(currentType.getJavaModel());
               }
               else
                  toReparent.setParentNode(currentType);
            }
         }

         JavaModel theModel = node.getJavaModel();
         if (theModel != null) {
            if (theModel.layeredSystem == null && currentType != null) {
               theModel.layeredSystem = currentType.getLayeredSystem();
               theModel.layer = currentType.getLayer();
            }
            // Temporarily turn off error display for this type
            boolean oldTE = theModel.disableTypeErrors;
            theModel.setDisableTypeErrors(true);
            ParseUtil.initAndStartComponent(node);
            theModel.setDisableTypeErrors(oldTE);
         }
         else
            ParseUtil.initAndStartComponent(node);

         HashSet<String> collector = new HashSet<String>();
         int res = node.suggestCompletions(defaultPackage, currentType, execContext, command, cursor, collector, continuationValue);
         convertCollectorToCandidates(collector, candidates);
         return res;
      }
      else if (semanticValue instanceof IString || semanticValue instanceof String)
         return -1;
      // TODO: more completions!

      return -1;
   }

   private int completeCommand(Object semanticValue, String command, int cursor, List candidates) {
      return completeCommand(getPrefix(), semanticValue, command, cursor, candidates, null, false);
   }

   public BodyTypeDeclaration getCurrentType() {
      return currentTypes.size() == 0 ? null : currentTypes.get(currentTypes.size()-1);
   }

   public void pushCurrentType(BodyTypeDeclaration type) {
      BodyTypeDeclaration  enclType = type.getEnclosingType();
      if (enclType != null)
         pushCurrentType(enclType);

      currentTypes.add(type);
      execContext.pushStaticFrame(type);
      if (type.getDefinesCurrentObject())
         execContext.pushCurrentObject(getDefaultCurrentObj(type));
   }

   public void popCurrentType() {
      BodyTypeDeclaration lastType = currentTypes.remove(currentTypes.size()-1);
      if (lastType.getDefinesCurrentObject())
         execContext.popCurrentObject();
      execContext.popStaticFrame();
   }

   /** Called when the editor sets the type */
   @Bindable(manual=true)
   public void setCurrentType(BodyTypeDeclaration type) {
      if (type != null)
         setCurrentLayer(type.getLayer());
      clearPendingModel();
      while (currentTypes.size() > 0)
         popCurrentType();
      if (type != null) {
         pushCurrentType(type);
         setPendingModelType(type);
      }
      // Turning this off because when we change this from the editor, we don't want the change event back again
      //markCurrentTypeChanged();
   }

   /**
    * This is called after the user makes an action which changes the currentType - i.e. so it reflects when the
    * user wants to explicitly change the currentType.  This should trigger a change in any UI reflecting the
    * shared editor context with the command line or any other model editor.
    */
   protected void markCurrentTypeChanged() {
      Bind.sendChangedEvent(this, "currentType");
   }

   protected String getPrefix() {
      return CTypeUtil.prefixPath(layerPrefix, path);
   }

   public int completeExistingLayer(String command, int cursor, List candidates) {
      int ix = command.lastIndexOf(" ");
      String nextCommand;
      int pos;
      if (ix == -1) {
         nextCommand = command;
         pos = 0;
      }
      else {
         pos = ix + 1;
         if (ix == command.length()-1)
            nextCommand = "";
         else {
            nextCommand = command.substring(ix).trim();
         }
      }

      String pathName = nextCommand.replace(".", FileUtil.FILE_SEPARATOR);
      String dirName = FileUtil.getParentPath(pathName);
      String nextCommandRest;
      if (pathName.endsWith(FileUtil.FILE_SEPARATOR)) {
         nextCommandRest = "";

         pos += pathName.length();
      }
      else {
         nextCommandRest = FileUtil.getFileName(pathName);
         // If it is not the root dir, skip the parent path
         if (!nextCommandRest.equals(pathName))
            pos += dirName.length() + 1;
      }


      List<File> layerDirs = system.layerPathDirs;
      for (File layerDir:layerDirs) {
         String layerDirName;
         if (dirName == null)
            layerDirName = layerDir.getPath();
         else
            layerDirName = FileUtil.concat(layerDir.getPath(), dirName);

         // If we are inside of a layer directory, it's an invalid path for a layer so don't complete it
         for (String parentName = nextCommandRest.length() == 0 ? layerDirName : FileUtil.getParentPath(layerDirName); parentName != null; parentName = FileUtil.getParentPath(parentName)) {
            if (LayerUtil.isLayerDir(parentName))
               continue;
         }

         String[] files = new File(layerDirName).list();
         if (files != null) {
            for (String file : files)
               if (nextCommandRest.length() == 0 || file.startsWith(nextCommandRest))
                  candidates.add(file);
         }
      }
      return pos;
   }


   public void initSync() {
      // Manually adding these (roughly based on the generated code from js/layer/lang/EditorContext.java - so we sync the same properties
      int globalScopeId = GlobalScopeDefinition.getGlobalScopeDefinition().scopeId;
      SyncManager.addSyncType(getClass(), new sc.sync.SyncProperties(null, null, new Object[]{"currentLayer", "currentType", "needsSave", "canUndo", "canRedo"}, null, SyncOptions.SYNC_INIT_DEFAULT, globalScopeId));
      SyncManager.addSyncType(MemoryEditSession.class, new sc.sync.SyncProperties(null, null, new Object[] {"origText", "text", "model", "saved", "caretPosition"}, null, SyncOptions.SYNC_INIT_DEFAULT, globalScopeId));
      SyncManager.addSyncInst(this, true, true, null);
   }

   /** Rebuilds the system */
   @Remote(remoteRuntimes="js")
   public void rebuild() {
      system.rebuild();
   }

   /** Rebuild the system from scratch. */
   @Remote(remoteRuntimes="js")
   public void rebuildAll() {
      system.rebuildAll();
   }
}
