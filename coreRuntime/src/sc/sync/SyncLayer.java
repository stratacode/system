/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.bind.Bind;
import sc.dyn.DynUtil;
import sc.dyn.RemoteResult;
import sc.obj.Sync;
import sc.obj.SyncMode;
import sc.type.CTypeUtil;

import java.util.*;

/**
 * A SyncLayer works like a transaction log, storing the changes made in a given context. It's used to serialize
 * a batch of changes from the client to the server, or vice versa. It's also used to store the "initial" state
 * on both the client and server, used for resetting one side or the other when a session is lost and needs to be
 * restored.
 */
@sc.js.JSSettings(jsModuleFile="js/sync.js", prefixAlias="sc_")
@Sync(syncMode= SyncMode.Disabled)
public class SyncLayer {
   // Stores the changed values of any sync'd instance/properties.  Used to create a serialization layer to sync the other side
   private Map<Object, HashMap<String,Object>> changedValues = new IdentityHashMap<Object, HashMap<String,Object>>();

   // Each sync layer belongs to a syncContext
   SyncManager.SyncContext syncContext;

   // Set to true when this sync layer is being serialized.
   public boolean pendingSync = false;

   // Is this a layer which represents the initial state after a page load
   public boolean initialLayer = false;

   private Map<Object, HashMap<String,Object>> pendingValues;

   private SyncChange pendingChangeList;
   private SyncChange pendingChangeLast;

   private SyncChange syncChangeList = null;
   private SyncChange syncChangeLast = null;

   private Map<SyncChange,SyncChange> latestChanges = new HashMap<SyncChange,SyncChange>();

   private TreeMap<String, RemoteResult> pendingMethods = new TreeMap<String, RemoteResult>();
   TreeMap<String, Object> pendingMethodObjs = new TreeMap<String, Object>();
   private ArrayList<RemoteResult> notifyMethods = new ArrayList<RemoteResult>();

   public IdentityHashMap<Object, SyncNewObj> pendingNewObjs = null;

   /** Each sync layer stores a list of changes made in that layer - each are a subclass of this abstract class */
   public abstract static class SyncChange {
      Object obj;
      SyncChange next;
      // True if there's another change that's overridden this change - i.e. set the same property
      boolean overridden = false;
      /**
       * True for changes that were created on the remote side (i.e. on the server for the client or the client for the server)
       * More detail: When we are restoring a client from a previous version of that client on the server, we are actually restoring two types of state.
       * Properties which were changed on the previous client, but cached in the server's session and changes which originated on the server in the first place.
       * The distinction is important so we know what information the server needs on a 'reset' operation - i.e. when the server loses it's session.  In that case, we need to update the
       * server but only for the changes which originated on the client.
       * Each change is marked with a flag and as we record changes we switch back and forth, on a property-by-property basis or at the type level.
       */
      boolean remoteChange = false;

      SyncChange(Object o) {
         obj = o;
      }

      public int hashCode() {
         // Using system identity here because of types like PropertyAssignment that mess up the equals and hashCode methods
         return System.identityHashCode(obj);
      }

      public boolean equals(Object other) {
         // Using == here not equals because of types like PropertyAssignment that mess up equals and hashCode
         if (other instanceof SyncChange) {
            SyncChange otherChange = (SyncChange) other;
            boolean res = obj == otherChange.obj;
            return res;
         }
         return false;
      }

      // Returns true if they represent the exact same change - i.e. same property and same value.  equals returns true
      // if they are just the same property.
      public boolean sameChange(Object other) {
         return equals(other);
      }

      public String getStaticTypeName() {
         return null;
      }

      public boolean isCommand() {
         return false;
      }

      public boolean applySyncFilterOnSerialize() {
         return true;
      }
   }

   private static class SyncPropChange extends SyncChange {
      String prop;
      Object val;

      SyncPropChange(Object obj, String p, Object val, boolean remote) {
         super(obj);
         prop = p;
         this.val = val;
         remoteChange = remote;
      }

      public int hashCode() {
         return super.hashCode() + (prop == null ? 0 : prop.hashCode());
      }

      public boolean equals(Object other) {
         if (!super.equals(other))
            return false;
         if (other instanceof SyncPropChange)
            return DynUtil.equalObjects(prop, ((SyncPropChange)other).prop);
         return false;
      }

      // We use equals already to find the same property with a different change.  But also need a method to detect
      // identical changes.
      public boolean sameChange(Object other) {
         return equals(other) && DynUtil.equalObjects(val, ((SyncPropChange) other).val);
      }

      public String toString() {
         String annot = "";
         if (remoteChange)
            annot = "@SyncRemote ";
         return annot + DynUtil.getInstanceName(obj) + "." + prop + " = " + DynUtil.getInstanceName(val);
      }
   }

   public static class SyncNewObj extends SyncChange {
      SyncManager.InstInfo instInfo;
      SyncNewObj(Object obj, SyncManager.InstInfo instInfo, boolean remoteChange) {
         super(obj);

         this.instInfo = instInfo;
         this.remoteChange = remoteChange;
      }

      public int hashCode() {
         return super.hashCode() + (instInfo.args == null ? 0 : Arrays.hashCode(instInfo.args));
      }

      public boolean equals(Object other) {
         if (!super.equals(other))
            return false;
         if (other instanceof SyncNewObj)
            return DynUtil.equalArrays(instInfo.args, ((SyncNewObj)other).instInfo.args);
         return false;
      }

      public String toString() {
         String annot = "";
         if (remoteChange)
            annot = "@SyncRemote ";
         StringBuilder sb = new StringBuilder();
         sb.append(annot);
         sb.append("new ");
         sb.append(DynUtil.getInstanceName(obj));
         Object[] args = instInfo.args;
         if (args != null) {
            sb.append("(");
            for (int i = 0; i < args.length; i++) {
               if (i != 0)
                  sb.append(", ");
               sb.append(DynUtil.getInstanceName(args[i]));
            }
            sb.append(")");
         }
         return sb.toString();
      }
   }

