/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;


import sc.js.JSSettings;

/** Utility class to wrap an object for insertion into a HashMap or whatever to hide its equals/hashCode and instead do comparisons on the wrappers system identity. */
@JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public class IdentityWrapper<T> {
   public T wrapped;

   public IdentityWrapper(T wrapped) {
      this.wrapped = wrapped;
   }

   public int hashCode() {
      return wrapped == null ? 0 : System.identityHashCode(wrapped);
   }

   public boolean equals(Object other) {
      if (other instanceof IdentityWrapper)
         return ((IdentityWrapper) other).wrapped == wrapped;
      return false;
   }
   public String toString() {
      return "id(" + (wrapped == null ? "null" : wrapped.toString()) + ")";
   }

}
