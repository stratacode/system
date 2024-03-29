/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.js.URLPath;
import sc.type.RTypeUtil;
import sc.type.TypeUtil;
import sc.util.FileUtil;
import sc.util.StringUtil;
import sc.lang.java.*;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Stores the information accumulated during the processing of layers for compilation.  This object is automatically
 * saved to a sc file in the buildSrc directory.  It caches information for the types when using incremental
 * compilation so all source files don't have to be processed.  It makes this information available to compile time
 * templates.
 */
public class BuildInfo {
   public String layerNames;
   public List<ModelJar> modelJarFiles;
   public List<MainMethod> mainMethods;
   public List<TestInstance> testInstances;
   public Set<TypeGroupMember> typeGroupMembers;
   public Set<ExternalDynType> extDynTypes;

   transient LayeredSystem system;
   transient Layer layer;
   transient boolean changed = false;
   transient HashMap<String,ITestProcessor> testProcessors = new HashMap<String,ITestProcessor>();
   transient HashMap<String,ExternalDynType> extDynTypeIndex;
   transient List<SrcEntry> toCompile;
   public final static String StartupGroupName = "_startup";
   public final static String InitGroupName = "_init";
   public final static String AllowEditorCreateGroupName = "_allowEditorCreate";

   // The above public properties are saved in source format for easy inspection. For other information
   // that might be larger or not as useful to be in ascii, it can go in BuildInfoData to be serialized.
   transient BuildInfoData buildInfoData;

   public BuildInfo() {
   }

   BuildInfo(Layer lyr) {
      system = lyr.layeredSystem;
      layer = lyr;
   }

   public void init() {
      if (typeGroupMembers != null)
         for (TypeGroupMember mem:typeGroupMembers)
            mem.system = system;

      if (extDynTypes != null) {
         extDynTypeIndex = new HashMap<String, ExternalDynType>();
         for (ExternalDynType type:extDynTypes) {
            extDynTypeIndex.put(type.className, type);
            // Make sure we init the reverseDeps here as well
            type.initForBuildInfo(this);
         }
      }
   }

   public void registerTestProcessor(String testType, ITestProcessor tp) {
      testProcessors.put(testType, tp);
   }

   public void reload() {
      HashMap<String,ITestProcessor> newProcs = new HashMap<String,ITestProcessor>();
      for (Map.Entry<String,ITestProcessor> tp:testProcessors.entrySet()) {
         String typeName = TypeUtil.getTypeName(tp.getValue().getClass(), false);
         newProcs.put(tp.getKey(), (ITestProcessor) RTypeUtil.createInstance(RTypeUtil.loadClass(system.getSysClassLoader(), typeName, true)));
      }
      testProcessors = newProcs;
   }

   private void initMainMethods() {
      if (mainMethods == null)
         mainMethods = new ArrayList<MainMethod>();
   }

   public void addMainCommand(Layer modelLayer, String typeName, String execName, String[] args, String stopMethod) {
      MainMethod newMM = new MainMethod(typeName, execName, args, stopMethod);
      if (mainMethods != null && mainMethods.contains(newMM))
         return;
      initMainMethods();
      mainMethods.add(newMM);

      if (system != null && system.buildInfo == this) {
         for (Layer l:system.layers) {
            if ((modelLayer == l || l.extendsLayer(modelLayer)) && needsBuildInfo(l)) {
               // We may be compiling a previous layer but still need to add to a layer who's start method has not been called (since we start just before compiling so that we can pick up previous layers libs in the "start" method of the Layer)
               if (l.buildInfo == null)
                  l.loadBuildInfo();
               l.buildInfo.addMainCommand(modelLayer, typeName, execName, args, stopMethod);
            }
         }
      }
   }

   public void addMainCommand(JavaModel model, String execName, String[] args, String stopMethod) {
      Layer modelLayer = model.getLayer();
      changed = true;
      String typeName = model.getModelTypeName();

      addMainCommand(modelLayer, typeName, execName, args, stopMethod);
   }

