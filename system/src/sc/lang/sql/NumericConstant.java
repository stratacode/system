/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

public class NumericConstant extends SQLExpression {
   public String numberPart;
   public String fractionPart;
   public String exponent;
   public boolean negativeValue;

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid) {
         return numberPart + (fractionPart != null ? fractionPart : "") + (exponent != null ? exponent : "");
      }
      return super.toSafeLanguageString();
   }
}
