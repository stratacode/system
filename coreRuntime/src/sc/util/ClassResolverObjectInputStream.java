/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;

public class ClassResolverObjectInputStream extends ObjectInputStream {
   IClassResolver resolver;

   public ClassResolverObjectInputStream(InputStream in, IClassResolver resolver) throws IOException {
      super(in);
      this.resolver = resolver;
   }

   protected Class<?> resolveClass(ObjectStreamClass desc)
           throws IOException, ClassNotFoundException
   {
      String name = desc.getName();
      Class res = resolver == null ? null : resolver.getCompiledClass(name);
      if (res != null)
         return res;
      return super.resolveClass(desc);
   }
}
