/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.bind.*;
import sc.dyn.DynUtil;
import sc.dyn.RemoteResult;
import sc.obj.*;
import sc.type.CTypeUtil;
import sc.type.PTypeUtil;
import sc.util.IdentityWrapper;
import sun.plugin.javascript.navig4.Layer;

import java.util.*;

/**
 * This is the manager class for synchronization.  A SyncManager is registered for a given destination name.  The
 * destinations are added at startup - they might be client, server, or the name of a peer process.
 * <p>
 * The SyncManager collects the type and subset of the properties of those types
 * which are to be synchronized.
 * It manages a set of SyncContext's, one for each scope.  On the client, there will only be one sync context, but
 * on the server, the system has to track changes to objects based on their lifecycle.  So global objects will broadcast
 * out changes.  Session scoped objects are only visible in the current user's session and request scoped objects last only
 * for the given request.  Each SyncContext stores the set of objects that belong in that context.
 * <p>
 * For the sync operation, the request, session, and global sync contexts are rolled up into a single layer of objects definitions
 * and modify definitions.  When the server is replying to the client, the layer gets translated to Javascript before it is sent.
 * Once on the remote side, the layer is applied.  After applying the changes, any response layer that results from the previous layer is packages up
 * and put into the reply.
 * </p>
 * The syncManager operates in two modes.  When you are updating the objects, syncInProgress is true, no changes are recorded.  Instead, we are updating the
 * previous values stored for all synchronized objects (used to revert, and know the difference between old and new).
 * <p>
 * At other times, live changes made to the objects are recorded.  We store the new values for each change by listening to the property change events.
 * </p>
 * <p>During initialization, the initial property values are synchronized to the other side if the "init" flag is true.  If it's false, we assume the
 * client was initialized with the same values.  With the automatic sync mode, this flag is set automatically by looking at the initialiers.  If both sides
 * are initialized, it's not set.
 * </p>
 */
@sc.js.JSSettings(jsModuleFile="js/sync.js", prefixAlias="sc_")
@Sync(syncMode= SyncMode.Disabled)
public class SyncManager {
   // A SyncManager manages all traffic going out of a given channel type, i.e. per SyncDestination instance.
   private static Map<String,SyncManager> syncManagersByDest = new HashMap<String,SyncManager>();

   private static HashMap<Object, Class> syncHandlerRegistry = new HashMap<Object, Class>();

   /** Traces synchronization layers between client and server for understanding and debugging client/server issues */
   public static boolean trace = false;

   /** More detailed debugging of the synchronization system - i.e. all sync types, all sync insts, all properties create or updated etc. */
   public static boolean verbose = false;

   /** When false, the trace buffer is truncated at 'logSize' for each layer */
   public static boolean traceAll = false;
   public static int logSize = 512; // limit to the size of layer defs etc. that are logged - ellipsis are inserted for characters over that amount.

   /**
    * Set this to true for the server or the sync manager which manages the initialization.  It is set to false for the client or the one who receives the initial state.
    * When the server restarts, it can rebuild the initial state automatically so this prevents the client having to send all of that info over.  It's a bit conflicting
    * to have the client define objects which are already on the server (though probably setting initialSync on both should work - so those conflicts are just managed).
    */
   public boolean recordInitial = true;

   private Map<Object, SyncProperties> syncTypes = new IdentityHashMap<Object, SyncProperties>();

   // Records the sync instances etc. that are registered in a global context.
   Map<String,SyncContext> rootContexts = new HashMap<String,SyncContext>();

   public SyncContext getRootSyncContext(String appId) {
      if (appId == null) {
         appId = "defaultSyncAppId";
      }
      String ctxId = "global"; // appId
      SyncContext rootCtx = rootContexts.get(ctxId);
      if (rootCtx == null) {
         //rootCtx = newSyncContext("global:" + appId);
         // Do not include the appId in the context so that we can mirror static state.  Now that we have window scope to store the state of each request separately I think this is not needed.
         // TODO: we have global objects stored in a per-page object variable e.g. index.body.editorMixin.editorModel and the dirEnts.  If we do not share the global context, the global object
         // state is not sent down.  The page objects which are not global, provide an indexing into the global name space when an inner object extends a global base class.
         rootCtx = newSyncContext(ctxId);
         rootCtx.scope = GlobalScopeDefinition.getGlobalScopeDefinition().getScopeContext();
         rootContexts.put(ctxId, rootCtx);
      }
      return rootCtx;
   }

   public SyncContext getCurrentRootSyncContext() {
      return getRootSyncContext(getSyncAppId());
   }

   /** Specify to sync calls for the sync group name which synchronizes all sync groups. */
   public final static String SYNC_ALL = "syncAll";

   static class InstInfo {
      Object[] args;
      boolean initDefault;
      String name;
      boolean registered;  // Has this object's name been sent to the client
      boolean nameQueued;  // Has this object's name been at least queued to send to he client
      boolean onDemand;
      boolean fixedObject;
      boolean initialized;
      boolean inherited; // Set to true when the instance in this SyncContext is inherited from the parentContext.
      SyncProperties props;
      TreeMap<String,Boolean> onDemandProps; // The list of on-demand properties which have been fetched for this instance - stores true when the prop has been initialized, false for when it's been requested before the object has been initialized.

      HashMap<String,Object> previousValues;
      HashMap<String,Object> initialValues;  // TODO: REMOVE THIS - it's not used!
      //int refcnt = 1;

      DefaultValueListener valueListener;

      InstInfo(Object[] args, boolean initDef, boolean onDemand) {
         this.args = args;
         this.initDefault = initDef;
         this.onDemand = onDemand;
      }

      boolean isFetchedOnDemand(String prop) {
         return onDemandProps != null && onDemandProps.get(prop) != null;
      }

      boolean isInitializedOnDemand(String prop) {
         return onDemandProps != null && onDemandProps.get(prop) != null && onDemandProps.get(prop);
      }

      public void addFetchedOnDemand(String propName, boolean inited) {
         if (onDemandProps == null)
            onDemandProps = new TreeMap<String,Boolean>();
         onDemandProps.put(propName, inited);
      }

      public String toString() {
         if (name != null)
            return name;
         else
            return super.toString();
      }
   }

   public SyncProperties getSyncPropertiesForInst(Object changedObj) {
      // Work around weird behavior of DynUtil.getType for TypeDeclarations - since we will be serializing them over and need to treat them like objects, not types here
      Object syncType = DynUtil.isType(changedObj) ? changedObj.getClass() : DynUtil.getType(changedObj);
      return getSyncProperties(syncType);
   }

   @Sync(syncMode= SyncMode.Disabled)
   public class SyncContext implements ScopeDestroyListener {
      String name;
      SyncContext parentContext;
      ScopeContext scope;

      protected boolean initialSync = false;  // Before we've sent our context remotely we're in the initial sync

      // The server stores around the initial sync layer so clients can refresh, the client does not have to do that.
      protected boolean needsInitialSync = true;

      // Stores the array of arguments used to construct each instance, and the marker this is a synchronized instance, to track sync instances and to avoid adding them twice.
      private IdentityHashMap<Object, InstInfo> syncInsts = new IdentityHashMap<Object, InstInfo>();

      // Stores the SyncListener objects which we use to add as listeners on each sync instance.
      Map<String,SyncChangeListener> syncListenersByGroup = new HashMap<String,SyncChangeListener>();

      // Stores the set of SyncLayers, one for each sync group which are active on this context.
      private Map<String,SyncLayer> changedLayersByGroup = new HashMap<String,SyncLayer>();

      // How many pending requests do we have right now?
      int pendingSyncs = 0;

      // When we are applying a sync from the remote side, this designates the property we are in the midst of updating.
      // It allows us to differentiate a change that's already synced (this one) from any other properties which are triggered
      // after this property is set.
      //public String pendingSyncProp;

      // The map from name to object instance for all objects we know about.
      public HashMap<String,Object> objectIndex = new HashMap<String, Object>();

      IdentityHashMap<Object, SyncListenerInfo> syncListenerInfo = new IdentityHashMap<Object, SyncListenerInfo>();

      // A table for each type name which lists the current unique integer for that type that we use to automatically generate semi-unique ids for each instance
      Map<String,Integer> typeIdCounts;

      // A table which stores the automatically assigned id for a given object instance.
      //static Map<Object,String> objectIds = (Map<Object,String>) PTypeUtil.getWeakHashMap();
      Map<Object,String> objectIds;

      // A global sync context will have child contexts which are listening for it's objects
      HashSet<SyncContext> childContexts;

      SyncLayer initialSyncLayer = new SyncLayer(this);
      {
         initialSyncLayer.initialLayer = true;
      }

      public SyncContext(String name) {
         this.name = name;
      }

      private SyncChangeListener getSyncListenerForGroup(String syncGroup) {
         SyncChangeListener syncListener = syncListenersByGroup.get(syncGroup);
         if (syncListener == null) {
            syncListener = new SyncChangeListener(syncGroup, this);
            syncListenersByGroup.put(syncGroup, syncListener);
         }
         return syncListener;
      }

      public Set<String> getSyncGroups() {
         return changedLayersByGroup.keySet();
      }

      public void addInitialValue(Object inst, InstInfo ii, String prop, Object curVal) {
         HashMap<String,Object> prevMap = ii.previousValues;
         HashMap<String,Object> initMap = ii.initialValues;

         curVal = copyMutableValue(curVal);
         if (prevMap == null) {
            prevMap = new HashMap<String,Object>();
            ii.previousValues = prevMap;
            // The first layer of changes gets saved in the initialValues map.
            if (initMap == null)
               ii.initialValues = prevMap;
         }
         Object old = prevMap.put(prop,curVal);
         if (verbose && (curVal != null || old != null)) {
            if (old == null) {
               // Filter out boolean=false to minimize message trafic.
               if (!(curVal instanceof Boolean) || ((Boolean) curVal))
                  System.out.println("Initial value: " + DynUtil.getInstanceName(inst) + "." + prop + " = " + DynUtil.getInstanceName(curVal));
            }
            else
               System.out.println("Updating initial: " + DynUtil.getInstanceName(inst) + "." + prop + " = " + DynUtil.getInstanceName(curVal) + " (from=" + DynUtil.getInstanceName(old) + ")");
         }
      }

      public Object copyMutableValue(Object val) {
         SyncState origState = getSyncState();
         try {
            // Turn off synchronization completely during the clone, not even recording the previous value here.
            // In particular Lists and HashMaps will be firing events
            // and since we may already be dispatched from an event handler, without disabling we will record this
            // as a change.   If we record the previous value we get into an infinite loop.
            setSyncState(SyncState.CopyingPrevious);
            if (val == null)
               return null;
            if (val instanceof ArrayList) {
               return ((ArrayList) val).clone();
            }
            else if (val instanceof HashMap) {
               return ((HashMap) val).clone();
            }
            else if (val instanceof HashSet) {
               return ((HashSet) val).clone();
            }
            else if (!(val instanceof Cloneable))
               return val;
            else if (PTypeUtil.isArray(val.getClass())) {
               if (val instanceof Object[])
                  return ((Object[]) val).clone();
               if (val instanceof int[])
                  return ((int[]) val).clone();
               else
                  System.err.println("*** unsupported array type in copyMutableValue");
            }
            else
               System.err.println("*** Unrecognized type for synchronized property: " + val);
         }
         finally {
            setSyncState(origState);
         }
         return val;
      }

      public void addPreviousValue(Object obj, String propName, Object val) {
         addPreviousValue(obj, getInheritedInstInfo(obj), propName, val);
      }

      public void addPreviousValue(Object obj, InstInfo ii, String propName, Object val) {
         if (ii == null) {
            System.err.println("*** No sync inst registered for: " + DynUtil.getInstanceName(obj));
            return;
         }
         HashMap<String,Object> prevMap = ii.previousValues;
         HashMap<String,Object> initMap = ii.initialValues;

         Object savedVal = copyMutableValue(val);

         // Make a copy of the initial values first time the previous value is set.  As an optimization, we share the initialValues map as the first map for previous values, then do a copy-on-write when the previous value is first changed.
         if (initMap != null && initMap == prevMap && !initialSync) {
            initMap = (HashMap<String,Object>) initMap.clone();
            ii.initialValues = initMap;
         }
         if (prevMap == null) {
            prevMap = new HashMap<String,Object>();
            ii.previousValues = prevMap;
            // The first layer of changes gets saved in the initialValues map.
            if (initMap == null)
               ii.initialValues = prevMap;
         }
         Object old = prevMap.put(propName,savedVal);
         if (verbose) {
            String updateType = prevMap == initMap ? "initial" : "original";
            if (old == null)
               System.out.println("Sync " + updateType + " value: " + DynUtil.getInstanceName(obj) + "." + propName + " = " + DynUtil.getInstanceName(val));
            else if (!DynUtil.equalObjects(val, old))
               System.out.println("Sync updating " + updateType + " value: " + DynUtil.getInstanceName(obj) + "." + propName + " = " + DynUtil.getInstanceName(val) + " (from=" + DynUtil.getInstanceName(old) + ")");
         }

         SyncProperties syncProps = ii.props == null ? getSyncPropertiesForInst(obj) : ii.props;
         SyncState state = getSyncState();
         // Needs to be added as a change to the initialSyncLayer.  For the server (recordInitial=true), this records all changes so we can do an accurate "refresh" using the initial layer.  For the client (recordInitial = false), this only
         // includes the changes made while recording changes.  These are the changes made locally on the client, the only ones we need to resync back to the server when the session is lost.
         // If ii.inherited is true, we've been propagated this event from a parent context.  Do not record it in the initial layer in this case since the initial layer is already inherited.
         if (state != SyncState.Disabled && syncProps != null && syncProps.isSynced(propName) && ((recordInitial || state == SyncState.InitializingLocal) || (state != SyncState.Initializing && state != SyncState.ApplyingChanges)) && !ii.inherited)
            initialSyncLayer.addChangedValue(obj, propName, val, recordInitial && state == SyncState.ApplyingChanges);
         //else if (trace) {
         //   System.out.println("Not sync'ing back change to: " + propName + " that is sync'd us");
         //}
      }

