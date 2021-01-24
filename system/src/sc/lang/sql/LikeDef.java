/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.Set;

public class LikeDef extends TableDef {
   public String sourceTable;
   public String likeOptions;

   public void addTableReferences(Set<String> refTableNames) {
   }

   public boolean hasReferenceTo(SQLCommand cmd) {
      return false;
   }
}
