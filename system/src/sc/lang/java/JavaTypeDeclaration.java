/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.SemanticNode;
import sc.lang.sc.ModifyDeclaration;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.type.CTypeUtil;
import sc.type.DynType;
import sc.type.RTypeUtil;
import sc.type.TypeUtil;
import sc.util.StringUtil;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** This is a hook point for external implementations of the Java type system - like CFClass - a wrapper around the class format and IntelliJs PsiClass */
public abstract class JavaTypeDeclaration extends SemanticNode implements ITypeDeclaration {
   public LayeredSystem system;

   public JavaTypeDeclaration(LayeredSystem sys) {
      system = sys;
   }

   public JavaModel getJavaModel() {
      return null;
   }

   public boolean isLayerType() {
      return false;
   }

   public boolean isLayerComponent() {
      return false;
   }

   public Layer getLayer() {
      return null;
   }

   public LayeredSystem getLayeredSystem() {
      return system;
   }

   public boolean isAssignableFromClass(Class other) {
      String otherName = TypeUtil.getTypeName(other, true);
      if (otherName.equals(getFullTypeName()))
         return true;
      Class superType = other.getSuperclass();
      if (superType != null && isAssignableFromClass(superType))
         return true;
      Class[] ifaces = other.getInterfaces();
      if (ifaces != null) {
         for (Class c:ifaces)
            if (isAssignableFromClass(c))
               return true;
      }
      // Handles the int implements Comparable when Comparable is a CFClass
      if (other.isPrimitive()) {
         Class clWrapper = sc.type.Type.get(other).getObjectClass();
         if (isAssignableFromClass(clWrapper))
            return true;
      }
      return false;
   }

   public String getTypeName() {
      return CTypeUtil.getClassName(getFullTypeName());
   }

   public String getFullTypeName(boolean includeDims, boolean includeTypeParams) {
      return getFullTypeName(); // Can't have dims or type parameters with bound values
   }

   public Class getCompiledClass() {
      return system.getCompiledClassWithPathName(getFullTypeName());
   }

   public String getCompiledClassName() {
      return getFullTypeName();
   }

   public String getCompiledTypeName() {
      return getFullTypeName();
   }

   public Object getRuntimeType() {
      return getCompiledClass();
   }

   public boolean isDynamicType() {
      return false;
   }

   public boolean isDynamicStub(boolean includeExtends) {
      return false;
   }

