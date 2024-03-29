/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.bind.*;
import sc.dyn.DynUtil;
import sc.dyn.INameContext;
import sc.dyn.INamedChildren;
import sc.dyn.RemoteResult;
import sc.obj.*;
import sc.type.CTypeUtil;
import sc.type.PTypeUtil;
import sc.util.IdentityWrapper;

import java.util.*;

/**
 * This is the manager class for synchronization.  A SyncManager is registered for a given destination name.  The
 * destinations are added at startup and identified by names like client, server, or the name of a peer process.
 * <p>
 * The SyncManager collects the list of types and subset of the properties of those types which are to be synchronized.
 * Synchronized instances are stored in a SyncContext which is stored inside of a scope.  Scopes are organized into
 * a directed acyclic graph according to their lifecycle: global, appGlobal, session, appSession, window, request.
 * <p>
 * On the client, there will only be one sync context, but
 * on the server, the system tracks changes to objects based on their lifecycle.  So global objects will broadcast
 * out changes.  Session scoped objects are only visible in the current user's session and request scoped objects last only
 * for the given request.  Each SyncContext stores the set of objects that belong in that context.  SyncContexts may have
 * a parent context - e.g. window scope's parent is a session and session scope's parent is the global scope.
 * <p>
 * For the sync operation, the request, window, session, and global sync contexts are rolled up into a single layer of objects definitions
 * and modify definitions.  When the server is replying to the client, the layer gets translated to Javascript before it is sent.
 * Once on the remote side, the layer is applied.  After applying the changes, any response layer that results from the previous layer is packages up
 * and put into the reply.
 * <p>
 * SyncContexts are further separated in some cases by an application id ("appId").  For server applications the appId is the URL of the page so different
 * pages have different state contexts for a given session.
 * <p>
 * The syncManager operates in two modes.  When you are updating the objects from changes that occurred in the remote process,
 * the flag syncInProgress is set to true and no changes are recorded.  Instead, we are updating the
 * previous values stored for all synchronized objects (used to revert, and know the difference between old and new).
 * <p>
 * At other times, live changes made to the objects are recorded.  We store the new values for each change by listening to the property change events.
 * <p>During initialization, the initial property values are synchronized to the other side if the "init" flag is true.  If it's false, we assume the
 * client was initialized with the same values.  With the automatic sync mode, this flag is set automatically by looking at how a given property is initialized.  If both sides
 * are initialized, the init flag is set to false.  Otherwise, it's set to true.
 * </p>
 */
@sc.js.JSSettings(jsModuleFile="js/sync.js", prefixAlias="sc_")
@Sync(syncMode= SyncMode.Disabled)
public class SyncManager {
   // A SyncManager manages all traffic going out of a given channel type, i.e. per SyncDestination instance.
   private static Map<String,SyncManager> syncManagersByDest = new HashMap<String,SyncManager>();
   private static List<SyncManager> syncManagers = new ArrayList<SyncManager>();
   private static Map<Object, SyncProperties> globalSyncTypes = new IdentityHashMap<Object, SyncProperties>();
   public static Set<String> globalSyncTypeNames = null;

   private static HashMap<Object, Class> syncHandlerRegistry = new HashMap<Object, Class>();

   private static List<INameContext> frameworkNameContexts = null;
   private static List<IFrameworkListener> frameworkListeners = null;

   /** Traces synchronization layers between client and server for understanding and debugging client/server issues */
   public static boolean trace = false;

   /** More detailed debugging of the synchronization system - i.e. all sync types, all sync insts */
   public static boolean verbose = false;
   /** Like verbose above but includes individual property changes */
   public static boolean verboseValues = false;

   /** When false, the trace buffer is truncated at 'logSize' for each layer */
   public static boolean traceAll = false;
   public static int logSize = 128; // limit to the size of layer defs etc. that are logged - ellipsis are inserted for characters over that amount.

   public static String defaultLanguage = "json";

   private static boolean syncNoInitDisplayed = false;

   /**
    * Set this to true for the server or the sync manager which manages the initialization.  It is set to false for the client or the one who receives the initial state.
    * When the server restarts, it can rebuild the initial state automatically so this prevents the client having to send all of that info over.  It's a bit conflicting
    * to have the client define objects which are already on the server (though probably setting initialSync on both should work - so those conflicts are just managed).
    */
   public boolean recordInitial = true;

   public String destinationName;

   private Map<Object, SyncProperties> syncTypes = new IdentityHashMap<Object, SyncProperties>();

   // Records the sync instances etc. that are registered in a global context.
   Map<String,SyncContext> rootContexts = new HashMap<String,SyncContext>();

   public SyncDestination syncDestination;

   public SyncContext getRootSyncContext() {
      String ctxId = "global";
      SyncContext rootCtx = rootContexts.get(ctxId);
      if (rootCtx == null) {
         //rootCtx = newSyncContext("global:" + appId);
         // Do not include the appId in the context so that we can mirror static state.  Now that we have window scope to store the state of each request separately I think this is not needed.
         // TODO: we have global objects stored in a per-page object variable e.g. index.body.editorMixin.editorModel and the dirEnts.  If we do not share the global context, the global object
         // state is not sent down.  The page objects which are not global, provide an indexing into the global name space when an inner object extends a global base class.
         rootCtx = newSyncContext(ctxId);
         ScopeContext scopeCtx = GlobalScopeDefinition.getGlobalScopeDefinition().getScopeContext(true);
         rootCtx.scope = scopeCtx;
         scopeCtx.setValue(SC_SYNC_CONTEXT_SCOPE_KEY, rootCtx);
         rootContexts.put(ctxId, rootCtx);
      }
      return rootCtx;
   }

   public SyncContext getCurrentRootSyncContext() {
      return getRootSyncContext();
   }

   /** Specify to sync calls for the sync group name which synchronizes all sync groups. */
   public final static String SYNC_ALL = "syncAll";

   /**
    * This is the primary sync data structure we use for storing data for each synchronized object instance, for each client.
    * An InstInfo will be created both for the original SyncContext the object is created in as well as an 'inherited' InstInfo
    * for each context in which that instance is used.  This stores the buffer of changes - the change state - for propagating
    * the object's changes to listeners of the child context.
    */
   public static class InstInfo {
      SyncContext syncContext; // Each InstInfo is stored in the syncInsts of only one SyncContext
      // NOTE: these are public for the debugging package only - not intended to be manipulated in user code
      public Object[] args;       // Any argument values used in the constructor to construct this instance?
      public boolean initDefault;
      public String name;
      public String oldName; // Name change pending - we keep the old name so that we can satisfy requests using it until the ack of the name change is received
      // TODO: turn this into bitflags for efficiency (or better yet, build an SC plugin that can do that transformation automatically in code-gen!)
      public boolean registered;  // Has this object's name been sent to the client
      public boolean nameQueued;  // Has this object's name been at least queued to send to he client
      public boolean onDemand;
      public boolean fixedObject; // True if this is an object with a fixed name in the global name-space
      public boolean initialized;
      public boolean inherited; // Set to true when the instance in this SyncContext is inherited from the parentContext.
      public boolean resetState;
      public SyncProperties props;
      public TreeMap<String,Boolean> onDemandProps; // The list of on-demand properties which have been fetched for this instance - stores true when the prop has been initialized, false for when it's been requested before the object has been initialized.

      public HashMap<String,Object> previousValues;
      public HashMap<String,Object> initialValues;  // TODO: REMOVE THIS - it's not used!
      //int refcnt = 1;

      DefaultValueListener valueListener;

      SyncHandler syncHandler; // Caching the sync handler for this instance so we don't create them over and over again

      SyncContext parContext;

      InstInfo(SyncContext syncCtx, Object[] args, boolean initDef, boolean resetState, boolean onDemand) {
         this.syncContext = syncCtx;
         this.args = args;
         this.initDefault = initDef;
         this.resetState = resetState;
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

      public void setName(String name) {
         this.name = name;
      }
   }

   public SyncProperties getSyncPropertiesForInst(Object changedObj) {
      // Work around weird behavior of DynUtil.getType for TypeDeclarations - since we will be serializing them over and need to treat them like objects, not types here
      // And in Javascript, if we're given changedObj which is the class object, calling getClass() on the class does not return the jv_Class object - it just returns the changedObj
      // which means we get the sync properties not for the type object (maybe just class name?), but for the instance of that type.
      Object syncType = DynUtil.getTypeOfObj(changedObj);
      return getSyncProperties(syncType);
   }

   /**
    * The SyncContext stores the set of synchronized instances for a given life-cycle, or scope.  It listens for changes to synchronized
    * properties of these instances and stores the changes it collects in a SyncLayer identified by the
    * sync-group (if any) the property is apart of.  Sync-groups allow you to synchronize different groups of
    * properties at different times but is not a feature we use commonly.
    * <p>
    * The SyncContext also stores a SyncLayer which represents the changed state from when the syncContext was created.
    * In other words, all objects created (and not destroyed) and the current value of all properties.
    * If the client drops and reconnects, it can use this SyncLayer to bring it back up-to-date via the reset
    * operation (i.e. if you refresh the page in a client/server application).
    * </p>
    */
   @Sync(syncMode= SyncMode.Disabled)
   public class SyncContext implements ScopeDestroyListener, Comparable {
      String name;

      // Stores the list of parent contexts - e.g. for a window scope, it will store the app session and for the app session, it will store the app-global and session.
      ArrayList<SyncContext> parentContexts;

      // Stores the list of child sync contexts - e.g. based on the ScopeDefinition's parent/child relationship.  TODO: can we remove this now that we have this at the scope level and use that instead?
      HashSet<SyncContext> childContexts;

      ScopeContext scope;

      boolean isShared = false;

      protected boolean initialSync = false;  // Before we've sent our context remotely we're in the initial sync

      // The server stores around the initial sync layer so clients can refresh, the client does not have to do that.
      protected boolean needsInitialSync = true;

      // Stores the sync state for an instance: InstInfo for each instance that's managed by this SyncContext
      private IdentityHashMap<Object, InstInfo> syncInsts = new IdentityHashMap<Object, InstInfo>();

      // Stores the reference to the InstInfo for any instances which are managed by this context and references in some child SyncContext.
      private IdentityHashMap<Object, Set<InstInfo>> childSyncInsts = null;

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

      // A table which stores the automatically assigned id for a given object instance.
      //static Map<Object,String> objectIds = (Map<Object,String>) PTypeUtil.getWeakHashMap();
      Map<Object,String> objectIds;

      // For when auto-id generation is necessary, store the number for each type name created in this context
      Map<String,Integer> typeIdCounts;

      SyncLayer initialSyncLayer = new SyncLayer(this);
      {
         initialSyncLayer.initialLayer = true;
      }

      void initIdMaps() {
         if (objectIds == null) {
            // TODO: use WeakIdentityHashMap and remove the IdentityWrapper keys we use here?  Same with objectIds in DynUtil.
            objectIds = new HashMap<Object,String>();
            typeIdCounts = new TreeMap<String,Integer>();
         }
      }

      public SyncContext(String name) {
         this.name = name;
      }

      public void addParentContext(SyncContext parent) {
         if (parentContexts == null)
            parentContexts = new ArrayList<SyncContext>();
         parentContexts.add(parent);
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
         if (verboseValues && (curVal != null || old != null)) {
            if (old == null) {
               // Filter out initial value of boolean=false to reduce logging
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
                  return PTypeUtil.clone(val);
            }
            else
               return PTypeUtil.clone(val);
         }
         finally {
            setSyncState(origState);
         }
         //return val;
      }

      /** See addPreviousBelow - the inherited flag here determines if we look in parent contexts and register this object
       * in this context or only look in the current  */
      public void addPreviousValue(Object obj, String propName, Object val, boolean inherited, boolean addToInitialLayer) {
         InstInfo ii = inherited ? getInheritedInstInfo(obj) : getInstInfo(obj);
         if (ii != null && ii.syncContext != this)
            System.out.println("*** Using inherited context for previous value: " + ii.syncContext + " for " + this);
         addPreviousValue(obj, ii, propName, val, addToInitialLayer);
      }

