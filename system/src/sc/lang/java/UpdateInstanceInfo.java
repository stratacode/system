/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.dyn.DynUtil;
import sc.dyn.IDynObject;
import sc.dyn.ITypeChangeListener;
import sc.layer.LayeredSystem;
import sc.obj.ITypeUpdateHandler;
import sc.sync.SyncManager;
import sc.sync.SyncProperties;

import java.util.*;


/**
 * This class is used by the dynamic runtime to update the system in response to changes made to the source files
 * in the running application.   It's constructed during the updateModel/updateType process to hold all of the updates
 * that need to be made to the instances and notifications that need to be made to framework components - e.g. the TypeChangeListener
 */
public class UpdateInstanceInfo {
   List<UpdateAction> actionsToPerform = new LinkedList<UpdateAction>();
   HashMap<String,List<UpdateAction>> actionsByType = new HashMap<String,List<UpdateAction>>();

   /** For the default action, we do not queue the removes.  They are executed during the updateType operation.
    *  But for Javascript, we need to queue them so we can record them in JS. */
   public boolean queueRemoves() {
      return false;
   }

   public static abstract class UpdateAction {
      protected BodyTypeDeclaration newType;

      /** First pass - update data structures in the type system so instances are valid */
      abstract void updateTypes(ExecutionContext ctx);
      /** Second pass - now the type system is fine - it's ok to update the instances */
      abstract void updateInstances(ExecutionContext ctx);

      /** Third pass - after the instances have been updated, notify any listeners */
      abstract void postUpdate(ExecutionContext ctx);
   }

   public static class UpdateType extends UpdateAction {
      protected BodyTypeDeclaration oldType;
      void updateTypes(ExecutionContext ctx) {
         oldType.updateStaticValues(newType);
         BodyTypeDeclaration newRoot = newType.getModifiedByRoot();
         if (!newRoot.getFullTypeName().equals(newType.getFullTypeName()))
            System.out.println("*** Error - should be the same type!");
         LayeredSystem sys = newType.getLayeredSystem();
         Iterator insts = sys.getInstancesOfType(newType.getFullTypeName());
         while (insts.hasNext()) {
            Object inst = insts.next();
            if (inst instanceof IDynObject) {
               IDynObject dynInst = (IDynObject) inst;
               dynInst.setDynType(newRoot); // Forces the type to recompute the field mapping using "getOldInstFields"
            }
         }

         if (sys.runtimeProcessor == null || sys.runtimeProcessor.usesLocalSyncManager()) {
            List<SyncProperties> newSyncPropList = newRoot.getSyncProperties();
            if (newSyncPropList != null) {
               for (SyncProperties newSyncProps:newSyncPropList) {
                  // Using oldType here instead of oldType.getModifiedByRoot() since we've replaced the links in the chain that would let us get back to the
                  // original old type. It might not have changed...
                  SyncManager.replaceSyncType(oldType, newRoot, newSyncProps);
               }
            }
         }

         ClientTypeDeclaration oldClientTD = oldType.clientTypeDeclaration;
         if (oldClientTD != null) {
            if (!newType.isStarted())
               System.err.println("*** Should be started before refreshing the client type declaration");
            newType.clientTypeDeclaration = oldClientTD;
            newType.refreshClientTypeDeclaration();
         }
      }
      void updateInstances(ExecutionContext ctx) {
      }
      void postUpdate(ExecutionContext ctx) {
         Iterator insts = newType.getLayeredSystem().getInstancesOfType(newType.getFullTypeName());
         while (insts.hasNext()) {
            Object inst = insts.next();
            if (inst instanceof ITypeUpdateHandler)
               ((ITypeUpdateHandler) inst)._updateInst();
         }

         LayeredSystem sys = newType.getLayeredSystem();
         for (ITypeChangeListener tcl:sys.getTypeChangeListeners()) {
            // We are only notifying if the type being updated is a dynamic type.  We don't yet have a way to replace the class itself though this might
            // be a place where we could integrate another class-patching strategy.
            if (newType.isDynamicType() || newType.isDynamicNew())
               tcl.updateType(oldType, newType);
         }
      }

      public String toString() {
         return newType != oldType ? (" type changed: " + newType.typeName) : " base-type changed: " + newType.typeName;
      }
   }

   public static class NewType extends UpdateAction {
      void updateTypes(ExecutionContext ctx) {
         LayeredSystem sys = newType.getLayeredSystem();
         for (ITypeChangeListener tcl:sys.getTypeChangeListeners()) {
            tcl.typeCreated(newType);
         }
      }
      void updateInstances(ExecutionContext ctx) {
      }
      void postUpdate(ExecutionContext ctx) {
      }

      public String toString() {
         return "new type: " + newType.typeName;
      }
   }

   public static class RemovedType extends UpdateAction {
      BodyTypeDeclaration oldType;
      void updateTypes(ExecutionContext ctx) {
      }
      void updateInstances(ExecutionContext ctx) {
      }
      void postUpdate(ExecutionContext ctx) {
         LayeredSystem sys = oldType.getLayeredSystem();
         for (ITypeChangeListener tcl:sys.getTypeChangeListeners()) {
            tcl.typeRemoved(oldType);
         }
         Iterator insts = oldType.getLayeredSystem().getInstancesOfTypeAndSubTypes(oldType.getFullTypeName());
         while (insts.hasNext()) {
            Object inst = insts.next();
            DynUtil.dispose(inst);
         }
      }
      public String toString() {
         return "removed type: " + newType.typeName;
      }
   }

