package sc.db;

import java.util.ArrayList;
import java.util.List;

/** Used to represent a table which stores properties for items in DBTypeDescriptor - could be a primary table, or auxiliary table.
 * MultiTables use a sub-class. */
public class TableDescriptor {
   public DBTypeDescriptor dbTypeDesc;

   public String tableName;

   public List<IdPropertyDescriptor> idColumns;

   public List<DBPropertyDescriptor> columns;

   /** Controls whether a row is inserted when all property values stored in this table are null - true for primary tables always */
   public boolean insertWithNullValues = false;

   /** When false, there's one row per exposed value - it's not a multiTable */
   public boolean multiRow = false;

   public boolean primary = false;

   /** Set to true for descriptors that refer to tables defined elsewhere - so no schema is generated */
   public boolean reference = false;

   public TableDescriptor(String tableName) {
      this.tableName = tableName;
      this.columns = new ArrayList<DBPropertyDescriptor>();
   }

   public TableDescriptor(String tableName, List<IdPropertyDescriptor> idColumns, List<DBPropertyDescriptor> columns) {
      this.tableName = tableName;
      this.idColumns = idColumns;
      this.columns = columns;
   }

   void init(DBTypeDescriptor dbTypeDesc) {
      this.dbTypeDesc = dbTypeDesc;
      if (this == dbTypeDesc.primaryTable)
         insertWithNullValues = true;
      for (DBPropertyDescriptor col:columns) {
         col.init(dbTypeDesc, this);
      }
      if (idColumns != null) {
         for (IdPropertyDescriptor idcol:idColumns)
            idcol.init(dbTypeDesc, this);
      }
   }

   public void addColumnProperty(DBPropertyDescriptor prop) {
      prop.init(dbTypeDesc, this);
      this.columns.add(prop);
   }

   public void addIdColumnProperty(IdPropertyDescriptor prop) {
      if (this.idColumns == null)
         this.idColumns = new ArrayList<IdPropertyDescriptor>();
      prop.init(dbTypeDesc, this);
      this.idColumns.add(prop);
   }

   public DBPropertyDescriptor getPropertyDescriptor(String propName) {
      if (columns != null) {
         for (DBPropertyDescriptor col:columns)
            if (col.propertyName.equals(propName))
               return col;
      }
      if (idColumns != null) {
         for (DBPropertyDescriptor col:idColumns)
            if (col.propertyName.equals(propName))
               return col;
      }
      return null;
   }

   public String getJavaName() {
      return DBUtil.getJavaName(tableName);
   }

   // TODO: support a per-table data source name. We'd break up separate tables into different fetch queries, inserts
   public String getDataSourceName() {
      return dbTypeDesc.dataSourceName;
   }

   public List<IdPropertyDescriptor> getIdColumns() {
      if (idColumns == null)
         return dbTypeDesc.primaryTable.idColumns;
      return idColumns;
   }

   public List<IdPropertyDescriptor> createKeyIdColumns() {
      ArrayList<IdPropertyDescriptor> res = new ArrayList<IdPropertyDescriptor>();
      for (IdPropertyDescriptor idCol:idColumns) {
          IdPropertyDescriptor keyCol = idCol.createKeyIdColumn();
          res.add(keyCol);
      }
      return res;
   }

   public String toString() {
      return "table " + tableName;
   }

   public void initIdColumns() {
      if (idColumns == null)
         idColumns = dbTypeDesc.primaryTable.createKeyIdColumns();
   }

   public boolean isReadOnly() {
      for (DBPropertyDescriptor col:columns)
         if (!col.readOnly)
            return false;
      if (columns.size() == 0)
         System.err.println("*** no columns for table in isReadOnly?");
      return true;
   }
}