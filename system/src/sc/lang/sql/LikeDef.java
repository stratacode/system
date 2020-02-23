package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.Set;

public class LikeDef extends TableDef {
   public String sourceTable;
   public String likeOptions;

   public void addTableReferences(Set<String> refTableNames) {
   }
}
