package sc.db;

import sc.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

public class TableInfo {
   public String tableName;
   public List<ColumnInfo> colInfos = new ArrayList<ColumnInfo>();

   public StringBuilder diffMessage;

   public TableInfo() {}

   public TableInfo(String tableName) {
      this.tableName = tableName;
   }

   public ColumnInfo getColumnInfo(String colName) {
      if (colInfos == null)
         return null;
      for (ColumnInfo colInfo:colInfos)
         if (StringUtil.equalStrings(colInfo.colName, colName))
            return colInfo;
      return null;
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("table ");
      sb.append(tableName);
      sb.append("(");
      if (colInfos != null) {
         for (int i = 0; i < colInfos.size(); i++) {
            ColumnInfo ci = colInfos.get(i);
            if (i != 0)
               sb.append(", ");
            sb.append(ci);
         }
      }
      sb.append(")");
      return sb.toString();
   }
}
