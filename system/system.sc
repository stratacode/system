system extends fullRuntime, buildTag {
   // StrataCode itself can only be compiled since the type names needed for the dynamic mode are the same ones used by the system
   compiledOnly = true;

   // Source is stored in the src sub-directory of the layer
   srcPath = "src";

   object jlinePkg extends MvnRepositoryPackage {
      url = "mvn://jline/jline/2.12";
   }

   public void start() {
      sc.layer.LayeredSystem system = getLayeredSystem();

      sc.layer.LayerFileProcessor sctpFileProcessor = new sc.layer.LayerFileProcessor();

      sctpFileProcessor.prependLayerPackage = true;
      sctpFileProcessor.useSrcDir = false;
      // Store them into WEB-INF/classes as configured as the buildClassesSubDir.
      sctpFileProcessor.useClassesDir = true;
      // Do not copy sctp files in dynamic layers - instead, those directories are put into
      // the resource path (with package prefix prepended)
      sctpFileProcessor.compiledLayersOnly = true;

      // Need sc.jar files to have all of the files
      system.options.useCommonBuildDir = true;

      system.registerFileProcessor("sctp", sctpFileProcessor, this);
   }
}
