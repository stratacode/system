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

   public static QuotedStringLiteral create(String str) {
      QuotedStringLiteral res = new QuotedStringLiteral();
      res.value = str;
      return res;
   }
}