   public void runMatchingMainMethods(String runClass, String[] runClassArgs, List<Layer> theLayers) {
      if (mainMethods == null || mainMethods.size() == 0) {
         if (system != null && system.options.info) {
            if (runClass.equals(".*"))
               System.out.println("No main methods defined - nothing to run.");
         }
         return;
      }
      Pattern p = Pattern.compile(runClass);
      boolean any = false;
      for (int i = 0; i < mainMethods.size(); i++) {
         MainMethod m = mainMethods.get(i);
         if (p.matcher(m.typeName).matches()) {
            String res = system.runMainMethod(m.typeName, m.args == null ? runClassArgs : m.args, theLayers);
            if (res != null)
               system.error(res);
            any = true;
         }
      }
      if (!any) {
         // Let them specify a main class to run even if they don't use MainSettings
         String res = system.runMainMethod(runClass, runClassArgs, theLayers);
         if (res != null)
            system.error(res);
      }
   }

   public void runMatchingMainMethods(String runClass, String[] runClassArgs, int lowestLayer) {
      if (mainMethods == null || mainMethods.size() == 0) {
         if (system != null && system.options.verbose) {
            if (runClass.equals(".*"))
               System.out.println("No main methods defined - nothing to run.");
            else
               System.out.println("No main methods defined matching: " + runClass);
         }
         return;
      }
      Pattern p = Pattern.compile(runClass);
      boolean any = false;
      for (int i = 0; i < mainMethods.size(); i++) {
         MainMethod m = mainMethods.get(i);
         if (p.matcher(m.typeName).matches()) {
            String err = system.runMainMethod(m.typeName, m.args == null ? runClassArgs : m.args, lowestLayer);
            if (err != null)
               system.error(err);
            any = true;
         }
      }
      if (!any)
         System.out.println("No main methods match pattern: " + runClass + " in: " + mainMethods);
   }

   public void stopMainInstances() {
      if (mainMethods == null || mainMethods.size() == 0) {
         return;
      }
      boolean any = false;
      for (int i = 0; i < mainMethods.size(); i++) {
         MainMethod m = mainMethods.get(i);
         system.stopMainInstances(m.typeName);
         if (m.stopMethod != null) {
            String res = system.runStopMethod(m.typeName, m.stopMethod);
            if (res != null)
               System.err.println("*** Failed to run stopMethod: " + res);
         }
      }
   }

   public void runMatchingScripts(String runClass, String[] runClassArgs, List<Layer> theLayers) {
      if (mainMethods == null || mainMethods.size() == 0)
         throw new IllegalArgumentException("No main methods defined for runClass: " + runClass);
      Pattern p = Pattern.compile(runClass);
      boolean any = false;
      for (int i = 0; i < mainMethods.size(); i++) {
         MainMethod m = mainMethods.get(i);
         if (p.matcher(m.typeName).matches()) {
            if (theLayers != null) {
               TypeDeclaration td = system.getSrcTypeDeclaration(m.typeName, null, true);
               if (td == null || !theLayers.contains(td.getLayer()))
                  continue;
            }
            system.runScript(m.execName, runClassArgs);
            any = true;
         }
      }
      if (!any)
         System.out.println("No main methods match pattern: " + runClass + " in: " + mainMethods);
   }

   public void runMatchingScripts(String runClass, String[] runClassArgs, int lowestLayer) {
      if (mainMethods == null || mainMethods.size() == 0)
         throw new IllegalArgumentException("No main methods defined for runClass: " + runClass);
      Pattern p = Pattern.compile(runClass);
      boolean any = false;
      for (int i = 0; i < mainMethods.size(); i++) {
         MainMethod m = mainMethods.get(i);
         if (p.matcher(m.typeName).matches()) {
            if (lowestLayer != -1) {
               TypeDeclaration td = system.getSrcTypeDeclaration(m.typeName, null, true);
               if (td == null || td.getLayer().layerPosition <= lowestLayer)
                  continue;
            }
            system.runScript(m.execName, runClassArgs);
            any = true;
         }
      }
      if (!any)
         System.out.println("No main methods match pattern: " + runClass + " in: " + mainMethods);
   }

