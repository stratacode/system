/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import java.util.Set;

public class TableConstraint extends TableDef {
   public NamedConstraint namedConstraint;
   public SQLConstraint constraint;

   public void addTableReferences(Set<String> refTableNames) {
      if (constraint != null)
         constraint.addTableReferences(refTableNames);
   }

   public boolean hasReferenceTo(SQLCommand cmd) {
      if (constraint != null)
         return constraint.hasReferenceTo(cmd);
      return false;
   }
}
