/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class TryStatement extends Statement implements IBlockStatement {
   public List<Statement> statements;
   public List<CatchStatement> catchStatements;
   public FinallyStatement finallyStatement;

   public transient int frameSize;

   public void initialize() {
      if (initialized) return;
      super.initialize();

      frameSize = ModelUtil.computeFrameSize(statements);
   }

   public ExecResult exec(ExecutionContext ctx) {
      boolean popped = false;
      ExecResult res = ExecResult.Next;
      try {
         ctx.pushFrame(false, frameSize);

         res = ModelUtil.execStatements(ctx, statements);
      }
      catch (Throwable th) {
         // This frame should not be visible while executing any catches
         ctx.popFrame();
         popped = true;

         if (catchStatements != null) {
            for (CatchStatement st:catchStatements) {
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
            finallyStatement.exec(ctx);
      }
      return res;
   }

   public void refreshBoundTypes() {
      if (statements != null)
         for (Statement st:statements)
            st.refreshBoundTypes();
      if (catchStatements != null)
         for (CatchStatement cs:catchStatements)
            cs.refreshBoundTypes();
      if (finallyStatement != null)
         finallyStatement.refreshBoundTypes();
   }

   public void addChildBodyStatements(List<Object> sts) {
      if (statements != null)
         for (Statement st:statements)
            st.addChildBodyStatements(sts);
      if (catchStatements != null)
         for (Statement st:catchStatements)
            st.addChildBodyStatements(sts);
      if (finallyStatement != null)
         finallyStatement.addChildBodyStatements(sts);
   }

   public void addDependentTypes(Set<Object> types) {
      if (statements != null)
         for (Statement st:statements)
            st.addDependentTypes(types);
      if (catchStatements != null)
         for (CatchStatement cs:catchStatements)
            cs.addDependentTypes(types);
      if (finallyStatement != null)
         finallyStatement.addDependentTypes(types);
   }

   public Statement transformToJS() {
      if (statements != null)
         for (Statement st:statements)
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

         for (CatchStatement cs:catchStatements)
            cs.transformToJS();
      }
      if (finallyStatement != null)
         finallyStatement.transformToJS();
      return this;
   }

   public List<Statement> getBlockStatements() {
      return statements;
   }

}
