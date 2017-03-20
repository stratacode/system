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
    * To determine whether the fromStatement matches this statememtn, we call matchesStatement.  For assignment expressions
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

}