   public static class UpdateProperty extends UpdateAction {
      protected JavaSemanticNode overriddenAssign;
      protected BodyTypeDeclaration.InitInstanceType initType;

      public void updateTypes(ExecutionContext ctx) {
      }

      public void updateInstances(ExecutionContext ctx) {
         newType.updateInstancesForProperty(overriddenAssign, ctx, initType);
      }
      void postUpdate(ExecutionContext ctx) {
      }
      public String toString() {
         return "property " + initType + " : " + newType.typeName + "." + overriddenAssign.toString();
      }
   }

   public static class AddField extends UpdateAction {
      protected VariableDefinition varDef;

      public void updateTypes(ExecutionContext ctx) {
      }

      public void updateInstances(ExecutionContext ctx) {
         // Make sure sync properties are updated since we clear them before adding the field
         newType.initSyncProperties();
         if (newType.isDynamicType()) {
            if (newType.syncProperties != null) {
               // In case the sync properties have changed for a dynamic type, update them with the sync manager using the API.
               // The original call to addSyncType was done through a staticMixinTemplate so uses the same logic
               // as the compiled type version except that here we have programmatically build the SyncProperties, instead of code-gen
               for (SyncProperties syncProps:newType.syncProperties) {
                  SyncManager.addSyncType(newType, syncProps);
               }
            }
         }

         newType.doAddFieldToInstances(varDef, ctx);
      }
      void postUpdate(ExecutionContext ctx) {
      }
      public String toString() {
         return "add field " + varDef.variableName + " to: " + newType.typeName;
      }
   }

   public static class ExecBlock extends UpdateAction {
      protected BlockStatement blockStatement;

      public void updateTypes(ExecutionContext ctx) {
      }
      public void updateInstances(ExecutionContext ctx) {
         newType.execBlockStatement(blockStatement, ctx);
      }
      void postUpdate(ExecutionContext ctx) {
      }

      public String toString() {
         return "exec for: " + newType.typeName + ": " + blockStatement.toString();
      }
   }

   public UpdateProperty newUpdateProperty() {
      return new UpdateProperty();
   }

   public void addUpdateProperty(BodyTypeDeclaration newType, JavaSemanticNode overriddenAssign, BodyTypeDeclaration.InitInstanceType initType) {
      UpdateProperty prop = newUpdateProperty();
      prop.newType = newType;
      prop.overriddenAssign = overriddenAssign;
      prop.initType = initType;
      actionsToPerform.add(prop);
   }

   public AddField newAddField() {
      return new AddField();
   }

   public void addField(BodyTypeDeclaration newType, VariableDefinition varDef) {
      AddField af = newAddField();
      af.newType = newType;
      af.varDef = varDef;
      actionsToPerform.add(af);
   }

   public ExecBlock newExecBlock() {
      return new ExecBlock();
   }

   public void addBlockStatement(BodyTypeDeclaration newType, BlockStatement blockStatement) {
      ExecBlock eb = newExecBlock();
      eb.newType = newType;
      eb.blockStatement = blockStatement;
      actionsToPerform.add(eb);
   }

   public void typeChanged(BodyTypeDeclaration oldType, BodyTypeDeclaration newType) {
      String typeName = oldType.getFullTypeName();
      List<UpdateAction> oldActions = actionsByType.get(typeName);
      boolean found = false;
      if (oldActions != null) {
         for (UpdateAction oldAction:oldActions) {
            // We have different UpdateTypes for different layers with the same type name - be careful not to
            // update the wrong one.
            if (oldAction.newType.getLayer() != newType.getLayer()) {
               continue;
            }
            if (oldAction instanceof UpdateType)
               found = true;
            oldAction.newType = newType;
         }

         if (found) {
            return;
         }
         System.err.println("*** Failed to find previous UpdateType but where there are old updates for a type");
      }

      UpdateType ut = new UpdateType();
      ut.oldType = oldType;
      ut.newType = newType;
      actionsToPerform.add(ut);

      if (oldActions == null) {
         oldActions = new ArrayList<UpdateAction>();
         actionsByType.put(typeName, oldActions);
      }
      oldActions.add(ut);
   }

   public void typeCreated(BodyTypeDeclaration newType) {
      NewType nt = new NewType();
      nt.newType = newType;
      actionsToPerform.add(nt);
   }

   public void typeRemoved(BodyTypeDeclaration oldType) {
      RemovedType nt = new RemovedType();
      nt.oldType = oldType;
      actionsToPerform.add(nt);
   }

   public void methodChanged(AbstractMethodDefinition methChanged) {
   }

   public void updateInstances(ExecutionContext ctx) {
      // First we update all of the types
      for (UpdateAction act:actionsToPerform)
         act.updateTypes(ctx);
      // Then update the instances
      for (UpdateAction act:actionsToPerform)
         act.updateInstances(ctx);
      // Then notify listeners
      for (UpdateAction act:actionsToPerform)
         act.postUpdate(ctx);
   }


   public boolean needsChangedMethods() {
      return false;
   }
}
