/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.Set;

public abstract class SQLDefinition extends SemanticNode {
   public abstract void addTableReferences(Set<String> refTableNames);
}
