package sc.db;


import sc.dyn.DynUtil;
import sc.type.CTypeUtil;

import java.util.ArrayList;
import java.util.List;

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
 * During the 'commit', the object instance itself is locked and all values from the commmited transaction are applied
 * to the cached value. It's possible to use getX methods without synchronizing on the instance, but to ensure
 * a transactionally consistent view of an instance, sync on the instance while calling getX/setX or store make sure it's
 * stored a synchronized scope.
 */
public class DBObject implements IDBObject {
   final static int MAX_FETCH_ERROR_COUNT = 3;

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

   private int flags = TRANSIENT;

   /**
    * The object used for storing properties for this DBObject. When null, this instance is used for properties.
    * When not null, this instance is stored as the _dbObject field in a "wrapper" instance where this is our pointer back.
    */
   IDBObject wrapper;

   // Keep track of all of the transactions operation on this instance at one time - synchronized on this instance to access or update
   public final List<TxOperation> pendingOps = new ArrayList<TxOperation>();

   /** This will be set when this DBObject has been replaced for some reason by another one.
    *  Each operation will be redirected to the replaced version... maybe due to a race condition during the creation,
    *  because an instance was deserialized using the existing version. */
   public DBObject replacedBy = null;

   // We can also just extend DBObject to inherit - in this case wrapper is null.
   public DBObject() {
      this.dbTypeDesc = DBTypeDescriptor.getByType(DynUtil.getType(this));
      init();
   }

   // Used in generated code with a new field 'DBObject _dbObject = new DBObject(this)' along with IDBObject wrapper methods
   public DBObject(IDBObject wrapper) {
      this.wrapper = wrapper;
      this.dbTypeDesc = DBTypeDescriptor.getByType(DynUtil.getType(getInst()));
   }

   public void init() {
      if (dbTypeDesc != null)
         dbTypeDesc.initDBObject(this);
   }

   /** Returns the persistent instance with the getX/setX methods */
   public IDBObject getInst() {
      return wrapper == null ? this : wrapper;
   }

