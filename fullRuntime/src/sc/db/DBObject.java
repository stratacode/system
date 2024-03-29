/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.db;


import sc.bind.Bind;
import sc.bind.IChangeable;
import sc.bind.IListener;
import sc.dyn.DynUtil;
import sc.dyn.IPropValidator;
import sc.type.CTypeUtil;
import sc.type.IBeanMapper;
import sc.util.StringUtil;

import java.util.*;

/**
 * Database object class, usually stored as a code-generated field _dbObject in a given instance.
 * Manages the persistent state for the wrapping instance, using layered "DBTypeDescriptors" - used to map
 * properties from a primary table/database (to get the id properties), then merge in auxiliary tables/databases.
 * This is "mostly thread-safe" class. Instances can be cached and shared in scopes under the "highlander" principle -
 * one entity instance with a given id visible in a given process.
 * Individual fields and the default getX/setX methods operate on cached values, lazily populated by executing
 * queries, that each populate one or more properties at a time. If two threads try to query the same group, one
 * performs the query, the other waits.
 * Updates are stored in a transaction-specific way so that pending changes are only visible to the current thread.
 * Each object stores a set of pendingOps - TxUpdate, Insert, or Delete for this instance in different transactions.
 * The getX method looks in the list of pendingOps and returns a pending update before the cached value.
 * During the 'commit', the object instance itself is locked and all values from the committed transaction are applied
 * to the cached value. It's possible to use getX methods without synchronizing on the instance, but to ensure
 * a transactionally consistent view of an instance, sync on the instance while calling getX/setX or store make sure it's
 * stored a synchronized scope.
 */
@sc.js.JSSettings(prefixAlias="sc_", jsLibFiles="js/db.js")
public class DBObject implements IDBObject {
   final static int MAX_FETCH_ERROR_COUNT = 3;
   final static String ObjectIdSeparator = "__";
   final static String TransientSuffix = "t";

   // 2 Bits per each queryIndex in 'fstate' - for the queries needed to populate this instance - by default the primaryTable is index 0
   // then followed by aux-tables, or multi-tables. If two queries come in for the same batch, only one is executed - the rest wait and they
   // share the cached value in the index.
   public static final int PENDING = 1;
   public static final int FETCHED = 2;
   protected long fstate = 0;

   protected DBTypeDescriptor dbTypeDesc;

   // Bits set in 'flags':

   public static final int TRANSIENT = 1; // set by default, but should be cleared for objects returned from the database
   public static final int REMOVED = 2;
   public static final int PROTOTYPE = 4; // set when the instance is in 'prototype' mode - set id properties, or other properties and do 'dbRefresh()'
   public static final int PENDING_INSERT = 8;
   public static final int STOPPED = 16;
   public static final int PENDING_DELETE = 32;

   private int flags = TRANSIENT;

   /**
    * The object used for storing properties for this DBObject. When null, this instance is used for properties.
    * When not null, this instance is stored as the _dbObject field in a "wrapper" instance where this is our pointer back.
    */
   public IDBObject wrapper;

   Object dbId;
   boolean unknownType;

   // Keep track of all of the transactions operation on this instance at one time - synchronized on this instance to access or update
   public final List<TxOperation> pendingOps = new ArrayList<TxOperation>();

   /** This will be set when this DBObject has been replaced for some reason by another one.
    *  Each operation will be redirected to the replaced version... maybe due to a race condition during the creation,
    *  because an instance was deserialized using the existing version. */
   public IDBObject replacedBy = null;

   String objectId = null;

   private TreeMap<String,DBChangeableListener> changeableListeners;

   public DBObject(DBTypeDescriptor dbType) {
      this.dbTypeDesc = dbType;
      this.unknownType = true;
      // init(); not calling init here as this is used before we have an instance to fetch the type and initial properties
   }

   // We can also just extend DBObject to inherit - in this case wrapper is null.
   public DBObject() {
      this.dbTypeDesc = DBTypeDescriptor.getByType(DynUtil.getType(this), true);
      init();
   }

   // Used in generated code with a new field 'DBObject _dbObject = new DBObject(this)' along with IDBObject wrapper methods
   public DBObject(IDBObject wrapper) {
      this.wrapper = wrapper;
      this.dbTypeDesc = DBTypeDescriptor.getByType(DynUtil.getType(getInst()), true);
   }

   public void setWrapper(IDBObject wrapper, DBTypeDescriptor typeDesc) {
      this.wrapper = wrapper;
      this.dbTypeDesc = typeDesc;
      this.unknownType = false;
   }

   public void init() {
      if (dbTypeDesc != null)
         dbTypeDesc.initDBObject(this);
   }

   /** Returns the persistent instance with the getX/setX methods */
   public IDBObject getInst() {
      return wrapper == null ? this : wrapper;
   }

