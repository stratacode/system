/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.bind.Bind;
import sc.db.DBUtil;
import sc.lang.AbstractInterpreter;
import sc.lang.JavaLanguage;
import sc.lang.html.Element;
import sc.lang.html.Window;
import sc.lang.js.JSRuntimeProcessor;
import sc.lang.pattern.Pattern;
import sc.lang.sql.SchemaManager;
import sc.obj.Constant;
import sc.obj.ScopeDefinition;
import sc.sync.SyncManager;
import sc.type.PTypeUtil;
import sc.type.RTypeUtil;
import sc.util.FileUtil;
import sc.util.PerfMon;
import sc.util.StringUtil;

import java.io.File;
import java.util.*;

@sc.js.JSSettings(jsModuleFile="js/sclayer.js", prefixAlias="sc_")
public class Options {
   /** Re-generate all source files when true.  The default is to use dependencies to only generate changed files. */
   @Constant
   public boolean buildAllFiles;
   // TODO: for this option I think we need to restart all models and possibly clear the old transformed model?  Also, possibly some code to switch to this mode based on the types of changes when we are refreshing the system
   /** When doing an incremental build, turn this option on so that all files are regenerated.  Unlike buildAllFiles, this option only restarts changed models so should be faster than buildAllFiles but less buggy than when we only transform the files we can detect need to be re-transformed. */
   @Constant public boolean generateAllFiles = false;
   /** When true, do not inherit files from previous layers.  The buildDir will have all java files, even from layers that are already compiled */
   @Constant public boolean buildAllLayers;
   @Constant public boolean noCompile;
   /** Controls debug level verbose messages */
   @Constant public boolean verbose = false;
   /** Print the basic layer stack info at startup.  Very useful for seeing which runtimes and layers are created for your app. */
   @Constant public boolean verboseLayers = false;
   /** Print the diffs of the source for the same type in different build layers.  Interesting to see how types are modified - especially framework types */
   @Constant public boolean verboseLayerTypes = false;
   /** Diagnose issues finding classes (e.g. to trace adding entries to the package index) */
   @Constant public boolean verboseClasses = false;
   /** Show detail on locking issues, save stack trace of locking thread */
   @Constant public boolean verboseLocks = false;
   /* Shows every lock/release call */
   @Constant public boolean superVerboseLocks = false;
   /** Turns on debug messages specific to the @Exec annotation - see which types are excluded from a given runtime */
   @Constant public boolean verboseExec = false;
   @Constant public boolean verboseModelCache = false;
   /** Set to true when collecting the logs as a 'verification file' - a signal to not output dates, or other info that will vary from run to run */
   @Constant public boolean testVerifyMode = false;
   /** Set to true when debugging the program - used to disable timeouts  */
   @Constant public boolean testDebugMode = false;
   @Constant public boolean info = true;
   /** Controls whether java files compiled by this system debuggable */
   @Constant public boolean debug = true;
   /** Disable the default database for testing - store objects in memory only */
   @Constant public boolean dbDisabled = false;
   /** Should compiled classes be parsed using CFClass instead of the default which is to use the class loader */
   @Constant public boolean crossCompile = false;
   /** Change to the buildDir before running the command */
   @Constant public boolean runFromBuildDir = false;
   @Constant public boolean runScript = false;
   @Constant public boolean createNewLayer = false;
   @Constant public boolean dynamicLayers = false;
   @Constant public boolean anyDynamicLayers = false;
   /** Set to true for the -dyn option, which applies the dynamic state to all extended layers that are not marked explicitly as compiledOnly */
   @Constant public boolean recursiveDynLayers = false;
   /** -dynall: like -dyn but all layers included by the specified layers are also made dynamic */
   @Constant public boolean allDynamic = false;
   /** When true, we maintain the reverse mapping from type to object so that when certain type changes are made, we can propagate those changes to all instances.  This is set to true by default when the editor is enabled.  Turn it on with -dt  */
   @Constant public boolean liveDynamicTypes = true;
   /** When true, we compile in support for the ability to enable liveDynamicTypes */
   @Constant public boolean compileLiveDynamicTypes = true;
   /** When you have multiple build layers, causes each subsequent layer to get all source/class files from the previous. */
   @Constant public boolean useCommonBuildDir = false;
   @Constant public String buildDir;
   @Constant public String buildLayerAbsDir;
   @Constant public String buildSrcDir;
   /** By default run all main methods defined with no arguments */
   @Constant public String runClass = ".*";
   @Constant public String[] runClassArgs = null;
   @Constant public boolean noDynRuntime = false;
   /** File used to record script by default */
   @Constant public String recordFile;
   @Constant public String restartArgsFile;
   /** Enabled with the -c option - only compile, do not run either main methods or runCommands. */
   @Constant public boolean compileOnly = false;

