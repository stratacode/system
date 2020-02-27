package sc.db;

import sc.bind.*;
import sc.dyn.DynUtil;
import sc.obj.Constant;
import sc.type.CTypeUtil;
import sc.type.IBeanMapper;

import javax.print.attribute.standard.Destination;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores the metadata for a given type in the system that represents the mapping to a persistence storage
 * layer. A given type can be persisted from one or more tables, in the same or different databases.
 * There's a primary table, that will have one row per object instance. Auxiliary tables store alternate properties
 * and can be optionally created.  Multi-tables store multi-valued properties stored with 'multiRow' mode.
 *
 * A fetchGroup allows collections of properties to be loaded at the same time. By default, a fetch group is
 * created for each table.
 */
public class DBTypeDescriptor {
   static Map<Object,DBTypeDescriptor> typeDescriptorsByType = new HashMap<Object,DBTypeDescriptor>();
   static Map<String,DBTypeDescriptor> typeDescriptorsByName = new HashMap<String,DBTypeDescriptor>();

   public static void clearAllCaches() {
      for (DBTypeDescriptor dbTypeDesc:typeDescriptorsByName.values()) {
         dbTypeDesc.clearTypeCache();
      }
   }

   public static void add(DBTypeDescriptor typeDescriptor) {
      Object typeDecl = typeDescriptor.typeDecl;
      DBTypeDescriptor oldType = typeDescriptorsByType.put(typeDecl, typeDescriptor);
      String typeName = DynUtil.getTypeName(typeDecl, false);
      DBTypeDescriptor oldName = typeDescriptorsByName.put(typeName, typeDescriptor);
      typeDescriptor.init();

      if ((oldType != null && oldType != typeDescriptor) | (oldName != null && oldName != typeDescriptor)) {
         System.err.println("Replacing type descriptor for type: " + typeName);
      }
   }

   public static DBTypeDescriptor getByType(Object type, boolean start) {
      DBTypeDescriptor res = typeDescriptorsByType.get(type);
      if (res != null) {
         if (start && !res.started)
            res.start();
         return res;
      }
      Object superType = DynUtil.getExtendsType(type);
      if (superType != Object.class)
         return getByType(superType, start);
      return null;
   }

   public static DBTypeDescriptor getByName(String typeName, boolean start) {
      DBTypeDescriptor res = typeDescriptorsByName.get(typeName);
      if (res != null) {
         if (start && !res.started) {
            res.start();
         }
      }
      return res;
   }

   // Class or ITypeDeclaration for dynamic types
   public Object typeDecl;

   public DBTypeDescriptor baseType;

   // Name for the connection to the database
   public String dataSourceName;

   public boolean queueInserts = false;
   public boolean queueDeletes = false;

   /** Set to true for types where the source has no 'id' property */
   public boolean needsAutoId = false;

   public String defaultFetchGroup;

   public ConcurrentHashMap<Object,IDBObject> typeInstances = null;

   private boolean initialized = false, started = false;

   public boolean tablesInitialized = false;

   public List<FindByDescriptor> findByQueries = null;

   /**
    * Defines the type with the id properties of the primary table in tact so it can be used to create references from other types.
    * Properties may be later added to the primary table and other tables added with initTables
    */
   public DBTypeDescriptor(Object typeDecl, DBTypeDescriptor baseType, String dataSourceName, TableDescriptor primary, List<FindByDescriptor> findByQueries) {
      this.typeDecl = typeDecl;
      this.baseType = baseType;
      this.dataSourceName = dataSourceName;
      this.primaryTable = primary;
      this.findByQueries = findByQueries;
      primary.init(this);
      primary.primary = true;

      if (primaryTable.idColumns == null || primaryTable.idColumns.size() == 0) {
         needsAutoId = true;
         primaryTable.addIdColumnProperty(new IdPropertyDescriptor("id", "id", "bigserial", true));
      }
   }

   public DBTypeDescriptor(Object typeDecl, DBTypeDescriptor baseType, String dataSourceName, TableDescriptor primary, List<TableDescriptor> auxTables, List<TableDescriptor> multiTables, List<FindByDescriptor> findByQueries, String versionPropName) {
      this(typeDecl, baseType, dataSourceName, primary, findByQueries);
      initTables(auxTables, multiTables, versionPropName);
   }

