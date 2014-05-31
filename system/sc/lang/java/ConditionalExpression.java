/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.BindingDirection;
import sc.dyn.DynUtil;
import sc.lang.SemanticNodeList;
import sc.parser.ISemanticWrapper;

/** Note: this class is not in the grammar.  We process it as a BindingExpression */
public class ConditionalExpression extends BinaryExpression implements ISemanticWrapper {
   public Object eval(Class expectedType, ExecutionContext ctx) {
      if (bindingDirection != null)
         return initBinding(expectedType, ctx);

      Object lhsVal;
      Object res = DynUtil.evalPreConditionalExpression(operator, lhsVal = lhs.eval(expectedType, ctx));

      if (res != null)
         return res;

      return DynUtil.evalConditionalExpression(operator, lhsVal, getRhsExpr().eval(expectedType, ctx));
   }

   public Object getTypeDeclaration() {
      return Boolean.TYPE;
   }

   public String getBindingTypeName() {
      return nestedBinding ? "conditionP" : "condition";
   }

   public void setBindingInfo(BindingDirection dir, Statement dest, boolean nested) {
      super.setBindingInfo(dir, dest, nested);
      if (dir.doReverse())
         System.err.println("*** Conditional expressions do not support reverse binding expression (the '=:' operator) " + toDefinitionString());
   }

   public static ConditionalExpression create(Expression lhs, String operator, Expression rhs) {
      return create(lhs, operator, rhs, false);
   }

   public static ConditionalExpression create(Expression lhs, String operator, Expression rhs, boolean nested) {
      ConditionalExpression newExpr = new ConditionalExpression();
      newExpr.isTreeNode = true;
      newExpr.setProperty("lhs", lhs, !nested, !nested);
      newExpr.setProperty("firstExpr", lhs, !nested, !nested);
      newExpr.operator = operator;
      newExpr.setProperty("rhs", rhs, !nested, !nested);

      SemanticNodeList<BaseOperand> ops = new SemanticNodeList<BaseOperand>();
      BinaryOperand bo = new BinaryOperand();
      bo.setProperty("rhs", rhs, !nested, !nested);
      bo.setProperty("operator", operator);
      ops.add(bo);
      newExpr.setProperty("operands", ops);

      return newExpr;
   }

   public Class getWrappedClass() {
      return BinaryExpression.class;
   }
}
