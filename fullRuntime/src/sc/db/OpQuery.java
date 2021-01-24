/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.db;

import java.util.List;

public class OpQuery extends Query {
   public String propName;
   public QCompare comparator;
   public Object propValue;

   public OpQuery() {
   }

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

   private void appendPropValueJavaString(StringBuilder sb) {
      DBUtil.appendConstant(sb, propValue);
   }

   public String toString() {
      if (propName == null || comparator == null)
         return "null op query";
      StringBuilder sb = new StringBuilder();
      sb.append(propName);
      if (comparator == QCompare.Equals) {
         if (!(propValue instanceof Boolean)) {
            sb.append(" == ");
            appendPropValueJavaString(sb);
         }
      }
      else if (comparator == QCompare.NotEquals) {
         if (!(propValue instanceof Boolean)) {
            sb.append(" != ");
            appendPropValueJavaString(sb);
         }
         else
            sb = new StringBuilder("!" + sb);
      }
      else if (comparator == QCompare.Match) {
         sb.append(".contains(");
         sb.append('"');
         sb.append(propValue.toString());
         sb.append('"');
         sb.append(")");
      }
      else {
         sb.append(" ");
         sb.append(comparator.javaOp);
         sb.append(" ");
         appendPropValueJavaString(sb);
      }

      return sb.toString();
   }
}
