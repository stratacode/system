package sc.db;

import java.util.List;

public class PQuery extends Query {
   public Query[] queries;
   public QCombine combiner;

   public PQuery() {
   }

   public PQuery(QCombine c, Query... qs) {
      this.combiner = c;
      this.queries = qs;
   }
   public void addAllPropertyNames(List<String> res) {
      for (Query query:queries) {
         query.addAllPropertyNames(res);
      }
   }
   public void addAllPropertyValues(List<Object> res) {
      for (Query query:queries) {
         query.addAllPropertyValues(res);
      }
   }

   public String toString() {
      if (queries == null || combiner == null)
         return "null pQuery";
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < queries.length; i++) {
         if (i != 0) {
            sb.append(" ");
            sb.append(combiner.getJavaOperator());
            sb.append(" ");
         }
         sb.append(queries[i].toString());
      }
      return sb.toString();
   }
}
