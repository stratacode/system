/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos.mvn;

import sc.repos.IRepositoryManager;
import sc.repos.RepositorySource;

public class MvnRepositorySource extends RepositorySource {
   // Do we need to serialize the exclusions?
   transient MvnDescriptor desc;

   public MvnRepositorySource(IRepositoryManager mgr, String url, boolean unzip, MvnDescriptor desc) {
      super(mgr, url, unzip);
      this.desc = desc;
   }

   /** By maven convention, the version number is in the file name */
   public String getClassPathFileName() {
      if (desc == null)
         return super.getClassPathFileName();
      return desc.getJarFileName();
   }
}