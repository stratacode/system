package sc.db;

public class IdPropertyDescriptor extends DBPropertyDescriptor {
   /** Does the client or database provide the value */
   public boolean definedByDB;

   public IdPropertyDescriptor(String propertyName, String columnName, String columnType, boolean definedByDB) {
      super(propertyName, columnName, columnType, null, false, false, null, null);
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
}
