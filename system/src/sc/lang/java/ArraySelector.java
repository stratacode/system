/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.dyn.DynUtil;
import sc.lang.ISrcStatement;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class ArraySelector extends Selector {
   public Expression expression;

   transient boolean isAssignment = false;

   public static ArraySelector create(Expression expr) {
      ArraySelector arr = new ArraySelector();
      arr.setProperty("expression", expr);
      return arr;
   }

   public Object evalSelector(Object baseValue, Class expectedType, ExecutionContext ctx, Object boundType) {
      int dim = (int) expression.evalLong(Integer.class, ctx);
      return DynUtil.getArrayElement(baseValue, dim);
   }

   public void setAssignment(boolean assign) {
      isAssignment = assign;
   }

   public void setValue(Object obj, Object value, ExecutionContext ctx) {
      int dim = (int) expression.evalLong(Integer.class, ctx);
      DynUtil.setArrayElement(obj, dim, value);
   }

   public void visitTypeReferences(CycleInfo info, TypeContext ctx) {
      info.visit(expression, ctx);
   }

   public void changeExpressionsThis(TypeDeclaration td, TypeDeclaration outer, String newName) {
      expression.changeExpressionsThis(td, outer, newName);
   }

   public boolean refreshBoundType(int flags) {
      boolean res = false;
      if (expression != null)
         res = expression.refreshBoundTypes(flags);
      return res;
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (expression != null)
         expression.addDependentTypes(types, mode);
   }

   public void transformToJS() {
      if (expression != null)
         expression.transformToJS();
   }

   @Override
   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      sb.append("[");
      if (expression != null)
         sb.append(expression.toGenerateString());
      sb.append("]");
      return sb.toString();
   }

   public String toSafeLanguageString() {
      if (parseNode != null && !parseNodeInvalid)
         return super.toSafeLanguageString();
      return toGenerateString();
   }

   public ArraySelector deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      ArraySelector res = (ArraySelector) super.deepCopy(options, oldNewMap);

      if ((options & CopyInitLevels) != 0) {
         res.isAssignment = isAssignment;
      }
      return res;
   }

   public ISrcStatement findFromStatement(ISrcStatement st) {
      if (expression != null)
         return expression.findFromStatement(st);
      return null;
   }

   public void addGeneratedFromNodes(List<ISrcStatement> res, ISrcStatement srcStatement) {
      if (expression != null)
         expression.addBreakpointNodes(res, srcStatement);
   }

   public String toString() {
      return toGenerateString();
   }
}
