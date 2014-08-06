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

   public void initialize()
   {
      super.initialize();
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

   public void refreshBoundTypes() {
      if (expression != null)
         expression.refreshBoundTypes();
      if (trueStatement != null)
         trueStatement.refreshBoundTypes();
      if (falseStatement != null)
         falseStatement.refreshBoundTypes();
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

   public void addGeneratedFromNodes(List<ISrcStatement> res, ISrcStatement toFind) {
      if (toFind == this)
         res.add(this);
      if (trueStatement != null) {
         trueStatement.addGeneratedFromNodes(res, toFind);
      }
      if (falseStatement != null) {
         falseStatement.addGeneratedFromNodes(res, toFind);
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
}
