package sc.db;

import sc.obj.IObjectId;
import sc.type.IBeanMapper;

import javax.naming.InitialContext;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import sc.db.DataSourceManager;
import sc.util.IMessageHandler;
import sc.util.MessageHandler;

public class DBUtil {
   public static boolean verbose = true;

   public static IMessageHandler msgHandler;

   public static Connection createConnection(String dataSourceName) {
      try {
         DataSource ds = DataSourceManager.getDataSource(dataSourceName);
         return ds.getConnection();
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
         if (Character.isUpperCase(c) && !upper) {
            if (i > 0)
               sb.append("_");
            sb.append(Character.toLowerCase(c));
            upper = true;
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
    catch (SQLException exc) {}
    close(conn,stmt);
  }

  public static void close(Connection conn, PreparedStatement stmt) {
    close(conn);
    try {
      if (stmt != null)
        stmt.close();
    }
    catch (SQLException exc) {
      System.err.println("*** error closing statement: " + exc);
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

   public static void close(AutoCloseable conn) {
      try {
         if (conn != null)
            conn.close();
      }
      catch (Exception exc) {
         System.err.println("*** error closing item: " + exc);
      }
   }

   // TODO: add quoting here only if the ident was originally defined with quotes because they are case sensitive with quotes and not without...
   //  do we need to support quoting as an option through annotation specified definitions?
   public static void appendIdent(StringBuilder sb, StringBuilder logSB, String ident) {
      sb.append(ident);
      if (logSB != null)
         logSB.append(ident);
   }

   public static String formatValue(Object val) {
      if (val instanceof String) {
         return "\"" + val + "\"";
      }
      else if (val == null)
         return "null";
      else
         return val.toString();
   }

   public static void setStatementValue(PreparedStatement st, int index, Object propertyType, Object val) throws SQLException {
      if (propertyType == Integer.class || propertyType == Integer.TYPE) {
         st.setInt(index, (Integer) val);
      }
      else if (propertyType == String.class) {
         st.setString(index, (String) val);
      }
      else if (propertyType == Long.class || propertyType == Long.TYPE) {
         st.setLong(index, (Long) val);
      }
      else if (propertyType == Boolean.class || propertyType == Boolean.TYPE) {
         st.setBoolean(index, (Boolean) val);
      }
      else {
         st.setObject(index, val);
      }
   }

   public static Object getResultSetByIndex(ResultSet rs, int index, DBPropertyDescriptor dbProp) throws SQLException {
      IBeanMapper mapper = dbProp.getPropertyMapper();
      Object propertyType = mapper.getPropertyType();
      if (propertyType == Integer.class || propertyType == Integer.TYPE) {
         Object res = rs.getObject(index);
         if (res == null)
            return res;
         if (res instanceof Integer)
            return res;
         else {
            System.err.println("*** Unrecognized result for integer property");
            return res;
         }
      }
      else if (propertyType == String.class)
         return rs.getString(index);
      else if (propertyType == Long.class || propertyType == Long.TYPE)
         return rs.getLong(index);
      else if (propertyType == Boolean.class || propertyType == Boolean.TYPE)
         return rs.getBoolean(index);
      else
         return rs.getObject(index);
   }

   public static String getDefaultSQLType(Object propertyType) {
      if (propertyType == Integer.class || propertyType == Integer.TYPE)
         return "integer";
      else if (propertyType == String.class)
         return "text";
      else if (propertyType == Long.class || propertyType == Long.TYPE)
         return "bigint";
      else if (propertyType == Boolean.class || propertyType == Boolean.TYPE)
         return "boolean";
      else if (propertyType == Float.class || propertyType == Float.TYPE)
         return "real";
      else if (propertyType == Double.class || propertyType == Double.TYPE)
         return "double";
      else // TODO: BigDecimal, byte array, char, Character - size limit for strings through an annotation
         return null;
   }

   public static String getJavaTypeFromSQLType(String type) {
      if (type.equalsIgnoreCase("serial") || type.equalsIgnoreCase("integer"))
         return "int";
      else if (type.equalsIgnoreCase("bigserial") || type.equalsIgnoreCase("bigint"))
         return "long";
      else if (type.startsWith("varchar") || type.equals("text"))
         return "String";
      else if (type.equals("boolean"))
         return "boolean";
      else if (type.equals("real"))
         return "float";
      else if (type.equals("double"))
         return "double";
      throw new UnsupportedOperationException();
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

   public static String replaceNextParam(String logStr, Object colVal) {
      int ix = logStr.indexOf('?');
      if (ix == -1) {
         System.err.println("*** replaceNextParam - found no param");
         return logStr;
      }
      StringBuilder res = new StringBuilder();
      res.append(logStr.substring(0, ix));
      res.append(DBUtil.formatValue(colVal));
      res.append(logStr.substring(ix+1));
      return res.toString();
   }

   public static String toIdString(Object inst) {
      if (inst instanceof IObjectId)
         return ((IObjectId) inst).getObjectId();
      else
         return inst.toString();
   }
}
