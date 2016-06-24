/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos.mvn;

import sc.repos.DependencyContext;
import sc.repos.RepositoryPackage;
import sc.lang.html.Element;
import sc.lang.xml.XMLFileFormat;
import sc.type.CTypeUtil;
import sc.util.FileUtil;
import sc.util.MessageHandler;
import sc.util.StringUtil;
import sc.util.URLUtil;

import java.util.*;

/** Handles parsing and data model of the Maven POM file (pom.xml) */
public class POMFile extends XMLFileFormat {
   public static final POMFile NULL_SENTINEL = new POMFile(null, null, null, null, false, null);

   MvnRepositoryManager mgr;

   Element projElement;

   POMFile parentPOM;

   /** If this is a module in a parent package, the module name */
   String modulePath;

   boolean required = true;

   // Represents the chain of includes for variable resolving
   POMFile includedFromPOM;

   ArrayList<POMFile> modulePOMs;

   ArrayList<POMFile> importedPOMs;

   HashMap<String,String> properties = new HashMap<String,String>();

   ArrayList<MvnDescriptor> dependencyManagement;

   DependencyContext depCtx;

   public String packaging;

   public String artifactId;

   RepositoryPackage pomPkg;

   public MvnDescriptor getDescriptor() {
      if (pomPkg instanceof MvnRepositoryPackage)
         return ((MvnRepositoryPackage) pomPkg).getDescriptor();
      return null;
   }

   public POMFile(String fileName, MvnRepositoryManager mgr, DependencyContext ctx, RepositoryPackage pomPkg, boolean required, POMFile includedFromPOM) {
      super(fileName, mgr == null ? null : mgr.msg);
      this.mgr = mgr;
      depCtx = ctx;
      this.pomPkg = pomPkg;
      this.required = required;
      if (pomPkg instanceof MvnRepositoryPackage)
         ((MvnRepositoryPackage) pomPkg).pomFile = this;
      this.includedFromPOM = includedFromPOM;
   }

   public static POMFile readPOM(String fileName, MvnRepositoryManager mgr, DependencyContext depCtx, RepositoryPackage pomPkg, boolean required, POMFile parentPOM, POMFile includedFromPOM) {
      POMFile file = new POMFile(fileName, mgr, depCtx, pomPkg, required, includedFromPOM);
      file.parentPOM = parentPOM;
      // Need to put this before we try to parse it - in case we resolve another recursive reference during the parsing
      mgr.pomCache.put(fileName, file);
      try {
         if (file.parse())
            return file;
         else
            return null;
      }
      catch (IllegalArgumentException exc) {
         MessageHandler.error(mgr.msg, "Failed to read POM file: " + exc);
      }
      return null;
   }

   public boolean parse() {
      if (!super.parse())
         return false;

      projElement = getRootElement();

      if (projElement == null) {
         error("POM File should contain only a single <project> XML tag");
         return false;
      }

      if (!projElement.tagName.equals("project")) {
         error("POM file contains tag: " + projElement.tagName + " expected <project>");
         return false;
      }

      initPackaging();
      initProperties();
      // If we are a sub-module we are created with the parent and so don't need to init it
      if (parentPOM == null)
         initParent();
      // Must be after we've initialized the parent
      initCanonicalName();
      if (getUseRepositories())
         initRepositories();

      if (required)
         initModules();
      initDependencyManagement();

      return true;
   }

   private void initPackaging() {
      if (projElement != null) {
         packaging = projElement.getSimpleChildValue("packaging");
         if (packaging == null)
            packaging = "jar";
      }
   }

   private void initCanonicalName() {
      if (projElement != null) {
         MvnDescriptor desc = getDescriptor();
         if (desc != null) {
            if (desc.groupId == null) {
               desc.groupId = getGroupId();
            }
         }
         artifactId = projElement.getSimpleChildValue("artifactId");
         if (artifactId != null) {
            artifactId = artifactId.trim();
            pomPkg = mgr.registerNameForPackage(pomPkg, artifactId);
            if (desc != null) {
               desc.artifactId = artifactId;
               // And update the URL in the source so we save it pointing to the real artifactId
               if (pomPkg != null && pomPkg.currentSource != null) {
                  String descPkgName = desc.getPackageName();
                  // If we are a sub-package we might have one name that lets us find the package in the file system
                  // and another for finding it in the repository.  Need to preserve both names for proper initialization
                  if (pomPkg.packageName != null && (pomPkg.packageAlias == null || pomPkg.packageAlias.equals(descPkgName)) && !pomPkg.packageName.equals(descPkgName))
                     pomPkg.packageAlias = pomPkg.packageName;
                  pomPkg.packageName = descPkgName;
                  // The currentSource.srcURL will stay as the original URL
                  pomPkg.currentSource.url = desc.getURL();
                  // We only can have a different srcURL if we have no parent - for child packages, we actually update the url to include parent + module name here.
                  if (pomPkg.currentSource.srcURL == null || pomPkg.parentPkg != null)
                     pomPkg.currentSource.srcURL = pomPkg.currentSource.url;
                  pomPkg.updateInstallRoot(mgr);
               }
            }
         }
      }
   }

