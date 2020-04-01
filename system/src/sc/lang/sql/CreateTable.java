package sc.lang.sql;

import sc.db.DBUtil;
import sc.lang.ISemanticNode;
import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.parser.IString;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class CreateTable extends SQLCommand {
   public List<IString> tableOptions;
   public SQLIdentifier tableName;
   public SQLIdentifier ofType;
   public boolean ifNotExists;
   public List<TableDef> tableDefs;
   public List<SQLIdentifier> tableInherits;
   public TablePartition tablePartition;
   public IndexParameters storageParams;
   public String tableSpace;

   void addTableReferences(Set<String> refTableNames) {
      if (tableDefs != null) {
         for (TableDef def:tableDefs)
            def.addTableReferences(refTableNames);
      }
   }

   public ColumnDef findColumn(String colName) {
      if (tableDefs != null) {
         for (TableDef td:tableDefs)
            if (td instanceof ColumnDef && ((ColumnDef) td).columnName.getIdentifier().equals(colName))
               return (ColumnDef) td;
      }
      return null;
   }

   public void alterTo(SQLFileModel resModel, CreateTable newTable) {
      List<ColumnDef> toAddCols = null;
      for (TableDef newTableDef:newTable.tableDefs) {
         if (newTableDef instanceof ColumnDef) {
            ColumnDef newCol = (ColumnDef) newTableDef;
            ColumnDef oldCol = findColumn(newCol.columnName.getIdentifier());
            if (oldCol == null) {
               if (toAddCols == null)
                  toAddCols = new ArrayList<ColumnDef>();
               toAddCols.add(newCol);
            }
            else if (!oldCol.equals(newCol)) {
               displayError("Unhandled case of alter column for: ");
            }
         }
         else
            displayError("Unhandled table definition for migration: ");
      }
      if (toAddCols != null) {
         // Create:  ALTER TABLE tableName ADD COLUMN colName colType, ADD COLUMN colName, colType;
         AlterTable alterTable = AlterTable.create(newTable.tableName);
         for (ColumnDef colDef:toAddCols) {
            alterTable.addAlterDef(AddColumn.create(colDef));
         }
         resModel.addCommand(alterTable);
      }
   }

   public String toDeclarationString() {
      return "table " + tableName;
   }

   public SQLCommand getDropCommand() {
      DropTable dt = new DropTable();
      dt.tableNames = new SemanticNodeList<SQLIdentifier>();
      dt.tableNames.add((SQLIdentifier) tableName.deepCopy(ISemanticNode.CopyNormal, null));
      return dt;
   }
}
