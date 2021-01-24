/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sc;

import sc.lang.java.BodyTypeDeclaration;
import sc.lang.java.Expression;
import sc.lang.java.IVariableInitializer;

public class OverrideAssignment extends PropertyAssignment {
   private transient IVariableInitializer inheritedMember = null;

   public void start() {
      if (started)
         return;

      super.start();

      updateInheritedInitializer();
   }

   private void updateInheritedInitializer() {
      if (initializer == null) {
         BodyTypeDeclaration encType = getEnclosingType();
         encType = encType.resolve(true);
         // Must be in start, not initialize as it will force referenced types to be loaded (and could cause this type to be loaded)
         // Using "defines" here, not "find" as we do not want to inherit property assignments.  Why?  It's too easy to pick up a parent's property when a child's is not defined.  Get no error
         // and wonder why things are not working.  Secondly, we don't handle all of the cases for when the property is not in the type.  For example, when the type gets optimized away, we do not
         // implement the binding correctly.  So simpler is better.  Make sure the property is defined in the enclosing type.
         // Use the special "Initializer" flag here so that we only return a member which has an initializer... skipping empty fields and override definitions
         Object otherMember = encType.definesPreviousMember(propertyName, MemberType.InitializerSet, getEnclosingIType(), null, false, false);
         if (otherMember instanceof IVariableInitializer)
            inheritedMember = (IVariableInitializer) otherMember;
         else
            inheritedMember = null;
      }
      else
         inheritedMember = null;
   }

   public static OverrideAssignment create(String pname) {
      OverrideAssignment pa = new OverrideAssignment();
      pa.propertyName = pname;
      return pa;
   }

   public IVariableInitializer getInheritedMember() {
      if (inheritedMember != null)
         return inheritedMember;
      return this;
   }


   public Expression getInitializerExpr() {
      if (inheritedMember != null)
         return inheritedMember.getInitializerExpr();
      return initializer;
   }

   public void updateInitializer(String op, Expression expr) {
      super.updateInitializer(op, expr);
      updateInheritedInitializer();
   }
}
