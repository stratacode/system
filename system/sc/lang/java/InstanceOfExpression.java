/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.SemanticNodeList;
import sc.parser.ISemanticWrapper;
import sc.type.TypeUtil;

public class InstanceOfExpression extends BinaryExpression implements ISemanticWrapper {
   public JavaType type;
   {
      operator = "instanceof";
   }

   public void setRhs(JavaSemanticNode r) {
      rhs = r;
      type = (JavaType) r;
   }

   public static InstanceOfExpression create(Expression l, JavaType r) {
      return create(l, r, false);
   }

   public static InstanceOfExpression create(Expression l, JavaType r, boolean nested) {
      InstanceOfExpression newExpr = new InstanceOfExpression();
      newExpr.isTreeNode = true;
      newExpr.setProperty("lhs", l, !nested, !nested);
      newExpr.setProperty("firstExpr", l, !nested, !nested);
      newExpr.type = r;
      newExpr.setProperty("rhs", r, !nested, !nested);
      SemanticNodeList<BaseOperand> ops = new SemanticNodeList<BaseOperand>();
      InstanceOfOperand bo = new InstanceOfOperand();
      bo.setProperty("rhs", r, !nested, !nested);
      ops.add(bo);
      newExpr.setProperty("operands", ops);
      return newExpr;
   }

   public Object eval(Class expectedType, ExecutionContext ctx) {
      Object rtType = ModelUtil.getRuntimeType(type.getTypeDeclaration());
      if (rtType == null)
         throw new NullPointerException("Dynamic runtime: instanceof expression: " + toDefinitionString());
      Object lhsVal = lhs.eval(null, ctx);
      if (rtType instanceof Class)
         return TypeUtil.evalInstanceOfExpression(lhsVal, (Class) rtType);
      else
         return ModelUtil.evalInstanceOf(lhsVal, rtType, null);
   }

   public Object getTypeDeclaration() {
      return Boolean.class;
   }

   public void refreshBoundTypes() {
      super.refreshBoundTypes();
      if (type != null)
         type.refreshBoundType();
   }

   public Class getWrappedClass() {
      return BinaryExpression.class;
   }
}
