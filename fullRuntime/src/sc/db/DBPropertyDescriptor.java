package sc.db;

import sc.dyn.DynUtil;
import sc.type.IBeanMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents the metadata used for storing a property of a DBObject. It lives in a TableDescriptor and as part of a DBTypeDescriptor that corresponds to
 * the mapping for a single class or dynamic type in the system.
 */
public class DBPropertyDescriptor {
   public String propertyName;
   public String columnName;
   public String columnType;
   /** Optional table name - if not set, uses either the primary table, or another table for multi-row properties */
   public String tableName;
   // If required, the column gets a NOT NULL constraint
   public boolean required;
   // If unique gets a 'UNIQUE' constraint
   public boolean unique;
   /** For relationships, should the referenced value be fetched in-line, or should we wait till the properties of the referenced object are access to fetch them */
   public boolean onDemand;

   /** When the property is stored in a separate data source, specifies that data source name */
   public String dataSourceName;

   /**
    * Override the default fetching behavior for the property and instead fetch it with a group created with this name.
    * All properties using the same fetchGroup are populated at the same time
    * By default, a property's fetchGroup is the Java style-name for the table the property is defined in
    */
   public String fetchGroup;

   /** For both single and multi-valued properties that refer to other DBObject's persisted, specifies the type name for this reference */
   public String refTypeName;

   /** True if this property is a multi-valued property and is stored with a separate row per value (as opposed to a serialized form like JSON stored in a column) */
   public boolean multiRow;

   /**
    * For bi-directional relationships, stores the name of the reverse property. This is used to determine the nature of the relationship - one-to-many, one-to-one or many-to-many
    * and for the default table layout for storing the items.
    */
   public String reverseProperty;

   /** True for properties that are read from the database, but not updated. This is set in particular for reverse properties in a relationship - only one side needs to update based on the change. */
   public boolean readOnly;

   /** The type this property descriptor is part of */
   public DBTypeDescriptor dbTypeDesc;

   /** Reference to the table for this property */
   public TableDescriptor tableDesc;

   /** When this property references one or more other properties, this is the type descriptor for that reference */
   public DBTypeDescriptor refDBTypeDesc;

   private IBeanMapper propertyMapper;

   /**
    * If this property participates in a bi-directional relationship, points to the back-pointing property - i.e.
    * refDBTypeDesc == reversePropDesc.dbTypeDesc && reversePropDesc.reversePropDesc.refDBTypeDesc == dbTypeDesc
    */
   public DBPropertyDescriptor reversePropDesc;

   private boolean started = false;

   public DBPropertyDescriptor(String propertyName, String columnName, String columnType, String tableName,
                               boolean required, boolean unique, boolean onDemand, String dataSourceName, String fetchGroup,
                               String refTypeName, boolean multiRow, String reverseProperty) {
      this.propertyName = propertyName;
      this.columnName = columnName;
      this.columnType = columnType;
      this.tableName = tableName;
      this.required = required;
      this.unique = unique;
      this.onDemand = onDemand;
      this.dataSourceName = dataSourceName;
      this.fetchGroup = fetchGroup;
      this.refTypeName = refTypeName;
      this.multiRow = multiRow;
      this.reverseProperty = reverseProperty;
   }

   void init(DBTypeDescriptor typeDesc, TableDescriptor tableDesc) {
      this.dbTypeDesc = typeDesc;
      this.tableDesc = tableDesc;
      started = false;
   }

   void resolve() {
      if (this.refTypeName != null && refDBTypeDesc == null) {
         Object refType = DynUtil.findType(this.refTypeName);
         if (refType == null)
            System.out.println("*** Ref type: " + refTypeName + " not found for property: " + propertyName);
         else {
            this.refDBTypeDesc = DBTypeDescriptor.getByType(refType, false);
            if (this.refDBTypeDesc == null)
               System.out.println("*** Ref type: " + refTypeName + ": no DBTypeDescriptor for property: " + propertyName);
            else
               this.refDBTypeDesc.init();
         }
      }
      if (this.reverseProperty != null) {
         if (this.refTypeName == null)
            System.err.println("*** DBSettings.reverseProperty must be specified on a property that's a reference to another DBObject");
         if (this.refDBTypeDesc != null) {
            reversePropDesc = refDBTypeDesc.getPropertyDescriptor(reverseProperty);
            if (reversePropDesc == null)
               System.err.println("*** reverseProperty: " + reverseProperty + " not found in type: " + refDBTypeDesc + " referenced by: " + dbTypeDesc + "." + propertyName);
            else {
               // Make sure that only one side has 'reverseProperty' set - we'll use that to determine the table owning side. It should always be the 'one' side in a
               // one-to-many or many-to-many relationship
               if (reversePropDesc == this)
                  System.err.println("*** DBPropertyDescriptor: " + this + " has reverseProperty pointing to itself");
               else if (reversePropDesc.reversePropDesc != null || reversePropDesc.reverseProperty != null) {
                  System.err.println("*** reverseProperty conflict on  between: " + reverseProperty + " defined on: " + dbTypeDesc + " and " + reversePropDesc + " defined: " + reversePropDesc.reverseProperty);
               }
               else {
                  if (multiRow && !reversePropDesc.multiRow)
                     System.err.println("*** reverseProperty set on: " + this + " should be set the single-valued side of the relationship: " + reversePropDesc);
                  reversePropDesc.reversePropDesc = this;
                  // TODO: we could do read-only 1-1 relationships as well.
                  // For one-to-many relationships, usually the many property is read-only - i.e. used for querying only, not updated
                  //if (!multiRow && reversePropDesc.multiRow)
                  reversePropDesc.readOnly = true;
               }
            }
         }
      }
   }

