package sc.db;

import java.util.HashMap;

/**
 * Manages access to the runtime, active data sources - i.e the connections to the databases.
 * The DBDataSource object holds the connection configuration
 * information and typically a references to a javax.sql.DataSource
 */
public class DataSourceManager {
   public static HashMap<String,DBDataSource> dataSources = new HashMap<String,DBDataSource>();

   public static boolean dbDisabled = false;

   public static DBDataSource getDBDataSource(String dsName) {
      return dataSources.get(dsName);
   }

   public static void addDBDataSource(String jndiName, DBDataSource ds) {
      if (dbDisabled && ds != null) {
         ds.dbDisabled = true;
         ds.readOnly = true;
         DBUtil.verbose("Disabling dataSource: " + jndiName + " due to configured dbDisabled flag (-ndb) option");
      }
      dataSources.put(jndiName, ds);
   }

   public static HashMap<String, ISchemaUpdater> schemaUpdaters = new HashMap<String, ISchemaUpdater>();
   public static ISchemaUpdater getSchemaUpdater(String providerName) {
      return schemaUpdaters.get(providerName);
   }

   public static void addSchemaUpdater(String providerName, ISchemaUpdater schemaUpdater) {
      schemaUpdaters.put(providerName, schemaUpdater);
   }

}
