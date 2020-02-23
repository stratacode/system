package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.Set;

public abstract class TableDef extends SemanticNode {
   public abstract void addTableReferences(Set<String> refTableNames);
}
