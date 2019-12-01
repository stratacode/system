system extends fullRuntime, buildTag {
   buildTagProduct = "scc";
   // StrataCode itself can only be compiled since the type names needed for the dynamic mode are the same ones used by the system
   compiledOnly = true;

   // Source is stored in the src sub-directory of the layer
   srcPath = "src";

   object jlinePkg extends MvnRepositoryPackage {
      url = "mvn://jline/jline/2.12";
   }

   public void start() {
      sc.layer.LayeredSystem system = getLayeredSystem();

      // Need sc.jar files to have all of the files
      system.options.useCommonBuildDir = true;
   }
}
