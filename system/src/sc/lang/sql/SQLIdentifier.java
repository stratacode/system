package sc.lang.sql;

import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.parser.IString;
import sc.parser.PString;

public abstract class SQLIdentifier extends SQLParamType {
   // TODO: fix this we know whether or not to use QuotedIdentifier - maybe there's an extra flag we pass around in each DBTypeDescriptor or DBPropertyDescriptor
   public static SQLIdentifier create(String str) {
      QualifiedIdentifier qual = new QualifiedIdentifier();
      qual.identifiers = new SemanticNodeList<IString>();
      qual.identifiers.add(PString.toIString(str));
      return qual;
   }

   public abstract String getIdentifier();

   public String toString() {
      return getIdentifier();
   }
}
