package sc.db;

import com.sun.org.apache.xpath.internal.operations.Mult;

public class MultiTableDescriptor extends TableDescriptor {
   public MultiTableDescriptor(String tableName) {
      super(tableName);
   }
   DBPropertyDescriptor valueColumn; // Maps and Collections
   DBPropertyDescriptor keyColumn; // Maps only
}
