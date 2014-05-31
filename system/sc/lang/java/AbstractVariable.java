/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.js.JSUtil;

public class AbstractVariable extends JavaSemanticNode {
   public String variableName;
   public String arrayDimensions;

   public String getVariableName() {
      return variableName;
   }

   public void transformToJS() {
      if (JSUtil.jsKeywords.contains(variableName))
         setProperty("variableName", "_" + variableName);
   }
}
