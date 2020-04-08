package sc.lang.sql;

import sc.db.ColumnInfo;
import sc.db.DBUtil;
import sc.lang.SemanticNode;
import sc.util.StringUtil;

import java.sql.Types;
import java.util.List;
import java.util.Set;

public class ColumnDef extends TableDef {
   public SQLIdentifier columnName;
   public SQLDataType columnType;
   public Collation collation;
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

   public boolean hasReferenceTo(SQLCommand cmd) {
      if (columnConstraints != null) {
         for (SQLConstraint c:columnConstraints)
            if (c.hasReferenceTo(cmd))
               return true;
      }
      return false;
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

   public ColumnInfo createColumnInfo() {
      ColumnInfo info = new ColumnInfo();
      info.colName = columnName.getIdentifier();
      info.colType = columnType.getJDBCType();
      return info;
   }

   public ColumnInfo getMissingColumnInfo(ColumnInfo dbColInfo) {
      if (!dbColInfo.colName.equalsIgnoreCase(columnName.getIdentifier()))
         throw new UnsupportedOperationException();
      if (dbColInfo.colType != Types.OTHER) {
         if (columnType.getJDBCType() != dbColInfo.colType) {
            ColumnInfo diffs = new ColumnInfo();
            diffs.colType = columnType.getJDBCType();

            diffs.diffMessage = new StringBuilder("Mismatching column types - required: " + columnType.getIdentifier() + " database has: " +
                                                   SQLDataType.getNameForJDBCType(diffs.colType));
         }
      }
      return null;
   }
}
