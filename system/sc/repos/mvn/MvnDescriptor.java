package sc.repos.mvn;

import sc.util.FileUtil;
import sc.util.URLUtil;

public class MvnDescriptor {
   public String groupId;
   public String artifactId;
   public String version;

   public MvnDescriptor(String groupId, String artifactId, String version) {
      this.groupId = groupId;
      this.artifactId = artifactId;
      this.version = version;
   }

   public MvnDescriptor() {
   }

   public static String toURL(String groupId, String artifactId, String version) {
      return "mvn://" + groupId + "/" + artifactId + "/" + version;
   }

   public static MvnDescriptor fromURL(String url) {
      MvnDescriptor desc = new MvnDescriptor();
      desc.version = URLUtil.getFileName(url);
      String rest = URLUtil.getParentPath(url);
      desc.artifactId = URLUtil.getFileName(rest);
      desc.groupId = URLUtil.getParentPath(rest);
      return desc;
   }

   public String getURL() {
      return toURL(groupId, artifactId, version);
   }

   public String getPackageName() {
      return groupId + "/" + artifactId;
   }

   public String getJarFileName() {
      return FileUtil.addExtension(FileUtil.concat(groupId, artifactId + "-" + version), "jar");
   }
}
