/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.db;

import sc.dyn.DynUtil;
import sc.obj.IObjectId;
import sc.type.IBeanMapper;

import javax.sql.DataSource;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.*;

import sc.type.Type;
import sc.util.*;

import java.security.MessageDigest;

import static sc.type.PTypeUtil.testMode;

public class DBUtil {
   public static boolean verbose = false;

   public static Map<String,Map<Long,String>> testIdNameMap = null;
   public static WeakIdentityHashMap<IDBObject, String> testInstMap = null;
   public static Map<String,String> testTokens = null;

   public static IMessageHandler msgHandler;

   public static class TestValueReplacer implements IValueReplacer {
      public static Object replaceDate(Date dateVal) {
         long millisAgo = System.currentTimeMillis() - dateVal.getTime();
         if (millisAgo >= 0) {
            if (millisAgo < epsilonMillis)
               return "<recent-date>";
         }
         else {
            long millisAhead = -millisAgo;
            if (millisAhead < monthMillis + epsilonMillis && millisAhead > monthMillis - epsilonMillis)
               return "<month-from-now-date>";
         }
         return dateVal;
      }

      public Object replaceValue(Object orig) {
         if (!testMode)
            return orig;

         if (orig instanceof Date) {
            return replaceDate((Date) orig);
         }
         else if (orig instanceof IDBObject) {
            IDBObject dbObj = ((IDBObject) orig);
            Object id = dbObj.getDBId();
            if (id instanceof Long) {
               String testId = getTestId(((DBObject) dbObj.getDBObject()).dbTypeDesc, (Long) id);
               if (testId != null)
                  return testId;
            }
         }
         else if (orig instanceof CharSequence && testTokens != null) {
            String testToken = testTokens.get(orig.toString());
            if (testToken != null)
               return testToken;
         }
         return orig;
      }
   }

   public static TestValueReplacer testReplacer = new TestValueReplacer();

   public static void addTestId(DBTypeDescriptor typeDesc, long id, String idName) {
      if (testMode) {
         if (testIdNameMap == null) {
            testIdNameMap = new HashMap<String,Map<Long,String>>();
         }
         Map<Long,String> idMap = testIdNameMap.get(typeDesc.getBaseTypeName());
         if (idMap == null) {
            idMap = new HashMap<Long,String>();
            testIdNameMap.put(typeDesc.getBaseTypeName(), idMap);
         }
         idMap.put(id, idName);
      }
   }

   public static void addTestToken(String token, String name) {
      if (!testMode)
         return;
      if (testTokens == null)
         testTokens = new HashMap<String,String>();
      testTokens.put(token, name);
   }

   public static String getTestId(DBTypeDescriptor typeDesc, long id) {
      if (testIdNameMap == null)
         return null;

      Map<Long,String> idMap = testIdNameMap.get(typeDesc.getBaseTypeName());
      if (idMap == null)
         return null;
      return idMap.get(id);
   }

   public static void addTestIdInstance(IDBObject inst, String idName) {
      if (testMode) {
         DBObject dbObj = (DBObject) inst.getDBObject();
         if (!dbObj.isPrototype() && !dbObj.isTransient())
            addTestId(dbObj.dbTypeDesc, (Long) dbObj.getDBId(), idName);
         else {
            if (testInstMap == null)
               testInstMap = new WeakIdentityHashMap<IDBObject, String>();
            String oldIdName = testInstMap.put(inst, idName);
            if (oldIdName != null)
               info("replaced instance id: " + oldIdName + " with: " + idName);
         }
      }
   }

   public static void mapTestInstance(IDBObject inst) {
      if (testMode && testInstMap != null) {
         String idName = testInstMap.get(inst);
         if (idName != null) {
            testInstMap.remove(inst);
            Object id = inst.getDBId();
            if (id instanceof Long) {
               addTestId(((DBObject) inst.getDBObject()).dbTypeDesc, (Long) id, idName);
            }
            else
               DBUtil.error("*** Unrecognized id type in mapTestInstance");
         }
      }
   }

