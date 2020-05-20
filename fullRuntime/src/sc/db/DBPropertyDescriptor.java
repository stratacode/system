package sc.db;

import sc.dyn.DynUtil;
import sc.type.CTypeUtil;
import sc.type.IBeanMapper;
import sc.util.ResultWrapper;
import sc.util.StringUtil;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents the metadata used for storing a property of a DBObject. It lives in a TableDescriptor that is part of an enclosing
 * DBTypeDescriptor. This instance is used to define the first column in the mapping. See the subclass MultiColPropertyDescriptor
 * for when there's more than one column used to refer to a single property in the model.
 * The DBPropertyDescriptor is built from the DBPropertySettings annotation, along with defaults inherited from DBTypeSettings.
 */
public class DBPropertyDescriptor {
   public static final String RefIdPropertySuffix = "RefId";
   public String propertyName;
   public String columnName;
   public String columnType;
   /** Optional table name - if not set, uses either the primary table, or another table for multi-row properties */
   public String tableName;
   // If required, the column gets a NOT NULL constraint
   public boolean required;
   // If unique gets a 'UNIQUE' constraint
   public boolean unique;
   /** For relationships, should the referenced value be selected in-line, or should we wait till the properties of the referenced object are access to select them */
   public boolean onDemand;

   /** Set to true for properties to be stored in a single JSON column - not part of the normalized schema for the type */
   public boolean dynColumn;

   /** Set to true for a property that should be indexed in the database for faster searches */
   public boolean indexed;

   /** When the property is stored in a separate data source, specifies that data source name */
   public String dataSourceName;

   /**
    * Override the default selecting behavior for the property and instead select it with a group created with this name.
    * All properties using the same selectGroup are populated at the same time
    * By default, a property's selectGroup is the Java style-name for the table the property is defined in
    */
   public String selectGroup;

   /** For both single and multi-valued properties that refer to other DBObject's persisted, specifies the type name for this reference */
   public String refTypeName;

   /** True if this property is a multi-valued property and is stored with a separate row per value (as opposed to a serialized form like JSON stored in a column) */
   public boolean multiRow;

   /**
    * For bi-directional relationships, stores the name of the reverse property. This is used to determine the nature of the relationship - one-to-many, one-to-one or many-to-many
    * and for the default table layout for storing the items.
    */
   public String reverseProperty;

   /** If set, the value to use for the 'default' statement in the DDL */
   public String dbDefault;

   /** True for properties that are read from the database, but not updated. This is set in particular for reverse properties in a relationship - only one side needs to update based on the change. */
   public boolean readOnly;

   /** Set to true for the typeId property */
   public boolean typeIdProperty;

   /**
    * For properties defined in a subclass, specifies the type name of the class where this property is defined.
    * We can't just use dbTypeDesc to define this because we need to define the Table with this property before we
    * define the subtype itself.
    */
   public String ownerTypeName;

   /** The type this property descriptor is defined in. When a subclass shares a table with a base class, this points to the subclass the property is defined in */
   public DBTypeDescriptor dbTypeDesc;

   /** Reference to the table for this property */
   public TableDescriptor tableDesc;

   /** When this property references one or more other properties, this is the type descriptor for that reference */
   public DBTypeDescriptor refDBTypeDesc;

   public DBEnumDescriptor refEnumTypeDesc;

   private IBeanMapper propertyMapper;

   /** Especially for compile mode when property mapper is not available - the type of this property */
   public Object propertyType;

   /**
    * If this property participates in a bi-directional relationship, points to the back-pointing property - i.e.
    * refDBTypeDesc == reversePropDesc.dbTypeDesc && reversePropDesc.reversePropDesc.refDBTypeDesc == dbTypeDesc
    */
   public DBPropertyDescriptor reversePropDesc;

   private boolean started = false;

   private boolean validatorInited = false;
   private Object validateMethod;

