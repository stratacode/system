/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.classfile;

import sc.db.BaseTypeDescriptor;
import sc.db.DBTypeDescriptor;
import sc.lang.sql.DBProvider;
import sc.util.*;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.lifecycle.ILifecycle;
import sc.type.*;
import sc.lang.java.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** The class defined from a ClassFile, i.e. read directly from the binary .class representation. */
public class CFClass extends JavaTypeDeclaration implements ILifecycle, IDefinition {
   public ClassFile classFile;
   JavaType extendsJavaType;
   Object extendsType;
   List<JavaType> implJavaTypes;
   List<Object> implementsTypes;
   Layer layer;
   public List<TypeParameter> typeParameters;

   Object[] constructors;
   CoalescedHashMap<String,Object[]> methodsByName;  // This does include super-type methods
   CoalescedHashMap<String,CFField> fieldsByName;    // This does not include the super type fields

   String fullTypeName;

   CFClassSignature signature;

   static final int SyntheticModifier = 0x1000;
   static final int AccessFlagInterfaceMethod = Modifier.VOLATILE | SyntheticModifier; // Volatile & Synthetic appears to mean a method defined on the interface and inherited

   CFClass(ClassFile cl, Layer layer) {
      super(layer.getLayeredSystem());
      classFile = cl;
      this.layer = layer;
   }

