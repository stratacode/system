/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.dyn.DynUtil;
import sc.lang.SemanticNodeList;
import sc.parser.ISemanticWrapper;

/**
 * Represents an ArithmeticExpression in the Java language model but only when created
 * in code.  The parser will create a BinaryExpression with a list of operands.  The ArithmeticExpression
 * is created by the BinaryExpression when it builds the tree version of the expression. 
 */
public class ArithmeticExpression extends BinaryExpression implements ISemanticWrapper {

   public Object eval(Class expectedType, ExecutionContext ctx) {
      if (bindingDirection != null)
         return initBinding(expectedType, ctx);
      return DynUtil.evalArithmeticExpression(operator, expectedType, lhs.eval(expectedType, ctx), getRhsExpr().eval(expectedType, ctx));
   }

   public Object getTypeDeclaration() {
      return getExpressionType(lhs, operator, getRhsExpr());
   }

   public static Object getExpressionType(Expression lhs, String operator, Expression rhs) {
      Object lhsType = lhs.getTypeDeclaration();
      Object rhsType;
      if (operator.equals("+")) {
         if (ModelUtil.isString(lhsType))
            return lhsType;
         rhsType = rhs.getTypeDeclaration();
         if (ModelUtil.isString(rhsType))
            return rhsType;
      }
      else
         rhsType = rhs.getTypeDeclaration();
      return ModelUtil.coerceNumberTypes(lhsType, rhsType);
   }

   public String getBindingTypeName() {
      return nestedBinding ? "arithP" : "arith";
   }

   public static ArithmeticExpression create(Expression lhs, String operator, Expression rhs) {
      return create(lhs, operator, rhs, false);
   }

   /** This variant adds the nested property - used when the lhs and rhs properties already live in the semantic tree rooted from another node.  In this case, do not consider these nodes really part of the tree since the parent is already set. */
   public static ArithmeticExpression create(Expression lhs, String operator, Expression rhs, boolean nested) {
      ArithmeticExpression newExpr = new ArithmeticExpression();
      newExpr.isTreeNode = true;
      newExpr.setProperty("lhs", lhs, !nested, !nested);
      newExpr.setProperty("firstExpr", lhs, !nested, !nested);
      newExpr.operator = operator;
      newExpr.setProperty("rhs", rhs, !nested, !nested);
      SemanticNodeList<BaseOperand> newOps = new SemanticNodeList<BaseOperand>(1);
      newOps.add(new BinaryOperand(operator, rhs, !nested, !nested));
      newExpr.setProperty("operands", newOps);
      return newExpr;
   }

   public Class getWrappedClass() {
      return BinaryExpression.class;
   }
}
