/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.SemanticNode;
import sc.parser.IString;
import sc.util.StringUtil;

import java.util.List;

public class QualifiedIdentifier extends SQLIdentifier {
   public List<IString> identifiers;

   public String getIdentifier() {
      return StringUtil.arrayToType(identifiers.toArray());
   }

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid) {
         return toString();
      }
      return super.toSafeLanguageString();
   }
}
