/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.BindingDirection;
import sc.dyn.DynUtil;
import sc.lang.ISrcStatement;
import sc.lang.JavaLanguage;
import sc.parser.ParseError;
import sc.parser.ParseUtil;
import sc.lang.ILanguageModel;
import sc.lang.SemanticNodeList;
import sc.util.StringUtil;

import java.lang.reflect.Array;
import java.util.*;

public class ArrayInitializer extends Expression {
   public SemanticNodeList<Expression> initializers;

   private transient Object inferredType;

   public void start() {
      super.start();
   }

   public boolean setInferredType(Object type, boolean finalType) {
      super.setInferredType(type, finalType);

      if (type == UnknownReferredType) {
         if (initializers != null) {
            for (Expression init:initializers) {
               init.setInferredType(type, finalType);
            }
         }
      }
      else {
         inferredType = type;

         if (type != null) {
            Object componentType = ModelUtil.getArrayComponentType(type);
            if (initializers != null && componentType != null) {
               for (Expression init:initializers) {
                  init.setInferredType(componentType, finalType);
               }

               for (Expression init:initializers) {
                  Object initType = init.getTypeDeclaration();
                  // Using assignment semantics to get more flexible type conversions
                  if (initType != null && !ModelUtil.isAssignableFrom(componentType, initType, true, null, getLayeredSystem())) {
                     displayError("Array element type mismatch - Expected " + ModelUtil.getTypeName(componentType) + " found: " + ModelUtil.getTypeName(initType) + " for: ");
                     initType = init.getTypeDeclaration();
                     boolean res = ModelUtil.isAssignableFrom(componentType, initType, true, null, getLayeredSystem());
                  }
               }
            }
         }
      }
      return false;
   }

   public boolean propagatesInferredType(Expression child) {
      return true;
   }

   public Object eval(Class expectedType, ExecutionContext ctx) {
      boolean expectedCollection = expectedType != null && Collection.class.isAssignableFrom(expectedType);
      if (initializers == null && bindingDirection == null) {
         if (expectedCollection)
            return createCollectionType(expectedType, 0);
         return expectedType == null || !expectedType.isArray() ? new Object[0] : Array.newInstance(expectedType.getComponentType(), 0);
      }

      if (bindingDirection != null) {
         return evalBinding(expectedType, ctx);
      }
      return getConstantValue(expectedType, ctx);
   }

   private static Collection createCollectionType(Class expectedType, int size) {
      if (expectedType == java.util.Set.class)
         return new HashSet(size);
      return expectedType == java.util.List.class || expectedType == java.util.ArrayList.class ? new ArrayList(size) : (List) DynUtil.createInstance(expectedType, "I", size);
   }

   public static Object initializeArray(Class expectedType, List<Expression> initializers, ExecutionContext ctx) {
      Object[] value;
      boolean expectsArray = expectedType != null && expectedType.isArray();
      Class componentType;
      int size = initializers.size();
      if (expectedType == null || !expectsArray) {
         if (expectedType != null && Collection.class.isAssignableFrom(expectedType)) {
            if (Collection.class.isAssignableFrom(expectedType)) {
               Collection listVal = createCollectionType(expectedType, size);
               // TODO: component type here from list type somehow?
               for (int i = 0; i < size; i++)
                  listVal.add(initializers.get(i).eval(null, ctx));
               return listVal;
            }
            else
               throw new IllegalArgumentException("Unable to initialize property of type: " + expectedType + " using array initializer syntax");
         }
         else {
            value = new Object[size];
            componentType = Object.class;
         }
      }
      else {
         componentType = expectedType.getComponentType();
         Object valueObj = Array.newInstance(componentType, size);
         if (!componentType.isPrimitive()) {
            value = (Object[]) valueObj;
         }
         else {
            if (componentType == Integer.TYPE) {
               int[] arr = (int[]) valueObj;
               for (int i = 0; i < size; i++)
                  arr[i] = (int) initializers.get(i).evalLong(componentType, ctx);
            }
            else if (componentType == Long.TYPE) {
               long[] arr = (long[]) valueObj;
               for (int i = 0; i < size; i++)
                  arr[i] = initializers.get(i).evalLong(componentType, ctx);
            }
            else if (componentType == Short.TYPE) {
               short[] arr = (short[]) valueObj;
               for (int i = 0; i < size; i++)
                  arr[i] = (short) initializers.get(i).evalLong(componentType, ctx);
            }
            else if (componentType == Byte.TYPE) {
               byte[] arr = (byte[]) valueObj;
               for (int i = 0; i < size; i++)
                  arr[i] = (byte) initializers.get(i).evalLong(componentType, ctx);
            }
            else if (componentType == Character.TYPE) {
               char[] arr = (char[]) valueObj;
               for (int i = 0; i < size; i++)
                  arr[i] = (char) initializers.get(i).evalLong(componentType, ctx);
            }
            else if (componentType == Double.TYPE) {
               double[] arr = (double[]) valueObj;
               for (int i = 0; i < size; i++)
                  arr[i] = initializers.get(i).evalDouble(componentType, ctx);
            }
            else if (componentType == Float.TYPE) {
               float[] arr = (float[]) valueObj;
               for (int i = 0; i < size; i++)
                  arr[i] = (float) initializers.get(i).evalDouble(componentType, ctx);
            }
            return valueObj;
         }
      }
      for (int i = 0; i < size; i++)
         value[i] = initializers.get(i).eval(componentType, ctx);
      return value;
   }

