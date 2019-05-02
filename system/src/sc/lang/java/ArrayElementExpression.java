/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.BindingDirection;
import sc.bind.Bind;
import sc.bind.IListener;
import sc.dyn.DynUtil;
import sc.lang.ISrcStatement;
import sc.lang.SemanticNodeList;
import sc.parser.IString;
import sc.parser.IStyleAdapter;
import sc.type.IBeanMapper;
import sc.parser.PString;
import sc.parser.ParseUtil;

import java.util.List;

public class ArrayElementExpression extends IdentifierExpression {
   public SemanticNodeList<Expression> arrayDimensions;

   public void start() {
      if (started) return;

      super.start();

      Object superType = super.getTypeDeclaration();
      if (superType != null && !ModelUtil.isArray(superType)) {
         displayTypeError("Type: " + ModelUtil.getTypeName(superType) + " is not an array for: ");
      }
   }


   public Object eval(Class expectedType, ExecutionContext ctx) {
      if (bindingDirection != null)
         return initBinding(expectedType, ctx);

      Object arrVal = super.eval(expectedType, ctx);
      if (arrVal == null)
         throw new NullPointerException(toDefinitionString() + " encountered a null value");

      if (arrayDimensions != null) {
         for (Expression e:arrayDimensions) {
            int dim = (int) e.evalLong(Integer.TYPE, ctx);
            checkNull(arrVal, dim);
            arrVal = DynUtil.getArrayElement(arrVal, dim);
         }
      }
      return arrVal;
   }

   public void setValue(Object value, ExecutionContext ctx) {
      int sz = identifiers.size();
      Object arrVal;

      // We do not transform things before we evaluate so we need to do some data binding type transforms during
      // evaluation for dynamic types.  This includes sending the array index events if you are doing an array set.
      if (sz > 0 && idTypes[sz - 1] == IdentifierType.FieldName) {
         Object arrType = boundTypes[sz - 1];
         if (arrType instanceof VariableDefinition) {
            VariableDefinition arrDef = (VariableDefinition) arrType;
            if (arrDef.bindable) {
               Object rootObj = evalRootValue(ctx);
               checkNull(rootObj, arrDef.variableName);
               IBeanMapper prop = DynUtil.getPropertyMapping(DynUtil.getType(rootObj), arrDef.variableName);
               arrVal = prop.getPropertyValue(rootObj, false);
               arrVal = evalDimObj(arrVal, ctx);
               int dim = getLastDim(ctx);
               DynUtil.setArrayElement(arrVal, dim, value);
               Bind.sendEvent(IListener.ARRAY_ELEMENT_CHANGED, rootObj, prop, dim);
               return;
            }
         }
      }
      arrVal = super.eval(null, ctx);

      arrVal = evalDimObj(arrVal, ctx);

      int dim = getLastDim(ctx);

      checkNull(arrVal, dim);
      DynUtil.setArrayElement(arrVal, dim, value);
   }

   public Object evalDimObj(Object arrVal, ExecutionContext ctx) {
      Expression e;
      int dim;
      int i;
      for (i = 0; i < arrayDimensions.size()-1; i++) {
         e = arrayDimensions.get(i);
         dim = (int) e.evalLong(Integer.TYPE, ctx);
         checkNull(arrVal, dim);
         arrVal = DynUtil.getArrayElement(arrVal, dim);
      }
      return arrVal;
   }

   public int getLastDim(ExecutionContext ctx) {
      int i = arrayDimensions.size()-1;
      Expression e = arrayDimensions.get(i);
      return (int) e.evalLong(Integer.TYPE, ctx);
   }

   private void checkNull(Object value, int dim) {
      if (value == null)
         throw new NullPointerException("Null value encountered deferencing array element: " + dim + " in: " + toDefinitionString());
   }

   public Object getGenericType() {
      Object superType = super.getGenericType();
      if (superType == null)
         return null;

      if (arrayDimensions != null) {
         for (int i = 0; i < arrayDimensions.size(); i++) {
            if (superType == null)
               return null;
            superType = ModelUtil.getArrayComponentType(superType);
         }
      }
      return superType;
   }

   public Object getTypeDeclaration() {
      Object superType = super.getTypeDeclaration();
      if (superType == null)
         return null;

      if (arrayDimensions != null) {
         for (int i = 0; i < arrayDimensions.size(); i++) {
            if (superType == null)
               return null;
            superType = ModelUtil.getArrayComponentType(superType);
         }
      }
      return superType;
   }

