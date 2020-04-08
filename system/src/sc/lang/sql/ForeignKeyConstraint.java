package sc.lang.sql;

import sc.lang.SemanticNode;
import sc.parser.IString;

import java.util.List;
import java.util.Set;

public class ForeignKeyConstraint extends SQLConstraint {
   public List<SQLIdentifier> columnList;
   public SQLIdentifier refTable;
   public List<SQLIdentifier> refColList;
   public String matchOption;
   public String onOptions;

   public void addTableReferences(Set<String> refTableNames) {
      if (refTable != null)
         refTableNames.add(refTable.toString());
   }

   public boolean hasReferenceTo(SQLCommand cmd) {
      if (cmd instanceof CreateTable && refTable != null) {
         SQLIdentifier tableName = ((CreateTable) cmd).tableName;
         return refTable.getIdentifier().equals(tableName.getIdentifier());
      }
      return false;
   }
}
