/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.BindSettings;
import sc.bind.IChangeable;
import sc.classfile.CFClass;
import sc.classfile.CFMethod;
import sc.db.*;
import sc.dyn.DynRemoteMethod;
import sc.dyn.IDynObject;
import sc.lang.*;
import sc.lang.html.Attr;
import sc.lang.html.Element;
import sc.lang.sc.PropertyAssignment;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.sc.OverrideAssignment;
import sc.lang.sql.DBProvider;
import sc.lang.sql.SQLUtil;
import sc.lang.template.Template;
import sc.lang.template.TemplateStatement;
import sc.layer.*;
import sc.obj.*;
import sc.sync.SyncDestination;
import sc.sync.SyncManager;
import sc.type.*;
import sc.util.*;
import sc.bind.BindingDirection;
import sc.classfile.CFField;
import sc.dyn.DynUtil;
import sc.dyn.RDynUtil;
import sc.parser.*;

import java.io.File;
import java.io.StringReader;
import java.lang.annotation.ElementType;
import java.lang.reflect.*;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.*;

import sc.lang.java.Statement.RuntimeStatus;

import javax.management.loading.MLet;

/**
 * This interface contains a wide range of operations on Java objects defined in either java.lang.Class, or ITypeDeclaration as the
 * primary types.  For ITypeDeclaration there are two primary implementations: CFClass - the .class file parser, or sc.lang.java.TypeDeclaration,
 * a main type for source files parsed in Java or StrataCode.
 * We use Object here as the core type to avoid the need to create wrapper types for java.lang.Class to implement ITypeDeclaration.
 * From a performance perspective, lots of wrapper classes is not efficient but it means we do more casting.  If it were only classes, it would
 * not be a good tradeoff but this same decision affects all meta-data: java.lang.reflect.Field etc. which are more numerous.  Casting and Object
 * provides some flexibility for such a core API from an integration perspective, especially if we use it with layers down the road to add new languages
 * to this base API.
 *
 * TODO: this class has gotten too big.  Are there any feature chunks we can break off to improve modularization?
 */
public class ModelUtil {
   public static final String CHILDREN_ANNOTATION = "Children";
   public static final String PARENT_ANNOTATION = "Parent";

   /** Flags to refreshBoundTypes */
   public static int REFRESH_CLASSES = 1;
   public static int REFRESH_TYPEDEFS = 2;
   public static int REFRESH_TRANSFORMED_ONLY = 4;

   private ModelUtil() {}

   /**
    * Remap the Class back into either a type or the same class.
    * If we get a Class from reflection, we might have a more specific version, such as with an annotation layer 
    */
   private static Object mapClassToType(Class t, JavaModel model) {
      String name = t.getName();
      name = name.replace("$", ".");
      if (model == null || t.isPrimitive())
         return t;
      Object res = model.findTypeDeclaration(name, false);
      if (res != null) {
         if (res != t && t.isPrimitive())
            System.err.println("*** invalid optimization!"); // TODO: optimization - skip this whole thing for primitives which we never override
         return res;
      }
      // certain array classes do not get resolved this way - e.g. [[Lsc.lang.java.DynStubParameters.DynConstructor;
      return t;
   }

   public static Object getVariableTypeDeclaration(Object varObj, JavaModel model) {
      Object type = getVariableTypeDeclaration(varObj);
      if (type instanceof Class)
         return mapClassToType((Class) type, model);
      return type;
   }

   // TODO: this does not return consistent things for arrays... ClassType.getTypeDeclaration strips off the array
   public static Object getVariableTypeDeclaration(Object varObj) {
      if (varObj instanceof Field)
         return ((Field) varObj).getType();
      else if (varObj instanceof ITypedObject) 
         return ((ITypedObject) varObj).getTypeDeclaration();
      else if (varObj instanceof IBeanMapper)
         return ((IBeanMapper) varObj).getGenericType();
      else if (varObj instanceof ITypeDeclaration)
         return varObj;
      else if (varObj instanceof Class)
         return varObj;
      else if (ModelUtil.isTypeVariable(varObj))
         return ModelUtil.getTypeParameterDefault(varObj);
      else if (varObj instanceof ParameterizedType)
         return varObj;
      throw new UnsupportedOperationException();
   }

   public static Object getVariableGenericTypeDeclaration(Object varObj, JavaModel model) {
      if (varObj instanceof Field) {
         try {
            return ((Field) varObj).getGenericType();
         }
         catch (Exception exc) {
            model.displayError("Failed to resolve generic type for : " + varObj.toString() + ": " + exc.toString());
            return null;
         }
      }
      else if (varObj instanceof ITypedObject)
         return ((ITypedObject) varObj).getTypeDeclaration();
      else if (varObj instanceof IBeanMapper)
         return ((IBeanMapper) varObj).getGenericType();
      else if (varObj instanceof ITypeDeclaration)
         return varObj;
      else if (varObj instanceof Class)
         return mapClassToType((Class) varObj, model);
      throw new UnsupportedOperationException();
   }

   public static Class getVariableClass(Object varObj) {
      return typeToClass(getVariableTypeDeclaration(varObj));
   }

   public static Map<TypeParamKey,Object> initMethodTypeParameters(Object[] typeParameters, Object[] genericParamTypes, Object[] resolvedParamTypes, List<Expression> arguments, Object retType, Object retInferredType) {
      if (typeParameters == null)
         return new HashMap<TypeParamKey,Object>(2);

      Map<TypeParamKey,Object> paramMap = new HashMap<TypeParamKey,Object>(2*typeParameters.length);
      for (Object tp:typeParameters) {
         Object boundType = ModelUtil.getTypeParameterDefault(tp);
         // This happens with undefined references
         //if (boundType == null || boundType.equals("<null>"))
         //   System.out.println("*** Null type parameter default");
         if (boundType instanceof ExtendsType.WildcardTypeDeclaration)
            continue;
         // When we have a TypeParameter that conveys type info, pass along the TypeParameter so we don't lose any information
         if (tp instanceof TypeParameter && ((TypeParameter) tp).extendsType != null)
            boundType = tp;
         if (boundType != Object.class)
            paramMap.put(new TypeParamKey(tp), boundType);
      }


      // For Java 7 and Java 8 the targetType or inferredType of the method call can bind more parameters
      // Need to do this before we process the parameters, since if a type parameter is bound by a parameter it will replace the
      // return type (see test.methParam for a simple example that requires this order)
      if (retInferredType != null && retType != null) {
         addTypeParamDefinitions(paramMap, retType, retInferredType, retInferredType);
      }

      int argSize = arguments.size();

      if (genericParamTypes != null) {

         // Resolve any type parameters we can from the types of the arguments to the method
         for (int i = 0; i < genericParamTypes.length; i++) {
            Object genParam = genericParamTypes[i];
            Object resolvedParam = resolvedParamTypes[i];

            boolean isGenArray = ModelUtil.isGenericArray(genParam);

            Object argType;
            Object paramArgType;
            if (i >= argSize) {
               break;
            } else {
               Expression argExpr = arguments.get(i);
               argType = argExpr.getGenericType();
               paramArgType = argExpr.getGenericArgumentType();
            }
            if (argType == null || argType == NullLiteral.NULL_TYPE)
               continue;

            boolean repeating = false;
            if (isGenArray && i == genericParamTypes.length - 1) {
               if (ModelUtil.isGenericArray(genParam) && !ModelUtil.isArray(argType)) {
                  genParam = ModelUtil.getGenericComponentType(genParam);
                  repeating = true;
               }
            }

            if (!repeating) {
               while (isGenArray) {
                  genParam = ModelUtil.getGenericComponentType(genParam);
                  argType = ModelUtil.getArrayComponentType(argType);
                  if (argType == null)
                     break;
                  isGenArray = ModelUtil.isGenericArray(genParam);
               }
            }

            addTypeParamDefinitions(paramMap, genParam, argType, paramArgType);
         }
      }

      return paramMap;
   }

   private static void addTypeParamDefinitions(Map<TypeParamKey,Object> paramMap, Object genParam, Object argType, Object paramArgType) {
      if (ModelUtil.isTypeVariable(genParam)) {
         paramMap.put(new TypeParamKey(genParam), argType);
      }
      else if (ModelUtil.isGenericArray(genParam)) {
         Object paramComponentType = ModelUtil.getGenericComponentType(genParam);
         // Sometimes we get an Object type which is a valid array type but does not add any info to the type parameter so just ignore it
         if (ModelUtil.isTypeVariable(paramComponentType) && argType != Object.class) {
            Object newComponentType = ModelUtil.getGenericComponentType(argType);
            if (!(newComponentType instanceof ExtendsType.WildcardTypeDeclaration))
               paramMap.put(new TypeParamKey(paramComponentType), newComponentType);
         }
      }
      else if (genParam instanceof ExtendsType.LowerBoundsTypeDeclaration) {
         // TODO: I don't think there is anything we need to do here.
         //if (!ModelUtil.isUnboundSuper(genParam))
         //   System.out.println("*** Unkown extends lower bounds type");
      }
      else if (ModelUtil.isParameterizedType(genParam)) {
         int numTypeParams = getNumTypeParameters(genParam);
         for (int atpIndex = 0; atpIndex < numTypeParams; atpIndex++) {
            Object atp = getTypeParameter(genParam, atpIndex);
            Object typeVar = getTypeVariable(genParam, atpIndex);

            if (ModelUtil.isTypeVariable(atp)) {
               String argParamName = ModelUtil.getTypeParameterName(atp);
               Object curParamType = paramMap.get(new TypeParamKey(atp));
               // Special case - Class<E> - e.g. <E extends Enum<E>> EnumSet<E> allOf(Class<E> elementType)
               if (ModelUtil.getTypeName(genParam).equals("java.lang.Class") && DynUtil.isType(argType)) {
                  // assert curParamType.isAssignableFrom(atp)
                  if (curParamType != null && ModelUtil.isAssignableFrom(curParamType, paramArgType)) {
                     // Only do this rule when the param type is a real class.  If it's a type parameter, it's not the right mapping to apply this special rule
                     if (!ModelUtil.isTypeVariable(curParamType)) {
                        argType = paramArgType;
                        if (!(argType instanceof ExtendsType.WildcardTypeDeclaration))
                           paramMap.put(new TypeParamKey(atp), argType);
                     }
                     else {
                        //System.out.println("***");
                     }
                  }
               }
               else {
                  if (hasTypeParameters(argType)) {
                     if (ModelUtil.isAssignableFrom(genParam, argType)) {
                        Object resTypeParam = resolveTypeParameter(genParam, argType, typeVar);
                        if (resTypeParam != null && ModelUtil.isTypeVariable(resTypeParam)) {
                           int resPos = ModelUtil.getTypeParameterPosition(argType, ModelUtil.getTypeParameterName(resTypeParam));
                           Object resTypeValue = ModelUtil.getTypeParameter(argType, resPos);
                           if (!(resTypeValue instanceof ExtendsType.WildcardTypeDeclaration) && resTypeValue != null)
                              paramMap.put(new TypeParamKey(atp), resTypeValue);
                        }
                        else if (resTypeParam != null && !ModelUtil.isTypeVariable(resTypeParam)) {
                           if (!(resTypeParam instanceof ExtendsType.WildcardTypeDeclaration))
                              paramMap.put(new TypeParamKey(atp), resTypeParam);
                        }
                     }
                     else {
                        // This can happen for the return type because the inferred type is a base-type of genParam.  Should we be extracting parameters in this case?
                     }
                  }
                  // else - could be a method parameter
               }
            }
            else {
               if (ModelUtil.isTypeVariable(typeVar)) {
                  paramMap.put(new TypeParamKey(typeVar), atp);
               }
            }
         }
         if (genParam instanceof ParamTypeDeclaration) {
            ParamTypeDeclaration ptd = ((ParamTypeDeclaration) genParam);
            ptd.addMappedTypeParameters(paramMap);
         }
      }

   }

   public static boolean isGenericArray(Object genArray) {
      return genArray instanceof GenericArrayType ||
            (genArray instanceof ArrayTypeDeclaration && ((ArrayTypeDeclaration) genArray).isTypeParameter());
   }

   public static Object getGenericComponentType(Object genParam) {
      if (ModelUtil.hasTypeParameters(genParam))
         genParam = ModelUtil.getParamTypeBaseType(genParam);
      if (genParam instanceof GenericArrayType)
         return ((GenericArrayType) genParam).getGenericComponentType();
      else if (genParam instanceof ArrayTypeDeclaration)
         return ((ArrayTypeDeclaration) genParam).componentType;
      else if (genParam instanceof Class) {
         Class arrClass = (Class) genParam;
         if (arrClass.isArray())
            return arrClass.getComponentType();
      }
      throw new UnsupportedOperationException();
   }

   public static Object getMethodTypeDeclaration(Object typeContext, Object varObj, List<Expression> arguments, LayeredSystem sys, JavaModel model, Object inferredType, Object definedInType) {
      Object type = getMethodTypeDeclarationInternal(typeContext, varObj, arguments, sys, model, inferredType, definedInType);
      if (type instanceof Class)
         return mapClassToType((Class) type, model);
      return type;
   }

   private static Object getMethodTypeDeclarationInternal(Object typeContext, Object varObj, List<Expression> arguments, LayeredSystem sys, JavaModel model, Object inferredType, Object definedInType) {
      if (varObj == null)
         return null;
      if (varObj instanceof Method || varObj instanceof IMethodDefinition) {
         Object[] tps = ModelUtil.getMethodTypeParameters(varObj);

         Object genRetType = ModelUtil.getParameterizedReturnType(varObj, arguments, false);
         if (tps != null && tps.length > 0) {
            // The resolve parameter here must be false - we need the type parameters here so we can figure out how to
            // bind them to the concrete values.
            Object[] genParamTypes = ModelUtil.getGenericParameterTypes(varObj, false);
            Object[] resolvedParamTypes = ModelUtil.getGenericParameterTypes(varObj, true);

            boolean isTypeVariable = ModelUtil.isTypeVariable(genRetType);

            // Refine the return type using the type parameters as a guide.  First we bind the
            // type parameters to the arguments supplied, then resolve the type and map that to our
            // return type.
            boolean isParamType = hasTypeParameters(genRetType);
            boolean isArrayType = isGenericArray(genRetType);
            if (isParamType || isArrayType || isTypeVariable) {
               Map<TypeParamKey,Object> paramMap = initMethodTypeParameters(tps, genParamTypes, resolvedParamTypes, arguments, genRetType, inferredType);

               if (isTypeVariable) {
                  String retParamName = ModelUtil.getTypeParameterName(genRetType);
                  Object retParamType = paramMap.get(new TypeParamKey(genRetType));
                  if (retParamType != null) {
                     genRetType = retParamType;
                     isParamType = hasTypeParameters(genRetType);
                  }
               }

               Object retType = genRetType;
               int ndim = 0;

               if (isArrayType) {
                  Object componentType = retType;
                  while (ModelUtil.isGenericArray(componentType)) {
                     componentType = ModelUtil.getGenericComponentType(componentType);
                     ndim++;
                  }
                  retType = componentType;
               }

               if (isParamType) {
                  // Parameterized types: Match the names of the parameters with their values in the map
                  // e.g. public static <T> List<T> asList(T... a)

                  int ntps = getNumTypeParameters(retType);
                  List<Object> typeDefs = new ArrayList<Object>(ntps);
                  if (sys == null && model != null)
                     sys = model.getLayeredSystem();
                  for (int i = 0; i < ntps; i++) {
                     Object typeParam = getTypeParameter(retType, i);
                     if (ModelUtil.isTypeVariable(typeParam)) {
                        String paramName = ModelUtil.getTypeParameterName(typeParam);
                        TypeParamKey paramKey = new TypeParamKey(typeParam);
                        Object paramMappedType = paramMap.get(paramKey);

                        // If the parameter is like G extends T we still need to lookup T to get the value
                        if (paramMappedType != null && ModelUtil.isTypeVariable(paramMappedType)) {
                           if (varObj instanceof ParamTypedMethod) {
                              Object paramMappedValue = ((ParamTypedMethod) varObj).getTypeForVariable(paramMappedType, true);
                              if (paramMappedValue != null)
                                 paramMappedType = paramMappedValue;
                           }
                           else
                              System.out.println("*** Warning - unhandled case getting method type");
                        }

                        Object argMappedType = getTypeDeclFromType(typeContext, typeParam, false, sys, false, definedInType, null, -1);
                        if (paramMappedType == null) {
                           if (argMappedType != null) {
                              paramMappedType = argMappedType;
                              if (!ModelUtil.isTypeVariable(argMappedType)) {
                                 // assert paramMappedType != null && paramMappedType.isAssignableFrom(argMappedType)
                                 paramMap.put(paramKey, argMappedType);
                              }
                           }
                        }
                        typeDefs.add(paramMappedType);
                     }
                     else {
                        // We might have type variables defined at this level which we can substitute in this type param declaration
                        if (typeParam instanceof ParamTypeDeclaration && paramMap.size() > 0) {
                           typeParam = ((ParamTypeDeclaration) typeParam).mapTypeParameters(paramMap);
                        }
                        Object res = getTypeDeclFromType(typeContext, typeParam, false, sys, false, definedInType, null, -1);
                        typeDefs.add(res);
                     }
                  }
                  List<?> typeParams = getTypeParameters(getTypeDeclFromType(typeContext, retType, true, sys, false, definedInType, null, -1));
                  Object baseParamType = ModelUtil.getParamTypeBaseType(retType);
                  ParamTypeDeclaration resType;
                  if (definedInType != null)
                     resType = new ParamTypeDeclaration(sys, definedInType, typeParams, typeDefs, baseParamType);
                  else
                     resType = new ParamTypeDeclaration(sys, typeParams, typeDefs, baseParamType);
                  if (varObj instanceof ParamTypedMethod && ((ParamTypedMethod) varObj).unboundInferredType)
                     resType.unboundInferredType = true;
                  return resType;
               }

               if (retType != null && isTypeVariable(retType)) {
                  Object paramType = paramMap.get(new TypeParamKey(retType));
                  if (ndim == 0)
                     return paramType;
                  if (paramType == null) {
                     paramType = retType;
                  }
                  return new ArrayTypeDeclaration(sys, definedInType, paramType, StringUtil.repeat("[]", ndim));
               }
               if (retType != null && isTypeVariable)
                  return retType;
            }
         }
         if (varObj instanceof Method) {
            return getTypeDeclFromType(typeContext, genRetType, false, model == null ? null : model.getLayeredSystem(), false, definedInType, null, -1);
         }
         // Note: we are passing the arguments in here but I think they are only used for the case handled above.  There's code to handle type parameters
         // in there, but not parameters defined at the method level.
         else {
            return getTypeDeclFromType(typeContext, ((IMethodDefinition) varObj).getTypeDeclaration(arguments, false), false, model == null ? null : model.getLayeredSystem(), false, definedInType, null, -1);
         }
      }
      // MethodDefinition implements ITypedObject - don't reorder
      else  if (varObj instanceof ITypedObject)
         return ((ITypedObject) varObj).getTypeDeclaration();
      else if (varObj instanceof IBeanMapper)
         return getTypeDeclFromType(typeContext, ((IBeanMapper) varObj).getGenericType(), false, model.getLayeredSystem(), false, definedInType, null, -1);
      // Weird case - when you have a newX method which resolves against an untransformed reference - i.e. X has no newX method, it resolves to X.
      else if (varObj instanceof ITypeDeclaration)
         return varObj;
      throw new UnsupportedOperationException();
   }

   public static Object getTypeDeclFromType(Object typeContext, Object type, boolean classOnly, LayeredSystem sys, boolean bindUnboundParams, Object definedInType, Object parentType, int parentParamIx) {
      if (type instanceof Class)
         return type;
      else if (ModelUtil.hasTypeParameters(type)) {
         if (classOnly)
            return getTypeDeclFromType(typeContext, ModelUtil.getParamTypeBaseType(type), true, sys, bindUnboundParams, definedInType, parentType, parentParamIx);
         else {
            int numTypeParameters = ModelUtil.getNumTypeParameters(type);
            Object[] types = new Object[numTypeParameters];
            for (int i = 0; i < numTypeParameters; i++) {
               types[i] = getTypeDeclFromType(typeContext, ModelUtil.getTypeParameter(type, i), classOnly, sys, bindUnboundParams, definedInType, type, i);
               if (ModelUtil.hasTypeVariables(types[i]) && bindUnboundParams) {
                  // If we do this in a simple method declaration, we lose the type parameter - later in some cases we need that type parameter
                  // to bind it to the type context for the method invocation.
                  types[i] = ModelUtil.getTypeParameterDefault(types[i]);
                  //types[i] = getTypeDeclFromType(typeContext, ModelUtil.getTypeParameter(type, i), classOnly, sys);
               }
            }
            List<?> typeParams = getTypeParameters(getTypeDeclFromType(typeContext, type, true, sys, bindUnboundParams, definedInType, parentType, parentParamIx));
            Object baseType = ModelUtil.getParamTypeBaseType(type);
            ParamTypeDeclaration res;
            if (definedInType != null) {
               res = new ParamTypeDeclaration(sys, definedInType, typeParams, Arrays.asList(types), baseType);
            }
            else {
               res = new ParamTypeDeclaration(sys, typeParams, Arrays.asList(types), baseType);
            }
            res.writable = true;
            return res;
         }
      }
      else if (type instanceof GenericArrayType) {
         int ndim = 1;
         GenericArrayType gat = (GenericArrayType) type;
         Type compType;
         while ((compType = gat.getGenericComponentType()) instanceof GenericArrayType) {
            ndim++;
            gat = (GenericArrayType) gat.getGenericComponentType();
         }
         int[] dims = new int[ndim];
         Object compTypeDecl = getTypeDeclFromType(typeContext, compType, true, sys, bindUnboundParams, definedInType, null, -1);
         // Do not get the default here - need to signal that this is unbound
         if (bindUnboundParams && ModelUtil.hasTypeVariables(compTypeDecl))
            compTypeDecl = ModelUtil.getTypeParameterDefault(compTypeDecl);
         if (compTypeDecl instanceof ParamTypeDeclaration || ModelUtil.isTypeVariable(compTypeDecl)) {
            Object dit = null;
            // TODO: Should we perhaps always use definedInType here for the ArrayTypeDeclarations?
            if (typeContext instanceof ParameterizedType) {
               if (definedInType != null)
                  dit = definedInType;
               else
                  System.err.println("*** No defined in type for array type declaration");
            }
            else
               dit = (ITypeDeclaration) ModelUtil.getEnclosingType(typeContext);
            if (dit == null)
               dit = definedInType;
            return ArrayTypeDeclaration.create(sys, compTypeDecl, ndim, dit);
         }
         return Array.newInstance((Class) compTypeDecl, dims).getClass();
      }
      else if (ModelUtil.isTypeVariable(type)) {
         String tvName = ModelUtil.getTypeParameterName(type);
         if (typeContext != null) {
            Object genericType = typeContext instanceof ITypeParamContext ? typeContext : getParameterizedType(typeContext, JavaSemanticNode.MemberType.Field);
            if (genericType != null) {
               // Either a ParamTypeDeclaration or a ParamTypedMethod
               Object paramDecl = ModelUtil.getTypeParameterDeclaration(type);
               if (ModelUtil.typeContextsMatch(paramDecl, genericType)) {
                  if (genericType instanceof ITypeParamContext) {
                     return ((ITypeParamContext) genericType).getTypeDeclarationForParam(tvName, type, bindUnboundParams);
                  }
                  if (ModelUtil.hasTypeParameters(genericType)) {
                     int numTypeParams = ModelUtil.getNumTypeParameters(genericType);
                     int pix;
                     Object typeParam = null;
                     for (pix = 0; pix < numTypeParams; pix++) {
                        typeParam = ModelUtil.getTypeVariable(genericType, pix);
                        if (ModelUtil.isTypeVariable(typeParam) && ModelUtil.getTypeParameterName(typeParam).equals(tvName))
                           break;
                     }


                     if (pix != numTypeParams)
                        return ModelUtil.getTypeParameter(genericType, pix);
                     // else - in some cases, the type variable is just not bound, like when we are inside of a parameterized class.
                  }
                  else {
                     // No type parameters were supplied so this needs to be returned as unbound
                     if (!bindUnboundParams)
                        return type;
                     else
                        return getTypeParameterDefault(type);
                  }
               }
            }
         }

         return type; // Do not return the default - need to signal that this is an unbound type parameter - i.e. do not print an error if it does not match - e.g. <T> Collections.emptyList()

      }
      else if (ModelUtil.isWildcardType(type)) {
         // The super case does not act like a regular type.
         if (ModelUtil.isSuperWildcard(type)) {
            Object lowerBounds = getWildcardLowerBounds(type);
            type = getTypeDeclFromType(typeContext, lowerBounds, classOnly, sys, bindUnboundParams, definedInType, parentType, parentParamIx);
            if (type instanceof ExtendsType.LowerBoundsTypeDeclaration)
               return type;
            // Need to resolve the type parameters here and return a LowerBoundTD that points to the resolved type paraemters
            return new ExtendsType.LowerBoundsTypeDeclaration(sys, type);
         }
         else {
            Object upperBounds = getWildcardUpperBounds(type);
            if ((upperBounds == null || upperBounds == Object.class) && parentType != null && parentParamIx != -1) {
               Object parentBaseType = ModelUtil.getParamTypeBaseType(parentType);
               Object classTypeParam = ModelUtil.getTypeParameter(parentBaseType, parentParamIx);
               if (ModelUtil.isTypeVariable(classTypeParam))
                  upperBounds = ModelUtil.getTypeParameterDefault(classTypeParam);
            }
            return getTypeDeclFromType(typeContext, upperBounds, classOnly, sys, bindUnboundParams, definedInType, parentType, parentParamIx);
         }
      }
      else if (type instanceof ExtendsType.LowerBoundsTypeDeclaration) {
         return type;
      }
      else if (type instanceof ITypeDeclaration)
         return type;
      else if (type instanceof WildcardType) {
         // TODO: need to figure out how to really deal with this
      }
      else if (type instanceof JavaType) {
         return ((JavaType) type).getTypeDeclaration();
      }
      if (type == null)
         return null;
      else
         throw new UnsupportedOperationException();
   }

   private static boolean typeContextsMatch(Object paramDecl, Object genericType) {
      if (ModelUtil.isMethod(paramDecl)) {
         if (ModelUtil.isMethod(genericType))
            return overridesMethod(paramDecl, genericType);
         return false;
      }
      else {
         if (ModelUtil.isMethod(genericType))
            return false;
         return ModelUtil.isAssignableFrom(paramDecl, genericType);
      }
   }

   public static Object getTypeParameterDeclaration(Object type) {
      if (type instanceof TypeVariable)
         return ((TypeVariable) type).getGenericDeclaration();
      else if (type instanceof TypeParameter) {
         return ((TypeParameter) type).getGenericDeclaration();
      }
      else
         throw new UnsupportedOperationException();
   }

   public static Object[] getAllMethods(Object type, String modifier, boolean hasModifier, boolean isDyn, boolean overridesComp) {
      if (type instanceof Class) {
         if (isDyn || overridesComp)
            return null;
         return RTypeUtil.getMethodsWithModifier((Class) type, modifier, hasModifier);
      }
      else if (type instanceof ITypeDeclaration) {
         List<Object> methods = ((ITypeDeclaration) type).getAllMethods(modifier, hasModifier, isDyn, overridesComp);
         return methods == null ? null : methods.toArray(new Object[methods.size()]);
      }
      else if (type instanceof ParameterizedType) {
         return getAllMethods(((ParameterizedType) type).getRawType(), modifier, hasModifier, isDyn, overridesComp);
      }
      else
         return null;
   }

   public static Object[] getMethods(Object type, String methodName, String modifier) {
      return getMethods(type, methodName, modifier, true);
   }

   public static Object[] getMethods(Object type, String methodName, String modifier, boolean includeExtends) {
      if (type instanceof Class)
         return RTypeUtil.getMethods((Class) type, methodName, modifier);
      else if (type instanceof ITypeDeclaration) {
         List<Object> methods = ((ITypeDeclaration) type).getMethods(methodName, modifier, includeExtends);
         return methods == null ? null : methods.toArray(new Object[methods.size()]);
      }
      else
         throw new UnsupportedOperationException();
   }

   public static boolean overridesMethod(Object subTypeMethod, Object superTypeMethod) {
      String subName = ModelUtil.getMethodName(subTypeMethod);
      String superName = ModelUtil.getMethodName(superTypeMethod);
      if (!StringUtil.equalStrings(subName, superName))
         return false;

      Object[] subParamTypes = ModelUtil.getParameterTypes(subTypeMethod, true);
      Object[] superParamTypes = ModelUtil.getParameterTypes(superTypeMethod, true);

      int numSub = subParamTypes == null ? 0 : subParamTypes.length;
      int numSuper = superParamTypes == null ? 0 : superParamTypes.length;
      // null-null
      if (numSub != numSuper)
         return false;

      for (int i = 0; i < numSub; i++) {
         if (!isAssignableTypesFromOverride(superParamTypes[i], subParamTypes[i]))
            return false;
      }
      return true;
   }

   public static Object getTypeDeclarationFromJavaType(Object javaType) {
      if (javaType instanceof java.lang.reflect.Type)
         return typeToClass(javaType);
      else if (javaType instanceof ITypedObject)
         return ((ITypedObject) javaType).getTypeDeclaration();
      throw new UnsupportedOperationException();
   }

   public static Class[] typeToClassArray(JavaType[] types) {
      if (types == null || types.length == 0)
         return null;

      Class[] cls = new Class[types.length];
      for (int i = 0; i < cls.length; i++)
         cls[i] = types[i].getRuntimeClass();

      return cls;
   }

   public static Object getSetMethodPropertyType(Object setMethod, JavaModel model) {
      Object type = getSetMethodPropertyType(setMethod);
      if (type instanceof Class)
         return mapClassToType((Class) type, model);
      return type;
   }

   public static Object getSetMethodPropertyType(Object setMethod) {
      if (setMethod instanceof IMethodDefinition) {
         Object[] paramTypes = ((IMethodDefinition) setMethod).getParameterTypes(false);
         if (paramTypes == null || paramTypes.length == 0)
            throw new IllegalArgumentException("Set method without any parameters: " + setMethod);
         if (paramTypes.length != 1) {
            // Indexed setter?
            if (paramTypes.length == 2 && isInteger(paramTypes[0]))
               return paramTypes[1];
            throw new IllegalArgumentException("Set method with too many parameters: " + setMethod);
         }
         return paramTypes[0];
      }
      else if (setMethod instanceof IBeanMapper)
         return ((IBeanMapper) setMethod).getPropertyType();
      else if (setMethod instanceof Method) {
         Class[] parameterTypes = ((Method) setMethod).getParameterTypes();
         if (parameterTypes.length != 1)
            throw new IllegalArgumentException("Set method with wrong number of parameters: " + setMethod);
         return parameterTypes[0];
      }
      else if (setMethod instanceof ParamTypedMember) {
         return getSetMethodPropertyType(((ParamTypedMember) setMethod).getMemberObject());
      }
      else throw new UnsupportedOperationException();
   }

   public static JavaType getSetMethodJavaType(LayeredSystem sys, Object setMethod, Object definedInType) {
      if (setMethod instanceof IMethodDefinition) {
         JavaType[] paramTypes = ((IMethodDefinition) setMethod).getParameterJavaTypes(false);
         if (paramTypes == null || paramTypes.length == 0)
            throw new IllegalArgumentException("Set method without any parameters: " + setMethod);
         if (paramTypes.length != 1) {
            // Indexed setter?
            if (paramTypes.length == 2 && isInteger(paramTypes[0]))
               return paramTypes[1];
            throw new IllegalArgumentException("Set method with too many parameters: " + setMethod);
         }
         return paramTypes[0];
      }
      else if (setMethod instanceof IBeanMapper)
         return JavaType.createFromParamType(sys, ((IBeanMapper) setMethod).getPropertyType(), null, definedInType);
      else if (setMethod instanceof Method) {
         Class[] parameterTypes = ((Method) setMethod).getParameterTypes();
         if (parameterTypes.length != 1)
            throw new IllegalArgumentException("Set method with wrong number of parameters: " + setMethod);
         return JavaType.createFromParamType(sys, parameterTypes[0], null, definedInType);
      }
      else if (setMethod instanceof ParamTypedMember) {
         return getSetMethodJavaType(sys, ((ParamTypedMember) setMethod).getMemberObject(), definedInType);
      }
      else throw new UnsupportedOperationException();
   }

   public static Object getMethodDeclaringClass(Object methodObj) {
      if (methodObj instanceof Method)
         return ((Method) methodObj).getDeclaringClass();
      else if (methodObj instanceof IMethodDefinition)
         return ((IMethodDefinition) methodObj).getDeclaringType();
      else
         throw new UnsupportedOperationException();
   }

   public static boolean checkAccess(Object refType, Object member) {
      return checkAccess(refType, member, null);
   }

   public static boolean checkAccessList(Object refType, Object member, EnumSet<JavaSemanticNode.MemberType> mtypes) {
      for (JavaSemanticNode.MemberType mtype:mtypes)
         if (checkAccess(refType, member, mtype))
            return true;
      return false;
   }

   public static boolean checkAccess(Object refType, Object member, JavaSemanticNode.MemberType mtype) {
      AccessLevel memberLevel = getAccessLevel(member, false, mtype);
      if (memberLevel == null) {
         Object encType = getEnclosingType(member);
         if (ModelUtil.isInterface(encType))
            return true;
         // TODO: encType = null happens when the member is resolved as a top-level reference property.  maybe check the access level of the type?
         if (encType == null)
            return true;
         // All fields or methods defined in the cmd object are visible for everyone
         if (encType instanceof AbstractInterpreter.CmdClassDeclaration)
            return true;
         // By default everything is public inside of a layer definition file
         if (ModelUtil.isLayerType(encType) || ModelUtil.isLayerComponent(encType)) {
            /* old - a layer to see any package private values if it extends the other layer explicitly
            if (ModelUtil.isLayerType(refType)) {
               Layer encLayer = ModelUtil.getLayerForType(null, encType);
               Layer refLayer = ModelUtil.getLayerForType(null, refType);
               if (encLayer != null && refLayer != null && refLayer.extendsOrIsLayer(encLayer)) {
                  return true;
               }
            }
            */
            return true;
         }
         // TODO: should we make all layer components public by default
         return ModelUtil.samePackage(refType, encType);
      }
      switch (memberLevel) {
         case Public:
            return true;
         case Private:
            return ModelUtil.sameModel(refType, getEnclosingType(member));
         // Protected works for either the same package or a subclass
         case Protected:
            Object encType = getEnclosingType(member);
            if (encType == null)
               return false;
            Object refEnclType = refType;
            while (refEnclType != null) {
               boolean val = ModelUtil.isAssignableFrom(encType, refEnclType);
               if (val)
                  return true;
               refEnclType = ModelUtil.getEnclosingType(refEnclType);
            }
            // FALL THROUGH if false
            return ModelUtil.samePackage(refType, encType);
         default:
            throw new UnsupportedOperationException();
      }
   }

   public static boolean samePackage(Object type1, Object type2) {
      if (type1 == type2)
         return true;
      String package1 = getPackageName(type1);
      String package2 = getPackageName(type2);
      return StringUtil.equalStrings(package1, package2);
   }

   public static String getPackageName(Object type) {
      if (type instanceof Class) {
         java.lang.Package pkg = ((Class) type).getPackage();
         if (pkg == null)
            return null;
         return pkg.getName();
      }
      else if (type instanceof ITypeDeclaration) {
         Object rootType = getRootType(type);
         if (rootType != null)
            type = rootType;
         String typeName = ModelUtil.getTypeName(type);
         if (typeName == null)
            return null;
         return CTypeUtil.getPackageName(typeName);
      }
      else
         throw new UnsupportedOperationException();
   }

   public static boolean sameModel(Object type1, Object type2) {
      if (type1 == type2)
         return true;
      Object parent;
      do {
         parent = ModelUtil.getEnclosingType(type1);
         if (parent != null)
            type1 = parent;
      } while (parent != null);

      do {
         parent = ModelUtil.getEnclosingType(type2);
         if (parent != null)
            type2 = parent;
      } while (parent != null);

      if (type1 == type2)
         return true;
      // TODO: speed this up?
      return getTypeName(type1).equals(getTypeName(type2));
   }

   public static boolean isVarArgs(Object method) {
      if (method instanceof Method)
         return ((Method) method).isVarArgs();
      else if (method instanceof Constructor)
         return ((Constructor) method).isVarArgs();
      else if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).isVarArgs();
      // a field that gets turned into a setX method
      else if (method instanceof VariableDefinition)
         return false;
      else
         throw new UnsupportedOperationException();
   }

   /**
    * Returns the method specified on resultClass with the given name which matches either the paraemetersOrExpressions, or a
    * specific list of parameter types.
    * TODO: we should consolidate this logic - this version is used for Class types, CFClass has a defineMethod and AbstractMethodDefinition.definesMethod also has similar logic
    */
   public static Object getMethod(LayeredSystem sys, Object resultClass, String methodName, Object refType, ITypeParamContext ctx, Object inferredType, boolean staticOnly, List<JavaType> methodTypeArgs, List<?> parametersOrExpressions, Object[] typesToMatch) {
      // TODO: Need to wrap any param type methods and set the inferredType so we do the lookup accurately

      Object[] list = ModelUtil.getMethods(resultClass, methodName, null);

      if (list == null) {
         // Interfaces don't inherit object methods in Java but an inteface type in this system needs to still
         // implement methods like "toString" even if they are not on the interface.
         if (ModelUtil.isInterface(resultClass)) {
            return getMethod(sys, Object.class, methodName, refType, null, inferredType, staticOnly, methodTypeArgs, parametersOrExpressions, typesToMatch);
         }
         return null;
      }

      int typesLen = typesToMatch != null ? typesToMatch.length : parametersOrExpressions == null ? 0 : parametersOrExpressions.size();
      Object res = null;
      Object[] prevExprTypes = null;
      ArrayList<Expression> toClear = null;
      for (int i = 0; i < list.length; i++) {
         Object toCheck = list[i];
         if (ModelUtil.getMethodName(toCheck).equals(methodName)) {
            ParamTypedMethod paramMethod = null;

            Object[] parameterTypes = ModelUtil.getParameterTypes(toCheck);
            int paramLen = parameterTypes == null ? 0 : parameterTypes.length;

            int last = paramLen - 1;
            if (paramLen != typesLen) {
               // If the last guy is not a repeating parameter, it can't match
               if (last < 0 || !ModelUtil.isVarArgs(toCheck) || !ModelUtil.isArray(parameterTypes[last]) || typesLen < last)
                  continue;
            }

            if (staticOnly && !ModelUtil.hasModifier(toCheck, "static"))
               continue;

            if (!(toCheck instanceof ParamTypedMethod) && ModelUtil.isParameterizedMethod(toCheck) && typesToMatch == null) {

               // TODO: resultClass converted to definedIntype here - we could do a wrapper for the getClass method
               paramMethod = new ParamTypedMethod(sys, toCheck, ctx, resultClass, parametersOrExpressions, inferredType, methodTypeArgs);

               parameterTypes = paramMethod.getParameterTypes(true);
               toCheck = paramMethod;

               // Something did not match in the method type parameters - e.g. <T extends X> conflicted with another use of T.
               if (paramMethod.invalidTypeParameter)
                  continue;
            }


            if (paramLen == 0 && typesLen == 0) {
               if (refType == null || checkAccess(refType, toCheck))
                  res = ModelUtil.pickMoreSpecificMethod(res, toCheck, null, null, null);
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

                  Object exprType;
                  if (typesToMatch == null) {
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
                     exprType = ModelUtil.getVariableTypeDeclaration(exprObj);

                     // This was an invalid lambda
                     if (exprType instanceof BaseLambdaExpression.LambdaInvalidType)
                        break;
                  }
                  else
                     exprType = typesToMatch[j];

                  nextExprTypes[j] = exprType;

                  if (exprType != null && !ModelUtil.isAssignableFrom(paramType, exprType, false, ctx, sys)) {
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
                  if (refType == null || checkAccess(refType, toCheck)) {
                     res = ModelUtil.pickMoreSpecificMethod(res, toCheck, nextExprTypes, prevExprTypes, parametersOrExpressions);
                     if (res == toCheck)
                        prevExprTypes = nextExprTypes;
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
      return res;
   }

   public static boolean parameterTypesMatch(Object[] parameterTypes, Object[] types, Object toCheck, Object refType, ITypeParamContext ctx) {
      int paramLen = parameterTypes == null ? 0 : parameterTypes.length;
      int typesLen = types == null ? 0 : types.length;
      if (paramLen == 0 && typesLen == 0) {
         if (refType == null || checkAccess(refType, toCheck))
            return true;
      }
      else {
         int j;
         int last = paramLen - 1;
         if (paramLen != typesLen) {
            // If the last guy is not a repeating parameter, it can't match
            if (last < 0 || !ModelUtil.isVarArgs(toCheck) || !ModelUtil.isArray(parameterTypes[last]) || typesLen < last)
               return false;
         }
         for (j = 0; j < typesLen; j++) {
            Object paramType;
            if (j > last) {
               if (!ModelUtil.isArray(paramType = parameterTypes[last]))
                  return false;
            }
            else
               paramType = parameterTypes[j];
            if (types[j] != null && !ModelUtil.isAssignableFrom(paramType, types[j], false, ctx)) {
               // Repeating parameters... if the last parameter is an array match if the component type matches
               if (j >= last && ModelUtil.isArray(paramType) && ModelUtil.isVarArgs(toCheck)) {
                  if (!ModelUtil.isAssignableFrom(ModelUtil.getArrayComponentType(paramType), types[j], false, ctx)) {
                     return false;
                  }
               }
               else
                  return false;
            }
         }
         if (j == typesLen) {
            if (refType == null || checkAccess(refType, toCheck))
               return true;
         }
      }
      return false;

   }

   public static Object getConstructorFromSignature(Object type, String sig) {
      if (type instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) type).getConstructorFromSignature(sig);
      }
      else if (type instanceof Class) {
         return PTypeUtil.resolveMethod((Class) type, ModelUtil.getTypeName(type), null, sig);
      }
      else
         throw new UnsupportedOperationException();
   }

   public static Object getMethodFromSignature(Object type, String methodName, String paramSig, boolean resolveLayer) {
      if (type instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) type).getMethodFromSignature(methodName, paramSig, resolveLayer);
      }
      else if (type instanceof Class)
         return PTypeUtil.resolveMethod((Class) type, methodName, null, paramSig);
      else if (type instanceof DynType)
         return ((DynType) type).getMethod(methodName, paramSig);
      else if (type instanceof String) {
         String typeName = (String) type;
         LayeredSystem sys = LayeredSystem.getCurrent();
         Object resolvedType;
         if (sys != null) {
            resolvedType = sys.getTypeDeclaration(typeName);
         }
         else {
            resolvedType = DynUtil.findType(typeName);
         }
         if (resolvedType == null)
            throw new IllegalArgumentException("No type named: " + typeName);
         return getMethodFromSignature(resolvedType, methodName, paramSig, resolveLayer);
      }
      else
         throw new UnsupportedOperationException();
   }

   public static boolean methodNamesMatch(Object c1, Object c2) {
      return ModelUtil.getMethodName(c1).equals(ModelUtil.getMethodName(c2));
   }

   /*
    * Checks if the parameters in the two methods match.  First call methodNamesMatch if you want to really compare
    * that they are the same methods.
    */
   public static boolean methodsMatch(Object m1, Object m2) {
      if (m1 == null)
         return m2 == null;

      if (m2 == null)
         return false;

      Object[] c1Types = ModelUtil.getParameterTypes(m1);
      Object[] c2Types = ModelUtil.getParameterTypes(m2);
      if (!parametersMatch(c1Types, c2Types, false, null))
         return false;
      Object encl1Type = ModelUtil.getEnclosingType(m1);
      Object encl2Type = ModelUtil.getEnclosingType(m2);
      if (!ModelUtil.sameTypes(encl1Type, encl2Type))
         return false;
      return ModelUtil.isVarArgs(m1) == ModelUtil.isVarArgs(m2);
   }

   /*
    * Checks if the parameters in the two methods match.  First call methodNamesMatch if you want to really compare
    * that they are the same methods.
    */
   public static boolean sameMethodParameters(Object m1, Object m2) {
      if (m1 == null)
         return m2 == null;

      if (m2 == null)
         return false;

      Object[] c1Types = ModelUtil.getParameterTypes(m1);
      Object[] c2Types = ModelUtil.getParameterTypes(m2);
      if (!sameParameters(c1Types, c2Types, false, null))
         return false;
      Object encl1Type = ModelUtil.getEnclosingType(m1);
      Object encl2Type = ModelUtil.getEnclosingType(m2);
      if (!ModelUtil.sameTypes(encl1Type, encl2Type))
         return false;
      return ModelUtil.isVarArgs(m1) == ModelUtil.isVarArgs(m2);
   }

   /** Returns true for two methods which match in name and parameter signature.  Beware that it will return true for the same method in different layers (use sameMethodInLayer). */
   public static boolean sameMethods(Object m1, Object m2) {
      return ModelUtil.methodNamesMatch(m1, m2) && ModelUtil.sameMethodParameters(m1, m2);
   }

   public static boolean anyUnresolvedParamTypes(Object m) {
      Object[] pTypes = ModelUtil.getParameterTypes(m);
      if (pTypes == null)
         return false;
      for (Object pType:pTypes)
         if (pType == null)
            return true;
      return false;
   }

   /**
    * Returns true for two methods which are not only the same name, signature but also defined in the same layer.
    * Will consider two methods in
    * different runtimes but the same layer the same method.
    */
   public static boolean sameMethodInLayer(LayeredSystem sys, Object m1, Object m2) {
      if (ModelUtil.sameMethods(m1, m2)) {
         Object enc1 = ModelUtil.getEnclosingType(m1);
         Object enc2 = ModelUtil.getEnclosingType(m2);
         if (ModelUtil.sameTypes(enc1, enc2)) {
            Layer l1 = ModelUtil.getLayerForType(sys, enc1);
            Layer l2 = ModelUtil.getLayerForType(sys, enc2);
            if (l1 == l2 || l1 != null && l2 != null && l1.getLayerName().equals(l2.getLayerName()))
               return true;
         }
      }
      return false;
   }

   public static boolean sameParameters(Object[] c1Types, Object[] c2Types, boolean allowUnbound, LayeredSystem sys) {
      int c1Len = c1Types == null ? 0 : c1Types.length;
      int checkLen = c2Types == null ? 0 : c2Types.length;
      if (c1Len != checkLen)
         return false;

      if (c1Types != null) {
         for (int i = 0; i < c1Types.length; i++) {
            Object c1Arg = c1Types[i];
            Object c2Arg = c2Types[i];

            if (c1Arg == c2Arg)
               continue;

            if (sameTypes(c1Arg, c2Arg))
               continue;

            return false;
         }
      }
      return true;
   }

   public static boolean parametersMatch(Object[] c1Types, Object[] c2Types, boolean allowUnbound, LayeredSystem sys) {
      int c1Len = c1Types == null ? 0 : c1Types.length;
      int checkLen = c2Types == null ? 0 : c2Types.length;
      if (c1Len != checkLen)
         return false;

      if (c1Types != null) {
         for (int i = 0; i < c1Types.length; i++) {
            Object c1Arg = c1Types[i];
            Object c2Arg = c2Types[i];

            if (c1Arg == c2Arg)
               continue;

            if (sameTypes(c1Arg, c2Arg))
               continue;

            if (ModelUtil.isAssignableFrom(c1Arg, c2Arg, false, null, allowUnbound, sys))
               continue;

            return false;
         }
      }
      return true;
   }

   public static Object chooseImplMethod(Object c1, Object c2, boolean includeAbstract) {
      // Preference to methods in the type rather than in the interface
      boolean c1iface = ModelUtil.isInterface(ModelUtil.getEnclosingType(c1));
      boolean c2iface = ModelUtil.isInterface(ModelUtil.getEnclosingType(c2));
      if (c1iface && !c2iface)
         return c2;
      if (c2iface && !c1iface)
         return c1;

      if (includeAbstract) {
         // Most likely only one method in this list is abstract but just to be paranoid, we check both flags
         boolean c1abs = ModelUtil.hasModifier(c1, "abstract");
         boolean c2abs = ModelUtil.hasModifier(c2, "abstract");
         if (c1abs && !c2abs)
            return c2;
         if (c2abs && !c1abs)
            return c1;
      }
      return null;
   }

   /** Chooses the method with the more specific return type - as per the searchMethods method in java.lang.Class */
   public static Object pickMoreSpecificMethod(Object c1, Object c2, Object[] c1ArgTypes, Object[] c2ArgTypes, List<? extends Object> exprs) {
      if (c1 == null)
         return c2;

      // For CFClass at least, we pass in c2 as the sub-classes type as the second arg... not sure this is right.  Maybe we should only use this logic for when we get the list
      // from the same type?
      Object defaultType = c2;

      // First an exact match of parameter types overrides - e.g. Math.abs(int)
      if (c1ArgTypes != null) {
         Object[] c1Types = ModelUtil.getParameterTypes(c1);
         Object[] c2Types = ModelUtil.getParameterTypes(c2);
         int c1Len = c1Types == null ? 0 : c1Types.length;
         int checkLen = c2Types == null ? 0 : c2Types.length;
         if (c1Len != checkLen)
            defaultType = c1Len > checkLen ? c2 : c1;

         boolean c1VarArg = ModelUtil.isVarArgs(c1);
         boolean c2VarArg = ModelUtil.isVarArgs(c2);

         boolean paramsSorted = false;

         int param1Len = c1ArgTypes.length;
         int param2Len = c2ArgTypes == null ? param1Len : c2ArgTypes.length;

         // If we have one var arg and one non-var-arg and the same parameters, choose the type based on whether the
         // varArg argument is an array.  If it's an array, choose the varArgs one.  If not, choose the scalar.
         if (c1VarArg != c2VarArg) {
            if (c1Len == checkLen) {
               Object lastType = c1ArgTypes[param1Len - 1];
               // This rule for using varArgs to match arrays applies to Integer[] but not int[]
               boolean lastTypeArr = ModelUtil.isArray(lastType) && !ModelUtil.isPrimitive(ModelUtil.getArrayComponentType(lastType));
               // If one matches exactly and the other matches because it's a repeat definition, choose the non-repeating one (e.g. Seq.of(T) versus Seq.of(T...) except if you have an array param.
               // Then the match goes the other way.
               if (c1VarArg && !c2VarArg) {
                  defaultType = lastTypeArr ? c1 : c2;
                  paramsSorted = true;
               }
               else if (c2VarArg && !c1VarArg) {
                  defaultType = lastTypeArr ? c2 : c1;
                  paramsSorted = true;
               }
            }
            // Another var args case - we'd rather match the method with the exact number of parameters over a var args with fewer
            else {
               if (c1VarArg && checkLen == param1Len)
                  defaultType = c2;
               else if (c2VarArg && c1Len == param1Len)
                  defaultType = c1;
            }
         }

         // First if any of the parameters is a lambda expression, we need to ensure the
         // lambda expression parameters match the parameters of the single-interface method
         // defined for this parameter type.
         for (int i = 0; i < param1Len; i++) {
            Object arg = c1ArgTypes[i];
            // TODO: lambda params case - choosing the method with fewer parameters?
            if (i >= param2Len)
               return c2;
            if (arg instanceof BaseLambdaExpression.LambdaInferredType) {
               boolean repeat1Arg = c1Types.length <= i;
               boolean repeat2Arg = c2Types.length <= i;
               Object c1Arg = repeat1Arg ? c1Types[c1Types.length-1] : c1Types[i];
               Object c2Arg = repeat2Arg ? c2Types[c2Types.length-1] : c2Types[i];

               BaseLambdaExpression lambda = ((BaseLambdaExpression.LambdaInferredType) arg).rootExpr;

               Object lambdaRes = lambda.pickMoreSpecificMethod(c1, c1Arg, i >= c1Types.length - 1 && c1VarArg, c2, c2Arg, i >= c2Types.length - 1 && c2VarArg);
               if (lambdaRes != null)
                  return lambdaRes;
               /*
               if (c2Interface && !lambda.lambdaParametersMatch(c2Arg, i >= c2Types.length - 1 && c2VarArg))
                  c2Interface = false;
               boolean c1Interface = ModelUtil.isInterface(c1Arg);
               if (c1Interface && !lambda.lambdaParametersMatch(c1Arg, i >= c1Types.length - 1 && c1VarArg))
                  c1Interface = false;
               if (!c1Interface) {
                  if (c2Interface)
                     return c2;
               }
               else if (!c2Interface)
                  return c1;
               */
            }
         }

         // There are some special rules for matching method calls with lambda expressions.
         if (exprs != null) {
            for (int i = 0; i < param1Len; i++) {
               // TODO: lambda params case - choosing the method with fewer parameters?
               if (i >= param2Len)
                  return c2;
               boolean repeat1Arg = c1Types.length <= i;
               boolean repeat2Arg = c2Types.length <= i;
               Object c1Arg = repeat1Arg ? c1Types[c1Types.length-1] : c1Types[i];
               Object c2Arg = repeat2Arg ? c2Types[c2Types.length-1] : c2Types[i];
               Object expr = exprs.get(i);

               if (expr instanceof BaseLambdaExpression) {
                  BaseLambdaExpression lambda = (BaseLambdaExpression) expr;
                  Object lambdaRes = lambda.pickMoreSpecificMethod(c1, c1Arg, i >= c1Types.length - 1 && c1VarArg, c2, c2Arg, i >= c2Types.length - 1 && c2VarArg);
                  if (lambdaRes != null)
                     return lambdaRes;
               }
            }
         }

         if (!paramsSorted) {
            for (int i = 0; i < param1Len; i++) {
               // TODO: lambda params case - choosing the method with fewer parameters?
               if (i >= param2Len)
                  return c2;
               Object c1ArgType = c1ArgTypes[i];
               Object c2ArgType = c2ArgTypes[i];
               boolean repeat1Arg = c1Types.length <= i;
               boolean repeat2Arg = c2Types.length <= i;
               Object c1Arg = repeat1Arg ? c1Types[c1Types.length - 1] : c1Types[i];
               Object c2Arg = repeat2Arg ? c2Types[c2Types.length - 1] : c2Types[i];

               if (c1Arg == c2Arg)
                  continue;

               /*
               if (arg == LambdaExpression.LAMBDA_INFERRED_TYPE) {
                  boolean c2Interface = ModelUtil.isInterface(c2Arg);
                  if (!ModelUtil.isInterface(c1Arg)) {
                     if (c2Interface)
                        return c2;
                  }
                  else if (!c2Interface)
                     return c1;
                  arg = Object.class;
               }
               */

               if (sameTypes(c1ArgType, c1Arg))
                  return c1;

               if (sameTypes(c2ArgType, c2Arg))
                  return c2;

               boolean c1ArgTypePrim = ModelUtil.isPrimitive(c1ArgType);
               boolean c1ArgPrim = ModelUtil.isPrimitive(c1Arg);

               boolean c2ArgTypePrim = ModelUtil.isPrimitive(c2ArgType);
               boolean c2ArgPrim = ModelUtil.isPrimitive(c2Arg);

               // Need Integer to int to take precedence over Integer long
               if (c1ArgTypePrim && !c1ArgPrim && sameTypes(ModelUtil.wrapPrimitiveType(c1ArgType), c1Arg))
                  return c1;
               if (!c1ArgTypePrim && c1ArgPrim && sameTypes(ModelUtil.wrapPrimitiveType(c1Arg), c1ArgType))
                  return c1;

               if (c2ArgTypePrim && !c2ArgPrim && sameTypes(ModelUtil.wrapPrimitiveType(c2ArgType), c2Arg))
                  return c2;
               if (!c2ArgTypePrim && c2ArgPrim && sameTypes(ModelUtil.wrapPrimitiveType(c2Arg), c2ArgType))
                  return c2;

               if (isANumber(c1ArgType)) {
                  if (ModelUtil.isInteger(c1ArgType)) {
                     boolean c1Int = ModelUtil.isAnInteger(c1Arg);
                     boolean c2Int = ModelUtil.isAnInteger(c2Arg);
                     if (!c1Int && c2Int)
                        return c2;
                     if (!c2Int && c1Int)
                        return c1;
                  }
               }

               boolean argIsArray = ModelUtil.isArray(c1ArgType);
               if (ModelUtil.isArray(c1Arg) && !ModelUtil.isArray(c2Arg)) {
                  if (argIsArray)
                     return c1;
                  // If we have c1 as a "..." signifier and c2 is not, match the one which is not
                  return c2;
               }
               // If the arg is an array pick the array type
               else if (argIsArray && ModelUtil.isArray(c2Arg) && !ModelUtil.isArray(c1Arg))
                  return c2;
               return defaultType;
            }
         }
      }

      // TODO: should we be using 'abstract' methods here?    An example - an abstract clone() method can refine the return type
      // so if we return Object.clone, we return the wrong type.  That's why we added includeAbstract=false in initMethodAndFieldIndex
      Object implMethod = ModelUtil.chooseImplMethod(c1, c2, true);
      if (implMethod != null)
         return implMethod;

      Object c1Ret = ModelUtil.getReturnType(c1, true);
      Object c2Ret = ModelUtil.getReturnType(c2, true);
      if (!ModelUtil.sameTypes(c1Ret, c2Ret)) {
         if (ModelUtil.isAssignableFrom(c1Ret, c2Ret))
            return c2;
         else if (ModelUtil.isAssignableFrom(c2Ret, c1Ret))
            return c1;
      }
      // Picks by the method with the shortest number of parameters
      return defaultType;
   }

   public static boolean sameTypesOrVariables(Object typeObj1, Object typeObj2) {
      if (ModelUtil.isTypeVariable(typeObj1))
         return ModelUtil.isTypeVariable(typeObj2) && ModelUtil.sameTypeParameters(typeObj1, typeObj2);
      return !ModelUtil.isTypeVariable(typeObj2) && ModelUtil.sameTypes(typeObj1, typeObj2);
   }

   /** isSameType might be a better name? */
   public static boolean sameTypes(Object typeObj1, Object typeObj2) {
      if (typeObj1 == typeObj2)
         return true;

      if (isInteger(typeObj1) && isInteger(typeObj2) && isPrimitive(typeObj1) == isPrimitive(typeObj2))
         return true;

      // For sameMethods at least, it's ok to have foo(long p) and foo(Long p) as separate methods so need to treat these cases differently here
      if (isLong(typeObj1) && isLong(typeObj2) && isPrimitive(typeObj1) == isPrimitive(typeObj2))
         return true;

      if (isShort(typeObj1) && isShort(typeObj2) && isPrimitive(typeObj1) == isPrimitive(typeObj2))
         return true;

      if (isByte(typeObj1) && isByte(typeObj2) && isPrimitive(typeObj1) == isPrimitive(typeObj2))
         return true;

      if (isDouble(typeObj1) && isDouble(typeObj2) && isPrimitive(typeObj1) == isPrimitive(typeObj2))
         return true;

      if (isFloat(typeObj1) && isFloat(typeObj2) && isPrimitive(typeObj1) == isPrimitive(typeObj2))
         return true;

      if (isBoolean(typeObj1) && isBoolean(typeObj2) && isPrimitive(typeObj1) == isPrimitive(typeObj2))
         return true;

      if (isCharacter(typeObj1) && isCharacter(typeObj2) && isPrimitive(typeObj1) == isPrimitive(typeObj2))
         return true;

      if (typeObj1 == null || typeObj2 == null)
         return false;
      if (typeObj1 instanceof BaseLambdaExpression.LambdaInferredType || typeObj2 instanceof BaseLambdaExpression.LambdaInferredType)
         return true;

      if (typeObj1 instanceof ExtendsType.LowerBoundsTypeDeclaration)
         return typeObj1.equals(typeObj2);
      if (typeObj2 instanceof ExtendsType.LowerBoundsTypeDeclaration)
         return typeObj2.equals(typeObj1);

      // Need to include the dims at least to differentiate Object[] from Object
      return ModelUtil.getTypeName(typeObj1, true).equals(ModelUtil.getTypeName(typeObj2, true));
   }

   public static boolean sameTypesAndParams(Object t1, Object t2) {
      if (!ModelUtil.sameTypes(t1, t2))
         return false;
      boolean h1 = ModelUtil.hasTypeParameters(t1);
      boolean h2 = ModelUtil.hasTypeParameters(t2);
      if (h1 != h2)
         return false;
      if (!h1)
         return true;
      int n1 = ModelUtil.getNumTypeParameters(t1);
      int n2 = ModelUtil.getNumTypeParameters(t2);
      if (n1 != n2)
         return false;
      for (int i = 0; i < n1; i++) {
         Object tp1 = ModelUtil.getTypeParameter(t1, i);
         Object tp2 = ModelUtil.getTypeParameter(t2, i);
         boolean v1 = ModelUtil.isTypeVariable(tp1);
         boolean v2 = ModelUtil.isTypeVariable(tp2);
         if (v1 != v2)
            return false;
         if (v1) {
            if (!ModelUtil.sameTypeParameters(tp1, tp2))
               return false;
         }
         else {
            if (!ModelUtil.sameTypes(tp1, tp2))
               return false;
         }
      }
      if (!(t1 instanceof ParamTypeDeclaration)) {
         if (t2 instanceof ParamTypeDeclaration)
            return false;
      }
      else {
         if (!(t2 instanceof ParamTypeDeclaration))
            return false;
         ParamTypeDeclaration ptd1 = (ParamTypeDeclaration) t1;
         ParamTypeDeclaration ptd2 = (ParamTypeDeclaration) t2;
         for (int i = 0; i < n1; i++) {
            if (!ModelUtil.sameTypesAndParams(ptd1.getType(i), ptd2.getType(i)))
               return false;
         }
      }
      return true;
   }

   public static boolean sameTypesAndLayers(LayeredSystem sys, Object type1, Object type2) {
      if (ModelUtil.sameTypes(type1, type2)) {
         Layer l1 = ModelUtil.getLayerForType(sys, type1);
         Layer l2 = ModelUtil.getLayerForType(sys, type2);
         if (l1 == l2)
            return true;
      }
      return false;
   }

   public static boolean sameTypeParameters(Object tp1, Object tp2) {
      if (tp1 == tp2)
         return true;
      String tpName1 = ModelUtil.getTypeParameterName(tp1);
      String tpName2 = ModelUtil.getTypeParameterName(tp2);
      if (tpName1 == null || tpName2 == null)
         return false;
      if (!tpName1.equals(tpName2))
         return false;
      Object decl1 = ModelUtil.getTypeParameterDeclaration(tp1);
      Object decl2 = ModelUtil.getTypeParameterDeclaration(tp2);
      if (ModelUtil.isMethod(decl1)) {
         if (!ModelUtil.isMethod(decl2))
            return false;
         return ModelUtil.overridesMethod(decl1, decl2);
      }
      else if (ModelUtil.isMethod(decl2))
         return false;
      return sameTypes(decl1, decl2);
   }

   public static boolean isBoolean(Object type) {
      if (type instanceof PrimitiveType) {
         type = ((PrimitiveType) type).getRuntimeClass();
      }
      if (type instanceof Class) {
         sc.type.Type t = sc.type.Type.get((Class) type);
         return t == sc.type.Type.Boolean;
      }
      if (type instanceof CFClass) {
         return ((CFClass) type).isBoolean();
      }
      else if (type instanceof WrappedTypeDeclaration) {
         return isBoolean(((WrappedTypeDeclaration) type).getBaseType());
      }
      if (type instanceof ModifyDeclaration) {
         ModifyDeclaration modType = ((ModifyDeclaration) type);
         if (modType.typeName != null && modType.typeName.equals("Boolean"))
            return isBoolean(modType.getDerivedTypeDeclaration());
      }
      return false;
   }

   public static boolean isCharacter(Object type) {
      if (type instanceof PrimitiveType) {
         type = ((PrimitiveType) type).getRuntimeClass();
      }
      if (type instanceof Class) {
         sc.type.Type t = sc.type.Type.get((Class) type);
         return t == sc.type.Type.Character;
      }
      if (type instanceof CFClass) {
         return ((CFClass) type).isCharacter();
      }
      else if (type instanceof WrappedTypeDeclaration) {
         return isCharacter(((WrappedTypeDeclaration) type).getBaseType());
      }
      if (type instanceof ModifyDeclaration) {
         ModifyDeclaration modType = ((ModifyDeclaration) type);
         if (modType.typeName != null && modType.typeName.equals("Character"))
            return isCharacter(modType.getDerivedTypeDeclaration());
      }

      return false;
   }

   public static boolean isPrimitive(Object type) {
      if (type instanceof PrimitiveType) {
         return true;
      }
      if (type instanceof Class) {
         Class cl = (Class) type;
         boolean cval = cl.isPrimitive();
         sc.type.Type t = sc.type.Type.get(cl);
         if (cval != (t.primitiveClass == type))
            System.err.println("*** Internal error with primitive type comparison: " + type + " and: " + t);
         return t.primitiveClass == type;
      }
      else if (type instanceof WrappedTypeDeclaration) {
         return isPrimitive(((WrappedTypeDeclaration) type).getBaseType());
      }
      return false;
   }

   public static boolean isPrimitiveNumberType(Object type) {
      if (type instanceof PrimitiveType) {
         type = ((PrimitiveType) type).getRuntimeClass();
      }
      if (type instanceof Class) {
         sc.type.Type t = sc.type.Type.get((Class) type);
         return t.primitiveClass == type && t.isANumber();
      }
      else if (type instanceof WrappedTypeDeclaration) {
         return isPrimitiveNumberType(((WrappedTypeDeclaration) type).getBaseType());
      }
      return false;
   }

   public static boolean isANumber(Object type) {
      if (type instanceof PrimitiveType)
         type = ((PrimitiveType) type).getRuntimeClass();
      if (type instanceof Class) {
         if (type == Number.class)
            return true;
         return sc.type.Type.get((Class) type).isANumber();
      }
      if (isNumber(type))
         return true;
      else if (type instanceof ModifyDeclaration && isANumber(((ModifyDeclaration) type).getDerivedTypeDeclaration()))
         return true;
      else if (type instanceof WrappedTypeDeclaration) {
         return isANumber(((WrappedTypeDeclaration) type).getBaseType());
      }
      else {
         return false;
      }
   }

   public static boolean isNumber(Object type) {
      // A better way to detect this?  I don't think Number can be redefined...
      if (type instanceof ClassType && ((ClassType) type).typeName.equals("Number"))
         return true;
      if (type instanceof CFClass && ((CFClass) type).isNumber())
         return true;
      else if (type instanceof WrappedTypeDeclaration) {
         return isNumber(((WrappedTypeDeclaration) type).getBaseType());
      }
      return type == Number.class;
   }

   public static Object coerceTypes(LayeredSystem sys, Object lhsType, Object rhsType) {
      if (lhsType == rhsType)
         return lhsType;

      // Once we are coercing an 'int' to something that's an object, this is a case where we need to
      // do the auto-boxing
      if (ModelUtil.isPrimitive(lhsType))
         lhsType = ModelUtil.wrapPrimitiveType(lhsType);

      if (ModelUtil.isPrimitive(rhsType))
         rhsType = ModelUtil.wrapPrimitiveType(rhsType);

      if (lhsType instanceof BaseLambdaExpression.LambdaInferredType) {
         return rhsType; // Either another inferred type or the real type
      }
      else if (rhsType instanceof BaseLambdaExpression.LambdaInferredType)
         return lhsType;

      // When comparing against Null, always use the other guy
      if (lhsType == NullLiteral.NULL_TYPE)
         return rhsType;
      if (rhsType == NullLiteral.NULL_TYPE)
         return lhsType;

      if (isANumber(lhsType) && isANumber(rhsType))
         return coerceNumberTypes(lhsType, rhsType);

      // If you have one side that is ? super SuperType and we have SubType extends SuperType, oddly this seems to clamp the
      // coerced type to SuperType
      if (lhsType instanceof ExtendsType.LowerBoundsTypeDeclaration) {
         ExtendsType.LowerBoundsTypeDeclaration lbt = (ExtendsType.LowerBoundsTypeDeclaration) lhsType;
         if (ModelUtil.isAssignableFrom(rhsType, lbt.baseType))
            return lbt.baseType;
         return rhsType;
      }
      if (rhsType instanceof ExtendsType.LowerBoundsTypeDeclaration) {
         ExtendsType.LowerBoundsTypeDeclaration rbt = (ExtendsType.LowerBoundsTypeDeclaration) rhsType;
         if (ModelUtil.isAssignableFrom(rbt.baseType, lhsType))
            return rbt.baseType;
         return lhsType;
      }

      boolean lhsMatch = ModelUtil.isAssignableFrom(lhsType, rhsType);
      boolean rhsMatch = ModelUtil.isAssignableFrom(rhsType, lhsType);
      if (lhsMatch && !rhsMatch)
         return lhsType;
      else if (rhsMatch && !lhsMatch)
         return rhsType;
      else {
         if (ModelUtil.sameTypesAndParams(lhsType, rhsType))
            return lhsType;

         boolean lhsTypeVar = ModelUtil.isTypeVariable(lhsType);
         boolean rhsTypeVar = ModelUtil.isTypeVariable(rhsType);
         if (lhsTypeVar && !rhsTypeVar)
            return rhsType;
         if (rhsTypeVar && !lhsTypeVar)
            return lhsType;

         if (rhsTypeVar && lhsTypeVar)
            return lhsType; // TODO: Is there something more intelligent we need to do to coalesce type variables?

         Object type = ModelUtil.findCommonSuperClass(sys, lhsType, rhsType);
         if (type != null) {
            Object[] ifaces = ModelUtil.getOverlappingInterfaces(lhsType, rhsType);
            if (ifaces == null || ModelUtil.implementsInterfaces(type, ifaces))
               return type;
            // Weird case - need to build a new type which represents the common base type combined with the overlapping interfaces
            return new CoercedTypeDeclaration(sys, type, ifaces);
         }
         throw new IllegalArgumentException("Cannot coerce types: " + ModelUtil.getTypeName(lhsType) + " and: " + ModelUtil.getTypeName(rhsType));
      }
   }

   /**
    * This is used to combine the inferredType with the type extracted from the parameters in determining "the" type.
    * Originally it also called coerceTypes but ran into some problems with that.
    */
   public static Object blendTypes(Object lhsType, Object rhsType) {
      // If you have one side that is ? super SuperType and we have SubType extends SuperType, oddly this seems to clamp the
      // coerced type to SuperType
      if (lhsType instanceof ExtendsType.LowerBoundsTypeDeclaration) {
         ExtendsType.LowerBoundsTypeDeclaration lbt = (ExtendsType.LowerBoundsTypeDeclaration) lhsType;
         if (lbt.baseType == Object.class)
            return rhsType;
         if (ModelUtil.isAssignableFrom(rhsType, lbt.baseType))
            return lbt.baseType;
         return rhsType;
      }
      if (rhsType instanceof ExtendsType.LowerBoundsTypeDeclaration) {
         ExtendsType.LowerBoundsTypeDeclaration rbt = (ExtendsType.LowerBoundsTypeDeclaration) rhsType;
         if (rbt.baseType == Object.class)
            return lhsType;
         if (ModelUtil.isAssignableFrom(rbt.baseType, lhsType))
            return rbt.baseType;
         return lhsType;
      }

      return lhsType;
   }

   public static Object refineType(LayeredSystem sys, Object definedInType, Object origType, Object newType) {
      if (origType == newType)
         return origType;

      if (origType instanceof ExtendsType.LowerBoundsTypeDeclaration)
         origType = ((ExtendsType.LowerBoundsTypeDeclaration) origType).getBaseType();

      if (newType instanceof ExtendsType.LowerBoundsTypeDeclaration)
         newType = ((ExtendsType.LowerBoundsTypeDeclaration) newType).getBaseType();

      if (ModelUtil.isTypeVariable(origType) || ModelUtil.isWildcardType(origType))
         return newType;
      if (ModelUtil.isTypeVariable(newType) || ModelUtil.isWildcardType(newType))
         return origType;

      if (newType == null)
         return origType;

      if (origType == null)
         return newType;

      if (!ModelUtil.isAssignableFrom(origType, newType))
         return origType;

      if (!ModelUtil.isAssignableFrom(newType, origType))
         return newType;

      if (ModelUtil.isParameterizedType(origType)) {
         // If the old type has type parameters but the new one does not, create a new param type which has the new type
         // as the base type and the old type parameters
         if (!ModelUtil.isParameterizedType(newType)) {
            if (origType instanceof ParamTypeDeclaration) {
               List<?> typeParams = ModelUtil.getTypeParameters(newType);
               if (typeParams == null || typeParams.size() == 0)
                  return newType;
               ParamTypeDeclaration origParamType = (ParamTypeDeclaration) origType;
               ArrayList<Object> newTypes = new ArrayList<Object>(typeParams.size());
               List<Object> oldTypes = origParamType.types;
               List<?> oldTypeParams = origParamType.typeParams;
               for (Object newTypeParam:typeParams)
                  newTypes.add(newTypeParam);

               // Replace any type parameters we can map into the new type
               int i = 0;
               for (Object oldType:oldTypes) {
                  if (!ModelUtil.isTypeVariable(oldType)) {
                     int newPos = evalParameterPosition(origType, newType, ModelUtil.getTypeParameterName(oldTypeParams.get(i)));
                     if (newPos != -1)
                        newTypes.set(newPos, oldType);
                  }
                  i++;
               }
               ParamTypeDeclaration mergedType = new ParamTypeDeclaration(origParamType.system, typeParams, newTypes, newType);
               return mergedType;
            }
            else {
               // TODO: is this right?  Do we actually even get here?  Basically we do not have any bindings for type parameters
               List<?> typeParams = ModelUtil.getTypeParameters(origType);
               return new ParamTypeDeclaration(sys, definedInType, typeParams, (List<Object>)typeParams, newType);
            }
         }
         else {
            List<?> typeParams = ModelUtil.getTypeParameters(origType);
            int numParams = typeParams.size();
            List<?> newTypeParams = ModelUtil.getTypeParameters(newType);
            ArrayList<Object> typeDefs = new ArrayList<Object>(numParams);
            for (int i = 0; i < numParams; i++) {
               Object op = ModelUtil.getTypeParameter(origType, i);
               Object np = ModelUtil.getTypeParameter(newType, i);
               typeDefs.add(refineType(sys, definedInType, op, np));
            }
            ParamTypeDeclaration mergedParamType = new ParamTypeDeclaration(sys, definedInType, typeParams, typeDefs, ModelUtil.getParamTypeBaseType(newType));
            return mergedParamType;
         }
      }
      return newType;
   }

   public static boolean implementsInterfaces(Object type, Object[] ifaces) {
      for (int i = 0; i < ifaces.length; i++)
         if (!ModelUtil.isAssignableFrom(ifaces[i], type))
            return false;
      return true;
   }

   public static Object[] getAllImplementsTypeDeclarations(Object c1) {
      while (ModelUtil.hasTypeParameters(c1))
         c1 = ModelUtil.getParamTypeBaseType(c1);
      if (c1 instanceof Class) {
         Class class1 = (Class) c1;
         Object[] ifaces = class1.getInterfaces();
         ArrayList<Object> res = new ArrayList<Object>(Arrays.asList(ifaces));
         Object superClass = class1.getSuperclass();
         if (superClass != null) {
            Object[] superIfaces = getAllImplementsTypeDeclarations(superClass);
            for (Object superIface:superIfaces) {
               if (!res.contains(superIface))
                  res.add(superIface);
            }
         }
         return res.toArray();
      }
      else if (c1 instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) c1).getAllImplementsTypeDeclarations();
      }
      else
         throw new UnsupportedOperationException();
   }

   public static Object[] getImplementsTypeDeclarations(Object c1) {
      if (c1 instanceof Class)
         return ((Class) c1).getInterfaces();
      else if (c1 instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) c1).getImplementsTypeDeclarations();
      }
      else if (c1 instanceof ParameterizedType)
         return getImplementsTypeDeclarations(ModelUtil.getParamTypeBaseType(c1));
      else
         throw new UnsupportedOperationException();
   }

   public static Object[] getOverlappingInterfaces(Object c1, Object c2) {
      Object[] if1 = ModelUtil.getAllImplementsTypeDeclarations(c1);
      Object[] if2 = ModelUtil.getAllImplementsTypeDeclarations(c2);
      if (if2 == null)
         return null;
      if (if1 == null)
         return null;
      ArrayList<Object> res = new ArrayList<Object>();
      for (int i = 0; i < if2.length; i++) {
         for (int j = 0; j < if1.length; j++) {
            if (ModelUtil.sameTypes(if1[j], if2[i])) {
               res.add(if1[j]);
               break;
            }
         }
      }
      return res.toArray();
   }

   private static Object coerceTypeParams(LayeredSystem sys, Object o1Param, Object o2Param) {
      Object newParam;
      if (o1Param == null) {
         if (o2Param == null)
            newParam = null;
         else
            newParam = o2Param;
      }
      else if (o2Param == null) {
         newParam = o1Param;
      }
      else {
         boolean o1ParamTypeVar = ModelUtil.isTypeVariable(o1Param);
         boolean o2ParamTypeVar = ModelUtil.isTypeVariable(o2Param);
         if (o1ParamTypeVar) {
            if (o2ParamTypeVar)
               newParam = o1Param; // TODO: do we need to pick the more specific one here?
            else
               newParam = o2Param;
         }
         else if (o2ParamTypeVar) {
            newParam = o1Param;
         }
         else {
            newParam = ModelUtil.findCommonSuperClass(sys, o1Param, o2Param);
         }
      }
      return newParam;
   }

   public static Object findCommonSuperClass(LayeredSystem sys, Object c1, Object c2) {
      Object o1 = c1;
      Object o2 = c2;

      if (o1 == o2)
         return o1;

      if (o1 == null)
         return o2;
      if (o2 == null)
         return o1;

      if (o1 == NullLiteral.NULL_TYPE)
         return o2;
      if (o2 == NullLiteral.NULL_TYPE)
         return o1;

      boolean o1ParamType = ModelUtil.isParameterizedType(o1);
      boolean o2ParamType = ModelUtil.isParameterizedType(o2);
      if (o1ParamType && o2ParamType) {
         Object o1Base = ModelUtil.getParamTypeBaseType(o1);
         Object o2Base = ModelUtil.getParamTypeBaseType(o2);
         if (ModelUtil.sameTypes(o1Base, o2Base)) {
            int numParams = ModelUtil.getNumTypeParameters(o1);
            ArrayList<Object> newParams = new ArrayList<Object>(numParams);
            for (int i = 0; i < numParams; i++) {
               Object o1Param = ModelUtil.getTypeParameter(o1, i);
               Object o2Param = ModelUtil.getTypeParameter(o2, i);
               Object newParam;

               newParam = coerceTypeParams(sys, o1Param, o2Param);
               newParams.add(newParam);
            }
            return new ParamTypeDeclaration(sys, ModelUtil.getTypeParameters(o1), newParams, o1Base);
         }
         else {
            if (ModelUtil.isTypeVariable(o1)) {
               if (ModelUtil.sameTypeParameters(o1, o2))
                  return o1;
               if (ModelUtil.isTypeVariable(o2))
                  return o1;
            }

            if (o1Base == c1)
               System.out.println("*** Error recursive coerceTypes call");
            if (o2Base == c2)
               System.out.println("*** Error recursive coerceTypes call");

            Object newBaseType = ModelUtil.findCommonSuperClass(sys, o1Base, o2Base);
            List<?> newBaseTypeParams = ModelUtil.getTypeParameters(newBaseType);
            int sz = newBaseTypeParams.size();
            ArrayList<Object> newTypeParams = new ArrayList<Object>(sz);

            for (int i = 0; i < sz; i++)
               newTypeParams.add(ModelUtil.coerceTypeParams(sys, ModelUtil.getTypeParameter(o1, i), ModelUtil.getTypeParameter(o2, i)));

            return new ParamTypeDeclaration(sys, newBaseTypeParams, newTypeParams, newBaseType);
         }
      }
      // Strip off the type parameters if only one type has them (or perhaps we should substitute ? for each of them
      else if (o1ParamType)
         o1 = ModelUtil.getParamTypeBaseType(o1);
      else if (o2ParamType)
         o2 = ModelUtil.getParamTypeBaseType(o2);

      while (o1 != null && !ModelUtil.isAssignableFrom(o1, o2, sys))
         o1 = ModelUtil.getSuperclass(o1);

      while (o2 != null && !ModelUtil.isAssignableFrom(o2, c1, sys))
         o2 = ModelUtil.getSuperclass(o2);

      // We found a class which matches so return that
      if (o1 != null && o2 != null && o1 != Object.class && o2 != Object.class)
         return ModelUtil.isAssignableFrom(o1, o2) ? o2 : o1;

      Object i1 = findBestMatchingInterface(c1, c2, null);
      Object i2 = findBestMatchingInterface(c2, c1, null);

      if (i1 != null && i2 != null && i1 != Object.class && i2 != Object.class)
         return ModelUtil.isAssignableFrom(i1, i2, sys) ? i2 : i1;

      if (o1 != null)
         return o1;

      if (o2 != null)
         return o2;

      if (i1 != null)
         return i1;

      if (i2 != null)
         return i2;

      return null;
   }

   public static Object findBestMatchingInterface(Object src, Object target, Object curRes) {
      Object res = curRes;
      Object[] impls = ModelUtil.getImplementsTypeDeclarations(src);
      if (impls != null) {
         for (Object impl:impls) {
            if (ModelUtil.isAssignableFrom(impl, target)) {
               if (res == null || ModelUtil.isAssignableFrom(res, impl))
                  res = impl;
            }
            else {
               // Look for any super-interfaces of this interface that might match.
               res = findBestMatchingInterface(impl, target, res);
            }
         }
      }
      return res;
   }

   // TODO: replace with getRuntimeClass
   public static Class typeToClass(Object type) {
      if (type instanceof Class)
         return (Class) type;
      else if (type instanceof ITypeDeclaration)
         return ((ITypeDeclaration) type).getCompiledClass();
      else
         return null;
   }

   /** Returns just the base part of the name, i.e. "List" for java.util.List.) */
   public static String getClassName(Object type) {
      if (type instanceof Class)
         return CTypeUtil.getClassName(TypeUtil.getTypeName((Class) type, false));
      else if (type instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) type).getTypeName();
      }
      else
         throw new UnsupportedOperationException();
   }

   /** Returns the complete type name of the type with array dimensions, without type parameters */
   public static String getTypeName(Object type) {
      return getTypeName(type, true, false);
   }

   public static String getTypeName(Object type, boolean includeDims) {
      return getTypeName(type, includeDims, false);
   }

   public static String getTypeName(Object type, boolean includeDims, boolean includeTypeParams) {
      if (type == null)
         return "<missing type>";
      if (type instanceof Class) {
         String res = TypeUtil.getTypeName((Class) type, includeDims);
         return res;
      }
      else if (type instanceof ITypeDeclaration) {
         if (includeTypeParams)
            return ((ITypeDeclaration) type).getFullTypeName(includeDims, includeTypeParams);
         else if (includeDims)
            return ((ITypeDeclaration) type).getFullTypeName();
         else
            return ((ITypeDeclaration) type).getFullBaseTypeName();
      }
      else if (ModelUtil.isTypeVariable(type))
         return ModelUtil.getTypeParameterName(type);
      else if (type instanceof ITypedObject) {
         return getTypeName(((ITypedObject) type).getTypeDeclaration(), includeDims);
      }
      else if (type instanceof IBeanMapper)
         return getTypeName(((IBeanMapper) type).getPropertyType(), includeDims);
      else if (type == NullLiteral.NULL_TYPE)
         return (String) type;
      else if (type instanceof ParameterizedType)
         return getTypeName(((ParameterizedType) type).getRawType(), includeDims);
      else if (type instanceof WildcardType)
         return type.toString();
      //else if (ModelUtil.isTypeVariable(type))
      //   return ModelUtil.getTypeParameterName(type);
      throw new UnsupportedOperationException();
   }

   public static String getJavaFullTypeName(Object type) {
      if (type instanceof ITypeDeclaration)
         return ((ITypeDeclaration) type).getJavaFullTypeName();
      else if (type instanceof Class)
         return ((Class) type).getName();
      else if (ModelUtil.hasTypeParameters(type)) {
         return getJavaFullTypeName(ModelUtil.getParamTypeBaseType(type));
      }
      else if (ModelUtil.isTypeVariable(type))
         return ModelUtil.getTypeParameterName(type);
      else
         throw new UnsupportedOperationException();
   }

   // TODO: Performance Fix - me - should be looking up a precedence value in each type and just comparing them
   public static Object coerceNumberTypes(Object lhsType, Object rhsType) {
      // If we are coercing Integer, Float, etc. and Number the base is a Number
      if (isNumber(lhsType))
         return lhsType;

      if (isNumber(rhsType))
         return rhsType;

      if (lhsType == rhsType)
           return lhsType;

      if (isDouble(lhsType))
           return lhsType;

      if (isDouble(rhsType))
           return rhsType;

      if (isFloat(lhsType))
           return lhsType;

      if (isFloat(rhsType))
           return rhsType;

      if (isLong(lhsType))
           return lhsType;

      if (isLong(rhsType))
           return rhsType;

      if (isInteger(lhsType))
           return lhsType;

      if (isInteger(rhsType))
           return rhsType;

      if (isShort(lhsType))
           return lhsType;

      if (isShort(rhsType))
           return rhsType;

      return rhsType;
   }

   public static boolean isArray(Object arrayType) {
      if (arrayType instanceof ParamTypeDeclaration)
         arrayType = ((ParamTypeDeclaration) arrayType).baseType;
      if (arrayType instanceof Class) {
         Class arrayClass = (Class) arrayType;
         return arrayClass.isArray();
      }
      else if (arrayType instanceof IArrayTypeDeclaration) {
         return true;
      }
      else if (arrayType instanceof GenericArrayType)
         return true;
      else if (arrayType instanceof CFClass)
         return false;
      return false;
   }

   public static Object getArrayComponentType(Object arrayType) {
      if (arrayType instanceof ParamTypeDeclaration)
         arrayType = ((ParamTypeDeclaration) arrayType).baseType;
      if (arrayType instanceof Class) {
         Class arrayClass = (Class) arrayType;
         if (!arrayClass.isArray()) 
            return null;

         return arrayClass.getComponentType();
      }
      else if (arrayType instanceof GenericArrayType) {
         return ((GenericArrayType) arrayType).getGenericComponentType();
      }
      else if (arrayType instanceof IArrayTypeDeclaration) {
         return ((IArrayTypeDeclaration) arrayType).getComponentType();
      }
      return null;
   }

   public static boolean hasTypeParameters(Object paramType) {
      return paramType instanceof ParamTypeDeclaration || paramType instanceof ParameterizedType;
   }

   public static boolean hasDefinedTypeParameters(Object paramType) {
      return paramType instanceof ParamTypeDeclaration || paramType instanceof ParameterizedType || ModelUtil.getTypeParameters(paramType) != null;
   }

   public static int getNumTypeParameters(Object paramType) {
      if (paramType instanceof ParamTypeDeclaration) {
         return ((ParamTypeDeclaration) paramType).types.size();
      }
      else if (paramType instanceof ParameterizedType) {
         ParameterizedType pt = (ParameterizedType) paramType;
         return pt.getActualTypeArguments().length;
      }
      else if (paramType instanceof Class) {
         TypeVariable[] vars = ((Class) paramType).getTypeParameters();
         return vars == null ? 0 : vars.length;
      }
      else if (paramType instanceof ITypeDeclaration)
         return 0;
      else throw new UnsupportedOperationException();
   }

   public static Object getTypeVariable(Object paramType, int ix) {
      if (paramType instanceof ParamTypeDeclaration) {
         return ((ParamTypeDeclaration) paramType).typeParams.get(ix);
      }
      else if (paramType instanceof ParameterizedType) {
         ParameterizedType pt = (ParameterizedType) paramType;
         // Looking for type variables here - those are stored on the original class.
         return getTypeVariable(pt.getRawType(), ix);
         //return pt.getActualTypeArguments()[ix];
      }
      else if (paramType instanceof Class) {
         Class cl = (Class) paramType;
         return cl.getTypeParameters()[ix];
      }
      else throw new UnsupportedOperationException();
   }

   public static Object getTypeParameter(Object paramType, int ix) {
      if (paramType instanceof ParamTypeDeclaration) {
         ParamTypeDeclaration ptd = (ParamTypeDeclaration) paramType;
         // If this parameter type is bound, we'll return the bound type.  Otherwise, we return the type parameter placeholder
         Object res = null;
         // Wildcard types <> no type is specified so just use the type param
         if (ptd.types.size() != 0)
            res = ptd.types.get(ix);
         if (res == null && ix < ptd.typeParams.size())
            res = ptd.typeParams.get(ix);
         return res;
      }
      else if (paramType instanceof ParameterizedType) {
         ParameterizedType pt = (ParameterizedType) paramType;
         return pt.getActualTypeArguments()[ix];
      }
      else if (paramType instanceof Class) {
         Class cl = (Class) paramType;
         return cl.getTypeParameters()[ix];
      }
      else if (paramType instanceof ITypeDeclaration) {
         List typeParams = ((ITypeDeclaration) paramType).getClassTypeParameters();
         if (typeParams == null)
            return null;
         return typeParams.get(ix);
      }
      else if (paramType != null) {
         // TODO: maybe a type parameter? this happened once when a library was not resolved
         System.err.println("*** Unrecognized type to getTypeParameter: " + paramType.getClass());
         return null;
      }
      else throw new UnsupportedOperationException();
   }

   public static Object getArrayOrListComponentType(Object arrayType) {
      if (ModelUtil.isAssignableFrom(Iterable.class, arrayType)) {
         if (ModelUtil.isTypeVariable(arrayType))
            arrayType = ModelUtil.getTypeParameterDefault(arrayType);
         // We want to resolve the value of T in Iterable<T> for the current type.  This method remaps the base-type's type parameter to the current type
         Object res = ModelUtil.resolveBaseTypeParameter(arrayType, Iterable.class, ModelUtil.getTypeParameter(Iterable.class, 0));
         if (res != null && ModelUtil.isTypeVariable(res))
            return ModelUtil.getTypeParameterDefault(res);
         return res;
      }
      // for java.util.Map, we'll pull out the type parameter for the value
      else if (ModelUtil.isAssignableFrom(Map.class, arrayType)) {
         if (arrayType instanceof ParamTypeDeclaration) {
            ParamTypeDeclaration ptd = (ParamTypeDeclaration) arrayType;
            if (ptd.types.size() == 2)
               return ptd.types.get(1);
         }
         else if (arrayType instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) arrayType;
            Object[] typeArgs = pt.getActualTypeArguments();
            if (typeArgs.length == 2)
               return typeArgs[1];
         }
         else if (arrayType instanceof ITypeDeclaration)
            return ((ITypeDeclaration) arrayType).getArrayComponentType();
         return Object.class;
      }
      else
         return ModelUtil.getArrayComponentType(arrayType);
   }

   public static boolean isAssignableFrom(Object type1, Object type2) {
      return isAssignableFrom(type1, type2, false, null, false, null);
   }

   public static boolean isAssignableFrom(Object type1, Object type2, LayeredSystem sys) {
      return isAssignableFrom(type1, type2, false, null, false, sys);
   }

   /** Use for comparing parameter types in methods to decide when one overrides the other */
   public static boolean isAssignableTypesFromOverride(Object from, Object to) {
      if (to == null)
         return true;

       return from == to || (from != null && ModelUtil.getTypeName(from).equals(ModelUtil.getTypeName(to)));
   }

   public static boolean isAssignableFrom(Object type1, Object type2, boolean assignmentSemantics, ITypeParamContext ctx) {
      return isAssignableFrom(type1, type2, assignmentSemantics, ctx, false, null);
   }

   public static boolean isAssignableFrom(Object type1, Object type2, boolean assignmentSemantics, ITypeParamContext ctx, LayeredSystem sys) {
      return isAssignableFrom(type1, type2, assignmentSemantics, ctx, false, sys);
   }

   /** Note: the LayeredSystem is an optional parameter here for an edge case that allows us to detect mismatching Classes after we've reset the class loader and reload them rather than compare by name. */
   public static boolean isAssignableFrom(Object type1, Object type2, boolean assignmentSemantics, ITypeParamContext ctx, boolean allowUnbound, LayeredSystem sys) {
      if (type1 == type2)
         return true;

      if (type2 == NullLiteral.NULL_TYPE) {
         if (ModelUtil.isPrimitive(type1))
            return false;
         return true;
      }

      if (type1 instanceof TypeParameter) {
         // There java.lang.Void.class will match a type parameter
         //if (typeIsVoid(type2))
         //   return false;
         // Force primitive types to be their wrapper type so we find matches for Comparable and int
         return ((TypeParameter)type1).isAssignableFrom(wrapPrimitiveType(type2), ctx, allowUnbound);
      }
      if (type2 instanceof TypeParameter) {
         if (allowUnbound) {
            // TODO: enforce the extends type in the TypeParameter
            return true;
         }
         return ((TypeParameter)type2).isAssignableTo(type1, ctx, true /* allowUnbound */);
      }

      while (type1 instanceof TypeVariable) {
         type1 = ((TypeVariable) type1).getBounds()[0];
      }

      if (type2 instanceof TypeVariable) {
         do {
            type2 = ((TypeVariable) type2).getBounds()[0];
         }
         while (type2 instanceof TypeVariable);
         // If we have a type parameter that's not bound and are just matching for an incompatibility, only
         // reject the match if the bounds for the type is incompatible
         if (allowUnbound) {
            if (!ModelUtil.isAssignableFrom(type2, type1))
               return false;
            return true;
         }
      }

      boolean type1Num = isANumber(type1);
      boolean type2Num = isANumber(type2);
      if (type1Num && type2Num)
         // Note: switching the order here to "lhs" = "rhs"
         return numberTypesAssignableFrom(type1, type2, assignmentSemantics);

      if (isBoolean(type1) && isBoolean(type2))
         return true;

      boolean type1Char = isCharacter(type1);
      boolean type2Char = isCharacter(type2);
      if (type1Char && type2Char)
         return true;

      // Characters can be assigned to ints and vice versa during assignments only
      if (assignmentSemantics) {
         if ((type1Char && type2Num) || (type2Char && type1Num))
            return true;
      }
      else {
         // Method parameters - an integer can be assigned with a character argument
         if (isAnInteger(type1) && type2Char)
            return true;
      }

      while (type1 instanceof ParameterizedType)
         type1 = ((ParameterizedType) type1).getRawType();

      while (type2 instanceof ParameterizedType)
         type2 = ((ParameterizedType) type2).getRawType();

      if (type1 instanceof Class) {
         // Accepts double and other types assuming the unboxing rules in Java
         if (type1 == Object.class) {
            if (typeIsVoid(type2))
               return false;
            return true;
         }
         if (type2 instanceof ITypeDeclaration) {
            // In this case, we need to invert the direction of the test.  Let's see if our TypeDeclaration
            // implements a type with the same name.
            return ((ITypeDeclaration) type2).implementsType(((Class) type1).getName(), assignmentSemantics, allowUnbound);
         }
         else if (type2 instanceof Class) {
            Class cl1 = (Class) type1;
            Class cl2 = (Class) type2;
            boolean res = cl1.isAssignableFrom(cl2);

            // We only need this during the refreshBoundType after resetClassLoader.  Because we refresh references in any order
            // we might temporarily get incompatible class loaders during the refreshBoundTypes operation which prevent us from resolving types properly.
            // It seems like a better fix would be to just fix the order so we resolve things before we reference them?
            ClassLoader cl1Loader = cl1.getClassLoader();
            ClassLoader cl2Loader = cl2.getClassLoader();
            if (!res && cl1Loader != cl2Loader) {
               boolean deactivatedLoader = false;
               LayeredSystem sys1 = null, sys2 = null;
               if (cl1Loader instanceof TrackingClassLoader) {
                  TrackingClassLoader tcl1 = ((TrackingClassLoader) cl1Loader);
                  deactivatedLoader = tcl1.deactivated;
                  sys1 = tcl1.getSystem();
               }
               if (cl2Loader instanceof TrackingClassLoader) {
                  TrackingClassLoader tcl2 = ((TrackingClassLoader) cl2Loader);
                  deactivatedLoader |= tcl2.deactivated;
                  sys2 = tcl2.getSystem();
               }
               // It's possible we have the same class but different class loaders, either because one class loader was deactivated or we are dealing with two different layered systems - i.e.
               // finding an assignment across runtimes like java and js.  If so we need to convert the classes into the same class loader before doing the isAssignableFrom test.
               if (deactivatedLoader || sys1 != sys2) {
                  if (sys != null) {
                     Object ncl1, ncl2;
                     ncl1 = ModelUtil.refreshBoundClass(sys, cl1);
                     ncl2 = ModelUtil.refreshBoundClass(sys, cl2);
                     if (ncl1 != cl1 || ncl2 != cl2)
                        return ModelUtil.isAssignableFrom(ncl1, ncl2, assignmentSemantics, ctx, allowUnbound, sys);
                  }
                  if (ModelUtil.isAssignableByName(cl1, cl2)) {
                     return true;
                  }
               }
            }

            // Java will do a conversion from int to Integer so you can for example cast an int to Comparable and it will work.
            if (!res && cl2.isPrimitive()) {
               Class clWrapper = sc.type.Type.get(cl2).getObjectClass();
               res = isAssignableFrom(cl1, clWrapper);
            }

            return res;
         }
         else if (type2 == null)
            return false;
         else if (type2 instanceof BaseLambdaExpression.LambdaInferredType)
            return true;
         else
            throw new UnsupportedOperationException();
      }
      else if (type1 instanceof ITypeDeclaration) {
         ITypeDeclaration decl1 = (ITypeDeclaration) type1;
         if (type2 instanceof Class) {
            return decl1.isAssignableFromClass((Class) type2);
         }
         if (!(type2 instanceof ITypeDeclaration))
            System.out.println("*** Error - invalid type to isAssignable method");

         if (type2 instanceof BaseLambdaExpression.LambdaInferredType)
            return true;

         ITypeDeclaration decl2 = (ITypeDeclaration) type2;
         return decl1.isAssignableFrom(decl2, assignmentSemantics);
      }
      else if (type1 == null)
         return false;
      else
         throw new UnsupportedOperationException();
   }

   public static boolean isAssignableByName(Object cl1, Object cl2) {
      if (ModelUtil.getTypeName(cl1).equals(ModelUtil.getTypeName(cl2)))
         return true;

      Object[] implTypes = ModelUtil.getImplementsTypeDeclarations(cl2);
      if (implTypes != null) {
         for (Object implType:implTypes)
            if (isAssignableByName(cl1, implType))
               return true;
      }
      Object extType = ModelUtil.getExtendsClass(cl2);
      if (extType != null)
         if (isAssignableByName(cl1, extType))
            return true;
      return false;
   }

   public static boolean isString(Object type) {
      return type == String.class || (type instanceof CFClass && ((CFClass) type).isString()) ||
              type instanceof PrimitiveType && ((PrimitiveType) type).getRuntimeClass() == String.class || (type instanceof ITypeDeclaration && ((ITypeDeclaration) type).getFullTypeName().equals("java.lang.String"));
   }

   public static boolean isDouble(Object type) {
      return type == Double.class || type == Double.TYPE || (type instanceof CFClass && ((CFClass) type).isDouble()) ||
             (type instanceof PrimitiveType && isDouble(((PrimitiveType) type).getRuntimeClass())) || (type instanceof WrappedTypeDeclaration && ModelUtil.isDouble(((WrappedTypeDeclaration) type).getBaseType()));
   }

   public static boolean isFloat(Object type) {
      return type == Float.class || type == Float.TYPE || (type instanceof CFClass && ((CFClass) type).isFloat()) ||
             (type instanceof PrimitiveType && isFloat(((PrimitiveType) type).getRuntimeClass())) || (type instanceof WrappedTypeDeclaration && ModelUtil.isFloat(((WrappedTypeDeclaration) type).getBaseType()));
   }

   public static boolean isLong(Object type) {
      return type == Long.class || type == Long.TYPE || (type instanceof CFClass && ((CFClass) type).isLong()) ||
             (type instanceof PrimitiveType && isLong(((PrimitiveType) type).getRuntimeClass())) || (type instanceof WrappedTypeDeclaration && ModelUtil.isLong(((WrappedTypeDeclaration) type).getBaseType()));
   }

   public static boolean isInteger(Object type) {
      return type == Integer.class || type == Integer.TYPE || (type instanceof CFClass && ((CFClass) type).isInteger()) ||
              (type instanceof PrimitiveType && isInteger(((PrimitiveType) type).getRuntimeClass())) || (type instanceof WrappedTypeDeclaration && ModelUtil.isInteger(((WrappedTypeDeclaration) type).getBaseType()));
   }

   public static boolean isShort(Object type) {
      return type == Short.class || type == Short.TYPE || (type instanceof CFClass && ((CFClass) type).isShort()) ||
             (type instanceof PrimitiveType && isShort(((PrimitiveType) type).getRuntimeClass())) || (type instanceof WrappedTypeDeclaration && ModelUtil.isShort(((WrappedTypeDeclaration) type).getBaseType()));
   }

   public static boolean isByte(Object type) {
      return type == Byte.class || type == Byte.TYPE || (type instanceof CFClass && ((CFClass) type).isByte()) ||
             (type instanceof PrimitiveType && isByte(((PrimitiveType) type).getRuntimeClass())) || (type instanceof WrappedTypeDeclaration && ModelUtil.isByte(((WrappedTypeDeclaration) type).getBaseType()));
   }

   public static boolean isAnInteger(Object type) {
      return isInteger(type) || isShort(type) || isByte(type) || isLong(type);
   }

   private static boolean numberTypesAssignableFrom(Object lhsType, Object rhsType, boolean assignmentSemantics) {
      // In case there are annotation layers that modify the Java native types (like there are for JS) we need to skip those types
      while (lhsType instanceof ModifyDeclaration) {
         lhsType = ((ModifyDeclaration) lhsType).getDerivedTypeDeclaration();
      }

      while (rhsType instanceof ModifyDeclaration) {
         rhsType = ((ModifyDeclaration) rhsType).getDerivedTypeDeclaration();
      }

      // Any number can be assigned to a double or a number
      if (isDouble(lhsType) || isNumber(lhsType)) {
         return true;
      }

      // Any number but a double can be assigned to a float
      if (isFloat(lhsType))
         return !isDouble(rhsType);

      // Any integer type can be assigned to a long during an assignment but
      // when matching parameters of a method, it must be a long
      if (isLong(lhsType)) {
         if (assignmentSemantics)
            return !isFloat(rhsType);
         else
            return isAnInteger(rhsType);
      }

      // Integer types can't be assigned with longs or floats during assignments but not matching methods
      assert isAnInteger(lhsType);
      if (assignmentSemantics)
         return !isLong(rhsType) && !isFloat(rhsType) && !isDouble(rhsType);
      else {
         // Java allows silent conversion up but not down
         if (isInteger(lhsType))
            return isInteger(rhsType) || isShort(rhsType) || isByte(rhsType);
         if (isShort(lhsType))
            return isShort(rhsType) || isByte(rhsType);
         if (isByte(lhsType))
            return isByte(rhsType);

         throw new UnsupportedOperationException();
      }
   }

   public static boolean implementsType(Object implType, String fullTypeName, boolean assignment, boolean allowUnbound) {
      if (implType instanceof ITypeDeclaration)
         return ((ITypeDeclaration) implType).implementsType(fullTypeName, assignment, allowUnbound);
      else if (implType instanceof Class) {
         Class implClass = (Class) implType;

         Class typeClass = RTypeUtil.loadClass(implClass.getClassLoader(), fullTypeName, false);

         return typeClass != null && typeClass.isAssignableFrom(implClass);
      }
      else if (implType instanceof ParameterizedType) {
         return implementsType(((ParameterizedType) implType).getRawType(), fullTypeName, assignment, allowUnbound);
      }
      else
         throw new UnsupportedOperationException();
   }

   /**
    * As we are inheriting definitions we may find both src/dst types have the same annotation.
    * This method merges the definitions.  When we are merging up to a more specific layer, we only
    * copy definitions not found in the destination layer (replace=false).  When we are merging from
    * a more specific to a more general definition (the modify declaration), we replace conflicting
    * definitions in the dest.
    */
   public static Object mergeAnnotations(Object mainAnnot, Object overAnnot, boolean replace) {
      boolean any = false;

      Annotation newAnnotation = null;

      // Three types of annotations:
      // Marker: no value - nothing to merge
      // SingleElementAnnotation - if replace, we replace the value otherwise do nothing
      // InstInit Annotation - merge the name/values replacing values if replace is true

      if (ModelUtil.isComplexAnnotation(overAnnot)) {
         if (!ModelUtil.isComplexAnnotation(mainAnnot))
            return replace ? overAnnot : mainAnnot; // Weird case - an @Foo(a=1,b=2) overriding @Foo(3) or @Foo - use the more specific one or do we need a way to clear the

         List<AnnotationValue> overriddenValues = ModelUtil.getAnnotationComplexValues(overAnnot);
         List<AnnotationValue> thisElementValues = ModelUtil.getAnnotationComplexValues(mainAnnot);
         int thisSz = thisElementValues.size();
         for (int i = 0; i < overriddenValues.size(); i++) {
            AnnotationValue av = overriddenValues.get(i);

            int tix;
            AnnotationValue tv = null;
            for (tix = 0; tix < thisSz; tix++) {
               tv = thisElementValues.get(tix);
               if (tv.identifier.equals(av.identifier)) {
                  break;
               }
            }
            if (tix != thisSz) {
               if (replace) {
                  if (tv != null)
                     thisElementValues.remove(tv);
                  thisElementValues.add(av);
               }
               // else - use mainAnnot version
            }
            // Overridden value not found in main - first time clone the element, then add the overridden guys to this new list only if their' not in main
            else {
               if (newAnnotation == null) {
                  newAnnotation = Annotation.createFromAnnotation(mainAnnot);
               }
               List<Object> newValues = (List<Object>) newAnnotation.elementValue;
               newValues.add(av.deepCopy(ISemanticNode.CopyNormal, null));
            }
         }
         if (newAnnotation != null)
            return newAnnotation;
      }
      else if (replace) {
         Object overrideSV = ModelUtil.getAnnotationSingleValue(overAnnot);
         if (overrideSV != null)
            return overrideSV;
      }
      return mainAnnot;
   }

   public static Object getPropertyAnnotation(Object property, String annotationName) {
      Object res = getAnnotation(property, annotationName);
      if (res != null)
         return res;
      if (ModelUtil.isGetMethod(property)) {
         Object setMethod = ModelUtil.getSetMethodFromGet(property);
         if (setMethod != null)
            return ModelUtil.getAnnotation(setMethod, annotationName);
      }
      else if (ModelUtil.isSetMethod(property)) {
         Object getMethod = ModelUtil.getGetMethodFromSet(property);
         if (getMethod != null)
            return ModelUtil.getAnnotation(getMethod, annotationName);
      }
      return null;
   }

   public static Map<String,Object> getPropertyAnnotations(Object property) {
      Map<String,Object> res = getAnnotations(property);
      Map<String,Object> newRes = null;
      if (ModelUtil.isGetMethod(property)) {
         Object setMethod = ModelUtil.getSetMethodFromGet(property);
         if (setMethod != null) {
            newRes = ModelUtil.getAnnotations(setMethod);
         }
      }
      else if (ModelUtil.isSetMethod(property)) {
         Object getMethod = ModelUtil.getGetMethodFromSet(property);
         if (getMethod != null)
            newRes = ModelUtil.getAnnotations(getMethod);
      }
      if (res == null)
         return newRes;
      if (newRes == null)
         return res;
      HashMap<String,Object> combined = new HashMap<String,Object>();
      combined.putAll(res);
      combined.putAll(newRes);
      return combined;
   }

   public static List<Object> getPropertiesWithAnnotation(Object type, String annotationName) {
      Object[] props = getProperties(type, null);
      List<Object> res = null;
      if (props != null) {
         for (Object prop:props) {
            if (getPropertyAnnotation(prop, annotationName) != null) {
               if (res == null)
                  res = new ArrayList<Object>();
               res.add(prop);
            }
         }
      }
      return res;
   }

   /** This method is available in the Javascript runtime so we have it here so the APIs stay in sync */
   public static boolean hasAnnotation(Object definition, String annotationName) {
      return getAnnotation(definition, annotationName) != null;
   }

   public static Object getAnnotation(Object definition, String annotationName) {
      PerfMon.start("getAnnotation");
      try {
      if (definition instanceof IDefinition) {
         return ((IDefinition) definition).getAnnotation(annotationName);
      }
      else if (definition instanceof VariableDefinition)
         return getAnnotation(((VariableDefinition) definition).getDefinition(), annotationName);
      else {
         Class annotationClass = RDynUtil.loadClass(annotationName);
         if (annotationClass == null) {
            annotationClass = RDynUtil.loadClass("sc.obj." + annotationName);
            if (annotationClass == null) {
               // NOTE: assuming that the layers are defined properly, compiled classes can't have non-compiled annotations so just return null, e.g. searching for a JSSettings or MainInit annotation on a compiled class.
               return null;
            }
            else
               System.err.println("*** Using auto-imported sc.obj annoatation: " + annotationName);
         }

         if (definition instanceof ParameterizedType) {
            definition = ((ParameterizedType) definition).getRawType();
         }

         java.lang.annotation.Annotation jlannot;
         if (definition instanceof Class)
            jlannot = ((Class) definition).getAnnotation(annotationClass);
         else if (definition instanceof AnnotatedElement)
            jlannot = ((AnnotatedElement) definition).getAnnotation(annotationClass);
         // For bindable annotation at least, we need to get the setter and right now getPropertyMember returns the get over the set
         else if (definition instanceof IBeanMapper) {
            IBeanMapper mapper = (IBeanMapper) definition;
            Object ret = null;
            Object sel;
            if ((sel = mapper.getGetSelector()) != null) {
               ret = getAnnotation(sel, annotationName);
               if (ret != null)
                  return ret;
            }
            if ((sel = mapper.getSetSelector()) != null) {
               ret = getAnnotation(sel, annotationName);
               if (ret != null)
                  return ret;
            }
            if ((sel = mapper.getField()) != null) {
               ret = getAnnotation(sel, annotationName);
               if (ret != null)
                  return ret;
            }
            return null;
         }
         else if (definition instanceof java.lang.Enum || definition instanceof ITypeDeclaration || ModelUtil.isTypeVariable(definition))
            return null;
         else if (definition instanceof PrimitiveType)
            return null;
         else
            throw new UnsupportedOperationException();

         return jlannot;
      }
      }
      finally {
         PerfMon.end("getAnnotation");
      }
   }

   public static EnumSet<ElementType> getAnnotationTargets(Object annot) {
      if (annot instanceof Annotation) {
         annot = ((Annotation) annot).boundType;
         if (annot == null)
            return null;
      }
      Object targetAnnot = ModelUtil.getAnnotation(annot, "java.lang.annotation.Target");
      if (targetAnnot != null) {
         targetAnnot = ModelUtil.getAnnotationSingleValue(targetAnnot);
         if (targetAnnot instanceof java.lang.annotation.ElementType[]) {
            return EnumSet.copyOf(Arrays.asList((ElementType[]) targetAnnot));
         }
      }
      return null;
   }

   public static Object getAnnotationValueFromList(List<Object> annotations, String s) {
      for (Object annotation:annotations) {
         Object val;
         if (annotation instanceof IAnnotation)
            val = ((IAnnotation) annotation).getAnnotationValue(s);
         else if (annotation instanceof java.lang.annotation.Annotation)
            val = PTypeUtil.invokeMethod(annotation, RTypeUtil.getMethod(annotation.getClass(), s), (Object[]) null);
         else
            throw new UnsupportedOperationException();
         if (val != null)
            return val;
      }
      return null;
   }

   public static Object getAnnotationValue(Object annotation, String s) {
      if (annotation instanceof IAnnotation)
         return ((IAnnotation) annotation).getAnnotationValue(s);
      else if (annotation instanceof java.lang.annotation.Annotation)
         return PTypeUtil.invokeMethod(annotation, RTypeUtil.getMethod(annotation.getClass(), s), (Object[])null);
      throw new UnsupportedOperationException();
   }

   public static boolean isComplexAnnotation(Object annotation) {
      if (annotation instanceof IAnnotation)
         return ((IAnnotation) annotation).isComplexAnnotation();
      else if (annotation instanceof java.lang.annotation.Annotation) {
         Class cl = annotation.getClass();

         Method[] annotMethods = cl.getDeclaredMethods();
         // An annotation with one "value()" method is simple, no methods is a marker.
         return annotMethods != null && annotMethods.length != 0 && (annotMethods.length != 1 || !annotMethods[0].getName().equals("value"));
      }
      throw new UnsupportedOperationException();
   }

   public static Object[] parametersToTypeArray(List<? extends Object> parameters, ITypeParamContext ctx) {
      int size = parameters == null ? 0 : parameters.size();
      // Perf tuneup: could move logic to do comparisons into the cache so we don't allocate the temporary array here
      Object[] parameterTypes = new Object[size];
      for (int i = 0; i < size; i++)
         parameterTypes[i] = ModelUtil.getVariableTypeDeclaration(parameters.get(i));
      return parameterTypes;
   }

   public static JavaType[] parametersToJavaTypeArray(LayeredSystem sys, Object method, List<? extends Object> parameters, ParamTypedMethod ctx) {
      Object[] paramTypes = ModelUtil.getParameterTypes(method, false);
      int size = parameters == null ? 0 : parameters.size();
      // Perf tuneup: could move logic to do comparisons into the cache so we don't allocate the temporary array here
      JavaType[] parameterTypes = new JavaType[size];
      for (int i = 0; i < size; i++) {
         Object param = parameters.get(i);
         if (param instanceof Expression)
            param = ((Expression) param).getTypeDeclaration();
         Object paramType;
         if (param instanceof BaseLambdaExpression.LambdaInferredType) {
            param = NullLiteral.NULL_TYPE;
            // Mark this method so we know it may have unbound type parameters
            ctx.unboundInferredType = true;
         }
         if (param == NullLiteral.NULL_TYPE) {
            if (i >= paramTypes.length)
               paramType = paramTypes[paramTypes.length-1];
            else
               paramType = paramTypes[i];
         }
         else if (param != null)
            paramType = ModelUtil.getVariableTypeDeclaration(param);
         else
            paramType = null;
         if (paramType instanceof JavaType)
            parameterTypes[i] = (JavaType) paramType;
         else if (paramType != null) {
            // Used to pass in ctx here as the type param context but really the method's paramType info should not be used
            // to resolve the type variables here in that context.
            parameterTypes[i] = JavaType.createFromParamType(sys, paramType, null, ctx.getDefinedInType());
         }
         else
            parameterTypes[i] = null;
      }
      return parameterTypes;
   }


   public static Object definesMethod(Object td, String name, List<? extends Object> parameters, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs) {
      return definesMethod(td, name, parameters, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs, null);
   }

   public static Object definesMethod(Object td, String name, List<? extends Object> parameters, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs, LayeredSystem sys) {
      Object res;
      if (td instanceof ITypeDeclaration) {
         if ((res = ((ITypeDeclaration)td).definesMethod(name, parameters, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs)) != null)
            return res;
      }
      else if (td instanceof Class) {
         if ((res = ModelUtil.getMethod(sys, td, name, refType, ctx, inferredType, staticOnly, methodTypeArgs, parameters, null)) != null)
            return res;
      }
      return null;
   }

   public static Object declaresConstructor(LayeredSystem sys, Object td, List<?> parameters, ITypeParamContext ctx) {
      return declaresConstructor(sys, td, parameters, ctx, null);
   }

   public static Object declaresConstructor(LayeredSystem sys, Object td, List<?> parameters, ITypeParamContext ctx, Object refType) {
      Object res;
      if (td instanceof ITypeDeclaration) {
         if ((res = ((ITypeDeclaration)td).declaresConstructor(parameters, ctx)) != null)
            return res;
      }
      else if (td instanceof Class) {
         int paramsLen = parameters == null ? 0 : parameters.size();
         Object[] types = parametersToTypeArray(parameters, ctx);
         Object[] list = getConstructors(td, null);
         res = null;
         Object[] prevExprTypes = null;
         ArrayList<Expression> toClear = null;
         Object enclType = ModelUtil.getEnclosingInstType(td);
         int paramStart = 0;

         // The compiled class will have the outer class as an instance parameter.  When calling from the source world though, we don't have
         // that parameter in the list.  Skip that parameter.
         if (enclType != null)
            paramStart = 1;
         for (int i = 0; i < list.length; i++) {
            Object toCheck = list[i];
            Object[] parameterTypes = ModelUtil.getParameterTypes(toCheck);
            int paramLen = (parameterTypes == null ? 0 : parameterTypes.length) - paramStart;
            int last = paramLen - 1 + paramStart;
            if (paramLen != paramsLen) {
               // If the last guy is not a repeating parameter, it can't match
               if (last < 0 || !ModelUtil.isVarArgs(toCheck) || !ModelUtil.isArray(parameterTypes[last]) || paramsLen < last)
                  continue;
            }

            ParamTypedMethod paramMethod = null;
            if (!(toCheck instanceof ParamTypedMethod) && ModelUtil.isParameterizedMethod(toCheck)) {

               // TODO: resultClass converted to definedIntype here - we could do a wrapper for the getClass method
               paramMethod = new ParamTypedMethod(sys, toCheck, ctx, td, parameters, null, null);

               parameterTypes = paramMethod.getParameterTypes(true);
               toCheck = paramMethod;
               if (paramMethod.invalidTypeParameter)
                  continue;
            }

            if (paramLen == 0 && paramsLen == 0) {
               if (refType == null || checkAccess(refType, toCheck))
                  res = ModelUtil.pickMoreSpecificMethod(res, toCheck, null, null, parameters);
            }
            else {
               int j;
               Object[] nextExprTypes = new Object[paramsLen];
               for (j = 0; j < paramsLen; j++) {
                  Object paramType;
                  if (j > last) {
                     if (!ModelUtil.isArray(paramType = parameterTypes[last]))
                        break;
                  }
                  else
                     paramType = parameterTypes[j+paramStart];

                  Object exprType;
                  Object exprObj = parameters.get(j);

                  if (exprObj instanceof Expression) {
                     if (paramType instanceof ParamTypeDeclaration)
                        paramType = ((ParamTypeDeclaration) paramType).cloneForNewTypes();
                     Expression paramExpr = (Expression) exprObj;
                     paramExpr.setInferredType(paramType, false);
                     if (toClear == null)
                        toClear = new ArrayList<Expression>();
                     toClear.add(paramExpr);
                  }
                  exprType = ModelUtil.getVariableTypeDeclaration(exprObj);

                  nextExprTypes[j] = exprType;

                  if (exprType != null && !ModelUtil.isAssignableFrom(paramType, exprType, false, ctx)) {
                     // Repeating parameters... if the last parameter is an array match if the component type matches
                     if (j >= last && ModelUtil.isArray(paramType) && ModelUtil.isVarArgs(toCheck)) {
                        if (!ModelUtil.isAssignableFrom(ModelUtil.getArrayComponentType(paramType), types[j], false, ctx)) {
                           break;
                        }
                     }
                     else
                        break;
                  }
               }
               if (j == paramsLen) {
                  if (refType == null || checkAccess(refType, toCheck)) {
                     res = ModelUtil.pickMoreSpecificMethod(res, toCheck, nextExprTypes, prevExprTypes, parameters);
                     if (res == toCheck)
                        prevExprTypes = nextExprTypes;
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
         return res;
      }
      else
         throw new UnsupportedOperationException();
      return null;
   }

   /**
    * NOTE: it's rare that you want to use this method - use declaresConstructor instead.  This one searches for a matching
    * constructor in the type hierarchy but Java does not inherit constructors automatically from one type to it's sub-types.
    */
   public static Object definesConstructor(LayeredSystem sys, Object td, List<?> parameters, ITypeParamContext ctx) {
      return definesConstructor(sys, td, parameters, ctx, null, false);
   }

   // This version uses the getConstructors method to match methods generically using ITypeDeclaration implementations, IMethodDefinition etc.
   public static Object definesConstructorFromList(LayeredSystem sys, Object td, List<?> parameters, ITypeParamContext ctx, Object refType, boolean isTransformed) {
      Object[] list = getConstructors(td, null);
      if (list == null)
         return null;

      int argsLen = parameters == null ? 0 : parameters.size();
      Object[] types = parametersToTypeArray(parameters, ctx);
      Object res = null;
      Object[] prevExprTypes = null;
      ArrayList<Expression> toClear = null;
      for (int i = 0; i < list.length; i++) {
         Object toCheck = list[i];
         Object[] parameterTypes = ModelUtil.getParameterTypes(toCheck);
         int paramLen = parameterTypes == null ? 0 : parameterTypes.length;
         int last = paramLen - 1;
         boolean isVarArgs = ModelUtil.isVarArgs(toCheck);
         if (paramLen != argsLen) {
            // If the last guy is not a repeating parameter, it can't match
            if (last < 0 || !isVarArgs || !ModelUtil.isArray(parameterTypes[last]) || argsLen < last)
               continue;
         }

         ParamTypedMethod paramMethod = null;
         if (!(toCheck instanceof ParamTypedMethod) && ModelUtil.isParameterizedMethod(toCheck)) {

            // TODO: resultClass converted to definedIntype here - we could do a wrapper for the getClass method
            paramMethod = new ParamTypedMethod(sys, toCheck, ctx, td, parameters, null, null);

            parameterTypes = paramMethod.getParameterTypes(true);
            toCheck = paramMethod;
            if (paramMethod.invalidTypeParameter)
               continue;
         }

         if (paramLen == 0 && argsLen == 0) {
            if (refType == null || checkAccess(refType, toCheck))
               res = ModelUtil.pickMoreSpecificMethod(res, toCheck, null, null, parameters);
         }
         else {
            int j;
            Object[] nextExprTypes = new Object[argsLen];
            for (j = 0; j < argsLen; j++) {
               Object paramType;
               if (j > last) {
                  if (!ModelUtil.isArray(paramType = parameterTypes[last]))
                     break;
               }
               else
                  paramType = parameterTypes[j];

               Object exprType;
               Object exprObj = parameters.get(j);

               if (exprObj instanceof Expression) {
                  Object infParamType = paramType;
                  if (infParamType instanceof ParamTypeDeclaration)
                     infParamType = ((ParamTypeDeclaration) infParamType).cloneForNewTypes();
                  Expression paramExpr = (Expression) exprObj;
                  if (isVarArgs && j >= last && ModelUtil.isArray(infParamType)) {
                     infParamType = ModelUtil.getArrayComponentType(infParamType);
                  }
                  paramExpr.setInferredType(infParamType, false);
                  if (toClear == null)
                     toClear = new ArrayList<Expression>();
                  toClear.add(paramExpr);
               }
               exprType = ModelUtil.getVariableTypeDeclaration(exprObj);

               // Lambda inferred type is not valid so can't be a match
               if (exprType instanceof BaseLambdaExpression.LambdaInvalidType)
                  break;

               nextExprTypes[j] = exprType;

               if (exprType != null && !ModelUtil.isAssignableFrom(paramType, exprType, false, ctx)) {
                  // Repeating parameters... if the last parameter is an array match if the component type matches
                  if (j >= last && ModelUtil.isArray(paramType) && isVarArgs) {
                     if (!ModelUtil.isAssignableFrom(ModelUtil.getArrayComponentType(paramType), types[j], false, ctx)) {
                        break;
                     }
                  }
                  else
                     break;
               }
            }
            if (j == argsLen) {
               if (refType == null || checkAccess(refType, toCheck)) {
                  res = ModelUtil.pickMoreSpecificMethod(res, toCheck, nextExprTypes, prevExprTypes, parameters); // TODO separate types for each inferred type
                  if (res == toCheck)
                     prevExprTypes = nextExprTypes;
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
      return res;
   }

   public static Object definesConstructor(LayeredSystem sys, Object td, List<?> parameters, ITypeParamContext ctx, Object refType, boolean isTransformed) {
      Object res;
      if (td instanceof ITypeDeclaration && !(td instanceof CFClass)) {
         if ((res = ((ITypeDeclaration)td).definesConstructor(parameters, ctx, isTransformed)) != null)
            return res;
      }
      /*
      else if (td instanceof Class) {
         int size = parameters == null ? 0 : parameters.size();
         // Perf tuneup: could move logic to do comparisons into the cache so we don't allocate the temporary array here
         Class[] parameterTypes = new Class[size];
         for (int i = 0; i < size; i++)
            parameterTypes[i] = ModelUtil.getCompiledClass(parameters.get(i));
         // Does this need any more flexible matching ala ModelUtil.getMethod?
         if ((res = RTypeUtil.getConstructor((Class) td, parameterTypes)) != null)
            return res;
      }
      */
      else if (td instanceof Class || td instanceof CFClass) {
         return definesConstructorFromList(sys, td, parameters, ctx, refType, isTransformed);
      }
      else
         throw new UnsupportedOperationException();
      return null;
   }

   public static Object[] getConstructors(Object td, Object refType) {
      return getConstructors(td, refType, false);
   }

   public static Object[] getConstructors(Object td, Object refType, boolean includeHidden) {
      while (ModelUtil.hasTypeParameters(td))
         td = ModelUtil.getParamTypeBaseType(td);
      if (td instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) td).getConstructors(refType, includeHidden);
      else if (td instanceof ITypeDeclaration)
         return ((ITypeDeclaration) td).getConstructors(refType);
      else if (td instanceof Class) {
         Object[] res = RTypeUtil.getConstructors((Class) td);
         if (refType != null && res != null) {
            ArrayList<Object> accessibles = null;
            for (Object constr:res) {
               if (!checkAccess(refType, constr)) {
                  if (accessibles == null) {
                     accessibles = new ArrayList<Object>(res.length-1);
                     for (Object toAdd:res) {
                        if (toAdd == constr)
                           break;
                        accessibles.add(toAdd);
                     }
                  }
               }
               else if (accessibles != null)
                  accessibles.add(constr);
            }
            if (accessibles != null)
               return accessibles.toArray();
         }
         return res;
      }
      else
         throw new UnsupportedOperationException();
   }

   public static boolean hasDefaultConstructor(LayeredSystem sys, Object td, Object refType, JavaSemanticNode refNode, Layer refLayer) {
      Object[] constrs = getConstructors(td, refType);
      if (constrs == null || constrs.length == 0) {
         Object propConstr = getPropagatedConstructor(sys, td, refNode, refLayer);
         if (propConstr == null)
            return true;
         Object[] paramTypes = ModelUtil.getParameterTypes(propConstr);
         if (paramTypes == null || paramTypes.length == 0)
            return true;
         return false;
      }
      for (Object constr:constrs) {
         Object[] paramTypes = ModelUtil.getParameterTypes(constr);
         if (paramTypes == null || paramTypes.length == 0)
            return true;
      }
      return false;
   }

   public static boolean transformNewExpression(LayeredSystem sys, Object boundType) {
      // For types which have the component interface, if it is either an object or has a new template
      // we need to transform new Foo into a call to the newFoo method that was generated.
      if (!ModelUtil.isComponentInterface(sys, boundType))
         return false;
      // We always generate the newX methods for auto components but if it just implements the interface, it may not have one.
      if ((boundType instanceof Class || (boundType instanceof TypeDeclaration && !((TypeDeclaration) boundType).isAutoComponent())) && getMethods(boundType, "new" + ModelUtil.getClassName(boundType), null, false) == null)
         return false;
      return true;
   }

   public static boolean isObjectType(Object boundType) {
      return (boundType instanceof TypeDeclaration && ((TypeDeclaration) boundType).getDeclarationType() == DeclarationType.OBJECT) || hasObjectTypeAnnotation(boundType);
   }

   public static boolean isObjectProperty(Object boundType) {
      return boundType != null && (boundType instanceof TypeDeclaration && ((TypeDeclaration) boundType).isObjectProperty());
   }

   /** Is this an object type which overrides a previous getX/setX method */
   public static boolean isObjectSetProperty(Object boundType) {
      return boundType != null && (boundType instanceof BodyTypeDeclaration && ((BodyTypeDeclaration) boundType).isObjectSetProperty());
   }

   public static boolean hasObjectTypeAnnotation(Object boundType) {
      Object annotation = getAnnotation(boundType, "sc.obj.TypeSettings");
      if (annotation == null) {

         // It's possible we should treat getX without setX as constant by default?   I suspect the values of those
         // will change but we would not be able to detect that by default anyway.
         return false;
      }
      else {
         Boolean b = (Boolean) getAnnotationValue(annotation, "objectType");
         if (b == null)
            return false;
         return b;
      }
   }

   /** If given a top-level object type, returns the object instance.  If given a class, creates an instance of that class using the zero arg constructor.  */
   public static Object getObjectInstance(Object typeObj) {
      Object inst;
      if (ModelUtil.isObjectType(typeObj)) {
         Object runtimeType = ModelUtil.getRuntimeType(typeObj);
         if (runtimeType instanceof TypeDeclaration) {
            inst = ((TypeDeclaration) runtimeType).getObjectInstance();
         }
         else
            inst = DynUtil.getStaticProperty(runtimeType, CTypeUtil.decapitalizePropertyName(getClassName(typeObj)));
      }
      else {
         ExecutionContext ctx;
         if (typeObj instanceof ITypeDeclaration)
            ctx = new ExecutionContext(ModelUtil.getJavaModel(typeObj));
         else
            ctx = new ExecutionContext();
         inst = createInstance(typeObj, null, new ArrayList<Expression>(0), ctx);
      }
      return inst;
   }

   public static Object getAndRegisterGlobalObjectInstance(Object typeObj) {
      Object inst = getObjectInstance(typeObj);
      // Register this as a top-level object with the system under the type name.
      if (inst != null && ModelUtil.getLiveDynamicTypes(typeObj)) {
         DynUtil.addDynObject(ModelUtil.getTypeName(typeObj), inst);
      }
      return inst;
   }

   public static boolean isField(Object varObj) {
      if (varObj instanceof ParamTypedMember)
         return isField(((ParamTypedMember) varObj).getMemberObject());
      if (varObj instanceof PropertyAssignment)
         return isField(((PropertyAssignment) varObj).assignedProperty);
      return varObj instanceof Field || (varObj instanceof IBeanMapper && isField(((IBeanMapper) varObj).getPropertyMember())) || varObj instanceof IFieldDefinition ||
          varObj instanceof FieldDefinition || (varObj instanceof VariableDefinition && (((VariableDefinition) varObj).isProperty()));
   }

   public static boolean hasField(Object varObj) {
      if ((varObj instanceof IBeanMapper) && ((IBeanMapper) varObj).getField() != null)
         return true;
      if ((varObj instanceof IMethodDefinition)) {
         return ((IMethodDefinition) varObj).hasField();
      }
      return false;
   }

   public static boolean isProperty(Object member) {
      if (member instanceof ParamTypedMember)
         member = ((ParamTypedMember) member).getMemberObject();
      return member instanceof PropertyAssignment || member instanceof IBeanMapper || isField(member) || isGetSetMethod(member);
   }

   public static boolean isReadableProperty(Object member) {
      return isProperty(member) && (hasGetMethod(member) || isField(member) || hasField(member));
   }

   public static boolean isWritableProperty(Object member) {
      return isProperty(member) && (hasSetMethod(member) || isField(member) || hasField(member));
   }

   public static boolean isGetSetMethod(Object member) {
      if (member instanceof PropertyAssignment)
         member = ((PropertyAssignment) member).getAssignedProperty();
      if (member instanceof Method) {
         Method meth = (Method) member;
         return RTypeUtil.isGetMethod(meth) || RTypeUtil.isSetMethod(meth);
      }
      else if (member instanceof IMethodDefinition) {
         IMethodDefinition methDef = (IMethodDefinition) member;
         return methDef.getPropertyName() != null;
      }
      return false;
   }

   public static boolean isPropertyGetSet(Object member) {
      if (member instanceof ParamTypedMember)
          return isPropertyGetSet(((ParamTypedMember) member).getMemberObject());
      if (member instanceof PropertyAssignment)
         return isPropertyGetSet(((PropertyAssignment) member).getAssignedProperty());
      if (member instanceof IBeanMapper) {
         IBeanMapper mapper = ((IBeanMapper) member);
         return mapper.hasSetterMethod() || mapper.hasAccessorMethod();
      }
      return isGetSetMethod(member);
   }

   public static boolean hasGetMethod(Object member) {
      if (member instanceof ParamTypedMember)
         member = ((ParamTypedMember) member).getMemberObject();
      if (member instanceof PropertyAssignment)
         member = ((PropertyAssignment) member).getAssignedProperty();
      if (member instanceof IBeanMapper && ((IBeanMapper) member).hasAccessorMethod())
         return true;
      if (member instanceof IMethodDefinition)
         return ((IMethodDefinition) member).hasGetMethod();
      // TODO: handle Method here - usually it will be an IBeanMapper
      return false;
   }

   public static boolean hasSetMethod(Object member) {
      if (member instanceof ParamTypedMember)
         member = ((ParamTypedMember) member).getMemberObject();
      if (member instanceof PropertyAssignment)
         member = ((PropertyAssignment) member).getAssignedProperty();
      if (member instanceof IBeanMapper && ((IBeanMapper) member).hasSetterMethod())
         return true;
      if (member instanceof IMethodDefinition)
         return ((IMethodDefinition) member).hasSetMethod();
      return false;
   }

   public static Object getGetMethodFromSet(Object member) {
      if (member instanceof ParamTypedMember)
         member = ((ParamTypedMember) member).getMemberObject();
      if (member instanceof PropertyAssignment)
         member = ((PropertyAssignment) member).getAssignedProperty();
      if (member instanceof IBeanMapper && ((IBeanMapper) member).hasAccessorMethod())
         return ((IBeanMapper) member).getGetSelector();
      if (member instanceof IMethodDefinition)
         return ((IMethodDefinition) member).getGetMethodFromSet();
      // TODO: handle Method here - usually it will be an IBeanMapper
      return null;
   }

   public static Object getSetMethodFromGet(Object member) {
      if (member instanceof ParamTypedMember)
         member = ((ParamTypedMember) member).getMemberObject();
      if (member instanceof PropertyAssignment)
         member = ((PropertyAssignment) member).getAssignedProperty();
      if (member instanceof IBeanMapper)
         return((IBeanMapper) member).getSetSelector();
      if (member instanceof IMethodDefinition)
         return ((IMethodDefinition) member).getSetMethodFromGet();
      // TODO: handle Method here - usually it will be an IBeanMapper
      return null;
   }

   public static Object getFieldFromGetSetMethod(Object member) {
      if (member instanceof ParamTypedMember)
         member = ((ParamTypedMember) member).getMemberObject();
      if (member instanceof PropertyAssignment)
         member = ((PropertyAssignment) member).getAssignedProperty();
      if (member instanceof IBeanMapper)
         return((IBeanMapper) member).getField();
      if (member instanceof IMethodDefinition)
         return ((IMethodDefinition) member).getFieldFromGetSetMethod();
      // TODO: handle Method here - usually it will be an IBeanMapper
      return null;
   }

   public static boolean isPropertyIs(Object propObj) {
      if (propObj instanceof ParamTypedMember)
         return isPropertyIs(((ParamTypedMember) propObj).getMemberObject());
      if (propObj instanceof PropertyAssignment)
         return isPropertyIs(((PropertyAssignment) propObj).getAssignedProperty());
      else if (ModelUtil.isObjectType(propObj))
         return false;
      if (propObj instanceof VariableDefinition)
         return false;
      if (ModelUtil.isSetMethod(propObj)) {
         Object getMethod = ModelUtil.getGetMethodFromSet(propObj);
         if (getMethod != null && propObj != getMethod)
            return isPropertyIs(getMethod);
         return false;
      }
      return propObj instanceof IBeanMapper ? ((IBeanMapper) propObj).isPropertyIs() : getMethodName(propObj).startsWith("is");
   }

   public static Object getPropertyType(Object prop) {
      return getPropertyType(prop, null);
   }

   /** For compiled properties, if you pass in a layered system, you will get the annotated version of the type, not the compiled type */
   public static Object getPropertyType(Object prop, LayeredSystem sys) {
      if (prop instanceof ParamTypedMember)
         prop = ((ParamTypedMember) prop).getMemberObject();
      if (prop instanceof IBeanMapper) {
         IBeanMapper mapper = (IBeanMapper) prop;
         Object propType = mapper.getPropertyType();
         if (sys != null)
            return resolveSrcTypeDeclaration(sys, propType);
         return propType;
      }
      else if (prop instanceof PropertyAssignment) {
         Object propType = ((PropertyAssignment) prop).getPropertyDefinition();
         if (propType == null)
            return Object.class; // Unresolved property - for lookupUIIcon just return something generic here
         return getPropertyType(propType, sys);
      }
      else if (ModelUtil.isField(prop))
         return getFieldType(prop);
      else if (ModelUtil.isMethod(prop)) {
         if (isGetMethod(prop))
            return getReturnType(prop, true);
         else if (isSetMethod(prop))
            return getSetMethodPropertyType(prop);
         else if (isGetIndexMethod(prop))
            return getReturnType(prop, true); // TODO: should be an array of return type?
         else if (isSetIndexMethod(prop))
            return getSetMethodPropertyType(prop); // TODO: should be an array
      }
      else if (prop instanceof TypeDeclaration)
         return prop;
      else if (prop instanceof EnumConstant)
         return ((EnumConstant) prop).getEnclosingType();
      else if (prop instanceof VariableDefinition) {
         return ((VariableDefinition) prop).getTypeDeclaration();
      }
      throw new UnsupportedOperationException();
   }

   public static Object getFieldType(Object field) {
      if (field instanceof ParamTypedMember)
         field = ((ParamTypedMember) field).getMemberObject();
      if (field instanceof Field)
         return ((Field) field).getType();
      else if (field instanceof FieldDefinition)
         return ((FieldDefinition) field).type.getTypeDeclaration();
      else if (field instanceof VariableDefinition) {
         VariableDefinition varDef = ((VariableDefinition) field);
         if (varDef.frozenTypeDecl != null) // To support the case VariableDefinitions are used for method metadata when there is no definition
            return varDef.frozenTypeDecl;
         return getFieldType(varDef.getDefinition());
      }
      else if (field instanceof IFieldDefinition)
         return ((IFieldDefinition) field).getFieldType();
      else
         throw new UnsupportedOperationException();
   }

   public static JavaType getFieldJavaType(LayeredSystem sys, Object field, Object definedInType) {
      if (field instanceof ParamTypedMember)
         field = ((ParamTypedMember) field).getMemberObject();
      if (field instanceof Field)
         return JavaType.createFromParamType(sys, ((Field) field).getType(), null, definedInType);
      else if (field instanceof FieldDefinition)
         return ((FieldDefinition) field).type;
      else if (field instanceof VariableDefinition)
         return getFieldJavaType(sys, ((VariableDefinition) field).getDefinition(), definedInType);
      else if (field instanceof IFieldDefinition)
         return ((IFieldDefinition) field).getJavaType();
      else if (field instanceof IBeanMapper)
         return getFieldJavaType(sys, ((IBeanMapper) field).getField(), definedInType);
      else
         throw new UnsupportedOperationException();
   }

   public static boolean isMethod(Object member) {
      if (member instanceof ParamTypedMember)
         member = ((ParamTypedMember) member).getMemberObject();
      return member instanceof Method || member instanceof IMethodDefinition;
   }

   public static int computeFrameSize(List<Statement> statements) {
      int frameSize = 0;
      if (statements != null) {
         int sz = statements.size();
         for (int i = 0; i < sz; i++) {
            Statement s = statements.get(i);
            if (s instanceof VariableStatement) {
               VariableStatement vs = (VariableStatement) s;
               if (vs.definitions != null)
                  frameSize += vs.definitions.size();
            }
         }
      }
      return frameSize;
   }

   public static ExecResult execStatements(ExecutionContext ctx, List<Statement> statements) {
      if (statements == null)
         return ExecResult.Next;

      int sz = statements.size();
      ExecResult res = ExecResult.Next;
      Statement currentStatement = null;
      for (int i = 0; i < sz; i++) {
         try {
            currentStatement = statements.get(i);
            res = currentStatement.execSys(ctx);
         }
         catch (RuntimeException exc) {
            throw (RuntimeException) wrapRuntimeException(currentStatement, exc);
         }
         catch (Error exc) {
            throw (Error) wrapRuntimeException(currentStatement, exc);
         }
         if (res != ExecResult.Next)
            return res;
      }
      return res;
   }

   public static Throwable wrapRuntimeException(Statement statement, Throwable exc) {
      Class cl = exc.getClass();
      Constructor constr = RTypeUtil.getConstructor(cl, String.class, Throwable.class);
      boolean addThrowable = true;
      boolean newExc = false;
      if (constr == null) {
         constr = RTypeUtil.getConstructor(cl, String.class);
         if (constr == null) {
            return exc;
         }
         addThrowable = false;
      }

      String message = exc.getMessage();
      boolean first = false;
      if (message == null) {
         message = "dyn stack:\n";
         first = true;
         newExc = true;
      }
      else if (!message.contains("dyn stack")) {
          message = message + ": dyn stack:\n";
         first = true;
         newExc = true;
      }

      if (first || statement instanceof AbstractMethodDefinition) {
         message += statement.toLocationString() + "\n";
         newExc = true;
      }

      if (!message.contains("Compiled stack")) {
         message += "Compiled stack:\n";
         message += PTypeUtil.getStackTrace(exc);
         newExc = true;
      }

      if (!newExc)
         return exc;

      if (addThrowable)
         return (Throwable) PTypeUtil.createInstance(cl, null, message, exc);
      else
         return (Throwable) PTypeUtil.createInstance(cl, null, message);
   }

   public static Object getMethod(Object currentObject, String s) {
      // TODO: support a sc-table in the dynamic object and interpret a MethodDefinition here
      // See ClassDeclaration.createInstance.  Here we need to implement overriding of dynamic methods.
      // The execution context probably needs to track the current type declaration so we can do the "super"
      // definition in IdentifierExpression.  
      return RTypeUtil.getMethod(currentObject.getClass(), s);
   }

   public static Object createInstance(Object boundType, String sig, List<Expression> arguments, ExecutionContext ctx) {
      if (boundType instanceof Class) {
         Object[] values = constructorArgListToValues(boundType, arguments, ctx, null);
         return PTypeUtil.createInstance((Class) boundType, sig, values);
      }
      else if (boundType instanceof TypeDeclaration) {
         TypeDeclaration decl = (TypeDeclaration) boundType;
         return decl.createInstance(ctx, sig, arguments);
      }
      else if (boundType instanceof CFClass) {
         CFClass cfClass = (CFClass) boundType;
         Class rtClass = cfClass.getCompiledClass();
         if (rtClass != null)
            return createInstance(rtClass, sig, arguments, ctx);
         else
            throw new IllegalArgumentException("Class: " + cfClass.getFullTypeName() + " is not in the system classpath and cannot be instantiated by the interpreter");
      }
      throw new UnsupportedOperationException();
   }

   public static void execTemplateDeclarations(StringBuilder sb, ExecutionContext ctx, List<Object> declarations) {
      if (declarations == null)
         return;

      if (sb == null)
         throw new IllegalArgumentException("template output method called with null value for the 'out' StringBuilder parameter");
      
      for (int i = 0; i < declarations.size(); i++) {
         Object def = declarations.get(i);
         if (def instanceof IString)
            sb.append(def.toString());
         else if (def instanceof IValueNode) {
            do {
               // A glue expression can evaluate into a Template.  Need to use eval again instead of explicitly
               Object val = ((IValueNode )def).eval(null, ctx);
               if (val != null) {
                  if (val instanceof IValueNode)
                     def = val;
                  else {
                     sb.append(val.toString());
                     break;
                  }
               }
               else
                  break;
            } while (true);
         }
         else if (def instanceof TemplateStatement) {
            ModelUtil.execStatements(ctx, ((TemplateStatement) def).statements);
         }
         else if (def instanceof Element) {
            // In dynamic model, a glue expression may not have been transformed so it will still have references to Elements.  We'll get the output expression here which creates the tag type
            // if needed.  TODO: maybe we should split GlueExpression.transformTemplate into two steps - one to produce the expression and the other to replace and then we would skip the eval here?
            Element elem = (Element) def;
            Expression outExpr = elem.getOutputExpression();
            Object exprRes = outExpr.eval(String.class, ctx);
            if (exprRes != null)
               sb.append(exprRes.toString());

            /*
            sb.append("<");
            sb.append(elem.tagName);
            if (elem.attributeList != null) {
               for (int ai = 0; ai < elem.attributeList.size(); ai++) {
                  Attr att = (Attr) elem.attributeList.get(ai);
                  sb.append(" ");
                  sb.append(att.name);
                  if (att.value != null) {

                  }

               }
            }
            if (elem.selfClose != null)
               sb.append("/");
            sb.append(">");
            if (elem.children != null) {

            }
            */
         }
      }
   }

   public static AccessLevel getAccessLevel(Object def, boolean explicitOnly) {
      return getAccessLevel(def, explicitOnly, null);
   }

   public static String getAccessLevelString(Object def, boolean explicitOnly, JavaSemanticNode.MemberType type) {
      AccessLevel level = getAccessLevel(def, explicitOnly, type);
      if (level == null)
         return "package-private";
      else
         return level.toString().toLowerCase();
   }

   public static AccessLevel getAccessLevel(Object def, boolean explicitOnly, JavaSemanticNode.MemberType mtype) {
      if (def instanceof IDefinition)
         return ((IDefinition) def).getAccessLevel(explicitOnly);
      else if (def instanceof IBeanMapper) {
         IBeanMapper mapper = (IBeanMapper) def;
         if (mtype == null)
            def = mapper.getPropertyMember();
         else {
            if (mtype == JavaSemanticNode.MemberType.GetMethod) {
               def = mapper.getGetSelector();
               if (def == null) {
                  if (mapper instanceof IBeanIndexMapper)
                     def = ((IBeanIndexMapper) mapper).getIndexedGetSelector();
               }
               if (def == null)
                  def = mapper.getField();
               if (def == null)
                  def = mapper.getSetSelector();
            }
            else if (mtype == JavaSemanticNode.MemberType.SetMethod || mtype == JavaSemanticNode.MemberType.Assignment) {
               def = mapper.getSetSelector();
               if (mapper instanceof IBeanIndexMapper)
                  def = ((IBeanIndexMapper) mapper).getIndexedSetSelector();
               if (def == null)
                  def = mapper.getField();
            }
            else if (mtype == JavaSemanticNode.MemberType.Field || mtype == JavaSemanticNode.MemberType.Enum) {
               def = mapper.getField();
               if (def == null)
                  def = mapper.getGetSelector();
            }
         }
      }
      else if (def instanceof VariableDefinition)
         return getAccessLevel(((VariableDefinition) def).getDefinition(), explicitOnly);

      if (def == null)
         return null;

      int modifiers = getModifiers(def);
      if (Modifier.isPublic(modifiers))
         return AccessLevel.Public;
      else if (Modifier.isProtected(modifiers))
         return AccessLevel.Protected;
      else if (Modifier.isPrivate(modifiers))
         return AccessLevel.Private;
      return null;
   }

   public static boolean hasModifier(Object def, String s) {
      if (def instanceof IDefinition)
         return ((IDefinition) def).hasModifier(s);
      else if (def instanceof IBeanMapper)
         def = ((IBeanMapper) def).getPropertyMember();
      else {
         Object origDef = def;
         while (ModelUtil.hasTypeParameters(def))
            def = ModelUtil.getParamTypeBaseType(def);
      }

      if (def instanceof VariableDefinition)
         return hasModifier(((VariableDefinition) def).getDefinition(), s);
      if (def == null)
         return false;

      return PTypeUtil.hasModifier(def, s);
   }

   public static SemanticNodeList<AnnotationValue> getAnnotationComplexValues(Object annotObj) {
      if (annotObj instanceof IAnnotation)
         return ((IAnnotation) annotObj).getElementValueList();
      else {
         java.lang.annotation.Annotation lannot = (java.lang.annotation.Annotation) annotObj;

         //Class cl = lannot.getClass();
         Class annotCl = lannot.annotationType();
         //Method[] annotValues = cl.getDeclaredMethods();
         Method[] annotValues = annotCl.getDeclaredMethods();

         SemanticNodeList<AnnotationValue> values = new SemanticNodeList<AnnotationValue>(annotValues.length);
         for (int i = 0; i < annotValues.length; i++) {
            Method annotMeth = annotValues[i];
            if (ModelUtil.hasModifier(annotMeth, "static"))
               continue;
            String name = annotValues[i].getName();
            // TODO: find some better way to eliminate the Object class methods from the annotation class.  Presumably it does not change often but should put this in a static table at least or better yet, find some way to get it from the runtime.
            //if (name.equals("equals") || name.equals("toString") || name.equals("hashCode") || name.equals("annotationType") || name.equals("isProxyClass") || name.equals("wait") || name.equals("getClass") || name.equals("notify") || name.equals("notifyAll"))
            //   continue;
            AnnotationValue av = new AnnotationValue();
            av.identifier = name;
            av.elementValue = PTypeUtil.invokeMethod(lannot, annotValues[i], (Object[])null);
            Object defaultValue = annotMeth.getDefaultValue();
            if (DynUtil.equalObjects(av.elementValue, defaultValue))
               continue;
            values.add(av);
         }
         return values;
      }
   }

   public static Object getAnnotationSingleValue(Object annotObj) {
      if (annotObj instanceof IAnnotation)
         return ((IAnnotation) annotObj).getElementSingleValue();
      else {
         java.lang.annotation.Annotation lannot = (java.lang.annotation.Annotation) annotObj;

         Class cl = lannot.getClass();
         Method[] annotValues = cl.getMethods();
         if (annotValues.length != 1) {
            return PTypeUtil.invokeMethod(lannot, TypeUtil.resolveMethod(cl, "value", null), (Object[])null);
         }

         return PTypeUtil.invokeMethod(lannot, annotValues[0], (Object[])null);
      }
   }

   public static BindingDirection initBindingDirection(String operator) {
      if (operator == null) return null;
      int cix = operator.indexOf(":");
      if (cix != -1) {
         if (cix == 0) {
            if (operator.length() == 3)
               return BindingDirection.BIDIRECTIONAL;
            else
               return BindingDirection.FORWARD;
         }
         else
            return BindingDirection.REVERSE;
      }
      return null;
   }

   public static Object getEnclosingInstType(Object compClass) {
      Object type = getEnclosingType(compClass);
      // Inner enums are implicity static
      if (isEnumType(compClass))
         return null;
      return type == null ? null : ModelUtil.hasModifier(compClass, "static") ? null : type;
   }

   public static Object getEnclosingType(Object srcMember) {
      if (srcMember instanceof IDefinition) {
         return ((IDefinition) srcMember).getEnclosingIType();
      }
      else if (srcMember instanceof Member) {
         return ((Member) srcMember).getDeclaringClass();
      }
      else if (srcMember instanceof BeanMapper) {
         return ((BeanMapper) srcMember).getPropertyMember().getDeclaringClass();
      }
      else if (srcMember instanceof VariableDefinition) {
         return ((VariableDefinition) srcMember).getEnclosingType();
      }
      else if (srcMember instanceof Class) {
         return ((Class) srcMember).getDeclaringClass();
      }
      else if (srcMember instanceof Template)
         return null;
      else if (srcMember instanceof Parameter)
         return ((Parameter) srcMember).getEnclosingType();
      else if (srcMember instanceof java.lang.Enum)
         return ((java.lang.Enum) srcMember).getDeclaringClass();
      else if (srcMember instanceof ArrayTypeDeclaration) {
         return getEnclosingType(((ArrayTypeDeclaration) srcMember).getComponentType());
      }
      else if (srcMember instanceof ITypeDeclaration)
         return null; // CmdScriptModel or Template?
      throw new UnsupportedOperationException();
   }

   // This version is here to mirror the client api which needs the layered system to map the type name to the type
   public static Object getEnclosingType(Object prop, LayeredSystem sys) {
      return getEnclosingType(prop);
   }

   public static Object getOuterObject(Object toConstructType, ExecutionContext ctx) {
      Object toConstructCompiled = ModelUtil.getCompiledClass(toConstructType);
      if (toConstructCompiled == null) {
         toConstructCompiled = toConstructType; // A dynamic type
      }
      Object enclType = ModelUtil.getEnclosingInstType(toConstructCompiled);
      if (enclType != null) {
         return ctx.findThisType(enclType);
      }
      return null;
   }

   /** Returns true for classes which have a constructor that takes a BodyTypeDeclaration */
   public static boolean needsTypeDeclarationParam(Class compClass) {
      Constructor[] ctors = RTypeUtil.getConstructors(compClass);
      if (ctors == null)
         return false;
      for (Constructor ctor:ctors) {
         Class[] subParamTypes = ctor.getParameterTypes();
         if (subParamTypes != null && subParamTypes.length > 0) {
            if (BodyTypeDeclaration.class.isAssignableFrom(subParamTypes[0]))
               return true;
         }
      }
      return false;
   }

   public static Object[] constructorArgListToValues(Object toConstructType, List<Expression> arguments, ExecutionContext ctx, Object outerObj) {
      Object enclType = ModelUtil.getEnclosingInstType(toConstructType);
      int size = arguments == null ? 0 : arguments.size();
      if (enclType != null)
         size++;
      Object[] argValues = new Object[size];

      int start = 0;
      if (enclType != null) {
         argValues[0] = outerObj == null ? ctx.findThisType(enclType) : outerObj;
         if (argValues[0] == null) {
            Object tmpRes = ctx.findThisType(enclType);
            throw new IllegalArgumentException("No enclosing type: " + ModelUtil.getTypeName(enclType) + " to construct inner type: " + toConstructType);
         }
         start = 1;
      }
      if (arguments != null) {
         for (int i = start; i < size; i++) {
            Expression arg = arguments.get(i-start);
            argValues[i] = arg.eval(arg.getRuntimeClass(), ctx);
         }
      }
      return argValues;
   }

   public static Object[] expressionListToValues(List<Expression> arguments, ExecutionContext ctx) {
      int size = arguments.size();
      Object[] argValues = new Object[size];
      for (int i = 0; i < size; i++) {
         Expression arg = arguments.get(i);
         argValues[i] = arg.eval(arg.getRuntimeClass(), ctx);
      }
      return argValues;
   }

   // Warning: this version will not check for a dynamic method - use invokeMethod below instead unless you know the MethodDefinition is dynamic
   public static Object callMethod(Object thisObj, Object method, Object...argValues) {
      if (method instanceof Method) {
         Method jMethod = (Method) method;
         return PTypeUtil.invokeMethod(thisObj, jMethod, argValues);
      }
      else if (method instanceof AbstractMethodDefinition) {
         AbstractMethodDefinition methDef = (AbstractMethodDefinition) method;
         if (methDef.isDynMethod())
            return ((AbstractMethodDefinition) method).callVirtual(thisObj, argValues);
         Object invMeth = methDef.getRuntimeMethod();
         return ModelUtil.invokeMethod(thisObj, invMeth, argValues, new ExecutionContext(methDef.getLayeredSystem()));
      }
      else if (method instanceof CFMethod) {
         Method rtMeth = ((CFMethod) method).getRuntimeMethod();

         if (rtMeth == null)
            throw new IllegalArgumentException("Can't invoke method: " + method + " No runtime class found");

         return ModelUtil.callMethod(thisObj, rtMeth, argValues);
      }
      else if (method instanceof ParamTypedMethod) {
         return callMethod(thisObj, ((ParamTypedMethod) method).method, argValues);
      }
      else if (method instanceof String) {
         Object methodObj = ModelUtil.getMethod(thisObj, (String) method);
         if (methodObj == null) {
            throw new IllegalArgumentException("No method: " + method + " on: " + thisObj);
         }
         return callMethod(thisObj, methodObj, argValues);

      }
      else
         throw new UnsupportedOperationException();
   }

   /** A simple direct invoke of either an interpreted or compiled method once values are bound to parameters */
   public static Object invokeMethod(Object thisObject, Object method, Object[] argValues, ExecutionContext ctx) {
      // Compiled
      if (method instanceof Method) {
         Method jMethod = (Method) method;
         return PTypeUtil.invokeMethod(thisObject, jMethod, argValues);
      }
      // Interpreted
      else if (method instanceof AbstractMethodDefinition) {
         AbstractMethodDefinition rtmeth = (AbstractMethodDefinition) method;

         if (rtmeth.isDynMethod())
            return rtmeth.invoke(ctx, argValues == null ? Collections.EMPTY_LIST : Arrays.asList(argValues));
         else {
            Object invMeth = rtmeth.getRuntimeMethod();
            return ModelUtil.invokeMethod(thisObject, invMeth, argValues, ctx);
         }
      }
      else if (method instanceof CFMethod) {
         Method rtMeth = ((CFMethod) method).getRuntimeMethod();

         if (rtMeth == null)
            throw new IllegalArgumentException("Can't invoke method: " + method + " No runtime class found");

         return ModelUtil.invokeMethod(thisObject, rtMeth, argValues, ctx);
      }
      else if (method instanceof ParamTypedMethod) {
         return invokeMethod(thisObject, ((ParamTypedMethod) method).method, argValues, ctx);
      }
      else
         throw new UnsupportedOperationException();
   }

   public static Object invokeRemoteMethod(LayeredSystem locSys, Object methThis, Object methToInvoke, SemanticNodeList<Expression> arguments, Class expectedType, ExecutionContext ctx,
                                           boolean repeatArgs, ParamTypedMethod pmeth, ScopeContext targetCtx) {
      Object[] argValues = expressionListToValues(arguments, ctx);

      if (repeatArgs && argValues.length > 0) {
         argValues = convertArgsForRepeating(methToInvoke, pmeth, arguments, argValues);
      }
      SyncDestination dest = SyncDestination.defaultDestination;
      return DynUtil.invokeRemoteSync(null, targetCtx, dest.name, dest.defaultTimeout, methThis, methToInvoke, argValues);
   }

   private static Object[] convertArgsForRepeating(Object meth, ParamTypedMethod pmeth, List<Expression> arguments, Object[] argValues) {
      // If we have a param-typed method use that to get the parameter types as those reflect the compile-time binding of the types
      // Possibly repeating parameter
      if (ModelUtil.isVarArgs(meth)) {
         Object[] types = pmeth == null ? ModelUtil.getParameterTypes(meth) : pmeth.getParameterTypes(true);
         int last = types.length - 1;
         Object lastType = types[last];
         if (ModelUtil.isArray(lastType)) {
            Object[] newArgValues = new Object[types.length];
            for (int i = 0; i < last; i++) {
               newArgValues[i] = argValues[i];
            }

            // Use the type of the expression, not the type of the value to emulate compile time binding
            if (!ModelUtil.isAssignableFrom(lastType, arguments.get(arguments.size() - 1).getTypeDeclaration())) {
               Object[] array = (Object[]) Array.newInstance(ModelUtil.getCompiledClass(ModelUtil.getArrayComponentType(lastType)), arguments.size() - last);
               for (int i = last; i < argValues.length; i++)
                  array[i - last] = argValues[i];
               newArgValues[last] = array;
               argValues = newArgValues;
            } else if (last >= arguments.size() && newArgValues[last] == null) {
               newArgValues[last] = Array.newInstance(ModelUtil.getCompiledClass(ModelUtil.getArrayComponentType(lastType)), 0);
               argValues = newArgValues;
            }
         }
      }
      return argValues;
   }

   private static Object invokeMethodWithValues(Object thisObject, Object method, List<Expression> arguments,
                                     Class expectedType, ExecutionContext ctx, boolean repeatArgs, boolean findMethodOnThis, ParamTypedMethod pmeth, Object[] argValues) {
      // Compiled
      if (method instanceof Method) {
         Method jMethod = (Method) method;
         if (repeatArgs && argValues.length > 0) {
            argValues = convertArgsForRepeating(jMethod, pmeth, arguments, argValues);
         }
         return PTypeUtil.invokeMethod(thisObject, jMethod, argValues);
      }
      // Interpreted
      else if (method instanceof AbstractMethodDefinition) {
         AbstractMethodDefinition rtmeth = (AbstractMethodDefinition) method;

         if (!rtmeth.isDynMethod()) {
            Object realRTMethod = rtmeth.getRuntimeMethod();
            if (realRTMethod != rtmeth && realRTMethod != null) {
               return invokeMethodWithValues(thisObject, realRTMethod, arguments, expectedType, ctx, repeatArgs, findMethodOnThis, pmeth, argValues);
            }
         }


         boolean isStatic = false;
         try {
            if (thisObject != null) {
               if (DynUtil.isType(thisObject)) {
                  ctx.pushStaticFrame(thisObject);
                  isStatic = true;
               }
               else
                  ctx.pushCurrentObject(thisObject);

               if (!isStatic && findMethodOnThis) {
                  Object concreteType = getObjectsType(thisObject);
                  Object overMeth = ModelUtil.definesMethod(concreteType, rtmeth.name, rtmeth.getParameterList(), null, null, false, false, null, null);

                  if (overMeth != rtmeth && overMeth != null) {
                     return invokeMethodWithValues(thisObject, overMeth, arguments, expectedType, ctx, repeatArgs, false, pmeth, argValues);
                  }
               }
            }
            if (repeatArgs && argValues.length > 0) {
               argValues = convertArgsForRepeating(method, pmeth, arguments, argValues);
            }
            return rtmeth.invoke(ctx, Arrays.asList(argValues));
         }
         finally {
            if (thisObject != null) {
               if (isStatic)
                  ctx.popStaticFrame();
               else
                  ctx.popCurrentObject();
            }
         }
      }
      else if (method instanceof CFMethod) {
         Method rtMeth = ((CFMethod) method).getRuntimeMethod();

         if (rtMeth == null)
            throw new IllegalArgumentException("Can't invoke method: " + method + " No runtime class found");

         return ModelUtil.invokeMethod(thisObject, rtMeth, arguments, expectedType, ctx, repeatArgs, findMethodOnThis, pmeth);
      }
      else if (method instanceof ParamTypedMethod) {
         pmeth = ((ParamTypedMethod) method);
         return invokeMethod(thisObject, pmeth.method, arguments, expectedType, ctx, repeatArgs, findMethodOnThis, pmeth);
      }
      else
         throw new UnsupportedOperationException();
   }

   /** Invokes a method given a list of arguments, the Method object and args to eval.  Handles repeated arguments */
   public static Object invokeMethod(Object thisObject, Object method, List<Expression> arguments,
                                     Class expectedType, ExecutionContext ctx, boolean repeatArgs, boolean findMethodOnThis, ParamTypedMethod pmeth) {
      Object[] argValues = expressionListToValues(arguments, ctx);

      return invokeMethodWithValues(thisObject, method, arguments, expectedType, ctx, repeatArgs, findMethodOnThis, pmeth, argValues);
   }

   public static boolean isComponentType(Object type) {
      if (type instanceof Class) {
         Class cl = (Class) type;
         return IComponent.class.isAssignableFrom(cl) || IAltComponent.class.isAssignableFrom(cl) || IDynComponent.class.isAssignableFrom(cl);
      }
      else if (type instanceof ITypeDeclaration) {
         ITypeDeclaration td = (ITypeDeclaration) type;
         return td.isComponentType();
      }
      return false;
   }

   /** Fields in the original model might get converted to get/set */
   public static boolean needsGetSet(Object boundType) {
      // The identifier can resolve to a "field" but the param typed member is a get method
      if (boundType instanceof ParamTypedMember) {
         boundType = ((ParamTypedMember) boundType).getMemberObject();
         return boundType instanceof MethodDefinition &&
                 ((MethodDefinition) boundType).hasGetMethod();
      }
      if (boundType instanceof PropertyAssignment)
         boundType = ((PropertyAssignment) boundType).getAssignedProperty();
      VariableDefinition varDef;
      return (boundType instanceof VariableDefinition) && ((varDef = (VariableDefinition) boundType).needsGetSet() || varDef.isDynamicType());
   }

   public static boolean needsSet(Object boundType) {
      // The identifier can resolve to a "field" but the param typed member is a get method
      if (boundType instanceof ParamTypedMember) {
         boundType = ((ParamTypedMember) boundType).getMemberObject();
         if (boundType instanceof MethodDefinition &&
                 ((MethodDefinition) boundType).hasSetMethod())
            return true;
      }
      if (boundType instanceof PropertyAssignment)
         boundType = ((PropertyAssignment) boundType).getAssignedProperty();
      VariableDefinition varDef;
      return (boundType instanceof VariableDefinition) && ((varDef = (VariableDefinition) boundType).needsGetSet() || varDef.isDynamicType());
   }

   /** Returns true if this property is marked as bindable as a type-level annotation.  Use isBindable(Object propObj) to check for annotations on the property object itself. */
   public static boolean isBindable(LayeredSystem system, Object parentType, String propName) {
      // Search the class hierarchy for all TypeSettings annotations and if any of them declare this property bindable at the type level
      // we treat it as bindable.
      ArrayList<Object> typeSetAnnots = ModelUtil.getAllInheritedAnnotations(system, parentType, "sc.obj.TypeSettings", false, null, false);
      if (typeSetAnnots != null) {
         for (Object typeSetAnnot:typeSetAnnots) {
            String[] bindablePropNames = (String[]) ModelUtil.getAnnotationValue(typeSetAnnot, "bindableProps");
            if (bindablePropNames != null) {
               for (String bp:bindablePropNames)
                  if (bp.equals(propName))
                     return true;
            }

         }
      }
      return false;
   }

   public static boolean isBindable(Object assignedProperty) {
      if (assignedProperty instanceof VariableDefinition) {
         return ((VariableDefinition) assignedProperty).needsBindable();
      }
      if (assignedProperty == null)
         return false;
      Object enclType = ModelUtil.getEnclosingType(assignedProperty);
      if (enclType instanceof BodyTypeDeclaration) {
         if (((BodyTypeDeclaration) enclType).isAutomaticBindable(ModelUtil.getPropertyName(assignedProperty)))
            return true;
      }
      return getAnnotation(assignedProperty, "sc.bind.Bindable") != null;
   }

   public static boolean isManualBindable(Object assignedProperty) {
      Object bindAnnot = getAnnotation(assignedProperty, "sc.bind.Bindable");
      return bindAnnot != null && !ModelUtil.isAutomaticBindingAnnotation(bindAnnot);
   }

   public static boolean isAutomaticBindable(Object assignedProperty) {
      if (assignedProperty instanceof VariableDefinition) {
         VariableDefinition vdef = (VariableDefinition) assignedProperty;
         return vdef.needsBindable() && vdef.needsGetSet();
      }
      if (assignedProperty instanceof Field) {
         return false;
      }
      if (assignedProperty == null)
         return false;
      Object enclType = ModelUtil.getEnclosingType(assignedProperty);
      if (enclType instanceof BodyTypeDeclaration) {
         if (((BodyTypeDeclaration) enclType).isAutomaticBindable(ModelUtil.getPropertyName(assignedProperty)))
            return true;
      }
      Object bindAnnot = getAnnotation(assignedProperty, "sc.bind.Bindable");
      return bindAnnot != null && ModelUtil.isAutomaticBindingAnnotation(bindAnnot);
   }

   public static boolean isArrayLength(Object assignedProperty) {
      if (assignedProperty instanceof ArrayLengthBeanMapper)
         return true;

      if (assignedProperty == ArrayTypeDeclaration.LENGTH_FIELD)
         return true;

      return false;
   }

   public static boolean isConstant(Object assignedProperty) {
      if (assignedProperty instanceof BeanMapper && ((BeanMapper) assignedProperty).isConstant())
         return true;

      boolean isField;
      if ((isField = ModelUtil.isField(assignedProperty)) && ModelUtil.hasModifier(assignedProperty, "final"))
         return true;

      if (ModelUtil.isObjectType(assignedProperty))
         return true;

      Object annotation = getAnnotation(assignedProperty, "sc.obj.Constant");
      if (annotation == null && !isField && !(assignedProperty instanceof PropertyAssignment)) {
         if (!(assignedProperty instanceof IBeanMapper)) {
            if (ModelUtil.isGetMethod(assignedProperty)) {
               Object setMethod = ModelUtil.getSetMethodFromGet(assignedProperty);
               if (setMethod != null)
                  annotation = ModelUtil.getAnnotation(setMethod, "sc.obj.Constant");
               if (annotation == null) {
                  Object field = ModelUtil.getFieldFromGetSetMethod(assignedProperty);
                  if (field != null)
                     annotation = ModelUtil.getAnnotation(field, "sc.obj.Constant");
               }
            }
            else if (ModelUtil.isSetMethod(assignedProperty)) {
               Object getMethod = ModelUtil.getGetMethodFromSet(assignedProperty);
               if (getMethod != null)
                  annotation = ModelUtil.getAnnotation(getMethod, "sc.obj.Constant");
               if (annotation == null) {
                  Object field = ModelUtil.getFieldFromGetSetMethod(assignedProperty);
                  if (field != null)
                     annotation = ModelUtil.getAnnotation(field, "sc.obj.Constant");
               }
            }
         }
      }
      if (annotation == null) {
         // It's possible we should treat getX without setX as constant by default?   I suspect the values of those
         // will change but we would not be able to detect that by default anyway.
         return false;
      }

      Boolean b = (Boolean) getAnnotationValue(annotation, "value");
      if (b == null)
         return true;
      return b;
   }

   public static Class getCompiledClass(Object fieldType) {
      TypeDeclaration typeDecl;
      // Skips any types defined in annotation layers
      while (fieldType instanceof TypeDeclaration && (typeDecl = (TypeDeclaration) fieldType).getJavaModel().isAnnotationModel())
         fieldType = typeDecl.getDerivedTypeDeclaration();

      if (fieldType instanceof ITypeDeclaration)
         return ((ITypeDeclaration) fieldType).getCompiledClass();
      else if (fieldType instanceof Class)
         return (Class) fieldType;
      else if (fieldType instanceof TypeParameter)
         return getCompiledClass(((TypeParameter) fieldType).getTypeDeclaration());
      else if (hasTypeParameters(fieldType))
         return getCompiledClass(getParamTypeBaseType(fieldType));
      else if (fieldType == null)
         return null;
      else if (fieldType instanceof java.lang.reflect.Type) {
         return typeToClass((java.lang.reflect.Type) fieldType);
      }
      throw new UnsupportedOperationException();
   }

   public static Object getInnerType(Object type, String innerTypeName, TypeContext ctx) {
      int ix;
      do {
         Object nextType = null;
         ix = innerTypeName.indexOf(".");
         String nextTypeName;
         if (ix != -1) {
            nextTypeName = innerTypeName.substring(0, ix);
            innerTypeName = innerTypeName.substring(ix+1);
         }
         else {
            nextTypeName = innerTypeName;
         }
         if (type instanceof ITypeDeclaration) {
            nextType = ((ITypeDeclaration) type).getInnerType(nextTypeName, ctx);
         }
         else if (type instanceof Class) {
            nextType = RTypeUtil.getInnerClass((Class) type, nextTypeName);
         }
         else if (type instanceof TypeParameter) {
            nextType = getInnerType(((TypeParameter) type).getTypeDeclaration(), nextTypeName, ctx);
         }
         else if (type != null)
            throw new UnsupportedOperationException();
         if (nextType == null) 
            return null;
         type = nextType;
      } while (ix != -1);
      return type;
   }

   public static Object[] getAllInnerTypes(Object type, String modifier, boolean thisClassOnly, boolean includeInherited) {
      if (type instanceof Class)
         return RTypeUtil.getAllInnerClasses((Class) type, modifier); // TODO: implement thisClassOnly - currently not needed for compiled types
      else if (type instanceof ITypeDeclaration) {
         List<Object> types = ((ITypeDeclaration) type).getAllInnerTypes(modifier, thisClassOnly, includeInherited);
         return types == null ? null : types.toArray(new Object[types.size()]);
      }
      else
         return null;
   }

   public static String getCompiledClassName(Object type) {
      if (type instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) type).getCompiledClassName();
      }
      return getTypeName(type);
   }

   public static String getCompiledTypeName(Object type) {
      String dims = null;
      if (type instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) type).getCompiledTypeName();
      }
      return getTypeName(type, true, true);
   }

   public static String getRuntimeTypeName(Object type) {
      if (type instanceof TypeDeclaration) {
         TypeDeclaration typeDecl = (TypeDeclaration) type;
         return typeDecl.getRuntimeTypeName();
      }
      return getTypeName(type);
   }

   public static String modifiersToString(Object definition, boolean includeAnnotations, boolean includeAccess, boolean includeFinal, boolean includeScopes, boolean abs, JavaSemanticNode.MemberType type) {
      if (definition instanceof VariableDefinition)
         definition = ((VariableDefinition) definition).getDefinition();
      if (definition instanceof IDefinition) {
         return ((IDefinition) definition).modifiersToString(includeAnnotations, includeAccess, includeFinal, includeScopes, abs, type);
      }
      else {
         if (definition instanceof IBeanMapper)
            definition = ((IBeanMapper) definition).getPropertyMember();

         String str = Modifier.toString(getModifiers(definition));
         if (includeAnnotations) {
            // TODO: need to add this code
         }
         return str;
      }
   }

   public static String isGetMethodImpl(Object meth) {
      String name = ModelUtil.getMethodName(meth);
      if (name.startsWith("get") || name.startsWith("is")) {
         Object returnType = ModelUtil.getReturnJavaType(meth);
         if (returnType != null && ModelUtil.typeIsVoid(returnType))
            return null;
         Object[] parameterTypes = ModelUtil.getParameterJavaTypes(meth, false);
         if (parameterTypes == null || parameterTypes.length == 0 ||
                 (parameterTypes.length == 1 && name.startsWith("get") && typeIsBoolean(parameterTypes[0])))
            return CTypeUtil.decapitalizePropertyName(name.charAt(0) == 'g' ? name.substring(3) : name.substring(2));
      }
      return null;
   }

   /**
    * These next three functions take info from a method and turn it into a property name if it is a property of
    * this type.  Note that we can't use getTypeDeclaration here since we need this info before we start the
    * component so all determinations must be done using the type name.
    */
   public static String isGetMethod(String name, Object[] parameterTypes, Object returnType) {
      String typeName;
      if (name.startsWith("get") || name.startsWith("is")) {
         if (returnType != null && ModelUtil.typeIsVoid(returnType))
            return null;
         if (parameterTypes == null || parameterTypes.length == 0 ||
             (parameterTypes.length == 1 && name.startsWith("get") && typeIsBoolean(parameterTypes[0])))
           return CTypeUtil.decapitalizePropertyName(name.charAt(0) == 'g' ? name.substring(3) : name.substring(2));
      }
      return null;
   }

   public static String convertGetMethodName(String name) {
      String rest = null;
      if (name.startsWith("get")) {
         rest = name.substring(3);
      }
      else if (name.startsWith("is")) {
         rest = name.substring(2);
      }
      if (rest != null && rest.length() > 0) {
         return CTypeUtil.decapitalizePropertyName(rest);
      }
      return null;
   }

   public static String isSetMethodImpl(Object meth) {
      String name = ModelUtil.getMethodName(meth);
      if (name.startsWith("set") && name.length() >= 4) {
         Object returnType = ModelUtil.getReturnJavaType(meth);
         if (returnType != null && !ModelUtil.typeIsVoid(returnType))
            return null;
         Object[] parameterTypes = ModelUtil.getParameterJavaTypes(meth, false);
         if (parameterTypes != null && (parameterTypes.length == 1 || (parameterTypes.length == 2 && isInteger(parameterTypes[0])))) {
            return CTypeUtil.decapitalizePropertyName(name.substring(3));
         }
      }
      return null;
   }

   /**
    * For set methods, we allow both void setX(int) and "? setX(int)".  Some folks may need int setX(int) and others
    * do this setX(y) to chain methods.
    * Note: including indexed setters here - i.e. setX(ix, val).  Not sure that's right and corresponds to logic calling this in MethodDefinition.
    */
   public static String isSetMethod(String name, Object[] paramJavaTypes, Object returnType) {
      if (name.startsWith("set") && name.length() >= 4 && paramJavaTypes != null && (paramJavaTypes.length == 1 ||
          (paramJavaTypes.length == 2 && isInteger(paramJavaTypes[0])))
              /*
              && (returnType == null || ModelUtil.typeIsVoid(returnType) ||
               ModelUtil.getTypeName(returnType).equals(ModelUtil.getTypeName(paramJavaTypes[0])))
               */
              ) {
         return CTypeUtil.decapitalizePropertyName(name.substring(3));
      }
      return null;
   }

   public static boolean isSetMethod(Object method) {
      if (method instanceof ParamTypedMember)
         return isSetMethod(((ParamTypedMember) method).getMemberObject());
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).isSetMethod();
      else if (method instanceof Method)
         return RTypeUtil.isSetMethod((Method) method);
      else if (method instanceof VariableStatement)
         return false;
      if (method instanceof IBeanMapper || method instanceof VariableDefinition || method instanceof FieldDefinition || method instanceof Field) // Returning false here... isConstant already checks both get and set for mappers so it's not a get method for that purpose.
         return false;
      else if (method instanceof ITypeDeclaration)
         return false;
      else if (method instanceof PropertyAssignment)
         return false;
      else if (method instanceof IFieldDefinition)
         return false;
      else
         throw new UnsupportedOperationException();
   }

   public static boolean isGetMethod(Object method) {
      if (method instanceof ParamTypedMember)
         return isGetMethod(((ParamTypedMember) method).getMemberObject());
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).isGetMethod();
      if (method instanceof IBeanMapper || method instanceof VariableDefinition || method instanceof FieldDefinition || method instanceof Field) // Returning false here... isConstant already checks both get and set for mappers so it's not a get method for that purpose.
         return false;
      else if (method instanceof Method)
         return RTypeUtil.isGetMethod((Method) method);
      else if (method instanceof ITypeDeclaration)
         return false;
      else if (method instanceof PropertyAssignment) {
         Object prop = ((PropertyAssignment) method).getAssignedProperty();
         if (prop != null)
            return isGetMethod(prop);
         return false;
      }
      else if (method instanceof IFieldDefinition)
         return false;
      else if (method instanceof VariableStatement)
         return false;
      else
         throw new UnsupportedOperationException();
   }

   public static boolean isGetIndexMethod(Object method) {
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).isGetIndexMethod();
      else if (method instanceof Method)
         return RTypeUtil.isGetIndexMethod((Method) method);
      else
         throw new UnsupportedOperationException();
   }

   public static boolean isSetIndexMethod(Object method) {
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).isSetIndexMethod();
      else if (method instanceof Method)
         return RTypeUtil.isSetIndexMethod((Method) method);
      else if (method instanceof VariableDefinition) {
         return ((VariableDefinition) method).needsIndexedSetter();
      }
      else if (ModelUtil.isField(method))
         return false;
      else
         throw new UnsupportedOperationException();
   }

   public static boolean hasSetIndexMethod(Object method) {
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).hasSetIndexMethod();
      else if (method instanceof Method)
         return RTypeUtil.isSetIndexMethod((Method) method); // TODO: do we need a hasSetIndexMethod here?
      else if (method instanceof VariableDefinition) {
         return ((VariableDefinition) method).needsIndexedSetter();
      }
      else if (ModelUtil.isField(method))
         return false;
      else
         throw new UnsupportedOperationException();
   }

   public static String isSetIndexMethodImpl(Object meth) {
      String name = ModelUtil.getMethodName(meth);
      if (name.startsWith("set")) {
         Object returnType = ModelUtil.getReturnJavaType(meth);
         if (returnType != null && !ModelUtil.typeIsVoid(returnType))
            return null;
         Object[] paramJavaTypes = ModelUtil.getParameterJavaTypes(meth, false);
         if (paramJavaTypes != null && paramJavaTypes.length == 2 && ModelUtil.typeIsInteger(paramJavaTypes[0])) {
              return CTypeUtil.decapitalizePropertyName(name.substring(3));
         }
      }
      return null;
   }

   public static String isSetIndexMethod(String name, Object[] paramJavaTypes, Object returnType) {
      if (name.startsWith("set") && paramJavaTypes != null && paramJavaTypes.length == 2 &&
          (returnType == null || ModelUtil.typeIsVoid(returnType)) &&
          ModelUtil.typeIsInteger(paramJavaTypes[0])) {
         return CTypeUtil.decapitalizePropertyName(name.substring(3));
      }
      return null;
   }

   public static String isGetIndexMethodImpl(Object meth) {
      String name = ModelUtil.getMethodName(meth);
      if (name.startsWith("get")) {
         Object returnType = ModelUtil.getReturnJavaType(meth);
         if (returnType != null && ModelUtil.typeIsVoid(returnType))
            return null;
         Object[] paramJavaTypes = ModelUtil.getParameterJavaTypes(meth, false);
         if (paramJavaTypes != null && paramJavaTypes.length == 1 &&
              ModelUtil.typeIsInteger(paramJavaTypes[0])) {
            return CTypeUtil.decapitalizePropertyName(name.substring(3));
         }
      }
      return null;
   }

   public static String isGetIndexMethod(String name, Object[] paramJavaTypes, Object returnType) {
      if (name.startsWith("get") && paramJavaTypes != null && paramJavaTypes.length == 1 &&
              (returnType != null && !ModelUtil.typeIsVoid(returnType)) &&
              ModelUtil.typeIsInteger(paramJavaTypes[0])) {
         return CTypeUtil.decapitalizePropertyName(name.substring(3));
      }
      return null;
   }

   public static boolean typeIsVoid(Object type) {
      if (type instanceof JavaType)
         return ((JavaType) type).isVoid();
      // NOTE: do not include java.lang.Void here - i.e. Void.class.  that's a placeholder for type parameters and is assignable to object.
      return type == Void.TYPE;
   }

   public static boolean typeIsInteger(Object type) {
      if (type instanceof JavaType) {
         // Note: this does not get the absolute type name - we'd need to start it and get the type name on the type declaration
         // but for now we're just testing against Integer without the package name.
         String typeName = ((JavaType) type).getFullTypeName();
         return typeName != null && (typeName.equals("java.lang.Integer") || typeName.equals("int")) || typeName.equals("Integer");
      }
      return type == Integer.class || type == Integer.TYPE;
   }

   public static boolean typeIsBoolean(Object type) {
      if (type instanceof JavaType) {
         String typeName = ((JavaType) type).getFullTypeName();
         return typeName != null && (typeName.equals("java.lang.Boolean") || typeName.equals("boolean"));
      }
      return type == Boolean.class || type == Boolean.TYPE;
   }

   public static boolean typeIsString(Object type) {
      if (type instanceof JavaType) {
         String typeName = ((JavaType) type).getFullTypeName();
         return typeName != null && (typeName.equals("java.lang.String") || typeName.equals("String"));
      }
      return type == String.class;
   }

   public static boolean typeIsStringArray(Object type) {
      if (type instanceof JavaType) {
         String typeName = ((JavaType) type).getFullTypeName();
         return typeName != null && (typeName.equals("java.lang.String[]") || typeName.equals("String[]"));
      }
      return type == String.class;
   }

   public static String toDefinitionString(Object typeObj) {
      if (typeObj instanceof SemanticNode)
         return ((SemanticNode) typeObj).toDefinitionString();
      return typeObj.toString();
   }

   public static String toDeclarationString(Object typeObj) {
      if (typeObj instanceof SemanticNode)
         return ((SemanticNode) typeObj).toDeclarationString();
      return typeObj.toString();
   }

   public static String argumentsToString(List<? extends ITypedObject> arguments) {
      if (arguments == null)
         return "(null)";
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      sb.append("(");
      for (ITypedObject to:arguments) {
         if (first)
            first = false;
         else
            sb.append(",");
         Object type;
         if (to == null || (type = to.getTypeDeclaration()) == null)
            sb.append("null");
         else {
            sb.append(to.getAbsoluteGenericTypeName(null, true));
            //sb.append(ModelUtil.getTypeName(type));
         }
      }
      sb.append(")");
      return sb.toString();
   }

   // Should take a list of ITypedObject as the parameter type
   public static Object[] listToTypes(List<?> list) {
      if (list == null)
         return null;

      Object[] types = new Object[list.size()];
      for (int i = 0; i < types.length; i++) {
         ITypedObject type = (ITypedObject)list.get(i);
         types[i] = type.getTypeDeclaration();
      }
      return types;
   }

   public static Object[] varListToTypes(List<?> list) {
      if (list == null)
         return null;

      Object[] types = new Object[list.size()];
      for (int i = 0; i < types.length; i++) {
         Object t = list.get(i);
         if (t != null)
            types[i] = ModelUtil.getVariableTypeDeclaration(t);// TODO: performance: this method is probably slow
      }
      return types;
   }

   // TODO: reconcile this with hasMethodUnboundTypeParameters.
   public static boolean isParameterizedMethod(Object method) {
      return hasParameterizedReturnType(method) || hasParameterizedArguments(method);
   }

   public static boolean hasParameterizedReturnType(Object method) {
      if (method instanceof Method) {
         Type returnType = ((Method) method).getGenericReturnType();
         return returnType instanceof TypeVariable || returnType instanceof ParameterizedType || returnType instanceof GenericArrayType;
      }
      else if (method instanceof IMethodDefinition) {
         IMethodDefinition methDef = (IMethodDefinition) method;
         if (methDef.isConstructor())
            return false;
         Object retType = methDef.getReturnType(false);
         return retType instanceof TypeParameter || ModelUtil.hasUnboundTypeParameters(retType);
      }
      else if (method instanceof Constructor)
         return false;
      throw new UnsupportedOperationException();
   }

   public static boolean hasParameterizedArguments(Object method) {
      if (method instanceof Method) {
         Type[] paramTypes = ((Method) method).getGenericParameterTypes();
         for (Type paramType:paramTypes)
            if (paramType instanceof TypeVariable || ModelUtil.isParameterizedType(paramType))
               return true;
         return false;
      }
      else if (method instanceof IMethodDefinition) {
         JavaType[] paramTypes = ((IMethodDefinition) method).getParameterJavaTypes(true);
         if (paramTypes != null) {
            for (JavaType paramType:paramTypes) {
               if (paramType.isParameterizedType())
                  return true;
            }
         }
         return false;
      }
      // TODO: In Java 1.8 there's a new Executable interface - but not using it for compatibility with older versions
      if (method instanceof Constructor) {
         Type[] paramTypes = ((Constructor) method).getGenericParameterTypes();
         for (Type paramType:paramTypes)
            if (paramType instanceof TypeVariable || ModelUtil.isParameterizedType(paramType))
               return true;
         return false;
      }
      throw new UnsupportedOperationException();
   }

   /**
    * If we are looking for a type with information about type parameters, we need to use a different
    * method for the native Class/Method etc. as they do not store the type param stuff in the same place.
    */
   public static Object getParameterizedType(Object member, JavaSemanticNode.MemberType type) {
      if (member instanceof ParamTypedMember)
         return ((ParamTypedMember) member).paramTypeDecl;
      if (type == JavaSemanticNode.MemberType.SetMethod) {
         if (member instanceof IMethodDefinition) {
            Object[] paramTypes = ((IMethodDefinition) member).getParameterTypes(false);
            return paramTypes[0];
         }
         else if (member instanceof IBeanMapper)
            return ((IBeanMapper) member).getGenericType();
         else if (member instanceof Method) {
            Type[] parameterTypes = ((Method) member).getGenericParameterTypes();
            if (parameterTypes.length != 1)
               throw new IllegalArgumentException("Set method with wrong number of parameters: " + member);
            return parameterTypes[0];
         }
      }
      else if (type == JavaSemanticNode.MemberType.GetMethod || type == JavaSemanticNode.MemberType.Field || type == JavaSemanticNode.MemberType.Variable) {
         if (member instanceof Field)
            return ((Field) member).getGenericType();
         else if (member instanceof ITypedObject)
            return ((ITypedObject) member).getTypeDeclaration();
         else if (member instanceof IBeanMapper)
            return ((IBeanMapper) member).getGenericType();
         else if (member instanceof ITypeDeclaration)
            return member;
         else if (member instanceof Method)
            return ((Method) member).getGenericReturnType();
         else if (member instanceof IMethodDefinition) {
            return ((IMethodDefinition) member).getReturnType(true);
         }
         else if (member instanceof Class)
            return member;
         // Either ParamTypeDeclaration or ParameterizedType
         else if (ModelUtil.hasTypeParameters(member))
            return member;
      }
      else if (type == JavaSemanticNode.MemberType.ObjectType) {
         return member;
      }
      if (member instanceof ParamTypedMember)
         return member;
      throw new UnsupportedOperationException();
   }

   public static Object getParameterizedReturnType(Object method, List<? extends ITypedObject> arguments, boolean resolve) {
      if (method instanceof Method)
         return ((Method) method).getGenericReturnType();
      else if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).getTypeDeclaration(arguments, resolve);
      throw new UnsupportedOperationException();
   }

   // Is this type based on type parameters in any way
   public static boolean isParameterizedType(Object type) {
      // TODO: should we include ClassTypes here with type arguments?
      return type instanceof TypeParameter || type instanceof ParameterizedType || type instanceof TypeVariable || type instanceof ParamTypeDeclaration;
   }

   public static boolean isTypeVariable(Object type) {
      return type instanceof TypeParameter || type instanceof TypeVariable;
   }

   public static boolean isWildcardType(Object type) {
      return type instanceof WildcardType || type instanceof ExtendsType;
   }

   public static boolean isUnboundedWildcardType(Object type) {
      if (type instanceof ExtendsType.WildcardTypeDeclaration) {
         return true;
      }
      else if (type instanceof WildcardType) {
         WildcardType wt = (WildcardType) type;
         return wt.getUpperBounds().length == 0 && wt.getLowerBounds().length == 0;
      }
      else if (type instanceof ExtendsType)
         return ((ExtendsType) type).typeArgument == null;
      return false;
   }

   public static boolean isSuperWildcard(Object type) {
      if (type instanceof WildcardType)
         return type.toString().contains("super");
      else if (type instanceof ExtendsType) {
         String op = ((ExtendsType) type).operator;
         return op != null && op.equals("super");
      }
      else
         throw new UnsupportedOperationException();
   }

   public static Object getWildcardBounds(Object type) {
      if (ModelUtil.isSuperWildcard(type))
         return ModelUtil.getWildcardLowerBounds(type);
      else
         return ModelUtil.getWildcardUpperBounds(type);
   }

   public static Object getWildcardLowerBounds(Object type) {
      if (type instanceof WildcardType) {
         Type[] bounds = ((WildcardType) type).getLowerBounds();
         if (bounds.length == 0)
            return null;
         return bounds[0];
      }
      else if (type instanceof ExtendsType) {
         return ((ExtendsType) type).typeArgument.getTypeDeclaration();
      }
      else
         throw new UnsupportedOperationException();
   }

   public static Object getWildcardUpperBounds(Object type) {
      if (type instanceof WildcardType)
         return ((WildcardType) type).getUpperBounds()[0];
      else if (type instanceof ExtendsType) {
         ExtendsType ext = ((ExtendsType) type);
         if (ext.typeArgument == null)
            return Object.class;
         return ext.typeArgument.getTypeDeclaration();
      }
      else
         throw new UnsupportedOperationException();
   }

   public static boolean hasParameterizedType(Object member, JavaSemanticNode.MemberType type) {
      return isParameterizedType(getParameterizedType(member, type));
   }

   public static boolean hasTypeVariables(Object type) {
      return ModelUtil.isTypeVariable(type) || (type instanceof ExtendsType.LowerBoundsTypeDeclaration && hasTypeVariables(((ExtendsType.LowerBoundsTypeDeclaration) type).getBaseType())) ||
             (ModelUtil.isGenericArray(type) && hasTypeVariables(ModelUtil.getGenericComponentType(type))) || (type instanceof WildcardType && ModelUtil.hasTypeVariables(getWildcardBounds(type)));
   }

   public static String getTypeParameterString(Object type) {
      List<?> tps = ModelUtil.getTypeParameters(type);
      if (tps == null)
         return "";
      StringBuilder sb = null;
      for (Object o:tps) {
         if (sb == null) {
            sb = new StringBuilder();
            sb.append("<");
         }
         else
            sb.append(", ");
         sb.append(ModelUtil.getTypeParameterName(o));
      }
      if (sb != null) {
         sb.append(">");
         return sb.toString();
      }
      return "";
   }

   /** Returns TypeParameter or TypeVariable */
   public static List<?> getTypeParameters(Object type) {
      if (type instanceof ITypeDeclaration)
         return ((ITypeDeclaration) type).getClassTypeParameters();
      else if (type instanceof Class)
         return Arrays.asList(((Class) type).getTypeParameters());
      else if (type instanceof TypeParameter)
         return null;
      else if (type instanceof TypeVariable)
         return null;
      else if (type instanceof ParameterizedType)
         return getTypeParameters(((ParameterizedType) type).getRawType());
      else
         throw new UnsupportedOperationException();
   }

   public static String getTypeParameterName(Object typeParam) {
      if (typeParam instanceof TypeParameter)
         return ((TypeParameter) typeParam).name;
      else if (typeParam instanceof TypeVariable) {
         TypeVariable tvar = (TypeVariable) typeParam;
         return tvar.getName();
      }
      else if (typeParam instanceof Class) {
         return null;
      }
      // We could check if the bounds are a type parameter but don't think that's right since this is not a type variable
      else if (ModelUtil.isWildcardType(typeParam))
         return null;
      else
         throw new UnsupportedOperationException();
   }

   public static Object getTypeParameterDefault(Object typeParam) {
      if (typeParam instanceof TypeParameter) {
         return ((TypeParameter) typeParam).getTypeDeclaration();
      }
      else if (typeParam instanceof TypeVariable) {
         TypeVariable tv = (TypeVariable) typeParam;
         GenericDeclaration genericDecl = tv.getGenericDeclaration();
         return tv.getBounds()[0];
      }
      else if (typeParam instanceof ExtendsType.LowerBoundsTypeDeclaration) {
         return getTypeParameterDefault(((ExtendsType.LowerBoundsTypeDeclaration) typeParam).getBaseType());
      }
      else if (ModelUtil.isGenericArray(typeParam)) {
         return new ArrayTypeDeclaration(typeParam instanceof ITypeDeclaration ? ((ITypeDeclaration) typeParam).getLayeredSystem() : null, typeParam instanceof ArrayTypeDeclaration ? ((ArrayTypeDeclaration) typeParam).definedInType : null, ModelUtil.getTypeParameterDefault(ModelUtil.getGenericComponentType(typeParam)), "[]");
      }
      else
         throw new UnsupportedOperationException();
   }

   public static int getTypeParameterPosition(Object typeParam) {
      if (typeParam instanceof TypeParameter)
         return ((TypeParameter) typeParam).getPosition();
      else if (typeParam instanceof TypeVariable) {
         TypeVariable tvar = (TypeVariable) typeParam;
         return arrayIndexOf(tvar.getGenericDeclaration().getTypeParameters(), tvar);
      }
      else
         throw new UnsupportedOperationException();
   }

   public static int getTypeParameterPosition(Object rootType, String typeParamName) {
      List<?> typeParameters = ModelUtil.getTypeParameters(rootType);
      if (typeParameters != null) {
         int ix = 0;
         for (Object tp:typeParameters) {
            if (ModelUtil.getTypeParameterName(tp).equals(typeParamName))
               return ix;
            ix++;
         }
      }
      return -1;
   }

   private static int arrayIndexOf(Object[] array, Object element) {
      for (int i = 0; i < array.length; i++)
         if (array[i] == element)
            return i;
      return -1;
   }

   public static String getGenericTypeName(Object resultType, Object member, boolean includeDims) {
      if (member instanceof Member) {
         return RTypeUtil.getGenericTypeName(resultType, member, includeDims);
      }
      else if (member instanceof IBeanMapper) {
         Object pVal = ((IBeanMapper) member).getGenericType();
         String res = resolveGenericTypeName(getEnclosingType(member), resultType, pVal, includeDims);
         if (res == null)
            return "Object";
         return res;
      }
      else if (member instanceof ITypedObject) {
         Object pval = ((ITypedObject) member).getTypeDeclaration();
         String res = resolveGenericTypeName(getEnclosingType(member), resultType, pval, includeDims);
         if (res == null)
            return "Object";
         return res;
      }
      else
         throw new IllegalArgumentException();
   }

   public static String resolveGenericTypeName(Object srcType, Object resultType, Object genType, boolean includeDims) {
      if (genType instanceof GenericArrayType) {
         GenericArrayType gat = (GenericArrayType) genType;
         String componentName = resolveGenericTypeName(srcType, resultType, gat.getGenericComponentType(), includeDims);
         if (includeDims)
            return componentName + "[]";
         else
            return componentName;
      }
      if (genType instanceof ParameterizedType) {
         ParameterizedType pt = (ParameterizedType) genType;
         java.lang.reflect.Type[] args = pt.getActualTypeArguments();
         StringBuilder sb = new StringBuilder();
         sb.append(resolveGenericTypeName(srcType, resultType, pt.getRawType(), includeDims));
         boolean tpErased = false;
         StringBuilder tpNames = new StringBuilder();
         boolean first = true;
         for (java.lang.reflect.Type at:args) {
            // Do not propagate includeDims inside of the <> since it is part of the type args then
            Object tpName = resolveGenericTypeName(srcType, resultType, at, true);
            if (tpName == null) {
               // Do all args need to be unmapped for us to erase the type parameters?
               tpErased = true;
               break;
            }
            if (first)
               first = false;
            else
               tpNames.append(",");
            tpNames.append(tpName);
         }
         if (!tpErased) {
            sb.append("<");
            sb.append(tpNames);
            sb.append(">");
         }
         return sb.toString();
      }
      else if (genType instanceof Class) {
         Class cl = (Class) genType;
         if (cl.isArray()) {
            Class componentClass = cl.getComponentType();
            String componentName = resolveGenericTypeName(srcType, resultType, componentClass, includeDims);
            if (includeDims)
               return componentName + "[]";
            else
               return componentName;
         }
         return ((Class) genType).getName();
      }
      else if (ModelUtil.isTypeVariable(genType)) {
         String varName = ModelUtil.getTypeParameterName(genType);

         // TODO: replace this with resolveTypeParameter
         Object res = ModelUtil.resolveTypeParameterName(srcType, resultType, varName);
         if (res == null)
            return null;
         if (ModelUtil.isTypeVariable(res))
            return ModelUtil.getTypeParameterName(res);
         else if (res instanceof String)
            return (String) res;
         else
            return resolveGenericTypeName(srcType, resultType, res, true);
      }
      else if (genType instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) genType).getFullBaseTypeName();
      }
      else if (genType instanceof JavaType) {
         return ((JavaType) genType).getGenericTypeName(resultType, includeDims);
      }
      else
         throw new UnsupportedOperationException();
   }

   /** Computes the list of classes which map the base type 'srcType' to the sub type resultType.  For interfaces we check the implements types. */
   public static List<Object> getExtendsJavaTypePath(Object srcType, Object resultType) {
      List<Object> res = null;
      boolean sameTypes = false;
      while (resultType != null && !(sameTypes = ModelUtil.sameTypes(srcType, resultType))) {
         if (res == null)
            res = new ArrayList<Object>();

         // Object next = ModelUtil.getSuperclass(resultType);
         Object next = ModelUtil.getExtendsClass(resultType);
         if (next != null && ModelUtil.isAssignableFrom(srcType, next)) {
            res.add(ModelUtil.getExtendsJavaType(resultType));
         }
         else {
            next = null;
            Object[] ifaces = ModelUtil.getImplementsTypeDeclarations(resultType);
            Object[] ifaceJavaTypes = ModelUtil.getImplementsJavaTypes(resultType);
            if (ifaces != null && ifaceJavaTypes != null) {
               for (int i = 0; i < ifaces.length; i++) {
                  Object ifaceType = ifaces[i];
                  Object ifaceJavaType = ifaceJavaTypes[i];
                  if (ifaceType != null && ModelUtil.isAssignableFrom(srcType, ifaceType)) {
                     next = ifaceType;
                     res.add(ifaceJavaType);
                     break;
                  }
               }
            }
         }
         resultType = next;
      }
      if (!sameTypes)
         System.out.println("*** did not find expected path between types");
      return res;
   }

   /** TODO - replace this with resolveTypeParameter */
   public static Object resolveTypeParameterName(Object srcType, Object resultType, String typeParamName) {
      int srcIx = ModelUtil.getTypeParameterPosition(srcType, typeParamName);
      if (srcIx == -1) {
         System.err.println("*** Unresolveable type parameter!");
         return null;
      }
      List<Object> extTypes = getExtendsJavaTypePath(srcType, resultType);
      if (extTypes == null || extTypes.size() == 0) {
         return getTypeParameters(srcType).get(srcIx);
      }

      Object nextType;
      for (int i = extTypes.size()-1; i >= 0; i--) {
         Object ext = extTypes.get(i);
         List<?> typeArgs = ModelUtil.getTypeArguments(ext);
         if (typeArgs == null)
            return null;
         Object typeArg = typeArgs.get(srcIx);
         if (ModelUtil.isTypeVariable(typeArg)) {
            typeParamName = ModelUtil.getTypeParameterName(typeArg);

            if (i == 0)
               nextType = resultType;
            else {
               nextType = extTypes.get(i-1);
               if (nextType instanceof JavaType)
                  nextType = ((JavaType) nextType).getTypeDeclaration();
            }

            srcIx = ModelUtil.getTypeParameterPosition(nextType, typeParamName);
         }
         else if (typeArg instanceof ExtendsType.LowerBoundsTypeDeclaration)
            System.out.println("*** Unknown lower bounds type 3");
         else
            return typeArg;
      }
      return typeParamName;
   }

   public static Object resolveBaseTypeParameters(LayeredSystem sys, Object subType, Object baseType) {
      if (ModelUtil.sameTypes(subType, baseType)) {
         return ModelUtil.getTypeParameters(subType);
      }
      List<Object> extTypes = getExtendsJavaTypePath(baseType, subType);
      if (extTypes == null || extTypes.size() == 0) {
         System.err.println("*** No extends path from baseType to subType");
         return null;
      }

      int numInParams = ModelUtil.getNumTypeParameters(subType);
      int numOutParams = ModelUtil.getNumTypeParameters(baseType);
      ArrayList<Object> res = new ArrayList<Object>(numOutParams);
      for (int i = 0; i < numOutParams; i++) {
         Object typeParam = ModelUtil.getTypeParameter(baseType, i);
         Object newParam = resolveBaseTypeParameterInternal(extTypes, subType, baseType, typeParam);
         res.add(newParam);
      }
      return res;
   }

   /**
    * TODO: this seems like the same thing as resolveTypeParameter - can we eliminate one implementation?
    *
    * Given a subType and baseType and the typeParameter in the base-type, returns the type or type parameter in the sub type.
    * For example, given List&lt;E&gt; and MyList implements List&lt;Integer&gt; you provide subType = MyList, baseType = List and typeParam = E
    * and get back Integer.
    */
   public static Object resolveBaseTypeParameter(Object subType, Object baseType, Object typeParam) {
      if (ModelUtil.sameTypes(subType, baseType)) {
         String typeParamName = ModelUtil.getTypeParameterName(typeParam);
         int srcIx = ModelUtil.getTypeParameterPosition(subType, typeParamName);
         if (srcIx == -1) {
            System.err.println("*** Unresolveable base type parameter!");
            return null;
         }
         if (ModelUtil.hasTypeParameters(subType) && ModelUtil.getNumTypeParameters(subType) > srcIx)
            return ModelUtil.getTypeParameter(subType, srcIx);
         return typeParam;
      }
      List<Object> extTypes = getExtendsJavaTypePath(baseType, subType);

      return resolveBaseTypeParameterInternal(extTypes, subType, baseType, typeParam);
   }

   public static List<Object> resolveSubTypeParameters(Object subType, Object baseType) {
      List<?> typeParams = ModelUtil.getTypeParameters(subType);
      if (typeParams == null)
         return null;
      int numParams = typeParams.size();
      ArrayList<Object> res = new ArrayList<Object>(numParams);
      if (ModelUtil.sameTypes(subType, baseType)) {
         for (int i = 0; i < numParams; i++) {
            res.add(ModelUtil.getTypeParameter(subType, i));
         }
         return res;
      }
      else {
         List<Object> extTypes = getExtendsJavaTypePath(baseType, subType);
         for (int pix = 0; pix < numParams; pix++) {
            int srcIx = pix;
            Object nextType = subType;
            Object paramRes = ModelUtil.getTypeParameter(subType, pix);
            Object origRes = paramRes;
            Object lastTypeArg = null;
            Object nextParamType = null;
            if (ModelUtil.isTypeVariable(paramRes)) {
               for (int extIx = 0; extIx < extTypes.size(); extIx++) {
                  Object ext = extTypes.get(extIx);
                  List<?> typeArgs = ModelUtil.getTypeArguments(ext);
                  if (typeArgs == null) {
                     break;
                  }
                  srcIx = -1;
                  Object typeArg = null;
                  for (int tix = 0; tix < typeArgs.size(); tix++) {
                     typeArg = typeArgs.get(tix);
                     typeArg = resolveExtTypeDeclaration(typeArg);
                     if (ModelUtil.isTypeVariable(typeArg) && ModelUtil.sameTypeParameters(paramRes, typeArg)) {
                        srcIx = tix;
                        break;
                     }
                  }
                  if (srcIx == -1) {
                     break;
                  }
                  nextType = resolveExtTypeDeclaration(ext);
                  if (srcIx >= typeArgs.size()) {
                     srcIx = -1;
                     break;
                  }
                  if (typeArg instanceof JavaType)
                     typeArg = ((JavaType) typeArg).getTypeDeclaration();
                  if (nextType == null) {
                     srcIx = -1;
                     break;
                  }
                  paramRes = ModelUtil.getTypeArgument(nextType, srcIx);
                  if (!ModelUtil.isTypeVariable(paramRes)) {
                     srcIx = -1;
                     break;
                  }
               }
            }
            else
               srcIx = -1;
            if (srcIx != -1)
               paramRes = ModelUtil.getTypeParameter(baseType, srcIx);
            if (!ModelUtil.isTypeVariable(paramRes))
               res.add(paramRes);
            else
               res.add(origRes);
         }
      }
      return res;
   }

   private static Object resolveExtTypeDeclaration(Object ext) {
      return ext instanceof JavaType ? ((JavaType) ext).getTypeDeclaration() : (ext instanceof ParameterizedType ? ((ParameterizedType) ext).getRawType() : ext);
   }

   private static Object resolveBaseTypeParameterInternal(List<Object> extTypes, Object subType, Object baseType, Object typeParam) {
      if (extTypes == null || extTypes.size() == 0) {
         System.err.println("*** No extends path from baseType to subType");
         return null;
      }
      Object lastExtType = extTypes.remove(extTypes.size() - 1);

      int curParamIx = -1;
      List<?> baseTypeParams = ModelUtil.getTypeParameters(baseType);
      int ct = 0;
      for (Object baseTypeParam:baseTypeParams) {
         if (ModelUtil.sameTypeParameters(baseTypeParam, typeParam)) {
            curParamIx = ct;
            break;
         }
         ct++;
      }
      if (curParamIx == -1) {
         return null;
      }

      List<?> lastExtTypeArgs = ModelUtil.getTypeArguments(lastExtType);
      if (lastExtTypeArgs == null)
         return null;
      Object lastExtTypeArg = lastExtTypeArgs.get(curParamIx);
      if (lastExtTypeArg instanceof JavaType)
         lastExtTypeArg = ((JavaType) lastExtTypeArg).getTypeDeclaration();
      if (!ModelUtil.isTypeVariable(lastExtTypeArg)) {
         return lastExtTypeArg;
      }

      Object nextType;
      Object lastTypeArg = typeParam;
      for (int i = extTypes.size()-1; i >= 0; i--) {
         Object ext = extTypes.get(i);
         List<?> typeArgs = ModelUtil.getTypeArguments(ext);
         if (typeArgs == null)
            return null;

         Object typeArg = typeArgs.get(curParamIx);
         if (typeArg instanceof JavaType)
            typeArg = ((JavaType) typeArg).getTypeDeclaration();
         if (!ModelUtil.isTypeVariable(typeArg)) {
            return typeArg;
         }
         else {
            lastTypeArg = typeArg;
            String typeParamName = ModelUtil.getTypeParameterName(typeArg);

            if (i == 0)
               nextType = subType;
            else {
               nextType = extTypes.get(i - 1);
               if (nextType instanceof JavaType)
                  nextType = ((JavaType) nextType).getTypeDeclaration();
            }
            curParamIx = ModelUtil.getTypeParameterPosition(nextType, typeParamName);
         }
      }

      // The type arg here also needs to be mapped.
      int numTypeParams = ModelUtil.getNumTypeParameters(subType);
      if (curParamIx < numTypeParams) {
         lastTypeArg = ModelUtil.getTypeParameter(subType, curParamIx);
         if (!ModelUtil.isTypeVariable(lastTypeArg)) {
            return lastTypeArg;
         }
      }
      // The type arg is not propagated so use the last bound value we have for that type argument
      else if (lastExtType != null && ModelUtil.hasTypeParameters(lastExtType) && curParamIx < ModelUtil.getNumTypeParameters(lastExtType)) {
         lastTypeArg = ModelUtil.getTypeParameter(lastExtType, curParamIx);
         if (!ModelUtil.isTypeVariable(lastTypeArg)) {
            return lastTypeArg;
         }
      }
      else if (lastExtType != null && ModelUtil.hasTypeArguments(lastExtType) && curParamIx < ModelUtil.getNumTypeArguments(lastExtType)) {
         lastTypeArg = ModelUtil.getTypeArgument(lastExtType, curParamIx);
         if (lastTypeArg instanceof JavaType)
            lastTypeArg = ((JavaType) lastTypeArg).getTypeDeclaration();
         // TODO: will we ever have java.lang.reflect.Type here?
         if (!ModelUtil.isTypeVariable(lastTypeArg)) {
            return lastTypeArg;
         }
      }
      // Use the default type for this type variable
      return ModelUtil.getTypeParameterDefault(lastTypeArg);
   }

   /**
    * Computes the type parameter in type 'resultType' that corresponds to "typeParam" you provide for the base type srcType.
    * In other words, it lets you determine the value of a base type's type parameter when evaluated in the context of the sub-type.
    * So List&lt;E&gt; - you provide List.class, E, and the resultType of MySubType implements List&lt;Integer&gt; and it will return Integer.class
    * This "typeParam" should be one of the type variables srcType was defined with, and srcType should be a baseType of resultType.
    */
   public static Object resolveTypeParameter(Object srcType, Object resultType, Object typeParam) {
      String typeParamName = ModelUtil.getTypeParameterName(typeParam);
      int srcIx = ModelUtil.getTypeParameterPosition(srcType, typeParamName);
      if (srcIx == -1) {
         System.err.println("*** Unresolveable type parameter!");
         return null;
      }
      List<Object> extTypes = getExtendsJavaTypePath(srcType, resultType);
      if (extTypes == null || extTypes.size() == 0) {
         return getTypeParameters(srcType).get(srcIx);
      }

      Object res = typeParam;

      Object nextType;
      for (int i = extTypes.size()-1; i >= 0; i--) {
         Object ext = extTypes.get(i);
         List<?> typeArgs = ModelUtil.getTypeArguments(ext);
         if (typeArgs == null)
            return null;
         Object typeArg = typeArgs.get(srcIx);
         if (typeArg instanceof JavaType)
            typeArg = ((JavaType) typeArg).getTypeDeclaration();
         if (ModelUtil.isTypeVariable(typeArg)) {
            res = typeArg;
            typeParamName = ModelUtil.getTypeParameterName(res);

            if (i == 0)
               nextType = resultType;
            else {
               nextType = extTypes.get(i-1);
               if (nextType instanceof JavaType)
                  nextType = ((JavaType) nextType).getTypeDeclaration();
            }

            srcIx = ModelUtil.getTypeParameterPosition(nextType, typeParamName);
         }
         else if (typeArg instanceof ExtendsType.LowerBoundsTypeDeclaration)
            System.out.println("*** Unknown lower bounds type 2");
         else
            return typeArg;
      }
      return res;
   }

   public static Object resolveTypeParameter(Object srcType, ITypeParamContext resultType, String typeParamName) {
      int srcIx = ModelUtil.getTypeParameterPosition(srcType, typeParamName);
      if (srcIx == -1) {
         // This might be a method type parameter which is not yet defined
         return Object.class;
      }
      List<Object> extTypes = getExtendsJavaTypePath(srcType, resultType);
      if (extTypes == null || extTypes.size() == 0) {
         if (resultType == null)
            return null;
         return resultType.getType(srcIx);
      }

      Object nextType;
      for (int i = extTypes.size()-1; i >= 0; i--) {
         Object ext = extTypes.get(i);
         List<?> typeArgs = ModelUtil.getTypeArguments(ext);
         if (typeArgs == null)
            return null;
         Object typeArg = typeArgs.get(srcIx);
         if (typeArg instanceof JavaType) {
            typeArg = ((JavaType) typeArg).getTypeDeclaration();
         }
         if (ModelUtil.isTypeVariable(typeArg)) {
            typeParamName = ModelUtil.getTypeParameterName(typeArg);

            if (i == 0)
               nextType = resultType;
            else {
               nextType = extTypes.get(i-1);
               if (nextType instanceof JavaType)
                  nextType = ((JavaType) nextType).getTypeDeclaration();
            }

            srcIx = ModelUtil.getTypeParameterPosition(nextType, typeParamName);
         }
         else if (typeArg instanceof ExtendsType.LowerBoundsTypeDeclaration)
            System.out.println("*** Unknown lower bounds type");
         else
            return typeArg;
      }
      return resultType.getType(srcIx);
   }

   public static int getNumTypeArguments(Object extJavaType) {
      List<?> args = getTypeArguments(extJavaType);
      return args == null ? 0 : args.size();
   }

   public static List<?> getTypeArguments(Object extJavaType) {
      if (extJavaType instanceof JavaType) {
         return ((JavaType) extJavaType).getResolvedTypeArguments();
      }
      else if (extJavaType instanceof Type) {
         if (extJavaType instanceof ParameterizedType) {
            return Arrays.asList(((ParameterizedType) extJavaType).getActualTypeArguments());
         }
         else if (extJavaType instanceof Class) {
            return null;
         }
         else {
            throw new UnsupportedOperationException();
         }
      }
      else
         throw new UnsupportedOperationException();
   }

   public static boolean hasTypeArguments(Object extJavaType) {
      return extJavaType instanceof JavaType || extJavaType instanceof Type;
   }

   public static String getAbsoluteGenericTypeName(Object resultType, Object member, boolean includeDims) {
      if (member instanceof Member) {
         return RTypeUtil.getGenericTypeName(resultType, member, includeDims);
      }
      else if (member instanceof IBeanMapper) {
         return ((IBeanMapper) member).getGenericTypeName(resultType, includeDims);
      }
      else if (member instanceof ITypedObject) {
         return ((ITypedObject) member).getAbsoluteGenericTypeName(resultType, includeDims);
      }
      else
         throw new IllegalArgumentException();
   }

   /**
    * Looks for a member - field, method, enum with the given name on the given type.  The refType specifies a type used for access control checks - if you're not allowed, null is returned.
    * Pass in a LayeredSystem if you want to find a src reference for an annotation layer that's applied to a compiled type.  For example, you might have a compiled type Foo that extends Bar which
    * has an annotation layer.  If we find a member in 'Bar', we need to check if the 'Bar' src type has a version of that member.  If so we return that instead since it's more specific.
    * This is the one of the ways we can attach meta-data onto types that are delivered in a compiled format.  If you don't care about that, pass in null for the sys and that check is not performed.
    */
   public static Object definesMember(Object type, String name, EnumSet<JavaSemanticNode.MemberType> mtypes, Object refType, TypeContext ctx, LayeredSystem sys) {
      return definesMember(type, name, mtypes, refType, ctx, false, false, sys);
   }

   public static Object definesMember(Object type, String name, EnumSet<JavaSemanticNode.MemberType> mtypes, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed, LayeredSystem sys) {
      Object res;
      if (type instanceof ITypeDeclaration) {
         if ((res = ((ITypeDeclaration) type).definesMember(name, mtypes, refType, ctx, skipIfaces, isTransformed)) != null)
            return res;
      }
      else if (type instanceof TypeParameter) {
         // TODO... feret out more type info about the type parameter based on the TypeParamContext and
         // the extends and super things in type param's definition's definition.
         return null;
      }
      else {
         // No way to pull property assignments out of class definitions now
         if (mtypes.contains(JavaSemanticNode.MemberType.Initializer))
            return null;

         Class classDecl = (Class) type;
         IBeanMapper mapper = null;
         boolean found = false;
         for (JavaSemanticNode.MemberType mtype:mtypes) {
            switch (mtype) {
               case Field:
                  // Java does not let us reflect the length field of an array so we need to handle
                  // that special case here
                  if (name.equals("length") && classDecl.isArray())
                     return ArrayTypeDeclaration.LENGTH_FIELD;
                  if ((mapper = PTypeUtil.getPropertyMapping(classDecl, name)) != null && mapper.getField() != null &&
                       (refType == null || checkAccess(refType, mapper.getField())))
                     found = true;
                  else
                     mapper = null;

                  //Object theField;

                  //if ((res = RTypeUtil.getField(classDecl, name)) != null && ((refType == null || checkAccess(refType, res))))
                  //   return res;
                  //if ((res = TypeUtil.getPropertyMapping(classDecl, name, null, null)) != null &&
                  //        res instanceof IBeanMapper && (theField = ((IBeanMapper) res).getField()) != null &&
                  //        (refType == null || checkAccess(refType, theField))) {
                   //  if (RTypeUtil.getField(classDecl, name) != theField)
                   //     System.out.println("*** fields don';t match");
                   //  return theField;
                  //}

                  //if ((res = RTypeUtil.getField(classDecl, name)) != null && ((refType == null || checkAccess(refType, res))))
                  //   return res;

                  break;
               case GetMethod:
                  if ((mapper = TypeUtil.getPropertyMapping(classDecl, name, null, null)) != null &&
                      mapper.hasAccessorMethod() && (refType == null || checkAccess(refType, mapper.getPropertyMember())))
                     found = true;
                  else
                     mapper = null;
                  break;
               case SetMethod:
                  if ((mapper = PTypeUtil.getPropertyMapping(classDecl, name)) != null &&
                       mapper.hasSetterMethod() && (refType == null || checkAccess(refType, mapper.getPropertyMember())))
                     found = true;
                  else
                     mapper = null;
                  break;
               case Enum:
                  if ((res = RTypeUtil.getEnum(classDecl, name)) != null)
                     return res;
                  break;
               case GetIndexed:
                  IBeanIndexMapper im;
                  if ((mapper = TypeUtil.getPropertyMapping(classDecl, name, null, null)) != null &&
                          mapper instanceof IBeanIndexMapper && (im = (IBeanIndexMapper) mapper).hasIndexedAccessorMethod() &&
                          (refType == null || checkAccess(refType, im.getPropertyMember())))
                     found = true;
                  else
                     mapper = null;
                  break;
               case SetIndexed:
                  if ((mapper = PTypeUtil.getPropertyMapping(classDecl, name)) != null && mapper instanceof IBeanIndexMapper &&
                          ((IBeanIndexMapper) mapper).hasIndexedSetterMethod() && (refType == null || checkAccess(refType, mapper.getPropertyMember())))
                     found = true;
                  else
                     mapper = null;
                  break;
            }
            if (found && mapper != null) {
               return convertMapperToSrc(type, mapper, name, mtypes, refType, ctx, sys);
            }
         }
      }
      return null;
   }

   public static Object convertMapperToSrc(Object origType, IBeanMapper mapper, String name, EnumSet<JavaSemanticNode.MemberType> mtypes, Object refType, TypeContext ctx, LayeredSystem sys) {
      if (sys == null)
         return mapper;
      Object enclType = getEnclosingType(mapper.getPropertyMember());
      // Skipping ComponentImpl here because it's a special class used to hold the _initState field... if we resolve it here during transform, it might load it after it's been started and at least
      if (enclType != null && enclType != ComponentImpl.class) {
         Object srcClass = resolveSrcTypeDeclaration(sys, enclType);
         if (srcClass instanceof BodyTypeDeclaration && srcClass != enclType && srcClass != origType) {
            BodyTypeDeclaration srcType = (BodyTypeDeclaration) srcClass;
            // We found the member on a compiled class and there's a src type that's annotating that class.  See if the class declares a non-compiled member for the one we found and if so return that instead.
            Object newMember = srcType.declaresMember(name, mtypes, refType, ctx);
            if (newMember != null && newMember != mapper) {
               return newMember;
            }
         }
      }
      return mapper;
   }

   public static boolean isTypeInLayer(Object type, Layer layer) {
      if (type instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration td = (BodyTypeDeclaration) type;
         do {
            Layer typeLayer = td.getLayer();
            if (typeLayer == layer)
               return true;

            BodyTypeDeclaration nextType = td.getModifiedType();
            if (nextType == null)
               return false;
            td = nextType;
         } while (true);
      }
      return false;
   }

   public static Object getInheritedAnnotation(LayeredSystem system, Object superType, String annotationName) {
      return getInheritedAnnotation(system, superType, annotationName, false);
   }

   public static Object getInheritedAnnotationValue(LayeredSystem system, Object type, String annotName, String attributeName) {
      // Search the class hierarchy for all TypeSettings annotations and if any of them declare this property bindable at the type level
      // we treat it as bindable.
      ArrayList<Object> allAnnots = ModelUtil.getAllInheritedAnnotations(system, type, annotName, false, null, false);
      if (allAnnots != null) {
         for (Object annot:allAnnots) {
            Object annotVal = ModelUtil.getAnnotationValue(annot, attributeName);
            if (annotVal != null) {
               return annotVal;
            }
         }
      }
      return null;
   }

   public static Object getInheritedAnnotation(LayeredSystem system, Object superType, String annotationName, boolean skipCompiled) {
      return getInheritedAnnotation(system, superType, annotationName, skipCompiled, null, false);
   }

   public static ArrayList<Object> getAllInheritedAnnotations(LayeredSystem system, Object superType, String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      if (superType instanceof ITypeDeclaration)
         return ((ITypeDeclaration) superType).getAllInheritedAnnotations(annotationName, skipCompiled, refLayer, layerResolve);
      else if (superType instanceof IBeanMapper || ModelUtil.isMethod(superType) ||
              ModelUtil.isField(superType) || superType instanceof PropertyAssignment || superType instanceof AssignmentExpression) {
         ArrayList<Object> res = null;
         Object thisRes = getInheritedAnnotation(system, superType, annotationName, skipCompiled, refLayer, layerResolve);
         if (thisRes != null) {
            res = new ArrayList<Object>();
            res.add(thisRes);
         }
         Object enclType = getEnclosingType(superType);
         if (enclType != null) {
            ArrayList<Object> typeRes = getAllInheritedAnnotations(system, enclType, annotationName, skipCompiled, refLayer, layerResolve);
            if (typeRes != null) {
               if (res == null)
                  res = typeRes;
               else
                  res.addAll(typeRes);
            }
         }
         return res;
      }
      else {
         ArrayList<Object> res = null;
         Class superClass = (Class) superType;
         Class annotationClass = RDynUtil.loadClass(annotationName);
         if (annotationClass == null) {
            annotationClass = RDynUtil.loadClass("sc.obj." + annotationName);
            if (annotationClass != null)
               System.err.println("*** Remove old non-qualified annotation reference: " + annotationName);
         }

         // TODO: fix annotation type name resolution problems
         if (annotationClass == null) {
            // Assuming layer dependencies are correct, compiled classes can't have non-compiled annotations so just return null (e.g. MainInit on a compiled class)
            return null;
         }
         while (superClass != null) {
            java.lang.annotation.Annotation jlannot = superClass.getAnnotation(annotationClass);
            if (jlannot != null) {
               if (res == null)
                  res = new ArrayList<Object>();
               res.add(jlannot);
            }

            // As we walk up the type hierarchy looking for annotations we need to see if there is
            // a source version for any of these types.  That way, you can add annotations to a type in
            // a modified layer without modifying every class that implements that type.
            Class next = superClass.getSuperclass();
            if (next != null) {
               Object nextType = findTypeDeclaration(system, next.getName(), refLayer, layerResolve);
               if (nextType != null && nextType instanceof TypeDeclaration) {
                  if (nextType == superType) {
                     System.err.println("*** Loop in inheritance tree: " + next.getName());
                     return null;
                  }
                  ArrayList<Object> newRes = ((TypeDeclaration) nextType).getAllInheritedAnnotations(annotationName, skipCompiled, refLayer, layerResolve);
                  if (newRes != null)
                     res = appendLists(res, newRes);
               }
            }
            Class[] ifaces = superClass.getInterfaces();
            for (Class iface:ifaces) {
               Object nextIFace = findTypeDeclaration(system, iface.getName(), refLayer, layerResolve);
               if (nextIFace != null && nextIFace instanceof TypeDeclaration) {
                   ArrayList<Object> newRes = ((TypeDeclaration) nextIFace).getAllInheritedAnnotations(annotationName, skipCompiled, refLayer, layerResolve);
                  res = appendLists(res, newRes);
               }
            }
            superClass = next;
         }
         return res;
      }
   }

   public static ArrayList<Object> appendLists(ArrayList<Object> origList, ArrayList<Object> newList) {
      if (origList == null)
         return newList;
      if (newList == null)
         return origList;
      ArrayList<Object> res = new ArrayList<Object>(origList.size() + newList.size());
      res.addAll(origList);
      res.addAll(newList);
      return res;
   }

   public static Object getInheritedAnnotation(LayeredSystem system, Object superType, String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      if (superType instanceof ITypeDeclaration)
         return ((ITypeDeclaration) superType).getInheritedAnnotation(annotationName, skipCompiled, refLayer, layerResolve);
      else if (superType instanceof IBeanMapper || ModelUtil.isMethod(superType) ||
               ModelUtil.isField(superType) || superType instanceof PropertyAssignment || superType instanceof AssignmentExpression) {
         return getAnnotation(superType, annotationName);
      }
      else if (superType instanceof Class) {
         Class superClass = (Class) superType;
         Class annotationClass = RDynUtil.loadClass(annotationName);
         if (annotationClass == null) {
            // TODO: is this still needed?  should remove
            annotationClass = RDynUtil.loadClass("sc.obj." + annotationName);
         }

         // TODO: fix annotation type name resolution problems
         if (annotationClass == null) {
            // Assuming layer dependencies are correct, compiled classes can't have non-compiled annotations so just return null (e.g. MainInit on a compiled class)
            return null;
         }
         while (superClass != null) {
            java.lang.annotation.Annotation jlannot = superClass.getAnnotation(annotationClass);
            if (jlannot != null)
               return jlannot;

            // As we walk up the type hierarchy looking for annotations we need to see if there is
            // a source version for any of these types.  That way, you can add annotations to a type in
            // a modified layer without modifying every class that implements that type.
            Class next = superClass.getSuperclass();
            if (next != null && refLayer != null) {
               Object nextType = findTypeDeclaration(system, next.getName(), refLayer, layerResolve);
               if (nextType != null) {
                  if (nextType == superType) {
                     System.err.println("*** Loop in inheritance tree: " + next.getName());
                     return null;
                  }
                  if (nextType instanceof TypeDeclaration) {
                     return ((TypeDeclaration) nextType).getInheritedAnnotation(annotationName, skipCompiled, refLayer, layerResolve);
                  }
                  else if (!skipCompiled) {
                     return getInheritedAnnotation(system, nextType, annotationName, skipCompiled, refLayer, layerResolve);
                  }
               }
            }
            Class[] ifaces = superClass.getInterfaces();
            for (Class iface:ifaces) {
               Object nextIface = findTypeDeclaration(system, iface.getName(), refLayer, layerResolve);
               if (nextIface != null) {
                  Object annotRes = null;
                  if (nextIface instanceof TypeDeclaration) {
                     annotRes = ((TypeDeclaration) nextIface).getInheritedAnnotation(annotationName, skipCompiled, refLayer, layerResolve);
                  }
                  else if (!skipCompiled) {
                     annotRes = getInheritedAnnotation(system, nextIface, annotationName, skipCompiled, refLayer, layerResolve);
                  }
                  if (annotRes != null)
                     return annotRes;
               }
            }
            superClass = next;
         }
      }
      else if (superType instanceof VariableDefinition) {
         Definition def = ((VariableDefinition) superType).getDefinition();
         return getAnnotation(def, annotationName);
      }
      else if (superType != null) {
         System.err.println("*** Unrecognized type in getInheritedAnnotation: " + superType);
      }
      return null;
   }

   /** Similar to DynUtil.findType, but if there are any source type declarations availble, that is returned first */
   public static Object findType(LayeredSystem sys, String typeName) {
      Object res = findTypeDeclaration(sys, typeName, null, false);
      if (res != null)
         return res;
      return DynUtil.findType(typeName);
   }

   public static Object findTypeDeclaration(LayeredSystem sys, String typeName, Layer refLayer, boolean layerResolve) {
      if (sys == null)
         sys = LayeredSystem.getCurrent();
      if (sys == null)
         return DynUtil.findType(typeName);
      return sys.getTypeDeclaration(typeName, false, refLayer, layerResolve);
   }

   public static Object findTypeDeclaration(LayeredSystem sys, Object baseType, String typeName, Layer refLayer, boolean addExternalReference) {
      if (sys == null)
         sys = LayeredSystem.getCurrent();
      if (baseType instanceof ITypeDeclaration)
         return ((ITypeDeclaration) baseType).findTypeDeclaration(typeName, addExternalReference);
      Object innerType = ModelUtil.getInnerType(baseType, typeName, null);
      if (innerType != null)
         return innerType;
      if (sys != null)
         return sys.getTypeDeclaration(typeName, false, refLayer, false);
      return null;
   }

   public static Object resolveSrcTypeDeclaration(LayeredSystem sys, Object type) {
      return resolveSrcTypeDeclaration(sys, type, false, true, null);
   }

   /**
    * In some cases, you might have a class which corresponds to an annotation layer or something.  Use this
    * method to ensure you get the most specific src type for that class name.  If cachedOnly is true, we
    * do not load the src file just to retrieve the src description.  If you are using the runtime descriptions of the type
    * this can be a lot faster.  But if you have loaded the src, it's best to use the most accurate type.
    */
   public static Object resolveSrcTypeDeclaration(LayeredSystem sys, Object type, boolean cachedOnly, boolean srcOnly, Layer refLayer) {
      if (type instanceof ParamTypeDeclaration)
         type = ((ParamTypeDeclaration) type).getBaseType();
      if (ModelUtil.isCompiledClass(type) || type instanceof ParameterizedType) {
         String typeName = ModelUtil.getTypeName(type);
         if (sys != null) {
            Object res = cachedOnly ? sys.getCachedTypeDeclaration(typeName, null, null, false, true, refLayer, true) :
                                      sys.getSrcTypeDeclaration(typeName, null, true, false, srcOnly, refLayer, false);
            if (res != null)
               return res;
         }
      }
      return type;
   }

   /** For modify operations, returns the modified type declaration, i.e. with the same type name */
   public static Object getSuperclass(Object type) {
      if (type instanceof Class)
         return ((Class) type).getSuperclass();
      else if (type instanceof ITypeDeclaration)
         return ((ITypeDeclaration) type).getDerivedTypeDeclaration();
      else if (type instanceof ParameterizedType)
         return getSuperclass(ModelUtil.getParamTypeBaseType(type));
      else
         throw new UnsupportedOperationException();
   }

   /** For modify operations, returns the actual super class or extends class of the current type, i.e. never the same type name */
   public static Object getExtendsClass(Object type) {
      if (type instanceof Class)
         return ((Class) type).getSuperclass();
      else if (type instanceof ITypeDeclaration)
         return ((ITypeDeclaration) type).getExtendsTypeDeclaration();
      else if (type instanceof ParameterizedType)
         return getExtendsClass(ModelUtil.getParamTypeBaseType(type));
      else
         throw new UnsupportedOperationException();
   }

   /** For modify types, if it's modify inherited it returns the modified type, since that's really a new type in the compiled world. */
   public static Object getCompiledExtendsTypeDeclaration(Object type) {
      if (type instanceof Class)
         return ((Class) type).getSuperclass();
      else if (type instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) type).getCompiledExtendsTypeDeclaration();
      else
         throw new UnsupportedOperationException();
   }

   public static String getExtendsTypeName(Object type) {
      Object ext = getExtendsClass(type);
      return ext == null ? null : ModelUtil.getTypeName(ext);
   }

   public static Object getExtendsJavaType(Object type) {
      if (type instanceof Class)
         return ((Class) type).getGenericSuperclass();
      else if (type instanceof ITypeDeclaration)
         return ((ITypeDeclaration) type).getExtendsType();
      else if (type instanceof ParameterizedType)
         return getExtendsJavaType(ModelUtil.getParamTypeBaseType(type));
      else
         throw new UnsupportedOperationException();
   }

   public static Object[] getImplementsJavaTypes(Object type) {
      if (type instanceof Class)
         return ((Class) type).getGenericInterfaces();
      else if (type instanceof ITypeDeclaration) {
         List<?> res = ((ITypeDeclaration) type).getImplementsTypes();
         return res == null ? null : res.toArray();
      }
      else if (type instanceof ParameterizedType)
         return getImplementsJavaTypes(ModelUtil.getParamTypeBaseType(type));
      else
         throw new UnsupportedOperationException();
   }

   public static Object[] getCompiledImplTypes(Object type) {
      if (type instanceof Class)
         return ((Class) type).getInterfaces();
      else if (type instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) type).getCompiledImplTypes();
      else
         throw new UnsupportedOperationException();
   }

   public static Object[] getCompiledImplJavaTypes(Object type) {
      if (type instanceof Class)
         return ((Class) type).getGenericInterfaces();
      else if (type instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) type).getCompiledImplJavaTypes();
      else
         throw new UnsupportedOperationException();
   }

   public static String getOperator(Object fieldDef) {
      if (fieldDef instanceof VariableDefinition)
         return ((VariableDefinition) fieldDef).operator;
      if (fieldDef instanceof IBeanMapper)
         return "=";
      if (fieldDef instanceof Field)
         return "=";
      if (fieldDef instanceof IMethodDefinition)
         return "=";
      else if (fieldDef instanceof PropertyAssignment)
         return ((PropertyAssignment) fieldDef).operator;
      else if (fieldDef instanceof ParamTypedMember)
         return getOperator(((ParamTypedMember) fieldDef).getMemberObject());
      else if (fieldDef instanceof Method) {
         return "=";
      }
      else if (fieldDef instanceof EnumConstant) {
         return "=";
      }
      throw new UnsupportedOperationException("Invalid property type: " + fieldDef);
   }

   public static String getPropertyName(Object fieldDef) {
      if (fieldDef instanceof VariableDefinition)
         return ((VariableDefinition) fieldDef).variableName;
      if (fieldDef instanceof IBeanMapper)
         return ((IBeanMapper) fieldDef).getPropertyName();
      if (fieldDef instanceof Field)
         return ((Field) fieldDef).getName();
      if (fieldDef instanceof IMethodDefinition)
         return ((IMethodDefinition) fieldDef).getPropertyName();
      if (fieldDef instanceof IFieldDefinition)
         return ((IFieldDefinition) fieldDef).getFieldName();
      else if (fieldDef instanceof PropertyAssignment)
         return ((PropertyAssignment) fieldDef).propertyName;
      else if (fieldDef instanceof ParamTypedMember)
         return getPropertyName(((ParamTypedMember) fieldDef).getMemberObject());
      else if (fieldDef instanceof TypeDeclaration) {
         TypeDeclaration type = ((TypeDeclaration) fieldDef);
         String typeName = type.getTypeClassName();
         if (!(type.isEnumConstant()))
            return CTypeUtil.decapitalizePropertyName(typeName);
         return typeName;
      }
      else if (fieldDef instanceof Method) {
         return RTypeUtil.getPropertyNameFromSelector(fieldDef);
      }
      else if (fieldDef instanceof EnumConstant) {
         return ((EnumConstant) fieldDef).getTypeName();
      }
      // In the UI we use property name to refer to classes as well
      else if (fieldDef instanceof Class)
         return CTypeUtil.getClassName(((Class) fieldDef).getName());
      throw new UnsupportedOperationException("Invalid property type: " + fieldDef);
   }

   /** Returns true for a Java field which has the same name as one of its methods - used for JS.  Only works for source types currently. */
   public static boolean isFieldShadowedByMethod(Object obj) {
      if (obj instanceof ParamTypedMember) {
         obj = ((ParamTypedMember) obj).member;
      }
      if (obj instanceof VariableDefinition) {
         VariableDefinition varDef = (VariableDefinition) obj;
         // TODO: Not sure why this was not validated yet at this point during an incremental compile but this is the easiest fix
         varDef.ensureValidated();
         return varDef.shadowedByMethod;
      }
      return false;
   }

   public static Layer getLayerForMember(LayeredSystem sys, Object member) {
      return getLayerForType(sys, getEnclosingType(member));
   }

   public static Layer getLayerForType(LayeredSystem sys, Object type) {
      if (type instanceof ITypeDeclaration)
         return ((ITypeDeclaration) type).getLayer();
      if (sys != null && type instanceof Class) {
         SrcEntry srcEnt = sys.getSrcFileFromTypeName(ModelUtil.getTypeName(type), true, null, true, null);
         if (srcEnt != null)
            return srcEnt.layer;
      }
      return null;
   }

   public static Layer getPropertyLayer(Object propDef) {
      if (propDef instanceof VariableDefinition)
         return ((VariableDefinition) propDef).getEnclosingType().getLayer();
      if (propDef instanceof Statement) {
         return ((Statement) propDef).getEnclosingType().getLayer();
      }
      if (propDef instanceof MethodDefinition)
         return ((MethodDefinition) propDef).getEnclosingType().getLayer();
      if (propDef instanceof IBeanMapper)
         return getPropertyLayer(((IBeanMapper) propDef).getPropertyMember());
      if (propDef instanceof ParamTypedMember)
         return getPropertyLayer(((ParamTypedMember) propDef).getMemberObject());
      return null;
   }

   public static boolean isStringOrChar(Object typeDeclaration) {
      return ModelUtil.isAssignableFrom(String.class, typeDeclaration) ||
             ModelUtil.isAssignableFrom(Character.class, typeDeclaration);
   }

   public static boolean isCompiledClass(Object type) {
      return type instanceof CFClass || type instanceof Class;
   }

   /** Returns true for EnumConstants */
   public static boolean isEnum(Object varObj) {
      if (varObj instanceof ClientTypeDeclaration)
         varObj = ((ClientTypeDeclaration) varObj).getOriginal();
      if (varObj instanceof EnumConstant)
         return true;
      if (varObj instanceof CFField && ((CFField) varObj).isEnumConstant())
         return true;
      if (varObj instanceof ModifyDeclaration && ((ModifyDeclaration) varObj).isEnumConstant())
         return true;
      if (varObj instanceof DynEnumConstant)
         return true;
      if (varObj instanceof Field)
         return ((Field) varObj).isEnumConstant();
      if (varObj instanceof IBeanMapper) {
         Object field = ((IBeanMapper) varObj).getField();
         return field != null && isEnum(field);
      }
      return (varObj instanceof java.lang.Enum);
   }

   public static String getEnumConstantName(Object enumConst) {
      if (enumConst instanceof java.lang.Enum)
         return ((java.lang.Enum) enumConst).name();
      if (enumConst instanceof EnumConstant)
         return ((EnumConstant) enumConst).typeName;
      else if (enumConst instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) enumConst).getTypeName(); // Modify of an enum constant
      }
      else if (enumConst instanceof CFField)
         return ((CFField) enumConst).getFieldName();
      else if (enumConst instanceof DynEnumConstant)
         return ((DynEnumConstant) enumConst).name();
      else if (enumConst instanceof Field)
         return ((Field) enumConst).getName();
      else if (enumConst instanceof IBeanMapper) {
         Object field = ((IBeanMapper) enumConst).getField();
         if (field != null)
            return ModelUtil.getPropertyName(field);
      }
      throw new IllegalArgumentException("getEnumConstantName: Not an enum constant");
   }

   public static boolean isInterface(Object obj) {
      if (obj instanceof Class)
         return ((Class) obj).isInterface();
      else if (obj instanceof CFClass)
         return ((CFClass) obj).isInterface();
      else if (obj instanceof ParameterizedType) {
         return ModelUtil.isInterface(((ParameterizedType) obj).getRawType());
      }
      else
         return obj instanceof ITypeDeclaration && ((ITypeDeclaration) obj).getDeclarationType() == DeclarationType.INTERFACE;
   }

   public static boolean isAnnotation(Object obj) {
      if (obj instanceof Class)
         return ((Class) obj).isAnnotation();
      else if (obj instanceof CFClass)
         return ((CFClass) obj).isAnnotation();
      return obj instanceof AnnotationTypeDeclaration;
   }

   public static boolean isEnumType(Object varObj) {
      if (varObj instanceof ClientTypeDeclaration)
         varObj = ((ClientTypeDeclaration) varObj).getOriginal();
      return varObj instanceof EnumDeclaration || ((varObj instanceof Class) && ((Class) varObj).isEnum()) || ((varObj instanceof ITypeDeclaration) && ((ITypeDeclaration) varObj).isEnumeratedType()) ||
             ((varObj instanceof CFClass) && ((CFClass) varObj).isEnum()) || (varObj instanceof ModifyDeclaration && isEnumType(((ModifyDeclaration) varObj).getModifiedType()));
   }

   public static Object[] getEnumConstants(Object enumType) {
      if (enumType instanceof ClientTypeDeclaration)
         enumType = ((ClientTypeDeclaration) enumType).getOriginal();
      if (enumType instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) enumType).getEnumValues();
      return DynUtil.getEnumConstants(enumType);
   }

   public static Object getEnumTypeFromEnum(Object enumObj) {
      if (enumObj instanceof EnumConstant) {
         // The type of the EnumConstant is the enum itself, not the constant
         return ((EnumConstant) enumObj).getEnclosingType();
      }
      if (enumObj instanceof CFField)
         return ((CFField) enumObj).getEnclosingIType();
      if (enumObj instanceof java.lang.Enum)
         return enumObj.getClass(); // TODO: this is a bit funky here... this returns the class for the specific enum constant wheras most of these return the parent enum type.  Should we use getDeclaringClass() to be more consistent?  But at least some callers, like identifier expression, seem to really need the type corresponding to the enum constant.
      if (enumObj instanceof EnumDeclaration) {
         return enumObj;
      }
      if (enumObj instanceof Field) {
         return ((Field) enumObj).getGenericType();
      }
      if (enumObj instanceof IBeanMapper)
         return ((IBeanMapper) enumObj).getGenericType();
      if (enumObj instanceof ModifyDeclaration) {
         ModifyDeclaration enumMod = (ModifyDeclaration) enumObj;
         if (enumMod.isEnumConstant())
            return enumMod.getEnclosingIType();
         System.err.println("*** Invalid type for enum");
         return null;
      }
      else
         return null;
   }

   public static String getMethodName(Object methObj) {
      if (methObj instanceof Method)
         return ((Method) methObj).getName();
      else if (methObj instanceof IMethodDefinition)
         return ((IMethodDefinition) methObj).getMethodName();
      // For constructors, we use just the class name part of the whole type name since methods cannot have that name
      else if (methObj instanceof Constructor)
         return CTypeUtil.getClassName(((Constructor) methObj).getName());
      else
         throw new UnsupportedOperationException();
   }

   public static int getNumParameters(Object methObj) {
      if (methObj instanceof Method)
         return ((Method) methObj).getParameterCount();
      else if (methObj instanceof Constructor)
         return ((Constructor) methObj).getParameterCount();
      else if (methObj instanceof IMethodDefinition)
         return ((IMethodDefinition) methObj).getNumParameters();
      else
         throw new UnsupportedOperationException();
   }

   public static String getThrowsClause(Object methObj) {
      if (methObj instanceof Constructor)
         return typesToThrowsClause(((Constructor) methObj).getExceptionTypes());
      else if (methObj instanceof Method) {
         return typesToThrowsClause(((Method) methObj).getExceptionTypes());
      }
      else if (methObj instanceof IMethodDefinition)
         return ((IMethodDefinition) methObj).getThrowsClause();
      // For constructors
      else if (methObj instanceof AbstractMethodDefinition)
         return ((AbstractMethodDefinition) methObj).getThrowsClause();
      else
         throw new UnsupportedOperationException();
   }

   public static IBeanMapper[] getPropertyMappers(Object typeObj) {
      if (typeObj instanceof Class) {
         return TypeUtil.getProperties((Class) typeObj, null);
      }
      else if (typeObj instanceof ITypeDeclaration) {
         DynType cache = ((ITypeDeclaration) typeObj).getPropertyCache();
         return cache.getPropertyList();
      }
      throw new UnsupportedOperationException();
   }

   public static IBeanMapper[] getStaticPropertyMappers(Object typeObj) {
      if (typeObj instanceof Class) {
         return TypeUtil.getStaticProperties((Class) typeObj);
      }
      else if (typeObj instanceof ITypeDeclaration) {
         DynType cache = ((ITypeDeclaration) typeObj).getPropertyCache();
         return cache.getStaticPropertyList();
      }
      throw new UnsupportedOperationException();
   }

   public static Object[] getDeclaredMergedPropertiesAndTypes(Object type, String modifier, boolean includeModified) {
      Object[] types = getAllInnerTypes(type, modifier, true, true);
      Object[] props = getDeclaredMergedProperties(type, modifier, includeModified);
      return mergeTypesAndProperties(type, types, props);
   }

   public static Object[] getMergedPropertiesAndTypes(Object type, String modifier, LayeredSystem sys) {
      Object[] types = getAllInnerTypes(type, modifier, false, false);
      Object[] props = getMergedProperties(type, modifier);
      return mergeTypesAndProperties(type, types, props);
   }

   /** Note: This merges the types but does not merge the properties.  Assumes that's been done in getMerged/DeclaredProperties */
   public static Object[] mergeTypesAndProperties(Object type, Object[] types, Object[] props) {
      ArrayList<Object> res = new ArrayList<Object>();
      if (props != null) {
         for (int i = 0; i < props.length; i++) {
            Object prop = props[i];
            if (prop instanceof Class || prop instanceof BodyTypeDeclaration)
               continue; // Objects already included above
            res.add(props[i]);
         }
      }
      if (types != null) {
         for (int i = 0; i < types.length; i++) {
            Object innerType = types[i];
            String innerTypeName = ModelUtil.getClassName(innerType);
            // If it's a compiled anonymous inner type, nothing we can do for it.
            if (Character.isDigit(innerTypeName.charAt(0)))
                 continue;
            Object mergedType = ModelUtil.getInnerType(type, innerTypeName, null);
            if (mergedType == null) {
               // Some inconsistency in how enum constants are treated here...
               mergedType = ModelUtil.getEnum(type, innerTypeName);
               if (mergedType == null)
                  System.err.println("*** can't find merged type!");
            }
            else
               types[i] = mergedType;
            res.add(types[i]);
         }
      }
      return res.toArray(new Object[res.size()]);
   }

   public static Object[] getDeclaredMergedProperties(Object type, String modifier, boolean includeModified) {
      Object[] props = getDeclaredProperties(type, modifier, true, includeModified, true);
      if (props == null)
         return new Object[0];
      for (int i = 0; i < props.length; i++) {
         Object prop = props[i];
         if (prop != null) {
            String propName = ModelUtil.getPropertyName(prop);
            Object def = ModelUtil.definesMember(type, propName, JavaSemanticNode.MemberType.AllSet, null, null, null);
            if (def == null) {
               System.err.println("*** Can't resolve merged property");
               def = ModelUtil.definesMember(type, propName, JavaSemanticNode.MemberType.AllSet, null, null, null);
            }
            // Reverse bindings do not replace the previous definition so don't let them do that here
            else if (!ModelUtil.isReverseBinding(def))
               props[i] = def;
         }
      }
      return props;
   }

   public static boolean isReverseBinding(Object def) {
      if (!(def instanceof PropertyAssignment))
         return false;

      PropertyAssignment pa = (PropertyAssignment) def;
      return pa.bindingDirection != null && !pa.bindingDirection.doForward() && pa.bindingDirection.doReverse();
   }

   public static Object[] getMergedProperties(Object type, String modifier) {
      Object[] props = getProperties(type, modifier);
      if (props != null) {
         for (int i = 0; i < props.length; i++) {
            Object prop = props[i];
            if (prop != null) {
               String propName = ModelUtil.getPropertyName(prop);
               Object def = ModelUtil.definesMember(type, propName, JavaSemanticNode.MemberType.AllSet, null, null, null);
               if (def == null) {
                  System.err.println("*** Can't resolve merged property: " + propName + " in type: " + ModelUtil.getTypeName(type));
                  def = ModelUtil.definesMember(type, propName, JavaSemanticNode.MemberType.AllSet, null, null, null);
               }
               else if (!ModelUtil.isReverseBinding(def))
                  props[i] = def;
            }
         }
      }
      return props;
   }

   public static Object[] getProperties(Object typeObj, String modifier) {
      return getProperties(typeObj, modifier, false);
   }

   public static Object[] getProperties(Object typeObj, String modifier, boolean includeAssigns) {
      if (typeObj instanceof Class) {
        // Need to convert this to an Object[] from an IBeanMapper[] so that it can be modified downstream
         return new ArrayList<Object>(Arrays.asList(TypeUtil.getProperties((Class) typeObj, modifier))).toArray();
      }
      else if (typeObj instanceof ITypeDeclaration) {
         List<Object> props = ((ITypeDeclaration) typeObj).getAllProperties(modifier, includeAssigns);
         return props == null ? null : props.toArray(new Object[props.size()]);
      }
      else if (typeObj instanceof TypeParameter)
         return null;
      throw new UnsupportedOperationException();
   }

   public static Object[] getDeclaredProperties(Object typeObj, String modifier, boolean includeAssigns, boolean includeModified, boolean editorProperties) {
      if (typeObj instanceof ParamTypeDeclaration)
         typeObj = ((ParamTypeDeclaration) typeObj).getBaseType();
      if (typeObj instanceof Class) {
         return RTypeUtil.getDeclaredProperties((Class) typeObj, modifier);
      }
      else if (typeObj instanceof BodyTypeDeclaration) {
         List<Object> props = ((BodyTypeDeclaration) typeObj).getDeclaredProperties(modifier, includeAssigns, includeModified, editorProperties);
         return props == null ? null : props.toArray(new Object[props.size()]);
      }
      else if (typeObj instanceof ITypeDeclaration) {
         List<Object> props = ((ITypeDeclaration) typeObj).getDeclaredProperties(modifier, includeAssigns, includeModified, editorProperties);
         return props == null ? null : props.toArray(new Object[props.size()]);
      }
      throw new UnsupportedOperationException();
   }

   public static Object[] getDeclaredPropertiesAndTypes(Object typeObj, String modifier, LayeredSystem sys) {
      if (ModelUtil.hasTypeParameters(typeObj))
         typeObj = getParamTypeBaseType(typeObj);
      if (ModelUtil.isGenericArray(typeObj) || typeObj instanceof ArrayTypeDeclaration)
         typeObj = ModelUtil.getGenericComponentType(typeObj);

      Object[] types = ModelUtil.getAllInnerTypes(typeObj, modifier, true, true);
      ArrayList<Object> res = new ArrayList<Object>();
      if (typeObj instanceof Class) {
         Object[] props = RTypeUtil.getDeclaredProperties((Class) typeObj, modifier);
         if (props != null) {
            for (int i = 0; i < props.length; i++) {
               Object prop = props[i];
               if (prop instanceof Class)
                  continue;
               if (sys != null && prop instanceof IBeanMapper) {
                  IBeanMapper mapper = (IBeanMapper) prop;
                  prop = convertMapperToSrc(typeObj, mapper, mapper.getPropertyName(), JavaSemanticNode.MemberType.PropertyAnySet, null, null, sys);
               }
               res.add(prop);
            }
         }
      }
      else if (typeObj instanceof BodyTypeDeclaration) {
         List<Object> props = ((BodyTypeDeclaration) typeObj).getDeclaredProperties(modifier, true, false, true);
         if (props != null) {
            for (int i = 0; i < props.size(); i++) {
               Object prop = props.get(i);
               if (prop instanceof BodyTypeDeclaration)
                  continue;
               res.add(prop);
            }
         }
      }
      else
         throw new UnsupportedOperationException();
      if (types != null)
         res.addAll(Arrays.asList(types));
      return res.toArray();
   }

   public static Object[] getPropertiesAndTypes(Object typeObj, String modifier) {
      ArrayList<Object> res = new ArrayList();
      if (typeObj instanceof Class) {
         Object[] props = TypeUtil.getProperties((Class) typeObj, modifier);
         if (props != null) {
            for (int i = 0; i < props.length; i++) {
               Object prop = props[i];
               if (prop instanceof Class)
                  continue;
               /* Eliminate write-only properties? For now, keeping the in the list
               if (!ModelUtil.isReadableProperty(prop))
                  continue; */
               res.add(prop);
            }
         }
      }
      else if (typeObj instanceof ITypeDeclaration) {
         List<Object> props = ((ITypeDeclaration) typeObj).getAllProperties(modifier, true);
         if (props != null) {
            for (int i = 0; i < props.size(); i++) {
               Object prop = props.get(i);
               if (prop instanceof BodyTypeDeclaration)
                  continue;
               //if (!ModelUtil.isReadableProperty(prop))
               //   continue;
               res.add(prop);
            }
         }
      }
      else
         throw new UnsupportedOperationException();
      Object[] types = ModelUtil.getAllInnerTypes(typeObj, modifier, false, true);
      if (types != null)
         res.addAll(Arrays.asList(types));
      return res.toArray();
   }

   public static Object[] getFields(Object typeObj, String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified) {
      if (typeObj instanceof Class) {
         if (dynamicOnly)
            return null;
         return RTypeUtil.getFields((Class) typeObj, modifier, hasModifier);
      }
      else if (typeObj instanceof ITypeDeclaration) {
         List<Object> props = ((ITypeDeclaration) typeObj).getAllFields(modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);
         return props == null ? null : props.toArray(new Object[props.size()]);
      }
      throw new UnsupportedOperationException();
   }

   public static List removePropertiesInList(List declProps, List remProps, boolean byName) {
      if (remProps == null || declProps == null)
         return declProps;
      if (!(declProps instanceof ArrayList) && declProps.size() > 0)
         declProps = new ArrayList(declProps);
      for (int i = 0; i < declProps.size(); i++) {
         Object prop = declProps.get(i);
         if (prop == null)
            continue;
         int ix = propertyIndexOf(remProps, prop, byName);
         if (ix != -1) {
            declProps.remove(i);
            i--;
         }
      }
      return declProps;
   }

   public static List mergeProperties(List modProps, List declProps, boolean replace) {
      return mergeProperties(modProps, declProps, replace, false);
   }

   /** Merges property lists, modifying the first argument, adding any new entries, replacing entries in declProps */
   public static List mergeProperties(List modProps, List declProps, boolean replace, boolean includeAssigns) {
      if (modProps == null)
         return declProps;
      if (declProps == null)
         return modProps;
      if (!(modProps instanceof ArrayList) && declProps.size() > 0)
         modProps = new ArrayList(modProps);
      for (int i = 0; i < declProps.size(); i++) {
         Object prop = declProps.get(i);
         if (prop == null)
            continue;
         if (includeAssigns && isReverseBinding(prop))
            modProps.add(prop);
         else {
            int ix = propertyIndexOf(modProps, prop, true);
            if (ix == -1)
               modProps.add(prop);
            else if (replace)
               modProps.set(ix, prop);
            else {
               // Get method overrides the field
               Object modProp = modProps.get(ix);
               if (ModelUtil.isGetMethod(prop) && ModelUtil.isField(modProp))
                  modProps.set(ix, prop);
            }
         }
      }
      return modProps;
   }

   public static int propertyIndexOf(List props, Object prop, boolean byName) {
      if (props == null || prop == null)
         return -1;
      for (int i = 0; i < props.size(); i++) {
         Object cprop = props.get(i);
         if (cprop == null)
            continue;
         if (byName) {
            String name = ModelUtil.getPropertyName(cprop);
            if (StringUtil.equalStrings(name, ModelUtil.getPropertyName(prop))) {
               return i;
            }
         }
         else {
            if (prop == cprop)
               return i;
         }
      }
      return -1;
   }

   public static List<Object> mergeMethods(List modMeths, List declMeths) {
      if (modMeths == null)
         return declMeths;
      if (declMeths == null)
         return modMeths;
      if (!(modMeths instanceof ArrayList) && declMeths.size() > 0)
         modMeths = new ArrayList(modMeths);
      for (int i = 0; i < declMeths.size(); i++) {
         Object prop = declMeths.get(i);
         int ix = methodIndexOf(modMeths, prop);
         if (ix == -1) {
            modMeths.add(prop);
         }
         else {
            modMeths.set(ix, prop);
         }
      }
      return modMeths;
   }

   public static List<Object> appendInheritedMethods(Object[] implResult, List<Object> result) {
      if (result == null) {
         result = new ArrayList<Object>();
         result.addAll(Arrays.asList(implResult));
      }
      else {
         for (Object implMeth:implResult) {
            int r;
            for (r = 0; r < result.size(); r++) {
               Object resMeth = result.get(r);
               if (ModelUtil.overridesMethod(resMeth, implMeth))
                  break;
            }
            if (r == result.size())
               result.add(implMeth);
         }
      }
      return result;
   }

   private static int methodIndexOf(List props, Object prop) {
      if (props == null)
         return -1;
      for (int i = 0; i < props.size(); i++)
         if (ModelUtil.overridesMethod(props.get(i), prop))
            return i;
      return -1;
   }

   public static List mergeInnerTypes(List modProps, List declProps) {
      if (modProps == null)
         return declProps;
      if (declProps == null)
         return modProps;
      if (!(modProps instanceof ArrayList) && declProps.size() > 0)
         modProps = new ArrayList(modProps);
      for (int i = 0; i < declProps.size(); i++) {
         Object prop = declProps.get(i);
         int ix = innerTypeIndexOf(modProps, prop);
         if (ix == -1)
            modProps.add(prop);
         else
            modProps.set(ix, prop);
      }
      return modProps;
   }

   private static int innerTypeIndexOf(List props, Object prop) {
      if (props == null)
         return -1;
      // This compares just the class name part of the type name.  That's because we'll inherit an inner type through
      // a base type which has a different full type name.  We still consider that the same type though.
      for (int i = 0; i < props.size(); i++)
         if (StringUtil.equalStrings(ModelUtil.getClassName(props.get(i)), ModelUtil.getClassName(prop)))
            return i;
      return -1;
   }

   /** Takes an array of methods, fields etc. and dumps it to standard out for debugging purposes.
    *  Semantic nodes use their language representation.  Fields, Methods, etc. are ok as they are in Java with toString.
    */
   public static String arrayToString(Object[] list) {
      if (list == null)
         return "";
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < list.length; i++) {
         Object elem = list[i];
         if (elem == null)
            continue;
         sb.append(elementToString(elem, false));
         sb.append("\n");
      }
      return sb.toString();
   }

   public static String elementWithTypeToString(Object elem, boolean baseNameOnly) {
      StringBuilder sb = new StringBuilder();
      if (elem instanceof Method) {
         sb.append("method in type: " + elementToString(ModelUtil.getEnclosingType(elem), baseNameOnly) + ": ");
      }
      else if (elem instanceof Field) {
         sb.append("field: in type: " + elementToString(ModelUtil.getEnclosingType(elem), baseNameOnly) + ": ");
      }
      else if (elem instanceof IBeanMapper) {
         IBeanMapper mapper = (IBeanMapper) elem;
         sb.append(elementWithTypeToString(mapper.getPropertyMember(), baseNameOnly));
         return sb.toString();
      }
      else if (elem instanceof BodyTypeDeclaration) {
      }
      else if (elem instanceof JavaSemanticNode) {
      }
      sb.append(elementToString(elem, baseNameOnly));
      return sb.toString();
   }

   public static String elementToString(Object elem, boolean baseNameOnly) {
      StringBuilder sb = new StringBuilder();
      // TODO: it would be nice here to show the bound type parameters expanded
      if (elem instanceof ParamTypedMethod)
         return elementToString(((ParamTypedMethod) elem).method, baseNameOnly);
      else if (elem instanceof Method) {
         Method meth = (Method) elem;
         sb.append(getTypeName(meth.getReturnType()) + " " + meth.getName() + parameterTypesToString(meth.getParameterTypes()));
      }
      else if (elem instanceof CFMethod) {
         CFMethod meth = (CFMethod) elem;
         sb.append(getTypeName(meth.getReturnType(true)) + " " + meth.getMethodName() + " (" + StringUtil.arrayToString(meth.getParameterTypes(false)) + ")");
      }
      else if (elem instanceof Field) {
         Field f = (Field) elem;
         sb.append(f.getType() + " " + f.getName());
      }
      else if (elem instanceof IBeanMapper) {
         IBeanMapper mapper = (IBeanMapper) elem;
         sb.append(ModelUtil.getTypeName(mapper.getPropertyType()) + " " + mapper.getPropertyName());
      }
      else if (elem instanceof BodyTypeDeclaration) {
         // For the typeName, include just the part specific to the layer.  i.e. strip off the package prefix.
         TypeDeclaration td = (TypeDeclaration) elem;
         Layer l = td.getLayer();
         String typeName = ModelUtil.getTypeName(elem);
         if (l != null && l.packagePrefix != null && l.packagePrefix.length() > 0) {
            if (typeName.startsWith(l.packagePrefix))
              typeName = typeName.substring(l.packagePrefix.length() + 1);
         }
         if (baseNameOnly)
            typeName = CTypeUtil.getClassName(typeName);
         BodyTypeDeclaration type = (BodyTypeDeclaration) elem;
         if (type instanceof ModifyDeclaration)
            sb.append(typeName);
         else {
            sb.append(type.getDeclarationType().keyword + " " + typeName);
         }
         Object ext = type.getExtendsTypeDeclaration();
         if (ext != null) {
            sb.append(" extends ");
            sb.append(CTypeUtil.getClassName(ModelUtil.getTypeName(ext)));
         }
      }
      else if (elem instanceof JavaSemanticNode)
         sb.append(((JavaSemanticNode) elem).toDeclarationString());
      else
         sb.append(elem);
      return sb.toString();
   }

   public static String elementName(Object elem) {
      StringBuilder sb = new StringBuilder();
      if (elem instanceof Method) {
         Method meth = (Method) elem;
         sb.append(meth.getName() + parameterTypesToString(meth.getParameterTypes()));
      }
      else if (elem instanceof Field) {
         Field f = (Field) elem;
         sb.append(f.getName());
      }
      else if (elem instanceof IBeanMapper) {
         IBeanMapper mapper = (IBeanMapper) elem;
         sb.append(mapper.getPropertyName());
      }
      else if (elem instanceof BodyTypeDeclaration) {
         // For the typeName, include just the part specific to the layer.  i.e. strip off the package prefix.
         TypeDeclaration td = (TypeDeclaration) elem;
         Layer l = td.getLayer();
         String typeName = ModelUtil.getTypeName(elem);
         if (l.packagePrefix != null) {
            if (typeName.startsWith(l.packagePrefix))
               typeName = typeName.substring(l.packagePrefix.length() + 1);
         }
         typeName = CTypeUtil.getClassName(typeName);
         sb.append(typeName);
      }
      else
         sb.append(elem);
      return sb.toString();
   }

   public static ClientTypeDeclaration getClientTypeDeclaration(Object type) {
      if (type == null)
         return null;
      if (type instanceof ClientTypeDeclaration)
         return (ClientTypeDeclaration) type;
      if (type instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) type).getClientTypeDeclaration();
      return null;
   }

   /**
    * Modifies the code for the current program model, setting the specified property called 'elem' to a text value specified.   This
    * operation is invoked when you update the initialization expression for a property, field, etc. in a program editor.
    */
   public static Object setElementValue(Object type, Object instance, Object elem, String text, boolean updateType, boolean updateInstances, boolean valueIsExpr) {
      String origText = text;
      if (elem instanceof IBeanMapper) {
         return setElementValue(type, instance, ((IBeanMapper) elem).getPropertyMember(), text, updateType, updateInstances, valueIsExpr);
      }
      if (type instanceof BodyTypeDeclaration && elem instanceof IVariableInitializer) {
         text = text.trim();
         String op;
         if (text.length() == 0) {
            op = null;
            text = "";
         }
         else if (text.startsWith("=:")) {
            op = "=:";
            text = text.substring(2).trim();
         }
         else if (text.startsWith("=")) {
            op = "=";
            text = text.substring(1).trim();
         }
         else if (text.startsWith(":=:")) {
            op = ":=:";
            text = text.substring(3).trim();
         }
         else if (text.startsWith(":=")) {
            op = ":=";
            text = text.substring(2).trim();
         }
         else {
            op = ModelUtil.getOperator(elem);
            if (op == null)
               op = "=";
         }

         if (text.endsWith(";"))
            text = text.substring(0,text.length()-1);

         if (!valueIsExpr && text.length() > 0)
            text = RTypeUtil.fromString(ModelUtil.getRuntimeType(ModelUtil.getPropertyType(elem)), text);

         BodyTypeDeclaration typeDef = (BodyTypeDeclaration) type;
         JavaModel model = typeDef.getJavaModel();

         // Need to have a parse node in place before we do the update or else we'll restore it later on without the change
         if (model.parseNode == null)
            model.restoreParseNode();

         MessageHandler handler = new MessageHandler();
         IMessageHandler oldHandler = model.getErrorHandler();
         try {
            model.setErrorHandler(handler);

            Object exprObj = parseCommandString(model.getLanguage(), text, ((JavaLanguage) model.getLanguage()).variableInitializer);
            if (exprObj == null)
               op = null;

            Expression expr = (Expression) exprObj;
            PropertyAssignment newAssign = null;

            if (updateType) {
               // Because we parsed a variableInitializer, we expect either a PropertyAssignment or VariableDefinition
               JavaSemanticNode node = (JavaSemanticNode) elem;

               // If we inherited the definition of this property from a base class, we need to create a property assignment to override that one in this type
               if (!ModelUtil.sameTypes(node.getEnclosingType(), type)) {
                  String propName = ModelUtil.getPropertyName(node);
                  if (expr == null) {
                     newAssign = OverrideAssignment.create(propName);
                  }
                  else {
                     newAssign = PropertyAssignment.create(propName, expr, op);
                  }
                  elem = newAssign;
                  typeDef.addBodyStatementIndent(newAssign);
               }
               else {
                  // Need to convert "override name = x" to "name = x"
                  if (node instanceof OverrideAssignment && expr != null) {
                     newAssign = (OverrideAssignment) node;
                     elem = PropertyAssignment.create(newAssign.propertyName, expr, op);
                     node.parentNode.replaceChild(node, elem);
                  }
                  // And convert name = x to override name when setting x = null;
                  else if (expr == null && node instanceof PropertyAssignment && !(node instanceof OverrideAssignment)) {
                     newAssign = (PropertyAssignment) node;
                     elem = OverrideAssignment.create(newAssign.propertyName);
                     node.parentNode.replaceChild(node, elem);
                  }
                  else {
                     ((IVariableInitializer) node).updateInitializer(op, expr);

                     if (node instanceof PropertyAssignment) {
                        PropertyAssignment updateAssign = (PropertyAssignment) node;
                        // If this definition was generated, also update the source statement
                        PropertyAssignment fromAssign = (PropertyAssignment) updateAssign.fromStatement;
                        if (fromAssign != null) {
                           setElementValue(type, instance, updateAssign.fromStatement, origText, true, false, valueIsExpr);
                        }
                        Attr fromAttr = updateAssign.fromAttribute;
                        if (fromAttr != null) {
                           /** Node - this is SemanticNode.setProperty with a wrapper due to the name conflict with IDynObject */
                           fromAttr.setSemanticProperty("value", op + " " + text);
                        }
                     }
                     // If this definition was generated and it was a field, need to find the original variable definition in the original field and update that
                     else if (node instanceof VariableDefinition) {
                        VariableDefinition updateVar = (VariableDefinition) node;
                        Statement updateDef = updateVar.getDefinition();
                        ISrcStatement fromStatement = updateDef.fromStatement;
                        if (fromStatement != null) {
                           if (fromStatement instanceof FieldDefinition) {
                              FieldDefinition otherField = (FieldDefinition) fromStatement;
                              for (VariableDefinition otherVar:otherField.variableDefinitions) {
                                 if (otherVar.variableName.equals(updateVar.variableName)) {
                                    setElementValue(type, instance, otherVar, origText, true, false, valueIsExpr);
                                    break;
                                 }
                              }
                           }
                        }
                     }
                  }
               }

               if (ModelUtil.isProperty(elem)) {
                  ExecutionContext ctx = new ExecutionContext(typeDef.getJavaModel());
                  if (instance != null)
                     ctx.pushCurrentObject(instance);

                  LayeredSystem sys = model.getLayeredSystem();

                  UpdateInstanceInfo info = null;
                  if (updateInstances)
                     info = sys.newUpdateInstanceInfo();
                  typeDef.updatePropertyForType((JavaSemanticNode) elem, ctx, BodyTypeDeclaration.InitInstanceType.Init, updateInstances, info);

                  // Need to rebuild here to be sure all transformed types are transformed before we try to generate the JS that updates the clients
                  //sys.rebuildSystem();

                  if (!typeDef.isDynamicType())
                     model.transformModel();

                  if (info != null)
                     info.updateInstances(ctx);

                  model.validateSavedModel(false);
                  // Notify code that this model has changed by sending a binding event.
                  model.markChanged();

                  if (handler.err != null)
                     throw new IllegalArgumentException(handler.err);
               }
               else
                  throw new UnsupportedOperationException();
            }
            else if (expr != null) {
               expr.setParentNode(typeDef.getJavaModel()); // Goal is to let the expression in this case refer to anything defined in this type
               ParseUtil.initAndStartComponent(expr);
               if (!expr.hasErrors() && ModelUtil.isProperty(elem)) {
                  ExecutionContext ctx = new ExecutionContext(typeDef.getJavaModel());
                  Object value = expr.eval(null, ctx);
                  String propName = getPropertyName(elem);
                  DynUtil.setPropertyValue(instance, propName, value);
               }
            }
            return newAssign;
         }
         finally {
            model.setErrorHandler(oldHandler);
         }
      }
      else
         throw new UnsupportedOperationException();
   }

   public static Object getPreviousDefinition(Object def) {
      if (def instanceof PropertyAssignment)
         return ((PropertyAssignment) def).getPreviousDefinition();
      else if (def instanceof VariableDefinition)
         return ((VariableDefinition) def).getPreviousDefinition();
      else if (def instanceof MethodDefinition)
         return ((MethodDefinition) def).getPreviousDefinition();
      else if (def instanceof ParamTypedMember) {
         return getPreviousDefinition(((ParamTypedMember) def).getMemberObject());
      }
      // A compiled definition
      return null;
   }

   public static Object parseCommandString(Language cmdLang, String command, Parselet start) {
      // Skip empty lines, though make sure somewhitespace gets in there
      if (command.trim().length() == 0) {
         return null;
      }

      if (!start.initialized) {
         ParseUtil.initAndStartComponent(start);
      }
      Parser p = new Parser(cmdLang, new StringReader(command));
      Object parseTree = p.parseStart(start);
      if (parseTree instanceof ParseError) {
         ParseError err = (ParseError) parseTree;
         throw new IllegalArgumentException(err.errorStringWithLineNumbers(command));
      }
      // The parser did not consume all of the input - ordinarily an error but for the command interpreter,
      // we'll just save the extra stuff for the next go around.
      else if (!p.eof || p.peekInputChar(0) != '\0') {
         throw new IllegalArgumentException("Unable to parse: " + command + " as: " + start);
      }
      return ParseUtil.nodeToSemanticValue(parseTree);
   }

   public static String elementValueString(Object elem) {
      StringBuilder sb = new StringBuilder();
      if (elem instanceof IBeanMapper) {
         IBeanMapper mapper = (IBeanMapper) elem;
         Object member = mapper.getPropertyMember();
         if (member instanceof VariableDefinition)
            return elementValueString(member);
      }
      else if (elem instanceof VariableDefinition) {
         Expression expr = ((VariableDefinition) elem).getInitializerExpr();
         if (expr != null)
            sb.append(expr.toLanguageString().trim());
      }
      else if (elem instanceof PropertyAssignment) {
         Expression expr = ((PropertyAssignment) elem).getInitializerExpr();
         if (expr != null)
            sb.append(expr.toLanguageString().trim());
      }
      return sb.toString().trim();
   }

   public static String instanceValueString(Object inst, Object value, ExecutionContext ctx) {
      if (ModelUtil.isReadableProperty(value)) {
         Object val;
         String propName = ModelUtil.getPropertyName(value);
         if (ModelUtil.hasModifier(value, "static")) {
            if (value instanceof PropertyAssignment)
               value = ((PropertyAssignment) value).getAssignedProperty();
            if (value instanceof VariableDefinition)
               val = ((VariableDefinition) value).getStaticValue(ctx);
            else if (value instanceof IBeanMapper)
               val = "";
            else
               throw new UnsupportedOperationException();
         }
         else
            val = DynUtil.getProperty(inst, propName);
         if (val == null)
            return "";
         Class valClass = val.getClass();
         if (valClass.isArray()) {
            if (valClass.getComponentType().isPrimitive()) {
               val = TypeUtil.primArrayToObjArray(val);
            }
            return StringUtil.arrayToString((Object[]) val);
         }
         return val.toString().trim();
      }
      return "";
   }

   private static String parameterTypesToString(Class[] ptypes) {
      StringBuilder sb = new StringBuilder();
      sb.append("(");
      boolean first = true;
      if (ptypes != null) {
         for (Class c:ptypes) {
            if (!first)
               sb.append(", ");
            sb.append(c.getName());
            first = false;
         }
      }
      sb.append(")");
      return sb.toString();
   }

   public static Expression getPropertyInitializer(Object extType, String name) {
      if (extType instanceof TypeDeclaration)
         return ((TypeDeclaration) extType).getPropertyInitializer(name);

      // no way to get this info for compiled models... could hack it for statics I guess?
      // this is just for diagnostics anyway.
      return null;
   }

   public static Object getRuntimeMethod(Object method) {
      if (method instanceof AbstractMethodDefinition)
         return ((AbstractMethodDefinition) method).getRuntimeMethod();
      else if (method instanceof Method)
         return (Method) method;
      else if (method instanceof CFMethod)
         return ((CFMethod) method).getRuntimeMethod();
      else if (method instanceof ParamTypedMethod)
         return getRuntimeMethod(((ParamTypedMethod) method).method);
      else {
         if (method == null)
            throw new NullPointerException();
         else
            System.err.println("**** method type is: " + method.getClass());
         throw new UnsupportedOperationException();
      }
   }

   public static Object getRuntimePropertyMapping(Object propType) {
      if (propType instanceof VariableDefinition)
         return ((VariableDefinition) propType).getRuntimePropertyMapping();
      return propType;
   }

   /** Allows $ to match . in type names */
   public static boolean typeNamesEqual(String fte, String fullTypeName) {
      if (fte == fullTypeName)
         return true;

      int fteLen;
      if ((fteLen = fte.length()) != fullTypeName.length())
         return false;

      for (int i = 0; i < fteLen; i++) {
         char c1 = fte.charAt(i);
         char c2 = fullTypeName.charAt(i);
         if (c1 != c2 && ((c1 != '.' && c1 != '$') || (c2 != '.' && c2 != '$')))
            return false;
      }
      return true;
   }

   public static boolean addCompletionCandidate(Set<String> candidates, String prospect, int max) {
      if (prospect.startsWith("_") || prospect.contains("$"))
         return true;
      candidates.add(prospect);
      if (candidates.size() >= max)
         return false;
      return true;
   }

   public static boolean suggestMembers(JavaModel model, Object type, String prefix, Set<String> candidates, boolean includeGlobals,
                                     boolean includeProps, boolean includeMethods, boolean includeClassBodyKeywords, int max) {
      if (type != null) {
         if (includeProps) {
            Object[] props = getProperties(type, null);
            if (props != null) {
               for (int i = 0; i < props.length; i++) {
                  Object prop = props[i];
                  if (prop != null) {
                     String pname = ModelUtil.getPropertyName(prop);
                     if (pname != null && pname.startsWith(prefix)) {
                        if (!addCompletionCandidate(candidates, pname, max))
                           return false;
                     }
                  }
               }
            }
         }

         if (includeMethods) {
            Object[] meths = getAllMethods(type, null, false, false, false);
            if (meths != null) {
               for (int i = 0; i < meths.length; i++) {
                  Object meth = meths[i];
                  String mname = ModelUtil.getMethodName(meth);
                  if (mname != null && mname.startsWith(prefix)) {
                     if (!addCompletionCandidate(candidates, mname + ModelUtil.getParameterString(meth), max))
                        return false;
                  }
               }
            }
         }
         Object[] types = getAllInnerTypes(type, null, false, false);
         if (types != null) {
            for (int i = 0; i < types.length; i++) {
               String mname = CTypeUtil.getClassName(ModelUtil.getTypeName(types[i]));
               if (mname != null && mname.startsWith(prefix))
                  if (!addCompletionCandidate(candidates, mname, max))
                     return false;
            }
         }
      }
      if (includeGlobals) {
         Object encType = getEnclosingType(type);

         if (encType != null) {// Include members that are visible in the namespace
            if (!suggestMembers(model, encType, prefix, candidates, true, includeProps, includeMethods, false, 20))
               return false;
         }
         else if (model != null) { // only for the root - search the global ones
            if (!model.findMatchingGlobalNames(prefix, candidates, false, max))
               return false;
         }
      }
      if (includeClassBodyKeywords && model != null && model.getLanguage() instanceof JavaLanguage) {
         List<String> keywords = ((JavaLanguage) model.getLanguage()).getClassLevelKeywords();
         for (int i = 0; i < keywords.size(); i++) {
            String keyword = keywords.get(i);
            if (keyword.startsWith(prefix))
               if (!addCompletionCandidate(candidates, keyword, max))
                  return false;
         }
      }
      return true;
   }

   public static boolean suggestTypes(JavaModel model, String prefix, String lastIdent, Set<String> candidates, boolean includeGlobals) {
      return suggestTypes(model, prefix, lastIdent, candidates, includeGlobals, false, 20);
   }

   public static boolean suggestTypes(JavaModel model, String prefix, String lastIdent, Set<String> candidates, boolean includeGlobals, boolean annotTypes, int max) {
      if (prefix == null)
         prefix = "";
      if (model == null)
         return true;
      Set<String> files = model.layeredSystem.getFilesInPackage(prefix);
      if (files != null) {
         for (String file:files) {
            if (file.startsWith(lastIdent)) {
               // Remove inner classes
               int dix = file.indexOf("$");
               int uix = file.indexOf("__");
               // We skip stub classes which have __ in the name
               if (dix == -1 && (uix == -1 || uix == 0 || uix >= file.length()-2)) {
                  candidates.add(file);
                  if (candidates.size() >= max)
                     return false;
               }
            }
         }
      }
      if (includeGlobals) {
         if (lastIdent.equals("")) {
            if (!model.findMatchingGlobalNames(prefix, candidates, annotTypes, max))
               return false;
         }
         else {
            if (!model.layeredSystem.findMatchingGlobalNames(null, model.getLayer(), lastIdent, candidates, false, false, annotTypes, max))
               return false;
         }
      }

      String absName, pkgName, baseName;
      if (lastIdent.equals("")) {
         absName = prefix;
         pkgName = prefix;
         baseName = "";
      }
      else {
         absName = CTypeUtil.prefixPath(prefix, lastIdent);
         pkgName = prefix;
         baseName = lastIdent;
      }

      if (!model.findMatchingGlobalNames(absName, pkgName, baseName, candidates, annotTypes, max))
         return false;
      return model.layeredSystem.findMatchingGlobalNames(null, model.getLayer(), absName, pkgName, baseName, candidates, false, false, annotTypes, max);
   }

   public static void suggestVariables(IBlockStatement enclBlock, String prefix, Set<String> candidates, int max) {
      List<Statement> statements = enclBlock.getBlockStatements();
      if (statements != null) {
         for (Statement st:statements) {
            if (st instanceof VariableStatement) {
               VariableStatement varSt = (VariableStatement) st;
               if (varSt.definitions != null) {
                  for (VariableDefinition varDef:varSt.definitions) {
                     String varName = varDef.variableName;
                     if (varName != null && varName.startsWith(prefix)) {
                        candidates.add(varName);
                        if (candidates.size() >= max)
                           return;
                     }
                  }
               }
            }
         }
      }
      AbstractMethodDefinition enclMethod = ((JavaSemanticNode)enclBlock).getEnclosingMethod();
      if (enclMethod != null && enclMethod.parameters != null) {
         for (Parameter param:enclMethod.parameters.getParameterList()) {
            String varName = param.variableName;
            if (varName != null && varName.startsWith(prefix)) {
               candidates.add(varName);
               if (candidates.size() >= max)
                  return;
            }
         }
      }
      enclBlock = enclBlock.getEnclosingBlockStatement();
      if (enclBlock != null)
         suggestVariables(enclBlock, prefix, candidates, max);
   }

   public static Object getReturnType(Object method, boolean boundParams) {
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).getReturnType(boundParams);
      else if (method instanceof Constructor)
         return ((Constructor) method).getDeclaringClass();
      else if (method instanceof Method) {
         Method meth = (Method) method;
         if (!boundParams)
            return meth.getGenericReturnType();
         else
            return meth.getReturnType();
      }
      else if (method instanceof DynRemoteMethod)
         return ((DynRemoteMethod) method).returnType;
      else
         throw new UnsupportedOperationException();
   }

   public static Object getReturnJavaType(Object method) {
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).getReturnJavaType();
      else if (method instanceof Constructor)
         return ((Constructor) method).getDeclaringClass();
      else if (method instanceof Method)
         return ((Method) method).getGenericReturnType();
      else
         throw new UnsupportedOperationException();
   }

   public static Object[] getParameterTypes(Object method) {
      return getParameterTypes(method, false);
   }

   public static Object[] getParameterTypes(Object method, boolean bound) {
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).getParameterTypes(bound);
      else if (method instanceof Method)
         return ((Method) method).getParameterTypes();
      else if (method instanceof Constructor)
         return ((Constructor) method).getParameterTypes();
      else
         throw new UnsupportedOperationException();
   }

   public static Object[] getGenericParameterTypes(Object method, boolean bound) {
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).getParameterTypes(bound);
      else if (method instanceof Method)
         return ((Method) method).getGenericParameterTypes();
      else if (method instanceof Constructor)
         return ((Constructor) method).getGenericParameterTypes();
      // It's a getX method for an object
      else if (method instanceof ITypeDeclaration) {
         return new Object[] {};
      }
      // It's a variable that will be turned into a getX/setX method so this must be the params of the setX method
      else if (method instanceof VariableDefinition) {
        return new Object[] {((VariableDefinition) method).getTypeDeclaration()};
      }
      else
         throw new UnsupportedOperationException();
   }

   /** Just like the above but removes the outer-instance parameter which is present in java.lang.reflect.Constructor's getParameterTypes. */
   public static Object[] getActualParameterTypes(Object method, boolean bound) {
      Object[] res = getGenericParameterTypes(method, bound);
      if (res == null)
         return null;

      if ((method instanceof Constructor) && getEnclosingInstType(getEnclosingType(method)) != null) {
         Object[] newRes = new Object[res.length-1];
         System.arraycopy(res, 1, newRes, 0, newRes.length);
         res = newRes;
      }
      return res;
   }

   public static Object[] getParameterJavaTypes(Object method, boolean convertRepeating) {
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).getParameterJavaTypes(convertRepeating);
      else if (method instanceof Method)
         return ((Method) method).getParameterTypes();
      else if (method instanceof Constructor)
         return ((Constructor) method).getParameterTypes();
      else
         throw new UnsupportedOperationException();
   }

   public static DeclarationType getDeclarationType(Object type) {
      if (type instanceof ITypeDeclaration)
         return ((ITypeDeclaration) type).getDeclarationType();
      if (ModelUtil.isObjectType(type))
         return DeclarationType.OBJECT;
      if (ModelUtil.isEnum(type))
         return DeclarationType.ENUM;
      if (ModelUtil.isInterface(type))
         return DeclarationType.INTERFACE;
      return DeclarationType.CLASS;
   }

   public static CoalescedHashMap getMethodCache(Object type) {
      if (type instanceof Class)
         return RTypeUtil.getMethodCache((Class) type).methodsByName;
      else if (type instanceof CFClass)
         return ((CFClass) type).getMethodCache();
      else if (type instanceof TypeDeclaration)
         return ((TypeDeclaration) type).getMethodCache();
      else if (type instanceof ParamTypeDeclaration)
         return getMethodCache(((ParamTypeDeclaration) type).baseType);
      else
         throw new UnsupportedOperationException();
   }

   public static Object getBindableAnnotation(Object def) {
      // Check both get and set method if this is a get or set method
      return ModelUtil.getPropertyAnnotation(def, "sc.bind.Bindable");
   }

   public static boolean isAutomaticBindingAnnotation(Object annotationObj) {
      Object manualObj = ModelUtil.getAnnotationValue(annotationObj, "manual");
      return manualObj == null || !(manualObj instanceof Boolean) ||
              !((Boolean) manualObj);
   }

   public static Object resolveAnnotationReference(JavaModel model, Object def) {
      Object encType = ModelUtil.getEnclosingType(def);
      if (encType == null) {
         return def;
      }
      Object res;
      String propName;
      String typeName;
      Object specEncType = model.layeredSystem.getTypeDeclaration(typeName = ModelUtil.getTypeName(encType));
      if (specEncType == null && (specEncType = model.findTypeDeclaration(typeName, false)) == null) {
         System.err.println("**** resolveSpecificReference failed for: " + def);
         res = def;
      }
      else if (specEncType == encType)
         res = def;
      else if (def instanceof IMethodDefinition && ((IMethodDefinition) def).getPropertyName() != null) {
         propName = ((IMethodDefinition) def).getPropertyName();
         if (propName != null) {
            res = ModelUtil.definesMember(specEncType, ModelUtil.getPropertyName(def), JavaSemanticNode.MemberType.PropertyAssignmentSet, null, null, model.layeredSystem);
            if (res == null) {
               System.out.println("*** no member in specEncType");
               res = def;
            }
         }
         else
            res = def;
      }
      else if (ModelUtil.isField(def)) {
         res = ModelUtil.definesMember(specEncType, ModelUtil.getPropertyName(def), JavaSemanticNode.MemberType.PropertyAssignmentSet, null, null, model.layeredSystem);
         if (res == null) {
            System.out.println("*** no field in specEncType");
            res = def;
         }
      }
      else if (ModelUtil.isMethod(def)) {
         Object[] params = ModelUtil.getParameterTypes(def);
         List lp;
         if (params == null)
            lp = null;
         else
            lp = Arrays.asList(params);
         res = ModelUtil.definesMethod(specEncType, ModelUtil.getMethodName(def), lp, null, null, false, false, null, null);
         if (res == null) {
            System.out.println("*** no method in specEncType");
            res = def;
         }
      }
      // Object reference
      else {
         res = def;
      }
      return res;
   }

   public static String getFullBaseTypeName(Object typeDeclaration) {
      if (typeDeclaration instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) typeDeclaration).getFullBaseTypeName();
      }
      else {
         String typeName = ModelUtil.getTypeName(typeDeclaration);
         int ix = typeName.indexOf("[]");
         if (ix != -1)
            return typeName.substring(0, ix);
         return typeName;
      }
   }

   public static String[] getParameterNames(Object meth) {
      String[] names;
      if (meth instanceof AbstractMethodDefinition) {
         Parameter parameters = ((AbstractMethodDefinition)meth).parameters;
         if (parameters == null)
            return null;
         return parameters.getParameterNames();
      }
      else {
         Object[] types = ModelUtil.getParameterTypes(meth);
         if (types == null)
            return null;
         names = new String[types.length];
         for (int i = 0; i < names.length; i++) {
            names[i] = "_p" + i;
         }
      }
      return names;
   }

   public static String getParameterDecl(Object meth) {
      Object[] types = getParameterTypes(meth);
      Object[] names = getParameterNames(meth);
      if (types == null || types.length == 0)
         return "";
      int len = types.length;
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < len; i++) {
         if (i != 0)
            sb.append(", ");
         sb.append(ModelUtil.getTypeName(types[i]));
         sb.append(" ");
         sb.append(names[i]);
      }
      return sb.toString();
   }

   public static int getModifiers(Object def) {
      if (def instanceof Member)
         return ((Member) def).getModifiers();
      else if (def instanceof Class)
         return ((Class) def).getModifiers();
      else if (def instanceof ParamTypedMethod)
         return getModifiers(((ParamTypedMethod) def).method);
      else if (def instanceof Definition)
         return ((Definition) def).getModifierFlags();
      throw new UnsupportedOperationException();
   }

   public static Object getRootType(Object typeObj) {
      Object nextObj = typeObj;
      Object lastParent = typeObj;
      do {
         nextObj = ModelUtil.getEnclosingType(nextObj);
         if (nextObj != null)
            lastParent = nextObj;
      } while (nextObj != null);
      return lastParent;
   }

   public static Object[] getExceptionTypes(Object meth) {
      if (meth instanceof Method)
         return ((Method) meth).getExceptionTypes();
      else if (meth instanceof IMethodDefinition) {
         return ((IMethodDefinition) meth).getExceptionTypes();
      }
      else
         throw new IllegalArgumentException("Bad method type: " + meth);
   }

   public static boolean isComponentInterface(LayeredSystem sys, Object typeDecl) {
      if (!ModelUtil.isComponentType(typeDecl))
         return false;

      // TODO: right now, for Jetty there are conflicts so it can't implement the start method since it has one.
      // turns out, it does the lifecycle management we need through lifecycle.  This is not the clearest way
      // to spec it but if the overrideStartName is set, it means it does not implement the interface and we
      // can't treat it as a component in the code.
      Object compilerSettings = ModelUtil.getInheritedAnnotation(sys, typeDecl, "sc.obj.CompilerSettings", false, typeDecl instanceof ITypeDeclaration ? ((ITypeDeclaration) typeDecl).getLayer() : null, false);
      if (compilerSettings == null)
         return true;
      String overrideStartName;
      if ((overrideStartName = (String) ModelUtil.getAnnotationValue(compilerSettings, "overrideStartName")) == null ||
          overrideStartName.length() == 0)
         return true;
      return false;
   }

   public static Object getAnnotationValue(Object type, String annotationName, String valueName) {
      Object annot = getAnnotation(type, annotationName);
      if (annot != null) {
         return getAnnotationValue(annot, valueName);
      }
      return null;
   }

   public static Object getPropertyAnnotationValue(Object type, String annotationName, String valueName) {
      Object annot = getPropertyAnnotation(type, annotationName);
      if (annot != null) {
         return getAnnotationValue(annot, valueName);
      }
      return null;
   }

   /** Returns any scope name set on this type or field.  Note that scopes can only be set on source descriptions.  If you need scope info for other than code generation, i.e. at runtime, you set the annotationName for the scope process and then check for the annotation at runtime. */
   public static String getScopeName(Object def) {
      if (def instanceof Definition) {
         return ((Definition) def).getScopeName();
      }
      else if (def instanceof Class)
         return (String) getAnnotationValue(def, "sc.obj.Scope", "name");
      return null;
   }

   /** Returns any scope name set on this type or field.  Note that scopes can only be set on source descriptions.  If you need scope info for other than code generation, i.e. at runtime, you set the annotationName for the scope process and then check for the annotation at runtime. */
   public static String getInheritedScopeName(LayeredSystem sys, Object def, Layer refLayer) {
      if (sys == null)
         sys = LayeredSystem.getCurrent();
      def = resolveSrcTypeDeclaration(sys, def, false, false, refLayer);
      if (def instanceof BodyTypeDeclaration) {
         return ((BodyTypeDeclaration) def).getInheritedScopeName();
      }
      else if (def instanceof Class) {
         return (String) getInheritedAnnotationValue(sys, def, "sc.obj.Scope", "name");
      }
      return null;
   }

   /** Takes name of the form: typeName&lt;paramType&gt; and returns just the type object for "typeName", ignoring the parameters */
   public static Object getTypeFromTypeOrParamName(BodyTypeDeclaration srcType, String fieldTypeName) {
      int ix = fieldTypeName.indexOf("<");
      String fieldBaseType;
      if (ix != -1) {
         fieldBaseType = TypeUtil.stripTypeParameters(fieldTypeName);
         int eix = fieldTypeName.indexOf(">");
         if (eix == -1) {
            return null;
         }
      }
      else {
         fieldBaseType = fieldTypeName;
      }
      JavaModel model;
      Object fieldType = (model = srcType.getJavaModel()).findTypeDeclaration(fieldBaseType, false);
      if (fieldType == null) {
         TypeDeclaration encType = srcType.getEnclosingType();
         if (encType != null) {
            fieldTypeName = encType.mapTypeParameterNameToTypeName(fieldTypeName);
            fieldType = model.findTypeDeclaration(fieldTypeName, false);
         }
      }
      return fieldType;
   }

   /** Takes name of the form: typeName&lt;paramType&gt; and returns the JavaType which represents that expression */
   public static JavaType getJavaTypeFromTypeOrParamName(BodyTypeDeclaration srcType, String fieldTypeName) {
      JavaModel model;
      int ix = fieldTypeName.indexOf("<");
      String fieldBaseType;
      String[] fieldTypeParamNames;
      if (ix != -1) {
         fieldBaseType = TypeUtil.stripTypeParameters(fieldTypeName);
         int eix = fieldTypeName.indexOf(">");
         if (eix == -1) {
            srcType.displayError("scopeFieldDefs format error for type: " + fieldTypeName);
            return null;
         }
         else {
            fieldTypeParamNames = StringUtil.split(fieldTypeName.substring(ix + 1, eix), ',');
         }
      }
      else {
         fieldBaseType = fieldTypeName;
         fieldTypeParamNames = null;
      }
      Object fieldType = (model = srcType.getJavaModel()).findTypeDeclaration(fieldBaseType, false);
      if (fieldType == null) {
         TypeDeclaration encType = srcType.getEnclosingType();
         if (encType != null) {
            fieldTypeName = encType.mapTypeParameterNameToTypeName(fieldTypeName);
            fieldType = model.findTypeDeclaration(fieldTypeName, false);
         }
      }
      if (fieldType == null)
         return null;

      if (fieldTypeParamNames != null) {
         JavaType[] fieldTypeParams = new JavaType[fieldTypeParamNames.length];
         for (int i = 0; i < fieldTypeParamNames.length; i++) {
            fieldTypeParams[i] = getJavaTypeFromTypeOrParamName(srcType, fieldTypeParamNames[i]);
         }
         return JavaType.createFromTypeParams(ModelUtil.getTypeName(fieldType), fieldTypeParams, fieldType);
      }
      return JavaType.createJavaType(srcType.getLayeredSystem(), fieldType);
   }

   public static Object getStaticPropertyValue(Object staticType, String firstIdentifier) {
      if (staticType instanceof TypeDeclaration) {
         return ((TypeDeclaration) staticType).getStaticProperty(firstIdentifier);
      }
      else if (staticType instanceof Class) {
         return TypeUtil.getStaticValue((Class) staticType, firstIdentifier);
      }
      else if (staticType instanceof CFClass) {
         return getStaticPropertyValue(((CFClass) staticType).getCompiledClass(), firstIdentifier);
      }
      else if (staticType == null)
         throw new NullPointerException("No static type where static property: " + firstIdentifier + " expected");
      else
         throw new UnsupportedOperationException();
   }

   // Is this an initialization method - i.e. an init method in a component type.
   // If so, we can treat this expression as a 'referenceInitializer' and convert getX into
   // getX(false) so that we don't fully initialize the object before returning it.  It allows
   // all objects in the graph to be created first, then initialized, then started.
   // TODO: should we use getX(List<Object>) and support getX() calls on components accessed in the preInit method? We could init a deeper component graph that way using this mechanism and still
   // initialize any new objects created in a multi-step way. Right now we only init child inner objects using the graph style access pattern.
   public static boolean isChainedReferenceInitializer(MethodDefinition mdef) {
      Parameter params;
      if (mdef.propertyName != null && mdef.propertyMethodType.isGet() &&
              (params = mdef.parameters) != null && params.getNumParameters() == 1 && mdef.parameters.getParameterNames()[0].equals("doInit"))
         return true;

      // TODO: probably need a better test than that - should be in the IComponent interface and also the method should be accessing the getX of a sub-object
      String methName = mdef.name;
      if (methName.equals("init") && mdef.getNumParameters() == 0) {
         return true;
      }

      // Is this a newX method that we generated.  TODO: this could be cleaner too: maybe use an annotation?
      if (mdef.name.startsWith("new") && mdef.getNumParameters() >= 1 && mdef.parameters.getParameterNames()[0].equals("doInit"))
         return true;

      return false;
   }

   public static boolean isDynamicProperty(Object propType) {
      if (propType instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration pt = (BodyTypeDeclaration) propType;
         BodyTypeDeclaration enc = pt.getEnclosingType();
         if (enc == null)
            return ModelUtil.isDynamicType(propType);  // Top level object
         if (pt.getEnclosingType().isDynamicType() && !pt.isCompiledProperty(pt.typeName, false, false))
            return true;
         return false;
      }
      else {
         return ModelUtil.isDynamicType(propType);
      }
   }

   public static boolean isDynamicType(Object encType) {
      if (encType instanceof JavaSemanticNode)
         return ((JavaSemanticNode) encType).isDynamicType();
      else if (encType instanceof DynBeanMapper)
         return isDynamicType(((DynBeanMapper) encType).getPropertyMember());
      else if (encType instanceof ITypeDeclaration)
         return ((ITypeDeclaration) encType).isDynamicType();
      return false;
   }

   public static boolean isDynamicNew(Object boundType) {
      return boundType instanceof ITypeDeclaration && ((ITypeDeclaration) boundType).isDynamicNew();
   }

   public static Object getTypeFromInstance(Object arg) {
      if (arg instanceof IDynObject)
         return ((DynObject) arg).getDynType();
      else
         return arg.getClass();
   }

   public static String getRuntimeClassName(Object extendsType) {
      if (extendsType instanceof TypeDeclaration)
         return ((TypeDeclaration) extendsType).getCompiledClassName();
      else
         return getTypeName(extendsType);
   }

   public static String getSignature(Object type) {
      if (type instanceof JavaType)
         return ((JavaType) type).getSignature(false);
      else if (type instanceof ITypeDeclaration)
         return "L" + ((ITypeDeclaration)type).getJavaFullTypeName().replace('.', '/') + ";";
      else if (type instanceof Class) {
         return RTypeUtil.getSignature((Class) type);
      }
      else
         throw new UnsupportedOperationException();
   }

   /** Returns the type signature for the parameters for a method - NOTE: does not include the return type like the Java method signature that's in the class file.  */
   public static String getTypeSignature(Object obj) {
      if (obj == null)
         return null;
      if (obj instanceof IMethodDefinition) {
         return ((IMethodDefinition) obj).getTypeSignature();
      }
      else {
         Object[] types = getParameterTypes(obj);
         if (types == null)
            return null;
         StringBuilder sb = new StringBuilder();
         for (Object pt:types) {
            sb.append(ModelUtil.getSignature(pt));
         }
         return sb.toString();
      }
   }

   public static DynType getPropertyCache(Object extType) {
      if (extType instanceof ITypeDeclaration)
         return ((ITypeDeclaration) extType).getPropertyCache();
      else if (extType instanceof Class)
         return TypeUtil.getPropertyCache((Class) extType);
      else
         throw new UnsupportedOperationException();
   }

   public static IBeanMapper getPropertyMapping(Object type, String propName) {
      if (type instanceof Class)
         return PTypeUtil.getPropertyMapping((Class) type, propName);
      else if (type instanceof ITypeDeclaration) {
         if (ModelUtil.isDynamicNew(type)) // returns true for dynamicType or dynamicNew
            return ((ITypeDeclaration) type).getPropertyCache().getPropertyMapper(propName);
         else {
            ITypeDeclaration itype = (ITypeDeclaration) type;
            Class cl = itype.getCompiledClass();
            Object extType;
            // If there's no compiled class for this type, we'll should at least try the compiled class for any extends type.  Otherwise, we fail less gracefully than we should when you add a field to a compiled type that was previously omitted.  Another fix for this case wold be to actually try and transform/generate that new class
            if (cl == null && (extType = itype.getExtendsTypeDeclaration()) != null) {
               return getPropertyMapping(extType, propName);
            }
            if (cl == null)
               return null;
            return PTypeUtil.getPropertyMapping(cl, propName);
         }
      }
      else if (type instanceof String) {
         String typeName = (String) type;
         Object resolvedType = LayeredSystem.getCurrent().getTypeDeclaration(typeName);
         if (resolvedType == null)
            throw new IllegalArgumentException("No type named: " + typeName);
         return getPropertyMapping(resolvedType, propName);
      }
      else
         throw new UnsupportedOperationException();
   }

   public static IBeanMapper getConstantPropertyMapping(Object type, String propName) {
      if (type instanceof Class)
         return TypeUtil.getConstantPropertyMapping((Class) type, propName);
      else if (type instanceof ITypeDeclaration) {
         if (ModelUtil.isDynamicType(type)) {
            IBeanMapper mapper;
            mapper = ((ITypeDeclaration) type).getPropertyCache().getPropertyMapper(propName);
            // Mark this mapping as constant to disable set methods and so we do not have to listen for changes
            mapper.setConstant(true);
            return mapper;
         }
         else {
            Class cl = ((ITypeDeclaration) type).getCompiledClass();
            if (cl == null)
               return null;
            return TypeUtil.getConstantPropertyMapping(cl, propName);
         }
      }
      else if (type instanceof String) {
         String typeName = (String) type;
         Object resolvedType = LayeredSystem.getCurrent().getTypeDeclaration(typeName);
         if (resolvedType == null)
            throw new IllegalArgumentException("No type named: " + typeName);
         return getConstantPropertyMapping(resolvedType, propName);
      }
      else
         throw new UnsupportedOperationException();
   }

  public static Object evalCast(Object propertyType, Object val) {
     if (propertyType instanceof Class) {
        return DynUtil.evalCast((Class) propertyType, val);
     }
     else if (propertyType instanceof ITypeDeclaration) {
        ITypeDeclaration castType = (ITypeDeclaration) propertyType;
        if (val instanceof IDynObject) {
           Object valType = ((IDynObject) val).getDynType();
           if (!ModelUtil.isAssignableFrom(castType, valType))
              throw new ClassCastException("Class cast - mismatching classes: " + ModelUtil.getTypeName(valType) + " cannot be cast to: " + ModelUtil.getTypeName(castType));
        }
        return val;
     }
     else
        throw new UnsupportedOperationException();
   }

   /**
    * Unlike DynUtil.getType which conditionally strips the type off, here we do it explicitly so that when the
    * runtime gets a java.lang.Class instance we still call getClass
    */
   public static Object getObjectsType(Object obj) {
      if (obj instanceof IDynObject) {
         return ((IDynObject) obj).getDynType();
      }
      else
         return obj.getClass();
   }

   public static boolean evalInstanceOf(Object lhsObj, Object rhs, ITypeParamContext ctx) {
      if (lhsObj == null)
         return false;
      return isAssignableFrom(rhs, getObjectsType(lhsObj), false, ctx);
   }

   public static Object getRuntimeType(Object rootType) {
      if (rootType instanceof Class || rootType instanceof ParameterizedType)
         return rootType;
      else if (rootType instanceof ITypeDeclaration)
         return ((ITypeDeclaration) rootType).getRuntimeType();
      else if (rootType instanceof PropertyAssignment) {
         return ModelUtil.getRuntimeType(((PropertyAssignment) rootType).getTypeDeclaration());
      }
      else
         throw new UnsupportedOperationException();
   }

   public static boolean isInstance(Object srcTypeObj, Object obj) {
      if (obj == null)
         return true;
      return isAssignableFrom(srcTypeObj, DynUtil.getType(obj));
   }

   public static boolean isCompiledMethod(Object override) {
      if (override instanceof ParamTypedMethod)
         return isCompiledMethod(((ParamTypedMethod) override).method);
      return override instanceof Method || override instanceof CFMethod || ((override instanceof MethodDefinition) && !((MethodDefinition) override).isDynamicType());
   }

   public static boolean getCompileLiveDynamicTypes(Object typeObj) {
      if (typeObj instanceof TypeDeclaration) {
         TypeDeclaration objType = (TypeDeclaration)typeObj;
         return objType.getLayeredSystem().options.compileLiveDynamicTypes && (objType.layer == null || objType.layer.compileLiveDynamicTypes) && objType.getLiveDynamicTypesAnnotation();
      }
      return false;
   }

   public static boolean getLiveDynamicTypes(Object typeObj) {
      if (typeObj instanceof TypeDeclaration) {
         TypeDeclaration objType = (TypeDeclaration)typeObj;
         Layer layer = objType.layer;
         return objType.getLayeredSystem().options.liveDynamicTypes && (layer == null || layer.liveDynamicTypes) && objType.getLiveDynamicTypesAnnotation();
      }
      return false;
   }

   public static boolean isDynamicStub(Object extendsType, boolean includeExtends) {
      if (extendsType instanceof TypeDeclaration) {
         return ((TypeDeclaration) extendsType).isDynamicStub(includeExtends);
      }
      return false;
   }

   public static Object resolve(Object typeObj, boolean modified) {
      if (typeObj instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) typeObj).resolve(modified);
      // For super.meth() we do not want to ever get the current method here (as far as I can tell).  There's a weird case where
      else if (!modified && typeObj instanceof AbstractMethodDefinition) {
         return ((AbstractMethodDefinition) typeObj).resolve(modified);
      }
      return typeObj;
   }

   /** Prefix for the intValue and intPropertyValue methods in TypeUtil and DynUtil */
   public static String getNumberPrefixFromType(Object type) {
      if (isAnInteger(type))
         return "int";
      else if (isDouble(type))
         return "double";
      else if (isFloat(type))
         return "float";
      else if (isLong(type))
         return "long";
      else if (isBoolean(type))
         return "boolean";
      else if (isCharacter(type))
         return "char";
      throw new UnsupportedOperationException();
   }

   public static String typesToThrowsClause(Object[] throwsTypes) {
      if (throwsTypes == null || throwsTypes.length == 0)
         return "";

      StringBuilder sb = new StringBuilder();
      sb.append("throws ");
      for (int i = 0; i < throwsTypes.length; i++) {
         if (i != 0)
            sb.append(", ");
         sb.append(ModelUtil.getTypeName(throwsTypes[i]));
      }
      return sb.toString();
   }

   public static Object refreshBoundProperty(LayeredSystem sys, Object type, int flags) {
      // TODO: do we need to replace a transformed property reference?  I don't think so because we never use
      // Replace any properties which refresh to the object itself
      return ModelUtil.refreshBoundType(sys, type, flags);
   }

   public static Object refreshBoundMethod(LayeredSystem sys, Object meth, int flags) {
      if (meth instanceof ParamTypedMethod) {
         ParamTypedMethod ptm = (ParamTypedMethod) meth;
         ptm.method = refreshBoundMethod(sys, ptm.method, flags);
         return ptm;
      }
      Object enclType = refreshBoundType(sys, ModelUtil.getEnclosingType(meth), flags);
      if (enclType == null) {
         System.err.println("** Failed to refresh enclosing type for method");
         return meth;
      }
      Object res = ModelUtil.getMethodFromSignature(enclType, ModelUtil.getMethodName(meth), ModelUtil.getTypeSignature(meth), false);
      if (res == null) {
         System.err.println("*** Unable to refresh bound method");
         return meth;
      }
      return res;
   }

   public static Object refreshBoundField(LayeredSystem sys, Field field) {
      // Don't try to resolve these because it won't resolve to the right thing.
      if (field == ArrayTypeDeclaration.LENGTH_FIELD)
         return field;
      Object enclType = refreshBoundClass(sys, field.getDeclaringClass());
      Object res = ModelUtil.definesMember(enclType, ModelUtil.getPropertyName(field), JavaSemanticNode.MemberType.FieldSet, null, null, sys);
      if (res == null)
         System.out.println("*** Unable to refreshBoundField");
      return res;
   }

   public static Object refreshBoundIdentifierType(LayeredSystem sys, Object boundType, int flags) {
      if ((flags & REFRESH_TYPEDEFS) != 0) {
         if (boundType instanceof TypeDeclaration)
            return refreshBoundType(sys, boundType, flags);
         else if (boundType instanceof AbstractMethodDefinition || boundType instanceof ParamTypedMethod)
            return refreshBoundMethod(sys, boundType, flags);
      }
      if ((flags & REFRESH_CLASSES) != 0) {
         if (boundType instanceof Class)
            return refreshBoundClass(sys, (Class) boundType);
         if (boundType instanceof Method || boundType instanceof ParamTypedMethod)
            return refreshBoundMethod(sys, boundType, flags);
         if (boundType instanceof Constructor)
            return refreshBoundMethod(sys, boundType, flags);
         if (boundType instanceof Field)
            return refreshBoundField(sys, (Field) boundType);
         if (boundType instanceof ParamTypeDeclaration) {
            ParamTypeDeclaration ptd = (ParamTypeDeclaration) boundType;
            ptd.baseType = refreshBoundIdentifierType(sys, ptd.baseType, flags);
         }
         else if (boundType instanceof ParamTypedMember) {
            ParamTypedMember memb = (ParamTypedMember) boundType;
            memb.member = refreshBoundIdentifierType(sys, memb.member, flags);
         }
      }

      // TODO: do we need to re-resolve any identifier references that are children types?  Variables, fields, methods?
      // Theoretically their type should not change and we should not be interpreting these references so I'm thinking
      // we are ok for now.
      return boundType;
   }

   public static Object refreshBoundType(LayeredSystem sys, Object boundType, int flags) {
      if (boundType instanceof ParamTypeDeclaration) {
         ParamTypeDeclaration ptd = (ParamTypeDeclaration) boundType;
         ptd.baseType = refreshBoundType(sys, ptd.baseType, flags);
         return ptd;
      }
      if ((flags & REFRESH_TYPEDEFS) != 0 && boundType instanceof TypeDeclaration) {
         TypeDeclaration td = (TypeDeclaration) boundType;
         return td.refreshBoundType(boundType);
      }
      if ((flags & REFRESH_CLASSES) != 0 && boundType instanceof Class)
         return refreshBoundClass(sys, (Class) boundType);
      return boundType;
   }

   public static Object refreshBoundClass(LayeredSystem sys, Object boundClass) {
      Object res = sys.getClass(ModelUtil.getTypeName(boundClass), true);
      if (res != null)
         return res;
      return boundClass;
   }

   public static Object getEnum(Object currentType, String nextName) {
      if (currentType instanceof Class)
         return RTypeUtil.getEnum((Class) currentType, nextName);
      else if (currentType instanceof ITypeDeclaration) {
         ITypeDeclaration typeDecl = (ITypeDeclaration) currentType;
         if (typeDecl.isEnumeratedType())
            return typeDecl.getEnumConstant(nextName);
         return null;
      }
      else throw new UnsupportedOperationException();
   }

   public static Object getRuntimeEnum(Object boundType) {
      if (boundType instanceof java.lang.Enum)
         return boundType;
      if (boundType instanceof BodyTypeDeclaration && ((BodyTypeDeclaration) boundType).isEnumConstant())
         return ((BodyTypeDeclaration) boundType).getRuntimeEnum();
      if (boundType instanceof Field) {
         Field f = (Field) boundType;
         return DynUtil.getStaticProperty(f.getDeclaringClass(), f.getName());
      }
      if (boundType instanceof IBeanMapper) {
         IBeanMapper mapper = (IBeanMapper) boundType;
         return getRuntimeEnum(mapper.getField());
      }
      if (boundType instanceof CFField) {
         CFField cfield = (CFField) boundType;
         if (cfield.isEnumConstant()) {
            return getRuntimeEnum(((CFField) boundType).getRuntimeField());
         }
      }
      throw new UnsupportedOperationException();
   }

   public static void startType(BodyTypeDeclaration bt) {
      JavaModel model = bt.getJavaModel();
      if (model != null && !model.isStarted())
         ParseUtil.initAndStartComponent(model);
      else
         ParseUtil.initAndStartComponent(bt);
   }

   public static StringBuilder convertToCommaSeparatedStrings(Set<String> names) {
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (String n:names) {
         if (!first)
            sb.append(",");
         else
            first = false;
         sb.append("\"");
         sb.append(n);
         sb.append("\"");
      }
      return sb;
   }

   public static boolean isAbstractType(Object type) {
      return (hasModifier(type, "abstract") || isInterface(type) || isAbstractElement(type));
   }

   public static boolean isAbstractElement(Object type) {
      if (type instanceof TypeDeclaration) {
         TypeDeclaration td = (TypeDeclaration) type;
         if (td.element != null)
            return td.element.isAbstract();
      }
      return false;
   }

   public static boolean isAbstractMethod(Object meth) {
      return (hasModifier(meth, "abstract") || isInterface(getEnclosingType(meth)));
   }

   public static Object getReverseBindingMethod(Object method) {
      Object settings = RDynUtil.getAnnotation(method, BindSettings.class);
      String reverseMethodName;
      if (settings != null && (reverseMethodName = (String) RDynUtil.getAnnotationValue(settings, "reverseMethod")).length() > 0) {
         Object[] methods = RDynUtil.getMethods(RDynUtil.getDeclaringClass(method), reverseMethodName);
         if (methods == null) {
            System.err.println("*** Method: " + method + " BindSettings.reverseMethod annotation refers to a non-existent method: " + reverseMethodName);
            return null;
         }
         Object returnType = DynUtil.getReturnType(method);
         for (Object invMeth:methods) {
            Object[] ptypes = DynUtil.getParameterTypes(invMeth);
            if (ptypes.length >= 1) {
               if (DynUtil.isAssignableFrom(ptypes[0], returnType))
                  return invMeth;
            }
         }
         System.err.println("*** Method: " + method + " BindSettings.reverseMethod: " + reverseMethodName + " needs a signature like: (" + returnType + ", ...) - the first param is the inverse value, the rest are the input parameters");
      }
      else
         System.err.println("*** Method: " + method + " has no BindSettings(reverseMethod=..) annotation - invalid use in bi-directional (:=:) binding");
      return null;
   }

   public static boolean needsCompMethod(Object meth) {
      return (meth instanceof AbstractMethodDefinition) && (((AbstractMethodDefinition) meth).getNeedsDynInvoke());
   }

   public static void markNeedsDynAccess(Object boundType) {
      if (boundType instanceof BodyTypeDeclaration)
         ((BodyTypeDeclaration) boundType).needsDynAccess = true;
      else
         System.err.println("*** Error: unable to make compiled object available at runtime: " + boundType);
   }

   public static boolean needsDynType(Object type) {
      return type instanceof TypeDeclaration && ((TypeDeclaration) type).needsDynType();
   }

   public static boolean isConstructor(Object method) {
      return method instanceof Constructor || method instanceof ConstructorDefinition || (method instanceof CFMethod && ((CFMethod) method).getMethodName().equals("<init>")) ||
              (method instanceof ParamTypedMethod && isConstructor(((ParamTypedMethod) method).method));
   }

   /** Weird rule in java.  When you have a parameterized return type and no arguments, the type comes from the LHS of the assignment expression
    * (only for assignment expressions apparently)
    */
   public static boolean isLHSTypedMethod(Object boundType) {
      Object[] retVal;
      return ModelUtil.isMethod(boundType) && hasParameterizedReturnType(boundType) &&
             ((retVal = ModelUtil.getParameterTypes(boundType)) == null || retVal.length == 0);
   }


   /** Returns the class which will hold the newX method for a component type that is transformed */
   public static Object getAccessClass(Object base) {
      if (!(base instanceof TypeDeclaration)) {
         if (base instanceof Class) {
            Class enclType = ((Class) base).getDeclaringClass();
            if (enclType == null)
               return base;
            return enclType;
         }
         return base;
      }
      TypeDeclaration baseTD = (TypeDeclaration) base;
      TypeDeclaration enclType;
      if ((enclType = baseTD.getEnclosingType()) == null)
         return base;
      return enclType.getClassDeclarationForType();
   }

   public static Object getRuntimeTypeDeclaration(Object type) {
      if (type instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) type).getClassDeclarationForType();
      return type;
   }

   public static boolean isCompiledProperty(Object impl, String name, boolean fieldMode, boolean interfaceMode) {
      if (impl instanceof ITypeDeclaration)
         return ((ITypeDeclaration) impl).isCompiledProperty(name, fieldMode, interfaceMode);
      else {
         // Assume the rest are all compiled types
         return ModelUtil.definesMember(impl, name, JavaSemanticNode.MemberType.PropertyGetSetObj, null, null, null) != null;
      }
   }

   /** Returns false if this type is optimized away.  i.e. an object instance which contains no methods or fields, just initializations which we can apply at runtime on the base class */
   public static boolean needsOwnClass(Object type, boolean checkComponents) {
      if (type instanceof ITypeDeclaration) {
         ITypeDeclaration typeDef = (ITypeDeclaration) type;
         if (typeDef instanceof TypeDeclaration && ((TypeDeclaration) typeDef).getEnclosingIType() == null)
            return true;
         return (typeDef.needsOwnClass(checkComponents));
      }
      return true;
   }

   public static boolean definesCurrentObject(Object type) {
      if (type instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) type).getDefinesCurrentObject();
      return ModelUtil.isObjectType(type);
   }

   public static String getInnerTypeName(Object typeObj) {
      if (typeObj instanceof ITypeDeclaration)
         return ((ITypeDeclaration) typeObj).getInnerTypeName();
      else if (typeObj instanceof Class) {
         return TypeUtil.getInnerTypeName((Class) typeObj);
      }
      throw new UnsupportedOperationException();
   }

   public static int getTypeOffset(Object type) {
      if (type instanceof Definition) {
         Definition def = (Definition) type;
         IParseNode node = def.getParseNode();
         if (node == null)
            return -1;
         else {
            return node.getStartIndex();
         }
      }
      return -1;
   }

   public static int getLineNumber(Object type) {
      if (type instanceof Definition) {
         Definition def = (Definition) type;
         IParseNode node = def.getParseNode();
         if (node == null)
            return -1;
         else {
            SrcEntry srcFile = def.getJavaModel().getSrcFile();
            if (srcFile == null)
               return -1;
            return ParseUtil.getParseNodeLineNumber(new File(srcFile.absFileName), node);
         }
      }
      return -1;
   }

   /**
    * Used to filter objects in the UI.  Returns true if the type provided has any definitions in a layer with the
    * given code types and functions
    */
   public static boolean matchesLayerFilter(Object type, Collection<CodeType> codeTypes) {
      if (!(type instanceof BodyTypeDeclaration))
         return codeTypes.contains(CodeType.Framework);

      BodyTypeDeclaration typeDecl = (BodyTypeDeclaration) type;
      while (typeDecl != null) {
         if (typeDecl.getLayer().matchesFilter(codeTypes))
            return true;
         typeDecl = typeDecl.getModifiedType();
      }
      return false;
   }

   public static void getFiltersForType(Object type, Collection<CodeType> codeTypes, boolean allLayers) {
      if (!(type instanceof BodyTypeDeclaration)) {
         codeTypes.add(CodeType.Framework);
         return;
      }

      BodyTypeDeclaration typeDecl = (BodyTypeDeclaration) type;
      while (typeDecl != null) {
         Layer layer = typeDecl.getLayer();
         if (!codeTypes.contains(layer.codeType))
            codeTypes.add(layer.codeType);
         typeDecl = typeDecl.getModifiedType();
         if (!allLayers)
            break;
      }
   }

   public static boolean definedInLayer(Object type, Layer layer) {
      if (!(type instanceof BodyTypeDeclaration))
            return false;
      BodyTypeDeclaration typeDecl = (BodyTypeDeclaration) type;
      while (typeDecl != null) {
         if (typeDecl.getLayer() == layer)
            return true;
         typeDecl = typeDecl.getModifiedType();
      }
      return false;
   }

   public static boolean isLayerType(Object type) {
      return type instanceof BodyTypeDeclaration && ((BodyTypeDeclaration) type).isLayerType;
   }

   public static boolean isLayerComponent(Object type) {
      return type instanceof BodyTypeDeclaration && ((BodyTypeDeclaration) type).isLayerComponent();
   }

   public static JavaModel getJavaModel(Object node) {
      if (node instanceof JavaSemanticNode)
         return ((JavaSemanticNode) node).getJavaModel();
      return null;
   }

   /** Determines whether or not we display this type in application tree view.  Should we check if the component is
    * validated, has instances, or something?  If so, we need to figure out how to revalidate the view when those things change. */
   public static boolean isApplicationType(Object type) {
      return type instanceof BodyTypeDeclaration;
   }

   public static String validateElement(Parselet element, String str, boolean optional) {
      Object res = element.language.parseString(str, element);
      if (!(res instanceof ParseError)) {
         if (res == null)
            return optional ? null : "Missing value";
         return null;
      }
      return res.toString();
   }

   public static String javaTypeToCompiledString(Object refType, Object type, boolean retNullForDynObj) {
      if (type instanceof JavaType)
         return ((JavaType) type).toCompiledString(refType, retNullForDynObj);
      return ModelUtil.getCompiledTypeName(type);
   }

   public static String evalParameterForType(Object type, Object memberType, String typeParam) {
      if (memberType == type)
         return typeParam;

      Object nextType;
      nextType = ModelUtil.getExtendsClass(type);
      int pos = evalParameterPosition(nextType, memberType, typeParam);
      if (pos == -1) {
         System.err.println("*** Can't evaluate parameter: " + typeParam);
         return typeParam;
      }
      else {
         Object extJavaType = ModelUtil.getExtendsJavaType(type);
         Object typeArg = ModelUtil.getTypeArgument(extJavaType, pos);
         if (ModelUtil.isTypeVariable(typeArg))
            return ModelUtil.getTypeParameterName(typeArg);
         else
            return ModelUtil.javaTypeToCompiledString(type, typeArg, false);
      }
   }

   public static int evalParameterPosition(Object fromType, Object memberType, String typeParam) {
      if (ModelUtil.sameTypes(fromType, memberType)) {
         List<?> tps = ModelUtil.getTypeParameters(fromType);
         if (tps == null)
            return -1;
         int i = 0;
         for (Object o:tps) {
            String tpN = ModelUtil.getTypeParameterName(o);
            if (tpN.equals(typeParam))
               return i;
            i++;
         }
         return -1;
      }
      // We need to map this parameter position into the type params for memberType given
      // a type parameter in fromType.  First need to find the base class or interface
      // which defines this method.
      else {
         Object extType = ModelUtil.getExtendsClass(fromType);
         Object extJavaType = ModelUtil.getExtendsJavaType(fromType);
         if (extType != null && ModelUtil.isAssignableFrom(memberType, extType)) {
            int pos = evalParamPositionForBaseClass(fromType, extType, extJavaType, memberType, typeParam);
            if (pos != -1)
               return pos;
         }
         Object[] ifaces = ModelUtil.getImplementsTypeDeclarations(fromType);
         Object[] ifaceJavaTypes = ModelUtil.getImplementsJavaTypes(fromType);
         if (ifaces != null) {
            for (int i = 0; i < ifaces.length; i++) {
               Object ifaceType = ifaces[i];
               Object ifaceJavaType = ifaceJavaTypes[i];
               if (ifaceType != null && ModelUtil.isAssignableFrom(memberType, ifaceType)) {
                  int pos = evalParamPositionForBaseClass(fromType, ifaceType, ifaceJavaType, memberType, typeParam);
                  if (pos != -1)
                     return pos;
               }
            }
         }
      }
      return -1;
   }

   private static int evalParamPositionForBaseClass(Object fromType, Object extType, Object extJavaType, Object memberType, String typeParam) {
      int pos = evalParameterPosition(extType, memberType, typeParam);
      if (pos == -1) {
         return -1;
      }
      else {
         Object typeArg = ModelUtil.getTypeArgument(extJavaType, pos);
         ClassType ct;
         if (typeArg instanceof ClassType && (ct = (ClassType) typeArg).type instanceof TypeParameter)
            typeArg = ct.type;
         if (ModelUtil.isTypeVariable(typeArg)) {
            List<?> tps = ModelUtil.getTypeParameters(fromType);
            if (tps == null)
               return -1;
            int i = 0;
            for (Object o:tps) {
               String tpN = ModelUtil.getTypeParameterName(o);
               if (tpN.equals(typeParam))
                  return i;
               i++;
            }
         }
      }
      return -1;
   }

   public static Object getTypeArgument(Object javaType, int ix) {
      if (javaType instanceof ClassType)
         return ((ClassType) javaType).getTypeArgument(ix);
      else if (javaType instanceof ParameterizedType) {
         ParameterizedType pt = (ParameterizedType) javaType;
         Type[] actual = pt.getActualTypeArguments();
         if (actual == null || ix >= actual.length) {
            TypeVariable[] arr = ((Class) pt.getRawType()).getTypeParameters();
            if (arr != null && ix < arr.length)
               return arr[ix];
         }
         return actual[ix];
         //Object res = getTypeVariable(pt.getRawType(), ix);
         //Object res2 = ModelUtil.getTypeVariable(javaType, ix);
      }
      else if (javaType instanceof ParamTypeDeclaration) {
         ParamTypeDeclaration ptd = (ParamTypeDeclaration) javaType;
         return ptd.typeParams.get(ix);
      }
      else if (javaType instanceof Class) {
         return ((Class) javaType).getTypeParameters()[ix];
      }
      else
         return null;
   }

   /** Converts the type arguments for the compiled class.  Similar to getCompiledClassName but converts the type arguments along the way through the type hierarchy skipping dynamic and skipped classes */
   public static List<JavaType> getCompiledTypeArgs(Object type, List<JavaType> typeArgs) {
      if (type instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) type).getCompiledTypeArgs(typeArgs);
      }
      else if (type instanceof Class) {
         return typeArgs; // No mapping in this case since we have the compiled class
      }
      throw new UnsupportedOperationException();
   }

   public static String getGenericSetMethodPropertyTypeName(Object resultType, Object setMethod, boolean includeDim) {
      if (setMethod instanceof ParamTypedMember) {
         return getGenericSetMethodPropertyTypeName(resultType, ((ParamTypedMember) setMethod).getMemberObject(), includeDim);
      }
      if (setMethod instanceof Method) {
         Method setMeth = (Method) setMethod;
         Type[] parameterTypes = setMeth.getGenericParameterTypes();
         if (parameterTypes.length == 0)
            return null;
         String res = resolveGenericTypeName(getEnclosingType(setMethod), resultType, parameterTypes[0], includeDim);
         if (res == null)
            return "Object";
         return res;
      }
      else if (setMethod instanceof IMethodDefinition) {
         IMethodDefinition meth = (IMethodDefinition) setMethod;
         JavaType[] paramTypes = meth.getParameterJavaTypes(true);
         if (paramTypes.length == 0)
            return null;
         return paramTypes[0].getGenericTypeName(resultType, includeDim);
      }
      else if (setMethod instanceof IBeanMapper) {
         return getGenericSetMethodPropertyTypeName(resultType, ((IBeanMapper) setMethod).getSetSelector(), includeDim);
      }
      throw new UnsupportedOperationException();
   }

   public static Object definesComponentMethod(Object type, String name, Object refType) {
      Object res = ModelUtil.definesMethod(type, name, null, null, refType, false, false, null, null);
      if (res == null)
         res = ModelUtil.definesMethod(type, "_" + name, null, null, refType, false, false, null, null);
      return res;
   }

   public static String getSuperInitTypeCall (LayeredSystem sys, Object objType) {
      Object extType = ModelUtil.getExtendsClass(objType);
      if (extType == null)
         return null;
      if (ModelUtil.needsDynType(extType)) {
         return ModelUtil.getRuntimeTypeName(extType) + "._initType()";
      }
      else if (sys.needsExtDynType(extType)) {
         return sys.buildInfo.getExternalDynTypeName(ModelUtil.getTypeName(extType)) + "._initType()";
      }
      return null;
   }

   public static Object getParamTypeBaseType(Object paramType) {
      if (paramType instanceof ParamTypeDeclaration)
         return ((ParamTypeDeclaration) paramType).getBaseType();
      else if (paramType instanceof ParameterizedType) {
         return ((ParameterizedType) paramType).getRawType();
      }
      return paramType;
   }

   public static int getOuterInstCount(Object pType) {
      if (pType instanceof BodyTypeDeclaration) {
         return ((BodyTypeDeclaration) pType).getOuterInstCount();
      }
      else {
         Object outer = ModelUtil.getEnclosingInstType(pType);
         int ct = 0;
         while (outer != null) {
            ct++;
            outer = ModelUtil.getEnclosingInstType(outer);
         }
         return ct;
      }
   }

   public static Object getSuperMethod(Object method) {
      Object enclType = ModelUtil.getEnclosingType(method);
      Object extClass = ModelUtil.getExtendsClass(enclType);

      if (extClass == null)
         return null;
      Object[] paramTypes = ModelUtil.getParameterTypes(method);
      List<Object> params = paramTypes == null ? null : Arrays.asList(paramTypes);
      // If we're looking for the super of a constructor, use the type name
      String methName = ModelUtil.isConstructor(method) ? CTypeUtil.getClassName(ModelUtil.getTypeName(extClass)) : ModelUtil.getMethodName(method);
      return ModelUtil.definesMethod(extClass, methName, params, null, null, false, false, null, null);
   }

   public static boolean needsClassInit(Object srcType) {
      if (ModelUtil.isCompiledClass(srcType))
         return true;
      if (srcType instanceof BodyTypeDeclaration) {
         return ((BodyTypeDeclaration) srcType).needsClassInit();
      }
      if (srcType instanceof ParamTypeDeclaration) {
         ParamTypeDeclaration pt = (ParamTypeDeclaration) srcType;
         return ModelUtil.needsClassInit(pt.getBaseType());
      }
      else throw new UnsupportedOperationException();
   }

   public static Object resolveSrcMethod(LayeredSystem sys, Object meth, boolean cachedOnly, boolean srcOnly, Layer refLayer) {
      if (meth instanceof AbstractMethodDefinition)
         return meth;

      Object enclType = ModelUtil.getEnclosingType(meth);
      enclType = ModelUtil.resolveSrcTypeDeclaration(sys, enclType, cachedOnly, srcOnly, refLayer);
      // TODO performance: should have a way to do 'declaresMethod' here to avoid the 'sameTypes' call on the enclosing type.
      Object newMeth = ModelUtil.getMethod(sys, enclType, ModelUtil.getMethodName(meth), null, null, null, false, null, null, ModelUtil.getParameterTypes(meth));
      if (newMeth != null && ModelUtil.sameTypes(ModelUtil.getEnclosingType(newMeth), enclType))
         return newMeth;
      return meth;
   }

   public static boolean isProcessableType(Object existing) {
      if (existing instanceof Class)
         return true;
      else if (existing instanceof ITypeDeclaration) {
         Layer layer = ModelUtil.getLayerForType(null, existing);
         if (layer != null) {
            if (layer.compiled)
               return true;
            if (layer.annotationLayer) {
               if (existing instanceof ModifyDeclaration) {
                  Object type = ((ModifyDeclaration) existing).getDerivedTypeDeclaration();
                  if (type != null)
                     return ModelUtil.isProcessableType(type);
                  return false;
               }
            }
         }
         return false;
      }
      return false;
   }

   public static int getNumInnerTypeLevels(Object obj) {
      int ct = 0;
      Object cur = obj;
      Object parent;

      while ((parent = ModelUtil.getEnclosingType(cur)) != null) {
         cur = parent;
         ct++;
      }
      return ct;
   }

   public static int getArrayNumDims(Object type) {
      if (type instanceof ParamTypeDeclaration)
         type = ((ParamTypeDeclaration) type).baseType;
      if (type instanceof Class) {
         Class cl = (Class) type;
         if (cl.isArray()) {
            int ndim = 0;
            String className = cl.getName();
            for (int i = 0; i < className.length(); i++)
               if (className.charAt(i) == '[')
                  ndim++;
            return ndim;
         }
         else
            return -1;
      }
      else if (type instanceof JavaType) {
         return ((JavaType) type).getNdims();
      }
      else if (type instanceof GenericArrayType) {
         int ndim = 0;
         Object compType = type;
         do {
            compType = ModelUtil.getArrayComponentType(compType);
            ndim++;
         } while (compType != null && ModelUtil.isArray(compType));
         return ndim;
      }
      else if (type instanceof ArrayTypeDeclaration) {
         return ((ArrayTypeDeclaration) type).getNumDims();
      }
      else
         throw new UnsupportedOperationException();
   }

   public static boolean isGlobalScope(LayeredSystem sys, Object typeObj) {
      ScopeDefinition highest = null;
      String scopeName = ModelUtil.getScopeName(typeObj);
      if (scopeName != null) {
         if (sys.isGlobalScope(scopeName))
            return true;
         if (GlobalScopeDefinition.getGlobalScopeDefinition().matchesScope(scopeName))
            return true;
         ScopeDefinition def = ScopeDefinition.getScopeByName(scopeName);
         if (def != null) {
            if (!def.isGlobal())
               return false;
         }
         else if (!scopeName.equals("global")) // TODO: need another way to hook in the set of global scopes for the "process" stage - before this gets set.
            return false;
         else
            return true;

         Object[] innerTypes = getAllInnerTypes(typeObj, null, false, false);
         if (innerTypes != null) {
            for (Object inner:innerTypes) {
               if (!isGlobalScope(sys, inner))
                  return false;
            }
         }
      }
      return true;
   }

   public static boolean isChangeable(Object prop) {
      Object propType = getPropertyType(prop);
      return propType != null && ModelUtil.isAssignableFrom(IChangeable.class, propType);
   }

   public static void makeBindable(TypeDeclaration type, String propName, boolean needsBindable) {
      Object member = type.definesMember(propName, JavaSemanticNode.MemberType.FieldSet, null, null);
      if (member instanceof ParamTypedMember)
         member = ((ParamTypedMember) member).member;
      if (member instanceof VariableDefinition) {
         // Someone could have added the manual annotation after the fact
         if (!ModelUtil.isManualBindable(member))
            ((VariableDefinition) member).makeBindable(!needsBindable);
         else
            ((VariableDefinition) member).needsDynAccess = true;
      }
      else {
         member = type.definesMember(propName, JavaSemanticNode.MemberType.PropertyAnySet, null, null);
         if (member != null) {
            if (!ModelUtil.isBindable(member))
               type.addPropertyToMakeBindable(propName, null, null, !needsBindable, null);
         }
      }
   }

   public static boolean hasUnboundTypeParameters(Object type) {
      if (ModelUtil.hasTypeParameters(type)) {
         int num = getNumTypeParameters(type);
         for (int i = 0; i < num; i++) {
            Object typeParam = ModelUtil.getTypeParameter(type, i);
            if (ModelUtil.hasTypeVariables(typeParam) || ModelUtil.isParameterizedType(typeParam))
               return true;
         }
      }
      return false;
   }

   // TODO: it seems like we need to use this method in places where we use hasUnboundTypeParaemters and make sure we check for ? extends Type<T>
   public static boolean hasAnyTypeVariables(Object type) {
      return ModelUtil.hasTypeVariables(type) || ModelUtil.hasUnboundTypeParameters(type);
   }

   /**
    * Use this method when you want to get an annotation first on this type, and if not set, use the layer's version.
    * Note that this does not support the inherited flag.  I'm not sure what that would mean... should it also search all dependent layers at the same time?
    * First check all types, then all layers?  Or should inheriting via the layer setting be different than inheriting from the type?
    */
   public static Object getTypeOrLayerAnnotation(LayeredSystem sys, Object type, String annotName) {
      Object settingsObj = ModelUtil.getAnnotation(type, annotName);
      Object value;

      if (settingsObj != null) {
         return settingsObj;
      }

      Layer layer = ModelUtil.getLayerForType(sys, type);
      if (layer != null) {
         settingsObj = getLayerAnnotation(layer, annotName);
         if (settingsObj != null)
            return settingsObj;
      }
      return null;
   }

   /**
    * Use this method when you want to get an annotation first on this type, and if not set, use the layer's version.
    * Note that this does not support the inherited flag.  I'm not sure what that would mean... should it also search all dependent layers at the same time?
    * First check all types, then all layers?  Or should inheriting via the layer setting be different than inheriting from the type?
     */
   public static Object getTypeOrLayerAnnotationValue(LayeredSystem sys, Object type, String annotName, String attName) {
      Object settingsObj = ModelUtil.getAnnotation(type, annotName);
      Object value;

      if (settingsObj != null) {
         value = ModelUtil.getAnnotationValue(settingsObj, attName);
         if (value != null)
            return value;
      }

      Layer layer = ModelUtil.getLayerForType(sys, type);
      if (layer != null) {
         settingsObj = getLayerAnnotationValue(layer, annotName, attName);
         if (settingsObj != null)
            return settingsObj;
      }
      return null;
   }

   public static Object getLayerAnnotation(Layer layer, String annotName) {
      if (layer.model == null) return null; return ModelUtil.getAnnotation(layer.model.getModelTypeDeclaration(), annotName);
   }

   public static Object getLayerAnnotationValue(Layer layer, String annotName, String attName) {
      Object settingsObj = ModelUtil.getAnnotation(layer.model.getModelTypeDeclaration(), annotName);
      if (settingsObj != null) {
         return ModelUtil.getAnnotationValue(settingsObj, attName);
      }
      return null;
   }

   public static void initType(Object type) {
      if (type instanceof Class) {
         Class cl = (Class) type;
         // The only way I know of to guarantee we've initialized this class
         try {
            Class.forName(DynUtil.getTypeName(type, false), true, cl.getClassLoader());
         }
         catch (NoClassDefFoundError exc2) {}
         catch (ClassNotFoundException exc) {}
      }
      else {
         ITypeDeclaration td = (ITypeDeclaration) type;
         td.staticInit();
      }
   }

   public static void ensureStarted(Object type, boolean validate) {
      // We always start the model so things get initialized in a consistent order
      if (type instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration td = ((BodyTypeDeclaration) type);
         JavaModel model = td.getJavaModel();
         if (model == null)
            return;
         if (!model.isStarted() && !td.isStarted()) {
            if (validate)
               ParseUtil.initAndStartComponent(model);
            else
               ParseUtil.realInitAndStartComponent(model);
         }
         else if (validate) {
            if (!model.isValidated())
               ParseUtil.initAndStartComponent(model);
            else
               ParseUtil.initAndStartComponent(td);
         }
         else {
            ParseUtil.realInitAndStartComponent(model);
         }
      }
      else if (type instanceof VariableDefinition) {
         VariableDefinition varDef = ((VariableDefinition) type);
         if (!varDef.isValidated()) {
            JavaModel model = varDef.getJavaModel();
            // It's best to validate from the top-down
            if (model != null && !model.isValidated()) {
               if (validate)
                  ParseUtil.initAndStartComponent(model);
               else
                  ParseUtil.realInitAndStartComponent(model);
            }
            else {
               if (validate)
                  ParseUtil.initAndStartComponent(varDef);
               else
                  ParseUtil.realInitAndStartComponent(varDef);
            }
         }
      }
      else {
         if (validate)
            ParseUtil.initAndStartComponent(type);
         else
            ParseUtil.realInitAndStartComponent(type);
      }
   }

   public static boolean isTransformedType(Object refType) {
      return refType instanceof ITypeDeclaration && ((ITypeDeclaration) refType).isTransformedType();
   }

   public static boolean hasDynTypeConstructor(Object extType) {
      extType = ModelUtil.getParamTypeBaseType(extType);
      // For Dynamic TypeDeclaration's we will insert the dyn type constructor arg so it's not there.  This check
      // is just to detect when we have a compiled dynamic type but which does not have the constructor parameter
      // we need to actually create it as a dynamic type.
      if (extType instanceof Class || !ModelUtil.isDynamicType(extType)) {
         Object[] constrs = ModelUtil.getConstructors(extType, null);
         if (constrs == null)
            return false;
         for (Object constr:constrs) {
            Object[] paramTypes = ModelUtil.getParameterTypes(constr);
            if (paramTypes != null && paramTypes.length > 0 && ModelUtil.isAssignableFrom(TypeDeclaration.class, paramTypes[0]))
               return true;
         }
         return false;
      }
      else
         return true;
   }

   public static ISemanticNode getTopLevelStatement(ISemanticNode node) {
      ISemanticNode origNode = node;
      while (node instanceof SemanticNodeList)
         node = node.getParentNode();
      if (node == null)
         return origNode;
      Statement st = node instanceof Statement ? (Statement) node : ((JavaSemanticNode) node).getEnclosingStatement();
      if (st == null)
         return node;
      ISemanticNode parent = st.getParentNode();

      // We use the block statement that's part of the method to represent the "end of the method"
      if (node instanceof BlockStatement) {
         BlockStatement bst = (BlockStatement) node;
         if (bst.getParentNode() instanceof MethodDefinition)
            return bst;
      }
      if (parent instanceof SemanticNodeList)
         parent = parent.getParentNode();
      if (parent == null || parent instanceof ILanguageModel)
         return st;

      if (parent instanceof Statement) {
         Statement parentStatement = (Statement) parent;
         if (parentStatement.childIsTopLevelStatement(st))
            return st;
      }
      // Element and other statements
      else if (parent instanceof ISrcStatement)
         return parent;

      // TODO: what about variable definitions and fields?  Shouldn't the variable definition be the top-level statement?
      return getTopLevelStatement(parent);
   }

   public static void updateFromStatementRefs(SemanticNodeList list, SemanticNodeList<Statement> fromStatements, ISrcStatement defaultSt) {
      if (fromStatements != null) {
         for (int fromIx = 0; fromIx < fromStatements.size(); fromIx++) {
            Statement fromSt = fromStatements.get(fromIx);
            boolean found = false;
            for (Object newSemNode:list) {
               if (newSemNode instanceof Statement) {
                  Statement st = (Statement) newSemNode;
                  if (st.updateFromStatementRef(fromSt, defaultSt)) {
                     found = true;
                     break;
                  }
               }
            }
            //if (!found) - this happens for mixin templates but they should be caught by the default statement
            //   System.err.println("*** Warning - some statements not matched up with the generated source: " + fromSt);
         }
      }
      // Do this even if there are no assignments - any statement which does not have a fromStatement will be set to
      // the default statement.
      if (defaultSt != null) {
         for (Object newSemNode:list) {
            if (newSemNode instanceof Statement) {
               Statement st = (Statement) newSemNode;
               st.updateFromStatementRef(null, defaultSt);
            }
         }
      }
   }

   public static boolean isDefaultMethod(Object meth) {
      if (meth instanceof IMethodDefinition)
         return ((IMethodDefinition) meth).hasModifier("default");
         /*
      else if (meth instanceof Method)
         return ((Method) meth).isDefault();
         */
      else if (meth instanceof Method) {
         return PTypeUtil.isDefaultMethod(meth);
      }
                
      throw new UnsupportedOperationException();
   }

   public static Object[] getMethodTypeParameters(Object method) {
      if (method instanceof IMethodDefinition) {
         return ((IMethodDefinition) method).getMethodTypeParameters();
      }
      else if (method instanceof Method) {
         return ((Method) method).getTypeParameters();
      }
      else if (method instanceof Constructor) {
         return ((Constructor) method).getTypeParameters();
      }
      else
         throw new UnsupportedOperationException();
   }

   public static boolean hasMethodTypeParameters(Object method) {
      Object[] typeParams = getMethodTypeParameters(method);
      return typeParams != null && typeParams.length > 0;
   }

   public static boolean hasMethodUnboundTypeParameters(Object method) {
      Object[] typeParams = getMethodTypeParameters(method);
      if (typeParams != null && typeParams.length > 0)
         return true;
      Object[] methParams = ModelUtil.getGenericParameterTypes(method, false);
      if (methParams != null) {
         for (Object methParam:methParams) {
            // Used to only consider unbound params here but we can refine even a bound type paraemter apparently so
            // we need to use ParamTypeMethods whenever there's a type parameter in the arg list
            if (ModelUtil.hasUnboundTypeParameters(methParam) || ModelUtil.isParameterizedType(methParam))
               return true;
         }
      }
      if (ModelUtil.isConstructor(method))
         return false;
      Object returnType = ModelUtil.getReturnType(method, false);
      return ModelUtil.hasUnboundTypeParameters(returnType);
   }

   public static Object wrapPrimitiveType(Object newVal) {
      String primTypeName;
      if (ModelUtil.isPrimitive(newVal)) {
         if (newVal instanceof PrimitiveType) {
            primTypeName = ((PrimitiveType) newVal).typeName;
         }
         else if (newVal instanceof Class) {
            primTypeName = ((Class) newVal).getName();
         }
         else if (newVal instanceof WrappedTypeDeclaration) {
            return wrapPrimitiveType(((WrappedTypeDeclaration) newVal).getBaseType());
         }
         else
            throw new UnsupportedOperationException();
         String wrapperTypeName = RTypeUtil.getPrimitiveWrapperName(primTypeName);
         return RTypeUtil.loadClass(null, "java.lang." + wrapperTypeName, true);
      }
      else
         return newVal;
   }

   public static boolean isUnboundSuper(Object type) {
      if (type instanceof ExtendsType.LowerBoundsTypeDeclaration) {
         ExtendsType.LowerBoundsTypeDeclaration lbt = (ExtendsType.LowerBoundsTypeDeclaration) type;
         Object baseType = lbt.getBaseType();
         if (baseType == null || ModelUtil.isTypeVariable(baseType) || ModelUtil.isUnboundSuper(baseType))
            return true;
      }
      return false;
   }

   public static boolean overridesMethodInType(Object type, Object meth) {
      Object[] paramTypes = ModelUtil.getGenericParameterTypes(meth, false);
      if (getMethod(null, type, ModelUtil.getMethodName(meth), null, null, null, false, null, null, paramTypes) != null)
         return true;
      return false;
   }

   public static boolean isOuterType(Object enclosingType, Object currentType) {
      while (enclosingType != null) {
         if (ModelUtil.sameTypes(enclosingType, currentType))
            return true;
         enclosingType = ModelUtil.getEnclosingType(enclosingType);
      }
      return false;
   }

   public static Object getPropagatedConstructor(LayeredSystem sys, Object type, JavaSemanticNode refNode, Layer refLayer) {
      Object compilerSettings = ModelUtil.getInheritedAnnotation(sys, type, "sc.obj.CompilerSettings", false, refLayer, false);
      if (compilerSettings != null) {
         String pConstructor = (String) ModelUtil.getAnnotationValue(compilerSettings, "propagateConstructor");
         Object[] propagateConstructorArgs;

         if (pConstructor != null && pConstructor.length() > 0) {
            String[] argTypeNames = StringUtil.split(pConstructor, ',');
            propagateConstructorArgs = new Object[argTypeNames.length];
            JavaModel m = refNode == null ? null : refNode.getJavaModel();
            for (int i = 0; i < argTypeNames.length; i++) {
               propagateConstructorArgs[i] = m == null ? sys.getTypeDeclaration(argTypeNames[i]) : m.findTypeDeclaration(argTypeNames[i], false);
               if (propagateConstructorArgs[i] == null) {
                  if (refNode != null)
                     refNode.displayError("Bad value to CompilerSettings.propagateConstructor annotation " + pConstructor + " no type: " + argTypeNames[i]);
                  else
                     System.err.println("Bad value to CompilerSettings.propagateConstructor annotation " + pConstructor + " no type: " + argTypeNames[i]);
               }
            }
            List constParams = Arrays.asList(propagateConstructorArgs);
            return ModelUtil.definesConstructor(sys, type, constParams, null);
         }
      }
      return null;
   }

   public static boolean isAnonymousClass(Object depClass) {
      if (depClass instanceof Class)
         return ((Class) depClass).isAnonymousClass();
      else if (depClass instanceof CFClass)
         return ((CFClass) depClass).isAnonymous();
      else // TODO: do we need this for ITypeDeclaration in general?
         throw new UnsupportedOperationException();
   }

   public static String getArrayDimsStr(Object type) {
      if (type instanceof ArrayTypeDeclaration) {
         return ((ArrayTypeDeclaration) type).arrayDimensions;
      }
      else if (type instanceof GenericArrayType) {
         return "[]";
      }
      else if (type instanceof Class && ((Class) type).isArray()) {
         return "[]";
      }
      throw new UnsupportedOperationException();
   }

   public static String paramTypeToString(Object type) {
      if (type instanceof TypeDeclaration)
         return ((TypeDeclaration) type).typeName;
      else if (type instanceof Class)
         return CTypeUtil.getClassName(ModelUtil.getTypeName(type));
      else
         return type.toString();
   }

   public static Object getClassFromType(LayeredSystem system, Object definedInType, String className, boolean useImports, boolean compiledOnly) {
      if (definedInType instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) definedInType).getClass(className, useImports, compiledOnly);
      }
      return system.getTypeDeclaration(className);
   }

   public static Object resolveCompiledType(LayeredSystem sys, Object extendsType, String fullTypeName) {
      if (extendsType instanceof BodyTypeDeclaration) {
         Object newExtType = ((BodyTypeDeclaration) extendsType).getCompiledClass();
         if (newExtType != null)
            extendsType = newExtType;
         else
            sys.error("Unable to resolve compiled interface for: " + fullTypeName);
      }

      Object compiledType = sys.getClassWithPathName(fullTypeName, null, false, true, false, true);
      // In some situations we might find a source file version of some class... the CFClass can only
      // point to the compiled class
      if (extendsType instanceof ParamTypeDeclaration) {
         ((ParamTypeDeclaration) extendsType).setBaseType(compiledType);
      }
      if (extendsType instanceof ArrayTypeDeclaration) {
         if (compiledType != null)
            ((ArrayTypeDeclaration) extendsType).componentType = compiledType;
         else
            sys.error("Unable to resolve compiled interface for: " + fullTypeName);
      }
      return extendsType;
   }

   /** Returns true if  is equal to child or is in the  */
   public static boolean isOuterTypeOf(BodyTypeDeclaration parentTD, TypeDeclaration enclType) {
      if (enclType == null)
         return false;
      if (ModelUtil.sameTypes(parentTD, enclType))
         return true;
       return isOuterTypeOf(parentTD, enclType.getEnclosingType());
   }

   public static String getDebugName(Object privRes) {
      if (ModelUtil.isField(privRes))
         return "field";
      if (ModelUtil.isMethod(privRes))
         return "method";
      if (ModelUtil.isEnum(privRes))
         return "enum";
      if (ModelUtil.isCompiledClass(privRes))
         return "compiled class";
      if (privRes instanceof TypeDeclaration)
         return "type";
      if (privRes == null)
         return "<null member>";
      return "<unknown member>";
   }

   public static Object[] getTypesFromExpressions(List<Expression> args) {
      if (args == null)
         return null;
      int sz = args.size();
      Object[] res = new Object[sz];
      for (int i = 0; i < sz; i++) {
         res[i] = args.get(i).getTypeDeclaration();
      }
      return res;
   }

   static boolean isRemoteMethod(LayeredSystem sys, Object methObj) {
      Object remoteAnnot = getAnnotation(methObj, "sc.obj.Remote");
      if (remoteAnnot != null) {
         String remoteRts = (String) getAnnotationValue(remoteAnnot, "remoteRuntimes");
         String localRts = (String) getAnnotationValue(remoteAnnot, "localRuntimes");
         boolean remote = true;
         String runtimeName = sys.getRuntimeName();
         boolean alreadyMatched = false;
         if (!StringUtil.isEmpty(remoteRts)) {
            remote = false;
            String[] remoteArr = StringUtil.split(remoteRts, ',');
            for (int i = 0; i < remoteArr.length; i++) {
               if (remoteArr[i].equals(runtimeName)) {
                  alreadyMatched = true;
                  remote = true;
                  break;
               }
            }
         }
         if (!StringUtil.isEmpty(localRts)) {
            String[] localArr = StringUtil.split(localRts, ',');
            for (int i = 0; i < localArr.length; i++) {
               if (localArr[i].equals(runtimeName)) {
                  if (alreadyMatched)
                     System.out.println("Warning: method " + methObj + " has conflicting definitions in remoteRuntime and localRuntime for: " + runtimeName + " - ignoring @Remote definition");
                  remote = false;
                  break;
               }
            }
         }
         return remote;
      }
      else
         return false;
   }

   /**
    * Returns true if the given method is declared to be local for the specified runtime - overrides the default detection of
    * a remote method call for those cases where there really is a method in the local runtime even if the system does not find
    * it.
    */
   static boolean isLocalDefinedMethod(LayeredSystem sys, Object methObj) {
      Object remoteAnnot = getAnnotation(methObj, "sc.obj.Remote");
      if (remoteAnnot != null) {
         String localRts = (String) getAnnotationValue(remoteAnnot, "localRuntimes");
         boolean local = false;
         String runtimeName = sys.getRuntimeName();
         if (!StringUtil.isEmpty(localRts)) {
            local = false;
            String[] localArr = StringUtil.split(localRts, ',');
            for (int i = 0; i < localArr.length; i++) {
               if (localArr[i].equals(runtimeName)) {
                  local = true;
                  break;
               }
            }
            return local;
         }
      }
      return false;
   }

   public static Map<String, Object> getAnnotations(Object def) {
      if (def instanceof ParamTypedMember) {
         return getAnnotations(((ParamTypedMember) def).getMemberObject());
      }
      if (def instanceof IDefinition)
         return ((IDefinition) def).getAnnotations();
      else if (def instanceof AnnotatedElement) {
         java.lang.annotation.Annotation[] annots = ((AnnotatedElement) def).getAnnotations();
         return createAnnotationsMap(annots);
      }
      else if (def instanceof IBeanMapper) {
         // TODO: For get/set methods, do we have to combine annotations here?
         IBeanMapper mapper = (IBeanMapper) def;
         Object gsel = mapper.getGetSelector();
         Map<String,Object> res = null;
         if (gsel != null)
            res = getAnnotations(gsel);
         Object ssel = mapper.getSetSelector();
         if (ssel != null && ssel != gsel) {
            Map<String,Object> nres = getAnnotations(ssel);
            if (nres != null && res != null)
               res.putAll(nres);
            else if (res == null)
               res = nres;
         }
         Object fsel = mapper.getField();
         if (fsel != null && fsel != ssel) {
            Map<String,Object> nres = getAnnotations(fsel);
            if (nres != null && res != null)
               res.putAll(nres);
            else if (res == null)
               res = nres;
         }
         return res;
      }
      else if (def instanceof VariableDefinition)
         return ((VariableDefinition)def).getDefinition().getAnnotations();
      else
         throw new UnsupportedOperationException();
   }

   public static Map<String, Object> createAnnotationsMap(java.lang.annotation.Annotation[] annotations) {
      if (annotations != null) {
         TreeMap<String,Object> res = new TreeMap<String,Object>();
         for (java.lang.annotation.Annotation annot:annotations) {
            Annotation myAnnot = Annotation.createFromElement(annot);
            Annotation.addToAnnotationsMap(res, myAnnot);
         }
         return res.size() == 0 ? null : res;
      }
      return null;
   }

   public static RuntimeStatus execForRuntime(LayeredSystem refSys, Layer refLayer, Object refTypeOrMember, LayeredSystem runtimeSys) {
      if (refTypeOrMember instanceof ParamTypedMember)
         refTypeOrMember = ((ParamTypedMember) refTypeOrMember).member;
      if (refTypeOrMember instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration td = (BodyTypeDeclaration) refTypeOrMember;
         if (td.isExcludedStub) // The stub is included even though it's underlying type has been excluded
            return RuntimeStatus.Enabled;
      }
      Object execAnnot = ModelUtil.getInheritedAnnotation(refSys, refTypeOrMember, "sc.obj.Exec", false, refLayer, false);
      if (execAnnot != null) {
         String execRuntimes = (String) ModelUtil.getAnnotationValue(execAnnot, "runtimes");
         if (execRuntimes != null && execRuntimes.length() > 0) {
            String[] rtNames = execRuntimes.split(",");
            for (String rtName:rtNames) {
               if (runtimeSys == null || rtName.equals(runtimeSys.getRuntimeName()) || rtName.equals(runtimeSys.getProcessName()))
                  return RuntimeStatus.Enabled;
               if (rtName.equals("default") && runtimeSys.isDefaultSystem())
                  return RuntimeStatus.Enabled;
               if (rtName.equals("server") && runtimeSys.serverEnabled)
                  return RuntimeStatus.Enabled;
               if (rtName.equals("client") && !runtimeSys.serverEnabled)
                  return RuntimeStatus.Enabled;
            }
            return RuntimeStatus.Disabled;
         }
         Boolean serverOnly = (Boolean) ModelUtil.getAnnotationValue(execAnnot, "serverOnly");
         Boolean clientOnly = (Boolean) ModelUtil.getAnnotationValue(execAnnot, "clientOnly");
         if (serverOnly != null && serverOnly) {
            if (clientOnly != null && clientOnly)
               System.err.println("Exec annotation for type: " + ModelUtil.getTypeName(refTypeOrMember) + " has both clientOnly and serverOnly");
            return runtimeSys.serverEnabled ? RuntimeStatus.Enabled : RuntimeStatus.Disabled;
         }
         if (clientOnly != null && clientOnly) {
            return !runtimeSys.serverEnabled ? RuntimeStatus.Enabled : RuntimeStatus.Disabled;
         }
      }
      if (ModelUtil.isMethod(refTypeOrMember) || ModelUtil.isField(refTypeOrMember) || refTypeOrMember instanceof IBeanMapper)
         return execForRuntime(refSys, refLayer, getEnclosingType(refTypeOrMember), runtimeSys);

      if (refTypeOrMember instanceof ParamTypeDeclaration)
         refTypeOrMember = ((ParamTypeDeclaration) refTypeOrMember).getBaseType();
      /*
      if (refTypeOrMember instanceof BodyTypeDeclaration) {
         if (refSys != runtimeSys)
            System.out.println("*** Note: found mismatching runtime in execForRuntime - make sure this is right!");
         return refSys == runtimeSys ? RuntimeStatus.Enabled : RuntimeStatus.Disabled;
      }
      else */
      return RuntimeStatus.Unset;
   }

   public static Object getPropertyTypeFromType(Object type, String propName) {
      Object prop = ModelUtil.definesMember(type, propName, JavaSemanticNode.MemberType.PropertyAnySet, null, null, false, true, null);
      if (prop == null) {
         return null;
      }
      else {
         return ModelUtil.getPropertyType(prop);
      }
   }

   public static String getTemplatePathName(Object objType) {
      if (objType instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) objType).getTemplatePathName();
      else
         throw new UnsupportedOperationException(); // don't believe we need this for compiled types but if we do, we'll need to generate an annotation and stuff it into the class.
   }

   /** The equals operator on TypeDeclaration is to compare the files so that's not a good test.  This prevents the same type-name/layer-name combo from being added to the list */
   public static boolean addUniqueLayerType(LayeredSystem sys, List<BodyTypeDeclaration> result, BodyTypeDeclaration res) {
      for (int i = 0; i < result.size(); i++) {
         if (ModelUtil.sameTypesAndLayers(sys, result.get(i), res))
            return false;
      }
      result.add(res);
      return true;
   }

   public static String getParameterString(Object meth) {
      if (meth instanceof IMethodDefinition) {
         return ((IMethodDefinition) meth).getParameterString();
      }
      else if (meth instanceof Method) {
         return RTypeUtil.getParameterString((Method) meth);
      }
      else {
         throw new UnsupportedOperationException();
      }
   }

   /**
    * For compile time access to whether or not we are dealing with a synchronize type.  TODO:
    * calls to 'addSyncType' are not reflected here which breaks the syncTypeFilter.  For now, we're leaving
    * the syncTypeFilter in as a system option to workaround this problem.  But you can update globalSyncTypes
    * to include other types as synchronized for when you need the syncTypeFilter.
    */
   public static boolean isSyncEnabled(Object type) {
      if (type instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration typeDecl = (BodyTypeDeclaration) type;
         if (typeDecl.getInheritedSyncProperties() != null)
            return true;
         // Because type is defined on the client, it may not have sync properties but still be synchronized
         // on the server.  To include "ServerToClient" mode, need to check the annotation and see if it's disabled.
         // Erring on the side of having sync enabled since this is for the type filter.
         SyncMode syncMode = BodyTypeDeclaration.getInheritedSyncMode(typeDecl.getLayeredSystem(), typeDecl);
         if (syncMode != null && syncMode != SyncMode.Disabled)
            return true;
      }
      String typeName = ModelUtil.getTypeName(type);
      // TODO: is this needed anymore? We now use the SyncTypeFilter annotation instead of global type names
      if (SyncManager.globalSyncTypeNames == null)
         return false;
      return SyncManager.globalSyncTypeNames.contains(typeName);
   }

   public static Set<String> getJSSyncTypes(LayeredSystem sys, Object type) {
      if (!(type instanceof BodyTypeDeclaration))
         System.err.println("*** Unable to get sync types from compiled class!");
      else {
         LayeredSystem jsSys = sys.getPeerLayeredSystem("js");
         if (!sys.hasActiveRuntime("js"))
            return Collections.emptySet();
         if (jsSys == null)
            System.out.println("*** getJSSyncTypes called on wrong system: " + sys.getProcessIdent()); // Happens if we call it from the JS system itself
         Object jsType = jsSys.getRuntimeTypeDeclaration(ModelUtil.getTypeName(type));
         HashSet<String> syncTypeNames = null;
         if (jsType instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration jsTypeDecl = (BodyTypeDeclaration) jsType;
            JavaSemanticNode.DepTypeCtx ctx = new JavaSemanticNode.DepTypeCtx();
            ctx.mode = JavaSemanticNode.DepTypeMode.SyncTypes;
            ctx.recursive = true;
            ctx.visited = new IdentityHashSet<Object>();
            Set<Object> syncTypes = jsTypeDecl.getDependentTypes(ctx);
            if (syncTypes.size() > 0) {
               if (syncTypeNames == null)
                  syncTypeNames = new HashSet<String>(syncTypes.size());
               for (Object syncType:syncTypes) {
                  if (syncType instanceof String)
                     syncTypeNames.add((String) syncType);
                  else
                     syncTypeNames.add(ModelUtil.getTypeName(syncType));
               }
            }
            if (jsTypeDecl.getSyncProperties() != null) {
               if (syncTypeNames == null)
                  syncTypeNames = new HashSet<String>(syncTypes.size());
               syncTypeNames.add(jsTypeDecl.getFullTypeName());
            }
         }
         BodyTypeDeclaration typeDecl = (BodyTypeDeclaration) type;
         JavaSemanticNode.DepTypeCtx ctx = new JavaSemanticNode.DepTypeCtx();
         ctx.mode = JavaSemanticNode.DepTypeMode.RemoteMethodTypes;
         ctx.recursive = true;
         ctx.visited = new IdentityHashSet<Object>();
         Set<Object> methTypes = typeDecl.getDependentTypes(ctx);
         if (methTypes.size() > 0) {
            if (syncTypeNames == null)
               syncTypeNames = new HashSet<String>(methTypes.size());
            for (Object methType:methTypes) {
               syncTypeNames.add(ModelUtil.getTypeName(methType));
            }
         }
         if (syncTypeNames != null)
            return syncTypeNames;
      }
      // Returning the empty set here - which means no sync types at all, not null which means to disable the filter
      return Collections.emptySet();
   }

   public static Object getEditorCreateMethod(LayeredSystem sys, Object typeObj) {
      Object[] methods = ModelUtil.getAllMethods(typeObj, "public", true, false, false);
      if (methods != null) {
         for (Object method:methods) {
            Object annot = ModelUtil.getAnnotation(method, "sc.obj.EditorCreate");
            if (annot != null) {
               if (ModelUtil.isConstructor(method)) {
                  if (ModelUtil.sameTypes(ModelUtil.getEnclosingType(method), typeObj))
                     return method;
               }
               return method;
            }
         }
      }
      // If it's set at the type level, pick the first constructor in the list.
      Object annot = ModelUtil.getInheritedAnnotation(sys, typeObj, "sc.obj.EditorCreate");
      if (annot != null) {
         Object[] constrs = ModelUtil.getConstructors(typeObj, null, true);
         // Refer the type itself when there's only the empty constructor
         if (constrs == null || constrs.length == 0 )
            return typeObj;
         return constrs[0];
      }
      return null;
   }

   public static JavaType getJavaTypeFromDefinition(LayeredSystem sys, Object def, Object definedInType) {
      if (ModelUtil.isMethod(def)) {
         if (ModelUtil.isSetMethod(def)) {
            return getSetMethodJavaType(sys, def, definedInType);
         }
         if (ModelUtil.isGetMethod(def)) {
            Object pt = ModelUtil.getReturnJavaType(def);
            if (pt instanceof JavaType)
               return (JavaType) pt;
            return JavaType.createFromParamType(sys, pt, null, definedInType);
         }
         throw new UnsupportedOperationException();
      }
      else if (ModelUtil.isField(def)) {
         return getFieldJavaType(sys, def, definedInType);
      }
      else if (def instanceof TypeDeclaration) {
         return JavaType.createJavaTypeFromName(((TypeDeclaration) def).getFullTypeName());
      }
      else if (def instanceof IBeanMapper) {
         return JavaType.createJavaTypeFromName(ModelUtil.getTypeName(((IBeanMapper) def).getPropertyType()));
      }
      else
         throw new UnsupportedOperationException();
   }

   public static boolean getExportProperties(LayeredSystem sys, Object type) {
      if (type instanceof ParamTypeDeclaration)
         type = ((ParamTypeDeclaration) type).getBaseType();
      if (type instanceof BodyTypeDeclaration) {
         return ((BodyTypeDeclaration) type).getExportProperties();
      }
      else if (ModelUtil.isCompiledClass(type)) {
         Boolean exportProperties = (Boolean) ModelUtil.getAnnotationValue(type, "sc.obj.CompilerSettings", "exportProperties");
         return exportProperties == null || exportProperties;
      }
      else
         throw new UnsupportedOperationException();
   }

   public static void addSyncTypeFilterTypes(Object type, Set<Object> types) {
      Object annot = ModelUtil.getAnnotation(type, "sc.obj.SyncTypeFilter");
      if (annot != null) {
         String[] filterTypeNames = (String[]) ModelUtil.getAnnotationValue(annot, "typeNames");
         if (filterTypeNames != null) {
            // Note: adding type names directly here - the caller will have to look for Strings type names or Object types
            for (String ftn:filterTypeNames)
               types.add(ftn);
         }
      }
   }

   public static DBTypeDescriptor getDBTypeDescriptor(LayeredSystem sys, Layer refLayer, Object typeDecl) {
      if (typeDecl instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) typeDecl).getDBTypeDescriptor();
      }
      else {
         System.err.println("*** Not caching DB type descriptor");
         return initDBTypeDescriptor(sys, refLayer, typeDecl);
      }

   }

   public static DBTypeDescriptor initDBTypeDescriptor(LayeredSystem sys, Layer refLayer, Object typeDecl) {
      ArrayList<Object> typeSettings = ModelUtil.getAllInheritedAnnotations(sys, typeDecl, "sc.db.DBTypeSettings", false, refLayer, false);
      // TODO: should check for annotations on the Layer of all types in the type tree. For each attribute, check the layer annotation if it's not set at the type level
      // TODO: need getAllLayerAnnotations(typeDecl, annotName)
      if (typeSettings != null) {
         boolean persist = true;
         String dataSourceName = null;
         String versionProp = null, primaryTableName = null;
         List<String> auxTableNames = null;
         for (Object annot:typeSettings) {
            Boolean tmpPersist  = (Boolean) ModelUtil.getAnnotationValue(annot, "persist");
            if (tmpPersist != null)
               persist = tmpPersist;
            String tmpDataSourceName  = (String) ModelUtil.getAnnotationValue(annot, "dataSourceName");
            if (tmpDataSourceName != null)
               dataSourceName = tmpDataSourceName;
            String tmpVersionProp = (String) ModelUtil.getAnnotationValue(annot, "versionProp");
            if (tmpVersionProp != null) {
               versionProp = tmpVersionProp;
            }
            String tmpPrimaryTableName = (String) ModelUtil.getAnnotationValue(annot, "primaryTable");
            if (tmpPrimaryTableName != null) {
               primaryTableName = tmpPrimaryTableName;
            }
            String tmpAuxTableNames = (String) ModelUtil.getAnnotationValue(annot, "auxTables");
            if (tmpAuxTableNames != null) {
               auxTableNames = new ArrayList<String>(Arrays.asList(tmpAuxTableNames.split(",")));
            }
         }

         if (persist) {
            if (dataSourceName == null) {
               DataSourceDef def = sys.defaultDataSource;
               dataSourceName = def == null ? null : def.jndiName;
               if (dataSourceName == null)
                  return null;
            }
            Object baseType = ModelUtil.getExtendsClass(typeDecl);
            DBTypeDescriptor baseTD = baseType != null && baseType != Object.class ? ModelUtil.getDBTypeDescriptor(sys, refLayer, baseType) : null;

            String fullTypeName = ModelUtil.getTypeName(typeDecl);

            DBTypeDescriptor dbTypeDesc = sys.getDBTypeDescriptor(fullTypeName);
            if (dbTypeDesc != null)
               return dbTypeDesc;

            String typeName = CTypeUtil.getClassName(fullTypeName);
            if (primaryTableName == null)
               primaryTableName = DBUtil.getSQLName(typeName);
            ArrayList<TableDescriptor> auxTables = new ArrayList<TableDescriptor>();
            Map<String,TableDescriptor> auxTablesIndex = new TreeMap<String,TableDescriptor>();
            ArrayList<TableDescriptor> multiTables = new ArrayList<TableDescriptor>();
            if (auxTableNames != null) {
               for (String auxTableName:auxTableNames)
                  auxTables.add(new TableDescriptor(auxTableName));
            }
            TableDescriptor primaryTable = new TableDescriptor(primaryTableName);

            ArrayList<Object> persistProps = new ArrayList<Object>();

            Object[] properties = ModelUtil.getDeclaredProperties(typeDecl, null, false, true, false);
            if (properties != null) {
               for (Object property:properties) {
                  Object idSettings = ModelUtil.getAnnotation(property, "sc.db.IdSettings");
                  String propName = ModelUtil.getPropertyName(property);
                  Object propType = ModelUtil.getPropertyType(property);

                  if (propName == null || propType == null)
                     continue;

                  if (idSettings != null) {
                     String idColumnName = null;
                     String idColumnType = null;
                     boolean definedByDB = true;

                     String tmpColumnName = (String) ModelUtil.getAnnotationValue(idSettings, "columnName");
                     if (tmpColumnName != null) {
                        idColumnName = tmpColumnName;
                     }

                     String tmpColumnType = (String) ModelUtil.getAnnotationValue(idSettings, "columnType");
                     if (tmpColumnType != null) {
                        idColumnType = tmpColumnType;
                     }

                     Boolean tmpDefinedByDB  = (Boolean) ModelUtil.getAnnotationValue(idSettings, "definedByDB");
                     if (tmpDefinedByDB != null) {
                        definedByDB = tmpDefinedByDB;
                     }

                     if (idColumnName == null)
                        idColumnName = DBUtil.getSQLName(propName);
                     if (idColumnType == null) {
                        idColumnType = DBUtil.getDefaultSQLType(propType);
                        if (idColumnType == null)
                           throw new IllegalArgumentException("Invalid property type: " + propType + " for id: " + idColumnName);
                     }

                     IdPropertyDescriptor idDesc = new IdPropertyDescriptor(propName, idColumnName, idColumnType, definedByDB);

                     primaryTable.addIdColumnProperty(idDesc);
                  }
               }
            }

            dbTypeDesc = new DBTypeDescriptor(typeDecl, baseTD, dataSourceName, primaryTable);

            sys.addDBTypeDescriptor(fullTypeName, dbTypeDesc);

            if (properties != null) {
               for (Object property:properties) {
                  Object idSettings = ModelUtil.getAnnotation(property, "sc.db.IdSettings");
                  String propName = ModelUtil.getPropertyName(property);
                  Object propType = ModelUtil.getPropertyType(property);

                  if (propName == null || propType == null)
                     continue;

                  if (idSettings != null) { // handled above
                     continue;
                  }
                  // Skip transient fields
                  if (ModelUtil.hasModifier(property, "transient"))
                     continue;
                  Object propSettings = ModelUtil.getAnnotation(property, "sc.db.DBPropertySettings");
                  String propTableName = null;
                  String propColumnName = null;
                  String propColumnType = null;
                  boolean propOnDemand = false;
                  boolean propAllowNull = false;
                  String propDataSourceName = null;
                  String propFetchGroup = null;
                  String propReverseProperty = null;
                  if (propSettings != null) {
                     Boolean tmpPersist  = (Boolean) ModelUtil.getAnnotationValue(propSettings, "persist");
                     if (tmpPersist != null && !tmpPersist) {
                        continue;
                     }

                     String tmpTableName = (String) ModelUtil.getAnnotationValue(propSettings, "tableName");
                     if (tmpTableName != null) {
                        propTableName = tmpTableName;
                     }

                     // TODO: should we specify columnNames and Types here as comma separated lists to deal with multi-column primary key properties (and possibly others that need more than one column?)
                     String tmpColumnName = (String) ModelUtil.getAnnotationValue(propSettings, "columnName");
                     if (tmpColumnName != null) {
                        propColumnName = tmpColumnName;
                     }

                     String tmpColumnType = (String) ModelUtil.getAnnotationValue(propSettings, "columnType");
                     if (tmpColumnType != null) {
                        propColumnType = tmpColumnType;
                     }

                     Boolean tmpOnDemand  = (Boolean) ModelUtil.getAnnotationValue(propSettings, "onDemand");
                     if (tmpOnDemand != null) {
                        propOnDemand = tmpOnDemand;
                     }
                     Boolean tmpAllowNull  = (Boolean) ModelUtil.getAnnotationValue(propSettings, "allowNull");
                     if (tmpAllowNull != null) {
                        propAllowNull = tmpAllowNull;
                     }

                     String tmpDataSourceName = (String) ModelUtil.getAnnotationValue(propSettings, "dataSourceName");
                     if (tmpDataSourceName != null) {
                        propDataSourceName = tmpDataSourceName;
                     }

                     String tmpFetchGroup = (String) ModelUtil.getAnnotationValue(propSettings, "fetchGroup");
                     if (tmpFetchGroup != null) {
                        propFetchGroup = tmpFetchGroup;
                     }

                     String tmpReverseProperty = (String) ModelUtil.getAnnotationValue(propSettings, "reverseProperty");
                     if (tmpReverseProperty != null) {
                        propReverseProperty = tmpReverseProperty;
                     }
                  }

                  if (propColumnName == null)
                     propColumnName = DBUtil.getSQLName(propName);
                  DBTypeDescriptor refDBTypeDesc = null;

                  boolean isMultiCol = false;

                  // TODO: should we handle array and set properties here?
                  boolean isArrayProperty = ModelUtil.isAssignableFrom(List.class, propType);
                  if (isArrayProperty) {
                     if (hasTypeParameters(propType)) {
                        propType = getTypeParameter(propType, 0);
                     }
                     else
                        propType = Object.class;
                  }

                  if (propColumnType == null) {
                     propColumnType = DBUtil.getDefaultSQLType(propType);
                     if (propColumnType == null) {
                        refDBTypeDesc = getDBTypeDescriptor(sys, refLayer, propType);
                        if (refDBTypeDesc == null) {
                           propColumnType = "json";
                        }
                        else {
                           TableDescriptor refTable = refDBTypeDesc.primaryTable;
                           if (refTable.idColumns.size() == 1)
                              propColumnType = DBUtil.getKeyIdColumnType(refTable.getIdColumns().get(0).columnType);
                           else {
                              isMultiCol = true;
                              StringBuilder cns = new StringBuilder();
                              StringBuilder cts = new StringBuilder();
                              for (int idx = 0; idx < refTable.idColumns.size(); idx++) {
                                 if (idx != 0) {
                                    cns.append(",");
                                    cts.append(",");
                                 }
                                 IdPropertyDescriptor idCol = refTable.idColumns.get(idx);
                                 cns.append(propColumnName);
                                 cns.append("_");
                                 cns.append(idCol.columnName);
                                 cts.append(DBUtil.getKeyIdColumnType(idCol.columnType));
                              }
                              propColumnName = cns.toString();
                              propColumnType = cts.toString();
                           }
                        }
                     }
                  }

                  boolean multiRow = false;
                  // TODO: should we allow scalar arrays - arrays of strings and stuff like that?
                  if (isArrayProperty && refDBTypeDesc != null)
                     multiRow = true;

                  DBPropertyDescriptor propDesc;

                  if (isMultiCol) {
                     propDesc = new MultiColPropertyDescriptor(propName, propColumnName,
                                                               propColumnType, propTableName, propAllowNull, propOnDemand,
                                                               propDataSourceName, propFetchGroup,
                                                               refDBTypeDesc == null ? null : refDBTypeDesc.getTypeName(),
                                                               multiRow, propReverseProperty);
                  }
                  else {
                     propDesc = new DBPropertyDescriptor(propName, propColumnName,
                                                         propColumnType, propTableName, propAllowNull, propOnDemand,
                                                         propDataSourceName, propFetchGroup,
                                                         refDBTypeDesc == null ? null : refDBTypeDesc.getTypeName(),
                                                         multiRow, propReverseProperty);
                  }

                  propDesc.refDBTypeDesc = refDBTypeDesc;

                  TableDescriptor propTable = primaryTable;

                  if (multiRow) {
                     // NOTE: this table name may be reassigned after we resolve references to other type descriptors
                     // since the reference semantics determine how the reference is stored.  For example, if this is a
                     // one-to-many relationship, we'll use the table for the 'one' side to avoid an extra multi-table
                     if (propTableName == null)
                        propTableName = primaryTableName + "_" + propColumnName;

                     TableDescriptor multiTable = new TableDescriptor(propTableName);
                     propTable = multiTable;
                     multiTable.multiRow = true;
                     multiTables.add(multiTable);
                  }
                  else if (propTableName != null && !propTableName.equals(propTable.tableName)) {
                     propTable = auxTablesIndex.get(propTableName);
                     if (propTable == null) {
                        TableDescriptor auxTable = new TableDescriptor(propTableName);
                        auxTablesIndex.put(propTableName, auxTable);
                        auxTables.add(auxTable);
                        propTable = auxTable;
                     }
                  }
                  propTable.addColumnProperty(propDesc);
               }
            }

            dbTypeDesc.initTables(auxTables, multiTables, versionProp);

            return dbTypeDesc;
         }
      }
      return null;
   }

   public static DBPropertyDescriptor getDBPropertyDescriptor(LayeredSystem sys, Layer refLayer, Object propObj) {
      Object enclType = getEnclosingType(propObj);
      if (enclType != null) {
         DBTypeDescriptor typeDesc = getDBTypeDescriptor(sys, refLayer, enclType);
         String propName = ModelUtil.getPropertyName(propObj);
         if (typeDesc != null) {
            typeDesc.init();
            return typeDesc.getPropertyDescriptor(propName);
         }
      }
      return null;
   }

   public static DBProvider getDBProviderForType(LayeredSystem sys, Layer refLayer, Object typeObj) {
      DBTypeDescriptor typeDesc = ModelUtil.getDBTypeDescriptor(sys, ModelUtil.getLayerForType(sys, typeObj), typeObj);
      if (typeDesc != null) {
         String dataSourceName = typeDesc.dataSourceName;
         DataSourceDef dataSource = sys.getDataSourceDef(dataSourceName);
         if (dataSource != null) {
            return sys.dbProviders.get(dataSource.provider);
         }
      }
      return null;
   }

   public static DBProvider getDBProviderForPropertyDesc(LayeredSystem sys, Layer refLayer, DBPropertyDescriptor propDesc) {
      String dataSourceName = propDesc.dataSourceName;
      if (dataSourceName == null)
         dataSourceName = propDesc.dbTypeDesc.dataSourceName;
      DataSourceDef dataSource = sys.getDataSourceDef(dataSourceName);
      if (dataSource != null) {
         return sys.dbProviders.get(dataSource.provider);
      }
      return null;
   }

   public static DBProvider getDBProviderForProperty(LayeredSystem sys, Layer refLayer, Object propObj) {
      Object enclType = ModelUtil.getEnclosingType(propObj);
      String propName = ModelUtil.getPropertyName(propObj);
      DBTypeDescriptor typeDesc = enclType == null ? null : ModelUtil.getDBTypeDescriptor(sys, ModelUtil.getLayerForMember(sys, propObj), enclType);
      if (typeDesc != null) {
         DBPropertyDescriptor propDesc = typeDesc.getPropertyDescriptor(propName);
         if (propDesc != null) {
            String dataSourceName = propDesc.dataSourceName;
            if (dataSourceName == null)
               dataSourceName = typeDesc.dataSourceName;
            DataSourceDef dataSource = sys.getDataSourceDef(dataSourceName);
            if (dataSource != null) {
               return sys.dbProviders.get(dataSource.provider);
            }
         }
      }
      return null;
   }
}
