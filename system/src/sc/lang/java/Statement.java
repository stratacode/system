/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.*;
import sc.lang.js.JSFormatMode;
import sc.lang.js.JSLanguage;
import sc.parser.*;
import sc.type.DynType;
import sc.type.IBeanMapper;
import sc.type.TypeUtil;

import java.util.*;

public abstract class Statement extends Definition implements IUserDataNode, ISrcStatement {
   public transient Object[] errorArgs;
   public transient int childNestingDepth = -1;

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

      public ErrorRangeInfo(int fromIx, int toIx) {
         this.fromIx = fromIx;
         this.toIx = toIx;
      }

      public String toString() {
         return fromIx + ":" + toIx;
      }
   }

   void displayRangeError(int fromIx, int toIx, String...args) {
      displayTypeError(args);
      if (errorArgs != null) {
         ArrayList<Object> eargs = new ArrayList<Object>(Arrays.asList(errorArgs));
         eargs.add(new ErrorRangeInfo(fromIx, toIx));
         errorArgs = eargs.toArray();
      }
   }

   public void displayFormatatedError(String arg) {
      if (errorArgs == null) {
         super.displayFormattedError(arg);
         errorArgs = new Object[] {arg};
      }
   }

   public String getUserVisibleName() {
      return "statement";
   }

   /** Returns true if there are errors in this node or any child node of this node */
   public boolean anyError() {
      if (errorArgs != null)
         return true;

      DynType type = TypeUtil.getPropertyCache(getClass());
      IBeanMapper[] semanticProps = type.getSemanticPropertyList();

      for (int i = 0; i < semanticProps.length; i++) {
         IBeanMapper prop = semanticProps[i];
         Object thisProp = TypeUtil.getPropertyValue(this, prop);
         if (thisProp instanceof Statement && ((Statement) thisProp).anyError())
            return true;
      }
      return false;
   }

   /** Does this method call "super(xxx)" for a constructor definition */
   public boolean callsSuper() {
      return false;
   }

   public boolean callsThis() {
      return false;
   }

   public Expression[] getConstrArgs() {
      return null;
   }

   protected void initDynStatements(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode) {
   }

   public void clearDynFields(Object inst, ExecutionContext ctx) {
   }

   public abstract void refreshBoundTypes(int flags);

   public abstract void addDependentTypes(Set<Object> types);

   public abstract Statement transformToJS();

   public CharSequence formatToJS(JSFormatMode mode) {
      SemanticNodeList<Statement> sts = new SemanticNodeList<Statement>();
      sts.parentNode = parentNode; // Plug it into the hierarchy so it gets the right nesting level
      sts.add(this, false, true);

      // Using blockStatements here to include VariableStatement.  Since it is a choice of items and an array it expects an array value so just wrap it here.
      String res = sts.toLanguageString(JSLanguage.getJSLanguage().blockStatements);
      if (res.contains("Generation error"))
         System.out.println("*** Generation error for statmeent: " + this);
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
      res.fromStatement = this;
      return res;
   }

   public String getNodeErrorText() {
      if (errorArgs != null) {
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
      return null;
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
      return !(this instanceof IBlockStatement);
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
      return ParseUtil.countLinesInNode(getParseNode());
   }

   public void addToFileLineIndex(GenFileLineIndex idx) {
      if (isLineStatement()) {
         ISrcStatement srcStatement = getSrcStatement(null);
         if (srcStatement != null && srcStatement != this) {
            ISemanticNode srcRoot = srcStatement.getRootNode();
            if (srcRoot instanceof JavaModel) {
               JavaModel srcModel = (JavaModel) srcRoot;
               int startGenOffset = getParseNode().getStartIndex();
               if (startGenOffset == -1) {
                  System.out.println("*** Attempt to add statement to genLine index with a parse-node that's missing a startIndex");
                  return;
               }
               int startGenLine = idx.genFileLineIndex.getLineForOffset(startGenOffset);
               int numGenLines = getNumStatementLines();
               if (numGenLines > 0)
                  numGenLines--;
               int endGenLine = startGenLine + numGenLines;

               IParseNode srcParseNode = srcStatement.getParseNode();
               if (srcParseNode == null) {
                  int ict = 0;
                  ISemanticNode srcParent = srcStatement.getParentNode();
                  while (srcParent != null && srcParseNode == null && ict < 2) {
                     srcParseNode = srcParent.getParseNode();
                     if (srcParseNode == null || !(srcParent instanceof ISrcStatement))
                        srcParent = srcParent.getParentNode();
                     else
                        break;
                     ict++;
                  }
                  if (srcParseNode != null) {
                     if (srcParent instanceof ISrcStatement)
                        srcStatement = (ISrcStatement) srcParent;
                     else {
                        System.err.println("*** found a non-statement parent!");
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
                  System.out.println("*** Warning - no source for statement: " + this);
               }
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
               blockSt.addToFileLineIndex(idx);
            }
         }
      }
      else if (this instanceof IStatementWrapper) {
         Statement wrappedSt = ((IStatementWrapper) this).getWrappedStatement();
         if (wrappedSt != null)
            wrappedSt.addToFileLineIndex(idx);
      }
      else if (this instanceof IBlockStatementWrapper) {
         BlockStatement bst = ((IBlockStatementWrapper) this).getWrappedBlockStatement();
         if (bst != null)
            bst.addToFileLineIndex(idx);
      }
      // NewExpression and TypeDeclaration
      else if (this instanceof IClassBodyStatement) {
         List<Statement> sts = ((IClassBodyStatement) this).getBodyStatements();
         if (sts != null) {
            for (Statement st:sts)
               st.addToFileLineIndex(idx);
         }
      }
   }

   public void setFromStatement(ISrcStatement from) {
      fromStatement = from;
   }
}