   private PropUpdate getPendingUpdate(DBTransaction curr, String property, boolean createUpdateProp, Object propertyValue) {
      DBPropertyDescriptor pdesc = dbTypeDesc.getPropertyDescriptor(property);
      if (pdesc == null)
         throw new IllegalArgumentException("Property update for: " + property + " without db property descriptor for type: " + dbTypeDesc);
      if (pdesc instanceof IdPropertyDescriptor)
         throw new IllegalArgumentException("Update for id property on persistent object: " + pdesc);

      // Don't record changes to read-only properties.
      if (pdesc.readOnly) {
         if (pdesc.reversePropDesc == null)
            throw new IllegalArgumentException("Attempt to update readOnly property that's not the reverse side of a relationship");
         // We have an association that is transient - this is our only change to insert the value
         else if (propertyValue instanceof IDBObject) {
            IDBObject assocVal = (IDBObject) propertyValue;
            if (((DBObject) assocVal.getDBObject()).isTransient() && !isTransient())
               assocVal.dbInsert(true);
         }
         return null;
      }

      TxOperation op = getPendingOperation(curr, property, createUpdateProp, false, false, false, false);
      if (op instanceof TxUpdate) {
         TxUpdate objUpd = (TxUpdate) op;
         PropUpdate propUpd = objUpd.updateIndex.get(property);
         if (propUpd == null) {
            if (createUpdateProp) {
               propUpd = new PropUpdate(pdesc);
               objUpd.updateIndex.put(property, propUpd);
               objUpd.updateList.add(propUpd);
            }
         }
         return propUpd;
      }
      else if (op instanceof TxListUpdate) {
         TxListUpdate listUpdate = (TxListUpdate) op;
         return listUpdate.getPropUpdate();
      }
      else if (op instanceof TxInsert)
         return null; // The insert will insert the instance when the transaction is committed, so don't override the property value
      else if (op instanceof TxDelete)
         return null; // ???
      else if (op != null)
         throw new UnsupportedOperationException();
      return null;
   }

   <E extends IDBObject> TxListUpdate<E> getListUpdate(DBList<E> list, boolean create, boolean emptyList) {
      if (!list.trackingChanges || (flags & (TRANSIENT | REMOVED | STOPPED)) != 0)
         return null;
      DBTransaction cur = create ? DBTransaction.getOrCreate() : DBTransaction.getCurrent();
      if (cur == null || cur.applyingDBChanges)
         return null;
      synchronized (pendingOps) {
         instanceLastUsed = cur.startTime;
         int txSz = pendingOps.size();
         TxUpdate upd = null;
         for (int i = 0; i < txSz; i++) {
            TxOperation c = pendingOps.get(i);
            if (c.transaction == cur && c instanceof TxUpdate) {
               upd = (TxUpdate) c;

               TxListUpdate<E> listUpdate = upd.listUpdateIndex.get(list.listProp.propertyName);
               if (listUpdate != null)
                  return listUpdate;
            }
         }

         if (create) {
            if (upd == null) {
               upd = new TxUpdate(cur, this);
               pendingOps.add(upd);
               cur.addOp(upd);
            }
            TxListUpdate<E> listUpdate = new TxListUpdate<E>(cur, this, list, emptyList);
            upd.listUpdateIndex.put(list.listProp.propertyName, listUpdate);
            upd.listUpdates.add(listUpdate);

            return listUpdate;
         }
      }
      return null;
   }

   boolean removeOperation(TxOperation op) {
      synchronized (pendingOps) {
         return pendingOps.remove(op);
      }
   }

   // get or create operations using one lock
   private TxOperation getPendingOperation(DBTransaction curr, String property, boolean createUpdateProp, boolean createInsert,
                                           boolean createDelete, boolean remove, boolean queue) {
      synchronized (pendingOps) {
         instanceLastUsed = curr.startTime;

         // If we've updated the property in this transaction, find the updated value and return
         // a reference to that update so the updated value gets returned - only for that transaction.
         int txSz = pendingOps.size();
         for (int i = 0; i < txSz; i++) {
            TxOperation c = pendingOps.get(i);
            if (c.transaction == curr) {
               boolean apply = false;
               if (createInsert && c instanceof TxInsert) {
                  remove = true;
                  apply = true;
               }
               if (createDelete) {
                  if (c instanceof TxDelete) {
                     remove = true;
                     apply = true;
                  }
                  else if (c instanceof TxUpdate || c instanceof TxInsert || c instanceof TxListUpdate) {
                     // TODO: for TxInsert, should we skip the DELETE call since the row is not there
                     curr.removeOp(c);
                     pendingOps.remove(i);
                     txSz--;
                     i--;
                     continue;
                  }
                  else
                     System.err.println("*** Unhandled case in dbDelete");
               }
               if (createUpdateProp) { // TODO: Remove this if statement
                  if (c instanceof TxListUpdate) {
                     TxListUpdate listUpdate = (TxListUpdate) c;
                     if (!listUpdate.oldList.listProp.propertyName.equals(property)) {
                        System.err.println("*** Remove this whole thing!");
                        continue;
                     }
                  }
               }
               if (remove) {
                  curr.removeOp(c);
                  pendingOps.remove(i);
               }
               if (apply) {
                  c.apply();
               }
               return c;
            }
         }
         if (createUpdateProp) {
            TxUpdate tu = new TxUpdate(curr, this);
            curr.addOp(tu);
            pendingOps.add(tu);
            return tu;
         }
         if (createInsert) {
            TxInsert ins = new TxInsert(curr, this);
            if (queue) {
               curr.addOp(ins);
               pendingOps.add(ins);
            }
            else
               ins.apply();
            return ins;
         }
         if (createDelete) {
            TxDelete del = new TxDelete(curr, this);
            if (queue) {
               curr.addOp(del);
               pendingOps.add(del);
            }
            else
               del.apply();
            return del;
         }
      }
      return null;
   }

