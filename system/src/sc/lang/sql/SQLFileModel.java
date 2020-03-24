package sc.lang.sql;

import sc.db.DBPropertyDescriptor;
import sc.db.DBTypeDescriptor;
import sc.db.DBUtil;
import sc.db.TableDescriptor;
import sc.lang.*;
import sc.lang.java.*;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.sc.SCModel;
import sc.layer.Layer;
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

      String fullTypeName = getModelTypeName();
      String typeName = CTypeUtil.getClassName(fullTypeName);

      if (sqlCommands == null) {
         displayError("No sql commands in file to define type: " + typeName + ": for: ");
         return;
      }

      String sqlName = SQLUtil.getSQLName(typeName);
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

   public void addCreateTable(TableDescriptor tableDesc) {
      // TODO: TableDescriptor is a big subset of 'CreateTable' - need to either fill it out or preserve it by modifying the source when augmenting DDL
      CreateTable ct = new CreateTable();
      ct.setProperty("tableName", SQLIdentifier.create(tableDesc.tableName));
      SemanticNodeList<TableDef> tableDefs = new SemanticNodeList<TableDef>();
      appendColumnDefs(tableDefs, tableDesc.getIdColumns(), true, tableDesc.multiRow);
      appendColumnDefs(tableDefs, tableDesc.columns, false, tableDesc.multiRow);
      ct.setProperty("tableDefs", tableDefs);

      if (sqlCommands == null) {
         setProperty("sqlCommands", new SemanticNodeList<SQLCommand>());
      }
      sqlCommands.add(ct);
   }

   private void appendColumnDefs(List<TableDef> tableDefs, List<? extends DBPropertyDescriptor> propDescList, boolean isId, boolean multiRow) {
      for (DBPropertyDescriptor propDesc:propDescList) {
         ColumnDef colDef = new ColumnDef();
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
               DBTypeDescriptor refTypeDesc = ModelUtil.getDBTypeDescriptor(layeredSystem, getLayer(), refType, true);
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
}
