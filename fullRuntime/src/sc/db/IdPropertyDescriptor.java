package sc.db;

public class IdPropertyDescriptor extends DBPropertyDescriptor {
   /** Does the client or database provide the value */
   public boolean definedByDB;

   public IdPropertyDescriptor(String propertyName, String columnName, String columnType, boolean definedByDB) {
      super(propertyName, columnName, columnType, null, false, true, false, null, null, null, false, null);
      this.definedByDB = definedByDB;
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(super.toString());
      sb.append(" (id)");
      return sb.toString();
   }

   public boolean isId() {
      return true;
   }

   public IdPropertyDescriptor createKeyIdColumn() {
      IdPropertyDescriptor res = new IdPropertyDescriptor(propertyName, columnName, DBUtil.getKeyIdColumnType(columnType), definedByDB);
      res.dbTypeDesc = dbTypeDesc;
      return res;
   }

   public DBTypeDescriptor getColTypeDesc() {
      return dbTypeDesc;
   }
}
