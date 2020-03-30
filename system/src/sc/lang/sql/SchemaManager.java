package sc.lang.sql;

import sc.db.DBSchemaType;
import sc.db.DBSchemaVersion;
import sc.db.DBUtil;
import sc.db.ISchemaUpdater;
import sc.lang.SQLLanguage;
import sc.layer.*;
import sc.parser.ParseError;
import sc.parser.ParseUtil;
import sc.type.CTypeUtil;
import sc.util.FileUtil;
import sc.util.StringUtil;

import java.io.File;
import java.util.*;

/**
 * Manages the synchronization of current schema generated from the application and the
 * schema in the database.
 */
public class SchemaManager {
   public LayeredSystem system;
   public String dataSourceName;
   public DBProvider provider;
   public Layer buildLayer;

   /** Map from the type name to the list of sql commands to implement that type */
   public Map<String,SQLFileModel> schemasByType = new HashMap<String, SQLFileModel>();
   /** Ordered list of SQL commands for for all types */
   public ArrayList<SQLFileModel> currentSchema = new ArrayList<SQLFileModel>();

   /** True the first time a layer is built and we have not yet captured a schema */
   boolean noCurrentSchema = false;
   /** True anytime there are changes to the schema that might need user approval */
   boolean schemaChanged = false;

   public ArrayList<SchemaTypeChange> changedTypes = new ArrayList<SchemaTypeChange>();
   public ArrayList<SQLFileModel> newModels = new ArrayList<SQLFileModel>();

   boolean needsInitFromDB = true;
   public boolean initFromDBFailed = false;
   public List<DBSchemaType> dbSchemaTypes;

   public List<DBSchemaType> notUsedTypeSchemas = new ArrayList<DBSchemaType>();

   public SchemaManager(LayeredSystem sys, DBProvider provider, String dataSourceName) {
      this.system = sys;
      this.dataSourceName = dataSourceName;
      this.provider = provider;
   }

   public Object getDeployedDBSchemaInfo() {
      if (dbSchemaTypes == null)
         return "No db_schema_type table found in deployed database";
      else if (dbSchemaTypes.size() == 0)
         return "No types defined in db_schema_type table for dataSource: " + dataSourceName;

      StringBuilder res = new StringBuilder();
      for (DBSchemaType tsi: dbSchemaTypes) {
         res.append("--- ");
         res.append(tsi.getTypeName());
         res.append(" ");
         DBSchemaVersion vers = tsi.getCurrentVersion();
         res.append(vers.getBuildLayerName());
         res.append(" ");
         res.append(vers.getDateApplied());
         if (vers.getVersionInfo() != null) {
            res.append(" ");
            res.append(vers.getVersionInfo());
         }
         res.append(":\n--- schemaSQL:");
         res.append(vers.getSchemaSQL());
         if (vers.getAlterSQL() != null) {
            res.append("\n--- alterSQL:");
            res.append(vers.getAlterSQL());
            res.append("\n");
         }
      }
      return res.toString();
   }

   static class SchemaTypeChange {
      String typeName;
      SQLFileModel fromModel, toModel;
      SQLFileModel alterModel;
      SchemaTypeChange(String typeName, SQLFileModel from, SQLFileModel to) {
         this.typeName = typeName;
         fromModel = from;
         toModel = to;
      }
   }

   /** Connect to the database and return the metadata about the schema. */
   public void initFromDB(Layer buildLayer) {
      if (!needsInitFromDB)
         return;
      ISchemaUpdater schemaUpdater = provider.getSchemaUpdater();
      if (schemaUpdater != null) {
         if (dataSourceName != null) {
            dbSchemaTypes = schemaUpdater.getDBSchemas(dataSourceName);
            initFromDBFailed = dbSchemaTypes == null;
         }

         if (!initFromDBFailed) {
            needsInitFromDB = false;

            // We're going to go through the results we go
            for (DBSchemaType dbTypeSchema: dbSchemaTypes) {
               String typeName = dbTypeSchema.getTypeName();
               SQLFileModel newSchema = schemasByType.get(typeName);
               // There's a schema in the database for a type not defined in this layer - it might be that we are sharing
               // a database with other programs but at least make this info available to the schemaManager
               if (newSchema == null) {
                  notUsedTypeSchemas.add(dbTypeSchema);
               }
               else {
                  String sqlStr = dbTypeSchema.getCurrentVersion().getSchemaSQL();
                  Object parseRes = SQLLanguage.getSQLLanguage().parseString(sqlStr);
                  if (parseRes instanceof ParseError) {
                     DBUtil.error("Parse error - unable to parse schema from current database for type:" + typeName + " :" + sqlStr);
                     continue;
                  }
                  SQLFileModel dbModel = (SQLFileModel) ParseUtil.nodeToSemanticValue(parseRes);
                  // This model already matches what's in the database schema so it's not actually a new model
                  if (dbModel.equals(newSchema)) {
                     newModels.remove(newSchema);
                     removeChangedType(typeName);
                     recordDeployedSchema(typeName, newSchema, buildLayer);
                  }
                  else {
                     SchemaTypeChange curChange = getChangedType(typeName);
                     if (curChange != null) {
                        if (!curChange.fromModel.equals(dbModel)) {
                           DBUtil.warn("Model stored in database does not match last build");
                           // This will be more reliable since it's stored next to the data
                           curChange.fromModel = dbModel;
                        }
                        // else - we already have this change from the file system cache
                     }
                     else {
                        DBUtil.verbose("Schema for type: " + typeName + " discovered from database schema");
                        changedTypes.add(new SchemaTypeChange(typeName, dbModel, newSchema));
                     }
                  }
               }
            }
            schemaChanged = newModels.size() > 0 || changedTypes.size() > 0;
         }
      }
   }

