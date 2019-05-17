/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.bind.Bind;
import sc.bind.DestinationListener;
import sc.dyn.DynUtil;
import sc.dyn.IDynObject;
import sc.dyn.IScheduler;
import sc.lang.sc.PropertyAssignment;
import sc.lang.sc.EndTypeDeclaration;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.java.Package;
import sc.lang.java.*;
import sc.layer.*;
import sc.obj.CurrentScopeContext;
import sc.obj.ScopeContext;
import sc.parser.*;
import sc.type.CTypeUtil;
import sc.type.TypeUtil;
import sc.util.DialogManager;
import sc.util.FileUtil;
import sc.util.StringUtil;

import java.io.*;
import java.util.*;
import sc.dyn.ScheduledJob;

// We want these commands to be run for the Java_Server runtime when there's a server and the js runtime when it
// is the only runtime.
@sc.obj.Exec(runtimes="default")
public abstract class AbstractInterpreter extends EditorContext implements IScheduler, Runnable, IDynObject {
   static SCLanguage vlang = SCLanguage.INSTANCE;

   public class InputSource {
      String inputFileName;
      String inputRelName;
      InputStream inputStream;
      Reader inputReader;
      int currentLine;
      Layer includeLayer;
      Object consoleObj;
      boolean pushLayer;
      boolean returnOnInputChange;

      public String toString() {
         return "file: " + inputFileName + "[" + currentLine + "]" + " layer:" + (includeLayer == null ? system.buildLayer : includeLayer);
      }
   }

   // The current inputFileName, inputStream etc are not in the pendingInputSources list.
   ArrayList<InputSource> pendingInputSources = new ArrayList<InputSource>();

   String inputFileName = null;

   // If inputFileName is a relative file in the layer tree, the relative path-name (used for includeSuper)
   String inputRelName = null;

   // Either null (which means effectively the buildLayer) or the layer of the current include file when we're running a script from
   // a layer file - e.g. testInit.scr
   Layer includeLayer = null;

   StringBuilder pendingInput = new StringBuilder();

   boolean returnOnInputChange = false;

   // The script starts out in the context of a global 'cmd' object which is a dynamic type so you can add fields, etc.
   // in a dynamic context even if you are running a fully compiled application.  Inner types however are not added to this
   // type - in that case, you are defining real types in the application.
   DynObject dynObj;

   // Are we in the midst of a slash-star style comment - i.e. ignoring all lines
   boolean inBlockComment = false;

   public boolean exitOnError = true;
   public boolean noPrompt = false;

   /** A stateless class we use for resolving scripts at edit time - i.e. in the IDE */
   public static class DefaultCmdClassDeclaration extends ClassDeclaration {
      public boolean isDynamicStub(boolean includeExt) {
         return false;
      }

      public Class getCompiledClass() {
         return AbstractInterpreter.class.getClass();
      }
   }

   /** This one is like the above but gets modified during the the running of the script in the interpreter instance */
   public class CmdClassDeclaration extends ClassDeclaration {
      public boolean isDynamicStub(boolean includeExt) {
         return false;
      }

      public Class getCompiledClass() {
         return AbstractInterpreter.this.getClass();
      }
   }

   public static ClassDeclaration defaultCmdObject = new DefaultCmdClassDeclaration();
   static {
      defaultCmdObject.staleClassName = defaultCmdObject.fullTypeName = defaultCmdObject.typeName = "cmd";
      defaultCmdObject.operator = "object"; // this one needs to be object and unlike cmdObject is not used in sync - it's edit time only
      defaultCmdObject.setDynamicType(true);
      defaultCmdObject.liveDynType = true;
      defaultCmdObject.setProperty("extendsType", ClassType.create(AbstractInterpreter.class.getTypeName()));
   }

   public ClassDeclaration cmdObject = new CmdClassDeclaration();
   {
      cmdObject.staleClassName = cmdObject.fullTypeName = cmdObject.typeName = "cmd";
      cmdObject.operator = "class"; // If we use object, this becomes a 'rooted object' in the sync system and we won't construct it during sync
      cmdObject.setDynamicType(true);
      cmdObject.liveDynType = true;
      cmdObject.setProperty("extendsType", ClassType.create(getClass().getTypeName()));
      // We used to not have this scriptObject and disallowed classBodyDeclarations from the top-level - as though you were editing a Java file.  But when writing scripts,
      // it's really useful to be able to just define fields, and methods, and not save them - you are already authoring them in the script.  So now, the idea is that we have
      // a 'cmd' object which stores all of the script stuff which is thrown away.  The downside is that we may need some way to limit references to persistent edited code to
      // these 'cmd' fields, methods, etc since they won't be there unless the script is running.
      currentTypes.add(cmdObject);
      startTypeIndex = 1;

      // TODO: will there ever be more than one?
      DynUtil.addDynInstance("cmd", this);
      dynObj = new DynObject(cmdObject);
   }
   public static String INTRO =  "The scc command builds, runs, and edits StrataCode programs.  You can specify a set of layers to build/run, use the -c option to compile only, use the -a option to build all (useful when incremental compilation runs into problems), or run with no options to build a new application from scratch using the command interpreter.\n\n";

   public static String USAGE =  "Command line help:\n\n" +
           "StrataCode's REPL (read-eval-print-loop) provides command line access to the dynamic runtime features of the current application.\n\n" +
           "The command line syntax uses a slightly modified version of the .sc syntax, as though you were adding/editing a file line-by-line, with a current type, instance, layer, package, and imports.\n\n" +
           "Use the global object 'cmd' to control the running application and features for implementing scripts.\n\n" +
           "In supported terminals, use TAB to suggest completions of the current context.\n\n" +
           "Command line modes: Two modes are supported: one for program editing, the other better suited for test scripts or " +
           "just using the current application. The prompt starts with 'edit:' if you are in edit mode" +
           "(the default for the command line), and 'scr:' for script mode (the default for test scripts).\n\n" +
           "Enter: 'cmd.edit=false/true;' to switch back and forth.\n\n" +
           "Using edit mode for writing code from scratch or changing the configured value of a property and applying the change to all" +
           "instances. If 'a = 3' should just set the current instance's value of a use script mode. Both modes let you add a new field, method or type to a temporary version of the current type or layer. In script mode, those are just temporary fields, methods, types etc. which are available only during the duration of the script rather. " +
           "Use cmd.save(); to save changes made in edit mode but be sure to check the current layer, the current source file, and use" +
           "cmd.print(); or cmd.printChanges(); to check what will be saved to avoid overwriting source code in an undesired way!\n\n" +
           "Navigation on the command line:\n   The command line starts at the current layer, which is by default the last build layer of the main process. There is no current type, but there is a current package, current layer etc. By default, you start out with the last layer of the application or if you use -i editing a temporary layer that extends the application layer." +
           "At this stage, enter another layer name to switch layers (or use cmd.down(), or cmd.up()). Create a new layer to build customizations of the current application using cmd.createLayer()." +
           "Enter a type name relative to the current package, or a packageName, or change the current package using a 'package' statement. At this level, you also can add an import or define a new class or object:\n" +
           " Example commands:" +
           "   layerName {                Change context to layerName.  \n" +
           "   typeName {                 Change context to the typeName specified.  If the type is defined in this layer, you are at this point editing that file from the command line incrementally.  Be careful!  If there is no definition of that type in this layer, a 'modify defineition' is automatically created and your changes are recorded in the new layer.\n" +
           "   packageName {              Set current context to the package name specified.\n" +
           "   class newClassName {\n" +
           "   object newInstanceName {   Define/replace a new top-level class or object with the current package in the current layer.  If this class is defined in a previous layer, you are replacing that definition.  Be careful!\n" +
           "   package newPackageName;    Change the current package\n" +
           "   import newImportName;      Add an import.\n\n" +
           "When you have navigated to a current type, the commands are limited to that context until the } is used to go back. At this level:\n" +
           "   foo = 3;                  Set a property - in edit mode, change all instances and stage a change to the current layer's source file, or add a new modify in this layer for this property of this type.\n" +
           "   foo;                      Eval an expression against the current instance of the current type.\n" +
           "   int foo; void foo() {}    Add/replace fields or methods - same in both edit and script modes\n\n" +
           "At any time:\n" +
           "   }                         To exit a type and return to the previous type or to the top-level with no type.\n" +
           "   cmd.save();               To save pending changes - use cmd.printChanges() to check them before saving.\n" +
           "   cmd.list();               Display current objects, classes, field, methods etc. in the current context\n" +
           "   <TAB>                     Command line completion with terminals supported by JLine\n" +
           "   cmd.edit = true/false;    Switch back and forth between edit and script modes\n" +
           "\n\n" +
           "Evaluating expressions:\n   At either the top-level or with a current type, evaluate any expression to see the result on the terminal.\n\n" +
           "Using the current instance:\n   When navigating to a type, the command line tries to select a default instance automatically. " +
           "If it's global or a singleton it happens automatically. To select a specific instance in a specific context (e.g. a specific " +
           "web page request in test-mode only) use cmd.scopeContextName along with ?scopeContextName= to create a page with that scopeContextName.\n\n" +
           "When there is no specified current instance, if the session is interactive, a wizard prompts the user to select an instance. " +
           "In a batch script, the first instance in the list chosen.\n\n" +
           "Runtimes and processes:\n" +
           "   By default, commands run on all matching runtimes. " +
           "Set cmd.targetRuntime = 'js', 'java' or other runtime name to target a specific runtime.  Or use a process identifier " +
           "like 'java_Server' to target a specific process. Annotations or the runtime which defines a particular entity control the default " +
           "but sometimes when changing a property that exists in both client and server runtimes, this will cause the change to be applied twice.\n\n" +
           /*
           "The Prompt                   Displays your current context - edit or script mode, the layer, package, and where you are in the layer stack using # before or after the layer name:\n\nExamples:\n" +
           "  (example.unitConverter.model:sc.example.unitConverter##) ->\n" +
           "   ^ current layer             ^ current package       ^^ 2 layers below this layer in the stack\n\n" +
           "  (doc.core:sc.doc#(15)#) ->\n" +
           "                    ^ 15 layers below\n\n" +
           */

