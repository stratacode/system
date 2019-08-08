/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import java.util.ArrayList;
import java.util.List;

public class ArrayUtil {
   public static Object[] array2DRange(Object[][] srcArr, int startIx, int endIx) {
      int srcSize = srcArr.length;
      ArrayList res = new ArrayList(srcSize);
      for (int i = 0; i < srcSize; i++) {
         Object[] inner = srcArr[i];
         for (int j = startIx; j < endIx; j++) {
            if (j < inner.length)
               res.add(inner[j]);
         }
      }
      return res.toArray();
   }

   public static Object[] listToArray(List l) {
      return l.toArray();
   }

   public static String argsToString(Object[] args) {
      if (args == null)
         return "";
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < args.length; i++) {
         if (i != 0)
            sb.append(", ");
         sb.append(args[i]);
      }
      return sb.toString();
   }
}
