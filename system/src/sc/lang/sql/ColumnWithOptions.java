package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.List;
import java.util.Set;

public class ColumnWithOptions extends TableDef {
   public SQLIdentifier columnName;
   public NamedConstraint constraintName;
   public List<SQLConstraint> columnConstraints;

   public void addTableReferences(Set<String> refTableNames) {
   }
}
