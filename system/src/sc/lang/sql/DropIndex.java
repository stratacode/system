/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.util.StringUtil;

import java.util.List;
import java.util.Set;

public class DropIndex extends SQLCommand {
   public List<SQLIdentifier> indexNames;
   public boolean concurrently;
   public boolean ifExists;
   public String dropOptions;

   void addTableReferences(Set<String> refTableNames) {
   }

   public boolean hasReferenceTo(SQLCommand cmd) {
      return false;
   }

   public String getIdentifier() {
      return null;
   }

   public String toDeclarationString() {
      return "drop index " + StringUtil.argsToString(indexNames);
   }
}
