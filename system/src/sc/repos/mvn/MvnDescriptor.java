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

   public String groupId;
   public String artifactId;
   public String version;
   public String type; // used in dependency references - jar is the default, test-jar, pom, etc.
   public String classifier; // e.g. jdk15 - appended
   public boolean optional;
   public String scope;

   // We might use the POM only to gather dependencies - skipping the package install if we are processing the source for that POM
   public boolean depsOnly;
   public boolean pomOnly;

   public List<MvnDescriptor> exclusions;

   public MvnDescriptor(String groupId, String artifactId, String version, String type, String classifier) {
      this(groupId, artifactId, version);
      this.type = type;
      this.classifier = classifier;
   }

   public MvnDescriptor(String groupId, String artifactId, String version) {
      this.groupId = groupId;
      this.artifactId = artifactId;
      this.version = version;
   }

   public MvnDescriptor() {
   }

   public static String getURLType(boolean depsOnly) {
      return depsOnly ? "mvndeps" : "mvn";
   }

   public static String toURL(String groupId, String artifactId, String version, boolean depsOnly) {
      return getURLType(depsOnly) + "://" + groupId + "/" + artifactId + "/" + version;
   }

   public static MvnDescriptor fromURL(String url) {
      MvnDescriptor desc = new MvnDescriptor();
      int ix = url.indexOf("://");
      if (ix != -1)
         url = url.substring(ix+3);
      desc.version = URLUtil.getFileName(url);
      String rest = URLUtil.getParentPath(url);
      desc.artifactId = URLUtil.getFileName(rest);
      desc.groupId = URLUtil.getParentPath(rest);
      desc.depsOnly = url.startsWith("mvndeps");
      return desc;
   }

   public String getURL() {
      return toURL(groupId, artifactId, version, depsOnly);
   }

   public String getPackageName() {
      return groupId + "/" + artifactId;
   }

   // relative to the pkg directory which already has group-id and artifact-id
   public String getJarFileName() {
      return FileUtil.addExtension(artifactId + "-" + version, "jar");
   }

   public static MvnDescriptor getFromTag(POMFile file, Element tag, boolean dependency, boolean appendInherited) {
      String groupId = file.getTagValue(tag, "groupId");
      String artifactId = file.getTagValue(tag, "artifactId");
      String type = file.getTagValue(tag, "type");
      String version = file.getTagValue(tag, "version");
      String classifier = file.getTagValue(tag, "classifier");
      MvnDescriptor desc = new MvnDescriptor(groupId, artifactId, version, type, classifier);
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
                  MvnDescriptor exclDesc = MvnDescriptor.getFromTag(file, exclTag, false, false);
                  exclList.add(exclDesc);
               }
               desc.exclusions = exclList;
            }
         }
         String optional = file.getTagValue(tag, "optional");
         if (optional != null && optional.equalsIgnoreCase("true"))
            desc.optional = true;
      }
      return desc;
   }

   public RepositoryPackage getOrCreatePackage(MvnRepositoryManager mgr, boolean install, DependencyContext ctx, boolean initDeps) {
      String pkgName = getPackageName();
      RepositorySource depSrc = new MvnRepositorySource(mgr, getURL(), false, this, ctx);

      RepositoryPackage pkg = mgr.system.addPackageSource(mgr, pkgName, getJarFileName(), depSrc, install);

      if (initDeps && pkg.dependencies == null) {
         POMFile pomFile = mgr.getPOMFile(this, pkg, ctx, true);
         if (pkg instanceof MvnRepositoryPackage)
            ((MvnRepositoryPackage) pkg).pomFile = pomFile;
         mgr.initDependencies(pkg.currentSource, ctx);
      }
      return pkg;
   }

   // GroupId and artifactId are required.  The others only default to being equal if not specified.
   public boolean matches(MvnDescriptor other) {
      return strMatches(other.groupId, groupId) && strMatches(other.artifactId, artifactId) &&
              // flexible match on version in this direction
              (version == null /* || other.version == null */ || strMatches(version, other.version)) &&
              (type == null || other.type == null || strMatches(type, other.type)) &&
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

      return StringUtil.equalStrings(groupId, od.groupId) && StringUtil.equalStrings(artifactId, od.artifactId) && StringUtil.equalStrings(version, od.version) &&
             StringUtil.equalStrings(classifier, od.classifier) && StringUtil.equalStrings(type, od.type);
   }

   public int hashCode() {
      return groupId.hashCode() + artifactId.hashCode();
   }

   public void mergeFrom(MvnDescriptor parentDesc) {
      if (version == null && parentDesc.version != null)
         version = parentDesc.version;
      // TODO: any other attributes inherited here?
   }
}