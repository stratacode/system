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

   public String getFileURL(String groupId, String artifactId, String modulePath, String version, String classifier, String suffix, String extension) {
      if (groupId == null) {
         groupId = "null";
      }
      // Haven't read in the POM yet so our best guess here is the modulePath is the artifactId
      if (artifactId == null)
         artifactId = modulePath;
      if (artifactId == null || artifactId.equals("null"))
         artifactId = modulePath;
      String classifierExt = classifier == null ? "" : "-" + classifier;
      return URLUtil.concat(baseURL, URLUtil.concat(groupId.replace('.', '/'), artifactId, version, FileUtil.addExtension(artifactId + "-" + version + classifierExt + suffix, extension)));
   }

   public String toString() {
      return baseURL;
   }
}
