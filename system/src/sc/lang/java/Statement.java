/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.dyn.DynUtil;
import sc.lang.*;
import sc.lang.js.JSFormatMode;
import sc.lang.js.JSLanguage;
import sc.lang.js.JSTypeParameters;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.layer.SrcEntry;
import sc.parser.*;
import sc.type.*;

import java.util.*;

public abstract class Statement extends Definition implements IUserDataNode, ISrcStatement {
   public transient Object[] errorArgs;
   public transient int childNestingDepth = -1;

   /**
    * Has this statement been determined not to be included in this runtime - e.g. it's a java only class and this is the js runtime
    */
   transient public boolean excluded = false;

   public enum RuntimeStatus {
      Enabled, Disabled, Unset
   }

   // During code-generation, one statement may be generated from another, e.g. for templates we clone statements that are children of a template declaration.  This
   // stores that references so we can find the original source Statement.
   public transient ISrcStatement fromStatement;

   transient Object userData = null;  // A hook for user data - specifically for an IDE to store its instance for this node

   public int getNestingDepth() {
      if (parentNode != null)
         return parentNode.getChildNestingDepth();
      return 0;
   }

   public String getIndentStr() {
      int indentLevel = getNestingDepth();
      StringBuilder res = new StringBuilder();
      for (int j = 0; j < indentLevel; j++)
         res.append(FormatContext.INDENT_STR);
      return res.toString();
   }

   public int getChildNestingDepth() {
      if (childNestingDepth != -1)
         return childNestingDepth;
      if (parentNode != null)
         return parentNode.getChildNestingDepth() + 1;
      return 0;
   }

   /** Called when we are initializing a dynamic instance from this statement */
   public void initDynStatement(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode, boolean inherit) {
   }

   public void initDynStatement(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode) {
   }

   public MethodDefinition getCurrentMethod() {
      ISemanticNode par = parentNode;
      while (par != null) {
         if (par instanceof MethodDefinition)
            return (MethodDefinition) par;
         if (par instanceof TypeDeclaration)
            return null;
         par = par.getParentNode();
      }
      return null;
   }

   public JavaSemanticNode modifyDefinition(BodyTypeDeclaration other, boolean doMerge, boolean inTransformed) {
      throw new UnsupportedOperationException();
   }

   public void collectReferenceInitializers(List<Statement> refInits) {
   }

   public void collectConstructorPropInit(ConstructorPropInfo cpi) {
   }

   public ExecResult exec(ExecutionContext ctx) {
      throw new UnsupportedOperationException();
   }

   public boolean isLabeled(String label) {
      return parentNode instanceof LabelStatement && ((LabelStatement) parentNode).labelName.equals(label);
   }

   public boolean spaceAfterParen() {
      return false;
   }

   public boolean displayTypeError(String...args) {
      if (errorArgs == null) {
         if (super.displayTypeError(args)) {
            errorArgs = args;
            return true;
         }
      }
      return false;
   }

   public void displayError(String...args) {
      if (errorArgs == null) {
         super.displayError(args);
         errorArgs = args;
      }
   }

   public static class ErrorRangeInfo {
      public int fromIx;
      public int toIx;
      public boolean notFoundError = false;

      public ErrorRangeInfo(int fromIx, int toIx, boolean notFound) {
         this.fromIx = fromIx;
         this.toIx = toIx;
         this.notFoundError = notFound;
      }

      public String toString() {
         return fromIx + ":" + toIx;
      }
   }

   public void displayRangeError(int fromIx, int toIx, boolean notFound, String...args) {
      if (errorArgs == null) {
         errorArgs = args;
         // Set error args so getNotFoundError() works inside of reportError
         ArrayList<Object> eargs = new ArrayList<Object>(Arrays.asList(errorArgs));
         eargs.add(new ErrorRangeInfo(fromIx, toIx, notFound));
         errorArgs = eargs.toArray();

         super.displayTypeError(args);
      }
   }

   public boolean getNotFoundError() {
      if (errorArgs != null) {
         for (Object earg:errorArgs) {
            if (earg instanceof ErrorRangeInfo) {
               return ((ErrorRangeInfo) earg).notFoundError;
            }
         }
      }
      return false;
   }

   public void displayFormattedError(String arg) {
      if (errorArgs == null) {
         super.displayFormattedError(arg);
         errorArgs = new Object[] {arg};
      }
   }

