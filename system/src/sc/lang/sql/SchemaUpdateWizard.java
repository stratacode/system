package sc.lang.sql;

import sc.db.DBSchemaType;
import sc.lang.AbstractInterpreter;
import sc.lang.CommandWizard;
import sc.layer.LayeredSystem;

import java.util.List;

public class SchemaUpdateWizard extends CommandWizard {
   SchemaManager mgr;
   LayeredSystem system;

   enum UpdateSchemaState {
      Init
   }

   UpdateSchemaState curState = UpdateSchemaState.Init;

   boolean firstTime = true;

   boolean waitForQuit = false;

   public int currentTypeIx = 0;

   public SchemaUpdateWizard(AbstractInterpreter cmd, LayeredSystem sys, SchemaManager mgr) {
      this.commandInterpreter = cmd;
      this.mgr = mgr;
      this.system = sys;
   }

   public boolean getActive() {
      this.mgr.initFromDB(system.buildLayer, true);
      return mgr.schemaChanged || waitForQuit;
   }

   public String prompt() {
      // Make sure to call initFromDB before we do the prompt
      if (!getActive())
         return "SchemaUpdateWizard - exiting";

      switch (curState) {
         case Init:
            if (firstTime) {
               print("SchemaUpdateWizard - schema changed for " + mgr.dataSourceName + " building: " + system.buildLayer);

               printList();

               usage();

               firstTime = false;
            }

            break;
      }
      //return "Enter: h - help, u - update, P - show new, O - show deployed: [hulPpOoq]: ";
      return "Enter [hcunolNOtaq<i>]: ";
   }

   private void usage() {
      if (mgr.provider.getSchemaUpdater() == null)
         printNoSchemaUpdater();
      print("   c - show 'create/alter' script to update existing schema");
      print("   u - update schema");
      print("   n - show new schema");
      print("   o - show deployed schema for all types");
      print("   l - list new/changed types");
      print("   N - show schema/changes for current type");
      print("   O - show current deployed schema for current type ");
      print("   q - quit schema update wizard");
      print("   t - go to next type");
      print("   a - accept current schema for this layer without updating database schema");
      print("   d - diff current schema with database metadata");
      print("   D - show drop schema");
      print("   r - refresh database metadata");
      print(" <i> - select type by number");
   }

   private void printNoSchemaUpdater() {
      print("No schemaUpdater included in the application. Add the layer: jdbc.schemaManager and re-run");
   }

   private void printCurrentType() {
      int nsz = this.mgr.newModels.size();
      int csz = this.mgr.changedTypes.size();
      if (currentTypeIx >= nsz) {
         if (currentTypeIx - nsz < csz)
            printChange(this.mgr.changedTypes.get(currentTypeIx - nsz));
         else {
            SQLFileModel conflictModel = this.mgr.conflictModels.get(currentTypeIx - nsz - csz);
            print("Conflicting type: " + conflictModel.srcType.typeName);
            print("--- ");
            print(conflictModel.toLanguageString());
         }
      }
      else {
         SQLFileModel newModel = this.mgr.newModels.get(currentTypeIx);
         print("New schema for type: " + newModel.srcType.typeName);
         print("--- ");
         print(newModel.toLanguageString());
      }
   }

   private void printList() {
      if (mgr.noCurrentSchema) {
         print("First run of schema update for buildLayer: " + system.buildLayer);
      }
      if (this.mgr.initFromDBFailed) {
         print("Failed to connect to DB for current schema\n");
      }

      print("Schema changes:");
      if (this.mgr.newModels.size() > 0) {
         print("- New types: " + this.mgr.newModels.size());
         printNewModelsList();
         print("---");
      }
      if (this.mgr.changedTypes.size() > 0) {
         print("- Changes to types: " + this.mgr.changedTypes.size());
         printChangesList();
         print("---");
      }
      if (this.mgr.conflictModels.size() > 0) {
         print("- Conflicting types: " + this.mgr.conflictModels.size());
         printConflictModelsList();
         print("---");
      }

      if (mgr.dbMissingMetadata != null && (!mgr.noCurrentSchema && !mgr.initFromDBFailed)) {
         print("- Warning: current database is missing these items:" + mgr.dbMissingMetadata + "\n---");
      }
      if (mgr.dbExtraMetadata != null) {
         print("- Warning: current database has extra items:" + mgr.dbExtraMetadata + "\n---");
      }
   }

