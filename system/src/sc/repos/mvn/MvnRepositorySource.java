/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos.mvn;

import sc.repos.DependencyContext;
import sc.repos.IRepositoryManager;
import sc.repos.RepositoryPackage;
import sc.repos.RepositorySource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MvnRepositorySource extends RepositorySource {
   // Do we need to serialize the exclusions?
   public MvnDescriptor desc;

   public MvnRepositorySource(IRepositoryManager mgr, String url, boolean unzip, RepositoryPackage parentPkg, MvnDescriptor desc, DependencyContext ctx) {
      super(mgr, url, unzip, false, parentPkg);
      this.desc = desc;
      this.ctx = ctx;
   }

   /** By maven convention, the version number is in the file name */
   public List<String> getClassPathFileNames() {
      // The package may include both the tests and regular jar files when it's set.
      if (pkg != null)
         return pkg.fileNames;
      if (desc == null)
         return super.getClassPathFileNames();
      boolean isTestJar = desc.type != null && desc.type.equals("test-jar");
      return new ArrayList<String>(Collections.singletonList(isTestJar ? desc.getTestJarFileName() : desc.getJarFileName("jar")));
   }

   public boolean mergeSource(RepositorySource other) {
      if (!super.mergeSource(other))
         return false;

      MvnRepositorySource otherm = (MvnRepositorySource) other;
      if (desc.parentPath == null && otherm.desc.parentPath != null)
         desc.parentPath = otherm.desc.parentPath;
      if (desc.modulePath == null && otherm.desc.modulePath != null)
         desc.modulePath = otherm.desc.modulePath;
      if (desc.artifactId == null && otherm.desc.artifactId != null)
         desc.artifactId = otherm.desc.artifactId;
      return true;
   }

   /**
    * If we reach the same package from two origins we need to remove any exclusions in the src we chose
    * that are not in the second one.  For example if spring-core is dependended upon by two different modules
    * and one excludes commons-logging but the other does not, we should not exclude commons-logging.
    */
   public boolean mergeExclusions(RepositorySource other) {
      if (!(other instanceof MvnRepositorySource))
         return false;

      if (!desc.reference && desc.exclusions == null)
         return false;

      MvnRepositorySource otherSrc = (MvnRepositorySource) other;
      MvnDescriptor otherDesc = otherSrc.desc;
      boolean changed = false;
      // If this descriptor was just a reference, just pick up the exclusions from the other
      // descriptor.  We did not define the exclusions and dependencies won't have changed so
      // no need to invalidate them
      if (!desc.reference) {
         if (otherDesc.reference)
            return false;
         if (otherDesc.exclusions == null) {
            desc.exclusions = null;
            return true;
         }
         for (int i = 0; i < desc.exclusions.size(); i++) {
            MvnDescriptor excl = desc.exclusions.get(i);
            boolean found = false;
            for (MvnDescriptor otherExcl : otherSrc.desc.exclusions) {
               if (otherExcl.matches(excl, false)) {
                  found = true;
                  break;
               }
            }
            if (!found) {
               changed = true;
               desc.exclusions.remove(i);
               i--;
            }
         }
      }
      else {
         if (!otherDesc.reference) {
            desc.exclusions = otherDesc.exclusions;
            desc.reference = false;
         }
      }
      return changed;
   }

   public String getDefaultPackageName() {
      return desc.getPackageName();
   }

   public String getDefaultFileName() {
      return desc.getJarFileName("jar");
   }
}
