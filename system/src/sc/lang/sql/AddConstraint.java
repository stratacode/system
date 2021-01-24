/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;

import java.util.Set;

public class AddConstraint extends AlterDef {
   public TableConstraint constraint;

   public static AlterDef create(SQLIdentifier colName, SQLConstraint toAddC) {
      if (toAddC instanceof UniqueConstraint) {
         AddConstraint res = new AddConstraint();
         UniqueConstraint uc = new UniqueConstraint();
         SemanticNodeList<SQLIdentifier> uniqueCols = new SemanticNodeList<SQLIdentifier>();
         uniqueCols.add((SQLIdentifier) colName.deepCopy(ISemanticNode.CopyNormal, null));
         uc.setProperty("columnList", uniqueCols);
         TableConstraint tc = new TableConstraint();
         tc.setProperty("constraint", uc);
         res.setProperty("constraint", tc);
         return res;
      }
      else if (toAddC instanceof NotNullConstraint) {
         AlterColumn res = new AlterColumn();
         res.columnName = (SQLIdentifier) colName.deepCopy(ISemanticNode.CopyNormal, null);
         AlterUpdateNotNull nn = new AlterUpdateNotNull();
         nn.op = "set";
         res.alterCmd = nn;
         return res;
      }
      // TODO: more cases we can alter?
      return null;
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
