/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;
import java.util.Set;

public class ForStatement extends Statement {
   public Statement statement;

   public boolean spaceAfterParen() {
      return true;
   }

   public void refreshBoundTypes() {
      if (statement != null)
         statement.refreshBoundTypes();
   }

   public void addChildBodyStatements(List<Object> res) {
      if (statement != null)
         statement.addChildBodyStatements(res);
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

   public int transformTemplate(int ix, boolean statefulContext) {
      if (statement != null)
         ix = statement.transformTemplate(ix, statefulContext);
      return ix;
   }

   public Statement findFromStatement(Statement toFind) {
      Statement res = super.findFromStatement(toFind);
      if (res != null)
         return res;
      if (statement != null)
         res = statement.findFromStatement(toFind);
      return res;
   }
}
