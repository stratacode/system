/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

public interface IReverseMethodMapper {
   Object invokeReverseMethod(Object obj, Object value, Object... params);

   /** Do we propagate the reverse value through the 0 based slot index specified */
   boolean propagateReverse(int slot);
}