   // TODO - restructure as (propertyName, colName, colType).withTable(tableName).withFlags(required, unique, onDemand, indexed).withDataSource(...), etc.
   public DBPropertyDescriptor(String propertyName, String columnName, String columnType, String tableName,
                               boolean required, boolean unique, boolean onDemand, boolean indexed, boolean dynColumn, String dataSourceName, String selectGroup,
                               String refTypeName, boolean multiRow, String reverseProperty, String dbDefault, String ownerTypeName) {
      this.propertyName = propertyName;
      this.columnName = columnName;
      this.columnType = columnType;
      this.tableName = tableName;
      this.required = required;
      this.unique = unique;
      this.onDemand = onDemand;
      this.indexed = indexed;
      this.dynColumn = dynColumn;
      this.dataSourceName = dataSourceName;
      this.selectGroup = selectGroup;
      this.refTypeName = refTypeName;
      this.multiRow = multiRow;
      this.reverseProperty = reverseProperty;
      this.dbDefault = dbDefault;
      this.ownerTypeName = ownerTypeName;
   }

   void init(DBTypeDescriptor typeDesc, TableDescriptor tableDesc) {
      if (ownerTypeName != null && typeDesc != null)
         this.dbTypeDesc = typeDesc.findSubType(ownerTypeName);
      else
         this.dbTypeDesc = typeDesc;
      this.tableDesc = tableDesc;
      started = false;
   }

