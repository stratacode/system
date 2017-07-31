/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

/**
 * Used to get delayed event notifications when instances have been created or destroyed
 */
public interface IDynListener {
   void instanceAdded(Object inst);
   void instanceRemoved(Object inst);
}
