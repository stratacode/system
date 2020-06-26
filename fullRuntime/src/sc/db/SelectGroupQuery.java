package sc.db;

import java.util.ArrayList;
import java.util.List;

/** Managed a list of SelectQuery instances - each one against a separate dataSource */
public class SelectGroupQuery extends DBQuery {
   List<SelectQuery> queries = new ArrayList<SelectQuery>();
   List<String> propNames;
   List<String> orderByProps;
   int startIx, maxResults;

   // Used when building the SelectGroupQuery - to point to the current SelectQuery
   SelectQuery curQuery;

   String selectGroup;

   private boolean activated;

   public SelectGroupQuery(DBTypeDescriptor dbTypeDesc, List<String> propNames, String selectGroup) {
      this.dbTypeDesc = dbTypeDesc;
      this.propNames = propNames;
      this.selectGroup = selectGroup;
      this.queryName = selectGroup;
   }

   public void addSelectGroup(String selectGroup, boolean multiRow) {
      SelectGroupQuery fgQuery = dbTypeDesc.getSelectGroupQuery(selectGroup);
      if (fgQuery == null)
         throw new IllegalArgumentException("No select group query: " + selectGroup + " for type: " + dbTypeDesc);
      for (SelectQuery ftq:fgQuery.queries) {
         SelectQuery newFtq = ftq.cloneForSubType(null);
         newFtq.multiRow = multiRow;
         newFtq.propNames = propNames;
         queries.add(newFtq);
      }
   }

   public SelectQuery addProperty(DBPropertyDescriptor curRefProp, DBPropertyDescriptor prop, boolean multiRowSelect) {
      TableDescriptor table = prop.getTable();
      String dataSourceName = table.getDataSourceName();

      SelectQuery query = getSelectQuery(dataSourceName, multiRowSelect || prop.isMultiRowProperty());
      query.addProperty(curRefProp, prop);
      return query;
   }

   public SelectQuery getSelectQuery(String dataSourceName, boolean multiRow) {
      for (SelectQuery query:queries)
         if (query.dataSourceName.equals(dataSourceName) && query.multiRow == multiRow)
            return query;
      SelectQuery ftq = new SelectQuery(dataSourceName, dbTypeDesc, multiRow);
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

   public boolean selectProperties(DBTransaction transaction, DBObject dbObj) {
      boolean res = true;
      for (SelectQuery query:queries) {
         boolean any = query.selectProperties(transaction, dbObj);
         if (!any && query.includesPrimary)
            res = false;
      }
      return res;
   }

   public SelectGroupQuery cloneForSubType(DBTypeDescriptor subType) {
      SelectGroupQuery res = new SelectGroupQuery(dbTypeDesc, propNames, selectGroup);
      res.queryNumber = queryNumber;
      res.queryName = queryName;
      for (SelectQuery ftq:queries)
         res.queries.add(ftq.cloneForSubType(subType));
      return res;
   }

   public List<IDBObject> runQuery(DBTransaction transaction, DBObject proto) {
      // Here we pick the main query, run it first, look for queries that just select additional properties (i.e. without a 'where clause') and do
      // selectProperties on them.
      // For any additional queries with 'where' clauses, I think we need to just make sure we select those properties, then do the test against each item
      // to remove it from the list.
      if (queries.size() > 1)
         System.err.println("*** Need to do join of queries here");
      if (curQuery == null)
         curQuery = queries.get(0);
      return curQuery.matchQuery(transaction, proto);
   }

   public IDBObject matchOne(DBTransaction transaction, DBObject proto) {
      // Here we pick the main query, run it first, look for queries that just select additional properties (i.e. without a 'where clause') and do
      // selectProperties on them.
      // For any additional queries with 'where' clauses, I think we need to just make sure we select those properties, then do the test against each item
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
         SelectQuery query = getSelectQuery(dataSourceName, true);
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
