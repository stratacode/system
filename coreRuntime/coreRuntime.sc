coreRuntime {
   codeType = CodeType.Framework;

   srcPath = "src";

   // TODO: building sc currently does not depend on the sc layers so
   // we are duplicating a few utility file types here.  These probably belong
   // in some core utilities layer that coreRuntime extends.
   
   // For copying README.md to the build
   object buildFileProcessor extends LayerFileProcessor {
      prependLayerPackage = false;
      useSrcDir = false;
      extensions = {"txt", "md"};
   }

   object configFileProcessor extends LayerFileProcessor {
      prependLayerPackage = true;
      useSrcDir = true;
      extensions = {"xml", "properties"};
   }

   object sctFileProcessor extends LayerFileProcessor {
      prependLayerPackage = true;
      useSrcDir = false;
      useClassesDir = true;
      compiledLayersOnly = true;
      extensions = {"sctp", "sctd"};
   }
}
