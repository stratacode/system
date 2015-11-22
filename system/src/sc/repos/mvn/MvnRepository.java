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

   public String getFileURL(String groupId, String artifactId, String version, String classifier, String suffix, String extension) {
      if (groupId == null) {
         groupId = "null";
      }
      String classifierExt = classifier == null ? "" : "-" + classifier;
      return baseURL + URLUtil.concat(groupId.replace('.', '/'), artifactId, version, FileUtil.addExtension(artifactId + "-" + version + classifierExt + suffix, extension));
   }

   public String toString() {
      return baseURL;
   }
}
