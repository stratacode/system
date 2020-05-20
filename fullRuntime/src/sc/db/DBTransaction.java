package sc.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.TreeMap;

/**
 * Represents a per-thread representation of the current transaction, including cached DB connections for each data source,
 * and a list of pending updates made by this transaction.
 *
 * This class is not thread-safe itself.
 */
public class DBTransaction {
   static ThreadLocal<DBTransaction> currentTransaction = new ThreadLocal<DBTransaction>();

   // This is the list+index for the list of pending update operations for DBObject's in this transaction.
   // One reason to queue them up is so that property sets can be accumulated for a single update (i.e. doing
   // the commit at the end of a sync operation).
   ArrayList<TxOperation> operationList;
   IdentityHashMap<Object,TxOperation> operationIndex;

   public long startTime = System.currentTimeMillis();
   public boolean completed = false;

   /** Set to indicate that the transaction is being committed - a signal to setX methods to allow the field to be updated */
   public boolean commitInProgress = false;

   public boolean applyingDBChanges = false;

   public boolean autoCommit = true;

   public String lastThreadName;

   public DBTransaction() {
   }

   public static DBTransaction getCurrent() {
      return currentTransaction.get();
   }

   public static DBTransaction getOrCreate() {
      DBTransaction res = currentTransaction.get();
      if (res == null) {
         res = new DBTransaction();
         res.makeCurrent();
         return res;
      }
      return res;
   }
   void makeCurrent() {
      currentTransaction.set(this);
      lastThreadName = Thread.currentThread().getName();
   }

   public void addOp(TxOperation op) {
      if (operationList == null) {
         operationList = new ArrayList<TxOperation>();
         operationIndex = new IdentityHashMap<Object,TxOperation>();
      }
      operationList.add(op);
      TxOperation oldOp = operationIndex.put(op.dbObject, op);
      // TODO: if we are updating something that hasn't been inserted maybe we discard the update?
      // If we are deleting something that has been updated, just replacing it with the delete seems ok for most cases
      if (oldOp != null) {
         System.err.println("*** Unhandled case of replacing operation: " + op + " with " + oldOp);
      }
   }

   public void removeOp(TxOperation op) {
      if (operationList != null && operationList.remove(op))
         operationIndex.remove(op.dbObject);
      else
         throw new IllegalArgumentException("tx:removeOp: operation not found");
   }

   /**
    * Applies any queued operations. By default setting properties are queued until dbUpdate is called.
    * Inserts and deletes are applied immediate (and flush the queue of pending changes) but it's possible
    * to enable queuing of inserts and deletes as well.
    */
   public void flush() {
      while (operationList != null) {
         ArrayList<TxOperation> toApply = operationList;

         operationList = null;
         operationIndex = null;

         for (TxOperation op:toApply) {
            if (op.removeOp())
               op.apply();
            else
               DBUtil.error("Attempt to flush operation not found in pendingOps for DBObject");
         }
      }
   }

   /** Cancels any pending operations that have been queued and rolls back any uncommitted connections */
   public void rollback() {
      while (operationList != null) {
         ArrayList<TxOperation> toCancel = operationList;

         operationList = null;
         operationIndex = null;

         for (TxOperation op:toCancel) {
            op.cancel();
         }
      }
      if (connections != null) {
         TreeMap<String,Connection> toRollback = connections;
         if (!autoCommit) {
            for (Connection conn:toRollback.values()) {
               try {
                  conn.rollback();
               }
               catch (SQLException exc) {
                  System.err.println("Rollback transaction failed: " + exc);
               }
            }
         }
      }
      close();
   }

   /** Flush pending operations and commit any transactions */
   public void commit() {
      flush();
      if (connections != null) {
         TreeMap<String,Connection> toCommit = connections;
         if (!autoCommit) {
            for (Connection conn:toCommit.values()) {
               try {
                  conn.commit();
               }
               catch (SQLException exc) {
                  throw new IllegalArgumentException("Commit transaction failed: " + exc);
               }
            }
         }
      }
      close();
   }

   public void close() {
      if (connections != null) {
         TreeMap<String,Connection> toClose = connections;
         connections = null;
         for (Connection conn:toClose.values()) {
            DBUtil.close(conn);
         }
      }
      currentTransaction.remove();
   }

   TreeMap<String,Connection> connections = null;

   public Connection getConnection(String dataSource) {
      Connection conn;
      if (connections == null) {
         connections = new TreeMap<String,Connection>();
      }
      else {
         conn = connections.get(dataSource);
         if (conn != null)
            return conn;
      }
      conn = DBUtil.createConnection(dataSource, autoCommit);
      connections.put(dataSource, conn);
      return conn;
   }

   public String toString() {
      return "tx:" + lastThreadName + (connections == null ? " (new)" : " pending dataSources:" + connections.keySet()) + (operationList == null ? "" : " - " + operationList.size() + " queued op");
   }
}
