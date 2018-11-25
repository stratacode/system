/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ILanguageModel;
import sc.lang.sc.PropertyAssignment;
import sc.layer.LayeredSystem;

import java.util.*;

public class InterfaceDeclaration extends TypeDeclaration {
   public List<JavaType> extendsTypes;

   public transient Object[] extendsBoundTypes;

   public DeclarationType getDeclarationType() {
      return DeclarationType.INTERFACE;
   }

   public void init() {
      if (initialized)
         return;

      if (body != null && getJavaModel().enableExtensions()) {
         ArrayList<Statement> getSetFields = null;

         for (int i = 0; i < body.size(); i++) {
            Statement st = body.get(i);
            // If this is not a constant field, it needs the convertGetSet applied
            if (isInterfaceField(st)) {
               FieldDefinition fd = (FieldDefinition) st;
               for (VariableDefinition varDef:fd.variableDefinitions) {
                  varDef.convertGetSet = true;
               }
            }
         }
      }

      super.init();
   }

   public void start() {
      if (started)
         return;

      initTypeInfo();
      if (extendsBoundTypes != null) {
         JavaModel m = getJavaModel();
         for (Object extendsTypeObj:extendsBoundTypes) {
            extendsTypeObj = ParamTypeDeclaration.toBaseType(extendsTypeObj);
            if (extendsTypeObj instanceof TypeDeclaration) {
               TypeDeclaration extendsTypeDecl = (TypeDeclaration) extendsTypeObj;

               startExtendedType(extendsTypeDecl, "extended");

               // Need to add interfaces to the sub-types table so that we can update static and the new interface instance properties when they change
               m.layeredSystem.addSubType(extendsTypeDecl, this);
            }
         }
      }

      super.start();
   }

   public void unregister() {
   }

   protected void completeInitTypeInfo() {
      super.completeInitTypeInfo();

      if (extendsTypes != null && getJavaModel() != null) {
         extendsBoundTypes = new Object[extendsTypes.size()];
         int i = 0;
         for (JavaType extendsType:extendsTypes) {
            // We don't want to resolve from this type cause we get into a recursive loop in findType.
            JavaSemanticNode resolver = getEnclosingType();
            if (resolver == null)
               resolver = getJavaModel();
            extendsType.initType(getLayeredSystem(), this, resolver, null, false, isLayerType, null);

            // Need to start the extends type as we need to dig into it
            Object extendsTypeDecl = extendsBoundTypes[i++] = extendsType.getTypeDeclaration();
            if (extendsTypeDecl == null)
               displayTypeError("Extends class not found: ", extendsType.getFullTypeName(), " for ");
         }
      }
   }

   public Object definesMethod(String name, List<?> types, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs) {
      // For an interface, we need to check hiddenBody first because it has the most specific definitions of things.  The constraint is that we can't modify hiddenBody elements
      // during the "transform" phase.... if we always search hiddenBody first, in Class types with scopes etc. we can end up returning the wrong member and not see the changes reflected in
      // the generted file.
      Object v = findMethodInBody(hiddenBody, name, types, ctx, refType, staticOnly, inferredType, methodTypeArgs);
      if (v != null)
         return v;

      v = super.definesMethod(name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs);
      if (v != null)
         return v;

      initTypeInfo();

      LayeredSystem sys = getLayeredSystem();
      if (extendsBoundTypes != null) {
         for (Object impl:extendsBoundTypes) {
            impl = ParamTypeDeclaration.convertBaseTypeContext(ctx, impl);
            if (impl != null && (v = ModelUtil.definesMethod(impl, name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs, sys)) != null)
               return v;
         }
      }
      else
         return ModelUtil.definesMethod(Object.class, name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs, sys);
      return null;
   }

   public Object definesMemberInternal(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      if (skipIfaces)
         return null;

      // See comment in definesMethod for why we check hiddenBody first for interfaces only
      Object v = findMemberInBody(hiddenBody, name, mtype, refType, ctx);
      if (v != null)
         return v;

      v = super.definesMemberInternal(name, mtype, refType, ctx, skipIfaces, isTransformed);
      if (v != null)
         return v;

      initTypeInfo();

      if (extendsBoundTypes != null) {
         for (Object impl:extendsBoundTypes) {
            if (impl != null && (v = ModelUtil.definesMember(impl, name, mtype, refType, ctx, skipIfaces, isTransformed, getLayeredSystem())) != null)
               return v;
         }
      }
      else
         return ModelUtil.definesMember(Object.class, name, mtype, refType, ctx, skipIfaces, isTransformed, getLayeredSystem());
      return null;
   }

