/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.bind.Bind;
import sc.bind.DestinationListener;
import sc.dyn.DynUtil;
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
import sc.util.FileUtil;
import sc.util.StringUtil;
import sc.layer.Layer;

import java.io.*;
import java.util.*;

public abstract class AbstractInterpreter extends EditorContext {
   static SCLanguage vlang = SCLanguage.INSTANCE;

   StringBuffer pendingInput = new StringBuffer();

   public static String USAGE =  "StrataCode Command Line Editor Help:\n" +
           "The command line interface lets you look at and modify the declarative state of the currently running StrataCode application.  When you run it with the graphical program editor, the two share the same editing state and generally stay in sync with each other.\n" +
           "The command prompt has two modes:\n" +
           "  1) The top level mode lets you inspect the top-level types (objects and classes) in the current layer.  You can change layers by typing the layer's path name followed by an open brace ('{').  You can inspect and modify existing types with 'typeName {'.  You can create a new type with 'class newTypeName {' or 'object newTypeName {'.\n" +
           "  2) When you're in the context of a current type, you can execute expressions in that context, add fields, set property values, just by typing the StrataCode code at the prompt.\n\n" +
           "Saving: Changes must be explicitly saved with cmd.save().\n\n" +
           "Use TAB for command-line completion of most identifiers and commands.\n\n" +
           "The prompt: displays the current layer's name, followed by the current package.  Both names are surrounded by '#' characters for each layer above or below that layer in the stack.  (<n>) is used to represent <n> layers for big stacks of layers.\n\n" +

           "   packageName {      - Set current context to the package name specified.\n" +
           "   layerName {        - Go to the layer name specified.\n" +
           "   typeName {         - Go to the type specified in the current layer.  If this type does not exist in this layer yet, the type will be added to the layer as needed.\n" +
           "   class typeName {   - Define a new class in the current layer.  The package for the class is the layer's package prefix (if any) plus the packageName context.\n" +
           "   object typeName {  - Define a new object.\n" +
           "   expression;        - Evaluate the expression and prints the output using the Java toString method.\n" +
           "   }                  - Close the current type - returning to any previous type or the top-level\n\n" +

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
           "   cmd.restart();     - Restart the process.\n\n";

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
               for (int c = currentLayer.getLayerPosition(); c < system.layers.size()-1; c++)
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
               if ((switchLayer = system.getLayerByPath(typeName)) != null || (switchLayer = system.getLayerByPath(CTypeUtil.prefixPath(currentLayer.getLayerGroupName(), typeName))) != null) {
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
                  system.refreshRuntimes();
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
                  modType.temporaryType = true;
                  ParseUtil.initAndStartComponent(type);
                  if (modType.mergeDefinitionsInto(currentDef))
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
            layer.addNewSrcFile(newSrcEnt);

         // Must be done after adding so we get the full type name
         String typeName = ModelUtil.getTypeName(type);
         boolean checkCurrentObject = parentType == null || hasCurrentObject();

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

         Object parentObj = getCurrentObject();

         if (type.getDefinesCurrentObject()) {
            boolean pushed = false;
            Object obj = null;
            try {
               execContext.pushStaticFrame(type);
               // Using origTypeName here - grabbed before we do the "a.b" to a { b" conversion.   type.typeName now will just be "b".
               obj = parentObj == null ? (checkCurrentObject ? system.resolveName(typeName, true) : null) : DynUtil.getPropertyPath(parentObj, origTypeName);
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
         PropertyAssignment pa = (PropertyAssignment) statement;
         if (!pa.isStatic() && autoObjectSelect) {
            if (!hasCurrentObject() || (curObj = getCurrentObject()) == null) {
               Object res = SelectObjectWizard.start(this, statement);
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
            BodyTypeDeclaration current = currentTypes.get(currentTypes.size()-1);
            PropertyAssignment assign = (PropertyAssignment) statement;

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

         if (!expr.hasError && !model.hasErrors) {
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

   public Object getCurrentObject() {
      Object obj = execContext.getCurrentObject();
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
         model.addTypeDeclaration((TypeDeclaration) type);

         // If this is a ModifyDeclaration which could not find it's type, do not add it as it is bogus anyway
         if (type instanceof ModifyDeclaration && type.hasError)
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
                  type.invalidateParseNode();
                  // Clear these out because the slots won't make sense with the new parselet
                  pn.children = null;
               }
            }
         }
         return parentType.updateInnerType(type, execContext, true, null);
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
         methObj = curType.definesMethod(def, null, null, null, false);
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

   /** Run a system command */
   public int exec(String argStr) {
      String[] args = StringUtil.splitQuoted(argStr);
      ProcessBuilder pb = new ProcessBuilder(args);
      return LayerUtil.execCommand(pb, execDir);
   }

   public void quit() {
      System.exit(system.anyErrors ? 1 : 0);
   }

   public String getEditLayerName() {
      return currentLayer.getLayerName();
   }

   public void setEditLayerName(String name) {
      Layer newLayer;
      if ((newLayer = system.getLayerByPath(name)) != null) {
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
            setCurrentLayer(system.layers.get(pos - 1));
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
            setCurrentLayer(system.layers.get(currentLayer.getLayerPosition() + 1));
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

}