   public void addTypeGroupMember(String typeName, String templatePathName, String typeGroupName) {
      changed = true;
      if (typeGroupMembers == null)
         typeGroupMembers = new LinkedHashSet<TypeGroupMember>();
      TypeGroupMember mem = new TypeGroupMember(typeName, typeGroupName, templatePathName);
      // Remove the unchanged one so we can one which is changed
      if (typeGroupMembers.contains(mem))
         typeGroupMembers.remove(mem);
      mem.system = system;
      mem.changed = true;
      typeGroupMembers.add(mem);

      if (system != null && system.buildInfo == this) {
         for (Layer l:system.layers) {
            if (needsBuildInfo(l)) {
               l.buildInfo.addTypeGroupMember(typeName, templatePathName, typeGroupName);
            }
         }
      }
   }

   private boolean needsBuildInfo(Layer l) {
      return l.isInitialized() && !l.compiled && l.isBuildLayer() && l != system.buildLayer && l.buildInfo != null;
   }

   /**
    * A jar file build by the layered system.  Contains a type which registers it, a main class (opt),
    * a jar file name and an optional list of packages to include from the buildDir
    */
   public static class ModelJar {
      public String typeName;
      public String mainClassName;
      public String jarName;
      public String[] packages;
      public boolean src;
      public boolean includeDeps;
      public ModelJar(String tn, String mainClass, String jn, String[] pkgs, boolean src, boolean includeDeps) {
         typeName = tn;
         mainClassName = mainClass;
         jarName = jn;
         packages = pkgs;
         this.src = src;
         this.includeDeps = includeDeps;
      }

      public boolean equals(Object other) {
         if (other instanceof ModelJar) {
            ModelJar otherMJ = (ModelJar) other;
            if (!StringUtil.equalStrings(typeName, otherMJ.typeName))
               return false;
            if (!StringUtil.equalStrings(mainClassName, otherMJ.mainClassName))
               return false;
            if (!StringUtil.equalStrings(jarName, otherMJ.jarName))
               return false;

            return true;
         }
         return false;
      }

      public int hashCode() {
         return typeName.hashCode();
      }
   }

   /** Main method registered with MainSettings.  Provides the name of the exec to generate for this main. */
   public static class MainMethod {
      public String typeName;
      public String execName;
      public String[] args;
      public String stopMethod;

      public MainMethod(String typeName, String e, String[] a, String stopMethod) {
         this.typeName = typeName;
         execName = e;
         args = a;
         this.stopMethod = stopMethod;
      }
      public String toString() {
         return typeName;
      }

      public boolean equals(Object other) {
         if (other instanceof MainMethod) {
            MainMethod otherMM = (MainMethod) other;
            if (!StringUtil.equalStrings(typeName, otherMM.typeName))
               return false;
            if (!StringUtil.equalStrings(execName, otherMM.execName))
               return false;
            if (!StringUtil.equalStrings(stopMethod, otherMM.stopMethod))
               return false;
            return true;
         }
         return false;
      }

      public int hashCode() {
         return typeName.hashCode() + (execName != null ? execName.hashCode() : 0) + (stopMethod != null ? stopMethod.hashCode() : 0);
      }
   }

   /** Global test instances - essentially a simple global registry of test class name to the type of test (e.g. junit) */
   public static class TestInstance {
      public String typeName;
      public String testType;
      public TestInstance(String typeName, String testType) {
         this.typeName = typeName;
         this.testType = testType;
      }

      public boolean equals(Object other) {
         if (other instanceof TestInstance) {
            TestInstance otherMM = (TestInstance) other;
            if (!StringUtil.equalStrings(typeName, otherMM.typeName))
               return false;
            if (!StringUtil.equalStrings(testType, otherMM.testType))
               return false;
            return true;
         }
         return false;
      }

      public int hashCode() {
         return typeName.hashCode() + (testType != null ? testType.hashCode() : 0);
      }
   }

   public static class ExternalDynType {
      public String className;
      public transient ReverseDependencies reverseDeps;

      public ExternalDynType(String name) {
         className = name;
      }

      public int hashCode() {
         return className.hashCode();
      }