   private void restartIfNecessary() {
      if ((flags & STOPPED) != 0) {
         fstate = 0;
         flags &= ~STOPPED;
         // Note: this can set replacedBy if this instance was replaced by another one in the cache
         dbTypeDesc.recacheDBObject(this);
      }
   }

   public PropUpdate dbFetch(String property) {
      restartIfNecessary();
      if (replacedBy != null)
         return ((DBObject) replacedBy.getDBObject()).dbFetch(property);

      if ((flags & (TRANSIENT | PROTOTYPE)) != 0)
         return null;

      if ((flags & (REMOVED | STOPPED)) != 0)
         throw new IllegalStateException("GetProperty on: " + getStateString() + " dbObject: " + this);

      DBTransaction curTransaction = DBTransaction.getOrCreate();

      PropUpdate pu = getPendingUpdate(curTransaction, property, false, null);
      if (pu != null)
         return pu;

      if (curTransaction.applyingDBChanges) {
         curTransaction.addFetchLaterProperty(wrapper, property);
         return null;
      }

      SelectGroupQuery selectQuery = dbTypeDesc.getFetchQueryForProperty(property);
      if (selectQuery == null)
         throw new IllegalArgumentException("Missing propQueries entry for property: " + property);
      int lockCt = selectQuery.queryNumber;
      if (lockCt >= 31) // TODO: add an optional array to handle more?
         throw new IllegalArgumentException("Query limit exceeded: " + lockCt + " queries for: " + dbTypeDesc);
      // two bits for each lock - 3 states 0,1,2
      int shift = lockCt << 1;
      while (((fstate >> shift) & FETCHED) == 0) {
         runQueryOnce(curTransaction, selectQuery);
      }
      return null;
   }

   public PropUpdate dbFetchWithRefId(String property) {
      PropUpdate res = dbFetch(property);
      if (res == null) {
         DBPropertyDescriptor prop = dbTypeDesc.getPropertyDescriptor(property);
         if (prop.getNeedsRefId()) {
            IDBObject inst = getInst();
            Object idVal = prop.getRefIdProperty(inst);
            if (idVal != null) {
               IDBObject refInst = prop.refDBTypeDesc.findById(idVal);
               if (refInst == null)
                  return null;
               else {
                  IBeanMapper mapper = prop.getPropertyMapper();

                  DBTransaction curTx = DBTransaction.getOrCreate();
                  if (curTx.applyingDBChanges) {
                     curTx.addFetchLaterProperty(wrapper, property);
                     return null;
                  }

                  curTx.applyingDBChanges = true;
                  try {
                     prop.setRefIdProperty(inst, null);
                     mapper.setPropertyValue(getInst(), refInst);
                  }
                  finally {
                     curTx.applyingDBChanges = false;
                     curTx.doFetchLater();
                  }
               }
            }
         }
      }
      return res;
   }

   public boolean dbFetchDefault() {
      restartIfNecessary();

      if (replacedBy != null)
         return ((DBObject) replacedBy.getDBObject()).dbFetchDefault();

      if ((flags & TRANSIENT) != 0)
         return false;

      if ((flags & (REMOVED | STOPPED)) != 0)
         throw new IllegalStateException("GetDefaultProperties on: " + getStateString() + " dbObject: " + this);

      SelectGroupQuery selectQuery = dbTypeDesc.getDefaultFetchQuery();
      if (selectQuery == null)
         throw new IllegalArgumentException("Missing default select query");
      int lockCt = selectQuery.queryNumber;
      if (lockCt >= 31) // TODO: add an optional array to handle more?
         throw new IllegalArgumentException("Query limit exceeded: " + lockCt + " queries for: " + dbTypeDesc);
      // two bits for each lock - 3 states 0,1,2
      int shift = lockCt << 1;
      DBTransaction curTransaction = DBTransaction.getOrCreate();
      while (((fstate >> shift) & FETCHED) == 0) {
         if (!runQueryOnce(curTransaction, selectQuery))
            return false;
      }
      return true;
   }