   public Object getConstantValue(Class expectedType, ExecutionContext ctx) {
      return initializeArray(expectedType, initializers, ctx);
   }

   public Object getTypeDeclaration() {
      Object initType = null;
      if (initializers == null)
         return null;
      for (Expression expr:initializers) {
         Object newType = expr.getTypeDeclaration();
         if (newType != NullLiteral.NULL_TYPE) {
            if (initType == null)
               initType = newType;
            else
               initType = ModelUtil.findCommonSuperClass(getLayeredSystem(), initType, newType);
         }
      }
      if (initType == null)
         return null; // TODO: do we need a new type for {null} - an array with null?
      if (inferredType == null || ModelUtil.isArray(inferredType))
         return ArrayTypeDeclaration.create(getLayeredSystem(), initType, "[]", getEnclosingType());
      else if (ModelUtil.isAssignableFrom(List.class, inferredType)) {
         //return new ParamTypeDeclaration(getEnclosingType(), ModelUtil.getTypeParameters(List.class), Collections.singletonList(initType), inferredType);
         return inferredType;
      }
      else if (ModelUtil.isAssignableFrom(Set.class, inferredType)) {
         //return new ParamTypeDeclaration(getEnclosingType(), ModelUtil.getTypeParameters(Set.class), Collections.singletonList(initType), inferredType);
         return inferredType;
      }
      else {
         return ArrayTypeDeclaration.create(getLayeredSystem(), initType, "[]", getEnclosingType());
      }
   }

   public void visitTypeReferences(CycleInfo info, TypeContext ctx) {
      info.visitList(initializers, ctx);
   }

   public static SemanticNodeList<Object> createAnnotationValue(Object literalValue) {
      int len = DynUtil.getArrayLength(literalValue);
      SemanticNodeList values = new SemanticNodeList(len);
      for (int i = 0; i < len; i++) {
         values.add(createFromValue(DynUtil.getArrayElement(literalValue, i), false));
      }
      return values;
   }

   public static ArrayInitializer create(Object literalValue) {
      ArrayInitializer arr = new ArrayInitializer();
      int len = Array.getLength(literalValue);
      SemanticNodeList values = new SemanticNodeList(len);
      for (int i = 0; i < len; i++) {
         values.add(createFromValue(DynUtil.getArrayElement(literalValue, i), false));
      }
      arr.setProperty("initializers", values);
      return arr;
   }

   public static ArrayInitializer create(SemanticNodeList<Expression> values) {
      ArrayInitializer arr = new ArrayInitializer();
      arr.setProperty("initializers", values);
      return arr;
   }

   public static ArrayInitializer createFromExprNames(int numExprs, StringBuilder exprString) {
      SemanticNodeList exprs;
      if (numExprs > 0) {
         JavaLanguage lang = JavaLanguage.getJavaLanguage();
         Object result = lang.parseString(exprString.toString(), lang.expressionList);
         if (result instanceof ParseError) {
            System.err.println("Unable to parse expression string: " + result);
            return null;
         }
         else {
            exprs = (SemanticNodeList) ParseUtil.nodeToSemanticValue(result);
         }
      }
      else
         exprs = new SemanticNodeList(0);

      ArrayInitializer ai = new ArrayInitializer();
      ai.setProperty("initializers", exprs);
      return ai;
   }

