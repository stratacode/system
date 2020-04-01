package sc.lang.sql;

import sc.lang.java.JavaSemanticNode;

import java.util.Set;

public abstract class SQLCommand extends JavaSemanticNode {
   public int getChildNestingDepth() {
      return 1;
   }

   abstract void addTableReferences(Set<String> refTableNames);

   public SQLCommand getDropCommand() {
      return null;
   }
}
