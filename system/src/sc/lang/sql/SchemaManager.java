package sc.lang.sql;

import sc.db.DBUtil;
import sc.lang.SQLLanguage;
import sc.layer.*;
import sc.parser.ParseError;
import sc.parser.ParseUtil;
import sc.type.CTypeUtil;
import sc.util.FileUtil;
import sc.util.StringUtil;

import java.io.File;
import java.util.*;

public class SchemaManager {
   LayeredSystem system;
   String dataSourceName;

   ArrayList<SQLFileModel> currentSchema = new ArrayList<SQLFileModel>();

   public SchemaManager(LayeredSystem sys, String dataSourceName) {
      this.system = sys;
      this.dataSourceName = dataSourceName;
   }

   public Map<String,SQLFileModel> schemasByType = new HashMap<String, SQLFileModel>();

   static class SchemaTypeChange {
      SQLFileModel fromModel, toModel;
      SchemaTypeChange(SQLFileModel from, SQLFileModel to) {
         fromModel = from;
         toModel = to;
      }
   }

   public boolean migrateSchemas(Layer buildLayer) {
      String currentSchemasDir = FileUtil.concat(LayerUtil.getCurrentDBSchemasDir(system), buildLayer.getUnderscoreName());

      ArrayList<SchemaTypeChange> changedTypes = new ArrayList<SchemaTypeChange>();
      ArrayList<SQLFileModel> newModels = new ArrayList<SQLFileModel>();

      for (Map.Entry<String,SQLFileModel> ent:schemasByType.entrySet()) {
         String typeName = ent.getKey();
         SQLFileModel sqlModel = ent.getValue();

         String filePart = FileUtil.addExtension(CTypeUtil.getClassName(typeName), "scsql");

         String curSchemaFileName = FileUtil.concat(currentSchemasDir, filePart);
         if (!new File(curSchemaFileName).canRead()) {
            newModels.add(sqlModel);
         }
         else {
            String oldFileBody = FileUtil.getFileAsString(curSchemaFileName);
            String curFileBody = sqlModel.toLanguageString();
            if (!oldFileBody.equals(curFileBody)) {
               Object parseRes = SQLLanguage.getSQLLanguage().parseString(curFileBody);
               if (parseRes instanceof ParseError) {
                  System.err.println("*** Unable to parse currentSchema sql file: " + curSchemaFileName + ": " + parseRes);
               }
               else {
                  SQLFileModel oldModel = (SQLFileModel) ParseUtil.nodeToSemanticValue(parseRes);
                  changedTypes.add(new SchemaTypeChange(sqlModel, oldModel));
               }
            }
         }
      }
      // TODO: look for deleted types and generate the drop script to remove the old SQL commands?

      boolean schemaChanged = false;
      if (changedTypes.size() == 0 && newModels.size() == 0) {
         DBUtil.verbose("No changes to schema for dataSource: " + dataSourceName + " for buildLayer: " + buildLayer);
      }
      else if (changedTypes.size() > 0) {
         ArrayList<SQLFileModel> migrateSchema = new ArrayList<SQLFileModel>();
         for (SQLFileModel newModel:newModels)
            addToSchemaList(migrateSchema, newModel);
         for (SchemaTypeChange change:changedTypes) {
            try {
               SQLFileModel updateModel = change.fromModel.alterTo(change.toModel);
               if (updateModel != null)
                  addToSchemaList(migrateSchema, updateModel);
            }
            catch (UnsupportedOperationException exc) {
               DBUtil.error("Unable to create SQL script to update for change: " + change.fromModel);
            }
         }
         // TODO: include info in this file in the from and to schema versions, or maybe the dates? The currentSchema files should have the dates, version
         // info stored (optionally) so we can track the database using the metadata table. Otherwise, we'll just use the last build date and snag the last
         // build version of the program in the current schema directory.
         StringBuilder alterSB = convertSQLModelsToString(migrateSchema, "Alter table script");
         if (saveSchemaFile(buildLayer, alterSB, "alter")) {
            DBUtil.info("Alter script changed for dataSource: " + dataSourceName);
            schemaChanged = true;
         }
      }
      else {
         DBUtil.info("First time build for dataSource: " + dataSourceName + " in build layer: " + buildLayer);
         SrcEntry alterEnt = getCurrentSchemaSrcEntry(buildLayer, "alter");
         File alterFile = new File(alterEnt.absFileName);
         if (alterFile.canRead()) {
            // TODO: should we remove this from the layer.buildSrcIndex? Do we ever clean that up if a file that was previously built is removed?
            FileUtil.removeFileOrDirectory(alterFile);
            DBUtil.info("Removing alter file - no current database schema " + alterFile);
         }
         schemaChanged = true;
      }
      return schemaChanged;
   }

