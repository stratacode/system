import sc.obj.BuildInit;
import sc.layer.LayerUtil;

/** 
  * This file is used to inject values from build-time expressions into the generated code. 
  * We can take the binaries with specific info derived from when it was built. 
  */
object SccBuildTag extends sc.util.BuildTag {
   override @BuildInit("new java.util.Date().toString()") timeStamp;
   override @BuildInit("System.getProperty(\"user.name\")") user;
   override @BuildInit("System.getProperty(\"java.version\")") javaVersion;
   // Want one string for the osVersion.  TODO: maybe call this osVersionStamp?
   override @BuildInit("System.getProperty(\"os.name\") + '.' + System.getProperty(\"os.arch\") + '.' + System.getProperty(\"os.version\")") osVersion;
   // Returns the top-most 'build.properties' definition of a property or null if there is none
   override @BuildInit("getLayerProperty(\"build\",\"version\")") version;
   override @BuildInit("getLayerProperty(\"build\",\"tag\")") tag;
   override @BuildInit("getLayerProperty(\"build\",\"revision\")") revision;
   override @BuildInit("sc.layer.LayerUtil.incrBuildNumber(\"scc\")") buildNumber;
   // Evaluates a layer variable scmVersion
   override @BuildInit("scmVersion") scmVersion;
}
