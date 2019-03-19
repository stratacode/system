/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.dyn.IDynObject;

// TODO: use this approach to keep IDynObject from generating too many stub methods. (or remove this?)
public interface IDynSupport {
   Object getPropertyFromWrapper(IDynObject origObj, String propName, boolean getField);

   Object getPropertyFromWrapper(IDynObject origObj, int propIndex, boolean getField);

   void setPropertyFromWrapper(Object origObj, String propName, Object value, boolean setField);

   Object getDynType();
}
