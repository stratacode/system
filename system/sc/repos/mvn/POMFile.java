/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos.mvn;

import sc.repos.DependencyContext;
import sc.repos.RepositoryPackage;
import sc.lang.html.Element;
import sc.lang.xml.XMLFileFormat;
import sc.type.CTypeUtil;
import sc.util.MessageHandler;
import sc.util.StringUtil;

import java.util.*;

/** Handles parsing and data model of the Maven POM file (pom.xml) */
public class POMFile extends XMLFileFormat {
   public static final POMFile NULL_SENTINEL = new POMFile(null, null, null, null);

   MvnRepositoryManager mgr;

   Element projElement;

   POMFile parentPOM;

   ArrayList<POMFile> modulePOMs;

   ArrayList<POMFile> importedPOMs;

   HashMap<String,String> properties = new HashMap<String,String>();

   ArrayList<MvnDescriptor> dependencyManagement;

   DependencyContext depCtx;

   public String packaging;

   RepositoryPackage pomPkg;

   public POMFile(String fileName, MvnRepositoryManager mgr, DependencyContext ctx, RepositoryPackage pomPkg) {
      super(fileName, mgr == null ? null : mgr.msg);
      this.mgr = mgr;
      depCtx = ctx;
      this.pomPkg = pomPkg;
   }