   public void initTables(List<TableDescriptor> auxTables, List<TableDescriptor> multiTables, String versionPropName) {
      tablesInitialized = true;
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
      if (versionPropName != null)
         this.versionProperty = primaryTable.getPropertyDescriptor(versionPropName);

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
      for (DBPropertyDescriptor prop:allDBProps) {
         prop.dbTypeDesc = this;
      }
   }

   void addMultiTable(TableDescriptor table) {
      if (multiTables == null)
         multiTables = new ArrayList<TableDescriptor>();
      multiTables.add(table);
   }

   void addAuxTable(TableDescriptor table) {
      if (auxTables == null)
         auxTables = new ArrayList<TableDescriptor>();
      auxTables.add(table);
   }

   public void addFindByQuery(FindByDescriptor findByDesc) {
      if (findByQueries == null)
         findByQueries = new ArrayList<FindByDescriptor>();
      findByQueries.add(findByDesc);
   }

   void initFetchGroups() {
      if (defaultFetchGroup != null)
         return;
      defaultFetchGroup = primaryTable.getJavaName();
      String firstFetchGroup = null;
      // Build up the set of 'fetchGroups' - the queries to fetch properties for a given item
      for (DBPropertyDescriptor prop:allDBProps) {
         String fetchGroup = prop.fetchGroup;
         boolean fetchable = false;
         if (prop.multiRow) {
            TableDescriptor mvTable = getMultiTableByName(prop.getTableName(), prop);
            if (mvTable == null)
               System.err.println("*** No multi-value table for property: " + prop.propertyName);
            else
               fetchable = addToFetchGroup(mvTable.getJavaName(), prop);
         }
         else {
            if (fetchGroup == null) {
               if (prop.onDemand) {
                  fetchGroup = prop.propertyName;
               }
               else {
                  TableDescriptor table = getTableByName(prop.tableName);
                  fetchGroup = table.getJavaName();
               }
            }
            fetchable = addToFetchGroup(fetchGroup, prop);
         }
         if (firstFetchGroup == null && fetchable) {
            firstFetchGroup = fetchGroup;
         }
      }
      // All properties are on-demand or in some other fetch group. Pick the first one to use as the default so that
      // we have some property to load to validate that the item exists.
      if (fetchGroups.get(defaultFetchGroup) == null) {
         if (firstFetchGroup == null)
            DBUtil.error("No main table properties for persistent type: " + this);
         else
            fetchGroups.put(defaultFetchGroup, fetchGroups.get(firstFetchGroup));
      }
   }

   public String getTypeName() {
      return DynUtil.getTypeName(typeDecl, false);
   }

   private boolean addToFetchGroup(String fetchGroup, DBPropertyDescriptor prop) {
      if (prop.isId()) // Don't fetch ids since they don't change
         return false;
      DBFetchGroupQuery query = fetchGroups.get(fetchGroup);
      if (query == null) {
         query = new DBFetchGroupQuery(this);
         fetchGroups.put(fetchGroup, query);
         addQuery(query);
      }
      query.addProperty(prop, false);
      propQueriesIndex.put(prop.propertyName, query);
      return true;
   }

   public TableDescriptor primaryTable;

   public List<TableDescriptor> auxTables;

   public List<TableDescriptor> multiTables;

   public DBPropertyDescriptor versionProperty;

   public List<DBPropertyDescriptor> allDBProps = new ArrayList<DBPropertyDescriptor>();

