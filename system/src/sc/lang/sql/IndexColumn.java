package sc.lang.sql;

import sc.lang.SemanticNode;

public class IndexColumn extends BaseIndexColumn {
   public SQLIdentifier columnName;

   public static IndexColumn create(String columnName) {
      IndexColumn res = new IndexColumn();
      res.columnName = SQLIdentifier.create(columnName);
      return res;
   }
}
