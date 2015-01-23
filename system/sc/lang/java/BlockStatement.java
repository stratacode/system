/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.IUserDataNode;
import sc.lang.js.JSFormatMode;

import java.util.IdentityHashMap;

public class BlockStatement extends AbstractBlockStatement {
   // For a reverse binding, we convert the initializer into a block statement and use this
   // hook so we can get back to the variableDefinition we were created from in transformBinding.
   public transient JavaSemanticNode fromDefinition;

   public CharSequence formatToJS(JSFormatMode mode) {
      StringBuilder res = new StringBuilder();
      res.append("{\n");
      if (statements != null) {
         for (Statement st:statements)
            res.append(st.formatToJS(mode));
      }
      res.append("}\n");
      return res;
   }

   public BlockStatement deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      BlockStatement res = (BlockStatement) super.deepCopy(options, oldNewMap);

      if ((options & CopyInitLevels) != 0) {
         res.fromDefinition = fromDefinition;
      }
      return res;
   }

   public boolean isTrailingSrcStatement() {
      return true;
   }

   public boolean needsEnclosingClass() {
      return true;
   }
}
