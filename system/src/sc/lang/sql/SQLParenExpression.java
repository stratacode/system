package sc.lang.sql;

import sc.lang.SemanticNode;

public class SQLParenExpression extends SQLExpression {
   public SQLExpression expression;

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid) {
         StringBuilder sb = new StringBuilder();
         sb.append("(");
         if (expression != null) {
            sb.append(expression.toSafeLanguageString());
         }
         sb.append(")");
         return sb.toString();
      }
      return super.toSafeLanguageString();
   }

   public static SQLParenExpression create(SQLExpression expr) {
      SQLParenExpression pe = new SQLParenExpression();
      pe.setProperty("expression", expr);
      return pe;
   }
}
