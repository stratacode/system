/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.template;

import sc.lang.ILanguageModel;
import sc.lang.SemanticNodeList;
import sc.lang.TemplateLanguage;
import sc.lang.html.Element;
import sc.lifecycle.ILifecycle;
import sc.parser.IString;
import sc.lang.java.*;
import sc.parser.IStyleAdapter;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Represents %&gt; template declarations &lt;% ).  This is one of the two most interesting elements in the TemplateLanguage
 * Grammar.  It's used along with GlueExpression to stitch together markup and the code on either side of it.  We encounter a %&gt;
 * in the template language in the context where we are expecting a Java statement.  We process template markup until we re-enter Java code.
 * We must reenter Java code to end the method or whatever.  To execute this markup, we effectively turn this into "out.append(x)" calls.
 */
public class GlueStatement extends Statement implements ITemplateDeclWrapper {
   public List<Object> declarations; // Strings or expressions - all turn into "append" operations onto the StringBuffer

   public transient boolean methodReturnValue = false;

   public transient boolean templateTransformed = false;

   public void init() {
      super.init();

      AbstractMethodDefinition def = getEnclosingMethod();
      methodReturnValue = def != null && def.body != null && def.body.getNumStatements() == 1;
      //transformTemplate();
   }

   public Object definesMember(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      if (methodReturnValue && mtype.contains(MemberType.Variable) && name.equals("out")) {
         VariableStatement vs = createOutField();
         vs.parentNode = getEnclosingMethod().body;
         return vs.definitions.get(0);
      }
      return super.definesMember(name, mtype, refType, ctx, skipIfaces, isTransformed);
   }

   private VariableStatement createOutField() {
      return VariableStatement.create(ClassType.create("StringBuilder"), "out", "=", NewExpression.create("StringBuilder", new SemanticNodeList(0)));
   }

   public ExecResult exec(ExecutionContext ctx) {
      // Instead of using global variables here, we should probably bind an identifier expression to the out
      // variable of the outer class, then just eval that to get the value?  This is just a little easier but
      // does not follow Java's rules for variables.
      StringBuilder out = (StringBuilder) ctx.getVariable("out", true, true);
      ModelUtil.execTemplateDeclarations(out, ctx, declarations);
      return ExecResult.Next;
   }

   public void styleNode(IStyleAdapter adapter) {
      TemplateUtil.glueStyledString(adapter, this);
   }

   public boolean transform(ILanguageModel.RuntimeType type) {
      AbstractMethodDefinition meth = getEnclosingMethod();
      boolean statefulContext;
      if (meth != null)
         statefulContext = false;
      else
         statefulContext = true;
      // Normally transformTemplate will replace this statement with a block statement so we need to find it and
      // make sure it gets transformed.
      int ix = parentNode.indexOfChild(this);
      transformTemplate(0, statefulContext);
      if (ix != -1) {
         Object newChild = parentNode.getChildAtIndex(ix);
         if (newChild != this) {
            if (newChild instanceof BlockStatement) {
               BlockStatement bs = (BlockStatement) newChild;
               if (!bs.getTransformed())
                  bs.transform(type);
            }
            else
               System.err.println("*** Unrecognized transformed result for glue expression");
         }
         else
            System.out.println("*** Glue statement not transformed?");
      }
      return true;
   }