   public Object declaresConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx) {
      return definesConstructor(parametersOrExpressions, ctx, false);
   }

   public Object definesMember(String name, EnumSet<JavaSemanticNode.MemberType> mtype, Object refType, TypeContext ctx) {
      return definesMember(name, mtype, refType, ctx, false, false);
   }

   public abstract boolean isInterface();

   public abstract boolean isEnum();

   public DeclarationType getDeclarationType() {
      if (ModelUtil.isObjectType(this))
         return DeclarationType.OBJECT;
      if (isInterface())
         return DeclarationType.INTERFACE;
      if (isEnum())
         return DeclarationType.ENUM;
      return DeclarationType.CLASS;
   }

   public boolean isRealType() {
      return true;
   }

   public void staticInit() {
   }

   public boolean isTransformedType() {
      return false;
   }

   @Override
   public ITypeDeclaration resolve(boolean modified) {
      return this; // TODO: should we support replacing these?
   }

   public boolean useDefaultModifier() {
      return false;
   }

   public void setAccessTimeForRefs(long time) {
   }

   public void setAccessTime(long time) {
   }

   public List<JavaType> getCompiledTypeArgs(List<JavaType> typeArgs) {
      return typeArgs;
   }

   public boolean needsOwnClass(boolean checkComponents) {
      return true;
   }

   public boolean isDynamicNew() {
      return false;
   }

   public void initDynStatements(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode) {
   }

   public void clearDynFields(Object inst, ExecutionContext ctx) {
   }

   public Object[] getAllImplementsTypeDeclarations() {
      return getImplementsTypeDeclarations();
   }

   public boolean isComponentType() {
      return implementsType("sc.obj.IComponent", false, false);
   }

   // This is a runtime only data structure so should be based off of the compiled class
   public DynType getPropertyCache() {
      return TypeUtil.getPropertyCache(getCompiledClass());
   }

   public void validate() {
   }

   public boolean isValidated() {
      return true;
   }

   public void stop() {
   }

   public boolean isAssignableFrom(ITypeDeclaration other, boolean assignmentSemantics) {
      if (other instanceof ArrayTypeDeclaration)
         return false;
      return other.isAssignableTo(this);
   }

   public Object getDerivedTypeDeclaration() {
      return getExtendsTypeDeclaration();
   }

   abstract public List<Object> getImplementsList();

   public abstract String getInterfaceName(int ix);

   public abstract String getExtendsTypeName();

   public boolean isEnumeratedType() {
      return isEnum();
   }

   public Object getEnumConstant(String nextName) {
      return RTypeUtil.getEnum(getCompiledClass(), nextName);
   }

   public boolean isCompiledProperty(String name, boolean fieldMode, boolean interfaceMode) {
      return definesMember(name, JavaSemanticNode.MemberType.PropertyGetSetObj, null, null) != null;
   }

   public boolean isAssignableTo(ITypeDeclaration other) {
      if (other instanceof ArrayTypeDeclaration)
         return false;

      if (!started)
         start();

      String otherName = other.getFullTypeName();

      if (otherName.equals(getFullTypeName()))
         return true;

      if (other instanceof ModifyDeclaration) {
         Object newType = other.getDerivedTypeDeclaration();
         if (this == newType)
            return true;
      }

      List<Object> implementsTypes = getImplementsList();
      if (implementsTypes != null) {
         int numInterfaces = implementsTypes.size();
         for (int i = 0; i < numInterfaces; i++) {
            String interfaceName = getInterfaceName(i);
            if (otherName.equals(interfaceName))
               return true;
            Object implType = implementsTypes.get(i);
            if (implType instanceof ITypeDeclaration) {
               if (((ITypeDeclaration) implType).isAssignableTo(other))
                  return true;
            }
            else if (implType instanceof Class && implType != Object.class) {
               if (ModelUtil.isAssignableFrom(other, implType))
                  return true;
            }
         }
      }
      Object extendsType = getExtendsTypeDeclaration();
      if (extendsType != null) {
         String extendsName = getExtendsTypeName();
         if (extendsName.equals(otherName))
            return true;
         if (extendsType instanceof ITypeDeclaration)
            return ((ITypeDeclaration) extendsType).isAssignableTo(other);
         else if (extendsType instanceof Class && extendsType != Object.class) {
            return ModelUtil.isAssignableFrom(other, extendsType);
         }
         // else - java.lang.Class
         // Classes here should be only java.lang classes and so should not match from this point on
      }
      return false;
   }

   public Object definesMethod(String name, List<?> parametersOrExpressions, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs) {
      if (!started)
         start();

      Object[] list = ModelUtil.getMethods(this, name, null);

      Object meth = null;

      if (list == null) {
         // Interfaces don't inherit object methods in Java but an inteface type in this system needs to still
         // implement methods like "toString" even if they are not on the interface.
         if (ModelUtil.isInterface(this)) {
            meth = ModelUtil.getMethod(system, Object.class, name, refType, null, inferredType, staticOnly, methodTypeArgs, parametersOrExpressions, null);
            if (meth != null)
               return meth;
         }
      }
      else {
         int typesLen = parametersOrExpressions == null ? 0 : parametersOrExpressions.size();
         Object[] prevExprTypes = null;
         ArrayList<Expression> toClear = null;
         for (int i = 0; i < list.length; i++) {
            Object toCheck = list[i];
            if (ModelUtil.getMethodName(toCheck).equals(name)) {
               Object[] parameterTypes = ModelUtil.getParameterTypes(toCheck);

               int paramLen = parameterTypes == null ? 0 : parameterTypes.length;
               if (staticOnly && !ModelUtil.hasModifier(toCheck, "static"))
                  continue;

               int last = paramLen - 1;
               if (paramLen != typesLen) {
                  int j;
                  // If the last guy is not a repeating parameter, it can't match
                  if (last < 0 || !ModelUtil.isVarArgs(toCheck) || !ModelUtil.isArray(parameterTypes[last]) || typesLen < last)
                     continue;
               }

               ParamTypedMethod paramMethod = null;
               if (ModelUtil.isParameterizedMethod(toCheck)) {

                  Object definedInType = refType != null ? refType : this;
                  if (ctx instanceof ParamTypeDeclaration) {
                     ParamTypeDeclaration paramCtx = (ParamTypeDeclaration) ctx;
                     if (paramCtx.getDefinedInType() != null)
                        definedInType = paramCtx.getDefinedInType();
                  }

                  paramMethod = new ParamTypedMethod(system, toCheck, ctx, definedInType, parametersOrExpressions, inferredType, methodTypeArgs);

                  parameterTypes = paramMethod.getParameterTypes(true);
                  toCheck = paramMethod;

                  // There was a conflict with the type parameters matching so the parameterTypes are not valid
                  if (paramMethod.invalidTypeParameter)
                     continue;
               }

               if (paramLen == 0 && typesLen == 0) {
                  if (refType == null || ModelUtil.checkAccess(refType, toCheck))
                     meth = ModelUtil.pickMoreSpecificMethod(meth, toCheck, null, null, null);
               }
               else {
                  int j;
                  Object[] nextExprTypes = new Object[typesLen];
                  for (j = 0; j < typesLen; j++) {
                     Object paramType;
                     if (j > last) {
                        if (!ModelUtil.isArray(paramType = parameterTypes[last]))
                           break;
                     }
                     else
                        paramType = parameterTypes[j];

                     Object exprObj = parametersOrExpressions.get(j);

                     if (exprObj instanceof Expression) {
                        if (paramType instanceof ParamTypeDeclaration)
                           paramType = ((ParamTypeDeclaration) paramType).cloneForNewTypes();
                        Expression paramExpr = (Expression) exprObj;
                        paramExpr.setInferredType(paramType, false);
                        if (toClear == null)
                           toClear = new ArrayList<Expression>();
                        toClear.add(paramExpr);
                     }

                     Object exprType = ModelUtil.getVariableTypeDeclaration(exprObj);
                     nextExprTypes[j] = exprType;

                     // Lambda inferred type is not valid so can't be a match
                     if (exprType instanceof BaseLambdaExpression.LambdaInvalidType)
                        break;

                     if (exprType != null && paramType != null && !ModelUtil.isAssignableFrom(paramType, exprType, false, ctx, system)) {
                        // Repeating parameters... if the last parameter is an array match if the component type matches
                        if (j >= last && ModelUtil.isArray(paramType) && ModelUtil.isVarArgs(toCheck)) {
                           if (!ModelUtil.isAssignableFrom(ModelUtil.getArrayComponentType(paramType), exprType, false, ctx)) {
                              break;
                           }
                        }
                        else
                           break;
                     }
                  }
                  if (j == typesLen) {
                     if (refType == null || ModelUtil.checkAccess(refType, toCheck)) {
                        Object newMeth = ModelUtil.pickMoreSpecificMethod(meth, toCheck, nextExprTypes, prevExprTypes, parametersOrExpressions);
                        if (newMeth != meth)
                           prevExprTypes = nextExprTypes;
                        meth = newMeth;
                     }
                  }
               }
               // Don't leave the inferredType lying around in the parameter expressions for when we start matching the next method.
               if (toClear != null) {
                  for (Expression clearExpr:toClear)
                     clearExpr.clearInferredType();
                  toClear = null;
               }
            }
         }
         if (meth != null)
            return meth;
      }

      Object superMeth = null;
      Object extendsType = getExtendsTypeDeclaration();
      if (extendsType != null) {
         // If necessary map the type variables in the base-types' declaration based on the type params in the context
         Object paramExtType = ParamTypeDeclaration.convertBaseTypeContext(ctx, extendsType);

         superMeth = ModelUtil.definesMethod(paramExtType, name, parametersOrExpressions, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs, getLayeredSystem());
      }
      List<Object> implementsTypes = getImplementsList();
      if (implementsTypes != null) {
         int numInterfaces = implementsTypes.size();
         for (int i = 0; i < numInterfaces; i++) {
            Object implType = implementsTypes.get(i);
            if (implType != null) {

               implType = ParamTypeDeclaration.convertBaseTypeContext(ctx, implType);
               meth = ModelUtil.definesMethod(implType, name, parametersOrExpressions, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs, getLayeredSystem());
               if (meth != null) {
                  superMeth = ModelUtil.pickMoreSpecificMethod(superMeth, meth, null, null, null);
               }
            }
         }
      }
      return superMeth;
   }

   public boolean implementsType(String otherName, boolean assignment, boolean allowUnbound) {
      if (!started)
         start();

      otherName = otherName.replace('$', '.');

      if (otherName.equals(getFullTypeName()))
         return true;

      List<Object> implementsTypes = getImplementsList();
      if (implementsTypes != null) {
         int numInterfaces = implementsTypes.size();
         for (int i = 0; i < numInterfaces; i++) {
            String interfaceName = getInterfaceName(i);
            if (otherName.equals(interfaceName))
               return true;
            Object implType = implementsTypes.get(i);
            if (implType != null && ModelUtil.implementsType(implType, otherName, assignment, allowUnbound))
               return true;
         }
      }
      Object extendsType = getExtendsTypeDeclaration();
      if (extendsType != null) {
         String extendsName = getExtendsTypeName();
         if (extendsName.equals(otherName))
            return true;
         return ModelUtil.implementsType(extendsType, otherName, assignment, allowUnbound);
      }
      return false;
   }

   public Object getInheritedAnnotation(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      Object annot = ModelUtil.getAnnotation(this, annotationName);
      if (annot != null)
         return annot;

      LayeredSystem sys = refLayer == null ? getLayeredSystem() : refLayer.layeredSystem;
      Object superType = getDerivedTypeDeclaration();
      // Look for an annotation layer that might be registered for this compiled class
      if (superType != null) {
         // In case we are using this type from a different layered system than it was created
         if (ModelUtil.isCompiledClass(superType)) {
            Object srcSuperType = ModelUtil.findTypeDeclaration(sys, ModelUtil.getTypeName(superType), refLayer, layerResolve);
            if (srcSuperType != null && srcSuperType != superType) {
               annot = ModelUtil.getInheritedAnnotation(sys, srcSuperType, annotationName, skipCompiled, refLayer, layerResolve);
               if (annot != null)
                  return annot;
            }
         }
         annot = ModelUtil.getInheritedAnnotation(sys, superType, annotationName, skipCompiled, refLayer, layerResolve);
         if (annot != null)
            return annot;
      }

      List<Object> implementsTypes = getImplementsList();
      if (implementsTypes != null) {
         int numInterfaces = implementsTypes.size();
         for (int i = 0; i < numInterfaces; i++) {
            Object implType = implementsTypes.get(i);
            if ((annot = ModelUtil.getInheritedAnnotation(sys, implType, annotationName, skipCompiled, refLayer, layerResolve)) != null)
               return annot;
         }
      }
      return null;
   }

   public ArrayList<Object> getAllInheritedAnnotations(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      Object annot = ModelUtil.getAnnotation(this, annotationName);
      ArrayList<Object> res = null;
      if (annot != null) {
         res = new ArrayList<Object>(1);
         res.add(annot);
      }

      LayeredSystem sys = refLayer == null ? system : refLayer.layeredSystem;

      Object superType = getDerivedTypeDeclaration();
      if (superType != null) {
         ArrayList<Object> superRes = ModelUtil.getAllInheritedAnnotations(sys, superType, annotationName, skipCompiled, refLayer, layerResolve);
         if (superRes != null)
            res = ModelUtil.appendLists(res, superRes);

         String nextTypeName = ModelUtil.getTypeName(superType);
         Object nextType = ModelUtil.findTypeDeclaration(sys, nextTypeName, refLayer, layerResolve);
         if (nextType != null && nextType instanceof TypeDeclaration && nextType != superType) {
            if (nextType == superType) {
               System.err.println("*** Loop in inheritance tree: " + nextTypeName);
               return null;
            }
            ArrayList<Object> newRes = ((TypeDeclaration) nextType).getAllInheritedAnnotations(annotationName, skipCompiled, refLayer, layerResolve);
            if (newRes != null)
               res = ModelUtil.appendLists(res, newRes);
         }
      }

      List<Object> implementsTypes = getImplementsList();
      if (implementsTypes != null) {
         int numInterfaces = implementsTypes.size();
         for (int i = 0; i < numInterfaces; i++) {
            Object implType = implementsTypes.get(i);
            if (implType != null) {
               ArrayList<Object> superRes;
               if ((superRes = ModelUtil.getAllInheritedAnnotations(sys, implType, annotationName, skipCompiled, refLayer, layerResolve)) != null) {
                  res = ModelUtil.appendLists(res, superRes);
               }

               String nextTypeName = ModelUtil.getTypeName(implType);
               Object nextType = ModelUtil.findTypeDeclaration(sys, nextTypeName, refLayer, layerResolve);
               if (nextType != null && nextType instanceof TypeDeclaration && nextType != implType) {
                  if (nextType == superType) {
                     System.err.println("*** Loop in interface inheritance tree: " + nextTypeName);
                     return null;
                  }
                  ArrayList<Object> newRes = ((TypeDeclaration) nextType).getAllInheritedAnnotations(annotationName, skipCompiled, refLayer, layerResolve);
                  if (newRes != null)
                     res = ModelUtil.appendLists(res, newRes);
               }
            }
         }
      }
      return res;
   }

   public Object getConstructorFromSignature(String sig) {
      Object[] cstrs = getConstructors(null);
      if (cstrs == null)
         return null;
      for (int i = 0; i < cstrs.length; i++) {
         Object constr = cstrs[i];
         if (StringUtil.equalStrings(((ConstructorDefinition) constr).getTypeSignature(), sig))
            return constr;
      }
      return null;
   }

   public Object getMethodFromSignature(String methodName, String signature, boolean resolveLayer) {
      List<Object> methods = getMethods(methodName, null, true);
      if (methods == null) {
         // Special case way to refer to the constructor
         if (methodName.equals(getTypeName()))
            return getConstructorFromSignature(signature);
         // TODO: default constructor?
         return null;
      }
      for (Object meth:methods) {
         if (StringUtil.equalStrings(ModelUtil.getTypeSignature(meth), signature)) {
            return meth;
         }
      }
      return null;
   }

   public Object getTypeDeclaration(String name, boolean compiledOnly) {
      if (system == null)
         return RTypeUtil.loadClass(name);
      Object newRes = system.getClassWithPathName(name, null, false, false, false, compiledOnly);
      return newRes;
   }

   public Object getClass(String className, boolean useImports, boolean compiledOnly) {
      return getTypeDeclaration(className, compiledOnly);
   }

}