           "The cmd object controls the interpreter and the dynamic runtime:\n" +
           "   cmd.list();         - Lists the types (if at the top level) or the current objects and properties if inside of a type.\n" +
           "   cmd.listLayers();\n" +
           "   cmd.listObjects();\n" +
           "   cmd.listTypes();\n" +
           "   cmd.listProps();\n" +
           "   cmd.listMethods();\n" +
           "                      - List the layers, objects, types, properties, or methods of the current type\n" +
           "   cmd.createLayer(); - Starts a command-line wizard to create a new layer.\n" +
           "   cmd.addLayer();    - Starts a command-line wizard to add an existing layer.\n" +
           "   cmd.save();        - Saves any changes made via the command line.\n" +
           "   cmd.undo();        - Undo any pending changes.\n" +
           "   cmd.redo();        - Redo any undone changes.\n" +
           "   cmd.quit();        - Exit the process.\n" +
           "   cmd.up();          - Move up the current layer stack for the given type.\n" +
           "   cmd.down();        - Move down the current layer stack for the given type.\n" +
           "   cmd.rebuild();     - Rebuild the system after you've made code changes.\n" +
           "   cmd.rebuildAll();  - Rebuild the system from scratch.\n" +
           "   cmd.restart();     - Restart the process.\n" +
           "   cmd.<TAB>          - list the remaining properties and methods\n" +
           "   cmd.targetRuntime  - Set to the runtime name or process identifier of the specific target. The default is to target all matching processes." +
           "   cmd.scopeContextName - Set to the name of the CurrentScopeContext which identifies the specific target for the command." +
           "\n\n";

   /** For commands like createLayer, we add a wizard which processes input temporarily */
   CommandWizard currentWizard = null;

   public boolean echoInput = true;

   /** Time to pause in between commands - useful for playing back scripts */
   public int pauseTime = 0;

   /** Set enableBatchMode = true to prevent releasing locks in between commands.  In other words, the commands run all at once until the next wait */
   public boolean enableBatchMode = false;

   public boolean autoObjectSelect = true;

   /** When set to true, we turn off the prompt, mostly for auto tests but it's also not helpful when stdin is redirected */
   boolean consoleDisabled = false;

   public AbstractInterpreter(LayeredSystem sys, boolean consoleDisabled, String inputFileName) {
      super(sys);
      cmdObject.parentNode = getModel();
      this.inputFileName = inputFileName;
      if (inputFileName != null)
         this.inputRelName = FileUtil.getFileName(inputFileName);
      this.consoleDisabled = consoleDisabled;
      cmdlang.initialize();

      // TODO: make this an object which does a thread-local lookup to find the right interpreter if we need more than one or to keep this out of the global name space when the interpreter is enabled
      sys.addGlobalObject("cmd", this);
   }

   private final static int MAX_PREFIX = 3;

   public abstract String readLine(String nextPrompt);

   public abstract boolean inputBytesAvailable();

   public void doProcessStatement(Object result, String lastCommand) {
      if (result != null) {
         try {
            // Nice for testing to see the command we are about to process
            if (echoInput && consoleDisabled && lastCommand != null && lastCommand.trim().length() > 0)
               System.out.println("Script cmd: " + lastCommand);
            statementProcessor.processStatement(this, result);
            if (pauseTime != 0 && !enableBatchMode)
               sleep(pauseTime);
         }
         catch (Throwable exc) {
            Object errSt = result;
            if (errSt instanceof List && ((List) errSt).size() == 1)
               errSt = ((List) errSt).get(0);
            System.err.println("Script error: " + exc.toString() + " for statement: " + errSt);
            if (system.options.verbose)
               exc.printStackTrace();
            if (exitOnError) {
               System.err.println("Exiting -1 on error because cmd.exitOnError configured as true");
               System.exit(-1);
            }
         }
      }
   }

   private String getSyncPromptStr() {
      List<LayeredSystem> syncSystems = system.getSyncSystems();
      if (!sync || syncSystems == null || syncSystems.size() == 0)
         return "";
      else {
         StringBuilder sb = new StringBuilder();
         sb.append("sync:");
         for (int i = 0; i < syncSystems.size(); i++) {
            LayeredSystem syncSys = syncSystems.get(i);
            if (i != 0)
               sb.append(",");
            sb.append(getPromptForRuntime(syncSys));
         }
         sb.append("<=>");
         sb.append(getPromptForRuntime(system));
         return sb.toString();
      }
   }

   private String getPromptForRuntime(LayeredSystem rtSys) {
      if (targetRuntime == null || runtimeNameMatches(rtSys, targetRuntime))
         return "(" + rtSys.getRuntimeName() + ")";
      else
         return rtSys.getRuntimeName();
   }

   protected String prompt() {
      // We don't want to display the prompt when we've been redirected from another file
      if (consoleDisabled || noPrompt)
         return "";
      system.acquireDynLock(false); // TODO: should be read-only lock - why does that not work?  so that we wait till the main thread has finished.  This both ensures the prompt is the last message displayed and that main processing completes before we start reading the next command.
      try {
         if (currentWizard != null)
            return currentWizard.prompt();

         String hdr = edit ? "edit" : "scr" + (system.staleCompiledModel ? "*" : "");
         hdr += ":";
         boolean displayLayer = edit;

         String basePrompt = getSyncPromptStr() + " -> ";

         if (currentTypes.size() == startTypeIndex) {
            String prefix = "";
            if (displayLayer) {
               prefix = currentLayer == null ? "<no layer>" : (currentLayer.getLayerName() + (getPrefix() == null ? "" : ":" + getPrefix()));
               StringBuilder upPrefix = new StringBuilder();
               StringBuilder downPrefix = new StringBuilder();
               if (currentLayer != null) {
                  for (int c = currentLayer.getLayerPosition(); c < currentLayer.getLayersList().size()-1; c++)
                     upPrefix.append("#");
                  for (int c = 0; c < currentLayer.getLayerPosition(); c++)
                     downPrefix.append("#");
               }
               if (prefix.length() > 0 || upPrefix.length() > 0 || downPrefix.length() > 0) {
                  if (upPrefix.length() > MAX_PREFIX)
                     upPrefix = new StringBuilder("#...#");
                  if (downPrefix.length() > MAX_PREFIX)
                     downPrefix = new StringBuilder("#...#");
                  prefix = upPrefix + prefix + downPrefix;
               }
            }
            return hdr + prefix + basePrompt;
         }
         else {
            BodyTypeDeclaration type = currentTypes.get(currentTypes.size()-1);

            Object curObj = getCurrentObject();
            String objOrTypeName;
            if (curObj != null) {
               objOrTypeName = "I:" + DynUtil.getInstanceName(curObj);
            }
            else
               objOrTypeName = "T:" + CTypeUtil.getClassName(ModelUtil.getTypeName(type));
            String layerPrefix = "";
            if (edit) {
               StringBuilder upPrefix = new StringBuilder(), downPrefix = new StringBuilder();
               BodyTypeDeclaration otherType = type;
               while ((otherType = otherType.getModifiedByType()) != null) {
                  upPrefix.append("#");
               }
               otherType = type;
               while ((otherType = otherType.getModifiedType()) != null) {
                  downPrefix.append("#");
               }
               layerPrefix = upPrefix + currentLayer.getLayerName() + downPrefix + ":";
            }
            return hdr + layerPrefix + objOrTypeName + " -> ";
         }
      }
      finally {
         system.releaseDynLock(false);
      }
   }

   protected Parselet getParselet() {
      if (currentTypes.size() == startTypeIndex)
         return cmdlang.topLevelCommands;
      else
         return cmdlang.typeCommands;
   }

   protected Object parseCommand(String command, Parselet startParselet) {
      // TODO: fixme!
      if (inBlockComment) {
         pendingInput = new StringBuilder();
         int endIx = command.lastIndexOf("*/");
         if (endIx != -1) {
            command = command.substring(endIx + 2);
            inBlockComment = false;
         }
         else {
            return null;
         }
      }
      if (currentWizard != null) {
         currentWizard.parseCommand(command);
         return null;
      }

      // Skip empty lines, though make sure somewhitespace gets in there
      String tcommand = command.trim();
      if (tcommand.length() == 0) {
         return null;
      }

      if (command.equals("help") || command.equals("?")) {
         System.out.println(StringUtil.insertLinebreaks(USAGE, 80));
         pendingInput = new StringBuilder();
         return null;
      }

      // TODO: fixme - this is rudimentary support for block commands and will fail for cases like multiple block comments on one line or block comments embedded in strings
      if (tcommand.startsWith("/*")) {
         int endIx = tcommand.lastIndexOf("*/");
         if (endIx == -1) {
            inBlockComment = true;
            pendingInput = new StringBuilder();
            return null;
         }
         else {
            String newCommand = tcommand.substring(endIx + 2);
            if (newCommand.trim().length() == 0)
               return null;
            pendingInput = new StringBuilder(newCommand);
         }
      }

      if (!startParselet.initialized) {
         ParseUtil.initAndStartComponent(startParselet);
      }
      Parser p = new Parser(cmdlang, new StringReader(command));
      Object parseTree = p.parseStart(startParselet);
      if (parseTree instanceof ParseError) {
         ParseError err = (ParseError) parseTree;
         // If we're reading from a script, we'll just accumulate the stuff to parse like we would if reading from a file
         if (!inputBytesAvailable() && (!p.eof || p.getInputChar(p.currentErrorEndIndex) != '\0')) {
            p = new Parser(cmdlang, new StringReader(command));
            p.enablePartialValues = true;
            parseTree = p.parseStart(startParselet);

            // Once we enable partial values and still can't parse it, we are ready to consider it junk and throw it away
            if (parseTree instanceof ParseError && (!p.eof || p.getInputChar(p.currentErrorEndIndex) != '\0')) {
               pendingInput = new StringBuilder();
               System.err.println(err.errorStringWithLineNumbers(command));
            }
            else
               pendingInput.append("\n");
         }
         else
            pendingInput.append("\n");
         return null;
      }
      // The parser did not consume all of the input - ordinarily an error but for the command interpreter,
      // we'll just save the extra stuff for the next go around.
      else if (!p.eof || p.peekInputChar(0) != '\0') {
         int consumed = p.getCurrentIndex();
         pendingInput = new StringBuilder(pendingInput.toString().substring(consumed));
      }
      // Parsed it all - zero out the pending input
      else
         pendingInput = new StringBuilder();
      return ParseUtil.nodeToSemanticValue(parseTree);
   }