      public boolean equals(Object other) {
         if (!(other instanceof ExternalDynType))
            return false;

         return ((ExternalDynType) other).className.equals(className);
      }

      public void initForBuildInfo(BuildInfo bi) {
         reverseDeps = ReverseDependencies.readReverseDeps(FileUtil.addExtension(bi.getExternalDynTypeFileName(className), ReverseDependencies.REVERSE_DEPENDENCIES_EXTENSION), null);
         if (reverseDeps == null)
            reverseDeps = new ReverseDependencies();

      }
   }

   public void addExternalDynMethod(Object referenceTypeObj, Object methodType, JavaModel fromModel) {
      ExternalDynType type = getExternalDynType(referenceTypeObj, true);
      type.reverseDeps.addDynMethod(fromModel.getModelTypeName(), ModelUtil.getMethodName(methodType), ModelUtil.getTypeSignature(methodType));
   }

   public ExternalDynType getExternalDynType(Object referenceTypeObj, boolean add) {
      String typeName = ModelUtil.getTypeName(referenceTypeObj);
      if (extDynTypes == null) {
         if (!add)
            return null;
         extDynTypes = new HashSet<ExternalDynType>();
         extDynTypeIndex = new HashMap<String,ExternalDynType>();
      }
      ExternalDynType type = extDynTypeIndex.get(typeName);
      if (type == null && add) {
         type = new ExternalDynType(typeName);
         type.initForBuildInfo(this);
         extDynTypes.add(type);
         extDynTypeIndex.put(typeName, type);
      }
      return type;
   }

   private final static String EXTERNAL_TYPE_PREFIX = "scstub";

   public String getExternalDynTypeName(String typeName) {
      return EXTERNAL_TYPE_PREFIX + "." + typeName;
   }

   public String getExternalDynTypeFileName(String typeName) {
      return FileUtil.concat(layer.buildSrcDir, getExternalDynTypeRelFile(typeName));
   }

   private String getExternalDynTypeRelFile(String typeName) {
      return FileUtil.concat(EXTERNAL_TYPE_PREFIX, typeName.replace(".", FileUtil.FILE_SEPARATOR));
   }

   public void addExternalDynProp(Object referenceType, String propertyName, JavaModel fromModel, boolean referenceOnly) {
      ExternalDynType type = getExternalDynType(referenceType, true);
      type.reverseDeps.addBindDependency(fromModel.getModelTypeName(), propertyName, referenceOnly);
   }

   private void initTestInstances() {
      if (testInstances == null)
         testInstances = new ArrayList<TestInstance>();
   }

   /** Adds a test instance - essentially a simple global registry of test class name to the type of test (e.g. junit) */
   public void addTestInstance(TestInstance tinst) {
      changed = true;
      initTestInstances();
      testInstances.add(tinst);

      if (system != null && system.buildInfo == this) {
         for (Layer l:system.layers) {
            if (needsBuildInfo(l) && l.buildInfo != null) {
               l.buildInfo.addTestInstance(tinst);
            }
         }
      }
   }

   public TestInstance getTestInstance(String typeName) {
      if (testInstances != null) {
         int sz = testInstances.size();
         for (int i = 0; i < sz; i++) {
            TestInstance ti = testInstances.get(i);
            if (ti.typeName.equals(typeName))
               return ti;
         }
      }
      return null;
   }

   /** Adds a model jar file to the build info */
   public void addModelJar(JavaModel model, String mainClassName, String jarName, String[] packages, boolean src, boolean includeDeps) {
      changed = true;
      if (modelJarFiles == null)
         modelJarFiles = new ArrayList<ModelJar>();
      ModelJar newMjar = new ModelJar(model.getModelTypeName(), mainClassName, jarName, packages, src, includeDeps);
      if (!modelJarFiles.contains(newMjar))
         modelJarFiles.add(newMjar);

      if (system != null && system.buildInfo == this) {
         for (Layer l:system.layers) {
            if (needsBuildInfo(l)) {
               l.buildInfo.addModelJar(model, mainClassName, jarName, packages, src, includeDeps);
            }
         }
      }
   }