   /** An IDE or other tool that never runs code should set this to false.  In these cases, we do not want to include the buildDir in the classPath - we'll never run the application code or try to evaluate a template or anything in a build layer */
   @Constant public boolean includeBuildDirInClassPath = true;

   /** Do a rebuild automatically when a page is refreshed.  Good for development since each page refresh will recompile and update the server and javascript code.  If a restart is required you are notified.  Bad for production because it's very expensive. */
   @Constant public boolean autoRefresh = true;

   @Constant public boolean retryAfterFailedBuild = false;

   /** When set, only matching tests are run  */
   @Constant public String testPattern = null;

   /** Set to true if the system should exit after running the tests */
   @Constant public boolean testExit = false;

   /** General flag for when the system is running tests - used to make log messages more consistent or to enable additional diagnostic  */
   @Constant public boolean testMode = false;

   /** Set to true for the -tv, -tw, -t, and -ta options to run the configured test suite. Use -tm to just enable testMode without running a script  */
   @Constant public boolean runTests = false;

   /** General flag for when the system is being run by a developer as opposed to production */
   @Constant public boolean devMode = true;

   /** Defaults the command interpreter to use cmd.edit = false - i.e. to just set properties rather than build or edit the layer */
   @Constant public boolean scriptMode = false;

   /** Argument to control what happens after the command is run, e.g. it can specify the URL of the page to open. */
   @Constant public String openPattern = null;

   /** Controls whether or not the start URL is opened */
   @Constant public boolean openPageAtStartup = true;

   @Constant /** An internal option for how we implement the transform.  When it's true, we clone the model before doing the transform.  This is the new way and makes for a more robust system.  false is deprecated and probably should be removed */
   public boolean clonedTransform = true;

   @Constant /** An internal option to enable use of the clone operation to re-parse the same file in a separate peer system */
   public boolean clonedParseModel = true;

   @Constant /** Additional system diagnostic information in verbose mode */
   public boolean sysDetails = false;

   @Constant /** Removes the existing modelCache directory before running */
   public boolean cleanModelCache = false;

   // Enable editing of the program editor itself
   @Constant public boolean editEditor = false;

   // As soon as any file in a build-dir is changed, build all files.  A simpler alternative to incremental compile that performs better than build all
   @Constant public boolean buildAllPerLayer = false;

   @Constant public TypeIndexMode typeIndexMode = TypeIndexMode.Lazy;

   @Constant public ArrayList<String> disabledRuntimes;

   @Constant public boolean disableCommandLineErrors = false;

   /** When doing an incremental build, this optimization allows us to load the compiled class for final types. */
   @Constant public boolean useCompiledForFinal = true;

   /** Should we clear up all data structured after running the program (for better heap diagnostics) */
   @Constant public boolean clearOnExit = true;

   /** Should we install default layers if we can't find them in the layer path? */
   @Constant public boolean installLayers = true;

   /** Should we reinstall all packages */
   @Constant public boolean reinstall;

   /** Should we reinstall all packages but reusing existing downloads */
   @Constant public boolean installExisting;

   /** Should we update all external repository packages on this run of the system (instead of using previously downloaded versions) */
   @Constant public boolean updateSystem;

   /** Maximum number of errors to display in one build */
   @Constant public int maxErrors = 100;

   /** After the first successful build, should we continue to use buildAllFiles or set it to false. */
   @Constant public boolean rebuildAllFiles = false;

   /** By default StrataCode will use System.gc() at intervals it thinks are great times to clean up unless this option is disabled */
   @Constant public boolean disableAutoGC = false;

   /** By default StrataCode sets the Thread's contextClassLoader automatically unless this option is set to true. */
   @Constant public boolean disableContextClassLoader = false;

   /** Should we generate the debugging line number mappings for generated source */
   @Constant public boolean genDebugInfo = true;

