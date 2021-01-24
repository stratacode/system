/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.util.StringUtil;

import java.util.List;
import java.util.Set;

public class DropType extends SQLCommand {
   public List<SQLIdentifier> typeNames;
   public String dropOptions;

   void addTableReferences(Set<String> refTableNames) {
      for (SQLIdentifier nm:typeNames)
         refTableNames.add(nm.getIdentifier());
   }

   public boolean hasReferenceTo(SQLCommand cmd) {
      return false;
   }

   public String toDeclarationString() {
      return "drop type " + StringUtil.argsToString(typeNames);
   }

   public String getIdentifier() {
      return null;
   }
}
