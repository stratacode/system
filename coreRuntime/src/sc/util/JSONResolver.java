/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

public interface JSONResolver {
   public Object resolveRef(String refName, Object propertyType);

   public Object resolveClass(String className);
}
