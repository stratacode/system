/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ILanguageModel;
import sc.lang.SemanticNodeList;

import java.util.Set;

public class AssertStatement extends Statement
{
   public Expression expression;
   public Expression otherExpression;

   public ExecResult exec(ExecutionContext ctx) {
      Boolean val = (Boolean) expression.eval(Boolean.class, ctx);
      if (!val) {
         String message = otherExpression == null ? "Assertion failed: " + expression.toLanguageString() : (String) otherExpression.eval(String.class, ctx);
         throw new AssertionError(message);
      }
      return ExecResult.Next;
   }

   public void refreshBoundTypes() {
      if (expression != null)
         expression.refreshBoundTypes();
      if (otherExpression != null)
         otherExpression.refreshBoundTypes();
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      if (expression != null)
         ix = expression.transformTemplate(ix, statefulContext);
      if (otherExpression != null)
         ix = otherExpression.transformTemplate(ix, statefulContext);
      return ix;
   }

   public void addDependentTypes(Set<Object> types) {
      if (expression != null)
         expression.addDependentTypes(types);
      if (otherExpression != null)
         otherExpression.addDependentTypes(types);
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
}
