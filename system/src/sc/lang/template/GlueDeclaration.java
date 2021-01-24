/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.template;

import sc.lang.ILanguageModel;
import sc.lang.TemplateLanguage;
import sc.lang.java.Statement;
import sc.parser.IStyleAdapter;

import java.util.List;
import java.util.Set;

/**
 * The companion to GlueStatement and GlueExpression.  This one is matched when we encounter %&gt; templateBodyDeclarations %&lt;
 * in the grammar - i.e. exiting Java code - in the class body context of a &lt;%!
 * This lets you mix in template strings along with methods etc.  I'm not sure how often this will be used and exactly
 * what to do with that text content... right now it gets put into the root template.
 */
public class GlueDeclaration extends Statement implements ITemplateDeclWrapper {
   public List<Object> declarations; // any template body declaration

   public void styleNode(IStyleAdapter adapter) {
      TemplateUtil.glueStyledString(adapter, this);
   }

   public boolean transform(ILanguageModel.RuntimeType type) {
      transformTemplate(0, true);
      return true;
   }

   public int transformTemplate(int ix) {
      int i = 0;
      parentNode.removeChild(this);
      return ix;
   }

   public boolean refreshBoundTypes(int flags) {
      boolean res = false;
      if (declarations != null)
         for (Object o:declarations)
            if (o instanceof Statement) {
               if (((Statement) o).refreshBoundTypes(flags))
                  res = true;
            }
      return res;
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (declarations != null)
         for (Object o:declarations)
            if (o instanceof Statement)
               ((Statement) o).addDependentTypes(types, mode);
   }

   public void setAccessTimeForRefs(long time) {
      if (declarations != null)
         for (Object o:declarations)
            if (o instanceof Statement)
               ((Statement) o).setAccessTimeForRefs(time);
   }

   public Statement transformToJS() {
      if (declarations != null)
         for (Object o:declarations)
            if (o instanceof Statement)
               ((Statement) o).transformToJS();
      return this;
   }

   @Override
   public List<Object> getTemplateDeclarations() {
      return declarations;
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
