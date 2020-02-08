package sc.lang.sql;

import sc.lang.SemanticNode;
import sc.util.StringUtil;

import java.util.List;

public class ColumnDef extends TableDef {
   public SQLIdentifier columnName;
   public SQLDataType columnType;
   public String collation;
   public NamedConstraint constraintName;
   public List<SQLConstraint> columnConstraints;

   public boolean isPrimaryKey() {
      if (columnConstraints == null)
         return false;
      for (SQLConstraint constraint:columnConstraints)
         if (constraint instanceof PrimaryKeyConstraint)
            return true;
      return false;
   }

   public String toString() {
      return columnName + " " + columnType + (collation == null ? "" : collation) + (constraintName == null ? "" : constraintName) + (columnConstraints == null ? "" : " " + StringUtil.argsToString(columnConstraints));
   }
}
