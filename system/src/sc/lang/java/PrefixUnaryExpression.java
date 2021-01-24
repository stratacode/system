/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

/** Like PostpfixUnaryExpression but a different class to differentiate the semantic values */
public class PrefixUnaryExpression extends UnaryExpression {
   public static Expression create(String op, Expression expr) {
      PrefixUnaryExpression pue = new PrefixUnaryExpression();
      pue.operator = op;
      pue.setChainedExpression(expr);
      return pue;
   }
}
