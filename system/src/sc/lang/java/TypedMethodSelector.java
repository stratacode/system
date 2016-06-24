/*
 * Copyright (c) 2016. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;

/** Used for a.b().<TypeParam>methodCall() */
public class TypedMethodSelector extends VariableSelector {
   public List<JavaType> typeArguments;

   public List<JavaType> getMethodTypeArguments() {
      return typeArguments;
   }
}