   CFClass(ClassFile cl, LayeredSystem sys) {
      super(sys);
      classFile = cl;
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
         file.classFileName = classFileName;
         file.lastModifiedTime = classFile.lastModified();
         file.initialize();
         return file.getCFClass();
      }
      catch (IOException exc) {
         if (system != null)
            system.error("*** Error reading class: " + classFileName);
         else
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
         String ext = FileUtil.getExtension(zipFile.getName());
         if (ext != null && ext.equals("jmod"))
            classPathName = FileUtil.concat("classes", classPathName);
         ZipEntry zipEnt = zipFile.getEntry(FileUtil.normalize(classPathName));
         if (zipEnt != null) {
            InputStream input = zipFile.getInputStream(zipEnt);
            if (input != null) {
               if (layer == null)
                  file = new ClassFile(input, system);
               else
                  file = new ClassFile(input, layer);
               file.initialize();
               // This matches the rules for a file name with intelliJ's virtual file system so we don't have to convert it to hand it off (and it seems like as good as any)
               file.zipFileName = zipFile.getName();
               file.classFileName = "jar://" + file.zipFileName + "!/" + classPathName;
               file.lastModifiedTime = new File(file.zipFileName).lastModified();
               return file.getCFClass();
            }
         }
         return null;
      }
      catch (IOException exc) {
         if (system != null)
            system.error("Error reading class: " + classPathName + " in zip: " + zipFile);
         else
            System.err.println("Error reading class: " + classPathName + " in zip: " + zipFile);
         return null;
      }
      finally {
         if (file != null)
            file.close();
      }
   }

   public void init() {
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

   private static Object[] mergeInterfaceMethods(CoalescedHashMap[] interfaceMethods, String name) {
      Object[] res = null;
      for (CoalescedHashMap<String,Object[]> m:interfaceMethods) {
         Object[] nextRes = m.get(name);
         if (nextRes != null) {
            if (res == null)
               res = nextRes;
            else {
               Object[] combined = new Object[res.length+nextRes.length];
               System.arraycopy(res, 0, combined, 0, res.length);
               System.arraycopy(nextRes, 0, combined, res.length, nextRes.length);
               res = combined;
            }
         }
      }
      return res;
   }

   // TODO: this is very similar to the method in RTypeUtil which works just for Class objects.  Here we are doing the same thing for CFClass.  We could merge this code and
   // put it into ModelUtil so it works generically pretty easily.
   private void initMethodAndFieldIndex() {
      CFMethod[] methods = classFile.methods;
      int tableSize = methods.length;

      CoalescedHashMap<String,Object[]> superMethods = null;
      CoalescedHashMap<String,Object[]>[] interfaceMethods = null;

      if (extendsType != null) {
         superMethods = ModelUtil.getMethodCache(extendsType);
         if (superMethods != null)
            tableSize += superMethods.size;
      }
      if (implementsTypes != null) {
         int numInterfaces = implementsTypes.size();
         interfaceMethods = new CoalescedHashMap[numInterfaces];
         for (int i = 0; i < numInterfaces; i++) {
            Object implType = implementsTypes.get(i);
            if (implType != null) {
               interfaceMethods[i] = ModelUtil.getMethodCache(implType);
               if (interfaceMethods[i] != null)
                  tableSize += interfaceMethods[i].size;
            }
         }
      }

      CoalescedHashMap<String,Object[]> cache = methodsByName = new CoalescedHashMap<String,Object[]>(tableSize);

      if (implementsTypes != null) {
         CoalescedHashMap<String,Object[]> implCache;
         for (int k = 0; k < implementsTypes.size(); k++) {
            Object implType = implementsTypes.get(k);
            if (implType != null) {
               implCache = ModelUtil.getMethodCache(implType);
               mergeSuperTypeMethods(implCache, implType);
            }
         }
      }
      if (superMethods != null) {
         mergeSuperTypeMethods(superMethods, extendsType);
      }

      for (CFMethod meth:methods) {
         String methodName = meth.name;
         Object method = meth;
         // Used to represent a method we inherit from the interface with a different signature (not that I could find this documented anyplace)
         if ((meth.accessFlags & AccessFlagInterfaceMethod) == AccessFlagInterfaceMethod)
            continue;
         // Is this actually set on methods anyplace by itself?
         if ((meth.accessFlags & Modifier.VOLATILE) != 0) {
            continue;
         }
         // Synthetic method - e.g. lambda code points and stuff like that
         if ((meth.accessFlags & SyntheticModifier) != 0) {
            continue;
         }
         Object[] methodList = cache.get(methodName);
         if (methodList == null) {
            methodList = new Object[1];
            methodList[0] = method;
            cache.put(methodName, methodList);
         }
         else {
            // Do not include super methods for the constructor - those are not inherited in Java
            Object[] superMethodList = methodName.equals("<init>") ? null : cache.get(methodName);
            boolean addToList = true;
            if (superMethodList != null) {
               for (int j = 0; j < superMethodList.length; j++) {
                  if (ModelUtil.overridesMethod(method, superMethodList[j])) {
                     addToList = false;
                     // Only override the method once for a given class.  Java has an annoying habit of returning
                     // interface methods that have different signatures from the class versions after the main
                     // methods in this list.  As long as we ignore those other methods, we get by ok.
                     if (j < methodList.length && superMethodList[j] == methodList[j]) {
                        // Used to use "pickMoreSpecificMethod" here but the isAssignableTo led to a recursive 'start' call which found an uninitialized method cache
                        // It looks like we really only need to choose between interface methods and real ones - since the default is that the sub-class overrides
                        // the base class for any implementation method.  That's true for 'abstract' methods as well here - e.g. an abstract class that overrides
                        // the Object.clone method and refines the type to
                        Object implMethod = ModelUtil.chooseImplMethod(superMethodList[j], method, false);
                        if (implMethod != null) {
                           method = implMethod;
                        }
                        // We start out with a CFMethod[] but may need to replace it with a Class if we override something
                        methodList = checkMethodList(cache, methodList, methodName, method);
                        methodList[j] = method;
                     }
                     // We'd like to break here and skip the rest of the loop but unfortunately Java returns methods with the same signature from
                     // getDeclared methods (e.g. AbstractStringBuilder.append(char)).   it also will not let you
                     // use those methods on an instance in this case.  So we need to replace all methods in the
                     // methodList that are overridden by this method.
                     //break;
                  }
               }
            }

            // New method - expand the list
            if (addToList) {
               Object[] newCachedList = new Object[methodList.length+1];
               System.arraycopy(methodList, 0, newCachedList, 0, methodList.length);
               newCachedList[methodList.length] = method;
               methodList = newCachedList;
               cache.put(methodName, methodList);
            }
            else {
               for (int j = 0; j < methodList.length; j++) {
                  Object otherMeth = methodList[j];
                  if (otherMeth != method && ModelUtil.overridesMethod(method, otherMeth)) {
                     Object newMethod = ModelUtil.pickMoreSpecificMethod(otherMeth, method, null, null, null);
                     if (newMethod == method) {
                        methodList = checkMethodList(cache, methodList, methodName, method);
                        methodList[j] = method;
                        break;
                     }
                  }
               }
            }
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

   static Object[] checkMethodList(CoalescedHashMap cache, Object[] methodList, String methodName, Object method) {
      if ((!(method instanceof CFMethod) && methodList instanceof CFMethod[]) || (methodList instanceof Method[])) {
         Object[] newMethodList = new Object[methodList.length];
         System.arraycopy(methodList, 0, newMethodList, 0, methodList.length);
         methodList = newMethodList;
         cache.put(methodName, methodList);
      }
      return methodList;
   }

   public void start() {
      if (started)
         return;
      if (!initialized)
         init();
      
      started = true;

      if (signature != null) {
         CFClassSignature sig = signature;
         sig.parentNode = this;
         if (sig.extendsType != null) {
            if (isInterface()) {
               //if (sig.extendsType.getFullTypeName().equals("java.lang.Object"))
               //   System.out.println("*** Ignoring this since it's the default");
            }
            else {
               extendsJavaType = sig.extendsType;
               extendsType = extendsJavaType.getTypeDeclaration();

               extendsType = ModelUtil.resolveCompiledType(system, extendsType, extendsJavaType.getFullTypeName());
            }
         }
         //if (extendsType instanceof ParamTypeDeclaration)
         //   extendsType = ((ParamTypeDeclaration) extendsType).getBaseType();
         implJavaTypes = sig.implementsTypes;
         if (implJavaTypes != null) {
            implementsTypes = new ArrayList<Object>(implJavaTypes.size());
            for (int i = 0; i < implJavaTypes.size(); i++) {
               JavaType implJavaType = implJavaTypes.get(i);
               Object implParamType = implJavaType.getTypeDeclaration();
               Object implType = ModelUtil.resolveCompiledType(system, implParamType, implJavaType.getFullTypeName());
               if (implType == null) {
                  error("Can't find interface: " + implJavaType.getFullTypeName());
                  implType = ModelUtil.resolveCompiledType(system, implParamType, implJavaType.getFullTypeName());
               }
               else
                  implementsTypes.add(implType);
            }
         }
         signature = null; // Throwing this away as we don't need it anymore...
      }
      else {
         String extendsTypeName = classFile.getExtendsTypeName();
         if (extendsTypeName != null) {
            // Not using type declaration here as we cannot yet handle CFClasses referencing src-based TypeDeclarations.  There's at least
            // a request that we support the initMethodCache method on the ITypeDeclaration interface which is not yet done.   Note also
            // this change must be made above.
            extendsType = system.getClassWithPathName(extendsTypeName, null, false, true, false, true);
            if (extendsType == null) {
               if (extendsTypeName.contains(".."))
                  extendsTypeName = extendsTypeName.replace("..", ".$");
               extendsType = system.getClassWithPathName(extendsTypeName, null, false, true, false, true);
            }
            if (extendsType == null)
               error("No extends type: " + extendsTypeName);
         }
         int numInterfaces = classFile.getNumInterfaces();
         if (numInterfaces > 0) {
            implementsTypes = new ArrayList<Object>(numInterfaces);
            for (int i = 0; i < numInterfaces; i++) {
               Object implType = system.getClassFromCFName(classFile.getInterfaceCFName(i), null);
               if (implType == null)
                  error("*** Can't find interface: " + classFile.getInterfaceName(i));
               implementsTypes.add(implType);
            }
         }
      }

      // Must be done after we've defined the type for this class.
      initMethodAndFieldIndex();
   }

   private void mergeSuperTypeMethods(CoalescedHashMap<String,Object[]> superCache, Object superType) {
      Object[] keys = superCache.keyTable;
      Object[] values = superCache.valueTable;
      for (int i = 0; i < keys.length; i++) {
         String key = (String) keys[i];
         if (key != null) {
            if (key.equals("<init>")) // don't merge super types constructors since these are not inherited
               continue;
            Object[] superMethods = (Object[]) values[i];
            Object[] thisMethods = methodsByName.get(key);
            if (thisMethods == null) {
               thisMethods = new Object[superMethods.length];
               System.arraycopy(superMethods, 0, thisMethods, 0, superMethods.length);
               if (superType instanceof ParamTypeDeclaration)
                  thisMethods = ((ParamTypeDeclaration) superType).parameterizeMethodList(thisMethods).toArray();
               methodsByName.put(key, thisMethods);
            }
            else {
               Object[] origThisMeths = thisMethods;
               for (int s = 0; s < superMethods.length; s++) {
                  int t;
                  Object sMeth = superMethods[s];
                  for (t = 0; t < thisMethods.length; t++) {
                     if (ModelUtil.overridesMethod(thisMethods[t], sMeth)) {
                        thisMethods[t] = ModelUtil.pickMoreSpecificMethod(thisMethods[t], sMeth, null, null, null);
                        break;
                     }
                  }
                  if (t == thisMethods.length) {
                     int newIx = thisMethods.length;
                     Object[] newThis = new Object[newIx+1];
                     System.arraycopy(thisMethods, 0, newThis, 0, newIx);
                     thisMethods = newThis;
                     if (superType instanceof ParamTypeDeclaration)
                        sMeth = ((ParamTypeDeclaration) superType).parameterizeMethod(sMeth);
                     newThis[newIx] = sMeth;
                  }
               }
               if (origThisMeths != thisMethods)
                  methodsByName.put(key, thisMethods);
            }
         }
      }
   }

   public String getInterfaceName(int ix) {
      return classFile.getInterfaceName(ix);
   }

   public String getExtendsTypeName() {
      return classFile.getExtendsTypeName();
   }

   public String getFullTypeName() {
      if (fullTypeName == null)
         fullTypeName = classFile.getTypeName();
      return fullTypeName;
   }

   public String getJavaFullTypeName() {
      return classFile.getCFClassName();
   }

   public String getFullBaseTypeName() {
      return classFile.getTypeName();
   }

   public String getInnerTypeName() {
      return getTypeName(); // TODO: does this need to handle inner types
   }


   public Object definesConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx, boolean isTransformed) {
      if (!started)
         start();
      Object[] meths = methodsByName.get("<init>");  //To change body of implemented methods use File | Settings | File Templates.
      if (meths == null)
         return null;

      return ModelUtil.definesConstructorFromList(system, this, parametersOrExpressions, ctx, this, isTransformed);
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
               meth.init();
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
         if (field != null && (refType == null || ModelUtil.checkAccess(refType, field)))
            return field;
      }

      if (mtype.contains(JavaSemanticNode.MemberType.Enum) && isEnum()) {
         CFField field = fieldsByName.get(name);
         if (field != null && (refType == null || ModelUtil.checkAccess(refType,field)))
            return field;
      }

      if (extendsType != null) {
         res = ModelUtil.definesMember(extendsType, name, mtype, refType, ctx, skipIfaces, isTransformed, system);
         if (res != null)
            return res;
      }
      if (implementsTypes != null && !skipIfaces) {
         int numInterfaces = implementsTypes.size();
         for (int i = 0; i < numInterfaces; i++) {
            Object implType = implementsTypes.get(i);
            if (implType != null) {
               res = ModelUtil.definesMember(implType, name, mtype, refType, ctx, skipIfaces, isTransformed, system);
               if (res != null)
                  return res;
            }
         }
      }
      return null;
   }

   public Object getInnerType(String name, TypeContext ctx) {
      if (!started)
         start();

      // This will return for SortedMap.Entry which appears to exist as a type but is inherited from java/util/Map$Entry.class so need to check the extendsType if we get back null
      if (classFile.hasInnerClass(name)) {
         Object res;
         if (layer == null)
             res = system.getInnerCFClass(getFullTypeName(), classFile.getCFClassName(), name);
         else
            res = layer.getInnerCFClass(getFullTypeName(), classFile.getCFClassName(), name);
         if (res != null)
            return res;
      }
      if (extendsType != null) {
         Object res = ModelUtil.getInnerType(extendsType, name, ctx);
         if (res != null)
            return res;
      }
      if (implementsTypes != null) {
         int numInterfaces = implementsTypes.size();
         for (int i = 0; i < numInterfaces; i++) {
            Object implType = implementsTypes.get(i);
            Object res = ModelUtil.getInnerType(implType, name, ctx);
            if (res != null)
               return res;
         }
      }
      return null;
   }


   public Object getExtendsTypeDeclaration() {
      if (!started)
         start();
//      if (extendsType == null && isInterface() && implementsTypes != null && implementsTypes.size() > 0)
//         return implementsTypes.get(0);
      return extendsType;
   }

   public JavaType getExtendsType() {
      if (extendsJavaType == null && extendsType != null)
         extendsJavaType = ClassType.createJavaType(getLayeredSystem(), extendsType);
      return extendsJavaType;
   }

   public List<?> getImplementsTypes() {
      if (implJavaTypes == null && implementsTypes != null) {
         implJavaTypes = new ArrayList<JavaType>(implementsTypes.size());
         for (Object implType:implementsTypes) {
            JavaType implJavaType = ClassType.createJavaType(getLayeredSystem(), implType);
            implJavaType.parentNode = this;
            implJavaTypes.add(implJavaType);
         }
      }
      return implJavaTypes;
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
      if (isDyn || overridesComp)
         return null;

      if (!started)
         start();

      CFMethod[] meths = classFile.methods;
      List<Object> res = new ArrayList<Object>();
      if (meths != null) {
         int sz = meths.length;
         for (int i = 0; i < sz; i++) {
            CFMethod meth = meths[i];
            if (meth.isConstructor())
               continue;
            if (modifier == null || hasModifier == ModelUtil.hasModifier(meth, modifier))
               res.add(meth);
         }
      }

      if (implementsTypes != null) {
         for (Object impl:implementsTypes) {
            Object[] implResult = ModelUtil.getAllMethods(impl, modifier, hasModifier, false, false);
            if (implResult != null && implResult.length > 0) {
               res = ModelUtil.appendInheritedMethods(implResult, res);
            }
         }
      }
      Object extendsObj = getDerivedTypeDeclaration();
      List<Object> extMeths;
      if (extendsObj == null)
         return res;
      else {
         Object[] extM = ModelUtil.getAllMethods(extendsObj, modifier, hasModifier, false, false);
         if (extM != null)
            extMeths = Arrays.asList(extM);
         else
            extMeths = null;
      }
      return ModelUtil.mergeMethods(extMeths, res);
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
               method.init();
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

   public List<Object> getDeclaredProperties(String modifier, boolean includeAssigns, boolean includeModified, boolean editorProperties) {
      // TODO: implement the rest of the behavior?  This is not yet done for Class types either
      return getAllProperties(modifier, includeAssigns);
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

   public List<Object> getAllInnerTypes(String modifier, boolean thisClassOnly, boolean includeInherited) {
      int num = classFile.getNumInnerClasses();
      ArrayList<Object> innerTypes = new ArrayList<Object>(num);
      for (int i = 0; i < num; i++) {
         String innerName = classFile.getInnerClassName(i);
         Object innerType = getInnerType(innerName, null);
         if (innerType == null) {
            // These could just be defined on the base class.  We don't need them here since we'll inherit them at lookup time
            //error("Can't find inner type: " + innerName + " of: " + getTypeName());
         }
         else if (modifier == null || ModelUtil.hasModifier(innerType, modifier))
            innerTypes.add(innerType);
      }
      return innerTypes;
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
      return getTypeDeclaration(typeName, true);
   }

   public List<TypeParameter> getClassTypeParameters() {
      if (!initialized)
         init();
      return typeParameters;
   }

   public Object[] getConstructors(Object refType, boolean includeHidden) {
      if (!started)
         start();
      // TODO: enforce ref type here?
      return methodsByName.get("<init>");
   }


   public Object getAnnotation(String annotName, boolean checkModified) {
      return classFile.getAnnotation(annotName);
   }

   public List<Object> getRepeatingAnnotation(String annotName) {
      return classFile.getRepeatingAnnotation(annotName);
   }

   public Collection<IAnnotation> getAnnotationsList(){
      if (classFile.attributes == null) {
         return Collections.emptyList();
      }
      ClassFile.AnnotationsAttribute aa = ClassFile.AnnotationsAttribute.getAttribute(classFile.attributesByName);
      if (aa == null) {
         return null;
      }
      return (Collection<IAnnotation>) (Collection) aa.annotations.values();
   }

   public Map<String,Object> getAnnotations() {
      Collection<IAnnotation> annotList = getAnnotationsList();
      if (annotList != null) {
         HashMap<String,Object> res = new HashMap<String,Object>();
         for (IAnnotation annot:annotList) {
            res.put(annot.getTypeName(),annot);
         }
         return res;
      }
      return null;
   }

   public boolean hasModifier(String modifierName) {
      if (modifierName.equals("static")) {
         int accessFlags = classFile.innerAccessFlags;
         if (accessFlags == -1)
            System.err.println("*** Can't find my inner type access flags!");
         else
            return (accessFlags & ClassFile.modifierNameToAccessFlag("static")) != 0;
      }
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
      return getTypeDeclaration(outer, true);
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

   public String toDeclarationString() {
      return getFullTypeName();
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

   public Object[] getImplementsTypeDeclarations() {
      if (implementsTypes == null)
         return null;
      return implementsTypes.toArray();
   }

   public List<Object> getImplementsList() {
      return implementsTypes;
   }

   public Object getArrayComponentType() {
      if (typeParameters != null && extendsType != null) {
         if (ModelUtil.isAssignableFrom(Collection.class, extendsType)) {
            if (typeParameters.size() == 1)
               return typeParameters.get(0).getTypeDeclaration();
         }
         else if (ModelUtil.isAssignableFrom(Map.class, extendsType)) {
            if (typeParameters.size() == 2)
               return typeParameters.get(1).getTypeDeclaration();
         }
      }
      return null;
   }

   @Override
   public void setAccessTime(long time) {
   }

   public void error(CharSequence... args) {
      reportMessage(MessageType.Error, args);
   }

   public void reportMessage(MessageType type, CharSequence... args) {
      StringBuilder sb = new StringBuilder();
      for (CharSequence arg:args)
         sb.append(arg);
      if (system != null)
         system.reportMessage(type, sb);
      else {
         if (type == MessageType.Error)
            System.err.println(StringUtil.arrayToString(args));
         else
            System.out.println(StringUtil.arrayToString(args));
      }
   }

   public boolean isAnonymous() {
      int ix = fullTypeName.indexOf("$");
      if (ix == -1)
         return false;
      boolean anon = fullTypeName.length() > ix && Character.isDigit(fullTypeName.charAt(ix+1));
      if (anon)
         return true;
      return false;
   }

   public boolean fileChanged() {
      return classFile.fileChanged();
   }

   public BaseTypeDescriptor getDBTypeDescriptor() {
      return DBProvider.initDBTypeDescriptor(system, layer, this);
   }
}
