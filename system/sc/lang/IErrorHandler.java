/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

public interface IErrorHandler {
   void reportError(String error);
   void reportWarning(String warning);
}