   public String getUserVisibleName() {
      return "statement";
   }

   /** Returns true if there are errors in this node or any child node of this node */
   public boolean hasErrors() {
      if (errorArgs != null)
         return true;

      DynType type = TypeUtil.getPropertyCache(getClass());
      IBeanMapper[] semanticProps = type.getSemanticPropertyList();

      for (int i = 0; i < semanticProps.length; i++) {
         IBeanMapper prop = semanticProps[i];
         Object thisProp = TypeUtil.getPropertyValue(this, prop);
         if (thisProp instanceof Statement && ((Statement) thisProp).hasErrors())
            return true;
      }
      return false;
   }

   /** Does this method call "super(xxx)" for a constructor definition */
   public boolean callsSuper(boolean checkModSuper) {
      return false;
   }

   /** Does this method call super.methName() */
   public boolean callsSuperMethod(String methName) {
      return false;
   }

   public void markFixedSuper() {
   }

   public boolean callsThis() {
      return false;
   }

   public Expression[] getConstrArgs() {
      return null;
   }

   protected void initDynStatements(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode, boolean initExt) {
   }

   public void clearDynFields(Object inst, ExecutionContext ctx, boolean initExt) {
   }

   public abstract boolean refreshBoundTypes(int flags);

   public abstract void addDependentTypes(Set<Object> types, DepTypeCtx mode);

   public abstract void setAccessTimeForRefs(long time);

   public abstract Statement transformToJS();

   public CharSequence formatToJS(JSFormatMode mode, JSTypeParameters params, int extraLines) {
      SemanticNodeList<Statement> sts = new SemanticNodeList<Statement>();
      sts.parentNode = parentNode; // Plug it into the hierarchy so it gets the right nesting level
      sts.add(this, false, true);

      // Using blockStatements here to include VariableStatement.  Since it is a choice of items and an array it expects an array value so just wrap it here.
      String res = sts.toLanguageString(JSLanguage.getJSLanguage().blockStatements);
      if (res.contains("Generation error"))
         System.out.println("*** Generation error for statement: " + this);
      IParseNode pn = getParseNode();
      if (pn != null && params.lineIndex != null) {
         // Update startIndex to be relative to the root node here
         pn.resetStartIndex(0, false, false);
         // This is the string version of the statement we are processing - to use for counting newlines for offsets of the generated statements
         params.lineIndex.currentStatement = res;
         int genLineCount = params.getGenLineCount();
         if (genLineCount != -1) {
             addToFileLineIndex(params.lineIndex, genLineCount + extraLines);
         }
         params.lineIndex.currentStatement = null;
      }
      //params.addGenLineMapping(this, res, extraLines);
      return res;
   }

   public void addInitStatements(List<Statement> res, InitStatementsMode mode) {
   }

   public void addReturnStatements(List<Statement> res, boolean includeThrow) {
   }

   public void setComment(String s) {
      throw new UnsupportedOperationException();
   }

   public String getComment() {
      ISemanticNode par = getParentNode();
      StringBuilder sb = new StringBuilder();
      if (par instanceof SemanticNodeList) {
         SemanticNodeList parList = (SemanticNodeList) par;
         sb.append(ParseUtil.getSpaceForBodyElement((ParentParseNode) parList.parseNode, this));
      }
      sb.append(ParseUtil.getTrailingSpaceForNode(parseNode));
      return ParseUtil.stripComments(sb.toString());
   }


   public void addChildBodyStatements(List<Object> statements) {
   }

   public void clearTransformed() {
      transformed = false;
   }

   public Statement deepCopy(int options, IdentityHashMap<Object,Object> oldNewMap) {
      Statement res = (Statement) super.deepCopy(options, oldNewMap);
      // When cloning the model for a different layered system, don't have the new one point back to the old
      // like we do when we are about to transform the new one
      if ((options & ISemanticNode.CopyIndependent) == 0)
         res.fromStatement = this;
      res.excluded = excluded;
      if ((options & CopyInitLevels) != 0) {
         res.errorArgs = errorArgs;
      }
      return res;
   }

