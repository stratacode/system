/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

public interface IDynObjManager {

   /**
    * For the given type object (either a Class or a TypeDeclaration), the enclosing instance (if any),
    * and constructor args (which will already include the parent instance if the constructed class is an inner class),
    * constructs a component of the appropriate type 
    */
   Object createInstance(Object type, Object parentInst, Object[] args);
}
