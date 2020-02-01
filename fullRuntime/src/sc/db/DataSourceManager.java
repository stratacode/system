package sc.db;

import javax.sql.DataSource;
import javax.naming.InitialContext;

import java.util.HashMap;

public class DataSourceManager {
   public static HashMap<String,DataSource> dataSources = new HashMap<String,DataSource>();

   public static DataSource getDataSource(String dsName) {
      DataSource ds = dataSources.get(dsName);
      if (ds == null) {
         try {
            InitialContext ctx = new InitialContext();
            ds = (DataSource) ctx.lookup("java:comp/env/" + dsName);
         }
         catch (javax.naming.NamingException exc) {}
      }
      return ds;
   }

   public static void addDataSource(String jndiName, DataSource ds) { 
      dataSources.put(jndiName, ds);
   }
}
