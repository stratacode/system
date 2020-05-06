package sc.lang.sql;

import sc.db.ColumnInfo;
import sc.db.DBUtil;
import sc.db.TableInfo;
import sc.dyn.DynUtil;
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
         for (TableDef def:tableDefs) {
            if (def.hasReferenceTo(cmd))
               return true;
         }
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

   private static List<ColumnDef> getColumnsAdded(CreateTable oldTable, CreateTable newTable, List<AlterDef> alterDefs, List<SchemaChangeDetail> notUpgradeable) {
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
               boolean sameType = oldCol.columnType.equals(newCol.columnType);
               boolean sameConstraints = DynUtil.equalObjects(oldCol.constraintName, newCol.constraintName) &&
                       DynUtil.equalObjects(oldCol.columnConstraints, newCol.columnConstraints);
               if (!sameType && sameConstraints && DBUtil.canCast(oldCol.columnType.getDBColumnType(), newCol.columnType.getDBColumnType())) {
                  if (alterDefs != null) {
                     alterDefs.add(AlterColumn.createSetDataType(newCol.columnName, newCol.columnType));
                  }
               }
               else {
                  SchemaChangeDetail scd = new SchemaChangeDetail();
                  scd.oldDef = oldCol;
                  scd.newDef = newCol;
                  if (!sameType) {
                     scd.message = " column type changed - from: " + oldCol.columnType + " to: " + newCol.columnType;
                     if (!sameConstraints)
                        scd.message += " and ";
                  }
                  else
                     scd.message = "";
                  if (!sameConstraints)
                     scd.message += "constraints changed";

                  notUpgradeable.add(scd);
                  DBUtil.info("Warning - unable to alter column: " + oldCol.columnName + " - alter schema will drop/add the column instead.");
                  if (toAddCols == null)
                     toAddCols = new ArrayList<ColumnDef>();
                  toAddCols.add(newCol); // This will drop and re-add the column
               }
            }
         }
         else
            oldTable.displayError("Unhandled table definition for migration: ");
      }
      return toAddCols;
   }

   public void alterTo(SQLFileModel resModel, SQLCommand newCmd, List<SchemaChangeDetail> notUpgradeable) {
      CreateTable newTable = (CreateTable) newCmd;
      List<AlterDef> alterDefs = new ArrayList<AlterDef>();
      List<ColumnDef> toAddCols = getColumnsAdded(this, newTable, alterDefs, notUpgradeable);
      List<ColumnDef> toRemCols = getColumnsAdded(newTable, this, null, notUpgradeable);
      if (toAddCols != null || toRemCols != null || alterDefs.size() > 0) {
         // Create:  ALTER TABLE tableName ADD COLUMN colName colType, ADD COLUMN colName, colType;
         AlterTable alterTable = AlterTable.create(newTable.tableName);
         if (toRemCols != null) {
            for (ColumnDef colDef:toRemCols) {
               alterTable.addAlterDef(DropColumn.create(colDef));
            }
         }
         if (toAddCols != null) {
            for (ColumnDef colDef:toAddCols) {
               alterTable.addAlterDef(AddColumn.create(colDef));
            }
         }
         if (alterDefs.size() > 0) {
            for (AlterDef alterDef:alterDefs)
               alterTable.addAlterDef(alterDef);
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

   public String toString() {
      return "CREATE TABLE " + tableName + "()";
   }
}