   private boolean runQueryOnce(DBTransaction curTransaction, SelectGroupQuery selectQuery) {
      int shift = selectQuery.queryNumber << 1;
      boolean doFetch = false;
      int errorCount = 0;
      boolean res = false;
      synchronized(this) {
         long state = fstate >> shift;
         if ((state & (PENDING | FETCHED)) == 0) {
            fstate = fstate | (PENDING << shift);
            doFetch = true;
         }
         else if (state == PENDING) {
            try {
               wait();
            }
            catch (InterruptedException exc) {
            }
         }
      }

      if (doFetch) {
         boolean selected = false;
         try {
            // TODO: do we want to flush before we query? It's possible the query results will be different for this transaction, and so the global
            // cache could show changes made in the transaction to others prematurely. One fix would be to cache queries made on modified transactions in the local
            // per-transaction cache until the transaction is committed. Then move them over to the shared cache.
            //curr.flush(); // Flush out any updates before running the query
            if (selectQuery.selectProperties(curTransaction, this)) {
               selected = true;
               res = true;
               clearPrototype();
            }
            else {
               if ((flags & PROTOTYPE) == 0) {
                  System.err.println("*** Non-prototype state DBObject no longer exists in DB");
               }
            }
         }
         catch (RuntimeException exc) {
            System.err.println("*** Fetch query failed: " + exc);
            exc.printStackTrace();
            errorCount++;
            if (errorCount == MAX_FETCH_ERROR_COUNT)
               throw exc;
         }
         synchronized(this) {
            if (selected) {
               fstate = (fstate & ~(PENDING << shift)) | (FETCHED << shift);
            }
            notify();
         }
      }
      else
         res = (fstate & (FETCHED << shift)) != 0;
      return res;
   }

   public PropUpdate dbSetIdProp(String propertyName, Object propertyValue, Object oldVal)  {
      if (replacedBy != null)
         return ((DBObject) replacedBy.getDBObject()).dbSetIdProp(propertyName, propertyValue, oldVal);

      if ((flags & (TRANSIENT | REMOVED | STOPPED | PROTOTYPE)) != 0)
         return null;

      if (propertyValue == oldVal)
         return null;

      throw new IllegalStateException("Id property values can't be changed on a persistent instance");
   }

   /**
    * Called to update the saved representation of the instance. Returns null to allow the assignment
    * in the field. If it returns non-null, the setX method will just return.
    * Note: In the current implementation if there's a change to the property made that's local to the thread, the
    * Bind.sendEvent call should be made by the framework since the setX method won't do anything else... for example,
    * in a reverse relationship change.
    */
   public PropUpdate dbSetProp(String propertyName, Object propertyValue, Object oldValue)  {
      restartIfNecessary();

      if (replacedBy != null)
         return ((DBObject) replacedBy.getDBObject()).dbSetProp(propertyName, propertyValue, oldValue);

      if ((flags & (REMOVED | STOPPED)) != 0)
         throw new IllegalStateException("setProperty on: " + getStateString() + " dbObject: " + this);

      boolean changed = false;
      if (propertyValue != oldValue) {
         changed = true;
         if (oldValue instanceof IChangeable) {
            if (changeableListeners != null) {
               DBChangeableListener oldListener = changeableListeners.remove(propertyName);
               if (oldListener != null)
                  Bind.removeListener(oldValue, null, oldListener, IListener.VALUE_INVALIDATED);
            }
         }
         if (propertyValue instanceof IChangeable) {
            if (changeableListeners == null)
               changeableListeners = new TreeMap<String,DBChangeableListener>();
            DBChangeableListener newListener = new DBChangeableListener(this, propertyName, (IChangeable) propertyValue);
            Bind.addListener(propertyValue, null, newListener, IListener.VALUE_INVALIDATED);
            changeableListeners.put(propertyName, newListener);
         }
      }
      else if (propertyValue instanceof IChangeable)
         changed = true;

      if ((flags & TRANSIENT) != 0)
         return null;
      DBTransaction curr = DBTransaction.getOrCreate();
      // When we are applying this transaction, we need to return null here to allow the setX call to update the cached value
      if (curr.commitInProgress) {
         return null;
      }
      SelectGroupQuery selectQuery = dbTypeDesc.getFetchQueryForProperty(propertyName);
      if (selectQuery == null)
         throw new IllegalArgumentException("Missing propQueries entry for property: " + propertyName);
      int lockCt = selectQuery.queryNumber;
      long fetchedBit = FETCHED << (lockCt * 2);

      // For the case where we are populating a newly selected instance, mark the property as selected and update the field
      if (curr.applyingDBChanges) {
         if ((fstate & fetchedBit) != 0)
            return null;

         // This is by default true but set to false when updating referenced values, since we might be setting the reverse
         // property in an object but do not want to mark it's state as fetched - just the parent object's state.
         if (curr.updateSelectState) {
            synchronized (this) {
               fstate |= fetchedBit;
            }
         }
         return null;
      }

      // Setting a property that has not been selected from the DB - possibly being selected from a query
      if ((fstate & fetchedBit) == 0)
         return null;
      //if (((fstate >> (lockCt * 2)) & FETCHED) == 0)
      //   return null;

      // If we are setting the value to the same value that's in the cache don't create a new DB update. A common case is
      // setting a property that's been fetched to null that's already null.
      // but for IChangeable, the value may have changed particularly if this called from DBChangeableListener
      PropUpdate pu = getPendingUpdate(curr, propertyName, changed, propertyValue);
      if (pu == null)
         return null;
      pu.value = propertyValue;

      // TODO: should we make this an option? If we don't; send this, reverse properties are not updated until after the
      // commit. We should at least be able to make those changes visible by the current thread before the commit and it
      // seems like code will behave better if it's notified sooner that a change is coming. If we notify other threads about a
      // change in progress it's not a problem - locking will protect it in some cases or if the other thread sees the old value
      // another change will be sent when we commit the property.
      Bind.sendChange(getInst(), propertyName, propertyValue);
      return pu;
   }

