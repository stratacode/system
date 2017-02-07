/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.dyn.IDynObject;
import sc.dyn.ITypeChangeListener;
import sc.layer.LayeredSystem;
import sc.obj.ITypeUpdateHandler;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;


/**
 * This class is used by the dynamic runtime to update the system in response to changes made to the source files
 * in the running application.   It's constructed during the updateModel/updateType process to hold all of the updates
 * that need to be made to the instances and notifications that need to be made to framework components - e.g. the TypeChangeListener
 */
public class UpdateInstanceInfo {

   /** For the default action, we do not queue the removes.  They are executed during the updateType operation.
    *  But for Javascript, we need to queue them so we can record them in JS. */
   public boolean queueRemoves() {
      return false;
   }

   public static abstract class UpdateAction {
      protected BodyTypeDeclaration newType;

      abstract void doAction(ExecutionContext ctx);
   }

   public static class UpdateType extends UpdateAction {
      protected BodyTypeDeclaration oldType;
      void doAction(ExecutionContext ctx) {
         Iterator insts = newType.getLayeredSystem().getInstancesOfTypeAndSubTypes(newType.getFullTypeName());
         while (insts.hasNext()) {
            Object inst = insts.next();
            if (inst instanceof IDynObject) {
               IDynObject dynInst = (IDynObject) inst;
               dynInst.setDynType(newType); // Forces the type to recompute the field mapping using "getOldInstFields"
            }
            if (inst instanceof ITypeUpdateHandler)
               ((ITypeUpdateHandler) inst)._updateInst();
         }

         LayeredSystem sys = newType.getLayeredSystem();
         for (ITypeChangeListener tcl:sys.getTypeChangeListeners()) {
            tcl.updateType(oldType, newType);
         }
      }
   }

   public static class NewType extends UpdateAction {
      void doAction(ExecutionContext ctx) {
         LayeredSystem sys = newType.getLayeredSystem();
         for (ITypeChangeListener tcl:sys.getTypeChangeListeners()) {
            tcl.typeCreated(newType);
         }
      }
   }

   public static class UpdateProperty extends UpdateAction {
      protected JavaSemanticNode overriddenAssign;
      protected BodyTypeDeclaration.InitInstanceType initType;

      public void doAction(ExecutionContext ctx) {
         newType.updateInstancesForProperty(overriddenAssign, ctx, initType);
      }
   }

   public static class ExecBlock extends UpdateAction {
      protected BlockStatement blockStatement;

      public void doAction(ExecutionContext ctx) {
         newType.execBlockStatement(blockStatement, ctx);
      }
   }

   List<UpdateAction> actionsToPerform = new LinkedList<UpdateAction>();

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
      UpdateType ut = new UpdateType();
      ut.oldType = oldType;
      ut.newType = newType;
      actionsToPerform.add(ut);
   }

   public void typeCreated(BodyTypeDeclaration newType) {
      NewType nt = new NewType();
      nt.newType = newType;
      actionsToPerform.add(nt);
   }

   public void methodChanged(AbstractMethodDefinition methChanged) {
   }

   public void updateInstances(ExecutionContext ctx) {
      for (UpdateAction act:actionsToPerform)
         act.doAction(ctx);
   }


   public boolean needsChangedMethods() {
      return false;
   }
}
