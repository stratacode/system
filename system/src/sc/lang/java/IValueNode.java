/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public interface IValueNode {
   Object eval(Class expectedType, ExecutionContext ctx);

   Object getPrimitiveValue();

}
