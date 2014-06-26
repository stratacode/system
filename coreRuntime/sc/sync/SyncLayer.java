/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.bind.Bind;
import sc.dyn.DynUtil;
import sc.dyn.RemoteResult;
import sc.obj.Sync;
import sc.obj.SyncMode;
import sc.type.CTypeUtil;

import java.util.*;

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

   public static class SyncChange {
      Object obj;
      SyncChange next;
      boolean overridden = false;
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
   }

   private static class SyncPropChange extends SyncChange {
      String prop;
      Object val;;

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
      Object[] args;
      SyncNewObj(Object obj, Object...args) {
         super(obj);

         this.args = args;
      }

      public int hashCode() {
         return super.hashCode() + (args == null ? 0 : args.hashCode());
      }

      public boolean equals(Object other) {
         if (!super.equals(other))
            return false;
         if (other instanceof SyncNewObj)
            return DynUtil.equalObjects(args, ((SyncNewObj)other).args);
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

   public static class SyncMethodCall extends SyncChange {
      Object[] args;
      String instName;
      String methName;
      String callId = null;
      RemoteResult result;

      SyncMethodCall(Object obj, String instName, String methName, Object...args) {
         super(obj);
         this.instName = instName;
         this.methName = methName;
         this.args = args;
      }

      // Explicitly not implementing equals and hashCode here since these are never replaced - they should use object identity

      public String getCallId() {
         if (callId == null) {
            String name = CTypeUtil.getClassName(instName) + "_" + methName;
            Integer count = DynUtil.getTypeIdCount(name);
            callId = name + "_" + count;
         }
         return callId;
      }

      // Method calls are always distinct
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

   public static class SyncMethodResult extends SyncChange {
      String callId = null;
      Object retValue;

      SyncMethodResult(Object ctxObj, String callId, Object retValue) {
         super(ctxObj);
         this.callId = callId;
         this.retValue = retValue;
      }

      public int hashCode() {
         return System.identityHashCode(this);
      }

      public boolean equals(Object other) {
         return this == other;
      }

      public String toString() {
         return "method result: " + (obj == null ? "" : DynUtil.getInstanceName(obj)) + " for: " + callId + " = " + DynUtil.getInstanceName(retValue);
      }
   }

   public SyncLayer(SyncManager.SyncContext ctx) {
      syncContext = ctx;
   }

   public void addChangedValue(Object obj, String propName, Object val) {
      addChangedValue(obj, propName, val, false);
   }

   public void addChangedValue(Object obj, String propName, Object val, boolean remote) {
      HashMap<String,Object> changeMap = changedValues.get(obj);
      if (changeMap == null) {
         changeMap = new HashMap<String,Object>();
         changedValues.put(obj, changeMap);
      }

      // TODO: should we remove the old one if changedBefore is true?  There could be data dependencies so maybe we need to apply them in order, ala a transaction log
      // boolean changedBefore = changeMap.containsKey(propName);
      changeMap.put(propName, val);

      SyncChange change = new SyncPropChange(obj, propName, val, remote);
      addSyncChange(change);
   }

   public void addNewObj(Object obj, Object...args) {
      SyncNewObj change = new SyncNewObj(obj, args);
      addSyncChange(change);
   }

   public static void addDepNewObj(List<SyncChange> depChanges, Object obj, Object...args) {
      depChanges.add(new SyncNewObj(obj, args));
   }

   public void addFetchProperty(Object obj, String prop) {
      addSyncChange(new SyncFetchProperty(obj, prop));
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

   public RemoteResult invokeRemote(Object obj, String methName, Object[] args) {
      SyncMethodCall change = new SyncMethodCall(obj, syncContext.findObjectName(obj), methName, args);
      addSyncChange(change);
      RemoteResult res = new RemoteResult();
      res.callId = change.getCallId();
      change.result = res;

      pendingMethods.put(res.callId, res);

      return res;
   }

   public void addMethodResult(Object ctxObj, String objName, Object retValue) {
      SyncMethodResult change = new SyncMethodResult(ctxObj, objName, retValue);
      addSyncChange(change);
   }

   public boolean processMethodReturn(String callId, Object retValue) {
      RemoteResult res = pendingMethods.remove(callId);
      if (res == null)
         return false;
      res.setValue(retValue);
      if (res.listener != null)
         res.listener.response(retValue);
      return true;
   }

   public void removeSyncInst(Object inst) {
      changedValues.remove(inst);
   }

   /** Called by the destination just after serializing this sync layer and sending it across the wire. */
   public void markSyncPending() {
      pendingSync = true;

      pendingValues = changedValues;
      changedValues = new IdentityHashMap<Object, HashMap<String,Object>>();
      pendingChangeList = syncChangeList;
      pendingChangeLast = syncChangeLast;
      syncChangeLast = syncChangeList = null;

      syncContext.markSyncPending();
   }

   public void completeSync(SyncManager.SyncContext clientContext, boolean error) {
      if (!pendingSync) {
         return;
      }
      pendingSync = false;
      // If there's no error - clear the pendingValues - reset any changedValues back to the server's version.
      if (!error) {
         // Did any changes come in when the system was pending?
         if (changedValues.size() > 0) {
            ArrayList<Object> toCullObjs = new ArrayList<Object>();
            for (Map.Entry<Object,HashMap<String,Object>> changedEnt:changedValues.entrySet()) {
               Object changedObj = changedEnt.getKey();
               HashMap<String,Object> changeMap = changedEnt.getValue();

               ArrayList<String> toCull = new ArrayList<String>();

               // If these new changes conflict with submitted values, need to reset them.
               // TODO: perhaps enable this via a mode.  You might want the latest user's changes to be put into the
               // deviations from the server as an alternative.
               HashMap<String,Object> pendingChangeMap = pendingValues.get(changedObj);
               if (pendingChangeMap != null) {
                  for (Map.Entry<String,Object> changedPropEnt:changeMap.entrySet()) {
                     String propName = changedPropEnt.getKey();

                     if (pendingChangeMap.containsKey(propName)) {
                        Object changedValue = changedPropEnt.getValue();
                        Object pendingValue = pendingChangeMap.get(propName);
                        if (!DynUtil.equalObjects(changedValue, pendingValue)) {
                           // Reset the old property

                           // TODO: put in a logging message here?
                           DynUtil.setProperty(changedObj, propName, pendingValue);

                           toCull.add(propName);
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
            if (res.listener != null)
               res.listener.error(1, null);
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
      syncContext.completeSync(clientContext, error);
      pendingValues = null;
      pendingChangeList = null;
      pendingChangeLast = null;
   }

   public CharSequence serialize(SyncManager.SyncContext parentContext, HashSet<String> createdTypes, boolean fetchesOnly) {
      StringBuilder sb = new StringBuilder();
      SyncManager syncManager = syncContext.getSyncManager();

      SyncChange change = syncChangeList;
      SyncChange prevChange = null;

      SyncChangeContext changeCtx = new SyncChangeContext();
      changeCtx.lastPackageName = null;
      changeCtx.currentObjNames = new ArrayList<String>();

      while (change != null) {
         // Skip overriden changes
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

         addChangedObject(parentContext, sb, change, prevChange, changeCtx, createdTypes);

         prevChange = change;
         change = change.next;
      }
      // If we ended up serializing remote changes, need to put it back in case there's another layer of changes
      // in the stream.  We could of course propagate the remoteChange flag to avoid that but I think it's
      // session to global now so it would be there in any case.
      if (changeCtx.remoteChange) {
         appendRemoteChanges(sb, false, changeCtx.currentObjNames.size() == 0);
      }
      popCurrentObjNames(sb, changeCtx.currentObjNames, changeCtx.currentObjNames.size());

      if (sb.length() > 0)
         return syncManager.syncDestination.translateSyncLayer(sb.toString());
      return "";
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

   private void addChangedObject(SyncManager.SyncContext parentContext, StringBuilder sb, SyncChange change, SyncChange prevChange,
                                   SyncChangeContext changeCtx, HashSet<String> createdTypes) {
      Object changedObj = change.obj;
      String objName;

      ArrayList<SyncChange> depChanges = new ArrayList<SyncChange>();

      HashMap<String,Object> changeMap = changedValues.get(changedObj);
      SyncHandler syncHandler = parentContext.getSyncHandler(changedObj);

      String changedObjName = syncHandler.getObjectBaseName(null, this);

      ArrayList<String> currentObjNames = changeCtx.currentObjNames;

      // isNew should not be set for property changes when the current object is set or already serialized.
      boolean isNew = (syncHandler.isNewInstance() || (initialLayer && change instanceof SyncNewObj)) && !currentObjNames.contains(changedObjName) && createdTypes != null && !createdTypes.contains(changedObjName) && !(change instanceof SyncMethodResult);
      Object[] newArgs = change instanceof SyncNewObj ? ((SyncNewObj) change).args : isNew ? parentContext.getNewArgs(changedObj) : null;
      Object changedObjType = DynUtil.isType(changedObj) ? changedObj.getClass() : DynUtil.getType(changedObj);
      String objTypeName = DynUtil.getTypeName(changedObjType, false);
      objName = null;

      // TODO: should we add an option to suppress redundant changes?  How can we tell when there are side effects or not?
      // if change.value != changeMap.get(prop) this guy has been been overridden.
      if (change instanceof SyncPropChange) {
         if (changeMap == null) {
            return;
         }
      }

      // What object do we need to be current
      Object newObj = changedObj;
      String newObjName = null;
      Object newObjType = DynUtil.getType(newObj);
      String newLastPackageName = changeCtx.lastPackageName;

      // When we are creating a new type, the current object is the parent of the object itself
      if (isNew) {
         SyncManager.InstInfo instInfo = parentContext.getInstInfo(changedObj);
         if (instInfo == null) {
            SyncManager.InstInfo parentInstInfo = parentContext.getInheritedInstInfo(changedObj);
            if (parentInstInfo == null) {
               System.err.println("*** Invalid sync change - no inst info");
               return;
            }
            instInfo = parentContext.createAndRegisterInheritedInstInfo(changedObj, parentInstInfo);
         }
         if (!instInfo.nameQueued)
            instInfo.nameQueued = true;
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
               // Creating a new top level object with new args.  Use the class as the context for the static variable
               else
                  newObjName = CTypeUtil.getClassName(DynUtil.getTypeName(newObjType, false));
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
            else if (DynUtil.getNumInnerTypeLevels(newObjType) == 0 || objName.startsWith("sc_type_"))
               newObjName = "";
         }
      }

      if (newObjName == null)
         newObjName = parentContext.getObjectBaseName(newObj, depChanges, this);

      if (objName == null)
         objName = newObjName;

      // First we compute the new obj name and new obj names for this change.  They are not getting applied yet though so we keep them as a copy.
      // We may need to add dependent objects first.

      StringBuilder newSB = new StringBuilder();
      StringBuilder switchSB = null;

      boolean pushName = true;
      boolean topLevel = false;
      int ix;
      int sz = currentObjNames.size();
      ArrayList<String> newObjNames = (ArrayList<String>) currentObjNames.clone();
      ArrayList<String> startObjNames = null;
      if (sz > 0) {
         ix = sz - 1;
         // Do these path names reach the same type?  If so, nothing changes
         if (DynUtil.equalObjects(getPathName(currentObjNames), newObjName)) {
            pushName = false;
            newObjNames = (ArrayList<String>) currentObjNames.clone();
         }
         else {
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
                        switchSB = new StringBuilder();
                     popCurrentObjNames(switchSB, newObjNames, sz - parIx - 1);
                     newObjName = currentChildPath;
                     break;
                  }
                  currentChildPath = CTypeUtil.getClassName(currentParent) + "." + currentChildPath;
                  currentParent = CTypeUtil.getPackageName(currentParent);
               }
               // Nothing in common so get rid of all of these names
               if (currentParent == null) {
                  if (switchSB == null)
                     switchSB = new StringBuilder();
                  popCurrentObjNames(switchSB, newObjNames, sz);
                  // This is the "object x" case with creating a new top-level object, such as an ObjectId.  We'll do object x as a top-level which is fine
                  if (isNew && !newObjName.contains(".") && (newArgs == null || newArgs.length == 0)) {
                     pushName = false; // topLevel
                     topLevel = true;
                  }
               }
            }
            else {
               if (switchSB == null)
                  switchSB = new StringBuilder();
               // Pop from sz to the one before curIx
               popCurrentObjNames(switchSB, newObjNames, sz - curIx - 1);
               pushName = false;
            }
         }
      }

      // Globally defined object?   Do we handle this case?
      if (objName == null) {
         System.err.println("*** No object name for sync operation");
      }
      else {
         String packageName = syncHandler.getPackageName();
         if (changeCtx.lastPackageName != packageName && (changeCtx.lastPackageName == null || !changeCtx.lastPackageName.equals(packageName))) {
            if (newObjNames.size() > 0)
               System.err.println("*** Mismatching packages with somehow matching types");
            if (switchSB == null)
               switchSB = new StringBuilder();
            switchSB.append("package ");
            // Need to be able to override the package with a null in the model.
            if (packageName != null)
               switchSB.append(packageName);
            switchSB.append(";\n\n");
            newLastPackageName = packageName;
         }
      }

      if (pushName && newObjName != null && newObjName.length() > 0) {
         if (switchSB == null)
            switchSB = new StringBuilder();
         int currSize = newObjNames.size();
         newObjNames.add(newObjName);
         switchSB.append(Bind.indent(currSize));
         switchSB.append(newObjName);
         switchSB.append(" {\n");
      }
      else if (sz == 0)
         topLevel = true;

      // END - set current context

      boolean newSBPushed = false;
      if (isNew) {
         newSB.append(Bind.indent(newObjNames.size()));
         if (newArgs == null || newArgs.length == 0) {
            newSB.append("object ");
            String objBaseName = CTypeUtil.getClassName(objName);
            newSB.append(objBaseName);
            newObjNames.add(objName);
            newSB.append(" extends ");
            newSB.append(objTypeName);
            newSB.append(" {\n");
            newSBPushed = true;
         }
         else {
            // When there are parameters passed to the addSyncInst call, those are the constructor parameters.
            // This means we can't use the object tag... instead we define a field with the objName in the context
            // of the parent object (so that's accessible when we call the new).   For top-level new X calls we need
            // to make them static since they are not using the outer object.
            if (DynUtil.getNumInnerObjectLevels(change.obj) == 0)
               newSB.append("static ");
            newSB.append(objTypeName);
            newSB.append(" ");
            String newVarName = CTypeUtil.getClassName(syncHandler.getObjectBaseName(depChanges, this));
            newSB.append(newVarName);
            newSB.append(" = new ");
            newSB.append(objTypeName);
            newSB.append("(");
            boolean first = true;
            StringBuilder preBlockCode = new StringBuilder();
            StringBuilder postBlockCode = new StringBuilder();
            for (Object newArg:newArgs) {
               if (!first)
                  newSB.append(", ");
               else
                  first = false;
               CharSequence res = parentContext.expressionToString(newArg, newObjNames, newLastPackageName, preBlockCode, postBlockCode, newVarName, false, "", depChanges, this);
               newSB.append(res);
            }
            // For property types like ArrayList and Map which have to execute statements at the block level to support their value.
            newSB.append(postBlockCode);
            newSB.append(");\n");

            if (preBlockCode.length() > 0) {
               StringBuilder preBuffer = new StringBuilder();
               preBuffer.append(preBlockCode);
               preBuffer.append(newSB);
               newSB = preBuffer;
            }

            if (change instanceof SyncPropChange) {
               newSB.append(Bind.indent(newObjNames.size()));
               newSB.append(newVarName);
               newSB.append(" {\n");
               startObjNames = (ArrayList<String>) newObjNames.clone();
               newObjNames.add(newVarName);
            }
         }

         // This prevents us from issuing 2 object operators in the same serialized session.  We could just call registerObjName on this
         // and avoid the extra hash map but I think there's value in being able to serlialize more than once before we mark the sync as completed.
         // If the remote side does not get data, we may need to resync with further changes, meaning we need to serialize again.
         if (createdTypes != null)
            createdTypes.add(changedObjName);
      }

      if (change instanceof SyncPropChange) {
         SyncPropChange propChange = (SyncPropChange) change;
         String propName = propChange.prop;
         if (changeMap != null && changeMap.containsKey(propName)) {
            Object propValue = changeMap.get(propName);

            StringBuilder preBlockCode = new StringBuilder();
            StringBuilder postBlockCode = new StringBuilder();
            StringBuilder statement = new StringBuilder();

            statement.append(Bind.indent(newObjNames.size()));

            if (propValue != null) {
               // Which sync context to use for managing synchronization of on-demand references for properties of this component?   When a session scoped component extends or refers to a global scoped component - e.g. EditorFrame's editorModel, typeTreeModel, etc.
               // how do we keep everything in sync?  Global scoped contexts keep the list of session contexts which are mapping them.  When session scoped extends global, it becomes session - so editorModel/typeTreeModel should be session.  But LayeredSystem,
               // Layer's etc. should be global - shared.  Information replicated into the sessionSyncContext as needed.
               // Change listeners - global or session scoped level:
               //   1) do them at the global level to synchronize event propagation to the session level using the back-event notification.  Theoretically you could generate a single serialized sync layer and broadcast it out to all listeners.  they are
               //    synchronized and seeing the same thing.  they can ignore any data they get pushed which does not match their criteria.
               //   2) NO: adding/removing/delivering events is complicated.  We need to sync the add listener with the get intitial sync value.  It's rare that one exact layer applies to everyone so that optimization won't work. - it's likely that application logic has customized it so different users see different things.  It's a security violation to
               //    violate application logic and broadcast all data to all users.
               //   ?? What about the property value listener?   That too should be done at the session level.
               //    simplify by doing event listeners, event propagation at the session scoped level always.  register instances, names etc. globally so those are all shared.
               //  Need to propagate both contexts - session and global through the createOnDemand calls
               //  Use global "new names" - never commit to registeredNewNames for global.  Instead put them into session so we keep track of what's registerd where.
               //
               // Problem with making the global objects full managed at the session level - if there are any changes at the global level which depend on the global object, it's change also must be tracked at the global level.  The depChanges takes
               // care of that now but currently we are registering the names at the session level which causes the problem.
               // What changes must be made at the global level?  Any initSyncInsts which are not on-demand and are on global scoped components.  We don't have a session scope then.
               SyncHandler valSyncHandler = parentContext.getSyncHandler(propValue);
               statement.append(valSyncHandler.getPropertyUpdateCode(changedObj, propName, propValue, parentContext.getPreviousValue(changedObj, propName), newObjNames, newLastPackageName, preBlockCode, postBlockCode, depChanges, this));
            }
            else {
               statement.append(propName);
               statement.append(" = null;\n");
            }
            statement.append(";\n");

            newSB.append(preBlockCode);
            newSB.append(statement);
            newSB.append(postBlockCode);
         }
      }
      else if (change instanceof SyncMethodCall) {
         SyncMethodCall smc = (SyncMethodCall) change;

         StringBuilder preBlockCode = new StringBuilder();
         StringBuilder postBlockCode = new StringBuilder();
         StringBuilder statement = new StringBuilder();

         int indentSize = newObjNames.size();

         statement.append(Bind.indent(indentSize));

         statement.append("Object ");
         statement.append(smc.callId);
         statement.append(" = ");
         statement.append(smc.methName);
         statement.append("(");
         if (smc.args != null) {
            int argIx = 0;
            for (Object arg:smc.args) {
               if (argIx > 0)
                  statement.append(", ");
               statement.append(parentContext.expressionToString(arg, newObjNames, newLastPackageName, preBlockCode, postBlockCode, null, false, String.valueOf(argIx), depChanges, this));
               argIx++;
            }
         }
         statement.append(");\n");

         newSB.append(preBlockCode);
         newSB.append(statement);
         newSB.append(postBlockCode);
      }
      else if (change instanceof SyncMethodResult) {
         SyncMethodResult mres = (SyncMethodResult) change;

         StringBuilder preBlockCode = new StringBuilder();
         StringBuilder postBlockCode = new StringBuilder();
         StringBuilder statement = new StringBuilder();

         int indentSize = newObjNames.size();

         statement.append(Bind.indent(indentSize + 1));
         statement.append("sc.sync.SyncManager.processMethodReturn(");
         statement.append("\"");
         statement.append(mres.callId);
         statement.append("\", ");
         statement.append(parentContext.expressionToString(mres.retValue, newObjNames, newLastPackageName, preBlockCode, postBlockCode, null, true, "", depChanges, this));
         statement.append(");\n");

         newSB.append(Bind.indent(indentSize));
         newSB.append("{\n");
         newSB.append(preBlockCode);
         newSB.append(statement);
         newSB.append(postBlockCode);
         newSB.append(Bind.indent(indentSize));
         newSB.append("}\n");
      }
      else if (change instanceof SyncFetchProperty) {
         SyncFetchProperty fetchProp = (SyncFetchProperty) change;

         int indentSize = newObjNames.size();

         newSB.append(Bind.indent(indentSize));
         // TODO: should we set the @sc.obj.Sync annotation here or is this the only reason we'd ever use override is to fetch?
         newSB.append("override ");
         newSB.append(fetchProp.propName);
         newSB.append(";\n");
      }

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

            addChangedObject(parentContext, sb, depChange, prevChange, changeCtx, createdTypes);
            prevChange = depChange;
            dix++;
         }

         // When we need to define a new field for a property change, there are two current objects - the starting one which holds the field, and then the second modify of the object that field defined
         ArrayList<String> toCompNames = startObjNames != null ? startObjNames : newObjNames;

         // The current object changed - need to reset it.  This is awkward both here and in the output probably but we are treating the dep changes as a special case.
         if (!currentObjNames.equals(toCompNames)) {
            // In this case, we just do the switch back and so do not need the original 'switch code' used to switch the types originally
            switchSB = null;
            popCurrentObjNames(sb, currentObjNames, currentObjNames.size());
            if (!DynUtil.equalObjects(origPackage, changeCtx.lastPackageName)) {
               sb.append("package ");
               if (origPackage != null)
                  sb.append(origPackage);
               sb.append(";\n\n");
            }

            ArrayList<String> toPush = toCompNames;
            if (newSBPushed) {
               toPush = new ArrayList<String>(toCompNames);
               toPush.remove(toPush.size()-1);
            }
            pushCurrentObjNames(sb, toPush, toPush.size());
         }
         else {
            // Just leave the current object since the dep set it properly for us
            if (DynUtil.equalObjects(newLastPackageName, changeCtx.lastPackageName))
               switchSB = null;
         }
      }
      // The close is kept separate - if we have to change names due to a dependent object, that will have already closed it for us.
      if (switchSB != null)
         sb.append(switchSB);

      // TODO: perhaps there won't be an object in context at this time so we need to add "SyncManager {" just so we are in the right code type
      boolean remoteChange = change.remoteChange;
      if (changeCtx.remoteChange != remoteChange) {
         changeCtx.remoteChange = remoteChange;
         appendRemoteChanges(sb, remoteChange, topLevel);
      }

      sb.append(newSB);

      currentObjNames.clear();
      currentObjNames.addAll(newObjNames);
      changeCtx.lastPackageName = newLastPackageName;
   }

   public final static String GLOBAL_TYPE_NAME = "_GLOBAL_";

   static void appendRemoteChanges(StringBuilder sb, boolean remoteChange, boolean topLevel) {
      if (topLevel) {
         sb.append(GLOBAL_TYPE_NAME);
         sb.append(" { ");
      }
      // TODO: import these things to make the strings smaller
      sb.append("{ sc.sync.SyncManager.setSyncState(");
      if (remoteChange)
         sb.append("sc.sync.SyncManager.SyncState.InitializingLocal");
      else
         sb.append("sc.sync.SyncManager.SyncState.Initializing");
      sb.append("); }");
      if (topLevel)
         sb.append("}");
      sb.append("\n");
   }

   static void pushCurrentObjNames(StringBuilder res, ArrayList<String> currentObjNames, int num) {
      String parentName = null;
      for (int i = 0; i < num; i++) {
         String objName = currentObjNames.get(i);
         if (i > 0) {
            res.append(Bind.indent(i));

            if (objName.startsWith(parentName))
               objName = objName.substring(parentName.length() + 1);
         }
         // Use the whole name when it's the first
         res.append(objName);
         if (parentName == null)
            parentName = objName;
         else
            parentName = parentName + "." + objName;
         res.append(" {\n");
      }
   }

   static void popCurrentObjNames(StringBuilder res, ArrayList<String> currentObjNames, int num) {
      for (int i = 0; i < num; i++) {
         int ix = currentObjNames.size()-1;
         if (ix > 0)
            res.append(Bind.indent(ix));
         res.append("}\n");
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
            // Skip overriden changes
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

}
