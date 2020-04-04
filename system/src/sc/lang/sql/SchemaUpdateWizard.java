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

   public int currentTypeIx = 0;

   public SchemaUpdateWizard(AbstractInterpreter cmd, LayeredSystem sys, SchemaManager mgr) {
      this.commandInterpreter = cmd;
      this.mgr = mgr;
      this.system = sys;
   }

   public boolean getActive() {
      this.mgr.initFromDB(system.buildLayer);
      return mgr.schemaChanged;
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
      print("   d - show drop schema");
      print(" <i> - select type by number");
   }

   private void printNoSchemaUpdater() {
      print("No schemaUpdater included in the application. Add the layer: jdbc.schemaManager and re-run");
   }

   private void printCurrentType() {
      int nsz = this.mgr.newModels.size();
      if (currentTypeIx >= nsz) {
         printChange(this.mgr.changedTypes.get(currentTypeIx - nsz));
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
         print("   New types: " + this.mgr.newModels.size());
         printNewModelsList();
      }
      if (this.mgr.changedTypes.size() > 0) {
         print(" Changes to types: " + this.mgr.changedTypes.size());
         printChangesList();
      }
   }

   private void printNewModelsList() {
      List<SQLFileModel> newModels = this.mgr.newModels;
      int nsz = newModels.size();
      for (int i = 0; i < nsz; i++) {
         SQLFileModel model = newModels.get(i);
         print("   " + (i == currentTypeIx? "*" : "") + "[" + i + "]: " + model.srcType.getFullTypeName() + ": " + model.getCommandSummary());
      }
   }

   private void printChangesList() {
      List<SchemaManager.SchemaTypeChange> changes = this.mgr.changedTypes;
      int nsz = this.mgr.newModels.size();
      for (int i = 0; i < changes.size(); i++) {
         SchemaManager.SchemaTypeChange change = changes.get(i);
         int ix = i + nsz;
         print((ix == currentTypeIx ? "*": "") + "[" + ix + "] changed: " + change.fromModel.srcType.typeName);
      }
   }

   private void printChange(SchemaManager.SchemaTypeChange change) {
      print("Change to schema for type: " + change.fromModel.srcType.typeName);
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
                     printNewSchema();
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
                     if (mgr.updateSchema(system.buildLayer, true))
                        print("Schema update complete...");
                     else
                        print("Schema update failed");
                  }
                  break;
               case 'a':
                  if (mgr.provider.getSchemaUpdater() == null)
                     printNoSchemaUpdater();
                  else {
                     print("Accepting existing schema...");
                     if (mgr.updateSchema(system.buildLayer, false))
                        print("Accept schema complete...");
                     else
                        print("Accept schema failed");
                  }
                  break;
               case 'd':
                  print("--- SQL drop commands to remove existing schema:");
                  print(mgr.getDropSchema().toString());
                  print("---");
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
         print("   --- old schema:");
         print(change.fromModel.toLanguageString());
         print("   --- new schema:");
         print(change.toModel.toLanguageString());
         print("   --- alter schema:");
         print(change.alterModel.toLanguageString());
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
