/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

/** When application code has a dependency on StrataCode, there are three options */
public enum RuntimeModuleType {
   // No jars included for StrataCode
   None(null, null),
   // sc.jar - TODO: rename this as 'scc' and change the name of the directory from 'system' to 'scc'
   DynamicRuntime("sc", "system"),
   // scrt.jar
   FullRuntime("fullRuntime", "fullRuntime"),
   // scrt-core.jar
   CoreRuntime("coreRuntime", "coreRuntime");

   /** Used to identify the stratacode class files when building an app that needs to include them */
   String ideModuleName;
   String ideDirectoryName;

   RuntimeModuleType(String ideModuleName, String ideDirectoryName) {
      this.ideModuleName = ideModuleName;
      this.ideDirectoryName = ideDirectoryName;
   }

   public static boolean isIdeModuleName(String v) {
      for (RuntimeModuleType rmt:values())
         if (rmt.ideModuleName != null && rmt.ideModuleName.equals(v))
            return true;
      return false;
   }

   public static String[] getIdeModuleNames() {
      return new String[] {CoreRuntime.ideModuleName, FullRuntime.ideModuleName, DynamicRuntime.ideModuleName};
   }

}