   public void dbInsert(boolean queue) {
      if (replacedBy != null) {
         replacedBy.dbInsert(queue);
         return;
      }

      synchronized (this) {
         if ((flags & (REMOVED | STOPPED | PENDING_DELETE)) != 0)
            throw new IllegalStateException("dbInsert on " + getStateString() + " instance: " + this);
         if ((flags & TRANSIENT) == 0) {
            if ((flags & PROTOTYPE) != 0) // TODO: or should we just allow this?
               throw new IllegalArgumentException("Attempting to insert prototype - use createInstance(), not createPrototype() for instances to be inserted ");
            throw new IllegalArgumentException("Attempting to insert non-transient instance");
         }
         flags |= PENDING_INSERT;
      }
      DBTransaction curr = DBTransaction.getOrCreate();
      TxOperation op = getPendingOperation(curr, null, false, true, false, false, queue);
      // The createInsert flag should return a new TxInsert, unless there's another operation already associated
      // to this instance in this transaction which I think should flag an error.
      if (!(op instanceof TxInsert))
         throw new UnsupportedOperationException();
      if (op.applied) {
         synchronized (this) {
            flags &= ~PENDING_INSERT;
         }
      }
   }

   public int dbUpdate() {
      DBTransaction curr = DBTransaction.getOrCreate();
      TxOperation op = getPendingOperation(curr, null, false, false, false, true, false);
      if (op != null)
         return op.apply();
      return 0;
   }

   public void dbDelete(boolean queue) {
      restartIfNecessary();
      if (replacedBy != null) {
         replacedBy.dbDelete(queue);
         return;
      }
      DBTransaction curr = DBTransaction.getOrCreate();

      synchronized (this) {
         if ((flags & (STOPPED | REMOVED)) != 0)
           throw new IllegalStateException("dbDelete for " + getStateString() + " instance: " + this);
         if ((flags & TRANSIENT) != 0)  // TODO: maybe this should be ok?
            throw new IllegalStateException("Attempting to remove instance never added");
         flags |= PENDING_DELETE;
      }
      TxOperation op = getPendingOperation(curr, null, false, false, true, false, queue);
      if (!(op instanceof TxDelete))
         throw new UnsupportedOperationException();

      if (op.applied) {
         synchronized(this) {
            flags &= ~PENDING_DELETE;
         }
      }
   }

   public Map<String,String> dbValidate() {
      DBTransaction curr = DBTransaction.getOrCreate();
      Map<String,String> curRes = null;
      synchronized (pendingOps) {
         // If we've updated the property in this transaction, find the updated value and return
         // a reference to that update so the updated value gets returned - only for that transaction.
         int txSz = pendingOps.size();
         for (int i = 0; i < txSz; i++) {
            TxOperation c = pendingOps.get(i);
            if (c.transaction == curr) {
               Map<String,String> errors = c.validate();
               if (curRes == null)
                  curRes = errors;
               else
                  curRes.putAll(errors);
            }
         }
      }
      return curRes;
   }

   public boolean dbRefresh() {
      restartIfNecessary();

      if (replacedBy != null)
         return replacedBy.dbRefresh();

      if ((flags & (STOPPED | TRANSIENT | REMOVED)) != 0)
         throw new IllegalStateException("dbRefresh for: " + getStateString() + " instance: " + this);

      if ((flags & PROTOTYPE) != 0) {
         // TODO: if the id properties are not set, and other properties have been set, we could do a query using those properties here
         dbFetch(null);
         return (flags & PROTOTYPE) == 0;
      }

      long selectState = fstate;
      fstate = 0;
      int queryNum = 0;
      DBTransaction curTransaction = DBTransaction.getOrCreate();
      while (selectState != 0) {
         if ((selectState & (FETCHED | PENDING)) != 0) {
            DBQuery query = dbTypeDesc.getFetchQueryForNum(queryNum);
            if (query instanceof SelectGroupQuery) {
               if (!runQueryOnce(curTransaction, (SelectGroupQuery) query))
                  System.out.println("*** DBrefresh of query: " + query + " returned no rows");
            }
            else
               System.out.println("*** DBrefresh - warning - not refreshing non select query");
         }
         selectState = selectState >> 2;
         queryNum++;
      }
      return flags == 0;
   }

   public boolean isTransient() {
      return (flags & TRANSIENT) != 0;
   }

   public synchronized void setTransient(boolean val) {
      if (val)
         flags = TRANSIENT;
      else
         flags &= ~(TRANSIENT | PENDING_INSERT);
   }

   public boolean isPrototype() {
      return (flags & PROTOTYPE) != 0;
   }

