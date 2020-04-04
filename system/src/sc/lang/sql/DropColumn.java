package sc.lang.sql;

import sc.lang.ISemanticNode;

public class DropColumn extends AlterDef {
   public boolean ifExists;
   public SQLIdentifier columnName;
   public String dropOptions;

   public static DropColumn create(ColumnDef colDef) {
      DropColumn res = new DropColumn();
      res.columnName = (SQLIdentifier) colDef.columnName.deepCopy(ISemanticNode.CopyNormal, null);
      return res;
   }
}
