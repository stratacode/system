/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISrcStatement;

import java.util.List;
import java.util.Set;

public class LabelStatement extends Statement {
   public String labelName;
   public Statement statement;

   public void refreshBoundTypes() {
      if (statement != null)
         statement.refreshBoundTypes();
   }

   public void addDependentTypes(Set<Object> types) {
      if (statement != null)
         statement.addDependentTypes(types);
   }

   public Statement transformToJS() {
      if (statement != null)
         statement.transformToJS();
      return this;
   }

   public void addGeneratedFromNodes(List<ISrcStatement> res, ISrcStatement srcStatement) {
      super.addGeneratedFromNodes(res, srcStatement);
      if (statement != null)
         statement.addGeneratedFromNodes(res, srcStatement);
   }
}
