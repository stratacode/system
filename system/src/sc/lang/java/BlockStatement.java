/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.IUserDataNode;
import sc.lang.js.JSFormatMode;
import sc.lang.js.JSTypeParameters;
import sc.parser.ParseUtil;
import sc.util.StringUtil;

import java.util.IdentityHashMap;

public class BlockStatement extends AbstractBlockStatement {
   // For a reverse binding, we convert the initializer into a block statement and use this
   // hook so we can get back to the variableDefinition we were created from in transformBinding.
   public transient JavaSemanticNode fromDefinition;

   public CharSequence formatToJS(JSFormatMode mode, JSTypeParameters params, int extraLines) {
      StringBuilder res = new StringBuilder();
      res.append("{\n");
      if (statements != null) {
         for (Statement st:statements) {
            res.append(StringUtil.indent(getNestingDepth()+1));
            res.append(st.formatToJS(mode, params, extraLines + ParseUtil.countLinesInNode(res)));
         }
      }
      res.append(StringUtil.indent(getNestingDepth()));
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

   public String getStartBlockString() {
      return (staticEnabled ? "static " : "") + "{";
   }

   public String getStartBlockToken() {
      return staticEnabled ? "static" : "{";
   }

   public String getEndBlockString() {
      return "}";
   }

   // Look for the parent to decide whether to add a space after a left paren. 
   public boolean formatLeftParenDelegateToParent() {
      return true;
   }
}
