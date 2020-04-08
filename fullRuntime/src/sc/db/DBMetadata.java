package sc.db;

import java.util.ArrayList;
import java.util.List;

/** Used as a container for metadata extracted from the current database */
public class DBMetadata {
   public String dataSourceName;
   public List<TableInfo> tableInfos = new ArrayList<TableInfo>();

   public TableInfo getTableInfo(String tableName) {
      if (tableInfos == null)
         return null;
      for (TableInfo ti:tableInfos)
         if (ti.tableName.equals(tableName))
            return ti;
      return null;
   }

   public void addMetadata(DBMetadata md) {
      tableInfos.addAll(md.tableInfos);
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < tableInfos.size(); i++) {
         if (i != 0)
            sb.append(", ");
         sb.append(tableInfos.get(i));
      }
      return sb.toString();
   }
}