   private PropUpdate getPendingUpdate(DBTransaction curr, String property, boolean createUpdateProp) {
      TxOperation op = getPendingOperation(curr, createUpdateProp, false, false, false);
      if (op instanceof TxUpdate) {
         TxUpdate objUpd = (TxUpdate) op;
         PropUpdate propUpd = objUpd.updateIndex.get(property);
         if (propUpd == null) {
            DBPropertyDescriptor pdesc = dbTypeDesc.getPropertyDescriptor(property);
            if (pdesc == null)
               throw new IllegalArgumentException("Property update for: " + property + " without db property descriptor for type: " + dbTypeDesc);
            if (pdesc instanceof IdPropertyDescriptor)
               throw new IllegalArgumentException("Update for id property on persistent object: " + pdesc);
            propUpd = new PropUpdate(pdesc);
            objUpd.updateIndex.put(property, propUpd);
            objUpd.updateList.add(propUpd);
         }
         return propUpd;
      }
      else if (op instanceof TxListUpdate) {
         return ((TxListUpdate) op).getPropUpdate();
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
      if (cur == null)
         return null;
      synchronized (pendingOps) {
         int txSz = pendingOps.size();
         for (int i = 0; i < txSz; i++) {
            TxOperation c = pendingOps.get(i);
            if (c.transaction == cur && c instanceof TxListUpdate) {
               TxListUpdate<E> listUpdate = (TxListUpdate<E>) c;
               if (listUpdate.oldList.listProp == list.listProp)
                  return listUpdate;
            }
         }

         if (create) {
            TxListUpdate<E> listUpdate = new TxListUpdate<E>(cur, this, list, emptyList);
            cur.addOp(listUpdate);
            pendingOps.add(listUpdate);
            return listUpdate;
         }
      }
      return null;
   }

   // get or create operations using one lock
   private TxOperation getPendingOperation(DBTransaction curr, boolean createUpdateProp, boolean createInsert, boolean createDelete, boolean remove) {
      synchronized (pendingOps) {
         // If we've updated the property in this transaction, find the updated value and return
         // a reference to that update so the updated value gets returned - only for that transaction.
         int txSz = pendingOps.size();
         for (int i = 0; i < txSz; i++) {
            TxOperation c = pendingOps.get(i);
            if (c.transaction == curr) {
               if (remove) {
                  curr.removeOp(c);
                  pendingOps.remove(i);
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
            if (dbTypeDesc.queueInserts) {
               curr.addOp(ins);
               pendingOps.add(ins);
            }
            else
               ins.apply();
            return ins;
         }
         if (createDelete) {
            TxDelete del = new TxDelete(curr, this);
            curr.addOp(del);
            pendingOps.add(del);
            return del;
         }
      }
      return null;
   }

   public PropUpdate dbFetch(String property) {
      if (replacedBy != null)
         return replacedBy.dbFetch(property);

      if ((flags & (TRANSIENT | REMOVED | STOPPED)) != 0)
         return null;

      DBTransaction curTransaction = DBTransaction.getOrCreate();

      PropUpdate pu = getPendingUpdate(curTransaction, property, false);
      if (pu != null)
         return pu;

      if (curTransaction.applyingDBChanges)
         return null;

      DBFetchGroupQuery fetchQuery = dbTypeDesc.getFetchQueryForProperty(property);
      if (fetchQuery == null)
         throw new IllegalArgumentException("Missing propQueries entry for property: " + property);
      int lockCt = fetchQuery.queryNumber;
      if (lockCt >= 31) // TODO: add an optional array to handle more?
         throw new IllegalArgumentException("Query limit exceeded: " + lockCt + " queries for: " + dbTypeDesc);
      // two bits for each lock - 3 states 0,1,2
      int shift = lockCt << 1;
      while (((fstate >> shift) & FETCHED) == 0) {
         runQueryOnce(curTransaction, fetchQuery);
      }
      return null;
   }

   public boolean dbFetchDefault() {
      if (replacedBy != null)
         return replacedBy.dbFetchDefault();

      if ((flags & (TRANSIENT | REMOVED | STOPPED)) != 0)
         return false;

      DBFetchGroupQuery fetchQuery = dbTypeDesc.getDefaultFetchQuery();
      if (fetchQuery == null)
         throw new IllegalArgumentException("Missing default fetch query");
      int lockCt = fetchQuery.queryNumber;
      if (lockCt >= 31) // TODO: add an optional array to handle more?
         throw new IllegalArgumentException("Query limit exceeded: " + lockCt + " queries for: " + dbTypeDesc);
      // two bits for each lock - 3 states 0,1,2
      int shift = lockCt << 1;
      DBTransaction curTransaction = DBTransaction.getOrCreate();
      while (((fstate >> shift) & FETCHED) == 0) {
         if (!runQueryOnce(curTransaction, fetchQuery))
            return false;
      }
      return true;
   }

   private boolean runQueryOnce(DBTransaction curTransaction, DBFetchGroupQuery fetchQuery) {
      int shift = fetchQuery.queryNumber << 1;
      boolean doFetch = false;
      int errorCount = 0;
      boolean res = false;
      synchronized(this) {
         long state = fstate >> shift;
         if (state == 0) {
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
         boolean fetched = false;
         try {
            // TODO: do we want to flush before we query? It's possible the query results will be different for this transaction, and so the global
            // cache could show changes made in the transaction to others prematurely. One fix would be to cache queries made on modified transactions in the local
            // per-transaction cache until the transaction is committed. Then move them over to the shared cache.
            //curr.flush(); // Flush out any updates before running the query
            if (fetchQuery.fetchProperties(curTransaction, this)) {
               fetched = true;
               res = true;
               if ((flags & PROTOTYPE) != 0)
                  flags &= ~PROTOTYPE;
            }
            else {
               if ((flags & PROTOTYPE) == 0) {
                  System.err.println("*** Non-prototype state DBObject no longer exists in DB");
               }
            }
         }
         catch (RuntimeException exc) {
            System.err.println("*** Fetch query failed: " + exc);
            errorCount++;
            if (errorCount == MAX_FETCH_ERROR_COUNT)
               throw exc;
         }
         synchronized(this) {
            if (fetched)
               fstate = (fstate & ~(PENDING << shift)) | (FETCHED << shift);
            notify();
         }
      }
      else
         res = (fstate & (FETCHED << shift)) != 0;
      return res;
   }

   /**
    * Called to update the saved representation of the instance. Returns null to allow the assignment
    * in the field. If it returns non-null, the setX method will just return.
    */
   public PropUpdate dbSetProp(String propertyName, Object propertyValue)  {
      if (replacedBy != null)
         return replacedBy.dbSetProp(propertyName, propertyValue);

      if ((flags & (TRANSIENT | REMOVED | STOPPED | PROTOTYPE)) != 0)
         return null;
      DBTransaction curr = DBTransaction.getOrCreate();
      // When we are applying this transaction, we need to return null here to allow the setX call to update the cached value
      if (curr.commitInProgress || curr.applyingDBChanges)
         return null;
      DBFetchGroupQuery fetchQuery = dbTypeDesc.getFetchQueryForProperty(propertyName);
      if (fetchQuery == null)
         throw new IllegalArgumentException("Missing propQueries entry for property: " + propertyName);
      int lockCt = fetchQuery.queryNumber;

      // Setting a property that has not been fetched from the DB - possibly being fetched from a query
      if (((fstate >> (lockCt * 2)) & FETCHED) == 0)
         return null;

      PropUpdate pu = getPendingUpdate(curr, propertyName, true);
      if (pu == null)
         return null;
      pu.value = propertyValue;
      return pu;
   }

   public void dbInsert() {
      if (replacedBy != null) {
         replacedBy.dbInsert();
         return;
      }

      if ((flags & REMOVED) != 0)
         throw new IllegalArgumentException("Attempting to insert removed instance");
      if ((flags & STOPPED) != 0)
         throw new IllegalArgumentException("Attempting to insert stopped instance");
      if ((flags & TRANSIENT) == 0) // TODO: Is this right?
         throw new IllegalArgumentException("Attempting to insert non-transient instance");
      if ((flags & PENDING_INSERT) != 0)
         throw new IllegalArgumentException("Attempting to insert instance with pending insert");
      flags |= PENDING_INSERT;
      DBTransaction curr = DBTransaction.getOrCreate();
      TxOperation op = getPendingOperation(curr, false, true, false, false);
      // The createInsert flag should return a new TxInsert, unless there's another operation already associated
      // to this instance in this transaction which I think should flag an error.
      if (!(op instanceof TxInsert))
         throw new UnsupportedOperationException();
      if (op.applied)
         flags &= ~PENDING_INSERT;
   }

   public int dbUpdate() {
      DBTransaction curr = DBTransaction.getOrCreate();
      TxOperation op = getPendingOperation(curr, false, false, false, true);
      if (op != null)
         return op.apply();
      return 0;
   }

   public void dbDelete() {
      if (replacedBy != null) {
         replacedBy.dbDelete();
         return;
      }

      if ((flags & STOPPED) != 0)
         throw new IllegalArgumentException("Attempting to remove stopped instance");
      if ((flags & REMOVED) != 0)
         throw new IllegalArgumentException("Attempting to remove already removed instance");
      if ((flags & TRANSIENT) != 0)  // TODO: maybe this should be ok?
         throw new IllegalArgumentException("Attempting to remove instance never added");
      DBTransaction curr = DBTransaction.getOrCreate();
      TxOperation op = getPendingOperation(curr, false, false, true, false);
      if (!(op instanceof TxDelete))
         throw new UnsupportedOperationException();
   }

   public boolean dbRefresh() {
      if (replacedBy != null)
         return replacedBy.dbRefresh();

      if ((flags & STOPPED) != 0)
         throw new IllegalArgumentException("dbRefresh called on stopped instance");
      if ((flags & TRANSIENT) != 0)
         throw new IllegalArgumentException("dbRefresh called on transient instance");
      if ((flags & REMOVED) != 0)
         throw new IllegalArgumentException("dbRefresh called on removed instance");

      if ((flags & PROTOTYPE) != 0) {
         // TODO: if the id properties are not set, and other properties have been set, we could do a query using those properties here
         dbFetch(null);
         return (flags & PROTOTYPE) == 0;
      }

      long fetchState = fstate;
      fstate = 0;
      int queryNum = 0;
      DBTransaction curTransaction = DBTransaction.getOrCreate();
      while (fetchState != 0) {
         if ((fetchState & (FETCHED | PENDING)) != 0) {
            DBQuery query = dbTypeDesc.getQueryForNum(queryNum);
            if (query instanceof DBFetchGroupQuery) {
               if (!runQueryOnce(curTransaction, (DBFetchGroupQuery) query))
                  System.out.println("*** DBrefresh of query: " + query + " returned no rows");
            }
            else
               System.out.println("*** DBrefresh - warning - not refreshing non fetch query");
         }
         fetchState = fetchState >> 2;
         queryNum++;
      }
      return flags == 0;
   }

   public boolean isTransient() {
      return (flags & TRANSIENT) != 0;
   }

   public void setTransient(boolean val) {
      if (val)
         flags = TRANSIENT;
      else
         flags &= ~TRANSIENT;
   }

   public boolean isPrototype() {
      return (flags & PROTOTYPE) != 0;
   }

   public void setPrototype(boolean val) {
      if (val) {
         flags = PROTOTYPE;
      }
      else
         flags &= ~PROTOTYPE;
   }

   public boolean isPendingInsert() {
      return (flags & PENDING_INSERT) != 0;
   }

   public String getObjectId() {
      StringBuilder sb = new StringBuilder();
      Object inst = getInst();
      String typeName = CTypeUtil.getClassName(DynUtil.getTypeName(dbTypeDesc.typeDecl, false));
      if (isTransient()) {
         sb.append(DynUtil.getObjectId(inst, dbTypeDesc.typeDecl, typeName + "-transient"));
      }
      else {
         sb.append(typeName);
         sb.append("__");
         for (int i = 0; i < dbTypeDesc.primaryTable.idColumns.size(); i++) {
            if (i != 0)
               sb.append("__");
            IdPropertyDescriptor idProp = dbTypeDesc.primaryTable.idColumns.get(i);
            Object idVal = idProp.getPropertyMapper().getPropertyValue(inst, false, false);
            if (idVal == null) // TODO: not sure what to do here since the object won't have an id until it's actually persisted and getObjectId has no way to change the id for the same instance (would require some changes to the sync system?)
               idVal = "null_id";
            sb.append(idVal);
         }
      }
      return sb.toString();
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      if (wrapper == null) {
         sb.append("DBObject: class: " + DynUtil.getType(this));
      }
      else {
         sb.append("DB store for: " + DynUtil.getType(wrapper));
      }
      if ((flags & TRANSIENT) == 0) {
         sb.append(": ");
         sb.append(getObjectId());
      }
      else
         sb.append(" - transient");
      return sb.toString();
   }

   void applyUpdates(ArrayList<PropUpdate> updateList) {
      synchronized (pendingOps) {
         for (PropUpdate propUpdate:updateList) {
            propUpdate.prop.getPropertyMapper().setPropertyValue(getInst(), propUpdate.value);
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
      if (!dbTypeDesc.removeInstance(this))
         DBUtil.error("Stopping instance not found in index!");
   }
}
