/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

public class HexStringLiteral extends SQLExpression {
   public String value;

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid) {
         return "'" + value + "'";
      }
      return super.toSafeLanguageString();
   }
}
