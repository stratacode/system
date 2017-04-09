/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISrcStatement;
import sc.parser.ParseUtil;

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

   public void refreshBoundTypes(int flags) {
      if (expression != null)
         expression.refreshBoundTypes(flags);
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

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation) {
      if (expression != null)
         return expression.suggestCompletions(prefix, currentType, ctx, command, cursor, candidates, continuation);
      return -1;
   }

}
