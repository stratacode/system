/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos.mvn;

import sc.util.FileUtil;
import sc.util.IMessageHandler;
import sc.repos.AbstractRepositoryManager;
import sc.repos.RepositorySource;
import sc.util.URLUtil;

import java.util.ArrayList;

// Handles URLs of the form: groupId/artifact-id/version
//   Here's an example: http://search.maven.org/remotecontent?filepath=junit/junit/4.12/junit-4.12.pom
public class MvnRepositoryManager extends AbstractRepositoryManager {
   public static String[] defaultRepositories = new String[] {
           "http://search.maven.org/remotecontent?filepath="

   }
   public MvnRepositoryManager(String managerName, String rootDir, IMessageHandler handler, boolean info) {
      super(managerName, rootDir, handler, info);

      for (String defPath:defaultRepositories) {
         repositories.add(new MvnRepository(defPath));
      }
   }

   public final String MAVEN_URL_PREFIX = "mvn://";

   ArrayList<MvnRepository> repositories = new ArrayList<MvnRepository>();

   @Override
   public String doInstall(RepositorySource src) {
      String url = src.url;

      if (url.startsWith(MAVEN_URL_PREFIX))
         url = url.substring(MAVEN_URL_PREFIX.length());

      String version = URLUtil.getFileName(url);
      String rest = URLUtil.getParentPath(url);
      String artifactId = URLUtil.getFileName(rest);
      String groupId = URLUtil.getParentPath(rest);

      String pomFileName = FileUtil.concat(src.pkg.installedRoot, "pom.xml");
      boolean found = false;
      for (MvnRepository repo:repositories) {
         String pomURL = repo.getFileURL(groupId, artifactId, version, "pom");
         String res = URLUtil.saveURLToFile(pomURL, pomFileName, false, messageHandler);
         if (res == null) { // If success we break
            found = true;
            break;
         }
      }
      if (!found)
         return "Maven package: " + groupId + "/" + artifactId + "/" + version + " not found in repositories: " + repositories;

      POMFile pomFile = new POMFile(pomFileName, messageHandler);
      if (!pomFile.parse())
         return "Failed to parse maven POM: " + pomFileName;



   }

   public static String toURL(String groupId, String artifactId, String version) {
      return "mvn://" + groupId + "/" + artifactId + "/" + version;
   }
}
