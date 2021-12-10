/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISemanticNode;

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

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation, int max) {
      if (rhs == null)
         return -1;
      return rhs.suggestCompletions(prefix, currentType, ctx, command, cursor, candidates, continuation, max);
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

   public int replaceChild(Object toReplace, Object replaceWith) {
      int ix =  super.replaceChild(toReplace, replaceWith);
      if (ix != -1) {
         BinaryExpression parentExpr = getEnclosingExpression();
         if (parentExpr != null) {
            if (!parentExpr.replaceInTree((JavaSemanticNode) toReplace, (JavaSemanticNode) replaceWith))
               System.err.println("*** Did not find the parent to replace for a binary operand");
         }
      }
      return ix;
   }

   public BinaryExpression getEnclosingExpression() {
      ISemanticNode par = parentNode;
      while (par != null && !(par instanceof BinaryExpression))
         par = par.getParentNode();

      if (par != null)
         return (BinaryExpression) par;
      return null;
   }

   public boolean formatSpaceBeforeAngleBracket() {
      return true;
   }

}
