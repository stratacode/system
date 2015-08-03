/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.template;

import sc.lang.ILanguageModel;
import sc.lang.java.Statement;
import sc.parser.IStyleAdapter;

import java.util.List;
import java.util.Set;

/**
 * The companion to GlueStatement and GlueExpression.  This one is matched when we encounter %> templateBodyDeclarations %>
 * in the grammar.  This one is used when we encounter %> - i.e. exiting Java code - in the class body context of a <%!
 * This lets you mix in template strings along with methods etc.  I'm not sure how often this will be used and exactly
 * what to do with that text content... right now it gets put into the root template.
 */
public class GlueDeclaration extends Statement {
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

   public void refreshBoundTypes(int flags) {
      if (declarations != null)
         for (Object o:declarations)
            if (o instanceof Statement)
               ((Statement) o).refreshBoundTypes(flags);
   }

   public void addDependentTypes(Set<Object> types) {
      if (declarations != null)
         for (Object o:declarations)
            if (o instanceof Statement)
               ((Statement) o).addDependentTypes(types);
   }

   public Statement transformToJS() {
      if (declarations != null)
         for (Object o:declarations)
            if (o instanceof Statement)
               ((Statement) o).transformToJS();
      return this;
   }
}