   public static abstract class SyncMethodBase extends SyncChange {
      Object type;
      public SyncMethodBase(Object obj, Object type) {
         super(obj);
         this.type = type;
      }

      public String getStaticTypeName() {
         return type != null ? DynUtil.getTypeName(type, false) : null;
      }
   }

   public static class SyncMethodCall extends SyncMethodBase {
      Object[] args;
      String instName;
      String methName;
      String callId = null;
      Object returnType;
      String paramSig;
      RemoteResult result;

      SyncManager.SyncContext syncContext;

      SyncMethodCall(SyncManager.SyncContext syncContext, Object obj, Object type, String instName, String methName, Object retType, String paramSig, Object...args) {
         super(obj, type);
         this.syncContext = syncContext;
         if (instName == null)
            instName = DynUtil.getTypeName(type, false);
         this.instName = instName;
         this.methName = methName;
         this.paramSig = paramSig;
         this.returnType = retType;
         this.args = args;
      }

      public String getCallId() {
         if (callId == null) {
            String name = CTypeUtil.getClassName(instName) + "_" + methName;
            syncContext.initIdMaps();
            Integer count = DynUtil.getTypeIdCount(name, syncContext.typeIdCounts);
            callId = name + "_" + count;
         }
         return callId;
      }

      // Method calls are always distinct - not replaced so don't match any other instances here
      public int hashCode() {
         return System.identityHashCode(this);
      }

      public boolean equals(Object other) {
         return other == this;
      }

      public String toString() {
         StringBuilder sb = new StringBuilder();
         sb.append("call ");
         if (obj != null) {
            sb.append(DynUtil.getInstanceName(obj));
            sb.append(".");
         }
         else if (type != null) {
            sb.append(DynUtil.getTypeName(type, false));
            sb.append(".");
         }
         sb.append(methName);
         if (args != null) {
            sb.append("(");
            for (int i = 0; i < args.length; i++) {
               if (i != 0)
                  sb.append(", ");
               sb.append(DynUtil.getInstanceName(args[i]));
            }
            sb.append(")");
         }
         return sb.toString();
      }

      /**
       * We don't filter remote method calls from the side in which the call originates - i.e. the server
       * can call any method on the client and the client does not use the sync filter.  We'll instead check
       * on the server before running a remote method call that is authorized
       */
      public boolean applySyncFilterOnSerialize() {
         return false;
      }
   }

   public static class SyncFetchProperty extends SyncChange {
      String propName;

      SyncFetchProperty(Object obj, String propName) {
         super(obj);
         this.propName = propName;
      }

      public int hashCode() {
         return super.hashCode() + (propName == null ? 0 : propName.hashCode());
      }

      public boolean equals(Object other) {
         if (!super.equals(other))
            return false;
         if (other instanceof SyncFetchProperty)
            return DynUtil.equalObjects(propName, ((SyncFetchProperty)other).propName);
         return false;
      }

      public String toString() {
         return "@SyncFetch " + DynUtil.getInstanceName(obj) + "." + propName;
      }
   }

   public static class SyncMethodResult extends SyncMethodBase {
      String callId = null;
      Object retValue;
      String exceptionStr;

      SyncMethodResult(Object ctxObj, Object type, String callId, Object retValue, String exceptionStr) {
         super(ctxObj, type);
         this.callId = callId;
         this.retValue = retValue;
         this.exceptionStr = exceptionStr;
      }

      public int hashCode() {
         return System.identityHashCode(this);
      }

      public boolean equals(Object other) {
         return this == other;
      }

      public String toString() {
         return (obj == null ? "static " : "") + "method result: " + (obj == null ? DynUtil.getTypeName(type, false) : DynUtil.getInstanceName(obj)) + " for: " + callId + " = " + (exceptionStr == null ? DynUtil.getInstanceName(retValue) : "Exception: " + exceptionStr);
      }
   }

   public static class SyncNameChange extends SyncChange {
      String oldName;
      String newName;

      SyncNameChange(Object obj, String oldName, String newName) {
         super(obj);
         this.oldName = oldName;
         this.newName = newName;
      }

      public int hashCode() {
         return super.hashCode() + oldName.hashCode() + newName.hashCode();
      }

      public boolean equals(Object other) {
         if (!super.equals(other))
            return false;
         // Be careful not to match an instance of the subclass SyncNameChangeAck
         if (other.getClass() == getClass()) {
            SyncNameChange oc = (SyncNameChange) other;
            return oldName.equals(oc.oldName) && newName.equals(oc.newName);
         }
         return false;
      }

      public String toString() {
         StringBuilder sb = new StringBuilder();
         if (getClass() == SyncNameChangeAck.class)
            sb.append(" nameChangeAck: ");
         else
            sb.append(" nameChange: ");
         sb.append(DynUtil.getInstanceName(obj));
         sb.append(" ");
         sb.append(oldName);
         sb.append(" -> ");
         sb.append(newName);
         return sb.toString();
      }

      public boolean isCommand() {
         return true;
      }
   }

   public static class SyncNameChangeAck extends SyncNameChange {
      SyncNameChangeAck(Object obj, String oldName, String newName) {
         super(obj, oldName, newName);
      }
   }

   public static class SyncClearResetState extends SyncChange {
      String objName;
      SyncClearResetState(Object obj, String objName) {
         super(obj);
         this.objName = objName;
      }

      public boolean equals(Object other) {
         if (!super.equals(other))
            return false;
         return other.getClass() == getClass();
      }

      public String toString() {
         StringBuilder sb = new StringBuilder();
         sb.append(" clearResetState: ");
         sb.append(objName);
         return sb.toString();
      }

      public boolean isCommand() {
         return true;
      }
   }

