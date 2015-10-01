/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos.mvn;

import sc.repos.DependencyContext;
import sc.repos.IRepositoryManager;
import sc.repos.RepositorySource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MvnRepositorySource extends RepositorySource {
   // Do we need to serialize the exclusions?
   MvnDescriptor desc;

   public MvnRepositorySource(IRepositoryManager mgr, String url, boolean unzip, MvnDescriptor desc, DependencyContext ctx) {
      super(mgr, url, unzip);
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
      return new ArrayList<String>(Collections.singletonList(isTestJar ? desc.getTestJarFileName() : desc.getJarFileName()));
   }

   /**
    * If we reach the same package from two origins we need to remove any exclusions in the src we chose
    * that are not in the second one.  For example if spring-core is dependended upon by two different modules
    * and one excludes commons-logging but the other does not, we should not exclude commons-logging.
    */
   public boolean mergeExclusions(RepositorySource other) {
      if (!(other instanceof MvnRepositorySource))
         return false;

      if (desc.exclusions == null)
         return false;

      MvnRepositorySource otherSrc = (MvnRepositorySource) other;
      if (otherSrc.desc.exclusions == null) {
         desc.exclusions = null;
         return true;
      }
      boolean changed = false;
      for (int i = 0; i < desc.exclusions.size(); i++) {
         MvnDescriptor excl = desc.exclusions.get(i);
         boolean found = false;
         for (MvnDescriptor otherExcl:otherSrc.desc.exclusions) {
            if (otherExcl.matches(excl)) {
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
      return changed;
   }

   public String getDefaultPackageName() {
      return desc.getPackageName();
   }

   public String getDefaultFileName() {
      return desc.getJarFileName();
   }
}