      public Object getPreviousValue(Object obj, String propName) {
         InstInfo ii = getInheritedInstInfo(obj);
         if (ii == null)
            return null;
         HashMap<String,Object> prevMap = ii.previousValues;
         if (prevMap == null)
            return null;
         return prevMap.get(propName);
      }

      public Object removePreviousValue(Object obj, String propName) {
         InstInfo ii = syncInsts.get(obj);
         if (ii == null)
            return null;
         HashMap<String,Object> changeMap = ii.previousValues;
         if (changeMap == null)
            return null;
         return changeMap.remove(propName);
      }

      public void addNewObj(Object obj, String syncGroup, Object...args) {

         SyncState state = getSyncState();
         if (state == SyncState.CopyingPrevious)
            return;
         if (state != SyncState.Disabled) {
            if (state == SyncState.RecordingChanges) {
               SyncLayer changedLayer = getChangedSyncLayer(syncGroup);
               // When we are in the initial sync, we are not recording these as changes - just putting them in the
               // initial layer.
               if (!initialSync)
                  changedLayer.addNewObj(obj, args);
            }
            if (recordInitial || state != SyncState.Initializing)
               initialSyncLayer.addNewObj(obj, args);
         }
      }

      public void addChangedValue(Object obj, String propName, Object val, String syncGroup) {
         SyncLayer changedLayer = getChangedSyncLayer(syncGroup);
         if (!needsSync) {
            if (verbose)
               System.out.println("needsSync=true");
            setNeedsSync(true);
         }
         if (verbose) {
            System.out.println("Changed value: " + DynUtil.getInstanceName(obj) + "." + propName + " = " + DynUtil.getInstanceName(val));
         }
         // When we are processing the initial sync, we are not recording changes.
         // TODO: do we need to record separate versions when dealing with sync contexts shared by more than one client?
         // to track versions, so we can respond to sync requests from more than one client
         if (!initialSync)
            changedLayer.addChangedValue(obj, propName, val);
         SyncState syncState = getSyncState();
         if (recordInitial || syncState != SyncState.Initializing) {
            // For the 'remote' parameter, we want it to be true only for those changes which definitively originated on the client.  The binding count test will
            // eliminate side-effect changes from those original ones.  This is the same logic we use to determine
            initialSyncLayer.addChangedValue(obj, propName, val, recordInitial && syncState == SyncState.ApplyingChanges && Bind.getNestedBindingCount() <= 1);
         }
      }

      public boolean hasChangedValue(Object obj, String propName, String syncGroup) {
         SyncLayer changedLayer = getChangedSyncLayer(syncGroup);

         return changedLayer.hasChangedValue(obj, propName);
      }

      public Object getChangedValue(Object obj, String propName, String syncGroup) {
         SyncLayer changedLayer = getChangedSyncLayer(syncGroup);

         return changedLayer.getChangedValue(obj, propName);
      }

      public Object removeChangedValue(Object obj, String propName, String syncGroup) {
         SyncLayer changedLayer = getChangedSyncLayer(syncGroup);
         return changedLayer.removeChangedValue(obj, propName);
      }

      public SyncLayer getChangedSyncLayer(String syncGroup) {
         SyncLayer syncLayer = changedLayersByGroup.get(syncGroup);
         if (syncLayer == null) {
            syncLayer = new SyncLayer(this);
            changedLayersByGroup.put(syncGroup, syncLayer);
         }
         return syncLayer;
      }

      public SyncManager getSyncManager() {
         return SyncManager.this;
      }

      public void updatePropertyValueListener(Object parentObj, String prop, Object newValue, String syncGroup) {
         removePropertyValueListener(parentObj, prop);
         addPropertyValueListener(parentObj, prop, newValue, syncGroup);
      }

      public void addPropertyValueListener(Object parentObj, String prop, Object value, String syncGroup) {
         if (value instanceof IChangeable) {
            SyncListenerInfo info = syncListenerInfo.get(parentObj);
            if (info == null) {
               info = new SyncListenerInfo();
               syncListenerInfo.put(parentObj, info);
            }
            PropertyValueListener listener = new PropertyValueListener();
            listener.ctx = this;
            listener.value = value;
            listener.syncGroup = syncGroup;
            listener.syncProp = prop;
            listener.syncObj = parentObj;
            Bind.addListener(value, null, listener, IListener.VALUE_CHANGED_MASK);
            info.valList.put(prop, listener);
         }
      }

      public void removePropertyValueListener(Object parentObj, String prop) {
         SyncListenerInfo info = syncListenerInfo.get(parentObj);
         if (info == null) {
            return;
         }
         PropertyValueListener listener = info.valList.remove(prop);
         if (listener != null) {
            Bind.removeListener(listener.value, null, listener, IListener.VALUE_CHANGED_MASK);
            if (info.valList.size() == 0)
               syncListenerInfo.remove(parentObj);
         }
      }

      public void removePropertyValueListeners(Object parentObj) {
         SyncListenerInfo info = syncListenerInfo.get(parentObj);
         if (info == null) {
            return;
         }
         for (PropertyValueListener listener:info.valList.values())
            Bind.removeListener(listener.value, null, listener, IListener.VALUE_CHANGED_MASK);
         syncListenerInfo.remove(parentObj);
      }

      public void registerObjName(Object inst, String objName, boolean fixedName, boolean initInst) {
         String scopeName = DynUtil.getScopeName(inst);
         ScopeDefinition def = getScopeDefinitionByName(scopeName);
         SyncContext useCtx = getSyncContext(def.scopeId, true);
         if (useCtx == null)
            System.err.println("Unable to resolve sync context for scope: " + scopeName + " to register inst: " + objName);
         else {
            useCtx.registerObjNameOnScope(inst, objName, fixedName, initInst, false);
            InstInfo ii = getInstInfo(inst);
            if (ii == null) {
               ii = getInheritedInstInfo(inst);
               if (ii != null)
                  ii = createAndRegisterInheritedInstInfo(inst, ii);
            }
            if (ii != null)
               ii.nameQueued = true;
         }
      }

      void registerObjNameOnScope(Object inst, String objName, boolean fixedName, boolean initInst, boolean nameQueued) {
         InstInfo ii = syncInsts.get(inst);
         if (ii == null) {
            ii = getInheritedInstInfo(inst);
            if (ii != null) {
               ii = createAndRegisterInheritedInstInfo(inst, ii);
            }
            else {
               ii = new InstInfo(null, false, false);
               syncInsts.put(inst, ii);
            }

            // When we are registering a new object on the remote side, if it's synchronized on this side, initialize it as an on-demand instance so we send the changes back.
            if (initInst) {
               SyncProperties props = getSyncPropertiesForInst(inst);
               if (props != null) {
                  ii.name = objName;
                  initOnDemandInst(inst, ii, false, false);
               }
            }
         }
         else {
            // This happens when the sync queue is disabled and the base class adds the instance with a different name.
            if (ii.name != null && !ii.name.equals(objName)) {
               if (verbose)
                  System.out.println("Re-registering " + ii.name + " as: " + objName);
            }
            if (ii.onDemand && !ii.initialized && initInst) {
               SyncProperties props = getSyncPropertiesForInst(inst);
               if (props != null) {
                  ii.name = objName;
                  initOnDemandInst(inst, ii, false, false);
               }
            }
         }
         ii.name = objName;
         ii.fixedObject = fixedName; // when true the object name is not reset
         ii.registered = nameQueued;
         ii.nameQueued = nameQueued;

         objectIndex.put(objName, inst);

         // When registering names if the other side used a type-id larger than in our current cache, we need to adjust ours to use larger ids to avoid conflicts
         // TODO: there is a race condition here - where the client and server could be allocating ids and so conflict.  This is only for the default naming system - use IObjectId
         // for cases where you need to do stuff like that.
         int usix = objName.indexOf(ID_INDEX_SEPARATOR);
         if (usix != -1) {
            String typeName = objName.substring(0, usix);
            String ctStr = objName.substring(usix + ID_INDEX_SEPARATOR.length());
            try {
               int ct = Integer.parseInt(ctStr);
               if (typeIdCounts == null)
                  typeIdCounts = new HashMap<String, Integer>();
               Integer val = typeIdCounts.get(typeName);
               if (val == null)
                  val = 0;
               if (val <= ct)
                  typeIdCounts.put(typeName, ct + 1);
            }
            catch (NumberFormatException exc) {}
         }
      }

      public boolean registerSyncInst(Object inst) {
         InstInfo ii = syncInsts.get(inst);
         if (ii == null) {
            ii = new InstInfo(null, false, false);
            ii.registered = true;
            ii.nameQueued = true;
            ii.fixedObject = true;
            syncInsts.put(inst, ii);
            return false;
         }
         else {
            ii.registered = true;
            ii.nameQueued = true;
            return true;
         }
      }

      public void resetContext() {
         for (Map.Entry<Object,InstInfo> nameEnt: syncInsts.entrySet()) {
            InstInfo ii = nameEnt.getValue();
            if (!ii.fixedObject) {
               ii.registered = false;
               ii.nameQueued = false;
            }
         }
         /*
         if (parentContext != null)
            parentContext.resetContext();
         */
      }

      public Object getObjectByName(String name, boolean unwrap) {
         Object res = objectIndex.get(name);
         if (res == null && parentContext != null)
            res = parentContext.getObjectByName(name, false);
         if (unwrap && res != null)
            res = getSyncHandler(res).restoreInstance(res);
         return res;
      }

      public Object getObjectByName(String name) {
         return getObjectByName(name, false);
      }

      public CharSequence expressionToString(Object value, ArrayList<String> currentObjNames, String currentPackageName, StringBuilder preBlockCode, StringBuilder postBlockCode, String varName, boolean inBlock, String uniqueId, List<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
         if (value == null)
            return "null";
         SyncHandler syncHandler = getSyncHandler(value);
         return syncHandler.expressionToString(currentObjNames, currentPackageName, preBlockCode, postBlockCode, varName, inBlock, uniqueId, depChanges, syncLayer);
      }

      public String getObjectName(Object changedObj, List<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
         return getObjectName(changedObj, null, true, true, depChanges, syncLayer);
      }

      private boolean isRootedObject(Object obj) {
         InstInfo ii = syncInsts.get(obj);
         if (ii != null) {
            if (ii.fixedObject)
               return true;
            // Until the name has been assigned we don't know for sure
            if (ii.name != null)
               return false;
         }

         if (DynUtil.isRootedObject(obj))
            return true;

         if (!DynUtil.isObject(obj))
            return false;

         Object outer = DynUtil.getOuterObject(obj);
         if (outer == null)
            return true;
         if (isRootedObject(outer))
            return true;
         if (parentContext != null)
            if (parentContext.isRootedObject(obj))
               return true;
         return false;
      }

      /** The SyncManager maintains the name space for the client/server interaction.  The names spaces themselves need to be synchronized.
       * Since objects have different lifecycle - scope, and can be stored in different SyncContext objects, that are layered (i.e. request, session, global scope)
       * the name search must go across those scopes.
       */
      public String getObjectName(Object changedObj, String propName, boolean create, boolean unregisteredNames, List<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
         InstInfo ii = syncInsts.get(changedObj);

         if (ii != null && ii.name != null) {
            if (!ii.initialized) {
               if (!unregisteredNames && !create) {
                  return null;
               }
               else if (create) {
                  if (!ii.inherited)
                     initOnDemandInst(changedObj, ii, false, false);
               }
            }
            if (!ii.fixedObject && !ii.nameQueued) {
               if (!unregisteredNames)
                  return null;
               else {
                  if (create) {
                     ii.nameQueued = true;
                  }
               }
            }
            return ii.name;
         }

         if (isRootedObject(changedObj)) {
            return findObjectName(changedObj);
         }

         String parentName = null;
         SyncContext parCtx = parentContext;
         while (parCtx != null && parentName == null) {
            // Here we don't want to create the object in the underlying context but do want to return even unregistered instances
            parentName = parCtx.getObjectName(changedObj, propName, false, true, null, syncLayer);
            if (parentName == null)
               parCtx = parCtx.parentContext;
         }

         // Doing this again because this might have caused us to be added indirectly and do not want to be added twice.
         ii = syncInsts.get(changedObj);
         if (ii != null && ii.name != null)
            return ii.name;

         if ((ii == null || ii.name == null) && create) {
            if (depChanges != null) {
               // If the parent has already registered this instance, we'll just add it to this sync context as an inherited object
               if (parentName != null) {
                  return registerInheritedInst(changedObj, parentName, ii);
               }
               else // Try to create it in this syncContext.
                  return createOnDemandInst(changedObj, depChanges, propName, syncLayer);
            }
            if (parentName != null) {
               return registerInheritedInst(changedObj, parentName, ii);
            }
            else {
               String objName = findObjectName(changedObj);
               if (ii == null) {
                  if (verbose)
                     System.out.println("*** No instance for: " + objName);
               }
               else
                  ii.name = objName;
               return objName;
            }
         }
         if (parentName != null) {
            if (create)
               return registerInheritedInst(changedObj, parentName, ii);
            // else - return null so that we can create this as an on-demand inst.
         }
         return null;
      }

