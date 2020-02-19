package sc.db;

import java.util.ArrayList;
import java.util.List;

class FetchTableDesc {
   TableDescriptor table;
   List<DBPropertyDescriptor> props;

   /** List of columns fetched with the columns in this table but that refer to reverse relationships to the current type */
   List<DBPropertyDescriptor> revColumns;
   /** For each revColumn, the property reference in the parent object that points to that column value */
   List<DBPropertyDescriptor> revProps;

   // Only set if this table corresponds to a reference property of the parent type
   DBPropertyDescriptor refProp;

   DBPropertyDescriptor joinProp;

   FetchTableDesc copyForRef(DBPropertyDescriptor refProp) {
      FetchTableDesc res = new FetchTableDesc();
      res.table = table;
      res.props = new ArrayList<DBPropertyDescriptor>(props);
      res.refProp = refProp;
      return res;
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(table == null ? "<null-fetch-table>" : table.tableName);
      if (props != null) {
         sb.append(props);
      }
      if (revProps != null) {
         sb.append(" reverse to: " + revProps + "." + revColumns);
      }
      return sb.toString();
   }
}
