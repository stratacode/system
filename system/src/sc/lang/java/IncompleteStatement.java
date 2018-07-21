/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.Set;

public class IncompleteStatement extends Statement {
   public ClassType type;

   @Override
   public void refreshBoundTypes(int flags) {
   }

   @Override
   public void addDependentTypes(Set<Object> types) {
   }

   @Override
   public Statement transformToJS() {
      return null;
   }

   public void init() {
      super.init();
      displayError("Incomplete statement: ");
   }

   public String getNodeErrorText() {
      String res = type == null ? null : type.getNodeErrorText();
      if (res != null)
         return res;
      return super.getNodeErrorText();
   }

   public boolean getNotFoundError() {
      return type != null && type.getNotFoundError();
   }

   public String addNodeCompletions(JavaModel origModel, JavaSemanticNode origNode, String matchPrefix, int offset, String dummyIdentifier, Set<String> candidates, boolean nextNameInPath) {
      if (type != null)
         return type.addNodeCompletions(origModel, origNode, matchPrefix, offset, dummyIdentifier, candidates, nextNameInPath);
      return matchPrefix;
   }

   public String toString() {
      return type == null ? "???" : type.toString() + "...";
   }
}
