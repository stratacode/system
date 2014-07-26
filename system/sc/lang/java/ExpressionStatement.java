/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISrcStatement;

import java.util.Set;

public abstract class ExpressionStatement extends Statement {
   public String operator;
   public Expression expression;

   public boolean callsSuper() {
      return expression != null && expression.callsSuper();
   }

   public boolean callsThis() {
      return expression != null && expression.callsThis();
   }

   public Expression[] getConstrArgs() {
      return expression != null ? expression.getConstrArgs() : null;
   }

   public void refreshBoundTypes() {
      if (expression != null)
         expression.refreshBoundTypes();
   }

   public void addDependentTypes(Set<Object> types) {
      if (expression != null)
         expression.addDependentTypes(types);
   }

   public Statement transformToJS() {
      if (expression != null)
         expression.transformToJS();
      return this;
   }

   public ISrcStatement findFromStatement(ISrcStatement toFind) {
      ISrcStatement res = super.findFromStatement(toFind);
      if (res != null)
         return res;
      if (expression != null)
         res = expression.findFromStatement(toFind);
      return res;
   }
}
