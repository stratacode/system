/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos.mvn;

import sc.repos.*;
import sc.util.*;

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
   public String doInstall(RepositorySource src, DependencyContext ctx) {
      String url = src.url;

      MvnDescriptor desc;
      Object pomFileRes;
      MvnRepositoryPackage pkg = (MvnRepositoryPackage) src.pkg;

      if (installRepository != null) {
         installRepository.doInstall(src, ctx);
         // This is the src repository and so does not need to go into classpath
         src.pkg.definesClasses = false;
         pomFileRes = POMFile.readPOM(FileUtil.concat(src.pkg.getVersionRoot(), "pom.xml"), this, ctx, pkg);
         desc = null;
      }
      else {
         desc = MvnDescriptor.fromURL(url);
         // Need this set here so the version suffix can be resolved from the pkg
         src.pkg.currentSource = src;
         pomFileRes = installPOM(desc, src.pkg, ctx, false);
         src.pkg.definesClasses = true;
      }

      if (pomFileRes instanceof String)
         return (String) pomFileRes;

      pkg.pomFile = (POMFile) pomFileRes;

      installDependencies(src, ctx);

      if (desc != null && !desc.depsOnly) {
         // Install the JAR file
         String jarFileName = FileUtil.concat(src.pkg.getVersionRoot(), desc.getJarFileName());
         boolean found = installMvnFile(desc, jarFileName, "jar");
         if (!found)
            return "Maven jar file: " + desc.getURL() + " not found in repositories: " + repositories;
      }

      // Success
      return null;
   }

   String initDependencies(RepositorySource src, DependencyContext ctx) {
      MvnRepositoryPackage pkg = (MvnRepositoryPackage) src.pkg;

      POMFile pomFile = pkg.pomFile;

      if (pomFile != null && !(pomFile.packaging.equals("pom"))) {
         List<MvnDescriptor> depDescs = pomFile.getDependencies(getScopesToBuild(src.pkg));
         if (depDescs != null) {
            ArrayList<RepositoryPackage> depPackages = new ArrayList<RepositoryPackage>();

            info(StringUtil.indent(DependencyContext.val(ctx)) + "Initializing dependencies for: " + pkg.packageName);

            List<MvnDescriptor> exclusions = null;
            if (src instanceof MvnRepositorySource) {
               exclusions = ((MvnRepositorySource) src).desc.exclusions;
            }
            for (int i = 0; i < depDescs.size(); i++) {
               MvnDescriptor depDesc = depDescs.get(i);
               // TODO: should we be validating that we have some version of this package?
               if (depDesc.version == null) {
                  depDescs.remove(i);
                  i--;
                  continue;
               }
               // First check if it's excluded on this descriptor
               if (exclusions != null) {
                  boolean excluded = false;
                  for (MvnDescriptor exclDesc : exclusions) {
                     if (exclDesc.matches(depDesc)) {
                        excluded = true;
                        // Need to remove these because right now we do not save/restore the MvnDescriptor which stores
                        // the exclusions.  We'll just get rid of them from the dependencies list so they don't interfere
                        // when we load the stored dependencies.
                        depDescs.remove(i);
                        i--;
                        break;
                     }
                  }
                  if (excluded)
                     continue;
               }
               // Then see if we exclude it from any exclusions in this path
               if (ctx != null && excludedContext(ctx, depDesc)) {
                  depDescs.remove(i);
                  i--;
                  continue;
               }
               // Always load dependencies with the maven repository.  This repository might be git-mvn which loads the initial
               // repository with git
               if (depDesc.version != null)
                  depPackages.add(depDesc.getOrCreatePackage((MvnRepositoryManager) system.getRepositoryManager("mvn"), false, DependencyContext.child(ctx, pkg), true));
            }
            src.pkg.dependencies = depPackages;

            info(StringUtil.indent(DependencyContext.val(ctx)) + "Done initializing dependencies for: " + pkg.packageName);
         }
      }
      return null;
   }

   private boolean excludedContext(DependencyContext ctx, MvnDescriptor desc) {
      if (ctx.fromPkg != null) {
         RepositorySource src = ctx.fromPkg.currentSource;
         if (src instanceof MvnRepositorySource) {
            MvnRepositorySource msrc = (MvnRepositorySource) src;
            List<MvnDescriptor> exclusions = msrc.desc.exclusions;
            if (exclusions != null) {
               for (MvnDescriptor exclDesc:exclusions) {
                  if (exclDesc.matches(desc))
                     return true;
               }
            }
         }
      }
      if (ctx.parent != null) {
         return excludedContext(ctx.parent, desc);
      }
      return false;
   }

   // Need to install the dependencies after all of them have been collected.  That's so that the sources array gets sorted properly.
   private String installDependencies(RepositorySource src, DependencyContext ctx) {
      MvnRepositoryPackage pkg = (MvnRepositoryPackage) src.pkg;

      ArrayList<RepositoryPackage> depPackages = src.pkg.dependencies;
      if (depPackages == null) {
         String err = initDependencies(src, ctx);
         if (err != null)
            return err;
         depPackages = src.pkg.dependencies;
      }

      if (depPackages != null) {
         info(StringUtil.indent(DependencyContext.val(ctx)) + "Installing dependencies for: " + pkg.packageName);
         for (RepositoryPackage depPkg:depPackages) {
            system.installPackage(depPkg, DependencyContext.child(ctx, pkg));
         }
         info(StringUtil.indent(DependencyContext.val(ctx)) + "Done installing dependencies for: " + pkg.packageName);
      }
      return null;
   }

   private final static String[] defaultScopes = {POMFile.DEFAULT_SCOPE};
   private final static String[] testScopes = {POMFile.DEFAULT_SCOPE, "test"};
   private final static String[] allScopes = {POMFile.DEFAULT_SCOPE, "test", "runtime"};
   private final static String[] runtimeScopes = {POMFile.DEFAULT_SCOPE, "runtime"};
   private String[] getScopesToBuild(RepositoryPackage pkg) {
      if (pkg.includeTests)
         return pkg.includeRuntime ? allScopes : testScopes;
      return pkg.includeRuntime ? runtimeScopes : defaultScopes;
   }

   Object installPOM(MvnDescriptor desc, RepositoryPackage pkg, DependencyContext ctx, boolean checkExists) {
      String pomFileName = FileUtil.concat(pkg.getVersionRoot(), "pom.xml");
      if (!checkExists || !new File(pomFileName).canRead()) {
         boolean found = installMvnFile(desc, pomFileName, "pom");
         if (!found) {
            pomCache.put(pomFileName, POMFile.NULL_SENTINEL);
            return "Maven pom file: " + desc.groupId + "/" + desc.artifactId + "/" + desc.version + " not found in repositories: " + repositories;
         }
      }

      POMFile pomFile = POMFile.readPOM(pomFileName, this, ctx, pkg);
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
      MvnRepositorySource src = new MvnRepositorySource(this, url, false, desc, null);
      return new MvnRepositoryPackage(this, desc.getPackageName(), desc.getJarFileName(), src);
   }

   public RepositoryPackage createPackage(IRepositoryManager mgr, String packageName, String fileName, RepositorySource src) {
      return new MvnRepositoryPackage(mgr, packageName, fileName, src);
   }

   public POMFile getPOMFile(MvnDescriptor desc, RepositoryPackage pkg, DependencyContext ctx) {
      String pomFileName = FileUtil.concat(pkg.getVersionRoot(), "pom.xml");
      POMFile res = pomCache.get(pomFileName);
      if (res != null) {
         if (res == POMFile.NULL_SENTINEL)
            return null;
         return res;
      }
      Object pomRes = installPOM(desc, pkg, ctx, true);
      if (pomRes instanceof String) {
         MessageHandler.error(msg, (String) pomRes);
         return null;
      }
      return (POMFile) pomRes;
   }
}
