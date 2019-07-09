/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISrcStatement;

import java.util.List;
import java.util.Set;

public class LabelStatement extends Statement implements IStatementWrapper {
   public String labelName;
   public Statement statement;

   public void refreshBoundTypes(int flags) {
      if (statement != null)
         statement.refreshBoundTypes(flags);
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (statement != null)
         statement.addDependentTypes(types, mode);
   }

   public void setAccessTimeForRefs(long time) {
      if (statement != null)
         statement.setAccessTimeForRefs(time);
   }

   public Statement transformToJS() {
      if (statement != null)
         statement.transformToJS();
      return this;
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement srcStatement) {
      super.addBreakpointNodes(res, srcStatement);
      if (statement != null)
         statement.addBreakpointNodes(res, srcStatement);
   }

   public void addReturnStatements(List<Statement> res, boolean incThrow) {
      if (statement != null) {
         statement.addReturnStatements(res, incThrow);
      }
   }

   @Override
   public Statement getWrappedStatement() {
      return statement;
   }

   @Override
   public String getFunctionEndString() {
      return ":";
   }
}
