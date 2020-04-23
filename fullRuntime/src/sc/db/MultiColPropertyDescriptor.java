package sc.db;

import java.util.Arrays;

public class MultiColPropertyDescriptor extends DBPropertyDescriptor {
   String[] extraColNames;
   String[] extraColTypes;

   public MultiColPropertyDescriptor(String propertyName, String columnNames, String columnTypes, String tableName,
                                     boolean required, boolean unique, boolean onDemand, boolean indexed, String dataSourceName,
                                     String selectGroup, String refTypeName, boolean multiRow, String reverseProperty,
                                     String dbDefault, String ownerTypeName) {
      super(propertyName, splitFirst(columnNames), splitFirst(columnTypes), tableName, required, unique, onDemand, indexed, false,
            dataSourceName, selectGroup, refTypeName, multiRow, reverseProperty, dbDefault, ownerTypeName);
      extraColNames = splitRest(columnNames);
      extraColTypes = splitRest(columnTypes);
   }

   private static String splitFirst(String names) {
      String[] namesArr = names.split(",");
      return namesArr[0];
   }

   private static String[] splitRest(String names) {
      String[] namesArr = names.split(",");
      int len = namesArr.length;
      return Arrays.copyOfRange(namesArr, 1, len);
   }

   public int getNumColumns() {
      return 1 + extraColNames.length;
   }

   public String getColumnName(int colIx) {
      if (colIx == 0)
         return columnName;
      return extraColNames[colIx-1];
   }

   public String getColumnType(int colIx) {
      if (colIx == 0)
         return columnType;
      return extraColTypes[colIx-1];
   }

}
