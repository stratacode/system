package sc.db;

import java.util.ArrayList;
import java.util.List;

/**
 * Used to represent a table storing properties for items in a DBTypeDescriptor.
 * This one class is used for primary, auxiliary, and multi valued tables. This same class is used to define tables
 * during code-processing, as well as to represent the table info during runtime.
 */
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

   public boolean hasDynColumns = false;

   /**
    * Set for the 'many' side of a 1-many case where the id column here is used for the 'value' of the reverse property, so
    * there is no reverse property descriptor in the columns list. We still need to look up this property descriptor from
    * the reverse side.
     */
   public DBPropertyDescriptor reverseProperty;

   public TableDescriptor(String tableName) {
      this.tableName = tableName;
      this.columns = new ArrayList<DBPropertyDescriptor>();
   }

   public TableDescriptor(String tableName, List<IdPropertyDescriptor> idColumns, List<DBPropertyDescriptor> columns,
                          DBPropertyDescriptor reverseProp, boolean hasDynColumns) {
      this.tableName = tableName;
      this.idColumns = idColumns;
      this.columns = columns;
      this.reverseProperty = reverseProp;
      this.hasDynColumns = hasDynColumns;
      if (idColumns != null) {
         for (IdPropertyDescriptor idCol:idColumns)
            idCol.tableName = tableName;
      }
      if (columns != null) {
         for (DBPropertyDescriptor col:columns) {
            col.tableName = tableName;
            if (col.dynColumn)
               this.hasDynColumns = true;
         }
      }
      if (reverseProp != null)
         reverseProp.tableName = tableName;
   }

   void init(DBTypeDescriptor dbTypeDesc) {
      this.dbTypeDesc = dbTypeDesc;
      if (this == dbTypeDesc.primaryTable)
         insertWithNullValues = true;
      if (reverseProperty != null)
         reverseProperty.init(dbTypeDesc, this);
      else {
         for (DBPropertyDescriptor col:columns) {
            col.init(dbTypeDesc, this);
            // TODO: this gets put into the runtime version as a normal column so identifying here by name
            if (col.columnName.equals(DBTypeDescriptor.DBTypeIdColumnName)) {
               col.typeIdProperty = true;
               dbTypeDesc.typeIdProperty = col;
            }
            if (col.dynColumn) {
               if (multiRow)
                  System.err.println("*** multiRow table has dynColumn property - not yet supported");
               else
                  hasDynColumns = true;
            }
         }
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
      if (reverseProperty != null && reverseProperty.propertyName.equals(propName))
         return reverseProperty;
      return null;
   }

   public DBPropertyDescriptor getPropertyForColumn(String colName) {
      if (columns != null) {
         for (DBPropertyDescriptor col:columns)
            if (col.columnName.equals(colName))
               return col;
      }
      if (idColumns != null) {
         for (DBPropertyDescriptor col:idColumns)
            if (col.columnName.equals(colName))
               return col;
      }
      if (reverseProperty != null && reverseProperty.columnName.equals(colName))
         return reverseProperty;
      return null;
   }

   public String getJavaName() {
      return DBUtil.getJavaName(tableName);
   }

   // TODO: support a per-table data source name. We'd break up separate tables into different select queries, inserts
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
      return "table " + tableName + (multiRow ? " (multi)" : "");
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

   public boolean hasColumn(DBPropertyDescriptor prop) {
      for (IdPropertyDescriptor idProp:idColumns)
         if (idProp.columnName.equalsIgnoreCase(prop.columnName))
            return true;
      for (DBPropertyDescriptor colProp:columns)
         if (colProp.columnName.equalsIgnoreCase(prop.columnName))
            return true;
      return false;
   }

   public DBPropertyDescriptor getTypeIdProperty() {
      if (primary)
         return dbTypeDesc.getTypeIdProperty();
      return null;
   }

   public void addTypeIdProperty(DBPropertyDescriptor typeIdProperty) {
      columns.add(0, typeIdProperty);
   }
}