      private String registerInheritedInst(Object changedObj, String parentName, InstInfo ii) {
         if (ii == null) {
            InstInfo parentII = parentContext.getInheritedInstInfo(changedObj);
            if (parentII != null) {
               if (!parentII.initialized)
                  parentContext.initOnDemandInst(changedObj, parentII, false, false);
               ii = createInheritedInstInfo(parentII);
            }
            else {
               ii = new InstInfo(null, false, false);
            }
            ii.name = parentName;
            syncInsts.put(changedObj, ii);
            objectIndex.put(parentName, changedObj);
         }
         else {
            ii.name = parentName;
         }
         ii.inherited = true;
         ii.nameQueued = true;
         return parentName;
      }

      public String findObjectName(Object obj) {
         InstInfo ii = syncInsts.get(obj);
         if (ii != null && ii.name != null)
            return ii.name;

         Object outer = DynUtil.getOuterObject(obj);
         if (outer == null) {
            String outerName = parentContext != null ? parentContext.findExistingName(obj) : null;
            if (outerName != null)
               return outerName;
            return DynUtil.getObjectName(obj);
         }
         // If the instance was created as an inner instance of an object, we need to use
         // the parent object's name so we can find the enclosing instance of the new guy.
         else {
            String baseName;
            boolean hasFixedId;
            if (obj instanceof IObjectId) {
               baseName = DynUtil.getInstanceId(obj);
               // Null from getObjectId means that there's no name yet - need to delay synchronizing this object
               if (baseName == null)
                  return null;
               hasFixedId = true;
            }
            else {
               baseName = CTypeUtil.getClassName(DynUtil.getTypeName(DynUtil.getSType(obj), false));
               hasFixedId = DynUtil.isObjectType(DynUtil.getSType(obj));
            }

            String outerName = findObjectName(outer);
            //if (outerName.contains("TodoList__0"))
            //   System.out.println("---");
            String objTypeName = outerName + "." + baseName;
            if (hasFixedId) {
               return objTypeName;
            }
            return findObjectId(obj, objTypeName);
         }
      }

      private final static String ID_INDEX_SEPARATOR = "__";

      public String findObjectId(Object obj, String typeName) {
         if (objectIds == null)
            objectIds = new HashMap<Object,String>();

         String objId = objectIds.get(new IdentityWrapper(obj));
         if (objId != null)
            return objId;

         String typeIdStr = ID_INDEX_SEPARATOR + getTypeIdCount(typeName);
         String id = DynUtil.cleanTypeName(typeName) + typeIdStr;
         objectIds.put(new IdentityWrapper(obj), id);
         return id;
      }

      public Integer getTypeIdCount(String typeName) {
         if (typeIdCounts == null)
            typeIdCounts = new HashMap<String,Integer>();
         Integer typeId = typeIdCounts.get(typeName);
         if (typeId == null) {
            typeIdCounts.put(typeName, 1);
            typeId = 0;
         }
         else {
            typeIdCounts.put(typeName, typeId + 1);
         }
         return typeId;
      }

      // After a successful sync, everything is registered.  Don't touch the parent context since it is shared with other clients
      public void commitNewObjNames(SyncContext clientContext) {
         if (this != clientContext)
            return;
         for (Map.Entry<Object,InstInfo> nameEnt: syncInsts.entrySet()) {
            InstInfo ii = nameEnt.getValue();
            // inherited types are not initialized so that test by itself is not good enough.  The key here is to avoid
            // marking on-demand objects as registered until we've sent their name across.  Maybe we need another flag for that?
            if (!ii.registered && ii.nameQueued)
               ii.registered = true;
         }
      }

      public SyncHandler getSyncHandler(Object obj) {
         // Workaround Javascript problems on chrome using String's class?
         if (obj instanceof String)
            return new SyncHandler(obj, this);

         boolean isType = DynUtil.isType(obj);
         Object type = isType ? obj.getClass() : DynUtil.getType(obj);
         Class handlerClass = syncHandlerRegistry.get(type);
         // For Layer objects they are not types but we do want to use Layer.class to find the handler
         if (handlerClass == null && !isType) {
            handlerClass = syncHandlerRegistry.get(obj.getClass());
         }

         if (handlerClass == null) {
            return new SyncHandler(obj, this);
         }
         else
            return (SyncHandler) DynUtil.createInstance(handlerClass, "Ljava/lang/Object;Lsc/sync/SyncManager$SyncContext;", obj, this);
      }

      boolean needsSync = false;

      public void setNeedsSync(boolean newNeedsSync) {
         this.needsSync = newNeedsSync;
      }

      public boolean getNeedsSync() {
         return needsSync;
      }

      public void completeSync(SyncContext clientContext, boolean error) {
         if (pendingSyncs == 0)
            System.err.println("*** unmatched complete sync call");
         else {
            --pendingSyncs;
            if (pendingSyncs == 0) {
               commitNewObjNames(clientContext);
               needsSync = false;  // TODO: should this be done as soon as we've sent a commit when there are no more unsent commits?
            }
         }
      }

      public void markSyncPending() {
         pendingSyncs++;
      }

      /**
       * This method gets called with a list of changes that were successfully applied by the other side.  It essentially commits
       * these changes by populating the previousValues map - our record of the state of the objects on the other side.
       */
      public void applyRemoteChanges(Object inst, Map<String,Object> pendingMap) {
         InstInfo ii = findSyncInstInfo(inst);

         HashMap<String,Object> prevMap = ii.previousValues;
         HashMap<String,Object> initMap = ii.initialValues;

         // This is done lazily just as a performance optimization.   Many objects will never be changed so why have two HashMap's that keep parallel info in that case.  We do the simple copy-on-write here.  Waiting as long as we can - the first time we update the previous values from a different transaction.
         if (initMap == prevMap && prevMap != null) {
            initMap = (HashMap<String,Object>) prevMap.clone();
            ii.initialValues = initMap;
         }

         if (prevMap == null)
            ii.previousValues = prevMap = new HashMap<String,Object>();

         //if (trace) {
            //System.out.println("Applying: " + pendingMap.size() + " remote changes to: " + DynUtil.getInstanceName(inst));
         //}

         for (Map.Entry<String,Object> pendingPropEnt:pendingMap.entrySet()) {
            String propName = pendingPropEnt.getKey();
            Object propVal = pendingPropEnt.getValue();
            prevMap.put(propName, propVal);

            //if (trace) {
            //   System.out.println("  " + propName + " = " + DynUtil.getInstanceName(propVal));
            //}
         }
      }

      private boolean changedInitValue(HashMap<String,Object> initMap, String propName, Object propVal) {
         boolean initValSet = initMap.containsKey(propName);
         Object initVal = initMap.get(propName);
         return (!initValSet || (initVal != propVal && (initVal == null || !DynUtil.equalObjects(initVal, propVal))));
      }

      public SyncLayer getInitialSyncLayer() {
         return initialSyncLayer;
         /*
         for (IdentityWrapper wrap:syncInsts) {
            Object inst = wrap.wrapped;
            HashMap<String,Object> prevMap = previousValues.get(inst);
            HashMap<String,Object> initMap = initialValues.get(inst);

            for (SyncLayer changeLayer:changedLayersByGroup.values()) {
               Map<Object,HashMap<String,Object>> changesMap = changeLayer.getChangedValues();
               HashMap<String,Object> instChanges = changesMap.get(inst);

               if (instChanges != null) {
                  for (Map.Entry<String,Object> changeEnt:instChanges.entrySet()) {
                     String propName = changeEnt.getKey();
                     Object propVal = changeEnt.getValue();

                     if (changedInitValue(initMap, propName, propVal)) {
                        syncLayer.addChangedValue(inst, propName, propVal);
                     }
                  }
               }
            }

            if (prevMap != null) {
               for (Map.Entry<String,Object> prevEnt:prevMap.entrySet()) {
                  String propName = prevEnt.getKey();
                  Object propVal = prevEnt.getValue();

                  // If this value is changed already here, that value override this one.
                  if (!syncLayer.hasChangedValue(inst, propName)) {

                     if (changedInitValue(initMap, propName, propVal)) {
                        syncLayer.addChangedValue(inst, propName, propVal);
                     }
                  }
               }
            }
         }
         return syncLayer;
         */
      }

      public boolean isNewInstance(Object changedObj) {
         InstInfo ii = syncInsts.get(changedObj);
         if (ii != null)
            return !ii.registered && !ii.fixedObject;
         if (parentContext != null && parentContext.isNewInstance(changedObj))
            return true;

         // For calling remote methods, we need to support objects which are not synhronized - at least calls to rooted objects.  Those are never new anyway.
         return !isRootedObject(changedObj);
      }

      public ScopeContext getScopeContext() {
         return scope;
      }

      public boolean hasSyncInst(Object inst) {
         return syncInsts.get(inst) != null;
      }

      void clearSyncInst(Object inst, SyncProperties syncProps) {
         if (syncInsts.get(inst) != null) {
            removeSyncInst(inst, syncProps);
         }
         if (parentContext != null)
            parentContext.clearSyncInst(inst, syncProps);
      }

      /**
       * This method is called with the ScopeContext selected already for scopeId.
       * It's possible the parent context has already registered this instance due to a super-type call to addSyncInst (when queeuing is disabled).
       * We'll add the instance to this sync context, then unless it's on-demand, we initialize it in all child-contexts so they sync it as well.
       */
      public void addSyncInst(Object inst, boolean initInst, boolean onDemand, boolean initDefault, boolean queueNewObj, boolean addPropChanges, int scopeId, SyncProperties syncProps, Object...args) {
         // We may have already registered this object with a parent scope cause the outer instance was not set when the object was created.
         if (parentContext != null)
            parentContext.clearSyncInst(inst, syncProps);

         InstInfo ii = syncInsts.get(inst);
         if (ii == null) {
            ii = new InstInfo(args, initDefault, onDemand);
            syncInsts.put(inst, ii);
         }
         else {
            ii = new InstInfo(args, initDefault, onDemand);
            if (ii.args == null && args != null && args.length > 0)
               ii.args = args;
            ii.initDefault = ii.initDefault || initDefault;
            ii.onDemand = ii.onDemand || onDemand;
            ii.inherited = false;
         }
         ii.props = syncProps;

         if (!ii.initialized) {
            if (initInst) {
               initSyncInst(inst, ii, initDefault, scopeId, syncProps, args, queueNewObj, false, addPropChanges, true);
            }
            // On-demand objects still need to have their names registered.  When the client needs to resync, it will try to set on-demand objects
            // which it has loaded but the server has not yet initialized.  We need the name at least to be able to find the instance and then create it on the fly.
            else if (ii.name == null && onDemand && needsInitialSync) {
               registerSyncInstName(inst, ii, scopeId, syncProps, false);
            }
         }

         // When adding a new global object which is not marked as on-demand, go and add it to all of the child contexts.  This is the "push new record" operation and so must be synchronized against adding new sessions (though maybe change this to an
         if (!onDemand && childContexts != null) {
            synchronized (this) {
               for (SyncContext childCtx:childContexts) {
                  InstInfo oldII = childCtx.syncInsts.get(inst);
                  if (oldII != null) {
                     // Update the args - from updateRuntimeType we will first register the name, then call the addSyncInst with the args by then, we've already
                     // created the child type.
                     if (args != null)
                        oldII.args = args;
                     if (!oldII.nameQueued)
                        oldII.nameQueued = true;
                     continue;
                  }
                  if (syncProps.broadcast || childCtx.scope.isCurrent()) {
                     InstInfo childII = childCtx.createAndRegisterInheritedInstInfo(inst, ii);
                     childII.nameQueued = true;
                  }
               }
            }
         }
      }

