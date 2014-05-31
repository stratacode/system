/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.obj.ITypeUpdateHandler;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;


public class UpdateInstanceInfo {

   /** For the default action, we do not queue the removes.  They are executed during the updateType operation.
    *  But for Javascript, we need to queue them so we can record them in JS. */
   public boolean queueRemoves() {
      return false;
   }

   public static abstract class UpdateAction {
      protected BodyTypeDeclaration baseType;

      abstract void doAction(ExecutionContext ctx);
   }

   public static class UpdateType extends UpdateAction {
      void doAction(ExecutionContext ctx) {
         if (baseType.implementsType("sc.obj.TypeUpdateHandler")) {
            Iterator insts = baseType.getLayeredSystem().getInstancesOfTypeAndSubTypes(baseType.getFullTypeName());
            while (insts.hasNext()) {
               Object inst = insts.next();
               if (inst instanceof ITypeUpdateHandler)
                  ((ITypeUpdateHandler) inst)._updateInst();
            }
         }
      }
   }

   public static class UpdateProperty extends UpdateAction {
      protected JavaSemanticNode overriddenAssign;
      protected BodyTypeDeclaration.InitInstanceType initType;

      public void doAction(ExecutionContext ctx) {
         baseType.updateInstancesForProperty(overriddenAssign, ctx, initType);
      }
   }

   public static class ExecBlock extends UpdateAction {
      protected BlockStatement blockStatement;

      public void doAction(ExecutionContext ctx) {
         baseType.execBlockStatement(blockStatement, ctx);
      }
   }

   List<UpdateAction> actionsToPerform = new LinkedList<UpdateAction>();

   public UpdateProperty newUpdateProperty() {
      return new UpdateProperty();
   }

   public void addUpdateProperty(BodyTypeDeclaration bodyTypeDeclaration, JavaSemanticNode overriddenAssign, BodyTypeDeclaration.InitInstanceType initType) {
      UpdateProperty prop = newUpdateProperty();
      prop.baseType = bodyTypeDeclaration;
      prop.overriddenAssign = overriddenAssign;
      prop.initType = initType;
      actionsToPerform.add(prop);
   }

   public ExecBlock newExecBlock() {
      return new ExecBlock();
   }

   public void addBlockStatement(BodyTypeDeclaration theType, BlockStatement blockStatement) {
      ExecBlock eb = newExecBlock();
      eb.baseType = theType;
      eb.blockStatement = blockStatement;
      actionsToPerform.add(eb);
   }

   public void typeChanged(BodyTypeDeclaration typeChanged) {
      UpdateType ut = new UpdateType();
      ut.baseType = typeChanged;
      actionsToPerform.add(ut);
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
