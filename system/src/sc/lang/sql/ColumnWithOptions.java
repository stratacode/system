package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.List;

public class ColumnWithOptions extends TableDef {
   public SQLIdentifier columnName;
   public NamedConstraint constraintName;
   public List<SQLConstraint> columnConstraints;
}
