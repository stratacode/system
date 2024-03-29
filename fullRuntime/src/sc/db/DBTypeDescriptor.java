/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.db;

import sc.bind.*;
import sc.dyn.DynUtil;
import sc.sync.SyncManager;
import sc.sync.SyncProperties;
import sc.type.CTypeUtil;
import sc.type.IBeanMapper;
import sc.util.StringUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Stores the metadata for a given type in the system that represents the mapping to a persistence storage
 * layer. A given type can be persisted from one or more tables, in the same or different databases.
 * There's a primary table, that will have one row per object instance. Auxiliary tables store alternate properties
 * and can be optionally created.  Multi-tables store multi-valued properties stored with 'multiRow' mode.
 *
 * A selectGroup allows collections of properties to be loaded at the same time. By default, a select group is
 * created for each table.
 */
public class DBTypeDescriptor extends BaseTypeDescriptor {
   public final static String DBTypeIdPropertyName = "dbTypeId";
   public final static String DBTypeIdColumnName = "db_type_id";
   public final static String LastModifiedPropertyName = "lastModified";

   public final static String DBDynPropsColumnName = "db_dyn_props";
   public final static String DBDynPropsColumnType = "jsonb";

   /** Two reserved type ids - either not specified or it's an abstract type so it's not created */
   public final static int DBUnsetTypeId = -1;
   public final static int DBAbstractTypeId = -2;

   boolean resolved = false;

   public static void clearAllCaches() {
      for (BaseTypeDescriptor dbTypeDesc:typeDescriptorsByName.values()) {
         if (dbTypeDesc instanceof DBTypeDescriptor)
            ((DBTypeDescriptor) dbTypeDesc).clearTypeCache();
      }
   }

   public static DBTypeDescriptor create(Object typeDecl, DBTypeDescriptor baseType, int typeId, String dataSourceName, TableDescriptor primary,
              List<TableDescriptor> auxTables, List<TableDescriptor> multiTables, List<BaseQueryDescriptor> queries, String versionPropName, String schemaSQL, boolean needsAutoId) {

      DBTypeDescriptor dbTypeDesc = new DBTypeDescriptor(typeDecl, baseType, typeId, dataSourceName, primary, auxTables, multiTables, queries, versionPropName, schemaSQL, needsAutoId);

      BaseTypeDescriptor oldType = typeDescriptorsByType.put(typeDecl, dbTypeDesc);
      String typeName = DynUtil.getTypeName(typeDecl, false);
      BaseTypeDescriptor oldName = typeDescriptorsByName.put(typeName, dbTypeDesc);

      dbTypeDesc.initTables(auxTables, multiTables, versionPropName, true);

      dbTypeDesc.init();

      if ((oldType != null && oldType != dbTypeDesc) | (oldName != null && oldName != dbTypeDesc)) {
         System.err.println("Replacing type descriptor for type: " + typeName);
      }
      return dbTypeDesc;
   }

   public static DBTypeDescriptor getByType(Object type, boolean start) {
      BaseTypeDescriptor base = getBaseByType(type, start);
      if (base instanceof DBTypeDescriptor)
         return (DBTypeDescriptor) base;
      return null;
   }


   public static BaseTypeDescriptor getBaseByType(Object type, boolean start) {
      BaseTypeDescriptor res = typeDescriptorsByType.get(type);
      if (res != null) {
         if (start)
            res.startAndActivate();
         return res;
      }
      Object superType = DynUtil.getExtendsType(type);
      if (superType != Object.class)
         return getByType(superType, start);
      return null;
   }

   public static DBTypeDescriptor getByName(String typeName, boolean start) {
      BaseTypeDescriptor res = typeDescriptorsByName.get(typeName);
      if (res == null) {
         Object findType = DynUtil.findType(typeName);
         if (findType == null) {
            return null;
         }
         else {
            res = typeDescriptorsByName.get(typeName);

         }
      }
      if (!(res instanceof DBTypeDescriptor))
         return null;
      if (start)
         res.startAndActivate();
      return (DBTypeDescriptor) res;
   }

   public static DBTypeDescriptor getByTableName(String tableName) {
      for (BaseTypeDescriptor dbTypeDesc:typeDescriptorsByName.values()) {
         if (dbTypeDesc instanceof DBTypeDescriptor && ((DBTypeDescriptor) dbTypeDesc).getTableByName(tableName) != null)
            return (DBTypeDescriptor) dbTypeDesc;
      }
      return null;
   }

   public DBTypeDescriptor baseType;

   /** Turn off writes to the database - instead, these will be stored in memory and merged into query results for testing purposes */
   public boolean dbReadOnly = false;
   /** Turn off database entirely - store objects and run queries only in memory for testing */
   public boolean dbDisabled = false;

   /** Set to true for types where the source has no 'id' property */
   public boolean needsAutoId = false;

   public String defaultFetchGroup;

   /** The cache of instances for this type but only if baseType = null - otherwise, we use the baseType's typeInstances cache */
   // TODO: need a way to expire the cache. I'm thinking framework hooks installed by type to control the behavior:
   // LRU cache, flush at the end of a transaction for all objects touched in that transaction, or for objects cached
   // in a ScopeContext, they will get removed when the scope context is disposed (e.g. per session).
   public ConcurrentHashMap<Object,IDBObject> typeInstances = null;

   public List<BaseQueryDescriptor> queries = null;
   private Map<String,NamedQueryDescriptor> namedQueryIndex = null;

   /** Commands in the database's DDL language (usually SQL) to be added after the generated create tables */
   public String schemaSQL;

   /** False during the code-generation phase, true when connected to a database */
   public boolean runtimeMode = false;

   public long expireTimeMillis = 60*1000*15;

   public Map<Integer,DBTypeDescriptor> subTypesById = null;
   public List<DBTypeDescriptor> subTypes = null;
   public int typeId = -1;
   DBPropertyDescriptor typeIdProperty = null;

   public int[] typeIdList = null;

   public SyncProperties syncProps = null;
   public boolean syncPropsInited = false;
   public boolean liveDynTypes = false;

   long transientIdCount = 0; // Incremented each time we need to allocate an object id for a transient object (i.e. when it's used in synchronization before being persisted)

   public DBTypeDescriptor findSubType(String subTypeName) {
      // During code-processing time, this is not set yet
      if (subTypes == null || subTypeName.equals(getTypeName()))
         return this;
      for (DBTypeDescriptor subType:subTypes)
         if (subType.getTypeName().equals(subTypeName))
            return subType;
      DBUtil.error("*** No sub-type: " + subTypeName + " for: " + getTypeName());
      return null;
   }

   /**
    * Defines the type with the id properties of the primary table in tact so it can be used to create references from other types.
    * Properties may be later added to the primary table and other tables added with initTables. This version is used from
    * the code processor as it must create the descriptors in phases.
    */
   public DBTypeDescriptor(Object typeDecl, DBTypeDescriptor baseType, int typeId, String dataSourceName, TableDescriptor primary,
                           List<BaseQueryDescriptor> queries, String schemaSQL) {
      super(typeDecl, dataSourceName);
      this.baseType = baseType;
      this.typeId = typeId;
      if (baseType != null) {
         this.primaryTable = baseType.primaryTable;
         if (primary != null && primaryTable != primary)
            System.err.println("*** Subtype should not have it's own primary table");
      }
      else
         this.primaryTable = primary;
      this.queries = queries;
      this.schemaSQL = schemaSQL;

      if (baseType == null) {
         if (primary == null) {
            DBUtil.error("No primary table for DB type: " + DynUtil.getType(typeDecl));
         }
         else {
            primary.init(this);
            primary.primary = true;

            if (primaryTable.idColumns == null || primaryTable.idColumns.size() == 0) {
               needsAutoId = true;
               IdPropertyDescriptor newIdProp = new IdPropertyDescriptor("id", "id", "bigserial", true, null);
               newIdProp.propertyType = Long.TYPE;
               primaryTable.addIdColumnProperty(newIdProp);
            }
         }
      }
      else {
         if (typeId != DBAbstractTypeId)
            baseType.addSubType(this);
      }
      initQueriesIndex();
   }

   public DBTypeDescriptor getRootType() {
      if (baseType != null) {
         return baseType.getRootType();
      }
      return this;
   }

   public void addSubType(DBTypeDescriptor subType) {
      int typeId = subType.typeId;
      if (typeId == -1) {
         DBUtil.error("Sub type: " + subType.getTypeName() + " extends DB type: " + getTypeName() + " - must have @DBTypeSettings(typeId) to register instances in the DB: ");
         return;
      }
      if (subTypesById == null) {
         subTypesById = new HashMap<Integer,DBTypeDescriptor>();
         if (!runtimeMode) {
            typeIdProperty = new DBPropertyDescriptor("dbTypeId", "db_type_id", "integer", null,
                    true, false, false, false, false, null, null,
                    null, false,  null, null, getTypeName());
            typeIdProperty.typeIdProperty = true;
            primaryTable.addTypeIdProperty(typeIdProperty);
         }
      }
      if (subTypes == null)
         subTypes = new ArrayList<DBTypeDescriptor>();
      subTypes.add(subType);
      DBTypeDescriptor old = subTypesById.put(typeId, subType);
      if (old != null && old != subType && !old.getTypeName().equals(subType.getTypeName())) {
         DBUtil.error("Error - same typeId used for different types: " + old.getTypeName() + " and " + subType.getTypeName() + " have: " + typeId);
      }
      if (subType.subTypesById == null) {
         subType.subTypesById = subTypesById;
      }
   }

   private void initQueriesIndex() {
      if (queries != null) {
         for (BaseQueryDescriptor queryDesc:queries) {
            addToQueryIndex(queryDesc);
         }
      }
   }

   /** The constructor called from generated runtime code - but the generated code uses the static 'create' method that calls this */
   public DBTypeDescriptor(Object typeDecl, DBTypeDescriptor baseType, int typeId, String dataSourceName, TableDescriptor primary,
                           List<TableDescriptor> auxTables, List<TableDescriptor> multiTables, List<BaseQueryDescriptor> queries, String versionPropName, String schemaSQL, boolean needsAutoId) {
      this(typeDecl, baseType, typeId, dataSourceName, primary, queries, schemaSQL);
      this.needsAutoId = needsAutoId;
   }

   public void initTables(List<TableDescriptor> auxTables, List<TableDescriptor> multiTables, String versionPropName, boolean runtimeMode) {
      tablesInitialized = true;
      this.runtimeMode = runtimeMode;
      if (auxTables != null) {
         for (TableDescriptor auxTable:auxTables)
            auxTable.init(this);
      }
      if (multiTables != null) {
         for (TableDescriptor multiTable:multiTables) {
            multiTable.multiRow = true;
            multiTable.init(this);
         }
      }
      this.multiTables = multiTables;
      this.auxTables = auxTables;
      if (versionPropName != null) {
         this.versionProperty = getPrimaryTable().getPropertyDescriptor(versionPropName);
      }
      // An optional property, but if it's here update it automatically.
      this.lastModifiedProperty = getPrimaryTable().getPropertyDescriptor(LastModifiedPropertyName);

      allDBProps.addAll(primaryTable.idColumns);
      allDBProps.addAll(primaryTable.columns);
      if (auxTables != null) {
         for (TableDescriptor td:auxTables) {
            td.initIdColumns();
            allDBProps.addAll(td.columns);
         }
      }
      if (multiTables != null) {
         for (TableDescriptor mtd:multiTables) {
            mtd.initIdColumns();
            // For the many property of a one-to-many, the table columns belong to the other side so don't add them here -
            // instead add the reverse property
            if (mtd.reverseProperty != null)
               allDBProps.add(mtd.reverseProperty);
            else
               allDBProps.addAll(mtd.columns);
         }
      }
   }

   void addMultiTable(TableDescriptor table) {
      if (multiTables == null)
         multiTables = new ArrayList<TableDescriptor>();
      else if (!(multiTables instanceof ArrayList))
         multiTables = new ArrayList<TableDescriptor>(multiTables);
      multiTables.add(table);
   }

