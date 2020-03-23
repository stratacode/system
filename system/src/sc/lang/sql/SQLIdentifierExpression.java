package sc.lang.sql;

public class SQLIdentifierExpression extends SQLExpression {
   public SQLIdentifier identifier;

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid) {
         return identifier.toSafeLanguageString();
      }
      return super.toSafeLanguageString();
   }
}
