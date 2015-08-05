/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public enum InitStatementsMode {
   PreInit, Init, Static;

   public boolean doStatic() {
      return this == Static;
   }
}
