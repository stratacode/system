/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.SemanticNode;

public class BitStringLiteral extends SQLExpression {
   public String value;

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid) {
         return "'" + value + "'";
      }
      return super.toSafeLanguageString();
   }
}