   public String getNodeErrorText() {
      // Note: in Template when dealing with a 'serverContent' section use [0] length array here as a marker or to clear old errors.   Ignore those false errors here
      if (errorArgs != null && errorArgs.length > 0) {
         StringBuilder sb = new StringBuilder();
         for (Object arg:errorArgs) {
            // This is added on so we know which component of an identifier expression should display the error.  Don't display the toString of it to the user.
            if (arg instanceof ErrorRangeInfo)
               continue;
            sb.append(arg.toString());
         }
         sb.append(this.toString());
         return sb.toString();
      }
      if (isIncompleteStatement())
         return "Missing " + getStatementTerminator();
      return null;
   }

   public String getStatementTerminator() {
      return ";";
   }

   public void setUserData(Object v)  {
      userData = v;
   }

   public Object getUserData() {
      return userData;
   }

   /**
    * If this statement, as part of the transformed model was generated from this provided source statement.
    * Override this statement to control which generated statements are produced from a given stc statement.
    */
   public boolean getNodeContainsPart(ISrcStatement partNode) {
      return partNode == this || sameSrcLocation(partNode);
   }

   /**
    * Returns the statement that was generated from the given src statement.  This will end up being the first
    * one we encounter in the search for the src statement in the chain of fromStatements.
    */
   public ISrcStatement findFromStatement(ISrcStatement st) {
      // If the src statement has a part of our origin statement we are a match.
      if (st.getNodeContainsPart(this.getFromStatement()))
         return this;
      if (fromStatement != null) {
         if (fromStatement == st || ((SemanticNode) fromStatement).sameSrcLocation(st))
            return this;
         // Note we return the first reference even after following the chain - since we want the resulting generated source
         // as mapped to the original source.
         if (fromStatement.findFromStatement(st) != null)
            return this;
      }
      return null;
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement st) {
      ISrcStatement fromSt = findFromStatement(st);
      if (fromSt != null)
         res.add(fromSt);
   }

   /*
   public boolean updateFromStatementRef(Statement fromSt, ISrcStatement srcSt) {
      return false;
   }
   */

   /** During code-transformation, sometimes we end up parsing dynamically generated code which is derived from
    * some source objects - e.g. during the object and property transformation.  This method registers the generated
    * code.  You can provide a 'fromStatement' and a default statement.  If the from statement matches, this node is
    * updated.  If the fromStatement is null the defaultStatement is used.
    * <p>
    * To determine whether the fromStatement matches this statement, we call matchesStatement.  For assignment expressions
    * right now, that allows us to match the right assignment statement in the list to the source from which it was generated from.
    * </p>
    */
   public boolean updateFromStatementRef(Statement fromSt, ISrcStatement defaultSt) {
      return checkFromStatementRef(this, fromSt, defaultSt);
   }

   public boolean matchesStatement(Statement other) {
      return false;
   }

   public static boolean checkFromStatementRef(Statement toUpdate, Statement fromSt, ISrcStatement defaultSt) {
      if (fromSt != null && toUpdate.fromStatement == null && toUpdate.matchesStatement(fromSt)) {
         toUpdate.fromStatement = fromSt;
         return true;
      }
      else if (toUpdate.fromStatement == null && defaultSt != null)
         toUpdate.fromStatement = defaultSt;
      return false;
   }

   public ISrcStatement getSrcStatement(Language lang) {
      if (fromStatement == null || (parseNode != null && parseNode.getParselet().getLanguage() == lang))
         return this;
      return fromStatement.getSrcStatement(lang);
   }

   public ISrcStatement getFromStatement() {
      return fromStatement;
   }

   public boolean childIsTopLevelStatement(Statement child) {
      return false;
   }

   public void stop() {
      super.stop();

      errorArgs = null;
      fromStatement = null;
      childNestingDepth = -1;
   }

   /**
    * Disables the optimization where 'object foo extends bar' omits class foo for simple configuration objects.
    * Certain constructs like methods, fields, clearly disable this.  Others like EnclosingType.this also will because
    * we can only evaluate that expression in the context of a class (unless I suppose the object happens to be a child of the same outer type)
    */
   public boolean needsEnclosingClass() {
      return false;
   }

   public void addMembersByName(Map<String,List<Statement>> membesByName) {
   }

   /**
    * Does this statement conflict with another statement in the same list with this member name.
    * Because fields can define more than one variable, (and variableDefinitions are not Statements) we need to report
    * conflicts on a name-by-name basis.
    */
   public boolean conflictsWith(Statement other, String memberName) {
      return false;
   }

