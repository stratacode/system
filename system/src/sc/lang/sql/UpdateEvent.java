package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.List;

public class UpdateEvent extends SemanticNode {
   public List<SQLIdentifier> columns;
}
