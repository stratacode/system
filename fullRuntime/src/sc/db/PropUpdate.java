package sc.db;

public class PropUpdate {
   public DBPropertyDescriptor prop;
   public Object value;

   public PropUpdate(DBPropertyDescriptor prop) {
      this.prop = prop;
   }

   public PropUpdate(DBPropertyDescriptor prop, Object value) {
      this.prop = prop;
      this.value = value;
   }

   public String toString() {
      return prop + " = " + value;
   }
}