   public SyncLayer(SyncManager.SyncContext ctx) {
      syncContext = ctx;
   }

   public void addChangedValue(Object obj, String propName, Object val) {
      addChangedValue(obj, propName, val, false);
   }

   private boolean updateChangedValue(Object obj, String propName, Object val) {
      HashMap<String,Object> changeMap = changedValues.get(obj);
      if (changeMap == null) {
         changeMap = new HashMap<String,Object>();
         changedValues.put(obj, changeMap);
      }

      // TODO: should we remove the old one if changedBefore is true?  There could be data dependencies so maybe we need to apply them in order, ala a transaction log
      // boolean changedBefore = changeMap.containsKey(propName);
      Object oldVal = changeMap.put(propName, val);
      return oldVal != val;
   }

   /**
    * Records a property change in the SyncLayer for the given object, property, and value.
    * Use remote = true for changes which originated on the other side
    */
   public void addChangedValue(Object obj, String propName, Object val, boolean remote) {
      // TODO: performance - if this returns false, we might be able to get rid of this change but only if there are no side-effects
      // of setting this property to a value earlier in the sequence. Instead, we mark that one as overridden and set it to this value
      updateChangedValue(obj, propName, val);

      SyncChange change = new SyncPropChange(obj, propName, val, remote);
      addSyncChange(change);
   }

   public void addDepChangedValue(List<SyncChange> depChanges, Object obj, String propName, Object val, boolean remote) {
      updateChangedValue(obj, propName, val);
      depChanges.add(new SyncPropChange(obj, propName, val, remote));
   }

   /** Records a 'new object' sync change, including the optional parameters passed to the new object. */
   public void addNewObj(Object obj, SyncManager.InstInfo instInfo, boolean remoteChange) {
      SyncNewObj change = new SyncNewObj(obj, instInfo, remoteChange);
      addSyncChange(change);
      addPendingNew(obj, change);
   }

   private void addPendingNew(Object obj, SyncNewObj newObj) {
      if (pendingNewObjs == null)
         pendingNewObjs = new IdentityHashMap<Object,SyncNewObj>();
      pendingNewObjs.put(obj, newObj);
   }

   public static void addDepNewObj(List<SyncChange> depChanges, Object obj, SyncManager.InstInfo instInfo) {
      depChanges.add(new SyncNewObj(obj, instInfo, false));
   }

   public void addFetchProperty(Object obj, String prop) {
      addSyncChange(new SyncFetchProperty(obj, prop));
   }

   public void addNameChange(Object obj, String oldName, String newName) {
      addSyncChange(new SyncNameChange(obj, oldName, newName));
   }

   public void addNameChangeAck(Object obj, String oldName, String newName) {
      addSyncChange(new SyncNameChangeAck(obj, oldName, newName));
   }

   public void addClearResetState(Object obj, String objName) {
      addSyncChange(new SyncClearResetState(obj, objName));
   }

   private void addSyncChange(SyncChange change) {
      SyncChange last = latestChanges.put(change, change);
      if (last != null) {
         last.overridden = true;
      }

      if (syncChangeLast != null) {
         syncChangeLast.next = change;
      }
      else {
         syncChangeList = change;
      }
      syncChangeLast = change;

      // This will schedule a refresh of any listeners to the scope in a 'do later' which should occur at the next
      // opportunity after the current transaction is committed.
      syncContext.scope.scopeChanged();
   }

   public Object removeChangedValue(Object obj, String propName) {
      HashMap<String,Object> changeMap = changedValues.get(obj);

      if (changeMap == null)
         return null;
      return changeMap.remove(propName);
   }

   public boolean hasExactChangedValue(Object obj, String propName, Object val) {
      HashMap<String,Object> changeMap = changedValues.get(obj);

      if (changeMap == null)
         return false;

      Object oldVal = changeMap.get(propName);
      return DynUtil.equalObjects(oldVal, val);
   }

   public boolean hasChangedValue(Object obj, String propName) {
      HashMap<String,Object> changeMap = changedValues.get(obj);

      if (changeMap == null)
         return false;

      return changeMap.containsKey(propName);
   }

   public Object getChangedValue(Object obj, String propName) {
      HashMap<String,Object> changeMap = changedValues.get(obj);

      if (changeMap == null)
         return null;

      return changeMap.get(propName);
   }

   public RemoteResult invokeRemote(Object obj, Object type, String methName, Object retType, String paramSig, Object[] args) {
      SyncMethodCall change = new SyncMethodCall(syncContext, obj, type, obj == null ? null : syncContext.findObjectName(obj), methName, retType, paramSig, args);
      addSyncChange(change);
      RemoteResult res = new RemoteResult();
      res.callId = change.getCallId();
      res.returnType = retType;
      res.object = obj;
      res.instName = change.instName;

      change.result = res;

      pendingMethods.put(res.callId, res);
      if (obj != null)
         pendingMethodObjs.put(change.instName, obj);

      return res;
   }

   public void addMethodResult(Object ctxObj, Object type, String objName, Object retValue, String exceptionStr) {
      SyncMethodResult change = new SyncMethodResult(ctxObj, type, objName, retValue, exceptionStr);
      addSyncChange(change);
   }

   public boolean processMethodReturn(String callId, Object retValue, String exceptionStr) {
      RemoteResult res = pendingMethods.remove(callId);
      if (res == null)
         return false;
      if (res.returnType != null)
         retValue = SyncHandler.convertRemoteType(retValue, res.returnType);
      if (res.instName != null)
         pendingMethodObjs.remove(res.instName);

      res.exceptionStr = exceptionStr;
      res.setValue(retValue);
      notifyMethods.add(res);
      res.notifyResponseListener();

      return true;
   }

   public void notifyMethodReturns() {
      ArrayList<RemoteResult> toNotify = new ArrayList<RemoteResult>(notifyMethods);
      notifyMethods.clear();

      for (RemoteResult res:toNotify) {
         if (SyncManager.trace && res.postListener != null)
            System.out.println("Notifying post remote method return: " + res.callId + " = " + res.getValue());
         res.notifyPostListener();
      }
   }