      private void registerSyncInstName(Object inst,  InstInfo ii, int scopeId, SyncProperties syncProps, boolean queueNewObj) {
         String syncType = null;
         String objName = null;
         objName = ii.name == null ? findObjectName(inst) : ii.name;

         // IObjectId can return null indicating the name is not available yet.  For on-demand objects, this means we can't sync them until they've
         // been registered which could be a problem for updating those instances lazily after a restart.  It's best to have the id defined
         // when the first addSyncInst call is made.
         if (objName == null)
            return;

         if (!isRootedObject(inst)) {
            ii.name = objName;
            // States disabled, or applying changes any new instances are automatically registered.  When initializing, it's registered only if it's the direct initializer, not part of a binding (that might not run on the other side)
            // SyncState state = getSyncState();
            //if (state != SyncState.RecordingChanges && (state != SyncState.Initializing || Bind.getNestedBindingCount() <= 1)) {
            //   ii.registered = true;
            //   syncType = " registered class, ";
            //}
            //else {
               syncType = " new class, ";
            //}
            if (queueNewObj) {
               addNewObj(inst, syncProps.syncGroup, ii.args);
               ii.nameQueued = true;
            }
         }
         else {
            ii.registered = true;
            ii.nameQueued = true;
            ii.fixedObject = true;
            ii.name = objName;
            syncType = " object ";
         }
         objectIndex.put(objName, inst);

         if (verbose) {
            System.out.println("Synchronizing instance: " + objName + syncType + (scopeId != 0 ? "scope: " + ScopeDefinition.getScopeDefinition(scopeId).name : "global scope") + ", properties: " + syncProps);
         }
      }

      public InstInfo createAndRegisterInheritedInstInfo(Object inst, InstInfo ii) {
         InstInfo childInstInfo = createInheritedInstInfo(ii);
         syncInsts.put(inst, childInstInfo);
         if (ii.name != null) {
            objectIndex.put(ii.name, inst);
         }
         return childInstInfo;
      }

      /** Initializes the instance.  If inherited is true, this instance is stored in a lower-level scope (e.g. we are initializing an instance for a session that's inited already at the global level)
       *   If queueNewObj is true, we add  */
      public void initSyncInst(Object inst, InstInfo ii, boolean initDefault, int scopeId, SyncProperties syncProps, Object[] args, boolean queueNewObj, boolean inherited, boolean addPropChanges, boolean initial) {
         Object[] props = syncProps == null ? null : syncProps.getSyncProperties();

         if (initial) {
            if (!inherited) {
               ii.initialized = true;
               registerSyncInstName(inst, ii, scopeId, syncProps, queueNewObj);
            }
            else {
               InstInfo curII = getInstInfo(inst);
               if (curII == null) {
                 // Make a copy of the inst info for this sync context.
                  ii = createAndRegisterInheritedInstInfo(inst, ii);
               }
               else
                  ii = curII;
               ii.nameQueued = queueNewObj;

               // Need to mark this initialized in this context.  We do not add the listeners when inherited but we do add the initial properties the first time it's accessed in the child scope.
               ii.initialized = true;
            }
         }
         // The replaceInstance operation
         else {
            if (ii.name != null)
               objectIndex.put(ii.name, inst);
            syncInsts.put(inst, ii);
         }

         // If the instance sends a "default change event" - i.e. prop = null, it's a signal that its properties have changed so add the listener for that event.
         if (inst instanceof IChangeable && syncProps != null && !ii.inherited) {
            DefaultValueListener listener = new DefaultValueListener();
            listener.ctx = this;
            listener.syncGroup = syncProps.syncGroup;
            listener.syncObj = inst;
            Bind.addListener(inst, null, listener, IListener.VALUE_CHANGED_MASK);
            ii.valueListener = listener;
         }

         if (props != null) {
            SyncChangeListener syncListener = getSyncListenerForGroup(syncProps.syncGroup);
            for (Object prop:props) {
               String propName;
               int flags;
               if (prop instanceof SyncPropOptions) {
                  SyncPropOptions syncProp = (SyncPropOptions) prop;
                  propName = syncProp.propName;
                  flags = syncProp.flags;
               }
               else {
                  propName = (String) prop;
                  flags = initDefault ? SyncPropOptions.SYNC_INIT : 0;
               }

               if ((flags & SyncPropOptions.SYNC_ON_DEMAND) == 0 || ii.isFetchedOnDemand(propName)) {
                  initProperty(inst, ii, propName, flags, inherited, syncProps, addPropChanges, syncListener, initial);
               }
            }
         }

      }

      /**
       * Call this to pull across a specific property's value from the remote side.  Useful both to initially fetch the
       * value for an on-demand property and also to refresh the value of a property sync'd normally.
       */
      public void fetchProperty(Object inst, String propName) {
         SyncProperties syncProps = getSyncPropertiesForInst(inst);
         if (syncProps == null) {
            System.err.println("*** Type for instance: " + DynUtil.getInstanceName(inst) + " not added to the SyncManager with addSyncType");
            return;
         }
         SyncLayer useLayer = getChangedSyncLayer(syncProps.syncGroup);
         useLayer.addFetchProperty(inst, propName);

         if (!needsSync) {
            setNeedsSync(true);
         }
      }

      /**
       * This method initializes an individual sync property which was initially defined to be an on-demand property.
       * You call this on the server (through SyncManager) to push this property to the client along with the current sync.
       * To pull across a property on the client, use fetchProperty(Object inst, String propName).
       */
      public void initProperty(Object inst, String propName) {
         updateProperty(inst, propName, true, false);
      }

      /**
       * This method lets you initialize a given property for synchronization on either the client or the server.
       * For the server, it adds that property to the synchronized set.  For the client, it initiates a "fetchProperty"
       * operator to get that property in the next sync.
       */
      public void startSync(Object inst, String propName) {
         SyncProperties syncProps = getSyncPropertiesForInst(inst);
         if (syncProps == null) {
            System.err.println("*** SyncManager.startSync: Type for instance: " + DynUtil.getInstanceName(inst) + " not added to the SyncManager with addSyncType");
            return;
         }
         int propOptions = syncProps.getSyncFlags(propName);
         if (propOptions == -1) {
            System.err.println("*** SyncManager.startSync: Property: " + propName + " for instance: " + DynUtil.getInstanceName(inst) + " not marked as synchronized in the addSyncType call.");
            return;
         }

         // If this property is not explicitly marked as either the client or the server, use the default for the destination - i.e. where the server is the web server, the browser is the client.
         // Ultimately we could make that assumption but it seems useful in the long term to allow the server to lazily pull on-demand properties from the client in the real time case.   Perhaps rare
         // in the browser/server case but maybe less so when sync is used for peer-to-peer.
         if ((propOptions & (SyncPropOptions.SYNC_SERVER | SyncPropOptions.SYNC_CLIENT)) == 0)
            propOptions = syncDestination.getDefaultSyncPropOptions();

         if ((propOptions & SyncPropOptions.SYNC_CLIENT) != 0) {
            fetchProperty(inst, propName);
         }
         else if ((propOptions & SyncPropOptions.SYNC_SERVER) != 0) {
            initProperty(inst, propName);
         }
         else
            throw new UnsupportedOperationException();
      }

      /** Performs an update of the sync property specified.  It will initialize the property if necessary and force an update, even if the value has not changed, if forceUpdate is true. */
      public void updateProperty(Object inst, String propName, boolean initOnly, boolean forceUpdate) {
         SyncProperties syncProps = getSyncPropertiesForInst(inst);
         if (syncProps == null) {
            System.err.println("*** Type for instance: " + DynUtil.getInstanceName(inst) + " not added to the SyncManager with addSyncType");
            return;
         }
         int flags = syncProps.getSyncFlags(propName);
         if (flags == -1) {
            System.err.println("*** Property: " + propName + " for instance: " + DynUtil.getInstanceName(inst) + " not added as a synchronized property to the SyncManager with addSyncType");
            return;
         }
         // This property gets initialized when the instance is added anyway so don't duplicate it here
         if ((flags & SyncPropOptions.SYNC_ON_DEMAND) != 0) {
            InstInfo ii = getInheritedInstInfo(inst);
            if (ii == null) {
               System.err.println("*** Unable to init sync property for instance which is not registered yet with the sync system: " + DynUtil.getInstanceName(inst));
               return;
            }
            // The instance has to be initialized.  for us to init the property.  It may be an on-demand instance which we have not gotten to yet.
            // If this object is initialized and initProperty adds the change, we are also done.  Otherwise, we may need to record the update.
            if (ii.initialized) {
               if (initProperty(inst, ii, propName, flags, false, syncProps, initOnly, getSyncListenerForGroup(syncProps.syncGroup), true))
                  return;
            }
            else {
               // Just mark that this property is fetched for this instance.  Later when we do initialize the instance we'll serialize it over
               ii.addFetchedOnDemand(propName, false);
            }
         }

         // Just initializing this property, not updating it.
         if (initOnly)
            return;

         Object curValue = DynUtil.getPropertyValue(inst, propName);

         if (forceUpdate || !DynUtil.equalObjects(getPreviousValue(inst, propName),curValue))
            addChangedValue(inst, propName, curValue, syncProps.syncGroup);
      }

      private boolean initProperty(Object inst, InstInfo ii, String propName, int flags, boolean inherited, SyncProperties syncProps, boolean addPropChanges, SyncChangeListener syncListener, boolean initial) {
         boolean changeAdded = false;

         try {
            if ((flags & SyncPropOptions.SYNC_ON_DEMAND) != 0 && initial) {
               // Already initialized - but could have been when the instance itself was not initialized so we still initializa it here.
               if (!ii.isInitializedOnDemand(propName)) {
                  if (addPropChanges) {
                     // Mark it as initialized now
                     ii.addFetchedOnDemand(propName, true);
                  }
               }
               // Already initialized this on-demand property.  The other properties track the initialized state of the instance.
               else
                  return false;
            }

            // Inherited instances will get the changes propagated from the shared sync context so don't listen themselves
            if (!inherited && !syncProps.constant)
               Bind.addListener(inst, propName, syncListener, IListener.VALUE_CHANGED_MASK);

            Object curVal = DynUtil.getPropertyValue(inst, propName);

            if (initial)
               addInitialValue(inst, ii,  propName, curVal);

            // On refresh, the inital sync layer will already have the change in the right order - if we re-add it, we mess up the order
            if (addPropChanges && (flags & SyncPropOptions.SYNC_INIT) != 0 && curVal != null) {
               SyncLayer toUse = null;

               // On the server, on demand properties, when initialized act just like regular property changes - i.e. must go into the init layer and the change layer.
               // On the client though, we do not want to record these initial changes in any case.
               if (initial && needsInitialSync && ((flags & SyncPropOptions.SYNC_ON_DEMAND) != 0 || ii.onDemand)) {
                  addChangedValue(inst, propName, curVal, syncProps.syncGroup);
               }
               else {
                  if (initialSync && initial)
                     toUse = initialSyncLayer;
                     // The client does not have to record any initial changes since we do not have to refresh the server from the client
                  else if (needsInitialSync) {
                     toUse = getChangedSyncLayer(syncProps.syncGroup);
                  }

                  if (toUse != null && !toUse.hasExactChangedValue(inst, propName, curVal)) {
                     toUse.addChangedValue(inst, propName, curVal);
                     changeAdded = true;
                  }
               }
            }

            if (!inherited && !syncProps.constant) {
               // Now add the listener for changes made to the property value
               addPropertyValueListener(inst, propName, curVal, syncProps.syncGroup);
            }
         }
         catch (IllegalArgumentException exc) {
            System.err.println("*** Sync property: " + propName + " not found for instance: " + DynUtil.getInstanceName(inst) + ": " + exc);
         }
         return changeAdded;
      }

      public SyncAction getSyncAction(String actionProp) {
         SyncState state = getSyncState();
         switch (state) {
            case Initializing:
            case InitializingLocal:
            case ApplyingChanges:
               // From the setX call we make directly when applying the sync, there's a sendEvent to call the sync handler - one level of nesting.  Be careful to reset the nested count if we ever need to invoke this sync process itself from
               // some binding.
               //if (DynUtil.equalObjects(actionProp, pendingSyncProp))
               if (Bind.getNestedBindingCount() <= 1)
                  return SyncAction.Previous;
               return SyncAction.Value;
            case Disabled:
               return SyncAction.Previous;
            case CopyingPrevious:
               return SyncAction.Ignore;
            case RecordingChanges:
               return SyncAction.Value;
            default:
               throw new UnsupportedOperationException();
         }
      }


      public void recordChange(Object syncObj, String syncProp, Object value, String syncGroup) {
         SyncAction action = getSyncAction(syncProp);
         switch (action) {
            case Previous:
               addPreviousValue(syncObj, syncProp, value);
               break;
            case Value:
               addChangedValue(syncObj, syncProp, value, syncGroup);
               break;
            case Ignore:
               break;
         }

         if (childContexts != null) {
            synchronized (this) {
               for (SyncContext childCtx:childContexts) {
                  if (childCtx.syncInsts.get(syncObj) != null)
                     childCtx.recordChange(syncObj, syncProp, value, syncGroup);
               }
            }
         }
      }

