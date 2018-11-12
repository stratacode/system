/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.lang.SemanticNodeList;

public class TagDynChildManager implements sc.dyn.IDynChildManager {
   public boolean getInitChildrenOnCreate() {
      return true;
   }

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
      // TODO: need to call invalidateBody here?
   }

   public boolean removeChild(Object parentObj, Object childObj) {
      Element parent = (Element) parentObj;
      boolean res = false;
      if (parent.children != null)
         res = parent.children.remove(childObj);
      // It's possible we are notified of a child tag object being removed - we need to refresh in that case.
      // One case is where we have a child object that's request scope where the parent is session scoped.   Then child
      // is removed after each request, forcing the parent to refresh it's body and get a new instance of the child.
      parent.invalidateBody();
      return res;
   }

   public Object[] getChildren(Object parentObj) {
      Element parent = (Element) parentObj;
      return parent.children == null ? null : parent.children.toArray();
   }
}
