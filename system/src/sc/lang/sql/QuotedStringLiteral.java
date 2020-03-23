package sc.lang.sql;

import sc.lang.SemanticNode;

public class QuotedStringLiteral extends SQLExpression {
   public String value;

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid) {
         return "'" + value + "'";
      }
      return super.toSafeLanguageString();
   }
}
