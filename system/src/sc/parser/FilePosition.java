/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

public class FilePosition {
   public int lineNum;
   public int colNum;
   public FilePosition(int l, int c) {
      lineNum = l;
      colNum = c;
   }
}
