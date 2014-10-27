/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
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

   public void refreshBoundTypes() {
      if (lhs != null)
         lhs.refreshBoundTypes();
      if (rhs != null)
         rhs.refreshBoundTypes();
   }
   public void addDependentTypes(Set<Object> types) {
      if (lhs != null)
         lhs.addDependentTypes(types);
      if (rhs != null)
         rhs.addDependentTypes(types);
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
