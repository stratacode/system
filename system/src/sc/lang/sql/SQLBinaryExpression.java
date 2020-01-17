package sc.lang.sql;

import java.util.List;

public class SQLBinaryExpression extends SQLExpression {
   public SQLExpression firstExpr;
   public List<SQLBinaryOperand> operands;
}
