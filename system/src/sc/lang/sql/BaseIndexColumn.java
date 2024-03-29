/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.SemanticNode;

public abstract class BaseIndexColumn extends SemanticNode {
   public Collation collation;
   public String opClass;
   public String sortDir;
   public String nullDir;
}
