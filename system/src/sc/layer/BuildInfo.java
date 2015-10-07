/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

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

   public void addMainCommand(JavaModel model, String execName) {
      addMainCommand(model, execName, null);
   }

   private void initMainMethods() {
      if (mainMethods == null)
         mainMethods = new ArrayList<MainMethod>();
   }

   public void addMainCommand(Layer modelLayer, String typeName, String execName, String[] args) {
      MainMethod newMM = new MainMethod(typeName, execName, args);
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
               l.buildInfo.addMainCommand(modelLayer, typeName, execName, args);
            }
         }
      }
   }

   public void addMainCommand(JavaModel model, String execName, String[] args) {
      Layer modelLayer = model.getLayer();
      changed = true;
      String typeName = model.getModelTypeName();

      addMainCommand(modelLayer, typeName, execName, args);
   }

   public void runMatchingMainMethods(String runClass, String[] runClassArgs, List<Layer> theLayers) {
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
            system.runMainMethod(m.typeName, m.args == null ? runClassArgs : m.args, theLayers);
            any = true;
         }
      }
      if (!any)
         System.out.println("No main methods match pattern: " + runClass + " in: " + mainMethods);
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
            system.runMainMethod(m.typeName, m.args == null ? runClassArgs : m.args, lowestLayer);
            any = true;
         }
      }
      if (!any)
         System.out.println("No main methods match pattern: " + runClass + " in: " + mainMethods);
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

   public void addTypeGroupMember(String typeName, String typeGroupName) {
      changed = true;
      if (typeGroupMembers == null)
         typeGroupMembers = new LinkedHashSet<TypeGroupMember>();
      TypeGroupMember mem = new TypeGroupMember(typeName, typeGroupName);
      // Remove the unchanged one so we can one which is changed
      if (typeGroupMembers.contains(mem))
         typeGroupMembers.remove(mem);
      mem.system = system;
      mem.changed = true;
      typeGroupMembers.add(mem);

      if (system != null && system.buildInfo == this) {
         for (Layer l:system.layers) {
            if (needsBuildInfo(l)) {
               l.buildInfo.addTypeGroupMember(typeName, typeGroupName);
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
      public MainMethod(String typeName, String e) {
         this.typeName = typeName;
         execName = e;
      }
      public MainMethod(String typeName, String e, String[] a) {
         this.typeName = typeName;
         execName = e;
         args = a;
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
            return true;
         }
         return false;
      }

      public int hashCode() {
         return typeName.hashCode() + (execName != null ? execName.hashCode() : 0);
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
      modelJarFiles.add(new ModelJar(model.getModelTypeName(), mainClassName, jarName, packages, src, includeDeps));

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
   void cleanStaleEntries(HashMap<String,IFileProcessorResult> changedModels, Set<String> processedModels, boolean removeEntries) {
      if (modelJarFiles != null) {
         for (int i = 0; i < modelJarFiles.size(); i++) {
            ModelJar mj = modelJarFiles.get(i);
            if (mj == null || (changedModels.get(mj.typeName) != null && !processedModels.contains(mj.typeName))) {
               modelJarFiles.remove(i);
               i--;
            }
         }
      }
      if (mainMethods != null) {
         for (int i = 0; i < mainMethods.size(); i++) {
            MainMethod mm = mainMethods.get(i);
            if (changedModels.get(mm.typeName) != null && !processedModels.contains(mm.typeName)) {
               mainMethods.remove(i);
               i--;
            }
         }
      }
      if (testInstances != null) {
         for (int i = 0; i < testInstances.size(); i++) {
            TestInstance ti = testInstances.get(i);
            if (changedModels.get(ti.typeName) != null && !processedModels.contains(ti.typeName)) {
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
            if (changedModels.get(ti.typeName) != null && !processedModels.contains(ti.typeName)) {
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
               // Remove any type references from types which are in the changedModels set but did not get added to the
               // registry since being restored.
               edt.reverseDeps.cleanStaleEntries(changedModels);
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
            if (LayerUtil.buildJarFile(system.buildDir, system.getRuntimePrefix(), mjar.jarName, mjar.mainClassName, mjar.packages, /* userClassPath */ null, mjar.includeDeps ? system.getDepsClassPath() : null, mjar.src ? LayerUtil.SRC_JAR_FILTER : LayerUtil.CLASSES_JAR_FILTER, system.options.verbose) != 0)
               return false;
         }
      }
      return true;
   }

   public boolean runMatchingTests(String pattern) {
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
                     if (!tp.executeTest(cl)) {
                        if (system.options.verbose)
                           System.err.println("FAILED: " + tinst.typeName);
                        success = false;
                     }
                     else if (system.options.verbose)
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
}
