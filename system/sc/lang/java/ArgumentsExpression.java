/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.SemanticNodeList;

public abstract class ArgumentsExpression extends Expression
{
   public SemanticNodeList<Expression> arguments;

   public void visitTypeReferences(CycleInfo info, TypeContext ctx) {
      info.visitList(arguments, ctx);
   }

   public void refreshBoundTypes() {
      if (arguments != null)
         for (Expression ex:arguments)
            ex.refreshBoundTypes();
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      if (arguments != null)
         for (Expression ex:arguments)
            ix = ex.transformTemplate(ix, statefulContext);
      return ix;
   }
}
