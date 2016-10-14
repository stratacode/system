/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;
import java.util.Set;

public class SynchronizedStatement extends Statement {
   public Expression expression;
   public Statement statement;

   public ExecResult exec(ExecutionContext ctx) {
      Object val = expression.eval(null, ctx);
      synchronized (val) {
         return statement.exec(ctx);
      }
   }

   public void refreshBoundTypes(int flags) {
      if (expression != null)
         expression.refreshBoundTypes(flags);
      if (statement != null)
         statement.refreshBoundTypes(flags);
   }

   public void addChildBodyStatements(List<Object> sts) {
      if (expression != null)
         expression.addChildBodyStatements(sts);
   }

   public void addDependentTypes(Set<Object> types) {
      if (expression != null)
         expression.addDependentTypes(types);
      if (statement != null)
         statement.addDependentTypes(types);
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
}
