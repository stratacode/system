/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISemanticNode;
import sc.lang.IUserDataNode;
import sc.lang.SemanticNodeList;
import sc.lang.js.JSFormatMode;
import sc.lang.js.JSLanguage;
import sc.parser.FormatContext;
import sc.parser.ParentParseNode;
import sc.parser.ParseUtil;
import sc.type.IBeanMapper;
import sc.type.TypeUtil;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public abstract class Statement extends Definition implements IUserDataNode {
   public transient Object[] errorArgs;
   public transient int childNestingDepth = -1;

   // During code-generation, one statement may be generated from another, e.g. for templates we clone statements that are children of a template declaration.  This
   // stores that references so we can find the original source Statement.
   public transient Statement fromStatement;

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

   public void displayTypeError(String...args) {
      if (errorArgs == null) {
         super.displayTypeError(args);
         errorArgs = args;
      }
   }

   public void displayError(String...args) {
      if (errorArgs == null) {
         super.displayError(args);
         errorArgs = args;
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

      IBeanMapper[] props = TypeUtil.getProperties(getClass());
      for (int i = 0; i < props.length; i++) {
         IBeanMapper prop = props[i];
         if (isSemanticProperty(prop)) {
            Object thisProp = TypeUtil.getPropertyValue(this, prop);
            if (thisProp instanceof Statement && ((Statement) thisProp).anyError())
               return true;
         }
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

   public abstract void refreshBoundTypes();

   public abstract void addDependentTypes(Set<Object> types);

   public abstract Statement transformToJS();

   public CharSequence formatToJS(JSFormatMode mode) {
      SemanticNodeList<Statement> sts = new SemanticNodeList<Statement>();
      sts.parentNode = parentNode; // Plug it into the hierarchy so it gets the right nesting level
      sts.add(this, false);

      // Using blockStatements here to include VariableStatement.  Since it is a choice of items and an array it expects an array value so just wrap it here.
      String res = sts.toLanguageString(JSLanguage.getJSLanguage().blockStatements);
      if (res.contains("Generation error"))
         System.out.println("*** Generation error for statmeent: " + this);
      return res;
   }

   public void addInitStatements(List<Statement> res, InitStatementsMode mode) {
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
         for (Object arg:errorArgs)
            sb.append(arg.toString());
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
}