      public String getObjectBaseName(Object obj, List<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
         String objName = getObjectName(obj, depChanges, syncLayer);
         int numLevels = DynUtil.getNumInnerTypeLevels(DynUtil.getType(obj));
         int numObjLevels = DynUtil.getNumInnerObjectLevels(obj);
         numLevels = Math.max(numLevels, numObjLevels);
         String path =  CTypeUtil.getClassName(objName);
         String root = CTypeUtil.getPackageName(objName);
         if (root != null) {
            while (numLevels > 0) {
               numLevels -= 1;
               path = CTypeUtil.getClassName(root) + "." + path;
               root = CTypeUtil.getPackageName(root);
               if (root == null)
                  break;
            }
         }
         return path;
      }

      /** Returns the arguments and other instance info for an on-demand object, or returns null if this instance is not an on-demand instance.  This is used for
       * instances which have addSyncInst calls made during code-gen time but are marked as "on-demand".  Other instances may only be registered by calling addSyncType but are also
       * processed as on-demand.
       */
      public InstInfo getInstInfo(Object changedObj) {
         InstInfo ii = syncInsts.get(changedObj);
         return ii;
      }

      public InstInfo findSyncInstInfo(Object changedObj) {
         InstInfo ii = syncInsts.get(changedObj);
         if (ii != null)
            return ii;
         if (parentContext != null)
            return parentContext.findSyncInstInfo(changedObj);
         return null;
      }

      public String findExistingName(Object changedObj) {
         InstInfo ii = syncInsts.get(changedObj);
         if (ii != null && ii.name != null)
            return ii.name;
         if (parentContext != null)
            return parentContext.findExistingName(changedObj);
         return null;
      }

      public void addDepNewObj(List<SyncLayer.SyncChange> depChanges, Object changedObj, InstInfo instInfo, boolean inherited, boolean queueObj) {
         if (!instInfo.fixedObject) {
            if (queueObj)
               SyncLayer.addDepNewObj(depChanges, changedObj, instInfo.args);
         }
         initOnDemandInst(changedObj, instInfo, inherited, true);
      }

      public void initOnDemandInst(Object changedObj, InstInfo instInfo, boolean inherited, boolean addPropChanges) {
         SyncProperties props = getSyncPropertiesForInst(changedObj);
         initSyncInst(changedObj, instInfo, instInfo == null ? props == null ? false : props.initDefault : instInfo.initDefault, scope == null ? 0 : scope.getScopeDefinition().scopeId, props, instInfo == null ? null : instInfo.args, false, inherited, addPropChanges, true);
      }

      public RemoteResult invokeRemote(String syncGroup, Object obj, String methName, Object[] args) {
         SyncLayer changedLayer = getChangedSyncLayer(syncGroup);
         if (!needsSync) {
            setNeedsSync(true);
         }
         if (trace) {
            System.out.println("Remote method call: " + DynUtil.getInstanceName(obj) + "." + methName + "(" + DynUtil.arrayToInstanceName(args) + ")");
         }
         return changedLayer.invokeRemote(obj, methName, args);
      }

      public void addMethodResult(Object ctxObj, String objName, Object retValue) {
         SyncLayer changedLayer = getChangedSyncLayer(null);
         changedLayer.addMethodResult(ctxObj, objName, retValue);
      }

      public void setInitialSync(boolean value) {
         initialSync = value;
         // TODO: this is not thread-safe as we could be sharing this parent context.
         // I think in general it only affects the case where we are modifying either the session or global context from the request which is hopefully not a common case.  We should technically synchronize against writes though to the global context so we can update "the initial" global sync layer which all clients are updated with.  We also may need to init the global context in a synchronous way the first time, if this affects any on-demand objects?
         if (parentContext != null)
            parentContext.setInitialSync(value);
      }

      public InstInfo getInheritedInstInfo(Object changedObj) {
         SyncManager.InstInfo ii = getInstInfo(changedObj);
         SyncContext parCtx = parentContext;
         // Need to find which context the referenced object is defined in.  Any dependent objects
         // need to be added to that context so that we can resolve them in nice dependent order in that context's changed list.
         while (ii == null && parCtx != null) {
            ii = parCtx.getInstInfo(changedObj);
            if (ii != null)
               break;
            parCtx = parCtx.parentContext;
         }
         return ii;
      }

      private InstInfo createInheritedInstInfo(InstInfo ii) {
         InstInfo newInstInfo = new InstInfo(ii.args, ii.initDefault, ii.onDemand);
         newInstInfo.name = ii.name;
         newInstInfo.fixedObject = ii.fixedObject;
         // TODO: Do we reuse the change maps or should we clone them so we can track the changes for each session, etc.
         newInstInfo.initialValues = ii.initialValues != null ? (HashMap<String,Object>) ii.initialValues.clone() : null;
         newInstInfo.previousValues = ii.previousValues != null ? (HashMap<String,Object>) ii.previousValues.clone() : null;

         newInstInfo.inherited = true;
         if (ii.onDemandProps != null)
            newInstInfo.onDemandProps = (TreeMap<String,Boolean>) ii.onDemandProps.clone();
         //newInstInfo.nameQueued = true;
         newInstInfo.props = ii.props;
         return newInstInfo;
      }

      public String createOnDemandInst(Object changedObj, List<SyncLayer.SyncChange> depChanges, String varName, SyncLayer syncLayer) {
         SyncManager.InstInfo ii = getInstInfo(changedObj);
         SyncManager.SyncContext parent = this.parentContext;
         SyncManager.SyncContext curContext = this;
         // Need to find which context the referenced object is defined in.  Any dependent objects
         // need to be added to that context so that we can resolve them in nice dependent order in that context's changed list.
         while (ii == null && parent != null) {
            ii = parent.getInstInfo(changedObj);
            if (ii != null) {
               curContext = parent;

               if (!ii.initialized)
                  curContext.initOnDemandInst(changedObj, ii, false, false);

               break;
            }
            parent = parent.parentContext;
         }
         if (ii != null) { // We have a declared on-demand instance - i.e. where addSyncInst was called in the code-generated for the class with onDemand=true
            SyncContext instCtx = syncLayer.initialLayer ? syncLayer.syncContext : this;
            InstInfo curInstInfo;
            // For the initial layer we store the nameQueued and use the inst info associated with the originating context (e.g. session or global).  That's because
            // this state is actually inherited by all windows, including the change list etc.  So when it's queued in the initial context, it's available to
            // all windows that come along.  But if we are using a changeLayer accumulated for a specific window in a session, we have to scope that to the
            // window always, since that info is only ever distributed to this window.
            if (instCtx != curContext) {
               curInstInfo = instCtx.getInstInfo(changedObj);
            }
            else
               curInstInfo = ii;
            addDepNewObj(depChanges, changedObj, ii, curContext != this || ii.inherited, curInstInfo == null || !curInstInfo.nameQueued);

            if (curInstInfo == null) {
               curInstInfo = instCtx.getInstInfo(changedObj);
               if (curInstInfo == null)
                  curInstInfo = instCtx.createAndRegisterInheritedInstInfo(changedObj, ii);
            }
            curInstInfo.nameQueued = true;

            // Always need to make sure the window ctx is marked with the queued flag
            if (instCtx != this) {
               InstInfo thisInfo = getInstInfo(changedObj);
               if (!thisInfo.nameQueued)
                  thisInfo.nameQueued = true;
            }

            String objName = getObjectName(changedObj, varName, false, false, null, syncLayer);
            if (objName != null)
               return objName;
            else
               System.err.println("*** On demand instance failed to add itself");
         }
         else { // lazy on-demand inst - addSyncType was called but addSyncInst was not called.  This makes things easier for integration cases where we can't do code-gen.  The addSyncType can specify the on-demand flag itself though so it's not that big of a deal.
            SyncProperties props = getSyncManager().getSyncPropertiesForInst(changedObj);
            // TODO: add an option here to turn on or off the "on-demand" sync behavior during addSyncType
            if (props != null) {
               int newScopeId = props.defaultScopeId == -1 ? scope.getScopeDefinition().scopeId : props.defaultScopeId;
               SyncContext newCtx;
               if (newScopeId != curContext.scope.getScopeDefinition().scopeId) {
                  newCtx = curContext;
                  while (newCtx != null && newCtx.scope.getScopeDefinition().scopeId != newScopeId)
                     newCtx = newCtx.parentContext;
                  if (newCtx == null) {
                     System.err.println("*** Unable to find SyncContext with scopeId: " + newScopeId + " for on-demand instance creation of: " + DynUtil.getInstanceName(changedObj));
                     return null;
                  }
               }
               else
                  newCtx = curContext;
               newCtx.addSyncInst(changedObj, true, true, props.initDefault, false, newCtx == curContext, newScopeId, props);
               // If we just added the object to a shared sync context, we still need to initialize it here as inherited.
               if (newCtx != this) {
                  ii = newCtx.getInstInfo(changedObj);
                  addDepNewObj(depChanges, changedObj, ii, true, !ii.nameQueued);
                  // Mark this as queued here in this context
                  InstInfo thisCtx = getInstInfo(changedObj);
                  thisCtx.nameQueued = true;
               }
               // If this context is the owner we've already added everything - just need to record the newObj in the changes.
               else {
                  ii = getInheritedInstInfo(changedObj);
                  ii.nameQueued = true;
                  if (!ii.fixedObject)
                     SyncLayer.addDepNewObj(depChanges, changedObj, ii.args);
               }
               return getObjectName(changedObj, varName, false, false, null, syncLayer);
            }
            // Could handle this else case by just creating the sync properties on the fly.  That would need some limitation though
            // since we don't want to accidentally serialize any types to the client.  Only those that are declarared to be shared via a
            // particular destination should go.
            else
               System.err.println("*** Error: unable to synchronize object reference to object: " + DynUtil.getInstanceId(changedObj) + ": value will be null on the other side");
         }
         return null;
      }

      public void printAllSyncInsts() {
         System.out.println("Printing all synchronized instances for scope: " + scope);

         for (Map.Entry<Object,InstInfo> instEnt:syncInsts.entrySet()) {
            Object inst = instEnt.getKey();
            System.out.println("  " + DynUtil.getInstanceName(inst));
         }

         for (Map.Entry<Object,SyncListenerInfo> syncValue:syncListenerInfo.entrySet()) {
            Object inst = syncValue.getKey();
            SyncListenerInfo sli = syncValue.getValue();
            int i = 0;
            System.out.println("Sync value listeners on properties for: " + findObjectName(inst));
            for (Map.Entry<String,PropertyValueListener> syncProp:sli.valList.entrySet()) {
               if (i != 0)
                  System.out.print(", ");
               System.out.print(syncProp.getKey());
               i++;
            }
            System.out.println();
         }


         if (parentContext != null)
            parentContext.printAllSyncInsts();
      }

      void removeSyncInstInternal(InstInfo toRemove, Object inst, SyncProperties syncProps, boolean listenersOnly) {
         if (trace)
            System.out.println("Removing sync inst: " + DynUtil.getInstanceName(inst));

         // We only add the listeners on the original instance subscription.  So when removing an inherited instance don't remove the listeners
         if (!toRemove.inherited && toRemove.initialized) {
            SyncChangeListener syncListener = getSyncListenerForGroup(syncProps.syncGroup);
            Object[] props = syncProps.getSyncProperties();
            if (props != null) {
               for (Object prop:props) {
                  String propName;
                  int flags;
                  if (prop instanceof SyncPropOptions) {
                     SyncPropOptions propOpts = (SyncPropOptions) prop;
                     propName = propOpts.propName;
                     flags = propOpts.flags;
                  } else {
                     propName = (String) prop;
                     flags = syncProps.getSyncFlags(propName);
                  }
                  if ((flags & SyncPropOptions.SYNC_ON_DEMAND) == 0 || toRemove.isFetchedOnDemand(propName))
                     Bind.removeListener(inst, propName, syncListener, IListener.VALUE_CHANGED_MASK);
               }
            }
            removePropertyValueListeners(inst);
         }
         if (!listenersOnly) {
            if (objectIds != null)
               objectIds.remove(inst);
            SyncLayer changedLayer = getChangedSyncLayer(syncProps.syncGroup);
            changedLayer.removeSyncInst(inst);
         }

         if (toRemove.valueListener != null) {
            Bind.removeListener(inst, null, toRemove.valueListener, IListener.VALUE_CHANGED_MASK);
            toRemove.valueListener = null;
         }
      }

      void removeSyncInst(Object inst, SyncProperties syncProps) {
         InstInfo toRemove = syncInsts.remove(inst);
         if (toRemove == null) {
            System.err.println("*** Unable to find sync inst to remove: " + DynUtil.getInstanceName(inst));
         }
         else
            removeSyncInstInternal(toRemove, inst, syncProps, false);
      }

      void replaceSyncInst(Object fromInst, Object toInst, SyncProperties syncProps) {
         InstInfo toRemove = syncInsts.remove(fromInst);
         if (toRemove == null) {
            return;
         }
         // Remove just the listeners.  Keep the changes in place so we can detect changed values when we add the new instance
         removeSyncInstInternal(toRemove, fromInst, syncProps, true);

         initSyncInst(toInst, toRemove, syncProps.initDefault, syncProps.defaultScopeId, syncProps, toRemove.args, false, toRemove.inherited, true, false);
      }

