package sc.lang.sql;

import sc.db.DBPropertyDescriptor;
import sc.db.DBTypeDescriptor;
import sc.db.DBUtil;
import sc.db.TableDescriptor;
import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.java.*;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.sc.SCModel;
import sc.layer.Layer;
import sc.type.CTypeUtil;
import sc.type.Modifier;
import sc.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

public class SQLFileModel extends SCModel {
   public List<SQLCommand> sqlCommands;

   public void init() {
      if (initialized) return;

      String fullTypeName = getModelTypeName();
      String typeName = CTypeUtil.getClassName(fullTypeName);

      if (sqlCommands == null) {
         displayError("No sql commands in file to define type: " + typeName + ": for: ");
         return;
      }

      String sqlName = DBUtil.getSQLName(typeName);
      String auxPrefix = sqlName + "_";
      CreateTable primaryTable = null;
      List<CreateTable> auxTables = new ArrayList<CreateTable>();
      for (SQLCommand cmd:sqlCommands) {
         if (cmd instanceof CreateTable) {
            CreateTable createTable = (CreateTable) cmd;
            String tableName = createTable.tableName.toString();
            if (StringUtil.equalStrings(tableName, sqlName)) {
               if (primaryTable != null) {
                  displayError("Redefining primary table: " + createTable.tableName + " - already defined at: " + primaryTable.toLocationString());
               }
               else
                  primaryTable = createTable;
            }
            else if (tableName.startsWith(auxPrefix)) {
               auxTables.add(createTable);
            }
            else
               displayError("Table name: " + tableName + " - should match type name: " + typeName + " in sql form: " + sqlName);
         }
      }

      if (primaryTable == null) {
         displayError("Missing table named: " + sqlName + " - It should match type name: " + typeName);
      }

      Layer typeLayer = getLayer();

      TypeDeclaration prevType = (TypeDeclaration) getPreviousDeclaration(fullTypeName, false);
      TypeDeclaration newType = null;
      SemanticNodeList<Object> mods = new SemanticNodeList<Object>();

      // Mark this type with DBTypeSettings - to enable persistence for this type
      mods.add(Annotation.create("sc.db.DBTypeSettings"));

      if (prevType == null) {
         newType = ClassDeclaration.create("class", typeName, ClassType.create("sc.db.DBObject"));
         if (typeLayer != null && typeLayer.defaultModifier != null) {
            mods.add(typeLayer.defaultModifier);
         }
      }
      else {
         newType = ModifyDeclaration.create(typeName);
      }

      newType.setProperty("modifiers", mods);
      newType.parentNode = this;
      // TODO: set this on the primary table once it's an ISrcStatement
      //newType.fromStatement = this;
      addTypeDeclaration(newType);

      if (primaryTable != null)
         addTableProperties(newType, primaryTable, true);

      for (CreateTable auxTable:auxTables)
         addTableProperties(newType, auxTable, false);
   }

   // Fills in any missing properties in the type from the information we have in the SQL definition.
   private void addTableProperties(TypeDeclaration type, CreateTable table, boolean primary) {
      if (table.tableDefs == null) {
         return; // TODO: should we display an error here?
      }
      for (TableDef tableDef:table.tableDefs) {
         if (tableDef instanceof ColumnDef) {
            ColumnDef colDef = (ColumnDef) tableDef;
            SQLIdentifier colIdent = colDef.columnName;
            String colName;
            if (colIdent != null) {
               colName = colIdent.toString();
               String propName = DBUtil.getJavaName(colName);
               SQLDataType colType = colDef.columnType;
               String javaTypeName = colType.getJavaTypeName();
               if (javaTypeName == null)
                  continue;
               JavaType javaType = JavaType.createJavaTypeFromName(javaTypeName);
               Object member = type.definesMember(propName, MemberType.PropertyAnySet, type, null);
               if (member == null) {
                  FieldDefinition field = FieldDefinition.create(getLayeredSystem(), javaType, propName);
                  if (colDef.isPrimaryKey())
                     field.addModifier("@sc.obj.Constant");
                  field.addModifier("public");
                  type.addBodyStatementIndent(field);
               }
            }
            else
               displayError("No name for column definition");
         }
      }
   }


   public void addCreateTable(TableDescriptor tableDesc) {
      // TODO: TableDescriptor is a big subset of 'CreateTable' - need to either fill it out or preserve it by modifying the source when augmenting DDL
      CreateTable ct = new CreateTable();
      ct.setProperty("tableName", SQLIdentifier.create(tableDesc.tableName));
      SemanticNodeList<TableDef> tableDefs = new SemanticNodeList<TableDef>();
      appendColumnDefs(tableDefs, tableDesc.idColumns, true);
      appendColumnDefs(tableDefs, tableDesc.columns, false);
      ct.setProperty("tableDefs", tableDefs);

      if (sqlCommands == null) {
         setProperty("sqlCommands", new SemanticNodeList<SQLCommand>());
      }
      sqlCommands.add(ct);
   }

   private void appendColumnDefs(List<TableDef> tableDefs, List<? extends DBPropertyDescriptor> propDescList, boolean isId) {
      for (DBPropertyDescriptor propDesc:propDescList) {
         ColumnDef colDef = new ColumnDef();
         colDef.setProperty("columnName", SQLIdentifier.create(propDesc.columnName));
         colDef.setProperty("columnType", SQLDataType.create(propDesc.columnType));
         if (isId && propDescList.size() == 1) {
            SemanticNodeList<SQLConstraint> constraints = new SemanticNodeList<SQLConstraint>();
            constraints.add(new PrimaryKeyConstraint());
            colDef.setProperty("columnConstraints", constraints);
         }
         tableDefs.add(colDef);
      }
   }
}
