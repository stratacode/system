/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.BindingDirection;
import sc.lang.ISrcStatement;

import java.util.List;
import java.util.Set;

public abstract class ChainedExpression extends Expression {
   public Expression expression;

   public void setChainedExpression(Expression expr) {
      expression = expr;
   }

   public Expression getChainedExpression() {
      return expression;
   }

   public Object getTypeDeclaration() {
      return expression.getTypeDeclaration();
   }

   public void setBindingInfo(BindingDirection dir, Statement dest, boolean nested) {
      super.setBindingInfo(dir, dest, nested);
      if (expression != null) {
         expression.setBindingInfo(bindingDirection, bindingStatement, true);
      }
   }

   public void changeExpressionsThis(TypeDeclaration td, TypeDeclaration outer, String newName) {
      expression.changeExpressionsThis(td, outer, newName);
   }

   /** Try completing the last operand */
   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation) {
      if (expression == null) {
         return -1;
      }
      return expression.suggestCompletions(prefix, currentType, ctx, command, cursor, candidates, continuation);
   }

   public boolean applyPartialValue(Object partial) {
      return expression != null && expression.applyPartialValue(partial);
   }

   public void visitTypeReferences(CycleInfo info, TypeContext ctx) {
      info.visit(expression, ctx);
   }

   public void refreshBoundTypes(int flags) {
      if (expression != null)
         expression.refreshBoundTypes(flags);
   }

   public void addDependentTypes(Set<Object> types) {
      if (expression != null)
         expression.addDependentTypes(types);
   }

   @Override
   public boolean isStaticTarget() {
      return expression.isStaticTarget();
   }


   public Statement transformToJS() {
      if (expression != null)
         expression.transformToJS();
      return this;
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement srcStatement) {
      super.addBreakpointNodes(res, srcStatement);
      if (expression != null) {
         expression.addBreakpointNodes(res, srcStatement);
      }
   }

   public void setInferredType(Object type) {
      if (expression != null)
         expression.setInferredType(type);
   }

   public boolean propagatesInferredType(Expression child) {
      return child == expression;
   }
}
