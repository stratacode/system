package sc.lang.sql;

import java.util.Set;

public class TableConstraint extends TableDef {
   public NamedConstraint namedConstraint;
   public SQLConstraint constraint;

   @Override
   public void addTableReferences(Set<String> refTableNames) {
      if (constraint != null)
         constraint.addTableReferences(refTableNames);
   }
}
