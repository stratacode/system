package sc.lang.sql;

import sc.db.DBTypeDescriptor;
import sc.db.TableDescriptor;

public class SQLUtil {
   // TODO: if we already have a SQLFileModel, we could modify the existing one to preserve aspects of the SQL we don't
   // capture in the DBTypeDescriptor. Or we could add those features to the DBTypeDescriptor and pass them through here
   // so that we could modify those features as well.
   public static SQLFileModel convertTypeToSQLFileModel(DBTypeDescriptor typeDesc) {
      SQLFileModel res = new SQLFileModel();
      res.addCreateTable(typeDesc.primaryTable);
      if (typeDesc.auxTables != null) {
         for (TableDescriptor auxTable:typeDesc.auxTables) {
            res.addCreateTable(auxTable);
         }
      }
      if (typeDesc.multiTables != null) {
         for (TableDescriptor multiTable:typeDesc.multiTables) {
            res.addCreateTable(multiTable);
         }
      }
      return res;
   }

}
