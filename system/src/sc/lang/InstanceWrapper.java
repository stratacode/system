/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.dyn.DynUtil;
import sc.lang.java.AbstractMethodDefinition;
import sc.lang.java.BodyTypeDeclaration;
import sc.lang.java.ConstructorDefinition;
import sc.lang.java.Parameter;
import sc.obj.IObjectId;
import sc.obj.Sync;
import sc.sync.SyncManager;
import sc.type.CTypeUtil;
import sc.obj.SyncMode;
import sc.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Sync(onDemand=true)
public class InstanceWrapper implements IObjectId {
   @Sync(syncMode=SyncMode.Disabled)
   EditorContext ctx;
   @Sync(syncMode=SyncMode.Disabled)
   public Object theInstance;
   @Sync(syncMode=SyncMode.Disabled)
   public String typeName;

   @Sync(syncMode=SyncMode.Disabled)
   public boolean pendingCreate = false;

   @Sync(syncMode=SyncMode.Disabled)
   public boolean selectToCreate = false;

   @Sync(syncMode=SyncMode.Disabled)
   public Map<String,Object> pendingValues = null;

   @Sync(syncMode=SyncMode.Disabled)
   public String labelName = null;

   /*
   public InstanceWrapper(EditorContext ctx, boolean canCreate, String typeName) {
      this.typeName = typeName;
      this.canCreate = canCreate;
      this.ctx = ctx;
      SyncManager.addSyncInst(this, true, true, null, ctx, canCreate, typeName);
   }
   */

   // WARNING: if you change the parameters here, also update the last three args for the addSyncInst call made here.
   // This class is also (mostly) copied in js/layer/lang/InstanceWrapper.scj so changes made here
   // usually are also made there.
   public InstanceWrapper(EditorContext ctx, Object inst, String typeName, String labelName, boolean selectToCreate) {
      this.theInstance = inst;
      this.ctx = ctx;
      this.typeName = typeName;
      this.labelName = labelName;
      this.selectToCreate = selectToCreate;
      // Because this class is not compiled with SC, we need to include this call by hand
      SyncManager.addSyncInst(this, true, true, null, null, ctx, inst, typeName);
   }

   public Object getInstance() {
      if (theInstance != null)
         return theInstance;

      if (selectToCreate) {
         return theInstance = DynUtil.resolveName(typeName, true, false);
      }

      return null;
   }

   public String toString() {
      if (labelName != null)
         return labelName;
      if (theInstance == null)
         return typeName != null ? (selectToCreate ? "<select to create>" : "<type>") : "<type>";
      return DynUtil.getDisplayName(theInstance);
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
      if (theInstance == null) {
         if (typeName != null)
            return typeName.hashCode();
         return 0;
      }
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
      else if (pendingCreate) {
         sb.append("_pending_new_");
      }
      return sb.toString();
   }

   public InstanceWrapper copyWithInstance(Object instance) {
      InstanceWrapper newWrapper = new InstanceWrapper(ctx, instance, typeName, null, false);
      return newWrapper;
   }
}
