/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.classfile;

import sc.lang.SemanticNode;
import sc.lang.sc.ModifyDeclaration;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.lifecycle.ILifecycle;
import sc.type.*;
import sc.util.CoalescedHashMap;
import sc.util.FileUtil;
import sc.lang.java.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** The class defined from a ClassFile, i.e. read directly from the binary .class representation. */
public class CFClass extends SemanticNode implements ITypeDeclaration, ILifecycle, IDefinition {
   ClassFile classFile;
   Object extendsType;
   List<Object> implementsTypes;
   Layer layer;
   LayeredSystem system;
   public List<TypeParameter> typeParameters;

   CoalescedHashMap<String,Object[]> methodsByName;  // This does include super-type methods
   CoalescedHashMap<String,CFField> fieldsByName;    // This does not include the super type fields

   String fullTypeName;

   CFClassSignature signature;

   CFClass(ClassFile cl, Layer layer) {
      classFile = cl;
      this.layer = layer;
      this.system = layer.getLayeredSystem();
   }

   CFClass(ClassFile cl, LayeredSystem sys) {
      classFile = cl;
      this.system = sys;
   }

   private static CFClass load(String classFileName, LayeredSystem system, Layer layer) {
      ClassFile file = null;
      File classFile = new File(classFileName);
      if (!classFile.exists())
         return null;
      try {
         InputStream input = new FileInputStream(classFile);
         if (layer == null) {
            if (system != null)
               file = new ClassFile(input, system);
            else
               file = new ClassFile(input);
         }
         else
            file = new ClassFile(input, layer);
         file.initialize();
         return file.getCFClass();
      }
      catch (IOException exc) {
         System.err.println("*** Error reading class: " + classFileName);
         return null;
      }
      finally {
         if (file != null)
            file.close();
      }
   }

   public static CFClass load(String classFileName, LayeredSystem system) {
      return load(classFileName, system, null);
   }

   public static CFClass load(String classFileName, Layer layer) {
      return load(classFileName, null, layer);
   }

   public static CFClass load(String classFileName) {
      return load(classFileName, (LayeredSystem) null, null);
   }

   public static CFClass load(String dirName, String classPathName, LayeredSystem system) {
      return load(FileUtil.concat(dirName, classPathName), system);
   }

   public static CFClass load(ZipFile zipFile, String classPathName, LayeredSystem system) {
      return load(zipFile, classPathName, system, null);
   }

   public static CFClass load(ZipFile zipFile, String classPathName, Layer layer) {
      return load(zipFile, classPathName, null, layer);
   }

   private static CFClass load(ZipFile zipFile, String classPathName, LayeredSystem system, Layer layer) {
      ClassFile file = null;
      try {
         ZipEntry zipEnt = zipFile.getEntry(classPathName);
         if (zipEnt != null) {
            InputStream input = zipFile.getInputStream(zipEnt);
            if (input != null) {
               if (layer == null)
                  file = new ClassFile(input, system);
               else
                  file = new ClassFile(input, layer);
               file.initialize();
               return file.getCFClass();
            }
         }
         return null;
      }
      catch (IOException exc) {
         System.err.println("*** Error reading class: " + classPathName + " in zip: " + zipFile);
         return null;
      }
      finally {
         if (file != null)
            file.close();
      }
   }

   public void initialize() {
      if (initialized)
         return;

      initialized = true;

      // Need these before we resolve all other references
      ClassFile.SignatureAttribute att = (ClassFile.SignatureAttribute) classFile.attributesByName.get("Signature");
      if (att != null) {
         signature = SignatureLanguage.getSignatureLanguage().parseClassSignature(att.signature);
         signature.parentNode = this;
         typeParameters = signature.typeParameters;
      }
   }