   void addAuxTable(TableDescriptor table) {
      if (auxTables == null)
         auxTables = new ArrayList<TableDescriptor>();
      else if (!(auxTables instanceof ArrayList))
         auxTables = new ArrayList<TableDescriptor>(auxTables);
      auxTables.add(table);
   }

   public void addQueryDescriptor(BaseQueryDescriptor queryDesc) {
      if (queries == null)
         queries = new ArrayList<BaseQueryDescriptor>();
      queries.add(queryDesc);
      addToQueryIndex(queryDesc);
   }

   private void addToQueryIndex(BaseQueryDescriptor queryDesc) {
      if (queryDesc instanceof NamedQueryDescriptor) {
         NamedQueryDescriptor nameDesc = (NamedQueryDescriptor) queryDesc;
         if (namedQueryIndex == null)
            namedQueryIndex = new HashMap<String,NamedQueryDescriptor>();
         namedQueryIndex.put(nameDesc.queryName, nameDesc);
         nameDesc.dbTypeDesc = this;
      }
   }

   void initFetchGroups() {
      if (!runtimeMode)
         return;
      if (defaultFetchGroup != null)
         return;
      defaultFetchGroup = getPrimaryTable().getJavaName();

      if (baseType != null) {
         baseType.initFetchGroups();
         // Start out with a complete copy of the queries
         copyQueriesFrom(baseType);
      }

      String firstFetchGroup = null;
      // Build up the set of 'selectGroups' - the queries to select properties for a given item
      for (DBPropertyDescriptor prop:allDBProps) {
         String selectGroup = prop.selectGroup;
         boolean selectable = false;
         if (prop.isMultiRowProperty()) {
            TableDescriptor mvTable = getMultiTableByName(prop.getTableName(), prop);
            if (mvTable == null)
               System.err.println("*** No multi-value table for property: " + prop.propertyName);
            else
               selectable = addToFetchGroup(prop.propertyName, prop);
         }
         else {
            if (selectGroup == null) {
               // For onDemand single-valued associations, load the id with the parent type by default.
               // For non-associations, onDemand puts the property into it's own property group (since it
               // does not do anything else)
               // TODO: should we have a separate flag for onDemand that's not for associations?
               if (prop.onDemand && prop.refDBTypeDesc == null) {
                  selectGroup = prop.propertyName;
               }
               else {
                  TableDescriptor table = getTableByName(prop.tableName);
                  selectGroup = table.getJavaName();
               }
            }
            selectable = addToFetchGroup(selectGroup, prop);
         }
         if (firstFetchGroup == null && selectable) {
            firstFetchGroup = selectGroup;
         }
      }
      // All properties are on-demand or in some other select group. Pick the first one to use as the default so that
      // we have some property to load to validate that the item exists.
      if (selectGroups.get(defaultFetchGroup) == null) {
         if (firstFetchGroup == null)
            DBUtil.error("No main table properties for persistent type: " + this);
         else
            selectGroups.put(defaultFetchGroup, selectGroups.get(firstFetchGroup));
      }
   }

   private boolean addToFetchGroup(String selectGroup, DBPropertyDescriptor prop) {
      if (prop.isId()) // Don't get the in a select when the id is known
         return false;
      SelectGroupQuery query = selectGroups.get(selectGroup);
      if (query == null) {
         query = new SelectGroupQuery(this, null, selectGroup);
         query.selectGroup = selectGroup;
         selectGroups.put(selectGroup, query);
         addFetchQuery(query);
      }
      query.addProperty(null, prop, false);
      propQueriesIndex.put(prop.propertyName, query);
      return true;
   }

   public TableDescriptor primaryTable;

   public List<TableDescriptor> auxTables;

   public List<TableDescriptor> multiTables;

   public DBPropertyDescriptor versionProperty;
   public DBPropertyDescriptor lastModifiedProperty;

   public List<DBPropertyDescriptor> allDBProps = new ArrayList<DBPropertyDescriptor>();

   public List<DBPropertyDescriptor> reverseProps = null;

   public LinkedHashMap<String, SelectGroupQuery> selectGroups = new LinkedHashMap<String, SelectGroupQuery>();

   // TODO: check property level database annotation and lazily add a chained list of other types to this one. Treat them like aux-tables that can't be joined in to queries
   //List<DBTypeDescriptor> auxDatabases;

