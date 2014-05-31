/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.Set;

public class NewSelector extends Selector {
   public NewExpression innerCreator;

   public Object evalSelector(Object baseValue, Class expectedType, ExecutionContext ctx, Object boundType) {
      if (innerCreator == null)
         return null;
      throw new UnsupportedOperationException("New Selector eval not implemented");
   }

   public void setAssignment(boolean assign) {
      throw new IllegalArgumentException("New expression cannot be on the left hand side of the equal sign: " + toDefinitionString());
   }

   public void setValue(Object obj, Object value, ExecutionContext ctx) {
      throw new IllegalArgumentException("New expression cannot be on the left hand side of the equal sign: " + toDefinitionString());
   }

   public void changeExpressionsThis(TypeDeclaration td, TypeDeclaration outer, String newName) {
      innerCreator.changeExpressionsThis(td, outer, newName);
   }

   public void refreshBoundType() {
      if (innerCreator != null)
         innerCreator.refreshBoundTypes();
   }

   public void addDependentTypes(Set<Object> types) {
      if (innerCreator != null)
         innerCreator.addDependentTypes(types);
   }

   public void transformToJS() {
      if (innerCreator != null)
         innerCreator.transformToJS();
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      sb.append(".");
      sb.append(innerCreator.toGenerateString());
      return sb.toString();
   }

}