   public void removeSyncInst(Object inst) {
      // We might have changes queued up for this object - if so, go through and remove them from the list
      HashMap<String,Object> vals = changedValues.remove(inst);
      if (vals != null && syncChangeList != null) {
         SyncChange prev = null;
         SyncChange cur = syncChangeList;
         while (cur != null) {
            if (cur.obj == inst) {
               cur = cur.next;
               if (prev == null)
                  syncChangeList = cur;
               else
                  prev.next = cur;

               if (cur instanceof SyncNewObj && pendingNewObjs != null)
                  pendingNewObjs.remove(inst);
            }
            else {
               prev = cur;
               cur = cur.next;
            }
         }

         if (prev == null)
            syncChangeLast = syncChangeList;
         else
            syncChangeLast = prev;
      }
   }

   /** Called by the destination just after serializing this sync layer and sending it across the wire. */
   public void markSyncPending() {
      pendingSync = true;

      pendingValues = changedValues;
      changedValues = new IdentityHashMap<Object, HashMap<String,Object>>();
      pendingChangeList = syncChangeList;
      pendingChangeLast = syncChangeLast;
      syncChangeLast = syncChangeList = null;
      pendingNewObjs = null;

      syncContext.markSyncPending();
   }

   public void completeSync(SyncManager.SyncContext clientContext, Integer errorCode, String message) {
      if (!pendingSync) {
         return;
      }
      pendingSync = false;

      // Notify any listeners of remote method calls in this sync and clear that list
      notifyMethodReturns();

      // If there's no error - clear the pendingValues - reset any changedValues back to the server's version.
      if (errorCode == null) {
         // Did any changes come in when the system was pending?
         if (changedValues.size() > 0) {
            ArrayList<Object> toCullObjs = new ArrayList<Object>();
            for (Map.Entry<Object,HashMap<String,Object>> changedEnt:changedValues.entrySet()) {
               Object changedObj = changedEnt.getKey();
               HashMap<String,Object> changeMap = changedEnt.getValue();

               ArrayList<String> toCull = new ArrayList<String>();

               // If these new changes conflict with submitted values, need to reset them.
               // TODO: perhaps we need various modes or a hook point to define the behavior here.
               // First need to determine if this change is a conflict or just a stream of changes to the same value
               // that are coming in asynchronously. For conflicts, have a few modes plus a hook for the client application
               // to resolve it with custom logic.
               HashMap<String,Object> pendingChangeMap = pendingValues.get(changedObj);
               if (pendingChangeMap != null) {
                  for (Map.Entry<String,Object> changedPropEnt:changeMap.entrySet()) {
                     String propName = changedPropEnt.getKey();

                     if (pendingChangeMap.containsKey(propName)) {
                        Object changedValue = changedPropEnt.getValue();
                        Object pendingValue = pendingChangeMap.get(propName);
                        if (!DynUtil.equalObjects(changedValue, pendingValue)) {
                           // Reset the old property
                           if (SyncManager.trace)
                              System.out.println("Warning: change in SyncLayer: " + this + " during sync for: " + changedObj + "." + propName + " - will be delivered on next sync");

                           // TODO: this logic where we reset it back to the value we just sync'd does not work in the case where we're really just like
                           // to send a streaming set of changes to the client (like from a test script)
                           //DynUtil.setProperty(changedObj, propName, pendingValue);
                           //toCull.add(propName);
                        }
                     }
                  }
               }
               for (String cullPropName:toCull){
                  changeMap.remove(cullPropName);
               }
               if (changeMap.size() == 0)
                  toCullObjs.add(changedObj);
            }
            for (Object toCullObj:toCullObjs)
               changedValues.remove(toCullObj);
            // TODO remove these guys from the syncChangeList as well.
         }
         if (pendingValues.size() > 0) {
            for (Map.Entry<Object,HashMap<String,Object>> pendingEnt:pendingValues.entrySet()) {
               Object pendingObj = pendingEnt.getKey();
               HashMap<String,Object> pendingMap = pendingEnt.getValue();

               syncContext.applyRemoteChanges(pendingObj, pendingMap);
            }
         }
      }
      // If there's an error we'll preserve the current state - merging pendingValues with the changedValues but where changedValues (the most recent changes) have precedence
      else {
         for (RemoteResult res:pendingMethods.values()) {
            res.exceptionStr = message;
            res.errorCode = errorCode;
            res.notifyAllListeners();
         }

         if (changedValues.size() > 0) {
            for (Map.Entry<Object,HashMap<String,Object>> pendingEnt:pendingValues.entrySet()) {
               Object pendingObj = pendingEnt.getKey();
               HashMap<String,Object> pendingMap = pendingEnt.getValue();

               // Merge the old changes with the new ones, keeping the new ones (in changedValues) in the case where's a duplicate
               HashMap<String,Object> changeMap = changedValues.get(pendingObj);
               if (changeMap != null) {
                  for (Map.Entry<String,Object> pendingPropEnt:pendingMap.entrySet()) {
                     String propName = pendingPropEnt.getKey();

                     if (!changeMap.containsKey(propName)) {
                        Object pendingValue = pendingPropEnt.getValue();

                        // TODO: this should be placed in order before the new changed values, since it happened first.
                        addChangedValue(pendingObj, propName, pendingValue);
                     }
                  }
               }
               else
                  changedValues.put(pendingObj, pendingMap);
            }
         }
         else {
            changedValues = pendingValues;
            syncChangeList = pendingChangeList;
            syncChangeLast = pendingChangeLast;
         }
      }
      syncContext.completeSync(clientContext, errorCode, message);
      pendingValues = null;
      pendingChangeList = null;
      pendingChangeLast = null;
   }

