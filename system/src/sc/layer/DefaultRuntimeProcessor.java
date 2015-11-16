/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.java.BodyTypeDeclaration;
import sc.lang.java.JavaSemanticNode;
import sc.lang.java.UpdateInstanceInfo;
import sc.util.FileUtil;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The RuntimeProcessor contains the code and configuration necessary to plug in a new runtime language, such as Javascript.
 * This default runtime processor is used to represent the default Java runtime.
 */
public class DefaultRuntimeProcessor implements IRuntimeProcessor, Serializable {
   public transient LayeredSystem system;

   /** Destination name you can use to talk to this runtime. (TODO: should this be a list?) */
   public String destinationName;

   public String runtimeName;

   boolean loadClassesInRuntime = true;

   ArrayList<String> syncProcessNames;

   public DefaultRuntimeProcessor(String rtName) {
      runtimeName = rtName;
   }

   public String getDestinationName() {
      return destinationName;
   }

   public String getRuntimeName() {
      return runtimeName;
   }

   public boolean initRuntime(boolean fromScratch) {
      return false;
   }

   public void saveRuntime() {
   }

   public void start(BodyTypeDeclaration def) {
   }

   public void process(BodyTypeDeclaration def) {
   }

   public List<SrcEntry> getProcessedFiles(IFileProcessorResult model, Layer genLayer, String buildSrcDir, boolean generate) {
      return model.getProcessedFiles(genLayer, genLayer.buildSrcDir, generate);
   }

   public String getStaticPrefix(Object def, JavaSemanticNode refNode) {
      // Used only by the JSRuntimeProcessor
      throw new UnsupportedOperationException();
   }

   /** Called after starting all types */
   public void postStart(LayeredSystem sys, Layer genLayer) {
   }

   /** Called after processing all types */
   public void postProcess(LayeredSystem sys, Layer genLayer) {
   }

   public String[] getJsFiles() {
      return new String[0];
   }

   public int getExecMode() {
      return 0;
   }

   public String replaceMethodName(LayeredSystem sys, Object methObj, String name) {
      return name;
   }

   public UpdateInstanceInfo newUpdateInstanceInfo() {
      return new UpdateInstanceInfo();
   }

   public void resetBuild() {
   }

   public void clearRuntime() {
   }

   public boolean getCompiledOnly() {
      return false;
   }

   public boolean getNeedsAnonymousConversion() {
      return false;
   }

   public void applySystemUpdates(UpdateInstanceInfo info) {
   }

   public boolean getNeedsEnumToClassConversion() {
      return false;
   }

   public boolean needsSrcForBuildAll(Object cl) {
      return false;
   }

   public void setLoadClassesInRuntime(boolean val) {
      this.loadClassesInRuntime = val;
   }
   public boolean getLoadClassesInRuntime() {
      return loadClassesInRuntime;
   }

   public List<String> getSyncProcessNames() {
      return syncProcessNames;
   }
   public void setSyncProcessNames(List<String> syncProcessNames) {
      this.syncProcessNames = new ArrayList<String>(syncProcessNames);
   }
   public void addSyncProcessName(String procName) {
      if (syncProcessNames == null)
         syncProcessNames = new ArrayList<String>();
      syncProcessNames.add(procName);
   }

   public String toString() {
      return runtimeName + " runtime";
   }

   public void setLayeredSystem(LayeredSystem sys) {
      system = sys;
      sys.enableRemoteMethods = false; // Don't look for remote methods in the JS runtime.  Changing this to true is a bad idea, even if you wanted the server to call into the browser.  We have some initialization dependency problems to work out.
   }

   public LayeredSystem getLayeredSystem() {
      return system;
   }

   public void runMainMethod(Object type, String runClass, String[] runClassArgs) {
      if (system.options.verbose)
         System.out.println("Warning: JSRuntime - not running main method for: " + runClass + " - this will run in the browser");
   }

   protected ArrayList<IRuntimeProcessor> syncRuntimes = new ArrayList<IRuntimeProcessor>();

   /** The list of runtimes that we are synchronizing with from this runtime.  In Javascript this is the default runtime */
   public List<IRuntimeProcessor> getSyncRuntimes() {
      return syncRuntimes;
   }

   public boolean usesThisClasspath() {
      return true;
   }

   public boolean equals(String runtimeName) {
      if (runtimeName == null)
         return getRuntimeName().equals(DEFAULT_RUNTIME_NAME);
      return getRuntimeName().equals(runtimeName);
   }

   public int hashCode() {
      // since null and 'java' are equivalent
      if (getRuntimeName().equals(DEFAULT_RUNTIME_NAME))
         return 0;
      return getRuntimeName().hashCode();
   }

   public static boolean compareRuntimes(IRuntimeProcessor proc1, IRuntimeProcessor proc2) {
      return proc1 == proc2 || (proc1 != null && proc1.equals(proc2)) || (proc2 != null && proc2.equals(proc1));
   }

   private static File getRuntimeFile(LayeredSystem sys, String runtimeName) {
      File typeIndexMainDir = new File(sys.getStrataCodeDir("idx"));
      return new File(typeIndexMainDir, runtimeName + ".ser");
   }

   public static IRuntimeProcessor readRuntimeProcessor(LayeredSystem sys, String runtimeName) {
      if (runtimeName.equals(IRuntimeProcessor.DEFAULT_RUNTIME_NAME))
         return null;
      File runtimeFile = getRuntimeFile(sys, runtimeName);
      ObjectInputStream ois = null;
      FileInputStream fis = null;
      try {
         ois = new ObjectInputStream(fis = new FileInputStream(runtimeFile));
         Object res = ois.readObject();
         if (res instanceof IRuntimeProcessor) {
            IRuntimeProcessor runtime = (IRuntimeProcessor) res;
            return runtime;
         }
         else {
            sys.error("Invalid runtime file contents: " + runtimeFile);
            runtimeFile.delete();
         }
      }
      catch (InvalidClassException exc) {
         System.out.println("runtime processor index - version changed: " + runtimeFile);
         runtimeFile.delete();
      }
      catch (IOException exc) {
         System.out.println("*** can't read runtime processor file: " + exc);
      }
      catch (ClassNotFoundException exc) {
         System.out.println("*** can't read runtime processor file: " + exc);
      }
      finally {
         FileUtil.safeClose(ois);
         FileUtil.safeClose(fis);
      }
      return null;
   }

   public static void saveRuntimeProcessor(LayeredSystem sys, IRuntimeProcessor runtime) {
      if (runtime == null) // Default runtime - no need to save
         return;
      File runtimeFile = getRuntimeFile(sys, runtime.getRuntimeName());

      ObjectOutputStream os = null;
      try {
         os = new ObjectOutputStream(new FileOutputStream(runtimeFile));
         os.writeObject(runtime);
      }
      catch (IOException exc) {
         System.err.println("*** can't save runtime processor in index " + exc);
      }
      finally {
         FileUtil.safeClose(os);
      }
   }
}
