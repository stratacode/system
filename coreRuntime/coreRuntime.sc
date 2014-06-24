coreRuntime {
   defaultSyncMode = sc.obj.SyncMode.Disabled;

   codeType = sc.layer.CodeType.Framework;
   codeFunction = sc.layer.CodeFunction.Program;

   public void start() {
      sc.layer.LayeredSystem system = getLayeredSystem();
      sc.layer.LayerFileProcessor configFileProcessor = new sc.layer.LayerFileProcessor();

      // Only layers after this one will see this extension
      configFileProcessor.definedInLayer = this;    
      configFileProcessor.prependLayerPackage = true;

      // Copy these extensions to the output file.  Needed for gwt.xml files, using src because that's where the gwt compiler looks for it.
      configFileProcessor.useSrcDir = true;
      system.registerFileProcessor("xml", configFileProcessor, this);
      system.registerFileProcessor("properties", configFileProcessor, this);

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
