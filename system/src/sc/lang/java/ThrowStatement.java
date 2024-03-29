/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;

public class ThrowStatement extends ExpressionStatement {
   
   public ExecResult eval(ExecutionContext ctx) {
      // Is this ever called?
      return exec(ctx);
   }

   public ExecResult exec(ExecutionContext ctx) {
      Object val = expression.eval(Throwable.class, ctx);
      if (val instanceof RuntimeException)
         throw (RuntimeException) val;
      else
         throw new RuntimeInvocationTargetException((Throwable) val);
   }

   public static ThrowStatement create(Expression ex) {
      ThrowStatement st = new ThrowStatement();
      st.setProperty("operator", "throw");
      st.setProperty("expression", ex);
      return st;
   }

   public void addReturnStatements(List<Statement> res, boolean includeThrow) {
      if (includeThrow)
         res.add(this);
   }
}