   private void printNewModelsList() {
      List<SQLFileModel> newModels = this.mgr.newModels;
      int nsz = newModels.size();
      for (int i = 0; i < nsz; i++) {
         SQLFileModel model = newModels.get(i);
         print("     " + (i == currentTypeIx? "*" : " ") + "[" + i + "]: " + model.srcType.getFullTypeName() + ": " + model.getCommandSummary());
      }
   }

   private void printChangesList() {
      List<SchemaManager.SchemaTypeChange> changes = this.mgr.changedTypes;
      int nsz = this.mgr.newModels.size();
      for (int i = 0; i < changes.size(); i++) {
         SchemaManager.SchemaTypeChange change = changes.get(i);
         int ix = i + nsz;
         print((ix == currentTypeIx ? "*": "") + "[" + ix + "] changed: " + change.fromModel.srcType.typeName +
                (change.alterModel == null ? " (no alter ddl)" : " (alter ddl available)"));
         printUpgradeWarning(change);
      }
   }

   private void printConflictModelsList() {
      List<SQLFileModel> conflictModels = this.mgr.conflictModels;
      int nsz = this.mgr.newModels.size();
      int csz = this.mgr.changedTypes.size();
      for (int i = 0; i < nsz; i++) {
         SQLFileModel model = conflictModels.get(i);
         int ix = i + nsz + csz;
         print("     " + (ix == currentTypeIx? "*" : " ") + "[" + ix + "] conflict: " + model.srcType.getFullTypeName() + ": " + model.getCommandSummary());
      }
   }

   private void printUpgradeWarning(SchemaManager.SchemaTypeChange change) {
      if (mgr.conflictModels.size() > 0)
         print("   Warning - schema not upgradeable due to conflicts found in metadata");
      if (change.notUpgradeable != null && change.notUpgradeable.size() > 0) {
         print("   Warning - tables for type cannot be upgraded without losing data:");
         for (int i = 0; i < change.notUpgradeable.size(); i++) {
            print("      " + change.notUpgradeable.get(i));
         }
      }
   }

   private void printChange(SchemaManager.SchemaTypeChange change) {
      print("Change to schema for type: " + change.fromModel.srcType.typeName);
      printUpgradeWarning(change);
      print("--- alterSQL:\n" + change.alterModel.toLanguageString());
   }

