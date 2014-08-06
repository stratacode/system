/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.IErrorHandler;

public class ErrorHandler implements IErrorHandler {
   public String err;
   public boolean isWarning;
   public void reportError(String error) {
      err = error;
   }
   public void reportWarning(String error) {
      isWarning = true;
      err = error;
   }
}