   public synchronized void clearPrototype() {
      if (isPrototype()) {
         setPrototype(false);
         //init(); // We didn't add reversePropertyListeners on the prototype so do that here
      }
   }

   public synchronized void setPrototype(boolean val) {
      if (val) {
         flags = PROTOTYPE;
      }
      else
         flags &= ~PROTOTYPE;
   }

   public void initProtoProperties(String... protoProps) {
      for (String protoProp:protoProps) {
         DBPropertyDescriptor pdesc = dbTypeDesc.getPropertyDescriptor(protoProp);
         if (pdesc.multiRow) {
            //ArrayList<Object> oneVal = new ArrayList<Object>(1);
            //if (pdesc.refDBTypeDesc != null) {
            //   oneVal.add(pdesc.refDBTypeDesc.createPrototype(false));
            //   setPropertyInPath(protoProp, oneVal);
            //}
            //else
            DBUtil.error("Failed to init multi-valued prototype property: " + protoProp + ": " + pdesc + " - don't handle list prototype properties of this type yet");
         }
         else {
            if (pdesc.refDBTypeDesc != null) {
               setPropertyInPath(protoProp, pdesc.refDBTypeDesc.createPrototype(false));
            }
            else if (pdesc.getDBColumnType() == DBColumnType.Json) {
               Object inst = DynUtil.createInstance(pdesc.getPropertyMapper().getPropertyType(), null);
               setPropertyInPath(protoProp, inst);
            }
            else
               DBUtil.error("Failed to init parent prototype: " + protoProp + ": " + pdesc + " not a reference type");
         }
      }
   }

   public void setPropertyInPath(String propPath, Object value) {
      String[] propArr = StringUtil.split(propPath, '.');
      Object curInst = getInst();
      int pathLen = propArr.length;
      //DBTypeDescriptor curTypeDesc = dbTypeDesc;
      int lastIx = pathLen - 1;
      for (int i = 0; i < lastIx; i++) {
         String curProp = propArr[i];
         curInst = DynUtil.getPropertyValue(curInst, curProp);
         if (curInst == null)
            throw new NullPointerException("Null reference in DBObject.setPropertyInPath path");
         //DBPropertyDescriptor propDesc = curTypeDesc.getPropertyDescriptor(curProp);
         //if (propDesc == null)
         //   System.err.println("*** No db property descriptor found for value: " + curProp);
         // In initProtoProperties we will have created a single element list to store the prototype values for this
         // multi-valued property
         //else if (propDesc.multiRow) {
         //   curInst = ((List) curInst).get(0);
         //}
      }
      DynUtil.setPropertyValue(curInst, propArr[lastIx], value);
   }

   public Object getPropertyInPath(String propPath) {
      String[] propArr = StringUtil.split(propPath, '.');
      Object curInst = getInst();
      int pathLen = propArr.length;
      for (int i = 0; i < pathLen; i++) {
         curInst = DynUtil.getPropertyValue(curInst, propArr[i]);
      }
      return curInst;
   }

   public Object getProperty(String propName) {
      return DynUtil.getPropertyValue(getInst(), propName);
   }

   public boolean isPendingInsert() {
      return (flags & PENDING_INSERT) != 0;
   }

   public void registerNew() {
      IDBObject curInst = getInst();
      IDBObject newInst = dbTypeDesc.registerInstance(curInst, true);
      if (newInst != null) {
         if (newInst != curInst) {
            System.err.println("*** Warning - registering new instance that has already been replaced!");
            replacedBy = newInst;
         }
         // else - this instance has already been registered so don't do anything
         return;
      }
      // Mark all of the property queries in this type as selected so we don't re-query them until the cache is invalidated
      int numFetchQueries = dbTypeDesc.getNumFetchPropQueries();
      synchronized (this) {
         for (int f = 0; f < numFetchQueries; f++) {
            fstate |= DBObject.FETCHED << (f*2);
         }
      }
   }

   long instanceLastUsed = -1;

   public synchronized void markStopped() {
      if ((flags & STOPPED) != 0) {
         System.err.println("DBObject.markStopped() called on stopped object");
         return;
      }
      if ((flags & (PENDING_INSERT | TRANSIENT | PROTOTYPE | REMOVED)) != 0)
         throw new IllegalArgumentException("Invalid state for markStopped: " + getStateString());
      if (changeableListeners != null)
         clearChangeableListeners();
      flags = STOPPED;
   }

   public synchronized void markRemoved() {
      if ((flags & (PENDING_INSERT | TRANSIENT | PROTOTYPE | STOPPED)) != 0)
         throw new IllegalArgumentException("Invalid state for markRemoved: " + getStateString());
      if (changeableListeners != null)
         clearChangeableListeners();
      flags = REMOVED;
   }

   private void clearChangeableListeners() {
      if (changeableListeners != null) {
         for (Map.Entry<String,DBChangeableListener> ent: changeableListeners.entrySet()) {
            DBChangeableListener listener = ent.getValue();
            Bind.removeListener(listener.value, null, listener, IListener.VALUE_INVALIDATED);
         }
         changeableListeners = null;
      }
   }

