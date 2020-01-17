package sc.lang.sql;

import sc.lang.SemanticNode;

public class ReferencesConstraint extends SQLConstraint {
   public String refTable;
   public String columnRef;
   public String matchOption;
   public String onOptions;
}