   /**
    * For incremental builds, we keep track of the set of changed models.  For global data structures
    * like modelJarFiles, mainMethods, etc. here we go through and remove the entry for any changed models.
    * The model should add it back again during the process phase.  This gets run after the validate phase.
    * We also must track the set of models that have already been processed in a previous build layer.  If the model
    * is changed but also processed, we can't remove it because we won't be adding it back in again.
    */
   void cleanStaleEntries(HashSet<String> changedTypes, Set<String> processedModels, boolean removeEntries) {
      if (modelJarFiles != null) {
         for (int i = 0; i < modelJarFiles.size(); i++) {
            ModelJar mj = modelJarFiles.get(i);
            if (mj == null || (changedTypes.contains(mj.typeName) && !processedModels.contains(mj.typeName))) {
               modelJarFiles.remove(i);
               i--;
            }
         }
      }
      if (mainMethods != null) {
         for (int i = 0; i < mainMethods.size(); i++) {
            MainMethod mm = mainMethods.get(i);
            if (changedTypes.contains(mm.typeName) && !processedModels.contains(mm.typeName)) {
               mainMethods.remove(i);
               i--;
            }
         }
      }
      if (testInstances != null) {
         for (int i = 0; i < testInstances.size(); i++) {
            TestInstance ti = testInstances.get(i);
            if (changedTypes.contains(ti.typeName) && !processedModels.contains(ti.typeName)) {
               testInstances.remove(i);
               i--;
            }
         }
      }
      if (typeGroupMembers != null) {
         for (Iterator<TypeGroupMember> it = typeGroupMembers.iterator(); it.hasNext(); ) {
            TypeGroupMember ti = it.next();
            // Wait till the buildLayer till we remove entries.  We can't find types in layers which have not
            // been started and once they are removed when building a base layer we don't get them back again.
            if (removeEntries && !system.hasAnyTypeDeclaration(ti.typeName)) {
               it.remove();
               changed = true;
            }
            if (changedTypes.contains(ti.typeName) && !processedModels.contains(ti.typeName)) {
               // We call this multiple times on the same build info when building successive layers.  Some of these
               // guys may have already changed on this build and won't be processed in the next set of files 
               if (!ti.changed) {
                  it.remove();
                  changed = true;
               }
            }
         }
      }
      /*
       * If we have mappings of external types, to do incremental builds we go and remove any bindings or dynamic
       * dependencies for changed models.  They'll get added back again if they are still required.
       *
       * Only do this for the buildLayer though, since the classpath etc. won't be set up until then.
       */
      if (extDynTypes != null && removeEntries) {
         for (ExternalDynType edt:extDynTypes) {
            if (edt.reverseDeps != null) {
               // Remove any type references from types which are in the changedTypes set but did not get added to the
               // registry since being restored.
               edt.reverseDeps.cleanStaleEntries(changedTypes);
               if (edt.reverseDeps.hasChanged() || externalTypeClassStale(edt.className)) {
                  ReverseDependencies.saveReverseDeps(edt.reverseDeps, FileUtil.addExtension(getExternalDynTypeFileName(edt.className), ReverseDependencies.REVERSE_DEPENDENCIES_EXTENSION));
                  if (toCompile == null)
                     toCompile = new ArrayList<SrcEntry>();
                  toCompile.add(generateExternalType(edt.className, edt.reverseDeps));
               }
            }
         }
      }
   }

   private boolean externalTypeClassStale(String typeName) {
      String baseFileName = getExternalDynTypeFileName(typeName);
      File srcFile = new File(FileUtil.addExtension(baseFileName, "java"));
      File classFileName = new File(FileUtil.addExtension(baseFileName, "class"));
      return !classFileName.canRead() || !srcFile.canRead() || classFileName.lastModified() < srcFile.lastModified();
   }

   private static final String EXTERNAL_DYN_TYPE_TEMPLATE = "sc.lang.java.ExternalDynTypeTemplate";

