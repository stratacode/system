/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import java.util.EnumSet;

public enum CodeFunction {
   Model, UI, Style, Program, Admin;

   public static EnumSet<CodeFunction> allSet = EnumSet.allOf(CodeFunction.class);
}

