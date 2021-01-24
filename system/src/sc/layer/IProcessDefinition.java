/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import java.util.List;

/**
 * Represents the configuration for a process which is produced by the system.
 */
public interface IProcessDefinition {
   public final static String DEFAULT_PROCESS_NAME = null;

   public String getProcessName();

   public String getRuntimeName();

   public IRuntimeProcessor getRuntimeProcessor();

   /** If this returns true, a separate sync destination is created for this process.  If it returns false, the default runtime's sync destination is used. */
   boolean getPerProcessSync();

   public List<String> getSyncProcessNames();

   /** Should this process use the Thread.currentThread().setContextClassLoader() mechanism (as used by java servlets and components) */
   boolean getUseContextClassLoader();
}
