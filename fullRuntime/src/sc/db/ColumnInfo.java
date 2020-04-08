package sc.db;

import java.sql.Types;

public class ColumnInfo {
   public String colName;
   public int colType;
   public String size;
   public int numDigits;
   public boolean isNullable;

   public StringBuilder diffMessage;

   public String toString() {
      return colName;
   }
}
