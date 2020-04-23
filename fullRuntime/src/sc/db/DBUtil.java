package sc.db;

import sc.dyn.DynUtil;
import sc.obj.IObjectId;
import sc.type.IBeanMapper;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import sc.type.Type;
import sc.util.IMessageHandler;
import sc.util.JSON;
import sc.util.MessageHandler;

import static sc.type.PTypeUtil.testMode;

public class DBUtil {
   public static boolean verbose = false;

   public static Map<Long,String> testIdNameMap = null;

   public static IMessageHandler msgHandler;

   public static void addTestId(long id, String idName) {
      if (testMode) {
         if (testIdNameMap == null) {
            testIdNameMap = new HashMap<Long,String>();
         }
         testIdNameMap.put(id, idName);
      }
   }

   public static Connection createConnection(String dataSourceName, boolean autoCommit) {
      try {
         DBDataSource dbDS = DataSourceManager.getDBDataSource(dataSourceName);
         DataSource javaDS = dbDS.getDataSource();
         Connection res = javaDS.getConnection();
         if (!autoCommit)
            res.setAutoCommit(false);
         return res;
      }
      catch (SQLException exc) {
         System.err.println("*** SQL error getting DB connection: " + exc);
         throw new IllegalArgumentException("SQL error getting DB connection");
      }
   }

   /** Converts from CamelCase to db_name_case  */
   public static String getSQLName(String javaName) {
      StringBuilder sb = new StringBuilder();
      boolean upper = false;
      for (int i = 0; i < javaName.length(); i++) {
         char c = javaName.charAt(i);
         if (Character.isUpperCase(c)) {
            if (i > 0)
               sb.append("_");
            upper = true;
            sb.append(Character.toLowerCase(c));
         }
         else {
            if (upper) {
               upper = false;
            }
            sb.append(c);
         }
      }
      return sb.toString();
   }

   public static String getJavaName(String sqlName) {
      StringBuilder sb = new StringBuilder();
      boolean needsUpper = true;
      for (int i = 0; i < sqlName.length(); i++) {
         char c = sqlName.charAt(i);
         if (c == '_') {
            needsUpper = true;
         }
         else if (needsUpper) {
            sb.append(Character.toUpperCase(c));
            needsUpper = false;
         }
         else {
            sb.append(c);
         }
      }
      return sb.toString();
   }

  public static void close(Connection conn, PreparedStatement stmt, ResultSet rs) {
    try {
      if (rs != null)
        rs.close();
    }
    catch (SQLException exc) {
    }
    finally {
       close(conn,stmt);
    }
  }

  public static void close(Connection conn, PreparedStatement stmt) {
    try {
      if (stmt != null)
        stmt.close();
    }
    catch (SQLException exc) {
      System.err.println("*** error closing statement: " + exc);
    }
    finally {
       close(conn);
    }
  }

  public static void close(Connection conn) {
    try {
      if (conn != null)
        conn.close();
    }
    catch (SQLException exc) {
      System.err.println("*** error closing connection: " + exc);
    }
  }

   public static void close(AutoCloseable cl) {
      try {
         if (cl != null)
            cl.close();
      }
      catch (Exception exc) {
         System.err.println("*** db error closing: " + exc);
      }
   }

   // TODO: add quoting here only if the ident was originally defined with quotes because they are case sensitive with quotes and not without...
   //  do we need to support quoting as an option through annotation specified definitions?
   public static void appendIdent(StringBuilder sb, StringBuilder logSB, String ident) {
      sb.append(ident);
      if (logSB != null)
         logSB.append(ident);
   }

   public static String formatValue(Object val, DBColumnType type) {
      if (val == null)
         return "null";
      switch (type) {
         case String:
            return "\"" + val + "\"";
         case Date:
            // We want to record dates that are in the past to catch logic errors but recent dates in the
            // test are last-modified or created-on dates that change from run to run so we return a code instead
            if (testMode) {
               Date dateVal = (Date) val;
               long dateDiff = System.currentTimeMillis() - dateVal.getTime();
               if (dateDiff >= 0 && dateDiff < 60*60*1000)
                  return "<recent-date>";
            }
            return val.toString();
         case LongId:
            if (testMode && val instanceof Long) {
               String idName = testIdNameMap == null ? null : testIdNameMap.get((Long) val);
               if (idName != null)
                  return "<id-" + idName + ">";
            }
            break;
         case Json:
            StringBuilder jsonSB = JSON.toJSON(val);
            return jsonSB.toString();
      }
      return val.toString();
   }

