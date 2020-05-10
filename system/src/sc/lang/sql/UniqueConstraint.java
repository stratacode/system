package sc.lang.sql;

import sc.lang.SemanticNode;
import sc.parser.IString;

import java.util.List;

public class UniqueConstraint extends SQLConstraint {
   public List<SQLIdentifier> columnList; // at the table level
   public IndexParameters indexParams;

   public String toString() {
      if (columnList == null)
         return "unique";
      else {
         StringBuilder sb = new StringBuilder();
         sb.append("unique (");
         sb.append(columnList);
         sb.append(")");
         return sb.toString();
      }
   }
}
