package sc.db;

import sc.dyn.DynUtil;

import java.util.*;

public class DBTypeDescriptor {
   static Map<Object,DBTypeDescriptor> typeDescriptorsByType = new HashMap<Object,DBTypeDescriptor>();
   static Map<String,DBTypeDescriptor> typeDescriptorsByName = new HashMap<String,DBTypeDescriptor>();

   public static void add(DBTypeDescriptor typeDescriptor) {
      Object typeDecl = typeDescriptor.typeDecl;
      DBTypeDescriptor oldType = typeDescriptorsByType.put(typeDecl, typeDescriptor);
      String typeName = DynUtil.getTypeName(typeDecl, false);
      DBTypeDescriptor oldName = typeDescriptorsByName.put(typeName, typeDescriptor);
      if ((oldType != null && oldType != typeDescriptor) | (oldName != null && oldName != typeDescriptor)) {
         System.err.println("Replacing type descriptor for type: " + typeName);
      }
   }

   public static DBTypeDescriptor getByType(Object type) {
      DBTypeDescriptor res = typeDescriptorsByType.get(type);
      if (res != null)
         return res;
      Object superType = DynUtil.getExtendsType(type);
      if (superType != Object.class)
         return getByType(superType);
      return null;
   }

   public static DBTypeDescriptor getByName(String typeName) {
      return typeDescriptorsByName.get(typeName);
   }

   // Class or ITypeDeclaration for dynamic types
   public Object typeDecl;

   public DBTypeDescriptor baseType;

   // Name for the connection to the database
   public String dataSourceName;

   public boolean queueInserts = false;

   /** Set to true for types where the source has no 'id' property */
   public boolean needsAutoId = false;

   public DBTypeDescriptor(Object typeDecl, DBTypeDescriptor baseType, String dataSourceName, TableDescriptor primary, List<TableDescriptor> auxTables, List<MultiTableDescriptor> multiTables, String versionPropName) {
      this.typeDecl = typeDecl;
      this.baseType = baseType;
      this.dataSourceName = dataSourceName;
      this.primaryTable = primary;
      primary.dbTypeDesc = this;
      if (auxTables != null) {
         for (TableDescriptor auxTable:auxTables)
            auxTable.init(this);
      }
      if (multiTables != null) {
         for (TableDescriptor multiTable:multiTables)
            multiTable.init(this);
      }
      this.multiTables = multiTables;
      this.auxTables = auxTables;
      if (versionPropName != null)
         this.versionProperty = primaryTable.getPropertyDescriptor(versionPropName);

      if (primaryTable.idColumns == null || primaryTable.idColumns.size() == 0) {
         needsAutoId = true;
         primaryTable.addIdColumnProperty(new IdPropertyDescriptor("id", "id", "serial", true));
      }

      allDBProps.addAll(primaryTable.idColumns);
      allDBProps.addAll(primaryTable.columns);
      if (auxTables != null) {
         for (TableDescriptor td:auxTables)
            allDBProps.addAll(td.columns);
      }
      if (multiTables != null) {
         for (MultiTableDescriptor mtd:multiTables)
            allDBProps.addAll(mtd.columns);
      }

      // Build up the set of 'fetchGroups' - the queries to fetch properties for a given item
      for (DBPropertyDescriptor prop:allDBProps) {
         prop.dbTypeDesc = this;
         if (prop.fetchGroup != null) {
            addToFetchGroup(prop.fetchGroup, prop);
         }
         else if (prop.onDemand)
            addToFetchGroup(prop.propertyName, prop);
         else {
            TableDescriptor table = getTableByName(prop.tableName);
            addToFetchGroup(table.getJavaName(), prop);
         }
      }
   }

   public TableDescriptor getTableForProp(DBPropertyDescriptor prop) {
      if (prop.tableName == null)
         return primaryTable;
      else
         return getTableByName(prop.tableName);
   }

   private void addToFetchGroup(String fetchGroup, DBPropertyDescriptor prop) {
      if (prop.isId()) // Don't fetch ids since they don't change
         return;
      DBFetchGroupQuery query = fetchGroups.get(fetchGroup);
      if (query == null) {
         query = new DBFetchGroupQuery();
         query.dbTypeDesc = this;
         query.fetchGroup = fetchGroup;
         fetchGroups.put(fetchGroup, query);
      }
      query.addProperty(prop);
      propQueriesIndex.put(prop.propertyName, query);
   }

   public TableDescriptor primaryTable;

   public List<TableDescriptor> auxTables;

   public List<MultiTableDescriptor> multiTables;

   public DBPropertyDescriptor versionProperty;

   public List<DBPropertyDescriptor> allDBProps = new ArrayList<DBPropertyDescriptor>();

   public LinkedHashMap<String,DBFetchGroupQuery> fetchGroups = new LinkedHashMap<String,DBFetchGroupQuery>();

   // TODO: check property level database annotation and lazily add a chained list of other types to this one. Treat them like aux-tables that can't be joined in to queries
   //List<DBTypeDescriptor> auxDatabases;

   public TableDescriptor getTableByName(String tableName) {
      if (tableName == null)
         return primaryTable;
      if (auxTables != null) {
         for (TableDescriptor td:auxTables)
            if (td.tableName.equalsIgnoreCase(tableName))
               return td;
      }
      if (multiTables != null) {
         for (TableDescriptor td:multiTables)
            if (td.tableName.equalsIgnoreCase(tableName))
               return td;
      }
      return null;
   }

   Map<String, DBFetchGroupQuery> propQueriesIndex = new HashMap<String, DBFetchGroupQuery>();

   Map<String,DBQuery> queriesIndex = new HashMap<String,DBQuery>();
   ArrayList<DBQuery> queriesList = new ArrayList<DBQuery>();

   public DBQuery getFetchQueryForProperty(String propName) {
      DBQuery query = propQueriesIndex.get(propName);
      if (query == null) {
         System.err.println("*** No fetch query for property: " + propName);
      }
      return query;
   }

   public DBPropertyDescriptor getPropertyDescriptor(String propName) {
      DBPropertyDescriptor res = null;
      if (primaryTable != null) {
         res = primaryTable.getPropertyDescriptor(propName);
         if (res != null)
            return res;
      }
      for (TableDescriptor tableDesc:auxTables) {
         res = tableDesc.getPropertyDescriptor(propName);
         if (res != null)
            return res;
      }
      for (MultiTableDescriptor tableDesc:multiTables) {
         res = tableDesc.getPropertyDescriptor(propName);
         if (res != null)
            return res;
      }
      /*
      if (auxDatabases != null) {
         for (DBTypeDescriptor auxDB:auxDatabases) {
            res = auxDB.getPropertyDescriptor(propName);
            if (res != null)
               return res;
         }
      }
      */
      return null;
   }

   public void addQuery(DBQuery query) {
      query.queryNumber = queriesList.size();
      queriesList.add(query);
      queriesIndex.put(query.queryName, query);
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("DBTypeDesc:");
      if (typeDecl != null)
         sb.append(DynUtil.getTypeName(typeDecl, false));
      return sb.toString();
   }
}