   private void initProperties() {
      Element propRoot = projElement.getSingleChildTag("properties");
      if (propRoot != null) {
         Element[] props = propRoot.getChildTags();
         for (Element prop:props) {
            String value = prop.getBodyAsString();
            if (value == null)
               value = "";
            else
               value = value.trim();
            properties.put(prop.tagName, value);
         }
      }
   }

   private void initParent() {
      Element parent = projElement.getSingleChildTag("parent");
      if (parent != null) {
         MvnDescriptor parentDesc = MvnDescriptor.getFromTag(this, parent, false, false, true);
         parentDesc.pomOnly = true; // This reference only requires the POM - not the jar or deps
         RepositoryPackage parentPackage = parentDesc.getOrCreatePackage(mgr.getChildManager(), false, DependencyContext.child(depCtx, pomPkg), false, null);
         // TODO: check for recursive references here!
         if (parentPackage != null) {
            // Wait to init the modules until after the parentPOM is set
            parentPOM = mgr.getPOMFile(parentDesc, parentPackage, DependencyContext.child(depCtx, pomPkg), false, null, this);
            parentPOM.initModules();
         }
      }
      // TODO: else - there is a default POM but so far, we don't need any of the contents since it's all concerned with the build
   }

   private void initRepositories() {
      if (projElement != null) {
         Element[] repos = projElement.getChildTagsWithName("repositories");
         if (repos != null && repos.length > 1)
            MessageHandler.error(msg, "Multiple repositories tags - only using the first one");
         ArrayList<MvnDescriptor> res = new ArrayList<MvnDescriptor>();
         if (repos != null) {
            Element[] repoTags = repos[0].getChildTagsWithName("repository");
            if (repoTags != null) {
               for (Element repoTag : repoTags) {
                  String repoURL = repoTag.getSimpleChildValue("url");
                  if (repoURL == null) {
                     MessageHandler.error(msg, "repository in POM - missing url attribute: ");
                  }
                  else {
                     boolean found = false;
                     for (MvnRepository old:mgr.repositories) {
                        if (old.baseURL.equals(repoURL)) {
                           found = true;
                           break;
                        }
                     }
                     if (!found)
                        mgr.repositories.add(new MvnRepository(repoURL));
                  }
                  // TODO: do we need id, name, layout, releases and snapshots information
               }
            }
         }
      }
   }

   private boolean getUseRepositories() {
      return pomPkg instanceof MvnRepositoryPackage && ((MvnRepositoryPackage) pomPkg).useRepositories;
   }

   private String getArtifactId() {
      return getProperty("project.artifactId", true, true);
   }

