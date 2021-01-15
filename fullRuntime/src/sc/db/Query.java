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

   public static Query neq(String propName, Object value) {
      return new OpQuery(propName, QCompare.NotEquals, value);
   }

   public static Query gt(String propName, Object value) {
      return new OpQuery(propName, QCompare.GreaterThan, value);
   }

   public static Query lt(String propName, Object value) {
      return new OpQuery(propName, QCompare.LessThan, value);
   }

   public static Query gteq(String propName, Object value) {
      return new OpQuery(propName, QCompare.GreaterThanEq, value);
   }

   public static Query lteq(String propName, Object value) {
      return new OpQuery(propName, QCompare.LessThanEq, value);
   }

   public static Query match(String propName, Object value) {
      return new OpQuery(propName, QCompare.Match, value);
   }

   public static Query op(String propName, QCompare op, Object value) {
      return new OpQuery(propName, op, value);
   }

   // TODO: finish implementing this - maybe with an 'exists' query?  I wanted to use it for the userManager findUsersBySite
   // query that needs to find all user accounts that have visited a site over a time period. But then it comes to sorting
   // the users and we want to sort them by the most recent session - that requires a group by user.id max(session.timestamp) type
   // of thing that required a custom DB query - now implemented by a stored procedure
   //public static Query includesItem(String propName, Query subQuery) {
   //   return new IncludesItemQuery(propName, subQuery);
   //}

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

   public abstract String toString();
}