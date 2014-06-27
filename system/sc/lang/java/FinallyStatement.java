/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;
import java.util.Set;

public class FinallyStatement extends NonIndentedStatement implements IBlockStatement {
   public List<Statement> statements;
   public transient int frameSize;

   public void initialize() {
      if (initialized) return;
      super.initialize();

      frameSize = ModelUtil.computeFrameSize(statements);
   }

   public int getNestingDepth() {
      if (parentNode != null)
         return parentNode.getChildNestingDepth() - 1;
      return 0;
   }

   public void refreshBoundTypes() {
      if (statements != null)
         for (Statement st:statements)
            st.refreshBoundTypes();
   }

   public void addChildBodyStatements(List<Object> sts) {
      if (statements != null)
         for (Statement st:statements)
            st.addChildBodyStatements(sts);
   }

   public void addDependentTypes(Set<Object> types) {
      if (statements != null)
         for (Statement st:statements)
            st.addDependentTypes(types);
   }

   public Statement transformToJS() {
      if (statements != null)
         for (Statement st:statements)
            st.transformToJS();
      return this;
   }

   public ExecResult exec(ExecutionContext ctx) {
      ctx.pushFrame(false, frameSize);
      try {
         return ModelUtil.execStatements(ctx, statements);
      }
      finally {
         ctx.popFrame();
      }
   }

   public List<Statement> getBlockStatements() {
      return statements;
   }
}
