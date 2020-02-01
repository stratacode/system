package sc.db;

import sc.dyn.DynUtil;
import sc.type.IBeanMapper;

public class DBPropertyDescriptor {
   public String propertyName;
   public String columnName;
   public String columnType;
   public String tableName;
   public boolean allowNull;
   public boolean onDemand;
   public String dataSourceName;
   public String fetchGroup;

   public DBTypeDescriptor dbTypeDesc;
   public TableDescriptor tableDesc;

   private IBeanMapper propertyMapper;

   public DBPropertyDescriptor(String propertyName, String columnName, String columnType, String tableName, boolean allowNull, boolean onDemand, String dataSourceName, String fetchGroup) {
      this.propertyName = propertyName;
      this.columnName = columnName;
      this.columnType = columnType;
      this.tableName = tableName;
      this.allowNull = allowNull;
      this.onDemand = onDemand;
      this.dataSourceName = dataSourceName;
      this.fetchGroup = fetchGroup;
   }

   void init(DBTypeDescriptor typeDesc, TableDescriptor tableDesc) {
      this.dbTypeDesc = typeDesc;
      this.tableDesc = tableDesc;
   }

   public IBeanMapper getPropertyMapper() {
      if (propertyMapper == null)
         propertyMapper = DynUtil.getPropertyMapping(dbTypeDesc.typeDecl, propertyName);
      return propertyMapper;
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      if (tableName != null) {
        sb.append(tableName);
        sb.append(".");
      }
      sb.append(columnName);
      sb.append(" ");
      sb.append(columnType);
      return sb.toString();
   }

   public boolean isId() {
      return false;
   }
}
