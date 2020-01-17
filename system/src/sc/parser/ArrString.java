/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

public class ArrString extends AbstractString {
   public char[] buf;

   public ArrString(char[] buf) {
      if (buf == null)
          throw new IllegalArgumentException("Invalid null PString");
      this.buf = buf;
   }

   public String toString() {
      return new String(buf);
   }

   public char charAt(int index) {
      return buf[index];
   }

   public int length() {
      return buf.length;
   }

   public static ArrString toArrString(String str) {
      if (str == null)
         return null;
      return new ArrString(str.toCharArray());
   }

}