   /** Treat warnings as errors - to stop builds and exit with error status - use true for most test and even production scenarios */
   @Constant public boolean treatWarningsAsErrors = true;

   /** Test script to run as input to command line interpreter after execution.  */
   @Constant public String testScriptName = null;

   /** When true, run the command interpreter after running the test script (-ti scriptName)  */
   @Constant public boolean includeTestScript = false;

   /** Directory to store test results */
   @Constant public String testResultsDir = null;

   /** Do not display windows during test running */
   @Constant public boolean headless = false;

   @Constant public String scInstallDir = null;
   @Constant public String mainDir = null;
   @Constant public String confPath = null;

   @Constant public String classPath = null;
   @Constant public String layerPath = null;
   @Constant public boolean needsClassLoaderReset;

   // TODO: remove me?
   /** For schtml pages, should they use caching by default for runtimes that support it? */
   @Constant public boolean defaultPageCache = true;

   /**
    * A performance optimization to reduce parsing overhead for files which don't change.  When a src file is changed, it is parsed and the model and parse-stream are serialized.  When that same file
    * needs to be parsed again, first the model is deserialized.   If and when the parseNode is needed, it is restored from a combination of the original file and the parse-stream.
    */
   @Constant public boolean modelCacheEnabled = true;

   /**
    * When running the compiler, do we use the internal compiler api or fork a process to run JavaC. It's gone back and forth which is
    * faster, but right now the external compiler seems to be about 10% faster and uses less of the heap so it's the default.
    */
   @Constant public boolean externalCompile = true;

   /** When modelCacheEnabled=true, if lazyParseNodeCache is true, we do not restore the parse-node tree until it's requested for a given model */
   @Constant public boolean lazyParseNodeCache = true;

   /**
    * For web applications that use scopes shared across different apps (web pages) like 'global' and 'session' do we
    * build a set of type names needed by each web-page, so we don't try to serialize objects which are not referenced in
    * that page (which will general a sync error)
    */
   @Constant public boolean syncTypeFilter = true;

   @Constant public boolean startInterpreter = true;

   /** Set when refreshing the system because usually it's not helpful to refresh changed compiled types - with this true, we will skip them  */
   @Constant public boolean refreshDynamicOnly = false;

   @Constant public SchemaManager.SchemaMode schemaMode = SchemaManager.SchemaMode.Prompt;

   boolean restartArg = false;
   boolean headlessSet = false;
   List<String> includeFiles = null;  // List of files to process
   boolean includingFiles = false;    // The -f option changes the context so we are reading file names
   boolean editLayer = true;
   String commandDirectory = null;
   ArrayList<String> restartArgs = new ArrayList<String>();
   int lastRestartArg = -1;
   ArrayList<String> explicitDynLayers = null;
   List<String> includeLayers = null;
   String buildLayerName = null;
   TreeMap<String,Properties> layerProps = null;

   static final TreeMap<String,String> optionAliases = new TreeMap<String,String>();

   {
      optionAliases.put("-help", "-h");
      optionAliases.put("--help", "-h");
      optionAliases.put("--version", "-version");
   }

   static void usage(String reason, String[] args) {
      if (reason.length() > 0)
         System.err.println(reason);
      usage(args);
   }

   static void printVersion() {
      LayerUtil.printVersion("scc", "sc.buildTag.SccBuildTag");
   }

