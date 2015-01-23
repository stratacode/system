coreRuntime {
   codeType = sc.layer.CodeType.Framework;
   codeFunction = sc.layer.CodeFunction.Program;

   public void start() {
      sc.layer.LayeredSystem system = getLayeredSystem();

      // For the XML and Properties files in the build-dir that need to go along with the class files
      sc.layer.LayerFileProcessor configFileProcessor = new sc.layer.LayerFileProcessor();
      // Only layers after this one will see this extension
      configFileProcessor.definedInLayer = this;    
      configFileProcessor.prependLayerPackage = true; // Prepend the layer's package onto the result file
      configFileProcessor.useSrcDir = true;  // Put it
      system.registerFileProcessor("xml", configFileProcessor, this);
      system.registerFileProcessor("properties", configFileProcessor, this);

      // For the README files we want to put into the buildDir
      sc.layer.LayerFileProcessor buildFileProcessor = new sc.layer.LayerFileProcessor();
      buildFileProcessor.definedInLayer = this;    
      buildFileProcessor.prependLayerPackage = false; // Just copy it across with the same name
      buildFileProcessor.useSrcDir = false;  // Do not put in in the 'java' sub-directory - put in relative to the root dir.
      system.registerFileProcessor("txt", buildFileProcessor, this);
      system.registerFileProcessor("md", buildFileProcessor, this);

      sc.layer.LayerFileProcessor sctpFileProcessor = new sc.layer.LayerFileProcessor();
      sctpFileProcessor.prependLayerPackage = true;
      sctpFileProcessor.useSrcDir = false;
      // Store them into WEB-INF/classes as configured as the buildClassesSubDir.
      sctpFileProcessor.useClassesDir = true;
      // Do not copy sctp files in dynamic layers - instead, those directories are put into
      // the resource path (with package prefix prepended)
      sctpFileProcessor.compiledLayersOnly = true;

      system.registerFileProcessor("sctp", sctpFileProcessor, this);
   }
}
