package sc.db;

import java.util.List;

/**
 * The implementation provides direct ability to update a database schema, store
 * metadata including the current DDL etc. This interface will be implemented
 * by a class using jdbc metadata to reflect on existing table/column names, and
 * also store metadata as the schema changers.
 */
public interface ISchemaUpdater {
   List<DBSchemaType> getDBSchemas(String dataSourceName);
   DBMetadata getDBMetadata(String dataSourceName);
   void applyAlterCommands(String dataSourceName, List<String> alterCommands);
   void updateDBSchemaForType(String dataSourceName, DBSchemaType info);
   void removeDBSchemaForType(String dataSourceName, String typeName);
   /* Set to false for an application when it's required schema does not match the registered DB schema and we launch the wizard */
   void setSchemaReady(String dataSourceName, boolean flag);
}