   public boolean refreshBoundTypes(int flags) {
      boolean res = false;
      if (initializers != null)
         for (Expression ex:initializers)
            if (ex.refreshBoundTypes(flags))
               res = true;
      return res;
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (initializers != null)
         for (Expression ex:initializers)
            ex.addDependentTypes(types, mode);
   }

   public void setAccessTimeForRefs(long time) {
      if (initializers != null)
         for (Expression ex:initializers)
            ex.setAccessTimeForRefs(time);
   }

   public String getBindingTypeName() {
      return nestedBinding ? "newArrayP" : "newArray";
   }

   Object getComponentType() {
      if (parentNode instanceof ITypedObject)
         return ((ITypedObject) parentNode).getTypeDeclaration();
      throw new UnsupportedOperationException();
   }

   public void setBindingInfo(BindingDirection dir, Statement dest, boolean nested) {
      super.setBindingInfo(dir, dest, nested);
      if (initializers != null) {
         for (Expression arg:initializers) {
            BindingDirection propBD;
            // Weird case:  convert reverse only bindings to a "none" binding for the arguments.
            // We do need to reflectively evaluate the parameters but do not listen on them like
            // in a forward binding.  If we propagate "REVERSE" down the chain, we get invalid errors
            // like when doing arithmetic in a reverse expr.
            if (bindingDirection.doReverse())
               propBD = bindingDirection.doForward() ? BindingDirection.FORWARD : BindingDirection.NONE;
            else
               propBD = bindingDirection;
            arg.setBindingInfo(propBD, bindingStatement, true);
         }
      }
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      if (parentNode instanceof NewExpression && bindingDirection != null) {
         initializers.transform(runtime);
         return true; // handled by NewExpression
      }
      return super.transform(runtime);
   }

   public void transformBindingArgs(SemanticNodeList<Expression> bindArgs, BindDescriptor bd) {
      // Up to the parent in this case
      if (parentNode instanceof NewExpression)
         return;

      bindArgs.add(ClassValueExpression.create(ModelUtil.getTypeName(getComponentType())));
      bindArgs.add(createBindingArray(initializers, false));
   }

   @Override
   public boolean isStaticTarget() {
      if (initializers == null)
         return true;
      for (Expression expr:initializers)
         if (!expr.isStaticTarget())
            return false;
      return true;
   }

   public void evalBindingArgs(List<Object> bindArgs, boolean isStatic, Class expectedType, ExecutionContext ctx) {
      bindArgs.add(ModelUtil.getRuntimeType(getComponentType()));
      bindArgs.add(evalBindingParameters(expectedType, ctx, initializers.toArray(new Expression[initializers.size()])));
   }

   public Statement transformToJS() {
      if (initializers != null)
         for (Expression expr:initializers)
           expr.transformToJS();
      return this;
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      String openArr = "{";
      String closeArr = "}";

      JavaLanguage javaLang = getJavaLanguage();
      if (javaLang != null) {
         openArr = javaLang.getOpenArray();
         closeArr = javaLang.getCloseArray();
      }
      sb.append(openArr);
      sb.append(" ");
      int i = 0;
      if (initializers != null) {
         int curLineLen = 0;
         int nestingDepth = getNestingDepth();
         for (Expression expr:initializers) {
            String childStr = expr == null ? "" : expr.toGenerateString();
            int childLen = childStr.length();
            if (i != 0) {
               if (curLineLen + childLen + nestingDepth * 3 > 80) {
                  sb.append(",\n        ");
                  sb.append(StringUtil.indent(nestingDepth));
                  curLineLen = 0;
               }
               else
                  sb.append(", ");
            }
            sb.append(childStr);
            curLineLen += childLen;
            i++;
         }
      }
      sb.append(" ");
      sb.append(closeArr);
      return sb.toString();
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement srcStatement) {
      super.addBreakpointNodes(res, srcStatement);
      if (initializers != null) {
         for (Expression expr:initializers) {
            if (expr != null)
               expr.addBreakpointNodes(res, srcStatement);
         }
      }
   }

   public int getNumDims() {
      Object type = getTypeDeclaration();
      return type == null ? 1 : ModelUtil.getArrayNumDims(type);
   }

   // TODO: This is here because of the new Type[] {} case - will it mess up plain old {}?
   public int getChildNestingDepth() {
      if (childNestingDepth != -1)
         return childNestingDepth;
      if (parentNode == null)
         return 1;
      return parentNode.getChildNestingDepth();
   }
}
