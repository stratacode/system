package sc.lang.sql;

import sc.db.DBTypeDescriptor;
import sc.db.DBUtil;
import sc.db.TableDescriptor;
import sc.lang.ISemanticNode;
import sc.lang.SQLLanguage;
import sc.lang.java.JavaModel;
import sc.lang.java.ModelUtil;
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

      String dataSourceName = dbTypeDesc.dataSourceName;
      if (dataSourceName == null)
         dataSourceName = sys.defaultDataSource == null ? null : sys.defaultDataSource.jndiName;

      SQLFileModel old = null;
      DBProvider provider = null;
      if (dataSourceName != null) {
         provider = DBProvider.getDBProviderForType(sys, fromModel.getLayer(), dbTypeDesc.typeDecl);
         if (provider != null)
            old = provider.getSchemaForType(dataSourceName, typeName);
      }

      SQLFileModel res = old == null ? new SQLFileModel() : (SQLFileModel) old.deepCopy(ISemanticNode.CopyNormal | ISemanticNode.CopyParseNode, null);
      if (!dbTypeDesc.tablesInitialized)
         DBProvider.completeDBTypeDescriptor(dbTypeDesc, sys, fromModel.layer, dbTypeDesc);
      dbTypeDesc.init();
      dbTypeDesc.start();
      res.layeredSystem = sys;
      res.layer = fromModel.layer;
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