      synchronized void addChildContext(SyncContext childCtx) {
         if (childContexts == null)
            childContexts = new HashSet<SyncContext>();
         childContexts.add(childCtx);
      }

      public synchronized void scopeDestroyed(ScopeContext scope) {
         TreeMap<String,SyncContext> ctxMap = (TreeMap<String,SyncContext>) scope.getValue(SC_SYNC_CONTEXT);
         if (ctxMap != null) {
            for (Map.Entry<String,SyncContext> ctxEnt:ctxMap.entrySet()) {
               SyncContext childSyncCtx = ctxEnt.getValue();
               if (trace)
                  System.out.println("Destroying scope : " + scope.getScopeDefinition().name + ":" + scope.getId() + " for app: " + ctxEnt.getKey());
               childSyncCtx.disposeContext();
            }
            // Need this to find and remove the sync insts so remove it after we are done.
            scope.setValue(SC_SYNC_CONTEXT, null);
         }
      }

      public void disposeContext() {
         ArrayList<Object> disposeList = new ArrayList<Object>();
         for (Map.Entry<Object,InstInfo> nameEnt: syncInsts.entrySet()) {
            InstInfo instInfo = nameEnt.getValue();
            Object inst = nameEnt.getKey();
            if (!instInfo.inherited)
               disposeList.add(inst);
         }
         for (Object toDispose:disposeList) {
            // Some elements may recursively dispose others
            if (syncInsts.get(toDispose) != null) {
               // TODO: Problems with disposing children here:
               // #1 - we might hit the child multiple times - since they might be sync insts
               // #2 - some of the children have not been created yet.  We don't have a way to dispose only the created children and creating them here would require an appId at least.
               // Solution: we should be disposing the root objects in the ScopeContext level I think.  There should be some way to dispose only the created children.
               // After we've disposed the objects at the root level, they should get removed from the sync inst table automatically, but this can be left around to clean up
               // anything that's not reachable from the page.
               DynUtil.dispose(toDispose, false);
            }
         }
         syncInsts.clear();
      }

      /** Called when we receive a property changed either from the data binding event (originalCtx=true) or propagated from a parent context (originalCtx = false) */
      public boolean valueInvalidated(Object obj, String propName, Object curValue, String syncGroup, boolean originalCtx) {
         if (originalCtx)
            updatePropertyValueListener(obj, propName, curValue, syncGroup);

         // Has the id changed
         /*
         if (obj instanceof IObjectId) {
            InstInfo info = getInheritedInstInfo(obj);
            if (info == null) {
               System.err.println("*** valueInvalidated called from non initialized object in sync?");
               return false;
            }
            IObjectId objIdImpl = (IObjectId) obj;
            String newId = objIdImpl.getObjectId();
            if (!DynUtil.equalObjects(newId, info.name)) {
               if (info.registered || info.nameQueued) {
                  // TODO: queue a new type of change id change via a method call SyncManager.idChange(old,new)
                  System.err.println("*** Warning id changed for synchronized instance: " + info.name + " to: " + newId);
               }
               info.name = newId;
            }
         }
         */

         // Any changes triggered when we are processing a sync just update the previous value - they do not trigger a change, unless there are pending bindingings.  That means we are setting a drived value.
         SyncAction action = getSyncAction(propName);
         if (action == SyncAction.Previous) {
            addPreviousValue(obj, propName, curValue);
         }
         else if (action == SyncAction.Value) {
            if (refreshProperty(obj, propName, curValue, syncGroup, "Property change")) {
               Object remValue;
               if ((remValue = removeChangedValue(obj, propName, syncGroup)) != null) {
                  if (verbose) {
                     System.out.println("Removing synced changed value: " + remValue);
                  }
               }
               return true;
            }
         }

         // Send this event to any child contexts which are registered for this instance.
         if (childContexts != null) {
            synchronized (this) {
               for (SyncContext childCtx:childContexts) {
                  if (childCtx.syncInsts.get(obj) != null)
                     childCtx.valueInvalidated(obj, propName, curValue, syncGroup, false);
               }
            }
         }

         return true;
      }

      private boolean refreshProperty(Object obj, String propName, Object curValue, String syncGroup, String opName) {
         Object prevValue = getPreviousValue(obj, propName);
         // Sometimes getPreviousValue returns arrays when we are comparing against a list so use array equal as well for that case
         if (DynUtil.equalObjects(prevValue, curValue) || arraysEqual(prevValue, curValue)) {
            if (verbose) {
               Object change = getChangedValue(obj, propName, syncGroup);
               if (change != null)
                  System.out.println(opName + ": reset to original value: " + DynUtil.getInstanceName(obj) + "." + propName + " = " + DynUtil.getInstanceName(curValue) + " pending change: " + change);
               else
                  System.out.println(opName + ": no change: " + DynUtil.getInstanceName(obj) + "." + propName + " = " + DynUtil.getInstanceName(curValue));
            }
            return true; // Still flagging this as a potential change since it may have changed from its most recent value.
         }
         addChangedValue(obj, propName, curValue, syncGroup);
         return false;
      }

      public void refreshSyncProperties(Object syncObj, String syncGroup) {
         InstInfo ii = getInstInfo(syncObj);
         if (ii != null) {
            SyncProperties syncProps = ii.props;
            if (syncProps != null) {
               Object[] props = syncProps.getSyncProperties();
               for (Object prop:props) {
                  String propName;
                  if (prop instanceof SyncPropOptions) {
                     SyncPropOptions syncProp = (SyncPropOptions) prop;
                     propName = syncProp.propName;
                  }
                  else {
                     propName = (String) prop;
                  }
                  Object value = DynUtil.getPropertyValue(syncObj, propName);
                  if (!refreshProperty(syncObj, propName, value, syncGroup, "Refresh")) {
                     if (verbose)
                        System.out.println("Default event on object: " + DynUtil.getInstanceName(syncObj) + " syncing changed property: " + propName);
                  }
               }
            }
         }
      }

      public Object[] getNewArgs(Object changedObj) {
         InstInfo ii = getInheritedInstInfo(changedObj);
         if (ii != null)
            return ii.args;
         return null;
      } public String toString() { return name == null ? super.toString() : name; } // FIXME!
   }

   static class SyncListenerInfo {
      TreeMap<String,PropertyValueListener> valList = new TreeMap<String,PropertyValueListener>();
   }

   public SyncDestination syncDestination;

   public SyncManager(SyncDestination dest) {
      syncDestination = dest;
   }

   public static void addSyncDestination(SyncDestination syncDest) {
      if (syncDest.name == null)
         throw new IllegalArgumentException("Must set the name property on the SyncDestination class: " + syncDest);
      syncDest.initSyncManager();
      syncManagersByDest.put(syncDest.name, syncDest.syncManager);
   }

   public static SyncManager getSyncManager(String destName) {
      return syncManagersByDest.get(destName);
   }

   public static boolean isSyncedPropertyForTypeName(String typeName, String propName) {
      Object type = DynUtil.resolveName(typeName, false);
      if (type != null)
         return isSyncedProperty(type, propName);
      return false;
   }

   public static boolean isSyncedProperty(Object type, String propName) {
      for (SyncManager mgr:syncManagersByDest.values()) {
         SyncProperties props = mgr.getSyncProperties(type);
         if (props != null && props.isSynced(propName)) {
            return true;
         }
      }
      return false;
   }

   public static SyncProperties getSyncProperties(Object type, String destName) {
      SyncManager syncMgr = getSyncManager(destName);
      return syncMgr.getSyncProperties(type);
   }

   public SyncProperties getSyncProperties(Object type) {
      Object nextType = type;
      // We choose the first sync properties we find in the type hierarchy.
      // That allows you to override the sync properties by adding a new one that may not
      // be a superset of the old one.  It does not allow you to passively not inherit sync however
      // but I think that's the right behavior.  Otherwise, there's no easy way to make sync be
      // something you can pass along down the class inheritance without the extender needing to turn on sync.
      do {
         SyncProperties props = syncTypes.get(nextType);
         if (props != null) {
            if (verbose && type != nextType) {
               System.out.println("Using sync properties for base type: " + DynUtil.getTypeName(nextType, false) + " for instance of: " + DynUtil.getTypeName(type, false));
            }
            return props;
         }
         nextType = DynUtil.getExtendsType(nextType);
      } while (nextType != null);
      return null;
   }

   /** Registers a new type for synchronization.  For each destination and syncGroup, specify the list of properties to be synchronized for this type.
    *  Once a type is registered, you can call addSyncInst.  The props can be an array of both String property names and instances created that specify
    *  additional attributes for each property using: new SyncPropOptions(propName, options).
    *  When you just specify a string, if initDefault is false, the flags are set to 0 (properties not sent over on init by default).
    *  When you set initDefault to true, your default properties are all sent across the wire on the initial sync.  You can override that on a per property basis by specifying a SyncPropOption.
    *  <p>
    *  For objects which are populated on the server at startup, not available on the client, setting initDefault=true keeps the configuration simpler.
    *  <p>Flags can be "0" for the default or OR together the values SyncOption.SYNC_INIT_DEFAULT and SyncOption.SYNC_CONSTANT.  INIT_DEFAULT specifies that all properties are sync'd on init unless overridden by specifying SyncPropOptions.  The CONSTANT value specifies that the state of the object is immutable.  No listeners will be added.  Instead the value is sync'd as a value object.</p>
    */
   public static void addSyncType(Object type, String syncGroup, Object[] props, String destName, int flags) {
      addSyncType(type, new SyncProperties(destName, syncGroup, props, flags));
   }

   public static void addSyncType(Object type, SyncProperties props) {
      Object old = null;
      // If no specific destination is given, register it for all destinations
      if (props.destName == null) {
         for (SyncManager mgr:syncManagersByDest.values()) {
            old = mgr.syncTypes.put(type, props);
         }
      }
      else {
         SyncManager syncMgr = getSyncManager(props.destName);
         if (syncMgr == null) {
             throw new IllegalArgumentException("*** No sync destination registered for: " + props.destName);
         }
         old = syncMgr.syncTypes.put(type, props);
      }

      if (verbose && old == null) {
         System.out.println("New synchronized type: " + DynUtil.getInstanceName(type) + " properties: " + props);
      }
   }

   @Sync(syncMode= SyncMode.Disabled, includeSuper=true)
   class PropertyValueListener extends AbstractListener {
      public String syncGroup;
      public SyncContext ctx;

      Object syncObj;
      String syncProp;

      Object value;

      // This is called when the IChangeable value of the property fires an event on itself - e.g. a list.set call which fires the default event to tell us that list has been changed.
      // We react by marking the owning property as changed.  So if you are listening to the property value, you'll see a change.
      public boolean valueInvalidated(Object obj, Object prop, Object eventDetail, boolean apply) {
         ctx.recordChange(syncObj, syncProp, value, syncGroup);
         return true;
      }
   }

   // The default event also may mean properties of the sync object itself has changed - e.g. JavaModel.  When it changes, it's declaredProperties, will change for instance.
   // We update any of them that have changed.
   @Sync(syncMode= SyncMode.Disabled, includeSuper=true)
   class DefaultValueListener extends AbstractListener {
      public String syncGroup;
      public SyncContext ctx;

      Object syncObj;

      public boolean valueInvalidated(Object obj, Object prop, Object eventDetail, boolean apply) {
         ctx.refreshSyncProperties(syncObj, syncGroup);
         return true;
      }
   }

   @Sync(syncMode= SyncMode.Disabled, includeSuper=true)
   class SyncChangeListener extends AbstractListener {
      public String syncGroup;
      public SyncContext ctx;

      public SyncChangeListener(String sg, SyncContext ctx) {
         syncGroup = sg;
         this.ctx = ctx;
      }

      /* TODO: listen for array element change.  Record those in the SyncLayer, serialize them as List.add/set calls with either a primitive value, or if it's a new object,
       * first create and populate the object, then do the setx using local variables in the statement block */

      /* This runs during the invalidate phase because we need the sync listener to run before other properties are changed as triggered by the firing of this property.  The sync listener can then order the changes in the way they happened. */
      public boolean valueInvalidated(Object obj, Object prop, Object eventDetail, boolean apply) {
         String propName = PBindUtil.getPropertyName(prop);
         Object curValue = DynUtil.getPropertyValue(obj, propName);

         return ctx.valueInvalidated(obj, propName, curValue, syncGroup, true);
      }
   }

   static boolean arraysEqual(Object o1, Object o2) {
      if (o1 == o2)
         return true;
      if (o1 == null || o2 == null)
         return false;
      if (PTypeUtil.isArray(DynUtil.getType(o1)) || PTypeUtil.isArray(DynUtil.getType(o2))) {
         int sz1 = PTypeUtil.getArrayLength(o1);
         int sz2 = PTypeUtil.getArrayLength(o2);
         if (sz1 != sz2)
            return false;
         for (int i = 0; i < sz1; i++) {
            Object elem1 = PTypeUtil.getArrayElement(o1, i);
            Object elem2 = PTypeUtil.getArrayElement(o2, i);
            if (!DynUtil.equalObjects(elem1, elem2))
               return false;
         }
         return true;
      }
      return false;
   }