      /**
       * Records the value that represents the last synchronized state from the remote process.  When you start a new
       * operation, the previous value also stores the unchanged value or original. Normally the previous value is maintained
       * automatically.   It's a cache of the old version basically and updated when a property change event is received.
       * It knows the context - are we apply changes from the server or recording new changes and updates the previous value accordingly.
       * There are some situations when you might update the previous value directly.  For example, you generate the
       * body of a parent tag which includes the child tag's startTagTxt and InnerHTML properties.  Once the parent's body
       * is received on the remote side, the children's values will updated implicitly.  We need to update the previous
       * value so we know when to send over changes.
       */
      public void addPreviousValue(Object obj, InstInfo ii, String propName, Object val, boolean addToInitialLayer) {
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
         if (verboseValues) {
            String updateType = prevMap == initMap ? "initial" : "original";
            if (old == null)
               System.out.println("Sync " + updateType + " value: " + DynUtil.getInstanceName(obj) + "." + propName + " = " + DynUtil.getInstanceName(val));
            else if (!DynUtil.equalObjects(val, old))
               System.out.println("Sync updating " + updateType + " value: " + DynUtil.getInstanceName(obj) + "." + propName + " = " + DynUtil.getInstanceName(val) + " (from=" + DynUtil.getInstanceName(old) + ")");
         }

         // If ii.inherited is true, we've been propagated this event from a parent context.  Do not record it in the initial layer in this case since the initial layer is already inherited.
         if (!addToInitialLayer || ii.inherited)
            return;

         SyncProperties syncProps = ii.props == null ? getSyncPropertiesForInst(obj) : ii.props;
         if (syncProps == null)
            return;
         int syncFlags = syncProps.getSyncFlags(propName);
         if (syncFlags == -1)
            return;

         SyncState state = getSyncState();
         if (state == SyncState.Disabled)
            return;
         // Needs to be added as a change to the initialSyncLayer, it is maintained on the client and server but for
         // different purposes.
         // For the server (recordInitial=true), this records all changes so we can do an accurate "refresh" using the initial layer.
         // For the client (recordInitial = false), includes changes required to reset the server's session when it is lost.
         // It's not all state since the server can reproduce much of the state that it sends to the client initially.
         // It does include changes made on the client - i.e. those made with SyncState.RecordingChanges.
         // It also includes properties explicitly marked as 'reset state'. That must be set manually to include properties
         // the server needs returned to it, typically properties it sets in response to some button clicked in the UI
         // where the client change does not include all of the info produced by the operation.
         if ((recordInitial || state == SyncState.InitializingLocal) || (state != SyncState.Initializing && state != SyncState.ApplyingChanges) || (ii.resetState && ((syncFlags & SyncPropOptions.SYNC_RESET_STATE) != 0)))
            initialSyncLayer.addChangedValue(obj, propName, val, recordInitial && state == SyncState.ApplyingChanges);
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

      public void addNewObj(Object obj, String syncGroup, InstInfo instInfo) {
         SyncState state = getSyncState();
         if (state == SyncState.CopyingPrevious)
            return;
         if (state != SyncState.Disabled) {
            //if (state == SyncState.RecordingChanges) {
            if (state == SyncState.RecordingChanges || (state == SyncState.ApplyingChanges && Bind.getNestedBindingCount() > 0)) {
               SyncLayer changedLayer = getChangedSyncLayer(syncGroup);
               // When we are in the initial sync, we are not recording these as changes - just putting them in the
               // initial layer.
               if (!initialSync)
                  changedLayer.addNewObj(obj, instInfo, false);
            }
            if (recordInitial || state != SyncState.Initializing) {
               initialSyncLayer.addNewObj(obj, instInfo, recordInitial && state == SyncState.ApplyingChanges);
            }
         }
      }

      private boolean isFixedObject(Object refVal) {
         InstInfo ii = getInstInfo(refVal);
         return (ii != null && ii.fixedObject);
      }

      private boolean isPrimitiveClass(Class valClass) {
         return PTypeUtil.isStringOrChar(valClass) || PTypeUtil.isPrimitive(valClass) || PTypeUtil.isANumber(valClass) || valClass == Boolean.class || valClass == Boolean.TYPE;
      }

      private boolean isNonReference(Object val) {
         if (val == null)
            return false;
         Class valClass = val.getClass();
         boolean primType = isPrimitiveClass(valClass);
         if (primType)
            return true;
         if (DynUtil.isEnumConstant(val) || isFixedObject(val))
            return true;
         if (PTypeUtil.isArray(valClass)) {
            Object compType = PTypeUtil.getComponentType(valClass);
            if (compType instanceof Class && isPrimitiveClass((Class) compType))
               return true;
         }
         if (val instanceof Collection) {
            int len = DynUtil.getArrayLength(val);
            if (len == 0 || isNonReference(DynUtil.getArrayElement(val, 0)))
               return true;
         }
         return false;
      }

      public void addChangedValue(List<SyncLayer.SyncChange> depChanges, Object obj, String propName, Object val, String syncGroup, SyncLayer syncLayer) {
         SyncLayer changedLayer = syncLayer == null ? getChangedSyncLayer(syncGroup) : syncLayer;
         if (verboseValues || (verbose && !needsSync)) {
            System.out.println("Changed value: " + DynUtil.getInstanceName(obj) + "." + propName + " = " + DynUtil.getInstanceName(val) + (!needsSync ? " *** first change in sync" : ""));
         }
         markChanged();
         InstInfo ii = getInstInfo(obj);
         if (ii == null) {
            System.err.println("*** No instInfo in addChangedValue");
            return;
         }
         boolean initialDepChange = false;
         // When we are processing the initial sync, we are not recording changes unless we are in the midst of serializing
         // the initial sync where depChanges will be set.
         if (!initialSync) {
            if (scope.getScopeDefinition().supportsChangeEvents) {
               // For simple value properties, that cannot refer recursively, we add them to the dep changes which are put before the object which is referencing the
               // object we are serializing.  If it's possibly a reference to an object either that's not yet serialized or is being serialized we do it after.  It might
               // be nice to check if it's not being serialized cause we could then serialize it all before... rather than splitting up the object definition unnecessarily
               // serialization buffer by adding a new change.  TODO: other value properties might be handled during the reference stage here
               boolean safeDepChange = false;
               if (depChanges != null) {
                  if (val == null)
                     safeDepChange = true;
                  else {
                     // TODO: we could find a faster way to do this logic, or switch it and only look for references which are being added to the stream since it's only 'forward references' we are trying to avoid here.
                     // we also could just support deserializing forward references
                     if (isNonReference(val))
                        safeDepChange = true;
                  }
               }
               if (safeDepChange)
                  changedLayer.addDepChangedValue(depChanges, obj, propName, val, false);
               else
                  changedLayer.addChangedValue(obj, propName, val);
            }
            else {
               // Should we add to the initial layer here? If this is onDemand and not queued and we are not processing a reference
               // to this object, it may not be referenced on the client so don't add it to the initial layer
               addPreviousValue(obj, propName, val, false, !ii.onDemand || ii.nameQueued || depChanges != null); // If this scope does not support change events, we just apply the previous value now, so we'll continue to propagate any subsequent changes to children
            }
         }
         else {
            if (ii.previousValues != null) {
               Object prevVal = ii.previousValues.get(propName);
               if (!DynUtil.equalObjects(prevVal,val))
                  addPreviousValue(obj, propName, val, false, true);
            }
         }

         // else if !initialSync - not recording this change here!
         SyncState syncState = getSyncState();
         if (recordInitial || syncState != SyncState.Initializing) {
            if (!recordInitial || !ii.onDemand || ii.nameQueued || depChanges != null) {
               // For the 'remote' parameter, we want it to be true only for those changes which definitively originated on the client.  The binding count test will
               // eliminate side-effect changes from those original ones.  This is the same logic we use to determine
               initialSyncLayer.addChangedValue(obj, propName, val, recordInitial && syncState == SyncState.ApplyingChanges && Bind.getNestedBindingCount() <= 1);
            }
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
         else {
            for (PropertyValueListener listener : info.valList.values())
               Bind.removeListener(listener.value, null, listener, IListener.VALUE_CHANGED_MASK);
            syncListenerInfo.remove(parentObj);
         }
      }

      public void registerObjName(Object inst, Object[] args, String objName, boolean fixedName, boolean initInst, boolean receivedChange) {
         String scopeName = DynUtil.getScopeName(inst);
         ScopeDefinition def = getScopeDefinitionByName(scopeName);
         SyncContext useCtx = getSyncContext(def.scopeId, true);
         if (useCtx == null)
            System.err.println("Unable to resolve sync context for scope: " + scopeName + " to register inst: " + objName);
         else {
            useCtx.registerObjNameOnScope(inst, args, objName, fixedName, initInst, false, false, false);
            InstInfo ii = getInstInfo(inst);
            if (ii == null) {
               ii = getInheritedInstInfo(inst);
               if (ii != null)
                  ii = createAndRegisterInheritedInstInfo(inst, ii);
            }
            if (ii != null) {
               ii.nameQueued = true;
               ii.registered = receivedChange;
            }
         }
      }

      private void putInstInfo(Object inst, InstInfo ii) {
         syncInsts.put(inst, ii);
      }

      InstInfo registerObjNameOnScope(Object inst, Object[] args, String objName, boolean fixedName, boolean initInst, boolean nameQueued, boolean registered, boolean addOnDemandChanges) {
         if (objName == null)
            throw new IllegalArgumentException("Invalid null object name for instance: " + inst);
         InstInfo ii = syncInsts.get(inst);
         if (ii == null) {
            ii = getInheritedInstInfo(inst);
            if (ii != null) {
               ii = createAndRegisterInheritedInstInfo(inst, ii);
            }
            else {
               ii = new InstInfo(this, args, false, true, false);
               putInstInfo(inst, ii);
            }

            // When we are registering a new object on the remote side, if it's synchronized on this side, initialize it as an on-demand instance so we send the changes back.
            if (initInst) {
               SyncProperties props = getSyncPropertiesForInst(inst);
               if (props != null) {
                  ii.setName(objName);
                  ii.props = props;
                  initOnDemandInst(null, inst, ii, ii.inherited, ii.onDemand && addOnDemandChanges, null);
               }
            }
         }
         else {
            // This happens when the sync queue is disabled and the base class adds the instance with a different name.
            if (ii.name != null && !ii.name.equals(objName)) {
               if (verbose)
                  System.out.println("Re-registering " + ii.name + " as: " + objName);
            }
            if (!ii.initialized && initInst) {
               SyncProperties props = getSyncPropertiesForInst(inst);
               if (props != null) {
                  ii.setName(objName);
                  ii.props = props;
                  initOnDemandInst(null, inst, ii, false, false, null);
               }
            }
         }
         ii.setName(objName);
         ii.fixedObject = fixedName; // when true the object name is not reset
         ii.registered = registered;
         ii.nameQueued = nameQueued;

         Object oldInst = objectIndex.put(objName, inst);
         if (verbose && oldInst != null && oldInst != inst)
            System.out.println("*** Warning: replacing old instance for: " + objName);
         DynUtil.setObjectId(inst, objName); // Register this instance so we use this one name for logging, management etc. to refer to this instance

         // When registering names if the other side used a type-id larger than in our current cache, we need to adjust ours to use larger ids to avoid conflicts
         // TODO: there is a race condition here - where the client and server could be allocating ids and so conflict.  This is only for the default naming system - use IObjectId
         // for cases where you need to do stuff like that.
         int usix = objName.indexOf(ID_INDEX_SEPARATOR);
         if (usix != -1) {
            String typeName = objName.substring(0, usix);
            String ctStr = objName.substring(usix + ID_INDEX_SEPARATOR.length());
            try {
               int ct = Integer.parseInt(ctStr);
               initIdMaps();
               int val = DynUtil.getTypeIdCount(typeName, typeIdCounts);
               if (val <= ct)
                  DynUtil.updateTypeIdCount(typeName, ct + 1);
            }
            catch (NumberFormatException exc) {}
         }
         return ii;
      }

      public boolean registerSyncInst(Object inst) {
         InstInfo ii = syncInsts.get(inst);
         if (ii == null) {
            ii = new InstInfo(this, null, false, true, false);
            ii.registered = true;
            ii.nameQueued = true;
            ii.fixedObject = true;
            putInstInfo(inst, ii);
            return false;
         }
         else {
            ii.registered = true;
            ii.nameQueued = true;
            return true;
         }
      }

      public boolean accessSyncInst(Object inst) {
         InstInfo ii = syncInsts.get(inst);
         if (ii == null) {
            return false;
         }
         else {
            if (ii.name != null && ii.initialized) {
               initChildContexts(ii, inst, ii.args, ii.props, true);
            }
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
         if (res == null && parentContexts != null) {
            for (SyncContext parCtx:parentContexts) {  // TODO: there should not be conflicts but should we go in reverse order in case - to pick the most specific instance
               res = parCtx.getObjectByName(name, false);
               if (res != null)
                  break;
            }
         }
         if (unwrap && res != null)
            res = getSyncHandler(res).restoreInstance(res);
         return res;
      }

      public Object getObjectByName(String name) {
         return getObjectByName(name, false);
      }

      public void formatExpression(SyncSerializer ser, StringBuilder out, Object value, ArrayList<String> currentObjNames, String currentPackageName, SyncSerializer preBlockCode, SyncSerializer postBlockCode, String varName, boolean inBlock, String uniqueId, List<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
         if (value == null) {
            ser.formatNullValue(out);
            return;
         }
         SyncHandler syncHandler = getSyncHandler(value);
         syncHandler.formatExpression(ser, out, currentObjNames, currentPackageName, preBlockCode, postBlockCode, varName, inBlock, uniqueId, depChanges, syncLayer);
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
         if (parentContexts != null) {
            for (SyncContext parentContext:parentContexts)
               if (parentContext.isRootedObject(obj))
                  return true;
         }
         return false;
      }

      /**
       * The SyncManager maintains the name space for the client/server interaction.
       * Since objects have different lifecycle - scope, and can be stored in different SyncContext objects, that are layered (i.e. request, session, global scope)
       * the name search must go across those scopes.
       * This method looks up the object name, creating one if 'create' is true.  It takes the syncLayer for which we want this name and an optional list of 'dependent changes'
       * i.e. objects which have been lazily instantiated during a given serialization.  We may find the name of the object in that list possibly add new dependencies during this
       * method.
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
                     initOnDemandInst(depChanges, changedObj, ii, false, false, syncLayer);
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

         // Does this object already have a name in a parent SyncContext?
         String parentName = getParentObjectName(changedObj, propName, false, true, null, syncLayer);

         // Doing this again because this might have caused us to be added indirectly and do not want to be added twice.
         ii = syncInsts.get(changedObj);
         if (ii != null && ii.name != null)
            return ii.name;

         if (create) {
            if (depChanges != null) {
               // If the parent sync context has already registered this instance, we'll just add it to this sync context as an inherited object
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
                  //if (verbose)  don't log this at least in the case where the instance exists in a parent context but has not been inherited yet
                  //   System.out.println("No sync instance for: " + objName);
               }
               else
                  ii.setName(objName);
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

      private String getParentObjectName(Object changedObj, String propName, boolean create, boolean unregisteredNames, Object o, SyncLayer syncLayer) {
         String parentName;
         if (parentContexts != null) {
            for (SyncContext parCtx : parentContexts) {
               // Here we don't want to create the object in the underlying context but do want to return even unregistered instances
               parentName = parCtx.getObjectName(changedObj, propName, create, unregisteredNames, null, syncLayer);
               if (parentName != null)
                  return parentName;
            }
         }
         return null;
      }

      private InstInfo getParentInheritedInstInfo(Object changedObj, SyncContextHolder resCtxHolder) {
         InstInfo parentII = null;
         if (parentContexts != null) {
            for (SyncContext parentContext:parentContexts) {
               // Is this an immediate instance of this context - if so, initialize it (and return the specific context it's in
               parentII = parentContext.getInstInfo(changedObj);
               if (parentII != null) {
                  if (!parentII.initialized)
                     parentContext.initOnDemandInst(null, changedObj, parentII, false, false, null);
                  if (resCtxHolder != null)
                     resCtxHolder.ctx = parentContext;
                  return parentII;
               }
               // Still need to check the parents of the parent but by then resCtxHolder is already filled in
               parentII = parentContext.getParentInheritedInstInfo(changedObj, resCtxHolder);
               if (parentII != null) {
                  return parentII;
               }
            }
         }
         return null;
      }

      private String registerInheritedInst(Object changedObj, String parentName, InstInfo ii) {
         if (ii == null) {
            InstInfo parentII = getParentInheritedInstInfo(changedObj, null);
            if (parentII != null) {
               ii = createInheritedInstInfo(changedObj, parentII);
            }
            else {
               ii = new InstInfo(this, null, false, true, false);
            }
            ii.setName(parentName);
            putInstInfo(changedObj, ii);
            objectIndex.put(parentName, changedObj);
         }
         else {
            ii.setName(parentName);
         }
         ii.inherited = true;
         ii.nameQueued = true;
         return parentName;
      }

      private String getParentExistingName(Object obj) {
         if (parentContexts != null) {
            for (SyncContext parentContext:parentContexts) {
               String outerName = parentContext.findExistingName(obj);
               if (outerName != null)
                  return outerName;
            }
         }
         return null;
      }

      public String findObjectName(Object obj) {
         InstInfo ii = syncInsts.get(obj);
         if (ii != null && ii.name != null)
            return ii.name;

         Object outer = DynUtil.getOuterObject(obj);
         if (outer == null) {
            String outerName = getParentExistingName(obj);
            if (outerName != null)
               return outerName;
            initIdMaps();
            return DynUtil.getObjectName(obj, objectIds, typeIdCounts);
         }
         // If the instance was created as an inner instance of an object, we need to use
         // the parent object's name so we can find the enclosing instance of the new guy.
         else {
            String baseName;
            boolean hasFixedId;
            if (obj instanceof IObjectId) {
               baseName = ((IObjectId) obj).getObjectId();
               // Null from getObjectId means that there's no name yet - need to delay synchronizing this object
               if (baseName == null)
                  return null;
               hasFixedId = true;
            }
            else {
               baseName = null;
               hasFixedId = DynUtil.isObjectType(DynUtil.getSType(obj));
               if (!hasFixedId && outer instanceof INamedChildren) {
                  baseName = ((INamedChildren) outer).getNameForChild(obj);
                  if (baseName != null)
                     hasFixedId = true;
               }
               if (baseName == null) {
                  baseName = CTypeUtil.getClassName(DynUtil.getTypeName(DynUtil.getSType(obj), false));
               }
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
         if (obj instanceof IObjectId) {
            return ((IObjectId) obj).getObjectId();
         }

         initIdMaps();
         return DynUtil.getObjectId(obj, null, typeName, objectIds, typeIdCounts);
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

         InstInfo instInfo = syncInsts.get(obj);
         if (instInfo != null) {
            if (instInfo.syncHandler != null)
               return instInfo.syncHandler;
         }


         boolean isType = DynUtil.isType(obj);
         Object type = isType ? obj.getClass() : DynUtil.getType(obj);
         Class handlerClass = syncHandlerRegistry.get(type);
         // For Layer objects they are not types but we do want to use Layer.class to find the handler
         if (handlerClass == null && !isType) {
            handlerClass = syncHandlerRegistry.get(obj.getClass());
         }

         SyncHandler res;
         if (handlerClass == null) {
            res = new SyncHandler(obj, this);
         }
         else
            res = (SyncHandler) DynUtil.createInstance(handlerClass, "Ljava/lang/Object;Lsc/sync/SyncManager$SyncContext;", obj, this);
         if (instInfo != null)
            instInfo.syncHandler = res;
         return res;
      }

      boolean needsSync = false;

      public void setNeedsSync(boolean newNeedsSync) {
         this.needsSync = newNeedsSync;
      }

      public void markChanged() {
         if (!needsSync) {
            setNeedsSync(true);
         }
      }

      public boolean getNeedsSync() {
         return needsSync;
      }

      public void completeSync(SyncContext clientContext, Integer errorCode, String message) {
         if (pendingSyncs == 0)
            System.err.println("*** unmatched complete sync call");
         else {
            --pendingSyncs;
            if (pendingSyncs == 0) {
               commitNewObjNames(clientContext);
               setNeedsSync(false);
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
         if (ii.syncContext != this) {
            // Pretty sure we need to create this here (or perhaps sooner) so that we store the previous values for the
            // specific context that retrieved them. This seems to happen for say global objects which are not on-demand and
            // so not discovered in the context of a reference.
            ii = createInheritedInstInfo(inst, ii);
         }

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
            propVal = copyMutableValue(propVal);
            prevMap.put(propName, propVal);

            //if (trace) {
            //   System.out.println("  " + propName + " = " + DynUtil.getInstanceName(propVal));
            //}
         }
      }

      /*
      private boolean changedInitValue(HashMap<String,Object> initMap, String propName, Object propVal) {
         boolean initValSet = initMap.containsKey(propName);
         Object initVal = initMap.get(propName);
         return (!initValSet || (initVal != propVal && (initVal == null || !DynUtil.equalObjects(initVal, propVal))));
      }
      */

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

      private boolean isParentNewInstance(Object changedObj) {
         if (parentContexts != null) {
            for (SyncContext parentContext:parentContexts) {
               if (parentContext.isNewInstance(changedObj))
                  return true;

            }
         }
         return false;
      }

      public boolean isNewInstance(Object changedObj) {
         InstInfo ii = syncInsts.get(changedObj);
         if (ii != null)
            return !ii.registered && !ii.fixedObject;
         if (isParentNewInstance(changedObj))
            return true;

         // For calling remote methods, we need to support objects which are not synchronized - at least calls to rooted objects.  Those are never new anyway.
         //return !isRootedObject(changedObj);
         return false;
      }

      public ScopeContext getScopeContext() {
         return scope;
      }

      public boolean hasSyncInst(Object inst) {
         return syncInsts.get(inst) != null;
      }

      private void clearParentSyncInst(Object inst, SyncProperties syncProps) {
         if (parentContexts != null) {
            for (SyncContext parentContext : parentContexts) {
               parentContext.clearSyncInst(inst, syncProps);
            }
         }
      }

      void clearSyncInst(Object inst, SyncProperties syncProps) {
         if (syncInsts.get(inst) != null) {
            removeSyncInst(inst, syncProps);
         }
         clearParentSyncInst(inst, syncProps);
      }

      /**
       * This is a lower-level method to add a new sync inst with the most flexibility.  Since it's defined on ScopeContext, it's called after you have selected the scope to manage
       * the lifecycle of this instance.
       * If depChanges is not null, any dependent changes required to initialize this instance are put into the provided depChanges list.  If depChanges is null, dependent changes are added to the end of the current SyncLayer of changes.
       * If initInst is false, we'll register the instance but won't initialize it with the sync system yet.
       * If onDemand is false, the instance is added explicitly to all sync contexts - they will be pushed to listeners on the scope involved
       * If queueNewObj is true, a 'new' or create object is added to the SyncLayer to create this instance on the remote side.
       * Otherwise, it's assumed the remote client will already have created the object, or a new obj has already been queued for this instance (or will be later).
       * Note that it's possible the parent context has already registered this instance due to a super-type call to addSyncInst (when queuing is disabled).
       * We'll add the instance to this sync context, then unless it's on-demand, we initialize it in all child-contexts so they sync it as well.
       */
      public void addSyncInst(List<SyncLayer.SyncChange> depChanges, Object inst, boolean initInst, boolean onDemand, boolean initDefault, boolean resetState, boolean queueNewObj, boolean addPropChanges, int scopeId, SyncProperties syncProps, Object...args) {
         // We may have already registered this object with a parent scope cause the outer instance was not set when the object was created.
         clearParentSyncInst(inst, syncProps);

         if (verbose) // TODO: add some info here about locks held if we have verboseLocks set?
            System.out.println("Add sync inst: " + DynUtil.getInstanceName(inst) + " on thread: " +  DynUtil.getCurrentThreadString());

         InstInfo ii = syncInsts.get(inst);
         if (ii == null) {
            ii = new InstInfo(this, args, initDefault, resetState, onDemand);
            putInstInfo(inst, ii);
         }
         else {
            // Note: we might be updating the args here to a new value.  First the base class addSyncInst is called with it's args, and the last
            // addSyncInst call should be in the constructor used for the concrete class. We pass the InstInfo into the NewObj so it will see the
            // updated args we set here.
            if (args != null && args.length > 0) {
               ii.args = args;
            }
            ii.initDefault = ii.initDefault || initDefault;
            ii.onDemand = ii.onDemand || onDemand;
            ii.inherited = false;
            if (!resetState)
               ii.resetState = false;
         }
         ii.props = syncProps;

         if (!ii.initialized) {
            if (initInst) {
               initSyncInst(depChanges, inst, ii, initDefault, scopeId, syncProps, args, queueNewObj, false, addPropChanges, true, null);
            }
            // On-demand objects still need to have their names registered.  When the client needs to resync, it will try to set on-demand objects
            // which it has loaded but the server has not yet initialized.  We need the name at least to be able to find the instance and then create it on the fly.
            else if (ii.name == null && onDemand && needsInitialSync) {
               registerSyncInstName(depChanges, inst, ii, scopeId, syncProps, false);
            }
         }

         // When adding a new global object which is not marked as on-demand, go and add it to all of the child contexts.  This is the "push new record" operation and so must be synchronized against adding new sessions (though maybe change this to an
         if (!onDemand) {
            initChildContexts(ii, inst, args, syncProps, false);
         }
      }

      private void initChildContexts(InstInfo ii, Object inst, Object[] args, SyncProperties syncProps, boolean addOnDemandChanges) {
         if (childContexts != null) {
            InstInfo childII = null;
            ArrayList<SyncContext> initChildContexts = null;
            ArrayList<InstInfo> childInstInfos = null;
            synchronized (this) {
               for (SyncContext childCtx:childContexts) {
                  childII = childCtx.syncInsts.get(inst);
                  if (childII != null) {
                     // Update the args - from updateRuntimeType we will first register the name, then call the addSyncInst with the args by then, we've already
                     // created the child type.
                     if (args != null)
                        childII.args = args;
                     if (!childII.nameQueued) {
                        childII.nameQueued = true;
                        childII.initialized = true;
                     }
                  }
                  if ((syncProps != null && syncProps.broadcast) || childCtx.scope.isCurrent()) {
                     if (childII == null) {
                        //childII = childCtx.createAndRegisterInheritedInstInfo(inst, ii);
                        //childII.nameQueued = true;
                        //childII.initialized = true;
                        childII = childCtx.registerObjNameOnScope(inst, ii.args, ii.name, ii.fixedObject, true, true, ii.registered, addOnDemandChanges);
                     }
                     if (initChildContexts == null) {
                        initChildContexts = new ArrayList<SyncContext>();
                        childInstInfos = new ArrayList<InstInfo>();
                     }
                     initChildContexts.add(childCtx);
                     childInstInfos.add(childII);
                  }
               }
            }
            // Once we've released the lock on the parent, we may need to initialize children of our children
            if (initChildContexts != null) {
               for (int i = 0; i < initChildContexts.size(); i++) {
                  SyncContext initCtx = initChildContexts.get(i);
                  InstInfo initChildII = childInstInfos.get(i);
                  initCtx.initChildContexts(initChildII, inst, args, syncProps, addOnDemandChanges);
               }
            }
         }
      }

      private void registerSyncInstName(List<SyncLayer.SyncChange> depChanges, Object inst,  InstInfo ii, int scopeId, SyncProperties syncProps, boolean queueNewObj) {
         String syncType = null;
         String objName = null;
         objName = ii.name == null ? findObjectName(inst) : ii.name;

         // IObjectId can return null indicating the name is not available yet.  For on-demand objects, this means we can't sync them until they've
         // been registered which could be a problem for updating those instances lazily after a restart.  It's best to have the id defined
         // when the first addSyncInst call is made.
         if (objName == null)
            return;

         if (!isRootedObject(inst)) {
            ii.setName(objName);
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
               /*
               if (depChanges != null)
                  SyncLayer.addDepNewObj(depChanges, inst, syncProps.syncGroup, ii.args);
               else
               */
                  addNewObj(inst, syncProps.syncGroup, ii);
               ii.nameQueued = true;
            }
            else {
               SyncState state = getSyncState();
               if (state == SyncState.ApplyingChanges || state == SyncState.InitializingLocal) {
                  if (initialSyncLayer != null) {
                     initialSyncLayer.addNewObj(inst, ii, recordInitial && getSyncState() == SyncState.ApplyingChanges);
                  }
               }
            }
         }
         else {
            ii.registered = true;
            ii.nameQueued = true;
            ii.fixedObject = true;
            ii.setName(objName);
            syncType = " object ";
         }
         Object oldInst;
         if ((oldInst = objectIndex.put(objName, inst)) != null && oldInst != inst && verbose) {
            System.out.println("*** Warning: replacing instance of: " + objName + " with different instance");
         }

         if (verbose) {
            String message;
            if (ii.registered || ii.nameQueued) {
               if (ii.onDemand)
                  message = "Synchronizing on-demand instance: ";
               else
                  message = "Synchronizing instance: ";
            }
            else if (ii.onDemand) {
               if (!ii.initialized)
                  message = "Adding on-demand instance: ";
               else
                  message = "Initializing on-demand instance: ";
            }
            else // TODO: any other cases other than remote here?
               message = "Synchronizing remote instance: ";
            System.out.println(message + objName + syncType + (scopeId != 0 ? "scope: " + ScopeDefinition.getScopeDefinition(scopeId).name : "global scope"));
         }
      }

      public InstInfo createAndRegisterInheritedInstInfo(Object inst, InstInfo ii) {
         InstInfo childInstInfo = createInheritedInstInfo(inst, ii);
         putInstInfo(inst, childInstInfo);
         if (ii.name != null) {
            objectIndex.put(ii.name, inst);
         }
         return childInstInfo;
      }

      /** Initializes the instance.  If inherited is true, this instance is stored in a lower-level scope (e.g. we are initializing an instance for a session that's inited already at the global level)
       *   If queueNewObj is true, we add  */
      public void initSyncInst(List<SyncLayer.SyncChange> depChanges, Object inst, InstInfo ii, boolean initDefault, int scopeId, SyncProperties syncProps, Object[] args, boolean queueNewObj, boolean inherited, boolean addPropChanges, boolean initial, SyncLayer syncLayer) {
         Object[] props = syncProps == null ? null : syncProps.getSyncProperties();

         if (initial) {
            if (!inherited) {
               ii.initialized = true;
               registerSyncInstName(depChanges, inst, ii, scopeId, syncProps, queueNewObj);
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
            putInstInfo(inst, ii);
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

         initPropertyListeners(depChanges, inst, ii, props, inherited, syncProps, addPropChanges, initial, syncLayer);
      }

      public void initPropertyListeners(List<SyncLayer.SyncChange> depChanges, Object inst, InstInfo ii, Object[] props, boolean inherited, SyncProperties syncProps, boolean addPropChanges, boolean initial, SyncLayer syncLayer) {
         SyncChangeListener syncListener;
         if (props != null) {
            syncListener = getSyncListenerForGroup(syncProps.syncGroup);
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
                  flags = syncProps.defaultPropOptions;
               }

               if ((flags & SyncPropOptions.SYNC_ON_DEMAND) == 0 || ii.isFetchedOnDemand(propName)) {
                  initProperty(depChanges, inst, ii, propName, flags, inherited, syncProps, addPropChanges, syncListener, initial, syncLayer);
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

         if (trace || verbose || verboseValues) {
            System.out.println("Fetch property: " + DynUtil.getInstanceName(inst) + "." + propName + " scope: " + name + (!needsSync ? " *** first change in sync" : ""));
         }
         markChanged();
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
       * This method lets you start the synchronization of an on-demand property, marked with @Sync(onDemand=true).
       * It can be called for either the client, the server, or both.  It's low cost to call it anytime you realize you
       * need to start synchronizing a property, like when a tree-node is opened.
       *
       * For the server, it adds that property to the synchronized set, so it's value is pushed to the client on the
       * next sync.  For the client, it initiates a "fetchProperty" operation.  The property will be set in processing
       * the results of the next sync.
       */
      public void startSync(Object inst, String propName) {
         InstInfo ii = getInstInfo(inst);
         Boolean od;
         if (ii != null && ii.onDemandProps != null && ((od = ii.onDemandProps.get(propName)) != null) && od)
            return;
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

         if (childSyncInsts != null) {
            Set<InstInfo> childInstInfos = childSyncInsts.get(inst);
            if (childInstInfos != null) {
               for (InstInfo childInstInfo:childInstInfos) {
                  if (childInstInfo.syncContext == this)
                     System.err.println("*** Invalid childInstInfo in startSync!");
                  else {
                     childInstInfo.syncContext.startSync(inst, propName);
                  }
               }
            }
         }
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
               if (initProperty(null, inst, ii, propName, flags, false, syncProps, initOnly, getSyncListenerForGroup(syncProps.syncGroup), true, null))
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
            addChangedValue(null, inst, propName, curValue, syncProps.syncGroup, null);
      }

      private boolean initProperty(List<SyncLayer.SyncChange> depChanges, Object inst, InstInfo ii, String propName,
                                   int flags, boolean inherited, SyncProperties syncProps, boolean addPropChanges,
                                   SyncChangeListener syncListener, boolean initial, SyncLayer syncLayer) {
         boolean changeAdded = false;

         try {
            if ((flags & SyncPropOptions.SYNC_ON_DEMAND) != 0 && initial) {
               // Already initialized - but could have been when the instance itself was not initialized so we still initialize it here.
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

            boolean isConst = (flags & SyncPropOptions.SYNC_CONSTANT) != 0;

            // For clientToServer properties, on the server, need to register the property but don't add a listener or init the value
            if ((flags & SyncPropOptions.SYNC_RECEIVE_ONLY) != 0)
               return false;

            boolean isStatic = (flags & SyncPropOptions.SYNC_STATIC) != 0;
            if (isStatic) {
               // TODO: do we need to support these? If so, we should do some type of initProperty for the static properties in addType
               // Also code to skip removing the listener
               if (trace)
                  System.out.println("*** Not listening for changes on static synchronized property: " + propName);
               return false;
            }

            // Inherited instances will get the changes propagated from the shared sync context so don't listen themselves
            if (!inherited && !isConst)
               Bind.addListener(inst, propName, syncListener, IListener.VALUE_CHANGED_MASK);

            Object curVal = DynUtil.getPropertyValue(inst, propName);

            if (initial)
               addInitialValue(inst, ii, propName, curVal);

            boolean addInitialValue;

            if (!addPropChanges)
               addInitialValue = false;
            else {
               // When the SyncProp is configured internally with a default value, the logic for determining when
               // a value has changed because simpler and more robust. With the SYNC_INIT flag, we might miss a property
               // change that needs to be sent but that happens before the addSyncInst call is made and the listener added.
               boolean hasDefaultValue = syncProps.hasDefaultValue(propName);
               if (hasDefaultValue) {
                  Object defaultValue = syncProps.getDefaultValue(propName);
                  addInitialValue = !DynUtil.equalObjects(defaultValue, curVal);
               }
               else if ((flags & SyncPropOptions.SYNC_INIT) != 0 && curVal != null)
                  addInitialValue = true;
               else
                  addInitialValue = false;
            }

            // On refresh, the initial sync layer will already have the change in the right order - if we re-add it, we mess up the order
            if (addInitialValue) {
               SyncLayer toUse = null;

               // On the server, on demand properties, when initialized act just like regular property changes - i.e. must go into the init layer and the change layer.
               // On the client though, we do not want to record these initial changes in any case.
               if (initial && ((flags & SyncPropOptions.SYNC_ON_DEMAND) != 0 || ii.onDemand)) {
                  addChangedValue(depChanges, inst, propName, curVal, syncProps.syncGroup, syncLayer);
               }
               else {
                  boolean addChange = false;
                  if (initialSync && initial) {
                     toUse = initialSyncLayer;
                     addChange = true;
                  }
                  // The client does not have to record any initial changes since we do not have to refresh the server from the client
                  else if (needsInitialSync) {
                     toUse = getChangedSyncLayer(syncProps.syncGroup);
                     addChange = scope.getScopeDefinition().supportsChangeEvents && scope.isCurrent();
                  }
                  else
                     addChange = true;

                  if (addChange && toUse != null && !toUse.hasExactChangedValue(inst, propName, curVal)) {
                     toUse.addChangedValue(inst, propName, curVal);
                     changeAdded = true;
                  }

                  // These changes have to be sent in the current sync layer, as well as recorded in the initial sync in case we do a reset
                  if (initial && !initialSync) {
                     toUse = initialSyncLayer;
                     if (toUse != null && !toUse.hasExactChangedValue(inst, propName, curVal)) {
                        toUse.addChangedValue(inst, propName, curVal);
                        changeAdded = true;
                     }
                  }
               }
            }

            if (!inherited && !isConst) {
               // Now add the listener for changes made to the property value
               addPropertyValueListener(inst, propName, curVal, syncProps.syncGroup);
            }
         }
         catch (IllegalArgumentException exc) {
            System.err.println("*** Sync property: " + propName + " not found for instance: " + DynUtil.getInstanceName(inst) + ": " + exc);
         }
         return changeAdded;
      }

      public SyncAction getSyncAction() {
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
         SyncAction action = getSyncAction();
         switch (action) {
            case Previous:
               addPreviousValue(syncObj, syncProp, value, true, true);
               break;
            case Value:
               addChangedValue(null, syncObj, syncProp, value, syncGroup, null);
               break;
            case Ignore:
               break;
         }

         /*
         if (childContexts != null) {
            synchronized (this) {
               for (SyncContext childCtx:childContexts) {
                  if (childCtx.syncInsts.get(syncObj) != null)
                     childCtx.recordChange(syncObj, syncProp, value, syncGroup);
               }
            }
         }
         */
         if (childSyncInsts != null) {
            Set<InstInfo> childInstInfos = childSyncInsts.get(syncObj);
            if (childInstInfos != null) {
               for (InstInfo childInstInfo:childInstInfos) {
                  if (childInstInfo.syncContext == this)
                     System.err.println("*** Invalid childInstInfo!");
                  else {
                     childInstInfo.syncContext.valueInvalidatedInternal(syncObj, syncProp, value, syncGroup, action);
                  }
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

      /** Looking for a scope that's either this scope or one that's reachable via it's parentContexts */
      public SyncContext findSyncContextWithScope(int newScopeId) {
         if (scope.getScopeDefinition().scopeId == newScopeId)
            return this;
         if (parentContexts != null) {
            for (SyncContext parentContext:parentContexts) {
               SyncContext res = parentContext.findSyncContextWithScope(newScopeId);
               if (res != null)
                  return res;
            }
         }
         return null;
      }

      public InstInfo findSyncInstInfo(Object changedObj) {
         InstInfo ii = syncInsts.get(changedObj);
         if (ii != null)
            return ii;
         if (parentContexts != null) {
            for (SyncContext parentContext:parentContexts)
               return parentContext.findSyncInstInfo(changedObj);
         }
         return null;
      }

      public String findExistingName(Object changedObj) {
         InstInfo ii = syncInsts.get(changedObj);
         if (ii != null && ii.name != null)
            return ii.name;
         if (parentContexts != null)
            for (SyncContext parentContext:parentContexts)
               return parentContext.findExistingName(changedObj);
         return null;
      }

      public void addDepNewObj(List<SyncLayer.SyncChange> depChanges, Object changedObj, InstInfo instInfo, boolean inherited, boolean queueObj, boolean newRemote, SyncLayer syncLayer) {
         if (!instInfo.fixedObject) {
            if (queueObj)
               SyncLayer.addDepNewObj(depChanges, changedObj, instInfo);
         }
         initOnDemandInst(depChanges, changedObj, instInfo, inherited, newRemote, syncLayer);
      }

      public void initOnDemandInst(List<SyncLayer.SyncChange> depChanges, Object changedObj, InstInfo instInfo, boolean inherited, boolean addPropChanges, SyncLayer syncLayer) {
         SyncProperties props = getSyncPropertiesForInst(changedObj);
         initSyncInst(depChanges, changedObj, instInfo, instInfo == null ? props == null ? false : props.initDefault : instInfo.initDefault, scope == null ? 0 : scope.getScopeDefinition().scopeId, props, instInfo == null ? null : instInfo.args, false, inherited, addPropChanges, true, syncLayer);
      }

      public RemoteResult invokeRemote(String syncGroup, Object obj, Object type, String methName, Object retType, String paramSig, Object[] args) {
         SyncLayer changedLayer = getChangedSyncLayer(syncGroup);
         if (trace || verbose || verboseValues) {
            System.out.println("Remote method call: " + (obj != null ? DynUtil.getInstanceName(obj) : DynUtil.getTypeName(type, false)) + "." + methName + "(" + DynUtil.arrayToInstanceName(args) + ") scope: " + name +
                               (!needsSync ? " *** first change in sync" : ""));
         }
         markChanged();
         return changedLayer.invokeRemote(obj, type, methName, retType, paramSig, args);
      }

      public void addMethodResult(Object ctxObj, Object type, String callId, Object retValue, String exceptionStr) {
         SyncLayer changedLayer = getChangedSyncLayer(null);
         if (exceptionStr != null)
            System.err.println("Remote method: " + name + " - runtime exception: " + exceptionStr);
         if (trace || verbose || verboseValues) {
            System.out.println("Add method result: " + callId + " scope: " + name + (!needsSync ? " *** first change in sync" : ""));
         }
         markChanged();

         changedLayer.addMethodResult(ctxObj, type, callId, retValue, exceptionStr);
      }

      public void setInitialSync(boolean value) {
         initialSync = value;
         if (parentContexts != null) {
            for (SyncContext parentContext:parentContexts) {
               if (!parentContext.isShared)
                  parentContext.setInitialSync(value);
            }
         }
      }

      private InstInfo lookupParentInheritedInstInfo(Object changedObj) {
         if (parentContexts != null) {
            for (SyncContext parentContext:parentContexts) {
               InstInfo ii = parentContext.getInheritedInstInfo(changedObj);
               if (ii != null)
                  return ii;
            }
         }
         return null;
      }

      public InstInfo getInheritedInstInfo(Object changedObj) {
         SyncManager.InstInfo ii = getInstInfo(changedObj);
         if (ii == null)
            ii = lookupParentInheritedInstInfo(changedObj);
         return ii;
      }

      private InstInfo createInheritedInstInfo(Object inst, InstInfo ii) {
         InstInfo newInstInfo = new InstInfo(this, ii.args, ii.initDefault, ii.resetState, ii.onDemand);
         newInstInfo.setName(ii.name);
         newInstInfo.fixedObject = ii.fixedObject;
         // TODO: Do we reuse the change maps or should we clone them so we can track the changes for each session, etc.
         newInstInfo.initialValues = ii.initialValues != null ? (HashMap<String,Object>) ii.initialValues.clone() : null;
         newInstInfo.previousValues = ii.previousValues != null ? (HashMap<String,Object>) ii.previousValues.clone() : null;

         newInstInfo.inherited = true;
         newInstInfo.parContext = ii.syncContext;
         if (ii.onDemandProps != null)
            newInstInfo.onDemandProps = (TreeMap<String,Boolean>) ii.onDemandProps.clone();
         //newInstInfo.nameQueued = true;
         newInstInfo.props = ii.props;
         SyncContext parCtx = ii.syncContext;
         if (parCtx == this) {
            System.err.println("*** Inherited inst info from itself?");
            return ii;
         }
         if (parCtx.childSyncInsts == null) {
            parCtx.childSyncInsts = new IdentityHashMap<Object, Set<InstInfo>>();
         }
         Set<InstInfo> parChildSet = parCtx.childSyncInsts.get(inst);
         if (parChildSet == null) {
            parChildSet = new HashSet<InstInfo>();
            parCtx.childSyncInsts.put(inst, parChildSet);
         }
         parChildSet.add(newInstInfo);
         return newInstInfo;
      }

      /*
      List<SyncContext> getPathToParentContext(SyncContext toFindPar) {
         if (parentContexts == null) {
            System.err.println("*** path to parent not found!");
            return null;
         }
         if (parentContexts.contains(toFindPar))
            return null; // Immediate child

         for (SyncContext curPar:parentContexts) {
            if (curPar.parentContexts != null && curPar.parentContexts.contains(toFindPar)) {
               return Collections.singletonList(curPar);
            }
            else {
               List<SyncContext> parPath = curPar.getPathToParentContext(toFindPar);
               if (parPath != null) {
                  List<SyncContext> path = new ArrayList<SyncContext>(parPath.size() + 1);
                  path.add(curPar);
                  path.addAll(parPath);
                  return path;
               }
            }
         }
         return null;
      }
      */

      public String createOnDemandInst(Object changedObj, List<SyncLayer.SyncChange> depChanges, String varName, SyncLayer syncLayer) {
         SyncManager.InstInfo ii = getInstInfo(changedObj);
         SyncManager.SyncContext curContext = this;
         if (ii == null) {
            SyncContextHolder resHolder = new SyncContextHolder();
            ii = getParentInheritedInstInfo(changedObj, resHolder);
            if (ii != null)
               curContext = resHolder.ctx;
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
            boolean inheritedChange = curContext != this || ii.inherited;
            // We need to queue this change to the remote side if we are inheriting it from another scope but only when those scopes are on the server.  If we
            // are inheriting a global scope into app-global context on the client, we don't need to queue the change back to the server.   But if we are inherited
            // a global into an app-global on the server, we do need to queue it to the client, since this is a new application for that global object.
            boolean newRemote = !inheritedChange || needsInitialSync;
            boolean queueObj = (curInstInfo == null || !curInstInfo.nameQueued) && newRemote;
            addDepNewObj(depChanges, changedObj, ii, inheritedChange, queueObj, newRemote, syncLayer); // Adds the statement to create the object on the other side for this sync layer

            if (curInstInfo == null) {
               curInstInfo = instCtx.getInstInfo(changedObj);
               if (curInstInfo == null)
                  curInstInfo = instCtx.createAndRegisterInheritedInstInfo(changedObj, ii);
            }
            curInstInfo.nameQueued = true;

            // Always need to make sure the window ctx is marked with the queued flag
            if (instCtx != this) {
               InstInfo thisInfo = getInstInfo(changedObj);
               if (!thisInfo.nameQueued) {
                  thisInfo.nameQueued = true;
               }
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
               SyncContext newCtx = curContext.findSyncContextWithScope(newScopeId);
               if (newCtx == null) {
                  System.err.println("*** Unable to find SyncContext with scopeId: " + newScopeId + " for on-demand instance creation of: " + DynUtil.getInstanceName(changedObj));
                  return null;
               }
               newCtx.addSyncInst(depChanges, changedObj, true, true, props.initDefault, true, false, newCtx == curContext, newScopeId, props);
               // If we just added the object to a shared sync context, we still need to initialize it here as inherited.

               if (newCtx != this) {
                  ii = newCtx.getInstInfo(changedObj);
                  addDepNewObj(depChanges, changedObj, ii, true, !ii.nameQueued, true, syncLayer);
                  // Mark this as queued here in this context
                  InstInfo thisCtx = getInstInfo(changedObj);
                  thisCtx.nameQueued = true;
               }
               // If this context is the owner we've already added everything - just need to record the newObj in the changes.
               else {
                  ii = getInheritedInstInfo(changedObj);
                  ii.nameQueued = true;
                  if (!ii.fixedObject)
                     SyncLayer.addDepNewObj(depChanges, changedObj, ii);
               }
               return getObjectName(changedObj, varName, false, false, null, syncLayer);
            }
            // Could handle this else case by just creating the sync properties on the fly.  That would need some limitation though
            // since we don't want to accidentally serialize any types to the client.  Only those that are declared be shared via a
            // particular destination should go.
            else {
               System.err.println("*** Synchronized ref to unsync'd type: " + DynUtil.getTypeName(DynUtil.getTypeOfObj(changedObj), false) + (varName != null ? " from: " + varName : "") +
                      " instance: " + DynUtil.getInstanceName(changedObj) + ": value will be null on the other side");
            }
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


         if (parentContexts != null) {
            for (SyncContext parentContext:parentContexts)
               parentContext.printAllSyncInsts();
         }
      }

      void removePropertyListener(InstInfo toRemove, Object inst, Object prop, SyncProperties syncProps, SyncChangeListener syncListener) {
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
         if ((flags & SyncPropOptions.SYNC_RECEIVE_ONLY) != 0)
            return;
         if ((flags & SyncPropOptions.SYNC_STATIC) != 0)
            return;

         if ((flags & SyncPropOptions.SYNC_ON_DEMAND) == 0 || toRemove.isFetchedOnDemand(propName))
            Bind.removeListener(inst, propName, syncListener, IListener.VALUE_CHANGED_MASK);
      }

      void removeSyncInstFromChildCtxs(Object inst, SyncProperties syncProps, boolean listenersOnly) {
         if (childSyncInsts != null) {
            Set<InstInfo> children = childSyncInsts.get(inst);
            if (children != null) {
               for (InstInfo child:children) {
                  child.syncContext.removeSyncInstInternal(child, inst, syncProps, listenersOnly, false);

                  // And remove it from the children's children
                  child.syncContext.removeSyncInstFromChildCtxs(inst, syncProps, listenersOnly);
               }
            }
         }
      }

      void removeSyncInstInternal(InstInfo toRemove, Object inst, SyncProperties syncProps, boolean listenersOnly, boolean removeFromParentList) {
         if (trace)
            System.out.println("Removing sync inst: " + DynUtil.getInstanceName(inst) + " from scope: " + name + (toRemove.inherited ? " inherited from: " + toRemove.parContext.name : ""));

         // We only add the listeners on the original instance subscription.  So when removing an inherited instance don't remove the listeners
         if (toRemove.initialized) {
            if (!toRemove.inherited) {
               if (syncProps != null) {
                  SyncChangeListener syncListener = getSyncListenerForGroup(syncProps.syncGroup);
                  Object[] props = syncProps.getSyncProperties();
                  if (props != null) {
                     for (Object prop : props) {
                        removePropertyListener(toRemove, inst, prop, syncProps, syncListener);
                     }
                  }
               }
               removePropertyValueListeners(inst);
            }
            removeSyncInstFromChildCtxs(inst, syncProps, listenersOnly);
         }
         if (removeFromParentList && toRemove.inherited) {
            Set<InstInfo> children = toRemove.parContext.childSyncInsts.get(inst);
            if (children == null || !children.remove(toRemove))
               System.err.println("*** Did not find inherited instance to remove for: " + toRemove);
         }

         if (!listenersOnly) {
            if (objectIds != null)
               objectIds.remove(inst);
            if (syncProps != null) {
               SyncLayer changedLayer = getChangedSyncLayer(syncProps.syncGroup);
               changedLayer.removeSyncInst(inst);
            }

            if (initialSyncLayer != null)
               initialSyncLayer.removeSyncInst(inst);
         }

         if (toRemove.valueListener != null) {
            Bind.removeListener(inst, null, toRemove.valueListener, IListener.VALUE_CHANGED_MASK);
            toRemove.valueListener = null;
         }

         if (toRemove.name != null)
            objectIndex.remove(toRemove.name);
      }

      void removeSyncInst(Object inst, SyncProperties syncProps) {
         if (verbose) // TODO: add some info here about locks held if we have verboseLocks set?
            System.out.println("Remove sync inst: " + DynUtil.getInstanceName(inst) + " on thread: " + DynUtil.getCurrentThreadString());

         InstInfo toRemove = syncInsts.remove(inst);
         if (toRemove == null) {
            System.err.println("*** Unable to find sync inst to remove: " + DynUtil.getInstanceName(inst));
         }
         else
            removeSyncInstInternal(toRemove, inst, syncProps, false, true);
      }

      void replaceSyncInst(Object fromInst, Object toInst, SyncProperties syncProps) {
         if (verbose) // TODO: add some info here about locks held if we have verboseLocks set?
            System.out.println("Replace sync inst: " + DynUtil.getInstanceName(toInst) + " on thread: " + DynUtil.getCurrentThreadString());

         InstInfo toRemove = syncInsts.remove(fromInst);
         if (toRemove == null) {
            return;
         }
         // Remove just the listeners.  Keep the changes in place so we can detect changed values when we add the new instance
         removeSyncInstInternal(toRemove, fromInst, syncProps, true, true);

         initSyncInst(null, toInst, toRemove, syncProps.initDefault, syncProps.defaultScopeId, syncProps, toRemove.args, false, toRemove.inherited, true, false, null);
      }

      synchronized void addChildContext(SyncContext childCtx) {
         if (childContexts == null)
            childContexts = new HashSet<SyncContext>();
         childContexts.add(childCtx);
         if (!isShared && childContexts.size() > 1)
            isShared = true;
      }

      public synchronized void scopeDestroyed(ScopeContext scope) {
         SyncContext childSyncCtx = (SyncContext) scope.getValue(SC_SYNC_CONTEXT_SCOPE_KEY);
         if (childSyncCtx != null) {
            if (trace)
               System.out.println("Destroying sync context for scope: " + scope.getScopeDefinition().name + ":" + scope.getId());
            childSyncCtx.disposeContext();
            // Need this to find and remove the sync insts so remove it after we are done.
            scope.setValue(SC_SYNC_CONTEXT_SCOPE_KEY, null);
         }
      }

      public void disposeContext() {
         ArrayList<InstInfo> disposeInfoList = new ArrayList<InstInfo>();
         ArrayList<Object> disposeInstList = new ArrayList<Object>();
         for (Map.Entry<Object,InstInfo> nameEnt: syncInsts.entrySet()) {
            InstInfo instInfo = nameEnt.getValue();
            Object inst = nameEnt.getKey();
            disposeInfoList.add(instInfo);
            disposeInstList.add(inst);

         }
         if (parentContexts != null) {
            for (SyncContext parCtx : parentContexts) {
               if (parCtx.childContexts.remove(this)) {
                  if (trace)
                     System.out.println("Removed sync context: " + this + " from parent: " + parCtx.scope + " remaining: " + parCtx.childContexts.size());
               }
               else
                  System.err.println("Child sync context: " + this + " not found in parent: " + parCtx.scope);
            }
         }
         // The childContexts will be destroyed in the scopeDestroyed callback from the parent
         for (int i = 0; i < disposeInfoList.size(); i++) {
            InstInfo instInfo = disposeInfoList.get(i);
            Object toDispose = disposeInstList.get(i);
            if (instInfo.props == null) {
               if (!instInfo.initialized)
                  continue;
            }
            removeSyncInstInternal(instInfo, toDispose, instInfo.props, true, true);
            // Another problem here is that DB objects can be shared by peer sync contexts - i.e. in a session
            // so disposing of them here is wrong. We really should be just removing the sync inst listeners here
            // I think and maybe only disposing instances that are registered that way in the addSyncInst call.
            // Some elements may recursively dispose others
            /*
            if (syncInsts.get(toDispose) != null) {
               // TODO: Problems with disposing children here:
               // #1 - we might hit the child multiple times - since they might be sync insts
               // #2 - some of the children have not been created yet.  We don't have a way to dispose only the created children and creating them here would require an appId at least.
               // Solution: we should be disposing the root objects in the ScopeContext level I think.  There should be some way to dispose only the created children.
               // After we've disposed the objects at the root level, they should get removed from the sync inst table automatically, but this can be left around to clean up
               // anything that's not reachable from the page.
               DynUtil.dispose(toDispose, false);
            }
            */
         }
         syncInsts.clear();
      }

      /** Called when we receive a property changed either from the data binding event (originalCtx=true) or propagated from a parent context (originalCtx = false) */
      public boolean valueInvalidated(Object obj, String propName, Object curValue, String syncGroup) {
         updatePropertyValueListener(obj, propName, curValue, syncGroup);
         SyncAction action = getSyncAction();
         return valueInvalidatedInternal(obj, propName, curValue, syncGroup, action);
      }

      private boolean valueInvalidatedInternal(Object obj, String propName, Object curValue, String syncGroup, SyncAction action) {
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

         boolean refreshAfterChildren = false;

         // Action == Previous when we are applying a change made already on the client.  We need to find the ScopeContext
         // which corresponds to the client and update it's previous value.
         // that we do not send a dupl
         if (action == SyncAction.Previous) {
            if (scope.getScopeDefinition().supportsChangeEvents && scope.isCurrent())
                addPreviousValue(obj, propName, curValue, true, true);
            // TODO: does this need to be done after we process the child contexts and update the previous value?
            else if (refreshProperty(obj, propName, curValue, syncGroup, "Applying remote change to shared context")) {
                // TODO: do we need to process child contexts here anyway? For example, to potentially cancel a change that's redundant now because we reset the value back on the previous side
                return true;
            }
         }
         else if (action == SyncAction.Value) {
            // Returns true if there are no changes
            if (refreshProperty(obj, propName, curValue, syncGroup, "Property change")) {
               Object remValue;
               if ((remValue = removeChangedValue(obj, propName, syncGroup)) != null) {
                  if (verboseValues) {
                     System.out.println("Removing synced changed value: " + remValue);
                  }
               }
               return true;
            }
         }

         // Send this event to any child contexts which are registered for this instance.
         /*
         if (childContexts != null) {
            synchronized (this) {
               for (SyncContext childCtx:childContexts) {
                  if (childCtx.syncInsts.get(obj) != null)
                     childCtx.valueInvalidated(obj, propName, curValue, syncGroup, false);
               }
            }
         }
         */
         if (childSyncInsts != null) {
            Set<InstInfo> childInstInfos = childSyncInsts.get(obj);
            if (childInstInfos != null) {
               for (InstInfo childInstInfo:childInstInfos) {
                  if (childInstInfo.syncContext == this)
                     System.err.println("*** Invalid childInstInfo!");
                  else {
                     childInstInfo.syncContext.valueInvalidatedInternal(obj, propName, curValue, syncGroup, action);
                  }
               }
            }
         }
         return true;
      }

      private boolean refreshProperty(Object obj, String propName, Object curValue, String syncGroup, String opName) {
         Object prevValue = getPreviousValue(obj, propName);
         // Sometimes getPreviousValue returns arrays when we are comparing against a list so use array equal as well for that case
         if (DynUtil.equalObjects(prevValue, curValue) || arraysEqual(prevValue, curValue)) {
            if (verboseValues) {
               Object change = getChangedValue(obj, propName, syncGroup);
               if (change != null)
                  System.out.println(opName + ": reset to original value: " + DynUtil.getInstanceName(obj) + "." + propName + " = " + DynUtil.getInstanceName(curValue) + " pending change: " + change);
               else
                  System.out.println(opName + ": no change: " + DynUtil.getInstanceName(obj) + "." + propName + " = " + DynUtil.getInstanceName(curValue));
            }
            return true; // Returns true for value has not changed from the previous value on the other side
         }
         addChangedValue(null, obj, propName, curValue, syncGroup, null);
         return false; // value has changed
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
                     if (verboseValues)
                        System.out.println("Default event on object: " + DynUtil.getInstanceName(syncObj) + " syncing changed property: " + propName);
                  }
               }
            }
         }
         refreshSyncPropertiesForChildren(syncObj, syncGroup);
      }

      public void refreshSyncPropertiesForChildren(Object syncObj, String syncGroup) {
         if (childSyncInsts != null) {
            Set<InstInfo> childInstInfos = childSyncInsts.get(syncObj);
            if (childInstInfos != null) {
               for (InstInfo childInstInfo:childInstInfos) {
                  if (childInstInfo.syncContext == this)
                     System.err.println("*** Invalid childInstInfo!");
                  else {
                     childInstInfo.syncContext.refreshSyncProperties(syncObj, syncGroup);
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
      }

      public String toString() {
         return "syncCtx:" + (scope == null ? super.toString() : scope.toString());
      }

      public int compareTo(Object o) {
         if (!(o instanceof SyncContext))
            return -1;
         return scope.getScopeDefinition().scopeId - ((SyncContext) o).scope.getScopeDefinition().scopeId;
      }

      private ArrayList<SyncContext> getSortedParentList() {

         // List of all parents of the given scope ordered manually by the scopeId.  In general, this graph
         // should not be too big and should be carefully crafted to define valid dependencies - e.g.
         // appGlobal should be ahead of session state.
         ArrayList<SyncContext> parCtxList = new ArrayList<SyncContext>();

         ArrayList<SyncContext> parentCtxs = parentContexts;
         if (parentCtxs != null) {
            addAllParentContexts(parentCtxs, parCtxList);
         }
         Collections.sort(parCtxList);
         removeDuplicateContexts(parCtxList);
         return parCtxList;
      }

      public ArrayList<SyncLayer> getChangedSyncLayers(String syncGroup) {
         ArrayList<SyncContext> ctxList = getSortedParentList();
         ctxList.add(this);
         ArrayList<SyncLayer> changedLayers = new ArrayList<SyncLayer>();
         for (int i = 0; i < ctxList.size(); i++) {
            SyncContext nextCtx = ctxList.get(i);
            if (syncGroup == SYNC_ALL) {
               for (String group:getSyncGroups()) {
                  SyncLayer changedLayer = nextCtx.getChangedSyncLayer(group);
                  changedLayers.add(changedLayer);
               }
            }
            else {
               SyncLayer changedLayer = nextCtx.getChangedSyncLayer(syncGroup);
               changedLayers.add(changedLayer);
            }
         }
         return changedLayers;
      }

      /** Returns the object instance with the given name - for runtime lookup. */
      public Object resolveObject(String currentPackage, String name, boolean create, boolean unwrap) {
         // If we are ApplyingChanges and resolve an object for the first time, the state needs to be set back to RecordingChanges
         // or we assume the initial state of the new component is already on the other side
         SyncManager.SyncState oldState = SyncManager.setSyncState(SyncManager.SyncState.RecordingChanges);
         try {
            Object inst = getObjectByName(name, unwrap);
            String fullPathName = null;
            if (inst == null && currentPackage != null)
               inst = getObjectByName(fullPathName = CTypeUtil.prefixPath(currentPackage, name), unwrap);
            if (inst == null) {
               inst = ScopeDefinition.resolveName(name, true, true);
               if (inst == null && fullPathName != null) {
                  inst = ScopeDefinition.resolveName(fullPathName, true, true);
               }
            }
            if (inst != null) {
               /* TODO: security: also would need to check if there are any remote methods on this type... but does it matter?   We will
                  check if the property or method itself is synchronized so maybe it's ok to allow the object to be resolved?  It does potentially give away
                  internal information if we don't do this though.
               if (!syncDestination.clientDestination && getSyncPropertiesForInst(inst) == null) {
                  System.err.println("Resolve instance not allowed for: " + inst + " type: " + DynUtil.getSType(inst) + " for sync context: " + this);
               }
               */
               return inst;
            }
            // Check framework specific name spaces - e.g. we want to lazily create tag objects from the server nodes created in the DOM space
            if (frameworkNameContexts != null) {
               for (INameContext resolver:frameworkNameContexts) {
                  inst = resolver.resolveName(name, create, true);
                  if (inst != null)
                     return inst;
               }
            }
            // It's possible that we are resolving a method return reference for a sync object that
            // has just been deleted by changes in the previous sync - e.g. deleteIcon.click() on an
            // element of the list.
            for (SyncLayer syncLayer: changedLayersByGroup.values()) {
               inst = syncLayer.pendingMethodObjs.get(fullPathName == null ? name : fullPathName);
               if (inst != null)
                  return inst;
            }
         }
         finally {
            SyncManager.setSyncState(oldState);
         }
         return null;
      }

      /** Returns the object instance with the given name - for runtime lookup. */
      public Object resolveOrCreateObject(String currentPackage, Object outerObj, String name, String typeName, boolean unwrap, String sig, Object...args) {
         Object inst = null;
         boolean flushSyncQueue = beginSyncQueue();

         try {
            // For TodoList.TodoItem we do want the prefix here but for UIIcon - the type which contains static UIIcons we don't want UIIcon.UIIcon__0
            if (outerObj != null && !DynUtil.isType(outerObj)) {
               String outerName = findObjectName(outerObj);
               if (outerName != null)
                  name = outerName + '.' + name;
            }
            else
               name = CTypeUtil.prefixPath(currentPackage, name);

            // Passing null here for currentPackage because we already handled currentPackage right above
            inst = resolveObject(null, name, false, unwrap);
            if (inst != null)
               return inst;
            Object type = DynUtil.findType(typeName);
            if (type == null) {
               System.err.println("No type: " + typeName + " for new sync instance");
               return null;
            }
            inst = DynUtil.newInnerInstance(type, outerObj, sig, args);
            boolean isClient = getSyncManager().syncDestination.clientDestination;
            if (!isClient && !allowCreate(type)) {
               System.err.println("Create type not allowed for: " + typeName + " for sync context: " + this);
               return null;
            }
            // Because we always use this to 'receive' a new instance set registered in this SyncContext to keep us from trying to send it back
            registerObjName(inst, args, name, isClient, true, true);
         }
         finally {
            if (flushSyncQueue)
               flushSyncQueue();
         }
         return inst;
      }

      public boolean hasCurrentScope() {
         if (scope.isCurrent())
            return true;
         if (childContexts != null) {
            synchronized (this) {
               for (SyncContext childCtx:childContexts) {
                  if (childCtx.hasCurrentScope())
                     return true;
               }
            }
         }
         return false;
      }

      public boolean matchesTypeFilter(Set<String> syncTypeFilter, String typeName) {
         if (syncTypeFilter == null)
            return true; // Warning! - a null syncTypeFilter means the filter is disabled. The client trusts the server with no filter so this is a common use case
         if (syncTypeFilter.contains(typeName))
            return true;
         return globalSyncTypeNames != null && globalSyncTypeNames.contains(typeName);
      }

      public void changeInstName(Object inst, String oldName, String newName, boolean queueEvent) {
         InstInfo ii = syncInsts.get(inst);
         if (ii == null)
            return;
         // Don't change the name here - instead, wait until we actually serialize this change so that refs before
         // the change use the old name and refs after use the new.
         //ii.name = newName;
         //ii.oldName = oldName;
         Object oldInst = objectIndex.put(newName, inst);
         if (oldInst != null) {
            if (oldInst == inst) {
               System.err.println("*** Warning - changeInstName - object already found with name in index");
               return;
            }
            System.err.println("*** Warning - changeInstName - replaced other object in index");
         }

         if (queueEvent) {
            SyncLayer useLayer = getChangedSyncLayer(ii.props.syncGroup);
            useLayer.addNameChange(inst, oldName, newName);

            if (trace || verbose || verboseValues) {
               System.out.println("Change name of: " + DynUtil.getInstanceName(inst) + " from: " + oldName + " to: " + newName + (!needsSync ? " *** first change in sync" : ""));
            }
            markChanged();
         }
      }

      public void setResetStateEnabled(Object inst, boolean enabled, boolean queueChange) {
         InstInfo ii = syncInsts.get(inst);
         if (ii == null)
            return;

         if (ii.resetState == enabled)
            return;

         if (!enabled && queueChange) {
            SyncLayer useLayer = getChangedSyncLayer(ii.props == null ? null : ii.props.syncGroup);
            useLayer.addClearResetState(inst, ii.name);
         }

         ii.resetState = enabled;

         if (queueChange) {
            if (trace || verbose || verboseValues) {
               System.out.println((enabled ? "Enable" : "Disable") + " reset state for: " + DynUtil.getInstanceName(inst) + " for scope: " + this);
            }
            markChanged();
         }
      }

      /**
       * Called from the deserializer when we receive a name change from the remote side. It will apply it to the
       * local sync data structure and send back the ack
       */
      public void receiveNameChange(String oldName, String newName) {
         Object inst = objectIndex.get(oldName);
         if (inst == null)
            System.err.println("*** receiveNameChange - object not found to change name from: " + oldName + " to: " + newName);
         else {
            InstInfo ii = syncInsts.get(inst);
            if (ii == null) {
               System.err.println("*** receiveNameChange - index out of sync");
               return;
            }
            ii.name = newName;
            objectIndex.put(newName, inst);
            objectIndex.remove(oldName);

            SyncProperties props = getSyncPropertiesForInst(inst);

            SyncLayer useLayer = getChangedSyncLayer(props == null ? null : props.syncGroup);
            useLayer.addNameChangeAck(inst, oldName, newName);

            if (trace || verbose || verboseValues) {
               System.out.println("Acknowledging name change of: " + DynUtil.getInstanceName(inst) + " from: " + oldName + " to: " + newName + (!needsSync ? " *** first change in sync" : ""));
            }
            markChanged();
         }
      }

      /** Called from the deserializer when we receive the name change ack */
      public void nameChangeAck(String oldName, String newName) {
         Object inst = objectIndex.get(oldName);
         if (inst == null)
            System.err.println("*** receiveNameChange - object not found to change name from: " + oldName + " to: " + newName);
         else {
            InstInfo ii = syncInsts.get(inst);
            if (ii == null)
               return;
            if (ii.name != null && ii.name.equals(newName) && ii.oldName != null && ii.oldName.equals(oldName)) {
               objectIndex.remove(oldName);
               ii.oldName = null;
               if (trace || verbose || verboseValues) {
                  System.out.println("Completed name change of: " + DynUtil.getInstanceName(inst) + " from: " + oldName + " to: " + newName);
               }
            }
            else
               System.err.println("*** confirmNameChange for instance out of sync");
         }
      }

      public void updateInstName(Object inst, String oldName, String newName) {
         InstInfo ii = syncInsts.get(inst);
         if (ii == null)
            return;
         ii.name = newName;
         ii.oldName = oldName;
      }

      public void clearResetState(Object inst) {
         if (initialSyncLayer != null)
            initialSyncLayer.removeSyncInst(inst);
      }

      public void clearAllResetState() {
         initialSyncLayer = new SyncLayer(this);
         initialSyncLayer.initialLayer = true;
      }
   }

   public boolean allowCreate(Object type) {
      SyncProperties syncProps = getSyncProperties(type);
      return syncProps == null || syncProps.allowCreate;
   }

   public boolean allowInvoke(Object method) {
      if (!syncDestination.clientDestination) {
         String remoteRuntimes = (String) DynUtil.getAnnotationValue(method, "sc.obj.Remote", "remoteRuntimes");
         if (remoteRuntimes == null)
            return false;
         String[] remoteRuntimeArr = remoteRuntimes.split(",");
         for (String remoteRuntime:remoteRuntimeArr)
            if (remoteRuntime.equals(syncDestination.remoteRuntimeName))
               return true;
      }
      else
         return true; // The server is free to call any method on the client
      return false;
   }

   static class SyncListenerInfo {
      TreeMap<String,PropertyValueListener> valList = new TreeMap<String,PropertyValueListener>();
   }

   public SyncManager(SyncDestination dest) {
      syncDestination = dest;
      destinationName = dest.name;
   }

   public static SyncManager addSyncDestination(SyncDestination syncDest) {
      if (syncDest.name == null)
         throw new IllegalArgumentException("Must set the name property on the SyncDestination class: " + syncDest);
      syncDest.initSyncManager();
      SyncManager mgr = syncDest.syncManager;
      syncManagersByDest.put(syncDest.name, mgr);
      syncManagers.add(mgr);
      mgr.syncTypes.putAll(globalSyncTypes);
      return mgr;
   }

   public static SyncManager getSyncManager(String destName) {
      return syncManagersByDest.get(destName);
   }

   public static boolean isSyncedPropertyForTypeName(String typeName, String propName, boolean forClient) {
      Object type = DynUtil.resolveName(typeName, false, true);
      if (type != null)
         return isSyncedProperty(type, propName, forClient);
      return false;
   }

   public static boolean isSyncedProperty(Object type, String propName, boolean forClient) {
      for (SyncManager mgr:syncManagers) {
         if (mgr.isSynced(type, propName, forClient))
            return true;
      }
      return false;
   }

   public boolean isSynced(Object type, String propName, boolean forClient) {
      do {
         SyncProperties props = getSyncProperties(type);
         if (props != null && props.isSynced(propName, forClient)) {
            return true;
         }
         // Need to check the extends type here for cases like when sync'ing properties in a sub-class of a tag object.
         // Right now, the SyncProperties created for the subclass (e.g. FormView.visible) will not include a reference to
         // the base type as a chainedType and so doesn't see all of the properties. We register the sync properties for the
         // Element.class base class manually so they are not visible to the code gen.
         // TODO: It might be faster to figure this out  at compile time?
         type = DynUtil.getExtendsType(type);
      } while (type != null);
      return false;
   }

   public static SyncProperties getSyncProperties(Object type, String destName) {
      SyncManager syncMgr = getSyncManager(destName);
      if (syncMgr == null) {
         if (destName == null) {
            for (SyncManager mgr:syncManagers) {
               SyncProperties sp = mgr.getSyncProperties(type);
               if (sp != null && sp.destName == null)
                  return sp;
            }
         }
         return null;
      }
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
    *  <p>Flags can be "0" for the default or OR together the values SyncOption.SYNC_INIT_DEFAULT and SyncOption.SYNC_CONSTANT.
    *  INIT_DEFAULT specifies that all properties are sync'd on init unless overridden by specifying SyncPropOptions.
    *  The CONSTANT value specifies that the state of the object is immutable.  No listeners will be added.  Instead the value is sync'd as a value object.</p>
    */
   public static void addSyncType(Object type, String syncGroup, Object[] props, String destName, int flags) {
      addSyncType(type, new SyncProperties(destName, syncGroup, props, flags));
   }

   public static void initStandardTypes() {
      // Allows serialization of references to java.lang.Class objects through synchronization
      SyncManager.addSyncType(ClassSyncWrapper.class, new SyncProperties(null, null, new Object[] {}, null, SyncPropOptions.SYNC_INIT | SyncPropOptions.SYNC_CONSTANT, 0));
   }

   public static boolean isSyncedType(Object type) {
      // If no specific destination is given, register it for all destinations
      for (SyncManager mgr:syncManagers) {
         if (mgr.syncTypes.containsKey(type))
            return true;
      }
      return false;
   }

   public static void addSyncType(Object type, SyncProperties props) {
      // If no specific destination is given, register it for all destinations
      if (props.destName == null) {
         for (SyncManager mgr:syncManagers) {
            mgr.addNewSyncType(type, props);
         }
         // Keep track of all global sync types added in case the SyncManager has not yet been created
         globalSyncTypes.put(type, props);
      }
      else {
         SyncManager syncMgr = getSyncManager(props.destName);
         if (syncMgr == null) {
             throw new IllegalArgumentException("*** No sync destination registered for: " + props.destName);
         }
         syncMgr.addNewSyncType(type, props);
      }
   }

   public static void replaceSyncType(Object oldType, Object newType, SyncProperties props) {
      // If no specific destination is given, register it for all destinations
      if (props.destName == null) {
         for (SyncManager mgr:syncManagers) {
            mgr.doReplaceSyncType(oldType, newType, props);
         }
         // Keep track of all global sync types added in case the SyncManager has not yet been created
         globalSyncTypes.remove(oldType);
         globalSyncTypes.put(newType, props);
      }
      else {
         SyncManager syncMgr = getSyncManager(props.destName);
         if (syncMgr == null) {
            throw new IllegalArgumentException("*** No sync destination registered for: " + props.destName);
         }
         syncMgr.doReplaceSyncType(oldType, newType, props);
      }
   }

   private void doReplaceSyncType(Object oldType, Object newType, SyncProperties newProps) {
      SyncProperties oldProps = syncTypes.remove(oldType);
      if (oldProps == null) {
         System.err.println("*** replace sync type - no sync properties for old type:" + oldType);
      }

      // In some cases we've updated a type in the chain - so the sync properties have changed but the type is the same type
      SyncProperties otherOld = syncTypes.put(newType, newProps);

      if (oldProps == null && otherOld != null)
         oldProps = otherOld;

      if (verbose)
         System.out.println("Replacing sync type: " + DynUtil.getTypeName(newType, false));

      updateSyncProperties(newType, oldProps, newProps);
   }

   private void addNewSyncType(Object type, SyncProperties syncProps) {
      SyncProperties old = syncTypes.put(type, syncProps);
      updateSyncProperties(type, old, syncProps);

      if (verbose && old == null) {
         System.out.println("New synchronized type: " + DynUtil.getInstanceName(type) + " properties: " + syncProps);
      }

      // TODO: do we need to support static synchronized properties? We could basically call addSyncInst here with the type as the inst
      // but there's the issue of how to support sharing and synchronization. Using a separate instance to hold the
      // static properties lets you put that object into different scopes etc. Maybe there's a use case in server-to-server synchronization?
      if (syncProps.staticProps != null && old == null) {
         System.err.println("*** Static synchronized properties not implemented: " + DynUtil.getTypeName(type, false) + ": " + Arrays.asList(syncProps.staticProps));
      }
   }

   private void updateSyncProperties(Object type, SyncProperties old, SyncProperties syncProps) {
      if (old != null && old != syncProps && !old.equals(syncProps)) {
         SyncContext ctx = syncProps.defaultScopeId == -1 ? getDefaultSyncContext() : getSyncContext(syncProps.defaultScopeId, false);
         if (ctx != null) {
            // TODO: only considering new properties added to the sync type - this would happen if we updated the type to add a field for example.
            // This might cause some errors if we try to remove listeners from properties that got removed but don't see any other reason to remove them here
            Object[] newProps = syncProps.getNewSyncProperties(old);
            if (newProps != null && newProps.length > 0) {
               for (Map.Entry<Object,InstInfo> ent:ctx.syncInsts.entrySet()) {
                  InstInfo ii = ent.getValue();
                  if (ii.props == old) {
                     ii.props = syncProps;
                     ctx.initPropertyListeners(null, ent.getKey(), ii, newProps, false, syncProps, false, true, null);
                  }
               }
               if (verbose)
                  System.out.println("Added listeners for new sync'd properties: " + Arrays.asList(newProps));
            }
         }
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
         ctx.refreshSyncPropertiesForChildren(syncObj, syncGroup);
         return true;
      }
   }

   /**
    * This is the listener which handles basic property changes on synchronized properties.  We might add the change
    * to the sync layer, or add it to the previous layer (if applying a remote change).   We also likely record the change
    * in the "initial sync layer" - which records the current state of the system for a client state refresh.
    */
   @Sync(syncMode= SyncMode.Disabled, includeSuper=true)
   public class SyncChangeListener extends AbstractListener {
      public String syncGroup;
      public SyncContext ctx;

      public SyncChangeListener(String sg, SyncContext ctx) {
         syncGroup = sg;
         this.ctx = ctx;
      }

      /* TODO: listen for array element change.  Record those in the SyncLayer, serialize them as List.add/set calls with either a primitive value, or if it's a new object,
       * first create and populate the object, then do the setX using local variables in the statement block */

      /* This runs during the invalidate phase because we need the sync listener to run before other properties are changed as triggered by the firing of this property.  The sync listener can then order the changes in the way they happened. */
      public boolean valueInvalidated(Object obj, Object prop, Object eventDetail, boolean apply) {
         String propName = PBindUtil.getPropertyName(prop);
         Object curValue = DynUtil.getPropertyValue(obj, propName);

         return ctx.valueInvalidated(obj, propName, curValue, syncGroup);
      }

      public String toString() {
         return "sync change listener for scope: " + ctx.name;
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
      /** We are applying changes from the other side.  Nothing is recorded in this mode, unless we are processing a side-effect change from a data binding expression needs recording */
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

   /**
    * Start queuing sync events on this thread - call flushSyncQueue to invoke them again.  This is particularly helpful
    * when the state required to register sync insts is not available right when the instance is created.
    */
   public static boolean beginSyncQueue() {
      if (PTypeUtil.getThreadLocal("syncAddQueue") != null)
         return false;
      PTypeUtil.setThreadLocal("syncAddQueue", new LinkedHashSet<AddSyncInfo>());
      return true;
   }

   /** Flushes sync events queued from beginSyncQueue */
   public static boolean flushSyncQueue() {
      LinkedHashSet<AddSyncInfo> queue = (LinkedHashSet<AddSyncInfo>) PTypeUtil.getThreadLocal("syncAddQueue");
      if (queue == null)
         return false;
      // First disable queueing
      PTypeUtil.setThreadLocal("syncAddQueue", null);

      // Process the original addSyncInst calls now in order
      for (AddSyncInfo asi:queue) {
         addSyncInst(asi.inst, asi.onDemand, asi.initDefault, asi.resetState, asi.scopeName, asi.props, asi.args);
      }
      return true;
   }

   static class AddSyncInfo {
      Object inst;
      boolean onDemand;
      boolean initDefault;
      boolean resetState;
      String scopeName;
      Object[] args;
      SyncProperties props;

      AddSyncInfo(Object i, boolean od, boolean initDef, boolean resetState, String scopeName, SyncProperties props, Object[] args) {
         this.inst = i;
         this.onDemand = od;
         this.initDefault = initDef;
         this.resetState = resetState;
         this.scopeName = scopeName;
         this.props = props;
         this.args = args;
      }
   }

   /**
    * Registers a new instance for synchronization.  You should have previously registered the type of
    * object using addSyncType - to specify the properties to synchronize on this object.
    * <p>
    * This method is used often in code-generation or you can
    * call it to manually implement synchronization without code-generation.
    * <p>
    * If a scopeName is supplied as null, we look for the scopeName annotation
    * on the instance or any outer objects to find the scope where this object should be registered.
    * <p>
    * If onDemand is true, this object is not immediately serialized to the other side - instead it's only serialized
    * the first time it is fetched or referenced by another object which is serialized.
    * <p>
    * If initDefault is true, the remote side is initialized with properties from this side even with no property change
    * <p>
    * If resetState is false, this instance is not treated as resetState even if it has 'resetState' properties. Persistent
    * instances set resetState=false, transient set it to true (the default) so that resetState flags are used.
    * <p>
    * If props is null, the SyncProperties registered using addSyncType for this instances type are used to determine which properties are synchronized
    * along with any property specific options.
    * <p>
    * Any constructor args needed to rebuild the instance should be provided here and those objects must also
    * be manageable by the sync system (e.g. primitives or that have a SyncProperties registered via addSyncType).
    */
   public static void addSyncInst(Object inst, boolean onDemand, boolean initDefault, boolean resetState, String scopeName, SyncProperties props, Object ...args) {
      // TODO: should we use a linked-map for the sync so we can replace a previous entry for the same instance.
      // If a base class is synchronized on a different scope than the sub-class and a sync queue is enabled, this prevents
      // us from temporarily initializing the instance in the wrong scope which is not a safe thing to do
      LinkedHashSet<AddSyncInfo> syncQueue = (LinkedHashSet<AddSyncInfo>) PTypeUtil.getThreadLocal("syncAddQueue");
      if (syncQueue != null) {
         syncQueue.add(new AddSyncInfo(inst, onDemand, initDefault, resetState, scopeName, props, args));
         return;
      }

      // The compile time representation may have a defined scope but if not, we need to search up the object hierarchy for the
      // scope annotation.  If we inherit an inner object from a base class and apply a scope, we need that scope to take effect.
      if (scopeName == null)
         scopeName = DynUtil.getScopeName(inst);
      ScopeDefinition def = getScopeDefinitionByName(scopeName);
      addSyncInstInternal(inst, onDemand, initDefault, resetState, def.scopeId, props, args);
   }

   private static void addSyncInstInternal(Object inst, boolean onDemand, boolean initDefault, boolean resetState, int scopeId, SyncProperties props, Object...args) {
      Object type = DynUtil.getType(inst);

      boolean found = false;

      for (SyncManager syncMgr:syncManagers) {
         SyncProperties syncProps = props != null ? props : syncMgr.getSyncProperties(type);
         if (syncProps != null) {
            syncMgr.addSyncInstCtx(inst, onDemand, initDefault, resetState, scopeId, syncProps, args);
            found = true;
         }
      }
      if (!found && verbose)
         System.out.println("*** Warning: SyncManager.addSyncInst called with type not registered to any destination " + DynUtil.getTypeName(type, false));
   }

   public static void changeInstName(Object inst, String oldName, String newName) {
      List<ScopeDefinition> scopeDefs = ScopeDefinition.getActiveScopes();
      if (scopeDefs == null)
         return;

      // We do not know the app ids which this instance is registered under right now so we have to do a bit of searching to find
      // the sync contexts (if any) which have it registered.
      for (ScopeDefinition scopeDef:scopeDefs) {
         ScopeContext scopeCtx = scopeDef.getScopeContext(false);
         if (scopeCtx == null)
            continue;
         SyncContext syncCtx = (SyncContext) scopeCtx.getValue(SC_SYNC_CONTEXT_SCOPE_KEY);
         if (syncCtx != null && syncCtx.hasSyncInst(inst)) {
            syncCtx.changeInstName(inst, oldName, newName, scopeDef.supportsChangeEvents);
         }
      }
   }

   public static void setResetStateEnabled(Object inst, boolean enabled) {
      List<ScopeDefinition> scopeDefs = ScopeDefinition.getActiveScopes();
      if (scopeDefs == null)
         return;

      // We do not know the app ids which this instance is registered under right now so we have to do a bit of searching to find
      // the sync contexts (if any) which have it registered.
      for (ScopeDefinition scopeDef:scopeDefs) {
         ScopeContext scopeCtx = scopeDef.getScopeContext(false);
         if (scopeCtx == null)
            continue;
         SyncContext syncCtx = (SyncContext) scopeCtx.getValue(SC_SYNC_CONTEXT_SCOPE_KEY);
         if (syncCtx != null && syncCtx.hasSyncInst(inst)) {
            syncCtx.setResetStateEnabled(inst, enabled, scopeDef.supportsChangeEvents);
         }
      }
   }

   public SyncContext newSyncContext(String name) {
      return new SyncContext(name);
   }

   /** Use this if you create a new object which you know to be created by your component on the other side of the destination.  By default
    * objects which are created outside of a sync operation will be "new" objects that get sent via an object tag, not a modify operator.
    * But for repeat nodes, etc. which are sync'd by the framework itself, those should not come across as new, just a modify. */
   public static boolean registerSyncInst(Object inst) {
      String scopeName = DynUtil.getScopeName(inst);
      SyncContext syncCtx;
      if (scopeName == null)
         syncCtx = getDefaultSyncContext();
      else
         syncCtx = getSyncContext(null, scopeName, true);

      if (syncCtx != null)
         return syncCtx.registerSyncInst(inst);
      else if (scopeName != null && verbose) {
         if (getSyncState() != SyncState.Disabled)
            System.err.println("*** No sync context for scope: " + scopeName + " available to register instance: " + DynUtil.getInstanceName(inst));
      }
      return false;
   }

   /**
    * This method is used to register an instance and it's children with the sync system so the new object requests are not
    * sent to the remote side. For example, a repeat tag or other UI component created on both client and server might have
    * synchronized sub-objects somewhere in the tree.
    * Calling this method marks the tree of declarative instances as "not new". If you see these 'new' operations coming across
    * for child components, either the object needs to be synchronized or registered as available either directly or through
    * it's parent using this method.
    */
   public static void registerSyncTree(Object res) {
      SyncManager.registerSyncInst(res);
      // Need to register this entire tree with the sync system, at least as far as it is sync'd.
      Object[] children = DynUtil.getObjChildren(res, null, true);
      if (children != null) {
         for (Object child:children) {
            registerSyncTree(child);
         }
      }
   }

   public static boolean accessSyncInst(Object inst, String scopeName) {
      SyncContext syncCtx;
      if (scopeName == null)
         syncCtx = getDefaultSyncContext();
      else
         syncCtx = getSyncContext(null, scopeName, true);
      if (syncCtx != null)
         return syncCtx.accessSyncInst(inst);
      return false;
   }

   public static SyncContext getSyncContext(String destName, String scopeName, boolean create) {
      ScopeDefinition def = DynUtil.getScopeByName(scopeName);
      if (def == null) {
         System.err.println("*** No scope defined with name: " + scopeName);
         return null;
      }
      if (destName != null) {
         SyncManager syncMgr = getSyncManager(destName);
         return syncMgr.getSyncContext(def.scopeId, create);
      }
      else {
         ScopeContext scopeCtx = def.getScopeContext(create);
         // TODO: do we need to create it here?
         SyncContext res = (SyncContext) scopeCtx.getValue(SC_SYNC_CONTEXT_SCOPE_KEY);
         return res;
      }
   }

   // TODO: should this be called "getCurrentSyncContext" - it takes the scopeId and looks up using the thread-local scope definition
   // to find the current scope and then find the SyncContext from the scope (if any).
   private SyncContext getSyncContext(int scopeId, boolean create) {
      if (scopeId == 0)
         return getRootSyncContext();
      ScopeDefinition scopeDef = ScopeDefinition.getScopeDefinition(scopeId);
      if (scopeDef == null) {
         System.err.println("*** No scope defined for synchronized instance with scopeId: " + scopeId);
         return null;
      }
      ScopeContext scopeCtx = scopeDef.getScopeContext(create);
      if (scopeCtx == null)
         return null;
      return getSyncContextFromScopeContext(scopeCtx, create);
   }

   private SyncContext getSyncContextFromScopeContext(ScopeContext scopeCtx, boolean create) {
      if (scopeCtx == null) {
         System.err.println("*** No scope to create sync context");
         return null;
      }
      ScopeDefinition scopeDef = scopeCtx.getScopeDefinition();
      SyncContext syncCtx = (SyncContext) scopeCtx.getValue(SC_SYNC_CONTEXT_SCOPE_KEY);
      if (syncCtx == null && create) {
         syncCtx = newSyncContext(scopeDef.name);
         syncCtx.scope = scopeCtx;
         scopeCtx.setValue(SC_SYNC_CONTEXT_SCOPE_KEY, syncCtx);
         ArrayList<ScopeDefinition> parentScopes = scopeDef.getParentScopes();
         if (parentScopes != null) {
            for (ScopeDefinition parentScope : parentScopes) {
               SyncContext parCtx;
               if (parentScope != null) {
                  parCtx = getSyncContext(parentScope.scopeId, true);
                  if (parCtx != null) {
                     parCtx.addChildContext(syncCtx);
                     syncCtx.addParentContext(parCtx);
                  }
               }
            }
         }
         if (syncCtx.parentContexts == null || syncCtx.parentContexts.size() == 0) {
            SyncContext parCtx = getRootSyncContext();
            syncCtx.addParentContext(parCtx);
            parCtx.addChildContext(syncCtx);
         }
         scopeCtx.setDestroyListener(getRootSyncContext());
      }
      return syncCtx;
   }

   public final static String SC_SYNC_CONTEXT_SCOPE_KEY = "sc.SyncContext";

   private void addSyncInstCtx(Object inst, boolean onDemand, boolean initDefault, boolean resetState, int scopeId, SyncProperties syncProps, Object...args) {
      SyncContext ctx;

      ctx = getSyncContext(scopeId, true);

      if (ctx != null) {
         /*
         Object outer = DynUtil.getOuterObject(inst);
         if (outer != null) {
            Object outerType = DynUtil.getType(outer);
            SyncProperties outerProps = getSyncProperties(outerType);
            // If the parent is synchronized and is not synchronized in this context, is it perhaps not supposed to be added here?
            if (outerProps != null && !ctx.hasSyncInst(outer)) {
               if (verbose || trace)
                  System.out.println("addSyncInst - not adding instance to current context: " + ctx + " because parent instance is not sync'd here: " + DynUtil.getInstanceName(inst) + " with outer: " + DynUtil.getInstanceName(outer));
               return;
            }
         }
         */

         ctx.addSyncInst(null, inst, !onDemand, onDemand, initDefault, resetState, true, true, scopeId, syncProps, args);
      }
      else {
         if (trace)
            System.err.println("Ignoring addSyncInst - not in scope: " + ScopeDefinition.getScope(scopeId));
         if (verbose)
            new Throwable().printStackTrace();
      }
   }

   public static void registerSyncInst(Object inst, Object[] args, String instName, int scopeId, boolean initInst) {
      registerSyncInst(inst, args, instName, scopeId, true, initInst);
   }

   public static void registerSyncInst(Object inst, Object[] args, String instName, int scopeId, boolean fixedName, boolean initInst) {
      for (SyncManager syncMgr:syncManagers) {
         SyncContext ctx = syncMgr.getSyncContext(scopeId, true);
         if (ctx != null) {
            // Here we've already been given the scope for the syncInst so just put it into that one.
            ctx.registerObjNameOnScope(inst, args, instName, fixedName, initInst, true, true, false);
         }
      }
   }

   public static void registerSyncInst(Object inst, Object[] args, String instName, boolean fixedName, boolean initInst) {
      SyncContext ctx = getDefaultSyncContext();
      ctx.registerObjName(inst, args, instName, fixedName, initInst, false);
   }

   // Used by the JS code
   public static void registerSyncInst(Object inst, String instName) {
      SyncContext ctx = getDefaultSyncContext();
      ctx.registerObjName(inst, null, instName, ctx.getSyncManager().syncDestination.clientDestination, true, false);
   }

   /** Returns the SyncContext to use for a sync instance which has already been added to the system. */
   /*
   public static SyncContext getSyncContextForInst(String destName, Object inst) {
      int scopeId = getScopeIdForSyncInst(inst);
      if (scopeId == -1)
         return null;
      SyncManager mgr = getSyncManager(destName);
      if (mgr == null)
         return null;
      return mgr.getSyncContext(scopeId, false);
   }
   */

   public static int getScopeIdForSyncInst(Object inst) {
      String scopeName = DynUtil.getScopeName(inst);
      if (scopeName == null)
         return getDefaultScope().scopeId;
      else {
         ScopeDefinition def = ScopeDefinition.getScopeByName(scopeName);
         if (def == null) {
            System.err.println("*** No scope: " + scopeName + " for getScopeIdForSyncInst");
            return -1;
         }
         else
            return def.scopeId;
      }
   }

   public static SyncContext getRootSyncContextForInst(Object inst) {
      // The scopes from global -> request
      ArrayList<ScopeDefinition> scopes = ScopeDefinition.scopes;

      // process them in the forward direction so we get the root scope context
      for (int i = 0; i < scopes.size(); i++) {
         ScopeDefinition scopeDef = scopes.get(i);
         if (scopeDef != null) {
            ScopeContext scopeCtx = scopeDef.getScopeContext(false);
            if (scopeCtx != null) {
               SyncContext syncCtx = (SyncContext) scopeCtx.getValue(SC_SYNC_CONTEXT_SCOPE_KEY);
               if (syncCtx != null && syncCtx.hasSyncInst(inst))
                  return syncCtx;
            }
         }
      }
      return null;
   }

   public static SyncContext getSyncContextForInst(Object inst) {
      // The scopes from global -> request
      ArrayList<ScopeDefinition> scopes = ScopeDefinition.scopes;

      // process them in the reverse direction so we get the 'most specific' SyncContext
      for (int i = scopes.size() - 1; i >= 0; i--) {
         ScopeDefinition scopeDef = scopes.get(i);
         if (scopeDef != null) {
            ScopeContext scopeCtx = scopeDef.getScopeContext(false);
            if (scopeCtx != null) {
               SyncContext syncCtx = (SyncContext) scopeCtx.getValue(SC_SYNC_CONTEXT_SCOPE_KEY);
               if (syncCtx != null && syncCtx.hasSyncInst(inst))
                  return syncCtx;
            }
         }
      }
      return null;
   }

   public static void removeSyncInst(Object inst) {
      Object type = DynUtil.getType(inst);

      // Removing this instance from all sync contexts by starting at the top
      SyncContext syncCtx = getRootSyncContextForInst(inst);
      if (syncCtx != null) {
         SyncProperties syncProps = syncCtx.getSyncManager().getSyncProperties(type);
         syncCtx.removeSyncInst(inst, syncProps);
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
         ScopeContext scopeCtx = scopeDef.getScopeContext(false);
         if (scopeCtx == null)
            continue;
         SyncContext syncCtx = (SyncContext) scopeCtx.getValue(SC_SYNC_CONTEXT_SCOPE_KEY);
         if (syncCtx.hasSyncInst(fromInst)) {
            SyncProperties syncProps = syncCtx.getSyncManager().getSyncProperties(type);
            if (syncProps != null) {
               syncCtx.replaceSyncInst(fromInst, toInst, syncProps);
            }
         }
         else if (scopeDef.isGlobal()) {
            for (SyncManager syncMgr:syncManagers) {
               syncCtx = syncMgr.getRootSyncContext();
               if (syncCtx != null && syncCtx.hasSyncInst(fromInst)) {
                  SyncProperties syncProps = syncCtx.getSyncManager().getSyncProperties(type);
                  syncCtx.replaceSyncInst(fromInst, toInst, syncProps);
               }
            }
         }
      }
   }

   /** Starts synchronization for an on-demand property - see SyncContext.startSync for details */
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
      SyncContext syncCtx = getSyncContextForInst(inst);
      if (syncCtx != null) {
         syncCtx.fetchProperty(inst, prop);
      }
      else {
         System.err.println("No sync inst: " + DynUtil.getInstanceName(inst) + " registered for fetchProperty of: " + prop);
      }
   }

   public static void startSync(Object inst, String prop) {
      SyncContext syncCtx = getRootSyncContextForInst(inst);
      if (syncCtx != null) {
         syncCtx.startSync(inst, prop);

      }
      else if (verbose) // Not treating this as an error because we might use the same code with and without synchronization so this is only a debug message.
         System.out.println("No sync inst: " + DynUtil.getInstanceName(inst) + " registered for startSync of: " + prop);
   }

   public static void initProperty(Object inst, String prop) {
      SyncContext syncCtx = getSyncContextForInst(inst);
      if (syncCtx == null) {
         System.err.println("No sync inst: " + DynUtil.getInstanceName(inst) + " registered for initProperty of: " + prop);
      }
      else
         syncCtx.initProperty(inst, prop);
   }

   /**
    * If this object has already been synchronized, update the remote side's representation of the value of this property - i.e.
    * record the value supplied as the 'previous value' managed by the sync system.
    * Use this to tell the sync system that a property has been updated implicitly on the client side - not using the sync
    * system.
    */
   public static void updateRemoteValue(Object inst, String prop, Object value) {
      SyncContext syncCtx = getSyncContextForInst(inst);
      if (syncCtx == null) { // This instance is just not synchronized yet so nothing to update.
         return;
      }
      else {
         // Not adding this to the initial layer because it will be included
         syncCtx.addPreviousValue(inst, prop, value, false, false);
      }
   }

   private void removeSyncInst(Object inst, int scopeId, SyncProperties syncProps) {
      SyncContext ctx = getSyncContext(scopeId, false);
      ctx.removeSyncInst(inst, syncProps);
   }

   /** Start a synchronize operation for all destinations. Types which have registered a custom sync group are not synchronized. */
   public static SyncResult sendSyncToAll() {
      return sendSyncToAll(null, false, false);
   }

   public static void setInitialSync(String destName, String appId, int scopeId, boolean val) {
      SyncManager syncMgr = syncManagersByDest.get(destName);

      syncMgr.setInitialSync(appId, scopeId, val);
   }

   public static void printAllSyncInsts() {
      printAllSyncInsts(getDefaultScope().scopeId);
   }

   public static void printAllSyncInsts(int scopeId) {
      for (SyncManager syncManager:syncManagers) {
         SyncContext ctx = syncManager.getSyncContext(scopeId, false);
         if (ctx != null)
            ctx.printAllSyncInsts();
      }
   }

   public static void resetContext(int scopeId) {
      for (SyncManager syncManager:syncManagers) {
         SyncContext ctx = syncManager.getSyncContext(scopeId, false);
         if (ctx != null)
            ctx.resetContext();
      }
   }

   /** Used from the generated code for the browser to apply a sync layer to the default destination */
   public static boolean applySyncLayer(String language, String data, String detail) {
      boolean anyChanges = false;
      for (SyncManager syncManager:syncManagers) {
         // TODO: validate that language and syncDestination.receiveLanguage match?
         if (syncManager.syncDestination.applySyncLayer(data, language, null, false, detail))
            anyChanges = true;
      }
      return anyChanges;
   }

   public static CharSequence getInitialSync(String destName, int scopeId, boolean resetSync, String outputLanguage,
                                             Set<String> syncTypeFilter, Set<String> resetTypeFilter) {
      SyncManager syncMgr = syncManagersByDest.get(destName);

      return syncMgr.getInitialSync(scopeId, resetSync, outputLanguage, syncTypeFilter, resetTypeFilter);
   }

   /**
    * Called when we retrieve the initial state of a set of components.  Both marks the current state as being initialized
    * and marks the specified sync context as being in the initial sync state.
    */
   public void setInitialSync(String appId, int scopeId, boolean val) { // TODO: rename to setInitialSyncState
      //SyncManager.setSyncAppId(appId);
      if (val)
         SyncManager.setSyncState(SyncManager.SyncState.Initializing);
      else
         SyncManager.setSyncState(null);
      SyncContext ctx = getSyncContext(scopeId, true);
      ctx.setInitialSync(val);
   }

  /**
   * Called to retrieve the sync state for a new client for a new client. If resetSync is true, it's the client calling this
   * method to receive the resetState for resetting the server when the server's session is lost.
   */
   public CharSequence getInitialSync(int scopeId, boolean resetSync, String outputLanguage, Set<String> syncTypeFilter, Set<String> resetTypeFilter) {
      SyncContext ctx = getSyncContext(scopeId, true); // Need to create it here since the initial sync will record lazy obj names we have to clear on reload
      if (ctx == null)
         return null;

      SyncSerializer lastSer = null;
      if (!resetSync) {
         SyncLayer layer = ctx.getInitialSyncLayer();
         HashSet<String> createdTypes = new HashSet<String>();

         ArrayList<SyncContext> parCtxList = ctx.getSortedParentList();

         // Start at the root parent context and work down the chain
         for (int i = 0; i < parCtxList.size(); i++) {
            SyncContext parentCtx = parCtxList.get(i);
            SyncSerializer parentSer = parentCtx.getInitialSyncLayer().serialize(ctx, createdTypes, false, syncTypeFilter, resetTypeFilter);
            if (parentSer != null) {
               if (lastSer == null)
                  lastSer = parentSer;
               else
                  lastSer.appendSerializer(parentSer);
            }
         }

         SyncSerializer thisSer = layer.serialize(ctx, createdTypes, false, syncTypeFilter, resetTypeFilter);
         if (thisSer != null) {
            if (lastSer == null)
               lastSer = thisSer;
            else
               lastSer.appendSerializer(thisSer);
         }
      }

      // Before we've finished the initial sync, there's no difference between the initial values and the previous values.
      ctx.setInitialSync(false);
      // Any new object names we generated during initialization are now 'registered' by that client
      ctx.commitNewObjNames(ctx);

      StringBuilder res = lastSer == null ? new StringBuilder() : lastSer.getOutput();

      if (outputLanguage == null || syncDestination.getSendLanguage().equals(outputLanguage))
         return res;

      // TODO: add this as a 'convert' method on SyncDestination so the logic is customizable for other formats that might need a conversion step
      // Need to apply the format we were given using some JS code - if stratacode, it's converted to 'js' directly, otherwise ('json') it gets applied in the same language
      if (outputLanguage.equals("js") && res.length() > 0) {
         StringBuilder jsRes = new StringBuilder();
         jsRes.append("sc_SyncManager_c." +
                 "applySyncLayer(\"" + syncDestination.getOutputLanguage() + "\", `");
         // Need to escape the / character in the strings to be sure that </script> turns into <\/script> when inside of a <script tag> or the JS parsing fails
         jsRes.append(CTypeUtil.escapeJavaString(res.toString(), '`', true));
         jsRes.append("`, \"init\");\n");
         return jsRes;
      }
      return res;
   }

   // Accumulate the list of all parent sync contexts.  We sort by scopeId and remove duplicates on this list but might as well get a rough sort
   private static void addAllParentContexts(ArrayList<SyncContext> parentList, ArrayList<SyncContext> result) {
      for (int i = 0; i < parentList.size(); i++) {
         SyncContext parent = parentList.get(i);
         if (parent.parentContexts != null)
            addAllParentContexts(parent.parentContexts, result);
      }
      result.addAll(parentList);
   }

   // Since we sorted the list, duplicates will be next to each other
   private static void removeDuplicateContexts(ArrayList<SyncContext> result) {
      for (int i = 0; i < result.size(); i++) {
         SyncContext cur = result.get(i);
         for (int j = i+1; j < result.size(); j++) {
            if (cur == result.get(j)) {
               result.remove(j);
               j--;
            }
         }
      }
   }

   /**
    * Does a global sync across all destinations for all current sync contexts.  If you use SYNC_ALL as the sync group
    * it will synchronize all sync groups.  Specifying a null syncGroup will choose the default sync group only.
    */
   public static SyncResult sendSyncToAll(String syncGroup, boolean sendReset, boolean markAsSentOnly) {
      boolean anyChanges = false;
      String errorMessage = null;
      for (String destName:syncManagersByDest.keySet()) {
         SyncResult res = sendSync(destName, syncGroup, sendReset, markAsSentOnly, null, null, null);
         anyChanges = anyChanges || res.anyChanges;
         if (errorMessage == null)
            errorMessage = res.errorMessage;
      }
      return new SyncResult(anyChanges, errorMessage);
   }

   public static Set<String> getDestinationNames() {
      return syncManagersByDest.keySet();
   }

   public static ScopeDefinition getDefaultScope() {
      ScopeDefinition syncScope = null;
      List<ScopeDefinition> activeScopes = ScopeDefinition.getActiveScopes();
      for (ScopeDefinition def: activeScopes) {
         if (syncScope == null || syncScope.includesScope(def))
            syncScope = def;
      }
      return syncScope;
   }

   public static SyncResult sendSync(String destName, String syncGroup, boolean sendReset, boolean markAsSentOnly, CharSequence codeUpdates, Set<String> syncTypeFilter, Set<String> resetTypeFilter) {
      ScopeDefinition syncScope = getDefaultScope();
      if (syncScope == null)
         throw new IllegalArgumentException("*** No active scopes to sync");
      else
         return sendSync(destName, syncGroup, syncScope.scopeId, sendReset, markAsSentOnly, codeUpdates, syncTypeFilter, resetTypeFilter);
   }

   public static SyncResult sendSync(String destName, String syncGroup, int scopeId, boolean sendReset, boolean markAsSentOnly, CharSequence codeUpdates, Set<String> syncTypeFilter, Set<String> resetTypeFilter) {
      SyncManager syncMgr = syncManagersByDest.get(destName);
      return syncMgr.sendSync(syncGroup, scopeId, sendReset, markAsSentOnly, codeUpdates, syncTypeFilter, resetTypeFilter);
   }

   private SyncContext getFirstParentSyncContext(int scopeId, boolean create) {
      ScopeDefinition scope = ScopeDefinition.getScopeDefinition(scopeId);
      if (scope != null) {
         List<ScopeDefinition> parentScopes = scope.getParentScopes();
         if (parentScopes != null) {
            for (ScopeDefinition parent:parentScopes) {
               SyncContext ctx = getSyncContext(parent.scopeId, false);
               if (ctx != null)
                  return ctx;
            }
            for (ScopeDefinition parent:parentScopes) {
               SyncContext ctx = getFirstParentSyncContext(parent.scopeId, false);
               if (ctx != null)
                  return ctx;
            }
         }
      }
      return null;
   }

   public SyncResult sendSync(String syncGroup, int scopeId, boolean sendReset, boolean markAsSentOnly, CharSequence codeUpdates, Set<String> syncTypeFilter, Set<String> resetTypeFilter) {
      SyncContext ctx = getSyncContext(scopeId, false);
      if (ctx == null) {  // If the default scope does not have a context, check for a sync context on the parent scope
         ctx = getFirstParentSyncContext(scopeId, false);
      }

      if (ctx != null) {
         ArrayList<SyncLayer> toSend = ctx.getChangedSyncLayers(syncGroup);

         if (sendReset) // On the client this happens when the server has lost the session or never kept the info in the first place
            return syncDestination.sendResetSync(ctx, toSend);

         return syncDestination.sendSync(ctx, toSend, syncGroup, markAsSentOnly, codeUpdates, syncTypeFilter, resetTypeFilter);
      }
      else if (verbose) {
         System.out.println("No changes to synchronize for scope: " + ScopeDefinition.getScope(scopeId));
      }
      return new SyncResult(false, null);
   }

   public SyncResult sendReInitSync(String reInitSync, String syncGroup, int scopeId, boolean markAsSentOnly, CharSequence codeUpdates) {
      SyncContext ctx = getSyncContext(scopeId, false);
      if (ctx == null) {  // If the default scope does not have a context, check for a sync context on the parent scope
         ctx = getFirstParentSyncContext(scopeId, false);
      }

      if (ctx != null) {
         return syncDestination.sendSyncData(ctx, null, reInitSync, syncGroup,reInitSync, markAsSentOnly, codeUpdates);
      }
      else if (verbose) {
         System.out.println("No changes to synchronize for scope: " + ScopeDefinition.getScope(scopeId));
      }
      return new SyncResult(false, null);
   }

   public static RemoteResult invokeRemote(ScopeDefinition def, ScopeContext ctx, Object obj, Object type, String methName, Object retType, String paramSig, Object...args) {
      return invokeRemoteDest(def, ctx, SyncDestination.defaultDestination.name, null, obj, type, methName, retType, paramSig, args);
   }

   public static void setCurrentSyncLayers(ArrayList<SyncLayer> current) {
      PTypeUtil.setThreadLocal("currentSyncLayer", current);
   }

   public static ArrayList<SyncLayer> getCurrentSyncLayers() {
      return (ArrayList<SyncLayer>) PTypeUtil.getThreadLocal("currentSyncLayer");
   }

   public static void processMethodReturn(SyncContext ctx, String callId, Object retValue, String exceptionStr) {
      ArrayList<SyncLayer> currentSyncLayers;
      if (ctx == null) {
         currentSyncLayers = getCurrentSyncLayers();
      }
      else
         currentSyncLayers = ctx.getChangedSyncLayers(null);

      boolean handled = false;
      if (currentSyncLayers != null) {
         if (trace || verbose)
            System.out.println("Processing remote method result: " + callId + " = " + retValue);

         SyncManager.SyncState oldState = SyncManager.setSyncState(SyncManager.SyncState.RecordingChanges);
         try {
            for (SyncLayer currentSyncLayer:currentSyncLayers) {
               if (currentSyncLayer.processMethodReturn(callId, retValue, exceptionStr)) {
                  handled = true;
                  break;
               }
            }
         }
         finally {
            SyncManager.setSyncState(oldState);
         }
      }
      if (!handled) {
         System.err.println("processMethodReturn called when no current sync layer is registered - " + (ctx == null ? "current sync layers" : " using ctx: " + ctx));
         System.err.println("*** at stack: " + PTypeUtil.getStackTrace(new Throwable()));
      }
   }

   /**
    * Used when the server invokes a remote change against the client.  We need to code generate this call in using
    * the result of the method call performed by the client.
    * The curObj is the current object, or null for a static method.
    * The type is the type for a static method call.
    * The callId is the name to use for storing the remote result - to represent a unique invocation of this method.
    * The retValue is the return value of the method.
    */
   public static void addMethodResult(Object curObj, Object type, String callId, Object retValue, String exceptionStr) {
      SyncContext ctx  = getDefaultSyncContext();
      ctx.addMethodResult(curObj, type, callId, retValue, exceptionStr);
   }

   public static void receiveNameChange(String oldName, String newName) {
      SyncContext ctx  = getDefaultSyncContext();
      ctx.receiveNameChange(oldName, newName);
   }

   public static void nameChangeAck(String oldName, String newName) {
      SyncContext ctx  = getDefaultSyncContext();
      ctx.nameChangeAck(oldName, newName);
   }

/*
 * This version was helpful for retrieving the sync context for a remote call for a page object
 * but ultimately moved the remote call into PageDispatcher so it could use the same scope as
 * the sync
   public static SyncContext getSyncContextForScopeInst(Object inst) {
      int scopeId = getScopeIdForSyncInst(inst);
      if (scopeId == -1)
         scopeId = GlobalScopeDefinition.getGlobalScopeDefinition().scopeId;
      SyncManager mgr = getSyncManager(null);
      if (mgr == null)
         mgr = SyncManager.getDefaultSyncManager();
      return mgr.getSyncContext(scopeId, true);
   }
*/

   /**
    * Called either with a scopeDefinition (to choose the current context in that scope), or an explicit ScopeContext as the target of the call,
    * or pass null for both to choose the default scope, default context.
    */
   public static RemoteResult invokeRemoteDest(ScopeDefinition def, ScopeContext scopeCtx, String destName, String syncGroup, Object obj, Object type, String methName, Object retType, String paramSig, Object...args) {
      SyncManager mgr = getSyncManager(destName);
      int scopeId;
      SyncContext ctx = null;
      if (scopeCtx == null) {
         if (def == null && obj != null) {
            scopeId = getScopeIdForSyncInst(obj);
            if (scopeId == -1)
               scopeId = GlobalScopeDefinition.getGlobalScopeDefinition().scopeId;
         }
         else if (def != null)
            scopeId = def.scopeId;
         else
            scopeId = GlobalScopeDefinition.getGlobalScopeDefinition().scopeId;

         ctx = mgr == null ? SyncManager.getDefaultSyncContext() : mgr.getSyncContext(scopeId, true);
      }
      else {
         if (mgr == null)
            mgr = SyncManager.getDefaultSyncManager();
         ctx = mgr.getSyncContextFromScopeContext(scopeCtx, true);
      }
      if (ctx == null) {
         throw new IllegalArgumentException("No SyncContext for scope ctx: " + scopeCtx);
      }
      return ctx.invokeRemote(syncGroup, obj, type, methName, retType, paramSig, args);
   }

   public static SyncContext getDefaultSyncContext() {
      SyncDestination def = SyncDestination.defaultDestination;
      if (def == null) {
         if (trace && !syncNoInitDisplayed) {
            syncNoInitDisplayed = true;
            System.out.println("No default sync destination defined - sync disabled");
         }
         return null;
      }
      return SyncDestination.defaultDestination.syncManager.getSyncContext(GlobalScopeDefinition.getGlobalScopeDefinition().scopeId, true);
   }

   @Constant
   public static SyncManager getDefaultSyncManager() {
      return SyncDestination.defaultDestination.syncManager;
   }

   public static void initChildren(Object obj) {
      // Some components need to do some initialization to create other components - i.e. the children created by the repeat attribute
      // This hook lets them do that...
      if (obj instanceof IChildInit)
         ((IChildInit) obj).initChildren();
      Object[] children = DynUtil.getObjChildren(obj, null, true);
      if (children != null) {
         for(Object child:children) {
            initChildren(child);
         }
      }
   }

   public static Object getSyncInst(String name) {
      SyncContext defaultCtx = getDefaultSyncContext();
      if (defaultCtx == null) // Probably js.sync or servlet.core not included in the stack - need to include a SyncDestination component if you are using sync
         return null;
      Object obj = defaultCtx.getObjectByName(name, true);
      return obj;
   }

   /** Used from JS generated code */
   public static Object resolveSyncInst(String name) {
      Object obj = getSyncInst(name);
      if (obj == null) {
         obj = DynUtil.resolveName(name, true, true);
         if (obj == null)
            System.err.println("*** Unable to resolve syncInst: " + name);
      }
      return obj;
   }

   /** Used from JS generated code */
   public static Object resolveOrCreateSyncInst(String name, Object type, String sig, Object...args) {
      Object inst = resolveSyncInst(name);
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

   /**
    * A hook point for frameworks to add their own name resolver for looking up sync objects.  For example, the JS runtime registers a resolver
    * so the server can map directly to DOM elements in the browser namespace.
    */
   public static void addFrameworkNameContext(INameContext nameResolver) {
      if (frameworkNameContexts == null)
         frameworkNameContexts = new ArrayList<INameContext>();
      frameworkNameContexts.add(nameResolver);
   }

   /** A hook point for frameworks to add listeners for sync events like after an applySync. */
   public static void addFrameworkListener(IFrameworkListener l) {
      if (frameworkListeners == null)
         frameworkListeners = new ArrayList<IFrameworkListener>();
      frameworkListeners.add(l);
   }

   public static void callAfterApplySync() {
      if (frameworkListeners != null)
         for (IFrameworkListener fl:frameworkListeners)
            fl.afterApplySync();
   }

   public static ScopeDefinition getScopeDefinitionByName(String scopeName) {
      ScopeDefinition def = ScopeDefinition.getScopeByName(scopeName);
      // Global scope has not yet been defined so just define it.
      if (def == null)
         def = GlobalScopeDefinition.getGlobalScopeDefinition();
      return def;
   }

   public int numSendsInProgress = 0;

   /** The number of sendSync's with data that have not been acknowledged yet - i.e. the number of requests outstanding */
   @Bindable(manual=true)
   public void setNumSendsInProgress(int newNum) {
      numSendsInProgress = newNum;
      Bind.sendChange(this, "numSendsInProgress", newNum);
   }

   public int getNumSendsInProgress() {
      return numSendsInProgress;
   }

   static class SyncContextHolder {
      SyncContext ctx;
   }

   public void autoSync() {
   }

   void scheduleConnectSync(long waitToSyncTime) {
   }

   /**
    * Hook for frameworks to add sync type names that are added at runtime - not via @Sync annotation but still use
    * the syncTypeFilter
    */
   public static void addGlobalSyncTypeName(String typeName) {
      if (globalSyncTypeNames == null)
         globalSyncTypeNames = new HashSet<String>();
      globalSyncTypeNames.add(typeName);
   }

   public static void clearResetState(String objName) {
      List<ScopeDefinition> scopeDefs = ScopeDefinition.getActiveScopes();
      if (scopeDefs == null)
         return;

      Object inst = null;
      // We do not know the app ids which this instance is registered under right now so we have to do a bit of searching to find
      // the sync contexts (if any) which have it registered.
      for (ScopeDefinition scopeDef:scopeDefs) {
         ScopeContext scopeCtx = scopeDef.getScopeContext(false);
         if (scopeCtx == null)
            continue;
         SyncContext syncCtx = (SyncContext) scopeCtx.getValue(SC_SYNC_CONTEXT_SCOPE_KEY);
         if (syncCtx == null)
            continue;
         if (inst == null)
            inst = syncCtx.getObjectByName(objName);
         if (inst != null && syncCtx.hasSyncInst(inst)) {
            syncCtx.clearResetState(inst);
         }
      }

   }

}
