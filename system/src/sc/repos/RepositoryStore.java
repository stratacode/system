package sc.repos;

import java.util.HashMap;

public class RepositoryStore {
   public String packageRoot;
   public HashMap<String,RepositoryPackage> packages = new HashMap<String,RepositoryPackage>();

   public RepositoryStore(String root) {
      packageRoot = root;
   }
}
