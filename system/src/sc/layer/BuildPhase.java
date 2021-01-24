/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.parser.Language;
import sc.util.FileUtil;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** The build iterates over all of the source files once for each phase.  The Prepare phase is optional but is
  used to generate any .java source files that are needed to complete the type system (e.g. for Android's resources xml file which
 turns into a generated Java file, then is used by application code as Java apis).
 *
 *  The process phase is the main phase used for converting registered languages to Java code.  File processors can register to handle
 *  files at either phase.
 *
 *  You can also register build command types to run before or after either phase, or the run type which runs after the system has been compiled.
 */
public enum BuildPhase {
   Prepare() {
      public String getDependenciesFile() {
         return "prepare.dep";
      }
   },
   Process() {
      public String getDependenciesFile() {
         return "process.dep";
      }
   };

   public abstract String getDependenciesFile();

   public boolean needsGeneration(String fileName, Layer fromLayer) {
      return getFileProcessorForType(fileName, fromLayer) != null;
   }

   // Hook point for registering new file extensions into the build process.  Implement the process method
   // return IModelObj objects to register new types.
   public HashMap<String,IFileProcessor[]> fileProcessors = new HashMap<String,IFileProcessor[]>();
   public LinkedHashMap<Pattern,IFileProcessor> filePatterns = new LinkedHashMap<Pattern,IFileProcessor>();

   public void registerFileProcessorForPattern(String pattern, IFileProcessor processor) {
      filePatterns.put(Pattern.compile(pattern), processor);
   }

   public void registerFileProcessor(String ext, IFileProcessor processor, Layer fromLayer) {
      IFileProcessor[] procs = fileProcessors.get(ext);
      if (procs == null || fromLayer == null) {
         procs = new IFileProcessor[1];
         procs[0] = processor;
         fileProcessors.put(ext, procs);
      }
      else {
         IFileProcessor[] newProcs = new IFileProcessor[procs.length+1];
         int k = 0;
         for (int i = 0; i < newProcs.length; i++) {
            if (i >= procs.length || fromLayer.layerPosition > procs[k].getLayerPosition())
               newProcs[i] = processor;
            else
               newProcs[i] = procs[k];
         }
      }
   }

   public IFileProcessor getFileProcessorForExtension(String ext, Layer srcLayer) {
      IFileProcessor[] procs = fileProcessors.get(ext);
      if (procs != null) {
         for (IFileProcessor proc:procs) {
            switch (proc.enabledFor(srcLayer)) {
               case Enabled:
                  return proc;
               case Disabled:
                  return null;
               case NotEnabled: // Go to the next one
            }
         }
      }
      return Language.getLanguageByExtension(ext);
   }

   public IFileProcessor getFileProcessorForType(String type, Layer fromLayer) {
      String fileName = type.replace('.', '/');
      if (filePatterns.size() > 0) {
         for (Map.Entry<Pattern,IFileProcessor> ent:filePatterns.entrySet()) {
            if (ent.getKey().matcher(fileName).matches()) {
               IFileProcessor proc = ent.getValue();
               switch (proc.enabledFor(fromLayer)) {
                  case Enabled:
                     return proc;
                  case Disabled:
                     return null;
                  case NotEnabled: // Go to the next one
                     break;
               }
            }
         }
      }
      String ext = FileUtil.getExtension(type);
      return getFileProcessorForExtension(ext, fromLayer);
   }

}
