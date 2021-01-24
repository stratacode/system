/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

public abstract class AbstractString implements IString {
   int hc = -333;

   public IString substring(int beginIndex)  {
      return substring(beginIndex, length());
   }

   public IString substring(int start, int end) {
      return new StringToken(this, start, end-start);
   }

   public boolean startsWith(CharSequence other) {
      if (other == null)
         return false;

      if (other.length() > length())
         return false;

      for (int i = 0; i < other.length(); i++)
         if (charAt(i) != other.charAt(i))
            return false;
      return true;
   }


   public int hashCode() {
      if (hc != -333) {
         return hc;
      }

      int hash = 0;
      int len = length();
      for (int i = 0; i < len; i++) {
         int inputChar = charAt(i);
         hash = 31 * hash + inputChar;
      }
      hc = hash;
      return hash;
   }

   public boolean equals(Object obj) {
      if (obj == this)
         return true;

      if (obj instanceof String) {
         String s = (String) obj;
         int len = length();
         if (s.length() != len)
            return false;

         if (s.hashCode() != hashCode())
            return false;

         for (int i = 0; i < len; i++)
            if (s.charAt(i) != charAt(i))
               return false;
      }
      else if (obj instanceof IString) {
         IString s = (IString) obj;
         if (s.length() != length())
            return false;

         if (s.hashCode() != hashCode())
            return false;

         int len = length();
         for (int i = 0; i < len; i++)
            if (s.charAt(i) != charAt(i))
               return false;
      }
      else
         return false;
      return true;
   }

   public void getChars(int srcBegin, int srcEnd, char dst[], int dstBegin)  {
      int dstOffset = dstBegin - srcBegin;
      for (int i = srcBegin; i < srcEnd; i++)
         dst[i-dstOffset] = charAt(i);
   }

   public CharSequence subSequence(int start, int end) {
      return substring(start, end);
   }

   public boolean equalsIgnoreCase(CharSequence other) {
      if (other == null)
         return false;
      int len = length();
      int olen = other.length();
      if (len != olen)
         return false;
      for (int i = 0; i < len; i++) {
         char c = charAt(i);
         char o = other.charAt(i);
         if (Character.toUpperCase(c) != Character.toUpperCase(o))
            return false;
         if (Character.toLowerCase(c) != Character.toLowerCase(o))
            return false;
      }
      return true;
   }
}
