package sc.lang.sql;

import sc.db.DBTypeDescriptor;
import sc.db.TableDescriptor;
import sc.lang.java.JavaModel;
import sc.lang.java.ModelUtil;

public class SQLUtil {
   // TODO: if we already have a SQLFileModel, we could modify the existing one to preserve aspects of the SQL we don't
   // capture in the DBTypeDescriptor. Or we could add those features to the DBTypeDescriptor and pass them through here
   // so that we could modify those features as well.
   public static SQLFileModel convertTypeToSQLFileModel(JavaModel fromModel, DBTypeDescriptor dbTypeDesc) {
      SQLFileModel res = new SQLFileModel();
      if (!dbTypeDesc.tablesInitialized)
         ModelUtil.completeDBTypeDescriptor(dbTypeDesc, fromModel.layeredSystem, fromModel.layer, dbTypeDesc);
      dbTypeDesc.init();
      dbTypeDesc.start();
      res.layeredSystem = fromModel.layeredSystem;
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
      return res;
   }

}
