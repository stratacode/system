package sc.repos.mvn;

import sc.lang.html.Element;
import sc.repos.DependencyContext;
import sc.repos.RepositoryPackage;
import sc.repos.RepositorySource;
import sc.util.FileUtil;
import sc.util.StringUtil;
import sc.util.URLUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class MvnDescriptor implements Serializable {
   private static final long serialVersionUID = 381740762128191325L;

   private final static String DEFAULT_TYPE = "jar";

   public String groupId;
   public String artifactId;
   public String version;
   public String type; // used in dependency references - jar is the default, test-jar, pom, etc.
   public String classifier; // e.g. jdk15 - appended
   public boolean optional;
   public String scope;
   public String parentPath;
   /** For sub-modules, this represents the path to the parent.  There's a point where we only have the modulePath, before we've read the POM file. */
   public String modulePath;

   // We might use the POM only to gather dependencies - skipping the package install if we are processing the source for that POM
   public boolean depsOnly;
   public boolean pomOnly;

   public List<MvnDescriptor> exclusions;

   public MvnDescriptor(String groupId, String parentPath, String modulePath, String artifactId, String version, String type, String classifier) {
      this(groupId, parentPath, modulePath, artifactId, version);
      this.type = type;
      this.classifier = classifier;
   }

   public MvnDescriptor(String groupId, String parentPath, String modulePath, String artifactId, String version) {
      this.groupId = groupId;
      this.parentPath = parentPath;
      this.modulePath = modulePath;
      this.artifactId = artifactId;
      this.version = version;

      if (artifactId != null && artifactId.contains("broadleaf/common"))
         System.out.println("***");
   }

   public MvnDescriptor() {
   }

   public static String getURLType(boolean depsOnly) {
      return depsOnly ? "mvndeps" : "mvn";
   }

   public static String toURL(String groupId, String parentPath, String modulePath, String artifactId, String version, boolean depsOnly) {
      if (artifactId == null) {
         artifactId = URLUtil.concat(parentPath, modulePath);
      }
      return getURLType(depsOnly) + "://" + URLUtil.concat(groupId, artifactId, version);
   }

   public static MvnDescriptor fromURL(String url) {
      MvnDescriptor desc = new MvnDescriptor();
      String origURL = url;
      int ix = url.indexOf("://");
      if (ix != -1)
         url = url.substring(ix+3);
      while (url.startsWith("/")) {
         System.err.println("*** Removing invalid '/' in URL: " + origURL);
         url = url.substring(1);
      }
      // The first part is the groupId, the last is the version and the middle is the groupId.  The group-id can have /'s for the sub-module case - e.g. "killbill/account"
      desc.version = URLUtil.getFileName(url);
      desc.groupId = URLUtil.getRootPath(url);
      desc.artifactId = url.substring(desc.groupId.length() + 1, url.length() - desc.version.length() - 1);
      if (desc.artifactId != null && desc.artifactId.contains("broadleaf/common"))
         System.out.println("***");
      return desc;
   }

   public String getURL() {
      return toURL(groupId, parentPath, modulePath, artifactId, version, depsOnly);
   }

   public String getPackageName() {
      if (artifactId == null && modulePath == null)
         System.out.println("***");
      return groupId + "/" + (artifactId == null ? modulePath : artifactId);
   }

   // relative to the pkg directory which already has group-id and artifact-id
   public String getJarFileName() {
      String ext;
      if (classifier != null) {
         ext = "-" + classifier;
      }
      else
         ext = "";
      return FileUtil.addExtension(artifactId + "-" + version + ext, "jar");
   }

   public String getTestJarFileName() {
      return FileUtil.addExtension(artifactId + "-" + version + "-tests", "jar");
   }

   public static MvnDescriptor getFromTag(POMFile file, Element tag, boolean dependency, boolean appendInherited, boolean required) {
      String groupId = file.getTagValue(tag, "groupId", required);
      String artifactId = file.getTagValue(tag, "artifactId", required);
      if (artifactId.contains("broadleaf/common"))
         System.out.println("***");
      String type = file.getTagValue(tag, "type", required);
      String version = file.getTagValue(tag, "version", required);
      if (artifactId.contains("broadleaf/common") && version != null && version.contains("4"))
         System.out.println("***");
      String classifier = file.getTagValue(tag, "classifier", required);
      MvnDescriptor desc = new MvnDescriptor(groupId, null, null, artifactId, version, type, classifier);
      if (appendInherited)
         file.appendInheritedAtts(desc, null);
      else if (file.parentPOM != null)
         file.parentPOM.appendInheritedAtts(desc, null);
      if (dependency) {
         Element exclRoot = tag.getSingleChildTag("exclusions");
         if (exclRoot != null) {
            Element[] exclTags = exclRoot.getChildTagsWithName("exclusion");
            if (exclTags != null) {
               ArrayList<MvnDescriptor> exclList = new ArrayList<MvnDescriptor>();
               for (Element exclTag : exclTags) {
                  // Do not append inherited here - we only want the values explicitly specified here as those are the
                  // ones which will match.
                  MvnDescriptor exclDesc = MvnDescriptor.getFromTag(file, exclTag, false, false, required);
                  exclList.add(exclDesc);
               }
               desc.exclusions = exclList;
            }
         }
         String optional = file.getTagValue(tag, "optional", true);
         if (optional != null && optional.equalsIgnoreCase("true"))
            desc.optional = true;
      }
      return desc;
   }

   public RepositoryPackage getOrCreatePackage(MvnRepositoryManager mgr, boolean install, DependencyContext ctx, boolean initDeps, MvnRepositoryPackage parentPkg) {
      String pkgName = getPackageName();
      RepositorySource depSrc = new MvnRepositorySource(mgr, getURL(), false, this, ctx);

      RepositoryPackage pkg = mgr.system.addPackageSource(mgr, pkgName, getJarFileName(), depSrc, install, parentPkg);
      if (pkg.parentPkg == null)
         pkg.parentPkg = parentPkg;

      if (initDeps && pkg.dependencies == null) {
         POMFile pomFile = mgr.getPOMFile(this, pkg, ctx, true, parentPkg != null ? parentPkg.pomFile : null, null);
         if (pkg instanceof MvnRepositoryPackage)
            ((MvnRepositoryPackage) pkg).pomFile = pomFile;
         mgr.initDependencies(pkg.currentSource, ctx);
      }
      return pkg;
   }

   public boolean sameArtifact(MvnDescriptor other) {
      if (strMatches(other.artifactId, artifactId))
         return true;
      if (other.modulePath != null && modulePath != null && strMatches(other.modulePath, modulePath))
         return true;
      if (artifactId == null || other.artifactId == null)
         System.out.println("***");
      // Warning - It's possible we have not yet set the artifactId for a module-based POM where the POM has not been loaded.
      return false;
   }

   // GroupId and artifactId are required.  The others only default to being equal if not specified.
   public boolean matches(MvnDescriptor other) {
      return strMatches(other.groupId, groupId) && sameArtifact(other) &&
              // flexible match on version in this direction
              (version == null /* || other.version == null */ || strMatches(version, other.version)) &&
              // Strict match on type - each type we reference becomes a different source for the same package
              // If type is omitted it only matches 'jar'
              (strMatches(type, other.type) || (type == null && other.type.equals(DEFAULT_TYPE))) &&
              (classifier == null || other.classifier == null || strMatches(classifier, other.classifier));
   }

   public boolean strMatches(String a, String b) {
      if (a == b)
         return true;
      if (a == null || b == null)
         return false;
      return a.equals("*") || b.equals("*") || a.equals(b);
   }

   public String toString() {
      return getURL();
   }

   public boolean equals(Object other) {
      if (!(other instanceof MvnDescriptor))
         return false;
      MvnDescriptor od = (MvnDescriptor) other;

      return StringUtil.equalStrings(groupId, od.groupId) && sameArtifact(od) && StringUtil.equalStrings(version, od.version) &&
             StringUtil.equalStrings(classifier, od.classifier) && StringUtil.equalStrings(type, od.type);
   }

   public int hashCode() {
      return groupId.hashCode() + (artifactId == null ? modulePath : artifactId).hashCode();
   }

   public void mergeFrom(MvnDescriptor parentDesc) {
      if (version == null && parentDesc.version != null)
         version = parentDesc.version;
      // TODO: any other attributes inherited here?
      if (artifactId.contains("broadleaf/common"))
         System.out.println("***");
   }
}