   public void addMemberByName(Map<String,List<Statement>> membersByName, String memberName) {
      List<Statement> sts = membersByName.get(memberName);
      if (sts == null) {
         sts = new ArrayList<Statement>();
         membersByName.put(memberName, sts);
      }
      else {
         for (Statement oldSt : sts) {
            if (conflictsWith(oldSt, memberName)) {
               displayError("Duplicate " + getUserVisibleName() + ": " + memberName + ": " + oldSt + " for: ");
            }
         }
      }
      sts.add(this);
   }

   public Statement findStatement(Statement in) {
      if (deepEquals(in))
         return this;
      return null;
   }

   public boolean isLeafStatement() {
      return !(this instanceof IBlockStatement) && !(this instanceof IStatementWrapper);
   }

   public boolean isLineStatement() {
      return isLeafStatement();
   }

   /** Returns the number of lines this statement occupies for means of navigation, breakpoints, debugging etc. */
   public int getNumStatementLines() {
      if (this instanceof IStatementWrapper) // e.g. synchronized, for, while, etc.  TODO: handle the case where the first part is split over more than one line - i.e. get the parse-node for the first part and count the lines there
         return 1;
      // While the TypeDeclarations are not leaf statements and so don't get added explicitly, we might point the generated
      // line at the first line of the class declaration if it's generated as part of the definition for that type.
      // This also handles NewExpression when there's a classBody
      if (this instanceof IClassBodyStatement && !isLeafStatement())
         return 1;
      // Ignore trailing newlines and whitespace so we get a closer approximation to the code
      // TODO: should we ignore comments here?  Maybe we should eliminate the 'spacing' parse-node in the count
      return ParseUtil.countCodeLinesInNode(getParseNode());
   }

   /**
    * Adds this statement to the generated file line index.  If you pass in rootStartGenLen = -1, the generated source file is used
    * to compute the line number using this parse-node's offset into that file.  If you pass in a positive value, we count the lines
    * in "currentStatement" of the index up until the parse-node's offset.  You can use this mode to incrementally generate an index,
    * even when there's no complete transformed model for the entire file (such as with generating JS).  In that case, we have a generated
    * model for each statement and are manually adding statements to the file, so we need to compute the line numbers relative to this "currentStatement"
    * - i.e. the root statement.
    */
   public void addToFileLineIndex(GenFileLineIndex idx, int rootStartGenLine) {
      if (isLineStatement()) {
         ISrcStatement srcStatement = getSrcStatement(null);
         if (srcStatement != null && srcStatement != this) {
            ISemanticNode srcRoot = srcStatement.getRootNode();
            if (srcRoot instanceof JavaModel) {
               JavaModel srcModel = (JavaModel) srcRoot;
               IParseNode pn = getParseNode();
               if (pn == null) {
                  System.out.println("*** Attempt to add statement to genLine index with no parse-node - ~/.stratacode/modelCache is corrupt?");
                  return;
               }
               int startGenOffset = pn.getStartIndex();
               if (startGenOffset == -1) {
                  System.out.println("*** Attempt to add statement to genLine index with a parse-node that's missing a startIndex");
                  return;
               }
               int startGenLine;
               if (rootStartGenLine == -1) {
                  // There are two ways to build the line index... incrementally or absolute.  This genFileLineIndex variable is only set when we are using absolute but -1 signals incremental
                  if (idx == null || idx.genFileLineIndex == null)
                     throw new IllegalArgumentException("*** Error - genFileLineIndex not initialized but rootStartGenLine is passed as -1");
                  startGenLine = idx.genFileLineIndex.getLineForOffset(startGenOffset);
               }
               else {
                  CharSequence cur = idx.currentStatement;
                  startGenLine = rootStartGenLine + ParseUtil.countCodeLinesInNode(cur, startGenOffset+1);
               }
               int numGenLines = getNumStatementLines();
               if (numGenLines > 0)
                  numGenLines--;
               int endGenLine = startGenLine + numGenLines;

               addMappingForSrcStatement(idx, srcModel, srcStatement, startGenLine, endGenLine);
            }
            else {
               System.err.println("*** Unrecognized root node for src statement in generated model");
            }
         }
         // else - these statements are not generated with the fromStatement set - break here to improve the registration
      }
      if (this instanceof IBlockStatement) {
         List<Statement> blockSts = ((IBlockStatement) this).getBlockStatements();
         if (blockSts != null) {
            for (Statement blockSt:blockSts) {
               blockSt.addToFileLineIndex(idx, rootStartGenLine);
            }
         }
      }
      else if (this instanceof IStatementWrapper) {
         Statement wrappedSt = ((IStatementWrapper) this).getWrappedStatement();
         if (wrappedSt != null)
            wrappedSt.addToFileLineIndex(idx, rootStartGenLine);
      }
      else if (this instanceof IBlockStatementWrapper) {
         BlockStatement bst = ((IBlockStatementWrapper) this).getWrappedBlockStatement();
         if (bst != null)
            bst.addToFileLineIndex(idx, rootStartGenLine);
      }
      // NewExpression and TypeDeclaration
      else if (this instanceof IClassBodyStatement) {
         List<Statement> sts = ((IClassBodyStatement) this).getBodyStatements();
         if (sts != null) {
            for (Statement st:sts)
               st.addToFileLineIndex(idx, rootStartGenLine);
         }
      }
   }

