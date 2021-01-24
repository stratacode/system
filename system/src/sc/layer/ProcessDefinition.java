/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.dyn.DynUtil;
import sc.util.FileUtil;
import sc.util.StringUtil;

import java.io.*;
import java.util.List;

/**
 * An instance of this class is used to represent each process we need to build.  If the processName is null
 * we are using the default process - i.e. no layers have defined any process constraints.
 */
public class ProcessDefinition implements IProcessDefinition, Serializable {
   String processName;
   boolean useContextClassLoader;

   public ProcessDefinition() {
   }
   public ProcessDefinition(String procName) {
      processName = procName;
   }
   public ProcessDefinition(String procName, IRuntimeProcessor proc, boolean useContextClassLoader) {
      this(procName);
      runtimeProcessor = proc;
      this.useContextClassLoader = useContextClassLoader;
   }
   public String getProcessName() {
      return processName;
   }
   public void setProcessName(String n) {
      processName = n;
   }

   transient IRuntimeProcessor runtimeProcessor;
   public IRuntimeProcessor getRuntimeProcessor() {
      return runtimeProcessor;
   }

   boolean perProcessSync;
   /** Set this to true if this process cannot use the default runtime's sync definition.  In this case, a new sync def is generated for this process. */
   public boolean getPerProcessSync() {
      return perProcessSync;
   }
   public void setPerProcessSync(boolean perProcessSync) {
      this.perProcessSync = perProcessSync;
   }

   List<String> syncProcessNames;
   public List<String> getSyncProcessNames() {
      if (syncProcessNames == null && runtimeProcessor != null)
         return runtimeProcessor.getSyncProcessNames();
      return syncProcessNames;
   }

   public void setSyncProcessNames(List<String> syncProcessNames) {
      this.syncProcessNames = syncProcessNames;
   }

   public void setRuntimeProcessor(IRuntimeProcessor proc) {
      runtimeProcessor = proc;
   }

   public String getRuntimeName() {
      return runtimeProcessor == null ? IRuntimeProcessor.DEFAULT_RUNTIME_NAME : runtimeProcessor.getRuntimeName();
   }

   public boolean getUseContextClassLoader() {
      return useContextClassLoader;
   }

   public static ProcessDefinition create(String procName, String runtimeName, boolean useContextClassLoader) {
      return new ProcessDefinition(procName, LayeredSystem.getRuntime(runtimeName), useContextClassLoader);
   }

   public String toString() {
      return getRuntimeName() + "_" + (processName == null ? "<default>" : processName);
   }

   public static boolean compare(IProcessDefinition proc, IProcessDefinition definedProcess) {
      if (proc == null) {
         return definedProcess == null || definedProcess.getRuntimeProcessor() == null;
      }
      else if (definedProcess == null) {
         return proc.getRuntimeProcessor() == null;
      }
      return proc.equals(definedProcess);
   }

   public boolean equals(Object other) {
      if (!(other instanceof ProcessDefinition))
         return false;
      ProcessDefinition otherDef = (ProcessDefinition) other;
      String procName = getProcessName();
      String otherProcName = otherDef.getProcessName();
      // Only mismatching process names should compare as not equal since a null process name matches anything.
      if (!StringUtil.equalStrings(procName, otherProcName) && procName != null && otherProcName != null)
         return false;
      return DynUtil.equalObjects(otherDef.getRuntimeProcessor(), getRuntimeProcessor());
   }

   private static File getProcessDefinitionFile(LayeredSystem sys, String runtimeName, String procName) {
      File typeIndexMainDir = new File(sys.getStrataCodeDir("idx"));
      return new File(typeIndexMainDir, FileUtil.addExtension(runtimeName + "_" + procName, "ser"));
   }

   public static ProcessDefinition readProcessDefinition(LayeredSystem sys, String runtimeName, String procName) {
      File procFile = getProcessDefinitionFile(sys, runtimeName, procName);
      ObjectInputStream ois = null;
      FileInputStream fis = null;
      try {
         ois = new ObjectInputStream(fis = new FileInputStream(procFile));
         Object res = ois.readObject();
         if (res instanceof ProcessDefinition) {
            return (ProcessDefinition) res;
         }
         else {
            sys.error("Invalid process file contents: " + procFile);
            procFile.delete();
         }
      }
      catch (InvalidClassException exc) {
         System.out.println("ProcessorDefinition - serialized version changed: " + procFile);
         procFile.delete();
      }
      catch (IOException exc) {
         System.out.println("*** can't read processDefinition file: " + exc);
      }
      catch (ClassNotFoundException exc) {
         System.out.println("*** can't read processDefinition file: " + exc);
      }
      finally {
         FileUtil.safeClose(ois);
         FileUtil.safeClose(fis);
      }
      return LayeredSystem.INVALID_PROCESS_SENTINEL;
   }

   public static void saveProcessDefinition(LayeredSystem sys, IProcessDefinition proc) {
      // No info - we only save the runtime processor
      if (proc.getProcessName() == null)
         return;
      File procFile = getProcessDefinitionFile(sys, proc.getRuntimeName(), proc.getProcessName());

      ObjectOutputStream os = null;
      try {
         os = new ObjectOutputStream(new FileOutputStream(procFile));
         os.writeObject(proc);
      }
      catch (IOException exc) {
         System.err.println("*** can't save process definition: " + exc);
      }
      finally {
         FileUtil.safeClose(os);
      }
   }
}
