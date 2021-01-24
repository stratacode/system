/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.db;

import java.util.Arrays;
import java.util.Date;

public class DBSchemaType {
   private long id;
   public long getId() {
      return id;
   }
   public void setId(long nid) {
      this.id = nid;
   }

   private String typeName;
   public String getTypeName() {
      return typeName;
   }
   public void setTypeName(String typeName) {
      this.typeName = typeName;
   }


   private DBSchemaVersion currentVersion;
   public DBSchemaVersion getCurrentVersion() {
      if (currentVersion == null)
         currentVersion = new DBSchemaVersion();
      return currentVersion;
   }
   public void setCurrentVersion(DBSchemaVersion version) {
      this.currentVersion = version;
   }

   // TODO: expose list of versions here?
}
