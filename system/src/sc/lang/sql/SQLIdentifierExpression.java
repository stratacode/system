/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

public class SQLIdentifierExpression extends SQLExpression {
   public SQLIdentifier identifier;

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid) {
         return identifier.toSafeLanguageString();
      }
      return super.toSafeLanguageString();
   }

   public static SQLIdentifierExpression create(String ident) {
      SQLIdentifierExpression res = new SQLIdentifierExpression();
      res.setProperty("identifier", SQLIdentifier.create(ident));
      return res;
   }
}
