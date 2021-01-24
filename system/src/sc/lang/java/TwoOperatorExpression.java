/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.BindingDirection;
import sc.lang.ISrcStatement;
import sc.lang.SemanticNodeList;

import java.util.List;
import java.util.Set;

public abstract class TwoOperatorExpression extends Expression {
   public Expression lhs;
   public String operator;
   public Expression rhs;

   /**
    * Produces binding arguments for the operator and the two nested bindings
    */
   public void transformBindingArgs(SemanticNodeList<Expression> bindArgs, BindDescriptor bd) {
      bindArgs.add(StringLiteral.create(operator));
      bindArgs.add(createBindingParameters(false, lhs, rhs));
   }

   public void evalBindingArgs(List<Object> bindArgs, boolean isStatic, Class expectedType, ExecutionContext ctx) {
      bindArgs.add(operator);
      bindArgs.add(evalBindingParameters(expectedType, ctx, lhs, rhs));
   }

   /**
    * Propagates the binding information to nested expressions
    */
   public void setBindingInfo(BindingDirection dir, Statement dest, boolean nested) {
      super.setBindingInfo(dir, dest, nested);
      if (lhs != null && rhs != null) {
         BindingDirection propBD;
         // Weird case:  convert reverse only bindings to a "none" binding for the arguments.
         // We do need to reflectively evaluate the parameters but do not listen on them like
         // in a forward binding.  If we propagate "REVERSE" down the chain, we get invalid errors
         // like when doing arithmetic in a reverse expr.
         if (bindingDirection.doReverse() && !bindingDirection.doForward())
            propBD = BindingDirection.NONE;
         else
            propBD = bindingDirection;
         lhs.setBindingInfo(propBD, bindingStatement, true);
         rhs.setBindingInfo(propBD, bindingStatement, true);
      }
   }

   public void changeExpressionsThis(TypeDeclaration td, TypeDeclaration outer, String newName) {
      lhs.changeExpressionsThis(td, outer, newName);
      rhs.changeExpressionsThis(td, outer, newName);
   }

   public void visitTypeReferences(CycleInfo info, TypeContext ctx) {
      info.visit(lhs, ctx, false);
      info.visit(rhs, ctx);
      info.remove(lhs);
   }

   public boolean refreshBoundTypes(int flags) {
      boolean res = false;
      if (lhs != null)
         res = lhs.refreshBoundTypes(flags);
      if (rhs != null) {
         if (rhs.refreshBoundTypes(flags))
            res = true;
      }
      return res;
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (lhs != null)
         lhs.addDependentTypes(types, mode);
      if (rhs != null)
         rhs.addDependentTypes(types, mode);
   }

   public void setAccessTimeForRefs(long time) {
      if (lhs != null)
         lhs.setAccessTimeForRefs(time);
      if (rhs != null)
         rhs.setAccessTimeForRefs(time);
   }

   public Statement transformToJS() {
      if (lhs != null)
         lhs.transformToJS();
      if (rhs != null)
         rhs.transformToJS();
      return this;
   }

   public boolean matchesStatement(Statement other) {
      return deepEquals(other);
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement srcStatement) {
      super.addBreakpointNodes(res, srcStatement);
      if (lhs != null) {
         lhs.addBreakpointNodes(res, srcStatement);
      }
      if (rhs != null)
         rhs.addBreakpointNodes(res, srcStatement);
   }
}