   private String getDeployedSchemasDir(Layer buildLayer) {
      return FileUtil.concat(LayerUtil.getDeployedDBSchemasDir(system), buildLayer.getUnderscoreName());
   }

   private String getDeployedSchemaFile(String deployedSchemaDir, String typeName) {
      String filePart = FileUtil.addExtension(CTypeUtil.getClassName(typeName), "scsql");
      return FileUtil.concat(deployedSchemaDir, filePart);
   }

   private void recordDeployedSchema(String typeName, SQLFileModel sqlModel, Layer buildLayer) {
      String deployedSchemasFile = getDeployedSchemaFile(getDeployedSchemasDir(buildLayer), typeName);
      FileUtil.saveStringAsFile(deployedSchemasFile, sqlModel.toLanguageString(), true);
   }

   public boolean generateAlterSchema(Layer buildLayer) {
      String deployedSchemasDir = getDeployedSchemasDir(buildLayer);

      for (Map.Entry<String,SQLFileModel> ent:schemasByType.entrySet()) {
         String typeName = ent.getKey();
         SQLFileModel sqlModel = ent.getValue();

         String curSchemaFileName = getDeployedSchemaFile(deployedSchemasDir, typeName);
         if (!new File(curSchemaFileName).canRead()) {
            newModels.add(sqlModel);
         }
         else {
            String oldFileBody = FileUtil.getFileAsString(curSchemaFileName);
            String curFileBody = sqlModel.toLanguageString();
            if (!oldFileBody.equals(curFileBody)) {
               Object parseRes = SQLLanguage.getSQLLanguage().parseString(curFileBody);
               if (parseRes instanceof ParseError) {
                  System.err.println("*** Unable to parse deployedSchema sql file: " + curSchemaFileName + ": " + parseRes);
               }
               else {
                  SQLFileModel oldModel = (SQLFileModel) ParseUtil.nodeToSemanticValue(parseRes);
                  changedTypes.add(new SchemaTypeChange(typeName, sqlModel, oldModel));
               }
            }
         }
      }
      // TODO: look for deleted types and generate the drop script to remove the old SQL commands?

      if (changedTypes.size() == 0 && newModels.size() == 0) {
         DBUtil.verbose("No changes to schema for dataSource: " + dataSourceName + " for buildLayer: " + buildLayer);
      }
      else if (changedTypes.size() > 0) {
         updateAlterSchema(buildLayer);
      }
      else {
         noCurrentSchema = true;
         DBUtil.info("First time build for dataSource: " + dataSourceName + " in build layer: " + buildLayer);
         SrcEntry alterEnt = getCurrentSchemaSrcEntry(buildLayer, "alter");
         File alterFile = new File(alterEnt.absFileName);
         if (alterFile.canRead()) {
            // TODO: should we remove this from the layer.buildSrcIndex? Do we ever clean that up if a file that was previously built is removed?
            FileUtil.removeFileOrDirectory(alterFile);
            DBUtil.info("Removing alter file - no deployed database schema " + alterFile);
         }
         schemaChanged = true;
      }

      if (schemaChanged) {
         if (system.options.startInterpreter) {
            system.cmd.addCommandWizard(new SchemaUpdateWizard(system.cmd, system, this));
         }
         else {
            if (noCurrentSchema) {
               system.warning("Database schema may not be in sync: no deployed/cached schema files and not running SchemaUpdateWizard because command interpreter is disabled");
            }
            else {
               system.warning("Database schema has changed and not running SchemaUpdateWizard because command interpreter is disabled");
            }
         }
      }
      return schemaChanged;
   }