   public List<DBPropertyDescriptor> reverseProps = null;

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
      multiTables.add(newTable);
      return null;
   }

   Map<String, DBFetchGroupQuery> propQueriesIndex = new HashMap<String, DBFetchGroupQuery>();

   Map<String,DBQuery> queriesIndex = new HashMap<String,DBQuery>();
   ArrayList<DBQuery> queriesList = new ArrayList<DBQuery>();

   public DBFetchGroupQuery getDefaultFetchQuery() {
      return fetchGroups.get(defaultFetchGroup);
   }

   public DBFetchGroupQuery getFetchGroupQuery(String fetchGroup) {
      return fetchGroups.get(fetchGroup);
   }

   public DBFetchGroupQuery getFetchQueryForProperty(String propName) {
      DBFetchGroupQuery query = propQueriesIndex.get(propName);
      if (query == null) {
         System.err.println("*** No fetch query for property: " + propName);
      }
      return query;
   }

   public DBQuery getQueryForNum(int queryNum) {
      return queriesList.get(queryNum);
   }

   public int getNumFetchPropQueries() {
      return queriesList.size();
   }

   public DBPropertyDescriptor getPropertyDescriptor(String propName) {
      DBPropertyDescriptor res = null;
      if (primaryTable != null) {
         res = primaryTable.getPropertyDescriptor(propName);
         if (res != null)
            return res;
      }
      if (auxTables != null) {
         for (TableDescriptor tableDesc:auxTables) {
            res = tableDesc.getPropertyDescriptor(propName);
            if (res != null)
               return res;
         }
      }
      if (multiTables != null) {
         for (TableDescriptor tableDesc:multiTables) {
            res = tableDesc.getPropertyDescriptor(propName);
            if (res != null)
               return res;
         }
      }
      return null;
   }

   public void addQuery(DBQuery query) {
      query.queryNumber = queriesList.size();
      queriesList.add(query);
      queriesIndex.put(query.queryName, query);
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      if (typeDecl != null)
         sb.append(CTypeUtil.getClassName(DynUtil.getTypeName(typeDecl, false)));
      return sb.toString();
   }

   public IDBObject findById(Object... idArgs) {
      Object id;
      if (idArgs.length == 1)
         id = idArgs[0];
      else
         id = new MultiColIdentity(idArgs);

      return lookupInstById(id, true, true);
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
    * If createProto is true, and the instance is not in the cache, a prototype is created with that and returned. If fetchDefault
    * is true, that prototype is populated with the default property group if the row exists and returned. If the row does not exist
    * null is returned in that case.
    */
   public IDBObject lookupInstById(Object id, boolean createProto, boolean fetchDefault) {
      if (typeInstances == null) {
         initTypeInstances();
      }
      // TODO: do we need to do dbRefresh() on the returned instance - check the status and return null if the item no longer exists?
      // check cache mode here to determine what to fetch?
      IDBObject inst = (IDBObject) typeInstances.get(id);
      if (inst != null || !createProto) {
         return inst;
      }
      DBObject dbObj;
      synchronized (this) {
         inst = (IDBObject) DynUtil.createInstance(typeDecl, null);
         dbObj = inst.getDBObject();
         dbObj.setPrototype(true);
         dbObj.setDBId(id);
         typeInstances.put(id, inst);
      }

      // If requested, fetch the default (primary table) property group - if the row does not exist, the object does not exist
      if (fetchDefault) {
         if (!dbObj.dbFetchDefault())
            return null;
      }
      return inst;
   }

   public IDBObject registerInstance(IDBObject inst) {
      if (typeInstances == null) {
         initTypeInstances();
      }
      Object id = inst.getDBId();
      synchronized (this) {
         IDBObject res = typeInstances.get(id);
         if (res != null)
            return res;
         typeInstances.put(id, inst);
      }
      return null;
   }

   public boolean removeInstance(DBObject dbObj, boolean remove) {
      if (typeInstances == null)
         return false;
      Object id = dbObj.getDBId();
      IDBObject removed = null;
      synchronized (this) {
         Object res = typeInstances.get(id);
         if (res == dbObj.getInst()) {
            removed = typeInstances.remove(id);
            if (removed != null) {
               if (remove)
                  removed.getDBObject().markRemoved();
               else
                  removed.getDBObject().markStopped();
               return true;
            }
         }
      }
      return false;
   }

   public void clearTypeCache() {
      if (typeInstances == null)
         return;

      synchronized (this) {
         for (IDBObject dbObj:typeInstances.values()) {
            dbObj.getDBObject().markStopped();
         }
         typeInstances.clear();
      }
   }

   public Object getIdColumnValue(Object inst, int ci) {
      IBeanMapper mapper = primaryTable.idColumns.get(ci).getPropertyMapper();
      return mapper.getPropertyValue(inst, false,false);
   }

   public Object getIdColumnType(int ci) {
      IBeanMapper mapper = primaryTable.idColumns.get(ci).getPropertyMapper();
      return mapper.getPropertyType();
   }

   public void init() {
      if (initialized)
         return;

      initialized = true;

      for (DBPropertyDescriptor prop:allDBProps) {
         prop.resolve();
      }
      if (multiTables != null) {
         for (TableDescriptor multiTable:multiTables) {
            DBPropertyDescriptor revProp = multiTable.reverseProperty;
            if (multiTable.reverseProperty != null) {

               for (DBPropertyDescriptor revPropCol:multiTable.columns) {
                  revPropCol.dbTypeDesc = revProp.refDBTypeDesc;
               }
            }
         }
      }
      if (findByQueries != null) {
         for (FindByDescriptor fbDesc:findByQueries)
            if (fbDesc.propTypes == null)
               fbDesc.initTypes(typeDecl);
      }
   }

   public void start() {
      if (started)
         return;
      started = true;
      for (DBPropertyDescriptor prop:allDBProps) {
         prop.start();
      }
      initFetchGroups();
   }

   public void addReverseProperty(DBPropertyDescriptor reverseProp) {
      if (reverseProps == null)
         reverseProps = new ArrayList<DBPropertyDescriptor>();
      reverseProps.add(reverseProp);
   }

   public void initDBObject(DBObject obj) {
      // These are properties in this type that have bi-directional relationships to properties in other types
      // We need to listen to change in this object on these properties in order to keep those other properties in sync
      // with those changes.
      if (reverseProps != null) {
         for (int i = 0; i < reverseProps.size(); i++) {
            DBPropertyDescriptor reverseProp = reverseProps.get(i);
            Object inst = obj.getInst();
            Object curVal = DynUtil.getPropertyValue(inst, reverseProp.propertyName);
            ReversePropertyListener listener = new ReversePropertyListener(obj, curVal, reverseProp);
            Bind.addListener(inst, reverseProp.getPropertyMapper(), listener, IListener.VALUE_CHANGED_MASK);
         }
      }
   }

   private DBFetchGroupQuery initQuery(DBObject proto, String fetchGroup, List<String> props, boolean multiRow) {
      DBFetchGroupQuery groupQuery =  new DBFetchGroupQuery(this);
      if (fetchGroup == null)
         fetchGroup = defaultFetchGroup;
      // Add the tables to fetch all of the properties requested in this query - the 'fetchGroup' but turn it into a 'multiRow' query
      groupQuery.addFetchGroup(fetchGroup, multiRow);
      // Because this is a query, need to be sure we fetch the id property first in the main query
      groupQuery.queries.get(0).insertIdProperty();

      int numProps = props.size();

      // First pass over the properties to gather the list of data sources and tables for this query.
      for (int i = 0; i < numProps; i++) {
         String propName = props.get(i);
         appendPropBindingTables(groupQuery,this, proto, propName);
      }

      // Second pass - build the where clause query string
      for (int i = 0; i < numProps; i++) {
         String propName = props.get(i);
         if (i != 0 && groupQuery.curQuery != null)
            groupQuery.curQuery.whereAppend(" AND ");
         appendPropToWhereClause(groupQuery,  this, proto, propName, numProps > 1, true);
      }

      return groupQuery;
   }

   private void addParamValues(DBFetchGroupQuery groupQuery, DBObject proto, List<String> props) {
      int numProps = props.size();
      // Third pass - for each execution of the query, get the paramValues
      for (int i = 0; i < numProps; i++) {
         String prop = props.get(i);
         if (i != 0 && groupQuery.curQuery.logSB != null)
            groupQuery.curQuery.logSB.append(" AND ");
         appendPropParamValues(groupQuery, this, proto, prop, numProps > 1, true);
      }
   }

   public List<? extends IDBObject> query(DBObject proto, String fetchGroup, List<String> props) {
      DBFetchGroupQuery groupQuery = initQuery(proto, fetchGroup, props, true);
      addParamValues(groupQuery, proto, props);

      DBTransaction curTx = DBTransaction.getOrCreate();
      return groupQuery.query(curTx, proto.getDBObject());
   }

   public IDBObject queryOne(DBObject proto, String fetchGroup, List<String> props) {
      DBFetchGroupQuery groupQuery = initQuery(proto, fetchGroup, props, true);
      addParamValues(groupQuery, proto, props);

      DBTransaction curTx = DBTransaction.getOrCreate();
      return groupQuery.queryOne(curTx, proto.getDBObject());
   }

   private void appendPropParamValues(DBFetchGroupQuery groupQuery, DBTypeDescriptor curTypeDesc, DBObject curObj, String propName, boolean needsParens, boolean compareVal) {
      DBPropertyDescriptor dbProp = getPropertyDescriptor(propName);
      Object propValue = DynUtil.getPropertyValue(curObj.getInst(), propName);
      FetchTablesQuery curQuery = groupQuery.curQuery;
      StringBuilder logSB = curQuery.logSB;
      if (dbProp != null) {
         if (logSB != null) {
            if (compareVal) {
               DBUtil.appendVal(logSB, propValue);
               logSB.append(" = ");
            }
            curQuery.appendLogWhereColumn(logSB, dbProp.getTableName(), dbProp.columnName);
         }
         if (compareVal) {
            curQuery.paramValues.add(propValue);
            curQuery.paramTypes.add(dbProp.getPropertyMapper().getPropertyType());
         }
      }
      else {
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

         Object propVal = DynUtil.getPropertyValue(curObj.getInst(), propName);
         Object propType = DynUtil.getPropertyType(DynUtil.getType(inst), propName);
         boolean needsInnerParen = false;
         if ((propType == Boolean.class || propType == Boolean.TYPE)) {
            if (propVal == null)
               propVal = Boolean.TRUE;

            // For boolean properties we could write it out as: TRUE = (a > b) but we can simplify that
            // to just the RHS. For
            if (logSB != null && !((Boolean)propVal)) {
               logSB.append("NOT ");
               needsInnerParen = true;
            }
         }
         else {
            curQuery.paramValues.add(propVal);
            curQuery.paramTypes.add(propType);

            if (logSB != null) {
               DBUtil.appendVal(logSB, propVal);
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

   private void appendParamValues(DBFetchGroupQuery groupQuery, DBTypeDescriptor curTypeDesc, DBObject curObj, IBinding binding) {
      if (binding instanceof IBeanMapper) {
         IBeanMapper propMapper = (IBeanMapper) binding;
         String propName = propMapper.getPropertyName();
         appendPropParamValues(groupQuery, curTypeDesc, curObj, propName, true, false);
      }
      else if (binding instanceof ConditionalBinding) {
         ConditionalBinding cond = (ConditionalBinding) binding;
         IBinding[] paramBindings = cond.getBoundParams();
         for (int i = 0; i < paramBindings.length; i++) {
            IBinding paramBinding = paramBindings[i];
            if (i != 0) {
               FetchTablesQuery curQuery = groupQuery.curQuery;
               StringBuilder logSB = curQuery.logSB;
               if (logSB != null) {
                  logSB.append(" ");
                  logSB.append(DBUtil.cvtJavaToSQLOperator(cond.operator).toString());
                  logSB.append(" ");
               }
            }
            appendParamValues(groupQuery, curTypeDesc, curObj, paramBinding);
         }
      }
      else if (binding instanceof VariableBinding) {
         VariableBinding varBind = (VariableBinding) binding;
         int numInChain = varBind.getNumInChain();
         FetchTablesQuery curQuery = groupQuery.curQuery;
         for (int i = 0; i < numInChain; i++) {
            Object chainProp = varBind.getChainElement(i);
            if (chainProp instanceof String || chainProp instanceof IBeanMapper) {
               String nextPropName = chainProp instanceof String ? (String) chainProp : ((IBeanMapper) chainProp).getPropertyName();
               if (i == 0 && numInChain == 1) {
                  appendPropParamValues(groupQuery, curTypeDesc, curObj, nextPropName, false, false);
               }
               else {
                  if (numInChain == 2) {
                     Object lastBinding = varBind.getChainElement(1);
                     if (lastBinding instanceof MethodBinding) {
                        MethodBinding methBind = (MethodBinding) lastBinding;
                        String methName = DynUtil.getMethodName(methBind.getMethod());
                        IBinding[] boundParams = methBind.getBoundParams();
                        if (methName.equals("equals") && boundParams.length == 1) {
                           appendPropParamValues(groupQuery, curTypeDesc, curObj, nextPropName, false, false);
                           if (curQuery.logSB != null)
                              curQuery.logSB.append(" = ");
                           appendParamValues(groupQuery, curTypeDesc, curObj, boundParams[0]);
                           return;
                        }
                     }
                  }
                  // If 'a' is a ref to another persistent property 'b' this should add a join between those two tables
                  // and here we'll include just 'bTable.bCol' for the value of this binding
                  throw new UnsupportedOperationException("Bindings with a.b not yet supported in queries");
               }
            }
            else {
               throw new UnsupportedOperationException("Variable binding type not yet supported in queries");
            }
         }
      }
      else if (binding instanceof ConstantBinding) {
         ConstantBinding cbind = (ConstantBinding) binding;
         FetchTablesQuery curQuery = groupQuery.curQuery;
         StringBuilder logSB = curQuery.logSB;
         if (logSB != null) {
            Object cval = cbind.getConstantValue();
            if (cval == null)
               logSB.append(" IS NULL");
            else if (cval instanceof CharSequence) {
               logSB.append("'");
               logSB.append(cval.toString());
               logSB.append("'");
            }
            else
               logSB.append(cval.toString());
         }
      }
      else
         throw new IllegalArgumentException("Unsupport binding type for query");
   }

   private void appendPropBindingTables(DBFetchGroupQuery groupQuery, DBTypeDescriptor curTypeDesc, DBObject curObj, String propName) {
      DBPropertyDescriptor dbProp = getPropertyDescriptor(propName);
      if (dbProp != null) {
         FetchTablesQuery newQuery = groupQuery.addProperty(dbProp, true);
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
         DestinationListener propBinding = Bind.getBinding(curObj.getInst(), propName);
         if (propBinding == null) {
            throw new IllegalArgumentException("No column or binding for property: " + this + "." + propName);
         }
         appendBindingTables(groupQuery, curTypeDesc, curObj, propBinding);
      }
   }

   private void appendBindingTables(DBFetchGroupQuery groupQuery, DBTypeDescriptor startTypeDesc, DBObject curObj, IBinding binding) {
      if (binding instanceof IBeanMapper) {
         appendPropBindingTables(groupQuery, startTypeDesc, curObj, ((IBeanMapper) binding).getPropertyName());
      }
      else if (binding instanceof VariableBinding) {
         VariableBinding varBind = (VariableBinding) binding;
         int numInChain = varBind.getNumInChain();
         DBTypeDescriptor curTypeDesc = startTypeDesc;
         for (int i = 0; i < numInChain; i++) {
            Object chainProp = varBind.getChainElement(i);
            if (chainProp instanceof String || chainProp instanceof IBeanMapper) {
               String nextPropName = chainProp instanceof String ? (String) chainProp : ((IBeanMapper) chainProp).getPropertyName();
               DBPropertyDescriptor nextPropDesc = curTypeDesc.getPropertyDescriptor(nextPropName);
               if (nextPropDesc == null) {
                  if (i == 0) {
                     appendPropBindingTables(groupQuery, curTypeDesc, curObj, nextPropName);
                  }
                  else {
                     // Reference to a non-persistent property -
                     // TODO: if the object holding this property is persistent, it could be a rule off of the other type - if so, validate
                     // that we can join to this other property and if so, generate the where clause for that join
                     throw new IllegalArgumentException("Unable to query non-DB property: " + nextPropName + " used in path for: " + binding);
                  }
               }
               else {
                  if (nextPropDesc.refDBTypeDesc != null) {
                     // Allow the prototype to have a reference to another prototype so we can build up graph style queries
                     IDBObject nextObj = curObj == null ? null : (IDBObject) nextPropDesc.getPropertyMapper().getPropertyValue(curObj.getInst(), false, false);
                     if (nextObj != null)
                        appendPropBindingTables(groupQuery, curTypeDesc, nextObj.getDBObject(), nextPropName);
                     curTypeDesc = nextPropDesc.refDBTypeDesc;
                     if (curTypeDesc == null && i != numInChain - 1) // TODO: handle other cases like a JSON property or a rule that's chained through some other component?
                        throw new UnsupportedOperationException("Property reference to non-db association");
                  }
                  else {
                     appendPropBindingTables(groupQuery, curTypeDesc, curObj, nextPropName);
                  }
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

   private void appendBindingToWhereClause(DBFetchGroupQuery groupQuery, DBTypeDescriptor curTypeDesc, DBObject curObj, IBinding binding) {
      if (binding instanceof IBeanMapper) {
         IBeanMapper propMapper = (IBeanMapper) binding;
         String propName = propMapper.getPropertyName();
         appendPropToWhereClause(groupQuery, curTypeDesc, curObj, propName, true, false);
      }
      else if (binding instanceof ConditionalBinding) {
         ConditionalBinding cond = (ConditionalBinding) binding;
         IBinding[] params = cond.getBoundParams();
         FetchTablesQuery curQuery = groupQuery.curQuery;
         boolean needsParen = curQuery != null;
         if (needsParen) {
            curQuery.whereAppend("(");
         }
         for (int i = 0; i < params.length; i++) {
            IBinding param = params[i];
            if (i != 0) {
               groupQuery.curQuery.whereAppend(" ");
               groupQuery.curQuery.whereAppend(DBUtil.cvtJavaToSQLOperator(cond.operator).toString());
               groupQuery.curQuery.whereAppend(" ");
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
         FetchTablesQuery curQuery = groupQuery.curQuery;
         for (int i = 0; i < numInChain; i++) {
            Object chainProp = varBind.getChainElement(i);
            if (chainProp instanceof String || chainProp instanceof IBeanMapper) {
               String nextPropName = chainProp instanceof String ? (String) chainProp : ((IBeanMapper) chainProp).getPropertyName();
               if (i == 0 && numInChain == 1) {
                  appendPropToWhereClause(groupQuery, curTypeDesc, curObj, nextPropName, false, false);
               }
               else {
                  if (numInChain == 2) {
                     Object lastBinding = varBind.getChainElement(1);
                     if (lastBinding instanceof MethodBinding) {
                        MethodBinding methBind = (MethodBinding) lastBinding;
                        String methName = DynUtil.getMethodName(methBind.getMethod());
                        IBinding[] boundParams = methBind.getBoundParams();
                        if (methName.equals("equals") && boundParams.length == 1) {
                           appendPropToWhereClause(groupQuery, curTypeDesc, curObj, nextPropName, false, false);
                           curQuery.whereAppend(" = ");
                           appendBindingToWhereClause(groupQuery, curTypeDesc, curObj, boundParams[0]);
                           return;
                        }
                     }
                  }
                  // If 'a' is a ref to another persistent property 'b' this should add a join between those two tables
                  // and here we'll include just 'bTable.bCol' for the value of this binding
                  throw new UnsupportedOperationException("Bindings with a.b not yet supported in queries");
               }
            }
            else {
               throw new UnsupportedOperationException("Variable binding type not yet supported in queries");
            }
         }
      }
      else if (binding instanceof ConstantBinding) {
         ConstantBinding cbind = (ConstantBinding) binding;
         Object cval = cbind.getConstantValue();
         FetchTablesQuery curQuery = groupQuery.curQuery;
         if (cval == null)
            curQuery.whereAppend(" IS NULL");
         else if (cval instanceof CharSequence) {
            curQuery.whereAppend("'");
            curQuery.whereAppend(cval.toString());
            curQuery.whereAppend("'");
         }
         else
            curQuery.whereAppend(cval.toString());
      }
      else
         throw new IllegalArgumentException("Unsupport binding type for query");
   }

   private void appendPropToWhereClause(DBFetchGroupQuery groupQuery, DBTypeDescriptor curTypeDesc, DBObject curObj, String propName, boolean needsParens, boolean compareVal) {
      FetchTablesQuery curQuery = groupQuery.curQuery;
      DBPropertyDescriptor dbProp = getPropertyDescriptor(propName);
      if (dbProp != null) {
         FetchTablesQuery newQuery = groupQuery.findQueryForProperty(dbProp, true);
         if (newQuery == null) // should have been added in the first pass
            throw new IllegalArgumentException("Missing query for db property: " + propName);

         if (newQuery != groupQuery.curQuery) {
            if (curQuery != null) {
               throw new UnsupportedOperationException("Add code to do join for different data sources here!");
            }
            else {
               curQuery = groupQuery.curQuery = newQuery;
            }
         }

         if (compareVal) {
            curQuery.whereAppend("? = ");
         }
         curQuery.appendWhereColumn(dbProp.getTableName(), dbProp.columnName);
      }
      else {
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

         Object propVal = DynUtil.getPropertyValue(inst, propName);
         Object propType = DynUtil.getPropertyType(DynUtil.getType(inst), propName);
         boolean needsInnerParen = false;
         if ((propType == Boolean.class || propType == Boolean.TYPE)) {
            if (propVal == null)
               propVal = Boolean.TRUE;

            // For boolean properties we could write it out as: TRUE = (a > b) but we can simplify that
            // to just the RHS. For
            if (!((Boolean)propVal)) {
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
   }
}
