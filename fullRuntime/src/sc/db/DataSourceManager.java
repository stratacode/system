/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

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
   public static boolean defaultSchemaReady = true;

   public static DBDataSource getDBDataSource(String dsName) {
      DBDataSource res = dataSources.get(dsName);
      if (res == null) {
         res = new DBDataSource();
         res.jndiName = dsName;
         javax.sql.DataSource ds = res.getDataSource();
         if (ds != null) {
            dataSources.put(dsName, res);
         }
         else
            res = null;
      }
      return res;
   }

   public static void addDBDataSource(String jndiName, DBDataSource ds) {
      if (ds != null) {
         if (dbDisabled) {
            ds.dbDisabled = true;
            ds.readOnly = true;
            DBUtil.verbose("Disabling dataSource: " + jndiName + " due to configured dbDisabled flag (-ndb) option");
         }
         if (!defaultSchemaReady) {
            DBUtil.verbose("DataSource: " + jndiName + " - setSchemaReady=false due to defaultSchemaReady=false");
            ds.setSchemaReady(false);
         }
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
