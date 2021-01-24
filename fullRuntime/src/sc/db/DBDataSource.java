/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.db;

import sc.type.PTypeUtil;

import javax.naming.InitialContext;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/** Used for defining data sources in the layer definition file, as well as for data source config at runtime if necessary.  */
public class DBDataSource {
   public String provider;
   public String jndiName, dbName, userName, password, serverName;
   public int port;
   public boolean readOnly, dbDisabled;
   /**
    * A layer will by default use the first data source it defines as it's defaultDataSource. Types that don't specify
    * an explicit dataSource name use the default for the layer they are defined in.
    * Set this to false for data sources that have a specific use and are only used by types that refer to them explicitly.
    */
   public boolean makeDefaultDataSource = true;
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
         if (toRunWhenReady != null) {
            for (Runnable r:toRunWhenReady)
               r.run();
         }
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

   List<Runnable> toRunWhenReady = null;

   public void runWhenReady(Runnable r) {
      if (toRunWhenReady == null)
         toRunWhenReady = new ArrayList<Runnable>();
      toRunWhenReady.add(r);
   }

   public String toDataSourceString(String defaultProvider) {
      StringBuilder sb = new StringBuilder();
      sb.append("dataSource:");
      sb.append(serverName);
      sb.append(":");
      sb.append(port);
      sb.append(":");
      sb.append(jndiName);
      sb.append(":");
      sb.append(dbName);
      if (readOnly)
         sb.append(" (read only)");
      if (dbDisabled)
         sb.append(" (disabled)");
      if (provider != null) {
         sb.append(" provider:");
         sb.append(provider);
      }
      else if (defaultProvider != null) {
         sb.append(" default provider:");
         sb.append(defaultProvider);
      }
      return sb.toString();
   }

   public String toString() {
      return toDataSourceString(null);
   }
}
