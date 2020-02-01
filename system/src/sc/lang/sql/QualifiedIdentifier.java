package sc.lang.sql;

import sc.lang.SemanticNode;
import sc.parser.IString;
import sc.util.StringUtil;

import java.util.List;

public class QualifiedIdentifier extends SQLIdentifier {
   public List<IString> identifiers;

   public String toString() {
      return StringUtil.arrayToType(identifiers.toArray());
   }
}
