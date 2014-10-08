/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

/**
 * Represents the configuration for a process which is produced by the system.
 */
public interface IProcessDefinition {
   public final static String DEFAULT_PROCESS_NAME = null;

   public String getProcessName();

   public String getRuntimeName();

   public IRuntimeProcessor getRuntimeProcessor();
}
