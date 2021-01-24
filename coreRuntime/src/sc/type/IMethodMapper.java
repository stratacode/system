/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

public interface IMethodMapper {
   Object invoke(Object thisObj, Object...params);
}
