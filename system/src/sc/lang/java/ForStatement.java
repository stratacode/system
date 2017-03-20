/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISrcStatement;

import java.util.List;
import java.util.Set;

public class ForStatement extends Statement implements IStatementWrapper {
   public Statement statement;

   public boolean spaceAfterParen() {
      return true;
   }

   public void refreshBoundTypes(int flags) {
      if (statement != null)
         statement.refreshBoundTypes(flags);
   }

   public void addChildBodyStatements(List<Object> res) {
      if (statement != null)
         statement.addChildBodyStatements(res);
   }

   public void addDependentTypes(Set<Object> types) {
      if (statement != null)
         statement.addDependentTypes(types);
   }

   public Statement transformToJS() {
      if (statement != null)
         statement.transformToJS();
      return this;
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      if (statement != null)
         ix = statement.transformTemplate(ix, statefulContext);
      return ix;
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement toFind) {
      super.addBreakpointNodes(res, toFind);
      if (statement != null)
         statement.addBreakpointNodes(res, toFind);
   }

   public boolean updateFromStatementRef(Statement fromSt, ISrcStatement defaultSt) {
      boolean res = super.updateFromStatementRef(null, defaultSt);
      if (statement != null)
         return statement.updateFromStatementRef(fromSt, defaultSt);
      return res;
   }

   public boolean childIsTopLevelStatement(Statement child) {
      return child == statement;
   }

   public void addReturnStatements(List<Statement> res, boolean incThrow) {
      if (statement != null) statement.addReturnStatements(res, incThrow);
   }

   public Statement findStatement(Statement in) {
      if (statement != null) {
         Statement out = statement.findStatement(in);
         if (out != null)
            return out;
      }
      return super.findStatement(in);
   }

   public boolean isLeafStatement() {
      return false;
   }

   public Statement getWrappedStatement() {
      return statement;
   }

   @Override
   public String getFunctionEndString() {
      return ")";
   }
}