   private final String noPackageNameSentinel = "$no-package-name";

   public SyncSerializer serialize(SyncManager.SyncContext parentContext, HashSet<String> createdTypes,
                                   boolean fetchesOnly, Set<String> syncTypeFilter, Set<String> resetTypeFilter) {
      SyncManager syncManager = syncContext.getSyncManager();
      SyncSerializer ser = syncManager.syncDestination.createSerializer();

      SyncChange change = syncChangeList;
      SyncChange prevChange = null;

      SyncChangeContext changeCtx = new SyncChangeContext();
      // init this so that even if the first package is null that we add the package ; statement.
      // If not, joining together sync layers from different contexts will break when the last component has a non-null
      // package and the first one here does not.
      changeCtx.lastPackageName = noPackageNameSentinel;
      changeCtx.currentObjNames = new ArrayList<String>();

      while (change != null) {
         // Skip overridden changes
         if (change.overridden) {
            prevChange = change;
            change = change.next;
            continue;
         }

         // During a resync, we will send all of the changes in the initial layer.  That won't contain any pending fetch properties though... so we collect them from
         // the pending layer by skipping all other changes.
         if (fetchesOnly && !(change instanceof SyncFetchProperty)) {
            prevChange = change;
            change = change.next;
            continue;
         }

         addChangedObject(parentContext, ser, change, prevChange, changeCtx, createdTypes, syncTypeFilter, resetTypeFilter);

         prevChange = change;
         change = change.next;
      }
      if (changeCtx.remoteChange) {
         int indent = changeCtx.currentObjNames.size();
         ser.appendRemoteChanges(false, indent == 0, indent);
      }
      popCurrentObjNames(ser, changeCtx.currentObjNames, changeCtx.currentObjNames.size());

      return ser;
   }

