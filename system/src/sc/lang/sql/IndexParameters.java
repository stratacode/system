/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.SemanticNode;
import sc.parser.IString;

import java.util.List;

public class IndexParameters extends SemanticNode {
   public List<IString> paramList;
}
