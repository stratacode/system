package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.Set;

public abstract class AlterDef extends SQLDefinition {
   public void addTableReferences(Set<String> refTableNames) {
   }
}
