package sc.lang.sql;

public abstract class BaseKeywordLiteral extends SQLExpression {
   public String value;

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid) {
         return value;
      }
      return super.toSafeLanguageString();
   }
}
