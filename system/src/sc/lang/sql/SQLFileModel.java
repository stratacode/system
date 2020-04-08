package sc.lang.sql;

import sc.db.*;
import sc.lang.*;
import sc.lang.java.*;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.sc.SCModel;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.parser.*;
import sc.type.CTypeUtil;
import sc.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class SQLFileModel extends SCModel {
   public List<SQLCommand> sqlCommands;

   public transient BodyTypeDeclaration srcType;

   public void init() {
      if (initialized) return;

      restoreParseNode();

      String fullTypeName = getModelTypeName();
      if (fullTypeName == null) {
         return; // This happens in TestUtil when there's no file registered for the model
      }
      String typeName = CTypeUtil.getClassName(fullTypeName);

      if (sqlCommands == null) {
         displayError("No sql commands in file to define type: " + typeName + ": for: ");
         return;
      }

      LayeredSystem sys = getLayeredSystem();

      DBDataSource dataSource = sys.defaultDataSource;
      if (dataSource == null) {
         displayWarning("No default data source: skipping scsql file: ");
         return;
      }
      String dataSourceName = dataSource.jndiName;
      DBProvider provider = DBProvider.getDBProviderForDataSource(sys, getLayer(), dataSourceName);
      if (provider == null) {
         displayWarning("No provider for data source: " + dataSourceName + " skipping scsql file: ");
         return;
      }

      SQLFileModel old = provider.addSchema(dataSourceName, fullTypeName, this);
      if (old != null) {
         // TODO: should we do a smart merge here - i.e. allow the next create table to override a previous one
         // maybe support way to add to a table - for now, we're just going to let the new one replace the old one
         displayWarning("Replacing old schema for type: " + fullTypeName);
      }

      String sqlName = SQLUtil.getSQLName(typeName);
      String auxPrefix = sqlName + "_";
      StringBuilder schemaSQL = null;
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
         else {
            if (schemaSQL == null)
               schemaSQL = new StringBuilder();
            schemaSQL.append(cmd.toLanguageString());
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
      if (schemaSQL != null) {
         mods.add(Annotation.create("sc.db.SchemaSQL", "value", schemaSQL.toString()));
      }

      if (prevType == null) {
         newType = ClassDeclaration.create("class", typeName, null);
         newType.addImplements(ClassType.create("sc.db.IDBObject"));
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

      srcType = newType;

      if (primaryTable != null)
         addTableProperties(newType, primaryTable, prevType, true);
      else
         displayError("No primary table: " + sqlName + " defined in: ");

      for (CreateTable auxTable:auxTables)
         addTableProperties(newType, auxTable, prevType, false);

      super.init();
   }

   // Fills in any missing properties in the type from the information we have in the SQL definition.
   private void addTableProperties(TypeDeclaration type, CreateTable table, TypeDeclaration prevType, boolean primary) {
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
               propName = CTypeUtil.decapitalizePropertyName(propName);
               ReferencesConstraint refConstr = colDef.getReferencesConstraint();
               String javaTypeName;
               if (refConstr != null) {
                  javaTypeName = DBUtil.getJavaName(refConstr.refTable.toString());
                  // TODO: if this is a multi table, we should make this List<typeName>
               }
               else {
                  SQLDataType colType = colDef.columnType;
                  javaTypeName = colType.getJavaTypeName();
               }
               if (javaTypeName == null)
                  continue;
               JavaType javaType = JavaType.createJavaTypeFromName(javaTypeName);
               Object member = prevType == null ? null : prevType.definesMember(propName, MemberType.PropertyAnySet, type, null);
               boolean required = colDef.hasNotNullConstraint();
               boolean unique = colDef.hasUniqueConstraint();
               String dbDefault = colDef.getDefaultExpression();
               if (member == null) {
                  FieldDefinition field = FieldDefinition.createFromJavaType(javaType, propName);
                  if (colDef.isPrimaryKey()) {
                     field.addModifier(Annotation.create("sc.obj.Constant"));
                     Annotation annot = Annotation.create("sc.db.IdSettings");
                     if (DBUtil.isDefinedInDBColumnType(colDef.columnType.toString())) {
                        annot.addAnnotationValues(AnnotationValue.create("definedByDB", true));
                     }
                     field.addModifier(annot);
                  }
                  else if (required || unique || dbDefault != null)
                     addDBPropertySettings(field, required, unique, dbDefault);
                  field.addModifier("public");
                  type.addBodyStatementIndent(field);
               }
               else {
                  if (required || unique || dbDefault != null) {
                     if (member instanceof Definition)
                        addDBPropertySettings((Definition) member, required, unique, dbDefault);
                     else
                        displayError("Unhandled case - no way to set unique/required on property: " + member);
                  }
               }
            }
            else
               displayError("No name for column definition");
         }
      }
   }

   void addDBPropertySettings(Definition def, boolean required, boolean unique, String dbDefault) {
      Annotation annot = Annotation.create("sc.db.DBPropertySettings");
      if (required) {
         AnnotationValue reqVal = AnnotationValue.create("required", Boolean.TRUE);
         annot.addAnnotationValues(reqVal);
      }
      if (unique) {
         AnnotationValue reqVal = AnnotationValue.create("unique", Boolean.TRUE);
         annot.addAnnotationValues(reqVal);
      }
      if (dbDefault != null) {
         AnnotationValue reqVal = AnnotationValue.create("dbDefault", dbDefault);
         annot.addAnnotationValues(reqVal);
      }
      def.addModifier(annot);
   }

   private CreateTable findCreateTable(String tableName) {
      if (sqlCommands == null)
         return null;
      for (SQLCommand cmd:sqlCommands) {
         if (cmd instanceof CreateTable) {
            CreateTable ct = (CreateTable) cmd;
            if (ct.tableName.getIdentifier().equals(tableName))
               return ct;
         }
      }
      return null;
   }

   public SQLCommand findSameCommand(SQLCommand oldCmd) {
      if (sqlCommands == null)
         return null;
      for (SQLCommand cmd:sqlCommands) {
         if (cmd.getClass() == oldCmd.getClass()) {
            if (cmd.equals(oldCmd))
               return cmd;
         }
      }
      return null;
   }

   public SQLCommand findMatchingCommand(SQLCommand oldCmd) {
      if (sqlCommands == null)
         return null;
      for (SQLCommand cmd:sqlCommands) {
         if (cmd.getClass() == oldCmd.getClass()) {
            if (StringUtil.equalStrings(cmd.getIdentifier(), oldCmd.getIdentifier()))
               return cmd;
         }
      }
      return null;
   }

   public void replaceCommand(SQLCommand newCmd) {
      if (sqlCommands == null) {
         addCommand(newCmd);
         return;
      }
      for (int i = 0; i < sqlCommands.size(); i++) {
         SQLCommand oldCmd = sqlCommands.get(i);
         if (oldCmd.getClass() == newCmd.getClass()) {
            if (newCmd.equals(oldCmd))
               return;
            String ident = newCmd.getIdentifier();
            if (ident == null) { // TODO: not sure what to do here
               System.err.println("*** Unable to replace/merge SQL commands");
            }
            else if (StringUtil.equalStrings(newCmd.getIdentifier(), oldCmd.getIdentifier())) {
               sqlCommands.set(i, newCmd);
               return;
            }
         }
      }
   }

   public void addCreateTable(TableDescriptor tableDesc) {
      String tableName = tableDesc.tableName;
      CreateTable createTable = findCreateTable(tableName);
      boolean newTable = false;
      if (createTable == null) {
         createTable = new CreateTable();
         createTable.setProperty("tableName", SQLIdentifier.create(tableDesc.tableName));
         newTable = true;
      }
      SemanticNodeList<TableDef> tableDefs = new SemanticNodeList<TableDef>();
      appendColumnDefs(createTable, tableDefs, tableDesc.getIdColumns(), true, tableDesc.multiRow);
      appendColumnDefs(createTable, tableDefs, tableDesc.columns, false, tableDesc.multiRow);
      if (createTable.tableDefs == null)
         createTable.setProperty("tableDefs", tableDefs);
      else {
         createTable.tableDefs.addAll(tableDefs);
      }

      if (newTable) {
         if (sqlCommands == null) {
            setProperty("sqlCommands", new SemanticNodeList<SQLCommand>());
         }
         sqlCommands.add(createTable);
      }
   }

   private void appendColumnDefs(CreateTable createTable, List<TableDef> tableDefs, List<? extends DBPropertyDescriptor> propDescList, boolean isId, boolean multiRow) {
      for (DBPropertyDescriptor propDesc:propDescList) {
         String colName = propDesc.columnName;
         String colType = propDesc.columnType;
         ColumnDef colDef = createTable == null ? null : createTable.findColumn(colName);

         if (colDef != null) {
            SQLDataType oldColType = colDef.columnType;
            if (oldColType.typeName == null || !oldColType.typeName.equalsIgnoreCase(colType))
               System.err.println("*** column type conflict for: " + colName);
            continue;
         }

         colDef = new ColumnDef();
         colDef.setProperty("columnName", SQLIdentifier.create(propDesc.columnName));
         colDef.setProperty("columnType", SQLDataType.create(propDesc.columnType));
         // If it's a single column primary key, add it here - otherwise, it's a new constraint at the table level
         if (isId && propDescList.size() == 1) {
            SemanticNodeList<SQLConstraint> constraints = new SemanticNodeList<SQLConstraint>();
            if (multiRow) {
               TableDescriptor primaryTable = propDesc.dbTypeDesc.primaryTable;
               constraints.add(ReferencesConstraint.create(primaryTable.tableName, primaryTable.idColumns.get(0).columnName));
            }
            else
               constraints.add(new PrimaryKeyConstraint());
            colDef.setProperty("columnConstraints", constraints);
         }
         if (propDesc.refTypeName != null) {
            Object refType = findTypeDeclaration(propDesc.refTypeName, false);
            if (refType == null) {
               System.err.println("*** Failed to find refType: " + propDesc.refTypeName + " for: " + propDesc.propertyName);
            }
            else {
               DBTypeDescriptor refTypeDesc = DBProvider.getDBTypeDescriptor(layeredSystem, getLayer(), refType, true);
               if (refTypeDesc == null) {
                  System.err.println("*** Failed to find DBTypeDescriptor for refType: " + propDesc.refTypeName + " for: " + propDesc.propertyName);
               }
               else {
                  SemanticNodeList<SQLConstraint> constraints = new SemanticNodeList<SQLConstraint>();
                  constraints.add(ReferencesConstraint.create(refTypeDesc.primaryTable.tableName, refTypeDesc.primaryTable.idColumns.get(0).columnName));
                  colDef.setProperty("columnConstraints", constraints);
               }
            }
         }
         List<SQLConstraint> constraints = colDef.columnConstraints;
         boolean setProp = false;
         if (propDesc.required) {
            if (constraints == null) {
               constraints = new SemanticNodeList<SQLConstraint>();
               setProp = true;
            }
            constraints.add(new NotNullConstraint());
         }
         if (propDesc.unique && !isId) {
            if (constraints == null) {
               constraints = new SemanticNodeList<SQLConstraint>();
               setProp = true;
            }
            constraints.add(new UniqueConstraint());
         }
         if (propDesc.dbDefault != null && propDesc.dbDefault.length() > 0) {
            if (constraints == null) {
               constraints = new SemanticNodeList<SQLConstraint>();
               setProp = true;
            }
            DefaultConstraint defConstr = new DefaultConstraint();
            defConstr.setProperty("expression", parseSQLExpression(propDesc.dbDefault));
            constraints.add(defConstr);
         }
         if (setProp)
            colDef.setProperty("columnConstraints", constraints);
         tableDefs.add(colDef);
      }
   }

   public SQLExpression parseSQLExpression(String exprStr) {
      SQLLanguage lang = SQLLanguage.getSQLLanguage();
      Object res = lang.parseString(exprStr, lang.sqlExpression);
      if (res instanceof ParseError) {
         displayError("Invalid dbDefault: " + exprStr + " not a valid sqlExpression");
         return null;
      }
      else {
         return (SQLExpression) ParseUtil.nodeToSemanticValue(res);
      }
   }

   /* TODO: Currently we sort for each type using the table's columns references clauses. Within a type, tables are sorted
    * based on their role primary, auxiliary, multi, sequences, indexes, functions. Do we need a finer grained way to sort
    * like this?
   static class ReferenceContext {
      TreeSet<String> tableRefs = new TreeSet<String>();
      TreeSet<String> seqRefs = new TreeSet<String>();
      TreeSet<String> indexRefs = new TreeSet<String>();
      TreeSet<String> funcRefs = new TreeSet<String>();
   }
   */

   public boolean hasTableReference(SQLFileModel other) {
      TreeSet<String> tableRefs = new TreeSet<String>();
      for (SQLCommand cmd:sqlCommands)
         cmd.addTableReferences(tableRefs);
      for (SQLCommand cmd:other.sqlCommands) {
         if (cmd instanceof CreateTable) {
            if (tableRefs.contains(((CreateTable) cmd).tableName.toString()))
               return true;
         }
      }
      return false;
   }

   // TODO: this clearParseTree stuff was taken from Template - should we share it in a common base class or put it in JavaModel?
   private boolean parseTreeCleared = false;

   private void clearParseTree() {
      if (parseTreeCleared)
         return;
      // Need to clear out the old parse tree.  During transform, we switch from the scsql file format to
      // a .java file format.
      IParseNode node = getParseNode();
      if (node != null)
         node.setSemanticValue(null, true, false);
      parseTreeCleared = true;
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      clearParseTree();

      return super.transform(runtime);
   }

   // TODO: also copied from TemplateLanguage
   public String getTransformedResult() {
      if (transformedModel != null)
         return transformedModel.getTransformedResult();
      clearParseTree();
      Object generateResult = SQLLanguage.INSTANCE.compilationUnit.generate(new GenerateContext(false), this);
      if (generateResult instanceof GenerateError) {
         displayError("*** Unable to generate template model for: " );
         return null;
      }
      else if (generateResult instanceof IParseNode) {
         // Need to format it and remove spacing and newline parse-nodes so we can accurately compute line numbers and offsets for registering gen source
         return ((IParseNode) generateResult).formatString(null, null, -1, true).toString();
      }
      else {
         return generateResult.toString();
      }
   }

   /**
    * This is the old SQL model that needs to be altered to convert a database schema to the one defined in the new toModel.
    * We'll return a new model with the differences
    */
   public SQLFileModel alterTo(SQLFileModel newModel) {
      if (sqlCommands == null || sqlCommands.size() == 0)
         return newModel;

      SQLFileModel resModel = new SQLFileModel();
      resModel.srcType = newModel.srcType;

      for (SQLCommand newCmd:newModel.sqlCommands) {
         // If this exact SQL command was in the old model just skip it
         SQLCommand sameCmd = findSameCommand(newCmd);
         if (sameCmd != null)
            continue;
         // Some SQL commands have a name - table, sequence, index, function - so if they don't match we either alter or drop/create
         SQLCommand oldCmd = findMatchingCommand(newCmd);
         if (oldCmd == null) {
            resModel.addCommand((SQLCommand) newCmd.deepCopy(ISemanticNode.CopyNormal | ISemanticNode.CopyParseNode, null));
         }
         else {
            oldCmd.alterTo(resModel, newCmd);
         }
      }
      return resModel;
   }

   public SQLFileModel createDropSQLModel() {
      SQLFileModel resModel = new SQLFileModel();
      resModel.srcType = srcType;

      if (sqlCommands == null)
         return null;

      for (int i = sqlCommands.size() - 1; i >= 0; i--) {
         SQLCommand srcCmd = sqlCommands.get(i);
         SQLCommand dropCmd = srcCmd.getDropCommand();
         if (dropCmd != null)
            resModel.addCommand(dropCmd);
      }
      return resModel;
   }

   public void addCommand(SQLCommand cmd) {
      List<SQLCommand> newCmds;
      boolean set = false;
      if (sqlCommands == null) {
         newCmds = new SemanticNodeList<SQLCommand>();
         set = true;
      }
      else
         newCmds = sqlCommands;
      newCmds.add(cmd);
      if (set)
         setProperty("sqlCommands", newCmds);
   }

   public void sortCommands() {
      if (sqlCommands == null)
         return;

      // First build list of references to SQL commands
      // Set graph depth of each node - 0
      // sort by depth in the graph

      // insertion sort
      int num = sqlCommands.size();
      for (int i = 1; i < num; i++) {
         SQLCommand cur = sqlCommands.get(i);
         int j = i - 1;
         for (; j >= 0; j--) {
            SQLCommand next = sqlCommands.get(j);
            if (cur.hasReferenceTo(next)) {
               sqlCommands.set(j + 1, next);
            }
            else
               break;
         }
         sqlCommands.set(j + 1, cur);
      }
   }

   public String getCommandSummary() {
      if (sqlCommands == null)
         return "<null commands>";
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (SQLCommand cmd:sqlCommands) {
         if (!first)
            sb.append(", ");
         first = false;
         sb.append(cmd.toDeclarationString());
      }
      return sb.toString();
   }

   public List<String> getCommandList() {
      ArrayList<String> res = new ArrayList<String>(sqlCommands.size());
      String wholeStr = toLanguageString();
      for (int i = 0; i < sqlCommands.size(); i++) {
         res.add(removeTrailingSemi(sqlCommands.get(i).toLanguageString().trim()));
      }
      return res;
   }

   private String removeTrailingSemi(String in) {
      int ix = in.lastIndexOf(';');
      if (ix != -1)
         return in.substring(0,ix);
      else
         System.err.println("*** Missing trailing semi");
      return in;
   }

   /** TODO: we should have a way to figure out the parselet for a top-level semantic node class to make this unnecessary */
   public String toLanguageString() {
      if (parseNode == null) {
         Object genRes = SQLLanguage.INSTANCE.generate(this, false);
         if (genRes instanceof IParseNode)
            parseNode = (IParseNode) genRes;
         else
            System.out.println("Generation error for sql model: " + toModelString());
      }
      return super.toLanguageString();
   }

   /**
    * Compare the schema required here against the metadata we found in the database and return
    * 'diffs' if there are any missing tables, columns
    * TODO: add indexes, sequences, functions
    */
   public DBMetadata getMissingTableInfo(DBMetadata dbMetadata) {
      if (sqlCommands == null)
         return null;

      DBMetadata diffs = null;

      for (SQLCommand cmd:sqlCommands) {
         if (cmd instanceof CreateTable) {
            CreateTable table = ((CreateTable) cmd);
            String tableName = table.tableName.getIdentifier();
            TableInfo ti = dbMetadata.getTableInfo(tableName);
            if (ti == null) {
               if (diffs == null)
                  diffs = new DBMetadata();
               diffs.tableInfos.add(new TableInfo(tableName));
            }
            else {
               ti = table.getMissingTableInfo(ti);
               if (ti != null) {
                  if (diffs == null)
                     diffs = new DBMetadata();
                  diffs.tableInfos.add(ti);
               }
            }
         }
      }
      return diffs;
   }
}
