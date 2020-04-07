package sc.db;

public abstract class BaseQueryDescriptor {
   public String queryName;

   public abstract boolean typesInited();
   public abstract void initTypes(Object typeDecl);
}
