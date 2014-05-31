/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import java.util.ArrayList;
import java.util.List;

public class PStringBuffer extends AbstractString {
   List<IString> baseStrings;
   boolean lenInvalid = true;
   int len = -1;

   public PStringBuffer(IString[] parr) {
      baseStrings = new ArrayList(parr.length);
      for (IString p:parr)
         baseStrings.add(p);
   }

   public String toString() {
      char[] buf = new char[length()];
      int currentDstIx = 0;
      for (int i = 0; i < baseStrings.size(); i++)
      {
         IString bstr = baseStrings.get(i);
         int srcLen = bstr.length();
         bstr.getChars(0, srcLen, buf, currentDstIx);
         currentDstIx += srcLen;
      }
      len = currentDstIx;
      lenInvalid = false;
      return new String(buf);
   }

   public char charAt(int index) {
      int currentDstIx = 0;
      for (int i = 0; i < baseStrings.size(); i++) {
         IString bstr = baseStrings.get(i);
         int oldDstIx = currentDstIx;
         currentDstIx += bstr.length();
         if (index < currentDstIx)
            return bstr.charAt(index - oldDstIx);
      }
      throw new IllegalArgumentException("chatAt(" + index + ") >= string size: " + currentDstIx);
   }

   public int length() {
      if (lenInvalid) {
         int newLen = 0;
         for (int i = 0; i < baseStrings.size(); i++) {
            IString bstr = baseStrings.get(i);
            newLen += bstr.length();
         }
         len = newLen;
         lenInvalid = false;
      }
      return len;
   }

   public IString substring(int start, int end) {
      throw new UnsupportedOperationException("todo");
   }

}
