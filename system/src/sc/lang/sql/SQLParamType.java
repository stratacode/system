/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.java.JavaSemanticNode;

public abstract class SQLParamType extends JavaSemanticNode {
   public abstract String getIdentifier();
}
