package sc.lang.sql;

import sc.db.ColumnInfo;
import sc.db.DBUtil;
import sc.db.TableInfo;
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

   public boolean hasReferenceTo(SQLCommand cmd) {
      if (tableDefs != null) {
         for (TableDef def:tableDefs)
            def.hasReferenceTo(cmd);
      }
      return false;
   }

   public ColumnDef findColumn(String colName) {
      if (tableDefs != null) {
         for (TableDef td:tableDefs)
            if (td instanceof ColumnDef && ((ColumnDef) td).columnName.getIdentifier().equals(colName))
               return (ColumnDef) td;
      }
      return null;
   }

   private static List<ColumnDef> getColumnsAdded(CreateTable oldTable, CreateTable newTable) {
      List<ColumnDef> toAddCols = null;
      for (TableDef newTableDef:newTable.tableDefs) {
         if (newTableDef instanceof ColumnDef) {
            ColumnDef newCol = (ColumnDef) newTableDef;
            ColumnDef oldCol = oldTable.findColumn(newCol.columnName.getIdentifier());
            if (oldCol == null) {
               if (toAddCols == null)
                  toAddCols = new ArrayList<ColumnDef>();
               toAddCols.add(newCol);
            }
            else if (!oldCol.equals(newCol)) {
               oldTable.displayError("Unhandled case of alter column for: ");
            }
         }
         else
            oldTable.displayError("Unhandled table definition for migration: ");
      }
      return toAddCols;
   }

   public void alterTo(SQLFileModel resModel, SQLCommand newCmd) {
      CreateTable newTable = (CreateTable) newCmd;
      List<ColumnDef> toAddCols = getColumnsAdded(this, newTable);
      List<ColumnDef> toRemCols = getColumnsAdded(newTable, this);
      if (toAddCols != null || toRemCols != null) {
         // Create:  ALTER TABLE tableName ADD COLUMN colName colType, ADD COLUMN colName, colType;
         AlterTable alterTable = AlterTable.create(newTable.tableName);
         if (toAddCols != null) {
            for (ColumnDef colDef:toAddCols) {
               alterTable.addAlterDef(AddColumn.create(colDef));
            }
         }
         if (toRemCols != null) {
            for (ColumnDef colDef:toRemCols) {
               alterTable.addAlterDef(DropColumn.create(colDef));
            }
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

   public String getIdentifier() {
      return tableName.getIdentifier();
   }

   /** Given the current database metadata, return any missing information */
   public TableInfo getMissingTableInfo(TableInfo ti) {
      if (tableDefs == null)
         return null;
      TableInfo diffs = null;
      for (TableDef tableDef:tableDefs) {
         if (tableDef instanceof ColumnDef) {
            ColumnDef colDef = (ColumnDef) tableDef;
            ColumnInfo dbColInfo = ti.getColumnInfo(colDef.columnName.getIdentifier());
            if (dbColInfo == null) {
               if (diffs == null)
                  diffs = new TableInfo(tableName.getIdentifier());
               diffs.colInfos.add(colDef.createColumnInfo());
            }
            else {
               /** Does the db entity match - if not, get the diffs and add them to the list */
               ColumnInfo colDiffs = colDef.getMissingColumnInfo(dbColInfo);
               if (colDiffs != null) {
                  if (diffs == null)
                     diffs = new TableInfo(tableName.getIdentifier());
                  diffs.colInfos.add(colDiffs);
               }
            }
         }
      }
      return null;
   }
}
