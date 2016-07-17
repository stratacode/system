/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.layer.LayeredSystem;

import java.util.List;

public class ArrayCloneMethod extends WrapperMethod {
   Object arrayType;
   ArrayCloneMethod(Object arrayType, Object wrapped, LayeredSystem sys, JavaModel model) {
      this.wrapped = wrapped;
      this.arrayType = arrayType;
      this.system = sys;
      this.model = model;
   }

   public Object getTypeDeclaration(List<? extends ITypedObject> args, boolean resolve) {
      return arrayType;
   }

   /** Array clone does not throw any checked exceptions */
   public Object[] getExceptionTypes() {
      return null;
   }

}
