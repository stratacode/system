/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISrcStatement;

import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class SwitchStatement extends Statement implements IBlockStatement {
   public Expression expression;
   public List<Statement> statements;

   public transient Object expressionType;
   public transient boolean isEnum;

   public void start() {
      if (started)
         return;

      if (expression != null) {
         Object exprType = expression.getTypeDeclaration();
         if (exprType != null)
            expressionType = exprType;
         if (expressionType != null)
            isEnum = ModelUtil.isEnumType(expressionType);
      }
      super.start();
   }

   public int getChildNestingDepth()
   {
      // Switch statement increases the indent 2 levels for children
      if (parentNode != null)
         return parentNode.getChildNestingDepth() + 2;
      return 0;
   }

   public ExecResult exec(ExecutionContext ctx) {
      Object value = expression.eval(null, ctx);
      if (value == null) {
         value = expression.eval(null, ctx);
      }

      int defaultIndex = -1;

      // This loop looks for a matching case statement
      for (int i = 0; i < statements.size(); i++) {
         Statement statement = statements.get(i);

         // Skip non-labels at this level
         if (statement instanceof SwitchLabel) {
            SwitchLabel label = (SwitchLabel) statement;

            // Found the default statement
            if (label.operator.charAt(0) == 'd') { // Default
               defaultIndex = i;
            }
            else {
               Object labelValue = label.expression.eval(value.getClass(), ctx);

               if (value.equals(labelValue)) {
                  for (; i < statements.size(); i++) {
                     statement = statements.get(i);
                     if (!(statement instanceof SwitchLabel)) {
                        ExecResult res = statement.exec(ctx);
                        if (res != ExecResult.Next) {
                           if (res == ExecResult.Break)
                              return ExecResult.Next;
                           else
                              return res;
                        }
                     }
                  }
                  return ExecResult.Next;
               }
            }
         }
      }

      if (defaultIndex != -1) {
         for (int i = defaultIndex; i < statements.size(); i++) {
            Statement statement = statements.get(i);
            if (!(statement instanceof SwitchLabel)) {
               ExecResult res = statement.exec(ctx);
               if (res != ExecResult.Next) {
                  if (res == ExecResult.Break)
                     return ExecResult.Next;
                  else
                     return res;
               }
            }
         }
      }
      return ExecResult.Next;
   }

   /**
    * For enum types only if the child is looking up a name, we need to check
    * if the switch expression defines that label.
    *
    * Switch statements also define variables so we need to look them up too.
    */
   public Object findMember(String name, EnumSet<MemberType> mtype, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      Object v;

      // Resolving the expression should not check for exposed variables in the switch itself
      if (fromChild != expression) {
         if (isEnum && mtype.contains(MemberType.Enum) && isFromSwitchLabel(fromChild)) {
            v = ModelUtil.definesMember(expressionType, name, MemberType.EnumOnlySet, refType, ctx, skipIfaces, false);
            if (v != null)
               return v;
         }

         // Propagate only variable searches
         if (mtype.contains(MemberType.Variable) && statements != null) {
            EnumSet<MemberType> subMtype;
            if (mtype.size() != 1)
               subMtype = EnumSet.of(MemberType.Variable);
            else
               subMtype = mtype;
            for (Statement s:statements)
               if ((v = s.definesMember(name, subMtype, refType, ctx, skipIfaces, false)) != null)
                  return v;
         }
      }
      return super.findMember(name, mtype, this, refType, ctx, skipIfaces);
   }

   private boolean isFromSwitchLabel(Object fromChild) {
      SwitchLabel theLabel;
      if (fromChild instanceof SwitchLabel)
         theLabel = (SwitchLabel) fromChild;
      else if (fromChild instanceof Expression && ((Expression) fromChild).parentNode instanceof SwitchLabel)
         theLabel = (SwitchLabel) ((Expression) fromChild).parentNode;
      else
         return false;
      return theLabel.parentNode != null && theLabel.parentNode.getParentNode() == this;
   }

   public Object findMemberOwner(String name, EnumSet<MemberType> mtype) {
      if (definesMember(name, mtype, null, null, false, false) != null)
         return expressionType;

      return super.findMemberOwner(name, mtype);
   }

   public void refreshBoundTypes(int flags) {
      if (expression != null)
         expression.refreshBoundTypes(flags);
      if (statements != null)
         for (Statement st:statements)
            st.refreshBoundTypes(flags);
   }

   public void addChildBodyStatements(List<Object> sts) {
      if (statements != null)
         for (Statement st:statements)
            st.addChildBodyStatements(sts);
   }

   public void addDependentTypes(Set<Object> types) {
      if (expression != null)
         expression.addDependentTypes(types);
      if (statements != null)
         for (Statement st:statements)
            st.addDependentTypes(types);
   }

   public Statement transformToJS() {
      if (expression != null) {
         ParenExpression parExpr = (ParenExpression) expression;
         Expression switchExpr = parExpr.expression;
         if (isEnum) {
            SelectorExpression se = SelectorExpression.create(switchExpr, VariableSelector.create("_ordinal", null));
            parExpr.setProperty("expression", se);
         }
         expression.transformToJS();
      }
      if (statements != null)
         for (Statement st:statements)
            st.transformToJS();
      return this;
   }

   public SwitchStatement deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      SwitchStatement newSt = (SwitchStatement) super.deepCopy(options, oldNewMap);
      if ((options & CopyState) != 0) {
         // Copying these over by default since we can't reliably restart transformed models - mid transform- and need this info intact
         // to the transformToJS function.
         newSt.isEnum = isEnum;
         newSt.expressionType = expressionType;
      }
      return newSt;
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement srcStatement) {
      super.addBreakpointNodes(res, srcStatement);
      if (expression != null)
         expression.addBreakpointNodes(res, srcStatement);
      if (statements != null) {
         for (Statement statement:statements) {
            if (statement != null)
               statement.addBreakpointNodes(res, srcStatement);
         }
      }
   }

   public List<Statement> getBlockStatements() {
      return statements;
   }

   public void addReturnStatements(List<Statement> res, boolean incThrow) {
      if (statements != null) {
         for (Statement statement:statements)
            statement.addReturnStatements(res, incThrow);
      }
   }
}
