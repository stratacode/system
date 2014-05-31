/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISemanticNode;
import sc.layer.LayeredSystem;

public class SwitchLabel extends ExpressionStatement {
   public int getNestingDepth() {
      if (parentNode != null)
         return parentNode.getChildNestingDepth() - 1;
      return 0;
   }

   SwitchStatement getSwitch() {
      ISemanticNode parent = parentNode;
      while (parent != null) {
         if (parent instanceof SwitchStatement)
            return (SwitchStatement) parent;
         parent = parent.getParentNode();
      }
      return null;
   }

   public Statement transformToJS() {
      LayeredSystem sys = getLayeredSystem();
      SwitchStatement sw = getSwitch();
      if (sw.isEnum && expression instanceof IdentifierExpression) {
         IdentifierExpression ie = (IdentifierExpression) expression;
         int sz = ie.identifiers.size();
         Object boundType = ie.boundTypes[sz-1];

         // If this is just 'case EnumName' we need to make it 'case EnumType.EnumName' for JS since Java adds the type automatically
         if (sz == 1) {
            Object enumType = ModelUtil.getEnclosingType(boundType);
            String prefix = sys.runtimeProcessor.getStaticPrefix(enumType, this);
            ie.addIdentifier(0, prefix, IdentifierExpression.IdentifierType.BoundTypeName, enumType);
            sz++;
         }
         // TODO: is the bound type here right?  Do we need to fake a field named _ordinal in java.lang.Enum
         ie.addIdentifier(sz, "_ordinal", IdentifierExpression.IdentifierType.FieldName, boundType);
      }
      return super.transformToJS();
   }
}