   public void setFromStatement(ISrcStatement from) {
      fromStatement = from;
   }

   public static void addMappingForSrcStatement(GenFileLineIndex idx, JavaModel srcModel, ISrcStatement srcStatement, int startGenLine, int endGenLine) {
      IParseNode srcParseNode = srcStatement.getParseNode();
      if (srcParseNode == null) {
         int ict = 0;
         ISemanticNode srcParent = srcStatement.getParentNode();
         while (srcParent != null && srcParseNode == null && ict < 2) {
            srcParseNode = srcParent.getParseNode();
            // TODO: should we have Template implement ISrcStatement for the empty src case?
            if (srcParseNode == null || !(srcParent instanceof ISrcStatement))
               srcParent = srcParent.getParentNode();
            else
               break;
            ict++;
         }
         if (srcParseNode != null) {
            if (srcParent instanceof ISrcStatement)
               srcStatement = (ISrcStatement) srcParent;
            if (srcParent == null) {  // this happens with Template which is not an
               srcParseNode = null;
            }
         }
      }
      if (srcParseNode != null) {
         int startSrcLine = idx.getSrcFileIndex(srcModel.getSrcFile()).srcFileLineIndex.getLineForOffset(srcParseNode.getStartIndex());
         int numSrcLines = srcStatement.getNumStatementLines();
         if (numSrcLines > 0)
            numSrcLines--;
         int endSrcLine = startSrcLine + numSrcLines;

         idx.addMapping(srcModel.getSrcFile(), startGenLine, endGenLine, startSrcLine, endSrcLine);
      }
      else if (GenFileLineIndex.verbose) {
         System.out.println("*** Warning - no source for src statement: " + srcStatement);
      }
   }

   public static void addMappingForSrcStatement(GenFileLineIndex lineIndex, ISrcStatement srcStatement, int genStartLine, CharSequence genStatementCode) {
      ISemanticNode srcRoot = srcStatement.getRootNode();
      if (srcRoot instanceof JavaModel) {
         JavaModel srcModel = (JavaModel) srcRoot;
         SrcEntry srcFile = srcModel.getSrcFile();
         if (srcFile != null) {
            int genLine = genStartLine;
            int numGenLines = ParseUtil.countLinesInNode(genStatementCode);
            if (numGenLines != 0)
               numGenLines--;
            Statement.addMappingForSrcStatement(lineIndex, srcModel, srcStatement, genLine, genLine + numGenLines);
         }
      }
   }

   public static void addMappingForStatement(GenFileLineIndex lineIndex, Statement st, int genStartLine, CharSequence genStatementCode) {
      ISrcStatement srcStatement = st.getSrcStatement(null);
      if (srcStatement != null && srcStatement != st) {
         addMappingForSrcStatement(lineIndex, srcStatement, genStartLine, genStatementCode);
      }
   }

   public static String addStatementNodeCompletions(JavaModel origModel, JavaSemanticNode origNode, String matchPrefix, int offset, String dummyIdentifier, Set<String> candidates, boolean nextNameInPath, int max) {
      String packagePrefix;
      boolean isQualifiedType = false;
      if (matchPrefix.contains(".")) {
         packagePrefix = CTypeUtil.getPackageName(matchPrefix);
         matchPrefix = CTypeUtil.getClassName(matchPrefix);
         isQualifiedType = true;
      }
      else {
         packagePrefix = origModel == null ? null : origModel.getPackagePrefix();
      }
      ModelUtil.suggestTypes(origModel, packagePrefix, matchPrefix, candidates, true, false, max);
      if (origModel != null && !isQualifiedType) {
         Object currentType = null;
         if (origNode != null)
            currentType = origNode.getEnclosingType();
         if (currentType == null)
            currentType = origModel.getModelTypeDeclaration();
         if (currentType != null)
            ModelUtil.suggestMembers(origModel, currentType, matchPrefix, candidates, true, true, true, true, max);
      }
      return matchPrefix;
   }

