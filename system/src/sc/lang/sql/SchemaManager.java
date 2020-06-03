package sc.lang.sql;

import sc.db.*;
import sc.dyn.DynUtil;
import sc.lang.SQLLanguage;
import sc.lang.SemanticNodeList;
import sc.layer.*;
import sc.parser.ParseError;
import sc.parser.ParseUtil;
import sc.type.CTypeUtil;
import sc.util.FileUtil;
import sc.util.StringUtil;

import java.io.File;
import java.util.*;

/**
 * Manages the synchronization of current schema generated from the application and the schema in the database for
 * a single data source.  An instance is created when code is processed for a data source, and types that use that
 * data source add themselves to the currentSchema (also represented in schemasByType).
 *
 * We keep track of the current 'deployedSchemas' for each type for a given buildLayer in the .stratacode/deployedSchemas.
 * We also keep track in the database with db_schema_type, db_schema_version, and db_schema_current_version tables. That
 * stores the .sql used to create each version of the database. We can use that to produce diffs - i.e. the alter commands
 * required to move from one version of the schema to the next.
 *
 * When a process has completed the code-processing phase, create, drop and optionally 'alter' schemas are generated
 * by looking at changes from this build to the last deployed version.
 *
 * If there are schema changes and the app is run interactively, the SchemaUpdateWizard is started. It can apply the
 * alter script and update the database , including the db_schema_ tables. Use it to print the new schema, the alter schema
 * to be applied to the current deployed DB, etc.
 *
 * If available, the database metadata is used to validate the db_schema tables and determine whether the existing
 * DB is at least a basic match for the current schema.
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
   public ArrayList<SQLFileModel> alterSchema = new ArrayList<SQLFileModel>();

   /** True the first time a layer is built and we have not yet captured a schema */
   boolean noCurrentSchema = false;
   /** True anytime there are changes to the schema that might need user approval */
   boolean schemaChanged = false;

   public ArrayList<SchemaTypeChange> changedTypes = new ArrayList<SchemaTypeChange>();
   public ArrayList<SQLFileModel> newModels = new ArrayList<SQLFileModel>();
   public ArrayList<SQLFileModel> conflictModels = new ArrayList<SQLFileModel>();

   boolean needsInitFromDB = true;
   public boolean initFromDBFailed = false, dbMetadataFailed = false;
   /** Contents of the db_schema_ tables for the current database, if initFromDBFailed = false. */
   public List<DBSchemaType> dbSchemaTypes;
   /** Metadata from the current database - used to validate the dbSchemaTypes and match against current schema */
   public DBMetadata dbMetadata;

   /** The tables/columns in currentSchema not found in dbMetadata */
   public DBMetadata dbMissingMetadata;

   public List<DBSchemaType> notUsedTypeSchemas = new ArrayList<DBSchemaType>();

   public enum SchemaMode {
      Prompt, Update, Accept
   }

   public SchemaMode schemaMode;

   public boolean schemaNotReady = false;

   public SchemaManager(LayeredSystem sys, DBProvider provider, String dataSourceName) {
      this.system = sys;
      this.dataSourceName = dataSourceName;
      this.provider = provider;
      this.schemaMode = sys.options.schemaMode;
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
      List<SchemaChangeDetail> notUpgradeable;

      SchemaTypeChange(String typeName, SQLFileModel from, SQLFileModel to) {
         this.typeName = typeName;
         fromModel = from;
         toModel = to;
      }
   }

   /**
    * Connect to the database and populate the schema manager with two types of metadata about the schema. Use the db_schema_
    * tables to retrieve our record of the schema along with (optionally) the database table/column metadata to see what's actually there.
    */
   public void initFromDB(Layer buildLayer, boolean changeReadyState) {
      if (!needsInitFromDB)
         return;
      ISchemaUpdater schemaUpdater = provider.getSchemaUpdater();
      int numOutOfSync = 0;
      int numChanged = 0;
      if (schemaUpdater != null) {
         if (dataSourceName != null) {
            dbSchemaTypes = schemaUpdater.getDBSchemas(dataSourceName);
            initFromDBFailed = dbSchemaTypes == null;

            dbMetadata = schemaUpdater.getDBMetadata(dataSourceName);
            dbMetadataFailed = dbMetadata == null;
            dbMissingMetadata = null;
         }

         if (!initFromDBFailed) {
            needsInitFromDB = false;

            // Compare the db_schema_ table contents to the new+changed types as computed from the most recent deployed build.
            // We'll recompute the new+alter scripts accordingly
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
                  dbModel.srcType = newSchema.srcType;

                  // Check the current database schema against what we have in the db_schema_ tables
                  DBMetadata missingTableInfo = dbModel.getMissingTableInfo(dbMetadata);
                  if (missingTableInfo != null) {
                     DBUtil.error("Current db_schema_type table for: " + typeName + " - out of sync. It has this info: " + missingTableInfo + " missing in current DB");
                     schemaUpdater.removeDBSchemaForType(dataSourceName, typeName);
                     numOutOfSync++;
                     continue;
                  }

                  // This model already matches what's in the database schema so it's not actually a new model
                  if (DynUtil.equalObjects(dbModel.sqlCommands, newSchema.sqlCommands)) {
                     newModels.remove(newSchema);
                     alterSchema.remove(newSchema);
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
                        newModels.remove(newSchema);
                        alterSchema.remove(newSchema);
                        DBUtil.info("Schema for type: " + typeName + " discovered from database schema");
                        SchemaTypeChange change = new SchemaTypeChange(typeName, dbModel, newSchema);
                        updateAlterModel(change);
                        changedTypes.add(change);
                        numChanged++;
                     }
                  }
               }
            }

            if (dbMetadata != null) {
               ArrayList<SQLFileModel> toRemove = new ArrayList<SQLFileModel>();
               for (TableInfo tableInfo:dbMetadata.tableInfos) {
                  for (SQLFileModel newSchema:newModels) {
                     CreateTable table = newSchema.findCreateTable(tableInfo.tableName);
                     if (table != null) {
                        TableInfo missingInfo = table.getMissingTableInfo(tableInfo);
                        toRemove.add(newSchema);
                        if (missingInfo == null) {
                           DBUtil.warn("Matching table: " + tableInfo.tableName + " exists in: " + dbMetadata.dataSourceName);
                           // TODO: We're removing all changes for this type rather than just the 'create table' that might not be fully accurate
                        }
                        else {
                           DBUtil.warn("Table: " + tableInfo.tableName + " exists in: " + dbMetadata.dataSourceName + " but is missing: " + missingInfo);
                           conflictModels.add(newSchema);
                        }
                     }
                  }
               }
               if (toRemove.size() > 0) {
                  newModels.removeAll(toRemove);
                  alterSchema.removeAll(toRemove);
               }
            }

            schemaChanged = newModels.size() > 0 || changedTypes.size() > 0;

            if (!schemaChanged) {
               DBUtil.info("Schema manager - init from DB found all tables/columns in schema");
            }
            else {
               DBUtil.info("Schema manager - init from DB found:  " + numChanged + " types changed and " + numOutOfSync + " out of sync ");
            }
         }

         // There's no db_schema_type metadata, but there is metadata provided by the DB. We'll check it against the
         // current schema for differences:
         if (!dbMetadataFailed) {
            if (currentSchema != null) {
               for (SQLFileModel typeSchema:currentSchema) {
                  DBMetadata missingData = typeSchema.getMissingTableInfo(dbMetadata);
                  if (missingData != null) {
                     if (dbMissingMetadata == null)
                        dbMissingMetadata = missingData;
                     else
                        dbMissingMetadata.addMetadata(missingData);
                  }
               }
            }
         }

         if (changeReadyState) {
            // The deployed schema does not match and we are running interactively so tell apps to wait till we fix
            // the DB schema before running
            if ((initFromDBFailed || schemaChanged || dbMissingMetadata != null) && system.options.startInterpreter) {
               markSchemaNotReady();
            }
            // After initializing from the database, we find that the DB is setup properly - because we turned off
            // the default schema though, need to tell the apps to go ahead and run
            else if (!schemaChanged && schemaNotReady) {
               markSchemaReady();
            }
         }
      }
   }

   public void markSchemaNotReady() {
      ISchemaUpdater schemaUpdater = provider.getSchemaUpdater();
      schemaUpdater.setSchemaReady(dataSourceName, false);
      schemaNotReady = true;
   }

   public void markSchemaReady() {
      ISchemaUpdater schemaUpdater = provider.getSchemaUpdater();
      if (schemaUpdater != null)
         schemaUpdater.setSchemaReady(dataSourceName, true);
      schemaNotReady = false;
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

   static class GraphSortNode {
      SQLCommand cmd;
      List<GraphSortNode> refsFrom = new ArrayList<GraphSortNode>();
      List<GraphSortNode> refsTo = new ArrayList<GraphSortNode>();
      int depth;
      boolean depthAssigned;

      public String toString() {
         StringBuilder sb = new StringBuilder();
         sb.append(cmd);

         if (depthAssigned)
            sb.append(" (depth: " + depth + ")");
         if (refsFrom.size() > 0) {
            sb.append(" refsFrom[0]: " + refsFrom.get(0).cmd);
         }
         if (refsTo.size() > 0) {
            sb.append(" refsTo[0]: " + refsTo.get(0).cmd);
         }
         return sb.toString();
      }
   }

   /**
    * Sort the SQLFileModels based on table references. This assumes that the SQLCommands
    * inside of each model have the same dependencies - i.e. we don't have to interleave
    * commands for different types.  Because the file models form a 'directed acyclic graph'
    * we need to first build the graph, then do a breadth first search on that graph to
    * sort the commands.
    * TODO: this could be made more efficient - start with an index for finding the
    * references?
    */
   public ArrayList<SQLFileModel> sortSQLModels(List<SQLFileModel> inList) {
      List<GraphSortNode> graphNodes = new ArrayList<GraphSortNode>();
      StringBuilder metadata = new StringBuilder();
      for (SQLFileModel in:inList) {
         metadata.append("   Type: ");
         metadata.append(in.srcType.getFullTypeName());
         if (in.typeMetadata != null) {
            metadata.append(in.typeMetadata);
         }
         metadata.append("\n");

         List<SQLCommand> cmds = in.sqlCommands;
         if (cmds != null) {
            for (SQLCommand cmd:cmds) {
               GraphSortNode node = new GraphSortNode();
               node.cmd = cmd;
               graphNodes.add(node);
            }
         }
      }

      int num = graphNodes.size();

      for (int i = 0; i < num; i++) {
         GraphSortNode outer = graphNodes.get(i);
         for (int j = i+1; j < num; j++) {
            GraphSortNode inner = graphNodes.get(j);
            boolean hasTo = false;
            if (outer.cmd.hasReferenceTo(inner.cmd)) {
               // outer has a reference to inner
               outer.refsFrom.add(inner);
               inner.refsTo.add(outer);
               hasTo = true;
            }
            if (inner.cmd.hasReferenceTo(outer.cmd)) {
               if (hasTo) {
                  System.err.println("*** Error - cycle detected in dependencies");
               }
               else {
                  inner.refsFrom.add(outer);
                  outer.refsTo.add(inner);
               }
            }
         }
      }

      int assignedCt = 0;

      /** First pass is to just find all of the independent nodes */
      boolean remaining = false;
      for (int i = 0; i < num; i++) {
         GraphSortNode node = graphNodes.get(i);
         if (node.refsFrom.size() == 0) {
            node.depthAssigned = true;
            node.depth = 0;
            assignedCt++;
         }
         else
            remaining = true;
      }

      while (remaining) {
         remaining = false;
         for (int i = 0; i < num; i++) {
            GraphSortNode assignNode = graphNodes.get(i);
            if (!assignNode.depthAssigned) {
               boolean fromDepthAssigned = true;
               int maxFromDepth = -1;
               for (int j = 0; j < assignNode.refsFrom.size(); j++) {
                  GraphSortNode fromNode = assignNode.refsFrom.get(j);
                  if (fromNode.depthAssigned) {
                     if (maxFromDepth == -1 || maxFromDepth < fromNode.depth)
                        maxFromDepth = fromNode.depth;
                  }
                  else {
                     fromDepthAssigned = false;
                     break;
                  }
               }
               if (fromDepthAssigned) {
                  assignNode.depth = maxFromDepth + 1;
                  assignNode.depthAssigned = true;
               }
               else // There is a node in our refFrom that still has not been assigned a depth
                  remaining = true;
            }
         }
      }
      SemanticNodeList<SQLCommand> resCmds = new SemanticNodeList<SQLCommand>(inList.size());
      int currentDepth = 0;
      while (resCmds.size() < num) {
         for (int i = 0; i < num; i++) {
            GraphSortNode node = graphNodes.get(i);
            if (node.depthAssigned && node.depth == currentDepth)
               resCmds.add(node.cmd);
         }
         currentDepth++;
      }

      ArrayList<SQLFileModel> res = new ArrayList<SQLFileModel>(1);
      SQLFileModel mergedModel = new SQLFileModel();
      mergedModel.typeMetadata = metadata;
      mergedModel.setProperty("sqlCommands", resCmds);
      res.add(mergedModel);
      return res;
   }

   public boolean generateAlterSchema(Layer buildLayer) {
      String deployedSchemasDir = getDeployedSchemasDir(buildLayer);
      boolean allNewSchema = true;

      for (Map.Entry<String,SQLFileModel> ent:schemasByType.entrySet()) {
         String typeName = ent.getKey();
         SQLFileModel sqlModel = ent.getValue();

         String curSchemaFileName = getDeployedSchemaFile(deployedSchemasDir, typeName);
         if (!new File(curSchemaFileName).canRead()) {
            addToSchemaList(newModels, sqlModel);
         }
         else {
            allNewSchema = false;
            String oldFileBody = FileUtil.getFileAsString(curSchemaFileName);
            String curFileBody = sqlModel.toLanguageString();
            if (!oldFileBody.equals(curFileBody)) {
               Object parseRes = SQLLanguage.getSQLLanguage().parseString(oldFileBody);
               if (parseRes instanceof ParseError) {
                  System.err.println("*** Unable to parse deployedSchema sql file: " + curSchemaFileName + ": " + parseRes);
               }
               else {
                  SQLFileModel oldModel = (SQLFileModel) ParseUtil.nodeToSemanticValue(parseRes);
                  oldModel.srcType = sqlModel.srcType;
                  changedTypes.add(new SchemaTypeChange(typeName, oldModel, sqlModel));
               }
            }
         }
      }

      // TODO: look for deleted types and generate the drop script to remove the old SQL commands?

      if (changedTypes.size() == 0 && newModels.size() == 0) {
         DBUtil.verbose("No changes to deployed schema for dataSource: " + dataSourceName + " for buildLayer: " + buildLayer);
      }
      else if (changedTypes.size() > 0 || !allNewSchema) {
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
         if (schemaMode == SchemaMode.Prompt) {
            if (system.options.startInterpreter && system.cmd != null) {
               system.cmd.addCommandWizard(new SchemaUpdateWizard(system.cmd, system, this));
            }
            else {
               if (system.defaultDBProvider == null || system.defaultDBProvider.needsSchema) {
                  if (noCurrentSchema) {
                     system.warning("Database schema may not be in sync: no deployed/cached schema files and not running SchemaUpdateWizard because command interpreter is disabled");
                  }
                  else {
                     system.warning("Database schema has changed and not running SchemaUpdateWizard because command interpreter is disabled");
                  }
               }
            }
         }
      }
      return schemaChanged;
   }

   void updateAlterModel(SchemaTypeChange change) {
      ArrayList<SchemaChangeDetail> notUpgradeable = new ArrayList<SchemaChangeDetail>();
      // Get the list of alter commands to convert a DDL defined by fromModel.sqlCommands to one defined by toModel.sqlCommands
      SQLFileModel updateModel = change.fromModel.alterTo(change.toModel, notUpgradeable);
      if (updateModel != null)
         addToSchemaList(alterSchema, updateModel);
      change.alterModel = updateModel;
      change.notUpgradeable = notUpgradeable;
   }

   void updateAlterSchema(Layer buildLayer) {
      for (SQLFileModel newModel:newModels)
         addToSchemaList(alterSchema, newModel);
      for (SchemaTypeChange change:changedTypes) {
         try {
            updateAlterModel(change);
         }
         catch (UnsupportedOperationException exc) {
            DBUtil.error("Unable to create SQL script to update for change: " + change.fromModel);
         }
      }

      // TODO: include info in this file in the from and to schema versions, or maybe the dates? The deployedSchema files should have the dates, version
      // info stored (optionally) so we can track the database using the metadata table. Otherwise, we'll just use the last build date and snag the last
      // build version of the program in the deployed schema directory.
      StringBuilder alterSB = convertSQLModelsToString(alterSchema, "Alter table script", buildLayer);
      if (saveSchemaFile(buildLayer, alterSB, "alter")) {
         schemaChanged = true;
      }
   }

   StringBuilder convertSQLModelsToString(List<SQLFileModel> unsortedList, String message, Layer buildLayer) {
      if (unsortedList == null || unsortedList.size() == 0)
         return null;

      List<SQLFileModel> sqlFileModels = sortSQLModels(unsortedList);

      StringBuilder schemaSB = new StringBuilder();
      schemaSB.append("/*** " + message + " for dataSource: " + dataSourceName + " build layer: " + buildLayer.getLayerName() + " */\n");
      for (SQLFileModel sqlFileModel:sqlFileModels) {
         if (sqlFileModel.srcType != null) {
            if (sqlFileModel.typeMetadata != null)
               schemaSB.append("\n/*\n   Type: ");
            else
               schemaSB.append("\n/* Type: ");
            schemaSB.append(sqlFileModel.srcType.getFullTypeName());
            if (sqlFileModel.typeMetadata != null)
               schemaSB.append(sqlFileModel.typeMetadata);
            schemaSB.append(" */\n");
         }
         // When we sort the commands they all get merged into one SQLFileModel so there's a comment
         // at the top for the type metadata for all of the types
         else if (sqlFileModel.typeMetadata != null) {
            schemaSB.append("/*\n");
            schemaSB.append(sqlFileModel.typeMetadata);
            schemaSB.append(" */\n\n");
         }
         schemaSB.append(sqlFileModel.toLanguageString());
      }
      return schemaSB;
   }

   SrcEntry getCurrentSchemaSrcEntry(Layer buildLayer, String postfix) {
      // TODO: should we use the dbname here?
      String dataSourceFile = dataSourceName.replace("/", "_");
      if (postfix != null)
         dataSourceFile += "-" + postfix;
      return new SrcEntry(buildLayer, buildLayer.buildSrcDir, "",  FileUtil.addExtension(dataSourceFile, "sql"), false, null);
   }

   boolean saveSchemaFile(Layer buildLayer, StringBuilder schemaSB, String postfix) {
      SrcEntry sqlSrcEnt = getCurrentSchemaSrcEntry(buildLayer, postfix);
      String schemaStr = schemaSB.toString();
      sqlSrcEnt.hash = StringUtil.computeHash(schemaStr.getBytes());

      SrcIndexEntry srcIndex = buildLayer.getSrcFileIndex(sqlSrcEnt.relFileName);
      // Avoid rewriting unchanged files
      if (srcIndex == null || !Arrays.equals(srcIndex.hash, sqlSrcEnt.hash)) {
         FileUtil.saveStringAsReadOnlyFile(sqlSrcEnt.absFileName, schemaStr, false);
         buildLayer.addSrcFileIndex(sqlSrcEnt.relFileName, sqlSrcEnt.hash, null, sqlSrcEnt.absFileName);

         StringBuilder logSB = new StringBuilder("- ");
         boolean info = false;
         if (srcIndex != null) {
            if (postfix == null)
               logSB.append("Schema changed: ");
            else
               logSB.append(CTypeUtil.capitalizePropertyName(postfix) + " schema changed: ");
            info = true;
         }
         else {
            logSB.append("New build for " + (postfix == null ? "" : postfix + " ") + "schema: ");
         }
         logSB.append(sqlSrcEnt.absFileName);
         logSB.append("\n");
         logSB.append(schemaStr);
         logSB.append("---");
         if (info)
            DBUtil.info(logSB);
         else
            DBUtil.verbose(logSB);
         return true;
      }
      else
         DBUtil.verbose("DB" + (postfix == null ? "" : " " + postfix) +  " schema: unchanged: " + sqlSrcEnt.absFileName);
      return false;
   }

   public StringBuilder getCurrentSchema() {
      return convertSQLModelsToString(currentSchema, "Database schema", buildLayer);
   }

   public StringBuilder getDropSchema() {
      if (currentSchema == null || currentSchema.size() == 0)
         return null;

      ArrayList<SQLFileModel> sortedSchema = sortSQLModels(currentSchema);

      StringBuilder dropSB = new StringBuilder();
      dropSB.append("/* " + "Drop schema - dataSource: " + dataSourceName + " */\n\n");
      for (SQLFileModel sqlFileModel:sortedSchema) {
         SQLFileModel dropModel = sqlFileModel.createDropSQLModel();
         if (dropModel == null)
            continue;
         if (sqlFileModel.srcType != null)
            dropSB.append("/* Drop type: " + sqlFileModel.srcType.getFullTypeName() + " */\n");
         else if (sqlFileModel.typeMetadata != null)
            dropSB.append("/* Drop schema for: " + sqlFileModel.typeMetadata + "\n */\n");
         dropSB.append(dropModel.toLanguageString());
      }
      return dropSB;
   }

   public StringBuilder getAlterSchema() {
      return convertSQLModelsToString(alterSchema, "Alter table script", buildLayer);
   }

   public void saveCurrentSchema(Layer buildLayer) {
      this.buildLayer = buildLayer;
      StringBuilder schemaSB = getCurrentSchema();

      boolean changed = false;
      if (schemaSB != null) {
         changed = saveSchemaFile(buildLayer, schemaSB, null);
      }

      StringBuilder dropSB = getDropSchema();
      if (dropSB != null) {
         saveSchemaFile(buildLayer, dropSB, "drop");
      }

      if (changed) {
         if (generateAlterSchema(buildLayer)) {
            if (system.options.startInterpreter && schemaMode == SchemaMode.Prompt) {
               if (!noCurrentSchema)
                  DBUtil.info("Schema changes found - setting defaultSchemaReady=false");
               DataSourceManager.defaultSchemaReady = false;
            }
            schemaNotReady = true;
         }
      }
   }

   public static void replaceInSchemaList(List<SQLFileModel> schemaList, SQLFileModel oldModel, SQLFileModel sqlModel) {
      if (!schemaList.remove(oldModel))
         System.err.println("*** Unable to remove old model");
      addToSchemaList(schemaList, sqlModel);
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
      return newModels.size() + changedTypes.size() + conflictModels.size();
   }

   public String getChangedTypeName(int index) {
      int newSize = newModels.size();
      if (index < newSize)
         return newModels.get(index).srcType.getFullTypeName();
      else if (index < newSize + changedTypes.size())
         return changedTypes.get(index).fromModel.srcType.getFullTypeName();
      else
         return conflictModels.get(index).srcType.getFullTypeName();
   }

   public boolean updateSchema(Layer buildLayer, boolean doAlter) {
      ISchemaUpdater updater = provider.getSchemaUpdater();
      if (updater == null) {
         throw new IllegalArgumentException("No schema updater configured");
      }
      int nsz = newModels.size();
      int nchanges = changedTypes.size();

      if (doAlter) {
         ArrayList<SQLFileModel> modelsToSort = new ArrayList<SQLFileModel>(nsz + nchanges);
         for (int i = 0; i < nsz; i++) {
            modelsToSort.add(newModels.get(i));
         }
         for (int i = 0; i < nchanges; i++) {
            modelsToSort.add(changedTypes.get(i).alterModel);
         }
         ArrayList<SQLFileModel> sortedModels = sortSQLModels(modelsToSort);
         try {
            for (SQLFileModel sortedModel:sortedModels)
               updater.applyAlterCommands(dataSourceName, sortedModel.getCommandList());
         }
         catch (IllegalArgumentException exc) {
            DBUtil.error("Update schema failed: " + exc.getMessage() + exc.getCause());
            return false;
         }
      }

      for (int i = 0; i < nsz; i++) {
         SQLFileModel newModel = newModels.get(i);
         DBSchemaType info = new DBSchemaType();
         String typeName = newModel.srcType.getFullTypeName();
         info.setTypeName(typeName);
         DBSchemaVersion curVersion = info.getCurrentVersion();
         String schemaSQL = newModel.toLanguageString();
         curVersion.setSchemaSQL(schemaSQL);
         curVersion.setDateApplied(new Date());
         updateDBSchema(updater, typeName, info, newModel, buildLayer);
      }

      for (int i = 0; i < nchanges; i++) {
         SchemaTypeChange change = changedTypes.get(i);
         SQLFileModel newModel = change.toModel;
         DBSchemaType info = new DBSchemaType();
         String typeName = change.typeName;
         info.setTypeName(typeName);
         DBSchemaVersion curVersion = info.getCurrentVersion();
         curVersion.setSchemaSQL(newModel.toLanguageString());
         curVersion.setDateApplied(new Date());
         if (change.alterModel == null) {
            System.out.println("*** Missing alter schema commands for change");
            continue;
         }
         String alterSQL = change.alterModel.toLanguageString();
         curVersion.setAlterSQL(alterSQL);
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