   private SrcEntry generateExternalType(String typeName, ReverseDependencies reverseDeps) {
      String res = TransformUtil.evalTemplateResource(EXTERNAL_DYN_TYPE_TEMPLATE, new DynStubParameters(system, null, system.getTypeDeclaration(typeName), reverseDeps), system.getSysClassLoader());
      String fileName = FileUtil.addExtension(getExternalDynTypeFileName(typeName), "java");
      FileUtil.saveStringAsFile(fileName, res, true);

      return new SrcEntry(layer, fileName,  getExternalDynTypeRelFile(typeName));
   }

   public DynStubParameters getDynStubParameters(String typeName) {
      ExternalDynType edt;
      Object typeObj = system.getTypeDeclaration(typeName);
      if (extDynTypeIndex != null && (edt = extDynTypeIndex.get(typeName)) != null)
         return new DynStubParameters(system, null, typeObj, edt.reverseDeps);

      // Or if it's a regular src type return the stub for that
      if (typeObj instanceof TypeDeclaration)
         return new DynStubParameters(system, null, typeObj);
      return null;
   }

   public boolean buildJars() {
      if (modelJarFiles != null) {
         for (int j = 0; j < modelJarFiles.size(); j++) {
            ModelJar mjar = modelJarFiles.get(j);
            // Setting the classpath overrides the default classes...
            String rtPrefix = system.getRuntimePrefix();
            //String jarUpLevelPrefix = rtPrefix != null && rtPrefix.length() > 0 ? ".." : null;
            String jarUpLevelPrefix = null;
            if (LayerUtil.buildJarFile(system.buildDir, system.getRuntimePrefix(), FileUtil.concat(system.buildDir, jarUpLevelPrefix, FileUtil.unnormalize(mjar.jarName)), mjar.mainClassName, mjar.packages, /* userClassPath */ null, mjar.includeDeps ? system.getDepsClassPath() : null, mjar.src ? LayerUtil.SRC_JAR_FILTER : LayerUtil.CLASSES_JAR_FILTER, system.options.verbose) != 0)
               return false;
         }
      }
      return true;
   }

   public boolean initMatchingTests(String pattern) {
      return runOrInitMatchingTests(pattern, true);
   }

   public boolean runMatchingTests(String pattern) {
      return runOrInitMatchingTests(pattern, false);
   }

   public boolean runOrInitMatchingTests(String pattern, boolean initOnly) {
      boolean success = true;
      Pattern p = pattern == null ? null : Pattern.compile(pattern);
      if (testInstances != null) {
         for (int j = 0; j < testInstances.size(); j++) {
            TestInstance tinst = testInstances.get(j);
            if (p == null || p.matcher(tinst.typeName).matches()) {
               ITestProcessor tp = testProcessors.get(tinst.testType);
               if (tp == null) {
                  success = false;
                  System.err.println("*** No registered test processor for type: " + tinst.testType);
               }
               else {
                  Object cl = system.getRuntimeType(tinst.typeName);
                  if (cl != null) {
                     if (initOnly) {
                        tp.initTypes();
                     }
                     else if (!tp.executeTest(cl)) {
                        if (system.options.verbose)
                           System.err.println("FAILED: " + tinst.typeName);
                        success = false;
                     }
                     else if (system.options.verbose || system.options.testMode)
                        System.out.println("Test: " + tinst.typeName + " success");
                  }
                  else {
                     System.err.println("*** Missing test class: " + tinst.typeName);
                  }
               }
            }
         }
      }
      return success;
   }

   public List<TypeGroupMember> getTypeGroupMembers(String groupName) {
      List<TypeGroupMember> agms = new ArrayList<TypeGroupMember>();
      if (typeGroupMembers == null)
         return agms;
      for (TypeGroupMember memb:typeGroupMembers) {
         if (StringUtil.equalStrings(groupName, memb.groupName))
            agms.add(memb);
      }
      return agms;
   }

   public List<String> getTypeGroupTypeNames(String groupName) {
      List<TypeGroupMember> tgms = getTypeGroupMembers(groupName);
      if (tgms == null)
         return null;
      ArrayList<String> res = new ArrayList<String>(tgms.size());
      for (TypeGroupMember tgm:tgms)
         res.add(tgm.typeName);
      return res;
   }