   private static Class pgObject = null;

   public static void setStatementValue(PreparedStatement st, int index, DBColumnType dbColumnType, Object val) throws SQLException {
      if (val == null) {
         st.setNull(index, dbColumnType.getSQLType());
         return;
      }
      switch (dbColumnType) {
         case Int:
            st.setInt(index, (Integer) val);
            break;
         case String:
            st.setString(index, (String) val);
            break;
         case Long:
         case LongId:
            st.setLong(index, (Long) val);
            break;
         case Boolean:
            st.setBoolean(index, (Boolean) val);
            break;
         case Float:
            st.setFloat(index, (Float) val);
            break;
         case Double:
            st.setDouble(index, (Double) val);
            break;
         case Date:
            java.util.Date update = (java.util.Date) val;
            //java.sql.Date sqlDate = update instanceof java.sql.Date ? (java.sql.Date) update : new java.sql.Date(update.getTime());
            java.sql.Timestamp ts = update instanceof java.sql.Timestamp ? (java.sql.Timestamp) update : new java.sql.Timestamp(update.getTime());
            st.setTimestamp(index, ts);
            break;
         case Reference:
            throw new IllegalArgumentException("type should be the id column type");
         case Json:
            String jsonStr = JSON.toJSON(val).toString();
            // TODO: using reflection here because we don't want this dependency unless using the postgresql driver.
            // We should add a general 'value converter' interface and register an implementation from the pgsql layer
            if (pgObject == null) {
               pgObject = (Class) DynUtil.findType("org.postgresql.util.PGobject");
               if (pgObject == null)
                  throw new IllegalArgumentException("Missing postgresql class: org.postgresql.util.PGobject");
            }
            Object pgo = DynUtil.createInstance(pgObject, null);
            DynUtil.setProperty(pgo, "type", "jsonb");
            DynUtil.setProperty(pgo, "value", jsonStr);
            st.setObject(index, pgo, Types.OTHER);
            break;
         default:
            throw new IllegalArgumentException("unrecognized type in setStatementValue");
      }
   }

   public static Object getResultSetByIndex(ResultSet rs, int index, DBPropertyDescriptor dbProp) throws SQLException {
      Object propertyType = dbProp.getPropertyType();
      DBColumnType colType = dbProp.getDBColumnType();
      return getResultSetByIndex(rs, index, propertyType, colType, dbProp.refDBTypeDesc);
   }

   public static Object getResultSetByName(ResultSet rs, String colName, DBPropertyDescriptor dbProp) throws SQLException {
      Object propertyType = dbProp.getPropertyType();
      DBColumnType colType = dbProp.getDBColumnType();
      return getResultSetByName(rs, colName, propertyType, colType, dbProp.refDBTypeDesc);
   }

   public static Object getResultSetByName(ResultSet rs, String colName, Object propertyType, DBColumnType colType, DBTypeDescriptor refType) throws SQLException {
      switch (colType) {
         case Int:
            Object res = rs.getObject(colName);
            if (res == null || rs.wasNull())
               return res;
            if (res instanceof Integer)
               return res;
            else {
               System.err.println("*** Unrecognized result for integer property");
               return res;
            }
         case String:
            String sres = rs.getString(colName);
            if (rs.wasNull())
               return null;
            return sres;
         case Long:
         case LongId:
            Long lres = rs.getLong(colName);
            if (rs.wasNull())
               return null;
            return lres;
         case Boolean:
            Boolean bres = rs.getBoolean(colName);
            if (rs.wasNull())
               return null;
            return bres;
         case Float:
            Float fres = rs.getFloat(colName);
            if (rs.wasNull())
               return null;
            return fres;
         case Double:
            Double dres = rs.getDouble(colName);
            if (rs.wasNull())
               return null;
            return dres;
         case Date:
            java.sql.Timestamp sqlDate = rs.getTimestamp(colName);
            if (rs.wasNull())
               return null;
            return sqlDate;
         case Json:
            Object ores = rs.getObject(colName);
            if (ores == null)
               return null;
            if (ores.getClass().getName().contains("PGobject")) {
               String jsonStr = (String) DynUtil.getPropertyValue(ores, "value");
               if (jsonStr == null || jsonStr.length() == 0)
                  return null;
               return JSON.toObject(propertyType, jsonStr);
            }
            else
               throw new UnsupportedOperationException("Unrecognized type from getObject");
         case Reference:
            if (refType == null)
               throw new UnsupportedOperationException("No refType provided for Reference column");
            return getResultSetByName(rs, colName, refType.primaryTable.idColumns.get(0));
         default:
            throw new UnsupportedOperationException("Unrecognized type from getObject");
      }

   }

