package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.List;

public class FunctionCall extends SQLExpression {
   public SQLIdentifier functionName;
   public List<SQLExpression> expressionList;

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid) {
         StringBuilder sb = new StringBuilder();
         sb.append(functionName);
         sb.append("(");
         if (expressionList != null) {
            for (int i = 0; i < expressionList.size(); i++) {
               if (i != 0)
                  sb.append(", ");
               SQLExpression expr = expressionList.get(i);
               sb.append(expr.toSafeLanguageString());
            }
         }
         sb.append(")");
         return sb.toString();
      }
      return super.toSafeLanguageString();
   }
}
