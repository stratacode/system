/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

public interface INameContext {
   public Object resolveName(String name, boolean create, boolean returnTypes);
}
