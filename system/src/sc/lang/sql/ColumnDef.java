package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.List;

public class ColumnDef extends TableDef {
   public SQLIdentifier columnName;
   public SQLDataType columnType;
   public String collation;
   public NamedConstraint constraintName;
   public List<SQLConstraint> columnConstraints;
}
