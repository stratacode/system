package sc.db;

import java.util.ArrayList;
import java.util.List;

class FetchTableDesc {
   TableDescriptor table;
   List<DBPropertyDescriptor> props;

   // Only set if this table corresponds to a reference property of the parent type
   DBPropertyDescriptor refProp;

   FetchTableDesc copyForRef(DBPropertyDescriptor refProp) {
      FetchTableDesc res = new FetchTableDesc();
      res.table = table;
      res.props = new ArrayList<DBPropertyDescriptor>(props);
      res.refProp = refProp;
      return res;
   }
}