   protected JavaModel getModel() {
      JavaModel m = super.getModel();
      m.setCommandInterpreter(this);
      return m;
   }

   public void promptAndSave(JavaModel model, boolean refreshEditSession) {
      SaveModelWizard.start(this, model);
   }

   public void processStatement(Object statement, boolean skipEval) {
      JavaModel model = getModel();

      // Use this as a quick way to detect any errors that happen during this process.
      model.hasErrors = false;

      List<LayeredSystem> syncSystems = getSyncSystems();

      String recordString = null;
      int origIndent = 0;

      if (recordOutputWriter != null && statement instanceof JavaSemanticNode) {
         recordString = ((JavaSemanticNode) statement).toLanguageString();
         origIndent = currentTypes.size();
      }

      if (statement instanceof SemanticNodeList) {
         SemanticNodeList sts = (SemanticNodeList) statement;
         for (Object subStatement: sts)
            processStatement(subStatement, skipEval);
      }
      else if (statement instanceof Package) {
         Package pkg = (Package) statement;
         if (pkg.name == null) {
            if (layerPrefix != null && layerPrefix.length() != 0)
               System.err.println("Empty package name invalid in layer with prefix: " + layerPrefix);
            else
               model.setProperty("packageDef", pkg);
         }
         else if (layerPrefix == null || pkg.name.startsWith(layerPrefix))
            model.setProperty("packageDef", pkg);
         else
            System.err.println("Package name: " + pkg.name + " must start with layer's package prefix: " + layerPrefix);
      }
      else if (statement instanceof ImportDeclaration) {
         model.addImport((ImportDeclaration) statement);
         addChangedModel(model);
      }
      else if (statement instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration type = (BodyTypeDeclaration) statement;
         BodyTypeDeclaration parentType = getCurrentType(false);
         Layer layer = currentLayer;

         if (layer == null) {
            System.err.println("Cannot define a type when there is no layer.  Use cmd.createLayer()");
            return;
         }

         boolean addToType = true;
         SrcEntry newSrcEnt = null;
         Object absType = null;
         if (parentType == null) {
            // If you do pkgName with an open brace we'll just prepend this onto the path.  Since pkgNames and class names do not
            // overlap in the namespace, this gives you one syntax for navigating the type hierarchy of packages,
            // classes and inner classes.   Needs to be before the file check because the layer def's .sc file can
            // overlap with the package name
            if (statement instanceof ModifyDeclaration) {
               String typeName = ((ModifyDeclaration) statement).typeName;
               // Specify the full-type name to a type and get it unambiguously
               absType = system.getTypeDeclaration(typeName, false, system.buildLayer, false);
               if (absType == null) {
                  String testPackageName = CTypeUtil.prefixPath(getPrefix(), typeName);
                  Set<String> pkgFiles = system.getFilesInPackage(testPackageName);
                  if (pkgFiles != null) {
                     path = typeName;
                     recordOutput(recordString, origIndent);
                     return;
                  }

                  Layer switchLayer;
                  if ((switchLayer = system.getLayerByPath(typeName, false)) != null || (switchLayer = system.getLayerByPath(CTypeUtil.prefixPath(currentLayer.getLayerGroupName(), typeName), false)) != null) {
                     setCurrentLayer(switchLayer);
                     recordOutput(recordString, origIndent);
                     return;
                  }
               }

               // If it's not an absolute name of a type, package or layer, see if it's a name that's been imported.
               if (absType == null) {
                  ImportDeclaration importDecl = system.getImportDecl(null, currentLayer, typeName);
                  if (importDecl != null && !importDecl.staticImport) {
                     absType = system.getTypeDeclaration(importDecl.identifier, false, system.buildLayer, false);
                  }
                  if (absType == null) {
                     for (String importPackage:importPackages) {
                        absType = system.getTypeDeclaration(CTypeUtil.prefixPath(importPackage, CTypeUtil.prefixPath(path, typeName)), false, system.buildLayer, false);
                        if (absType != null)
                           break;
                     }
                  }
                  if (absType != null) {
                     Layer typeLayer = ModelUtil.getLayerForType(system, absType);
                     JavaModel absModel = null;
                     if (absType instanceof BodyTypeDeclaration)
                        absModel = ((BodyTypeDeclaration) absType).getJavaModel();
                     if (absModel != null)
                        absModel.commandInterpreter = this;
                     // Unless we are creating a new 'modify' for this type, we want to change the current layer to match the type, or else this type will disappear.
                     if (typeLayer != null && typeLayer != currentLayer && (!edit || currentLayer == null || (!StringUtil.equalStrings(typeLayer.packagePrefix, currentLayer.packagePrefix) && !StringUtil.isEmpty(currentLayer.packagePrefix)))) {
                        setCurrentLayer(typeLayer);
                        layer = typeLayer;
                        if (!edit)
                           pendingModel = absModel;
                        model = getModel();
                     }
                  }
               }
            }

            if (edit) {
               newSrcEnt = findModelSrcFileForLayer(type, path, layer);

               boolean modelChanged = false;
               boolean currentDefChanged = false;
               TypeDeclaration currentDef = null;
               File modelFile = new File(newSrcEnt.absFileName);

               try {
                  system.acquireDynLock(false);
                  // If either there's a file in this layer or we may have unsaved changed to a file in this layer
                  if (modelFile.canRead() || isChangedModel(layer, newSrcEnt.relFileName)) {
                     boolean restart = model.isStarted();
                     model.removeSrcFiles();
                     model.addSrcFile(newSrcEnt);
                     if (restart)
                        ParseUtil.restartComponent(model);

                     if (!model.isStarted()) // make sure the model
                        ParseUtil.initAndStartComponent(model);

                     currentDef = system.getSrcTypeDeclaration(CTypeUtil.prefixPath(model.getPackagePrefix(), type.typeName), currentLayer.getNextLayer(), true);
                     if (currentDef == null || currentDef.getLayer() != layer) {
                        system.refreshRuntimes(true);
                        currentDef = system.getSrcTypeDeclaration(CTypeUtil.prefixPath(model.getPackagePrefix(), type.typeName), currentLayer.getNextLayer(), true);
                        if (currentDef == null) {
                           System.err.println("No type: " + type.typeName + " in layer: " + currentLayer);
                           return;
                        }
                     }

                     if (currentDef != null) {
                        if (currentDef.getLayer() != layer) {
                           System.err.println("*** definition has wrong layer");
                        }
                        if (type instanceof ModifyDeclaration) {
                           ModifyDeclaration modType = (ModifyDeclaration) type;
                           type.parentNode = currentDef.parentNode;
                           // We're going to throw this away so this tells the system not to consider it part of the type
                           // system.
                           modType.markAsTemporary();
                           ParseUtil.initAndStartComponent(type);
                           if (modType.mergeDefinitionsInto(currentDef, false))
                              currentDefChanged = true;
                        }
                        else {
                           System.err.println("*** Definition of type: " + type.typeName + " already exists in layer: " + layer);
                        }
                     }

                     // Proceed using this object as the type to push onto the stack
                     type = currentDef;
                     addToType = false;
                     List oldImports = pendingModel.imports;
                     sc.lang.java.Package oldPkg = pendingModel.packageDef;

                     pendingModel = type.getJavaModel();
                     pendingModel.setCommandInterpreter(this);

                     // Copy over any definitions added before we switched into this type
                     if (oldImports != null) {
                        for (Object imp:oldImports) {
                           pendingModel.addImport(((ImportDeclaration) imp));
                           currentDefChanged = true;
                        }
                     }
                     if (oldPkg != null) {
                        pendingModel.changePackage(oldPkg);
                        currentDefChanged = true;
                     }
                  }
                  else {
                     model.removeSrcFiles();
                     model.addSrcFile(newSrcEnt);
                     ParseUtil.restartComponent(model);
                     modelChanged = true;
                  }
               }
               finally {
                  system.releaseDynLock(false);
               }

               if (currentDefChanged)
                  addChangedModel(currentDef.getJavaModel());
               if (modelChanged)
                  addChangedModel(model);
            }
            else {
               model.setRelDirPath(path);
               if (!model.isInitialized())
                  model.init();
            }
         }

         String origTypeName = type.typeName;

         // By default, we'd like to the live dynamic types feature for types manipulated in the command line
         type.liveDynType = true;

         if (addToType) {
            if (edit) {
               BodyTypeDeclaration origType = type;
               type = addToCurrentType(model, parentType, type);
               if (type == null) {
                  removeFromCurrentObject(model, parentType, origType);
                  return;
               }
            }
            else {
               type.markAsTemporary();
               type.parentNode = parentType == null ? model : parentType;

               if (type instanceof ModifyDeclaration) {
                  ParseUtil.initAndStartComponent(type);
                  BodyTypeDeclaration modType = type.getModifiedType();
                  if (modType != null) {
                     type = modType;
                     JavaModel modModel = type.getJavaModel();

                     // TODO: security check - when we are using the command line here, we are randomly picking a type and exposing the 'cmd' name space through that type
                     // Great for diagnostics and debugging but not great for using in a live environment.  But the command line would typically not be exposed in that environment.
                     modModel.commandInterpreter = this;
                  }
               }
            }
         }
         // Need to do this after we've added the type to the file system.  Otherwise, we try to lookup the type
         // before it is on the file system and that leads to an error trying to find the type
         if (newSrcEnt != null)
            layer.addNewSrcFile(newSrcEnt, true);
         boolean pushedCtx = pushCurrentScopeContext();

         // Must be done after adding so we get the full type name
         String typeName = ModelUtil.getTypeName(type);
         boolean hasCurrentObject = hasCurrentObject();
         boolean checkCurrentObject = parentType == null || hasCurrentObject;
         if (currentScopeCtx != null)
            currentScopeCtx.addSyncTypeToFilter(typeName, " command line statement: " + statement);

         Object parentObj = getCurrentObjectWithDefault();
         currentTypes.add(type);

         DeclarationType declType = type.getDeclarationType();

         boolean definesCurrentObject = type.getDefinesCurrentObject();
         if (!definesCurrentObject)
            checkCurrentObject = false;

         if (definesCurrentObject || parentType == null) {
            boolean pushed = false;
            Object obj = null;
            try {
               execContext.pushStaticFrame(type);

               CurrentObjectResult cor = selectCurrentObject(null, type, !pushedCtx, false, true);
               if (cor.wizardStarted)
                  return;
               obj = cor.curObj;
               pushed = cor.pushedObj;
               pushedCtx = pushedCtx || cor.pushedCtx;

               // Using origTypeName here - grabbed before we do the "a.b" to a as a parent of b" conversion.   type.typeName now will just be "b".
               // Only do this if the current object is the parent object - not if it's already been resolved from the selectedInstances array
               if (!pushed) {
                  if (obj == null)
                     obj = parentObj == null ? (checkCurrentObject ? system.resolveName(typeName, true, false) : null) : (hasCurrentObject ? DynUtil.getPropertyPath(parentObj, origTypeName) : null);
                  execContext.pushCurrentObject(obj);
                  pushed = true;
               }
            }
            catch (RuntimeException exc) {
               system.anyErrors = true;
               System.err.println("*** Error trying to resolve name: " + typeName + ": " + exc);
               exc.printStackTrace();
            }
            finally {
               if (!pushed)
                  execContext.pushCurrentObject(null);
            }
            if (obj == null && checkCurrentObject) {
               System.err.println("*** Unable to resolve object: " + typeName);
            }
         }
         // Remove any traces of the failed modify declaration
         else if (declType == DeclarationType.UNKNOWN) {
            currentTypes.remove(currentTypes.size()-1);
            removeFromCurrentObject(model, parentType, type);
         }
         else {
            execContext.pushStaticFrame(type);
         }
         markCurrentTypeChanged();
         if (pushedCtx)
            popCurrentScopeContext();
      }
      else if (statement instanceof PropertyAssignment) {
         Object curObj = null;
         boolean pushed = false;
         boolean pushedCtx = pushCurrentScopeContext();
         boolean noInstance = false;
         PropertyAssignment pa = (PropertyAssignment) statement;

         BodyTypeDeclaration current = currentTypes.get(currentTypes.size()-1);

         if (!pa.isStatic()) {
            CurrentObjectResult cor = selectCurrentObject(pa, current, !pushedCtx, true, true);
            if (cor.wizardStarted)
               return;
            noInstance = cor.skipEval;
            pushed = cor.pushedObj;
            pushedCtx = pushedCtx || cor.pushedCtx;
            curObj = cor.curObj;
         }

         try {
            PropertyAssignment assign = (PropertyAssignment) statement;
            boolean performedOnce = false;

            assign.parentNode = getParentNode(current);

            ParseUtil.initAndStartComponent(assign);

            if (!assign.hasErrors() && performUpdatesToSystem(system, false) && !ignoreRemoteStatement(system, assign)) {
               performedOnce = true;

               if (system.options.verboseExec)
                  system.info("Exec: " + assign + " on system: " + system.getProcessIdent());

               if (edit) {
                  // synchronization done inside updateProperty - we grab the lock to update the model but release it to update the property in case that triggers events back into the system
                  JavaSemanticNode newDefinition = current.updateProperty(assign, execContext, !skipEval, null);
                  addChangedModel(pendingModel);

                  if (model.hasErrors() && newDefinition != assign) {
                     removeFromCurrentObject(model, current, assign);
                  }
               }
               else if (curObj != null) {
                  DynUtil.setProperty(curObj, assign.propertyName, assign.initializer.eval(null, execContext));
               }
               else
                  System.err.println("*** No object for property update: " + current.typeName + ". " + assign.propertyName);
            }

            if (!assign.hasErrors() && sync && syncSystems != null && currentLayer != null && !skipEval) {
               boolean any = false;
               for (LayeredSystem peerSys:syncSystems) {
                  if (performUpdatesToSystem(peerSys, performedOnce)) {
                     Layer peerLayer = peerSys.getLayerByName(currentLayer.layerUniqueName);
                     BodyTypeDeclaration peerType = peerSys.getSrcTypeDeclaration(current.getFullTypeName(), peerLayer == null ? null : peerLayer.getNextLayer(), true);
                     if (peerType != null && !peerType.excluded) {
                        PropertyAssignment peerAssign = assign.deepCopy(ISemanticNode.CopyAll, null);
                        peerAssign.parentNode = peerType;

                        if (!ignoreRemoteStatement(peerSys, peerAssign)) {
                           if (system.options.verboseExec)
                              system.info("Exec: " + peerAssign + " on system: " + peerSys.getProcessIdent());

                           if (edit) {
                              UpdateInstanceInfo peerUpdateInfo = peerSys.newUpdateInstanceInfo();
                              // TODO: is it right to pass execContext here and to updateInstances - isn't the currentObj stacks are not for the peer runtime
                              // But maybe we want to do all eval's required on the local runtime
                              peerType.updateProperty(peerAssign, execContext, true, peerUpdateInfo);
                              peerUpdateInfo.updateInstances(execContext);
                           }
                           else {
                              peerAssign.parentNode = peerType;
                              Object remoteRes = peerSys.runtimeProcessor.invokeRemoteStatement(peerType, curObj, peerAssign, getTargetScopeContext());
                              if (remoteRes != null) {
                                 System.out.println(remoteRes);
                              }
                           }
                           any = true;
                        }
                     }
                  }
               }
               if (any)
                  system.notifyCodeUpdateListeners();
            }
         }
         finally {
            if (pushed)
               execContext.popCurrentObject();
            if (pushedCtx)
               popCurrentScopeContext();
         }
      }
      else if (statement instanceof TypedDefinition) {
         BodyTypeDeclaration current = currentTypes.get(currentTypes.size()-1);

         // We do not generate unless the component is started
         if (!model.isStarted())
            ParseUtil.initAndStartComponent(model);
         TypedDefinition def = (TypedDefinition) statement;

         def.parentNode = current;
         ParseUtil.initAndStartComponent(statement);
         if (!def.hasErrors()) {
            current.updateBodyStatement(def, execContext, true, null);
         }
         else
            System.err.println("*** Errors resolving: " + def.getNodeErrorText());
         // TODO: handle other systems here
         addChangedModel(pendingModel);
      }
      else if (statement instanceof EndTypeDeclaration) {
         if (currentTypes.size() == startTypeIndex) {
            if (path == null || path.equals("")) {
               if (currentLayer == null)
                  System.err.println("No current layer to go up");
               else
                  System.err.println("No more package levels to go up.  Current layer: " + currentLayer + " begins with prefix: " + currentLayer.packagePrefix);
            }
            else {
               int ix = path.lastIndexOf(".");
               if (ix == -1) {
                  path = "";
               }
               else
                  path = path.substring(0, ix);
            }
         }
         else {
            BodyTypeDeclaration oldType = currentTypes.remove(currentTypes.size()-1);
            if (oldType.getDefinesCurrentObject()) {
               execContext.popCurrentObject();
               execContext.popStaticFrame();
            }
            else if (oldType.getDeclarationType() != DeclarationType.UNKNOWN)
               execContext.popStaticFrame();
            if (currentTypes.size() == startTypeIndex)
               clearPendingModel();
            origIndent--;
            markCurrentTypeChanged();
         }
      }
      else if (statement instanceof Expression) {
         boolean pushed = false;
         boolean pushedCtx = false;
         Object curObj = null;

         Expression expr = (Expression) statement;
         BodyTypeDeclaration currentType = currentTypes.size() == 0 ? null : currentTypes.get(currentTypes.size() - 1);
         if (currentType == null)
            expr.parentNode = getModel();
         else
            expr.parentNode = currentType;

         ParseUtil.initAndStartComponent(model);
         ParseUtil.initAndStartComponent(expr);

         if (expr.errorArgs == null && !model.hasErrors) {
            pushedCtx = pushCurrentScopeContext();
            if (!expr.isStaticTarget() && currentType != null) {
               CurrentObjectResult cor = selectCurrentObject(expr, currentType, !pushedCtx, true, true);
               if (cor.wizardStarted)
                  return;
               pushed = cor.pushedObj;
               pushedCtx = pushedCtx || cor.pushedCtx;
               skipEval = cor.skipEval;
               curObj = cor.curObj;
            }

            try {
               if (!skipEval) {
                  boolean evalPerformed = false;
                  if (performUpdatesToSystem(system, false) && !ignoreRemoteStatement(system, expr)) {
                     if (system.options.verboseExec)
                        system.info("Exec local expr: " + expr + " on: " + system.getProcessIdent());

                     Object exprResult = expr.eval(null, execContext);
                     evalPerformed = true;
                     if (exprResult == null) {
                        if (!ModelUtil.typeIsVoid(expr.getTypeDeclaration()))
                           System.out.println("null");
                     }
                     else
                        System.out.println(exprResult);
                  }

                  if (syncSystems != null && currentType != null && currentLayer != null) {
                     for (LayeredSystem peerSys:syncSystems) {
                        if (performUpdatesToSystem(peerSys, evalPerformed)) {
                           Layer peerLayer = peerSys.getLayerByName(currentLayer.layerUniqueName);
                           BodyTypeDeclaration peerType = peerSys.getSrcTypeDeclaration(currentType.getFullTypeName(), peerLayer == null ? null : peerLayer.getNextLayer(), true);
                           if (peerType != null && !peerType.excluded) {
                              Expression peerExpr = expr.deepCopy(0, null);
                              peerExpr.parentNode = peerType;
                              if (!ignoreRemoteStatement(peerSys, peerExpr)) {
                                 if (system.options.verboseExec)
                                    system.info("Exec remote expr: " + expr + " on: " + peerSys.getProcessIdent());
                                 Object remoteRes = peerSys.runtimeProcessor.invokeRemoteStatement(peerType, curObj, peerExpr, getTargetScopeContext());
                                 System.out.println(remoteRes);
                              }
                           }
                        }
                     }
                  }
               }
            }
            finally {
               if (pushed)
                  execContext.popCurrentObject();
               if (pushedCtx)
                  popCurrentScopeContext();
            }
         }
      }
      else if (statement instanceof BlockStatement) {
         BodyTypeDeclaration currentType = currentTypes.get(currentTypes.size()-1);
         BlockStatement block = (BlockStatement) statement;

         // We do not generate unless the component is started
         if (!model.isStarted())
            ParseUtil.initAndStartComponent(model);

         Object curObj = null;
         boolean pushed = false;
         boolean pushedCtx = pushCurrentScopeContext();

         if (!block.staticEnabled) {
            CurrentObjectResult cor = selectCurrentObject(block, currentType, !pushedCtx, true, true);
            if (cor.wizardStarted)
               return;
            pushed = cor.pushedObj;
            pushedCtx = pushedCtx || cor.pushedCtx;
            curObj = cor.curObj;
            skipEval = cor.skipEval;
         }

         try {
            boolean updatedAlready = false;
            if (performUpdatesToSystem(system, false)) {
               currentType.updateBlockStatement((BlockStatement)statement, execContext, null);
               updatedAlready = true;
            }

            if (sync && syncSystems != null && currentLayer != null && !skipEval) {
               for (LayeredSystem peerSys:syncSystems) {
                  if (performUpdatesToSystem(system, updatedAlready)) {
                     Layer peerLayer = peerSys.getLayerByName(currentLayer.layerUniqueName);
                     BodyTypeDeclaration peerType = peerSys.getSrcTypeDeclaration(currentType.getFullTypeName(), peerLayer == null ? null : peerLayer.getNextLayer(), true);
                     if (peerType != null && !peerType.excluded) {
                        BlockStatement peerBlock = block.deepCopy(ISemanticNode.CopyNormal, null);
                        if (edit) {
                           UpdateInstanceInfo peerUpdateInfo = peerSys.newUpdateInstanceInfo();
                           peerType.updateBlockStatement(peerBlock, execContext, peerUpdateInfo);
                           peerUpdateInfo.updateInstances(execContext);
                        }
                        else {
                           peerBlock.parentNode = peerType;
                           Object remoteRes = peerSys.runtimeProcessor.invokeRemoteStatement(peerType, curObj, peerBlock, getTargetScopeContext());
                           if (remoteRes != null) {
                              System.out.println(remoteRes);
                           }
                        }
                     }
                  }
               }
            }
         }
         finally {
            if (pushed)
               execContext.popCurrentObject();
            if (pushedCtx)
               popCurrentScopeContext();
         }
      }
      else if (statement instanceof Statement) {
         boolean pushed = false;
         boolean pushedCtx = pushCurrentScopeContext();
         Object curObj = null;

         Statement expr = (Statement) statement;
         BodyTypeDeclaration currentType = currentTypes.size() == 0 ? null : currentTypes.get(currentTypes.size() - 1);
         if (currentType == null)
            expr.parentNode = getModel();
         else
            expr.parentNode = currentType;

         ParseUtil.initAndStartComponent(model);
         ParseUtil.initAndStartComponent(expr);

         if (expr.errorArgs == null && !model.hasErrors) {
            if (currentType != null) {
               CurrentObjectResult cor = selectCurrentObject(expr, currentType, false, true, true);
               if (cor.wizardStarted)
                  return;
               pushedCtx = pushedCtx || cor.pushedCtx;
               skipEval = cor.skipEval;
               pushed = cor.pushedObj;
               curObj = cor.curObj;
            }

            try {
               if (!skipEval) {
                  boolean evalPerformed = false;
                  if (performUpdatesToSystem(system, false) && !ignoreRemoteStatement(system, expr)) {
                     if (system.options.verboseExec)
                        system.info("Exec local statement: " + expr + " on: " + system.getProcessIdent());
                     ExecResult execResult = expr.exec(execContext);
                     if (execResult != ExecResult.Next)
                        System.err.println("*** Statement flow control at top-level not supported: " + expr);
                     evalPerformed = true;
                  }

                  if (syncSystems != null && currentType != null && currentLayer != null) {
                     for (LayeredSystem peerSys:syncSystems) {
                        if (performUpdatesToSystem(peerSys, evalPerformed)) {
                           Layer peerLayer = peerSys.getLayerByName(currentLayer.layerUniqueName);
                           BodyTypeDeclaration peerType = peerSys.getSrcTypeDeclaration(currentType.getFullTypeName(), peerLayer == null ? null : peerLayer.getNextLayer(), true);
                           if (peerType != null && !peerType.excluded) {
                              Statement peerExpr = expr.deepCopy(0, null);
                              peerExpr.parentNode = peerType;

                              if (!ignoreRemoteStatement(peerSys, peerExpr)) {
                                 if (system.options.verboseExec)
                                    system.info("Exec remote statement: " + peerExpr + " on: " + peerSys.getProcessIdent());
                                 Object remoteRes = peerSys.runtimeProcessor.invokeRemoteStatement(peerType, curObj, peerExpr, getTargetScopeContext());
                                 System.out.println(remoteRes);
                              }
                           }
                        }
                     }
                  }
               }
            }
            finally {
               if (pushed)
                  execContext.popCurrentObject();
               if (pushedCtx)
                  popCurrentScopeContext();
            }
         }
      }
      else
         System.err.println("*** Unrecognized type of statement in command interpreter: " + statement);


      updateCurrentModelStale();

      if (!model.hasErrors) {
         recordOutput(recordString, origIndent);
      }
   }