   private static void usage(String[] args) {
      printVersion();
      StringUtil.insertLinebreaks(AbstractInterpreter.INTRO, 80);
      System.err.println("Command line overview:\n" + "scc [-a -i -ni -nc -dyn -cp <classpath> -lp <layerPath>]\n" +
                         "   [ -cd <defaultCommandDir/Path> ] [<layer1> ... <layerN-1>] <buildLayer>\n" +
                         "   [ -f <file-list> ] [-r <main-class-regex> ...app options...] [-t <test-class-regex>]\n" +
                         "   [ -d/-ds/-db buildOrSrcDir]\n\nOption details:");
      System.err.println("   [ -a ]: build all files\n   [ -i ]: create temporary layer for interpreter\n   [ -nc ]: generate but don't compile java files");
      System.err.println("   <buildLayer>:  The build layer is the last layer in your stack.\n" +
                         "   [ -dyn ]: Layers specified after -dyn (and those they pull in using the 'extends' keyword in the layer definition file) are made dynamic unless they are marked: 'compiledOnly'\n" +
                         "   [ -c ]: Generate and compile code only - do not run any main methods\n" +
                         "   [ -v* ]: Enable verbose flags to debug features\n" +
                         "       [ -v ]: verbose info of main system events.  [-vb ] [-vba] Trace data binding (or trace all) [-vs] [-vsa] [-vsv] [-vsp] Trace options for the sync system: trace, traceAll, verbose-inst, verbose-inst+props \n" +
                         "       [ -vh ]: verbose html [ -vha ]: trace html [ -vl ]: display initial layers [ -vp ] turn on performance monitoring [ -vc ]: info on loading of class files\n" +
                         "       [ -vlck ]: verbose lock [ -ve ]: verbose exec to see 'excluded classes'\n" +
                         "   [ -f <file-list>]: Process/compile only these files\n" +
                         "   [ -cp <classPath>]: Use this classpath for resolving compiled references.\n" +
                         "   [ -lp <layerPath>]: Set of directories to search in order for layer directories.\n" +
                         "   [ -db,-ds,-d <buildOrSrcDir> ]: override the buildDir, buildSrcDir, or both of the buildLayer's buildDir and buildSrcDir properties \n" +
                         "   [ -r/-rs <main-class-pattern> ...all remaining args...]: After compilation, run all main classes matching the java regex with the rest of the arguments.  -r: run in the same process.  -rs: exec the generated script in a separate process.\n" +
                         "   [ -ra ... remaining args passed to main methods... ]: Run all main methods\n" +
                         "   [ -tv ]: Enable 'test verify' mode.  Run all tests, or those specified with -t, -ts, -ti or -ta.  Don't show windows for frameworks that support headless operation. Omit timestamps or other info in log files that changes from run to run for easier comparison\n" +
                         "   [ -tw ]: Enable 'test with window' mode.  Like -tv but shows the window.\n" +
                         "   [ -t <test-class-pattern>]: Run only the matching tests.\n" +
                         "   [ -ts/-ti <scriptName.scr>]: After compilation, run (-ts) or include (-ti) the specified test-script.  Use -ts to exit when the script is done.  -ti to enter the interpreter when the test is completed\n" +
                         "   [ -tm ]: Enable test mode but do not run tests - use for creating new test scripts\n" +
                         "   [ -o <pattern> ]: Sets the openPattern, used by frameworks to choose which page to open after startup.\n" +
                         "   [ -ta ]: Like -tv but runs all tests without 'test verify mode'.\n" +
                         "   [ -nw ]: For web frameworks, do not open the default browser window.\n" +
                         "   [ -P<propFileName.propName=value> ]: Override layer property, bound at top-level of layer directory in propFileName with propName=value\n" +
                         "   [ -n ]: Start 'create layer' wizard on startup.\n" +
                         "   [ -ni ]: Disable command interpreter\n" +
                         "   [ -ndb ]: Disable the database for reads and writes - only store them in memory.\n" +
                         "   [ -ndbg ]: Do not compile Java files with debug enabled\n" +
                         "   [ -dt ]: Enable the liveDynamicTypes option - so that you can modify types at runtime.  This is turned when the editor is enabled by default but you can turn it on with this option.\n" +
                         "   [ -nd ]: Disable the liveDynamicTypes option - so that you cannot modify types at runtime.  This is turned when the editor is enabled by default but you can turn it on with this option.\n" +
                         "   [ -ee ]: Edit the editor itself - when including the program editor, do not exclude it's source from editing.\n" +
                         "   [ -cd <ApplicationTypeName>]: Start the command-interpreter in the context of the given ApplicationTypeName.\n" +
                         "   [ -schema:update, -schema:accept ]: update the schema database, or accept the current schema without updating. Default is to run the SchemaUpdateWizard to apply the changes interactively\n" +
                         "   [ -version, -h or -help - print version/usage info.\n\n" +
                         StringUtil.insertLinebreaks(AbstractInterpreter.USAGE, 80));
      System.exit(-1);
   }

