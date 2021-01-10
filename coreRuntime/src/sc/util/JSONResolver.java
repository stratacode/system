package sc.util;

public interface JSONResolver {
   public Object resolveRef(String refName, Object propertyType);

   public Object resolveClass(String className);
}
