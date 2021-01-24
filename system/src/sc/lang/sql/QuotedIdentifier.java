/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.SemanticNode;
import sc.util.StringUtil;

public class QuotedIdentifier extends SQLIdentifier {
   public String value;

   public String getIdentifier() {
      return value;
   }

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid) {
         return '"' + value + '"';
      }
      return super.toSafeLanguageString();
   }
}
