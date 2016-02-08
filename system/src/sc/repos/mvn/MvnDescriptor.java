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
   /** Set to true for descriptors that come from URLs - i.e. that did not define the exclusions at all */
   public boolean reference;

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
   }

   public MvnDescriptor() {
   }

   public static String getURLType(boolean depsOnly) {
      return depsOnly ? "mvndeps" : "mvn";
   }

   private final static String PATH_SEP = "?p=";
   private final static String PARENT_SEP = "&m=";
   private final static String NO_VERSION = "<no-version>";

   public static String toURL(String groupId, String parentPath, String modulePath, String artifactId, String version, boolean depsOnly) {
      String pathId;
      if (artifactId == null) {
         artifactId = URLUtil.concat(parentPath, modulePath);
         pathId = null;
      }
      else
         pathId = URLUtil.concat(parentPath == null ? null : parentPath + PARENT_SEP, modulePath);
      if (version == null)
         version = NO_VERSION;
      return getURLType(depsOnly) + "://" + URLUtil.concat(groupId, artifactId, version, pathId == null ? null : PATH_SEP + pathId);
   }

   public static MvnDescriptor fromURL(String url) {
      MvnDescriptor desc = new MvnDescriptor();
      String origURL = url;
      int ix = url.indexOf("://");
      if (ix != -1)
         url = url.substring(ix+3);
      int pathIx = url.indexOf(PATH_SEP);
      String pathId = null;
      if (pathIx != -1) {
         pathId = url.substring(pathIx + PATH_SEP.length());
         url = url.substring(0, pathIx);
      }
      while (url.startsWith("/")) {
         System.err.println("*** Removing invalid '/' in URL: " + origURL);
         url = url.substring(1);
      }
      // The first part is the groupId, the last is the version and the middle is the groupId.  The group-id can have /'s for the sub-module case - e.g. "killbill/account"
      desc.version = URLUtil.getFileName(url);
      desc.groupId = URLUtil.getRootPath(url);
      desc.reference = true;
      if (desc.groupId == null || desc.version == null) {
         System.err.println("*** Invalid URL for MvnDescriptor: " + url);
         return null;
      }

      String rest = url.substring(desc.groupId.length() + 1, url.length() - desc.version.length() - 1);
      while (rest.endsWith("/"))
         rest = rest.substring(0, rest.length() - 1);
      if (desc.version.equals(NO_VERSION))
         desc.version = null;

      if (pathIx == -1) {
         desc.artifactId = rest;
      }
      else {
         desc.artifactId = rest;
         int parIx = pathId.indexOf(PARENT_SEP);
         if (parIx == -1) {
            desc.parentPath = null;
            desc.modulePath = pathId;
         }
         else {
            desc.parentPath = pathId.substring(0, parIx);
            desc.modulePath = pathId.substring(parIx + PARENT_SEP.length() + 1);
         }
      }
      return desc;
   }

   public String getURL() {
      return toURL(groupId, parentPath, modulePath, artifactId, version, depsOnly);
   }

   public String getPackageName() {
      return (groupId == null ? "" : groupId + "/") + getUseArtifactId();
   }

   // relative to the pkg directory which already has group-id and artifact-id
   public String getJarFileName(String ext) {
      if (version == null)
         return null;
      String suffix;
      if (classifier != null) {
         suffix = "-" + classifier;
      }
      else
         suffix = "";
      return FileUtil.addExtension(getUseArtifactId() + "-" + version + suffix, ext);
   }

   public String getTestJarFileName() {
      return FileUtil.addExtension(getUseArtifactId() + "-" + version + "-tests", "jar");
   }

   public static MvnDescriptor getFromTag(POMFile file, Element tag, boolean dependency, boolean appendInherited, boolean required) {
      String groupId = file.getTagValue(tag, "groupId", required);
      String artifactId = file.getTagValue(tag, "artifactId", required);
      String type = file.getTagValue(tag, "type", required);
      String version = file.getTagValue(tag, "version", required);
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
      RepositorySource depSrc = new MvnRepositorySource(mgr, getURL(), false, parentPkg, this, ctx);

      RepositoryPackage pkg = mgr.system.addPackageSource(mgr, pkgName, getJarFileName("jar"), depSrc, install, parentPkg);
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
      // Warning - It's possible we have not yet set the artifactId for a module-based POM where the POM has not been loaded.
      return false;
   }

   // GroupId and artifactId are null for a descriptor created from the file system until we have read the POM.
   public boolean matches(MvnDescriptor other, boolean matchVersion) {

      return (strMatches(other.groupId, groupId) || groupId == null /* || other.groupId == null */) && sameArtifact(other) &&
              // flexible match on version in this direction
              (!matchVersion || (version == null /* || other.version == null */ || strMatches(version, other.version))) &&
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

   public String getUseArtifactId() {
      if (artifactId == null)
         return URLUtil.concat(parentPath, modulePath);
      return artifactId;
   }

   public void mergeFrom(MvnDescriptor parentDesc) {
      if (version == null && parentDesc.version != null)
         version = parentDesc.version;
      // TODO: any other attributes inherited here?
   }
}
