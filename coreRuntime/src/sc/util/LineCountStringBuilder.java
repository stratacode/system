/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import java.io.IOException;

/**
 * If only there were a base class to StringBuilder that implemented the same contract but which we could extend
 * but no, this is an attempt to replicate the key methods using an underlying StringBuilder, keeping track
 * of the line number.
 * TODO: keep track of column too?
 */
public class LineCountStringBuilder implements CharSequence {
   public StringBuilder sb = new StringBuilder();
   public int lineCount = 1; // First line of the file is 1 not 0

   public StringBuilder append(CharSequence csq) {
      if (csq == null)
         return sb;
      int len = csq.length();
      for (int i = 0; i < len; i++)
         if (csq.charAt(i) == '\n')
            lineCount++;
      return sb.append(csq);
   }

   public StringBuilder append(CharSequence csq, int start, int end) throws IOException {
      for (int i = start; i < end; i++)
         if (csq.charAt(i) == '\n')
            lineCount++;
      return sb.append(csq, start, end);
   }

   public StringBuilder append(char c) throws IOException {
      if (c == '\n')
         lineCount++;
      return sb.append(c);
   }

   public StringBuilder append(Object obj) {
      return append(String.valueOf(obj));
   }

   public StringBuilder append(boolean b) {
      return sb.append(b);
   }

   public StringBuilder append(int i) {
      return sb.append(i);
   }

   public StringBuilder append(long lng) {
      return sb.append(lng);
   }

   public StringBuilder append(float f) {
      return sb.append(f);
   }

   public StringBuilder append(double d) {
      return sb.append(d);
   }

   @Override
   public int length() {
      return sb.length();
   }

   @Override
   public char charAt(int index) {
      return sb.charAt(index);
   }

   @Override
   public CharSequence subSequence(int start, int end) {
      return sb.subSequence(start, end);
   }

   public String toString() {
      return sb.toString();
   }
}
