package sc.lang.sql;

public class SQLPrefixUnaryExpression extends SQLExpression {
   public String operator;
   public SQLExpression expression;

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid) {
         StringBuilder sb = new StringBuilder();
         sb.append(operator);
         if (expression != null) {
            sb.append(expression.toSafeLanguageString());
         }
         return sb.toString();
      }
      return super.toSafeLanguageString();
   }
}
