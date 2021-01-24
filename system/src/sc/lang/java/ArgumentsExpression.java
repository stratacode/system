/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.SemanticNodeList;

import java.util.ArrayList;
import java.util.List;

public abstract class ArgumentsExpression extends Expression {
   public SemanticNodeList<Expression> arguments;

   public void visitTypeReferences(CycleInfo info, TypeContext ctx) {
      info.visitList(arguments, ctx);
   }

   public boolean refreshBoundTypes(int flags) {
      boolean res = false;
      if (arguments != null)
         for (Expression ex:arguments)
            if (ex.refreshBoundTypes(flags))
               res = true;
      return res;
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      if (arguments != null)
         for (Expression ex:arguments)
            ix = ex.transformTemplate(ix, statefulContext);
      return ix;
   }

   public List<Statement> getBodyStatements() {
      if (arguments != null) {
         List<Statement> res = null;
         for (Expression arg:arguments) {
            if (!arg.isLeafStatement()) {
               if (res == null)
                  res = new ArrayList<Statement>();
               res.add(arg);
            }
         }
         return res;
      }
      return null;
   }
}
