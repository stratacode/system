/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.Set;

public class SQLBinaryOperand extends SemanticNode {
   public String operator;
   public SQLExpression rhs;


   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid) {
         return operator + " " + (rhs == null ? "null" : rhs.toSafeLanguageString());
      }
      return super.toSafeLanguageString();
   }
}
