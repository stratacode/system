package sc.layer;

/** When application code has a dependency on StrataCode, there are three options */
public enum RuntimeModuleType {
   // No jars included for StrataCode
   None,
   // sc.jar
   DynamicRuntime,
   // scrt.jar
   FullRuntime,
   // scrt-core.jar
   CoreRuntime
}
