/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.db;

import java.util.ArrayList;
import java.util.List;

public class IncludesItemQuery extends Query {
   public IncludesItemQuery(String propName, Query subQuery) {
      this.propName = propName;
      this.subQuery = subQuery;
   }

   String propName;
   Query subQuery;

   public void addAllPropertyNames(List<String> res) {
      List<String> subNames = new ArrayList<String>();
      subQuery.addAllPropertyNames(subNames);
      for (int i = 0; i < subNames.size(); i++) {
         res.add(propName + "." + subNames.get(i));
      }
   }

   public void addAllPropertyValues(List<Object> res) {
      subQuery.addAllPropertyValues(res);
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(propName);
      sb.append(" includesItem (");
      sb.append(subQuery);
      sb.append(")");
      return sb.toString();
   }
}
