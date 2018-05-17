/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import java.util.EnumSet;

/**
 * A way to categorize code in a layer by it's complexity.  Declarative is used to target configuration or rules, application
 * layers that contain normal application code, and framework for those layers that are implementing interfaces used by application
 */
public enum CodeType {
   Declarative, Application, Framework;

   public static EnumSet<CodeType> allSet = EnumSet.allOf(CodeType.class);
}
