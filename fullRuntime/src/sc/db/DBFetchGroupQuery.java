package sc.db;

import java.util.ArrayList;
import java.util.List;

public class DBFetchGroupQuery extends DBQuery {
   DBTypeDescriptor dbTypeDesc;
   List<FetchTablesQuery> queries = new ArrayList<FetchTablesQuery>();
   List<String> propNames;

   FetchTablesQuery curQuery;

   public DBFetchGroupQuery(DBTypeDescriptor dbTypeDesc, List<String> propNames) {
      this.dbTypeDesc = dbTypeDesc;
      this.propNames = propNames;
   }

   public void addFetchGroup(String fetchGroup, boolean multiRow) {
      DBFetchGroupQuery fgQuery = dbTypeDesc.getFetchGroupQuery(fetchGroup);
      if (fgQuery == null)
         throw new IllegalArgumentException("No fetch group query: " + fetchGroup + " for type: " + dbTypeDesc);
      for (FetchTablesQuery ftq:fgQuery.queries) {
         FetchTablesQuery newFtq = ftq.clone();
         newFtq.multiRow = multiRow;
         newFtq.propNames = propNames;
         queries.add(newFtq);
      }
   }

   public FetchTablesQuery addProperty(DBPropertyDescriptor prop, boolean multiRowFetch) {
      TableDescriptor table = prop.getTable();
      String dataSourceName = table.getDataSourceName();

      FetchTablesQuery query = getFetchTablesQuery(dataSourceName, multiRowFetch || prop.multiRow);
      query.addProperty(table, prop);
      return query;
   }

   public FetchTablesQuery getFetchTablesQuery(String dataSourceName, boolean multiRow) {
      for (FetchTablesQuery query:queries)
         if (query.dataSourceName.equals(dataSourceName) && query.multiRow == multiRow)
            return query;
      FetchTablesQuery ftq = new FetchTablesQuery(dataSourceName, multiRow);
      ftq.propNames = propNames;
      queries.add(ftq);
      return ftq;
   }

   public FetchTablesQuery findQueryForProperty(DBPropertyDescriptor pdesc, boolean multi) {
      String dataSourceName = pdesc.getDataSourceForProp();
      for (FetchTablesQuery query:queries)
         if (query.dataSourceName.equals(dataSourceName) && query.multiRow == multi && query.containsProperty(pdesc))
            return query;
      return null;
   }

   public boolean fetchProperties(DBTransaction transaction, DBObject dbObj) {
      boolean res = true;
      for (FetchTablesQuery query:queries) {
         boolean any = query.fetchProperties(transaction, dbObj);
         if (!any && query.includesPrimary)
            res = false;
      }
      return res;
   }

   public DBFetchGroupQuery clone() {
      DBFetchGroupQuery res = new DBFetchGroupQuery(dbTypeDesc, propNames);
      res.queryNumber = queryNumber;
      res.queryName = queryName;
      for (FetchTablesQuery ftq:queries)
         res.queries.add(ftq.clone());
      return res;
   }

   public List<IDBObject> query(DBTransaction transaction, DBObject proto) {
      // Here we pick the main query, run it first, look for queries that just fetch additional properties (i.e. without a 'where clause') and do
      // fetchProperties on them.
      // For any additional queries with 'where' clauses, I think we need to just make sure we fetch those properties, then do the test against each item
      // to remove it from the list.
      if (queries.size() > 1)
         System.err.println("*** Need to do join of queries here");
      return curQuery.query(transaction, proto);
   }

   public IDBObject queryOne(DBTransaction transaction, DBObject proto) {
      // Here we pick the main query, run it first, look for queries that just fetch additional properties (i.e. without a 'where clause') and do
      // fetchProperties on them.
      // For any additional queries with 'where' clauses, I think we need to just make sure we fetch those properties, then do the test against each item
      // to remove it from the list.
      if (queries.size() > 1)
         System.err.println("*** Need to do join of queries here");
      return curQuery.queryOne(transaction, proto);
   }
}
