/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISrcStatement;
import sc.lang.SemanticNodeList;
import sc.parser.GenFileLineIndex;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class TryStatement extends Statement implements IBlockStatement {
   public BlockStatement block; // TODO: should this just be a BlockStatement?  Wouldn't that make the code easier to manage so Try is not such a special case?
   public List<CatchStatement> catchStatements;
   // Java 7 auto-close resources
   public SemanticNodeList<VariableStatement> resources;
   public FinallyStatement finallyStatement;

   public transient int frameSize;

   public void init() {
      if (initialized) return;
      super.init();

      frameSize = block == null ? 0 : ModelUtil.computeFrameSize(block.statements);
   }

   public ExecResult exec(ExecutionContext ctx) {
      boolean popped = false;
      ExecResult res = ExecResult.Next;
      try {
         ctx.pushFrame(false, frameSize);

         if (block != null)
            res = ModelUtil.execStatements(ctx, block.statements);
      }
      catch (Throwable th) {
         // This frame should not be visible while executing any catches
         ctx.popFrame();
         popped = true;

         if (catchStatements != null) {
            for (CatchStatement st : catchStatements) {
               Object catchType = st.getCaughtTypeDeclaration();
               if (ModelUtil.isInstance(catchType, th)) {
                  return st.invokeCatch(Collections.singletonList(th), ctx);
               }
            }
         }
      }
      finally {
         if (!popped) ctx.popFrame();

         if (finallyStatement != null)
            finallyStatement.execSys(ctx);
      }
      return res;
   }

   public boolean refreshBoundTypes(int flags) {
      boolean res = false;
      if (block != null && block.statements != null)
         for (Statement st : block.statements) {
            if (st.refreshBoundTypes(flags))
               res = true;
         }
      if (catchStatements != null)
         for (CatchStatement cs : catchStatements) {
            if (cs.refreshBoundTypes(flags))
               res = true;
         }
      if (finallyStatement != null) {
         if (finallyStatement.refreshBoundTypes(flags))
            res = true;
      }
      return res;
   }

   public void addChildBodyStatements(List<Object> sts) {
      if (block != null && block.statements != null)
         for (Statement st : block.statements)
            st.addChildBodyStatements(sts);
      if (catchStatements != null)
         for (Statement st : catchStatements)
            st.addChildBodyStatements(sts);
      if (finallyStatement != null)
         finallyStatement.addChildBodyStatements(sts);
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (block != null && block.statements != null)
         for (Statement st : block.statements)
            st.addDependentTypes(types, mode);
      if (catchStatements != null)
         for (CatchStatement cs : catchStatements)
            cs.addDependentTypes(types, mode);
      if (finallyStatement != null)
         finallyStatement.addDependentTypes(types, mode);
   }

   public void setAccessTimeForRefs(long time) {
      if (block != null && block.statements != null)
         for (Statement st : block.statements)
            st.setAccessTimeForRefs(time);
      if (catchStatements != null)
         for (CatchStatement cs : catchStatements)
            cs.setAccessTimeForRefs(time);
      if (finallyStatement != null)
         finallyStatement.setAccessTimeForRefs(time);
   }

   public Statement transformToJS() {
      if (block != null && block.statements != null)
         for (Statement st : block.statements)
            st.transformToJS();
      if (catchStatements != null) {
         // JS only has a single 'catch' statement
         int numCatches = catchStatements.size();
         CatchStatement mainCst = catchStatements.get(0);
         IfStatement outerIf = new IfStatement();
         String mainParamName = mainCst.parameters.variableName;
         InstanceOfExpression iof = InstanceOfExpression.create(IdentifierExpression.create(mainParamName), (JavaType) mainCst.parameters.type.deepCopy(CopyNormal, null));
         outerIf.setProperty("expression", ParenExpression.create(iof));
         outerIf.setProperty("trueStatement", mainCst.statements);
         outerIf.setProperty("falseStatement", ThrowStatement.create(IdentifierExpression.create(mainParamName)));
         IfStatement prevIf = outerIf;
         for (int i = 1; i < numCatches; i++) {
            IfStatement nextIf = new IfStatement();
            CatchStatement nextCatch = catchStatements.get(i);

            nextIf.setProperty("expression", ParenExpression.create(InstanceOfExpression.create(IdentifierExpression.create(mainParamName), (JavaType) nextCatch.parameters.type.deepCopy(CopyNormal, null))));
            String nextParamName = nextCatch.parameters.variableName;
            // Define the variable used by the next catch unless they happen to use the same name.
            if (!mainParamName.equals(nextParamName)) {
               nextCatch.statements.addStatementAt(0, VariableStatement.create(ClassType.createStarted(Object.class, "var"),
                       nextParamName, "=", IdentifierExpression.create(mainParamName)));
            }
            nextIf.setProperty("trueStatement", nextCatch.statements);
            prevIf.setProperty("falseStatement", nextIf);
            prevIf = nextIf;
         }
         BlockStatement bs = new BlockStatement();
         bs.addStatementAt(0, outerIf);
         mainCst.setProperty("statements", bs);

         // Now remove the all but the first catch statement
         for (int i = numCatches - 1; i > 0; i--) {
            catchStatements.remove(i);
         }

         for (CatchStatement cs : catchStatements)
            cs.transformToJS();
      }
      if (finallyStatement != null)
         finallyStatement.transformToJS();
      return this;
   }

   public List<Statement> getBlockStatements() {
      return block.statements;
   }

   public Object definesMember(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      if (mtype.contains(MemberType.Variable) && resources != null) {
         for (VariableStatement v : resources) {
            Object res = v.definesMember(name, mtype, refType, ctx, skipIfaces, isTransformed);
            if (res != null)
               return res;
         }
         return super.definesMember(name, mtype, refType, ctx, skipIfaces, isTransformed);
      }
      return super.definesMember(name, mtype, refType, ctx, skipIfaces, isTransformed);
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement toFind) {
      super.addBreakpointNodes(res, toFind);
      AbstractBlockStatement.addBlockGeneratedFromNodes(this, res, toFind);
      if (catchStatements != null) {
         for (Statement st : catchStatements) {
            st.addBreakpointNodes(res, toFind);
         }
      }
      if (finallyStatement != null) {
         finallyStatement.addBreakpointNodes(res, toFind);
      }
   }

   public boolean childIsTopLevelStatement(Statement child) {
      return true;
   }

   public void addReturnStatements(List<Statement> res, boolean incThrow) {
      if (block != null && block.statements != null) {
         for (Statement statement : block.statements)
            statement.addReturnStatements(res, incThrow);
      }
      if (catchStatements != null) {
         for (Statement st : catchStatements) {
            st.addReturnStatements(res, incThrow);
         }
      }
      if (finallyStatement != null)
         finallyStatement.addReturnStatements(res, incThrow);
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("try ");
      if (block != null && block.statements != null) {
         sb.append(block.statements.toString());
      }
      if (catchStatements != null) {
         sb.append(catchStatements.toString());
      }
      if (finallyStatement != null) {
         sb.append(finallyStatement.toString());
      }
      return sb.toString();
   }

   public Statement findStatement(Statement in) {
      if (block != null && block.statements != null) {
         for (Statement st : block.statements) {
            Statement out = st.findStatement(in);
            if (out != null)
               return out;
         }
      }
      if (catchStatements != null) {
         for (Statement st : catchStatements) {
            Statement out = st.findStatement(in);
            if (out != null)
               return out;
         }
      }
      if (finallyStatement != null) {
         Statement out = finallyStatement.findStatement(in);
         if (out != null)
            return out;
      }
      return super.findStatement(in);
   }

   public boolean isLeafStatement() {
      return false;
   }

   public String getStartBlockString() {
      return "{";
   }

   public String getStartBlockToken() {
      return "try";
   }

   public String getEndBlockString() {
      return "}";
   }

   public void addToFileLineIndex(GenFileLineIndex idx, int startGenLine) {
      super.addToFileLineIndex(idx, startGenLine);
      if (catchStatements != null) {
         for (CatchStatement catchSt:catchStatements)
            catchSt.addToFileLineIndex(idx, startGenLine);
      }
      if (finallyStatement != null) {
         finallyStatement.addToFileLineIndex(idx, startGenLine);
      }
   }
}
