/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.lang.SemanticNodeList;

public class TagDynChildManager implements sc.dyn.IDynChildManager {
   public void initChildren(Object parentObj, Object[] newChildren) {
      Element parent = (Element) parentObj;
      if (parent.children == null) {
         parent.children = new SemanticNodeList();
         parent.children.setParentNode(parent);
      }
      for (Object childObj:newChildren) {
         parent.children.add(childObj);
      }
   }

   public void addChild(Object parentObj, Object childObj) {
      addChild(-1, parentObj, childObj);
   }

   public void addChild(int ix, Object parentObj, Object childObj) {
      Element parent = (Element) parentObj;
      if (ix == -1)
         parent.children.add(childObj);
      else
         parent.children.add(ix, childObj);
   }

   public boolean removeChild(Object parentObj, Object childObj) {
      Element parent = (Element) parentObj;
      if (parent.children == null)
         return false;
      return parent.children.remove(childObj);
   }

   public Object[] getChildren(Object parentObj) {
      Element parent = (Element) parentObj;
      return parent.children == null ? null : parent.children.toArray();
   }
}
