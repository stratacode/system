/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public abstract class BaseOperand extends JavaSemanticNode {
   public String operator;

   public abstract Object getRhs();

   public String toGenerateString() {
      Object rhsObj = getRhs();
      if (operator != null && rhsObj instanceof JavaSemanticNode) {
         StringBuilder sb = new StringBuilder();
         sb.append(" ");
         sb.append(operator);
         sb.append(" ");
         sb.append(((JavaSemanticNode) rhsObj).toGenerateString());
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