   public void merge(BuildInfo src) {

      if (src.modelJarFiles != null) {
         for (int i = 0; i < src.modelJarFiles.size(); i++) {
            ModelJar mj = src.modelJarFiles.get(i);
            if (modelJarFiles == null)
               modelJarFiles = new ArrayList<ModelJar>();
            if (!modelJarFiles.contains(mj))
               modelJarFiles.add(mj);
         }
      }

      if (src.mainMethods != null) {
         for (int i = 0; i < src.mainMethods.size(); i++) {
            MainMethod mm = src.mainMethods.get(i);
            if (mainMethods == null || !mainMethods.contains(mm)) {
               initMainMethods();
               mainMethods.add(mm);
            }
         }
      }

      if (src.testInstances != null) {
         for (int i = 0; i < src.testInstances.size(); i++) {
            TestInstance ti = src.testInstances.get(i);
            if (testInstances == null || !testInstances.contains(ti)) {
               initTestInstances();
               testInstances.add(ti);
            }
         }
      }

      if (src.typeGroupMembers != null) {
         for (TypeGroupMember tgm:src.typeGroupMembers) {
            if (typeGroupMembers == null)
               typeGroupMembers = new LinkedHashSet<TypeGroupMember>();
            if (!typeGroupMembers.contains(tgm))
               typeGroupMembers.add(tgm);
         }
      }

      if (src.extDynTypes != null) {
         for (ExternalDynType edt:src.extDynTypes) {
            if (extDynTypes == null)
               extDynTypes = new HashSet<ExternalDynType>();
            if (!extDynTypes.contains(edt))
               extDynTypes.add(edt);
         }
      }
   }

   /**
    * Returns all of the declared URL top-level system types, including those in the mainInit and URLTypes type groups.
    * It also includes any @URL(testURLs=...) that are found.
    */
   public List<URLPath> getURLPaths() {
      ArrayList<URLPath> res = null;
      try {
         system.acquireDynLock(false);
         List<TypeGroupMember> mainInitMembers = getTypeGroupMembers("mainInit");
         List<TypeGroupMember> urlTypes = getTypeGroupMembers("URLTypes");
         if (mainInitMembers == null && urlTypes == null)
            return null;
         res = new ArrayList<URLPath>();
         if (mainInitMembers != null) {
            for (TypeGroupMember memb:mainInitMembers) {
               // Skip mainInit types which also have URL since we'll process them below
               if (memb.hasAnnotation("sc.html.URL"))
                  continue;
               URLPath path = new URLPath(memb.templatePathName, memb.getType());
               if (!system.serverEnabled) {
                  path.convertToRelativePath();
               }
               if (!res.contains(path))
                  res.add(path);
            }
         }
         if (urlTypes != null) {
            for (TypeGroupMember memb:urlTypes) {
               // Skip the Html and Page types
               Object sto = memb.getAnnotationValue("sc.html.URL", "subTypesOnly");
               if (sto != null && (Boolean) sto)
                  continue;
               // Skip CSS types
               Object resource = memb.getAnnotationValue("sc.html.URL", "resource");
               if (resource != null && (Boolean) resource)
                  continue;
               // Skip types which may not be loaded yet
               if (memb.getType() == null)
                  continue;
               URLPath path = new URLPath(memb.templatePathName, memb.getType());
               // TODO: when the pattern is in the pattern language, we can init the pattern, find the variables and build
               // a form for testing the URL?
               String annotURL = (String) memb.getAnnotationValue("sc.html.URL", "pattern");
               // When the server is not enabled, the URLPath's have to refer to the file system right now.
               // TODO: add a hook so for static sites (!serverEnabled), we could generate the path-mapping file for some other web server apache, nginx, etc?
               if (annotURL != null && system.serverEnabled)
                  path.url = annotURL;
               Boolean realTimeDef = (Boolean) memb.getAnnotationValue("sc.html.URL", "realTime");
               path.realTime = realTimeDef == null || realTimeDef;
               if (!system.serverEnabled) {
                  path.convertToRelativePath();
               }
               String[] testScripts = (String[]) memb.getAnnotationValue("sc.html.URL", "testScripts");
               if (testScripts != null) {
                  path.testScripts = testScripts;
               }
               if (!res.contains(path))
                  res.add(path);
               String[] testURLs = (String[]) memb.getAnnotationValue("sc.html.URL", "testURLs");
               // TODO: also need a hook so we can serve up the test URLs which match against a pattern
               if (testURLs != null && system.serverEnabled && annotURL != null) {
                  int testIx = 0;
                  for (String testURL:testURLs) {
                     URLPath testURLPath = new URLPath(memb.templatePathName + "/test" + testIx,
                                                        memb.getType());
                     testURLPath.url = testURL;
                     testURLPath.realTime = path.realTime;
                     if (!res.contains(testURLPath))
                        res.add(testURLPath);
                     testIx++;
                  }
               }
            }
         }
      }
      finally {
         system.releaseDynLock(false);
      }
      Collections.sort(res);
      return res;
   }

