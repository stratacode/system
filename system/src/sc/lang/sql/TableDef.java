/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.Set;

/** TODO: rename to TableBodyDef */
public abstract class TableDef extends SQLDefinition {
   public abstract boolean hasReferenceTo(SQLCommand cmd);
}