   public boolean implementsType(String fullTypeName, boolean assignment, boolean allowUnbound) {
      if (super.implementsType(fullTypeName, assignment, allowUnbound))
         return true;

      if (extendsBoundTypes != null) {
         for (Object implType:extendsBoundTypes) {
            if (implType != null && ModelUtil.implementsType(implType, fullTypeName, assignment, allowUnbound))
               return true;
         }
      }
      return false;
   }

   public boolean isAssignableTo(ITypeDeclaration other) {
      if (super.isAssignableTo(other))
         return true;

      if (extendsBoundTypes != null) {
         for (Object implType:extendsBoundTypes) {
            if (implType != null && ModelUtil.isAssignableFrom(other, implType, getLayeredSystem()))
               return true;
         }
      }
      return false;
   }

   public List<Object> getAllMethods(String modifier, boolean hasModifier, boolean isDyn, boolean overridesComp) {
      List<Object> result = super.getAllMethods(modifier, hasModifier, isDyn, overridesComp);

      if (extendsBoundTypes != null) {
         for (Object impl:extendsBoundTypes) {
            Object[] implResult = ModelUtil.getAllMethods(impl, modifier, hasModifier, isDyn, overridesComp);
            if (implResult != null && implResult.length > 0) {
               if (result == null)
                  result = new ArrayList<Object>();
               result = ModelUtil.appendInheritedMethods(implResult, result);
            }
         }
      }
      return result;
   }

   public List<Object> getMethods(String methodName, String modifier, boolean includeExtends) {
      List<Object> result = super.getMethods(methodName, modifier, includeExtends);

      if (extendsBoundTypes != null) {
         for (Object impl:extendsBoundTypes) {
            if (impl != null) {
               Object[] implResult = ModelUtil.getMethods(impl, methodName, modifier);
               if (implResult != null && implResult.length > 0) {
                  if (result == null)
                     result = new ArrayList<Object>();
                  result.addAll(Arrays.asList(implResult));
               }
            }
         }
      }
      return result;
   }

   protected void updateBoundExtendsType(Object newType, Object oldType) {
      Object curType = null;
      if (extendsTypes != null) {
         for (JavaType implType:extendsTypes) {
            if ((curType = implType.getTypeDeclaration()) == oldType || curType == newType) {
               implType.setTypeDeclaration(newType);
               return;
            }
         }
      }
      if (oldType == Object.class || newType == Object.class)
         return;
      System.err.println("*** Failed to update type in updateBoundExtendsType: " + oldType + " ->" + newType);
   }

   public List<Object> getAllProperties(String modifier, boolean includeAssigns) {
      List<Object> result = super.getAllProperties(modifier, includeAssigns);

      if (extendsBoundTypes != null) {
         for (Object impl:extendsBoundTypes) {
            if (impl != null) {
               Object[] implResult = ModelUtil.getProperties(impl, modifier, includeAssigns);
               if (implResult != null && implResult.length > 0) {
                  if (result == null)
                     result = new ArrayList<Object>();
                  result.addAll(Arrays.asList(implResult));
               }
            }
         }
      }
      return result;
   }

   public List<Object> getAllFields(String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified) {
      List<Object> result = super.getAllFields(modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);

      if (extendsBoundTypes != null) {
         for (Object impl:extendsBoundTypes) {
            // We include interface properties even when dynamic only is true and we are not dynamic.  It's up to the compiled class
            // which extends us to filter these out.  Leaving this commented out so we do not add it back in!
            //if (dynamicOnly && !ModelUtil.isDynamicType(impl))
            //    continue;
            Object[] implResult = ModelUtil.getFields(impl, modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);
            if (implResult != null && implResult.length > 0) {
               if (result == null)
                  result = new ArrayList<Object>();
               result.addAll(Arrays.asList(implResult));
            }
         }
      }
      return result;
   }

