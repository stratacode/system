package sc.db;

import java.util.ArrayList;
import java.util.List;

/**
 * A given database query - i.e. a FetchTablesQuery - contains one or more FetchTableDesc - to describe the info needed to fetch this particular table's properties in this
 * query.
 */
class FetchTableDesc {
   TableDescriptor table;
   List<DBPropertyDescriptor> props;

   /** List of columns fetched with the columns in this table but that refer to reverse relationships to the current type */
   List<DBPropertyDescriptor> revColumns;
   /** For each revColumn, the property reference in the parent object that points to that column value */
   List<DBPropertyDescriptor> revProps;

   // Only set if this table corresponds to a reference property of the parent type
   DBPropertyDescriptor refProp;

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
         sb.append(" (");
         for (int i = 0; i < props.size(); i++) {
            Object prop = props.get(i);
            if (i != 0)
               sb.append(", ");
            sb.append(prop);
         }
         sb.append(") ");
      }
      if (revProps != null) {
         sb.append(" reverse to: " + revProps + "." + revColumns);
      }
      return sb.toString();
   }

   public FetchTableDesc clone() {
      FetchTableDesc res = new FetchTableDesc();
      res.table = table;
      res.props = new ArrayList<DBPropertyDescriptor>(props);
      if (res.revProps != null)
         res.revProps = new ArrayList<DBPropertyDescriptor>(revProps);
      if (res.revColumns != null)
         res.revColumns = new ArrayList<DBPropertyDescriptor>(revColumns);
      res.refProp = refProp;
      return res;
   }

   public boolean containsProperty(DBPropertyDescriptor pdesc) {
      if (props.contains(pdesc))
         return true;
      if (revProps != null && revProps.contains(pdesc))
         return true;
      if (refProp != null && refProp.equals(pdesc))
         return true;
      return false;
   }
}
