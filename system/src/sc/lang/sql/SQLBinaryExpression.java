package sc.lang.sql;

import sc.lang.SemanticNodeList;

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

   public static SQLBinaryExpression create(SQLExpression first, String op, SQLExpression second) {
      SQLBinaryExpression res = new SQLBinaryExpression();
      res.setProperty("firstExpr", first);
      SQLBinaryOperand operand = new SQLBinaryOperand();
      operand.operator = op;
      operand.setProperty("rhs", second);
      List<SQLBinaryOperand> ops = new SemanticNodeList<SQLBinaryOperand>();
      ops.add(operand);
      res.setProperty("operands", ops);
      return res;
   }

}
