package sc.lang.sql;

import sc.lang.SemanticNode;

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
}
