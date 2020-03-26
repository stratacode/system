package sc.lang.sql;

import sc.db.DBUtil;
import sc.lang.ISemanticNode;
import sc.lang.SemanticNode;
import sc.parser.IString;

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
      for (TableDef newTableDef:newTable.tableDefs) {
         if (newTableDef instanceof ColumnDef) {
            ColumnDef newCol = (ColumnDef) newTableDef;
            ColumnDef oldCol = findColumn(newCol.columnName.getIdentifier());
            if (oldCol == null) {
               resModel.addCommand((SQLCommand) newCol.deepCopy(ISemanticNode.CopyNormal | ISemanticNode.CopyParseNode, null));
            }
            else if (!oldCol.equals(newCol)) {
               displayError("Unhandled case of alter column for: ");
            }
         }
         else
            displayError("Unhandled table definition for migration: ");
      }
   }
}
