package sc.db;

/** Used for defining data sources in the layer definition file (not at runtime) */
public class DataSourceDef {
   public String provider;
   public String jndiName, dbName, userName, password, serverName;
   public int port;
}