   public String addNodeCompletions(JavaModel origModel, JavaSemanticNode origNode, String matchPrefix, int offset, String dummyIdentifier, Set<String> candidates, boolean nextNameInPath, int max) {
      return addStatementNodeCompletions(origModel, origNode, matchPrefix, offset, dummyIdentifier, candidates, nextNameInPath, max);
   }

   /** When choosing in which runtimes to run this statement, returns the member or type of the method or field used in the expression.  Not always possible to determine so return null in those other cases. */
   public RuntimeStatus execForRuntime(LayeredSystem sys) {
      return RuntimeStatus.Unset;
   }


   public Expression getBuildInitExpression(Object expectedType) {
      JavaModel model = getJavaModel();
      String buildInitTemplStr = (String) ModelUtil.getAnnotationValue(this, "sc.obj.BuildInit", "value");
      Expression buildInitExpr = null;
      if (buildInitTemplStr != null && model != null && !model.temporary) {
         if (buildInitTemplStr.length() > 0) {
            TypeDeclaration enclType = getEnclosingType();
            ObjectDefinitionParameters params = TransformUtil.createObjectDefinitionParameters(enclType);
            // TODO: should resolveInLayer be true here and pass in the current layer? We instead handle that by setting the parentNode below before starting it.
            String buildInitExprStr = TransformUtil.evalTemplate(params, buildInitTemplStr, false, false, null);
            if (buildInitExprStr == null) {
               displayError("@BuildInit(\"" + buildInitTemplStr + "\") invalid template expression for: ");
               return null;
            }
            SCLanguage lang = SCLanguage.getSCLanguage();
            Object evalRes = lang.parseString(buildInitExprStr, lang.expression);
            if (evalRes instanceof ParseError) {
               displayError("@BuildInit(\"" + buildInitTemplStr + "\") invalid template expression: " + evalRes + " for: ");
               return null;
            }
            else {
               LayeredSystem sys = model.getLayeredSystem();
               Layer buildLayer = sys.buildLayer;
               if (buildLayer == null)
                  return null;
               Layer layer = null;
               Layer modelLayer = model.getLayer();
               if (modelLayer == null)
                  return null;
               // If the build layer extends the layer where this reference exists, we'll use that since it's the most specific.
               // If not, it won't be able to find the definitions so we need to pick the last layer in the stack which extends
               // the layer which this model is defined in.
               if (buildLayer.extendsOrIsLayer(modelLayer))
                  layer = buildLayer;
               for (int i = sys.layers.size()-1; i >= 0; i--) {
                  Layer nextLayer = sys.layers.get(i);
                  if (nextLayer.extendsOrIsLayer(modelLayer)) {
                     layer = nextLayer;
                     break;
                  }
               }
               if (layer == null)
                  return null;
               Expression expr =  (Expression) ((IParseNode) evalRes).getSemanticValue();
               JavaModel layerModel = (JavaModel) layer.model;
               // Resolve this expression in the context of the parent layer's model - so it can see all types and values as defined
               expr.parentNode = layerModel.getModelTypeDeclaration();
               ParseUtil.initAndStartComponent(expr);
               Object newExpectedType = ModelUtil.getRuntimeType(expectedType);
               if (newExpectedType != null)
                  expectedType = newExpectedType;
               Class expectedClass = expectedType instanceof Class ? (Class) expectedType : null;
               try {
                  ExecutionContext ctx = new ExecutionContext(layerModel);
                  ctx.pushCurrentObject(layer);
                  Object initVal = expr.eval(expectedClass, ctx);
                  if (initVal != null) {
                     if (!ModelUtil.isInstance(expectedType, initVal)) {
                        displayError("@BuildInit - type mismatch between property type: " + ModelUtil.getTypeName(expectedType) + " and value type: " + ModelUtil.getTypeName(DynUtil.getType(initVal)) + " with init value: " + initVal + " for: ");
                        initVal = null;
                     }
                  }
                  if (initVal != null) {
                     // Turn the value back into the expression
                     buildInitExpr = Expression.createFromValue(initVal, true);
                  }
               }
               catch (RuntimeException exc) {
                  displayError("Runtime exception evaluating BuildInit expr: " + buildInitExprStr + " with exception: " + exc + " for: ");
                  sys.error(PTypeUtil.getStackTrace(exc));
               }
            }
         }
         return buildInitExpr;
      }
      return null;
   }


