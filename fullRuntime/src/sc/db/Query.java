package sc.db;

import java.util.ArrayList;
import java.util.List;

public abstract class Query {

   public static Query and(Query... queries) {
      return new PQuery(QCombine.And, queries);
   }

   public static Query or(Query... queries) {
      return new PQuery(QCombine.Or, queries);
   }

   public static Query eq(String propName, Object value) {
      return new OpQuery(propName, QCompare.Equals, value);
   }

   public static Query match(String propName, Object value) {
      return new OpQuery(propName, QCompare.Match, value);
   }

   public static Query op(String propName, QCompare op, Object value) {
      return new OpQuery(propName, op, value);
   }

   public List<String> getAllPropertyNames() {
      ArrayList<String> res = new ArrayList<String>();
      addAllPropertyNames(res);
      return res;
   }
   public List<Object> getAllPropertyValues() {
      ArrayList<Object> res = new ArrayList<Object>();
      addAllPropertyValues(res);
      return res;
   }
   public abstract void addAllPropertyNames(List<String> res);
   public abstract void addAllPropertyValues(List<Object> res);
}
