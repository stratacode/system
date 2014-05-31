/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.Set;

public abstract class TypedDefinition extends Statement {
   public JavaType type;

   public abstract boolean isProperty();

   public void refreshBoundTypes() {
      if (type != null)
         type.refreshBoundType();
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      if (type != null)
         ix = type.transformTemplate(ix, statefulContext);
      return ix;
   }

   public void addDependentTypes(Set<Object> types) {
      if (type != null)
         type.addDependentTypes(types);
   }

   public Statement transformToJS() { return this; }
}
