/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.db;

import java.util.Date;

public class DBSchemaVersion {
   private long id;
   public long getId() {
      return id;
   }
   public void setId(long id) {
      this.id = id;
   }

   private String schemaSQL;
   public String getSchemaSQL() {
      return schemaSQL;
   }
   public void setSchemaSQL(String schemaSQL) {
      this.schemaSQL = schemaSQL;
   }

   private String alterSQL;
   public String getAlterSQL() {
      return alterSQL;
   }
   public void setAlterSQL(String alterSQL) {
      this.alterSQL = alterSQL;
   }

   private Date dateApplied;
   public Date getDateApplied() {
      return dateApplied;
   }
   public void setDateApplied(Date dateApplied) {
      this.dateApplied = dateApplied;
   }

   private String versionInfo;
   public String getVersionInfo() {
      return versionInfo;
   }
   public void setVersionInfo(String versionInfo) {
      this.versionInfo = versionInfo;
   }

   private String buildLayerName;
   public String getBuildLayerName() {
      return buildLayerName;
   }
   public void setBuildLayerName(String buildLayerName) {
      this.buildLayerName = buildLayerName;
   }

}
