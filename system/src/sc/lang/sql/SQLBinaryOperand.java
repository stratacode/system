package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.Set;

public class SQLBinaryOperand extends SemanticNode {
   public String operator;
   public SQLExpression rhs;
}