   public void start() {
      if (started)
         return;

      started = true;

      if (reversePropDesc != null) {
         dbTypeDesc.addReverseProperty(this);

         // The read-only property case occurs when the reverse property uses this property's table
         if (reversePropDesc.readOnly) {
            reversePropDesc.resetTable(tableDesc);
         }
      }
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

   public String getTableName() {
      if (tableName == null) {
         if (multiRow) {
            if (reversePropDesc != null) {
               if (!reversePropDesc.multiRow || reversePropDesc.reverseProperty != null)
                  return reversePropDesc.getTableName();
            }
            tableName = dbTypeDesc.primaryTable.tableName + "_" + columnName;
         }
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

   /**
    * This is called when we determine that this property's value is derived from the table provided. If this table is
    * already an aux table, or one-to-many relationship, this property's table takes the name of the other table.
    * If it is a primary table for this type, need to create a new aux/multi table
    * If this is a many-to-many relationship, both tables get reconfigured to use the property names as the id columns
    * although each table uses the opposite column for the id
    */
   public void resetTable(TableDescriptor mainPropTable) {
      if (tableDesc != null) {
         if (tableDesc.primary) {
            TableDescriptor primaryTable = tableDesc;
            if (!primaryTable.columns.remove(this))
               System.err.println("*** Unable to find column to remove for resetTable");
            tableDesc = new TableDescriptor(mainPropTable.tableName);
            tableDesc.reference = true;
            tableDesc.dbTypeDesc = dbTypeDesc;
            if (!columnType.equals(reversePropDesc.columnType))
               System.err.println("*** Relationships and reverse don't have matching columnTypes");

            // The id property in this side's table is the value column in the reverse table
            IdPropertyDescriptor thisIdProp = new IdPropertyDescriptor(primaryTable.idColumns.get(0).propertyName, reversePropDesc.columnName, columnType, false);
            // The value column for this property is the reverse table's id column - TODO: deal with multi column primary keys by overriding this in MultiColPropertyDescriptor
            columnName = mainPropTable.idColumns.get(0).columnName;
            tableDesc.idColumns = Collections.singletonList(thisIdProp);
            tableDesc.columns = new ArrayList<DBPropertyDescriptor>();
            tableDesc.columns.add(this);
            if (multiRow)
               dbTypeDesc.addMultiTable(tableDesc);
            else
               dbTypeDesc.addAuxTable(tableDesc);
         }
         else {
            tableDesc.tableName = mainPropTable.tableName;
            tableDesc.reference = true;
            if (tableDesc.multiRow) {
               if (mainPropTable.multiRow) {
                  // We are going to turn mainPropTable into a many-to-many table using mainProp's join name
                  tableDesc.idColumns.get(0).columnName = mainPropTable.columns.get(0).columnName;
                  mainPropTable.idColumns.get(0).columnName = tableDesc.columns.get(0).columnName;
               }
               // Create the table descriptor for the many side of a one-to-many case
               else {
                  tableDesc.reverseProperty = this;
                  tableDesc.idColumns.get(0).columnName = reversePropDesc.columnName;
                  // First fetch the reverse props id as part of the reference so we can create the instance
                  ArrayList<DBPropertyDescriptor> fetchCols = new ArrayList<DBPropertyDescriptor>(mainPropTable.idColumns);
                  // Then fetch all of the properties in the default table for the reverse side of the reference
                  for (int i = 0; i < mainPropTable.columns.size(); i++) {
                     DBPropertyDescriptor mainProp = mainPropTable.columns.get(i);
                     if (mainProp == reversePropDesc)
                        continue;
                     fetchCols.add(mainProp);
                  }
                  tableDesc.columns = fetchCols;
               }
            }
         }
      }
   }

   public Object getValueFromResultSet(ResultSet rs, int rix) throws SQLException {
      Object val;
      int numCols = getNumColumns();
      if (numCols == 1)  {
         val = DBUtil.getResultSetByIndex(rs, rix++, this);
         if (refDBTypeDesc != null && val != null)
            val = refDBTypeDesc.lookupInstById(val, true, false);
      }
      else {
         if (refDBTypeDesc != null) {
            List<IdPropertyDescriptor> refIdCols = refDBTypeDesc.primaryTable.getIdColumns();
            if (numCols != refIdCols.size())
               throw new UnsupportedOperationException();
            MultiColIdentity idVals = new MultiColIdentity(numCols);
            boolean nullId = true;
            for (int i = 0; i < numCols; i++) {
               IdPropertyDescriptor refIdCol = refIdCols.get(i);
               Object idVal = DBUtil.getResultSetByIndex(rs, rix++, refIdCol);
               if (idVal != null)
                  nullId = false;
               idVals.setVal(idVal, i);
            }
            if (!nullId)
               val = refDBTypeDesc.lookupInstById(idVals, true, false);
            else
               val = null;
         }
         else {// TODO: is this a useful case? need some way here to create whatever value we have from the list of result set values
            System.err.println("*** Unsupported case - multiCol property that's not a reference");
            val = null;
         }
      }
      return val;
   }

   public String getDataSourceForProp() {
      if (dataSourceName != null)
         return dataSourceName;
      return dbTypeDesc.dataSourceName;
   }

   public DBTypeDescriptor getColTypeDesc() {
      return refDBTypeDesc;
   }
}