   @Override
   public Object parseCommand(String input) {
      commandInterpreter.pendingInput = new StringBuilder();

      input = input.trim();
      Character cmdChar = input.length() == 1 ? input.charAt(0) : null;
      if (cmdChar == null) {
         int curInt;
         try {
            curInt = Integer.parseInt(input);
            if (curInt > mgr.getNumChangedTypes() || curInt < 0) {
               print("Type selection out of range: 0-" + mgr.getNumChangedTypes());
               return Boolean.TRUE;
            }
            currentTypeIx = curInt;
         }
         catch (NumberFormatException exc) {
            print("- Invalid input: " + input);
         }
         usage();
         return Boolean.TRUE;
      }
      if (cmdChar == 'q' || cmdChar == 'Q') {
         mgr.markSchemaReady();
         system.cmd.completeCommandWizard(this);
         return Boolean.TRUE;
      }

      switch (curState) {
         case Init:
            switch (cmdChar) {
               case 'l':
                  printList();
                  break;
               case 'c':
                  StringBuilder alterSB = mgr.getAlterSchema();
                  if (alterSB == null) {
                     if (mgr.dbMissingMetadata != null) {
                        print("--- No alter schema despite missing tables/columns - showing entire schema.");
                        printNewSchema();
                     }
                     else
                        print("--- No alter schema and no missing metadata");
                  }
                  else {
                     print("--- SQL alter/create commands to update existing schema:");
                     print(alterSB.toString());
                     print("---");
                  }
                  break;
               case 'N':
                  printNewType();
                  break;
               case 'n':
                  printNewSchema();
                  break;
               case 't':
                  if (currentTypeIx + 1 >= mgr.getNumChangedTypes())
                     print("No more changed types - " + mgr.getNumChangedTypes());
                  else
                     currentTypeIx++;
                  printCurrentType();
                  break;
               case 'O':
                  if (mgr.provider.getSchemaUpdater() == null)
                     printNoSchemaUpdater();
                  else
                     printDeployedDBType();
                  break;
               case 'o':
                  if (mgr.provider.getSchemaUpdater() == null)
                     printNoSchemaUpdater();
                  else
                     print(mgr.getDeployedDBSchemaInfo().toString());
                  break;
               case 'u':
                  if (mgr.provider.getSchemaUpdater() == null)
                     printNoSchemaUpdater();
                  else {
                     print("Updating schema...");
                     if (mgr.updateSchema(system.buildLayer, true)) {
                        print("Schema update complete...");
                        mgr.initFromDB(system.buildLayer, false);
                        if (!mgr.schemaChanged) {
                           waitForQuit = true;
                           print("\nSchema update completed and all tables/columns are registered.\n");
                           print("Enter q to exit and continue");
                        }
                        else {
                           print("Warning: schema updated but metadata does not match");
                           printList();
                        }
                     }
                     else
                        print("Schema update failed");
                  }
                  break;
               case 'a':
                  if (mgr.provider.getSchemaUpdater() == null)
                     printNoSchemaUpdater();
                  else {
                     print("Accepting existing schema...");
                     if (mgr.updateSchema(system.buildLayer, false)) {
                        waitForQuit = true;
                        mgr.needsInitFromDB = true;
                        mgr.initFromDB(system.buildLayer, false);
                        if (!mgr.schemaChanged) {
                           waitForQuit = true;
                           print("\nAccept schema complete - all tables/columns present in the DB");
                           print("Enter q to exit and continue");
                        }
                        else {
                           if (mgr.dbMissingMetadata != null || mgr.dbExtraMetadata != null)
                              print("Warning: schema accepted but metadata does not match");
                           printList();
                        }
                     }
                     else
                        print("Accept schema failed");
                  }
                  break;
               case 'D':
                  print("--- SQL drop commands to remove existing schema:");
                  print(mgr.getDropSchema().toString());
                  print("---");
                  break;
               case 'r':
                  mgr.needsInitFromDB = true;
                  mgr.initFromDB(system.buildLayer, false);
                  // Fall through and show the differences
               case 'd':
                  // TODO: do a refresh of the meta data here?
                  if (mgr.dbExtraMetadata == null && mgr.dbMissingMetadata == null) {
                     print("- No differences in metadata between schema and database");
                  }
                  else {
                     if (mgr.dbMissingMetadata != null) {
                        print("--- Database is missing:\n" + mgr.dbMissingMetadata + "\n----");
                     }
                     if (mgr.dbExtraMetadata != null) {
                        print("--- Database has extra info:\n" + mgr.dbExtraMetadata + "\n----");
                     }
                  }
                  break;
               default:
                  print("Unrecognized command: " + cmdChar);
                  usage();
                  break;
            }
            break;
      }
      return Boolean.TRUE;
   }

   private void printNewSchema() {
      print("--- SQL commands to create new database schema:");
      print(mgr.getCurrentSchema().toString());
      print("---");
   }

   private void printNewType() {
      int newSize = mgr.newModels.size();
      if (currentTypeIx < newSize) {
         SQLFileModel newModel = mgr.newModels.get(currentTypeIx);
         print("New type: " + newModel.srcType.typeName);
         print(newModel.toLanguageString());
      }
      else {
         SchemaManager.SchemaTypeChange change = mgr.changedTypes.get(currentTypeIx - newSize);
         print("Schema change for type: " + change.fromModel.srcType.typeName);
         printUpgradeWarning(change);
         print("   --- old schema:");
         print(change.fromModel.toLanguageString());
         print("   --- new schema:");
         print(change.toModel.toLanguageString());
         if (change.alterModel == null)
            print("- no alter schema");
         else {
            print("   --- alter schema:");
            print(change.alterModel.toLanguageString());
         }
         // TODO: print alter table schema here?
      }
   }

   private void printDeployedDBType() {
      String typeName = mgr.getChangedTypeName(currentTypeIx);
      if (mgr.dbSchemaTypes != null) {
         for (DBSchemaType info:mgr.dbSchemaTypes) {
            if (info.getTypeName().equals(typeName)) {
               print("--- Current db schema for type:  "+ info.getTypeName() + ": ");
               print(info.getCurrentVersion().getSchemaSQL());
               return;
            }
         }
      }
      print("No schema found in database for type: " + typeName);
   }
}