   /**
    * Is synchronization enabled for the current type, or if no current type do we have any synchronization going on.  Useful in test scripts so we can tell
    * whether there is a separate client version of an object or not
    */
   public boolean getSyncEnabled() {
      if (currentTypes.size() == 0) {
         List<LayeredSystem> syncSystems = system.getSyncSystems();
         return syncSystems != null && syncSystems.size() > 0;
      }
      else {
         BodyTypeDeclaration type = currentTypes.get(currentTypes.size()-1);
         return system.hasSyncPeerTypeDeclaration(type);
      }
   }

   SrcEntry findModelSrcFileForLayer(BodyTypeDeclaration type, String filePrefix, Layer layer) {
      String baseFileName = type.typeName;
      int ix;
      if ((ix = baseFileName.indexOf(".")) != -1)
         baseFileName = baseFileName.substring(0, ix);

      // TODO: maybe change this into a utility method and have it using the file processors?  I like that it's going right to the file
      // system in case the layered system is not up-to-date but otherwise, it seems redundant to what's in LayeredSystem already
      String[] suffixes = {
          TemplateLanguage.SCT_SUFFIX,
          JavaLanguage.SCJ_SUFFIX,
          HTMLLanguage.SC_HTML_SUFFIX,
          SCLanguage.STRATACODE_SUFFIX  // This should be last - we need to check for the existence of all suffixes, but only create ".sc" files from the command-line
      };

      int suffixIx = 0;
      File mFile;
      String relFile;
      String absFileName;
      do {
         // TODO: if we are in persistent layer, a modify def should load the previous modify def.  A replace
         // def should warn the user and then (re)move the previous model object in the layer.
         String baseFile = FileUtil.addExtension(baseFileName.replace(".", FileUtil.FILE_SEPARATOR), suffixes[suffixIx]);
         if (filePrefix != null)
            relFile = FileUtil.concat(filePrefix.replace(".", FileUtil.FILE_SEPARATOR), baseFile);
         else
            relFile = baseFile;

         absFileName = FileUtil.concat(layer.getLayerPathName(), relFile);
         mFile = new File(absFileName);
         suffixIx++;
      } while (suffixIx < suffixes.length && !mFile.canRead());

      return new SrcEntry(layer, absFileName, relFile);
   }

