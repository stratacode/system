/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class CatchStatement extends NonIndentedStatement implements IBlockStatementWrapper {
   public Parameter parameters;
   public BlockStatement statements;

   public void init() {
      if (initialized) return;
      super.init();

      if (parameters == null || parameters.getNumParameters() != 1)
         displayError("Incorrect number of parameters to catch statement: ");
   }

   public Object getCaughtTypeDeclaration() {
      return parameters.getTypeDeclaration();
   }

   public Object findMember(String name, EnumSet<MemberType> mtype, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      Object v;
      if (mtype.contains(MemberType.Variable)) {
         EnumSet<MemberType> subtype;
         if (mtype.size() != 1)
            subtype = EnumSet.of(MemberType.Variable);
         else
            subtype = mtype;
         if (parameters != null)
            for (Parameter p:parameters.getParameterList())
               if ((v = p.definesMember(name, subtype, refType, ctx, skipIfaces, false)) != null)
                  return v;
      }
      return super.findMember(name, mtype, this, refType, ctx, skipIfaces);
   }

   public ExecResult invokeCatch(List<? extends Object> paramValues, ExecutionContext ctx) {
      ctx.pushFrame(false, statements.frameSize, paramValues, parameters, getEnclosingType());

      try {
         return ModelUtil.execStatements(ctx, statements.statements);

      }
      finally {
         ctx.popFrame();
      }
   }

   public boolean refreshBoundTypes(int flags) {
      boolean res = false;
      if (parameters != null)
         res = parameters.refreshBoundType(flags);
      if (statements != null)
         if (statements.refreshBoundTypes(flags))
            res = true;
      return res;
   }

   public void addChildBodyStatements(List<Object> sts) {
      if (statements != null)
         statements.addChildBodyStatements(sts);
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (parameters != null)
         parameters.addDependentTypes(types, mode);
      if (statements != null)
         statements.addDependentTypes(types, mode);
   }

   public void setAccessTimeForRefs(long time) {
      if (parameters != null)
         parameters.setAccessTimeForRefs(time);
      if (statements != null)
         statements.setAccessTimeForRefs(time);
   }

   public Statement transformToJS() {
      if (statements != null)
         statements.transformToJS();
      return this;
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("catch (");
      if (parameters != null)
         sb.append(parameters);
      sb.append(") ");
      if (statements != null)
         sb.append(statements);
      return sb.toString();
   }

   public void addReturnStatements(List<Statement> res, boolean incThrow) {
      if (statements != null)
         statements.addReturnStatements(res, incThrow);
   }

   @Override
   public BlockStatement getWrappedBlockStatement() {
      return statements;
   }

   public Statement findStatement(Statement in) {
      Statement res = super.findStatement(in);
      if (res != null)
         return res;
      if (statements != null) {
         Statement out = statements.findStatement(in);
         if (out != null)
            return out;
      }
      return null;
   }
}
