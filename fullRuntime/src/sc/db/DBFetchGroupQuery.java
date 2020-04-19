package sc.db;

import java.util.ArrayList;
import java.util.List;

// TODO: rename DBSelectGroupQuery, fetchGroup to selectGroup
public class DBFetchGroupQuery extends DBQuery {
   List<SelectQuery> queries = new ArrayList<SelectQuery>();
   List<String> propNames;
   List<String> orderByProps;
   int startIx, maxResults;

   // Used when building the FetchGroupQuery - to point to the current SelectQuery
   SelectQuery curQuery;

   String fetchGroup;

   private boolean activated;

   public DBFetchGroupQuery(DBTypeDescriptor dbTypeDesc, List<String> propNames, String fetchGroup) {
      this.dbTypeDesc = dbTypeDesc;
      this.propNames = propNames;
      this.fetchGroup = fetchGroup;
   }

   public void addFetchGroup(String fetchGroup, boolean multiRow) {
      DBFetchGroupQuery fgQuery = dbTypeDesc.getFetchGroupQuery(fetchGroup);
      if (fgQuery == null)
         throw new IllegalArgumentException("No fetch group query: " + fetchGroup + " for type: " + dbTypeDesc);
      for (SelectQuery ftq:fgQuery.queries) {
         SelectQuery newFtq = ftq.clone();
         newFtq.multiRow = multiRow;
         newFtq.propNames = propNames;
         queries.add(newFtq);
      }
   }

   public SelectQuery addProperty(DBPropertyDescriptor curRefProp, DBPropertyDescriptor prop, boolean multiRowFetch) {
      TableDescriptor table = prop.getTable();
      String dataSourceName = table.getDataSourceName();

      SelectQuery query = getSelectQuery(dataSourceName, multiRowFetch || prop.multiRow);
      query.addProperty(curRefProp, prop);
      return query;
   }

   public SelectQuery getSelectQuery(String dataSourceName, boolean multiRow) {
      for (SelectQuery query:queries)
         if (query.dataSourceName.equals(dataSourceName) && query.multiRow == multiRow)
            return query;
      SelectQuery ftq = new SelectQuery(dataSourceName, multiRow);
      ftq.propNames = propNames;
      queries.add(ftq);
      return ftq;
   }

   public SelectQuery findQueryForProperty(DBPropertyDescriptor pdesc, boolean multi) {
      String dataSourceName = pdesc.getDataSourceForProp();
      for (SelectQuery query:queries)
         if (query.dataSourceName.equals(dataSourceName) && query.multiRow == multi && query.containsProperty(pdesc))
            return query;
      return null;
   }

   public boolean fetchProperties(DBTransaction transaction, DBObject dbObj) {
      boolean res = true;
      for (SelectQuery query:queries) {
         boolean any = query.fetchProperties(transaction, dbObj);
         if (!any && query.includesPrimary)
            res = false;
      }
      return res;
   }

   public DBFetchGroupQuery clone() {
      DBFetchGroupQuery res = new DBFetchGroupQuery(dbTypeDesc, propNames, fetchGroup);
      res.queryNumber = queryNumber;
      res.queryName = queryName;
      for (SelectQuery ftq:queries)
         res.queries.add(ftq.clone());
      return res;
   }

   public List<IDBObject> matchQuery(DBTransaction transaction, DBObject proto) {
      // Here we pick the main query, run it first, look for queries that just fetch additional properties (i.e. without a 'where clause') and do
      // fetchProperties on them.
      // For any additional queries with 'where' clauses, I think we need to just make sure we fetch those properties, then do the test against each item
      // to remove it from the list.
      if (queries.size() > 1)
         System.err.println("*** Need to do join of queries here");
      return curQuery.matchQuery(transaction, proto);
   }

   public IDBObject matchOne(DBTransaction transaction, DBObject proto) {
      // Here we pick the main query, run it first, look for queries that just fetch additional properties (i.e. without a 'where clause') and do
      // fetchProperties on them.
      // For any additional queries with 'where' clauses, I think we need to just make sure we fetch those properties, then do the test against each item
      // to remove it from the list.
      if (queries.size() > 1)
         System.err.println("*** Need to do join of queries here");
      return curQuery.matchOne(transaction, proto);
   }

   public void setOrderBy(List<String> orderByProps) {
      this.orderByProps = orderByProps;
      for (int i = 0; i < orderByProps.size(); i++) {
         String orderByProp = orderByProps.get(i);
         boolean desc = orderByProp.startsWith("-");
         if (desc)
            orderByProp = orderByProp.substring(1);
         DBPropertyDescriptor propDesc = dbTypeDesc.getPropertyDescriptor(orderByProp);
         if (propDesc == null)
            throw new IllegalArgumentException("Missing orderBy property: " + orderByProp + " in: " + dbTypeDesc);
         if (propDesc.multiRow)
            throw new IllegalArgumentException("Multi valued property: " + orderByProp + " used in orderBy: " + dbTypeDesc);
         String dataSourceName = propDesc.getDataSourceForProp();
         SelectQuery query = getSelectQuery(dataSourceName, false);
         if (query.orderByProps == null) {
            query.orderByProps = new ArrayList<DBPropertyDescriptor>(orderByProps.size());
            query.orderByDirs = new ArrayList<Boolean>(orderByProps.size());
         }
         query.orderByProps.add(propDesc);
         query.orderByDirs.add(desc);
      }
   }

   public void setStartIndex(int ix) {
      this.startIx = ix;
      if (queries != null) {
         for (SelectQuery query:queries)
            query.startIndex = ix;
      }
   }

   public void setMaxResults(int max) {
      this.maxResults = max;
      if (queries != null) {
         for (SelectQuery query:queries)
            query.maxResults = max;
      }
   }

   public void activate() {
      if (activated)
         return;
      activated = true;
      if (queries != null) {
         for (SelectQuery query:queries)
            query.activate();
      }
   }
}
