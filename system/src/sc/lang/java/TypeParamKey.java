/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

/** Used in cases where we need to store a type variable as a key in a hash table */
public class TypeParamKey {
   Object typeVar;

   public TypeParamKey(Object tv) {
      typeVar = tv;
   }

   public boolean equals(Object other) {
      if (!(other instanceof TypeParamKey))
         return false;
      return ModelUtil.sameTypeParameters(typeVar, ((TypeParamKey) other).typeVar);
   }

   public int hashCode() {
      return ModelUtil.getTypeParameterName(typeVar).hashCode();
   }

   public String toString() {
      return typeVar == null ? "<null>" : typeVar.toString();
   }
}
