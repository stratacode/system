/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;
import java.util.Set;

public class SynchronizedStatement extends Statement implements IStatementWrapper {
   public Expression expression;
   public Statement statement;

   public ExecResult exec(ExecutionContext ctx) {
      Object val = expression.eval(null, ctx);
      synchronized (val) {
         return statement.execSys(ctx);
      }
   }

   public boolean refreshBoundTypes(int flags) {
      boolean res = false;
      if (expression != null)
         res = expression.refreshBoundTypes(flags);
      if (statement != null)
         if (statement.refreshBoundTypes(flags))
            res = true;
      return res;
   }

   public void addChildBodyStatements(List<Object> sts) {
      if (expression != null)
         expression.addChildBodyStatements(sts);
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (expression != null)
         expression.addDependentTypes(types, mode);
      if (statement != null)
         statement.addDependentTypes(types, mode);
   }

   public void setAccessTimeForRefs(long time) {
      if (expression != null)
         expression.setAccessTimeForRefs(time);
      if (statement != null)
         statement.setAccessTimeForRefs(time);
   }

   public Statement transformToJS() {
      if (expression != null)
         expression.transformToJS();
      if (statement != null)
         statement.transformToJS();

      if (statement instanceof BlockStatement) {
         parentNode.replaceChild(this, statement);
         return statement;
      }
      else {
         BlockStatement bs = new BlockStatement();
         bs.addStatementAt(0, statement);
         parentNode.replaceChild(this, bs);
         return bs;
      }
   }

   public void addReturnStatements(List<Statement> res, boolean incThrow) {
      if (statement != null)
         statement.addReturnStatements(res, incThrow);
   }

   public Statement findStatement(Statement in) {
      if (statement != null) {
         Statement out = statement.findStatement(in);
         if (out != null)
            return out;
      }
      return super.findStatement(in);
   }

   public Statement getWrappedStatement() {
      return statement;
   }

   public boolean isLeafStatement() {
      return false;
   }

   public String getFunctionEndString() {
      return ")";
   }
}
