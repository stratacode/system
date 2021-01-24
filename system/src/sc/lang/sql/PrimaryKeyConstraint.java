/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.SemanticNode;
import sc.parser.IString;

import java.util.List;

public class PrimaryKeyConstraint extends SQLConstraint {
   public List<SQLIdentifier> columnList; // at the table level
   public IndexParameters indexParams;

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("PRIMARY KEY");
      if (columnList != null) {
         sb.append(" (");
         sb.append(columnList);
         sb.append("(");
      }
      if (indexParams != null) {
         sb.append(" ");
         sb.append(indexParams);
      }
      return sb.toString();
   }
}
