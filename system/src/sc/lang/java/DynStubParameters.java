/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.dyn.IDynObject;
import sc.lang.ISemanticNode;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.sc.PropertyAssignment;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.layer.MethodKey;
import sc.layer.ReverseDependencies;
import sc.parser.ParseUtil;
import sc.type.*;
import sc.dyn.IObjChildren;
import sc.dyn.RDynUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DynStubParameters extends AbstractTemplateParameters {
   public String packageName;
   public String typeName;
   public String typeModifiers;
   public String constrModifiers;
   public String baseClassName;
   public String otherInterfaces;

   DynStubParameters superParams;

   DynProp[] compProps;
   DynMethod[] compMethods;

   Object objType;
   BodyTypeDeclaration objTypeDecl;

   List<DynProp> allProps;
   List<DynProp> superProps;

   private List<PropertyDefinitionParameters> cvtIsToGet = null;

   private LayeredSystem sys;
   private Layer refLayer;

   private boolean batchCompile;

   public String getFullTypeName() {
      return ModelUtil.getTypeName(objType);
   }

   public String getRuntimeTypeName() {
      return ModelUtil.getRuntimeTypeName(objType);
   }

   public DynStubParameters(LayeredSystem sys, Layer refLayer, Object stubType, ReverseDependencies reverseDeps) {
      this(sys, refLayer, stubType, false);

      ArrayList<DynProp> props = new ArrayList<DynProp>();
      for (String propName:reverseDeps.bindableDeps.keySet()) {
         String typeName = CTypeUtil.getPackageName(propName);
         // Don't think we should hit this hierarchical case (i.e. the else) with an external type.  We should generate a separate wrapper for
         // the inner type?
         if (typeName == null) {
            propName = CTypeUtil.getClassName(propName);
            Object prop = getPropertyByName(propName);
            int ix = getPropertyPos(propName);
            if (ix == -1)
               ix = props.size() + PTypeUtil.MIN_PROPERTY;
            props.add(new DynProp(prop, ix));
         }
      }
      ArrayList<DynMethod> meths = new ArrayList<DynMethod>();
      for (MethodKey methKey:reverseDeps.dynMethods.keySet()) {
         Object methObj = ModelUtil.getMethodFromSignature(objType, methKey.methodName, methKey.paramSig, true);
         // Treat constructors as static since they must go into the invokeStatic method
         boolean isStatic = ModelUtil.hasModifier(methObj, "static") || ModelUtil.isConstructor(methObj);
         int pos = getMethodIndex(isStatic, methObj);
         if (pos == -1)
            pos = meths.size();
         meths.add(new DynMethod(methObj, pos, isStatic));
      }

      this.compMethods = meths.toArray(new DynMethod[meths.size()]);
      this.compProps = props.toArray(new DynProp[props.size()]);
   }

   public DynStubParameters(LayeredSystem sys, Layer refLayer, Object stubType) {
      this(sys, refLayer, stubType, true);
   }

   public DynStubParameters(LayeredSystem sys, Layer refLayer, Object stubType, boolean batchCompile) {
      this.sys = sys;
      this.refLayer = refLayer;
      objType = stubType;
      this.batchCompile = batchCompile;

      Object[] scopeInterfaces;
      if (objType instanceof BodyTypeDeclaration) {
         objTypeDecl = (BodyTypeDeclaration) objType;

         ParseUtil.initAndStartComponent(objTypeDecl);

         boolean needsDynInnerStub = objTypeDecl.getNeedsDynInnerStub();
         // INNER STUB
         typeName = objTypeDecl.isDynInnerStub() && !needsDynInnerStub ? objTypeDecl.getInnerStubTypeName() : objTypeDecl.getTypeName();
         //typeName = objTypeDecl.getTypeName();

         packageName = getInnerType() && needsDynInnerStub ? "" : objTypeDecl.getPackageName();

         scopeInterfaces = objTypeDecl.scopeInterfaces;
      }
      else {
         typeName = ModelUtil.getClassName(objType);
         // INNER STUB packageName = TypeUtil.getPackageName(getFullTypeName());
         packageName = getInnerType() ? "" : CTypeUtil.getPackageName(getFullTypeName());

         scopeInterfaces = null;
      }

      Object extType;
      ModifyDeclaration modDecl;
      // This is the innerType extends ParentBaseType.innerType case.  Since we did not transform the modify inherited into this case,
      // we need to do it here.  There can be more than one level of indirection so using getModifyInheritedType (e.g. example.unitConverter.jsuis)
      Object modInheritType = objType instanceof ModifyDeclaration ? ((ModifyDeclaration) objType).getModifyInheritedType() : null;
      if (modInheritType != null) {
         extType = modInheritType;
         baseClassName = ModelUtil.getCompiledTypeName(extType);
      }
      else {
         extType = ModelUtil.getExtendsClass(objType);
         baseClassName = extType == null || extType == IDynObject.class ? null : ModelUtil.javaTypeToCompiledString(objType, ModelUtil.getExtendsJavaType(objType), true);
      }

      // Only strip off static if thi sis a top-level inner stub
      if (objTypeDecl != null && !objTypeDecl.getNeedsDynInnerStub())
         typeModifiers = TransformUtil.removeModifiers(ModelUtil.modifiersToString(objType, false, true, false, false, true, null), TransformUtil.nonStubTypeModifiers);
      else
         typeModifiers = ModelUtil.modifiersToString(objType, false, true, false, false, true, null);

      // Removes abstract
      constrModifiers = TransformUtil.removeModifiers(typeModifiers, TransformUtil.typeToConstrModifiers);

      /*
      if (typeModifiers != null) {
         typeModifiers = typeModifiers.replace("static","");
         typeModifiers = typeModifiers.trim();
      }
      */
      Object[] implJavaTypes = ModelUtil.getCompiledImplJavaTypes(stubType);
      if (implJavaTypes != null) {
         StringBuilder sb = new StringBuilder();
         for (Object type:implJavaTypes) {
            Object typeDecl = ModelUtil.getTypeDeclarationFromJavaType(type);
            if (!ModelUtil.isDynamicType(typeDecl)) {
               sb.append(", ");
               sb.append(ModelUtil.javaTypeToCompiledString(objType, type, false));
            }
         }
         // Includes the separator comma cause we always implement IDynObject
         otherInterfaces = sb.toString();
      }

      // If we are a component type and our extends is not, add the marker interface IDynComponent so we can easily recognize the
      // compiled type as requiring the newX method to construct it.
      if (objTypeDecl != null && objTypeDecl.isComponentType() && (extType == null || !ModelUtil.isComponentType(extType))) {
         String newIf = "sc.obj.IDynComponent";
         if (otherInterfaces == null)
            otherInterfaces = ", " + newIf;
         else {
            otherInterfaces += ", " + newIf;
         }
      }

      // Tack on any interfaces added by scopes.
      if (scopeInterfaces != null && scopeInterfaces.length > 0) {
         StringBuilder sb = new StringBuilder();
         for (Object type:scopeInterfaces) {
            // Don't need this one for dynamic types as we can get the children elsewhere.  If for some reason we do need this one
            // on the stub, we'd have to generate the getChildren interface
            if (type == IObjChildren.class)
               continue;
            //if (!ModelUtil.isDynamicType(type)) {
            //   sb.append(", ");
            //   sb.append(ModelUtil.javaTypeToCompiledString(objType, type));
            //}
         }
         if (sb.length() > 0) {
            if (otherInterfaces != null)
               otherInterfaces += sb.toString();
            else
               otherInterfaces = sb.toString();
         }
      }
   }

   public boolean getTypeIsComponentClass() {
      return ModelUtil.isComponentType(objType);
   }

   public boolean getTypeIsAbstract() {
      return ModelUtil.hasModifier(objType, "abstract");
   }

   public boolean getInnerType() {
      return ModelUtil.getEnclosingType(objType) != null;
   }

   /** Returns true if the dynamic stub needs to be an inner type for this type */
   public boolean getDynInnerInstType() {
      return ModelUtil.getEnclosingInstType(objType) != null && objTypeDecl.getNeedsDynInnerStub();
   }

   public String getUpperClassName() {
      return CTypeUtil.capitalizePropertyName(CTypeUtil.getClassName(typeName));
   }

   /**
    * Short name used to differentiate static definitions in an instance inner type.  If you are code generating
    * static definitions that are type specific, these cannot be placed in side of instance inner classes. (java restriction).
    * Instead, you need to append this name to your var and method names so we localize it to just your type.
    */
   public String getInnerName() {
      //if (getIsInnerType())
      // This needs to be consistent with the property mapping static objects... those right now are moved up on
      // any inner type, even though static inner types can have static members.
      if (ModelUtil.getEnclosingType(objType) != null) {
         String res = getUpperClassName();
         Object enc = objType;
         while ((enc = ModelUtil.getEnclosingType(enc)) != null)
            res = ModelUtil.getClassName(enc) + "_" + res;
         return res;
      }
      else
         return "";
   }

   // Like the above but ensures we pick the runtime types.  This is used for looking up DynType metadata when
   // useRuntimeReflection = false.
   public String getRuntimeInnerName() {
      //if (getIsInnerType())
      // This needs to be consistent with the property mapping static objects... those right now are moved up on
      // any inner type, even though static inner types can have static members.
      Object rtType = ModelUtil.getRuntimeTypeDeclaration(objType);
      if (ModelUtil.getEnclosingType(rtType) != null) {
         String res = CTypeUtil.capitalizePropertyName(CTypeUtil.getClassName(getRuntimeTypeName()));
         Object enc = rtType;
         while ((enc = ModelUtil.getEnclosingType(enc)) != null)
            res = CTypeUtil.getClassName(ModelUtil.getRuntimeTypeName(enc)) + "_" + res;
         return res;
      }
      else
         return "";
   }

   public boolean getSuperIsDynamicStub() {
      return objTypeDecl != null && objTypeDecl.getSuperIsDynamicStub();
   }

   public boolean getExtendsDynamicStub() {
      return objTypeDecl != null && objTypeDecl.getExtendsDynamicStub();
   }

   public DynConstructor[] getConstructors() {
      return getConstructors(false);
   }

   // Return a simple wrapper around the info the template needs to generate the dynamic stub class
   public DynConstructor[] getConstructors(boolean componentDef) {
      // When generating newX methods, if this is an inner class type, we already put the newX method in the enclosing
      // type.  Regular constructors still go in the inner class (componentDef = false).   Objects also need the newX
      // so that we can construct them at runtime when the getX method is not generated.
      if (componentDef && ModelUtil.getEnclosingType(objType) != null && objTypeDecl.isComponentType() && objTypeDecl.getNeedsDynInnerStub())
         return new DynConstructor[0];

     // Before we were always getting the constructors on the base type.  Why?  Maybe Java SHOULD work that way but it doesn't... we need the constructors on this type
     // so we do the proper parameters and can create the "superArgs" - which get propagated through.
     // Object extTypeDecl = ModelUtil.getExtendsClass(objType);
     // Object[] constrs = extTypeDecl == null ? null : ModelUtil.getConstructors(extTypeDecl, objType);
      Object[] constrs = ModelUtil.getConstructors(objType, objType, true);
      if (constrs == null) {
         Object ctor = ModelUtil.getPropagatedConstructor(sys, objType, objTypeDecl, refLayer);
         if (ctor != null)
            constrs = new Object[] {ctor};
      }
      DynConstructor[] dynConstrs;
      if (constrs == null) {
         dynConstrs = new DynConstructor[1];
         dynConstrs[0] = new DynConstructor();
      }
      else {
         dynConstrs = new DynConstructor[constrs.length];
         for (int i = 0; i < constrs.length; i++) {
            dynConstrs[i] = new DynConstructor();
            dynConstrs[i].method = constrs[i];
            dynConstrs[i].methodEnclosingType = objType; // The propagated guy will be defined on the super class but for the purposes of "needsSuper" needs to be objTpye
         }
      }
      return dynConstrs;
   }

   // Returns the list of class inner object component types which need to have an inner stub type.
   // This is for putting the newX methods into the parent class so that we can provide the context
   // for the outer object when constructing the inner object.
   public DynInnerConstructor[] getChildComponentConstructors() {
      List<DynInnerConstructor> res = new ArrayList<DynInnerConstructor>();
      List<Object> innerTypes = objTypeDecl.getAllInnerTypes(null, true, false);
      if (innerTypes != null) {
         for (Object t:innerTypes) {
            if (t instanceof BodyTypeDeclaration) {
               BodyTypeDeclaration bt = (BodyTypeDeclaration) t;
               if (bt.isComponentType() && bt.getNeedsDynInnerStub()) {
                  Object[] constrs = bt.getConstructors(objType, false);
                  DynInnerConstructor dc;
                  if (constrs == null) {
                     dc = new DynInnerConstructor();
                     dc.innerType = bt;
                     res.add(dc);
                  }
                  else {
                     for (Object constr:constrs) {
                        dc = new DynInnerConstructor();
                        dc.innerType = bt;
                        dc.method = constr;
                        res.add(dc);
                     }
                  }
               }
            }
         }
      }
      return res.toArray(new DynInnerConstructor[res.size()]);
   }

   public List<PropertyDefinitionParameters> getCvtIsToGet() {
      // This gets computed in there
      if (cvtIsToGet == null)
         getDynCompiledProperties();
      return cvtIsToGet;
   }

   public List<PropertyDefinitionParameters> getDynCompiledProperties() {
      List<Object> fields = objTypeDecl.getCompiledIFields();

      cvtIsToGet = new ArrayList<PropertyDefinitionParameters>();

      ArrayList<PropertyDefinitionParameters> res = new ArrayList<PropertyDefinitionParameters>();
      if (fields == null)
         return res;

      for (int i = 0; i < fields.size(); i++) {
         Object fieldObj = fields.get(i);
         if (fieldObj instanceof PropertyAssignment)
            fieldObj = ((PropertyAssignment) fieldObj).getAssignedProperty();

         String propName = ModelUtil.getPropertyName(fieldObj);

         // Skip compiled properties here - just need those which are dynamic
         if (objTypeDecl.isCompiledProperty(propName, false, false)) {
            // Need to ignore the interfaces so we get the actual definition of the member
            Object member = objTypeDecl.definesMember(propName, JavaSemanticNode.MemberType.PropertyGetSet, null, null, true, false);

            // If this is an interface field which is an "is" method, we need to add a getX method which invokes the isX method
            // because the interface property will always use the getX.
            if (member != null && ModelUtil.isPropertyIs(member)) {
               PropertyDefinitionParameters prm = PropertyDefinitionParameters.create(propName);
               prm.init(fieldObj, false, sys);
               cvtIsToGet.add(prm);
            }
            continue;
         }

         PropertyDefinitionParameters params = PropertyDefinitionParameters.create(propName);
         params.init(fieldObj, true, sys);
         res.add(params);
      }
      return res;
   }

   public DynMethod[] getDynCompiledMethods() {
      List<Object> methods = objTypeDecl.getDynCompiledMethods();
      if (methods == null)
         return new DynMethod[0];
      DynMethod[] dynMethods = new DynMethod[methods.size()];
      int i = 0;
      for (Object meth:methods) {
         // Note: we do not use methodIndex in this template currently so not going to the effort to compute it.
         dynMethods[i] = new DynMethod(meth, -1, ModelUtil.hasModifier(meth, "static"));
         i++;
      }
      return dynMethods;
   }

   class DynObjectInfo {
      BodyTypeDeclaration type;

      String getCompiledClassName() {
         if (!batchCompile)
            type.genDynamicStubIfNecessary();
         return ModelUtil.getCompiledClassName(type);
      }

      String getUpperClassName() {
         return CTypeUtil.capitalizePropertyName(type.typeName);
      }

      String getLowerClassName() {
         return CTypeUtil.decapitalizePropertyName(type.typeName);
      }

      String getModifiers() {
         return type.modifiersToString(false, true, false, false, true, null);
      }

      String getOuterObj() {
         return type.getEnclosingInstType() != null ? "this" : "null";
      }

      String getTypeName() {
         return type.getFullTypeName();
      }

      /** Returns just the modifiers to use for the getMethod */
      String getGetModifiers() {
         String modifiers = getModifiers();
         TransformUtil.removeModifiers(TransformUtil.removeClassOnlyModifiers(modifiers), TransformUtil.fieldOnlyModifiers);
         return type.modifiersToString(false, true, false, false, true, null);
      }

      boolean getComponentType() {
         return ModelUtil.isComponentType(type);
      }
   }

   /** Returns the info for any objects which have compiled definitions which are being overridden in this type. */
   public DynObjectInfo[] getDynCompiledObjects() {
      List<Object> innerTypes = objTypeDecl.getAllInnerTypes(null, true, false);
      List<DynObjectInfo> defs = new ArrayList<DynObjectInfo>();
      if (innerTypes == null)
         return new DynObjectInfo[0];
      for (Object t:innerTypes) {
         if (t instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration bt = (BodyTypeDeclaration) t;
            if (bt.isDynCompiledObject()) {
               DynObjectInfo info = new DynObjectInfo();
               info.type = bt;
               defs.add(info);
            }
         }
      }
      return defs.toArray(new DynObjectInfo[defs.size()]);
   }

   public List<BodyTypeDeclaration> getInnerDynStubs() {
      List<Object> myIts = objTypeDecl.getAllInnerTypes(null, false, false);
      if (myIts == null)
         return Collections.emptyList();

      ArrayList<BodyTypeDeclaration> defs = new ArrayList<BodyTypeDeclaration>();

      for (int i = 0; i < myIts.size(); i++) {
         Object innerType = myIts.get(i);
         if (ModelUtil.isDynamicType(innerType)) {
            if (innerType instanceof BodyTypeDeclaration) {
               BodyTypeDeclaration td = (BodyTypeDeclaration) innerType;
               // Only include the types which need the compiled inner type.  The other types will use the traditional
               // method of generating the inner stub as a separate class
               if (td.isDynamicStub(false) && td.getNeedsDynInnerStub()) {
                  defs.add(td);
               }
            }
         }
      }
      return defs;
   }

   public boolean getNeedsCompiledClass() {
      return objTypeDecl != null && objTypeDecl.needsCompiledClass;
   }

   // numCompProps, n

   private void initAllProps() {
      if (allProps == null) {
         allProps = Arrays.asList(getCompProps());
      }
   }

   private void initSuperProps() {
      if (superProps == null) {
         DynStubParameters superParams = getSuperParams();
         if (superParams == null)
            superProps = Collections.emptyList();
         else
            superProps = Arrays.asList(superParams.getCompProps());
      }
   }

   public DynProp[] getCompDeclaredProps() {
      if (compProps != null)
         return compProps;

      if (objTypeDecl == null || objTypeDecl.body == null)
         return new DynProp[0];

      ArrayList<Object> props = new ArrayList<Object>();
      BodyTypeDeclaration.addAllProperties(objTypeDecl.body, props, null, false, false);

      ArrayList<DynProp> res = new ArrayList<DynProp>();

      for (int i = 0; i < props.size(); i++) {
         Object prop = props.get(i);
         if (prop instanceof VariableDefinition && ((VariableDefinition) prop).needsDynAccess || (prop instanceof BodyTypeDeclaration && ((BodyTypeDeclaration) prop).needsDynAccess) ||
             (prop instanceof MethodDefinition) && ((MethodDefinition) prop).needsDynAccess) {
            String propName = ModelUtil.getPropertyName(prop);
            int pos = getPropertyPos(propName);
            if (pos == -1)
               pos = res.size() + PTypeUtil.MIN_PROPERTY;
            res.add(new DynProp(prop, pos));
         }
      }
      if (objTypeDecl.propertiesToMakeBindable != null) {
         for (String propName:objTypeDecl.propertiesToMakeBindable.keySet()) {
            Object prop = objTypeDecl.definesMember(propName, JavaSemanticNode.MemberType.PropertyAnySet, null, null);
            int pos = getPropertyPos(propName);
            if (pos == -1)
               pos = (superProps == null ? 0 : superProps.size()) + res.size() + PTypeUtil.MIN_PROPERTY;
            res.add(new DynProp(prop, pos));
         }
      }
      return compProps = res.toArray(new DynProp[res.size()]);
   }

   /** Note: these are not slot numbers... we probably need to strip the static properties out so that they
    * are consistent in the type hierarchy? */
   private int getPropertyPos(String propName) {
      initSuperProps();
      // Always start at 1.  0 is reserved for the "null" event
      int pos = PTypeUtil.MIN_PROPERTY;
      for (DynProp prop:superProps) {
         if (prop == null)
            continue;
         if (prop.getName().equals(propName))
            return pos;
         pos++;
      }
      return -1;
   }

   private Object getPropertyByName(String propName) {
      // This gets called with objType as a class so make sure it works for any type.
      return ModelUtil.definesMember(objType, propName, JavaSemanticNode.MemberType.PropertyAnySet, null, null, sys);
   }

   public DynMethod[] getCompMethods() {
      DynStubParameters superParams = getSuperParams();
      if (superParams == null)
         return getCompDeclaredMethods();

      DynMethod[] superMeths = superParams.getCompMethods();
      DynMethod[] declMeths = getCompDeclaredMethods();
      if (superMeths == null)
         return declMeths;

      if (declMeths == null)
         return superMeths;

      ArrayList<DynMethod> res = new ArrayList<DynMethod>(Arrays.asList(superMeths));
      for (int i = 0; i < declMeths.length; i++) {
         DynMethod meth = declMeths[i];
         int ix = methodIndexOf(superMeths, meth);
         if (ix == -1)
            res.add(meth);
         else
            res.set(ix, meth);
      }
      return res.toArray(new DynMethod[res.size()]);
   }

   private static int methodIndexOf(DynMethod[] meths, DynMethod meth) {
      if (meths == null)
         return -1;
      for (int i = 0; i < meths.length; i++)
         if (ModelUtil.overridesMethod(meths[i].method, meth.method))
            return i;
      return -1;
   }

   /** Returns DynMethod wrappers around for each method this type should expose via a compiled dynamic runtime call */
   public DynMethod[] getCompDeclaredMethods() {
      if (compMethods != null)
         return compMethods;

      initMethodTables();

      // Loop through all methods declared on this type for static and instance
      ArrayList<DynMethod> res = new ArrayList<DynMethod>();
      for (Object meth:declStaticMeths) {
         if (ModelUtil.needsCompMethod(meth)) {
            res.add(new DynMethod(meth, getMethodIndex(true, meth), true));
         }
      }
      for (Object meth:declInstMeths) {
         if (ModelUtil.needsCompMethod(meth)) {
            int ix = getMethodIndex(false, meth);
            if (ix == -1)
               ix = res.size();
            res.add(new DynMethod(meth, ix, false));
         }
      }

      // Also, sometimes we need to expose an inherited method.  These are listed separately here
      if (objTypeDecl.dynInvokeMethods != null) {
         for (Object meth:objTypeDecl.dynInvokeMethods.values()) {
            boolean isStatic = ModelUtil.hasModifier(meth, "static");
            int ix = getMethodIndex(isStatic, meth);
            if (ix == -1)
               ix = res.size();
            res.add(new DynMethod(meth, ix, isStatic));
         }
      }

      // Need to expose a call to the implicit constructor
      if (objTypeDecl.needsDynDefaultConstructor) {
         res.add(new DynMethod(objTypeDecl.getDefaultConstructor(), -2, true));
      }

      // Now, for any dynamic methods which have reverse methods, also make them dynamic.  We also link the forward
      // method to the reverse method.
      int forwardSize = res.size();
      for (int i = 0; i < forwardSize; i++) {
         DynMethod forwardMeth = res.get(i);
         MethodBindSettings bindSettings;
         if ((bindSettings = forwardMeth.getBindSettings()) != null) {
            Object revMeth = bindSettings.reverseMethod;
            boolean revIsStatic = ModelUtil.hasModifier(revMeth, "static");
            int ix = getMethodIndex(revIsStatic, revMeth);
            if (ix == -1)
               ix = res.size();
            DynMethod revDynMethod = new DynMethod(revMeth, ix, revIsStatic);
            forwardMeth.reverseDynMethod = revDynMethod;
            res.add(revDynMethod);
         }
      }
      return compMethods = res.toArray(new DynMethod[res.size()]);
   }

   private List<Object> declStaticMeths, declInstMeths, allStaticMethods;

   /* Note: the above are all the original method objects.  This one is the DynMethod itself used for computing the method index */
   private List<DynMethod> superInstMethods;

   private void initMethodTables() {
      Object[] carr = objTypeDecl.getConstructors(null, false);
      List<Object> constructors = carr == null ? null : Arrays.asList(carr);

      declStaticMeths = new ArrayList<Object>();
      declInstMeths = new ArrayList<Object>();
      BodyTypeDeclaration.addAllMethods(objTypeDecl.body, declStaticMeths, "static", true, false, false);
      if (constructors != null)
         declStaticMeths.addAll(constructors);
      BodyTypeDeclaration.addAllMethods(objTypeDecl.body, declInstMeths, "static", false, false, false);

      List<Object> methArr = objTypeDecl.getAllMethods("static", true, false, false);
      allStaticMethods = methArr == null ? new ArrayList<Object>() : new ArrayList<Object>(methArr);
      if (constructors != null)
         allStaticMethods.addAll(constructors);
   }

   private int getMethodIndex(boolean isStatic, Object meth) {
      if (isStatic)
         initMethodTables();
      else if (superInstMethods == null)
         superInstMethods = Arrays.asList(getSuperCompMethods());
      return isStatic ? allStaticMethods.indexOf(meth) : instMethodIndexOf(superInstMethods, meth);
   }

   private int instMethodIndexOf(List<DynMethod> methods, Object meth) {
      for (int i = 0; i < methods.size(); i++) {
         if (ModelUtil.overridesMethod(methods.get(i).method, meth))
            return i;
      }
      return -1;
   }

   public DynStubParameters getSuperParams() {
      if (superParams != null)
         return superParams;

      Object extType = ModelUtil.getExtendsClass(objType);
      if (extType == null)
         return null;
      return superParams = sys.buildInfo.getDynStubParameters(ModelUtil.getTypeName(extType));
   }

   /** Return the compiled methods for the super type (if any).  Used for computing method index */
   public DynMethod[] getSuperCompMethods() {
      DynStubParameters superParams = getSuperParams();
      if (superParams == null)
         return new DynMethod[0];
      return superParams.getCompMethods();
   }

   public int getNumCompProps() {
      DynStubParameters superParams = getSuperParams();
      return getNumCompDeclaredProps() + (superParams == null ? 0 : superParams.getNumCompProps());
   }

   public int getNumCompMethods() {
      DynStubParameters superParams = getSuperParams();
      return getNumCompDeclaredMethods() + (superParams == null ? 0 : superParams.getNumCompMethods());
   }

   public int getNumCompDeclaredProps() {
      return getCompDeclaredProps().length;
   }

   public int getNumCompDeclaredMethods() {
      return getCompDeclaredMethods().length;
   }

   public boolean getExtendsIsDynType() {
      Object extendsType = ModelUtil.getExtendsClass(objType);
      return extendsType != null && ModelUtil.isAssignableFrom(DynType.class, extendsType);
   }

   /** Returns true if the extends type of this class is an externally managed dynamic type.  Initializing and retrieving the dyn type is different in this case than a regular extendsIsDynType */
   public boolean getExtendsIsExtDynType() {
      Object extendsType = ModelUtil.getExtendsClass(objType);
      return extendsType != null && sys.needsExtDynType(extendsType);
   }

   public int getNumCompStaticMethods() {
      int num = 0;
      DynMethod[] allComp = getCompDeclaredMethods();
      for (DynMethod meth:allComp)
         if (meth.isStatic)
            num++;
      return num;
   }

   public DynMethod[] getCompStaticMethods() {
      ArrayList<DynMethod> res = new ArrayList<DynMethod>();
      DynMethod[] allComp = getCompDeclaredMethods();
      for (DynMethod meth:allComp)
         if (meth.isStatic)
            res.add(meth);
      return res.toArray(new DynMethod[res.size()]);
   }

   public int getNumCompDeclaredInstMethods() {
      int num = 0;
      DynMethod[] allComp = getCompDeclaredMethods();
      for (DynMethod meth:allComp)
         if (!meth.isStatic)
            num++;
      return num;
   }

   public int getNumCompInstMethods() {
      DynStubParameters superParams = getSuperParams();
      return getNumCompDeclaredInstMethods() + (superParams == null ? 0 : superParams.getNumCompInstMethods());
   }

   public DynMethod[] getCompDeclaredInstMethods() {
      ArrayList<DynMethod> res = new ArrayList<DynMethod>();
      DynMethod[] allComp = getCompDeclaredMethods();
      for (DynMethod meth:allComp)
         if (!meth.isStatic)
            res.add(meth);
      return res.toArray(new DynMethod[res.size()]);
   }

   public DynProp[] getCompStaticProps() {
      ArrayList<DynProp> res = new ArrayList<DynProp>();
      DynProp[] allComp = getCompDeclaredProps();
      for (DynProp prop:allComp)
         if (prop.isStatic)
            res.add(prop);
      return res.toArray(new DynProp[res.size()]);
   }

   public DynProp[] getCompDeclaredInstProps() {
      ArrayList<DynProp> res = new ArrayList<DynProp>();
      DynProp[] allComp = getCompDeclaredProps();
      for (DynProp prop:allComp)
         if (!prop.isStatic)
            res.add(prop);
      return res.toArray(new DynProp[res.size()]);
   }

   public int getNumCompStaticProps() {
      int num = 0;
      DynProp[] allComp = getCompDeclaredProps();
      for (DynProp prop:allComp)
         if (prop.isStatic)
            num++;
      return num;
   }

   public int getNumCompDeclaredInstProps() {
      int num = 0;
      DynProp[] allComp = getCompDeclaredProps();
      for (DynProp prop:allComp)
         if (!prop.isStatic)
            num++;
      return num;
   }

   public int getNumCompInstProps() {
      DynStubParameters superParams = getSuperParams();
      return getNumCompDeclaredInstProps() + (superParams == null ? 0 : superParams.getNumCompInstProps());
   }

   public DynProp[] getCompProps() {
      DynStubParameters superParams = getSuperParams();
      if (superParams == null)
         return getCompDeclaredProps();
      else {
         DynProp[] superProps = superParams.getCompProps();
         if (superProps == null)
            return getCompDeclaredProps();
         DynProp[] declProps = getCompDeclaredProps();
         if (declProps == null)
            return superProps;

         ArrayList<DynProp> res = new ArrayList<DynProp>(Arrays.asList(superProps));
         for (int i = 0; i < declProps.length; i++) {
            DynProp prop = declProps[i];
            if (prop == null)
               continue;
            int ix = propertyIndexOf(superProps, prop);
            if (ix == -1)
               res.add(prop);
            else
               res.set(ix, prop);
         }
         return res.toArray(new DynProp[res.size()]);
      }
   }

   private int propertyIndexOf(DynProp[] superProps, DynProp prop) {
      for (int i = 0; i < superProps.length; i++)
         if (superProps[i].getName().equals(prop.getName()))
            return i;
      return -1;
   }


   public String getSuperInitTypeStatement() {
      String initType = ModelUtil.getSuperInitTypeCall(sys, objType);
      if (initType == null)
         return null;
      return initType + ";";
   }

   public String getSuperTypeExpr() {
      String initType = ModelUtil.getSuperInitTypeCall(sys, objType);
      if (initType == null)
         initType = "null";
      return initType;
   }

   public DynProp[] getCompDeclaredInstWritableProps() {
      ArrayList<DynProp> res = new ArrayList<DynProp>();
      DynProp[] allComp = getCompDeclaredProps();
      for (DynProp prop:allComp)
         if (!prop.isStatic && !prop.constant)
            res.add(prop);
      return res.toArray(new DynProp[res.size()]);
   }

   public DynProp[] getCompStaticWritableProps() {
      ArrayList<DynProp> res = new ArrayList<DynProp>();
      DynProp[] allComp = getCompDeclaredProps();
      for (DynProp prop:allComp)
         if (prop.isStatic && !prop.constant)
            res.add(prop);
      return res.toArray(new DynProp[res.size()]);
   }

   public static class DynProp {
      Object prop;
      public int index;
      public boolean isStatic;
      public boolean constant;

      public DynProp(Object p, int in) {
         prop = p;
         index = in;
         isStatic = ModelUtil.hasModifier(prop, "static");
         constant = ModelUtil.isConstant(prop);
      }

      public String getName() {
         return ModelUtil.getPropertyName(prop);
      }

      public String getAccessor() {
         String pname = ModelUtil.getPropertyName(prop);
         if (ModelUtil.isField(prop))
            return pname;
         else
            return (ModelUtil.isPropertyIs(prop) ? "is" : "get") + CTypeUtil.capitalizePropertyName(pname) + "()";
      }

      public boolean isPrimitive() {
         return ModelUtil.isPrimitive(ModelUtil.getPropertyType(prop));
      }

      public String getPreSetter() {
         String pname = ModelUtil.getPropertyName(prop);
         Object propType = ModelUtil.getPropertyType(prop);
         String propTypeName = ModelUtil.getRuntimeClassName(propType);
         boolean isPrimitive = ModelUtil.isPrimitive(propType);
         String castPre;
         if (isPrimitive)
            castPre = "sc.dyn.DynUtil." + ModelUtil.getNumberPrefixFromType(propType) + "Value(";
         else
            castPre = "(" + propTypeName + ") ";

         if (ModelUtil.isField(prop))
            return pname + " = " + castPre;
         else
            return "set" + CTypeUtil.capitalizePropertyName(pname) + "(" + castPre;
      }

      public String getPostSetter() {
         String primClose = isPrimitive() ? ")" : "";
         if (ModelUtil.isField(prop))
            return primClose;
         else
            return ")" + primClose;
      }

      public String toString() {
         return String.valueOf(prop);
      }
   }

   public static class DynParam {
      Object paramType;
      String paramName;

      public DynParam(Object pt, String pn) {
         paramType = pt;
         paramName = pn;
      }

      public String getPreInvoke() {
         if (ModelUtil.isPrimitive(paramType))
            return "sc.dyn.DynUtil." + ModelUtil.getNumberPrefixFromType(paramType) + "Value(";
         else
            return "(" + ModelUtil.getTypeName(paramType) + ") ";
      }

      public String getPostInvoke() {
         if (ModelUtil.isPrimitive(paramType))
            return ")";
         else
            return "";
      }

      public String toString() {
         return paramName;
      }
   }

   public class DynMethod {
      Object method;

      /** When the constructor is propagated, we cannot use the method to obtain the enclosing type - it has to be set explicitly */
      Object methodEnclosingType;

      public DynMethod reverseDynMethod;

      public int methodIndex = -1;

      public boolean isStatic = false;

      public DynMethod() {}

      public DynMethod(Object meth, int index, boolean stat) {
         method = meth;
         methodIndex = index;
         isStatic = stat;
      }

      /*
      public boolean getCallsSuper() {
         return method instanceof AbstractMethodDefinition && ((AbstractMethodDefinition) method).callsSuper();
      }
      */

      public DynParam[] getParams() {
         Object[] ptypes;
         if (method == null || (ptypes = ModelUtil.getParameterTypes(method)) == null)
            return new DynParam[0];

         String[] names = getParamNames();

         DynParam[] params = new DynParam[ptypes.length];
         int i = 0;
         for (Object param:ptypes) {
            if (param == null) {
               System.out.println("*** Error null parameter type");
            }
            params[i] = new DynParam(param, names[i]);
            i++;
         }
         return params;
      }

      Object getSuperMethod() {
         Object encType = methodEnclosingType != null ? methodEnclosingType : ModelUtil.getEnclosingType(method);
         Object extType = ModelUtil.getExtendsClass(encType);
         Object[] methParams = ModelUtil.getParameterTypes(method);
         List<Object> paramList = methParams == null ?  null : Arrays.asList(methParams);
         return extType == null ? null : findSuperMethod(extType, paramList);
      }

      Object findSuperMethod(Object type, List<Object> paramList) {
         return ModelUtil.definesMethod(type, ModelUtil.getMethodName(method), paramList, null, null, false, false, null, null, sys);
      }

      public MethodBindSettings getBindSettings() {
         MethodBindSettings ret = RDynUtil.getMethodBindSettings(method);
         return ret;
      }

      /**
       * Down the road, we could optimize this so that we do not always generate the _super_x method for a stub
       * but for now, we should be safe always generating it.  Since we only enable the dynamic stub method
       * when we are overriding a compiled method, this should always match to a valid super class method.
       */
      public boolean getNeedsSuper() {
         if (method == null) // Default constructor
            return getExtendsDynamicStub(); // Need the super(typeDecl) but the super type should have a default constructor if it is compiled
         Object superMeth = getSuperMethod();
         if (superMeth != null && !ModelUtil.hasModifier(superMeth, "abstract") && !ModelUtil.hasModifier(superMeth, "private")) {
            // If we extend another dynamic stub, that stub should have created the _super_x method for this and if we both do it,
            // the second guy's _super_x ends up going to the first guy's stub method.
            Object extType = ModelUtil.getCompiledExtendsTypeDeclaration(objType);
            if (extType instanceof BodyTypeDeclaration && ((BodyTypeDeclaration) extType).hasDynStubForMethod(method))
               return false;
            return true;
         }
         return false;
      }

      public String getName() {
         return ModelUtil.getMethodName(method);
      }

      public String getReturnType() {
         return ModelUtil.javaTypeToCompiledString(objType, ModelUtil.getReturnJavaType(method), false);
      }

      public String getModifiers() {
         String res = ModelUtil.modifiersToString(method, false, true, false, false, true, null);
         if (res != null) {
            res = TransformUtil.removeModifiers(res, TransformUtil.abstractModifier);
         }
         return res;
      }

      public boolean isVoid() {
         Object ret;
         return (ret = ModelUtil.getReturnType(method, true)) == null || ModelUtil.typeIsVoid(ret);
      }

      public boolean getNeedsCast() {
         // Casts can't be used with primitive types
         return !ModelUtil.isPrimitive(ModelUtil.getReturnType(method, true));
      }

      public String getTypeSignature() {
         return ModelUtil.getTypeSignature(method);
      }

      public String getThrowsClause() {
         if (method == null)
            return "";
         return ModelUtil.getThrowsClause(method);
      }

      /** When doing the stub for the _super_x method, we need the exceptions thrown by the super method in the signature */
      public String getSuperThrowsClause() {
         return ModelUtil.getThrowsClause(getSuperMethod());
      }

      public String[] getParamTypeNames() {
         Object[] types;
         if (method == null || (types = ModelUtil.getParameterJavaTypes(method, true)) == null)
            return new String[0];
         String[] ptNames = new String[types.length];
         for (int i = 0; i < types.length; i++) {
            ptNames[i] = ModelUtil.javaTypeToCompiledString(objType, types[i], false);
         }
         return ptNames;
      }
      public String[] getParamNames() {
         if (method == null)
            return new String[0];
         String [] names = ModelUtil.getParameterNames(method);
         if (names == null)
            return new String[0];
         return names;
      }

      /** Used by templates for compiled in dynamic access, i.e. to generate a direct method call to the method from a reflective style method */
      public String getObjectPreInvoke() {
         Object retType = getReturnType();
         if (ModelUtil.isPrimitive(retType))
            return "sc.dyn.DynUtil." + ModelUtil.getNumberPrefixFromType(retType) + "Value(";
         else if (ModelUtil.isConstructor(method))
            return "new ";
         else
            return "";
      }

      /** Used by templates for dynamic type injection, i.e. to generate a call to invoke("name", ...)  */
      public String getPreInvoke() {
         if (isVoid())
            return "";

         Object retType = ModelUtil.getReturnType(method, true);
         return "return " +
                 (!ModelUtil.isPrimitive(retType) ? (getNeedsCast() ? "(" + getReturnType() + ")" : "") :
                         "sc.dyn.DynUtil." + ModelUtil.getNumberPrefixFromType(retType) + "Value(");
      }

      public String getPostInvoke() {
         if (isVoid())
            return "";
         return ModelUtil.isPrimitive(ModelUtil.getReturnType(method, true)) ? ")" : "";
      }

      public boolean isConstructor() {
         return ModelUtil.isConstructor(method);
      }

      public String getTypeName() {
         return ModelUtil.getTypeName(ModelUtil.getEnclosingType(method));
      }

      public String getForwardParams() {
         StringBuilder sb = new StringBuilder();
         String typeSig = getTypeSignature();
         if (typeSig == null)
            sb.append("null");
         else {
            sb.append('"');
            sb.append(getTypeSignature());
            sb.append('"');
         }
         String[] paramNames = getParamNames();
         for (int pn = 0; pn < paramNames.length; pn++) {
            sb.append(", ");
            sb.append(paramNames[pn]);
         }
         return sb.toString();
      }
   }

   public class DynConstructor extends DynMethod {

      public Expression[] getConstrArgs() {
         if (!(method instanceof ConstructorDefinition))
            return null;
         return ((ConstructorDefinition) method).getConstrArgs();
      }

      Object getSuperMethod() {
         Object meth = super.getSuperMethod();
         if (meth == null && methodEnclosingType instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration methEnclType = (BodyTypeDeclaration) methodEnclosingType;
            Object extType = methEnclType.getExtendsTypeDeclaration();
            if (extType != null) {
               Object pc = ModelUtil.getPropagatedConstructor(sys, extType, objTypeDecl, objTypeDecl.getLayer());
               if (pc != null && ModelUtil.methodsMatch(method, pc))
                  return pc;
            }
         }
         return meth;
      }

      public String[] getSuperArgNames() {
         String [] ptNames;
         Expression[] constrArgs = getConstrArgs();
         if (constrArgs != null) {
            ptNames = new String[constrArgs.length];
            for (int i = 0; i < constrArgs.length; i++) {
               ptNames[i] = constrArgs[i].toLanguageString().trim();
            }
            return ptNames;
         }
         else {
            return getParamNames();
         }
      }

      Object findSuperMethod(Object type, List<Object> paramList) {
         return ModelUtil.declaresConstructor(sys, type, paramList, null, null);
      }

      public String getSuperExpression() {
         String superStr = null;
         boolean needsSemi = false;
         if (method instanceof ConstructorDefinition) {
            ConstructorDefinition constr = (ConstructorDefinition) method;
            IdentifierExpression superExpr = constr.getSuperExpresssion();
            if (getSuperIsDynamicStub()) {
               if (superExpr != null) {
                  IdentifierExpression superCopy = superExpr.deepCopy(ISemanticNode.CopyNormal, null);
                  superCopy.arguments.add(0, IdentifierExpression.create("concreteType"));
                  needsSemi = superExpr.parseNode == null;
                  superStr = superCopy.toLanguageString();
               }
            }
            if (superExpr == null)
               return null;
            needsSemi = superExpr.parseNode == null;
            superStr = superExpr.toLanguageString();
         }
         // The toLanguageString() doesn't realize this was an exprStatement when originally parsed so it becomes "super(a, b, c)" if we have not restored
         // the parse node for this expression;
         if (needsSemi) {
            superStr = superStr + ";\n      ";
         }
         return superStr;
      }
   }


   public class DynInnerConstructor extends DynConstructor {
      BodyTypeDeclaration innerType;
      String getUpperInnerTypeName() {
         return CTypeUtil.capitalizePropertyName(innerType.typeName);
      }

      public String getInnerTypeName() {
         return innerType.typeName;
      }

      public String getCompiledInnerTypeName() {
         // Make sure to actually generate the class before we return a type name for it
         if (!batchCompile)
            innerType.genDynamicStubIfNecessary();
         return innerType.getCompiledClassName();
      }

      // Modifiers for the newX method
      String getInnerTypeModifiers() {
         return ModelUtil.modifiersToString(innerType, false, true, false, false, true, null);
      }

      String getFullInnerTypeName() {
         return innerType.getFullTypeName();
      }
   }

   public String getTypeParams() {
      return ModelUtil.getTypeParameterString(objType);
   }
}
