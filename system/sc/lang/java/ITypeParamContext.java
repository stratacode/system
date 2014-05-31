/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public interface ITypeParamContext {
   /** Returns the value of the type specified for that position in the type parameters list - may be an unresolved type variable */
   public Object getType(int position);

   /** Returns the value of the type specified for that position in the type parameters list.  Returns the default type for any type variables. */
   public Object getDefaultType(int position);
}
