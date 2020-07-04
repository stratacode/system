package sc.db;

import sc.dyn.DynUtil;

import java.sql.Types;

public enum DBColumnType {
   Int, Long, String, Float, Double, Boolean, Json, Reference, Date, LongId, Numeric, EnumInt, EnumDB, ByteArray;

   public static DBColumnType fromJavaType(Object propertyType) {
      if (propertyType == java.lang.Integer.class || propertyType == java.lang.Integer.TYPE) {
         return DBColumnType.Int;
      }
      else if (propertyType == java.lang.String.class || propertyType == java.lang.Character.class) {
         return DBColumnType.String;
      }
      else if (propertyType == java.lang.Long.class || propertyType == java.lang.Long.TYPE) {
         return DBColumnType.Long;
      }
      else if (propertyType == java.lang.Boolean.class || propertyType == java.lang.Boolean.TYPE) {
         return DBColumnType.Boolean;
      }
      else if (propertyType == java.lang.Float.class || propertyType == java.lang.Float.TYPE)
         return DBColumnType.Float;
      else if (propertyType == java.lang.Double.class || propertyType == java.lang.Double.TYPE)
         return DBColumnType.Double;
      else if (propertyType == java.util.Date.class)
         return DBColumnType.Date;
      else if (propertyType == java.math.BigDecimal.class)
         return DBColumnType.Numeric;
      else if (DynUtil.isEnumType(propertyType)) {
         DBEnumDescriptor enumDesc = DBEnumDescriptor.getByType(propertyType, false);
         if (enumDesc != null)
            return EnumDB;
         return EnumInt;
      }
      else if (DynUtil.isArray(propertyType)) {
         Object compType = DynUtil.getComponentType(propertyType);
         if (compType == Byte.TYPE)
            return ByteArray;
         return null;
      }
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
         case Numeric:
            return Types.NUMERIC;
         case EnumInt:
            return Types.INTEGER;
         case EnumDB:
            return Types.VARCHAR;
         case ByteArray:
            return Types.BLOB;
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
      else if (columnType.equalsIgnoreCase("float") || columnType.equalsIgnoreCase("real"))
         return Float;
      else if (columnType.equals("double precision"))
         return Double;
      else if (columnType.equals("numeric"))
         return Numeric;
      else if (columnType.equalsIgnoreCase("bit"))
         return Boolean;
      else if (columnType.equalsIgnoreCase("date") || columnType.equalsIgnoreCase("time") ||
              columnType.equalsIgnoreCase("datetime") || columnType.equals("timestamp"))
         return Date;
      else if (columnType.equalsIgnoreCase("json") || columnType.equalsIgnoreCase("jsonb"))
         return Json;
      else if (columnType.equalsIgnoreCase("bytea"))
         return ByteArray;
      return null;
   }
}


