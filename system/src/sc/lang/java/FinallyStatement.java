/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;
import java.util.Set;

public class FinallyStatement extends NonIndentedStatement implements IBlockStatementWrapper {
   public BlockStatement block;
   public transient int frameSize;

   public void init() {
      if (initialized) return;
      super.init();

      frameSize = block == null ? 0 : ModelUtil.computeFrameSize(block.statements);
   }

   public int getNestingDepth() {
      if (parentNode != null)
         return parentNode.getChildNestingDepth() - 1;
      return 0;
   }

   public void refreshBoundTypes(int flags) {
      if (block != null && block.statements != null)
         for (Statement st:block.statements)
            st.refreshBoundTypes(flags);
   }

   public void addChildBodyStatements(List<Object> sts) {
      if (block != null && block.statements != null)
         for (Statement st:block.statements)
            st.addChildBodyStatements(sts);
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (block != null && block.statements != null)
         for (Statement st:block.statements)
            st.addDependentTypes(types, mode);
   }

   public void setAccessTimeForRefs(long time) {
      if (block != null && block.statements != null)
         for (Statement st:block.statements)
            st.setAccessTimeForRefs(time);
   }

   public Statement transformToJS() {
      if (block != null && block.statements != null)
         for (Statement st:block.statements)
            st.transformToJS();
      return this;
   }

   public ExecResult exec(ExecutionContext ctx) {
      ctx.pushFrame(false, frameSize);
      try {
         return ModelUtil.execStatements(ctx, block.statements);
      }
      finally {
         ctx.popFrame();
      }
   }

   public boolean childIsTopLevelStatement(Statement child) {
      return true;
   }

   public void addReturnStatements(List<Statement> res, boolean incThrow) {
      if (block != null && block.statements != null)
         for (Statement st:block.statements)
            st.addReturnStatements(res, incThrow);
   }

   public Statement findStatement(Statement in) {
      if (block != null && block.statements != null) {
         for (Statement st:block.statements) {
            Statement out = st.findStatement(in);
            if (out != null)
               return out;
         }
      }
      return null;
   }

   @Override
   public BlockStatement getWrappedBlockStatement() {
      return block;
   }
}