   void resolve() {
      if (this.refTypeName != null && refDBTypeDesc == null && refEnumTypeDesc == null) {
         Object refType = DynUtil.findType(this.refTypeName);
         if (refType == null)
            System.out.println("*** Ref type: " + refTypeName + " not found for property: " + propertyName);
         else {
            this.refDBTypeDesc = DBTypeDescriptor.getByType(refType, false);
            if (this.refDBTypeDesc == null) {
               if (DynUtil.isEnumType(refType))
                  this.refEnumTypeDesc = DBEnumDescriptor.getByType(refType, false);
               else
                  System.out.println("*** Ref type: " + refTypeName + ": no DBTypeDescriptor for property: " + propertyName);
            }
            else
               this.refDBTypeDesc.resolve();
         }
      }
      if (this.reverseProperty != null) {
         if (this.refTypeName == null)
            System.err.println("*** DBSettings.reverseProperty must be specified on a property that's a reference to another DBObject");
         if (this.refDBTypeDesc != null) {
            reversePropDesc = refDBTypeDesc.getPropertyDescriptor(reverseProperty);
            if (reversePropDesc == null)
               DBUtil.error("reverseProperty: " + reverseProperty + " not found in type: " + refDBTypeDesc + " referenced by: " + dbTypeDesc + "." + propertyName);
            else {
               // Make sure that only one side has 'reverseProperty' set - we'll use that to determine the table owning side. It should always be the 'one' side in a
               // one-to-many or many-to-many relationship
               if (reversePropDesc == this)
                  System.err.println("*** DBPropertyDescriptor: " + this + " has reverseProperty pointing to itself");
               else if (reversePropDesc.reverseProperty != null) {
                  System.err.println("*** reverseProperty conflict between: " + reverseProperty + " defined on: " + dbTypeDesc + " and " + reversePropDesc + " defined: " + reversePropDesc.reverseProperty);
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

            this.refDBTypeDesc.resolve();
         }
      }
   }

   public void start() {
      if (started)
         return;

      started = true;

      if (refDBTypeDesc != null)
         refDBTypeDesc.start();

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
      if (propertyMapper == null) {
         DBUtil.error("No property: " + propertyName + " found on type: " + dbTypeDesc.getTypeName());
      }
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

   public boolean eagerJoinForTypeId(SelectTableDesc tableDesc) {
      if (refDBTypeDesc != null && refDBTypeDesc.getTypeIdProperty() != null && !onDemand && tableDesc.hasJoinTableForRef(this))
         return true;
      return false;
   }

   public int getNumResultSetColumns(SelectTableDesc tableDesc) {
      int res = getNumColumns();
      if (eagerJoinForTypeId(tableDesc))
         res++;
      return res;
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
                  // First select the reverse props id as part of the reference so we can create the instance
                  ArrayList<DBPropertyDescriptor> selectCols = new ArrayList<DBPropertyDescriptor>(mainPropTable.idColumns);
                  // Then select all of the properties in the default table for the reverse side of the reference
                  for (int i = 0; i < mainPropTable.columns.size(); i++) {
                     DBPropertyDescriptor mainProp = mainPropTable.columns.get(i);
                     if (mainProp == reversePropDesc)
                        continue;
                     selectCols.add(mainProp);
                  }
                  tableDesc.columns = selectCols;
               }
            }
         }
      }
   }

   public Object getValueFromResultSet(ResultSet rs, int rix, SelectTableDesc selectTable, IDBObject inst, ResultWrapper logRefIdResult) throws SQLException {
      Object val;
      int numCols = getNumColumns();
      DBTypeDescriptor refColTypeDesc = getRefColTypeDesc();
      if (numCols == 1)  {
         val = DBUtil.getResultSetByIndex(rs, rix, this);
         int typeId = -1;
         if (refColTypeDesc != null) {
            if (getNeedsRefId() && inst != null && val != null) {
               if (!ownedByOtherType(inst.getDBObject().dbTypeDesc)) {
                  if (logRefIdResult != null)
                     logRefIdResult.result = DBUtil.formatValue(val, DBColumnType.LongId, refDBTypeDesc);
                  setRefIdProperty(inst, val);
               }
            }
            if (eagerJoinForTypeId(selectTable)) {
               DBPropertyDescriptor typeIdProperty = refColTypeDesc.getTypeIdProperty();
               Object typeIdRes = DBUtil.getResultSetByIndex(rs, rix+1, typeIdProperty);
               if (typeIdRes != null)
                  typeId = (int) typeIdRes;
            }
            if (val != null) {
               val = refColTypeDesc.lookupInstById(val, typeId, true, false);
            }
         }
      }
      else {
         if (refColTypeDesc != null) {
            List<IdPropertyDescriptor> refIdCols = refColTypeDesc.primaryTable.getIdColumns();
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

            if (getNeedsRefId()) {
               if (logRefIdResult != null)
                  logRefIdResult.result = idVals;
               setRefIdProperty(inst, idVals);
            }

            int typeId = -1;
            if (eagerJoinForTypeId(selectTable)) {
               DBPropertyDescriptor typeIdProperty = refColTypeDesc.getTypeIdProperty();
               if (typeIdProperty != null) {
                  Object typeIdRes = DBUtil.getResultSetByIndex(rs, rix, typeIdProperty);
                  if (typeIdRes != null)
                     typeId = (int) typeIdRes;
               }
            }
            if (!nullId)
               val = refColTypeDesc.lookupInstById(idVals, typeId, true, false);
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

   public void updateReferenceForPropValue(Object inst, Object propVal) {
      if (refDBTypeDesc != null && propVal != null) {
         if (!(propVal instanceof IDBObject))
            throw new IllegalArgumentException("Invalid return from get value for reference");
         DBObject refDBObj = ((IDBObject) propVal).getDBObject();
         if (refDBObj.isPrototype()) {
            // Fill in the reverse property
            if (reversePropDesc != null) {
               reversePropDesc.updateReverseValue(propVal, inst);
            }
            // Because we have stored a reference and there's an integrity constraint, we're going to assume the
            // reference refers to a persistent object.
            // TODO: should there be an option to validate the reference here - specifically if it's defined in a
            // different data store?
            refDBObj.setPrototype(false);
         }
      }
   }

   private void updateReverseValue(Object propVal, Object inst) {
      // TODO: is this necessary? shouldn't the data binding events take care of it
      if (!reversePropDesc.multiRow && !multiRow)
         reversePropDesc.getPropertyMapper().setPropertyValue(propVal, inst);
      else {
         // use code from ReversePropertyListener if we need to do this at all...
      }
   }

   public Object getValueFromResultSetByName(ResultSet rs, String colName) throws SQLException {
      Object val;
      int numCols = getNumColumns();
      if (numCols == 1)  {
         val = DBUtil.getResultSetByName(rs, colName, this);
         if (refDBTypeDesc != null && val != null) {
            DBPropertyDescriptor typeIdProperty = refDBTypeDesc.getTypeIdProperty();
            int typeId = -1;
            if (typeIdProperty != null) {
               typeId = (int) DBUtil.getResultSetByName(rs, DBTypeDescriptor.DBTypeIdColumnName, typeIdProperty);
            }
            val = refDBTypeDesc.lookupInstById(val, typeId, true, false);
         }
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
               Object idVal = DBUtil.getResultSetByName(rs, refIdCol.columnName, refIdCol);
               if (idVal != null)
                  nullId = false;
               idVals.setVal(idVal, i);
            }
            DBPropertyDescriptor typeIdProperty = refDBTypeDesc.getTypeIdProperty();
            int typeId = -1;
            if (typeIdProperty != null) {
               typeId = (int) DBUtil.getResultSetByName(rs, DBTypeDescriptor.DBTypeIdColumnName, typeIdProperty);
            }
            if (!nullId)
               val = refDBTypeDesc.lookupInstById(idVals, typeId, true, false);
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

   /**
    * If this is an id property, returns the type of that id.
    * For a reference column, returns the type of the reference.
    */
   public DBTypeDescriptor getRefColTypeDesc() {
      return refDBTypeDesc;
   }


   public DBColumnType getDBColumnType() {
      if (refDBTypeDesc != null)
         return DBColumnType.Reference;
      if (typeIdProperty)
         return DBColumnType.Int;
      Object propertyType = getPropertyMapper().getPropertyType();
      DBColumnType res = DBColumnType.fromJavaType(propertyType);
      // TODO: should we have an annotation for this and print an error if it's not set?  Not all objects can be converted
      // to JSON
      if (res == null)
         res = DBColumnType.Json;
      return res;
   }

   public DBColumnType getSubDBColumnType(String propPath) {
      if (getDBColumnType() == DBColumnType.Json) {
         Object propertyType = getPropertyMapper().getPropertyType();
         String[] propNames = StringUtil.split(propPath, '.');
         for (int i = 0; i < propNames.length; i++) {
            String propName = propNames[i];
            if (propertyType == null)
               break;
            Object nextType = DynUtil.getPropertyType(propertyType, propName);
            propertyType = nextType;
         }
         if (propertyType == null)
            throw new IllegalArgumentException("Sub-property path: " + propPath + " not found in type: " + this);
         return DBColumnType.fromJavaType(propertyType);
      }
      throw new UnsupportedOperationException("Unhandled case");
   }

   public Object getDBDefaultValue() {
      if (dbDefault == null || dbDefault.length() == 0)
         return null;
      if (dbDefault.equalsIgnoreCase("now()"))
         return new java.util.Date();
      throw new UnsupportedOperationException("Unrecognized dbDefault value for memory database");
   }

   public boolean ownedByOtherType(DBTypeDescriptor otherTypeDesc) {
      if (ownerTypeName == null || otherTypeDesc == dbTypeDesc)
         return false;
      return !DynUtil.isAssignableFrom(dbTypeDesc.typeDecl, otherTypeDesc.typeDecl);
   }

   public Object getPropertyType() {
      return typeIdProperty ? int.class : getPropertyMapper().getGenericType();
   }

   /** For single-valued association properties, we add a property propNameRefId to store the id */
   public boolean getNeedsRefId() {
      return refDBTypeDesc != null && !multiRow;
   }

   public void setRefIdProperty(IDBObject obj, Object idVal) {
      String propName = propertyName + RefIdPropertySuffix;
      DynUtil.setProperty(obj, propName, idVal);
   }

   public Object getRefIdProperty(IDBObject inst) {
      String propName = propertyName + RefIdPropertySuffix;
      return DynUtil.getProperty(inst, propName);
   }

   public boolean hasValidator() {
      if (!validatorInited) {
         validatorInited = true;
         validateMethod = DynUtil.resolveMethod(dbTypeDesc.typeDecl, "validate" + CTypeUtil.capitalizePropertyName(propertyName), String.class, null);
      }
      return validateMethod != null;
   }

   public String validate(DBObject dbObj, Object propVal) {
      if (hasValidator()) {
         try {
            return (String) DynUtil.invokeMethod(dbObj.getInst(), validateMethod, propVal);
         }
         catch (IllegalArgumentException exc) {
            return "Invalid value for property " + propertyName;
         }
      }
      return null;
   }
}
