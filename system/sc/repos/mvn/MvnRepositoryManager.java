/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos.mvn;

import sc.repos.RepositoryPackage;
import sc.repos.RepositorySystem;
import sc.util.FileUtil;
import sc.util.IMessageHandler;
import sc.repos.AbstractRepositoryManager;
import sc.repos.RepositorySource;
import sc.util.URLUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

// Handles URLs of the form: groupId/artifact-id/version
//   Here's an example: http://search.maven.org/remotecontent?filepath=junit/junit/4.12/junit-4.12.pom
public class MvnRepositoryManager extends AbstractRepositoryManager {
   public static String[] defaultRepositories = new String[] {
       //"http://search.maven.org/remotecontent?filepath="
       "https://repo1.maven.org/maven2/"
   };
   public MvnRepositoryManager(RepositorySystem sys, String managerName, String rootDir, IMessageHandler handler, boolean info) {
      super(sys, managerName, rootDir, handler, info);

      for (String defPath:defaultRepositories) {
         repositories.add(new MvnRepository(defPath));
      }
   }

   public final String MAVEN_URL_PREFIX = "mvn://";

   // These are the maven repositories we use to find files.  Defaults to maven centrl.
   ArrayList<MvnRepository> repositories = new ArrayList<MvnRepository>();

   @Override
   public String doInstall(RepositorySource src) {
      String url = src.url;

      if (url.startsWith(MAVEN_URL_PREFIX))
         url = url.substring(MAVEN_URL_PREFIX.length());

      MvnDescriptor desc = MvnDescriptor.fromURL(url);

      String pomFileName = FileUtil.concat(src.pkg.installedRoot, "pom.xml");
      boolean found = installMvnFile(desc, pomFileName, "pom");
      if (!found)
         return "Maven pom file: " + desc.groupId + "/" + desc.artifactId + "/" + desc.version + " not found in repositories: " + repositories;

      POMFile pomFile = new POMFile(pomFileName, messageHandler);
      if (!pomFile.parse())
         return "Failed to parse maven POM: " + pomFileName;

      List<MvnDescriptor> depURLs = pomFile.getDependencies(null);
      if (depURLs != null) {
         for (MvnDescriptor depDesc : depURLs) {
            String pkgName = depDesc.getPackageName();
            RepositorySource depSrc = new RepositorySource(this, depDesc.getURL(), false);
            // This will add and install the package
            system.addPackageSource(this, pkgName, depDesc.getJarFileName(), depSrc, true);
         }
      }

      String jarFileName = FileUtil.concat(src.pkg.installedRoot, desc.getJarFileName());
      found = installMvnFile(desc, jarFileName, "jar");
      if (!found)
         return "Maven jar file: " + desc.groupId + "/" + desc.artifactId + "/" + desc.version + " not found in repositories: " + repositories;

      // Success
      return null;
   }

   private boolean installMvnFile(MvnDescriptor desc, String resFileName, String remoteExt) {
      boolean found = false;
      for (MvnRepository repo:repositories) {
         String pomURL = repo.getFileURL(desc.groupId, desc.artifactId, desc.version, remoteExt);
         String resDir = FileUtil.getParentPath(resFileName);
         new File(resDir).mkdirs();
         String res = URLUtil.saveURLToFile(pomURL, resFileName, false, messageHandler);
         if (res == null) { // If success we break
            found = true;
            break;
         }
      }
      return found;

   }

}
