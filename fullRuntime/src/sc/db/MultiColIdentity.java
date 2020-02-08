package sc.db;

public class MultiColIdentity {
   int sz;
   Object[] vals;
   public MultiColIdentity(int sz) {
      this.sz = sz;
      this.vals = new Object[sz];
   }
   public void setVal(Object v, int ix) {
      vals[ix] = v;
   }

   public int hashCode() {
      int res = 0;
      for (int i = 0; i < sz; i++) {
         res += vals[i].hashCode();
      }
      return res;
   }

   public boolean equals(Object o) {
      if (!(o instanceof MultiColIdentity))
         return false;
      MultiColIdentity oid = (MultiColIdentity) o;
      if (oid.sz != sz)
         return false;
      for (int i = 0; i < sz; i++) {
         if (!oid.vals[i].equals(vals[i]))
            return false;
      }
      return true;
   }
}
