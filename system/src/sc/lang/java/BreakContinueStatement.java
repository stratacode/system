/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.Set;

public class BreakContinueStatement extends Statement {
   public String operator;
   public String labelName;

   public ExecResult exec(ExecutionContext ctx) {
      ctx.currentLabel = labelName;
      return operator.charAt(0) == 'c' ? ExecResult.Continue : ExecResult.Break;
   }

   public void refreshBoundTypes(int flags) {
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
   }

   public void setAccessTimeForRefs(long time) {
   }

   public Statement transformToJS() { return this; }

   public String toString() {
      if (operator == null)
         return "<null break/continue>";
      else {
         if (labelName == null)
            return operator;
         else
            return operator + " " + labelName;
      }
   }
}