   public int transformTemplate(int chunkCt, boolean statefulContext) {
      if (templateTransformed)
         return chunkCt;
      templateTransformed = true;
      // Optimize the case for method { %> string <% } 
      BlockStatement bl = methodReturnValue ? getEnclosingMethod().body : new BlockStatement();
      int i = 0;
      if (methodReturnValue) {
         VariableStatement vs = createOutField();
         vs.fromStatement = this;
         bl.addStatementAt(i++, vs);
      }
      else
         bl.parentNode = parentNode;

      if (declarations != null) {
         for (Object o:declarations) {
            if (o instanceof IString) {
               Statement outSt = Template.getConstStringOutputStatement(o.toString());
               outSt.fromStatement = this;
               bl.addStatementAt(i++, outSt);
            }
            else if (o instanceof Expression) {
               Expression srcExpr = (Expression) o;
               IdentifierExpression outExpr = IdentifierExpression.create("out", "append");
               SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
               args.add(srcExpr.deepCopy(CopyNormal, null));
               outExpr.setProperty("arguments",  args);
               bl.addStatementAt(i++, outExpr);
               outExpr.fromStatement = srcExpr;
            }
            else if (o instanceof Element) {
               Element elem = (Element) o;
               TypeDeclaration enclType = getEnclosingType();
               if (enclType == null) {
                  Element enclTag = getEnclosingTag();
                  if (enclTag != null) {
                     if (enclTag.tagObject != null)
                        enclType = enclTag.tagObject;
                     else
                        enclType = enclTag.getEnclosingType();
                  }
               }
               chunkCt = elem.addToOutputMethod(enclType, bl, getEnclosingTemplate(), Element.doOutputAll, elem.children, chunkCt, statefulContext);
               // Need to reset "i" here since the code above may have inserted into the block.
               if (bl.statements.size() > 0) {
                  i = bl.statements.size();
                  if (bl.statements.get(i-1) instanceof ReturnStatement)
                     i--;
               }
            }
            else {
               throw new UnsupportedOperationException();
            }
         }
      }
      if (methodReturnValue) {
         Statement retSt = ReturnStatement.create(IdentifierExpression.create("out"));
         bl.addStatementAt(bl.getNumStatements(), retSt);
         retSt.fromStatement = this;
         parentNode.removeChild(this);
      }
      else {
         parentNode.replaceChild(this, bl);
         // We do not propagate down the initialize in the semantic node tree, so this has to be done manually.
         ILifecycle parent = (ILifecycle) parentNode;
         if (parent.isInitialized() && !bl.isInitialized())
            bl.init();
      }
      return chunkCt;
   }

   public boolean refreshBoundTypes(int flags) {
      boolean res = false;
      if (declarations != null) {
         for (Object d:declarations) {
            if (d instanceof Statement) {
               if (((Statement) d).refreshBoundTypes(flags))
                  res = true;
            }
         }
      }
      return res;
   }

   public void addChildBodyStatements(List<Object> statements) {
      if (declarations != null)
         for (Object d:declarations) {
            if (d instanceof Element)
               statements.add(d);
            else if (d instanceof TemplateStatement && Element.allowBehaviorTagsInContent) {
               ((TemplateStatement) d).addChildBodyStatements(statements);
            }
         }
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (declarations != null)
         for (Object d:declarations)
            if (d instanceof Statement)
               ((Statement) d).addDependentTypes(types, mode);
   }

   public void setAccessTimeForRefs(long time) {
      if (declarations != null)
         for (Object d:declarations)
            if (d instanceof Statement)
               ((Statement) d).setAccessTimeForRefs(time);
   }

   public Statement transformToJS() {
      if (declarations != null)
         for (Object d:declarations)
            if (d instanceof Statement)
               ((Statement) d).transformToJS();
      return this;
   }

   @Override
   public List<Object> getTemplateDeclarations() {
      return declarations;
   }

   public boolean isLeafStatement() {
      return false;
   }

   public int getNumStatementLines() {
      return 1;
   }

   public String getTemplateDeclStartString() {
      return TemplateLanguage.END_DELIMITER; // NOTE: the start delimiter for the glue is really the end delimiter token so start/end are reversed intentionally
   }

   public String getTemplateDeclEndString() {
      return TemplateLanguage.START_CODE_DELIMITER;
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(getTemplateDeclStartString());
      if (declarations != null) {
         for (Object decl:declarations)
            sb.append(decl);
      }
      sb.append(getTemplateDeclEndString());
      return sb.toString();
   }

}
