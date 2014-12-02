/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.SemanticNodeList;

import java.util.EnumSet;

/** This is used for the old-style for (init: condition: repeat) type of for statement */
public class ForControlStatement extends ForStatement {
   public SemanticNodeList<Definition> forInit;
   public Expression condition;
   public SemanticNodeList<Expression> repeat;

   public Object findMember(String name, EnumSet<MemberType> mtype, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      Object res;
      if (mtype.contains(MemberType.Variable) && forInit != null)
         for (Definition d:forInit)
            if ((res = d.definesMember(name, mtype, refType, ctx, skipIfaces, false)) != null)
               return res;

      return super.findMember(name, mtype, this, refType, ctx, skipIfaces);
   }

   public ExecResult exec(ExecutionContext ctx) {
      ctx.pushFrame(false, 1);
      try {
         if (forInit != null) {
            for (int i = 0; i < forInit.size(); i++) {
               Definition d = forInit.get(i);
               if (d instanceof VariableStatement) {
                  ((VariableStatement) d).exec(ctx);
               }
               else if (d instanceof Expression) {
                  ((Expression) d).exec(ctx);
               }
            }
         }
         while ((Boolean) condition.eval(Boolean.TYPE, ctx)) {
            switch (statement.exec(ctx)) {
               case Return:
                  return ExecResult.Return;
               case Break:
                  if (ctx.currentLabel == null || isLabeled(ctx.currentLabel))
                     return ExecResult.Next;
                  return ExecResult.Break;
               case Continue:
                  if (ctx.currentLabel == null || isLabeled(ctx.currentLabel)) {
                     if (repeat != null) {
                        for (int i = 0; i < repeat.size(); i++)
                           repeat.get(i).exec(ctx);
                     }
                     continue;
                  }
                  return ExecResult.Continue;
               // Fall through - next
            }
            if (repeat != null) {
               for (int i = 0; i < repeat.size(); i++)
                  repeat.get(i).exec(ctx);
            }
         }
      }
      finally {
         ctx.popFrame();
      }
      return ExecResult.Next;
   }

   public void refreshBoundTypes() {
      super.refreshBoundTypes();
      if (forInit != null) {
         for (Definition d:forInit) {
            if (d instanceof VariableStatement)
               ((VariableStatement) d).refreshBoundTypes();
            else if (d instanceof Expression)
               ((Expression) d).refreshBoundTypes();
         }
      }
      if (repeat != null) {
         for (Statement st:repeat)
            st.refreshBoundTypes();
      }
      if (condition != null) {
         condition.refreshBoundTypes();
      }
   }

   public Statement transformToJS() {
      super.transformToJS();
      if (forInit != null) {
         for (Definition d:forInit) {
            if (d instanceof VariableStatement)
               ((VariableStatement) d).transformToJS();
            else if (d instanceof Expression)
               ((Expression) d).transformToJS();
         }
      }
      if (repeat != null) {
         for (Statement st:repeat)
            st.transformToJS();
      }
      if (condition != null) {
         condition.transformToJS();
      }
      return this;
   }
}