   public enum SyncState {
      /** We are in a state where changes are recorded to ship to the other side */
      RecordingChanges,
      /** We are initializing objects.  This records the initial layer, used to reset a context when the client goes away */
      Initializing,
      /** We are initializing objects on the client but the changes that are being made are being sync'd back from the client, not originating from the server */
      InitializingLocal,
      /** We are applying changes from the other side.  Nothing is recorded in this mode, unless we are a few levels removed from data binding */
      ApplyingChanges,
      /** Syncing is disabled.  We update the previous values  */
      Disabled,
      /** Syncing is disabled.  We do not update the previous values */
      CopyingPrevious,
   }

   public enum SyncAction {
      Previous, Value, Ignore
   }

   /**
    * Set to true when you are applying changes from the other side.  In this case, the first layer of changes, i.e. those not triggered by binding events, are considered part of the 'synced' state and not recorded.  Those changes
    * may trigger changes indirectly.  Those changes must be recorded in case they are not emulated on the client.
    */
   public static SyncState setSyncState(SyncState value) {
      return (SyncState) PTypeUtil.setThreadLocal("syncState", value);
   }

   /** Use these to change sync state temporarily, then restore it the way it used to be without leaking a thread-local */
   public static SyncState getOldSyncState() {
     return (SyncState) PTypeUtil.getThreadLocal("syncState");
   }

   public static void setSyncAppId(String appId) {
      PTypeUtil.setThreadLocal("syncAppId", appId);
   }

   public static String getSyncAppId() {
      return (String) PTypeUtil.getThreadLocal("syncAppId");
   }

   public static void restoreOldSyncState(SyncState old) {
      if (old == null)
         PTypeUtil.clearThreadLocal("syncState");
      else
         PTypeUtil.setThreadLocal("syncState", old);
   }

   public static SyncState getSyncState() {
      SyncState res = getOldSyncState();
      if (res == null)
         return SyncState.RecordingChanges;
      return res;
   }

   public static SyncContext beginSync() {
      setSyncState(SyncState.Initializing);
      return getDefaultSyncContext();
   }

   public static void endSync() {
      setSyncState(null);
   }

   public static boolean beginSyncQueue() {
      if (PTypeUtil.getThreadLocal("syncAddQueue") != null)
         return false;
      PTypeUtil.setThreadLocal("syncAddQueue", new LinkedHashSet<AddSyncInfo>());
      return true;
   }

   public static boolean flushSyncQueue() {
      LinkedHashSet<AddSyncInfo> queue = (LinkedHashSet<AddSyncInfo>) PTypeUtil.getThreadLocal("syncAddQueue");
      if (queue == null)
         return false;
      // First disable queueing
      PTypeUtil.setThreadLocal("syncAddQueue", null);

      // Process the original addSyncInst calls now in order
      for (AddSyncInfo asi:queue) {
         addSyncInst(asi.inst, asi.onDemand, asi.initDefault, asi.scopeName, asi.args);
      }
      return true;
   }

   static class AddSyncInfo {
      Object inst;
      boolean onDemand;
      boolean initDefault;
      String scopeName;
      Object[] args;

      AddSyncInfo(Object i, boolean od, boolean initDef, String scopeName, Object[] args) {
         this.inst = i;
         this.onDemand = od;
         this.initDefault = initDef;
         this.scopeName = scopeName;
         this.args = args;
      }
   }

   public static void addSyncInst(Object inst, boolean onDemand, boolean initDefault, String scopeName, Object ...args) {
      LinkedHashSet<AddSyncInfo> syncQueue = (LinkedHashSet<AddSyncInfo>) PTypeUtil.getThreadLocal("syncAddQueue");
      if (syncQueue != null) {
         syncQueue.add(new AddSyncInfo(inst, onDemand, initDefault, scopeName, args));
         return;
      }

      // The compile time representation may have a defined scope but if not, we need to search up the object hierarchy for the
      // scope annotation.  If we inherit an inner object from a base class and apply a scope, we need that scope to take effect.
      if (scopeName == null)
         scopeName = DynUtil.getScopeName(inst);
      ScopeDefinition def = getScopeDefinitionByName(scopeName);
      addSyncInst(inst, onDemand, initDefault, def.scopeId, args);
   }

   public static void addSyncInst(Object inst, boolean onDemand, boolean initDefault, int scopeId, Object...args) {
      Object type = DynUtil.getType(inst);

      for (SyncManager syncMgr:syncManagersByDest.values()) {
         SyncProperties syncProps = syncMgr.getSyncProperties(type);
         if (syncProps != null)
            syncMgr.addSyncInst(inst, onDemand, initDefault, scopeId, syncProps, args);
      }
   }

   public SyncContext newSyncContext(String name) {
      return new SyncContext(name);
   }

   /** Use this if you create a new object which you know to be created by your component on the other side of the destination.  By default
    * objects which are created outside of a sync operation will be "new" objects that get sent via an object tag, not a modify operator.
    * But for repeat nodes, etc. which are sync'd by the framework itself, those should not come across as new, just a modify. */
   public static boolean registerSyncInst(Object inst) {
      boolean syncInst = false;
      for (Map.Entry<String,SyncManager> ent:syncManagersByDest.entrySet()) {
         String destName = ent.getKey();

         SyncContext ctx = getSyncContextForInst(destName, inst);
         if (ctx == null)
            ctx = getDefaultSyncContext();
         if (ctx != null)
            syncInst |= ctx.registerSyncInst(inst);
      }
      return syncInst;
   }

   public static SyncContext getSyncContext(String destName, String scopeName, boolean create) {
      ScopeDefinition def = ScopeDefinition.getScopeByName(scopeName);
      if (def == null) {
         System.err.println("*** No scope defined with name: " + scopeName);
         return null;
      }
      SyncManager syncMgr = getSyncManager(destName);
      return syncMgr.getSyncContext(def.scopeId, create);
   }

   SyncContext getSyncContext(int scopeId, boolean create) {
      String appId = getSyncAppId();
      if (scopeId == 0)
         return getRootSyncContext(appId);
      ScopeDefinition scopeDef = ScopeDefinition.getScopeDefinition(scopeId);
      ScopeContext scopeCtx = scopeDef.getScopeContext();
      if (appId == null) {
         System.err.println("*** Attempt to retrieve a sync context for a specific scope with no app id in place - see SyncManager.setSyncAppId");
         return null;
      }

      TreeMap<String, SyncContext> ctxMap = (TreeMap<String, SyncContext>) scopeCtx.getValue(SC_SYNC_CONTEXT);
      SyncContext ctx;
      if (ctxMap == null) {
         if (create) {
            ctxMap = new TreeMap<String,SyncContext>();
            scopeCtx.setValue(SC_SYNC_CONTEXT, ctxMap);
         }
         ctx = null;
      }
      else {
         ctx = ctxMap.get(appId);
      }
      if (ctx == null && create) {
         ctx = newSyncContext(scopeDef.name);
         ctx.scope = scopeCtx;
         ScopeDefinition parentScope = scopeDef.getParentScope();
         if (parentScope != null) {
            ctx.parentContext = getSyncContext(parentScope.scopeId, true);
         }
         if (ctx.parentContext == null)
            ctx.parentContext = getRootSyncContext(appId);
         ctx.parentContext.addChildContext(ctx);
         ctxMap.put(appId, ctx);
         scopeCtx.setDestroyListener(getRootSyncContext(appId));
      }
      // Even if this context has not been created, we might have changed for us in the root contexst.
      if (ctx == null)
         return null;
      return ctx;
   }

   private final static String SC_SYNC_CONTEXT = "sc.SyncContext";


   private void addSyncInst(Object inst, boolean onDemand, boolean initDefault, int scopeId, SyncProperties syncProps, Object...args) {
      SyncContext ctx;
      ctx = getSyncContext(scopeId, true);

      if (ctx != null)
         ctx.addSyncInst(inst, !onDemand, onDemand, initDefault, true, true, scopeId, syncProps, args);
      else {
         if (trace)
            System.err.println("Ignoring addSyncInst - not in scope: " + ScopeDefinition.getScope(scopeId));
         if (verbose)
            new Throwable().printStackTrace();
      }
   }

   public static void registerSyncInst(Object inst, String instName, int scopeId, boolean initInst) {
      registerSyncInst(inst, instName, scopeId, true, initInst);
   }

   public static void registerSyncInst(Object inst, String instName, int scopeId, boolean fixedName, boolean initInst) {
      for (SyncManager syncMgr:syncManagersByDest.values()) {
         SyncContext ctx = syncMgr.getSyncContext(scopeId, true);
         if (ctx != null) {
            // Here we've already been given thes cope for the syncInst so just put it into that one.
            ctx.registerObjNameOnScope(inst, instName, fixedName, initInst, true);
         }
      }
   }

   public static void registerSyncInst(Object inst, String instName, boolean fixedName, boolean initInst) {
      SyncContext ctx = getDefaultSyncContext();
      ctx.registerObjName(inst, instName, fixedName, initInst);
   }

   // Used by the JS code
   public static void registerSyncInst(Object inst, String instName) {
      SyncContext ctx = getDefaultSyncContext();
      ctx.registerObjName(inst, instName, true, true);
   }

   /** Returns the SyncContext to use for a sync instance which has already been added to the system. */
   public static SyncContext getSyncContextForInst(String destName, Object inst) {
      int scopeId = getScopeIdForSyncInst(inst);
      if (scopeId == -1)
         return null;
      SyncManager mgr = getSyncManager(destName);
      if (mgr == null)
         return null;
      return mgr.getSyncContext(scopeId, false);
   }

   public static int getScopeIdForSyncInst(Object inst) {
      List<ScopeDefinition> scopeDefs = ScopeDefinition.getActiveScopes();
      for (ScopeDefinition scopeDef:scopeDefs) {
         for (SyncManager syncMgr:syncManagersByDest.values()) {
            SyncContext syncCtx = syncMgr.getSyncContext(scopeDef.scopeId, false);
            if (syncCtx != null)
               if (syncCtx.syncInsts.get(inst) != null)
                  return scopeDef.scopeId;
         }
      }
      return -1;
   }

   public static void removeSyncInst(Object inst) {

      List<ScopeDefinition> scopeDefs = ScopeDefinition.getActiveScopes();
      if (scopeDefs == null)
         return;
      Object type = DynUtil.getType(inst);

      // We do not know the app ids which this instance is registered under right now so we have to do a bit of searching to find
      // the sync contexts (if any) which have it registered.
      for (ScopeDefinition scopeDef:scopeDefs) {
         ScopeContext scopeCtx = scopeDef.getScopeContext();
         TreeMap<String,SyncContext> ctxMap = (TreeMap<String,SyncContext>) scopeCtx.getValue(SC_SYNC_CONTEXT);
         if (ctxMap != null) {
            for (Map.Entry<String,SyncContext> ctxEnt:ctxMap.entrySet()) {
               SyncContext syncCtx = ctxEnt.getValue();
               if (syncCtx.hasSyncInst(inst)) {
                  SyncProperties syncProps = syncCtx.getSyncManager().getSyncProperties(type);
                  if (syncProps != null) {
                     syncCtx.removeSyncInst(inst, syncProps);
                  }
               }
            }
         }
      }
   }

   public static void replaceSyncInst(Object fromInst, Object toInst) {

      List<ScopeDefinition> scopeDefs = ScopeDefinition.getActiveScopes();
      if (scopeDefs == null)
         return;
      Object type = DynUtil.getType(fromInst);

      // We do not know the app ids which this instance is registered under right now so we have to do a bit of searching to find
      // the sync contexts (if any) which have it registered.
      for (ScopeDefinition scopeDef:scopeDefs) {
         ScopeContext scopeCtx = scopeDef.getScopeContext();
         TreeMap<String,SyncContext> ctxMap = (TreeMap<String,SyncContext>) scopeCtx.getValue(SC_SYNC_CONTEXT);
         if (ctxMap != null) {
            for (Map.Entry<String,SyncContext> ctxEnt:ctxMap.entrySet()) {
               SyncContext syncCtx = ctxEnt.getValue();
               if (syncCtx.hasSyncInst(fromInst)) {
                  SyncProperties syncProps = syncCtx.getSyncManager().getSyncProperties(type);
                  if (syncProps != null) {
                     syncCtx.replaceSyncInst(fromInst, toInst, syncProps);
                  }
               }
            }
         }
         else if (scopeDef.isGlobal()) {
            for (SyncManager syncMgr:syncManagersByDest.values()) {
               SyncContext syncCtx = syncMgr.getRootSyncContext(null);
               if (syncCtx != null && syncCtx.hasSyncInst(fromInst)) {
                  SyncProperties syncProps = syncCtx.getSyncManager().getSyncProperties(type);
                  syncCtx.replaceSyncInst(fromInst, toInst, syncProps);
               }
            }
         }
      }
   }

