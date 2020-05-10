package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.List;
import java.util.Set;

public class ColumnWithOptions extends TableDef {
   public SQLIdentifier columnName;
   public NamedConstraint namedConstraint;
   public List<SQLConstraint> columnConstraints;

   public void addTableReferences(Set<String> refTableNames) {
      if (columnConstraints == null)
         return;
      for (SQLConstraint constraint:columnConstraints)
         constraint.addTableReferences(refTableNames);
   }

   public boolean hasReferenceTo(SQLCommand cmd) {
      if (columnConstraints == null)
         return false;
      for (SQLConstraint constraint:columnConstraints)
         if (constraint.hasReferenceTo(cmd))
            return true;
      return false;
   }

}
