package sc.lang.sql;

import sc.db.*;
import sc.lang.ISemanticNode;
import sc.lang.SQLLanguage;
import sc.lang.SemanticNodeList;
import sc.lang.java.JavaModel;
import sc.lang.java.ModelUtil;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.parser.PString;
import sc.parser.ParseError;
import sc.parser.ParseUtil;

public class SQLUtil {
   // TODO: if we already have a SQLFileModel, we could modify the existing one to preserve aspects of the SQL we don't
   // capture in the DBTypeDescriptor. Or we could add those features to the DBTypeDescriptor and pass them through here
   // so that we could modify those features as well.
   public static SQLFileModel convertTypeToSQLFileModel(JavaModel fromModel, DBTypeDescriptor dbTypeDesc) {
      String typeName = ModelUtil.getTypeName(dbTypeDesc.typeDecl);
      LayeredSystem sys = fromModel.layeredSystem;

      Layer fromLayer = fromModel.getLayer();

      String dataSourceName = dbTypeDesc.dataSourceName;
      if (dataSourceName == null) {
         DBDataSource defaultDataSource = fromLayer.getDefaultDataSource();
         dataSourceName = defaultDataSource == null ? null : defaultDataSource.jndiName;
      }

      SQLFileModel old = null;
      DBProvider provider = null;
      if (dataSourceName != null) {
         provider = DBProvider.getDBProviderForType(sys, fromLayer, dbTypeDesc.typeDecl);
         if (provider != null)
            old = provider.getSchemaForType(dataSourceName, typeName);
      }

      SQLFileModel res = old == null ? new SQLFileModel() : (SQLFileModel) old.deepCopy(ISemanticNode.CopyNormal | ISemanticNode.CopyParseNode, null);
      // Do this to create the parseNode so that we can insert comments for the commands before we generate
      res.setParselet(SQLLanguage.getSQLLanguage().sqlFileModel);
      if (!dbTypeDesc.tablesInitialized)
         DBProvider.completeDBTypeDescriptor(dbTypeDesc, sys, fromLayer, dbTypeDesc);
      dbTypeDesc.init();
      dbTypeDesc.start();
      res.layeredSystem = sys;
      res.layer = fromLayer;
      res.typeMetadata = dbTypeDesc.getMetadataString();
      // The base type will have defined the primaryTable for this type
      if (dbTypeDesc.baseType == null || dbTypeDesc.primaryTable != dbTypeDesc.baseType.primaryTable)
         res.addCreateTable(dbTypeDesc.primaryTable);
      if (dbTypeDesc.auxTables != null) {
         for (TableDescriptor auxTable:dbTypeDesc.auxTables) {
            if (!auxTable.reference)
               res.addCreateTable(auxTable);
         }
      }
      if (dbTypeDesc.multiTables != null) {
         for (TableDescriptor multiTable:dbTypeDesc.multiTables) {
            if (!multiTable.reference)
               res.addCreateTable(multiTable);
         }
      }
      if (dbTypeDesc.schemaSQL != null) {
         Object schemaSQLRes = SQLLanguage.getSCLanguage().parseString(dbTypeDesc.schemaSQL);
         if (schemaSQLRes instanceof ParseError) {
            fromModel.displayError("SchemaSQL parse error: " + schemaSQLRes);
         }
         else {
            SQLFileModel schemaSQLCmds = (SQLFileModel) ParseUtil.nodeToSemanticValue(schemaSQLRes);
            if (schemaSQLCmds.sqlCommands != null) {
               for (SQLCommand newCmd:schemaSQLCmds.sqlCommands) {
                  res.replaceCommand(newCmd);
               }
            }
         }
      }
      // For any properties with indexed=true, create the appropriate index unless it's been defined already
      if (dbTypeDesc.allDBProps != null) {
         for (DBPropertyDescriptor prop:dbTypeDesc.allDBProps) {
            // These are indexed via the primary key
            if (prop instanceof IdPropertyDescriptor)
               continue;

            if (prop.indexed) {
               CreateIndex propIndex = new CreateIndex();
               propIndex.setProperty("indexName",SQLIdentifier.create(prop.getTableName() + "_" + prop.columnName + "_index"));
               propIndex.setProperty("tableName", SQLIdentifier.create(prop.getTableName()));
               propIndex.setProperty("indexColumns", new SemanticNodeList<BaseIndexColumn>());
               if (prop.dynColumn) {
                  String castType = DBUtil.getJSONCastType(DBColumnType.fromColumnType(prop.columnType));
                  IndexColumnExpr ice;
                  if (castType != null) {
                     ice = IndexColumnExpr.create(SQLParenExpression.create(SQLBinaryExpression.create(SQLParenExpression.create(
                         SQLBinaryExpression.create(
                             SQLIdentifierExpression.create(DBTypeDescriptor.DBDynPropsColumnName), "->>",
                                QuotedStringLiteral.create(prop.propertyName))), "::", SQLIdentifierExpression.create(castType))));
                  }
                  else {
                     ice = IndexColumnExpr.create(SQLParenExpression.create(
                         SQLBinaryExpression.create(SQLIdentifierExpression.create(DBTypeDescriptor.DBDynPropsColumnName),
                                                "->>", QuotedStringLiteral.create(prop.propertyName))));
                  }
                  propIndex.indexColumns.add(ice);
               }
               else
                  propIndex.indexColumns.add(IndexColumn.create(prop.columnName));

               // If we've already added an index with this name, it overrides this version
               if (res.findMatchingCommand(propIndex) == null)
                  res.addCommand(propIndex);
            }
         }
      }
      if (provider != null)
         provider.addSchema(dataSourceName, typeName, res);
      return res;
   }

   public static String getSQLName(String javaName) {
      String res = DBUtil.getSQLName(javaName);
      if (SQLLanguage.getSQLLanguage().getKeywords().contains(PString.toIString(res)))
         return res + "_";
      else
         return res;
   }

   public static String convertSQLToJavaTypeName(LayeredSystem sys, String sqlTypeName) {
      String javaTypeName = SQLDataType.convertToJavaTypeName(sqlTypeName);
      if (javaTypeName != null)
         return javaTypeName;
      DBTypeDescriptor dbTypeDesc = sys.getDBTypeDescriptorFromTableName(sqlTypeName);
      if (dbTypeDesc == null)
         dbTypeDesc = DBTypeDescriptor.getByTableName(javaTypeName);
      if (dbTypeDesc != null)
         return dbTypeDesc.getTypeName();
      return "Object";
   }

}
