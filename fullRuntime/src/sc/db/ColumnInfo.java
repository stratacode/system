/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.db;

public class ColumnInfo {
   public String colName;
   public int colType;
   public String size;
   public int numDigits;
   public boolean isNullable;

   public StringBuilder diffMessage;

   public String toString() {
      return colName + " " + DBUtil.getNameForJDBCType(colType);
   }
}
