/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.dyn.DynUtil;
import sc.obj.IObjectId;
import sc.sync.SyncManager;

@sc.obj.Sync(onDemand=true)
public class InstanceWrapper {
   EditorContext ctx;
   public Object theInstance;
   boolean canCreate = false;
   public String typeName;
   public InstanceWrapper(EditorContext ctx, boolean canCreate, String typeName) {
      this.typeName = typeName;
      this.canCreate = canCreate;
      this.ctx = ctx;
      SyncManager.addSyncInst(this, true, true, null, ctx, canCreate, typeName);
   }

   public InstanceWrapper(EditorContext ctx, Object inst) {
      this.theInstance = inst;
      this.ctx = ctx;
      SyncManager.addSyncInst(this, true, true, null, ctx, inst);
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

}
