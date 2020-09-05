package sc.db;

import java.util.List;

public class OpQuery extends Query {
   String propName;
   QCompare comparator;
   Object propValue;

   public OpQuery(String pn, QCompare c, Object pv) {
      propName = pn;
      comparator = c;
      propValue = pv;
   }

   public void addAllPropertyNames(List<String> res) {
      res.add(propName);
   }

   public void addAllPropertyValues(List<Object> res) {
      if (comparator == QCompare.Match && propValue instanceof CharSequence)
         res.add(DBTypeDescriptor.convertToSQLSearchString(propValue.toString()));
      else
         res.add(propValue);
   }
}
