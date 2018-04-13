/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import sc.dyn.DynUtil;
import sc.type.CTypeUtil;
import sc.type.PTypeUtil;

import java.util.Date;
import java.util.List;

/**
 * Information that tags a build in StrataCode.  You can use the API to configure
 * or extend a layer that uses the api to customize the way you tag your builds.
 * Stores the build tag information from a builderTag instance created when one scc program
 * builds another using meta-data stored from the buildInfo or LayeredSystem.  For
 * convenience, you can access the BuildTag.builderTag instance to populate
 * runtime values for the buildTag of the program you are defining using the @BuildInit
 * annotation on your BuildTag instance.
 */
public class BuildTag {
   public String version; // for semantic versioning you'd supply a string like "0.1.1" - but you can set this in your project so it represents the next release you are working on
   // Should end with an integer so we can do auto-increment, but might include
   public String tag; // release, beta, alpha, dev
   public String revision; // The revision of the current version.  e.g. the release candidate for a specific version.  It's the number after the _ in some versioning schemes.
   public String buildNumber; // increases for each build - normally an integer but using a string here because it's so project specific and these are normally set via properties
   public String timeStamp; // String timestamp for this build.  TODO: this could have been a Date but a) we don't want to make it easy to update, only easy to read and b) we don't support the value <-> Date in Expression.createFromValue yet
   public String user; // user.name

   public String scmVersion; // git-describe, or other source control version
   public String javaVersion; // java.version
   public String osVersion; // os.name+os.arch.+.os.version
   public String layerNames; // list of build layers
   //public List<LayerDepTag> layerDepTags; // the library and version of all dependencies added by each layer

   public String getBuildTag() {
      StringBuilder sb = new StringBuilder();
      if (version != null) {
         sb.append("v");
         sb.append(version);
      }
      if (tag != null && !tag.equals("release")) {
         sb.append("-");
         sb.append(tag);
      }
      if (revision != null) {
         sb.append("_");
         sb.append(revision);
      }
      if (buildNumber != null) {
         sb.append(".b");
         sb.append(buildNumber);
      }
      if (timeStamp != null) {
         sb.append(" @ ");
         sb.append(timeStamp);
      }
      if (scmVersion != null) {
         sb.append(", ");
         sb.append(scmVersion);
      }
      return sb.toString();
   }

   public String getManifest() {
      StringBuilder sb = new StringBuilder();
      sb.append("Build manifest:\n");
      sb.append("   tag: ");
      sb.append(getBuildTag());
      if (user != null) {
         sb.append(" by ");
         sb.append(user);
      }
      if (scmVersion != null) {
         sb.append("\nscmVersion:");
         sb.append(scmVersion);
      }
      if (javaVersion != null) {
         sb.append("\njavaVersion:");
         sb.append(javaVersion);
      }
      if (osVersion != null) {
         sb.append("\nosVersion:");
         sb.append(osVersion);
      }
      return sb.toString();
   }

   /*
     Would it be nice to tag a build with the layers and versions of each layer?
   public static class LayerDepTag {
      public String layerName;
      public String fileName;
      public String version;

      public LayerDepTag(String layerName, String fileName, String version) {
         this.layerName = layerName;
         this.fileName = fileName;
         this.version = version;
      }
   }
   */
}