   private void initModules() {
      Element modulesRoot = projElement.getSingleChildTag(("modules"));
      modulePOMs = new ArrayList<POMFile>();
      if (modulesRoot != null) {
         Element[] modules = modulesRoot.getChildTagsWithName("module");
         if (modules != null) {
            String parentName = getProperty("project.artifactId", true, true);
            if (pomPkg instanceof MvnRepositoryPackage) {
               String newParentName = ((MvnRepositoryPackage) pomPkg).getModuleBaseName();
               if (newParentName != null && !newParentName.equals(parentName))
                  parentName = newParentName;
            }
            for (Element module:modules) {
               String moduleName = module.getBodyAsString();
               while (moduleName.startsWith("../")) {
                  moduleName = moduleName.substring(3);
                  if (parentName != null)
                     parentName = CTypeUtil.getPackageName(parentName);
               }
               boolean isSrc = pomPkg.buildFromSrc;
               if (parentName != null) {
                  if (!isSrc)
                     moduleName = CTypeUtil.prefixPath(CTypeUtil.getPackageName(parentName), moduleName);
                  // else - when we have nested source modules, they are located in the file system in directories.  Pass that name directoy in the MvnDescriptor
               }
               // Initially create this with a modulePath, not the artifactId.  Later when we read the POM we set the artifactId
               MvnDescriptor desc = new MvnDescriptor(getProperty("project.groupId", true, true), parentName, moduleName, null, getProperty("project.version", true, true));
               // Do not init the dependencies here.  We won't be able to resolve deps inbetween these modules.  We have to create them all and
               // initialize the deps for the modules when we init them for the parent.
               RepositoryPackage pkg = desc.getOrCreatePackage(mgr.getChildManager(), false, depCtx, false, isSrc ? (MvnRepositoryPackage) pomPkg : null);
               if (!isSrc && pkg.currentSource != null)
                  pkg.currentSource.srcURL = null;
               if (pkg != null) {
                  POMFile modPOM = mgr.getPOMFile(desc, pkg, depCtx, isSrc, isSrc ? this : null, null);
                  if (modPOM != null)
                     modulePOMs.add(modPOM);
               }
            }
         }
         // Copy these over so they get serialized along with the parent module and to make them easier to find
         ArrayList<RepositoryPackage> subPackages = new ArrayList<RepositoryPackage>();
         pomPkg.subPackages = subPackages;
         for (POMFile modPOM:modulePOMs) {
            RepositoryPackage modPkg = modPOM.pomPkg;
            subPackages.add(modPkg);
         }
      }
   }

   private final static String[] variableTagNames = {"project", "pom", "version", "artifactId", "baseDir"};

   public String getProperty(String name, boolean up, boolean down) {
      String val;

      boolean isTagVariable = false;
      for (String varTagName:variableTagNames) {
         if (name.startsWith(varTagName)) {
            String[] varPath = StringUtil.split(name, '.');
            if (varPath.length > 1 && varPath[0].equals(varTagName)) {
               isTagVariable = true;
               if (varTagName.equals("pom")) {
                  if (varPath.length == 2 & varPath[1].equals("groupId"))
                     return getGroupId();
                  else
                     MessageHandler.error(msg, "Unrecognized 'pom' variable - " + varTagName);
                  return null;
               }

               // TODO: Are there variables that don't start with pom or project?
               int i = varTagName.equals("project") ? 1 : 0;
               String result = null;

               Element parent = projElement;
               while (parent != null && i < varPath.length) {
                  if (i == varPath.length - 1) {
                     result = parent.getSimpleChildValue(varPath[i]);
                  }
                  else {
                     parent = parent.getSingleChildTag(varPath[i]);
                  }
                  i++;
               }
               if (result != null)
                  return result.trim();
            }
            else if (varPath.length == 1) {
               if (varTagName.equals("baseDir")) {
                  return FileUtil.getParentPath(fileName);
               }

               Element parent = projElement;
               // Version, artifactId - any others?
               if (parent != null) {
                  String res = parent.getSimpleChildValue(varPath[0]);
                  if (res != null)
                     return res.trim();
               }
            }
         }
      }

      // For user-defined variables, it looks like the precedance order is different than when
      // evaluating project.version (and other tag varables).   For user-defined variables
      // the closest project file wins but for tag attributes we'll search these those that are
      // next to each other in the POM.xml hierarchy.
      if (!isTagVariable &&  includedFromPOM != null && down) {
         val = includedFromPOM.getProperty(name, false, true);
         if (val != null)
            return val;
      }

      val = properties.get(name);
      if (val != null)
         return val;

      if (parentPOM != null && up) {
         val = parentPOM.getProperty(name, true, false);
         if (val != null)
            return val;
      }

      if (isTagVariable && includedFromPOM != null && down) {
         val = includedFromPOM.getProperty(name, false, true);
         if (val != null)
            return val;
      }

      if (isTagVariable)
         MessageHandler.error(msg, "Tag variable: " + name + " does not have a corresponding tag");

      return null;
   }

   public static final String DEFAULT_SCOPE = "compile";

