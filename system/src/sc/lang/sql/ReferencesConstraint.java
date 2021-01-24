/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

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

   public boolean hasReferenceTo(SQLCommand cmd) {
      if (cmd instanceof CreateTable && refTable != null) {
         SQLIdentifier tableName = ((CreateTable) cmd).tableName;
         return refTable.getIdentifier().equals(tableName.getIdentifier());
      }
      return false;
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(" REFERENCES ");
      sb.append(refTable);
      sb.append("(");
      sb.append(columnRef);
      sb.append(")");
      return sb.toString();
   }
}