   public boolean needsSetMethod() {
      if (arrayDimensions == null || arrayDimensions.size() == 1) {
         Object assignedProp = getAssignedProperty();
         int last = idTypes.length-1;
         // Used to do this for any setX method but we really need to be sure there's an indexed method - e.g. set(int, value) method
         if (assignedProp != null && ModelUtil.hasSetIndexMethod(assignedProp)) {
            // Conversion is disabled for when you are in the setX method for this type or the method is marked as manual get set
            if (inPropertyMethodForDef(boundTypes[last]) || isManualGetSet())
               return false;

            return true;
         }
      }
      return false;
   }

   public void convertToSetMethod(Expression arg) {
      arg = doCastOnConvert(arg);
      
      int sz = identifiers.size();
      int ix = sz - 1;
      if (arrayDimensions == null)
         super.convertToSetMethod(arg);
      // x[0] = v -> setX(0, v)
      else {
         // needsSetMethod won't let us get here if this is not true
         // In the > 1 case, we don't have an indexed setter so we just convert to a get method
         assert arrayDimensions.size() == 1;
         
         IdentifierExpression newExpr = IdentifierExpression.create(identifiers.toArray(new IString[sz]));
         newExpr.identifiers.set(ix, PString.toIString(convertPropertyToSetName(identifiers.get(ix).toString())));
         newExpr.setProperty("arguments", new SemanticNodeList());
         newExpr.arguments.add(arrayDimensions.get(0));
         newExpr.arguments.add(arg);
         ParseUtil.stopComponent(this);
         parentNode.replaceChild(this, newExpr);
      }
   }

   public String getBindingTypeName() {
      return nestedBinding ? "arrayElementP" : "arrayElement";
   }

   /**
    * Produces binding arguments for the operator and the two nested bindings
    */
   public void transformBindingArgs(SemanticNodeList<Expression> bindArgs, BindDescriptor bd) {
      super.transformBindingArgs(bindArgs, bd);
      bindArgs.add(createBindingParameters(false, arrayDimensions.toArray(new Expression[arrayDimensions.size()])));
   }

   public void evalBindingArgs(List<Object> bindArgs, boolean isStatic, Class expectedType, ExecutionContext ctx) {
      super.evalBindingArgs(bindArgs, isStatic, expectedType, ctx);
      bindArgs.add(evalBindingParameters(expectedType, ctx, arrayDimensions.toArray(new Expression[arrayDimensions.size()])));
   }

   /**
    * Propagates the binding information to nested expressions
    */
   public void setBindingInfo(BindingDirection dir, Statement dest, boolean nested) {
      super.setBindingInfo(dir, dest, nested);
      for (Expression e:arrayDimensions)
         e.setBindingInfo(bindingDirection, bindingStatement, true);
   }

   public void visitTypeReferences(CycleInfo info, TypeContext ctx) {
      info.visitList(arrayDimensions, ctx);
      super.visitTypeReferences(info, ctx);
   }

   public void styleNode(IStyleAdapter adapter) {
      super.styleNode(adapter);
      // These get styled as the arguments in the identifier expression
      //arrayDimensions.styleNode(adapter);
   }

   public void refreshBoundTypes(int flags) {
      super.refreshBoundTypes(flags);
      if (arrayDimensions != null)
         for (Expression ex:arrayDimensions)
            ex.refreshBoundTypes(flags);
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      ix = super.transformTemplate(ix, statefulContext);
      if (arrayDimensions != null)
         for (Expression ex:arrayDimensions)
            ix = ex.transformTemplate(ix, statefulContext);
      return ix;
   }


   public Statement transformToJS() {
      super.transformToJS();
      if (arrayDimensions != null)
         for (Expression ex:arrayDimensions)
            ex.transformToJS();
      return this;
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      sb.append(super.toGenerateString());
      if (arrayDimensions != null) {
         for (Expression arrDim:arrayDimensions) {
            sb.append("[");
            sb.append(arrDim.toGenerateString());
            sb.append("]");
         }
      }
      return sb.toString();
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement srcStatement) {
      super.addBreakpointNodes(res, srcStatement);
      if (arrayDimensions != null) {
         for (Expression dim:arrayDimensions) {
            if (dim != null)
               dim.addBreakpointNodes(res, srcStatement);
         }
      }
   }
}
