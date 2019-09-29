/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

import sc.bind.BindingContext;
import sc.obj.ISystemExitListener;
import sc.obj.ScopeDefinition;
import sc.type.IBeanMapper;

import java.util.Iterator;
import java.util.Map;

/** A simple interface to bind the interpreter to the static type system. */
public interface IDynamicSystem {
   IBeanMapper getPropertyMapping(Object type, String dstPropName);
   
   boolean isTypeObject(Object obj);

   boolean isSTypeObject(Object obj);

   Object getReturnType(Object method);

   Object[] getParameterTypes(Object dynMethod);

   boolean isAssignableFrom(Object type1, Object type2);

   Object getAnnotation(Object def, Class annotClass);

   Object getAnnotationByName(Object def, String annotName);

   Object[] getMethods(Object type, String methodName);

   Object getDeclaringClass(Object method);

   Object invokeMethod(Object obj, Object method, Object[] paramValues);

   String getMethodName(Object method);

   String getPropertyName(Object propObj);

   Object getPropertyType(Object propObj);

   String getMethodTypeSignature(Object method);

   Object evalCast(Object type, Object value);

   boolean isNonCompiledType(Object obj);

   int getStaticPropertyCount(Object cl);

   int getPropertyCount(Object cl);

   IBeanMapper[] getProperties(Object typeObj);

   IBeanMapper[] getStaticProperties(Object typeObj);

   boolean isDynamicObject(Object obj);

   boolean isInstance(Object pt, Object argValue);

   Object getAnnotationValue(Object settings, String s);

   Object getAnnotationValue(Object typeObj, String annotName, String valueName);

   Object getStaticProperty(Object object, String propertyName);

   void setStaticProperty(Object object, String propertyName, Object valueToSet);

   Object createInstance(Object typeObj, String constrSig, Object[] params);

   Object createInnerInstance(Object typeObj, Object outerObj, String constrSig, Object[] params);

   Object[] getAllMethods(Object typeObj, String modifier, boolean hasModifier);

   Object getEnclosingType(Object memberType, boolean instOnly);

   void addDynInstance(String typeName, Object inst);

   void addDynObject(String typeName, Object inst);

   void addDynInnerInstance(String typeName, Object inst, Object outer);

   void addDynInnerObject(String typeName, Object inst, Object outer);

   Object[] resolveTypeGroupMembers(String typeGroupName);

   String getTypeName(Object type, boolean includeDims);

   Class getCompiledClass(Object type);

   String[] getDynSrcDirs();

   String[] getDynSrcPrefixes();

   void refreshType(String typeName);

   Object[] getObjChildren(Object inst, String scopeName, boolean create);

   String[] getObjChildrenNames(Object typeObj, String scopeName);

   Object[] getObjChildrenTypes(Object typeObj, String scopeName);

   boolean hasModifier(Object def, String modifier);

   Object getReverseBindingMethod(Object method);

   IBeanMapper getConstantPropertyMapping(Object type, String dstPropName);

   Class loadClass(String className);

   Object resolveMethod(Object type, String methodName, Object returnType, String paramSig);

   void dispose(Object obj);

   String getInnerTypeName(Object type);

   Object resolveRuntimeName(String name, boolean create, boolean returnTypes);

   Object findType(String typeName);

   boolean isObject(Object obj);

   boolean isObjectType(Object type);

   String getObjectName(Object obj, Map<Object,String>idMap, Map<String,Integer> typeIdCounts);

   boolean isRootedObject(Object obj);

   int getNumInnerTypeLevels(Object obj);

   Object getOuterObject(Object obj);

   String getPackageName(Object type);

   Object getInheritedAnnotationValue(Object typeObj, String annotName, String attName);

   ClassLoader getSysClassLoader();

   boolean isEnumConstant(Object obj);

   boolean isEnumType(Object type);

   Object getEnumConstant(Object typeObj, String enumConstName);

   Object getExtendsType(Object type);

   Iterator<Object> getInstancesOfTypeAndSubTypes(String typeName);

   String getInheritedScopeName(Object obj);

   void registerTypeChangeListener(ITypeChangeListener type);

   int getLayerPosition(Object type);

   boolean applySyncLayer(String lang, String destName, String scopeName, String code, boolean applyRemoteReset, boolean allowCodeEval, BindingContext ctx);

   Object newInnerInstance(Object typeObj, Object outerObj, String constrSig, Object[] params);

   boolean isComponentType(Object type);

   boolean isArray(Object type);

   Object getComponentType(Object arrayType);

   Object getPropertyAnnotationValue(Object typeObj, String propName, String annotName, String attName);

   void addDynListener(IDynListener listener);

   ScopeDefinition getScopeByName(String scopeName);

   boolean needsSync(Object type);

   void addSystemExitListener(ISystemExitListener listener);
}
