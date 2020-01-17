package sc.lang.sql;

import sc.lang.SemanticNode;

public class SQLPrefixUnaryExpression extends SQLExpression {
   public String operator;
   public SQLExpression expression;
}
