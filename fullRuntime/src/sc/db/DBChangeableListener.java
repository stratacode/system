package sc.db;

import sc.bind.AbstractListener;
import sc.bind.IChangeable;

public class DBChangeableListener extends AbstractListener {
   public IChangeable value;
   public DBObject obj;
   public String prop;

   public DBChangeableListener(DBObject obj, String prop, IChangeable val) {
      this.obj = obj;
      this.prop = prop;
      this.value = val;
   }

   // This will be called when the IChangeable sends a change event that some aspect of the value of the instance changed
   public boolean valueInvalidated(Object lobj, Object prop, Object eventDetail, boolean apply) {
      obj.dbSetProp(this.prop, value, value);
      return true;
   }
}
