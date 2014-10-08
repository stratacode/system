/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer.deps;

import sc.layer.IProcessDefinition;
import sc.layer.IRuntimeProcessor;
import sc.layer.LayeredSystem;

/**
 * An instance of this class is used to represent each process we need to build.  If the processName is null
 * we are using the default process - i.e. no layers have defined any process constraints.
 */
public class DefaultProcessDefinition implements IProcessDefinition {
   String processName;
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

   public void setRuntimeProcessor(IRuntimeProcessor proc) {
      runtimeProcessor = proc;
   }

   public String getRuntimeName() {
      return runtimeProcessor == null ? IRuntimeProcessor.DEFAULT_RUNTIME_NAME : runtimeProcessor.getRuntimeName();
   }
}
