/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISrcStatement;
import sc.parser.ParseUtil;

import java.util.List;
import java.util.Set;

/** Do/While statement */
public class WhileStatement extends ExpressionStatement implements IStatementWrapper {
   public Statement statement;

   public ExecResult exec(ExecutionContext ctx) {
      // Do statement
      if (operator.charAt(0) == 'd') {
         do {
            ExecResult res = statement.exec(ctx);
            switch (res) {
               case Return:
                  return ExecResult.Return;
               case Break:
                  if (ctx.currentLabel == null || isLabeled(ctx.currentLabel))
                     return ExecResult.Next;
                  return ExecResult.Break;
               case Continue:
                  if (ctx.currentLabel == null || isLabeled(ctx.currentLabel))
                     continue;
                  return ExecResult.Continue;
               // Next: fall through and do next
            }
         } while ((Boolean) expression.eval(Boolean.TYPE, ctx));
      }
      else {
         while ((Boolean) expression.eval(Boolean.TYPE, ctx)) {
            ExecResult res = statement.exec(ctx);
            switch (res) {
               case Return:
                  return ExecResult.Return;
               case Break:
                  if (ctx.currentLabel == null || isLabeled(ctx.currentLabel))
                     return ExecResult.Next;
                  return ExecResult.Break;
               case Continue:
                  if (ctx.currentLabel == null || isLabeled(ctx.currentLabel))
                     continue;
                  return ExecResult.Continue;
               // Next: fall through
            }
         }
      }
      return ExecResult.Next;
   }

   public boolean spaceAfterParen() {
      return true;
   }

   public void refreshBoundTypes(int flags) {
      if (statement != null)
         statement.refreshBoundTypes(flags);
   }

   public void addChildBodyStatements(List<Object> sts) {
      if (statement != null)
         statement.addChildBodyStatements(sts);
   }

   public void addDependentTypes(Set<Object> types) {
      if (statement != null)
         statement.addDependentTypes(types);
   }

   public Statement transformToJS() {
      super.transformToJS();
      if (statement != null)
         statement.transformToJS();

      return this;
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

   public boolean isLeafStatement() {
      return false;
   }

   public Statement getWrappedStatement() {
      return statement;
   }

   public String getFunctionEndString() {
      return ")";
   }

   /** For the while-statement, our breakpoints really are set on the while (expression) part */
   public int getNumStatementLines() {
      if (expression != null)
         return ParseUtil.countLinesInNode(expression.getParseNode());
      return super.getNumStatementLines();
   }
}