   private String getPathName(List<String> names) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < names.size(); i++) {
         if (i != 0)
            sb.append(".");
         sb.append(names.get(i));
      }
      return sb.toString();
   }

   // Two identical ways to reach the same type
   private boolean namesMatch(List<String> newNames, List<String> oldNames) {
      return getPathName(newNames).equals(getPathName(oldNames));
   }

   private class SyncChangeContext {
      ArrayList<String> currentObjNames;
      String lastPackageName;
      boolean remoteChange;
   }

   private void addChangedObject(SyncManager.SyncContext parentContext, SyncSerializer ser, SyncChange change, SyncChange prevChange,
                                 SyncChangeContext changeCtx, HashSet<String> createdTypes,
                                 Set<String> syncTypeFilter, Set<String> resetTypeFilter) {
      Object changedObj = change.obj;
      String objName = null;

      // As we add this change, we might encounter instances which are newly referenced in this sync layer.  This list accumulates those
      // objects so we can be sure to include their definition before we serialize this change.
      ArrayList<SyncChange> depChanges = new ArrayList<SyncChange>();

      HashMap<String,Object> changeMap = null;
      SyncHandler syncHandler = changedObj == null ? null : parentContext.getSyncHandler(changedObj);

      ArrayList<String> currentObjNames = changeCtx.currentObjNames;
      String newLastPackageName = changeCtx.lastPackageName;
      boolean isNew = false;
      boolean isResetType = false;
      String objTypeName;
      boolean useObjNameForPackage = true;
      String newObjName = null;
      Object[] newArgs = null;
      String changedObjFullName = null;

      String changedObjPkg;
      Object changedObjType = null;

      boolean isCommand = change.isCommand();
      SyncManager.InstInfo instInfo = null;

      if (changedObj != null) {
         changedObjPkg = syncHandler.getPackageName();
         String changedObjName = syncHandler.getObjectBaseName(null, this);
         changedObjFullName = syncHandler.getObjectName();

         boolean isNewObj = change instanceof SyncNewObj;

         // isNew should not be set for property changes when the current object is set or already serialized.
         isNew = (syncHandler.isNewInstance() || (initialLayer && isNewObj)) &&
                  (!currentObjNames.contains(changedObjName) || !equalStrings(changedObjPkg, changeCtx.lastPackageName)) &&
                  createdTypes != null && !createdTypes.contains(changedObjFullName) && !(change instanceof SyncMethodResult);

         newArgs = isNewObj ? ((SyncNewObj) change).instInfo.args : isNew ? parentContext.getNewArgs(changedObj) : null;
         changedObjType = syncHandler.getObjectType(changedObj);
         objTypeName = DynUtil.getTypeName(changedObjType, false);

         if (change.applySyncFilterOnSerialize() && syncTypeFilter != null && !parentContext.matchesTypeFilter(syncTypeFilter, objTypeName)) {
            // If it's not in either filter, it's not meant to be serialized to this client
            if (resetTypeFilter == null || !resetTypeFilter.contains(objTypeName)) {
               if (SyncManager.trace) {
                  if (change instanceof SyncPropChange)
                     System.out.println("Omitting sync for prop: " + objTypeName + "." + ((SyncPropChange) change).prop + ": to client - no reference in filter for: " + parentContext);
                  else
                     System.out.println("Omitting sync for type: " + objTypeName + ": to client - no reference in filter for: " + parentContext);
               }
               return;
            }
            // This represents a client that does not have the code for the remote side, but still wants some state to
            // be serialized so it can reset the session if it goes away.
            isResetType = true;
         }

         // TODO: should we add an option to suppress redundant changes?  How can we tell when there are side effects or not?
         // if change.value != changeMap.get(prop) this guy has been been overridden.
         if (change instanceof SyncPropChange) {
            changeMap = changedValues.get(changedObj);
            if (changeMap == null) {
               return;
            }
         }

         if (isNewObj && pendingNewObjs != null)
            pendingNewObjs.remove(changedObj);

         // What object do we need to be current
         Object newObj = changedObj;
         Object newObjType = DynUtil.getType(newObj);

         instInfo = parentContext.getInstInfo(changedObj);
         if (instInfo == null) {
            SyncManager.InstInfo parentInstInfo = parentContext.getInheritedInstInfo(changedObj);
            // If there's no inst info and it's not a new sync instance, it's a change for some instance that's not synchronized.  This happens for the RemoteObject
            // sample which only needs the name of the remote object, since the object itself is not sync'd.
            if (parentInstInfo == null) {
               if (isNew) {
                  if (SyncManager.trace)
                     System.out.println("Skipping sync change for object of type: " + objTypeName + " - not found in sync context: " + parentContext);
                  return;
               }
            }
            else
               instInfo = parentContext.createAndRegisterInheritedInstInfo(changedObj, parentInstInfo);
         }
         if (isCommand) {
            changedObjPkg = null;
            objName = newObjName = null;
            objTypeName = null;
         }
         else {
            if (instInfo != null && !instInfo.nameQueued)
               instInfo.nameQueued = true;

            // When we are creating a new type, the current object is the parent of the object itself
            if (isNew) {
               if (newArgs != null && newArgs.length > 0) {
                  // For objects that will turn into a field, need to go out one level for the modify operator
                  Object outer = DynUtil.getOuterObject(changedObj);
                  if (outer != null)
                     newObj = outer;
                  else {
                     // Some objects are not tracked so we don't know the outer object.  In this case, use the type to see if this is in an inner class and base the type to use for the container on the type name.
                     int numLevels = DynUtil.getNumInnerTypeLevels(newObjType);
                     if (numLevels > 0)
                        newObjName = CTypeUtil.getPackageName(objTypeName);
                     else {
                        // For SC serialization format, because it's like Java we can't omit the top-level name so we use the new obj type name but for JSON and other formats
                        // we don't want this top-level thing
                        if (ser.needsObjectForTopLevelNew()) {
                           newObjName = CTypeUtil.getClassName(DynUtil.getTypeName(newObjType, false));
                           useObjNameForPackage = false;
                        }
                        else {
                           newObjName = "";
                        }
                     }
                  }
                  objName = syncHandler.getObjectBaseName(depChanges, this);
               }
               else {
                  objName = newObjName = syncHandler.getObjectBaseName(depChanges, this);
                  if (newObjName.contains(".")) {
                     newObjName = CTypeUtil.getPackageName(objName);
                     Object outer = DynUtil.getOuterObject(newObj);
                     if (outer != null)
                        newObj = outer;
                     else {
                        int numLevels = DynUtil.getNumInnerTypeLevels(newObjType);
                        if (numLevels > 0) {
                           newObjName = CTypeUtil.getPackageName(objTypeName);
                        }
                        else
                           newObjName = "";
                     }
                  }
                  // Treat top-level types as top-level.  Also treat TypeDeclarations as top-level even if they represent an inner type
                  else if (DynUtil.getNumInnerTypeLevels(newObjType) == 0 || objName.startsWith("sc_type_")) {
                     newObjName = "";
                  }
               }
            }

            if (newObjName == null)
               newObjName = parentContext.getObjectBaseName(newObj, depChanges, this);

            if (objName == null)
               objName = newObjName;
         }
      }
      else {
         objTypeName = change.getStaticTypeName();
         useObjNameForPackage = false;
         newObjName = objName = CTypeUtil.getClassName(objTypeName);
         changedObjPkg = null;
      }

      // First we compute the new obj name and new obj names for this change.  They are not getting applied yet though so we keep them as a copy.
      // We may need to add dependent objects first.

      SyncSerializer switchSB = null;
      int switchObjNum = -1;
      int switchObjStart = -1;

      String newPackageName = useObjNameForPackage ? changedObjPkg : CTypeUtil.getPackageName(objTypeName);
      boolean packagesMatch = !(changeCtx.lastPackageName != newPackageName && (changeCtx.lastPackageName == null || !changeCtx.lastPackageName.equals(newPackageName)));
      if (isCommand) // commands always are put at the top level
         packagesMatch = false;

      boolean switchedPackage = false;
      boolean pushName = true;
      boolean topLevel = false;
      int ix;
      int sz = currentObjNames.size();
      ArrayList<String> newObjNames = (ArrayList<String>) currentObjNames.clone();
      ArrayList<String> startObjNames = null;
      if (!packagesMatch) {
         if (sz > 0) {
            if (switchSB == null)
               switchSB = ser.createTempSerializer(false, newObjNames.size());
            // Pop from sz to the one before curIx
            // But... do not put the close braces into switchSB because we may need to change the number of close tags before
            // we insert the new object name.  Instead, keep track of the start/end level and generate them just before
            // we prepend the switchSB.
            switchObjNum = sz;
            switchObjStart = newObjNames.size();
            popCurrentObjNames(null, newObjNames, sz);

            // This is the "object x" case with creating a new top-level object, such as an ObjectId.  We'll do object x as a top-level which is fine
            if (isNew && !newObjName.contains(".") && (newArgs == null || newArgs.length == 0)) {
               pushName = false; // topLevel
               topLevel = true;
            }
         }
      }
      else {
         if (sz > 0) {
            ix = sz - 1;
            // Do these path names reach the same type?  If so, nothing changes
            if (DynUtil.equalObjects(getPathName(currentObjNames), newObjName)) {
               pushName = false;
               newObjNames = (ArrayList<String>) currentObjNames.clone();
            } else {
               int curIx = newObjNames.indexOf(newObjName);
               // This object is not in the current stack - but maybe a parent is?
               if (curIx == -1) {
                  // Can we find a parent name which matches?
                  String currentParent = CTypeUtil.getPackageName(newObjName);
                  String currentChildPath = CTypeUtil.getClassName(newObjName);
                  while (currentParent != null) {
                     int parIx = newObjNames.indexOf(currentParent);

                     // Found a parent so close to reach it's context, then strip that part off of the rest of the name we need to set.
                     if (parIx != -1) {
                        if (switchSB == null)
                           switchSB = ser.createTempSerializer(false, newObjNames.size());
                        switchObjNum = sz - parIx - 1;
                        switchObjStart = newObjNames.size();
                        popCurrentObjNames(null, newObjNames, switchObjNum);
                        newObjName = currentChildPath;
                        break;
                     }
                     currentChildPath = CTypeUtil.getClassName(currentParent) + "." + currentChildPath;
                     currentParent = CTypeUtil.getPackageName(currentParent);
                  }
                  // Nothing in common so get rid of all of these names
                  if (currentParent == null) {
                     if (switchSB == null)
                        switchSB = ser.createTempSerializer(false, newObjNames.size());
                     switchObjNum = sz;
                     switchObjStart = newObjNames.size();
                     popCurrentObjNames(null, newObjNames, sz);
                     // This is the "object x" case with creating a new top-level object, such as an ObjectId.  We'll do object x as a top-level which is fine
                     if (isNew && !newObjName.contains(".") && (newArgs == null || newArgs.length == 0)) {
                        pushName = false; // topLevel
                        topLevel = true;
                     }
                  }
               } else {
                  if (switchSB == null)
                     switchSB = ser.createTempSerializer(false, newObjNames.size());
                  // Pop from sz to the one before curIx
                  switchObjNum = sz - curIx - 1;
                  switchObjStart = newObjNames.size();
                  popCurrentObjNames(null, newObjNames, switchObjNum);
                  pushName = false;
               }
            }
         }
      }

      // Globally defined object?   Do we handle this case?
      if (objName == null) {
         if (!isCommand)
            System.err.println("*** No object name for sync operation");
      }
      else {
         String packageName = useObjNameForPackage ? syncHandler.getPackageName() : CTypeUtil.getPackageName(objTypeName);
         if (changeCtx.lastPackageName != packageName && (changeCtx.lastPackageName == null || !changeCtx.lastPackageName.equals(packageName))) {
            if (newObjNames.size() > 0)
               System.err.println("*** Mismatching packages with somehow matching types");
            if (switchSB == null)
               switchSB = ser.createTempSerializer(false, newObjNames.size());

            switchedPackage = true;
            switchSB.changePackage(packageName);
            newLastPackageName = packageName;
         }
      }

      if (pushName && newObjName != null && newObjName.length() > 0) {
         int currSize = newObjNames.size();
         newObjNames.add(newObjName);
         int indent = currSize + 1;
         if (switchSB == null)
            switchSB = ser.createTempSerializer(false, indent);
         else
            switchSB.setIndent(indent);
         switchSB.pushCurrentObject(newObjName, currSize);
      }
      else if (sz == 0)
         topLevel = true;

      // END - set current context

      SyncSerializer newSB = ser.createTempSerializer(false, newObjNames.size());

      boolean newSBPushed = false;
      boolean newRemoteChange = false;
      boolean newRemoteChangeSet = false;
      if (isNew) {
         NewObjResult newObjRes = newSB.appendNewObj(changedObj, objName, objTypeName, newArgs, newObjNames, newLastPackageName, syncHandler, parentContext, this, depChanges, change instanceof SyncPropChange);
         if (newObjRes != null) {
            if (newObjRes.newSB != null)
               newSB = newObjRes.newSB;
            newSBPushed = newObjRes.newSBPushed;
            if (newObjRes.startObjNames != null)
               startObjNames = newObjRes.startObjNames;
         }

         // This prevents us from issuing 2 object operators in the same serialized session.  We could just call registerObjName on this
         // and avoid the extra hash map but I think there's value in being able to serialize more than once before we mark the sync as completed.
         // If the remote side does not get data, we may need to resync with further changes, meaning we need to serialize again.
         if (createdTypes != null)
            createdTypes.add(changedObjFullName);
      }

      if (change instanceof SyncPropChange) {
         SyncPropChange propChange = (SyncPropChange) change;
         String propName = propChange.prop;
         if (changeMap != null && changeMap.containsKey(propName)) {
            Object propValue = changeMap.get(propName);

            if (isResetType) {
               if (changedObjType == null)
                  return;
               SyncProperties syncProps = syncContext.getSyncManager().getSyncProperties(changedObjType);
               if (instInfo == null) {
                  System.err.println("*** Missing instInfo for reset prop change");
                  return;
               }
               if ((syncProps.getSyncFlags(propName) & SyncPropOptions.SYNC_RESET_STATE) == 0 || !instInfo.resetState) {
                  if (SyncManager.trace)
                     System.out.println("Omitting sync for property: " + objTypeName + "." + propName + ": to client - in reset filter but not regular and not sync'd as reset state: " + parentContext);
                  return;
               }
            }

            // Need to add this in the context of a current object - before the property change
            boolean remoteChange = change.remoteChange;
            if (changeCtx.remoteChange != remoteChange) {
               newRemoteChange = remoteChange;
               newRemoteChangeSet = true;
               newSB.appendRemoteChanges(remoteChange, false, newObjNames.size());
            }

            newSB.appendProp(changedObj, propName, propValue, newObjNames, newLastPackageName, parentContext, this, depChanges);
         }
      }
      else if (change instanceof SyncMethodCall) {
         SyncMethodCall smc = (SyncMethodCall) change;

         newSB.appendMethodCall(parentContext, smc, newObjNames, newLastPackageName, depChanges, this);
      }
      else if (change instanceof SyncMethodResult) {
         SyncMethodResult mres = (SyncMethodResult) change;

         newSB.appendMethodResult(parentContext, mres, newObjNames, newLastPackageName, depChanges, this);

      }
      else if (change instanceof SyncFetchProperty) {
         SyncFetchProperty fetchProp = (SyncFetchProperty) change;

         int indentSize = newObjNames.size();

         newSB.appendFetchProperty(fetchProp.propName, indentSize);
      }
      else if (change instanceof SyncNameChange) {
         SyncNameChange nameChange = (SyncNameChange) change;

         int indentSize = newObjNames.size();

         if (nameChange.getClass() == SyncNameChangeAck.class)
            newSB.appendNameChangeAck(nameChange.oldName, nameChange.newName, indentSize);
         else {
            // Update the name here so that references following this change will use the new name
            syncContext.updateInstName(nameChange.obj, nameChange.oldName, nameChange.newName);

            newSB.appendNameChange(nameChange.oldName, nameChange.newName, indentSize);
         }
      }
      else if (change instanceof SyncClearResetState) {
         SyncClearResetState resetStateChange = (SyncClearResetState) change;

         newSB.appendClearResetState(resetStateChange.objName, newObjNames.size());
      }

      // Before we add the newSB, insert any dependencies that need to be before
      if (depChanges.size() != 0) {
         String origPackage = newLastPackageName;
         int dix = 0;
         // Insert the dep changes into the original list just before the current change.  Any dependencies they cause will go into the list
         // just ahead of the change which caused the dependency.
         for (SyncChange depChange:depChanges) {
            if (prevChange == null)
               syncChangeList = depChange;
            else
               prevChange.next = depChange;

            if (dix == depChanges.size() - 1)
               depChange.next = change;
            else
               depChange.next = depChanges.get(dix+1);

            addChangedObject(parentContext, ser, depChange, prevChange, changeCtx, createdTypes, syncTypeFilter, resetTypeFilter);
            prevChange = depChange;
            dix++;
         }

         // When we need to define a new field for a property change, there are two current objects - the starting one which holds the field, and then the second modify of the object that field defined
         ArrayList<String> toCompNames = startObjNames != null ? startObjNames : newObjNames;

         // The current object changed - need to reset it.  This is awkward both here and in the output probably but we are treating the dep changes as a special case.
         if (!currentObjNames.equals(toCompNames) || !equalStrings(changeCtx.lastPackageName, origPackage)) {
            // In this case, we just do the switch back and so do not need the original 'switch code' used to switch the types originally
            switchSB = null;
            switchObjStart = -1;
            popCurrentObjNames(ser, currentObjNames, currentObjNames.size());
            if (!DynUtil.equalObjects(origPackage, changeCtx.lastPackageName)) {
               ser.changePackage(origPackage);
            }

            ArrayList<String> toPush = toCompNames;
            if (newSBPushed) {
               toPush = new ArrayList<String>(toCompNames);
               toPush.remove(toPush.size()-1);
            }
            pushCurrentObjNames(ser, toPush, toPush.size());
         }
         else {
            // Just leave the current object since the dep set it properly for us
            if (DynUtil.equalObjects(newLastPackageName, changeCtx.lastPackageName)) {
               switchSB = null;
               switchObjStart = -1;
            }
            else {
               // Cancel the 'close' brace we would have needed since this was done already but we do need to switch the package part
               switchObjStart = -1;
               // If the package matched before the dependencies we won't have a package statement here and yet now need one
               if (!switchedPackage) {
                  if (switchSB == null)
                     switchSB = ser.createTempSerializer(false, newObjNames.size());
                  switchSB.changePackage(newPackageName);
               }
            }
         }
      }
      // The close is kept separate - if we have to change names due to a dependent object, that will have already closed it for us.
      if (switchSB != null) {
         if (switchObjStart != -1) {
            int end = switchObjStart - switchObjNum;
            for (int popIx = switchObjStart - 1; popIx >= end; popIx--)
               ser.popCurrentObject(popIx);
         }
         ser.appendSerializer(switchSB);
      }

      // We had to change the init/initLocal flag as part of the new buffer which starts right after we append this
      // here in the newSB so update changeCtx here
      if (newRemoteChangeSet)
         changeCtx.remoteChange = newRemoteChange;

      ser.appendSerializer(newSB);

      currentObjNames.clear();
      currentObjNames.addAll(newObjNames);
      changeCtx.lastPackageName = newLastPackageName;
   }

   public final static String GLOBAL_TYPE_NAME = "_GLOBAL_";

   static boolean equalStrings(String s1, String s2) {
      return s1 == s2 || (s1 != null && s1.equals(s2));
   }

   static void pushCurrentObjNames(SyncSerializer ser, ArrayList<String> currentObjNames, int num) {
      String parentName = null;
      for (int i = 0; i < num; i++) {
         String objName = currentObjNames.get(i);
         if (i > 0) {
            if (objName.startsWith(parentName))
               objName = objName.substring(parentName.length() + 1);
         }
         // Use the whole name when it's the first
         if (parentName == null)
            parentName = objName;
         else
            parentName = parentName + "." + objName;

         ser.pushCurrentObject(objName, i);
      }
   }

   static void popCurrentObjNames(SyncSerializer ser, ArrayList<String> currentObjNames, int num) {
      for (int i = 0; i < num; i++) {
         int ix = currentObjNames.size()-1;
         if (ser != null)
            ser.popCurrentObject(ix);
         currentObjNames.remove(currentObjNames.size()-1);
      }
   }

   Map<Object, HashMap<String,Object>> getChangedValues() {
      return changedValues;
   }

   public String getChangeList() {
      StringBuilder sb = new StringBuilder();
      if (syncChangeList == null)
         sb.append("empty list");
      else {
         SyncChange change = syncChangeList;

         while (change != null) {
            // Skip overridden changes
            if (change.overridden) {
               change = change.next;
               continue;
            }
            sb.append(change.toString());
            sb.append("\n");
            change = change.next;
         }
      }
      return sb.toString();
   }

   public void dump() {
      System.out.println(getChangeList());
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("syncLayer for: ");
      if (syncContext != null)
         sb.append(syncContext);
      if (initialLayer)
         sb.append(" - initial layer");
      return sb.toString();
   }

}
