/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.obj.Constant;

public interface IVariable extends ITypedObject {
   @Constant
   public String getVariableName();
}