   public boolean isIncompleteStatement() {
      if (parseNode != null && parseNode.isIncomplete()) {
         boolean res = parseNode.isIncomplete();
         return true;
      }
      return false;
   }

   public ExecResult execSys(ExecutionContext ctx) {
      boolean evalPerformed = false;
      JavaModel model = getJavaModel();
      LayeredSystem sys = model.layeredSystem;
      AbstractInterpreter cmd = model.commandInterpreter;
      boolean syncExec = ctx.syncExec;
      ExecResult execResult = null;
      RuntimeStatus sysStatus = null;
      if (!syncExec || cmd == null || ((sysStatus = getRuntimeStatus(sys)) != RuntimeStatus.Disabled &&
                    (sysStatus == RuntimeStatus.Enabled || cmd.performUpdatesToSystem(sys, false)))) {
         if (sys.options.verboseExec)
            sys.info("Exec local statement: " + this + " on: " + sys.getProcessIdent());
         execResult = exec(ctx);
         evalPerformed = true;
      }

      if (syncExec && cmd != null) {
         List<LayeredSystem> syncSystems = getLayeredSystem().getSyncSystems();
         Layer currentLayer = cmd.currentLayer;
         BodyTypeDeclaration currentType = cmd.getCurrentType();
         if (syncSystems != null && currentType != null && currentLayer != null && sysStatus != RuntimeStatus.Enabled) {
            for (LayeredSystem peerSys:syncSystems) {
               if (sysStatus == RuntimeStatus.Disabled || cmd.performUpdatesToSystem(peerSys, evalPerformed)) {
                  Layer peerLayer = peerSys.getLayerByName(currentLayer.layerUniqueName);
                  BodyTypeDeclaration peerType = peerSys.getSrcTypeDeclaration(currentType.getFullTypeName(), peerLayer == null ? null : peerLayer.getNextLayer(), true);
                  if (peerType != null && !peerType.excluded) {
                     Statement peerExpr = deepCopy(0, null);
                     peerExpr.parentNode = peerType;

                     RuntimeStatus peerStatus = peerExpr.getRuntimeStatus(peerSys);
                     if (peerStatus != RuntimeStatus.Disabled) {
                        if (sys.options.verboseExec)
                           sys.info("Exec remote statement: " + peerExpr + " on: " + peerSys.getProcessIdent());
                        Object remoteRes = peerSys.runtimeProcessor.invokeRemoteStatement(peerType, ctx.getCurrentObject(), peerExpr, ctx, cmd.getTargetScopeContext());
                        if (sys.options.verboseExec)
                           sys.info("Exec remote result: " + peerExpr + " returns: " + remoteRes);
                     }
                  }
               }
            }
         }
      }
      if (execResult == null)
         execResult = ExecResult.Next;
      return execResult;
   }

   public RuntimeStatus getRuntimeStatus(LayeredSystem sys) {
      JavaModel stModel = getJavaModel();
      if (stModel == null)
         return RuntimeStatus.Disabled;
      boolean oldDisable = stModel.disableTypeErrors;
      try {
         stModel.disableTypeErrors = true;
         ParseUtil.initAndStartComponent(this);
         if (errorArgs != null) // An error starting this statement means not to run it - the
            return RuntimeStatus.Disabled;
         return execForRuntime(sys);
      }
      finally {
         stModel.disableTypeErrors = oldDisable;
      }
   }

   public void evalRemoteExprs(ExecutionContext ctx) {
   }

   public ParseRange getNodeErrorRange() {
      if (fromStatement != null) {
         ParseRange range = fromStatement.getNodeErrorRange();
         if (range != null)
            return range;
         IParseNode fromPN = fromStatement.getParseNode();
         if (fromPN != null) {
            int startIx = fromPN.getStartIndex();
            if (startIx != -1) {
               return new ParseRange(startIx, startIx + fromPN.length());
            }
         }
      }
      return super.getNodeErrorRange();
   }
}