   StringBuilder convertSQLModelsToString(List<SQLFileModel> sqlFileModels, String message) {
      if (sqlFileModels == null || sqlFileModels.size() == 0)
         return null;

      StringBuilder schemaSB = new StringBuilder();
      schemaSB.append("/* " + message + " for dataSource: " + dataSourceName + " */\n");
      for (SQLFileModel sqlFileModel:sqlFileModels) {
         schemaSB.append("\n\n/* Schema for type: " + sqlFileModel.srcType.getFullTypeName() + " */\n\n");
         schemaSB.append(sqlFileModel.toLanguageString());
         schemaSB.append("\n");
      }
      return schemaSB;
   }

   SrcEntry getCurrentSchemaSrcEntry(Layer buildLayer, String postfix) {
      // TODO: should we use the dbname here?
      String dataSourceFile = dataSourceName.replace("/", "_");
      if (postfix != null)
         dataSourceFile += "-" + postfix;
      dataSourceFile = FileUtil.addExtension(dataSourceFile, "sql");
      return new SrcEntry(buildLayer, buildLayer.buildSrcDir, "",  FileUtil.addExtension(dataSourceFile, "sql"), false, null);
   }

   boolean saveSchemaFile(Layer buildLayer, StringBuilder schemaSB, String postfix) {
      SrcEntry sqlSrcEnt = getCurrentSchemaSrcEntry(buildLayer, postfix);
      String schemaStr = schemaSB.toString();
      sqlSrcEnt.hash = StringUtil.computeHash(schemaStr.getBytes());

      SrcIndexEntry srcIndex = buildLayer.getSrcFileIndex(sqlSrcEnt.relFileName);
      // Avoid rewriting unchanged files
      if (srcIndex == null || !Arrays.equals(srcIndex.hash, sqlSrcEnt.hash)) {
         FileUtil.saveStringAsReadOnlyFile(sqlSrcEnt.absFileName, schemaSB.toString(), false);
         buildLayer.addSrcFileIndex(sqlSrcEnt.relFileName, sqlSrcEnt.hash, null, sqlSrcEnt.absFileName);

         DBUtil.info("Schema: " + (postfix == null ? "" : postfix) + " changed: " + sqlSrcEnt.absFileName);

         return true;
      }
      else
         DBUtil.verbose("Schema: " + (postfix == null ? "" : postfix) + " unchanged: " + sqlSrcEnt.absFileName);
      return false;
   }

   public void saveCurrentSchema(Layer buildLayer) {
      StringBuilder schemaSB = convertSQLModelsToString(currentSchema, "Database schema");

      boolean changed = false;
      if (schemaSB != null) {
         changed = saveSchemaFile(buildLayer, schemaSB, null);
      }

      if (changed)
         migrateSchemas(buildLayer);
   }

   public static void addToSchemaList(List<SQLFileModel> schemaList, SQLFileModel sqlModel) {
      int ix = -1;
      for (int i = 0; i < schemaList.size(); i++) {
         SQLFileModel curModel = schemaList.get(i);
         if (curModel.hasTableReference(sqlModel)) {
            ix = i;
            break;
         }
      }

      if (ix == -1)
         schemaList.add(sqlModel);
      else
         schemaList.add(ix, sqlModel);
   }

}