   class CurrentObjectResult {
      Object curObj;
      boolean wizardStarted;
      boolean pushedCtx;
      boolean pushedObj;
      boolean skipEval;
   }

   private CurrentObjectResult selectCurrentObject(Statement statement, BodyTypeDeclaration currentType, boolean doPushCtx, boolean needsInstance, boolean useWizard) {
      CurrentObjectResult cor = new CurrentObjectResult();
      if (autoObjectSelect && currentType != null) {
         if (doPushCtx)
            cor.pushedCtx = pushCurrentScopeContext();
         if (!hasCurrentObject() || (cor.curObj = getCurrentObject()) == null) {
            // When we need to choose a current object, our first choice is to use the ScopeContext to choose a "singleton" - i.e. one instance of the given type in that CurrentScopeContext.
            CurrentScopeContext ctx = CurrentScopeContext.getThreadScopeContext();
            if (ctx != null) {
               Object scopeInst = ctx.getSingletonForType(currentType);
               if (scopeInst != null) {
                  cor.curObj = scopeInst;
                  execContext.pushCurrentObject(cor.curObj);
                  cor.pushedObj = true;
                  return cor;
               }
            }

            // No singleton instance so now look in the dynamic type system for the set of instances available using liveDynamicTypes.
            Object res;
            List<InstanceWrapper> instances;
            int max = 10;
            try {
               system.acquireDynLock(false);
               instances = getInstancesOfType(currentType, max, false);
            }
            finally {
               system.releaseDynLock(false);
            }
            int numInsts = instances.size();
            if (numInsts == 0) {
               if (!consoleDisabled && needsInstance)
                  System.out.println("*** Warning: No instances for class: " + ModelUtil.getTypeName(currentType) + " skipping eval");
               cor.skipEval = true;
            }
            else if (numInsts == 1) {
               if (!consoleDisabled)
                  System.out.println("Class context - choosing singleton for type: " + ModelUtil.getTypeName(currentType));
               cor.curObj = instances.get(0).getInstance();
               execContext.pushCurrentObject(cor.curObj);
               cor.pushedObj = true;
            }
            else if (useWizard) {
               SelectObjectWizard.start(this, statement, instances);
               cor.wizardStarted = true;
               if (cor.pushedCtx) {
                  popCurrentScopeContext();
                  cor.pushedCtx = false;
               }
            }
         }
         else {
            execContext.pushCurrentObject(cor.curObj);
            cor.pushedObj = true;
         }
      }
      return cor;
   }

