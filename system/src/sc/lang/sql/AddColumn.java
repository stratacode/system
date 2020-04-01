package sc.lang.sql;

import sc.lang.ISemanticNode;

import java.util.Set;

public class AddColumn extends AlterDef {
   public boolean ifNotExists;
   public ColumnDef columnDef;

   public static AlterDef create(ColumnDef colDef) {
      AddColumn res = new AddColumn();

      res.columnDef = (ColumnDef) colDef.deepCopy(ISemanticNode.CopyNormal, null);
      return res;
   }

   public void addTableReferences(Set<String> refTableNames) {
      if (columnDef != null)
         columnDef.addTableReferences(refTableNames);
   }
}