   public List<MvnDescriptor> getDependencies(String[] scopes, boolean addChildren, boolean addParent, boolean parentDefinesSrc, DependencyContext ctx) {
      Element[] deps = projElement.getChildTagsWithName("dependencies");
      if (deps != null && deps.length > 1)
         MessageHandler.error(msg, "Multiple tags with dependencies - should be only one");
      ArrayList<MvnDescriptor> res = new ArrayList<MvnDescriptor>();
      if (deps != null) {
         Element[] depTags = deps[0].getChildTagsWithName("dependency");
         if (depTags != null) {
            for (Element depTag : depTags) {
               String depScope = depTag.getSimpleChildValue("scope");
               if (depScope == null)
                  depScope = DEFAULT_SCOPE;
               if (includesScope(scopes, depScope)) {
                  MvnDescriptor desc = MvnDescriptor.getFromTag(this, depTag, true, true, true);
                  if (ctx != null) {
                     List<RepositoryPackage> incPkgs = ctx.getIncludingPackages();
                     if (incPkgs != null) {
                        for (RepositoryPackage incPkg:incPkgs) {
                           // Maven packages which are in the dependency chain from this package should override the version number for any
                           // dependencies in the dependencyManagement section.  This list starts at the root and goes to the last one so
                           // as soon as we see an override we stop.
                           if (incPkg instanceof MvnRepositoryPackage) {
                              if (((MvnRepositoryPackage) incPkg).overrideVersion(desc))
                                 break;
                           }
                        }
                     }
                  }
                  if (!desc.optional || (pomPkg instanceof MvnRepositoryPackage && ((MvnRepositoryPackage) pomPkg).includeOptional))
                     res.add(desc);

               }
            }
         }
      }
      if (parentPOM != null && addParent) {
         addPOMRefDependencies(parentPOM, res, scopes, false, true, false, ctx);
      }
      // We either want to include modules that directly defineSrc or if the parent is a src module, we need to include the child dependencies
      // We don't want to include dependencies from the parent-pom's modules.
      if (pomPkg instanceof MvnRepositoryPackage && (((MvnRepositoryPackage) pomPkg).definesSrc || parentDefinesSrc) && addChildren) {
         if (modulePOMs == null)
            initModules();
         if (modulePOMs != null) {
            for (POMFile modulePOM:modulePOMs) {
               addPOMRefDependencies(modulePOM, res, scopes, true, false, true, ctx);
            }
         }
      }
      return res;
   }

   private void addPOMRefDependencies(POMFile refPOM, ArrayList<MvnDescriptor> res, String[] scopes, boolean checkChildren, boolean checkParent, boolean parentDefinesSrc, DependencyContext ctx) {
      List<MvnDescriptor> parentDeps = refPOM.getDependencies(scopes, checkChildren, checkParent, parentDefinesSrc, ctx);
      if (parentDeps != null) {
         for (MvnDescriptor parentDesc:parentDeps) {
            boolean found = false;
            for (MvnDescriptor childDesc:res) {
               if (childDesc.matches(parentDesc, false)) {
                  found = true;
                  childDesc.mergeFrom(parentDesc);
                  break;
               }
            }
            if (!found) {
               res.add(parentDesc);
            }
         }
      }

   }

   private boolean includesScope(String[] scopes, String scope) {
      for (int i = 0; i < scopes.length; i++) {
         if (scopes[i].equals(scope))
            return true;
      }
      return false;
   }

   private String replaceVariables(String input, boolean required) {
      int ix;
      do {
         ix = input.indexOf("${");
         if (ix != -1) {
            int endIx = input.indexOf("}", ix);
            if (endIx != -1) {
               String varName = input.substring(ix+2, endIx);
               String varValue = getProperty(varName, true, true);
               if (varValue == null) {
                  if (required)
                      MessageHandler.error(msg, "No value for POM variable: " + varName + " in: ", this.toString());
                  return input;
               }
               input = input.substring(0, ix) + varValue + input.substring(endIx + 1);
            }
            else {
               MessageHandler.error(msg, "Misformed variable name: " + input + " missing close }");
               break;
            }
         }
      } while (ix != -1);
      return input;
   }

   public String getTagValue(Element tag, String valName, boolean required) {
      String res = tag.getSimpleChildValue(valName);
      if (res == null)
         return null;
      String tRes = res.trim();
      do {
         // TODO: Check if this is correct: we are replacing the variables we find in one file with resolutions from the same context - i.e.
         // starting in the current file, not looking from the file that originated the first call to getTagValue.
         String newRes = replaceVariables(tRes, required);
         // Keep going as long as we replaced something.
         if (newRes == tRes)
            break;
         res = newRes;
         tRes = newRes;
      } while (true);
      /*
      if (tRes.startsWith("${") && tRes.endsWith("}")) {
         String varName = tRes.substring(2, tRes.length()-1);
         String val = getProperty(varName, true, true);
         if (val == null) {
            if (required)
               MessageHandler.error(msg, "No value for POM variable: " + varName + " in: ", this.toString());
            else
               return res;
         }
         return val;
      }
      else if (tRes.contains("${"))
         System.out.println("*** Warning - variable inside of value: " + tRes);
       */
      return res;
   }

