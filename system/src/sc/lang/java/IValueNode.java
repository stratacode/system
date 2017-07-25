/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public interface IValueNode {
   Object eval(Class expectedType, ExecutionContext ctx);

   Object getPrimitiveValue();

}
