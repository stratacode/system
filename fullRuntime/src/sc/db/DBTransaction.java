package sc.db;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.TreeMap;

/**
 * Represents a per-thread representation of the current transaction, including cached DB connection, list of pending updates
 * made by this transaction.
 *
 * This class is not thread-safe itself.
 *
 * TODO:  Not sure if there's a use case to use one from more than one thread but perhaps we
 * should at least detect and print an error if the current thread does not match?
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
      if (operationList.remove(op))
         operationIndex.remove(op.dbObject);
      else
         throw new IllegalArgumentException("tx:removeOp: operation not found");
   }

   public void flush() {
      while (operationList != null) {
         ArrayList<TxOperation> toApply = operationList;

         operationList = null;
         operationIndex = null;

         for (TxOperation op:toApply) {
            op.apply();
         }
      }
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
      conn = DBUtil.createConnection(dataSource);
      connections.put(dataSource, conn);
      return conn;
   }

   public String toString() {
      return "tx:" + lastThreadName + (connections == null ? " (new)" : " pending dataSources:" + connections.keySet()) + (operationList == null ? "" : " - " + operationList.size() + " queued op");
   }
}
