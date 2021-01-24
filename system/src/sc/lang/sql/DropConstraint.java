/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.ISemanticNode;

import java.util.Set;

public class DropConstraint extends AlterDef {
   public SQLConstraint constraint;

   public static AlterDef create(String tableName, String colName, SQLConstraint constraint) {
      if (constraint instanceof NotNullConstraint) {
         AlterColumn res = new AlterColumn();
         res.columnName = SQLIdentifier.create(colName);
         AlterUpdateNotNull nn = new AlterUpdateNotNull();
         nn.op = "drop";
         res.alterCmd = nn;
         return res;
      }
      else {
         DropConstraint res = new DropConstraint();

         if (constraint instanceof NamedConstraint) {
            res.setProperty("constraint", (NamedConstraint) constraint.deepCopy(ISemanticNode.CopyNormal, null));
         }
         else if (constraint instanceof UniqueConstraint) {
            String constraintName = tableName + "_" + colName + "_key";
            NamedConstraint nc = new NamedConstraint();
            nc.constraintName = SQLIdentifier.create(constraintName);
            res.setProperty("constraint", nc);
         }
         else
            return null;
         return res;
      }
   }

   public void addTableReferences(Set<String> refTableNames) {
      if (constraint != null)
         constraint.addTableReferences(refTableNames);
   }

   public boolean hasReferenceTo(SQLCommand cmd) {
      if (constraint != null)
         return constraint.hasReferenceTo(cmd);
      return false;
   }
}
