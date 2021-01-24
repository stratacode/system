/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.SemanticNode;

public class WithOperand extends SemanticNode {
   public SQLIdentifier identifier;
   public String operand;
}
