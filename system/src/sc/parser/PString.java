/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

public class PString extends AbstractString {
   String str;

   public PString(String s) {
      if (s == null)
          throw new IllegalArgumentException("Invalid null PString");
      str = s;
   }

   public String toString() {
      return str;
   }

   public char charAt(int index) {
      return str.charAt(index);
   }

   public int length() {
      return str.length();
   }

   public static PString EMPTY_STRING = new PString("");

   public static PString toPString(Object str) {
      if (str == null)
         return null;
      if (str instanceof PString)
         return (PString) str;
      return new PString(str.toString());
   }

   public static boolean isString(Object str) {
      return str instanceof String || str instanceof IString;
   }

   public int getStringLength(Object str) {
      if (str instanceof CharSequence)
         return ((CharSequence) str).length();
      throw new UnsupportedOperationException();
   }

   public static IString toIString(Object str) {
      if (str == null)
         return null;
      if (str instanceof IString)
         return (IString) str;
      return new PString(str.toString());
   }

   public static IString toIString(String str) {
      if (str == null)
         return null;
      return new PString(str);
   }

   public static IString [] toPString(String[] arr) {
      IString [] res = new IString[arr.length];
      int i = 0;
      for (String str:arr)
      {
         if (str == null)
            res[i] = null;
         else
            res[i] = new PString(str);
         i++;
      }
      return res;
   }
}
