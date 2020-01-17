package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.List;

public class FunctionCall extends SQLExpression {
   public SQLIdentifier functionName;
   public List<SQLExpression> expressionList;

}
