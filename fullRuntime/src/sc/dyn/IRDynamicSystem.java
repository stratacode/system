/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

public interface IRDynamicSystem extends IDynamicSystem {
   void setSystemClassLoader(ClassLoader loader);
}
