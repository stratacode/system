/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public class AbstractTemplateParameters {
   public static boolean emptyString(String str) {
      return str == null || str.length() == 0;
   }

   public static String formatString(String str) {
      if (str == null)
         return "null";
      else
         return '"' + str + '"';
   }

   public static String formatStringArray(String[] strarr) {
      StringBuilder sb = new StringBuilder();
      sb.append("new String[] {");
      int i = 0;
      for (String str:strarr) {
         if (i != 0)
            sb.append(",");
         sb.append(formatString(str));
         i++;
      }
      sb.append("}");
      return sb.toString();
   }
}
