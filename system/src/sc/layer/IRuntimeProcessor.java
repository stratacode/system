/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.java.BodyTypeDeclaration;
import sc.lang.java.JavaSemanticNode;
import sc.lang.java.UpdateInstanceInfo;

import java.util.List;

public interface IRuntimeProcessor {
   public final static String DEFAULT_RUNTIME_NAME = "java";

   public String getRuntimeName();

   public List<SrcEntry> getProcessedFiles(IFileProcessorResult model, Layer genLayer, String buildSrcDir, boolean generate);

   /** Return true if you need to build all files for this runtime.  If fromScratch is true, prepare for a full rebuild */
   public boolean initRuntime(boolean fromScratch);

   public void saveRuntime();

   public void start(BodyTypeDeclaration def);

   public void process(BodyTypeDeclaration def);

   /** The prefix to use for transforming to JS */
   public String getStaticPrefix(Object def, JavaSemanticNode refNode);

   /** Called after starting all types */
   public void postStart(LayeredSystem sys, Layer genLayer);

   /** Called after processing all types */
   public void postProcess(LayeredSystem sys, Layer genLayer);

   public String[] getJsFiles();

   public int getExecMode();

   /** Returns the given name or, if the runtime needs to rename this method for some reason (e.g. a conflict or framework injects a rename), returns the new method name. */
   public String replaceMethodName(LayeredSystem sys, Object methObj, String name);

   public void setLayeredSystem(LayeredSystem sys);

   public LayeredSystem getLayeredSystem();

   public void runMainMethod(Object type, String runClass, String[] runClassArgs);

   public String getDestinationName();

   public List<IRuntimeProcessor> getSyncRuntimes();

   public boolean usesThisClasspath();

   public UpdateInstanceInfo newUpdateInstanceInfo();

   /** Called just before building the second time to clear out state from a previous build */
   public void resetBuild();

   /** If this returns true, no dynamic layers are enabled in this runtime */
   public boolean getCompiledOnly();

   /** Javascript and other runtimes may need anonymous classes converted to real classes */
   public boolean getNeedsAnonymousConversion();

   public void applySystemUpdates(UpdateInstanceInfo info);

   boolean getNeedsEnumToClassConversion();

   /** Normally returns false - which means we can use .class files as an optimization except for special js types. */
   boolean needsSrcForBuildAll(Object cl);

   /** Some runtimes, like Javascript, are not active in Java so suppress loading classes there */
   boolean getLoadClassesInRuntime();

   /** The list of process names this runtime should sync against. */
   public List<String> getSyncProcessNames();
}