   public static Connection createConnection(String dataSourceName, boolean autoCommit) {
      try {
         DBDataSource dbDS = DataSourceManager.getDBDataSource(dataSourceName);
         if (dbDS == null) {
            throw new IllegalArgumentException("No dataSource found: " + dataSourceName);
         }
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

   private static final long epsilonMillis = 60*60*1000L; // less than one hour ago
   private static final long monthMillis = 30*24*60*60*1000L; // 30 days ago

   public static String formatValue(Object val, DBColumnType type, DBTypeDescriptor refType, Object pType) {
      if (val == null)
         return "null";
      switch (type) {
         case String:
            if (testMode) {
               if (testTokens != null) {
                  String tokenId = testTokens.get(val.toString());
                  if (tokenId != null) {
                     return "<" + tokenId + ">";
                  }
               }
            }
            return "\"" + val + "\"";
         case Date:
            // We want to record dates that are in the past to catch logic errors but recent dates in the
            // test are last-modified or created-on dates that change from run to run so we return a code instead
            if (testMode) {
               Date dateVal = (Date) val;
               Object newVal = TestValueReplacer.replaceDate(dateVal);
               if (newVal instanceof String)
                  return (String) newVal;
            }
            return "'" + DynUtil.formatDate((Date) val) + "'";
         case LongId:
            if (testMode && val instanceof Long) {
               if (refType != null) {
                  String idName = getTestId(refType, (Long) val);
                  if (idName != null)
                     return "<id-" + idName + ">";
               }
               else
                  System.err.println("*** Missing ref type for id column");
            }
            break;
         case Json:
            StringBuilder jsonSB = JSON.toJSON(val, pType, testMode ? testReplacer : null);
            return jsonSB.toString();
         case ByteArray: {
            if (testMode)
               return "<bytearray>";
            return base64Encoder.encodeToString((byte[]) val);
         }
         case Inet:
         case Cidr:
            return val.toString();

      }
      return val.toString();
   }

   private static Class pgObjectClass = null;

   private static Class getPGObjectClass() {
      if (pgObjectClass == null) {
         pgObjectClass = (Class) DynUtil.findType("org.postgresql.util.PGobject");
         if (pgObjectClass == null)
            throw new IllegalArgumentException("Missing postgresql class: org.postgresql.util.PGobject - check for postgres jdbc library in classpath");
      }
      return pgObjectClass;
   }

   public static void setStatementValue(PreparedStatement st, int index, DBColumnType dbColumnType, Object val, Object pType) throws SQLException {
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
         case Numeric:
            st.setBigDecimal(index, (BigDecimal) val);
            break;
         case Date:
            java.util.Date update = (java.util.Date) val;
            //java.sql.Date sqlDate = update instanceof java.sql.Date ? (java.sql.Date) update : new java.sql.Date(update.getTime());
            java.sql.Timestamp ts = update instanceof java.sql.Timestamp ? (java.sql.Timestamp) update : new java.sql.Timestamp(update.getTime());
            st.setTimestamp(index, ts);
            break;
         case EnumInt:
            java.lang.Enum enumVal = (Enum) val;
            st.setInt(index, enumVal.ordinal());
            break;
         case EnumDB:
            enumVal = (Enum) val;
            st.setObject(index, enumVal.name(), Types.OTHER);
            break;
         case Reference:
            if (!(val instanceof IDBObject))
               throw new IllegalArgumentException("type should be the id column type");
            // TODO: support id type conversion if necessary? Multi-columns
            st.setObject(index, ((IDBObject) val).getDBId());
            break;
         case Json:
            String jsonStr = JSON.toJSON(val, pType, null).toString();
            // TODO: using reflection here because we don't want this dependency unless using the postgresql driver.
            // We should add a general 'value converter' interface and register an implementation from the pgsql layer
            Class pgObjectCl = getPGObjectClass();
            Object pgo = DynUtil.createInstance(pgObjectCl, null);
            DynUtil.setProperty(pgo, "type", "jsonb");
            DynUtil.setProperty(pgo, "value", jsonStr);
            st.setObject(index, pgo, Types.OTHER);
            break;
         case ByteArray:
            st.setBytes(index, (byte[]) val);
            break;
         case Inet:
         case Cidr:
            pgObjectCl = getPGObjectClass();
            Object pginet = DynUtil.createInstance(pgObjectCl, null);
            DynUtil.setProperty(pginet, "type", dbColumnType == DBColumnType.Inet ? "inet" : "cidr");
            DynUtil.setProperty(pginet, "value", val.toString());
            st.setObject(index, pginet, Types.OTHER);
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

   static JSONResolver refResolver = new JSONResolver() {
      public Object resolveRef(String refName, Object propertyType) {
         if (refName.startsWith("db:")) {
            String idStr = refName.substring(3);
            DBTypeDescriptor typeDesc = DBTypeDescriptor.getByType(propertyType, true);
            Object dbId = typeDesc.stringToId(idStr);
            return typeDesc.findById(dbId);
         }
         else
            return DynUtil.resolveName(refName, true, false);
      }
      public Object resolveClass(String className) {
         Object instType = DynUtil.findType(className);
         if (instType == null) {
            System.err.println("Database reference to missing class: " + className);
            throw new IllegalArgumentException("Database reference to missing class: " + className);
         }
         return instType;
      }
   };

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
         case Numeric:
            BigDecimal bdres = rs.getBigDecimal(colName);
            if (rs.wasNull())
               return null;
            return bdres;
         case Date:
            java.sql.Timestamp sqlDate = rs.getTimestamp(colName);
            if (rs.wasNull())
               return null;
            return sqlDate;
         case EnumInt:
            res = rs.getObject(colName);
            if (res == null || rs.wasNull())
               return res;
            if (res instanceof Integer) {
               int ival = (Integer) res;
               Object[] enumConsts = DynUtil.getEnumConstants(propertyType);
               if (ival >= 0 && ival < enumConsts.length)
                  return enumConsts[ival];
               else
                  error("Invalid integer value: " + ival + " for enum type: " + propertyType);
            }
            else {
               System.err.println("*** Unrecognized result for code based Enum property");
            }
            return null;
         case EnumDB:
            sres = rs.getString(colName);
            if (rs.wasNull())
               return null;
            return DynUtil.getEnumConstant(propertyType, sres);
         case Json:
            Object ores = rs.getObject(colName);
            if (ores == null)
               return null;
            if (ores.getClass().getName().contains("PGobject")) {
               String jsonStr = (String) DynUtil.getPropertyValue(ores, "value");
               if (jsonStr == null || jsonStr.length() == 0)
                  return null;
               return JSON.toObject(propertyType, jsonStr, refResolver);
            }
            else
               throw new UnsupportedOperationException("Unrecognized result set type from getObject for json property");
         case Reference:
            if (refType == null)
               throw new UnsupportedOperationException("No refType provided for Reference column");
            return getResultSetByName(rs, colName, refType.primaryTable.idColumns.get(0));
         case ByteArray:
            return rs.getBytes(colName);
         case Inet:
         case Cidr:
            Object inres = rs.getObject(colName);
            if (inres == null)
               return null;
            if (inres.getClass().getName().contains("PGobject")) {
               String inetStr = (String) DynUtil.getPropertyValue(inres, "value");
               if (inetStr == null || inetStr.length() == 0)
                  return null;
               return inetStr;
            }
            else
               throw new UnsupportedOperationException("Unrecognized type from getObject for inet column");
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
         case Numeric:
            BigDecimal bdres = rs.getBigDecimal(index);
            if (rs.wasNull())
               return null;
            return bdres;
         case Date:
            java.sql.Timestamp sqlDate = rs.getTimestamp(index);
            if (rs.wasNull())
               return null;
            return sqlDate;
         case EnumInt:
            res = rs.getObject(index);
            if (res == null || rs.wasNull())
               return res;
            if (res instanceof Integer) {
               int ival = (Integer) res;
               Object[] enumConsts = DynUtil.getEnumConstants(propertyType);
               if (ival >= 0 && ival < enumConsts.length)
                  return enumConsts[ival];
               else
                  error("Invalid integer value: " + ival + " for enum type: " + propertyType);
            }
            else {
               System.err.println("*** Unrecognized result for code based Enum property");
            }
            return null;
         case EnumDB:
            sres = rs.getString(index);
            if (rs.wasNull())
               return null;
            return DynUtil.getEnumConstant(propertyType, sres);
         case Json:
            Object ores = rs.getObject(index);
            if (ores == null)
               return null;
            if (ores.getClass().getName().contains("PGobject")) {
               String jsonStr = (String) DynUtil.getPropertyValue(ores, "value");
               if (jsonStr == null || jsonStr.length() == 0)
                  return null;
               return JSON.toObject(propertyType, jsonStr, refResolver);
            }
            else
               throw new UnsupportedOperationException("Unrecognized type from getObject");
         case Reference:
            if (refType == null)
               throw new UnsupportedOperationException("No refType provided for Reference column");
            return getResultSetByIndex(rs, index, refType.primaryTable.idColumns.get(0));
         case ByteArray:
            return rs.getBytes(index);
         case Inet:
         case Cidr:
            Object inres = rs.getObject(index);
            if (inres == null)
               return null;
            if (inres.getClass().getName().contains("PGobject")) {
               String inStr = (String) DynUtil.getPropertyValue(inres, "value");
               if (inStr == null || inStr.length() == 0)
                  return null;
               return inStr;
            }
            else
               throw new UnsupportedOperationException("Unrecognized type from getObject for inet/cidr column");
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
         return "double precision"; // Should we use numeric here?
      else if (propertyType == java.util.Date.class || propertyType == java.sql.Date.class)
         return "timestamp";
      else if (propertyType == java.math.BigDecimal.class)
         return "numeric";
      else if (propertyType instanceof Class) {
         Class propertyClass = (Class) propertyType;
         if (propertyClass.isArray() && propertyClass.getComponentType() == Byte.TYPE)
            return "bytea";
         return null;
      }
      else // TODO: BigDecimal, char, Character - size limit for strings through an annotation
         return null;
   }

   public static String getJavaTypeFromSQLType(String type) {
      if (type.equalsIgnoreCase("serial") || type.equalsIgnoreCase("integer") || type.equalsIgnoreCase("int") || type.equalsIgnoreCase("int4"))
         return "int";
      else if (type.equalsIgnoreCase("bigserial") || type.equalsIgnoreCase("bigint"))
         return "long";
      else if (type.toLowerCase().startsWith("varchar") || type.equalsIgnoreCase("text"))
         return "String";
      else if (type.equalsIgnoreCase("boolean"))
         return "boolean";
      else if (type.equalsIgnoreCase("real"))
         return "float";
      else if (type.equalsIgnoreCase("double precision"))
         return "double";
      else if (type.equalsIgnoreCase("timestamp"))
         return "java.util.Date";
      else if (type.equalsIgnoreCase("numeric"))
         return "java.math.BigDecimal";
      else if (type.equalsIgnoreCase("bytea"))
         return "byte[]";
      else if (type.equalsIgnoreCase("inet") || type.equalsIgnoreCase("cidr"))
         return "String";
      return null;
   }

   /** The column types that generate their default value */
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

   public static String replaceNextParam(String logStr, Object colVal, DBColumnType colType, DBTypeDescriptor refType) {
      int ix = logStr.indexOf('?');
      if (ix == -1) {
         System.err.println("*** replaceNextParam - found no param");
         return logStr;
      }
      StringBuilder res = new StringBuilder();
      res.append(logStr.substring(0, ix));
      res.append(DBUtil.formatValue(colVal, colType, refType, null));
      res.append(logStr.substring(ix+1));
      return res.toString();
   }

   public static String toIdString(Object inst) {
      if (inst instanceof IObjectId)
         return ((IObjectId) inst).getObjectId();
      else
         return inst.toString();
   }

   public static void appendVal(StringBuilder logSB, Object val, DBColumnType colType, DBTypeDescriptor refType) {
     if (val instanceof IDBObject)
        logSB.append(((IDBObject) val).getDBObject());
     else if (val instanceof CharSequence) {
        logSB.append("'");
        logSB.append(val);
        logSB.append("'");
     }
     else if (colType != null) {
        logSB.append(formatValue(val, colType, refType, null));
     }
     else
        logSB.append(val);
   }

   static void appendConstant(StringBuilder sb, Object constVal) {
     if (constVal instanceof CharSequence) {
         sb.append("'");
         sb.append(constVal);
         sb.append("'");
      }
      else if (constVal instanceof Date) {
         sb.append("'");
         sb.append(DynUtil.formatDate((Date) constVal));
         sb.append("'");
      }
      else if (constVal == null)
         sb.append("null");
      else
         sb.append(constVal.toString());
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
      DBTypeDescriptor dbTypeDesc = DBTypeDescriptor.getByTableName(sqlTypeName);
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
            return "double precision";
         case Numeric:
            return "numeric";
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

   public static boolean isAssociationType(Object propType) {
      return getDefaultSQLType(propType, false) == null;
   }

   private static final SecureRandom randGen = new SecureRandom();
   private static final Base64.Encoder base64Encoder = Base64.getUrlEncoder().withoutPadding();
   private static final Base64.Decoder base64Decoder = Base64.getUrlDecoder();

   public static String createSecureUniqueToken() {
      byte[] buf = new byte[20];
      randGen.nextBytes(buf);
      String res = base64Encoder.encodeToString(buf);
      if (testMode)
         addTestToken(res, "secure-token");
      return res;
   }

   /** Used for logs to identify sessions without using part of the authToken or sessionId */
   public static String createMarkerToken() {
      byte[] buf = new byte[8];
      randGen.nextBytes(buf);
      String res = base64Encoder.encodeToString(buf);
      return res;
   }

   public static byte[] createSalt() {
      byte[] salt = new byte[16];
      randGen.nextBytes(salt);
      return salt;
   }

   public static String createSalt64() {
      byte[] salt = createSalt();
      String res = base64Encoder.encodeToString(salt);
      if (testMode)
         addTestToken(res, "secure-salt");
      return res;
   }

   public static String hashPassword(String salt64, String password) {
      try {
         byte[] salt = base64Decoder.decode(salt64);
         MessageDigest md = MessageDigest.getInstance("SHA-512");
         md.update(salt);
         byte[] buf = md.digest(password.getBytes(StandardCharsets.UTF_8));
         String res = base64Encoder.encodeToString(buf);
         if (testMode)
            addTestToken(res, "password-hash");
         return res;
      }
      catch (NoSuchAlgorithmException exc) {
         throw new IllegalArgumentException("DBUtil.hashPassword failed with: " + exc);
      }
   }

   public static String hashString(byte[] salt, String input, boolean weakHash) {
      try {
         MessageDigest md = MessageDigest.getInstance(weakHash ? "SHA-1" : "SHA-512");
         if (salt != null)
            md.update(salt);
         byte[] buf = md.digest(input.getBytes(StandardCharsets.UTF_8));
         return base64Encoder.encodeToString(buf);
      }
      catch (NoSuchAlgorithmException exc) {
         throw new IllegalArgumentException("DBUtil.hashString failed with: " + exc);
      }
   }

   private final static int BatchSize = 1000;

   public static int importCSVFile(String fileName, Object rowType, String separator, boolean headerRow, String commentPrefix, List<String> properties) {
      int lineCt = 0;
      File file = new File(fileName);
      BufferedReader reader = null;
      int numProps = properties.size();
      DBTypeDescriptor dbTypeDesc = DBTypeDescriptor.getByType(rowType, true);
      if (dbTypeDesc == null)
         throw new IllegalArgumentException("No DBTypeDescriptor found for: " + DynUtil.getTypeName(rowType,false));
      try {
         FileInputStream fileInput = new FileInputStream(file);
         reader = new BufferedReader(new InputStreamReader(fileInput, "UTF-8"));
         String nextLine;
         DBTransaction curTx = DBTransaction.getOrCreate();
         ArrayList<IDBObject> resInsts = new ArrayList<IDBObject>();
         while ((nextLine = reader.readLine()) != null) {
            lineCt++;
            if (nextLine.length() == 0)
               continue;
            if (commentPrefix != null && nextLine.startsWith(commentPrefix))
               continue;
            String[] values = StringUtil.split(nextLine, separator);

            // If there is one fewer value, the last property is treated as empty or null - we just won't set it. We could also make sure there really is a \t\r\n at the end
            if (values.length != numProps && values.length != numProps - 1)
               throw new IllegalArgumentException("Column mismatch - file has: " + values.length + " but expected: " + properties.size() + " for line: " + lineCt);

            if (headerRow && lineCt == 1) {
               StringBuilder sb = new StringBuilder();
               sb.append("*** Importing csv file: " + fileName + " with columns: ");
               for (int i = 0; i < values.length; i++) {
                  if (i != 0)
                     sb.append(", ");
                  String prop = properties.get(i);
                  if (prop == null)
                     sb.append("skipping col: ");
                  else
                     sb.append("prop: " + prop + " = col:");
                  sb.append(values[i]);
               }
               System.out.println(sb);
               continue;
            }

            IDBObject obj = dbTypeDesc.createInstance();
            int numValues = values.length;
            for (int pi = 0; pi < numValues; pi++) {
               String strVal = values[pi];
               String propName = properties.get(pi);
               if (propName == null)
                  continue; // Allows us to skip columns
               DBPropertyDescriptor propDesc = dbTypeDesc.getPropertyDescriptor(propName);
               if (propDesc == null)
                  throw new IllegalArgumentException("No property: " + propName + " in DBTypeDescriptor: " + dbTypeDesc);

               Object val;
               if (strVal.length() == 0) {
                  val = null;
               }
               else
                  val = propDesc.stringToValue(strVal);
               ((DBObject) obj.getDBObject()).setPropertyInPath(propName, val);
            }
            // Queuing here allows the transaction to batch up the inserts
            obj.dbInsert(true);

            resInsts.add(obj);

            if (resInsts.size() >= BatchSize) {
               curTx.commit();
               for (IDBObject resInst:resInsts)
                  DynUtil.dispose(resInst);
               resInsts.clear();
            }
         }

         curTx.commit();
         for (IDBObject resInst:resInsts)
            DynUtil.dispose(resInst);
      }
      catch (IOException exc) {
         throw new IllegalArgumentException(exc.toString());
      }
      finally {
         try {
            if (reader != null)
               reader.close();
         }
         catch (IOException exc) {
            System.err.println("*** Error closing reader: " + exc);
         }
      }
      return lineCt;
   }

   /**
    * TODO: this is for debugging only right now - it seems like we lose info from the schema to the metadata so this helps
    * translate what the metadata is expressing about the data type
    */
   public static String getNameForJDBCType(int colType) {
      switch (colType) {
         case Types.VARCHAR:
            return "text";
         case Types.INTEGER:
            return "integer";
         case Types.BIGINT:
            return "bigint";
         case Types.TINYINT:
            return "tinyint";
         case Types.SMALLINT:
            return "smallint";
         case Types.FLOAT:
            return "float";
         case Types.DOUBLE:
            return "double";
         case Types.BOOLEAN:
         case Types.BIT:
            return "bit";
         case Types.DATE:
            return "timestamp";
         case Types.OTHER:
            return "other";
         default:
            return "<missing-name-for-type:" + colType + ">";
      }
   }

}

