/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.Set;

public abstract class TypedDefinition extends Statement {
   public JavaType type;

   public abstract boolean isProperty();

   public boolean refreshBoundTypes(int flags) {
      if (type != null) {
         if (type.refreshBoundType(flags))
            return true;
      }
      return false;
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      if (type != null)
         ix = type.transformTemplate(ix, statefulContext);
      return ix;
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (type != null)
         type.addDependentTypes(types, mode);
   }

   public void setAccessTimeForRefs(long time) {
      if (type != null)
         type.setAccessTimeForRefs(time);
   }

   public Statement transformToJS() { return this; }
}
