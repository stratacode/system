/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.dyn.IDynChildManager;

import java.util.ArrayList;

public class LayerDynChildManager implements IDynChildManager {
   public void initChildren(Object parentObj, Object[] newChildren) {
      Layer parent = (Layer) parentObj;
      if (parent.children == null) {
         parent.children = new ArrayList();
      }
      for (Object childObj:newChildren) {
         parent.children.add(childObj);
      }
   }

   public void addChild(Object parentObj, Object childObj) {
      addChild(-1, parentObj, childObj);
   }

   public void addChild(int ix, Object parentObj, Object childObj) {
      Layer parent = (Layer) parentObj;
      if (ix == -1)
         parent.children.add(childObj);
      else
         parent.children.add(ix, childObj);
   }

   public boolean removeChild(Object parentObj, Object childObj) {
      Layer parent = (Layer) parentObj;
      if (parent.children == null)
         return false;
      return parent.children.remove(childObj);
   }

   public Object[] getChildren(Object parentObj) {
      Layer parent = (Layer) parentObj;
      return parent.children == null ? null : parent.children.toArray();
   }
}