   private void initMethodAndFieldIndex() {
      CFMethod[] methods = classFile.methods;
      int tableSize = methods.length;

      CoalescedHashMap<String,Object[]> superCache = null;

      if (extendsType != null) {
         superCache = ModelUtil.getMethodCache(extendsType);
         if (superCache != null)
            tableSize += superCache.size;
      }
      if (implementsTypes != null) {
         int numInterfaces = implementsTypes.size();
         CoalescedHashMap<String,Object[]> implCache;
         for (int i = 0; i < numInterfaces; i++) {
            implCache = ModelUtil.getMethodCache(implementsTypes.get(i));
            if (implCache != null)
               tableSize += implCache.size;
         }
      }

      CoalescedHashMap<String,Object[]> cache = methodsByName = new CoalescedHashMap<String,Object[]>(tableSize);

      for (CFMethod meth:methods) {
         Object[] meths = cache.get(meth.name);
         if (meths == null) {
            meths = new Object[1];
         }
         else {
            Object[] newMeths = new Object[meths.length+1];
            System.arraycopy(meths, 0, newMeths, 0, meths.length);
            meths = newMeths;
         }
         cache.put(meth.name, meths);
         meths[meths.length-1] = meth;
      }

      if (superCache != null) {
         mergeSuperTypeMethods(superCache);
      }
      if (implementsTypes != null) {
         CoalescedHashMap<String,Object[]> implCache;
         for (int k = 0; k < implementsTypes.size(); k++) {
            implCache = ModelUtil.getMethodCache(implementsTypes.get(k));
            mergeSuperTypeMethods(implCache);
         }
      }

      // These do not include the super-type fields - instead, we aggregate on-the-fly in the various methods below.
      // The thinking is that it is pretty fast to do a property name lookup but more complex with methods given
      // the overriding rules.
      CFField[] fields = classFile.fields;
      fieldsByName = new CoalescedHashMap<String, CFField>(fields.length);
      for (CFField field:fields) {
         fieldsByName.put(field.name, field);
      }
   }

   public void start() {
      if (started)
         return;
      if (!initialized)
         initialize();
      
      started = true;

      if (signature != null) {
         CFClassSignature sig = signature;
         sig.parentNode = this;
         extendsType = sig.extendsType != null ? sig.extendsType.getTypeDeclaration() : null;
         if (extendsType instanceof ParamTypeDeclaration)
            extendsType = ((ParamTypeDeclaration) extendsType).getBaseType();
         if (sig.implementsTypes != null) {
            implementsTypes = new ArrayList<Object>(sig.implementsTypes.size());
            for (int i = 0; i < sig.implementsTypes.size(); i++) {
               String implTypeCFName = ((ClassType)sig.implementsTypes.get(i)).getCompiledTypeName();
               Object implType = system.getClassFromCFName(implTypeCFName, null);
               if (implType == null)
                  System.out.println("*** Can't find interface: " + implTypeCFName);
               implementsTypes.add(implType);
            }
         }
         signature = null; // Throwing this away as we don't need it anymore...
      }
      else {
         String extendsTypeName = classFile.getExtendsTypeName();
         if (extendsTypeName != null)
            extendsType = system.getTypeDeclaration(extendsTypeName);
         int numInterfaces = classFile.getNumInterfaces();
         if (numInterfaces > 0) {
            implementsTypes = new ArrayList<Object>(numInterfaces);
            for (int i = 0; i < numInterfaces; i++) {
               Object implType = system.getClassFromCFName(classFile.getInterfaceCFName(i), null);
               if (implType == null)
                  System.out.println("*** Can't find interface: " + classFile.getInterfaceName(i));
               implementsTypes.add(implType);
            }
         }
      }

      // Must be done after we've defined the type for this class.
      initMethodAndFieldIndex();
   }

   private void mergeSuperTypeMethods(CoalescedHashMap<String,Object[]> superCache) {
      Object[] keys = superCache.keyTable;
      Object[] values = superCache.valueTable;
      for (int i = 0; i < keys.length; i++) {
         String key = (String) keys[i];
         if (key != null) {
            Object[] superMethods = (Object[]) values[i];
            Object[] thisMethods = methodsByName.get(key);
            if (thisMethods == null)
               methodsByName.put(key, superMethods);
            else {
               Object[] origThisMeths = thisMethods;
               for (int s = 0; s < superMethods.length; s++) {
                  int t;
                  Object sMeth = superMethods[s];
                  for (t = 0; t < thisMethods.length; t++) {
                     if (ModelUtil.overridesMethod(thisMethods[t], sMeth))
                        break;
                  }
                  if (t == thisMethods.length) {
                     int newIx = thisMethods.length;
                     Object[] newThis = new Object[newIx+1];
                     System.arraycopy(thisMethods, 0, newThis, 0, newIx);
                     thisMethods = newThis;
                     newThis[newIx] = sMeth;
                  }
               }
               if (origThisMeths != thisMethods)
                  methodsByName.put(key, thisMethods);
            }
         }
      }
   }