   public static Object getResultSetByIndex(ResultSet rs, int index, Object propertyType, DBColumnType colType, DBTypeDescriptor refType) throws SQLException {
      if (colType == null) {
         Object res = rs.getObject(index);
         if (res == null || rs.wasNull())
            return null;
         return res;
      }
      switch (colType) {
         case Int:
            Object res = rs.getObject(index);
            if (res == null || rs.wasNull())
               return null;
            if (res instanceof Integer)
               return res;
            else {
               System.err.println("*** Unrecognized result for integer property");
               return res;
            }
         case String:
            String sres = rs.getString(index);
            if (rs.wasNull())
               return null;
            return sres;
         case Long:
         case LongId:
            Object lres = rs.getObject(index);
            if (lres == null || rs.wasNull())
               return null;
            if (lres instanceof Long)
               return lres;
            else
               System.err.println("*** Unrecognized result type for long property");
            return lres;
         case Boolean:
            Boolean bres = rs.getBoolean(index);
            if (rs.wasNull())
               return null;
            return bres;
         case Float:
            Float fres = rs.getFloat(index);
            if (rs.wasNull())
               return null;
            return fres;
         case Double:
            Double dres = rs.getDouble(index);
            if (rs.wasNull())
               return null;
            return dres;
         case Date:
            java.sql.Timestamp sqlDate = rs.getTimestamp(index);
            if (rs.wasNull())
               return null;
            return sqlDate;
         case Json:
            Object ores = rs.getObject(index);
            if (ores == null)
               return null;
            if (ores.getClass().getName().contains("PGobject")) {
               String jsonStr = (String) DynUtil.getPropertyValue(ores, "value");
               if (jsonStr == null || jsonStr.length() == 0)
                  return null;
               return JSON.toObject(propertyType, jsonStr);
            }
            else
               throw new UnsupportedOperationException("Unrecognized type from getObject");
         case Reference:
            if (refType == null)
               throw new UnsupportedOperationException("No refType provided for Reference column");
            return getResultSetByIndex(rs, index, refType.primaryTable.idColumns.get(0));
         default:
            throw new UnsupportedOperationException("Unrecognized type from getObject");
      }
   }

   public static String getDefaultSQLType(Object propertyType, boolean dbDefinedId) {
      if (propertyType == Integer.class || propertyType == Integer.TYPE)
         return dbDefinedId ? "serial" : "integer";
      else if (propertyType == String.class)
         return "text";
      else if (propertyType == Long.class || propertyType == Long.TYPE)
         return dbDefinedId ? "bigserial" : "bigint";
      else if (propertyType == Boolean.class || propertyType == Boolean.TYPE)
         return "boolean";
      else if (propertyType == Float.class || propertyType == Float.TYPE)
         return "real";
      else if (propertyType == Double.class || propertyType == Double.TYPE)
         return "double";
      else if (propertyType == java.util.Date.class || propertyType == java.sql.Date.class)
         return "timestamp";
      else // TODO: BigDecimal, byte array, char, Character - size limit for strings through an annotation
         return null;
   }

   public static String getJavaTypeFromSQLType(String type) {
      if (type.equalsIgnoreCase("serial") || type.equalsIgnoreCase("integer"))
         return "int";
      else if (type.equalsIgnoreCase("bigserial") || type.equalsIgnoreCase("bigint"))
         return "long";
      else if (type.toLowerCase().startsWith("varchar") || type.equalsIgnoreCase("text"))
         return "String";
      else if (type.equalsIgnoreCase("boolean"))
         return "boolean";
      else if (type.equalsIgnoreCase("real"))
         return "float";
      else if (type.equalsIgnoreCase("double"))
         return "double";
      else if (type.equalsIgnoreCase("timestamp"))
         return "java.util.Date";
      return null;
   }

