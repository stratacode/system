package sc.layer;

import sc.util.StringUtil;

import java.util.ArrayList;

/** Stores info shared by multiple LayeredSystems, part of the system multi-process system */
public class SystemContext {
   /** The list of runtimes required to execute this stack of layers (e.g. javascript and java).  If java is in the list, it will be the first one and represented by a "null" entry. */
   public ArrayList<IRuntimeProcessor> runtimes;

   /** The list of processes to build for this stack of layers */
   public ArrayList<IProcessDefinition> processes;

   /** A global setting turned on when in the IDE.  If true the original runtime is always 'java' - the default.  In the normal build env, if there's only one runtime, we never create the default runtime. */
   public boolean javaIsAlwaysDefaultRuntime = false;

   public boolean procInfoNeedsSave = false;

   public void addRuntime(Layer fromLayer, IRuntimeProcessor proc) {
      if (runtimes == null) {
         runtimes = new ArrayList<IRuntimeProcessor>();
         if (proc != null && javaIsAlwaysDefaultRuntime)
            addRuntime(null, null);
      }

      IRuntimeProcessor existing = null;
      for (IRuntimeProcessor existingProc:runtimes) {
         if (proc == null && existingProc == null)
            return;
         else if (existingProc != null && proc != null && proc.getRuntimeName().equals(existingProc.getRuntimeName()))
            existing = existingProc;
      }
      // Replace the old runtime - allows a subsequent layer to redefine the parameters of the JSRuntimeProcessor or even subclass it
      if (existing != null) {
         int ix = runtimes.indexOf(existing);
         runtimes.set(ix, proc);
      }
      else {
         // Default is always the first one in the list if it's there.
         if (proc == null) {
            runtimes.add(0, null);
         }
         else
            runtimes.add(proc);

         int i;
         int sz = processes == null ? 0 : processes.size();
         for (i = 0; i < sz; i++) {
            IProcessDefinition procDef = processes.get(i);
            IRuntimeProcessor procProc = procDef == null ? null : procDef.getRuntimeProcessor();

            if (procProc == proc)
               break;
         }
         // Add a new process only if there isn't one already defined for this runtime.
         if (i == sz) {
            ProcessDefinition newProcDef = proc == null ? null : new ProcessDefinition();
            if (newProcDef != null)
               newProcDef.setRuntimeProcessor(proc);
            addProcess(fromLayer, newProcDef);
         }
      }

   }

   private void initProcessesList(IProcessDefinition procDef) {
      if (processes == null) {
         processes = new ArrayList<IProcessDefinition>();
         if (procDef != null && javaIsAlwaysDefaultRuntime)
            addProcess(null, null);
      }
   }

   public IRuntimeProcessor getRuntime(String name) {
      if (runtimes == null)
         return null;
      for (IRuntimeProcessor proc:runtimes) {
         if (proc != null) {
            String procName = proc.getRuntimeName();
            if (procName.equals(name))
               return proc;
         }
      }
      return null;
   }

   public IProcessDefinition getProcessDefinition(String procName, String runtimeName) {
      if (processes != null) {
         for (IProcessDefinition proc : processes) {
            if (proc == null && procName == null && runtimeName == null)
               return null;

            if (proc != null && StringUtil.equalStrings(procName, proc.getProcessName()) && StringUtil.equalStrings(proc.getRuntimeName(), runtimeName))
               return proc;
         }
      }
      return null;
   }

   public void addProcess(Layer fromLayer, IProcessDefinition procDef) {
      initProcessesList(procDef);
      IProcessDefinition existing = null;
      int procIndex = 0;
      LayeredSystem fromSys = fromLayer == null ? null : fromLayer.layeredSystem;
      for (IProcessDefinition existingProc:processes) {
         boolean isDisabled = fromSys != null && existingProc != null && fromSys.isRuntimeDisabled(existingProc.getRuntimeName());
         if (procDef == null && existingProc == null)
            return;
            // If the existing proc is null it's a default runtime and no designated process.  If the new guy has a name and the same runtime process, just use it rather than creating a new one.
            // Just don't let a disabled process define the main system since that ends up with one too many systems (e.g. Runtime: android happens to be the first process
            // created - since it's a Java process, it becomes the system but then is disabled).
         else if (existingProc == null && procDef.getRuntimeProcessor() == null && getProcessDefinition(procDef.getProcessName(), procDef.getRuntimeName()) == null && !isDisabled) {
            processes.set(procIndex, procDef);
            if (fromSys != null && fromSys.processDefinition == null)
               fromSys.updateProcessDefinition(procDef);
            procInfoNeedsSave = true;
            return;
         }
         else if (existingProc != null && procDef != null && procDef.getRuntimeName().equals(existingProc.getRuntimeName()) && StringUtil.equalStrings(procDef.getProcessName(), existingProc.getProcessName()))
            existing = existingProc;
         procIndex++;
      }
      // Replace the old runtime - allows a subsequent layer to redefine the parameters of the JSRuntimeProcessor or even subclass it
      if (existing != null) {
         int ix = processes.indexOf(existing);
         processes.set(ix, procDef);
         if (fromLayer != null && fromLayer.layeredSystem.processDefinition == existing)
            fromLayer.layeredSystem.updateProcessDefinition(procDef);
      }
      else {
         // Default is always the first one in the list if it's there.
         if (procDef == null) {
            processes.add(0, null);
         }
         else
            processes.add(procDef);
      }

      // Each process has a runtimeProcessor so make sure to add that runtime, if it's not here
      IRuntimeProcessor rtProc = procDef == null ? null : procDef.getRuntimeProcessor();
      if (runtimes == null || !runtimes.contains(rtProc))
         addRuntime(fromLayer, rtProc);

      procInfoNeedsSave = true;
   }

   public boolean createDefaultRuntime(Layer fromLayer, String name, boolean needsContextClassLoader) {
      if (runtimes != null) {
         for (IRuntimeProcessor proc:runtimes) {
            if (proc != null) {
               String procName = proc.getRuntimeName();
               if (procName.equals(name))
                  return false;
            }
         }
      }
      addRuntime(fromLayer, new DefaultRuntimeProcessor(name, needsContextClassLoader));
      return true;
   }
}
