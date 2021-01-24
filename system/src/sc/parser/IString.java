/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

public interface IString extends CharSequence {
   String toString();

   char charAt(int index);

   int length();

   IString substring(int start, int end);

   IString substring(int start);

   boolean startsWith(CharSequence other);

   void getChars(int srcBegin, int srcEnd, char dst[], int dstBegin); 
}
