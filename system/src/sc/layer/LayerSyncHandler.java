/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.AbstractInterpreter;
import sc.lang.java.*;
import sc.lang.sc.OverrideAssignment;
import sc.sync.SyncHandler;
import sc.sync.SyncManager;
import sc.type.IBeanMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * This class is registered at startup for the various types listed in replaceInstance.  When the serializer goes to
 * serialize one of these types, they are replaced with something which can be serialized to the client.  If the client
 * makes change and the server needs to be updated, it also can perform that conversion in restoreInstance.
 */
public class LayerSyncHandler extends SyncHandler {
   public LayerSyncHandler(Object inst, SyncManager.SyncContext ctx) {
      super(inst, ctx);
   }

   public Object replaceInstance(Object inst) {
      if (inst instanceof ParamTypedMember)
         return replaceInstance(((ParamTypedMember) inst).getMemberObject());

      if (inst instanceof VariableDefinition) {
         VariableDefinition varDef = (VariableDefinition) inst;
         Definition def = varDef.getDefinition();
         if (def != null) {
            varDef.annotations = def.getAnnotations();
            varDef.modifierFlags = def.getModifierFlags();
         }
      }

      if (inst instanceof Field) {
         return VariableDefinition.createFromField((Field) inst);
      }

      if (inst instanceof IBeanMapper) {
         IBeanMapper mapper = ((IBeanMapper) inst);
         Object member = mapper.getPropertyMember();
         return replaceInstance(member);
      }

      if (inst instanceof ParamTypeDeclaration)
         return replaceInstance(((ParamTypeDeclaration) inst).getBaseType());

      if (inst instanceof Method || (inst instanceof MethodDefinition && ((MethodDefinition) inst).propertyName != null)) {
         VariableDefinition varDef = new VariableDefinition();
         varDef.variableName = ModelUtil.getPropertyName(inst);
         varDef.frozenTypeDecl = ModelUtil.getPropertyType(inst);
         varDef.indexedProperty = ModelUtil.isGetIndexMethod(inst);
         varDef.annotations = ModelUtil.getAnnotations(inst);
         varDef.modifierFlags = ModelUtil.getModifiers(inst);
         varDef.setWritable(ModelUtil.hasSetMethod(inst));
         varDef.methodMetadata = true;
         // So getEnclosingType and getLayer will work, we set the parentNode.
         if (inst instanceof MethodDefinition)
            varDef.parentNode = ((MethodDefinition) inst).getParentNode();
         return varDef;
      }

      if (inst instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration btd = (BodyTypeDeclaration) inst;
         return btd.getClientTypeDeclaration();
      }

      // These need to have constructor args since they are used in HashMap's as keys and equals/hashCode needs to be
      // implemented right when it's registered in the map.
      if (inst instanceof SrcEntry) {
         SrcEntry ent = (SrcEntry) inst;
         SyncManager.addSyncInst(ent, true, true, true, null, null, ent.layer, ent.absFileName, ent.relFileName);
         return inst;
      }

      return super.replaceInstance(inst);
   }

   public Object restoreInstance(Object syncInst) {
      if (syncInst instanceof ClientTypeDeclaration) {
         return ((ClientTypeDeclaration) syncInst).getOriginal();
      }
      return super.restoreInstance(syncInst);
   }

   public Object getObjectType(Object changedObj) {
      if (changedObj instanceof Layer)
         return Layer.class;
      // The cmd interpreter object implements IDynObject which has CmdClassDeclaration as it's type.  But the sync system
      // should see it as a normal class so it can create the remote side
      if (changedObj instanceof AbstractInterpreter)
         return changedObj.getClass();
      return super.getObjectType(changedObj);
   }
}
