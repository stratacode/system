/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.dyn.DynUtil;
import sc.util.IntCoalescedHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

   public int defaultPropOptions = 0;

   IntCoalescedHashMap propIndex;

   Object[] allProps;

   IntCoalescedHashMap staticPropIndex;

   Object[] staticProps;

   public SyncProperties(String destName, String syncGroup, Object[] props, int defaultPropOptions) {
      this(destName, syncGroup, props, null, defaultPropOptions, -1);
   }

   public SyncProperties(String destName, String syncGroup, Object[] props, Object chainedType, int defaultPropOptions) {
      this(destName, syncGroup, props, chainedType, defaultPropOptions, -1);
   }

   /**
    * Creates a SyncProperties object to define how to synchronize a specific type or instance.  After you create one of these
    * you pass it to either addSyncType - when all instances are synchronized using the same properties/settings or addSyncInst - when
    * an individual instance is not synchronized like others with the same type.
    */
   public SyncProperties(String destName, String syncGroup, Object[] props, Object chainedType, int defaultPropOptions, int scopeId) {
      this.destName = destName;
      this.syncGroup = syncGroup;
      this.defaultScopeId = scopeId;
      this.defaultPropOptions = defaultPropOptions;
      classProps = props;
      // TODO: this assumes addSyncType has been called on the base type already.  Counting on the fact that Java initializes
      // the base class before we initializes this class.
      chainedProps = chainedType == null ? null : SyncManager.getSyncProperties(chainedType, destName);
      int numProps = classProps == null ? 0 : classProps.length + (chainedProps == null ? 0 : chainedProps.getNumProperties());
      propIndex = new IntCoalescedHashMap(numProps);
      ArrayList<Object> newProps = null;
      ArrayList<Object> newStaticProps = null;
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
               options = defaultPropOptions;
               propName = (String) prop;
            }
            if ((options & SyncPropOptions.SYNC_STATIC) != 0) {
               if (staticPropIndex == null) {
                  staticPropIndex = new IntCoalescedHashMap(numProps);
                  newStaticProps = new ArrayList<Object>();
               }
               staticPropIndex.put(propName, options);
               newStaticProps.add(propName);
            }
            else if (propIndex.put(propName, options) == -1 && chainedProps != null) {
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
      staticProps = newStaticProps == null ? null : newStaticProps.toArray();
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

   public Object[] getStaticSyncProperties() {
      return staticProps;
   }

   public String toString() {
      return "SyncProperties: " + (destName != null ? "dest=" + destName : "") + " " + (syncGroup != null ? "syncGroup=" + syncGroup : "") + " properties=" + DynUtil.arrayToInstanceName(classProps) + " staticProperties=" + DynUtil.arrayToInstanceName(staticProps);
   }

   public String getDestinationName() {
      return destName;
   }

   // Merges the other properties - only replacing properties which do not exist in this sync properties.
   public void merge(SyncProperties other) {
      ArrayList<Object> myProps = null;
      ArrayList<Object> myStaticProps = null;
      for (Object prop: other.classProps) {
         String propName;
         int propFlags;
         if (prop instanceof SyncPropOptions) {
            SyncPropOptions opts = (SyncPropOptions) prop;
            propName = opts.propName;
            propFlags = opts.flags;
         }
         else {
            propName = (String) prop;
            propFlags = 0;
         }
         if ((propFlags & SyncPropOptions.SYNC_STATIC) != 0) {
            if (staticPropIndex == null) {
               staticPropIndex = new IntCoalescedHashMap(classProps.length);
            }
            if (myStaticProps == null)
               myStaticProps = new ArrayList<Object>();
            if (staticPropIndex.get(propName) == -1) {
               myStaticProps.add(propName);
               staticPropIndex.put(propName, propFlags);
            }
         }
         else if (propIndex.get(propName) == -1) {
            if (myProps == null)
               myProps = new ArrayList<Object>(Arrays.asList(classProps));
            myProps.add(prop);
            propIndex.put(propName, propFlags);
         }
      }
      if (myProps != null) {
         classProps = myProps.toArray(new Object[classProps.length]);
         if (chainedProps != null) // TODO: this is used when building the SyncProperties at compile time which right now does not use chainedProps so we should be ok
            System.err.println("*** Not merging in chainedProperties!");
         allProps = classProps;
      }

      if (myStaticProps != null) {
         if (staticProps == null)
            staticProps = myStaticProps.toArray();
         else {
            ArrayList<Object> mergedStatic = new ArrayList<Object>();
            mergedStatic.addAll(Arrays.asList(staticProps));
            mergedStatic.addAll(myStaticProps);
            staticProps = mergedStatic.toArray();
         }
      }
   }

   public boolean equals(Object other) {
      if (!(other instanceof SyncProperties))
         return false;
      if (other == this)
         return true;

      SyncProperties op = (SyncProperties) other;
      if (!DynUtil.equalObjects(op.destName, destName))
         return false;
      if (!DynUtil.equalObjects(op.syncGroup, syncGroup))
         return false;
      if (!Arrays.equals(op.classProps, classProps))
         return false;
      if (!DynUtil.equalObjects(op.chainedProps, chainedProps))
         return false;
      if (!DynUtil.equalObjects(op.allowCreate, allowCreate))
         return false;
      if (!DynUtil.equalObjects(op.initDefault, initDefault))
         return false;
      if (!DynUtil.equalObjects(op.constant, constant))
         return false;
      if (!DynUtil.equalObjects(op.broadcast, broadcast))
         return false;
      if (!DynUtil.equalObjects(op.defaultScopeId, defaultScopeId))
         return false;
      if (!DynUtil.equalObjects(op.defaultPropOptions, defaultPropOptions))
         return false;
      return true;
   }

   public int hashCode() {
      if (classProps == null)
         return 0;
      return Arrays.hashCode(classProps);
   }

   public Object[] getNewSyncProperties(SyncProperties old) {
      Object[] oldProps = old.getSyncProperties();
      Object[] newProps = getSyncProperties();
      if (oldProps == null)
         return newProps;
      ArrayList<Object> res = new ArrayList<Object>();
      List<Object> oldList = Arrays.asList(oldProps);
      for (Object newProp:newProps) {
         if (!oldList.contains(newProp))
            res.add(newProp);
      }
      return res.toArray();
   }
}
