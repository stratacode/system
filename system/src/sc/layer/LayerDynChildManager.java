/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.dyn.DynUtil;
import sc.dyn.IDynChildManager;
import sc.dyn.IDynObjManager;

import java.util.ArrayList;

public class LayerDynChildManager implements IDynChildManager, IDynObjManager {
   /**
    * We need to create the layer instances without initializing the children objects.  That way, we can create
    * all subsequent layers first, have their modifications merged into the object types before we create the objects.
    * Otherwise, you'd need to use data binding for every simple property override.
    */
   @Override
   public boolean getInitChildrenOnCreate() {
      return false;
   }

   // NOTE: because the above is false, we don't hit this case,  Instead, we initialize the children explicitly
   // in Layer.initialize but if we were to set the initChildrenOnCreate = true, then this would initialize the children.
   // In that case, the children are created as part of the constructor for the instance but we want all layer instances
   // to be created first, then we create the children components to allow better overriding.
   public void initChildren(Object parentObj, Object[] newChildren) {
      Layer parent = (Layer) parentObj;
      if (parent.children == null) {
         parent.children = new ArrayList();
      }
      for (Object childObj : newChildren) {
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

   @Override
   public Object createInstance(Object type, Object parentInst, Object[] args) {
      if (parentInst instanceof Layer) {
         int len = (args == null ? 0 : args.length);
         Object[] newArgs = new Object[len + 1];
         if (args != null)
            System.arraycopy(args, 0, newArgs, 0, args.length);
         newArgs[len] = parentInst;
         args = newArgs;
      }
      if (parentInst != null)
         return DynUtil.createInnerInstance(type, parentInst, null, args);
      else
         return DynUtil.createInstance(type, null, args);
   }
}

