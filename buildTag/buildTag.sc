package sc.buildTag;

/** 
 * This is the layer definition file for the buildTag layer. It generates the SccBuildTag class with the version information and a 'scc.version' file placed in the buildDir. 
 * <p/>
 * More about layer definition files in general: The code here is interpreted when the layered system is initialized - at the beginning of a build, or when initializing the
 * dynamic runtime. 
*/
public buildTag {
   exportPackage = false;

   // Need this for build.properties
   object configFileProcessor extends LayerFileProcessor {
      prependLayerPackage = true;
      useSrcDir = true;
      extensions = {"properties"};
   }

   String buildTagProduct = "scc";
   String scmVersion;
   String buildVersion;
   String buildTagName;
   String buildRevision;
   String buildNumber;
   String buildTime;

   // In validate because we need the Layer.start method to run to look up layer properties
   void validate() {
      if (buildTagProduct == null) {
         System.err.println("*** Must set buildTagProduct to the name of the product when extending the buildTag layer");
      }
      String branch = FileUtil.execCmd("git symbolic-ref --short HEAD"); // Current branch name only
      if (branch != null) {
         branch = branch.trim();
         String hash = FileUtil.execCmd("git rev-parse --short HEAD").trim();

         // Look at 'git status' and see if there are local changes in this build - if so, summarize them like '3M,2C,1D'
         String status = FileUtil.execCmd("git status -suno --porcelain").trim();
         String repoStatus = ""; 
         if (status.length() > 0) {
            String[] statusLines = status.split("\n");
            int numMods = 0, numAdds = 0, numDels = 0, numOther = 0;
            for (String line:statusLines) {
               char start = line.trim().charAt(0);
               switch (start) {
                  case 'M':
                     numMods++;
                     break;
                  case 'A':
                     numAdds++;
                     break;
                  case 'D':
                     numDels++;
                     break;
                  default:
                     numOther++;
                     break;
               }
            }
            if (numMods != 0) 
               repoStatus += numMods + "M";
            if (numAdds != 0)
               repoStatus += repoStatus.length() == 0 ? "" : "," + numAdds + "A";
            if (numDels != 0)
               repoStatus += repoStatus.length() == 0 ? "" : "," + numDels + "D";
            if (numOther != 0)
               repoStatus += repoStatus.length() == 0 ? "" : "," + numOther + "?";
            repoStatus = "+" + repoStatus;
         }
         scmVersion = branch + '@' + hash + repoStatus; 
      }
      else {
         scmVersion = "not built from git dir";
      }
      buildVersion = getLayerProperty("build","version"); 
      if (buildVersion == null)
         buildVersion = "<build.version>";
      buildTagName = getLayerProperty("build","tag");
      buildRevision = getLayerProperty("build","revision");
      buildNumber = LayerUtil.incrBuildNumber(buildTagProduct);
      buildTime = new java.util.Date().toString();
   }

   void process() {
      FileUtil.saveStringAsReadOnlyFile(FileUtil.concat(layeredSystem.buildDir, buildTagProduct + ".version"), sc.util.BuildTag.getBuildTagString(buildVersion, buildTagName, buildRevision, buildNumber, buildTime, scmVersion) + "\n", false);
   }
}
