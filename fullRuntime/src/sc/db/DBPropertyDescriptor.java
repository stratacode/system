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
   public String refTypeName;
   public boolean multiRow;

   public DBTypeDescriptor dbTypeDesc;
   public TableDescriptor tableDesc;

   public DBTypeDescriptor refDBTypeDesc;

   private IBeanMapper propertyMapper;

   public DBPropertyDescriptor(String propertyName, String columnName, String columnType, String tableName,
                               boolean allowNull, boolean onDemand, String dataSourceName, String fetchGroup,
                               String refTypeName, boolean multiRow) {
      this.propertyName = propertyName;
      this.columnName = columnName;
      this.columnType = columnType;
      this.tableName = tableName;
      this.allowNull = allowNull;
      this.onDemand = onDemand;
      this.dataSourceName = dataSourceName;
      this.fetchGroup = fetchGroup;
      this.refTypeName = refTypeName;
      this.multiRow = multiRow;
   }

   void init(DBTypeDescriptor typeDesc, TableDescriptor tableDesc) {
      this.dbTypeDesc = typeDesc;
      this.tableDesc = tableDesc;
   }

   void resolve() {
      if (this.refTypeName != null) {
         Object refType = DynUtil.findType(this.refTypeName);
         if (refType == null)
            System.out.println("*** Ref type: " + refTypeName + " not found for property: " + propertyName);
         else {
            this.refDBTypeDesc = DBTypeDescriptor.getByType(refType);
            if (this.refDBTypeDesc == null)
               System.out.println("*** Ref type: " + refTypeName + ": no DBTypeDescriptor for property: " + propertyName);
         }
      }
   }

   public IBeanMapper getPropertyMapper() {
      if (dbTypeDesc == null)
         System.out.println("***");
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

   public String getTableName() {
      if (tableName == null) {
         if (multiRow)
            tableName = dbTypeDesc.primaryTable.tableName + "_" + columnName;
         else
            return dbTypeDesc.primaryTable.tableName;
      }
      return tableName;
   }

   public TableDescriptor getTable() {
      if (tableDesc != null)
         return tableDesc;
      if (multiRow) {
         if (tableName == null)
         tableDesc = dbTypeDesc.getMultiTableByName(getTableName(), this);
         return tableDesc;
      }

      if (tableName != null) {
         tableDesc = dbTypeDesc.getTableByName(tableName);
         if (tableDesc != null)
            return tableDesc;
      }
      tableDesc = dbTypeDesc.primaryTable;
      return tableDesc;
   }

   public boolean isId() {
      return false;
   }

   public int getNumColumns() {
      return 1;
   }

   public String getColumnName(int colIx) {
      return columnName;
   }

   public String getColumnType(int colIx) {
      return columnType;
   }
}