   public static boolean isDefinedInDBColumnType(String colType) {
      return colType.equals("bigserial") || colType.equals("serial");
   }

   public static String getKeyIdColumnType(String type) {
      if (type.equalsIgnoreCase("serial"))
         return "integer";
      else if (type.equalsIgnoreCase("bigserial"))
         return "bigint";
      return type;
   }

   public static void info(CharSequence... msgs) {
      MessageHandler.info(msgHandler, msgs);
   }

   public static void warn(CharSequence... msgs) {
      MessageHandler.warning(msgHandler, msgs);
   }

   public static void verbose(CharSequence... msgs) {
      if (verbose)
         MessageHandler.info(msgHandler, msgs);
   }

   public static void error(CharSequence... msgs) {
      MessageHandler.error(msgHandler, msgs);
   }

   public static String replaceNextParam(String logStr, Object colVal, DBColumnType colType) {
      int ix = logStr.indexOf('?');
      if (ix == -1) {
         System.err.println("*** replaceNextParam - found no param");
         return logStr;
      }
      StringBuilder res = new StringBuilder();
      res.append(logStr.substring(0, ix));
      res.append(DBUtil.formatValue(colVal, colType));
      res.append(logStr.substring(ix+1));
      return res.toString();
   }

   public static String toIdString(Object inst) {
      if (inst instanceof IObjectId)
         return ((IObjectId) inst).getObjectId();
      else
         return inst.toString();
   }

   public static void appendVal(StringBuilder logSB, Object val, DBColumnType colType) {
     if (val instanceof IDBObject)
        logSB.append(((IDBObject) val).getDBObject());
     else if (val instanceof CharSequence) {
        logSB.append("'");
        logSB.append(val);
        logSB.append("'");
     }
     else if (colType != null) {
        logSB.append(formatValue(val, colType));
     }
     else
        logSB.append(val);
   }

   static void append(StringBuilder sb, StringBuilder logSB, CharSequence val) {
      sb.append(val);
      if (logSB != null) {
         logSB.append(val);
      }
   }

   public static CharSequence cvtJavaToSQLOperator(String operator) {
      if (operator.equals("instanceof"))
         throw new UnsupportedOperationException();
      if (operator.equals("=="))
         return "=";
      else if (operator.equals("&&"))
         return "AND";
      else if (operator.equals("||"))
         return "OR";
      return operator;
   }

   public static StringBuilder readInputStream(InputStream is) {
      StringBuilder sb = new StringBuilder();

      BufferedReader bis = new BufferedReader(new InputStreamReader(is));
      char [] buf = new char[4096];
      int len;
      try {
         while ((len = bis.read(buf, 0, buf.length)) != -1) {
            sb.append(new String(buf, 0, len));
         }
         return sb;
      }
      catch (IOException exc) {
         System.err.println("*** Failed to read from input stream: " + exc);
      }
      return null;
   }

   public static String convertSQLToJavaTypeName(String sqlTypeName) {
      String javaTypeName = getJavaTypeFromSQLType(sqlTypeName);
      if (javaTypeName != null)
         return javaTypeName;
      DBTypeDescriptor dbTypeDesc = DBTypeDescriptor.getByTableName(javaTypeName);
      if (dbTypeDesc != null)
         return dbTypeDesc.getTypeName();
      return "Object";
   }

   public static boolean isDefaultJSONComponentType(Object componentType) {
      if (componentType instanceof Class) {
         Type type = Type.get((Class)componentType);
         return type != Type.Object;
      }
      return false;
   }

   public static boolean canCast(DBColumnType fromType, DBColumnType toType) {
      if (fromType == toType)
         return true;
      // TODO: others we should add here?
      return false;
   }

   public static String getJSONCastType(DBColumnType dbColumnType) {
      switch (dbColumnType) {
         case Int:
            return "integer";
         case Long:
         case LongId:
            return "bigint";
         case Boolean:
            return "boolean";
         case Float:
            return "real";
         case Double:
            return "double";
         case Date:
            return "timestamp";
         case String:
            return null;
         default:
            System.err.println("*** Unrecognized type for JSON cast");
            break;
      }
      return null;
   }
}
