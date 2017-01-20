/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.dyn.DynUtil;
import sc.obj.IObjectId;

@sc.obj.Sync(onDemand=true)
public class InstanceWrapper implements IObjectId {
   EditorContext ctx;
   public Object theInstance;
   boolean canCreate = false;
   public String typeName;
   public InstanceWrapper(EditorContext ctx, boolean canCreate, String typeName) {
      this.typeName = typeName;
      this.canCreate = canCreate;
      this.ctx = ctx;
   }

   public InstanceWrapper(EditorContext ctx, Object inst) {
      this.theInstance = inst;
      this.ctx = ctx;
   }

   public Object getInstance() {
      if (theInstance != null)
         return theInstance;

      if (canCreate)
         return theInstance = DynUtil.resolveName(typeName, true);

      return null;
   }

   public String toString() {
      if (theInstance == null)
         return typeName != null ? (canCreate ? "<select to create>" : "<restart to create>") : "<type>";
      return DynUtil.getInstanceName(theInstance);
   }

   public boolean equals(Object other) {
      if (other instanceof InstanceWrapper) {
         InstanceWrapper otherInst = (InstanceWrapper) other;
         if (!DynUtil.equalObjects(typeName, otherInst.typeName))
            return false;
         return DynUtil.equalObjects(otherInst.theInstance, theInstance);
      }
      return false;
   }

   public int hashCode() {
      if (theInstance == null) return 0;
      return theInstance.hashCode();
   }

   public String getObjectId() {
      // We have to put the type name in the object id.  It has to do with name scoping through the sync system since this
      // instance turns into a static field of the type it makes sense that the type name should be on there.
      String base = "sc.lang.InstanceWrapper";
      if (typeName == null)
         return base + "__nullWrapper";
      else if (theInstance == null)
         return base + "__type_" + typeName.replace(".", "_");
      return base + "__" + DynUtil.getObjectId(theInstance, null, typeName);
   }
}
