package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.Set;

public class ReferencesConstraint extends SQLConstraint {
   public SQLIdentifier refTable;
   public SQLIdentifier columnRef;
   public String matchOption;
   public String onOptions;

   public static ReferencesConstraint create(String tableName, String columnName) {
      ReferencesConstraint rc = new ReferencesConstraint();
      rc.setProperty("refTable", SQLIdentifier.create(tableName));
      rc.setProperty("columnRef", SQLIdentifier.create(columnName));
      return rc;
   }

   public void addTableReferences(Set<String> refTableNames) {
      if (refTable != null)
         refTableNames.add(refTable.toString());
   }
}
