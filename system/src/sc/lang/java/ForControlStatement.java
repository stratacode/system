/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.SemanticNodeList;
import sc.util.StringUtil;

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
                  ((VariableStatement) d).execSys(ctx);
               }
               else if (d instanceof Expression) {
                  ((Expression) d).execSys(ctx);
               }
            }
         }
         while ((Boolean) condition.eval(Boolean.TYPE, ctx)) {
            switch (statement.execSys(ctx)) {
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
                           repeat.get(i).execSys(ctx);
                     }
                     continue;
                  }
                  return ExecResult.Continue;
               // Fall through - next
            }
            if (repeat != null) {
               for (int i = 0; i < repeat.size(); i++)
                  repeat.get(i).execSys(ctx);
            }
         }
      }
      finally {
         ctx.popFrame();
      }
      return ExecResult.Next;
   }

   public boolean refreshBoundTypes(int flags) {
      boolean res = super.refreshBoundTypes(flags);
      if (forInit != null) {
         for (Definition d:forInit) {
            if (d instanceof VariableStatement) {
               if (((VariableStatement) d).refreshBoundTypes(flags))
                  res = true;
            }
            else if (d instanceof Expression) {
               if (((Expression) d).refreshBoundTypes(flags))
                  res = true;
            }
         }
      }
      if (repeat != null) {
         for (Statement st:repeat) {
            if (st.refreshBoundTypes(flags))
               res = true;
         }
      }
      if (condition != null) {
         if (condition.refreshBoundTypes(flags))
            res = true;
      }
      return res;
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

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("for (");
      if (forInit != null)
         sb.append(StringUtil.arrayToString(forInit.toArray()));
      else
         sb.append("null");
      sb.append("; ");
      sb.append(condition);
      sb.append("; ");
      if (repeat != null) {
         boolean first = true;
         for (Object robj:repeat) {
            if (!first)
               sb.append(", ");
            sb.append(robj);
            first = false;
         }
      }
      sb.append(") ");

      if (statement != null)
         sb.append(statement);

      return sb.toString();
   }

   public Statement findStatement(Statement in) {
      if (forInit != null) {
         for (Definition def:forInit) {
            if (def instanceof Statement) {
               Statement out = ((Statement) def).findStatement(in);
               if (out != null)
                  return out;
            }
         }
      }
      return super.findStatement(in);
   }
}
