/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public abstract class NonIndentedStatement extends Statement {
   public int getChildNestingDepth() {
      if (childNestingDepth != -1)
         return childNestingDepth;
      if (parentNode != null)
         return parentNode.getChildNestingDepth();
      return 0;
   }

   public boolean isLeafStatement() {
      return false;
   }
}
