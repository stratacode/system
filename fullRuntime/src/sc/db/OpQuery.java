/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.db;

import sc.dyn.DynUtil;

import java.util.ArrayList;
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
      if (comparator == QCompare.In)
         return;
      res.add(propName);
   }

   public List<String> getNonProtoProps() {
      if (comparator == QCompare.In) {
         ArrayList<String> res = new ArrayList<String>(1);
         res.add(propName);
         return res;
      }
      return null;
   }

   public void addAllPropertyValues(List<Object> res) {
      if (comparator == QCompare.In)
         return;
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
      else if (comparator == QCompare.In) {
         sb.append(".in(");
         int numValues = DynUtil.getArrayLength(propValue);
         for (int i = 0; i < numValues; i++) {
            if (i != 0)
               sb.append(",");
            DBUtil.appendConstant(sb, DynUtil.getArrayElement(propValue, i));
         }
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
