package sc.db;

import javax.naming.InitialContext;
import javax.sql.DataSource;

/** Used for defining data sources in the layer definition file, as well as for data source config at runtime if necessary.  */
public class DBDataSource {
   public String provider;
   public String jndiName, dbName, userName, password, serverName;
   public int port;
   public boolean readOnly, dbDisabled;
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
}
