/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.Set;

public class BinaryOperand extends BaseOperand {
   public Expression rhs;

   public BinaryOperand() {}

   public BinaryOperand(String op, Expression rhsExpr, boolean changeParseTree, boolean changeParent) {
      operator = op;
      setProperty("rhs", rhsExpr, changeParseTree, changeParent);
   }

   public Object getRhs() {
      return rhs;
   }

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates) {
      if (rhs == null)
         return -1;
      return rhs.suggestCompletions(prefix, currentType, ctx, command, cursor, candidates);
   }

   public boolean applyPartialValue(Object partial) {
      return rhs != null && rhs.applyPartialValue(partial);
   }

   public void visitTypeReferences(CycleInfo info, TypeContext ctx) {
      info.visit(rhs, ctx);
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      if (rhs != null)
         ix = rhs.transformTemplate(ix, statefulContext);
      return ix;
   }

}
