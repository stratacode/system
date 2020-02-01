package sc.lang.sql;

import sc.lang.SemanticNode;
import sc.util.StringUtil;

public class QuotedIdentifier extends SQLIdentifier {
   public String value;

   public String toString() {
      return value;
   }
}
