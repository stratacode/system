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
   Model, UI, Style, Application, Persist, Framework, Admin, Deploy;

   public static EnumSet<CodeType> allSet = EnumSet.allOf(CodeType.class);

   public static EnumSet<CodeType> nonFrameworkSet = EnumSet.of(CodeType.Model, CodeType.UI, CodeType.Style, CodeType.Application, CodeType.Admin, CodeType.Style, CodeType.Persist, CodeType.Deploy);
}
