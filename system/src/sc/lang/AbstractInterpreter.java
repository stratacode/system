/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.bind.Bind;
import sc.bind.DestinationListener;
import sc.dyn.DynUtil;
import sc.dyn.IScheduler;
import sc.lang.sc.PropertyAssignment;
import sc.lang.sc.EndTypeDeclaration;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.java.Package;
import sc.lang.java.*;
import sc.layer.LayerUtil;
import sc.layer.SrcEntry;
import sc.layer.LayeredSystem;
import sc.parser.*;
import sc.type.CTypeUtil;
import sc.type.TypeUtil;
import sc.util.DialogManager;
import sc.util.FileUtil;
import sc.util.StringUtil;
import sc.layer.Layer;

import java.io.*;
import java.util.*;
import sc.dyn.ScheduledJob;

public abstract class AbstractInterpreter extends EditorContext implements IScheduler {
   static SCLanguage vlang = SCLanguage.INSTANCE;

   StringBuffer pendingInput = new StringBuffer();

   public static String USAGE =  "Command Line Interface Help:\n\n" +
           "In the command line editor, you can examine and modify your application like an ordinarily REPL (read-eval-print-loop). Most REPLs start with commands but for the StrataCode REPL imagine that you are editing a Java file from the command line.  You do have commands but those are implemented by calling methods on a special 'cmd' object.  Because you already know Java, you can learn the StrataCode REPL quickly, without having to learn the details of each command.\n" +
           "You have one context in which you navigate layers, set your package, define imports, and find or create your current Java class or SC object.  Once you have a current class or instance, or method you need to complete that operation to return to the previous context.\n\n" +
           "No Current Type:\n" +
           "   layerName {                Change context to layerName.  \n" +
           "   typeName {                 Change context to the typeName specified.  If the type is defined in this layer, you are at this point editing that file from the command line incrementally.  Be careful!  If there is no definition of that type in this layer, a 'modify defineition' is automatically created and your changes are recorded in the new layer.\n" +
           "   packageName {              Set current context to the package name specified.\n" +
           "   class newClassName {\n" +
           "   object newInstanceName {   Define/replace a new top-level class or object with the current package in the current layer.  If this class is defined in a previous layer, you are replacing that definition.  Be careful!\n" +
           "   package newPackageName;    Change the current package\n" +
           "   import newImportName;      Add an import.  level.  \n\n" +
           "With a Current Type:\n" +
           "   foo = 3;                  Set a property\n" +
           "   foo;                      Eval an expression\n" +
           "   int foo; void foo() {}    Add/replace fields or methods\n\n" +
           "At any time:\n" +
           "   }                         To exit a type and return to the previous type or to the top-level with no type.\n" +
           "   cmd.save();               To save your changes\n" +
           "   cmd.list();               Display current objects, classes, field, methods etc. in the current context\n" +
           "   <TAB>                     Comamnd line completion with terminals supported by JLine\n" +
           "\n\n" +
           "The Prompt                   Displays your current context - layer, package, and where you are in the layer stack using # before or after the layer name:\n\nExamples:\n" +
           "  (example.unitConverter.model:sc.example.unitConverter##) ->\n" +
           "   ^ current layer             ^ current package       ^^ 2 layers below this layer in the stack\n\n" +
           "  (doc.core:sc.doc#(15)#) ->\n" +
           "                    ^ 15 layers below\n\n" +

           "The cmd object is used to control the interpreter:\n" +
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
           "   cmd.<TAB>          - list the remaining properties and methods\n\n";

   /** For commands like createLayer, we add a wizard which processes input temporarily */
   CommandWizard currentWizard = null;

   /** Time to pause in between commands - useful for playing back scripts */
   public int pauseTime = 0;

   public boolean autoObjectSelect = true;

   public AbstractInterpreter(LayeredSystem sys) {
      super(sys);
      cmdlang.initialize();

      // TODO: make this an object which does a thread-local lookup to find the right interpreter if we need more than one or to keep this out of the global name space when the interpreter is enabled
      sys.addGlobalObject("cmd", this);
   }

