/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.EnumSet;
import java.util.List;

/**
 * Used when we create an anonymous class inside of code.  It routes all of the defines and find methods back to the
 * original NewExpression so that they find the definitions in the right order.
 */
public class AnonClassDeclaration extends ClassDeclaration {
   public transient NewExpression newExpr;

   /*
   public Object definesMember(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx) {
      return newExpr.definesMember(name, mtype, refType, ctx);
   }

   public Object findMemberOwner(String name, EnumSet<MemberType> type) {
      return newExpr.findMemberOwner(name, type);
   }
   */

   public Object findType(String name, Object refType, TypeContext context) {
      Object res = super.findType(name, refType, context);
      if (res != null)
         return res;
      return newExpr.findType(name, refType, context);
   }

   public Object findMember(String name, EnumSet<MemberType> mtype, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      // Once we've been initialized, we'll have copied the body over and so these find requests could becoming from our children.
      Object res = super.findMember(name, mtype, fromChild, refType, ctx, skipIfaces);
      if (res != null)
         return res;
      return newExpr.findMember(name, mtype, fromChild, refType, ctx, skipIfaces);
   }

   public Object findMethod(String name, List<? extends Object> params, Object fromChild, Object refType, boolean staticOnly, Object inferredType) {
      Object res = super.findMethod(name, params, fromChild, refType, staticOnly, inferredType);
      if (res != null)
         return res;
      return newExpr.findMethod(name, params, fromChild, refType, staticOnly, inferredType);
   }

   public TypeDeclaration getEnclosingType() {
      if (newExpr == null)
         return null;
      return newExpr.getEnclosingType();
   }
}