   /** In what context do we create this element.  When in edit mode, we want to use the current parent type
    * or model as the container for this entity.  But in script mode, we need to resolve properties in all of the
    * layers so we choose the most specific type.
    */
   private JavaSemanticNode getParentNode(BodyTypeDeclaration current) {
      if (edit) {
         if (current == null)
            return getModel();
         else
            return current;
      }
      else {
         if (current == null) {
            return getModel().resolve();
         }
         else
           return current.resolve(true);
      }
   }

   private static boolean ignoreRemoteStatement(LayeredSystem sys, Statement st) {
      JavaModel stModel = st.getJavaModel();
      if (stModel == null)
         return true;
      boolean oldDisable = stModel.disableTypeErrors;
      try {
         stModel.disableTypeErrors = true;
         ParseUtil.initAndStartComponent(st);
         if (st.errorArgs != null) // An error starting this statement means not to run it - the
            return true;
         return !st.execForRuntime(sys);
      }
      finally {
         stModel.disableTypeErrors = oldDisable;
      }
   }

   private boolean performUpdatesToSystem(LayeredSystem sys, boolean performedOnce) {
      // Only targeting the first runtime and this statement was already invoked once
      if (performedOnce && !targetAllRuntimes)
         return false;

      if (targetRuntime == null || runtimeNameMatches(sys, targetRuntime))
         return true;
      return false;
   }

   private static boolean runtimeNameMatches(LayeredSystem sys, String rtName) {
      return rtName.equals(sys.getProcessIdent()) || rtName.equals(sys.getRuntimeName());
   }

   private void recordOutput(String recordString, int indent) {
      if (recordString != null && recordOutputWriter != null) {
         recordOutputWriter.print(StringUtil.indent(indent));
         recordOutputWriter.println(recordString);
         recordOutputWriter.flush();
      }
   }

   private boolean hasCurrentObject() {
      return execContext.getCurrentObject() != null;
   }

   public Object getCurrentObjectWithDefault() {
      Object obj = getCurrentObject();
      if (obj == null && currentTypes.size() > startTypeIndex)
         obj = getDefaultCurrentObj(currentTypes.get(currentTypes.size()-1));
      return obj;
   }

   public String scopeContextName = "defaultCmdContext";
   public String targetScopeName = null;

   public CurrentScopeContext currentScopeCtx = null;
   /**
    * Before we run any object resolveName methods or expressions from the command, we may need to select a CurrentScopeContext that's
    * been registered by the framework, to select the specific context these commands operate in.  For example, when the command
    * line is enabled, each time we render a window, we switch to the current scopeContextName's context so we can control a specific
    * collection of state with the right locks to operate in that context.  The defaultCmdContext can be updated to point to the most
    * logical state based on the developer's current context (e.g. the last web-page loaded).
    */
   public boolean pushCurrentScopeContext() {
      if (currentScopeCtx != null)
         System.err.println("*** Nested pushCurrentScopeContext calls in interpreter!");
      CurrentScopeContext ctx = CurrentScopeContext.get(scopeContextName);
      if (ctx != null && ctx != CurrentScopeContext.getThreadScopeContext()) {
         CurrentScopeContext.pushCurrentScopeContext(ctx, true);
         currentScopeCtx = ctx;
         return true;
      }
      return false;
   }

   public void popCurrentScopeContext() {
      if (currentScopeCtx == null)
         System.err.println("*** Popping current scope context when it has not been pushed!");

      // We need to exec any jobs that were invoked with this current scope context before we release the locks.
      // this helps us flush out all data binding events before any waiting sync's are woken up.  Otherwise, they may get
      // only part of the change.
      execLaterJobs();

      CurrentScopeContext.popCurrentScopeContext(true);
      currentScopeCtx = null;
   }

   /**
    * Wait for the specified scopeContextName to be created, and for it's ready bit to be set, indicating it's done initializing.
    * Then use that scopeState for resolving names in subsequent script commands.
    */
   public CurrentScopeContext waitForReady(String scopeContextName, long timeout) {
      CurrentScopeContext ctx = CurrentScopeContext.waitForReady(scopeContextName, timeout);
      this.scopeContextName = scopeContextName;
      return ctx;
   }

   /** Selects the specific targeted ScopeContext (e.g. 'window') from the list in the CurrentScopeContext which is the list of available ScopeContexts */
   public ScopeContext getTargetScopeContext() {
      if (currentScopeCtx == null)
         return null;
      if (targetScopeName == null)
         currentScopeCtx.getEventScopeContext();
      return currentScopeCtx.getScopeContextByName(targetScopeName);
   }

   private void removeFromCurrentObject(JavaModel model, BodyTypeDeclaration parentType, Statement type) {
      if (parentType == null) {
         assert type instanceof TypeDeclaration;
         SrcEntry toRemove = model.getSrcFile();
         if (model.types.remove(type)) {
            if (toRemove != null)
              model.layer.removeSrcFile(toRemove);
            model.removeSrcFiles();
         }
      }
      else {
         if (parentType.body != null)
            parentType.body.remove(type);
      }
   }

   private BodyTypeDeclaration addToCurrentType(JavaModel model, BodyTypeDeclaration parentType, BodyTypeDeclaration type) {
      if (parentType == null) {
         TypeDeclaration newTD = (TypeDeclaration) type;
         newTD.liveDynType = true;
         model.addTypeDeclaration(newTD);
         // This optimization let's us avoid an extra dynamic stub for empty classes but in the interpreter we are likely
         // going to need that dynamic stub so just clear it to avoid a restart.
         newTD.clearDynamicNew();

         // If this is a ModifyDeclaration which could not find it's type, do not add it as it is bogus anyway
         if (type instanceof ModifyDeclaration && type.errorArgs != null)
            return null;

         // Need to generate the model the first time around so it knows its parse node.  It would be faster
         // execution-wise to associate the parselet with the CCakeModel and have it generate automatically when
         // it needs to.
         if (model.types.size() == 1)
            vlang.generate(model, false);

         // Make sure others can resolve this new type
         model.layeredSystem.addNewModel(model, null, execContext, null, false, true);

         return type;
      }
      else {
         ParentParseNode pn = (ParentParseNode) type.getParseNode();
         // Before we add an incomplete definition into the parse tree we need to swap in the right parselet.
         // otherwise, when we add a body it won't be able to find the body in the parselet's grammar.
         if (pn != null) {
            Parselet pl = pn.getParselet();
            if (pl.language instanceof CommandSCLanguage) {
               CommandSCLanguage ccl = ((CommandSCLanguage) pl.language);
               if (pl == ccl.startClassDeclaration)
                  pn.setParselet(ccl.classDeclaration);
               else if (pl == ccl.startModifyDeclaration)
                  pn.setParselet(ccl.modifyDeclaration);

               // It seems we could just repair the existing parsenode to reuse the existing parse but why bother when
               // it's only the first line of the statement anyway.
               if (pl.language.trackChanges)
                  type.regenerate(false);
               else {
                  type.setParseNodeValid(false);
                  // Clear these out because the slots won't make sense with the new parselet
                  pn.children = null;
               }
            }
         }
         UpdateInstanceInfo info = new UpdateInstanceInfo();
         BodyTypeDeclaration resType = parentType.updateInnerType(type, execContext, true, info, true);
         info.updateInstances(execContext);
         return resType;
      }
   }

   public void printDebugModel() {
      if (pendingModel == null)
         System.out.println("null");
      else
         System.out.println(getModel().toString());
   }

   /** Prints out the definition of the current type or model  */
   public void print() {
      JavaModel model = getModel();
      if (model.types != null && model.types.size() > 0)
         System.out.println(getModel().toLanguageString());
      else {
         if (model.packageDef != null)
            System.out.println(model.packageDef.toLanguageString());
         if (model.imports != null && model.imports.size() > 0)
            System.out.println(model.imports.toLanguageString());
      }
   }

   public void printChanges() {
      for (JavaModel model:changedModels) {
         System.out.println("------: " + model.getSrcFile() + ":");
         System.out.println(model.toLanguageString());
      }
   }

   public void autoRefresh() {
      if (!statementProcessor.disableRefresh)
         refresh();
   }

   public void updateLayerState() {
      super.updateLayerState();
      updateCurrentLayer();
      for (Layer layer:system.specifiedLayers) {
         String pref = layer.packagePrefix;
         if (layer.exportPackage && pref != null) {
            if (!importPackages.contains(pref))
               importPackages.add(pref);
         }
      }
   }

   public void updateCurrentLayer() {
      if (pendingModel != null && pendingModel.layer != currentLayer) {
         clearPendingModel();
         initPendingModel();
      }
   }