   private final static int MAX_PREFIX = 4;

   public abstract String readLine(String nextPrompt);

   public void processCommand(String nextLine) {
      Object result = null;
      if (nextLine.trim().length() != 0) {
         pendingInput.append(nextLine);

         result = parseCommand(pendingInput.toString(), getParselet());
      }
      if (result != null) {
         try {
            statementProcessor.processStatement(this, result);
         }
         catch (Throwable exc) {
            System.err.println(exc);
            system.anyErrors = true;
            if (system.options.verbose)
               exc.printStackTrace();
         }
      }
   }

   protected String prompt() {
      system.acquireDynLock(false); // TODO: should be read-only lock - why does that not work?  so that we wait till the main thread has finished.  This both ensures the prompt is the last message displayed and that main processing completes before we start reading the next command.
      try {
         if (currentWizard != null)
            return currentWizard.prompt();

         JavaModel model = pendingModel;
         String hdr = system.staleCompiledModel ? "*" : "";

         if (currentTypes.size() == 0) {
            String prefix = currentLayer == null ? "" : (currentLayer.getLayerName() + (getPrefix() == null ? "" : ":" + getPrefix()));
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
                  upPrefix = new StringBuilder("#(" + upPrefix.length() + ")#");
               if (downPrefix.length() > MAX_PREFIX)
                  downPrefix = new StringBuilder("#(" + downPrefix.length() + ")#");
               prefix = "(" + upPrefix + prefix + downPrefix + ") ";
            }
            return hdr + prefix + "-> ";
         }
         else {
            BodyTypeDeclaration type = currentTypes.get(currentTypes.size()-1);
            StringBuilder upPrefix = new StringBuilder(), downPrefix = new StringBuilder();
            BodyTypeDeclaration otherType = type;
            while ((otherType = otherType.getModifiedByType()) != null) {
               upPrefix.append("#");
            }
            otherType = type;
            while ((otherType = otherType.getModifiedType()) != null) {
               downPrefix.append("#");
            }
            return hdr + "(" + upPrefix + currentLayer.getLayerName() + downPrefix + ":" + (hasCurrentObject() ? "object " : "class ") + ModelUtil.getTypeName(currentTypes.get(currentTypes.size()-1)) + ") -> ";
         }
      }
      finally {
         system.releaseDynLock(false);
      }
   }

   protected Parselet getParselet() {
      if (currentTypes.size() == 0)
         return cmdlang.topLevelCommands;
      else
         return cmdlang.typeCommands;
   }

   protected Object parseCommand(String command, Parselet start) {
      if (currentWizard != null) {
         currentWizard.parseCommand(command);
         return null;
      }

      // Skip empty lines, though make sure somewhitespace gets in there
      if (command.trim().length() == 0) {
         return null;
      }

      if (command.equals("help") || command.equals("?")) {
         System.out.println(StringUtil.insertLinebreaks(USAGE, 80));
         pendingInput = new StringBuffer();
         return null;
      }

      if (!start.initialized) {
         ParseUtil.initAndStartComponent(start);
      }
      Parser p = new Parser(cmdlang, new StringReader(command));
      Object parseTree = p.parseStart(start);
      if (parseTree instanceof ParseError) {
         ParseError err = (ParseError) parseTree;
         // We have to both hit EOF and leave the current index at EOF
         if (!p.eof || p.getInputChar(p.currentErrorEndIndex) != '\0') {
            pendingInput = new StringBuffer();
            System.err.println(err.errorStringWithLineNumbers(command));
         }
         else
            pendingInput.append("\n");
         return null;
      }
      // The parser did not consume all of the input - ordinarily an error but for the command interpreter,
      // we'll just save the extra stuff for the next go around.
      else if (!p.eof || p.peekInputChar(0) != '\0') {
         int consumed = p.getCurrentIndex();
         pendingInput = new StringBuffer(pendingInput.toString().substring(consumed));
      }
      // Parsed it all - zero out the pending input
      else
         pendingInput = new StringBuffer();
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
         BodyTypeDeclaration parentType = getCurrentType();
         Layer layer = currentLayer;

         if (layer == null) {
            System.err.println("Cannot define a type when there is no layer.  Use cmd.createLayer()");
            return;
         }

         boolean addToType = true;
         SrcEntry newSrcEnt = null;
         if (parentType == null) {
            // If you do pkgName with an open brace we'll just prepend this onto the path.  Since pkgNames and class names do not
            // overlap in the namespace, this gives you one syntax for navigating the type hierarchy of packages,
            // classes and inner classes.   Needs to be before the file check because the layer def's .sc file can
            // overlap with the package name
            if (statement instanceof ModifyDeclaration) {
               String typeName = ((ModifyDeclaration) statement).typeName;
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

            String filePrefix = path;
            
            String baseFileName = type.typeName;
            int ix;
            if ((ix = baseFileName.indexOf(".")) != -1)
               baseFileName = baseFileName.substring(0, ix);

            // TODO: if we are in persistent layer, a modify def should load the previous modify def.  A replace
            // def should warn the user and then (re)move the previous model object in the layer.
            String baseFile = baseFileName.replace(".", FileUtil.FILE_SEPARATOR) + SCLanguage.STRATACODE_SUFFIX;
            String relFile;
            if (filePrefix != null)
               relFile = FileUtil.concat(filePrefix.replace(".", FileUtil.FILE_SEPARATOR), baseFile);
            else
               relFile = baseFile;

            String absFileName = FileUtil.concat(layer.getLayerPathName(), relFile);
            File mFile = new File(absFileName);

            // If either there's a file in this layer or we may have unsaved changed to a file in this layer
            if (mFile.canRead() || isChangedModel(layer, relFile)) {

               boolean restart = model.isStarted();
               model.removeSrcFiles();
               model.addSrcFile(new SrcEntry(layer, absFileName, relFile));
               if (restart)
                  ParseUtil.restartComponent(model);

               if (!model.isStarted()) // make sure the model
                  ParseUtil.initAndStartComponent(model);
               
               TypeDeclaration currentDef = system.getSrcTypeDeclaration(CTypeUtil.prefixPath(model.getPackagePrefix(), type.typeName), currentLayer.getNextLayer(), true);
               if (currentDef == null || currentDef.getLayer() != layer) {
                  system.refreshRuntimes(true);
                  currentDef = system.getSrcTypeDeclaration(CTypeUtil.prefixPath(model.getPackagePrefix(), type.typeName), currentLayer.getNextLayer(), true);
                  if (currentDef == null) {
                     System.err.println("No type (mismatching case?): " + type.typeName);
                     return;
                  }
               }

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
                     addChangedModel(currentDef.getJavaModel());
               }
               else {
                  System.err.println("*** Definition of type: " + type.typeName + " already exists in layer: " + layer);
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
                     addChangedModel(pendingModel);
                  }
               }
               if (oldPkg != null) {
                  pendingModel.setProperty("packageDef", oldPkg);
                  addChangedModel(pendingModel);
               }
            }
            else {
               model.removeSrcFiles();
               newSrcEnt = new SrcEntry(layer, absFileName, relFile);
               model.addSrcFile(newSrcEnt);
               ParseUtil.restartComponent(model);
               addChangedModel(model);
            }
         }

         String origTypeName = type.typeName;

         if (addToType) {
            BodyTypeDeclaration origType = type;
            type = addToCurrentType(model, parentType, type);
            if (type == null) {
               removeFromCurrentObject(model, parentType, origType);
               return;
            }
         }
         // Need to do this after we've added the type to the file system.  Otherwise, we try to lookup the type
         // before it is on the file system and that leads to an error trying to find the type
         if (newSrcEnt != null)
            layer.addNewSrcFile(newSrcEnt, true);

         // Must be done after adding so we get the full type name
         String typeName = ModelUtil.getTypeName(type);
         boolean hasCurrentObject = hasCurrentObject();
         boolean checkCurrentObject = parentType == null || hasCurrentObject;

         /*
         if (layer.packagePrefix != null && layer.packagePrefix.length() > 0) {
            if (!typeName.startsWith(layer.packagePrefix))
               System.err.println("*** Error - typeName does not start with package prefix:");
            else
               typeName = typeName.substring(layer.packagePrefix.length()+1);
         }
         */

         currentTypes.add(type);

         // Modify declarations need to start things up so their type is obtained correctly.
         //if (type instanceof ModifyDeclaration) {
         //   ParseUtil.initAndStartComponent(model);
         //}

         DeclarationType declType = type.getDeclarationType();

         Object parentObj = getCurrentObjectWithDefault();

         if (type.getDefinesCurrentObject()) {
            boolean pushed = false;
            Object obj = null;
            try {
               execContext.pushStaticFrame(type);
               // Using origTypeName here - grabbed before we do the "a.b" to a { b" conversion.   type.typeName now will just be "b".
               // Only do this if the current object is the parent object - not if it's already been resolved from the selectedInstances array
               obj = parentObj == null ? (checkCurrentObject ? system.resolveName(typeName, true) : null) : (hasCurrentObject ? DynUtil.getPropertyPath(parentObj, origTypeName) : null);
               execContext.pushCurrentObject(obj);
               pushed = true;
            }
            catch (RuntimeException exc) {
               system.anyErrors = true;
               System.err.println("*** Error trying to resolve name: " + typeName + ": " + exc);
               exc.printStackTrace();
            }
            finally {
               // Avoid stack problems
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
      }
      else if (statement instanceof PropertyAssignment) {
         Object curObj;
         boolean pushed = false;
         boolean noInstance = false;
         PropertyAssignment pa = (PropertyAssignment) statement;
         if (!pa.isStatic() && autoObjectSelect) {
            if (!hasCurrentObject() || (curObj = getCurrentObject()) == null) {
               Object res = SelectObjectWizard.start(this, statement);
               if (res == SelectObjectWizard.NO_INSTANCES_SENTINEL)
                  noInstance = true; // TODO: see below - used to set skipEval = true here
               else if (res == null)
                  return;
               else {
                  curObj = res;
                  execContext.pushCurrentObject(curObj);
                  pushed = true;
               }
            }
            else {
               execContext.pushCurrentObject(curObj);
               pushed = true;
            }
         }

         try {
            BodyTypeDeclaration current = currentTypes.get(currentTypes.size()-1);
            PropertyAssignment assign = (PropertyAssignment) statement;

            // TODO: if noInstance = true and assign.assignedProperty is an instance property we should not try to update the instances

            JavaSemanticNode newDefinition = current.updateProperty(assign, execContext, !skipEval, null);
            addChangedModel(pendingModel);

            if (model.hasErrors() && newDefinition != assign)
               removeFromCurrentObject(model, current, assign);
         }
         finally {
            if (pushed)
               execContext.popCurrentObject();
         }
      }
      else if (statement instanceof TypedDefinition) {
         BodyTypeDeclaration current = currentTypes.get(currentTypes.size()-1);

         // We do not generate unless the component is started
         if (!model.isStarted())
            ParseUtil.initAndStartComponent(model);

         current.updateBodyStatement((TypedDefinition)statement, execContext, true, null);
         addChangedModel(pendingModel);
      }
      else if (statement instanceof EndTypeDeclaration) {
         if (currentTypes.size() == 0) {
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
            if (currentTypes.size() == 0)
               clearPendingModel();
            origIndent--;
            markCurrentTypeChanged();
         }
      }
      else if (statement instanceof Expression) {
         boolean pushed = false;
         Object curObj;

         Expression expr = (Expression) statement;
         expr.parentNode = currentTypes.size() == 0 ? getModel() : currentTypes.get(currentTypes.size()-1);

         ParseUtil.initAndStartComponent(model);
         ParseUtil.initAndStartComponent(expr);

         if (expr.errorArgs == null && !model.hasErrors) {
            if (!expr.isStaticTarget() && autoObjectSelect) {
               if (!hasCurrentObject() || (curObj = getCurrentObject()) == null) {
                  Object res;
                  res = SelectObjectWizard.start(this, statement);
                  if (res == SelectObjectWizard.NO_INSTANCES_SENTINEL)
                     skipEval = true;
                  else if (res == null)
                     return;
                  else {
                     curObj = res;
                     execContext.pushCurrentObject(curObj);
                     pushed = true;
                  }
               }
               else {
                  execContext.pushCurrentObject(curObj);
                  pushed = true;
               }
            }

            try {
               if (!skipEval) {
                  Object exprResult = expr.eval(null, execContext);
                  if (exprResult == null) {
                     if (!ModelUtil.typeIsVoid(expr.getTypeDeclaration()))
                        System.out.println("null");
                  }
                  else
                     System.out.println(exprResult);
               }
            }
            finally {
               if (pushed)
                  execContext.popCurrentObject();
            }
         }
      }
      else if (statement instanceof BlockStatement) {
         BodyTypeDeclaration current = currentTypes.get(currentTypes.size()-1);

         // We do not generate unless the component is started
         if (!model.isStarted())
            ParseUtil.initAndStartComponent(model);

         current.updateBlockStatement((BlockStatement)statement, execContext);
      }
      else
         System.err.println("*** Unrecognized type of statement in command interpreter: " + statement);


      updateCurrentModelStale();

      if (!model.hasErrors) {
         recordOutput(recordString, origIndent);
      }

      if (pauseTime != 0)
         sleep(pauseTime);
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
      if (obj == null && currentTypes.size() > 0)
         obj = getDefaultCurrentObj(currentTypes.get(currentTypes.size()-1));
      return obj;
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
         model.layeredSystem.addNewModel(model, null, execContext, false);

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
         return parentType.updateInnerType(type, execContext, true, null, true);
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

   public void setCurrentType(BodyTypeDeclaration newType) {
      super.setCurrentType(newType);
      if (pendingModel != null) {
         pendingModel.setCommandInterpreter(this);

         ArrayList<IEditorSession> sessions = editSessions.get(pendingModel.getModelTypeDeclaration().getFullTypeName());
         if (sessions != null && sessions.size() > 0) {
            edit();
         }
      }
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


   public void gui() {
      String[] editorLayers = {"gui/editor"};
      system.addLayers(editorLayers, false, execContext);
   }

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
      if (currentTypes.size() == 0) {
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

   // TODO: should we use a more complete shell environment - support with JLine 3.x or Crashub.org to get complete shell features, remote access, etc?
   /** Run a system command */
   public int exec(String argStr) {
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
      return LayerUtil.execCommand(pb, execDir, inputFile, outputFile);
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
      if (currentTypes.size() == 0) {
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
      if (currentTypes.size() == 0) {
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
      return 80;
   }

   public void initReadThread() {
      DynUtil.setThreadScheduler(this);
   }

   private ArrayList<ScheduledJob> toRunLater = new ArrayList<ScheduledJob>();

   public void invokeLater(Runnable r, int priority) {
      ScheduledJob job = new ScheduledJob();
      job.priority = priority;
      job.toInvoke = r;
      ScheduledJob.addToJobList(toRunLater, job);
   }

   public void execLaterJobs() {
      for (int i = 0; i < toRunLater.size(); i++) {
         ScheduledJob toRun = toRunLater.get(i);
         toRun.toInvoke.run();
      }
   }

   public void addDialogAnswer(String dialogName, Object value) {
      DialogManager.addDialogAnswer(dialogName, value);
   }
}