   public void setObjectId(String str) {
      objectId = str;
   }

   public String getObjectId() {
      if (objectId != null)
         return objectId;

      StringBuilder sb = new StringBuilder();
      Object inst = getInst();
      String typeName = DynUtil.getTypeName(dbTypeDesc.typeDecl, false);
      sb.append(typeName);
      sb.append(DBObject.ObjectIdSeparator);
      if (isTransient()) {
         appendTransientId(sb);
      }
      else {
         for (int i = 0; i < dbTypeDesc.primaryTable.idColumns.size(); i++) {
            if (i != 0)
               sb.append("__");
            IdPropertyDescriptor idProp = dbTypeDesc.primaryTable.idColumns.get(i);
            Object idVal = idProp.getPropertyMapper().getPropertyValue(inst, false, false);
            if (idVal == null || (idVal instanceof Long && ((Long) idVal) == 0)) {
               appendTransientId(sb);
            }
            else
               sb.append(idVal);
         }
      }
      objectId = sb.toString();
      return objectId;
   }

   private void appendTransientId(StringBuilder sb) {
      sb.append(dbTypeDesc.transientIdCount++);
      sb.append(DBObject.TransientSuffix);
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      if ((flags & (TRANSIENT|PROTOTYPE)) == 0) {
         sb.append(getObjectId());
      }
      else {
         sb.append(CTypeUtil.getClassName(DynUtil.getTypeName(DynUtil.getType(getInst()), false)));
      }
      if (flags != 0) {
         sb.append(" - ");
         sb.append(getStateString());
      }
      if (fstate != 0) {
         sb.append(getFetchedStateString(dbTypeDesc, fstate));
      }
      return sb.toString();
   }

   public static String getFetchedStateString(DBTypeDescriptor dbTypeDesc, long fstate) {
      if (fstate == 0)
         return "";
      long cstate = fstate;
      int qn = 0;
      List<DBQuery> ql = dbTypeDesc.selectQueriesList;
      StringBuilder res = new StringBuilder();
      while (cstate > 0) {
         DBQuery query = qn >= ql.size() ? null : ql.get(qn);
         if (query == null) {
            return "Invalid fstate";
         }

         if ((cstate & PENDING) != 0) {
            res.append("[" + query.queryName + ":pending]");
         }
         if ((cstate & FETCHED) != 0) {
            res.append("[" + query.queryName + "]");
         }
         cstate = cstate >> 2;
         qn++;
      }
      return res.toString();
   }

   /** Called once the database has been updated with the changes. Updates the cached values in this instance. */
   void applyUpdates(DBTransaction transaction, ArrayList<PropUpdate> updateList, DBPropertyDescriptor versProp, long newVersion,
                     DBPropertyDescriptor lastModifiedProp, Date lmtValue) {
      synchronized (pendingOps) {
         transaction.commitInProgress = true;
         if (transaction.commitTime == -1)
            transaction.commitTime = System.currentTimeMillis();
         instanceLastUsed = transaction.commitTime;
         try {
            IDBObject inst = getInst();
            boolean needsValidate = inst instanceof IPropValidator;
            boolean errorsChanged = false;
            Map<String,String> propErrors = needsValidate ? ((IPropValidator) inst).getPropErrors() : null;
            Map<String,String> newPropErrors = null;
            for (PropUpdate propUpdate:updateList) {
               DBPropertyDescriptor prop = propUpdate.prop;
               Object newVal = propUpdate.value;
               String propName = prop.propertyName;
               if (needsValidate && prop.hasValidator()) {
                  String err = propUpdate.prop.validate(this, newVal);
                  if (err == null) {
                     if (propErrors != null) {
                        if (newPropErrors == null)
                           newPropErrors = new TreeMap<String,String>(propErrors);
                        newPropErrors.remove(propName);
                        errorsChanged = true;
                     }
                  }
                  else {
                     if (propErrors != null) {
                        String oldError = propErrors.get(propName);
                        if (!StringUtil.equalStrings(oldError, err)) {
                           if (newPropErrors == null)
                              newPropErrors = new TreeMap<String,String>(propErrors);
                           newPropErrors.put(propName, err);
                           errorsChanged = true;
                        }
                     }
                     else {
                        newPropErrors = new TreeMap<String,String>();
                        newPropErrors.put(propName, err);
                        errorsChanged = true;
                     }
                  }
               }
               propUpdate.prop.getPropertyMapper().setPropertyValue(inst, newVal);
            }
            if (versProp != null)
               versProp.getPropertyMapper().setPropertyValue(inst, newVersion);
            if (lastModifiedProp != null && lmtValue != null)
               lastModifiedProp.getPropertyMapper().setPropertyValue(inst, lmtValue);
            if (errorsChanged)
               ((IPropValidator) inst).setPropErrors(newPropErrors.size() == 0 ? null : newPropErrors);
         }
         finally {
            transaction.commitInProgress = false;
         }
      }
   }

   public DBObject getDBObject() {
      return this;
   }

