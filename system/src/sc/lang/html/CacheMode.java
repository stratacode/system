package sc.lang.html;

public enum CacheMode {
   Unset, Enabled, Disabled;

   public static CacheMode fromString(String value) {
      for (CacheMode m:values())
         if (m.name().equalsIgnoreCase(value))
            return m;
      return null;
   }
}
