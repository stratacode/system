/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos.mvn;

import sc.repos.*;
import sc.util.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Handles URLs of the form: mvn://groupId/artifact-id/version or mvndeps://...  The mvndeps will only install
 * the dependencies for the POM, not the jar for the project itself.
 */
public class MvnRepositoryManager extends AbstractRepositoryManager {
   public static String mvnRepositoryDir = FileUtil.concat(System.getProperty("user.home"), "/.m2/repository");

   /** Should we use the local maven repository directory in case this user has already installed the file? */
   public boolean useLocalRepository = true;

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

      // Need to make sure both types share the same pomCache and repositories
      if (managerName.equals("mvn")) {
         pomCache = new HashMap<String,POMFile>();
         repositories = new ArrayList<MvnRepository>();

         for (String defPath:defaultRepositories) {
            repositories.add(new MvnRepository(defPath));
         }
      }
      else {
         MvnRepositoryManager mvnMgr = (MvnRepositoryManager) sys.getRepositoryManager("mvn");
         pomCache = mvnMgr.pomCache;
         repositories = mvnMgr.repositories;
      }

   }

   public final String MAVEN_URL_PREFIX = "mvn://";

   public AbstractRepositoryManager installRepository;

   // These are the maven repositories we use to find files.  Defaults to maven centrl.
   ArrayList<MvnRepository> repositories;

   public HashMap<String,POMFile> pomCache;

   @Override
   public String doInstall(RepositorySource src, DependencyContext ctx, DependencyCollection deps) {
      String url = src.url;

      MvnDescriptor desc;
      Object pomFileRes;
      MvnRepositoryPackage pkg = (MvnRepositoryPackage) src.pkg;

      POMFile parentPOM = null;
      if (pkg.parentPkg instanceof MvnRepositoryPackage) {
         parentPOM = ((MvnRepositoryPackage) pkg.parentPkg).pomFile;
      }

      if (installRepository != null) {
         installRepository.doInstall(src, ctx, deps);
         // Typically installRepository is git so we change the defaults
         pkg.buildFromSrc = true;
         // Don't update the class path
         pkg.definesClasses = false;
         // Do update the src path - this will be changed if pom packaging to false - then we pick up the src path from the sub-modules
         pkg.definesSrc = true;
         pomFileRes = POMFile.readPOM(FileUtil.concat(pkg.getVersionRoot(), "pom.xml"), this, ctx, pkg, true, parentPOM, null);
         desc = null;
         // Can't read file returns null
         if (pomFileRes == null)
            pomFileRes = "Failed to read pom file for package: " + pkg.packageName;
      }
      else {
         // If this is a module of a Git or other source repository it's a src package by default
         if (pkg.parentPkg != null && pkg.parentPkg.mgr.isSrcRepository()) {
            pkg.buildFromSrc = true;
         }
         if (src instanceof MvnRepositorySource) {
            desc = ((MvnRepositorySource) src).desc;
            if (desc == null) // Does this ever happen?
               desc = MvnDescriptor.fromURL(url);
         }
         else
            desc = MvnDescriptor.fromURL(url);
         // Need this set here so the version suffix can be resolved from the pkg
         pkg.setCurrentSource(src);
         // We may have already processed a pom for this package as part of a weird module reference, before we'd decided on the right version for the package
         // We just overwrite the old one
         if (pkg.pomFile != null && !pkg.pomFile.fileName.equals(getPOMFileName(pkg))) {
            pkg.pomFile = null;
         }
         POMFile pomFile = pkg.pomFile;
         if (pomFile == null) {
            // If this is a nested POM this should find it's already there and not install
            pomFileRes = installPOM(desc, pkg, ctx, pkg.parentPkg != null, true, parentPOM, null);
            if (pomFileRes instanceof POMFile) {
               pomFile = (POMFile) pomFileRes;
            } else if (pomFileRes instanceof String) {
               System.err.println("*** Failed to read POM file: " + pomFileRes);
            }
         }
         else
            pomFileRes = null;

         // If we are included this package as source, don't download and include the jar files even if we could grab them from maven
         if (pomFile != null) {
            if (!pkg.buildFromSrc) {
               // When a repository is not downloaded in src form (i.e. from 'git') these types will have classes.  The type 'pom' usually does not but we still need to
               // check for a jar file since it may be there.
               if (pomFile.packaging.equals("jar") || pomFile.packaging.equals("bundle") || pomFile.packaging.equals("pom") | pomFile.packaging.equals("war")) {
                  pkg.definesClasses = true;
                  pkg.definesSrc = false;
               }
               else {
                  pkg.definesSrc = false;
                  pkg.definesClasses = false;
               }
            }
            else {
               if ((pomFile.packaging.equals("jar") || pomFile.packaging.equals("bundle")) || pomFile.packaging.equals("war") || pomFile.packaging.equals("orbit")) {
                  pkg.definesSrc = true;
                  pkg.definesClasses = false;
               }
               else {
                  pkg.definesSrc = false;
                  pkg.definesClasses = false;
               }
            }
         }
      }

      if (pomFileRes instanceof String)
         return (String) pomFileRes;

      collectDependencies(src, ctx, deps);

      if (desc != null && !desc.depsOnly) {
         // Install the JAR/WAR file
         String typeExt = null;

         for (String fileType:pkg.installFileTypes) {
            boolean optionalFile = false;
            if (fileType == null) {
               String packaging = pkg.pomFile.packaging;
               if (packaging.equals("war")) {
                  // killbill-platform-server - dependencies come with classifier = classes which seems to imply that
                  // there's a jar file '-classes' that we download instead
                  if (desc.classifier != null && desc.classifier.equals("classes"))
                     typeExt = "jar";
                  else
                     typeExt = "war";
               }
               else if (packaging.equals("jar"))
                  typeExt = "jar";
                  // bundle: OSGI bundles, orbit: signed OSGI bundles - treat them like jar files
               else if (packaging.equals("bundle") || packaging.equals("orbit"))
                  typeExt = "jar";
               else if (packaging.equals("pom")) {
                  // Some pom packages - e.g. org.apache.zookeeper actually have a jar file that needs to be picked up
                  // when you include this package as a dependency.  I don't see any way other than to try and download it
                  // to determine if it's there.
                  typeExt = "jar";
                  optionalFile = true;
               }
               else
                  System.err.println("*** Warning - unrecognized packaging type: " + packaging);
               // If we are in source mode, do not download the jar file
               if (typeExt != null && pkg.definesClasses) {
                  String jarFileName = FileUtil.concat(pkg.getVersionRoot(), desc.getJarFileName(typeExt));
                  boolean found = installMvnFile(desc, jarFileName, "", typeExt);
                  if (!found) {
                     if (optionalFile) {
                        pkg.definesClasses = false;
                     }
                     else {
                        return "Maven " + typeExt + " file: " + desc.getURL() + " not found in repositories: " + repositories;
                     }
                  }
               }
            }
            else {
               if (fileType.equals("test-jar")) {
                  String testJarName = FileUtil.concat(pkg.getVersionRoot(), desc.getTestJarFileName());
                  boolean found = installMvnFile(desc, testJarName, "-tests", "jar");
                  if (!found)
                     return "Maven " + typeExt + " test-jar file: " + desc.getURL() + " not found in repositories: " + repositories;
               }
               else {
                  System.out.println("*** Warning - unable to install artifact-type: " + fileType);
               }
            }
         }
      }

      // Success
      return null;
   }

   String initDependencies(RepositorySource src, DependencyContext ctx) {
      MvnRepositoryPackage pkg = (MvnRepositoryPackage) src.pkg;

      POMFile pomFile = pkg.pomFile;

      if (pomFile != null) {
         List<MvnDescriptor> depDescs = pomFile.getDependencies(getScopesToBuild(src.pkg), true, src.pkg.parentPkg == null, false, ctx);
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
                  System.err.println("Warning: no version number found for maven dependency: " + depDesc + " from package: " + src.pkg.packageName);
                  depDescs.remove(i);
                  i--;
                  continue;
               }
               // First check if it's excluded on this descriptor
               if (exclusions != null) {
                  boolean excluded = false;
                  for (MvnDescriptor exclDesc : exclusions) {
                     if (exclDesc.matches(depDesc, false)) {
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
               if (pkg.hasSubPackage(depDesc))
                  continue;
               // Always load dependencies with the maven repository.  This repository might be git-mvn which loads the initial
               // repository with git
               if (depDesc.version != null)
                  depPackages.add(depDesc.getOrCreatePackage(getChildManager(), false, DependencyContext.child(ctx, pkg), true, null));
            }
            src.pkg.dependencies = depPackages;

            info(StringUtil.indent(DependencyContext.val(ctx)) + "Done initializing dependencies for: " + pkg.packageName);
         }
      }
      return null;
   }

   MvnRepositoryManager getChildManager() {
      return (MvnRepositoryManager) system.getRepositoryManager("mvn");
   }

   private boolean excludedContext(DependencyContext ctx, MvnDescriptor desc) {
      if (ctx.fromPkg != null) {
         RepositorySource src = ctx.fromPkg.currentSource;
         if (src instanceof MvnRepositorySource) {
            MvnRepositorySource msrc = (MvnRepositorySource) src;
            List<MvnDescriptor> exclusions = msrc.desc.exclusions;
            if (exclusions != null) {
               for (MvnDescriptor exclDesc:exclusions) {
                  if (exclDesc.matches(desc, false))
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
   private String collectDependencies(RepositorySource src, DependencyContext ctx, DependencyCollection deps) {
      MvnRepositoryPackage pkg = (MvnRepositoryPackage) src.pkg;

      ArrayList<RepositoryPackage> depPackages = src.pkg.dependencies;
      if (depPackages == null) {
         String err = initDependencies(src, ctx);
         if (err != null)
            return err;
         depPackages = src.pkg.dependencies;
      }

      /*
      if (depPackages != null) {
         info(StringUtil.indent(DependencyContext.val(ctx)) + "Installing dependencies for: " + pkg.packageName);
         for (RepositoryPackage depPkg:depPackages) {
            system.installPackage(depPkg, DependencyContext.child(ctx, pkg));
         }
         info(StringUtil.indent(DependencyContext.val(ctx)) + "Done installing dependencies for: " + pkg.packageName);
      }
      */
      if (depPackages != null) {
         for (RepositoryPackage depPkg:depPackages) {
            deps.addDependency(depPkg, DependencyContext.child(ctx, pkg));
         }
      }
      return null;
   }

   private final static String[] defaultScopes = {POMFile.DEFAULT_SCOPE};
   private final static String[] testScopes = {POMFile.DEFAULT_SCOPE, "test"};
   private final static String[] allScopes = {POMFile.DEFAULT_SCOPE, "test", "runtime"};
   private final static String[] runtimeScopes = {POMFile.DEFAULT_SCOPE, "runtime"};
   private String[] getScopesToBuild(RepositoryPackage pkg) {
      String[] baseList;
      if (pkg.getIncludeTests())
         baseList = pkg.getIncludeRuntime() ? allScopes : testScopes;
      else
         baseList = pkg.getIncludeRuntime() ? runtimeScopes : defaultScopes;
      if (pkg instanceof MvnRepositoryPackage) {
         ArrayList<String> newList = null;
         MvnRepositoryPackage mpkg = (MvnRepositoryPackage) pkg;
         if (mpkg.includeProvided) {
            newList = new ArrayList<String>(Arrays.asList(baseList));
            newList.add("provided");
         }
         if (newList != null)
            return newList.toArray(new String[newList.size()]);
      }
      return baseList;
   }

   String getPOMFileName(RepositoryPackage pkg) {
      return FileUtil.concat(pkg.getVersionRoot(), "pom.xml");
   }

   Object installPOM(MvnDescriptor desc, RepositoryPackage pkg, DependencyContext ctx, boolean checkExists, boolean required, POMFile parentPOM, POMFile includedFromPOM) {
      // If we are going to be the first to create the install directory, need to make sure it's been validated that we can write to it.
      // and also make sure we don't do this twice.
      preInstallPackage(pkg, ctx);
      String pomFileName = getPOMFileName(pkg);
      File pomFileFile = new File(pomFileName);
      String notExistsFile = pomFileName + ".notFound";
      File notExistsFileFile = new File(notExistsFile);
      if (!system.reinstallSystem && notExistsFileFile.canRead())
         return "POM file: " + pomFileName + " did not exist when last checked.";
      if (!checkExists || !pomFileFile.canRead()) {
         boolean found = installMvnFile(desc, pomFileName, "", "pom");
         if (!found) {
            pomCache.put(pomFileName, POMFile.NULL_SENTINEL);
            FileUtil.saveStringAsFile(notExistsFile, "does not exist", true);
            if (pomFileFile.canRead())
               pomFileFile.delete();
            return "Maven pom file: " + desc.groupId + "/" + desc.artifactId + "/" + desc.version + " not found in repositories: " + repositories;
         }
         else
            notExistsFileFile.delete();
      }

      POMFile pomFile = POMFile.readPOM(pomFileName, this, ctx, pkg, required, parentPOM, includedFromPOM);
      if (pomFile == null) {
         pomCache.put(pomFileName, POMFile.NULL_SENTINEL);
         return "Failed to parse maven POM: " + pomFileName;
      }
      return pomFile;
   }

   private boolean mvnFileExists(MvnDescriptor desc, String resFileName, String remoteSuffix, String remoteExt) {
      boolean found = false;
      if (!system.reinstallSystem && new File(resFileName).canRead()) {
         info("File already downloaded: " + resFileName);
         return true;
      }
      if (useLocalRepository) {
         String localPkgDir = FileUtil.concat(mvnRepositoryDir, desc.groupId.replace(".", FileUtil.FILE_SEPARATOR), desc.artifactId, desc.version);
         if (new File(localPkgDir).isDirectory()) {
            String fileName = FileUtil.concat(localPkgDir, FileUtil.addExtension(desc.artifactId + "-" + desc.version + remoteSuffix, remoteExt));
            if (new File(fileName).canRead()) {
               if (FileUtil.copyFile(fileName, resFileName, true))
                  return true;
               else
                  info("Failed to copy from local repository: " + fileName + " to: " + resFileName);
            }
         }
      }
      for (MvnRepository repo:repositories) {
         String pomURL = repo.getFileURL(desc.groupId, desc.artifactId, desc.modulePath, desc.version, desc.classifier, remoteSuffix, remoteExt);
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

   private boolean needsClassifierSuffix(String ext) {
      if (ext.equals("jar"))
         return true;
      if (ext.equals("pom"))
         return false;
      System.err.println("*** Unrecognized classifier suffix: " + ext);
      return false;
   }

   private boolean installMvnFile(MvnDescriptor desc, String resFileName, String remoteSuffix, String remoteExt) {
      boolean found = false;
      if (!system.reinstallSystem && new File(resFileName).canRead()) {
         info("File already downloaded: " + resFileName);
         return true;
      }
      String classifierExt;
      boolean useClassifier = false;
      if (desc.classifier != null && needsClassifierSuffix(remoteExt)) {
         classifierExt = "-" + desc.classifier;
         useClassifier = true;
      }
      else
         classifierExt = "";
      if (useLocalRepository) {
         String artifactId = desc.getUseArtifactId();
         String groupPrefix = desc.groupId == null ? null : desc.groupId.replace(".", FileUtil.FILE_SEPARATOR);
         String localPkgDir = FileUtil.concat(mvnRepositoryDir, groupPrefix, artifactId, desc.version);
         if (new File(localPkgDir).isDirectory()) {
            String fileName = FileUtil.concat(localPkgDir, FileUtil.addExtension(artifactId + "-" + desc.version + classifierExt + remoteSuffix, remoteExt));
            if (new File(fileName).canRead()) {
               if (FileUtil.copyFile(fileName, resFileName, true))
                  return true;
               else
                  info("Failed to copy from local repository: " + fileName + " to: " + resFileName);
            }
         }
      }
      for (MvnRepository repo:repositories) {
         String remoteURL = repo.getFileURL(desc.groupId, desc.artifactId, desc.modulePath, desc.version, useClassifier ? desc.classifier : null, remoteSuffix, remoteExt);
         String resDir = FileUtil.getParentPath(resFileName);
         new File(resDir).mkdirs();
         String res = URLUtil.saveURLToFile(remoteURL, resFileName, false, msg);
         if (res == null) { // If success we break
            found = true;
            break;
         }
      }
      return found;

   }

   public RepositoryPackage getOrCreatePackage(String url, RepositoryPackage parent, boolean install) {
      RepositorySource src = createRepositorySource(url, false, parent);
      String pkgName = src.getDefaultPackageName();
      RepositoryPackage pkg = system.addPackageSource(this, pkgName, src.getDefaultFileName(), src, install, parent);
      return pkg;
   }

   @Override
   public RepositoryPackage createPackage(String url) {
      MvnRepositorySource src = (MvnRepositorySource) createRepositorySource(url, false, null);
      return new MvnRepositoryPackage(this, src.desc.getPackageName(), src.desc.getJarFileName("jar"), src, null);
   }

   public RepositoryPackage createPackage(IRepositoryManager mgr, String packageName, String fileName, RepositorySource src) {
      return new MvnRepositoryPackage(mgr, packageName, fileName, src, null);
   }

   public RepositoryPackage createPackage(IRepositoryManager mgr, String packageName, String fileName, RepositorySource src, RepositoryPackage parentPkg) {
      return new MvnRepositoryPackage(mgr, packageName, fileName, src, parentPkg);
   }

   public POMFile getPOMFile(MvnDescriptor desc, RepositoryPackage pkg, DependencyContext ctx, boolean required, POMFile parentPOM, POMFile includedFromPOM) {
      String pomFileName = getPOMFileName(pkg);
      POMFile res = pomCache.get(pomFileName);
      if (res != null) {
         if (res == POMFile.NULL_SENTINEL)
            return null;
         return res;
      }
      Object pomRes = installPOM(desc, pkg, ctx, true, required, parentPOM, includedFromPOM);
      if (pomRes instanceof String) {
         if (required)
            MessageHandler.error(msg, (String) pomRes);
         else
            MessageHandler.debug(msg, (String) pomRes);
         return null;
      }
      return (POMFile) pomRes;
   }

   public RepositorySource createRepositorySource(String url, boolean unzip, RepositoryPackage parentPkg) {
      if (url.startsWith("mvn")) {
         MvnDescriptor desc = MvnDescriptor.fromURL(url);
         MvnRepositorySource src = new MvnRepositorySource(this, url, false, parentPkg, desc, null);
         return src;
      }
      else {
         if (parentPkg != null) {
            MvnDescriptor desc = new MvnDescriptor(null, parentPkg.packageName, url, null, null);
            MvnRepositorySource src = new MvnRepositorySource(this, url, false, parentPkg, desc, null);
            return src;
         }
         return super.createRepositorySource(url, unzip, null);
      }
   }

   /**
    * For nested modules, we create the package with one name, then once we read the POM we figure out it's name in the maven package
    * space.  If there are dependencies to a module we need to resolve them against the maven name or we'll load the whole thing again.
    */
   public RepositoryPackage registerNameForPackage(RepositoryPackage pkg, String name) {
      if (pkg instanceof MvnRepositoryPackage) {
         MvnRepositoryPackage mpkg = (MvnRepositoryPackage) pkg;
         String groupId = mpkg.getGroupId();
         if (groupId != null) {
            String newName = groupId + "/" + name;
            if (!pkg.packageName.equals(newName)) {
               pkg = system.registerAlternateName(pkg, newName);
            }
         }
      }
      return pkg;
   }

}

