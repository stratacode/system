package sc.lang.sql;

import sc.layer.LayeredSystem;

import java.util.HashMap;
import java.util.Map;

public class SchemaManager {
   LayeredSystem system;

   public SchemaManager(LayeredSystem sys) {
      this.system = sys;
   }

   public Map<String,SQLFileModel> schemasByType = new HashMap<String, SQLFileModel>();

   
}
