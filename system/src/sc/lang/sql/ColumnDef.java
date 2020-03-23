package sc.lang.sql;

import sc.lang.SemanticNode;
import sc.util.StringUtil;

import java.util.List;
import java.util.Set;

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

   public void addTableReferences(Set<String> refTableNames) {
      if (columnConstraints != null) {
         for (SQLConstraint c:columnConstraints)
            c.addTableReferences(refTableNames);
      }
   }

   public ReferencesConstraint getReferencesConstraint() {
      if (columnConstraints != null) {
         for (SQLConstraint constraint:columnConstraints)
            if (constraint instanceof ReferencesConstraint)
               return (ReferencesConstraint) constraint;
      }
      return null;
   }

   public boolean hasNotNullConstraint() {
      if (columnConstraints != null) {
         for (SQLConstraint constraint:columnConstraints)
            if (constraint instanceof NotNullConstraint)
               return true;
      }
      return false;
   }

   public boolean hasUniqueConstraint() {
      if (columnConstraints != null) {
         for (SQLConstraint constraint:columnConstraints)
            if (constraint instanceof UniqueConstraint)
               return true;
      }
      return false;
   }

   public String getDefaultExpression() {
      if (columnConstraints != null) {
         for (SQLConstraint constraint:columnConstraints)
            if (constraint instanceof DefaultConstraint)
               return ((DefaultConstraint) constraint).expression.toSafeLanguageString();
      }
      return null;
   }
}