   private ArrayList<InitTypeInfo> getInitTypes(String groupName, boolean doStartup) {
      List<TypeGroupMember> initTypes = getTypeGroupMembers(groupName);
      ArrayList<InitTypeInfo> res = new ArrayList<InitTypeInfo>();
      if (initTypes != null) {
         for (TypeGroupMember memb:initTypes) {
            Object type = memb.getType();
            Integer priority = 0;
            if (type != null) {
               priority = (Integer) ModelUtil.getAnnotationValue(type, "sc.obj.CompilerSettings", "startPriority");
               if (priority == null)
                  priority = 0;
            }
            InitTypeInfo it = new InitTypeInfo();
            it.initType = memb;
            it.priority = priority;
            it.doStartup = doStartup;
            res.add(it);
         }
      }
      Collections.sort(res, Collections.reverseOrder());
      return res;
   }

   /** Returns the types that need to be started in the proper order */
   public List<InitTypeInfo> getInitTypes() {
      ArrayList<InitTypeInfo> res = getInitTypes("_init", false);
      ArrayList<InitTypeInfo> startRes = getInitTypes("_startup", true);
      if (startRes != null) {
         res.addAll(startRes);
      }
      return res;
   }

   public void updateSyncTypeNames(String forType, Set<String> syncTypeNames) {
      if (buildInfoData == null) {
         buildInfoData = new BuildInfoData();
      }
      buildInfoData.syncTypeNames.put(forType, syncTypeNames);
   }

   public Set<String> getSyncTypeNames(String forType) {
      if (buildInfoData == null)
         return null;
      return buildInfoData.syncTypeNames.get(forType);
   }

   public void updateResetSyncTypeNames(String forType, Set<String> syncTypeNames) {
      if (buildInfoData == null) {
         buildInfoData = new BuildInfoData();
      }
      buildInfoData.resetSyncTypeNames.put(forType, syncTypeNames);
   }

   public Set<String> getResetSyncTypeNames(String forType) {
      if (buildInfoData == null)
         return null;
      return buildInfoData.resetSyncTypeNames.get(forType);
   }

   public void addCompiledType(String compiledTypeName) {
      if (buildInfoData == null)
         buildInfoData = new BuildInfoData();
      buildInfoData.compiledTypes.add(compiledTypeName);
   }

   public boolean isCompiledType(String typeName) {
      if (buildInfoData == null)
         return false;
      return buildInfoData.compiledTypes.contains(typeName);
   }

   public void addRemoteMethodRuntime(String methFullName, String runtime) {
      if (buildInfoData == null) {
         buildInfoData = new BuildInfoData();
      }
      Set<String> remoteRuntimes = buildInfoData.remoteMethodRuntimes.get(methFullName);
      if (remoteRuntimes == null) {
         remoteRuntimes = new TreeSet<String>();
         buildInfoData.remoteMethodRuntimes.put(methFullName, remoteRuntimes);
      }
      else if (remoteRuntimes.contains(runtime))
         return;
      remoteRuntimes.add(runtime);
   }

   public Set<String> getRemoteMethodRuntimes(String methFullName) {
      if (buildInfoData == null)
         return null;
      return buildInfoData.remoteMethodRuntimes.get(methFullName);
   }
}