   public String toString() {
      return fileName;
   }

   void initDependencyManagement() {
      if (dependencyManagement == null) {
         dependencyManagement = new ArrayList<MvnDescriptor>();

         Element depMgmt = projElement.getSingleChildTag("dependencyManagement");
         if (depMgmt != null) {
            Element depsRoot = depMgmt.getSingleChildTag("dependencies");
            if (depsRoot != null) {
               Element[] deps = depsRoot.getChildTagsWithName("dependency");
               if (deps != null) {
                  for (Element dep : deps) {
                     String scope = dep.getSimpleChildValue("scope");
                     // For <scope>import</scope> (where type = pom) we need to read the POM file specified by this
                     // descriptor and include the dependencyManagement tags from the referenced file directly into this
                     // files dependency management section.
                     if (scope != null && scope.equals("import")) {
                        // TODO: assert that dep's type attribute = pom.
                        MvnDescriptor importDesc = MvnDescriptor.getFromTag(this, dep, false, false, false);
                        DependencyContext importDepCtx = DependencyContext.child(depCtx, pomPkg);
                        RepositoryPackage importPkg = importDesc.getOrCreatePackage(mgr.getChildManager(), false, importDepCtx, false, null);
                        // TODO should we set includedFromPOM here so the imported file can resolve variables defined in this POM
                        POMFile importPOM = mgr.getPOMFile(importDesc, importPkg, importDepCtx, true, null, null);
                        if (importPOM != null) {
                           importPOM.initDependencyManagement();
                           if (importedPOMs == null)
                              importedPOMs = new ArrayList<POMFile>();
                           importedPOMs.add(importPOM);
                        }
                        else {
                           System.err.println("*** Failed to read POM file: " + importDesc + " for scope=import");
                        }
                     }
                     else
                        dependencyManagement.add(MvnDescriptor.getFromTag(this, dep, true, false, false));
                  }
               }
            }
         }
      }
   }

   /** If the child POM file does not specify a version or other properties, those are inherited from the parent POM in the dependency management section. */
   public void appendInheritedAtts(MvnDescriptor desc, HashSet<POMFile> visited) {
      if (visited == null)
         visited = new HashSet<POMFile>();
      if (visited.contains(this))
         return;
      visited.add(this);
      if (desc.version == null || desc.classifier == null) {
         initDependencyManagement();
         for (MvnDescriptor dep : dependencyManagement) {
            if (desc.matches(dep, false)) {
               if (desc.version == null && dep.version != null) {
                  desc.version = dep.version;
                  if (desc.version.contains("${")) {
                     mgr.error("Reference to dependencyManagement entry with unresolved version: " + this + ": " + desc);
                  }
                  // TODO: We should add this POM file name + attribute to MvnDescriptor as debugInfo, then log the dependency tree
                  // with all 'debugInfo' displayed
                  //if (mgr.info)
                  //   mgr.info("Version for package: " + desc + " chosen in file: " + this);
               }
               // It does not appear that we should be inheriting the type
               //if (desc.type == null && dep.type != null)
               //   desc.type = dep.type;
               if (desc.classifier == null && dep.classifier != null) {
                  desc.classifier = dep.classifier;
               }
            }
         }
         if (parentPOM != null)
            parentPOM.appendInheritedAtts(desc, visited);
         if (importedPOMs != null) {
            for (POMFile importPOM:importedPOMs) {
               importPOM.appendInheritedAtts(desc, visited);
            }
         }
      }
      if (modulePOMs == null)
         initModules();
      if (modulePOMs != null) {
         for (POMFile modPOM:modulePOMs) {
            modPOM.appendInheritedAtts(desc, visited);
         }
      }
   }

   public String getGroupId() {
      String res;

      res = projElement.getSimpleChildValue("groupId");
      if (res != null) {
         return res.trim();
      }

      if (parentPOM != null)
         return parentPOM.getGroupId();

      return null;
   }

   /** TODO: performance - build up a hash-table of these dependencies by group-id/artifactId so we can filter them quickly */
   public boolean overrideVersion(MvnDescriptor desc) {
      initDependencyManagement();
      if (dependencyManagement != null) {
         for (MvnDescriptor depDesc:dependencyManagement) {
            if (depDesc.version != null && depDesc.matches(desc, false)) {
               if (desc.version == null || !desc.version.equals(depDesc.version))
                  desc.version = depDesc.version;
               return true;
            }
         }
      }
      if (parentPOM != null)
         parentPOM.overrideVersion(desc);
      return false;
   }
}
