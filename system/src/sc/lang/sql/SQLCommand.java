package sc.lang.sql;

import sc.lang.java.JavaSemanticNode;

public abstract class SQLCommand extends JavaSemanticNode {
   public int getChildNestingDepth() {
      return 1;
   }
}
