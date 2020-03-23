package sc.lang.sql;

import java.util.List;

public class SQLBinaryExpression extends SQLExpression {
   public SQLExpression firstExpr;
   public List<SQLBinaryOperand> operands;

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid) {
         StringBuilder sb = new StringBuilder();
         if (firstExpr != null)
            sb.append(firstExpr.toSafeLanguageString());
         if (operands != null) {
            for (SQLBinaryOperand op:operands)
               sb.append(op.toSafeLanguageString());
         }
         return sb.toString();
      }
      return super.toSafeLanguageString();
   }

}
