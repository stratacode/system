/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISrcStatement;

import java.util.List;
import java.util.Set;

public class IfStatement extends NonIndentedStatement {
   public ParenExpression expression;
   public Statement trueStatement, falseStatement;

   //public boolean suppressIndent;

   public void init() {
      super.init();
   }

   public void start() {
      super.start();

      if (expression != null)
         expression.setInferredType(Boolean.class);
   }

   public ExecResult exec(ExecutionContext ctx) {
      if ((Boolean) expression.eval(Boolean.TYPE, ctx)) {
         return trueStatement.exec(ctx);
      }
      else if (falseStatement != null)
         return falseStatement.exec(ctx);
      return ExecResult.Next;
   }

   public boolean spaceAfterParen() {
      return true;
   }

   public void refreshBoundTypes(int flags) {
      if (expression != null)
         expression.refreshBoundTypes(flags);
      if (trueStatement != null)
         trueStatement.refreshBoundTypes(flags);
      if (falseStatement != null)
         falseStatement.refreshBoundTypes(flags);
   }

   public void addChildBodyStatements(List<Object> sts) {
      if (trueStatement != null)
         trueStatement.addChildBodyStatements(sts);
      if (falseStatement != null)
         falseStatement.addChildBodyStatements(sts);
   }

   public void addDependentTypes(Set<Object> types) {
      if (expression != null)
         expression.addDependentTypes(types);
      if (trueStatement != null)
         trueStatement.addDependentTypes(types);
      if (falseStatement != null)
         falseStatement.addDependentTypes(types);
   }

   public Statement transformToJS() {
      if (expression != null)
         expression.transformToJS();
      if (trueStatement != null)
         trueStatement.transformToJS();
      if (falseStatement != null)
         falseStatement.transformToJS();
      return this;
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      if (expression != null)
         ix = expression.transformTemplate(ix, statefulContext);
      if (trueStatement != null)
         ix = trueStatement.transformTemplate(ix, statefulContext);
      if (falseStatement != null)
         ix = falseStatement.transformTemplate(ix, statefulContext);
      return ix;
   }

   public boolean applyPartialValue(Object value) {
      if (value == expression || value == trueStatement || value == falseStatement)
         return true;
      return false;
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement toFind) {
      super.addBreakpointNodes(res, toFind);
      if (trueStatement != null) {
         trueStatement.addBreakpointNodes(res, toFind);
      }
      if (falseStatement != null) {
         falseStatement.addBreakpointNodes(res, toFind);
      }
   }

   public boolean updateFromStatementRef(Statement fromSt, ISrcStatement defaultSt) {
      super.updateFromStatementRef(null, defaultSt);
      if (trueStatement != null && trueStatement.updateFromStatementRef(fromSt, defaultSt))
         return true;
      if (falseStatement != null && falseStatement.updateFromStatementRef(fromSt, defaultSt))
         return true;
      return false;
   }

   public boolean childIsTopLevelStatement(Statement child) {
      return child == trueStatement || child == falseStatement;
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("if (");
      if (expression != null)
         sb.append(expression);
      sb.append(")");
      if (trueStatement != null)
         sb.append(trueStatement);
      if (falseStatement != null) {
         sb.append(" else ");
         sb.append(falseStatement);
      }
      return sb.toString();
   }

   public void addReturnStatements(List<Statement> res) {
      if (trueStatement != null) {
         trueStatement.addReturnStatements(res);
      }
      if (falseStatement != null) {
         falseStatement.addReturnStatements(res);
      }
   }
}
