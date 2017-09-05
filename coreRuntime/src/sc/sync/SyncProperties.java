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

   // The default value for initDefault for those cases where you do not call addSyncInst explicitly
   public boolean initDefault = true;

   // Set this to true if the object's values are constant - i.e. don't need listeners
   public boolean constant = false;

   public int defaultScopeId = -1;

   // Set this to true if a new instance should propagate even to child scope contexts that are not active.  For example, creating a new instance in a global scope propagates to all sessions, or in a session propagates to all windows
   public boolean broadcast = false;

   IntCoalescedHashMap propIndex;

   public SyncProperties(String destName, String syncGroup, Object[] props, int flags) {
      this(destName, syncGroup, props, null, flags);
   }

   public SyncProperties(String destName, String syncGroup, Object[] props, Object chainedType, int flags) {
      this(destName, syncGroup, props, chainedType, flags, -1);
   }

   public SyncProperties(String destName, String syncGroup, Object[] props, Object chainedType, int flags, int scopeId) {
      this.destName = destName;
      this.syncGroup = syncGroup;
      this.defaultScopeId = scopeId;
      classProps = props;
      // TODO: this assumes the sync type, the base type has already run.  Since we are doing this as part of the
      // static initialization of our class, doesn't that mean the static init of the base class has already been done?
      chainedProps = chainedType == null ? null : SyncManager.getSyncProperties(chainedType, destName);
      propIndex = new IntCoalescedHashMap(getNumProperties());
      constant = (flags & SyncOptions.SYNC_CONSTANT) != 0;
      initDefault = (flags & SyncOptions.SYNC_INIT_DEFAULT) != 0;
      if (props != null) {
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
            propIndex.put(propName, options);
         }
         if (chainedProps != null)
            propIndex.putAll(chainedProps.propIndex);
      }
      else if (chainedProps != null)
         propIndex = chainedProps.propIndex;
   }

   public boolean isSynced(String prop) {
      return propIndex == null ? false : propIndex.containsKey(prop);
   }

   public int getSyncFlags(String prop) {
      return propIndex == null ? -1 : propIndex.get(prop);
   }

   public int getNumProperties() {
      return (classProps == null ? 0 : classProps.length) + (chainedProps == null ? 0 : chainedProps.getNumProperties());
   }

   public Object[] getSyncProperties() {
      return classProps;
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
   }
}
