/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

public class StringToken extends AbstractString {
   IString baseString;

   public int startIndex;
   public int len;

   StringToken(IString p) {
      baseString = p;
   }

   StringToken(IString p, int start, int l) {
      this(p); 
      startIndex = start;
      len = l;
   }

   public String toString() {
      char[] buf = new char[len];
      for (int i = 0; i < len; i++)
         buf[i] = charAt(i);
      return new String(buf);
   }

   public char charAt(int index) {
      return (char) baseString.charAt(startIndex + index);
   }

   public int length() {
      return len;
   }

   public IString substring(int start, int end) {
      return new StringToken(baseString, startIndex + start, end - start);
   }

   public static StringToken concatTokens(StringToken t1, StringToken t2) {
      // assert t2.startIndex = t1.startIndex + len
      return new StringToken(t1.baseString, t1.startIndex, t1.len + t2.len);
   }

   public void getChars(int srcBegin, int srcEnd, char dst[], int dstBegin)  {
      baseString.getChars(startIndex + srcBegin, startIndex + srcEnd, dst, dstBegin);
   }
}
