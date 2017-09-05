/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.dyn.DynUtil;
import sc.obj.IObjectId;
import sc.obj.Sync;
import sc.sync.SyncManager;
import sc.type.CTypeUtil;
import sc.obj.SyncMode;


@Sync(onDemand=true)
public class InstanceWrapper implements IObjectId {
   @Sync(syncMode=SyncMode.Disabled)
   EditorContext ctx;
   @Sync(syncMode=SyncMode.Disabled)
   public Object theInstance;
   @Sync(syncMode=SyncMode.Disabled)
   boolean canCreate = false;
   @Sync(syncMode=SyncMode.Disabled)
   public String typeName;
   /*
   public InstanceWrapper(EditorContext ctx, boolean canCreate, String typeName) {
      this.typeName = typeName;
      this.canCreate = canCreate;
      this.ctx = ctx;
      SyncManager.addSyncInst(this, true, true, null, ctx, canCreate, typeName);
   }
   */

   // WARNING: if you change the parameters here, also update them in the addSyncInst call.
   // This class is also (mostly) copied in js/layer/lang/InstanceWrapper.scj so changes made here
   // usually are also made there.
   public InstanceWrapper(EditorContext ctx, Object inst, String typeName) {
      this.theInstance = inst;
      this.ctx = ctx;
      this.typeName = typeName;
      // Because this class is not compiled with SC, we need to include this call by hand
      SyncManager.addSyncInst(this, true, true, null, ctx, inst, typeName);
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
         return typeName != null ? (canCreate ? "<select to create>" : "<type>") : "<type>";
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

   @Override
   public String getObjectId() {
      StringBuilder sb = new StringBuilder();
      sb.append("IW__");
      if (theInstance == null && typeName != null) {
         sb.append(typeName);
         sb.append("__");
      }
      if (theInstance != null) {
         sb.append(CTypeUtil.escapeIdentifierString(DynUtil.getInstanceId(theInstance)));
      }
      return sb.toString();
   }
}