   public void setCurrentType(BodyTypeDeclaration newType) {
      if (currentTypes.size() > 0 && newType == currentTypes.get(currentTypes.size() - 1))
         return;
      super.setCurrentType(newType);
      if (pendingModel != null) {
         pendingModel.setCommandInterpreter(this);

         TypeDeclaration modelType = pendingModel.getModelTypeDeclaration();
         if (modelType != null) {
            ArrayList<IEditorSession> sessions = editSessions.get(modelType.getFullTypeName());
            if (sessions != null && sessions.size() > 0) {
               edit();
            }
         }
         // else - should we be adding the modify-types required for this type in the pendingModel?
      }
      CurrentObjectResult cor = selectCurrentObject(null, newType, false, false, false);
   }

   public void edit() {
      JavaModel model = getModel();
      if (modelChanged(model))
         save(); 
      if (model == null || model.getSrcFile() == null)
         System.out.println("No current type to edit");
      else {
         ModelEditorLauncher mel = new ModelEditorLauncher(this, pendingModel);
         mel.start();
      }
   }

   /* TODO: add this back in again?
   public void gui() {
      String[] editorLayers = {"gui/editor"};
      system.addLayers(editorLayers, false, execContext);
   }
   */

   public void createLayer() {
      CreateLayerWizard.start(this, false);
   }

   public void addLayer() {
      AddLayerWizard.start(this); 
   }

   public void askCreateLayer() {
      CreateLayerWizard.start(this, true);
   }

   public void printBinding(String propName) {
      Object curObj = getCurrentObject();
      // TODO: should we support this for classes or even layers?
      if (curObj == null)
         curObj = execContext.getCurrentStaticType();
      if (curObj == null)
         System.err.println("*** No current object for print binding");
      else
         System.out.println(Bind.getBinding(curObj, propName));
   }

   public void printBindings() {
      Object curObj = getCurrentObject();
      if (curObj == null)
         curObj = execContext.getCurrentStaticType();
      if (curObj == null)
         System.err.println("*** No current object for print bindings");
      else
         System.out.println(ModelUtil.arrayToString(Bind.getBindings(curObj)));
   }

   public void printListeners() {
      Object curObj = getCurrentObject();
      if (curObj == null)
         curObj = execContext.getCurrentStaticType();
      if (curObj == null)
         System.err.println("*** No current object for print bindings");
      else {
         Bind.printBindings(curObj);
      }
   }

   public void printLayers() {
      System.out.println(LayerUtil.layersToString(system.layers));
   }

   public void printInactiveLayers() {
      System.out.println(LayerUtil.layersToString(system.inactiveLayers));
   }

   /** General debug method to print out all information about a definition by its name */
   public void print(String def) {
      Object methObj = null;
      Object varObj = null;
      Object typeObj = null;
      JavaModel model = getModel();
      BodyTypeDeclaration curType = null;

      // Note: this is a bit wrong since we could have a method and member with the same name or a type
      // with the same name and this allows no way to override that.
      if (currentTypes.size() > 0) {
         curType = currentTypes.get(currentTypes.size()-1);
         methObj = curType.definesMethod(def, null, null, null, false, false, null, null);
         varObj = curType.definesMember(def, JavaSemanticNode.MemberType.AllSet, null, null);
         typeObj = curType.findType(def);
         if (typeObj == null)
            typeObj = ModelUtil.getInnerType(curType, def, null);
      }

      Object curObj = getCurrentObject();
      String initStr = null;
      if (typeObj == null)
         typeObj = model.getTypeDeclaration(def);
      if (typeObj == null && varObj == null && methObj == null)
         System.out.println("No type, method, or member named: " + def + (curType == null ? " for context: " + model.getPackagePrefix() : " for " + ModelUtil.getTypeName(curType)));
      if (varObj instanceof JavaSemanticNode) {
         if (varObj instanceof VariableDefinition) {
            VariableDefinition varDef = (VariableDefinition) varObj;
            if (curObj != null) {
               DestinationListener dl = Bind.getBinding(curObj, varDef.variableName);
               if (dl != null) {
                  System.out.println("Bound: " + dl.toString());
                  return;
               }
               else
                  initStr = " = " + String.valueOf(TypeUtil.getPropertyValue(curObj, varDef.variableName));
            }
            else if (curType != null) {
               Object initializer = curType.getPropertyInitializer(varDef.variableName);
               initStr = " = " + (initializer == null ? " null" :
                            (initializer instanceof JavaSemanticNode ? 
                                 ((JavaSemanticNode) initializer).toLanguageString() :
                                 initializer.toString()));
            }
         }
         System.out.println("Source: " + ((JavaSemanticNode) varObj).toLanguageString().trim() + (initStr == null ? "" : initStr));
      }
      else if (ModelUtil.isProperty(varObj)) {
         if (curObj != null) {
            DestinationListener dl = Bind.getBinding(curObj, ModelUtil.getPropertyName(varObj));
            if (dl != null)
               System.out.println("Bound: " + dl.toString());
            else
               System.out.println("Compiled property: " + varObj + " = " + TypeUtil.getPropertyValue(curObj, def));
         }
         else
            System.out.println("Compiled property: " + varObj);
      }
      if (methObj != null) {
         if (methObj instanceof AbstractMethodDefinition)
            System.out.println("Source method: " + ((AbstractMethodDefinition)methObj).toDefinitionString());
         else
            System.out.println("Compiled method: " + methObj);
      }
      if (typeObj != null) {
         if (typeObj instanceof BodyTypeDeclaration)
            System.out.println("Source type: " + ((BodyTypeDeclaration)typeObj).toDeclarationString());
         else
            System.out.println("Compiled type: " + typeObj);
      }
   }

   public void list() {
      if (currentTypes.size() == startTypeIndex) {
         listLayers();
      }
      else {
         listProps();
         listTypes();
         listMethods();
      }
   }

   public void listLayers() {
      printLayers();
   }

   public void listProps() {
      if (currentTypes.size() == 0)
         System.out.println("No current type to list properties");
      else {
         Object[] props = ModelUtil.getProperties(currentTypes.get(currentTypes.size()-1), null);
         if (props != null)
            System.out.print(ModelUtil.arrayToString(props));
      }
   }

   public void listMethods() {
      if (currentTypes.size() == 0)
         System.out.println("No current type to list properties");
      else {
         Object[] meths = ModelUtil.getAllMethods(currentTypes.get(currentTypes.size()-1), null, false, false, false);
         if (meths != null)
            System.out.print(ModelUtil.arrayToString(meths));
      }
   }

   public void listTypes() {
      if (currentTypes.size() == 0) {
         Set<String> filesInPackage = system.getFilesInPackage(getPrefix());
         if (filesInPackage != null) {
            ArrayList<String> toDisp = new ArrayList<String>();
            for (String f:filesInPackage) {
               toDisp.add(f.replace("$", "."));
            }
            System.out.print(ModelUtil.arrayToString(toDisp.toArray()));
         }
         else
            System.out.println("No global types with prefix: " + getPrefix());
      }
      else {
         Object[] types = ModelUtil.getAllInnerTypes(currentTypes.get(currentTypes.size()-1), null, false);
         if (types != null)
            System.out.print(ModelUtil.arrayToString(types));
      }
   }

   public void listObjects() {
      if (currentTypes.size() == 0) {
         String prefix = getPrefix();
         Set<String> filesInPackage = system.getFilesInPackage(prefix);
         if (filesInPackage != null) {
            ArrayList<String> toDisp = new ArrayList<String>();
            for (String f:filesInPackage) {
               f = f.replace("$", ".");
               String fullType = CTypeUtil.prefixPath(prefix, f);
               Object type = system.getTypeDeclaration(fullType);
               if (type != null && ModelUtil.isObjectType(type))
                  toDisp.add(f);
            }
            System.out.print(ModelUtil.arrayToString(toDisp.toArray()));
         }
         else
            System.out.println("No global types with prefix: " + getPrefix());
      }
      else {
         Object[] types = ModelUtil.getAllInnerTypes(currentTypes.get(currentTypes.size()-1), null, false);
         if (types != null) {
            ArrayList<Object> objs = new ArrayList<Object>();
            for (Object type:types) {
               if (ModelUtil.isObjectType(type))
                  objs.add(type);
            }
            System.out.print(ModelUtil.arrayToString(objs.toArray()));
         }
      }
   }

   public void setVerbose(boolean v) {
      system.options.verbose = v;
      Bind.trace = v;
      if (v)
         Bind.info = true;
   }
   public boolean getVerbose() {
      return system.options.verbose;
   }

   public void setInfo(boolean v) {
      system.options.info = v;
   }
   public boolean getInfo() {
      return system.options.info;
   }

   public String getStaleInfo() {
      return system.getStaleInfo();
   }

   public void waitForUI(int millis) {
      sleep(millis);
   }

   public void sleep(int millis) {
      try {
         Thread.sleep(millis);
      }
      catch (InterruptedException exc) {}
   }

   public PrintWriter recordOutputWriter;
   private String recordFile;

   public void record(String fileName) {
      if (recordOutputWriter != null) {
         recordOutputWriter.close();
         recordOutputWriter = null;
         System.out.println("Recording stopped: " + recordFile);
         recordFile = null;
      }
      if (fileName != null) {
         File file = new File(fileName);
         if (file.exists())
            System.out.println("Appending to: " + fileName);
         else
            System.out.println("Recording to new file: " + fileName);
         try {
            recordOutputWriter = new PrintWriter(new BufferedWriter(new FileWriter(file, true)));
         }
         catch (IOException exc) {
            system.anyErrors = true;
            System.out.println("Unable to write to record file: " + fileName + " error: " + exc);
            recordOutputWriter = null;
         }
         recordFile = fileName;
      }
      else
         System.err.println("*** Missing file name to record method");
   }

   /** Directory to run commands - defaults to the current directory */
   public String execDir = null;

   /** Run a system command synchronously - returns the exit status of the command */
   public int exec(String argStr) {
      return (int) execCommand(argStr, false);
   }

   /**
    * Run a system command asynchronously.  Returns an AsyncProcessHandle object which has a "process" field you can use
    * to control the exec'd process - i.e. call waitFor or destroy.
    */
   public AsyncProcessHandle execAsync(String argStr) {
      return (AsyncProcessHandle) execCommand(argStr, true);
   }

