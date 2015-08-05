/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.java.*;
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

      if (inst instanceof Field) {
         return VariableDefinition.createFromField((Field) inst);
      }

      if (inst instanceof IBeanMapper) {
         Object member = ((IBeanMapper) inst).getPropertyMember();
         return replaceInstance(member);
      }

      if (inst instanceof ParamTypeDeclaration)
         return replaceInstance(((ParamTypeDeclaration) inst).getBaseType());

      if (inst instanceof Method || inst instanceof MethodDefinition) {
         VariableDefinition varDef = new VariableDefinition();
         varDef.variableName = ModelUtil.getPropertyName(inst);
         varDef.frozenTypeDecl = ModelUtil.getPropertyType(inst);
         return varDef;
      }

      if (inst instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration btd = (BodyTypeDeclaration) inst;
         return btd.getClientTypeDeclaration();
      }

      return inst;
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
      return super.getObjectType(changedObj);
   }
}