   public void parseOptions(String[] args) {
      for (int i = 0; i < args.length; i++) {
         restartArg = true;
         String arg = args[i];
         if (arg.length() == 0)
            Options.usage("Invalid empty option", args);
         if (arg.charAt(0) == '-') {
            if (arg.length() == 1)
               Options.usage("Invalid option: " + args[i], args);

            String alias = Options.optionAliases.get(arg);
            if (alias != null) {
               arg = alias;
            }

            String opt = arg.substring(1);
            switch (opt.charAt(0)) {
               case 'a':
                  if (opt.equals("a")) {
                     // The thinking by setting both here is that if you are building all files, there's no point in trying to inherit from other layers.  It won't be any fasteruu
                     buildAllFiles = true;
                     restartArg = false;
                     break;
                  }
                  else if (opt.equals("al")) {
                     buildAllPerLayer = true;
                     break;
                  }
                  else
                     Options.usage("Unrecognized option: " + opt, args);
               case 'A':
                  if (opt.equals("A")) {
                     buildAllLayers = true;
                     restartArg = false;
                     break;
                  }
                  else
                     Options.usage("Unrecognized option: " + opt, args);
               case 'd':
                  if (opt.equals("d") || opt.equals("ds") || opt.equals("db") || opt.equals("da")) {
                     if (i == args.length - 1)
                        System.err.println("*** Missing buildDir argument to -d option");
                     else {
                        String buildArg = args[++i];
                        if (opt.equals("d") || opt.equals("db"))
                           buildDir = buildArg;
                        if (opt.equals("ds"))
                           buildSrcDir = buildArg;
                        if (opt.equals("da"))
                           buildLayerAbsDir = buildArg;
                     }
                  }
                  else if (opt.equals("dynone")) {
                     anyDynamicLayers = true;
                     explicitDynLayers = new ArrayList<String>();
                  }
                  else if (opt.equals("dynall")) {
                     dynamicLayers = true;
                     allDynamic = true;
                     anyDynamicLayers = true;
                  }
                  else if (opt.equals("dyn")) {
                     explicitDynLayers = new ArrayList<String>();
                     anyDynamicLayers = true;
                     recursiveDynLayers = true;
                  }
                  else if (opt.equals("dt"))
                     liveDynamicTypes = true;
                  else if (opt.equals("dbg")) // Right now, this option is passed to the 'start' script (e.g. scc) to enable the java debugger options
                     ;
                  else if (opt.equals("dmc"))
                     modelCacheEnabled = false;
                  else
                     Options.usage("Unrecognized option: " + opt, args);
                  break;
               case 'e':
                  if (opt.equals("ee"))
                     editEditor = true;
                  else if (opt.equals("emc"))
                     modelCacheEnabled = true;
                  else if (opt.equals("exc"))
                     externalCompile = true;
                  else
                     Options.usage("Unrecognized option: " + opt, args);
                  break;
               case 'i':
                  if (opt.equals("ie"))
                     installExisting = true;
                  if (opt.equals("ic"))
                     externalCompile = false;
                  else if (opt.equals("id")) {
                     if (args.length < i + 1)
                        Options.usage("Missing arg to install directory (-id) option", args);
                     scInstallDir = args[++i];
                  }
                  else {
                     startInterpreter = true;
                     editLayer = false;
                  }
                  break;
               case 'h':
                  Options.usage("", args);
                  break;

               case 's':
                  if (opt.equals("scn"))
                     SyncManager.defaultLanguage = "stratacode";
                  else if (opt.equals("scr"))
                     scriptMode = true;
                  else if (opt.equals("schema:update"))
                     schemaMode = SchemaManager.SchemaMode.Update;
                  else if (opt.equals("schema:accept"))
                     schemaMode = SchemaManager.SchemaMode.Accept;
                  else
                     Options.usage("Unrecognized option: " + opt, args);
                  break;
               case 'm':
                  if (opt.equals("me")) {
                     if (args.length < i + 1)
                        Options.usage("Missing arg to -me (maxErrors) option: ", args);
                     else {
                        try {
                           maxErrors = Integer.parseInt(args[++i]);
                        }
                        catch (NumberFormatException exc) {
                           Options.usage("Invalid integer arg to -me (maxErrors) option: " + exc.toString(), args);
                        }
                     }
                  }
                  else if (opt.equals("md")) {
                     if (args.length < i + 1)
                        Options.usage("Missing arg to main directory (-md) option", args);
                     mainDir = args[++i];
                  }
                  break;
               case 'n':
                  if (opt.equals("nc")) {
                     noCompile = true;
                  }
                  else if (opt.equals("nd"))
                     liveDynamicTypes = false;
                  else if (opt.equals("ndr"))
                     noDynRuntime = true;
                  else if (opt.equals("n")) {
                     createNewLayer = true;
                  }
                  else if (opt.equals("nw")) {
                     openPageAtStartup = false;
                  }
                  else if (opt.equals("nh")) {
                     headless = true;
                     headlessSet = true;
                  }
                  else if (opt.equals("ni"))
                     startInterpreter = false;
                  else if (opt.equals("ndbg")) {
                     debug = false;
                  }
                  else if (opt.equals("ndb")) {
                     dbDisabled = true;
                  }
                  else
                     Options.usage("Unrecognized option: " + opt, args);
                  break;
               case 'o':
                  if (opt.equals("o")) {
                     if (i == args.length - 1)
                        System.err.println("*** missing pattern arg to run -o option");
                     else {
                        openPattern = args[++i];
                     }
                  }
                  else if (opt.startsWith("opt:")) {
                     if (opt.equals("opt:disableFastGenExpressions"))
                        JavaLanguage.fastGenExpressions = false;
                     else if (opt.equals("opt:disableFastGenMethods"))
                        JavaLanguage.fastGenMethods = false;
                     else if (opt.equals("opt:perfMon"))
                        PerfMon.enabled = true;
                     else
                        System.err.println("*** Unrecognized option: " + opt);
                  }
                  break;
               case 'P':
                  processLayerProperty(opt, args);
                  break;
               case 'f':
                  includingFiles = true;
                  break;
               case 'l':
                  if (opt.equals("lp")) {
                     if (i == args.length - 1)
                        layerPath = "";
                     else
                        layerPath = args[++i];
                  }
                  else if (opt.equals("l"))
                     includingFiles = false;
                  else
                     Options.usage("Unrecognized option: " + opt, args);
                  break;
               case 'c':
                  // Compile only
                  if (opt.equals("c")) {
                     runClass = null;
                     startInterpreter = false;
                     compileOnly = true;
                  }
                  else if (opt.equals("cp")) {
                     if (i == args.length - 1)
                        classPath = "";
                     else
                        classPath = args[++i];
                  }
                  else if (opt.equals("cd")) {
                     if (i == args.length - 1)
                        System.err.println("*** missing arg to command directory -cd option");
                     else
                        commandDirectory = args[++i];
                  }
                  else if (opt.equals("cc")) {
                     crossCompile = true;
                  }
                  else if (opt.equals("cmc")) {
                     System.out.println("Cleaning model cache");
                     LayerUtil.cleanModelCache();
                  }
                  else
                     Options.usage("Unrecognized option: " + opt, args);
                  break;
               case 'r':
                  if (opt.equals("rb")) {
                     runFromBuildDir = true;
                     opt = "r";
                  }
                  else if (opt.equals("rs")) {
                     runScript = true;
                     opt = "r";
                  }
                  else if (opt.equals("ra")) {
                     runClass = ".*";
                     runClassArgs = new String[args.length-i-1];
                     int k = 0;
                     for (int j = i+1; j < args.length; j++)
                        runClassArgs[k++] = args[j];
                     i = args.length;
                  }
                  else if (opt.equals("rec")) {
                     if (i == args.length - 1)
                        System.err.println("*** missing record file to -rec option");
                     recordFile = args[++i];
                  }
                  else if (opt.equals("restartArgsFile")) {
                     restartArgsFile = args[++i];
                     restartArg = false;
                  }
                  else if (opt.equals("restart")) {
                     // Start over again with the arguments saved from the program just before restarting
                     if (restartArgsFile == null) {
                        Options.usage("-restart requires the use of the restartArgsFile option - typically only used from the generated shell script: ", args);
                     }
                     else {
                        File restartFile = new File(restartArgsFile);
                        args = StringUtil.splitQuoted(FileUtil.readFirstLine(restartFile));
                        restartFile.delete();
                     }
                     restartArg = false;
                     i = -1;
                  }
                  else if (opt.equals("ri"))
                     reinstall = true;
                  else if (opt.equals("r")) {
                     if (i == args.length - 1)
                        System.err.println("*** missing arg to run -r option");
                     else {
                        runClass = args[++i];
                        runClassArgs = new String[args.length-i-1];
                        int k = 0;
                        for (int j = i+1; j < args.length; j++)
                           runClassArgs[k++] = args[j];
                        i = args.length;
                     }
                  }
                  else
                     Options.usage("Unrecognized option: " + opt, args);
                  break;
               case 't':
                  if (opt.equals("ta")) {
                     testPattern = ".*";
                     testMode = true;
                     runTests = true;
                  }
                  else if (opt.equals("te")) {
                     testExit = true;
                     testMode = true;
                  }
                  // Test in verify mode (headless)
                  else if (opt.equals("tv")) {
                     // TODO: shouldn't we set the testPattern for this and tw? It breaks unitDemoScript2 though
                     //testPattern = ".*";
                     testVerifyMode = true;
                     testMode = true;
                     runTests = true;
                  }
                  // Test in verify with window mode
                  else if (opt.equals("tw")) {
                     testVerifyMode = true;
                     testMode = true;
                     headlessSet = true;
                     runTests = true;
                  }
                  else if (opt.equals("tdbg")) {
                     testDebugMode = true;
                  }
                  else if (opt.equals("t")) {
                     testMode = true;
                     headlessSet = true;
                     if (i == args.length - 1)
                        System.err.println("*** missing arg to run -t option");
                     else {
                        testPattern = args[++i];
                     }
                     runTests = true;
                  }
                  else if (opt.equals("tm")) {
                     testMode = true;
                     headlessSet = true;
                  }
                  else if (opt.equals("ts") || opt.equals("ti")) {
                     testMode = true;
                     includeTestScript = opt.equals("ti");
                     if (i == args.length - 1)
                        System.err.println("*** missing arg to run -ts/-ti option");
                     else {
                        testScriptName = args[++i];
                     }
                     runTests = true;
                  }
                  else if (opt.equals("tip")) {
                     testMode = true;
                     headlessSet = true;
                     if (i == args.length - 1)
                        System.err.println("*** missing arg to run -tip");
                     Window.globalTestRemoteIp = args[++i];
                  }
                  else if (opt.equals("tua")) {
                     testMode = true;
                     headlessSet = true;
                     if (i == args.length - 1)
                        System.err.println("*** missing arg to run -tua");
                     Window.globalTestUserAgent = args[++i];
                  }
                  else
                     Options.usage("Unrecognized option: " + opt, args);
                  break;
               case 'q':
                  if (opt.equals("q")) {
                     info = false;
                  }
                  else
                     Options.usage("Unrecognized option: " + opt, args);
                  break;
               case 'u':
                  if (opt.equals("u"))
                     updateSystem = true;
                  else
                     Options.usage("Unrecognized option: " + opt, args);
                  break;
               case 'v':
                  if (opt.equals("vb"))
                     Bind.trace = true;
                  else if (opt.equals("vv")) {
                     sysDetails = true;
                     verbose = true;
                  }
                  else if (opt.equals("vba")) {
                     Bind.trace = true;
                     Bind.traceAll = true;
                  }
                  else if (opt.equals("vl"))
                     verboseLayers = true;
                  else if (opt.equals("vlt")) {
                     if (!SrcIndexEntry.debugSrcIndexEntry)
                        System.out.println("*** -vlt option ignored - must recompile with SrcIndexEntry.debugSrcIndexEntry = true and clear out srcIndexes for this feature");
                     verboseLayerTypes = true;
                  }
                  else if (opt.equals("vh"))
                     Element.verbose = true;
                  else if (opt.equals("vha"))
                     Element.trace = true;
                  else if (opt.equals("vs"))
                     SyncManager.trace = true;
                  else if (opt.equals("vdb"))
                     DBUtil.verbose = true;
                  else if (opt.equals("vc")) {
                     RTypeUtil.verboseClasses = true;
                     verboseClasses = true;
                  }
                  else if (opt.equals("vlck")) {
                     verboseLocks = true;
                     ScopeDefinition.traceLocks = true;
                  }
                  else if (opt.equals("vsa")) {
                     ScopeDefinition.verbose = true;
                     ScopeDefinition.trace = true;
                     SyncManager.verbose = true;
                     SyncManager.trace = true;
                     SyncManager.traceAll = true;
                     // Includes JS that is sent to the browser due to changed source files
                     JSRuntimeProcessor.traceSystemUpdates = true;
                  }
                  else if (opt.equals("vsv")) {
                     // Verbose messages for sync events on the instance-level
                     SyncManager.verbose = true;
                     ScopeDefinition.verbose = true;
                  }
                  else if (opt.equals("vsp")) {
                     // Verbose messages for sync events on the instance and property levels
                     SyncManager.verboseValues = true;
                  }
                  else if (opt.equals("vp"))
                     PerfMon.enabled = true;
                  else if (opt.equals("ve"))
                     verboseExec = true;
                  else if (opt.equals("v"))
                     LayeredSystem.traceNeedsGenerate = verbose = true;
                  else if (opt.equals("version")) {
                     Options.printVersion();
                     System.exit(0);
                  }
                  else
                     Options.usage("Unrecognized option: " + opt, args);
                  break;
               default:
                  Options.usage("Unrecognized option: " + opt, args);
                  break;
            }
         }
         else {
            if (!includingFiles) {
               if (includeLayers == null)
                  includeLayers = new ArrayList<String>(args.length-1);

               includeLayers.add(args[i]);

               if (explicitDynLayers != null)
                  explicitDynLayers.add(args[i]);
            }
            else {
               if (includeFiles == null)
                  includeFiles = new ArrayList<String>();
               includeFiles.add(args[i]);
            }
         }
         if (restartArg) {
            int max;
            if (i == args.length)
               max = i - 1;
            else
               max = i;
            for (int j = lastRestartArg+1; j <= max; j++) {
               restartArgs.add(args[j]);
            }
         }
         lastRestartArg = i;
      }

      if (testResultsDir == null) {
         testResultsDir = System.getenv("TEST_DIR");
         if (testResultsDir == null)
            testResultsDir = "/tmp";
      }

      // TODO: do we want to make this configurable?   The class loader reset is required in frameworks like with servlets where the layered classpath doesn't work - i.e. a downstream
      // class needs to refer to an upstream one ala a JDBC connection pool.  Currently enabled whenever we are running something after the compile.
      needsClassLoaderReset = runClass != null || testPattern != null;

      // When testing we don't want the normal run - open page to open - it's up to the test script to decide what to open to test
      if (testMode) {
         PTypeUtil.testMode = true;
         Pattern.testMode = true;
         openPageAtStartup = false;
         // By default test modes should not display unless you use -tw
         if (!headlessSet)
            headless = true;
      }
      if (testVerifyMode)
         PTypeUtil.testVerifyMode = true;
      if (verbose)
         PTypeUtil.verbose = true;

      // Handle normalized layer names to make scripts portable
      if (FileUtil.FILE_SEPARATOR_CHAR != '/' && includeLayers != null) {
         for (int i = 0; i < includeLayers.size(); i++) {
            includeLayers.set(i, FileUtil.unnormalize(includeLayers.get(i)));
         }
      }

      // Build layer is always the last layer in the list
      if (includeLayers != null) {
         buildLayerName = includeLayers.get(includeLayers.size()-1);
      }

      if (info) {
         StringBuilder sb = new StringBuilder();
         sb.append("Running: scc ");
         for (String varg:args) {
            if (testVerifyMode && varg.matches("/tmp/restart\\d+.tmp"))
               sb.append("/tmp/restart<pid>.tmp");
            else
               sb.append(varg);
            sb.append(" ");
         }
         System.out.println(sb);
      }
   }

   private final static String invalidPOpt = "Invalid layer property (-P) option.  Expecting: -P<propFileName>.<propName>=value";
   private void processLayerProperty(String opt, String[] args) {
      int eqIx = opt.indexOf('=');
      if (eqIx == -1) {
         usage(invalidPOpt, args);
      }
      String nameStr = opt.substring(1,eqIx);
      int dotIx = nameStr.indexOf('.');
      if (dotIx == -1)
         usage(invalidPOpt, args);
      String fileName = nameStr.substring(0, dotIx);
      String propName = nameStr.substring(dotIx+1);
      if (propName.length() == 0)
         usage(invalidPOpt, args);
      String propVal = opt.substring(eqIx+1);
      if (layerProps == null)
         layerProps = new TreeMap<String,Properties>();
      Properties p = layerProps.get(fileName);
      if (p == null) {
         p = new Properties();
         layerProps.put(fileName, p);
      }
      p.setProperty(propName, propVal);
   }
}
