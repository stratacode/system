/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos.mvn;

import sc.repos.*;
import sc.util.FileUtil;
import sc.util.IMessageHandler;
import sc.util.MessageHandler;
import sc.util.URLUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Handles URLs of the form: mvn://groupId/artifact-id/version or mvndeps://...  The mvndeps will only install
 * the dependencies for the POM, not the jar for the project itself.
 */
public class MvnRepositoryManager extends AbstractRepositoryManager {
   public static String[] defaultRepositories = new String[] {
       //"http://search.maven.org/remotecontent?filepath="
       "https://repo1.maven.org/maven2/"
   };

   public MvnRepositoryManager(RepositorySystem sys, String managerName, String rootDir, IMessageHandler handler, boolean info, AbstractRepositoryManager installMgr) {
      this(sys, managerName, rootDir, handler, info);
      installRepository = installMgr;
   }

   public MvnRepositoryManager(RepositorySystem sys, String managerName, String rootDir, IMessageHandler handler, boolean info) {
      super(sys, managerName, rootDir, handler, info);

      for (String defPath:defaultRepositories) {
         repositories.add(new MvnRepository(defPath));
      }
   }

   public final String MAVEN_URL_PREFIX = "mvn://";

   public AbstractRepositoryManager installRepository;

   // These are the maven repositories we use to find files.  Defaults to maven centrl.
   ArrayList<MvnRepository> repositories = new ArrayList<MvnRepository>();

   public HashMap<String,POMFile> pomCache = new HashMap<String,POMFile>();

   @Override
   public String doInstall(RepositorySource src) {
      String url = src.url;

      MvnDescriptor desc;
      Object pomFileRes;
      MvnRepositoryPackage pkg = (MvnRepositoryPackage) src.pkg;

      if (installRepository != null) {
         installRepository.doInstall(src);
         // This is the src repository and so does not need to go into classpath
         src.pkg.definesClasses = false;
         pomFileRes = POMFile.readPOM(FileUtil.concat(src.pkg.installedRoot, "pom.xml"), this);
         desc = null;
      }
      else {
         desc = MvnDescriptor.fromURL(url);
         pomFileRes = installPOM(desc, src.pkg);
         src.pkg.definesClasses = true;
      }

      if (pomFileRes instanceof String)
         return (String) pomFileRes;

      pkg.pomFile = (POMFile) pomFileRes;

      installDependencies(src);

      if (desc != null && !desc.depsOnly) {
         // Install the JAR file
         String jarFileName = FileUtil.concat(src.pkg.installedRoot, desc.getJarFileName());
         boolean found = installMvnFile(desc, jarFileName, "jar");
         if (!found)
            return "Maven jar file: " + desc.getURL() + " not found in repositories: " + repositories;
      }

      // Success
      return null;
   }

   private String installDependencies(RepositorySource src) {
      MvnRepositoryPackage pkg = (MvnRepositoryPackage) src.pkg;

      POMFile pomFile = pkg.pomFile;

      if (pomFile != null && !(pomFile.packaging.equals("pom"))) {
         List<MvnDescriptor> depDescs = pomFile.getDependencies(getScopesToBuild(src.pkg));
         if (depDescs != null) {
            ArrayList<RepositoryPackage> depPackages = new ArrayList<RepositoryPackage>();

            List<MvnDescriptor> exclusions = null;
            if (src instanceof MvnRepositorySource) {
               exclusions = ((MvnRepositorySource) src).desc.exclusions;
            }
            for (MvnDescriptor depDesc : depDescs) {
               if (exclusions != null) {
                  boolean excluded = false;
                  for (MvnDescriptor exclDesc:exclusions) {
                     if (exclDesc.matches(depDesc)) {
                        excluded = true;
                        break;
                     }
                  }
                  if (excluded)
                     continue;
               }
               // Always load dependencies with the maven repository.  This repository might be git-mvn which loads the initial
               // repository with git
               // TODO: check optional and exclusions - don't install optional or excluded repositories.
               // For optional, maybe it's just left off the list.   so we don't get here.  For excluded, we
               // Sometimes we just don't have a version - no use trying to install it with null
               if (depDesc.version != null)
                  depPackages.add(depDesc.getOrCreatePackage((MvnRepositoryManager) system.getRepositoryManager("mvn"), true));
            }
            src.pkg.dependencies = depPackages;
         }
      }
      else
         return "POMFile for package: " + pkg.packageName + " not available - unable to install tests";

      return null;
   }

   private final static String[] defaultScopes = {POMFile.DEFAULT_SCOPE};
   private final static String[] testScopes = {POMFile.DEFAULT_SCOPE, "test"};
   private String[] getScopesToBuild(RepositoryPackage pkg) {
      if (pkg.includeTests)
         return testScopes;
      return defaultScopes;
   }

   Object installPOM(MvnDescriptor desc, RepositoryPackage pkg) {
      String pomFileName = FileUtil.concat(pkg.installedRoot, "pom.xml");
      boolean found = installMvnFile(desc, pomFileName, "pom");
      if (!found) {
         pomCache.put(pomFileName, POMFile.NULL_SENTINEL);
         return "Maven pom file: " + desc.groupId + "/" + desc.artifactId + "/" + desc.version + " not found in repositories: " + repositories;
      }

      POMFile pomFile = POMFile.readPOM(pomFileName, this);
      if (pomFile == null) {
         pomCache.put(pomFileName, POMFile.NULL_SENTINEL);
         return "Failed to parse maven POM: " + pomFileName;
      }
      return pomFile;
   }

   private boolean installMvnFile(MvnDescriptor desc, String resFileName, String remoteExt) {
      boolean found = false;
      for (MvnRepository repo:repositories) {
         String pomURL = repo.getFileURL(desc.groupId, desc.artifactId, desc.version, remoteExt);
         String resDir = FileUtil.getParentPath(resFileName);
         new File(resDir).mkdirs();
         String res = URLUtil.saveURLToFile(pomURL, resFileName, false, msg);
         if (res == null) { // If success we break
            found = true;
            break;
         }
      }
      return found;

   }

   @Override
   public RepositoryPackage createPackage(String url) {
      MvnDescriptor desc = MvnDescriptor.fromURL(url);
      RepositorySource src = new RepositorySource(this, url, false);
      return new MvnRepositoryPackage(this, desc.getPackageName(), desc.getJarFileName(), src);
   }

   public RepositoryPackage createPackage(IRepositoryManager mgr, String packageName, String fileName, RepositorySource src) {
      return new MvnRepositoryPackage(mgr, packageName, fileName, src);
   }

   public POMFile getPOMFile(MvnDescriptor desc, RepositoryPackage pkg) {
      String pomFileName = FileUtil.concat(pkg.installedRoot, "pom.xml");
      POMFile res = pomCache.get(pomFileName);
      if (res != null) {
         if (res == POMFile.NULL_SENTINEL)
            return null;
         return res;
      }
      Object pomRes = installPOM(desc, pkg);
      if (pomRes instanceof String) {
         MessageHandler.error(msg, (String) pomRes);
         return null;
      }
      return (POMFile) pomRes;
   }
}
