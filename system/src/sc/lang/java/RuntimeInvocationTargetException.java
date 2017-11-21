/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

/** Thrown when we need to throw a checked exception in a dynamic context */
public class RuntimeInvocationTargetException extends RuntimeException {
   public Throwable wrappedException;
   public RuntimeInvocationTargetException(Throwable throwable) {
      wrappedException = throwable;
   }
}
