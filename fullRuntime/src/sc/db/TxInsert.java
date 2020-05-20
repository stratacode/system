package sc.db;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class TxInsert extends TxOperation {
   public TxInsert(DBTransaction tx, DBObject inst) {
      super(tx, inst);
   }

   public int apply() {
      if (applied)
         throw new IllegalArgumentException("Already applied insert!");
      applied = true;
      insertTransientRefs(true);
      DBTypeDescriptor dbTypeDesc = dbObject.dbTypeDesc;
      List<TableDescriptor> tables = dbTypeDesc.auxTables;
      int ct = doInsert(dbTypeDesc.primaryTable);
      if (ct > 0) {
         if (tables != null) {
            for (TableDescriptor table:tables)
               ct += doInsert(table);
         }
         tables = dbTypeDesc.multiTables;
         if (tables != null) {
            for (TableDescriptor table:tables)
               ct += doMultiInsert(table, null, true);
         }
      }
      insertTransientRefs(false);
      return ct;
   }

   public Map<String,String> validate() {
      IDBObject inst = dbObject.getInst();

      DBTypeDescriptor dbTypeDesc = dbObject.dbTypeDesc;

      List<DBPropertyDescriptor> allProps = dbTypeDesc.allDBProps;
      int psz = allProps.size();

      Map<String,String> res = null;

      for (int i = 0; i < psz; i++) {
         DBPropertyDescriptor prop = allProps.get(i);

         if (prop.hasValidator()) {
            String error = prop.validate(dbObject, prop.getPropertyMapper().getPropertyValue(inst, false, false));
            if (error != null) {
               if (res == null)
                  res = new TreeMap<String,String>();
               res.put(prop.propertyName, error);
            }
         }
      }
      return res;
   }
}
