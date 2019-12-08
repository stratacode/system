package sc.layer;

/** When application code has a dependency on StrataCode, there are three options */
public enum RuntimeModuleType {
   // sc.jar
   DynamicRuntime,
   // scrt.jar
   FullRuntime,
   // scrt-core.jar
   CoreRuntime
}
