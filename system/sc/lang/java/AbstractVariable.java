/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.INamedNode;
import sc.lang.IUserDataNode;
import sc.lang.js.JSUtil;

public class AbstractVariable extends JavaSemanticNode implements INamedNode, IUserDataNode {
   public String variableName;
   public String arrayDimensions;

   private transient Object userData;

   public String getVariableName() {
      return variableName;
   }

   public void transformToJS() {
      if (JSUtil.jsKeywords.contains(variableName))
         setProperty("variableName", "_" + variableName);
   }

   public void setNodeName(String newName) {
      setProperty("variableName", newName);
   }

   public String getNodeName() {
      return variableName;
   }

   public void setUserData(Object v)  {
      userData = v;
   }

   public Object getUserData() {
      return userData;
   }

   public String toListDisplayString() {
      return variableName;
   }
}
