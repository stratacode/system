/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public abstract class BaseOperand extends JavaSemanticNode {
   public String operator;

   public abstract Object getRhs();

   public String toGenerateString() {
      Object rhsObj = getRhs();
      StringBuilder sb = new StringBuilder();
      if (operator != null) {
         sb.append(" ");
         sb.append(operator);
         sb.append(" ");
         if (rhsObj instanceof JavaSemanticNode)
            sb.append(((JavaSemanticNode) rhsObj).toGenerateString());
         else
            sb.append("? unknown operand: " + rhsObj);
         return sb.toString();
      }
      throw new UnsupportedOperationException();
   }

   public String toSafeLanguageString() {
      Object rhsObj = getRhs();
      if (operator != null && rhsObj instanceof JavaSemanticNode) {
         StringBuilder sb = new StringBuilder();
         sb.append(operator);
         sb.append(" ");
         sb.append(((JavaSemanticNode) rhsObj).toSafeLanguageString());
         return sb.toString();
      }
      return super.toSafeLanguageString();
   }
}
