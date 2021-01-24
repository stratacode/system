/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.Set;

public abstract class SQLConstraint extends SemanticNode {

   public void addTableReferences(Set<String> refTableNames) {}

   public boolean hasReferenceTo(SQLCommand cmd) {
      return false;
   }
}
