/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.dyn.DynUtil;
import sc.dyn.IDynObject;
import sc.dyn.ITypeChangeListener;
import sc.layer.LayeredSystem;
import sc.obj.ITypeUpdateHandler;

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
         Iterator insts = newType.getLayeredSystem().getInstancesOfType(newType.getFullTypeName());
         while (insts.hasNext()) {
            Object inst = insts.next();
            if (inst instanceof IDynObject) {
               IDynObject dynInst = (IDynObject) inst;
               dynInst.setDynType(newType); // Forces the type to recompute the field mapping using "getOldInstFields"
            }
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
            // We are only notifing if the type being updated is a dynamic type.  We don't yet have a way to replace the class itself though this might
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
