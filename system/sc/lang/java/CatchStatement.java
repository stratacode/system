/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class CatchStatement extends NonIndentedStatement {
   public Parameter parameters;
   public BlockStatement statements;

   public void init() {
      if (initialized) return;
      super.init();

      if (parameters.getNumParameters() != 1)
         System.err.println("*** Incorrect number of parameters to catch statement: " + toDefinitionString());
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

   public void refreshBoundTypes() {
      if (parameters != null)
         parameters.refreshBoundType();
      if (statements != null)
         statements.refreshBoundTypes();
   }

   public void addChildBodyStatements(List<Object> sts) {
      if (statements != null)
         statements.addChildBodyStatements(sts);
   }

   public void addDependentTypes(Set<Object> types) {
      if (parameters != null)
         parameters.addDependentTypes(types);
      if (statements != null)
         statements.addDependentTypes(types);
   }

   public Statement transformToJS() {
      if (statements != null)
         statements.transformToJS();
      return this;
   }
}
