/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISemanticNode;
import sc.lang.ISrcStatement;
import sc.lang.SemanticNodeList;
import sc.type.TypeUtil;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class VariableSelector extends Selector {
   public String identifier;
   public SemanticNodeList<Expression> arguments;

   transient boolean isAssignment = false;

   public static VariableSelector create(String id, SemanticNodeList<Expression> args) {
      VariableSelector sel = new VariableSelector();
      sel.identifier = id;
      sel.setProperty("arguments", args);
      return sel;
   }

   public Object evalSelector(Object baseValue, Class expectedType, ExecutionContext ctx, Object boundType) {
      if (arguments != null) {
         if (boundType == null)
            throw new IllegalArgumentException("Unable to find method: " + identifier + " on type: " +
                                               baseValue.getClass() + " with arguments: " + arguments.toLanguageString());
         return ModelUtil.invokeMethod(baseValue, boundType, arguments, expectedType, ctx, true, true, null);
      }
      else {
         /*
           A special case that will never occur in a valid model for the "suggestCompletions" method.
           If you tab when there are no characters following the "." it is just like using the original object.
          */
         if (identifier == null || identifier.length() == 0)
            return baseValue;
         return TypeUtil.getPropertyOrStaticValue(baseValue, identifier);
      }
   }

   public void setAssignment(boolean isAssign) {
      // We can't do a.b(x) = 3
      assert arguments == null;

      isAssignment = isAssign;
   }

   public void setValue(Object obj, Object value, ExecutionContext ctx) {
      TypeUtil.setPropertyOrStaticValue(obj, identifier, value);
   }

   public void changeExpressionsThis(TypeDeclaration td, TypeDeclaration outer, String newName) {
      if (arguments != null) {
         for (int i = 0; i < arguments.size(); i++)
            arguments.get(i).changeExpressionsThis(td, outer, newName);
      }
   }

   int getSelectorIndex() {
      ISemanticNode parpar;
      if (parentNode == null || !((parpar = parentNode.getParentNode()) instanceof SelectorExpression))
         return -1;
      SelectorExpression sex = (SelectorExpression) parpar;
      return sex.selectors.indexOf(this);
   }

   public void visitTypeReferences(CycleInfo info, TypeContext ctx) {
      info.visitList(arguments, ctx);
   }

   public void refreshBoundType(int flags) {
      if (arguments != null)
         for (Expression expr:arguments)
            expr.refreshBoundTypes(flags);
   }

   public void addDependentTypes(Set<Object> types) {
      if (arguments != null)
         for (Expression expr:arguments)
            expr.addDependentTypes(types);
   }

   public void transformToJS() {
      if (arguments != null)
         for (Expression expr:arguments)
            expr.transformToJS();
   }

   public String toSafeLanguageString() {
      if (parseNode != null && !parseNodeInvalid)
         return super.toSafeLanguageString();
      return toGenerateString();
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      sb.append(".");
      sb.append(identifier);
      if (arguments != null)
         sb.append(IdentifierExpression.argsToGenerateString(arguments));
      return sb.toString();
   }

   public String toString() {
      return toGenerateString();
   }

   public VariableSelector deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      VariableSelector res = (VariableSelector) super.deepCopy(options, oldNewMap);

      if ((options & CopyInitLevels) != 0) {
         res.isAssignment = isAssignment;
      }
      return res;
   }

   public ISrcStatement findFromStatement(ISrcStatement st) {
      if (arguments != null) {
         for (Expression expr:arguments) {
            if (expr != null) {
               ISrcStatement res = expr.findFromStatement(st);
               if (res != null)
                  return res;
            }
         }
      }
      return null;
   }

   public void addGeneratedFromNodes(List<ISrcStatement> res, ISrcStatement srcStatement) {
      if (arguments != null) {
         for (Expression expr:arguments) {
            if (expr != null) {
               expr.addBreakpointNodes(res, srcStatement);
            }
         }
      }
   }
}
