/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public enum InitStatementsMode {
   PreInit, Init, Static;

   public boolean doStatic() {
      return this == Static;
   }
}
