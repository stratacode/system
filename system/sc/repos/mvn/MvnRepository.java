/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos.mvn;

import sc.util.FileUtil;
import sc.util.URLUtil;

public class MvnRepository {
   public String baseURL;

   public MvnRepository(String baseURL) {
      this.baseURL = baseURL;
   }

   public String getFileURL(String groupId, String artifactId, String version, String extension) {
      return baseURL + URLUtil.concat(groupId, artifactId, version, FileUtil.addExtension(artifactId + "-" + version, extension));
   }

   public String toString() {
      return baseURL;
   }
}