   public Object getSimpleInnerType(String name, TypeContext ctx, boolean checkBaseType, boolean redirected, boolean srcOnly) {
      Object t = super.getSimpleInnerType(name, ctx, checkBaseType, redirected, srcOnly);
      if (t != null)
         return t;

      if (checkBaseType) {
         if (extendsBoundTypes != null) {
            for (Object impl:extendsBoundTypes) {
               if ((t = getSimpleInnerTypeFromExtends(impl, name, ctx, redirected, srcOnly)) != null)
                  return t;
            }
         }
      }
      return t;
   }

   /*
   public List<Object> getDeclaredFields(String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns) {
      List<Object> props = super.getDeclaredFields(modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns);
      if (props == null)
         return null;
      // Do not include the interface fields in the default list of fields declared.  We need to merge them in separately
      for (int i = 0; i < props.size(); i++) {
         Object p = props.get(i);
         if (p instanceof Statement && (p instanceof PropertyAssignment || isInterfaceField((Statement) p))) {
            props.remove(i);
            i--;
         }
      }
      return props;
   }
   */


   public boolean transform(ILanguageModel.RuntimeType runtime) {
      boolean any = false;

      if (excluded)
         return transformExcluded(runtime);

      // Before we transform the fields, we first make a copy of the entire field into the hidden body.  When we are transforming
      // the classes, we'll need to get at the complete field definition for copying it into them.
      if (body != null && getJavaModel().enableExtensions()) {
         for (int i = 0; i < body.size(); i++) {
            Statement st = body.get(i);
            // If this is not a constant field, we move the statement into the hidden body and remove it from the .java file's interface body
            // Instead, we add a get/set method for the field in the interface
            if (isInterfaceField(st)) {
               addToHiddenBody((Statement) st.deepCopy(CopyNormal, null));
            }
            else if (st instanceof PropertyAssignment) {
               body.remove(i);
               i--;
               addToHiddenBody(st);
            }
            // Handle non-empty methods by moving them to the hiddenBody and getting rid of the body for the interface itself
            else if (st instanceof MethodDefinition) {
               MethodDefinition meth = (MethodDefinition) st;
               if (meth.suppressInterfaceMethod()) {
                  addToHiddenBody((Statement) meth.deepCopy(CopyNormal, null));
                  meth.setProperty("body", null);
               }
            }
         }
         any = true;
      }

      if (super.transform(runtime))
         any = true;

      if (body != null && getJavaModel().enableExtensions()) {
         for (int i = 0; i < body.size(); i++) {
            Statement st = body.get(i);
            if (st instanceof MethodDefinition) {
               MethodDefinition meth = (MethodDefinition) st;
               // For methods which have an implementation, we preserve them in hidden body and then nix the body
               if (meth.suppressInterfaceMethod()) {
                  addToHiddenBody((Statement) st.deepCopy(CopyNormal, null));
                  meth.setProperty("body", null);
                  any = true;
               }
            }
         }
      }
      return any;
   }

   public void refreshBoundTypes(int flags) {
      super.refreshBoundTypes(flags);
      JavaModel m = getJavaModel();
      if (extendsTypes != null) {
         for (JavaType jt:extendsTypes) {
            Object oldType = jt.getTypeDeclaration();
            jt.refreshBoundType(flags);
            if (oldType != jt.getTypeDeclaration()) {
               if (oldType instanceof TypeDeclaration)
                  m.layeredSystem.addSubType((TypeDeclaration) oldType, this);
            }
         }
      }

      if (extendsBoundTypes != null) {
         for (int i = 0; i < extendsBoundTypes.length; i++) {
            Object extType = ModelUtil.refreshBoundType(getLayeredSystem(), extendsBoundTypes[i], flags);
            if (extType != extendsBoundTypes[i]) {
               extendsBoundTypes[i] = extType;
               if (extType instanceof TypeDeclaration) {
                  m.layeredSystem.addSubType((TypeDeclaration) extType, this);
               }
            }
         }
      }
   }

