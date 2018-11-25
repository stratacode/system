/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.template;

import sc.bind.BindingDirection;
import sc.bind.ConstantBinding;
import sc.bind.IBinding;
import sc.lang.ILanguageModel;
import sc.lang.ISrcStatement;
import sc.lang.SemanticNodeList;
import sc.lang.TemplateLanguage;
import sc.lang.html.Element;
import sc.lang.java.*;
import sc.parser.IString;
import sc.parser.IStyleAdapter;
import sc.parser.PString;
import sc.parser.ParseUtil;

import java.util.List;
import java.util.Set;

/**
 * The cousin to GlueStatement.  This gets produced in the TemplateLanguage grammar by matching %&gt; templateStuff &lt;%.  It turns into one giant out.append(...) call effectively.
 * We can then insert a GlueExpression into the "primary" grammar node as an option in the template language.  This allows us to break into template mode in place of a Java expression - e.g.
 * pass template strings as parameters to a method or compare or initialize variables etc.
 */
public class GlueExpression extends Expression {
   public List<Object> expressions; // IStrings or expressions - all turn into "append" operations onto the StringBuffer

   private transient Expression outExpr = null;

   public void start() {
      super.start();
      if (outExpr != null)
         outExpr.start();
   }

   public void validate() {
      super.validate();
      if (outExpr != null)
         outExpr.validate();
   }

   public void process() {
      super.process();
      if (outExpr != null)
         outExpr.process();
   }

   public ExecResult exec(ExecutionContext ctx) {
      StringBuilder out = (StringBuilder) ctx.getVariable("out", true, true);
      ModelUtil.execTemplateDeclarations(out, ctx, expressions);
      return ExecResult.Next;
   }

   @Override
   public boolean isStaticTarget() {
      for (Object expr:expressions) {
         if (expr instanceof Expression) {
            if (!((Expression) expr).isStaticTarget())
               return false;
         }
      }
      return true;
   }

   public void refreshBoundTypes(int flags) {
      if (expressions != null) {
         for (Object expr:expressions)
            if (expr instanceof Expression)
               ((Expression) expr).refreshBoundTypes(flags);
      }
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (expressions != null) {
         for (Object expr:expressions)
            if (expr instanceof Expression)
               ((Expression) expr).addDependentTypes(types, mode);
      }
   }

   public Statement transformToJS() {
      if (expressions != null) {
         for (Object expr:expressions)
            if (expr instanceof Expression)
               ((Expression) expr).transformToJS();
      }
      return this;
   }

   public Object eval(Class expectedType, ExecutionContext ctx) {
      StringBuilder sb = new StringBuilder();
      initOutExpr();
      if (outExpr != null)
         return outExpr.eval(expectedType, ctx);
      return null;
      /*
      if (bindingDirection != null) {
         return initBinding(expectedType, ctx);
      }
      else {
         ModelUtil.execTemplateDeclarations(sb, ctx, expressions);
         if (expectedType == String.class)
            return sb.toString();
         else // trying to be efficient...
            return sb;
      }
      */
   }

   public IBinding[] evalTemplateBindingParameters(Class expectedType, ExecutionContext ctx, List<Object> bindingParams) {
      int sz = bindingParams.size();
      IBinding[] result = new IBinding[sz];
      for (int i = 0; i < sz; i++) {
         Object bparam = bindingParams.get(i);
         if (bparam instanceof Expression) {
            result[i] = (IBinding) ((Expression) bparam).evalBinding(expectedType, ctx);
         }
         else if (PString.isString(bparam)) {
            result[i] = new ConstantBinding(bparam.toString());
         }
      }
      return result;
   }

   public void evalBindingArgs(List<Object> bindArgs, boolean isStatic, Class expectedType, ExecutionContext ctx) {
      bindArgs.add("+");
      bindArgs.add(evalTemplateBindingParameters(expectedType, ctx, expressions));
   }

   public String getBindingTypeName() {
      return nestedBinding ? "arithP" : "arith";
   }

   public Object getTypeDeclaration() {
      return String.class;
   }

   public void styleNode(IStyleAdapter adapter) {
      TemplateUtil.glueStyledString(adapter, this);
   }

   public boolean transform(ILanguageModel.RuntimeType type) {
      transformTemplate(0, type);
      return true;
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      return transformTemplate(ix, null);
   }

   private void initOutExpr() {
      if (expressions == null || outExpr != null)
         return;

      SemanticNodeList<Expression> exprs = new SemanticNodeList<Expression>(expressions.size());
      for (Object expr:expressions) {
         if (expr instanceof IString) {
            exprs.add(StringLiteral.create(expr.toString()));
         }
         else if (expr instanceof Expression) {
            exprs.add((Expression) expr);
         }
         else if (expr instanceof Element) {
            Element elem = (Element) expr;
            // TODO: need to extract a sub-method from element.addToOutputMethod that gets the expression from the attributes and body when there's no object (it should be a string concat of the various expressions)
            // Generate a call to "output" if needsObject is true and a call to this method if it is false.
            Expression elemExpr = elem.getOutputExpression();
            if (elemExpr != null)
               exprs.add(elemExpr);
         }
      }
      Expression res;
      if (exprs.size() == 1)
         res = exprs.get(0);
      else {
         res = BinaryExpression.createMultiExpression(exprs.toArray(new Expression[exprs.size()]), "+");
         if (bindingDirection != null)
            res.setBindingInfo(bindingDirection, bindingStatement, nestedBinding);
         res.fromStatement = this;
         res.parentNode = parentNode;
      }
      outExpr = res;
      if (initialized)
         ParseUtil.initComponent(outExpr);
      if (started)
         ParseUtil.startComponent(outExpr);
      if (validated)
         ParseUtil.validateComponent(outExpr);
   }

   public int transformTemplate(int ix, ILanguageModel.RuntimeType type) {
      if (expressions == null)
         return ix;
      initOutExpr();
      if (outExpr != null) {
         if (parentNode.replaceChild(this, outExpr) == -1) {
            System.err.println("*** - unable to replace glue expression child");
         }
         if (initialized)
            ParseUtil.initComponent(outExpr);
         ix = outExpr.transformTemplate(ix, true);
         if (type != null)
            outExpr.transform(type);
      }
      return ix;
   }

   public void setBindingInfo(BindingDirection dir, Statement dest, boolean nested) {
      super.setBindingInfo(dir, dest, nested);
      for (Object expr:expressions) {
         if (expr instanceof Expression) {
            Expression exprNode = (Expression) expr;
            exprNode.setBindingInfo(dir, dest, true);
         }
      }
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      sb.append(TemplateLanguage.END_DELIMITER);
      for (Object expr:expressions) {
         if (expr instanceof Expression)
            sb.append(((Expression) expr).toGenerateString());
         else if (PString.isString(expr))
            sb.append(expr);
      }
      sb.append(TemplateLanguage.START_CODE_DELIMITER);
      return sb.toString();
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement srcStatement) {
      super.addBreakpointNodes(res, srcStatement);
      if (expressions != null) {
         for (Object expr:expressions) {
            if (expr instanceof Expression)
               ((Expression) expr).addBreakpointNodes(res, srcStatement);
         }
      }
   }

   public boolean isLeafStatement() {
      return false;
   }
}
