/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.layer.Layer;
import sc.layer.LayeredSystem;

public interface ITypeParamContext {
   /** This returns the value of the type specified for that position in the type's parameters list - may be an unresolved type variable.  NOTE: does not include method type params */
   public Object getType(int position);

   /** Returns the value of the type specified for that position in the type parameters list.  Returns the default type for any type variables. */
   public Object getDefaultType(int position);

   public Object getTypeForVariable(Object typeVar, boolean resolve);

   public Object getTypeDeclarationForParam(String tvarName, Object tvar, boolean resolve);

   public LayeredSystem getLayeredSystem();

   public Layer getRefLayer();

   public Object getDefinedInType();
}
