/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public interface ICustomResolver {
   public Object resolveType(String currentPackage, String name, boolean create, TypeDeclaration fromType);
   public String getResolveMethodName();
   public Object resolveObject(String currentPackage, String name, boolean create, boolean unwrap);
   public String getRegisterInstName();
   public String getResolveOrCreateInstName();
   /** At runtime, once all of the source has been compiled, it's expensive to load source definitions for things.  A custom resolver can use the compiled description unless the source is already loaded by returning true here. */
   public boolean useRuntimeResolution();
}
