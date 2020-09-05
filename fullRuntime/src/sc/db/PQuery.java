package sc.db;

import java.util.List;

public class PQuery extends Query {
   Query[] queries;
   QCombine combiner;
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
}
