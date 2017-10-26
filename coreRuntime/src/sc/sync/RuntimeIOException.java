/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

/**
 * Thrown when a sync fails because of an IOException (typically connection closed)
 */
public class RuntimeIOException extends RuntimeException {
   public RuntimeIOException(String message) {
      super(message);
   }
}
