package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.Set;

public abstract class SQLConstraint extends SemanticNode {

   public void addTableReferences(Set<String> refTableNames) {}
}
