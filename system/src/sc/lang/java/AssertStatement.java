/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ILanguageModel;
import sc.lang.SemanticNodeList;
import sc.layer.LayeredSystem;

import java.util.Set;

public class AssertStatement extends Statement {
   public Expression expression;
   public Expression otherExpression;

   public ExecResult exec(ExecutionContext ctx) {
      Boolean val = (Boolean) expression.eval(Boolean.class, ctx);
      if (!val) {
         String exprStr = expression.toLanguageString();
         String message = otherExpression == null ? "Assertion failed: " + exprStr : exprStr + " : " + (String) otherExpression.eval(String.class, ctx);
         throw new AssertionError(message);
      }
      return ExecResult.Next;
   }

   public boolean refreshBoundTypes(int flags) {
      boolean res = false;
      if (expression != null)
         res = expression.refreshBoundTypes(flags);
      if (otherExpression != null)
         if (otherExpression.refreshBoundTypes(flags))
            res = true;
      return res;
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      if (expression != null)
         ix = expression.transformTemplate(ix, statefulContext);
      if (otherExpression != null)
         ix = otherExpression.transformTemplate(ix, statefulContext);
      return ix;
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (expression != null)
         expression.addDependentTypes(types, mode);
      if (otherExpression != null)
         otherExpression.addDependentTypes(types, mode);
   }

   public void setAccessTimeForRefs(long time) {
      if (expression != null)
         expression.setAccessTimeForRefs(time);
      if (otherExpression != null)
         otherExpression.setAccessTimeForRefs(time);
   }

   public void start() {
      super.start();
   }

   public boolean transform(ILanguageModel.RuntimeType type) {
      return super.transform(type);
   }

   @Override
   public Statement transformToJS() {
      // TODO: a debug mode should do replace this node with a method call to do an alert or something if the expression fails.
      SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
      if (expression != null) {
         expression.transformToJS();
         args.add(expression);
      }
      if (otherExpression != null) {
         otherExpression.transformToJS();
         args.add(otherExpression);
      }
      Statement repl = IdentifierExpression.createMethodCall(args, "jv_assert");
      parentNode.replaceChild(this, repl);
      return repl;
   }

   public RuntimeStatus execForRuntime(LayeredSystem sys) {
      if (expression != null)
         return expression.execForRuntime(sys);
      return RuntimeStatus.Unset;
   }

   public String toString() {
      return "assert " + (expression == null ? "<null>" : expression.toString() + (otherExpression == null ? "" : " : " + otherExpression));
   }
}