   public boolean isAssignableFrom(ITypeDeclaration other) {
      if (other instanceof ArrayTypeDeclaration)
         return false;
      return other.isAssignableTo(this);
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

      if (implementsTypes != null) {
         int numInterfaces = implementsTypes.size();
         for (int i = 0; i < numInterfaces; i++) {
            String interfaceName = classFile.getInterfaceName(i);
            if (otherName.equals(interfaceName))
               return true;
            Object implType = implementsTypes.get(i);
            if (implType instanceof ITypeDeclaration) {
               if (((ITypeDeclaration) implType).isAssignableTo(other))
                  return true;
            }
            else if (implType != Object.class)
               System.out.println("*** Unable to test impl type: " + implType);
         }
      }
      if (extendsType != null) {
         String extendsName = classFile.getExtendsTypeName();
         if (extendsName.equals(otherName))
            return true;
         if (extendsType instanceof ITypeDeclaration)
            return ((ITypeDeclaration) extendsType).isAssignableTo(other);
         // else - java.lang.Class
         // Classes here should be only java.lang classes and so should not match from this point on
      }
      return false;
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
      return false;
   }

   public String getTypeName() {
      return CTypeUtil.getClassName(getFullTypeName());
   }

   public String getFullTypeName() {
      if (fullTypeName == null)
         fullTypeName = classFile.getTypeName();
      return fullTypeName;
   }

   public String getFullTypeName(boolean includeDims, boolean includeTypeParams) {
      return getFullTypeName(); // Can't have dims or type parameters with bound values
   }

   public String getFullBaseTypeName() {
      return classFile.getTypeName();
   }

   public String getInnerTypeName() {
      return getTypeName(); // TODO: does this need to handle inner types
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

   public Object definesMethod(String name, List<?> parametersOrExpressions, ITypeParamContext ctx, Object refType, boolean isTransformed) {
      if (!started)
         start();

      Object meth = ModelUtil.getMethod(this, name, refType, ctx, ModelUtil.varListToTypes(parametersOrExpressions));
      if (meth != null)
         return meth;

      if (extendsType != null) {
         meth = ModelUtil.definesMethod(extendsType, name, parametersOrExpressions, ctx, refType, isTransformed);
         if (meth != null)
            return meth;
      }
      if (implementsTypes != null) {
         int numInterfaces = implementsTypes.size();
         for (int i = 0; i < numInterfaces; i++) {
            Object implType = implementsTypes.get(i);
            if (implType != null) {
               meth = ModelUtil.definesMethod(implType, name, parametersOrExpressions, ctx, refType, isTransformed);
               if (meth != null)
                  return meth;
            }
         }
      }
      return null;
   }

   public Object declaresConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx) {
      return definesConstructor(parametersOrExpressions, ctx, false);
   }

