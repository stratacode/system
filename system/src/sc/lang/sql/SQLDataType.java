package sc.lang.sql;

import sc.db.DBColumnType;
import sc.db.DBTypeDescriptor;
import sc.lang.SQLLanguage;
import sc.lang.SemanticNode;
import sc.lang.java.JavaSemanticNode;
import sc.parser.IParseNode;
import sc.parser.IString;
import sc.parser.ParseError;

import java.sql.Types;
import java.util.List;

public class SQLDataType extends SQLParamType {
   public String typeName;
   public List<IString> sizeList;
   public List<IString> dimsList;
   public String intervalOptions;

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(typeName);
      if (sizeList != null) {
         sb.append("(");
         appendList(sb, sizeList, ")(");
         sb.append(")");
      }
      if (dimsList != null) {
         sb.append("[");
         appendList(sb, sizeList, "][");
         sb.append("]");
      }
      if (intervalOptions != null) {
         sb.append(" ");
         sb.append(intervalOptions);
      }
      return sb.toString();
   }

   private void appendList(StringBuilder sb, List<IString> list, String sep) {
      for (int i = 0; i < list.size(); i++) {
         if (i != 0)
            sb.append(sep);
         sb.append(list.get(i).toString());
      }
   }

   public String getJavaTypeName() {
      String res = convertToJavaTypeName(typeName);
      if (res == null) {
         displayError("No java type name for sql type: " + typeName);
         res = "Object";
      }
      return res;
   }

   public static String convertToJavaTypeName(String typeName) {
      if (typeName.equalsIgnoreCase("varchar") || typeName.equals("text") || typeName.equals("nvarchar") || typeName.equals("ntext"))
         return "String";
      else if (typeName.equalsIgnoreCase("integer") || typeName.equals("serial"))
         return "int";
      else if (typeName.equalsIgnoreCase("bigint") || typeName.equals("bigserial"))
         return "long";
      else if (typeName.equalsIgnoreCase("tinyint"))
         return "byte";
      else if (typeName.equalsIgnoreCase("smallint"))
         return "short";
      else if (typeName.equalsIgnoreCase("float"))
         return "float";
      else if (typeName.equalsIgnoreCase("real") || typeName.equals("numeric"))
         return "double";
      else if (typeName.equalsIgnoreCase("bit"))
         return "boolean";
      else if (typeName.equalsIgnoreCase("date") || typeName.equalsIgnoreCase("time") ||
               typeName.equalsIgnoreCase("datetime") || typeName.equals("timestamp"))
         return "java.util.Date";
      else if (typeName.equalsIgnoreCase("cidr") || typeName.equalsIgnoreCase("inet"))
         return "String";
      return null;
   }

   public static SQLDataType create(String typeStr) {
      if (typeStr == null)
         throw new IllegalArgumentException("Illegal null SQLDataType");
      SQLLanguage lang = SQLLanguage.getSQLLanguage();
      Object res = lang.parseString(typeStr, lang.sqlDataType);
      if (!(res instanceof IParseNode))
         throw new IllegalArgumentException("Invalid SQLDataType: " + typeStr + ": " + res);
      else
         return (SQLDataType) ((IParseNode) res).getSemanticValue();
   }

   @Override
   public String getIdentifier() {
      return typeName;
   }

   public int getJDBCType() {
      if (typeName.equalsIgnoreCase("varchar") || typeName.equals("text") || typeName.equals("nvarchar") || typeName.equals("ntext"))
         return Types.VARCHAR;
      else if (typeName.equalsIgnoreCase("integer") || typeName.equals("serial"))
         return Types.INTEGER;
      else if (typeName.equalsIgnoreCase("bigint") || typeName.equals("bigserial"))
         return Types.BIGINT;
      else if (typeName.equalsIgnoreCase("tinyint"))
         return Types.TINYINT;
      else if (typeName.equalsIgnoreCase("smallint"))
         return Types.SMALLINT;
      else if (typeName.equalsIgnoreCase("float"))
         return Types.FLOAT;
      else if (typeName.equalsIgnoreCase("real") || typeName.equals("numeric"))
         return Types.DOUBLE;
      else if (typeName.equalsIgnoreCase("bit"))
         return Types.BOOLEAN;
      else if (typeName.equalsIgnoreCase("date") || typeName.equalsIgnoreCase("time") ||
              typeName.equalsIgnoreCase("datetime") || typeName.equals("timestamp"))
         return Types.DATE;
      return Types.OTHER;
   }

   public DBColumnType getDBColumnType() {
      return DBColumnType.fromColumnType(getIdentifier());
   }
}
