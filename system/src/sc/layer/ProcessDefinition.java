/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.dyn.DynUtil;
import sc.util.StringUtil;

import java.util.List;

/**
 * An instance of this class is used to represent each process we need to build.  If the processName is null
 * we are using the default process - i.e. no layers have defined any process constraints.
 */
public class ProcessDefinition implements IProcessDefinition {
   String processName;
   public ProcessDefinition() {
   }
   public ProcessDefinition(String procName) {
      processName = procName;
   }
   public ProcessDefinition(String procName, IRuntimeProcessor proc) {
      this(procName);
      runtimeProcessor = proc;
   }
   public String getProcessName() {
      return processName;
   }
   public void setProcessName(String n) {
      processName = n;
   }

   IRuntimeProcessor runtimeProcessor;
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

   public static ProcessDefinition create(String procName) {
      return new ProcessDefinition(procName);
   }

   public static ProcessDefinition create(String procName, IRuntimeProcessor proc) {
      return new ProcessDefinition(procName, proc);
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
}
