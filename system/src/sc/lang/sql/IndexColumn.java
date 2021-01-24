/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.SemanticNode;

public class IndexColumn extends BaseIndexColumn {
   public SQLIdentifier columnName;

   public static IndexColumn create(String columnName) {
      IndexColumn res = new IndexColumn();
      res.columnName = SQLIdentifier.create(columnName);
      return res;
   }

   public String toString() {
      if (columnName == null)
         return "<null index column name>";
      return columnName.getIdentifier();
   }
}
