package sc.db;

import java.sql.Types;

public enum DBColumnType {
   Int, Long, String, Float, Double, Boolean, Json, Reference, Date, LongId;

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
         case LongId:
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

   public static DBColumnType fromColumnType(String columnType) {
      if (columnType.equalsIgnoreCase("varchar") || columnType.equals("text") || columnType.equals("nvarchar") || columnType.equals("ntext"))
         return String;
      else if (columnType.equalsIgnoreCase("integer") || columnType.equals("serial"))
         return Int;
      else if (columnType.equalsIgnoreCase("bigint") || columnType.equals("bigserial"))
         return Long;
      else if (columnType.equalsIgnoreCase("tinyint"))
         return Int;
      else if (columnType.equalsIgnoreCase("smallint"))
         return Int;
      else if (columnType.equalsIgnoreCase("float"))
         return Float;
      else if (columnType.equalsIgnoreCase("real") || columnType.equals("numeric"))
         return Double;
      else if (columnType.equalsIgnoreCase("bit"))
         return Boolean;
      else if (columnType.equalsIgnoreCase("date") || columnType.equalsIgnoreCase("time") ||
              columnType.equalsIgnoreCase("datetime") || columnType.equals("timestamp"))
         return Date;
      else if (columnType.equalsIgnoreCase("json") || columnType.equalsIgnoreCase("jsonb"))
         return Json;
      return null;
   }
}