   public Object definesConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx, boolean isTransformed) {
      if (!started)
         start();
      Object[] meths = methodsByName.get("<init>");  //To change body of implemented methods use File | Settings | File Templates.
      if (meths == null)
         return null;

      return ModelUtil.definesConstructor(this, Arrays.asList(ModelUtil.parametersToTypeArray(parametersOrExpressions, ctx)), ctx, this, isTransformed);
   }

   public Object definesMember(String name, EnumSet<JavaSemanticNode.MemberType> mtype, Object refType, TypeContext ctx) {
      return definesMember(name, mtype, refType, ctx, false, false);
   }

   public Object definesMember(String name, EnumSet<JavaSemanticNode.MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      if (!started)
         start();

      // Properties take precedence over fields
      boolean needsGet = mtype.contains(JavaSemanticNode.MemberType.GetMethod);
      boolean needsSet = mtype.contains(JavaSemanticNode.MemberType.SetMethod);
      if (needsGet || needsSet) {
         CFMethod[] methods = classFile.methods;
         for (int i = 0; i < methods.length; i++) {
            CFMethod meth = methods[i];
            if (!meth.isInitialized())
               meth.initialize();
            if (meth.propertyName != null && meth.propertyName.equals(name) && (refType == null || ModelUtil.checkAccess(refType, meth))) {
               if (needsGet && meth.propertyMethodType.isGet())
                  return meth;
               if (needsSet && meth.propertyMethodType == PropertyMethodType.Set)
                  return meth;
            }
         }
      }

      Object res;
      if (mtype.contains(JavaSemanticNode.MemberType.Field)) {
         CFField field = fieldsByName.get(name);
         if (field != null)
            return field;
      }

      if (mtype.contains(JavaSemanticNode.MemberType.Enum) && isEnum()) {
         CFField field = fieldsByName.get(name);
         if (field != null)
            return field;
      }

      if (extendsType != null) {
         res = ModelUtil.definesMember(extendsType, name, mtype, refType, ctx, skipIfaces, isTransformed);
         if (res != null)
            return res;
      }
      if (implementsTypes != null && !skipIfaces) {
         int numInterfaces = implementsTypes.size();
         for (int i = 0; i < numInterfaces; i++) {
            Object implType = implementsTypes.get(i);
            if (implType != null) {
               res = ModelUtil.definesMember(implType, name, mtype, refType, ctx, skipIfaces, isTransformed);
               if (res != null)
                  return res;
            }
         }
      }
      return null;
   }

   public Object getInnerType(String name, TypeContext ctx) {
      if (classFile.hasInnerClass(name)) {
         if (layer == null)
            return system.getInnerCFClass(getFullTypeName(), name);
         return layer.getInnerCFClass(getFullTypeName(), name);
      }

      return null;
   }

   public boolean implementsType(String otherName) {
      if (!started)
         start();

      if (otherName.equals(getFullTypeName()))
         return true;

      if (implementsTypes != null) {
         int numInterfaces = implementsTypes.size();
         for (int i = 0; i < numInterfaces; i++) {
            String interfaceName = classFile.getInterfaceName(i);
            if (otherName.equals(interfaceName))
               return true;
            Object implType = implementsTypes.get(i);
            if (implType != null && ModelUtil.implementsType(implType, otherName))
               return true;
         }
      }
      if (extendsType != null) {
         String extendsName = classFile.getExtendsTypeName();
         if (extendsName.equals(otherName))
            return true;
         return ModelUtil.implementsType(extendsType, otherName);
      }
      return false;
   }

   public Object getInheritedAnnotation(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      Object annot = ModelUtil.getAnnotation(this, annotationName);
      if (annot != null)
         return annot;

      Object superType = getDerivedTypeDeclaration();
      annot = ModelUtil.getInheritedAnnotation(system, superType, annotationName, skipCompiled, refLayer, layerResolve);
      if (annot != null)
         return annot;

      if (implementsTypes != null) {
         int numInterfaces = implementsTypes.size();
         for (int i = 0; i < numInterfaces; i++) {
            Object implType = implementsTypes.get(i);
            if ((annot = ModelUtil.getInheritedAnnotation(system, implType, annotationName, skipCompiled, refLayer, layerResolve)) != null)
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

      Object superType = getDerivedTypeDeclaration();
      ArrayList<Object> superRes = ModelUtil.getAllInheritedAnnotations(system, superType, annotationName, skipCompiled, refLayer, layerResolve);
      if (superRes != null) {
         res = ModelUtil.appendLists(res, superRes);
      }

      if (implementsTypes != null) {
         int numInterfaces = implementsTypes.size();
         for (int i = 0; i < numInterfaces; i++) {
            Object implType = implementsTypes.get(i);
            if ((superRes = ModelUtil.getAllInheritedAnnotations(system, implType, annotationName, skipCompiled, refLayer, layerResolve)) != null)
               res = ModelUtil.appendLists(res, superRes);
         }
      }
      return res;
   }

   public Object getDerivedTypeDeclaration() {
      return extendsType;
   }

   public Object getExtendsTypeDeclaration() {
      return extendsType;
   }

   public Object getExtendsType() {
      return extendsType;
   }

   public CoalescedHashMap getMethodCache() {
      if (!started)
         start();
      return methodsByName;
   }

   public List<Object> getMethods(String methodName, String modifier, boolean includeExtends) {
      if (!started)
         start();

      Object[] meths = methodsByName.get(methodName);  //To change body of implemented methods use File | Settings | File Templates.
      if (meths == null) {
         return null;
      }
      if (modifier != null) {
         List<Object> res = null;
         int sz = meths.length;
         boolean removing = false;
         for (int i = 0; i < sz; i++) {
            Object meth = meths[i];
            if (!ModelUtil.hasModifier(meth, modifier)) {
               if (res == null) {
                  if (i == 0)
                     removing = true;
                  else if (!removing) {
                     res = new ArrayList<Object>(sz-1);
                     for (int j = 0; j < i; j++)
                        res.add(meths[j]);
                  }
               }
            }
            else if (removing) {
               res = new ArrayList<Object>(sz-i);
               res.add(meths[i]);
               removing = false;
            }
         }
         if (res != null)
            return res;
         if (removing)
            return null;
      }
      return Arrays.asList(meths);
   }

   public List<Object> getAllMethods(String modifier, boolean hasModifier, boolean isDyn, boolean overridesComp) {
      CFMethod[] meths = classFile.methods;
      if (meths == null || isDyn || overridesComp)
         return null;
      int sz = meths.length;
      ArrayList<Object> res = new ArrayList<Object>(sz);
      for (int i = 0; i < sz; i++) {
         Object meth = meths[i];
         if (modifier == null || hasModifier == ModelUtil.hasModifier(meth, modifier))
            res.add(meth);
      }
      return res;
   }


   public List<Object> getAllProperties(String modifier, boolean includeAssigns) {
      CFField[] fields = classFile.fields;
      CFMethod[] methods = classFile.methods;

      ArrayList<Object> res = new ArrayList<Object>();

      if (fields != null) {
         int sz = fields.length;
         for (int i = 0; i < sz; i++) {
            Object field = fields[i];
            if (modifier == null || ModelUtil.hasModifier(field, modifier))
               res.add(field);
         }
      }
      if (methods != null) {
         int sz = methods.length;
         for (int i = 0; i < sz; i++) {
            CFMethod method = methods[i];
            if (!method.isInitialized())
               method.initialize();
            if (method.propertyName != null) {
               if (modifier == null || ModelUtil.hasModifier(method, modifier)) {
                  if (method.propertyMethodType == PropertyMethodType.Set) {
                     int j, rs = res.size();
                     // Avoid duplicates for get/set methods
                     for (j = 0; j < rs; j++) {
                        Object o = res.get(j);
                        if (o instanceof CFMethod &&
                            ((CFMethod) o).propertyName.equals(method.propertyName))
                           break;
                     }
                     if (j == rs)
                        res.add(method);
                  }
                  else
                     res.add(method);
               }
            }
         }
      }
      return res;
   }

   public List<Object> getAllFields(String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified) {
      CFField[] fields = classFile.fields;
      if (fields == null)
         return null;
      int sz = fields.length;
      ArrayList<Object> res = new ArrayList<Object>(sz);
      for (int i = 0; i < sz; i++) {
         Object field = fields[i];
         if (modifier == null || ModelUtil.hasModifier(field, modifier) == hasModifier)
            res.add(field);
      }
      return res;
   }

   public List<Object> getAllInnerTypes(String modifier, boolean thisClassOnly) {
      int num = classFile.getNumInnerClasses();
      ArrayList<Object> innerTypes = new ArrayList<Object>(num);
      for (int i = 0; i < num; i++) {
         String innerName = classFile.getInnerClassName(i);
         Object innerType = getInnerType(innerName, null);
         if (innerType == null)
            System.err.println("*** Can't find inner type: " + innerName + " of: " + getTypeName());
         else if (modifier == null || ModelUtil.hasModifier(innerType, modifier))
            innerTypes.add(innerType);
      }
      return innerTypes;
   }

   public DeclarationType getDeclarationType() {
      if (ModelUtil.isObjectType(this))
         return DeclarationType.OBJECT;
      if (isInterface())
         return DeclarationType.INTERFACE;
      if (isEnum())
         return DeclarationType.ENUM;
      return DeclarationType.CLASS;
   }

   public Object getClass(String className, boolean useImports) {
      return getTypeDeclaration(className);
   }

   public Object findTypeDeclaration(String typeName, boolean addExternalReference) {
      if (typeParameters != null) {
         int sz = typeParameters.size();
         for (int i = 0; i < sz; i++) {
            TypeParameter tp = typeParameters.get(i);
            if (tp.name.equals(typeName))
               return tp;
         }
      }
      return getTypeDeclaration(typeName);
   }

   public JavaModel getJavaModel() {
      return null;
   }

   public LayeredSystem getLayeredSystem() {
      return system;
   }

   public List<TypeParameter> getClassTypeParameters() {
      if (!initialized)
         initialize();
      return typeParameters;
   }

   public Object[] getConstructors(Object refType) {
      // TODO: enforce ref type here?
      return methodsByName.get("<init>");
   }

   public boolean isComponentType() {
      return implementsType("sc.obj.IComponent");
   }

   public DynType getPropertyCache() {
      return TypeUtil.getPropertyCache(getCompiledClass());
   }

   public void validate() {
   }

   public boolean isInitialized() {
      return initialized;
   }

   public boolean isStarted() {
      return started;
   }

   public boolean isValidated() {
      return true;
   }

   public void stop() {
   }

   public Object getTypeDeclaration(String name) {
      if (system == null)
         return RTypeUtil.loadClass(name);
      return system.getTypeDeclaration(name);
   }

   public Object getAnnotation(String annotName) {
      return classFile.getAnnotation(annotName);
   }

   public boolean hasModifier(String modifierName) {
      return (classFile.accessFlags & ClassFile.modifierNameToAccessFlag(modifierName)) != 0;
   }

   public AccessLevel getAccessLevel(boolean explicitOnly) {
      return ClassFile.flagsToAccessLevel(classFile.accessFlags);
   }

   public boolean isInterface() {
      return (classFile.accessFlags & Modifier.INTERFACE) != 0;
   }

   public String modifiersToString(boolean includeAnnotations, boolean includeAccess, boolean includeFinal, boolean includeScope, boolean abs, JavaSemanticNode.MemberType filterType) {
      // TODO includeAnnotations, includeFinal
      int flags = classFile.accessFlags;
      if (!includeFinal)
         flags = flags & ~(Modifier.FINAL);
      return includeAccess ? Modifier.toString(flags) : "";
   }

   private final static int ACC_ANNOTATION = 0x2000; // TODO: Modifier.ANNOTATION
   private final static int ACC_ENUM = 0x4000; // TODO: Modifier.ENUM

   //private final static int ACC_BRIDGE = 0x0040; // A bridge method, generated by the

   // TODO: should use this flag to turn on/off var-args detection in ModelUtil.getMethod.
   //private final static int ACC_VARARGS = 0x0080;  // method declared with varargs

   public boolean isAnnotation() {
      return (classFile.accessFlags & ACC_ANNOTATION) != 0;
   }

   public boolean isEnum() {
      return (classFile.accessFlags & ACC_ENUM) != 0;
   }

   public Object getEnclosingType() {
      String outer = classFile.getOuterClassName();
      if (outer == null)
         return null;
      return getTypeDeclaration(outer);
   }

   public boolean isString() {
      return classFile.getCFClassName().equals("java/lang/String");
   }

   public boolean isDouble() {
      return classFile.getCFClassName().equals("java/lang/Double");
   }

   public boolean isBoolean() {
      return classFile.getCFClassName().equals("java/lang/Boolean");
   }

   public boolean isCharacter() {
      return classFile.getCFClassName().equals("java/lang/Character");
   }

   public boolean isFloat() {
      return classFile.getCFClassName().equals("java/lang/Float");
   }

   public boolean isInteger() {
      return classFile.getCFClassName().equals("java/lang/Integer");
   }

   public boolean isLong() {
      return classFile.getCFClassName().equals("java/lang/Long");
   }

   public boolean isShort() {
      return classFile.getCFClassName().equals("java/lang/Short");
   }

   public boolean isByte() {
      return classFile.getCFClassName().equals("java/lang/Byte");
   }

   public boolean isNumber() {
      return classFile.getCFClassName().equals("java/lang/Number");
   }

   public String toString() {
      if (classFile != null)
         return getTypeName();
      return super.toString();
   }

   public ITypeDeclaration getEnclosingIType() {
      Object obj = getEnclosingType();
      if (obj instanceof ITypeDeclaration)
         return ((ITypeDeclaration) obj);
      return null;
   }

   public String toModelString() {
      return "CFClass: " + getTypeName();
   }

   public boolean isEnumeratedType() {
      return isEnum();
   }
   
   public Object getEnumConstant(String nextName) {
      return RTypeUtil.getEnum(getCompiledClass(), nextName);
   }

   public boolean isCompiledProperty(String name, boolean fieldMode, boolean interfaceMode) {
      return definesMember(name, JavaSemanticNode.MemberType.PropertyGetSetObj, null, null) != null;
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

   public Object[] getImplementsTypeDeclarations() {
      if (implementsTypes == null)
         return null;
      return implementsTypes.toArray();
   }

   public Object[] getAllImplementsTypeDeclarations() {
      return getImplementsTypeDeclarations();
   }

   public boolean isRealType() {
      return true;
   }

   public void staticInit() {
   }

   public boolean isTransformedType() {
      return false;
   }
}