   public void startSync(Object inst, int scopeId, String prop) {
      SyncContext ctx = getSyncContext(scopeId, false);
      if (ctx != null)
         ctx.startSync(inst, prop);
      else
         System.err.println("*** No sync context for initProperty call");
   }

   public void initProperty(Object inst, int scopeId, String prop) {
      SyncContext ctx = getSyncContext(scopeId, false);
      if (ctx != null)
         ctx.initProperty(inst, prop);
      else
         System.err.println("*** No sync context for initProperty call");
   }

   public void fetchProperty(Object inst, int scopeId, String prop) {
      SyncContext ctx = getSyncContext(scopeId, false);
      if (ctx != null)
         ctx.fetchProperty(inst, prop);
      else
         System.err.println("*** No sync context for initProperty call");
   }

   public static void fetchProperty(Object inst, String prop) {
      Object type = DynUtil.getType(inst);

      int scopeId = getScopeIdForSyncInst(inst);
      if (scopeId == -1) {
         System.err.println("No sync inst: " + DynUtil.getInstanceName(inst) + " registered for fetchProperty of: " + prop);
         return;
      }

      for (SyncManager syncMgr:syncManagersByDest.values()) {
         SyncProperties syncProps = syncMgr.getSyncProperties(type);
         if (syncProps != null && syncProps.isSynced(prop))
            syncMgr.fetchProperty(inst, scopeId, prop);
      }
   }

   public static void startSync(Object inst, String prop) {
      Object type = DynUtil.getType(inst);

      int scopeId = getScopeIdForSyncInst(inst);
      // Not treating this as an error because we might use the same code with and without synchronization so this is only a debug message.
      if (scopeId == -1) {
         if (verbose)
            System.out.println("No sync inst: " + DynUtil.getInstanceName(inst) + " registered for startSync of: " + prop);
         return;
      }

      for (SyncManager syncMgr:syncManagersByDest.values()) {
         SyncProperties syncProps = syncMgr.getSyncProperties(type);
         if (syncProps != null && syncProps.isSynced(prop))
            syncMgr.startSync(inst, scopeId, prop);
      }
   }

   public static void initProperty(Object inst, String prop) {
      Object type = DynUtil.getType(inst);

      int scopeId = getScopeIdForSyncInst(inst);
      if (scopeId == -1) {
         System.err.println("No sync inst: " + DynUtil.getInstanceName(inst) + " registered for initProperty of: " + prop);
         return;
      }

      for (SyncManager syncMgr:syncManagersByDest.values()) {
         SyncProperties syncProps = syncMgr.getSyncProperties(type);
         if (syncProps != null && syncProps.isSynced(prop))
            syncMgr.initProperty(inst, scopeId, prop);
      }
   }

   private void removeSyncInst(Object inst, int scopeId, SyncProperties syncProps) {
      SyncContext ctx = getSyncContext(scopeId, false);
      ctx.removeSyncInst(inst, syncProps);
   }

   public static boolean sendSync() {
      return sendSync(null, false);
   }

   /** Start a synchronize operation for all destinations. Types which have registered a custom sync group are not synchronized. */
   public static boolean sendSync(boolean resetSync) {
      return sendSync(null, resetSync);
   }

   public static void setInitialSync(String destName, String appId, int scopeId, boolean val) {
      SyncManager syncMgr = syncManagersByDest.get(destName);

      syncMgr.setInitialSync(appId, scopeId, val);
   }

   public static void printAllSyncInsts() {
      printAllSyncInsts(getDefaultScope().scopeId);
   }

   public static void printAllSyncInsts(int scopeId) {
      for (String destName:syncManagersByDest.keySet()) {
         SyncManager syncManager = syncManagersByDest.get(destName);
         SyncContext ctx = syncManager.getSyncContext(scopeId, false);
         if (ctx != null)
            ctx.printAllSyncInsts();
      }
   }

   public static void resetContext(int scopeId) {
      for (String destName:syncManagersByDest.keySet()) {
         SyncManager syncManager = syncManagersByDest.get(destName);
         SyncContext ctx = syncManager.getSyncContext(scopeId, false);
         if (ctx != null)
            ctx.resetContext();
      }
   }

   public static CharSequence getInitialSync(String destName, int scopeId, boolean resetSync) {
      SyncManager syncMgr = syncManagersByDest.get(destName);

      return syncMgr.getInitialSync(scopeId, resetSync);
   }

   /**
    * Called when we retrieve the initial state of a set of components.  Both marks the current state as being initialized
    * and marks the specified sync context as being in the initial sync state.
    */
   public void setInitialSync(String appId, int scopeId, boolean val) {
      SyncManager.setSyncAppId(appId);
      if (val)
         SyncManager.setSyncState(SyncManager.SyncState.Initializing);
      else
         SyncManager.setSyncState(null);
      SyncContext ctx = getSyncContext(scopeId, true);
      ctx.setInitialSync(val);
   }

   public CharSequence getInitialSync(int scopeId, boolean resetSync) {
      SyncContext ctx = getSyncContext(scopeId, true); // Need to create it here since the initial sync will record lazy obj names we have to clear on reload
      if (ctx == null)
         return null;

      StringBuilder res = new StringBuilder();
      if (!resetSync) {
         SyncLayer layer = ctx.getInitialSyncLayer();
         HashSet<String> createdTypes = new HashSet<String>();

         ArrayList<SyncContext> parCtxList = new ArrayList<SyncContext>();
         SyncContext parentCtx = ctx.parentContext;
         while (parentCtx != null) {
            parCtxList.add(parentCtx);
            parentCtx = parentCtx.parentContext;
         }

         // Start at the root parent context and work down the chain
         for (int i = parCtxList.size() - 1; i >= 0; i--) {
            parentCtx = parCtxList.get(i);
            CharSequence parentRes = parentCtx.getInitialSyncLayer().serialize(ctx, createdTypes, false);
            if (parentRes != null) {
               res.append(parentRes);
            }
         }

         CharSequence thisRes = layer.serialize(ctx, createdTypes, false);
         res.append(thisRes);
      }

      // Before we've finished the initial sync, there's no difference between the initial values and the previous values.
      ctx.setInitialSync(false);
      // Any new object names we generated during initialization are now 'registered' by that client
      ctx.commitNewObjNames(ctx);
      return res;
   }

   /**
    * Does a global sync across all destinations for all current sync contexts.  If you use SYNC_ALL as the sync group
    * it will synchronize all sync groups.  Specifying a null syncGroup will choose the default sync group only.
    */
   public static boolean sendSync(String syncGroup, boolean resetSync) {
      boolean sentAnything = false;
      for (String destName:syncManagersByDest.keySet())
         sentAnything = sendSync(destName, syncGroup, resetSync) || sentAnything;
      return sentAnything;
   }

   public static Set<String> getDestinationNames() {
      return syncManagersByDest.keySet();
   }

   public static ScopeDefinition getDefaultScope() {
      ScopeDefinition syncScope = null;
      List<ScopeDefinition> activeScopes = ScopeDefinition.getActiveScopes();
      for (ScopeDefinition def: activeScopes) {
         if (syncScope == null || def.includesScope(syncScope))
            syncScope = def;
      }
      return syncScope;
   }

   public static boolean sendSync(String destName, String syncGroup, boolean resetSync) {
      ScopeDefinition syncScope = getDefaultScope();
      if (syncScope == null)
         throw new IllegalArgumentException("*** No active scopes to sync");
      else
         return sendSync(destName, syncGroup, syncScope.scopeId, resetSync);
   }

   public static boolean sendSync(String destName, String syncGroup, int scopeId, boolean resetSync) {
      SyncManager syncMgr = syncManagersByDest.get(destName);
      return syncMgr.sendSync(syncGroup, scopeId, resetSync);
   }

   public boolean sendSync(String syncGroup, int scopeId, boolean resetSync) {
      SyncContext ctx = getSyncContext(scopeId, false);
      if (ctx != null) {
         ArrayList<SyncLayer> toSend = new ArrayList<SyncLayer>();
         SyncContext nextCtx = ctx;
         do {
            if (syncGroup == SYNC_ALL) {
               for (String group:ctx.getSyncGroups()) {
                  SyncLayer changedLayer = nextCtx.getChangedSyncLayer(group);
                  toSend.add(changedLayer);
               }
            }
            else {
               SyncLayer changedLayer = nextCtx.getChangedSyncLayer(syncGroup);
               toSend.add(changedLayer);
            }
            nextCtx = nextCtx.parentContext;
         } while (nextCtx != null);

         return syncDestination.sendSync(ctx, toSend, syncGroup, resetSync);
      }
      else if (verbose) {
         System.out.println("No changes to synchronize in scope: " + ScopeDefinition.getScope(scopeId));
      }
      return true;
   }

   public static RemoteResult invokeRemote(Object obj, String methName, Object...args) {
      return invokeRemoteDest(SyncDestination.defaultDestination.name, null, obj, methName, args);
   }

   public static void setCurrentSyncLayers(ArrayList<SyncLayer> current) {
      PTypeUtil.setThreadLocal("currentSyncLayer", current);
   }

   public static ArrayList<SyncLayer> getCurrentSyncLayers() {
      return (ArrayList<SyncLayer>) PTypeUtil.getThreadLocal("currentSyncLayer");
   }

   public static void processMethodReturn(String callId, Object retValue) {
      ArrayList<SyncLayer> currentSyncLayers = getCurrentSyncLayers();
      boolean handled = false;
      if (currentSyncLayers != null) {
         for (SyncLayer currentSyncLayer:currentSyncLayers) {
            if (currentSyncLayer.processMethodReturn(callId, retValue)) {
               handled = true;
               break;
            }
         }
      }
      if (!handled)
         System.err.println("processMethodReturn called when no current sync layer is registered");
   }

   public static RemoteResult invokeRemoteDest(String destName, String syncGroup, Object obj, String methName, Object...args) {
      SyncManager mgr = getSyncManager(destName);
      int scopeId = getScopeIdForSyncInst(obj);
      if (scopeId == -1)
         scopeId = GlobalScopeDefinition.getGlobalScopeDefinition().scopeId;
      SyncContext ctx = obj == null ? mgr.getDefaultSyncContext() : mgr.getSyncContext(scopeId, true);
      return ctx.invokeRemote(syncGroup, obj, methName, args);
   }

   public static SyncContext getDefaultSyncContext() {
      return SyncDestination.defaultDestination.syncManager.getSyncContext(GlobalScopeDefinition.getGlobalScopeDefinition().scopeId, true);
   }

   @Constant
   public static SyncManager getDefaultSyncManager() {
      return SyncDestination.defaultDestination.syncManager;
   }

   public static void initChildren(Object obj) {
      // Some components need to do some initialization to create other components - i.e. the children created by the repeat attribute
      // This hook lets them do that... maybe it should be merged with starting the component?
      if (obj instanceof ISyncInit)
         ((ISyncInit) obj).initSync();
      Object[] children = DynUtil.getObjChildren(obj, null, true);
      if (children != null) {
         for(Object child:children) {
            initChildren(child);
         }
      }
   }

   public static Object getSyncInst(String name) {
      SyncContext defaultCtx = getDefaultSyncContext();
      Object obj = defaultCtx.getObjectByName(name, true);
      if (obj == null)
         obj = DynUtil.resolveName(name, true);
      return obj;
   }

   /** Used from JS generated code */
   public static Object resolveSyncInst(String name) {
      Object obj = getSyncInst(name);
      if (obj == null)
         System.err.println("*** Unable to resolve syncInst: " + name);
      return obj;
   }

   /** Used from JS generated code */
   public static Object resolveOrCreateSyncInst(String name, Object type, String sig, Object...args) {
      Object inst = getSyncInst(name);
      if (inst != null)
         return inst;
      inst = DynUtil.createInstance(type, sig, args);
      registerSyncInst(inst, name);
      return inst;
   }

   public static void addSyncHandler(Object typeObj, Class handlerClass) {
      if (!SyncHandler.class.isAssignableFrom(handlerClass))
         throw new IllegalArgumentException("Must provide a class which extends SyncHandler and has a constructor matching: (Object inst, SyncContext ctx)");
      syncHandlerRegistry.put(typeObj, handlerClass);
   }

   public static ScopeDefinition getScopeDefinitionByName(String scopeName) {
      ScopeDefinition def = ScopeDefinition.getScopeByName(scopeName);
      // Global scope has not yet been defined so just define it.
      if (def == null)
         def = GlobalScopeDefinition.getGlobalScopeDefinition();
      return def;
   }

   public int numSendsInProgress = 0;

   /** The number of sendSync's that have not been ackowledged yet - i.e. the number of requests outstanding */
   @Bindable(manual=true)
   public void setNumSendsInProgress(int newNum) {
      numSendsInProgress = newNum;
      Bind.sendChange(this, "numSendsInProgress", newNum);
   }

   public int getNumSendsInProgress() {
      return numSendsInProgress;
   }

}
