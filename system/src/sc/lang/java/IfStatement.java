/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ILanguageModel;
import sc.lang.ISrcStatement;
import sc.layer.LayeredSystem;
import sc.parser.GenFileLineIndex;
import sc.parser.IParseNode;
import sc.parser.ParseUtil;

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
         expression.setInferredType(Boolean.class, true);
   }

   public void validate() {
      super.validate();
      if (expression != null) {
         Object type = expression.getTypeDeclaration();
         if (type != null && expression != null && !ModelUtil.isAssignableFrom(Boolean.class, type))
            expression.displayError("Type: " + ModelUtil.getTypeName(type) + " not a boolean for if expression: ");
      }
   }

   public ExecResult exec(ExecutionContext ctx) {
      if ((Boolean) expression.eval(Boolean.TYPE, ctx)) {
         return trueStatement.execSys(ctx);
      }
      else if (falseStatement != null)
         return falseStatement.execSys(ctx);
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

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (expression != null)
         expression.addDependentTypes(types, mode);
      if (trueStatement != null)
         trueStatement.addDependentTypes(types, mode);
      if (falseStatement != null)
         falseStatement.addDependentTypes(types, mode);
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

   public void addReturnStatements(List<Statement> res, boolean incThrow) {
      if (trueStatement != null) {
         trueStatement.addReturnStatements(res, incThrow);
      }
      if (falseStatement != null) {
         falseStatement.addReturnStatements(res, incThrow);
      }
   }

   public Statement findStatement(Statement in) {
      if (trueStatement != null) {
         Statement out = trueStatement.findStatement(in);
         if (out != null)
            return out;
      }
      if (falseStatement != null) {
         Statement out = falseStatement.findStatement(in);
         if (out != null)
            return out;
      }
      return super.findStatement(in);
   }

   public boolean isLeafStatement() {
      return false;
   }

   /** Return true for a statement where there's no falseStatement yet but there is an 'else' that was parsed as part of the partial value */
   public boolean isIncompleteElse() {
      IParseNode ifPN = getParseNode();
      if (falseStatement == null && trueStatement != null && ifPN != null) {
         IParseNode truePN = trueStatement.getParseNode();
         int trueOffset = (truePN.getStartIndex() - ifPN.getStartIndex()) + truePN.toString().trim().length();
         if (ifPN.indexOf("else", trueOffset) != -1)
            return true;
      }
      return false;
   }

   /** For the if-statement, our breakpoints really are set on the if (expression) part only */
   public int getNumStatementLines() {
      if (expression != null) {
         IParseNode pn = expression.getParseNode();
         if (pn == null)
            return 0;
         return ParseUtil.countLinesInNode(pn);
      }
      return super.getNumStatementLines();
   }

   public boolean isLineStatement() {
      return true;
   }

   public void addToFileLineIndex(GenFileLineIndex idx, int startGenLine) {
      super.addToFileLineIndex(idx, startGenLine);
      if (trueStatement != null)
         trueStatement.addToFileLineIndex(idx, startGenLine);
      if (falseStatement != null)
         falseStatement.addToFileLineIndex(idx, startGenLine);
   }

   public boolean isIncompleteStatement() {
      return false;
   }

   /**
    * First see if the expression part must be run here, then look for a setting on the true and false statements.
    */
   public RuntimeStatus execForRuntime(LayeredSystem sys) {
      RuntimeStatus exprStat = expression.execForRuntime(sys);
      if (exprStat != RuntimeStatus.Unset)
         return exprStat;
      RuntimeStatus trueStat = trueStatement.execForRuntime(sys);
      if (trueStat != RuntimeStatus.Unset)
         return trueStat;
      if (falseStatement != null)
         return falseStatement.execForRuntime(sys);
      return RuntimeStatus.Unset;
   }

}
