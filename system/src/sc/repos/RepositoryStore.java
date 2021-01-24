/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import java.util.HashMap;

public class RepositoryStore {
   public String packageRoot;
   public HashMap<String,RepositoryPackage> packages = new HashMap<String,RepositoryPackage>();

   public RepositoryStore(String root) {
      packageRoot = root;
   }
}
