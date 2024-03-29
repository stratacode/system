/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISemanticNode;
import sc.lang.ISrcStatement;

import java.util.List;
import java.util.Set;

public abstract class Selector extends JavaSemanticNode {
   public abstract Object evalSelector(Object baseValue, Class expectedType, ExecutionContext ctx, Object boundType);

   public abstract void setAssignment(boolean assign);

   public abstract void setValue(Object obj, Object value, ExecutionContext ctx);

   public abstract void changeExpressionsThis(TypeDeclaration td, TypeDeclaration outer, String newName);

   public abstract boolean refreshBoundType(int flags);

   public abstract void addDependentTypes(Set<Object> types, DepTypeCtx mode);

   public abstract void transformToJS();

   public abstract String toGenerateString();

   public abstract ISrcStatement findFromStatement(ISrcStatement st);

   public abstract void addGeneratedFromNodes(List<ISrcStatement> res, ISrcStatement srcStatement);

   public SelectorExpression getSelectorExpression() {
      ISemanticNode parpar;
      if (parentNode == null || !((parpar = parentNode.getParentNode()) instanceof SelectorExpression))
         return null;
      return (SelectorExpression) parpar;
   }

   public int getSelectorIndex() {
      SelectorExpression selEx = getSelectorExpression();
      if (selEx == null)
         return -1;
      return selEx.selectors.indexOf(this);
   }
}