   public static POMFile readPOM(String fileName, MvnRepositoryManager mgr, DependencyContext depCtx, RepositoryPackage pomPkg) {
      POMFile file = new POMFile(fileName, mgr, depCtx, pomPkg);
      // Need to put this before we try to parse it - in case we resolve another recursive reference during the parsing
      mgr.pomCache.put(fileName, file);
      if (file.parse())
         return file;
      else
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
      initParent();
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

   private void initProperties() {
      Element propRoot = projElement.getSingleChildTag("properties");
      if (propRoot != null) {
         Element[] props = propRoot.getChildTags();
         for (Element prop:props) {
            properties.put(prop.tagName, prop.getBodyAsString());
         }
      }
   }

   private void initParent() {
      Element parent = projElement.getSingleChildTag("parent");
      if (parent != null) {
         MvnDescriptor parentDesc = MvnDescriptor.getFromTag(this, parent, false, false);
         parentDesc.pomOnly = true; // This reference only requires the POM - not the jar or deps
         RepositoryPackage parentPackage = parentDesc.getOrCreatePackage(mgr, false, DependencyContext.child(depCtx, pomPkg), false);
         // TODO: check for recursive references here!
         if (parentPackage != null) {
            parentPOM = mgr.getPOMFile(parentDesc, parentPackage, DependencyContext.child(depCtx, pomPkg));
         }
      }
      // TODO: else - there is a default POM but so far, we don't need any of the contents since it's all concerned with the build
   }

   private void initModules() {
      Element modulesRoot = projElement.getSingleChildTag(("modules"));
      modulePOMs = new ArrayList<POMFile>();
      if (modulesRoot != null) {
         Element[] modules = modulesRoot.getChildTagsWithName("module");
         if (modules != null) {
            for (Element module:modules) {
               String moduleName = module.getBodyAsString();
               String parentName = getProperty("project.artifactId");
               while (moduleName.startsWith("../")) {
                  moduleName = moduleName.substring(3);
                  if (parentName != null)
                     parentName = CTypeUtil.getPackageName(parentName);
               }
               if (parentName != null)
                  moduleName = CTypeUtil.prefixPath(CTypeUtil.getPackageName(parentName), moduleName);
               MvnDescriptor desc = new MvnDescriptor(getProperty("project.groupId"), moduleName, getProperty("project.version"));
               RepositoryPackage pkg = desc.getOrCreatePackage(mgr, false, depCtx, false);
               if (pkg != null) {
                  POMFile modPOM = mgr.getPOMFile(desc, pkg, depCtx);
                  if (modPOM != null)
                     modulePOMs.add(modPOM);
               }
            }
         }
      }
   }

   private final static String[] variableTagNames = {"project", "pom"};

   public String getProperty(String name) {
      String val = properties.get(name);
      if (val != null)
         return val;

      boolean isTagVariable = false;
      for (String varTagName:variableTagNames) {
         if (name.startsWith(varTagName)) {
            String[] varPath = StringUtil.split(name, '.');
            if (varPath.length > 1 && varPath[0].equals(varTagName)) {
               isTagVariable = true;
               Element parent = projElement;
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
                  return result;
            }
         }
      }

      if (parentPOM != null) {
         val = parentPOM.getProperty(name);
         if (val != null)
            return val;
      }

      if (isTagVariable)
         MessageHandler.error(msg, "Tag variable: " + name + " does not have a corresponding tag");

      return null;
   }

   public static final String DEFAULT_SCOPE = "compile";

   public List<MvnDescriptor> getDependencies(String[] scopes) {
      Element[] deps = projElement.getChildTagsWithName("dependencies");
      if (deps == null || deps.length == 0)
         return Collections.emptyList();
      if (deps.length > 1)
         MessageHandler.error(msg, "Multiple tags with dependencies - should be only one");
      ArrayList<MvnDescriptor> res = new ArrayList<MvnDescriptor>();
      Element[] depTags = deps[0].getChildTagsWithName("dependency");
      if (depTags != null) {
         for (Element depTag:depTags) {
            String depScope = depTag.getSimpleChildValue("scope");
            if (depScope == null)
               depScope = DEFAULT_SCOPE;
            if (includesScope(scopes, depScope)) {
               MvnDescriptor desc = MvnDescriptor.getFromTag(this, depTag, true, true);
               // TODO: do we need a mechanism to specify which optional packages should be installed?
               if (!desc.optional)
                  res.add(desc);
            }
         }
      }
      return res;
   }

   private boolean includesScope(String[] scopes, String scope) {
      for (int i = 0; i < scopes.length; i++) {
         if (scopes[i].equals(scope))
            return true;
      }
      return false;
   }

   private String replaceVariables(String input) {
      int ix;
      do {
         ix = input.indexOf("${");
         if (ix != -1) {
            int endIx = input.indexOf("}", ix);
            if (endIx != -1) {
               String varName = input.substring(ix+2, endIx);
               String varValue = getProperty(varName);
               if (varValue == null) {
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

   public String getTagValue(Element tag, String valName) {
      String res = tag.getSimpleChildValue(valName);
      if (res == null)
         return null;
      String tRes = res.trim();
      if (tRes.startsWith("${") && tRes.endsWith("}")) {
         String varName = tRes.substring(2, tRes.length()-1);
         String val = getProperty(varName);
         if (val == null) {
            MessageHandler.error(msg, "No value for POM variable: " + varName + " in: ", this.toString());
         }
         return val;
      }
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
                        MvnDescriptor importDesc = MvnDescriptor.getFromTag(this, dep, false, false);
                        DependencyContext importDepCtx = DependencyContext.child(depCtx, pomPkg);
                        RepositoryPackage importPkg = importDesc.getOrCreatePackage(mgr, false, importDepCtx, false);
                        POMFile importPOM = mgr.getPOMFile(importDesc, importPkg, importDepCtx);
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
                        dependencyManagement.add(MvnDescriptor.getFromTag(this, dep, true, false));
                  }
               }
            }
         }
      }
   }

   /** If the child POM file does not specify a version or other properties, those are inherited from the parent POM in the dependency management section. */
   // TODO: need to implement the type=import operation where you can selectiely import dependencies from another POM.
   public void appendInherited(MvnDescriptor desc, HashSet<POMFile> visited) {
      if (visited == null)
         visited = new HashSet<POMFile>();
      if (visited.contains(this))
         return;
      visited.add(this);
      if (desc.version == null || desc.type == null || desc.classifier == null) {
         initDependencyManagement();
         for (MvnDescriptor dep : dependencyManagement) {
            if (desc.matches(dep)) {
               if (desc.version == null && dep.version != null) {
                  desc.version = dep.version;
                  if (mgr.info)
                     mgr.info("Version for package: " + desc + " chosen in file: " + this);
               }
               if (desc.type == null && dep.type != null)
                  desc.type = dep.type;
               if (desc.classifier == null && dep.classifier != null)
                  desc.classifier = dep.classifier;
            }
         }
         if (parentPOM != null)
            parentPOM.appendInherited(desc, visited);
         if (importedPOMs != null) {
            for (POMFile importPOM:importedPOMs) {
               importPOM.appendInherited(desc, visited);
            }
         }
      }
      if (modulePOMs != null) {
         for (POMFile modPOM:modulePOMs) {
            modPOM.appendInherited(desc, visited);
         }
      }
   }

   public String getGroupId() {
      String res;

      res = projElement.getSimpleChildValue("groupId");
      if (res != null)
         return res;

      if (parentPOM != null)
         return parentPOM.getGroupId();

      return null;
   }

}
