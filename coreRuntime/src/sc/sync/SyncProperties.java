/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.dyn.DynUtil;
import sc.util.IntCoalescedHashMap;

import java.util.ArrayList;
import java.util.Arrays;

/** Represents a set of properties which are synchronized to a specific destination. */
@sc.js.JSSettings(jsModuleFile="js/sync.js", prefixAlias="sc_")
public class SyncProperties {
   public String destName;
   public String syncGroup;
   /** Array of either property names or SyncPropOption instances to control which properties are to be synchronized */
   public Object[] classProps;
   /** Then sync properties of a base type we are chained from */
   public SyncProperties chainedProps;

   /** True if this an instance of this object is allowed to be created from the client */
   public boolean allowCreate = true;

   /**
    * The default value for initDefault for those cases where you do not call addSyncInst explicitly.  See @Sync(initDefault)
    * for details, but basically whether or not the remote side needs to be initialized with default values when the instance is
    * created.  Sometimes, the code for the remote side already has default values and so only needs to be sent changes.
    */
   public boolean initDefault = true;

   // Set this to true if the object's values are constant - i.e. don't need listeners
   public boolean constant = false;

   public int defaultScopeId = -1;

   // Set this to true if a new instance should propagate even to child scope contexts that are not active.  For example, creating a new instance in a global scope propagates to all sessions, or in a session propagates to all windows
   public boolean broadcast = false;

   IntCoalescedHashMap propIndex;

   Object[] allProps;

   public SyncProperties(String destName, String syncGroup, Object[] props, int flags) {
      this(destName, syncGroup, props, null, flags);
   }

   public SyncProperties(String destName, String syncGroup, Object[] props, Object chainedType, int flags) {
      this(destName, syncGroup, props, chainedType, flags, -1);
   }

   /**
    * Creates a SyncProperties object to define how to synchronize a specific type or instance.  After you create one of these
    * you pass it to either addSyncType - when all instances are synchronized using the same properties/settings or addSyncInst - when
    * an individual instance is not synchronized like others with the same type.
    */
   public SyncProperties(String destName, String syncGroup, Object[] props, Object chainedType, int flags, int scopeId) {
      this.destName = destName;
      this.syncGroup = syncGroup;
      this.defaultScopeId = scopeId;
      classProps = props;
      // TODO: this assumes the sync type, the base type has already run.  Since we are doing this as part of the
      // static initialization of our class, doesn't that mean the static init of the base class has already been done?
      chainedProps = chainedType == null ? null : SyncManager.getSyncProperties(chainedType, destName);
      propIndex = new IntCoalescedHashMap(classProps == null ? 0 : classProps.length + (chainedProps == null ? 0 : chainedProps.getNumProperties()));
      constant = (flags & SyncOptions.SYNC_CONSTANT) != 0;
      initDefault = (flags & SyncOptions.SYNC_INIT_DEFAULT) != 0;
      ArrayList<Object> newProps = null;
      if (props != null) {
         if (chainedProps != null)
            propIndex.putAll(chainedProps.propIndex);
         for (Object prop:props) {
            int options;
            String propName;
            if (prop instanceof SyncPropOptions) {
               SyncPropOptions opt = (SyncPropOptions) prop;
               // TODO: should there be a way to turn off SYNC_INIT when initDefault is true?  Or should SYNC_INIT be the default?
               if (initDefault)
                  opt.flags |= SyncPropOptions.SYNC_INIT;
               options = opt.flags;
               propName = opt.propName;
            }
            else {
               options = 0;
               propName = (String) prop;
            }
            if (propIndex.put(propName, options) == -1 && chainedProps != null) {
               // when we are combining two property lists, keep track of only the new ones so the order of properties
               // in the list remains consistent just like we do elsewhere
               if (newProps == null)
                  newProps = new ArrayList<Object>(props.length);
               newProps.add(propName);
            }
         }
      }
      else if (chainedProps != null)
         propIndex = chainedProps.propIndex;

      if (chainedProps != null) {
         ArrayList<Object> allPropsList = new ArrayList<Object>(propIndex.size);
         allPropsList.addAll(Arrays.asList(chainedProps.getSyncProperties()));
         if (newProps != null)
            allPropsList.addAll(newProps);
         allProps = allPropsList.toArray(new Object[allPropsList.size()]);
      }
      else
         allProps = classProps;
   }

   public boolean isSynced(String prop) {
      return propIndex == null ? false : propIndex.containsKey(prop);
   }

   public int getSyncFlags(String prop) {
      return propIndex == null ? -1 : propIndex.get(prop);
   }

   public int getNumProperties() {
      if (allProps == null)
         return 0;
      return allProps.length;
   }

   public Object[] getSyncProperties() {
      return allProps;
   }

   public String getSyncGroup() {
      return syncGroup;
   }

   public String toString() {
      return "SyncProperties: " + (destName != null ? "dest=" + destName : "") + " " + (syncGroup != null ? "syncGroup=" + syncGroup : "") + " properties=" + DynUtil.arrayToInstanceName(classProps);
   }

   public String getDestinationName() {
      return destName;
   }

   // Merges the other properties - only replacing properties which do not exist in this sync properties.
   public void merge(SyncProperties other) {
      ArrayList<Object> myProps = null;
      for (Object prop: other.classProps) {
         String propName;
         int propFlags = 0;
         if (prop instanceof SyncPropOptions) {
            SyncPropOptions opts = (SyncPropOptions) prop;
            propName = opts.propName;
            propFlags = opts.flags;
         }
         else {
            propName = (String) prop;
            propFlags = 0;
         }
         if (propIndex.get(propName) == -1) {
            if (myProps == null)
               myProps = new ArrayList<Object>(Arrays.asList(classProps));
            myProps.add(prop);
            propIndex.put(propName, propFlags);
         }
      }
      if (myProps == null)
         return;
      classProps = myProps.toArray(new Object[classProps.length]);
      if (chainedProps != null) // TODO: this is used when building the SyncProperties at compile time which right now does not use chainedProps so we should be ok
         System.err.println("*** Not merging in chainedProperties!");
      allProps = classProps;
   }
}