   void updateAlterSchema(Layer buildLayer) {
      ArrayList<SQLFileModel> alterSchema = new ArrayList<SQLFileModel>();
      for (SQLFileModel newModel:newModels)
         addToSchemaList(alterSchema, newModel);
      for (SchemaTypeChange change:changedTypes) {
         try {
            // Get the list of alter commands to convert a DDL defined by fromModel.sqlCommands to one defined by toModel.sqlCommands
            SQLFileModel updateModel = change.fromModel.alterTo(change.toModel);
            if (updateModel != null)
               addToSchemaList(alterSchema, updateModel);
            change.alterModel = updateModel;
         }
         catch (UnsupportedOperationException exc) {
            DBUtil.error("Unable to create SQL script to update for change: " + change.fromModel);
         }
      }
      // TODO: include info in this file in the from and to schema versions, or maybe the dates? The deployedSchema files should have the dates, version
      // info stored (optionally) so we can track the database using the metadata table. Otherwise, we'll just use the last build date and snag the last
      // build version of the program in the deployed schema directory.
      StringBuilder alterSB = convertSQLModelsToString(alterSchema, "Alter table script");
      if (saveSchemaFile(buildLayer, alterSB, "alter")) {
         DBUtil.info("Alter script changed for dataSource: " + dataSourceName);
         schemaChanged = true;
      }
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

   public StringBuilder getCurrentSchema() {
      return convertSQLModelsToString(currentSchema, "Database schema");
   }

   public void saveCurrentSchema(Layer buildLayer) {
      StringBuilder schemaSB = getCurrentSchema();

      boolean changed = false;
      if (schemaSB != null) {
         changed = saveSchemaFile(buildLayer, schemaSB, null);
      }

      if (changed)
         generateAlterSchema(buildLayer);
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

   SchemaTypeChange getChangedType(String typeName) {
      for (SchemaTypeChange curChange:changedTypes)
         if (curChange.typeName.equals(typeName))
            return curChange;
      return null;
   }

   void removeChangedType(String typeName) {
      for (int i = 0; i < changedTypes.size(); i++) {
         SchemaTypeChange curChange = changedTypes.get(i);
         if (curChange.typeName.equals(typeName)) {
            changedTypes.remove(i);
            return;
         }
      }
   }

   public int getNumChangedTypes() {
      return newModels.size() + changedTypes.size();
   }

   public String getChangedTypeName(int index) {
      int newSize = newModels.size();
      if (index < newSize)
         return newModels.get(index).srcType.getFullTypeName();
      else
         return changedTypes.get(index).fromModel.srcType.getFullTypeName();
   }

   public boolean updateSchema(Layer buildLayer) {
      ISchemaUpdater updater = provider.getSchemaUpdater();
      if (updater == null) {
         throw new IllegalArgumentException("No schema updater configured");
      }
      int nsz = newModels.size();
      for (int i = 0; i < nsz; i++) {
         SQLFileModel newModel = newModels.get(i);
         DBSchemaType info = new DBSchemaType();
         String typeName = newModel.srcType.getFullTypeName();
         info.setTypeName(typeName);
         DBSchemaVersion curVersion = info.getCurrentVersion();
         String schemaSQL = newModel.toLanguageString();
         curVersion.setSchemaSQL(schemaSQL);
         curVersion.setDateApplied(new Date());
         try {
            updater.applyAlterCommands(dataSourceName, newModel.getCommandList());
         }
         catch (IllegalArgumentException exc) {
            DBUtil.error("Update schema failed: " + exc.getMessage() + exc.getCause());
            return false;
         }
         updateDBSchema(updater, typeName, info, newModel, buildLayer);
      }
      for (int i = 0; i < changedTypes.size(); i++) {
         SchemaTypeChange change = changedTypes.get(i);
         SQLFileModel newModel = change.toModel;
         DBSchemaType info = new DBSchemaType();
         String typeName = change.typeName;
         info.setTypeName(typeName);
         DBSchemaVersion curVersion = info.getCurrentVersion();
         curVersion.setSchemaSQL(newModel.toLanguageString());
         curVersion.setDateApplied(new Date());
         String alterSQL = change.alterModel.toLanguageString();
         curVersion.setAlterSQL(alterSQL);
         try {
            updater.applyAlterCommands(dataSourceName, change.alterModel.getCommandList());
         }
         catch (IllegalArgumentException exc) {
            DBUtil.error("Error applying alter schema SQL for type: " + typeName + ":\n" + alterSQL);
            return false;
         }
         updateDBSchema(updater, typeName, info, newModel, buildLayer);
      }
      return true;
   }

   private void updateDBSchema(ISchemaUpdater updater, String typeName, DBSchemaType info, SQLFileModel newModel, Layer buildLayer) {
      try {
         updater.updateDBSchemaForType(dataSourceName, info);
         recordDeployedSchema(typeName, newModel, buildLayer);
      }
      catch (IllegalArgumentException exc) {
         DBUtil.error("Error updating schema metadata for type: " + typeName + ": " + exc);
         throw exc;
      }
   }

}
