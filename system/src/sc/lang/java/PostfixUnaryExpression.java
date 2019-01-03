/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.type.RTypeUtil;

// ++ or -- after the expression
public class PostfixUnaryExpression extends UnaryExpression {
   // Need to save/restore this as part of the model's serialized state
   Expression chainedExpression;

   public boolean isPrefix() {
      return false;
   }

   /**
    * In the grammar, when we set this property there is an optional selector expression
    * sitting in front of the PostfixUnaryExpression.  If the selector expr is present it is placed in
    * the expression property by the parser.  Later the rule for the primary will set the chainedExpression
    * property.  The selector expression really needs to operate on this expression when it is set.
    */
   public void setChainedExpression(Expression expr) {
      if (expression == null || !(expression instanceof SelectorExpression))
         super.setChainedExpression(expr);  // Set "expression" in ChainedExpression
      else {
         chainedExpression = expr;
         ((SelectorExpression)expression).expression = expr;
      }
   }

   public Expression getChainedExpression() {
      return chainedExpression != null ? chainedExpression : expression;
   }

   public void setProperty(Object selector, Object value) {
      String prop = RTypeUtil.getPropertyNameFromSelector(selector);
      super.setProperty(prop, value);
      if (prop.equals("expression")) {
         if (value instanceof SelectorExpression) {
            super.setProperty("chainedExpression", ((SelectorExpression)value).expression);
         }
      }
   }

   public static PostfixUnaryExpression create(String loopVar, String operator) {
      PostfixUnaryExpression pue = new PostfixUnaryExpression();
      pue.setProperty("chainedExpression", IdentifierExpression.create(loopVar));
      pue.operator = operator;
      return pue;
   }
}
