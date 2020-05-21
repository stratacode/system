/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISrcStatement;

import java.util.Set;

public abstract class ExpressionStatement extends Statement {
   public String operator;
   public Expression expression;

   public boolean callsSuper(boolean checkModSuper) {
      return expression != null && expression.callsSuper(checkModSuper);
   }

   public boolean callsSuperMethod(String methName) {
      return expression != null && expression.callsSuperMethod(methName);
   }

   public boolean callsThis() {
      return expression != null && expression.callsThis();
   }

   public void markFixedSuper() {
      if (expression != null)
         expression.markFixedSuper();
   }

   public Expression[] getConstrArgs() {
      return expression != null ? expression.getConstrArgs() : null;
   }

   public boolean refreshBoundTypes(int flags) {
      boolean res = false;
      if (expression != null)
         res = expression.refreshBoundTypes(flags);
      return res;
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (expression != null)
         expression.addDependentTypes(types, mode);
   }

   public void setAccessTimeForRefs(long time) {
      if (expression != null)
         expression.setAccessTimeForRefs(time);
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

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation, int max) {
      if (expression != null)
         return expression.suggestCompletions(prefix, currentType, ctx, command, cursor, candidates, continuation, max);
      return -1;
   }

   public boolean isLineStatement() {
      return true;
   }
}