   public TableDescriptor getTableByName(String tableName) {
      if (tableName == null || tableName.equalsIgnoreCase(primaryTable.tableName))
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

   public TableDescriptor getMultiTableByName(String tableName, DBPropertyDescriptor mvProp) {
      if (tableName == null)
         throw new UnsupportedOperationException();
      if (multiTables != null) {
         for (TableDescriptor td:multiTables)
            if (td.tableName.equalsIgnoreCase(tableName))
               return td;
      }
      else
         multiTables = new ArrayList<TableDescriptor>();
      TableDescriptor newTable = new TableDescriptor(tableName);
      newTable.multiRow = true;
      newTable.addColumnProperty(mvProp);
      newTable.dbTypeDesc = this;
      addMultiTable(newTable);
      return newTable;
   }

   // Data structures for queries that select property values. We can look up the query for a given property,
   // and ensure we only queue a single query at a time for that property group, even if requested by more than
   // one thread.
   Map<String, SelectGroupQuery> propQueriesIndex = new HashMap<String, SelectGroupQuery>();
   Map<String,DBQuery> selectQueriesIndex = new HashMap<String,DBQuery>();
   ArrayList<DBQuery> selectQueriesList = new ArrayList<DBQuery>();

   public void copyQueriesFrom(DBTypeDescriptor fromType) {
      for (DBQuery fromQuery:fromType.selectQueriesList) {
         DBQuery toQuery = fromQuery.cloneForSubType(this);
         toQuery.dbTypeDesc = this;
         selectQueriesList.add(toQuery);
         selectQueriesIndex.put(toQuery.queryName, toQuery);
         if (toQuery instanceof SelectGroupQuery) {
            SelectGroupQuery toGroupQuery = (SelectGroupQuery) toQuery;
            selectGroups.put(toGroupQuery.selectGroup, toGroupQuery);
         }
      }
      for (String baseProp:fromType.propQueriesIndex.keySet()) {
         DBQuery fromQuery = fromType.propQueriesIndex.get(baseProp);
         DBQuery toQuery = selectQueriesIndex.get(fromQuery.queryName);
         if (toQuery instanceof SelectGroupQuery)
            propQueriesIndex.put(baseProp, (SelectGroupQuery) toQuery);
      }
   }

   public SelectGroupQuery getDefaultFetchQuery() {
      return selectGroups.get(defaultFetchGroup);
   }

   public SelectGroupQuery getSelectGroupQuery(String selectGroup) {
      return selectGroups.get(selectGroup);
   }

   public SelectGroupQuery getFetchQueryForProperty(String propName) {
      if (!started)
         startAndActivate();
      SelectGroupQuery query = propQueriesIndex.get(propName);
      if (query == null) {
         System.err.println("*** No select query for property: " + propName);
      }
      return query;
   }

   public DBQuery getFetchQueryForNum(int queryNum) {
      return selectQueriesList.get(queryNum);
   }

   public int getNumFetchPropQueries() {
      return selectQueriesList.size();
   }

   public DBPropertyDescriptor getPropertyDescriptor(String propNamePath) {
      String[] propNameList = StringUtil.split(propNamePath, '.');
      DBPropertyDescriptor res = null;
      DBTypeDescriptor curType = this;
      int pix = 0;
      int numInChain = propNameList.length;
      do {
         String propName = propNameList[pix++];
         if (curType.primaryTable != null) {
            res = curType.primaryTable.getPropertyDescriptor(propName);
         }
         if (res == null && curType.baseType != null) {
            res = curType.baseType.getPropertyDescriptor(propName);
         }
         if (res == null && curType.auxTables != null) {
            for (TableDescriptor tableDesc:curType.auxTables) {
               res = tableDesc.getPropertyDescriptor(propName);
            }
         }
         if (res == null && curType.multiTables != null) {
            for (TableDescriptor tableDesc:curType.multiTables) {
               res = tableDesc.getPropertyDescriptor(propName);
               if (res != null)
                  break;
            }
         }
         if (res == null) {
            if (pix < numInChain) {
               DBUtil.error("Property path name: " + propNamePath + " missing property: " + propName + " in chain");
            }
            return null; // More detailed error printed by caller
         }
         if (pix < numInChain) {
            DBTypeDescriptor nextType = res.refDBTypeDesc;
            if (nextType == null) {
               if (res.getDBColumnType() != DBColumnType.Json)
                  DBUtil.error("Property path name: " + propNamePath + " no DB ref type for: " + propName);
               return null;
            }
            curType = nextType;
         }
      } while (pix < numInChain);
      return res;
   }

   public DBPropertyDescriptor getPropertyForColumn(String colName) {
      DBPropertyDescriptor res;
      if (primaryTable != null) {
         res = primaryTable.getPropertyForColumn(colName);
         if (res != null)
            return res;
      }
      if (auxTables != null) {
         for (TableDescriptor table:auxTables) {
            res = table.getPropertyForColumn(colName);
            if (res != null)
               return res;
         }
      }
      if (multiTables != null) {
         for (TableDescriptor table:multiTables) {
            res = table.getPropertyForColumn(colName);
            if (res != null)
               return res;
         }
      }
      return null;
   }

   public void addFetchQuery(DBQuery query) {
      query.queryNumber = selectQueriesList.size();
      selectQueriesList.add(query);
      selectQueriesIndex.put(query.queryName, query);
   }

   public Object stringToId(String idStr) {
      Object idType = getIdColumnType(0);
      if (idType == Long.class || idType == Long.TYPE) {
         try {
            return Long.parseLong(idStr);
         }
         catch (NumberFormatException exc) {
            System.err.println("*** Invalid id string: " + idStr + " for db type: " + this);
         }
         return null;
      }
      else if (idType == String.class)
         return idStr;
      // TODO add multi column, maybe int id support
      throw new IllegalArgumentException("Unsupported id type");
   }

   public IDBObject findById(Object... idArgs) {
      Object id;
      if (idArgs.length == 1)
         id = idArgs[0];
      else
         id = new MultiColIdentity(idArgs);

      IDBObject res = lookupInstById(id, -1, false, true);
      if (res != null && ((DBObject)res.getDBObject()).isPrototype())
         res = null;
      return res;
   }

   public List<? extends IDBObject> findByIds(Object... ids) {
      if (primaryTable.idColumns.size() == 1) {
         DBPropertyDescriptor id = primaryTable.idColumns.get(0);
         return query(Query.in(id.propertyName, ids), null, null, -1, -1);
      }
      else
         throw new UnsupportedOperationException("findByIds - not yet supported for multi column ids");
   }

   public List<? extends IDBObject> findAll(List<String> orderByNames, int startIx, int maxResults) {
      return findBy(null, null, null, orderByNames, startIx, maxResults);
   }

   public List<? extends IDBObject> findBy(List<String> propNames, List<Object> propValues, String selectGroup, List<String> orderByNames, int startIx, int maxResults) {
      IDBObject proto = initPrototypeForQuery(propNames, propValues);
      try {
         return matchQuery((DBObject) proto.getDBObject(), propNames, selectGroup, orderByNames, startIx, maxResults);
      }
      finally {
         DynUtil.dispose(proto);
      }
   }

   private IDBObject initPrototypeForQuery(List<String> propNames, List<Object> propValues) {
      int numVals = propValues == null ? 0 : propValues.size();
      int numParams = propNames == null ? 0 : propNames.size();
      if (numVals != numParams)
         throw new IllegalArgumentException("Mismatching numParamValues = " + numVals + " numParamNames: " + numParams);

      IDBObject proto = createPrototype(false);
      DBObject protoDB = (DBObject) proto.getDBObject();

      List<String> protoProperties = null;

      // Build the list of parent properties in order - if we have "a.b" and "a.b.c" in the query
      // the list should contain ["a", "a.b"] in that order
      for (int i = 0; i < numVals; i++) {
         String[] pathNames = StringUtil.split(propNames.get(i), '.');
         if (pathNames.length > 1) {
            if (protoProperties == null)
               protoProperties = new ArrayList<String>();
            String prefix = pathNames[0];
            if (!protoProperties.contains(prefix))
               protoProperties.add(prefix);
            for (int j = 1; j < pathNames.length - 1; j++) {
               prefix = CTypeUtil.prefixPath(prefix, pathNames[j]);
               if (!protoProperties.contains(prefix))
                  protoProperties.add(prefix);
            }
         }
      }
      // This creates any required intermediate properties if the query refers to "a.b" style queries.
      if (protoProperties != null)
         protoDB.initProtoProperties(protoProperties.toArray(new String[protoProperties.size()]));

      for (int i = 0; i < numVals; i++) {
         protoDB.setPropertyInPath(propNames.get(i), propValues.get(i));
      }
      return proto;
   }

   private void appendNextOrClause(SelectQuery query, String whereClause, boolean first) {
      if (!first)
         query.whereAppend(" OR ");
      query.whereAppend(whereClause);
   }

   public static String convertToSQLSearchString(String text) {
      if (text == null || text.length() == 0)
         return "%";
      return '%' + text + '%';
   }

   private void addSearchToQuery(SelectGroupQuery groupQuery, String text) {
      Long longVal = null;
      try {
         longVal = Long.parseLong(text);
      }
      catch (NumberFormatException exc) {
      }

      String pattern = convertToSQLSearchString(text);

      boolean first = true;
      for (DBPropertyDescriptor prop:allDBProps) {
         if (prop.typeIdProperty)
            continue;

         DBColumnType type = prop.getDBColumnType();
         switch (type) {
            case Int:
            case Long:
               if (longVal != null) {
                  SelectQuery query = groupQuery.addProperty(null, prop, true);
                  appendNextOrClause(query, prop.columnName, first);
                  query.whereAppend(" = ?");
                  if (type == DBColumnType.Int)
                     query.paramValues.add(longVal.intValue());
                  else
                     query.paramValues.add(longVal);
                  query.paramTypes.add(type);
                  first = false;
               }
               break;
            case String: {
               SelectQuery query = groupQuery.addProperty(null, prop, true);
               appendNextOrClause(query, prop.columnName, first);
               query.whereAppend(" ILIKE ?");
               query.paramValues.add(pattern);
               query.paramTypes.add(type);
               first = false;
               break;
            }
            case Boolean:
               break;
         }
      }
   }

   public List<? extends IDBObject> searchQuery(String text, List<String> propNames, List<Object> propValues, String selectGroup, List<String> orderByProps, int startIx, int maxResults) {
      IDBObject proto = propNames == null ? null : initPrototypeForQuery(propNames, propValues);
      SelectGroupQuery groupQuery = initQuery(selectGroup, propNames, true);
      if (proto != null) {
         DBObject protoDB = (DBObject) proto.getDBObject();
         addPropsToQuery(groupQuery, protoDB, propNames, true);
         addParamValues(groupQuery, protoDB, propNames, QCombine.And);
         if (text != null)
            groupQuery.curQuery.whereAppend(" AND (");
      }
      if (text != null)
         addSearchToQuery(groupQuery, text);
      if (proto != null && text != null) {
         groupQuery.curQuery.whereAppend(")");
      }
      groupQuery.setQueryAttributes(orderByProps, startIx, maxResults);

      DBTransaction curTx = DBTransaction.getOrCreate();

      try {
         return groupQuery.runQuery(curTx, null);
      }
      finally {
         if (proto != null)
            DynUtil.dispose(proto);
      }
   }

   /*
   public int searchCountQuery(String text) {
      //List <? extends IDBObject> res = searchQuery(null, text, null, 0, 100);
      //return res.size();
      SelectGroupQuery groupQuery =  new SelectGroupQuery(this, null, null);
      if (text != null)
         addSearchToQuery(groupQuery, text);

      DBTransaction curTx = DBTransaction.getOrCreate();

      return groupQuery.countQuery(curTx, null);
   }
   */

   public int searchCountQuery(String text, List<String> propNames, List<Object> propValues) {
      IDBObject proto = propNames == null ? null : initPrototypeForQuery(propNames, propValues);
      //List <? extends IDBObject> res = searchQuery(null, text, null, 0, 100);
      //return res.size();
      SelectGroupQuery groupQuery =  new SelectGroupQuery(this, null, null);
      DBObject protoDB = null;
      if (proto != null) {
         protoDB = (DBObject) proto.getDBObject();
         addPropsToQuery(groupQuery, protoDB, propNames, true);
         addParamValues(groupQuery, protoDB, propNames, QCombine.And);
         if (text != null)
            groupQuery.curQuery.whereAppend(" AND (");
      }
      if (text != null)
         addSearchToQuery(groupQuery, text);
      if (proto != null && text != null) {
         groupQuery.curQuery.whereAppend(")");
      }

      DBTransaction curTx = DBTransaction.getOrCreate();

      try {
         return groupQuery.countQuery(curTx, protoDB);
      }
      finally {
         if (proto != null)
            DynUtil.dispose(proto);
      }
   }

   public int countAll() {
      return searchCountQuery(null, null, null);
   }

   private boolean addQueryProps(Query query, SelectGroupQuery groupQuery, DBObject protoDB, boolean first, QCombine operator) {
      if (!first)
         groupQuery.curQuery.whereAppend(" " + operator.getSQLOperator() + " ");

      if (query instanceof PQuery) {
         PQuery pquery = (PQuery) query;
         boolean parens = !first;
         if (parens) {
            groupQuery.curQuery.whereAppend("(");
            first = true;
         }
         for (Query subQuery:pquery.queries) {
            addQueryProps(subQuery, groupQuery, protoDB, first, pquery.combiner);
            first = false;
         }
         if (parens)
            groupQuery.curQuery.whereAppend(")");
      }
      else if (query instanceof OpQuery) {
         OpQuery opquery = (OpQuery) query;

         String propName = opquery.propName;

         appendPropToWhereClause(groupQuery,  this, protoDB, CTypeUtil.getPackageName(propName), propName,
                     false, opquery.comparator, true, false, opquery.propValue);

         first = false;
      }
      return first;
   }

   private boolean addQueryParams(Query query, SelectGroupQuery groupQuery, DBObject protoDB, boolean first, QCombine operator) {
      if (query instanceof PQuery) {
         PQuery pquery = (PQuery) query;
         boolean parens = !first;
         if (parens) {
            first = true;
         }
         for (Query subQuery:pquery.queries) {
            addQueryParams(subQuery, groupQuery, protoDB, first, pquery.combiner);
            first = false;
         }
      }
      else if (query instanceof OpQuery) {
         OpQuery opquery = (OpQuery) query;

         if (!first) {
            SelectQuery curQuery = groupQuery.curQuery;
            StringBuilder logSB = curQuery.logSB;
            if (logSB != null) {
               logSB.append(" ");
               logSB.append(operator.getSQLOperator());
               logSB.append(" ");
            }
         }

         String propName = opquery.propName;

         appendPropParamValues(groupQuery, this, protoDB, propName, false, opquery.comparator, false, opquery.propValue);
         first = false;
      }
      return first;
   }

   public List<? extends IDBObject> query(Query query, String selectGroup, List<String> orderByProps, int startIx, int maxResults) {
      List<String> protoPropNames = query.getAllPropertyNames();
      List<Object> propValues = query.getAllPropertyValues();
      IDBObject proto = protoPropNames == null ? null : initPrototypeForQuery(protoPropNames, propValues);
      List<String> nonProtoProps = query.getNonProtoProps();
      List<String> allPropNames;
      if (nonProtoProps != null) {
         if (protoPropNames == null)
            allPropNames = nonProtoProps;
         else {
            allPropNames = new ArrayList<String>(protoPropNames);
            allPropNames.addAll(nonProtoProps);
         }
      }
      else
         allPropNames = protoPropNames;

      // Initialize the wrapper query - the one that will join across multiple DBs
      SelectGroupQuery groupQuery = initQuery(selectGroup, allPropNames, true);
      if (proto != null) {
         DBObject protoDB = (DBObject) proto.getDBObject();

         // Add all of the tables requires for the query
         addPropBindingTables(groupQuery, protoDB, protoPropNames);

         // Build the where clause
         boolean first = addQueryProps(query, groupQuery, protoDB, true, QCombine.And);

         // If it's a polymorphic query, add the constraint for the sub-types returned
         addBaseTypeClause(groupQuery, first);

         // Add the parameter values to the query
         addQueryParams(query, groupQuery, protoDB, true, QCombine.And);
      }
      groupQuery.setQueryAttributes(orderByProps, startIx, maxResults);

      DBTransaction curTx = DBTransaction.getOrCreate();

      try {
         return groupQuery.runQuery(curTx, null);
      }
      finally {
         if (proto != null)
            DynUtil.dispose(proto);
      }
   }


   public int count(Query query) {
      List<String> propNames = query.getAllPropertyNames();
      List<Object> propValues = query.getAllPropertyValues();
      IDBObject proto = propNames == null ? null : initPrototypeForQuery(propNames, propValues);
      // Initialize the wrapper query - the one that will join across multiple DBs
      SelectGroupQuery groupQuery = initQuery(null, propNames, true);
      if (proto != null) {
         DBObject protoDB = (DBObject) proto.getDBObject();

         // Add all of the tables requires for the query
         addPropBindingTables(groupQuery, protoDB, propNames);

         // Build the where clause
         boolean first = addQueryProps(query, groupQuery, protoDB, true, QCombine.And);

         // If it's a polymorphic query, add the constraint for the sub-types returned
         addBaseTypeClause(groupQuery, first);

         // Add the parameters to the query
         addQueryParams(query, groupQuery, protoDB, true, QCombine.And);
      }

      DBTransaction curTx = DBTransaction.getOrCreate();
      try {
         return groupQuery.countQuery(curTx, null);
      }
      finally {
         if (proto != null)
            DynUtil.dispose(proto);
      }
   }

   private void initTypeInstances() {
      synchronized (this) {
         if (typeInstances == null)
            typeInstances = new ConcurrentHashMap<Object,IDBObject>();
      }
   }

   /**
    * Lower level method used to lookup an instance of this type with a given id - based on the "highlander principle" - we try to ensure
    * there's only one instance of an object with the same id to prevent aliasing, simplify code and caching and provide a 'transactional view'
    * of objects with persistent state.
    *
    * If createProto is true, and the instance is not in the cache, a prototype is created with that and returned. If selectDefault
    * is true, that prototype is populated with the default property group if the row exists and returned. If the row does not exist
    * null is returned in that case.
    */
   public IDBObject lookupInstById(Object id, int typeId, boolean createProto, boolean selectDefault) {
      if (baseType != null) {
         return baseType.lookupInstById(id, typeId == DBUnsetTypeId ? this.typeId : typeId, createProto, selectDefault);
      }

      if (typeInstances == null) {
         initTypeInstances();
      }
      DBTypeDescriptor resTypeDesc = getSubTypeByTypeId(typeId);
      if (resTypeDesc == null) {
         throw new IllegalArgumentException("Attempt to lookup instance of abstract type: " + this);
      }

      // TODO: add a cacheMode option to enable a call to dbRefresh() or dbFetchDefault() on the returned instance, then return null if the item no longer exists
      IDBObject inst = typeInstances.get(id);
      if (inst != null) {
         return inst;
      }
      if (!createProto && dbDisabled)
         return null;

      DBObject dbObj;
      synchronized (this) {
         // check again after synchronizing
         inst = typeInstances.get(id);
         if (inst != null)
            return inst;
         // When typeId is unset, this returns null for abstract types or those with sub-types because we don't really know the
         // concrete type. In this case, we will create a stub DBObject to store the id properties and manage access to the query
         // to populate properties on this instance - at which point we learn the type and create the wrapper.
         inst = resTypeDesc.createPrototype(typeId == DBUnsetTypeId);
         if (inst != null) {
            dbObj = (DBObject) inst.getDBObject();
            dbObj.setDBId(id);
            typeInstances.put(id, inst);

            initSyncForInst(inst, true, false);
         }
         // Don't know the concrete type so create a DBObject without the instance and register that instead
         else if (selectDefault) {
            dbObj = new DBObject(this);
            dbObj.setDBId(id);
            dbObj.setPrototype(true);
            typeInstances.put(id, dbObj);
         }
         else // We do not know the concrete class yet for this object so just return
            return null;

         checkCacheSize();
      }

      // If requested, select the default (primary table) property group - if the row does not exist, the object does not exist
      if (selectDefault) {
         if (!dbObj.dbFetchDefault()) {
            // TODO: should we optionally cache a null-sentinel here for some time to avoid re-executing the same null result queries?
            typeInstances.remove(id);
            return null;
         }
      }
      if (dbObj.wrapper != inst)
         return dbObj.wrapper;
      return inst;
   }

   private final static int cacheCheckInterval = 100;

   private void checkCacheSize() {
      int size = typeInstances.size();
      if (size > cacheCheckInterval && ((size % cacheCheckInterval) == 0))
         DBUtil.info("DB cache size: " + size + " for type " + getTypeName());
   }

   public IDBObject createInstance() {
      return (IDBObject) DynUtil.createInstance(typeDecl, null);
   }

   public IDBObject createInstance(DBObject dbObj) {
      return (IDBObject) DynUtil.createInstance(typeDecl, "Lsc/db/DBObject;", dbObj);
   }

   public IDBObject createInstanceOfType(int typeId) {
      DBTypeDescriptor subType = getSubTypeByTypeId(typeId);
      if (subType == null)
         throw new IllegalArgumentException("No subType of: " + this + ": for typeId: " + typeId + " to create");
      return subType.createInstance();
   }

   public IDBObject createPrototype(boolean concreteOnly) {
      if (typeId == DBAbstractTypeId)
         return null;
      if (concreteOnly && subTypes != null && subTypes.size() > 0)
         return null;
      IDBObject inst = createInstance();
      DBObject dbObj = (DBObject) inst.getDBObject();
      dbObj.setPrototype(true);
      return inst;
   }

   private IDBObject registerInstanceInternal(IDBObject inst, boolean clearTransient) {
      if (typeInstances == null) {
         initTypeInstances();
      }
      Object id = inst.getDBId();
      synchronized (this) {
         IDBObject res = typeInstances.get(id);
         if (res != null)
            return res;
         typeInstances.put(id, inst);
         checkCacheSize();
         if (clearTransient) {
            DBObject dbObj = (DBObject) inst.getDBObject();

            // Do this before we've changed the objectId by clearing transient
            SyncManager.setResetStateEnabled(inst, false);

            String oldName = null;
            if (dbObj.objectId != null)
               oldName = dbObj.objectId;

            dbObj.setTransient(false);

            // Possible that we've been synchronized already as a transient object - change the id now that it's been assigned
            if (oldName != null) {
               dbObj.objectId = null;
               String newName = dbObj.getObjectId();
               // Now that the object is transient, the database will hold the state for this instance so don't need the sync system to manage it
               SyncManager.changeInstName(inst, oldName, newName);
            }
         }
      }
      return null;
   }

   public IDBObject registerInstance(IDBObject inst, boolean clearTransient) {
      IDBObject res = null;
      if (baseType != null)
         res = baseType.registerInstanceInternal(inst, clearTransient);
      else
         res = registerInstanceInternal(inst, clearTransient);

      DBUtil.mapTestInstance(inst);
      initSyncForInst(inst, true, false);
      return res;
   }

   public void initSyncForInst(Object inst, boolean onDemand, boolean resetState) {
      if (!syncPropsInited) {
         syncProps = SyncManager.getSyncProperties(typeDecl, null);
         syncPropsInited = true;
      }
      // Need to make the addSyncInst call after the id has been defined since we use the object id in the sync id.
      // The generated addSyncInst call is not included in the generated code so we do it here
      // TODO: do we need to specify a different value of onDemand here?  Maybe it gets put into SyncProperties so
      // it's available?
      if (syncProps != null) {
         SyncManager.addSyncInst(inst, onDemand, syncProps.initDefault, resetState, null, syncProps);
      }

      if (liveDynTypes)
         sc.dyn.DynUtil.addDynInstance(getTypeName(), inst);
   }

   private void replaceInstanceInternal(IDBObject inst) {
      if (typeInstances == null) {
         initTypeInstances();
      }
      Object id = inst.getDBId();
      synchronized (this) {
         IDBObject old = typeInstances.put(id, inst);
         if (old != null) {
            if (!(old instanceof DBObject))
               DBUtil.verbose("Replacing instance of type: " + this + " with id: " + id);
         }
         else
            checkCacheSize();
      }
      DBUtil.mapTestInstance(inst);
   }

   public void replaceInstance(IDBObject inst) {
      if (baseType != null) {
         baseType.replaceInstanceInternal(inst);
         return;
      }
      replaceInstanceInternal(inst);
      initSyncForInst(inst, true, ((DBObject) inst.getDBObject()).isTransient());
   }

   public boolean removeInstance(DBObject dbObj, boolean remove) {
      if (baseType != null)
         return baseType.removeInstance(dbObj, remove);

      if (typeInstances == null)
         return false;
      Object id = dbObj.getDBId();
      IDBObject removed = null;
      synchronized (this) {
         Object res = typeInstances.get(id);
         if (res == dbObj.getInst()) {
            removed = typeInstances.remove(id);
            if (removed != null) {
               DBObject remDBObj = (DBObject) removed.getDBObject();
               if (remove)
                  remDBObj.markRemoved();
               else
                  remDBObj.markStopped();

               removeReverseListeners(remDBObj);
               return true;
            }
         }
      }
      return false;
   }

   private void removeReverseListeners(DBObject dbObj) {
      if (baseType != null)
         baseType.removeReverseListeners(dbObj);
      if (reverseProps != null) {
         Object inst = dbObj.getInst();
         for (int i = 0; i < reverseProps.size(); i++) {
            DBPropertyDescriptor reverseProp = reverseProps.get(i);
            // Already added this one from the base type
            if (baseType != null && baseType.reverseProps.contains(reverseProp))
               continue;
            Bind.removeListenerOfType(inst, reverseProp.getPropertyMapper(), ReversePropertyListener.class, IListener.VALUE_CHANGED_MASK);
         }
      }
   }

   public void clearTypeCache() {
      if (baseType != null) {
         baseType.clearTypeCache();
         return;
      }

      if (typeInstances == null)
         return;

      synchronized (this) {
         for (IDBObject dbObj:typeInstances.values()) {
            DBObject unwrapped = ((DBObject) dbObj.getDBObject());
            stopObject(unwrapped);
         }
         typeInstances.clear();
      }
   }

   private void stopObject(DBObject toStop) {
      boolean isProto = toStop.isPrototype();
      toStop.markStopped();
      if (!isProto)
         removeReverseListeners(toStop);
   }

   public Object getIdColumnValue(Object inst, int ci) {
      IBeanMapper mapper = primaryTable.idColumns.get(ci).getPropertyMapper();
      return mapper.getPropertyValue(inst, false,false);
   }

   public Object getIdColumnType(int ci) {
      IBeanMapper mapper = primaryTable.idColumns.get(ci).getPropertyMapper();
      return mapper.getPropertyType();
   }

   public DBColumnType getIdDBColumnType(int ci) {
      return primaryTable.idColumns.get(ci).getDBColumnType();
   }

   public Object getIdPropertyType(int ci) {
      return primaryTable.idColumns.get(ci).getPropertyType();
   }

   public List<IdPropertyDescriptor> getIdProperties() {
      if (baseType != null)
         return baseType.getIdProperties();
      return primaryTable.idColumns;
   }

   public TableDescriptor getPrimaryTable() {
      if (baseType != null)
         return baseType.getPrimaryTable();
      return primaryTable;
   }

   public void init() {
      if (initialized)
         return;

      super.init();
      if (baseType != null)
         baseType.init();

      // There might be reverse properties in these tables that need to be started here
      if (auxTables != null) {
         for (TableDescriptor auxTable:auxTables) {
            DBTypeDescriptor propType = auxTable.reverseProperty == null ? this : auxTable.reverseProperty.dbTypeDesc;
            for (DBPropertyDescriptor prop:auxTable.columns) {
               prop.init(prop.dbTypeDesc == null ? propType : prop.dbTypeDesc, auxTable);
            }
         }
      }
      if (multiTables != null) {
         for (TableDescriptor multiTable:multiTables) {
            DBTypeDescriptor propType = multiTable.reverseProperty == null ? this : multiTable.reverseProperty.dbTypeDesc;
            for (DBPropertyDescriptor prop:multiTable.columns)
               prop.init(prop.dbTypeDesc == null ? propType : prop.dbTypeDesc, multiTable);
         }
      }

      if (queries != null) {
         for (BaseQueryDescriptor fbDesc: queries)
            if (!fbDesc.typesInited())
               fbDesc.initTypes(typeDecl);
      }
   }

   public void resolve() {
      if (resolved)
         return;
      if (!initialized)
         init();
      resolved = true;
      if (baseType != null)
         baseType.resolve();

      /* Resolve the property descriptor's owner type and reference types, reverse properties now that all types have been initialized */
      for (DBPropertyDescriptor prop:allDBProps) {
         prop.dbTypeDesc = resolvePropTypeDesc(prop);
         prop.resolve();
      }
      // There might be reverse properties in these tables that need to be started here
      if (auxTables != null) {
         for (TableDescriptor auxTable:auxTables) {
            for (DBPropertyDescriptor prop:auxTable.columns) {
               prop.dbTypeDesc = resolvePropTypeDesc(prop);
               prop.resolve();
            }
         }
      }
      if (multiTables != null) {
         for (TableDescriptor multiTable:multiTables) {
            for (DBPropertyDescriptor prop:multiTable.columns) {
               prop.dbTypeDesc = resolvePropTypeDesc(prop);
               prop.resolve();
            }
         }
      }
   }

   private DBTypeDescriptor resolvePropTypeDesc(DBPropertyDescriptor prop) {
      if (runtimeMode && prop.ownerTypeName != null && !prop.ownerTypeName.equals(getTypeName())) {
         return DBTypeDescriptor.getByName(prop.ownerTypeName, false);
      }
      else {
         if (prop.dbTypeDesc != null)
            return prop.dbTypeDesc;
         return this;
      }
   }

   public void start() {
      if (started)
         return;
      if (!resolved)
         resolve();
      started = true;
      if (baseType != null)
         baseType.start();

      if (multiTables != null) {
         for (TableDescriptor multiTable:multiTables) {
            DBPropertyDescriptor revProp = multiTable.reverseProperty;
            if (multiTable.reverseProperty != null) {
               for (DBPropertyDescriptor revPropCol:multiTable.columns) {
                  if (revPropCol.dbTypeDesc == null)
                     revPropCol.dbTypeDesc = revProp.refDBTypeDesc;
               }
            }
         }
      }

      if (dataSource == null) {
         dataSource = !runtimeMode ? null : DataSourceManager.getDBDataSource(dataSourceName);
         if (dataSource == null) {
            if (runtimeMode)
               throw new IllegalArgumentException("No data source: " + dataSourceName);
         }
         else {
            if (dataSource.readOnly)
               dbReadOnly = true;
            if (dataSource.dbDisabled)
               dbDisabled = true;
         }
      }

      for (DBPropertyDescriptor prop:allDBProps) {
         prop.start();
      }
      // There might be reverse properties in these tables that need to be started here
      if (auxTables != null) {
         for (TableDescriptor auxTable:auxTables) {
            for (DBPropertyDescriptor prop:auxTable.columns) {
               prop.start();
            }
         }
      }
      if (multiTables != null) {
         for (TableDescriptor multiTable:multiTables) {
            for (DBPropertyDescriptor prop:multiTable.columns)
               prop.start();
         }
      }
      initFetchGroups();
   }

   public void activate() {
      if (activated)
         return;
      activated = true;
      if (baseType != null)
         baseType.activate();
      for (DBQuery query:selectQueriesList)
         query.activate();
   }

   public void addReverseProperty(DBPropertyDescriptor reverseProp) {
      if (reverseProps == null)
         reverseProps = new ArrayList<DBPropertyDescriptor>();
      reverseProps.add(reverseProp);
   }

   public void initDBObject(DBObject obj) {
      // This is called from 'init' right when we create the instance so it will be called for transient and
      // prototypes so need the corresponding removeReverseListener call when the object is stopped.
      if (baseType != null)
         baseType.initDBObject(obj);
      // These are properties in this type that have bi-directional relationships to properties in other types
      // We need to listen to change in this object on these properties in order to keep those other properties in sync
      // with those changes.
      if (reverseProps != null) {
         Object inst = obj.getInst();
         for (int i = 0; i < reverseProps.size(); i++) {
            DBPropertyDescriptor reverseProp = reverseProps.get(i);
            // Already added this one from the base type
            if (baseType != null && baseType.reverseProps.contains(reverseProp))
               continue;
            Object curVal = DynUtil.getPropertyValue(inst, reverseProp.propertyName);
            ReversePropertyListener listener = new ReversePropertyListener(obj, curVal, reverseProp);
            Bind.addListener(inst, reverseProp.getPropertyMapper(), listener, IListener.VALUE_CHANGED_MASK);
         }
      }
   }

   private SelectGroupQuery initQuery(String selectGroup, List<String> props, boolean multiRow) {
      if (selectGroup == null)
         selectGroup = defaultFetchGroup;
      SelectGroupQuery groupQuery =  new SelectGroupQuery(this, props, selectGroup);
      // Add the tables to select all of the properties requested in this query - the 'selectGroup' but turn it into a 'multiRow' query
      groupQuery.addSelectGroup(selectGroup, multiRow);
      // Because this is a query, need to be sure we select the id property first in the main query
      groupQuery.queries.get(0).insertIdProperty();
      return groupQuery;
   }

   private void addPropBindingTables(SelectGroupQuery groupQuery, DBObject proto, List<String> props) {
      int numProps = props == null ? 0 : props.size();

      // First pass over the properties to gather the list of data sources and tables for this query.
      for (int i = 0; i < numProps; i++) {
         String propName = props.get(i);
         appendPropBindingTables(groupQuery,this, proto, propName);
      }
   }

   private void addPropsToQuery(SelectGroupQuery groupQuery, DBObject proto, List<String> props, boolean first) {
      addPropBindingTables(groupQuery, proto, props);
      int numProps = props == null ? 0 : props.size();

      if (!first && groupQuery.curQuery == null)
         System.err.println("*** Error - invalid value of 'first' to addPropsToQuery");

      addPropListToQuery(groupQuery, proto, props, QCombine.And, first);

      addBaseTypeClause(groupQuery, first && numProps == 0);
   }

   private void addPropListToQuery(SelectGroupQuery groupQuery, DBObject proto, List<String> props, QCombine operator, boolean first) {
      int numProps = props == null ? 0 : props.size();
      // Second pass - build the where clause query string
      for (int i = 0; i < numProps; i++) {
         String propName = props.get(i);
         if (!first)
            groupQuery.curQuery.whereAppend(" " + operator.getSQLOperator() + " ");
         appendPropToWhereClause(groupQuery,  this, proto, CTypeUtil.getPackageName(propName), propName, numProps > 1, QCompare.Equals, true, true, null);
         first = false;
      }
   }

   private void addBaseTypeClause(SelectGroupQuery groupQuery, boolean first) {
      if (baseType != null) {
         int[] typeIds = getTypeIdList();
         if (typeIds != null && typeIds.length > 0) {
            DBPropertyDescriptor typeIdProp = getTypeIdProperty();
            SelectQuery query = groupQuery.findQueryForProperty(typeIdProp, true);
            if (!first)
               query.whereAppend(" AND ");
            query.appendWhereColumn(null, typeIdProp);
            query.whereAppend(" IN (");
            for (int i = 0; i < typeIds.length; i++) {
               if (i != 0)
                  query.whereAppend(", ");
               query.whereAppend(String.valueOf(typeIds[i]));
            }
            query.whereAppend(")");
         }
         else
            DBUtil.error("Base type but no concrete types for query on type: " + this);
         // If this is a sub-type, add 'and db_type_id = our-type-id' here
      }
   }

   private void addParamValues(SelectGroupQuery groupQuery, DBObject proto, List<String> props, QCombine operator) {
      int numProps = props == null ? 0 : props.size();
      // Third pass - for each execution of the query, get the paramValues
      for (int i = 0; i < numProps; i++) {
         String prop = props.get(i);
         if (i != 0 && groupQuery.curQuery.logSB != null)
            groupQuery.curQuery.logSB.append(" " + operator.getSQLOperator() + " ");
         appendPropParamValues(groupQuery, this, proto, prop, numProps > 1, QCompare.Equals, true, null);
      }
   }

   /**
    * The core "declarative query" method to return objects for this type matching a prototype object and list of
    * protoProp names. The query returns matching objects for all properties (i.e.
    * 'where clause' using 'and' for all properties in the list). Optionally provide an orderByProps list
    * for the list of properties used in sorting the result. Use "-propName" for a descending order sort on that property and
    * start and max properties for limit/offset in the result list.
    */
   public List<? extends IDBObject> matchQuery(DBObject proto, List<String> protoProps, String selectGroup, List<String> orderByProps, int startIx, int maxResults) {
      SelectGroupQuery groupQuery = initQuery(selectGroup, protoProps, true);
      addPropsToQuery(groupQuery, proto, protoProps, true);
      addParamValues(groupQuery, proto, protoProps, QCombine.And);

      groupQuery.setQueryAttributes(orderByProps, startIx, maxResults);

      DBTransaction curTx = DBTransaction.getOrCreate();
      return groupQuery.runQuery(curTx, proto.getDBObject());
   }

   public IDBObject matchOne(DBObject proto, List<String> props, String selectGroup) {
      SelectGroupQuery groupQuery = initQuery(selectGroup, props,  true);
      addPropsToQuery(groupQuery, proto, props, true);
      addParamValues(groupQuery, proto, props, QCombine.And);

      DBTransaction curTx = DBTransaction.getOrCreate();
      return groupQuery.matchOne(curTx, proto.getDBObject());
   }


   public List<IDBObject> mergeResultLists(List<IDBObject> primary, List<IDBObject> other) {
      int osz;
      if (other == null || (osz = other.size()) == 0)
         return primary;
      int psz;
      if (primary == null || (psz = primary.size()) == 0)
         return other;

      List<IDBObject> res;
      List<IDBObject> toApply;
      boolean resIsPrimary;
      if (osz > psz) {
         res = other;
         toApply = primary;
         resIsPrimary = false;
      }
      else {
         res = primary;
         toApply = other;
         resIsPrimary = true;
      }
      Map<Object,IDBObject> applyIndex = new HashMap<Object,IDBObject>();
      for (IDBObject applyEnt:toApply)
         applyIndex.put(applyEnt.getDBId(), applyEnt);
      for (int i = 0; i < res.size(); i++) {
         IDBObject resEnt = res.get(i);
         Object resId = resEnt.getDBId();
         IDBObject toMerge = applyIndex.remove(resId);
         if (toMerge != null) {
            if (!resIsPrimary) {
               res.set(i, toMerge);
            }
         }
      }
      for (IDBObject applyEnt:toApply) {
         if (applyIndex.get(applyEnt.getDBId()) != null) {
            // TODO: if sorting, insert this into the right location in the resulting list
            res.add(applyEnt);
         }
      }
      return res;
   }

   public List<IDBObject> queryCache(DBObject proto, List<String> props, DBTypeDescriptor fromType) {
      if (baseType != null) {
         return baseType.queryCache(proto, props, fromType == null ? this : fromType);
      }
      if (typeInstances == null)
         return null;
      ArrayList<IDBObject> res = new ArrayList<IDBObject>();
      List<Object> protoVals = new ArrayList<Object>();
      int numProps = props.size();
      for (int i = 0; i < numProps; i++) {
         Object protoVal = proto.getPropertyInPath(props.get(i));
         protoVals.add(protoVal);
      }
      for (IDBObject inst: typeInstances.values()) {
         if (fromType != null && !DynUtil.instanceOf(inst, fromType.typeDecl))
            continue;
         DBObject dbObj = (DBObject) inst.getDBObject();
         boolean matched = true;
         for (int i = 0; i < numProps; i++) {
            Object propVal = dbObj.getPropertyInPath(props.get(i));
            if (!DynUtil.equalObjects(propVal, protoVals.get(i))) {
               matched = false;
               break;
            }
         }
         if (matched)
            res.add(inst);
      }
      return res;
   }

   private void appendDBPropParamValue(SelectQuery curQuery, String parentProp, DBPropertyDescriptor dbProp, StringBuilder logSB, QCompare compareOp, Object propValue, Object compareVal) {
      if (logSB != null) {
         if (compareOp != null) {
            if (propValue == null) {
               if (compareOp == QCompare.NotEquals)
                  logSB.append(dbProp.columnName + " IS NOT NULL");
               else if (compareOp == QCompare.Equals)
                  logSB.append(dbProp.columnName + " IS NULL");
               else
                  throw new IllegalArgumentException("Invalid null database value comparison");
            }
         }
         if (dbProp.dynColumn)
            curQuery.appendJSONLogWhereColumn(logSB, dbProp.getTableName(), DBTypeDescriptor.DBDynPropsColumnName, dbProp.propertyName);
         else if (compareOp == null || propValue != null)
            curQuery.appendLogWhereColumn(logSB, parentProp, dbProp);
      }
      if (compareOp != null) {
         if (propValue != null) {
            if (logSB != null) {
               if (compareOp == QCompare.Match) {
                  logSB.append(" ILIKE ");
                  DBUtil.appendVal(logSB, propValue, null, null);
               }
               else if (compareOp == QCompare.In) {
                  logSB.append(" IN (");
                  int numValues = DynUtil.getArrayLength(compareVal);
                  for (int i = 0; i < numValues; i++) {
                     if (i != 0)
                        logSB.append(", ");
                     DBUtil.appendVal(logSB, DynUtil.getArrayElement(compareVal, i), null, null);
                  }
                  logSB.append(")");
               }
               else {
                  logSB.append(" ");
                  logSB.append(compareOp.sqlOp);
                  logSB.append(" ");
                  DBUtil.appendVal(logSB, propValue, null, null);
               }
            }
            if (compareOp == QCompare.In) {
               int numValues = DynUtil.getArrayLength(compareVal);
               List<Object> pvs = curQuery.paramValues;
               List<DBColumnType> pts = curQuery.paramTypes;
               DBColumnType pt = dbProp.getDBColumnType();
               for (int i = 0; i < numValues; i++) {
                  pvs.add(DynUtil.getArrayElement(compareVal, i));
                  pts.add(pt);
               }
            }
            else {
               curQuery.paramValues.add(propValue);
               curQuery.paramTypes.add(dbProp.getDBColumnType());
            }
         }
      }
   }

   private void appendJSONPropParamValue(SelectQuery curQuery, DBPropertyDescriptor dbProp, StringBuilder logSB, QCompare compareOp, Object propValue, String propPath) {
      if (logSB != null) {
         if (compareOp.sqlOp != null) {
            DBUtil.appendVal(logSB, propValue, dbProp.getDBColumnType(), dbProp.refDBTypeDesc);
            logSB.append(" ");
            logSB.append(compareOp.sqlOp);
            logSB.append(" ");
         }
         curQuery.appendJSONLogWhereColumn(logSB, dbProp.getTableName(), dbProp.columnName, propPath);
      }
      if (compareOp != null) {
         if (logSB != null && compareOp.sqlOp == null) {
            if (compareOp == QCompare.Match) {
               logSB.append(" ILIKE ");
               DBUtil.appendVal(logSB, propValue, dbProp.getDBColumnType(), dbProp.refDBTypeDesc);
            }
            else if (compareOp == QCompare.In) {
               logSB.append(" IN (");
               int numValues = DynUtil.getArrayLength(propValue);
               for (int i = 0; i < numValues; i++) {
                  if (i != 0)
                     logSB.append(", ");
                  DBUtil.appendVal(logSB, DynUtil.getArrayElement(propValue, i), null, null);
               }
               logSB.append(")");
            }
         }
         curQuery.paramValues.add(propValue);
         curQuery.paramTypes.add(dbProp.getSubDBColumnType(propPath));
      }
   }

   private void appendPropParamValues(SelectGroupQuery groupQuery, DBTypeDescriptor curTypeDesc, DBObject curObj,
                                      String propNamePath, boolean needsParens, QCompare compareOp, boolean useProtoVal, Object compareVal) {
      DBPropertyDescriptor dbProp = curTypeDesc.getPropertyDescriptor(propNamePath);
      Object propValue = curObj.getPropertyInPath(propNamePath);
      SelectQuery curQuery = groupQuery.curQuery;
      StringBuilder logSB = curQuery.logSB;
      if (dbProp != null) {
         appendDBPropParamValue(curQuery, CTypeUtil.getPackageName(propNamePath), dbProp, logSB, compareOp, propValue, compareVal);
      }
      else {
         String[] propNames = StringUtil.split(propNamePath, '.');
         int pathLen = propNames.length;
         for (int i = 0; i < pathLen; i++) {
            String propName = propNames[i];
            DBPropertyDescriptor pathProp = curTypeDesc.getPropertyDescriptor(propName);
            if (pathProp != null) {
               if (pathProp.getDBColumnType() == DBColumnType.Json) {
                  StringBuilder pathRes = getPathRes(propNames, i+1);
                  appendJSONPropParamValue(curQuery, pathProp, logSB, compareOp, propValue, pathRes.toString());
                  return;
               }
            }
            if (i == 0) {
               DestinationListener binding = Bind.getBinding(curObj.getInst(), propName);
               if (binding == null) {
                  throw new IllegalArgumentException("No column or binding for property: " + this + "." + propName);
               }

               int closeParenCt = 0;
               if (needsParens && logSB != null) {
                  logSB.append("(");
                  closeParenCt = 1;
               }

               Object inst = curObj.getInst();

               Object propVal = useProtoVal ? curObj.getPropertyInPath(propName) : compareVal;
               Object propType = DynUtil.getPropertyType(DynUtil.getType(inst), propName);
               boolean needsInnerParen = false;
               if ((propType == Boolean.class || propType == Boolean.TYPE)) {
                  boolean boolVal = (Boolean) propVal;
                  if ((binding instanceof UnaryBinding && ((UnaryBinding) binding).getOperator().equals("!")))
                     boolVal = !boolVal; // TODO: is this right?

                  if (compareOp == QCompare.NotEquals)
                     boolVal = !boolVal;

                  // For boolean properties we could write it out as: TRUE = (a > b) but we can simplify that
                  // to just the RHS. For
                  if (logSB != null && !boolVal) {
                     logSB.append("NOT ");
                     needsInnerParen = true;
                  }
               }
               else {
                  curQuery.paramValues.add(propVal);

                  DBColumnType propColType = DBColumnType.fromJavaType(propType);
                  curQuery.paramTypes.add(propColType);

                  if (logSB != null) {
                     DBUtil.appendVal(logSB, propVal, propColType, null);
                     logSB.append(" = ");
                  }
               }

               if (logSB != null && needsInnerParen) {
                  logSB.append("(");
                  closeParenCt++;
               }

               appendParamValues(groupQuery, curTypeDesc, curObj, binding);
               if (logSB != null) {
                  while (closeParenCt-- > 0)
                     logSB.append(")");
               }
            }
         }
      }
   }

   private void appendParamValues(SelectGroupQuery groupQuery, DBTypeDescriptor curTypeDesc, DBObject curObj, IBinding binding) {
      if (binding instanceof IBeanMapper) {
         IBeanMapper propMapper = (IBeanMapper) binding;
         String propName = propMapper.getPropertyName();
         appendPropParamValues(groupQuery, curTypeDesc, curObj, propName, true, null, true, null);
      }
      else if (binding instanceof ConditionalBinding) {
         ConditionalBinding cond = (ConditionalBinding) binding;
         IBinding[] paramBindings = cond.getBoundParams();
         String operator = cond.operator;
         for (int i = 0; i < paramBindings.length; i++) {
            IBinding paramBinding = paramBindings[i];
            if (i != 0) {
               SelectQuery curQuery = groupQuery.curQuery;
               if (paramBinding instanceof ConstantBinding && ((ConstantBinding) paramBinding).getConstantValue() == null) {
                  StringBuilder logSB = curQuery.logSB;
                  if (logSB != null) {
                     if (operator.equals("=="))
                        logSB.append(" IS NULL");
                     else if (operator.equals("!="))
                        logSB.append(" IS NOT NULL");
                  }
                  continue;
               }
               else {
                  StringBuilder logSB = curQuery.logSB;
                  if (logSB != null) {
                     logSB.append(" ");
                     logSB.append(DBUtil.cvtJavaToSQLOperator(cond.operator).toString());
                     logSB.append(" ");
                  }
               }
            }
            appendParamValues(groupQuery, curTypeDesc, curObj, paramBinding);
         }
      }
      else if (binding instanceof VariableBinding) {
         VariableBinding varBind = (VariableBinding) binding;
         int numInChain = varBind.getNumInChain();
         SelectQuery curQuery = groupQuery.curQuery;
         DBPropertyDescriptor queryProp = getDBColumnProperty(varBind);
         // This is an a.b.c that corresponds to a column in the DB
         if (queryProp != null) {
            //Object propValue = DynUtil.getPropertyValue(curObj.getInst(), queryProp.propertyName);
            Object propValue = null; // Do we need this at all with compareOp == null - if it.s an a.b it might not be defined since we don't set 'a' in this case
            appendDBPropParamValue(curQuery, getParentPropertyName(varBind), queryProp, curQuery.logSB, null, propValue, null);
         }
         else {
            Object lastBinding = varBind.getChainElement(numInChain-1);
            if (lastBinding instanceof MethodBinding) {
               if (numInChain == 2) {
                  // This is var.method(...)
                  MethodBinding methBind = (MethodBinding) lastBinding;
                  String methName = DynUtil.getMethodName(methBind.getMethod());
                  IBinding[] boundParams = methBind.getBoundParams();
                  // var.equals(...)
                  if (methName.equals("equals") && boundParams.length == 1) {
                     appendPropParamValues(groupQuery, curTypeDesc, curObj, getPathPropertyName(varBind, 0), false, null, true, null);
                     if (curQuery.logSB != null)
                        curQuery.logSB.append(" = ");
                     appendParamValues(groupQuery, curTypeDesc, curObj, boundParams[0]);
                     return;
                  }
               }
               throw new UnsupportedOperationException("Unhandled method binding");
            }
            else {
               for (int i = 0; i < numInChain; i++) {
                  Object nextInChain = varBind.getChainElement(i);
                  // The JSON property case
                  if (nextInChain instanceof IBeanMapper) {
                     IBeanMapper nextMapper = (IBeanMapper) nextInChain;
                     DBPropertyDescriptor pathProp = curTypeDesc.getPropertyDescriptor(nextMapper.getPropertyName());
                     if (pathProp != null) {
                        if (pathProp.dynColumn) {
                           StringBuilder logSB = curQuery.logSB;
                           if (logSB != null) {
                              StringBuilder subPath = getVarBindPropPath(varBind, i + 1);
                              curQuery.appendDynLogWhereColumn(logSB, pathProp.getTableName(), pathProp, subPath.toString());
                           }
                        }
                        else if (pathProp.getDBColumnType() == DBColumnType.Json) {
                           StringBuilder logSB = curQuery.logSB;
                           if (logSB != null) {
                              StringBuilder propPath = getVarBindPropPath(varBind, i + 1);
                              curQuery.appendJSONLogWhereColumn(logSB, pathProp.getTableName(), pathProp.columnName, propPath.toString());
                           }
                        }

                     }
                     return;
                  }
               }
            }
            throw new UnsupportedOperationException("TODO: need to support more variable binding cases");
         }
      }
      else if (binding instanceof ConstantBinding) {
         ConstantBinding cbind = (ConstantBinding) binding;
         SelectQuery curQuery = groupQuery.curQuery;
         StringBuilder logSB = curQuery.logSB;
         if (logSB != null) {
            Object cval = cbind.getConstantValue();
            if (cval == null) {
               logSB.append(" IS NULL");
            }
            else if (cval instanceof CharSequence) {
               logSB.append("'");
               logSB.append(cval.toString());
               logSB.append("'");
            }
            else
               logSB.append(cval.toString());
         }
      }
      else if (binding instanceof ArithmeticBinding) {
         SelectQuery curQuery = groupQuery.curQuery;
         ArithmeticBinding abind = (ArithmeticBinding) binding;
         StringBuilder logSB = curQuery.logSB;
         if (abind.operator.equals("&")) {
            IBinding[] boundParams = abind.getBoundParams();
            appendParamValues(groupQuery, curTypeDesc, curObj, boundParams[0]);
            if (logSB != null) {
               logSB.append(" & ");
            }
            appendParamValues(groupQuery, curTypeDesc, curObj, boundParams[1]);
         }
         else
            throw new IllegalArgumentException("Unsupported arithmetic binding for appendParams");
      }
      else if (binding instanceof UnaryBinding) {
         SelectQuery curQuery = groupQuery.curQuery;
         UnaryBinding ubind = (UnaryBinding) binding;
         String op = ubind.getOperator();
         StringBuilder logSB = curQuery.logSB;
         IBinding param = ubind.getBoundParams()[0];
         if (op.equals("!")) {
            if (logSB != null)
               logSB.append(" NOT(");

            appendParamValues(groupQuery, curTypeDesc, curObj, param);

            if (logSB != null)
               logSB.append(")");
         }
         else if (op.equals("-") || op.equals("+")) {
            if (logSB != null)
               logSB.append(op);
            appendParamValues(groupQuery, curTypeDesc, curObj, param);
         }
         else
            throw new IllegalArgumentException("Unsupported unary binding operator: " + op);
      }
      else
         throw new IllegalArgumentException("Unsupported binding type for appendParams");
   }

   private void appendPropBindingTables(SelectGroupQuery groupQuery, DBTypeDescriptor curTypeDesc, DBObject curObj, String propNamePath) {
      DBPropertyDescriptor dbProp = curTypeDesc.getPropertyDescriptor(propNamePath);
      if (dbProp != null) {
         DBPropertyDescriptor parentProp = null;
         String parentPropName = CTypeUtil.getPackageName(propNamePath);
         if (parentPropName != null)
            parentProp = curTypeDesc.getPropertyDescriptor(parentPropName);
         SelectQuery newQuery = groupQuery.addProperty(parentProp, dbProp, true);
         if (newQuery != groupQuery.curQuery) {
            if (groupQuery.curQuery != null) {
               throw new UnsupportedOperationException("Add code to do join for different data sources here!");
            }
            else {
               groupQuery.curQuery = newQuery;
            }
         }
      }
      else {
         String[] propNames = StringUtil.split(propNamePath, '.');
         for (int i = 0; i < propNames.length; i++) {
            String propName = propNames[i];
            DBPropertyDescriptor pathProp = curTypeDesc.getPropertyDescriptor(propName);
            if (pathProp != null) {
               SelectQuery newQuery = groupQuery.addProperty(null, pathProp, true);
               if (newQuery != groupQuery.curQuery) {
                  if (groupQuery.curQuery != null) {
                     throw new UnsupportedOperationException("Add code to do join for different data sources here!");
                  }
                  else {
                     groupQuery.curQuery = newQuery;
                  }
               }
               if (pathProp.getDBColumnType() == DBColumnType.Json) {
                  return;
               }
               if (pathProp.dynColumn)
                  return;
               throw new UnsupportedOperationException("Unhandled query property");
            }
            if (i == 0) {
               DestinationListener propBinding = Bind.getBinding(curObj.getInst(), propName);
               if (propBinding == null) {
                  throw new IllegalArgumentException("No column or binding for property: " + this + "." + propName);
               }
               appendBindingTables(groupQuery, curTypeDesc, curObj, propBinding);
            }
            else
               throw new UnsupportedOperationException("Unhandled path query");
         }
      }
   }

   DBPropertyDescriptor getDBColumnProperty(VariableBinding  varBind) {
      int pathLen = varBind.getNumInChain();
      DBTypeDescriptor curTypeDesc = this;
      DBPropertyDescriptor curProp = null;
      for (int i = 0; i < pathLen; i++) {
         Object pathProp = varBind.getChainElement(i);
         if (pathProp instanceof String || pathProp instanceof IBeanMapper) {
            String nextPropName = pathProp instanceof String ? (String) pathProp : ((IBeanMapper) pathProp).getPropertyName();
            curProp = curTypeDesc.getPropertyDescriptor(nextPropName);
            if (curProp == null) {
               return null;
            }
            else {
               if (i != pathLen - 1) {
                  curTypeDesc = curProp.refDBTypeDesc;
                  if (curTypeDesc == null) {
                     return null;
                  }
               }
            }
         }
         else
            return null;
      }
      return curProp;
   }

   String getParentPropertyName(VariableBinding  varBind) {
      int pathLen = varBind.getNumInChain();
      StringBuilder res = null;
      for (int i = 0; i < pathLen-1; i++) {
         Object pathProp = varBind.getChainElement(i);
         if (pathProp instanceof String || pathProp instanceof IBeanMapper) {
            String nextPropName = pathProp instanceof String ? (String) pathProp : ((IBeanMapper) pathProp).getPropertyName();
            if (res == null)
               res = new StringBuilder();
            else
               res.append(".");
            res.append(nextPropName);
         }
         else
            return null;
      }
      return res == null ? null : res.toString();
   }

   private static String getPathPropertyName(VariableBinding varBind, int ix) {
      Object chainProp = varBind.getChainElement(ix);
      if (chainProp instanceof String || chainProp instanceof IBeanMapper) {
         String propName = chainProp instanceof String ? (String) chainProp : ((IBeanMapper) chainProp).getPropertyName();
         return propName;
      }
      return null;
   }

   private void appendBindingTables(SelectGroupQuery groupQuery, DBTypeDescriptor startTypeDesc, DBObject curObj, IBinding binding) {
      if (binding instanceof IBeanMapper) {
         appendPropBindingTables(groupQuery, startTypeDesc, curObj, ((IBeanMapper) binding).getPropertyName());
      }
      else if (binding instanceof VariableBinding) {
         VariableBinding varBind = (VariableBinding) binding;
         int numInChain = varBind.getNumInChain();
         DBTypeDescriptor curTypeDesc = startTypeDesc;
         DBPropertyDescriptor queryProp = getDBColumnProperty(varBind);
         DBPropertyDescriptor lastPropDesc = null;
         // Does this a.b.c map to a column for the entire path including 'c'?  If so, add joins for each of the intermediate
         // property tables
         if (queryProp != null) {
            for (int i = 0; i < numInChain; i++) {
               Object chainProp = varBind.getChainElement(i);
               if (chainProp instanceof String || chainProp instanceof IBeanMapper) {
                  String nextPropName = getPathPropertyName(varBind, i);
                  DBPropertyDescriptor nextPropDesc = curTypeDesc.getPropertyDescriptor(nextPropName);
                  SelectQuery newQuery = groupQuery.addProperty(lastPropDesc, nextPropDesc, true);
                  if (newQuery != groupQuery.curQuery) {
                     if (groupQuery.curQuery != null) {
                        throw new UnsupportedOperationException("Add code to do join for different data sources here!");
                     }
                     else {
                        groupQuery.curQuery = newQuery;
                     }
                  }
                  lastPropDesc = nextPropDesc;
                  curTypeDesc = nextPropDesc.refDBTypeDesc;
               }
               else if (chainProp instanceof IBinding) {
                  appendBindingTables(groupQuery, curTypeDesc, curObj, (IBinding) chainProp);
               }
            }
         }
         else {
            if (numInChain == 1) {
               String propName = getPathPropertyName(varBind, 0);
               if (propName != null) {
                  DestinationListener propBinding = Bind.getBinding(curObj.getInst(), propName);
                  if (propBinding == null) {
                     throw new IllegalArgumentException("No column or binding for property: " + this + "." + propName);
                  }
                  appendBindingTables(groupQuery, curTypeDesc, curObj, propBinding);
               }
               else {
                  // Could be a method with no prefix
                  throw new UnsupportedOperationException("Unhandled binding case");
               }
            }
            else {
               DBPropertyDescriptor lastProp = null;
               for (int i = 0; i < numInChain; i++) {
                  Object chainProp = varBind.getChainElement(i);
                  if (chainProp instanceof IBeanMapper) {
                     IBeanMapper nextProp = (IBeanMapper) chainProp;
                     String propName = nextProp.getPropertyName();
                     DBPropertyDescriptor nextPropDesc = curTypeDesc.getPropertyDescriptor(propName);
                     if (nextPropDesc != null && nextPropDesc.getDBColumnType() == DBColumnType.Json) {
                        SelectQuery newQuery = groupQuery.addProperty(lastProp, nextPropDesc, true);
                        if (newQuery != groupQuery.curQuery) {
                           if (groupQuery.curQuery != null) {
                              throw new UnsupportedOperationException("Add code to do join for different data sources here!");
                           }
                           else {
                              groupQuery.curQuery = newQuery;
                           }
                        }
                        return; // No more tables in this expression
                     }
                     lastPropDesc = nextPropDesc;
                  }
                  else if (chainProp instanceof IBinding)
                     appendBindingTables(groupQuery, curTypeDesc, curObj, (IBinding) chainProp);
                  else
                     throw new IllegalArgumentException("Unsupported binding path element");
               }
            }
         }
      }
      else if (binding instanceof AbstractMethodBinding) {
         AbstractMethodBinding methBind = (AbstractMethodBinding) binding;
         for (IBinding boundParam:methBind.getBoundParams()) {
            appendBindingTables(groupQuery, startTypeDesc, curObj, boundParam);
         }
      }
   }

   private void appendBindingToWhereClause(SelectGroupQuery groupQuery, DBTypeDescriptor curTypeDesc, DBObject curObj, IBinding binding) {
      if (binding instanceof IBeanMapper) {
         IBeanMapper propMapper = (IBeanMapper) binding;
         String propName = propMapper.getPropertyName();
         appendPropToWhereClause(groupQuery, curTypeDesc, curObj, null, propName, true, null, false, true, null);
      }
      else if (binding instanceof ConditionalBinding) {
         ConditionalBinding cond = (ConditionalBinding) binding;
         IBinding[] params = cond.getBoundParams();
         SelectQuery curQuery = groupQuery.curQuery;
         String operator = cond.operator;
         boolean needsParen = curQuery != null;
         if (needsParen) {
            curQuery.whereAppend("(");
         }
         for (int i = 0; i < params.length; i++) {
            IBinding param = params[i];
            if (i != 0) {
               if (param instanceof ConstantBinding && ((ConstantBinding) param).getConstantValue() == null) {
                  if (operator.equals("=="))
                     groupQuery.curQuery.whereAppend(" IS NULL");
                  else if (operator.equals("!="))
                     groupQuery.curQuery.whereAppend(" IS NOT NULL");
                  else
                     System.err.println("*** Unsupported operator with null comparison in db query: " + operator);
                  continue;
               }
               else {
                  groupQuery.curQuery.whereAppend(" ");
                  groupQuery.curQuery.whereAppend(DBUtil.cvtJavaToSQLOperator(operator).toString());
                  groupQuery.curQuery.whereAppend(" ");
               }
            }
            appendBindingToWhereClause(groupQuery, curTypeDesc, curObj, param);
         }
         if (needsParen) {
            curQuery.whereAppend(")");
         }
      }
      else if (binding instanceof VariableBinding) {
         VariableBinding varBind = (VariableBinding) binding;
         int numInChain = varBind.getNumInChain();
         SelectQuery curQuery = groupQuery.curQuery;
         DBPropertyDescriptor queryProp = getDBColumnProperty(varBind);
         if (queryProp != null) {
            appendPropToWhereClause(groupQuery, queryProp.dbTypeDesc, curObj, getParentPropertyName(varBind), queryProp.propertyName, false, null, false, true, null);
         }
         else {
            if (numInChain == 2) {
               // TODO: generalize this case into the code below for handling arbitrary a.b.c chains
               Object lastBinding = varBind.getChainElement(1);
               if (lastBinding instanceof MethodBinding) {
                  MethodBinding methBind = (MethodBinding) lastBinding;
                  String methName = DynUtil.getMethodName(methBind.getMethod());
                  IBinding[] boundParams = methBind.getBoundParams();
                  if (methName.equals("equals") && boundParams.length == 1) {
                     appendPropToWhereClause(groupQuery, curTypeDesc, curObj, null, getPathPropertyName(varBind, 0), false, null, false, true, null);
                     curQuery.whereAppend(" = ");
                     appendBindingToWhereClause(groupQuery, curTypeDesc, curObj, boundParams[0]);
                     return;
                  }
               }
            }
            for (int i = 0; i < numInChain; i++) {
               Object chainProp = varBind.getChainElement(i);
               if (chainProp instanceof IBeanMapper) {
                  IBeanMapper nextProp = (IBeanMapper) chainProp;
                  String propName = nextProp.getPropertyName();
                  DBPropertyDescriptor nextPropDesc = curTypeDesc.getPropertyDescriptor(propName);
                  if (nextPropDesc != null) {
                     // This is a property that's stored in the db_dyn_props JSON column of this table
                     if (nextPropDesc.dynColumn) {
                        StringBuilder subPropPath = getVarBindPropPath(varBind, i + 1);
                        curQuery.appendDynWhereColumn(nextPropDesc.getTableName(), nextPropDesc, subPropPath.toString());
                     }
                     else if (nextPropDesc.getDBColumnType() == DBColumnType.Json) {
                        StringBuilder propPath = getVarBindPropPath(varBind, i + 1);
                        curQuery.appendJSONWhereColumn(nextPropDesc.getTableName(), nextPropDesc.columnName, propPath.toString());
                        return;
                     }
                  }
                  else {
                     // For numInChain == 1, this could just be a property that has another rule we need to translate into a query
                     if (numInChain == 1)
                        appendBindingToWhereClause(groupQuery, curTypeDesc, curObj, nextProp);
                     else
                        throw new IllegalArgumentException("Unsupported binding in query");
                  }
               }
               else if (chainProp instanceof IBinding)
                  appendBindingToWhereClause(groupQuery, curTypeDesc, curObj, (IBinding) chainProp);
               else
                  throw new UnsupportedOperationException("Error should have been thrown before");
            }
         }
      }
      else if (binding instanceof ConstantBinding) {
         ConstantBinding cbind = (ConstantBinding) binding;
         Object cval = cbind.getConstantValue();
         SelectQuery curQuery = groupQuery.curQuery;
         if (cval == null)
            curQuery.whereAppend(" IS NULL");
         StringBuilder newWhere = new StringBuilder();
         DBUtil.appendConstant(newWhere, cval);
         curQuery.whereAppend(newWhere.toString());
      }
      else if (binding instanceof ArithmeticBinding) {
         SelectQuery curQuery = groupQuery.curQuery;
         ArithmeticBinding abind = (ArithmeticBinding) binding;
         if (abind.operator.equals("&")) {
            IBinding[] boundParams = abind.getBoundParams();
            appendBindingToWhereClause(groupQuery, curTypeDesc, curObj, boundParams[0]);
            curQuery.whereAppend(" & ");
            appendBindingToWhereClause(groupQuery, curTypeDesc, curObj, boundParams[1]);
         }
         else
            throw new IllegalArgumentException("Unsupported arithmetic binding for query");
      }
      else if (binding instanceof UnaryBinding) {
         UnaryBinding ubind = (UnaryBinding) binding;
         String op = ubind.getOperator();
         if (op.equals("++") || op.equals("--"))
            throw new IllegalArgumentException("Unsupported operator for query: " + op);
         SelectQuery curQuery = groupQuery.curQuery;
         IBinding param = ubind.getBoundParams()[0];

         if (op.equals("!")) {
            curQuery.whereAppend(" NOT(");

            appendBindingToWhereClause(groupQuery, curTypeDesc, curObj, param);

            curQuery.whereAppend(")");
         }
         else if (op.equals("-")) {
            curQuery.whereAppend(" -");
            appendBindingToWhereClause(groupQuery, curTypeDesc, curObj, param);
         }
         else if (op.equals("+")) {
            appendBindingToWhereClause(groupQuery, curTypeDesc, curObj, param);
         }
         else
            throw new IllegalArgumentException("Unsupported operator for query: " + op);
      }
      else
         throw new IllegalArgumentException("Unsupported binding type for query");
   }

   private StringBuilder getVarBindPropPath(VariableBinding varBind, int startIx) {
      StringBuilder propPath = new StringBuilder();
      int numInChain = varBind.getNumInChain();
      for (int j = startIx; j < numInChain; j++) {
         Object nextChainProp = varBind.getChainElement(j);
         if (j > startIx)
            propPath.append(".");
         if (nextChainProp instanceof IBeanMapper)
            propPath.append(((IBeanMapper) nextChainProp).getPropertyName());
      }
      return propPath;
   }

   private void appendPropToWhereClause(SelectGroupQuery groupQuery, DBTypeDescriptor curTypeDesc, DBObject curObj,
                                        String parentPropPath, String propNamePath, boolean needsParens, QCompare comparator,
                                        boolean topLevelQueryProp, boolean useProtoValue, Object compareVal) {
      SelectQuery curQuery = groupQuery.curQuery;
      DBPropertyDescriptor dbProp = curTypeDesc.getPropertyDescriptor(propNamePath);
      if (dbProp != null) {
         SelectQuery newQuery = groupQuery.findQueryForProperty(dbProp, true);
         if (newQuery == null) // should have been added in the first pass
            throw new IllegalArgumentException("Missing query for db property: " + propNamePath);

         if (newQuery != groupQuery.curQuery) {
            if (curQuery != null) {
               throw new UnsupportedOperationException("Add code to do join for different data sources here!");
            }
            else {
               curQuery = groupQuery.curQuery = newQuery;
            }
         }

         // If this is a top-level property used in the original query, the value in the protoType is used as the
         // argument. For boolean properties, a false value will negate the query for example. But for nested properties
         // we ignore the property value.
         Object propValue = useProtoValue ? (topLevelQueryProp ? curObj.getPropertyInPath(propNamePath) : null) : compareVal;
         if (comparator != null && propValue == null && parentPropPath == null && topLevelQueryProp) {
            // TODO: this should add the table name if parentPropPath is not null right?
            curQuery.whereAppend(dbProp.columnName + " IS NULL");
         }
         else {
            if (parentPropPath != null && propValue == null && comparator == QCompare.Equals)
               System.err.println("*** Funky case with is null comparison in a.b");
            curQuery.appendWhereColumn(parentPropPath, dbProp);
            if (comparator == QCompare.Match)
               curQuery.whereAppend(" ILIKE ? ");
            else if (comparator == QCompare.In) {
               curQuery.whereAppend(" IN ( ");
               int numValues = DynUtil.getArrayLength(propValue);
               for (int i = 0; i < numValues; i++) {
                  if (i != 0)
                     curQuery.whereAppend(", ");
                  curQuery.whereAppend("?");
               }
               curQuery.whereAppend(")");
            }
            else if (comparator != null) {
               curQuery.whereAppend(" " + comparator.sqlOp + " ? ");
            }
         }
      }
      else {
         String[] propNames = StringUtil.split(propNamePath, '.');
         int pathLen = propNames.length;
         for (int i = 0; i < pathLen; i++) {
            String propName = propNames[i];
            DBPropertyDescriptor pathProp = curTypeDesc.getPropertyDescriptor(propName);
            if (pathProp != null) {
               if (pathProp.getDBColumnType() == DBColumnType.Json) {
                  StringBuilder pathRes = getPathRes(propNames, i+1);
                  curQuery.appendJSONWhereColumn(pathProp.getTableName(), pathProp.columnName, pathRes.toString());
                  if (comparator == QCompare.Match) {
                     curQuery.whereAppend(" ILIKE ? ");
                  }
                  else if (comparator == QCompare.In) {
                     System.err.println("*** Error - need support for JSON property with IN query");
                  }
                  else if (comparator != null) {
                     curQuery.whereAppend(" " + comparator.sqlOp + " ? ");
                  }
                  return;
               }
            }
            if (i == 0) {
               DestinationListener binding = Bind.getBinding(curObj.getInst(), propName);

               if (binding == null) {
                  throw new IllegalArgumentException("No column or binding for property: " + this + "." + propName);
               }
               int closeParenCt = 0;
               if (needsParens) {
                  curQuery.whereAppend("(");
                  closeParenCt = 1;
               }

               Object inst = curObj.getInst();

               Object propVal = useProtoValue ? (topLevelQueryProp ? DynUtil.getPropertyValue(inst, propName) : null) : compareVal;
               Object propType = DynUtil.getPropertyType(DynUtil.getType(inst), propName);
               boolean needsInnerParen = false;
               if ((propType == Boolean.class || propType == Boolean.TYPE)) {
                  boolean boolVal = propVal == null ? Boolean.TRUE : (Boolean) propVal;

                  if (comparator == QCompare.NotEquals)
                     boolVal = !boolVal;
                  // For boolean properties we could write it out as: TRUE = (a > b) but we can simplify that
                  // to just the RHS. For
                  if (!boolVal) {
                     curQuery.whereAppend("NOT ");
                     needsInnerParen = true;
                  }
               }
               else {
                  // We'll put propVal into this parameter spot on the next pass
                  curQuery.whereAppend(" ? = ");
               }

               if (needsInnerParen) {
                  curQuery.whereAppend("(");
                  closeParenCt++;
               }

               appendBindingToWhereClause(groupQuery, curTypeDesc, curObj, binding);
               while (closeParenCt-- > 0)
                  curQuery.whereAppend(")");
            }
            else
               throw new UnsupportedOperationException("Unhandled case");
         }
      }
   }

   private StringBuilder getPathRes(String[] pathName, int startIx) {
      StringBuilder pathRes = new StringBuilder();
      int pathLen = pathName.length;
      for (int j = startIx; j < pathLen; j++) {
         if (j > startIx)
            pathRes.append(".");
         pathRes.append(pathName[j]);
      }
      return pathRes;
   }

   public DBDataSource getDataSource() {
      if (dataSource == null || !started)
         throw new IllegalArgumentException("DBTypeDescriptor being used before being started");
      return dataSource;
   }

   /**
    * Allow test or application threads to wait until the SchemaManager finishes updating/validating the schema before
    * continuing
    */
   public void waitForSchemaReady() {
      DBDataSource ds = getDataSource();
      ds.waitForReady();
   }

   public boolean getSchemaReady() {
      DBDataSource ds = getDataSource();
      return ds.getSchemaReady();
   }

   public Object namedQuery(String queryName, Object...args) {
      if (!started)
         startAndActivate();
      DBTransaction curTx = DBTransaction.getOrCreate();
      NamedQueryDescriptor namedQuery = namedQueryIndex == null ? null : namedQueryIndex.get(queryName);
      if (namedQuery != null)
         return namedQuery.execute(curTx, args);
      throw new IllegalArgumentException("No query named: " + queryName + " for type: " + getTypeName());
   }

   public DBPropertyDescriptor getTypeIdProperty() {
      if (baseType != null)
         return baseType.getTypeIdProperty();
      return typeIdProperty;
   }

   public int[] getTypeIdList() {
      if (typeIdList == null) {
         List<Integer> resList = new ArrayList<Integer>();
         if (typeId != DBUnsetTypeId && typeId != DBAbstractTypeId)
            resList.add(typeId);
         if (subTypes != null) {
            for (DBTypeDescriptor subType:subTypes) {
               int[] subTypeList = subType.getTypeIdList();
               if (subTypeList != null) {
                  for (int st:subTypeList) {
                     resList.add(st);
                  }
               }
            }
         }
         int resSz = resList.size();
         int[] res = new int[resSz];
         for (int i = 0; i < resSz; i++)  {
            res[i] = resList.get(i);
         }
         typeIdList = res;
         return res;
      }
      return null;
   }

   public StringBuilder getMetadataString() {
      StringBuilder res = new StringBuilder();
      if (typeId != -1) {
         if (typeId == DBAbstractTypeId)
            res.append(" (abstract)");
         else {
            res.append(" typeId: ");
            res.append(typeId);
         }
      }
      if (baseType != null) {
         res.append("\n      extends type: ");
         res.append(baseType.getTypeName());
      }
      if (subTypes != null && subTypes.size() > 0) {
         res.append("\n      sub-types: ");
         for (DBTypeDescriptor subTypeDesc:subTypes) {
            res.append("\n         ");
            res.append(subTypeDesc.getTypeName());
            if (subTypeDesc.typeId == DBAbstractTypeId)
               res.append(" (abstract)");
            else {
               res.append(" - typeId: ");
               res.append(subTypeDesc.typeId);
            }
         }
      }
      if (allDBProps != null) {
         boolean first = true;
         for (DBPropertyDescriptor prop:allDBProps) {
            if (prop.refDBTypeDesc != null) {
               if (first) {
                  res.append("\n      associations: ");
                  first = false;
               }
               res.append("\n         ");
               res.append(CTypeUtil.getClassName(prop.refDBTypeDesc.getTypeName()));
               if (prop.multiRow)
                  res.append("[]");
               res.append(" ");
               res.append(prop.propertyName);

               DBPropertyDescriptor revProp = prop.reversePropDesc;
               if (revProp != null) {
                  res.append(" reverse: " + CTypeUtil.getClassName(revProp.dbTypeDesc.getTypeName()) + "." + revProp.propertyName);
               }
            }
         }
      }
      if (res.length() > 0) {
         res.append("\n");
         return res;
      }
      else
         return null;
   }

   public DBTypeDescriptor getSubTypeByTypeId(int typeId) {
      if (typeId == this.typeId)
         return this;
      if (typeId == DBUnsetTypeId)
         return this;
      if (subTypesById != null)
         return subTypesById.get(typeId);
      return null;
   }

   public void updateTypeDescriptor(IDBObject newInst, DBObject dbObj) {
      // We do replace the wrapper when we do a prototype query of a base class and end up returning a subclass
      //if (dbObj.wrapper != null) {
         //if (dbObj.wrapper != newInst) {
         //
         //}
      //}
      dbObj.setWrapper(newInst, this);
      dbObj.setDBId(dbObj.dbId);
      dbObj.setPrototype(false);
      // TODO: do we need to add reversePropertyListeners here - maybe only add the new ones that might exist
      // in the new type
      dbObj.dbTypeDesc.replaceInstance(newInst);
   }

   public String getBaseTypeName() {
      if (baseType != null)
         return baseType.getBaseTypeName();
      return getTypeName();
   }

   public void enableLiveDynTypes() {
      if (!liveDynTypes) {
         liveDynTypes = true;

         Map<Object,IDBObject> instList = baseType == null ? typeInstances : baseType.typeInstances;
         if (instList != null) {
            String typeName = getTypeName();
            for (IDBObject inst:instList.values()) {
               DynUtil.addDynInstance(typeName, inst);
            }
         }
      }
   }

   /**
    * For when an object expired in the cache, but there was still a reference to it - put it back in the cache unless
    * it's already been replaced. If it has been replaced, all methods will be redirected to the replaced version.
    */
   void recacheDBObject(DBObject obj) {
      if (baseType != null) {
         baseType.recacheDBObject(obj);
         return;
      }

      synchronized (this) {
         Object dbId = obj.getDBId();
         IDBObject newInst = typeInstances.get(dbId);
         if (newInst != null) {
            obj.replacedBy = newInst;
         }
         else {
            typeInstances.put(dbId, obj.wrapper);
         }
      }
   }

   public void invalidateExpiredItems() {
      final long now = System.currentTimeMillis();
      synchronized (this) {
         typeInstances.entrySet().removeIf(new Predicate<Map.Entry<Object, IDBObject>>() {
            public boolean test(Map.Entry<Object, IDBObject> obj) {
               DBObject dbObj = (DBObject) obj.getValue().getDBObject();
               if (dbObj.instanceLastUsed + expireTimeMillis < now) {
                  stopObject(dbObj);
                  return true;
               }
               return false;
            }
         });
      }
   }
}
