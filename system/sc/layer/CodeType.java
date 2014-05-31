/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import java.util.EnumSet;

public enum CodeType {
   Declarative, Application, Framework;

   public static EnumSet<CodeType> allSet = EnumSet.allOf(CodeType.class);
}
