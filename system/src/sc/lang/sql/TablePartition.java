/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.List;

public class TablePartition extends SemanticNode {
   public String partitionBy;
   public List<SQLExpression> expressionList;
}
