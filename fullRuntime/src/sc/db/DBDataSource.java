package sc.db;

import sc.type.PTypeUtil;

import javax.naming.InitialContext;
import javax.sql.DataSource;

/** Used for defining data sources in the layer definition file, as well as for data source config at runtime if necessary.  */
public class DBDataSource {
   public String provider;
   public String jndiName, dbName, userName, password, serverName;
   public int port;
   public boolean readOnly, dbDisabled;
   private boolean schemaReady = true;
   public javax.sql.DataSource dataSource;

   public javax.sql.DataSource getDataSource() {
      if (dataSource != null)
         return dataSource;

      try {
         InitialContext ctx = new InitialContext();
         dataSource = (DataSource) ctx.lookup("java:comp/env/" + jndiName);
      }
      catch (javax.naming.NamingException exc) {}
      return dataSource;
   }

   public boolean getSchemaReady() {
      return schemaReady;
   }
   public synchronized void setSchemaReady(boolean val) {
      if (schemaReady == val)
         return;
      schemaReady = val;
      if (val) {
         notifyAll();
      }
   }

   public void waitForReady() {
      while (!schemaReady) {
         synchronized (this) {
            try {
               wait();
            }
            catch (InterruptedException exc) {
               System.err.println("*** Schema - waitForReady interrupted: " + exc + " on thread: " + PTypeUtil.getThreadName());
            }
         }
      }
   }
}
