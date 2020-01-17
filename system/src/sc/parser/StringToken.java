/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

public class StringToken extends AbstractString {
   IString baseString;
   public boolean toLower;

   public int startIndex;
   public int len;

   public StringToken(IString p) {
      baseString = p;
   }

   public StringToken(IString p, int start, int l) {
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
      char c = baseString.charAt(startIndex + index);
      if (toLower)
         return Character.toLowerCase(c);
      return c;
   }

   public int length() {
      return len;
   }

   public IString substring(int start, int end) {
      return new StringToken(baseString, startIndex + start, end - start);
   }

   public static CharSequence concatTokens(StringToken t1, StringToken t2) {
      if (t1.startIndex + t1.len == t2.startIndex) {
         // assert t2.startIndex = t1.startIndex + len
         return new StringToken(t1.baseString, t1.startIndex, t1.len + t2.len);
      }
      else {
         // Hopefully this is an odd case or we should speed this up.  It happens when there was some whitespace or
         // non-semantic value in between the tokens we are turning into a String.
         StringBuilder sb = new StringBuilder();
         sb.append(t1.toString());
         sb.append(t2.toString());
         return sb.toString();
      }
   }

   public void getChars(int srcBegin, int srcEnd, char dst[], int dstBegin)  {
      baseString.getChars(startIndex + srcBegin, startIndex + srcEnd, dst, dstBegin);
   }
}
