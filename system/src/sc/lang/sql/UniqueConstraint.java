package sc.lang.sql;

import sc.lang.SemanticNode;
import sc.parser.IString;

import java.util.List;

public class UniqueConstraint extends SQLConstraint {
   public List<SQLIdentifier> columnList; // at the table level
   public IndexParameters indexParams;
}