   public Object getDBId() {
      List<IdPropertyDescriptor> idProps = dbTypeDesc.primaryTable.idColumns;
      int num = idProps.size();
      if (num == 1) {
         if (unknownType) {
            if (dbId != null)
               return dbId;
            System.err.println("*** getDBId() on DBObject with unknown type and no id");
         }
         IdPropertyDescriptor idProp = idProps.get(0);
         return idProp.getPropertyMapper().getPropertyValue(getInst(), false, false);
      }
      else {
         MultiColIdentity res = new MultiColIdentity(num);
         for (int i = 0; i < num; i++) {
            IdPropertyDescriptor idProp = idProps.get(i);
            res.setVal(idProp.getPropertyMapper().getPropertyValue(getInst(), false, false), i);
         }
         return res;
      }
   }

   public void setDBId(Object dbId) {
      this.dbId = dbId;
      if (unknownType)
         return;

      List<IdPropertyDescriptor> idProps = dbTypeDesc.primaryTable.idColumns;
      int num = idProps.size();
      if (num == 1) {
         IdPropertyDescriptor idProp = idProps.get(0);
         idProp.getPropertyMapper().setPropertyValue(getInst(), dbId);
      }
      else {
         MultiColIdentity id = (MultiColIdentity) dbId;
         for (int i = 0; i < num; i++) {
            IdPropertyDescriptor idProp = idProps.get(i);
            idProp.getPropertyMapper().setPropertyValue(getInst(), id.vals[i]);
         }
      }
   }

   public void stop() {
      if (isTransient())
         return;
      // We do put prototypes into the typeInstances table, to reserve the spot for a particular id we are looking up.
      // but othertimes, prototypes are not in the typeInstances map so here we are removing it but not printing an error
      // if it's not there
      if (!dbTypeDesc.removeInstance(this, false) && !isPrototype())
         DBUtil.error("DBObject.stop: instance not found: " + this);
      else {
         /*
         for (DBPropertyDescriptor prop:dbTypeDesc.allDBProps) {
            // Need to dispose children that might reference back to us, or any child owned by this instance
            // Need to figure out how to update one-to-many properties that point back to this instance - e.g.
            // if we are disposing a userProfile, how do we update the siteAdmins that point back?
            if (prop.refDBTypeDesc != null && prop.childProperty) {
               if (!prop.multiRow) {
                  Object propVal = prop.getPropertyMapper().getPropertyValue(getInst(), false, false);
                  if (propVal != null)
                     DynUtil.dispose(propVal);
               }
               else {
                  List propVals = (List) prop.getPropertyMapper().getPropertyValue(getInst(), false, false);
                  if (propVals != null) {
                     for (Object propVal:propVals)
                        DynUtil.dispose(propVal);
                  }
               }
            }
         }
         */
      }
   }

   public boolean isActive() {
      return (flags & (REMOVED | STOPPED)) == 0;
   }

   public String getStateString() {
      if ((flags & REMOVED) != 0)
         return "removed";
      else if ((flags & STOPPED) != 0)
         return "stopped";
      else if ((flags & TRANSIENT) != 0)
         return "transient";
      else if ((flags & PENDING_INSERT) != 0)
         return "pending-insert";
      else if ((flags & PROTOTYPE) != 0)
         return "prototype";
      return "persistent";
   }

   public static PropUpdate dbGetProperty(IDBObject wrapper, DBObject obj, String prop) {
      if (obj != null)
         return obj.dbFetch(prop);
      else {
         // For when the code-gen ends up calling a getX method before the DBObject has been created. Although we can't
         // return the db value until the id and DBObject have been created, we add it to a list to get from the DB afterwards
         // It will send a change event at that time and update any bindings
         DBTransaction curTx = DBTransaction.getCurrent();
         if (curTx != null && curTx.applyingDBChanges) {
            curTx.addFetchLaterProperty(wrapper, prop);
         }
      }
      return null;
   }

   public static PropUpdate dbGetPropertyWithRefId(IDBObject wrapper, DBObject obj, String prop) {
      if (obj != null)
         return obj.dbFetchWithRefId(prop);
      return null;
   }

   public static PropUpdate dbSetProperty(DBObject obj, String propName, Object propVal, Object oldValue) {
      if (obj != null)
         return obj.dbSetProp(propName, propVal, oldValue);
      return null;
   }

   public static PropUpdate dbSetIdProperty(DBObject obj, String propName, Object propVal, Object oldVal) {
      if (obj != null)
         return obj.dbSetIdProp(propName, propVal, oldVal);
      return null;
   }

   public boolean equalProps(DBObject other) {
      if (other.dbTypeDesc != dbTypeDesc)
         return false;
      for (DBPropertyDescriptor prop:dbTypeDesc.allDBProps) {
         Object thisVal = getProperty(prop.propertyName);
         Object otherVal = other.getProperty(prop.propertyName);
         if (thisVal instanceof Date && otherVal instanceof Date) {
            if (((Date) thisVal).getTime() != ((Date) otherVal).getTime())
               return false;
         }
         else if (!DynUtil.equalObjects(thisVal, otherVal))
            return false;
      }
      return true;
   }
}
