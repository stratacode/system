package sc.db;

import java.util.ArrayList;
import java.util.List;

class DBFetchGroupQuery extends DBQuery {
   DBTypeDescriptor dbTypeDesc;
   String fetchGroup;
   List<FetchTablesQuery> queries = new ArrayList<FetchTablesQuery>();

   public void addProperty(DBPropertyDescriptor prop) {
      TableDescriptor table = prop.getTable();
      String dataSourceName = table.getDataSourceName();

      FetchTablesQuery query = getFetchTablesQuery(dataSourceName, prop.multiRow);
      query.addProperty(table, prop);
   }

   public FetchTablesQuery getFetchTablesQuery(String dataSourceName, boolean multiRow) {
      for (FetchTablesQuery query:queries)
         if (query.dataSourceName.equals(dataSourceName) && query.multiRow == multiRow)
            return query;
      FetchTablesQuery ftq = new FetchTablesQuery(dataSourceName, multiRow);
      queries.add(ftq);
      return ftq;
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
}