   public void initDynStatements(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode) {
      if (extendsBoundTypes != null) {
         for (int i = 0; i < extendsBoundTypes.length; i++) {
            Object impl = extendsBoundTypes[i];
            ((BodyTypeDeclaration) impl).initDynStatements(inst, ctx, mode);
         }
      }
      super.initDynStatements(inst, ctx, mode);
   }

   public Object[] getImplementsTypeDeclarations() {
      return extendsBoundTypes;
   }

   public List<?> getImplementsTypes() {
      return extendsTypes;
   }

   public List<Object> getCompiledIFields() {
      List<Object> res = super.getCompiledIFields();

      // For .java files we should not process multiple-inheritance fields because the syntax of a default field with
      // not static final is treat as static final in Java but in SC it's copied into each class (with getX/setX methods)
      if (!isDynamicType() && getJavaModel().enableExtensions()) {
         if (res == null)
            res = new ArrayList<Object>();
         addAllIFields(body, res, false, false, true);
         addAllIFields(hiddenBody, res, false, false, true);
      }

      if (extendsBoundTypes != null) {
         for (Object impl:extendsBoundTypes) {
            // TODO: deal with compiled interfaces fields here!
            if (!(impl instanceof TypeDeclaration)) {
               continue;
            }
            TypeDeclaration iface = (TypeDeclaration) impl;
            List<Object> implResult = iface.getCompiledIFields();
            if (implResult != null && implResult.size() > 0) {
               if (res == null) {
                  res = new ArrayList<Object>();
                  res.addAll(implResult);
               }
               else {
                  res = ModelUtil.mergeProperties(res, implResult, false);
               }
            }
         }
      }

      return res;
   }

   /** Returns the JavaTypes so we preserve the type parameters */
   public Object[] getCompiledImplJavaTypes() {
      if (extendsTypes == null)
         return null;

      List<Object> compiledImpl = null;
      for (int i = 0; i < extendsTypes.size(); i++) {
         Object impl = extendsBoundTypes[i];
         // Get all compiled implemented types
         if (!ModelUtil.isDynamicType(impl)) {
            if (compiledImpl == null)
               compiledImpl = new ArrayList<Object>();
            compiledImpl.add(extendsTypes.get(i));
         }
         else {
            Object[] nestedTypes = ModelUtil.getCompiledImplJavaTypes(impl);
            if (nestedTypes != null) {
               if (compiledImpl == null)
                  compiledImpl = new ArrayList<Object>();
               compiledImpl.addAll(Arrays.asList(nestedTypes));
            }
         }
      }
      if (compiledImpl == null)
         return null;
      return compiledImpl.toArray(new Object[compiledImpl.size()]);
   }

   public Object[] getCompiledImplTypes() {
      if (extendsBoundTypes == null && scopeInterfaces.length == 0)
         return null;

      List<Object> compiledImpl = null;
      if (extendsBoundTypes != null) {
         for (Object impl:extendsBoundTypes) {
            // Get all compiled implemented types
            if (!ModelUtil.isDynamicType(impl)) {
               if (compiledImpl == null)
                  compiledImpl = new ArrayList<Object>();
               compiledImpl.add(impl);
            }
         }
      }
      for (Object impl:scopeInterfaces) {
         // Get all compiled implemented types
         if (!ModelUtil.isDynamicType(impl)) {
            if (compiledImpl == null)
               compiledImpl = new ArrayList<Object>();
            compiledImpl.add(impl);
         }
      }
      if (compiledImpl == null)
         return null;
      return compiledImpl.toArray(new Object[compiledImpl.size()]);
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      super.addDependentTypes(types, mode);

      if (extendsTypes != null) {
         for (JavaType extendsType:extendsTypes)
            extendsType.addDependentTypes(types, mode);
      }
   }

   public InterfaceDeclaration deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      InterfaceDeclaration res = (InterfaceDeclaration) super.deepCopy(options, oldNewMap);

      if ((options & CopyInitLevels) != 0) {
         res.extendsBoundTypes = extendsBoundTypes == null ? null : extendsBoundTypes.clone();
      }
      return res;
   }

   public AccessLevel getDefaultAccessLevel() {
      return AccessLevel.Public;
   }
}