   private Object execCommand(String argStr, boolean async) {
      String[] args = StringUtil.splitQuoted(argStr);
      String outputFile = null;
      String inputFile = null;
      for (int i = 0; i < args.length; i++) {
         String arg = args[i];
         if ((arg.equals(">") || arg.equals("<")) && i < args.length - 1) {
            if (arg.equals(">"))
               outputFile = args[i+1];
            else
               inputFile = args[i+1];
            ArrayList<String> argsList = new ArrayList<String>(Arrays.asList(args));
            argsList.remove(i);
            argsList.remove(i);
            args = argsList.toArray(new String[argsList.size()]);
            i--;
         }
      }
      ProcessBuilder pb = new ProcessBuilder(args);
      if (async) {
         return LayerUtil.execAsync(pb, execDir, inputFile, outputFile);
      }
      else {
         return LayerUtil.execCommand(pb, execDir, inputFile, outputFile);
      }
   }


   public void quit() {
      System.exit(system.anyErrors ? 1 : 0);
   }

   public String getEditLayerName() {
      return currentLayer.getLayerName();
   }

   public void setEditLayerName(String name) {
      Layer newLayer;
      if ((newLayer = system.getLayerByPath(name, false)) != null) {
         setCurrentLayer(newLayer);
      }
      else {
         System.err.println("No layer named: " + name + " in: ");
         listLayers();
      }
   }

   /** Move down the layer stack */
   public void down() {
      if (currentTypes.size() == startTypeIndex) {
         int pos;
         if (currentLayer == null) {
            System.err.println("No current layer");
         }
         else if ((pos = currentLayer.getLayerPosition()) == 0)
            System.err.println("At lowest level layer");
         else
            setCurrentLayer(currentLayer.getPreviousLayer());
      }
      else {
         BodyTypeDeclaration current = currentTypes.get(currentTypes.size()-1);
         BodyTypeDeclaration downType = current.getModifiedType();
         if (downType == null)
            System.err.println("No layers below type: " + current + " in layer: " + current.getLayer());
         else {
            // First change the layer, otherwise when editor model rebuilds it will pick the version of this type in the old layer
            setCurrentType(downType);
         }
      }
   }

   /** Move up the layer stack */
   public void up() {
      if (currentTypes.size() == startTypeIndex) {
         int pos;
         if (currentLayer == null) {
            System.err.println("No current layer");
         }
         else if (currentLayer == system.lastLayer)
            System.err.println("Already at last layer");
         else
            setCurrentLayer(currentLayer.getNextLayer());
      }
      else {
         BodyTypeDeclaration current = currentTypes.get(currentTypes.size()-1);
         BodyTypeDeclaration upType = current.getModifiedByType();
         if (upType == null)
            System.err.println("No layers that modify type: " + current + " in layer: " + current.getLayer());
         else {
            setCurrentType(upType);
         }
      }
   }

   public int getTermWidth() {
      return 100;
   }

   public void initReadThread() {
      DynUtil.setThreadScheduler(this);
   }

   protected ArrayList<ScheduledJob> toRunLater = new ArrayList<ScheduledJob>();

   public void invokeLater(Runnable r, int priority) {
      ScheduledJob job = new ScheduledJob();
      job.priority = priority;
      job.toInvoke = r;
      // TODO: performance check if it's different?  Or maybe check on the other end?
      job.curScopeCtx = CurrentScopeContext.getThreadScopeContext();
      ScheduledJob.addToJobList(toRunLater, job);
   }

   public void execLaterJobs() {
      execLaterJobs(IScheduler.NO_MIN, IScheduler.NO_MAX);
   }

   public void execLaterJobs(int minPriority, int maxPriority) {
      boolean pushed = false;
      if (CurrentScopeContext.getThreadScopeContext() == null)
         pushed = pushCurrentScopeContext(); // TODO: maybe we should just set this and leave it in place rather than popping in processStatement?  We do need to update it each time in case the scope we are using has been changed
      try {
         ScheduledJob.runJobList(toRunLater, minPriority, maxPriority);
      }
      finally {
         if (pushed)
            popCurrentScopeContext();
      }
   }

   public boolean hasPendingJobs() {
      return toRunLater.size() > 0;
   }

   public void addDialogAnswer(String dialogName, Object value) {
      DialogManager.addDialogAnswer(dialogName, value);
   }

   public abstract boolean readParseLoop();

   abstract void pushCurrentInput(boolean pushLayer);

   abstract void popCurrentInput();

   public void loadScript(String baseDirName, String pathName, Layer includeLayer) {
      if (!pathName.startsWith(".") && !FileUtil.isAbsolutePath(pathName)) {
         this.inputRelName = pathName;
         pathName = FileUtil.concat(baseDirName, pathName);
      }
      else
         this.inputRelName = null;
      this.inputFileName = pathName;
      this.includeLayer = includeLayer;
      resetInput();
   }

   public boolean exists(String includeName) {
      String pathName = resolvePathName(includeName);
      return new File(pathName).canRead();
   }

   private String resolvePathName(String includeName) {
      if (!FileUtil.isAbsolutePath(includeName)) {
         return FileUtil.concat(system.buildDir, includeName);
      }
      return includeName;
   }

   /** Pushes an include script onto the top of the stack of files to be processed and returns immediately - before the script has run. */
   public void pushIncludeScript(String baseDirName, String includeName, Layer includeLayer) {
      String relName = null;
      if (!FileUtil.isAbsolutePath(includeName)) {
         relName = includeName;
         String fileName = FileUtil.concat(baseDirName, includeName);

         if (!new File(fileName).canRead()) {
            throw new IllegalArgumentException("No script to include: " + fileName);
         }
         includeName = fileName;
      }
      pushCurrentInput(false);
      try {
         this.inputFileName = includeName;
         this.inputRelName = relName;
         this.includeLayer = includeLayer; // Specifies the source layer where the test script came from (or null if we should use the buildLayer)
         resetInput();
      }
      catch (RuntimeException exc) {
         popCurrentInput();
         throw exc;
      }
   }

   /** Includes the script and waits for the script to complete.  Use this from a script to synchronously include another */
   public void include(String includeName) {
      try {
         SrcEntry includeSrcEnt = system.buildLayer.getLayerFileFromRelName(includeName, true, true);
         if (system.options.verbose) {
            system.verbose("Script include: " + includeName + " from layer: " + (includeSrcEnt == null ? "null" : includeSrcEnt.layer));
         }
         pushIncludeScript(system.buildDir, includeName, includeSrcEnt == null ? null : includeSrcEnt.layer);
         this.returnOnInputChange = true;
         if (!readParseLoop())
            pendingInput = new StringBuilder();

         if (system.options.verbose) {
            system.verbose("Script end: " + includeName + " from layer: " + (includeSrcEnt == null ? "null" : includeSrcEnt.layer));
         }
      }
      finally {
         popCurrentInput();
      }
   }

   // for the test scripts, if we are running as script "testScript.scr" - let's find the previous testScript.scr and include it as above
   public void includeSuper() {
      if (inputRelName != null) {
         Layer curLayer = includeLayer == null ? system.buildLayer : includeLayer;
         if (curLayer == null)
            throw new IllegalArgumentException("*** No current layer for includeSuper()");
         // Using the layer position for the runtime view - TODO: should this use byPosition = false?
         SrcEntry srcEnt = curLayer.getBaseLayerFileFromRelName(inputRelName, true);
         if (srcEnt != null) {
            // Setting the currentLayer here because the includedScript has to expect the context of the layer in which it's defined, since it can be used from multiple places.
            // The normal include always picks a file that's defined in the context of the buildLayer.
            pushCurrentInput(true);
            try {
               this.inputFileName = srcEnt.absFileName;
               this.inputRelName = srcEnt.relFileName;
               includeLayer = srcEnt.layer;
               currentLayer = srcEnt.layer;
               resetInput();
               this.returnOnInputChange = true;
               if (!readParseLoop())
                  pendingInput = new StringBuilder();
            }
            finally {
               popCurrentInput();
            }
         }
         else
            throw new IllegalArgumentException("includeSuper - no super script for: " + inputFileName + " relative name: " + inputRelName + " from layer: " + curLayer);
      }
   }


   private List<LayeredSystem> getSyncSystems() {
      return system.getSyncSystems();
   }

   abstract void resetInput();

   public String getInputFileName() {
      if (inputFileName == null)
         return "<stdin>";
      else
         return inputFileName;
   }

   // ---- Begin the DynObject redirection boilerplate
   public Object getProperty(String propName, boolean getField) {
      return dynObj.getPropertyFromWrapper(this, propName, getField);
   }
   public Object getProperty(int propIndex, boolean getField) {
      return dynObj.getPropertyFromWrapper(this, propIndex, getField);
   }
   public <_TPROP> _TPROP getTypedProperty(String propName, Class<_TPROP> propType) {
      return (_TPROP) dynObj.getPropertyFromWrapper(this, propName, false);
   }
   public void setProperty(String propName, Object value, boolean setField) {
      dynObj.setPropertyFromWrapper(this, propName, value, setField);

   }
   public void setProperty(int propIndex, Object value, boolean setField) {
      dynObj.setProperty(propIndex, value, setField);
   }
   public Object invoke(String methodName, String paramSig, Object... args) {
      return dynObj.invokeFromWrapper(this, methodName, paramSig, args);
   }
   public Object invoke(int methodIndex, Object... args) {
      return dynObj.invokeFromWrapper(this, methodIndex, args);
   }
   public Object getDynType() {
      return dynObj.getDynType();
   }
   public void setDynType(Object type) {
      dynObj.setTypeFromWrapper(this, type);
   }
   public void addProperty(Object propType, String propName, Object initValue) {
      dynObj.addProperty(propType, propName, initValue);
   }
   public boolean hasDynObject() {
      return dynObj.hasDynObject();
   }
   // ---- End DynObject redirection boilerplate
}
