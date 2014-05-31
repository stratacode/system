/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import java.util.EnumSet;

public enum CodeFunction {
   Program, Style, UI, Business, Admin;

   public static EnumSet<CodeFunction> allSet = EnumSet.allOf(CodeFunction.class);
}
