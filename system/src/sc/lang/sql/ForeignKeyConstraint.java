package sc.lang.sql;

import sc.lang.SemanticNode;
import sc.parser.IString;

import java.util.List;

public class ForeignKeyConstraint extends SQLConstraint {
   public List<SQLIdentifier> columnList;
   public SQLIdentifier refTable;
   public List<SQLIdentifier> refColList;
   public String matchOption;
   public String onOptions;
}
