package sc.db;

import java.sql.Types;

public enum DBColumnType {
   Int, Long, String, Float, Double, Boolean, Json, Reference, Date;

   public static DBColumnType fromJavaType(Object propertyType) {
      if (propertyType == Integer.class || propertyType == Integer.TYPE) {
         return DBColumnType.Int;
      }
      else if (propertyType == String.class || propertyType == Character.class) {
         return DBColumnType.String;
      }
      else if (propertyType == Long.class || propertyType == java.lang.Long.TYPE) {
         return DBColumnType.Long;
      }
      else if (propertyType == Boolean.class || propertyType == java.lang.Boolean.TYPE) {
         return DBColumnType.Boolean;
      }
      else if (propertyType == Float.class)
         return DBColumnType.Float;
      else if (propertyType == Double.class)
         return DBColumnType.Double;
      else if (propertyType == java.util.Date.class)
         return DBColumnType.Date;
      else
         return null;
   }

   public int getSQLType() {
      switch (this) {
         case Int:
            return Types.INTEGER;
         case Long:
            return Types.BIGINT;
         case String:
            return Types.VARCHAR;
         case Float:
            return Types.REAL;
         case Double:
            return Types.DOUBLE;
         case Boolean:
            return Types.BIT;
         case Json:
         case Reference:
            return Types.OTHER;
         case Date:
            return Types.TIMESTAMP;
      }
      throw new UnsupportedOperationException("Missing value in getSQLType");
   }
}


