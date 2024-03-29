/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.BindingDirection;
import sc.dyn.IDynObject;
import sc.dyn.IObjChildren;
import sc.lang.*;
import sc.lang.html.Element;
import sc.lang.sc.PropertyAssignment;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.template.Template;
import sc.lang.template.TemplateDeclaration;
import sc.layer.SrcEntry;
import sc.obj.IComponent;
import sc.layer.LayeredSystem;
import sc.parser.ParseRange;
import sc.parser.ParseUtil;
import sc.type.CTypeUtil;
import sc.util.IdentityHashSet;
import sc.lang.sc.IScopeProcessor;
import sc.layer.Layer;
import sc.type.PTypeUtil;

import java.util.*;

/**
 * TypeDeclaration is the base class for all types except for EnumConstants.  EnumConstants extend BodyTypeDeclaration,
 * the base class for TypeDeclaration.  Together BodyTypeDeclaration and TypeDeclaration contain the core features necessary
 * to implemented classes, objects, enums, enum constants, and modify declarations.  Instances of these classes are create by parsing
 * grammars based on the JavaLanguage, or classes can be programmatically defined and converted to source code using the grammar.
 * The non-transient properties are considered semantic properties - defined in both directions with the grammar. When those properties
 * change, any code associated with the instance is invalidated and can be regenerated to update a type declaration.
 */
public abstract class TypeDeclaration extends BodyTypeDeclaration {
   public List<JavaType> implementsTypes;
   public List<TypeParameter> typeParameters;

   public transient Object[] implementsBoundTypes;

   public transient boolean typeInfoInitialized = false;
   public transient boolean constructorsInited = false;
   public transient String skippedClassVarName;   // If we omitted the real class for this type, this is the var name to replace it with


   /**
    * By default, objects use the object template.  If a scope wants to create more than one instance of an object
    * it has to tell the object to use the new template.  When true, the childrenNames or childrenByScope properties
    * include the "newX" call instead of the getX call.
    */
   public transient boolean useNewTemplate = false;

   /** Template types use this hook to lazily define methods that eventually get added to the model */
   public transient ITypeDeclaration modelType = null;

   /** Set to true for reflection.  If false, compile in a DynType object for each class */
   public transient boolean useRuntimeReflection = true;

   /** Set to true if useRuntimeReflection is true and CompilerSettings.useExternalDynType is true.  We'll generate a separate class for the DynType wrapper used to replace reflection in that case. */
   public transient boolean useExternalDynType = false;

   /** Index to insert new property definitions.  If static code must run before you do a getPropertyMapping (i.e. GWT), this tracks the end position of that static code */
   public transient int propDefInsertIndex = 0;

   private transient boolean dynTypeInfoInitialized = false;

   /** When this type is created from an HTML element, this stores a reference to that element */
   public transient Element element;

   // Set this to true for modify types which are not actually part of the type system - i.e. children of template declarations that get cloned and inserted as children of a real type.
   transient public boolean inactiveType = false;


   public JavaType getExtendsType() {
      return null;
   }

   public List<?> getImplementsTypes() {
      return implementsTypes;
   }

   public JavaType getDeclaredExtendsType() {
      return null;
   }

   public void modifyExtendsType(JavaType type) {
      displayError("Invalid type for modify extends operation: ");
   }

   private static final boolean useDynamicNew = true;

   protected void initDynamicType() {
      // dynamic type might also be initialized to true externally
      // TODO: should we at this point see if we can propagate the dynamic status to our extends type?  It would have
      // to be a src file but this would reduce the number of stubs we generate.
      if (hasModifier("dynamic") || ((layer != null && layer.dynamic) && !getCompiledOnly()) || isLayerComponent()) {
         if (!isLayerType() && !isLayerComponent())
            initCompiledOnly();

         if (compiledOnly)
            return;

         if (!useDynamicNew) {
            dynamicType = true;
         }
         else {
            /*
             * We'd like to set dynamicNew here to avoid creating stubs for the simple object dynType extends compiledType
             * scenario.  Unfortunately if this type is an inner type, we have no way to store the enclosing type so we
             * need the stub.
             */
            if (modifyNeedsClass() || dynamicType) {
               dynamicType = true;
            } else {
               dynamicNew = true;
            }
         }
      }
   }

   public void init() {
      JavaModel m = getJavaModel();
      if (m != null) {
         layer = m.getLayer(); // Bind this here so we can get the original layer from the type even after it was moved
      }

      initDynamicType();

      // Don't add the transformed types to the main type system.
      if (m != null && !isTransformedType()) {
         // Types defined inside of a method are not globally visible within the file
         if (getEnclosingMethod() == null && typeName != null && !isAnonymousType())
            m.addTypeDeclaration(getFileRelativeTypeName(), this);
      }

      super.init();
   }

   public void start() {
      if (started)
         return;

      initTypeInfo();
      initPropagateConstructors();
      initCompiledOnly();

      if (implementsBoundTypes != null) {
         JavaModel m = getJavaModel();
         for (Object implTypeObj:implementsBoundTypes) {
            implTypeObj = ParamTypeDeclaration.toBaseType(implTypeObj);
            if (implTypeObj instanceof TypeDeclaration) {
               TypeDeclaration implType = (TypeDeclaration) implTypeObj;
               startExtendedType(implType, "implemented");

               // Need to add interfaces to the sub-types table so that we can update static and the new interface instance properties when they change
               m.layeredSystem.addSubType(implType, this);
            }
         }
      }

      if (getEnclosingType() == null && !isLayerType) {
         JavaModel model = getJavaModel();
         SrcEntry srcFile = model == null ? null : model.getSrcFile();
         // Some Java classes have more than one type in them?
         if (srcFile != null && model.types != null && model.types.size() == 1 && model.types.get(0) == this) {
            String fileName = CTypeUtil.getClassName(model.getSrcFile().getRelTypeName());
            if (!fileName.equals(typeName)) {
               displayError("Type name: " + typeName + " does not match file name: " + fileName + " for: ");
            }
         }
      }

      IScopeProcessor proc = getScopeProcessor();
      if (proc != null) {
         if (proc.getUseNewTemplate())
            useNewTemplate = true;
      }

      // Snag a copy of the TypeDeclarations which are inner objects.  Because we may remove these during transform
      // we need to keep a copy so we can consistently find them.
      if (body != null) {
         ArrayList<TypeDeclaration> objList = null;
         for (Statement st:body) {
            if (st instanceof TypeDeclaration) {
               TypeDeclaration innerTD = (TypeDeclaration) st;
               if (innerTD.getDeclarationType() == DeclarationType.OBJECT) {
                  if (objList == null)
                     objList = new ArrayList<TypeDeclaration>();
                  objList.add(innerTD);
               }
            }
         }
         if (objList != null)
            innerObjs = objList.toArray(new TypeDeclaration[objList.size()]);
      }
      super.start();

   }

   public void initCompiledOnly() {
      JavaModel model = getJavaModel();
      if (model != null && model.mergeDeclaration && !isLayerType) {
         // Can't access inherited annotations in start
         Object setting;
         if (!needsCompiledClass) {
            boolean ncc = getBoolCompilerSetting("needsCompiledClass", false);
            if (ncc)
               enableNeedsCompiledClass();
         }
         if (!compiledOnly) {
            compiledOnly = getBoolCompilerSetting("compiledOnly", false);
         }
      }
   }

   public void validate() {
      if (validated)
         return;
      if (implementsBoundTypes != null) {
         for (Object extendsTypeDecl:implementsBoundTypes) {
            if (extendsTypeDecl instanceof TypeDeclaration) {
               TypeDeclaration implType = (TypeDeclaration) extendsTypeDecl;
               if (!implType.isValidated())
                  implType.validate();
            }
         }
      }

      // Need to skip the getInheritedAnnotation call made indirectly here for the case where this is just a sync'd model (i.e. a dynamic
      // merged model).
      JavaModel model = getJavaModel();
      if (model != null && model.mergeDeclaration && !isLayerType) {

         //initCompiledOnly();
         initDynTypeInfo();
      }

      super.validate();
   }

   public void process() {
      if (processed)
         return;
      if (implementsBoundTypes != null) {
         for (Object extendsTypeDecl:implementsBoundTypes) {
            if (extendsTypeDecl instanceof TypeDeclaration) {
               TypeDeclaration implType = (TypeDeclaration) extendsTypeDecl;
               if (!implType.isProcessed())
                  implType.process();
            }
         }
      }

      super.process();
   }

   public void stop() {
      if (started)
         unregister();

      super.stop();

      typeInfoInitialized = false;
      implementsBoundTypes = null;
      constructorsInited = false;
   }

   public boolean isAutoComponent() {
      if (isLayerType)
         return false;
      if (autoComponent == null) {
         Object annot;
         Object av;
         autoComponent = (annot = getInheritedAnnotation(IComponent.COMPONENT_ANNOTATION)) != null &&
              ((av = ModelUtil.getAnnotationValue(annot, "disabled")) == null || !(Boolean) av);
      }
      return autoComponent;
   }

   public boolean isComponentType() {
      return isAutoComponent() || implementsType("sc.obj.IComponent", false, false) || implementsType("sc.obj.IAltComponent", false, false);
   }

   private Object resolveExtendsType(JavaType extendsType) {
      // We don't want to resolve from this type cause we get into a recursive loop in findType.
      if (!extendsType.isBound()) {
         JavaSemanticNode resolver = getEnclosingType();
         if (resolver == null)
            resolver = getJavaModel();
         extendsType.initType(getLayeredSystem(), this, resolver, null, false, isLayerType, null);
      }

      // Need to start the extends type as we need to dig into it
      Object extendsTypeDecl = extendsType.getTypeDeclaration();
      if (extendsTypeDecl == null) {
         extendsType.displayTypeError("Extends class not found: ", extendsType.getFullTypeName(), " for ");
         extendsTypeDecl = extendsType.getTypeDeclaration();
      }
      return extendsTypeDecl;
   }

   protected void reInitTypeInfo() {
      typeInfoInitialized = false;
      initTypeInfo();
   }

   public void ensureTypeInfoInited() {
      initTypeInfo();
      BodyTypeDeclaration modType = getModifiedType();
      if (modType != null)
         modType.ensureTypeInfoInited();
   }

   public void initTypeInfo() {
      if (typeInfoInitialized)
         return;
      if (!isInitialized()) {
         JavaModel model = getJavaModel();
         ParseUtil.initComponent(model);
      }

      try {
         typeInfoInitialized = true;

         // Need to make sure the parent type is initialized before the sub-type since we need to search the parent types extends/implementBoundTypes to possibly find the implements types here
         TypeDeclaration enclType = getEnclosingType();
         if (enclType != null)
            enclType.initTypeInfo();

         JavaModel m = getJavaModel();

         if (implementsTypes != null && m != null) {
            implementsBoundTypes = new Object[implementsTypes.size()];
            int i = 0;
            for (JavaType extendsType:implementsTypes) {
               // Need to start the extends type as we need to dig into it
               implementsBoundTypes[i++] = resolveExtendsType(extendsType);
            }
         }

         // Do any subclass type initialization before we apply the scope templates, so that we can get annotations, etc.
         // down the derived type hierarchy.
         completeInitTypeInfo();
         typeInfoCompleted = true;

         IScopeProcessor scopeProc = getScopeProcessor();
         if (scopeProc != null) {
            scopeProc.applyScopeTemplate(this);
         }
         if (m != null && m.getLayer() != null && m.getLayer().annotationLayer && !isAnnotationDefinition()) {
            displayError("Annotation layers may only attach annotations onto definitions: ");
            boolean dummy = isAnnotationDefinition();
         }
      }
      catch (RuntimeException exc) {
         typeInfoInitialized = false;
         typeInfoCompleted = false;
         throw exc;
      }
   }

   public Object declaresConstructor(List<?> types, ITypeParamContext ctx) {
      // We are lazily doing this, the first time an attempt is made to look up a constructor.
      initTypeInfo();
      initPropagateConstructors();
      return super.declaresConstructor(types, ctx);
   }

   protected void completeInitTypeInfo() {
      initMixinTemplates(true);
      // When building templates, the findTypeDeclaration in this call can force access to the very types we are in the midst of building
      // higher up in the element chain.
      //JavaModel m = getJavaModel();
      //if (m != null && !m.isLayerModel) {
         //initPropagateConstructors();
      //}
   }

   public Object definesType(String name, TypeContext ctx) {
      Object res = super.definesType(name, ctx);
      if (res != null)
         return res;
      if (typeParameters != null) {
         for (Object p:typeParameters)
            if (!(p instanceof TypeParameter))
               System.err.println("*** Invalid typeParmeter in type!");
         for (TypeParameter param:typeParameters)
            if (name.equals(param.name))
               return param;
      }
      return null;
   }

   private boolean isAnnotationDefinition() {
      if (implementsTypes != null)
         return false;
      if (body == null)
         return true;

      JavaModel model = getJavaModel();
      if (model.isLayerModel)
         return true;

      for (Statement st:body) {
         if (!(st instanceof PropertyAssignment)) {
            if (!(st instanceof MethodDefinition) || !((MethodDefinition) st).override)
               return false;
         }
         else {
            PropertyAssignment pa = (PropertyAssignment) st;
            if (pa.initializer != null)
               return false;
         }
      }
      return true;
   }

   public Object getExtendsTypeDeclaration() {
      JavaType t = getExtendsType();
      if (t != null) {
         Object o = resolveExtendsType(t);
         if (o instanceof BodyTypeDeclaration)
            o = ((BodyTypeDeclaration) o).resolve(true);
         /*
         Object o = t.getTypeDeclaration();
         */
         return o;
      }
      return null;
   }

   public Object getDeclaredExtendsTypeDeclaration() {
      JavaType t = getDeclaredExtendsType();
      if (t != null) {
         Object o = t.getTypeDeclaration();
         if (o instanceof BodyTypeDeclaration)
            return ((BodyTypeDeclaration) o).resolve(true);
         return o;
      }
      return null;
   }

   public ITypeDeclaration getEnclosingIType() {
      for (ISemanticNode par = parentNode; par != null; par = par.getParentNode()) {
         if (par instanceof JavaModel) {
            TypeDeclaration impl = ((JavaModel) par).getImplicitTypeDeclaration();
            if (impl == null) {
               if (par instanceof Template)
                  return (Template) par;
            }
            ITypeDeclaration res = getImplicitEnclosingType(impl, par);
            return res;
         }
         else if (par instanceof Element) {
            Element elem = (Element) par;
            TypeDeclaration res = elem.getElementTypeDeclaration();
            if (res == null)
               return elem.getEnclosingType();
            return res;
         }
         else if (par instanceof ITypeDeclaration) {
            ITypeDeclaration itd = (ITypeDeclaration) par;
            if (itd.isRealType())
               return itd;
            TypeDeclaration td = (TypeDeclaration) itd;
            ITypeDeclaration enclType = td.getEnclosingIType();
            if (enclType == this)
               return null;
            return enclType;
         }
      }
      return null;
   }

   private ITypeDeclaration getImplicitEnclosingType(ITypeDeclaration implicit, ISemanticNode par) {
      if (!ModelUtil.sameTypes(implicit, this))
         return implicit;

      if (this instanceof TemplateDeclaration && !(implicit instanceof TemplateDeclaration))
         return implicit;

      return null;
   }

   public TypeDeclaration getEnclosingType() {
      for (ISemanticNode par = parentNode; par != null; par = par.getParentNode()) {
         if (par instanceof TypeDeclaration) {
            TypeDeclaration td = (TypeDeclaration) par;
            if (td.isRealType())
               return td;
            else {
               TypeDeclaration enclType = td.getEnclosingType();
               // This is the implicit root looking for it's enclosing type.  it finds itself which means it's the root.
               if (enclType == this)
                  return null;
               else
                  return enclType;
            }
         }
         else if (par instanceof Element) {
            Element el = (Element) par;
            if (el.tagObject != null)
               return el.tagObject;
         }
         if (par instanceof JavaModel) {
            TypeDeclaration impl = ((JavaModel) par).getImplicitTypeDeclaration();
            return (TypeDeclaration) getImplicitEnclosingType(impl, par);
         }
      }
      return null;
   }


   public boolean isClassOrObjectType() {
      DeclarationType dt = getDeclarationType();
      return dt == DeclarationType.CLASS || dt == DeclarationType.OBJECT;
   }

   /** Is this a compiled declaration or one that we'll have to interpret at runtime */
   public boolean isCompiled() {
      return !isLayerType && !isDynamicType();
   }

   public Object definesMethod(String name, List<?> types, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs) {
      initTypeInfo();

      Object v = super.definesMethod(name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs);
      if (v != null)
         return v;

      if (modelType != null) {
         v = modelType.definesMethod(name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs);
         if (v != null)
            return v;
      }

      if (implementsBoundTypes != null) {
         LayeredSystem sys = getLayeredSystem();
         for (Object impl:implementsBoundTypes) {
            if (impl != null && (v = ModelUtil.definesMethod(impl, name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs, sys)) != null)
               return v;
         }
      }

      if (scopeInterfaces != null) {
         LayeredSystem sys = getLayeredSystem();
         for (Object scopeIf:scopeInterfaces)
            if (scopeIf != null && (v = ModelUtil.definesMethod(scopeIf, name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs, sys)) != null)
               return v;
      }
      return null;
   }

   public Object definesPreviousMember(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      Object v = super.definesPreviousMember(name, mtype, refType, ctx, skipIfaces, isTransformed);
      if (v != null)
         return v;
      initTypeInfo();

      if (implementsBoundTypes != null && !skipIfaces) {
         for (Object impl:implementsBoundTypes) {
            if (impl != null && (v = ModelUtil.definesMember(impl, name, mtype, refType, ctx, skipIfaces, isTransformed, getLayeredSystem())) != null)
               return v;
         }
      }

      if (isLayerType && layer != null && layer.excluded)
         return null;

      IDefinitionProcessor[] procs = getDefinitionProcessors();
      if (procs != null) {
         for (IDefinitionProcessor proc:procs)
            if ((v = proc.definesMember(this, name, mtype, ctx)) != null)
               return v;
      }
      return null;
   }

   public Object definesMemberInternal(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      /* We support ObjectName.ObjectName resolution for the top-level object to mimic how the property model works.  Need to do this before we call super.definesMemberInternal because otherwise we'll find a modified object type first */
      if (mtype.contains(MemberType.Field) && name.equals(typeName) && getDeclarationType() == DeclarationType.OBJECT && getEnclosingType() == null)
         return this;

      Object v = super.definesMemberInternal(name, mtype, refType, ctx, skipIfaces, isTransformed);
      if (v != null)
         return v;

      v = definesPreviousMember(name, mtype, refType, ctx, skipIfaces, isTransformed);
      if (v != null)
         return v;

      if (modelType != null) {
         v = modelType.definesMember(name, mtype, refType, ctx, skipIfaces, isTransformed);
         if (v != null)
            return v;
      }

      return null;
   }

   public static enum InitStatementMode {
      All, SimpleOnly, RefsOnly;

      public boolean evalExpression(Expression initializer, BindingDirection bd) {
         boolean eval = false;
         switch (this) {
            case All:
               eval = true;
               break;
            case RefsOnly:
               eval = initializer.isReferenceInitializer() || bd != null;
               break;
            case SimpleOnly:
               eval = !initializer.isReferenceInitializer() && bd == null;
               break;
         }
         return eval;
      }

      public boolean evalStatements() {
         switch (this) {
            case All:
            case RefsOnly:
               return true;
            case SimpleOnly:
            default:
               return false;
         }
      }
   }

   public void clearDynFields(Object inst, ExecutionContext ctx, boolean initExt) {
      // No dynamic fields
      if (!(inst instanceof IDynObject))
         return;

      // TODO: Doing the implements before the extends so that extends has precedence... is that right and consistent with transform?
      if (implementsBoundTypes != null && initExt) {
         for (int i = 0; i < implementsBoundTypes.length; i++) {
            Object impl = implementsBoundTypes[i];
            // Need to do even compiled interfaces in case there are any interface instance fields that were not compiled in
            if (impl instanceof BodyTypeDeclaration)
               ((BodyTypeDeclaration) impl).clearDynFields(inst, ctx, initExt);
         }
      }

      Object extType = getDerivedTypeDeclaration();
      if (ModelUtil.isDynamicNew(extType) && initExt)
         ((ITypeDeclaration) extType).clearDynFields(inst, ctx, initExt);

      if (body != null) {
         IDynObject dinst = (IDynObject) inst;
         for (Statement s:body) {
            if (!(s instanceof TypeDeclaration))
               s.clearDynFields(inst, ctx, true);
            else {
               TypeDeclaration innerType = (TypeDeclaration) s;
               int dynIndex;
               // The field which defines this object may not be dynamic, even if the object itself is dynamic.  Really
               // we need to check whether that slot has been compiled but this test is accurate and efficient.
               if (innerType.isDynObj(false) && (dynIndex = getDynInstPropertyIndex(innerType.typeName)) != -1) {
                  dinst.setProperty(dynIndex, DynObject.lazyInitSentinel, true);
               }
            }
         }
      }
   }

   public boolean isAssignableFrom(ITypeDeclaration other, boolean assignmentSemantics) {
      if (other instanceof ArrayTypeDeclaration)
         return false;
      if (other == null) {
         return true;
      }
      return other.isAssignableTo(this);
   }

   public boolean isAssignableTo(ITypeDeclaration other) {
      if (other instanceof ArrayTypeDeclaration)
         return false;

      // Occasionally, like when styling code snippets we'll bring in a new type with layer=null so it does not
      // get stored in the type cache but we still want to consider it the same type if its name is the same.
      if (this == other || (typeName != null && typeName.equals(other.getTypeName()) && getFullTypeName().equals(other.getFullTypeName())))
         return true;

      if (other instanceof ModifyDeclaration) {
         Object newType = other.getDerivedTypeDeclaration();
         if (this == newType)
            return true;
      }

      initTypeInfo();

      if (implementsBoundTypes != null) {
         for (int i = 0; i < implementsBoundTypes.length; i++) {
            Object implType = implementsBoundTypes[i];
            if (implType instanceof Class)
               implType = ModelUtil.resolveSrcTypeDeclaration(getLayeredSystem(), implType, false, false, getLayer());

            if (implType instanceof ITypeDeclaration && ((ITypeDeclaration) implType).isAssignableTo(other))
               return true;
         }
      }

      Object[] scopeIfaces = getScopeInterfaces();
      for (Object iface:scopeIfaces) {
         if (iface instanceof ITypeDeclaration && ((ITypeDeclaration) iface).isAssignableTo(other))
            return true;
      }

      Object extType = getDerivedTypeDeclaration();
      if (extType instanceof ITypeDeclaration) {
         if (((ITypeDeclaration) extType).isAssignableTo(other))
            return true;
      }
      else if (extType != null && ModelUtil.isAssignableFrom(other, extType))
         return true;

      // NOTE: this test is replicated in implementsType
      if (isAutoComponent() && other.getFullTypeName().equals("sc.obj.ComponentImpl"))
         return true;

      return false;
   }

   public void addImplements(JavaType impl) {
      if (implementsTypes == null) {
         // This order reduces generation overhead a little...
         SemanticNodeList<JavaType> newTypes = new SemanticNodeList<JavaType>(1);
         newTypes.add(impl);
         setProperty("implementsTypes", newTypes);
         if (typeInfoInitialized) {
            implementsBoundTypes = new Object[1];
            implementsBoundTypes[0] = resolveExtendsType(impl);
         }
      }
      else {
         for (int i = 0; i < implementsTypes.size(); i++) {
            JavaType oldImpl = implementsTypes.get(i);
            if (oldImpl.equalTypes(impl)) {
               return;
            }
         }
         implementsTypes.add(impl);
         if (typeInfoInitialized) {
            int sz;
            Object[] newImpls = new Object[sz = implementsTypes.size()];
            int last = sz - 1;
            System.arraycopy(implementsBoundTypes, 0, newImpls, 0, last);
            implementsBoundTypes = newImpls;
            newImpls[last] = resolveExtendsType(impl);
         }
      }
   }

   // TODO: this is almost the same as the super method. Can we get rid of the duplication here?
   public Definition modifyDefinition(BodyTypeDeclaration base, boolean doMerge, boolean inTransformed) {
      boolean isEnumType = base.isEnumeratedType();
      TypeDeclaration otherType = (TypeDeclaration) base.getInnerType(typeName, null, true, false, false, isEnumType);
      Object annotObj;
      // We are inside of a modify but are defining a new type.  If this type is the same as the inherited type
      // we can just replace it, but if it's a new type, it just gets added to the modified type.
      if (otherType != null && ModelUtil.sameTypes(otherType, this)) {
         overrides = otherType;
         // Preserves the order of the children in the list.
         otherType.parentNode.replaceChild(otherType, this);
      }
      else if ((annotObj = getAnnotation("AddBefore", true)) != null || (annotObj = getAnnotation("AddAfter", true)) != null) {
         Annotation annot = Annotation.toAnnotation(annotObj);
         if (!(annot.elementValue instanceof StringLiteral)) {
            System.err.println("*** Annotation: " + annot.toDefinitionString() + " should specify class name as a String");
         }
         else {
            String otherTypeName = ((StringLiteral) annot.elementValue).stringValue;
            TypeDeclaration indexType = (TypeDeclaration) base.getInnerType(otherTypeName, null, false, false, false, isEnumType);
            if (indexType == null) {
               System.err.println("*** Can't find type in annotation: " + annot.toDefinitionString() + " must be an object defined in: " + typeName);
            }
            else {
               int ix = base.body.indexOf(indexType);

               if (ix != -1) {
                  if (annot.typeName.equals("AddBefore"))
                     base.body.add(ix, this);
                  else
                     base.body.add(ix+1, this);
               }
               else {
                  System.err.println("*** Internal error: can't find position of type in base");
               }
            }
         }
      }
      else
         base.body.add(this);

      return this;
   }

   public boolean implementsType(String fullTypeName, boolean assignment, boolean allowUnbound) {
      String fte = getFullTypeName();
      if (fte != null && ModelUtil.typeNamesEqual(fte, fullTypeName))
         return true;

      initTypeInfo();

      if (implementsBoundTypes != null) {
         for (Object implType:implementsBoundTypes) {
            if (implType != null && ModelUtil.implementsType(implType, fullTypeName, assignment, allowUnbound))
               return true;
         }
      }

      // If our annotations add any interfaces consider those as part of this type
      Object[] scopeIfaces = getScopeInterfaces();
      for (Object iface:scopeIfaces) {
         if (ModelUtil.implementsType(iface, fullTypeName, assignment, allowUnbound))
            return true;
      }

      Object ext = getDerivedTypeDeclaration();
      if (ext != null) {
         if (ModelUtil.implementsType(ext, fullTypeName, assignment, allowUnbound))
            return true;
      }

      // We use the ComponentImpl's _initState field to resolve _initState references on untransformed components.
      // This test is needed because that field is protected and we need to be 'assignable' to ComponentImpl to access it.
      // NOTE: this test is replicated in isAssignableTo.
      if (isAutoComponent() && fullTypeName.equals("sc.obj.ComponentImpl"))
         return true;

      return false;
   }

   public void addPropertyToMakeBindable(String propertyName, Object propType, JavaModel fromModel, boolean referenceOnly, JavaSemanticNode fromNode) {
      Layer lyr = getLayer();
      LayeredSystem sys = lyr != null ? lyr.getLayeredSystem() : null;

      if (lyr != null && lyr.annotationLayer) {
         if (fromNode != null && fromNode instanceof Expression) {
            Expression fromExpr = (Expression) fromNode;
            Statement fromSt = fromExpr.bindingStatement;

            if (fromSt != null && ModelUtil.getAnnotation(fromSt, "sc.bind.NoBindWarn") != null)
               return;
         }

         if (fromNode == null)
            fromNode = this;

         if (!fromModel.disableTypeErrors)
            fromNode.displayWarning("Unable to make property: " + propertyName + " bindable on annotation type: " + typeName + " for: ");
         return;
      }
      if (sys != null && sys.buildLayer != null && sys.buildLayer.compiled && !referenceOnly) {
         Object field;
         // First check if the model has a known bindable property.  If this is a dynamic type, all sets should throw the property change even through the wrapper.  Not entirely sure
         // May need to also mark this property as a dynamic property though?  Or do we need a new type of dynamic property which just wraps data binding around the setX method of the
         // compiled type ala the compiled model?
         if (propType != null && !ModelUtil.isDynamicType(propType) && ((field = ModelUtil.getRuntimePropertyMapping(propType)) == null || (!ModelUtil.isBindable(field)) && !isDynamicType() && !ModelUtil.isConstant(field))) {

            // If not, we want to still check the compiled definition.  That's cause we physically add get/set methods
            // to make properties bindable - so they are not in the TypeDeclaration.
            Class rtClass = getCompiledClass();
            if (rtClass != null) {
               Object rtField = PTypeUtil.getPropertyMapping(rtClass, propertyName);
               if (rtField == null || !ModelUtil.isBindable(rtField)) {
                  boolean stale = true;
                  /** If we have the runtime property here there's a case where the runtime does not contain the "is bindable" info.  If we mark the property as Bindable(manual=true), there's no runtime reflection that it is bindable.  Maybe we should just wrap with get/set methods in this case since it is rare and that would give us a runtime annotation */
                  if (rtField == propType) {
                     Object declaredField = definesMember(propertyName, MemberType.PropertyAnySet, this, null);
                     if (declaredField != null && declaredField != rtField && ModelUtil.isAutomaticBindable(declaredField))
                        stale = false;
                  }
                  if (stale) {
                     if (!ModelUtil.isBindable(sys, rtClass, propertyName)) {
                        sys.setStaleCompiledModel(true, "Recompile needed to make compiled property bindable: ", propertyName, " in type ", typeName);
                        ModelUtil.isBindable(sys, rtClass, propertyName); // TODO: remove - for debugging only
                     }
                  }
               }
            }
            // rtClass == null when we are building a new layer
         }
      }

      Boolean val;
      if (propertiesToMakeBindable == null) {
         // Must be a tree for consistent order of properties.  As things change, this list may get build up in a different order but we need the generated classes to look the same.
         propertiesToMakeBindable = new TreeMap<String,Boolean>();
         val = null;
      }
      else
         val = propertiesToMakeBindable.get(propertyName);

      // Update the entry if it's not there or it's there but set to reference only and this is not reference only
      if ((val == null || val) && !referenceOnly) {
         propertiesToMakeBindable.put(propertyName, referenceOnly);
         if (fromModel != null)
            getJavaModel().addBindDependency(this, propertyName, fromModel.getModelTypeDeclaration(), referenceOnly);
      }
   }

   public boolean isStaticObject() {
      return getDeclarationType() == DeclarationType.OBJECT && (getEnclosingType() == null || isStaticType());
   }

   public boolean getDefinesCurrentObject() {
      return getDeclarationType() == DeclarationType.OBJECT;
   }

   public List<Object> getAllMethods(String modifier, boolean hasModifier, boolean isDyn, boolean overridesComp) {
      List<Object> result = super.getAllMethods(modifier, hasModifier, isDyn, overridesComp);

      initTypeInfo();

      if (implementsBoundTypes != null) {
         for (Object impl:implementsBoundTypes) {
            Object[] implResult = ModelUtil.getAllMethods(impl, modifier, hasModifier, isDyn, overridesComp);
            if (implResult != null && implResult.length > 0) {
               if (result == null)
                  result = new ArrayList<Object>();
               result = ModelUtil.appendInheritedMethods(implResult, result);
               //result.addAll(Arrays.asList(implResult));
            }
         }
      }
      return result;
   }

   public List<Object> appendInterfaceMethods(List<Object> result, String methodName, String modifier, boolean includeExtends) {
      initTypeInfo();

      if (implementsBoundTypes != null && includeExtends) {
         for (Object impl:implementsBoundTypes) {
            if (impl != null) {
               // TODO: If we do this, it creates a lot more work to map type-parameters etc, but the ParamTypeDeclaration
               // does have the mapping between the type parameters of the implementing class and the interface.  Do we
               // need that?  I feel like ordinarily we would expect the type parameters to be in the context of the interface
               // anyway so unwrapping it here.
               if (impl instanceof ParamTypeDeclaration)
                  impl = ((ParamTypeDeclaration) impl).baseType;
               Object[] implResult = ModelUtil.getMethods(impl, methodName, modifier);
               if (implResult != null && implResult.length > 0) {
                  result = ModelUtil.appendInheritedMethods(implResult, result);
               }
            }
         }
      }
      return result;
   }

   public List<Object> getMethods(String methodName, String modifier, boolean includeExtends) {
      List<Object> result = super.getMethods(methodName, modifier, includeExtends);

      return result;
   }


   public List<Object> getAllProperties(String modifier, boolean includeAssigns) {
      List<Object> result = super.getAllProperties(modifier, includeAssigns);

      initTypeInfo();

      if (implementsBoundTypes != null) {
         for (Object impl:implementsBoundTypes) {
            Object[] implResult = impl == null ? null : ModelUtil.getProperties(impl, modifier, includeAssigns);
            if (implResult != null && implResult.length > 0) {
               if (result == null)
                  result = new ArrayList<Object>();

               List implList = removeStaticProperties(implResult);
               // Not replacing properties here... preserve the offsets of properties defined in a real class
               // Interfaces can add new abstract properties
               result = ModelUtil.mergeProperties(implList, result, true, includeAssigns);
            }
         }
      }
      if (hiddenBody != null) {
         if (result == null)
            result = new ArrayList<Object>();
         addAllProperties(hiddenBody, result, modifier, includeAssigns, false);
      }
      return result;
   }

   public List<Object> getDeclaredProperties(String modifier, boolean includeAssigns, boolean includeModified, boolean editorProperties) {
      List<Object> res = super.getDeclaredProperties(modifier, includeAssigns, includeModified, editorProperties);
      if (editorProperties) {
         if (getInheritProperties()) {
            Object extType = getExtendsTypeDeclaration();
            if (extType != null) {
               if (ModelUtil.getExportProperties(getLayeredSystem(), extType)) {
                  Object[] extRes = ModelUtil.getDeclaredProperties(extType, modifier, includeAssigns, includeModified, editorProperties);
                  List<Object> extProps;
                  if (extRes != null)
                     extProps = Arrays.asList(extRes);
                  else
                     extProps = null;

                  res = ModelUtil.mergeProperties(extProps, res, true, includeAssigns);
               }
            }
         }
      }
      return res;
   }

   public boolean isCompiledProperty(String propName, boolean fieldMode, boolean interfaceMode) {

      // We only want this to return true when there's a compiled implementation of the property.  It gets used to decide if we need to make a dynamic property for this given type.  if we have a compiled
      // interface we need the dynamic property and implementation of that interface.
      if (getDeclarationType() == DeclarationType.INTERFACE && !interfaceMode)
         return false;

      if (!fieldMode) {
         Object extType = getExtendsTypeDeclaration();
         if (extType != null && ModelUtil.isCompiledProperty(extType, propName, fieldMode, interfaceMode))
            return true;
      }


      if (super.isCompiledProperty(propName, fieldMode, false))
         return true;

      // A compiled class implementing an interface with non-static fields will generate these properties as compiled properties.
      if (!isDynamicNew()) {
         if (implementsBoundTypes != null) {
            for (Object impl:implementsBoundTypes) {
               if (ModelUtil.isCompiledProperty(impl, propName, fieldMode, true))
                  return true;
            }
         }
      }

      return false;
   }

   /** When hasModifier is true, presence of the modifier matches.  When it is false, lack of modifier matches */
   public List<Object> getAllFields(String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified) {
      List<Object> result = super.getAllFields(modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);

      initTypeInfo();

      // TODO: why is this not in BodyTypeDeclaration?
      if (hiddenBody != null) {
         if (result == null)
            result = new ArrayList<Object>();
         addAllFields(hiddenBody, result, modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);
      }

      // Do not process interfaces when we are looking for dynamicOnly properties and this is a compiled type
      if (implementsBoundTypes != null && (!dynamicOnly || isDynamicType())) {
         for (Object impl:implementsBoundTypes) {
            // Since the interfaces do not actually implement the field, we'll return these as dynamic properties
            //if (dynamicOnly && !ModelUtil.isDynamicType(impl))
            //   continue;
            Object[] implResult = ModelUtil.getFields(impl, modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);

            if (implResult != null && implResult.length > 0) {
               ArrayList<Object> implRes = new ArrayList<Object>(implResult.length);

               // Only add interface properties if there's no compiled implementation of the property already in the type.  This is the case where you have a compiled property being
               // set by a dynamic interface.  We are looking for any property with this name, not just fields with this name (i.e. check more than just this class)
               for (Object ir:implResult) {
                  if (!dynamicOnly || !ModelUtil.isCompiledProperty(this, ModelUtil.getPropertyName(ir), false, false)) {
                     implRes.add(ir);
                  }
               }

               if (result == null) {
                  result = new ArrayList<Object>();
                  result.addAll(implRes);
               }
               else {
                  result = ModelUtil.mergeProperties(result, implRes, true, includeAssigns);
               }
            }
         }
      }

      return result;
   }

   private void addIFieldDefinition(Object modifiers, JavaType type, String varName, String operator, Expression initializer, boolean bindable, ISrcStatement fromSt) {
      initializer = initializer == null ? null : (Expression) initializer.deepCopy(CopyNormal, null);
      FieldDefinition newField = FieldDefinition.createFromJavaType(type, varName, operator, initializer);
      if (modifiers instanceof ISemanticNode)
         modifiers = ((ISemanticNode) modifiers).deepCopy(CopyNormal, null);
      newField.setProperty("modifiers", modifiers);
      VariableDefinition newVarDef = newField.variableDefinitions.get(0);
      newVarDef.convertGetSet = true;
      newVarDef.bindable = bindable;
      newVarDef.fromStatement = fromSt;
      newField.fromStatement = fromSt;
      addBodyStatementIndent(newField);
   }

   public List<Object> getCompiledIFields() {
      List<Object> result = null;

      /*
        * We do not inherit compiled ifields from the extends class since they were already included
        * in the definition of those claslses.
      Object derivedType = getDerivedTypeDeclaration();
      Object extType = getExtendsTypeDeclaration();

      if (derivedType != null && derivedType instanceof BodyTypeDeclaration)
         result = ((BodyTypeDeclaration) derivedType).getCompiledIFields();

      if (extType != derivedType && extType instanceof BodyTypeDeclaration) {
         List<Object> extResult = ((BodyTypeDeclaration) derivedType).getCompiledIFields();
         if (extResult != null) {
            if (result == null)
               result = extResult;
            else
               result = ModelUtil.mergeProperties(result, extResult, false);
         }
      }

      */

      if (implementsBoundTypes == null)
         return null;

      for (Object impl:implementsBoundTypes) {
         // TODO: deal with compiled interfaces fields here!
         if (!(impl instanceof TypeDeclaration)) {
            continue;
         }
         TypeDeclaration iface = (TypeDeclaration) impl;
         List<Object> implResult = iface.getCompiledIFields();
         if (implResult != null && implResult.size() > 0) {
            if (result == null) {
               result = new ArrayList<Object>();
               result.addAll(implResult);
            }
            else {
               result = ModelUtil.mergeProperties(result, implResult, false);
            }
         }
      }
      return result;
   }

   public void transformIFields() {
      if (implementsBoundTypes != null) {
         List<Object> result = getCompiledIFields();
         List<JavaModel> importedInterfaceModels = new ArrayList<JavaModel>();
         if (result != null) {
            for (int i = 0; i < result.size(); i++) {
               Object res = result.get(i);
               String propName = ModelUtil.getPropertyName(res);
               Object member = definesMember(propName, MemberType.PropertyAnySet, null, null);
               Object resVarDefObj;
               if (res instanceof PropertyAssignment) {
                  resVarDefObj = ((PropertyAssignment) res).getAssignedProperty();
               }
               else
                  resVarDefObj = res;

               VariableDefinition resVarDef = null;
               if (resVarDefObj instanceof VariableDefinition)
                  resVarDef = (VariableDefinition) resVarDefObj;

               // If there's no definition in the base class, we'll add this field - because we are in mid-transform, we might have redefined the field but if the parent types are the same, it's still in the interface.
               if (member == null || member == res || (res != null && ModelUtil.getEnclosingType(member) == ModelUtil.getEnclosingType(res))) {
                  if (res instanceof PropertyAssignment) {
                     PropertyAssignment assign = (PropertyAssignment) res;
                     Object varDefObj = assign.getAssignedProperty();
                     if (varDefObj instanceof VariableDefinition) {
                        VariableDefinition varDef = (VariableDefinition) varDefObj;
                        FieldDefinition fd = (FieldDefinition) varDef.getDefinition();
                        IVariableInitializer initializer = assign.getInheritedMember();
                        Expression initExpr = assign.getInitializerExpr();

                        // Need to call this so we have the types imported before the referenced field is initialized
                        addInterfaceImports(res);
                        // Note addFieldDefinition does a deep copy on the initExpr
                        addIFieldDefinition(fd.modifiers, fd.type, varDef.variableName, initializer.getOperatorStr(), initExpr, varDef.bindable, assign);
                     }
                     else // Right now we can only inherit interface fields from source based definitions - this is a special case of one where we refer to a compiled definition.  Need a metadata solution to store the info we need to create the field
                        System.err.println("*** Interface property refers to compiled field!");
                  }
                  else if (resVarDef != null) {
                     FieldDefinition fd = (FieldDefinition) resVarDef.getDefinition();
                     Expression initExpr = resVarDef.initializer;
                     addIFieldDefinition(fd.modifiers, fd.type, resVarDef.variableName, resVarDef.operator, initExpr, resVarDef.bindable, resVarDef);
                     addInterfaceImports(res);
                  }
               }
               // Inheriting a definition of this property.  Ensure the types match.
               else if (!ModelUtil.sameTypes(ModelUtil.getEnclosingType(member), this)) {
                  Object membType = ModelUtil.getPropertyType(member);
                  Object resType = ModelUtil.getPropertyType(res);
                  // TODO: this needs to move into start and use error
                  if (!ModelUtil.isAssignableFrom(membType, resType))
                     System.err.println("*** Error: inheriting a property from an interface whose type is not compatible with the type of the field of the same name in the base class");
                  else {
                     if (res instanceof IVariableInitializer) {
                        // The interface is defined as a field, turn it into a property assignment since we are overriding
                        if (resVarDef != null) {
                           IVariableInitializer initMember = (IVariableInitializer) res;
                           if (res instanceof PropertyAssignment)
                              initMember = ((PropertyAssignment) res).getInheritedMember();
                           Expression initializer = initMember.getInitializerExpr() == null ? null : (Expression) initMember.getInitializerExpr().deepCopy(CopyNormal, null);
                           if (initializer != null) {
                              PropertyAssignment pa = PropertyAssignment.create(resVarDef.variableName, initializer, initMember.getOperatorStr());
                              addInterfaceImports(res);
                              addBodyStatementIndent(pa);
                           }
                        }
                        // It's already a property assignment - just clone it into the class to override the value
                        else {
                           addInterfaceImports(res);
                           addBodyStatementIndent((Statement) ((SemanticNode) res).deepCopy(CopyNormal, null));
                        }
                     }
                     else
                        System.err.println("*** compiled IField property? ");
                  }
               }
               // There's a property assignment in this type - need to check if it is derived from the field in the interface.  If so, this initializer overrides that one.
               else if (member instanceof PropertyAssignment) {
                  PropertyAssignment pa = (PropertyAssignment) member;
                  Object assignedProp = pa.getAssignedProperty();
                  // The assignment in the class is referring to the interface's definition - that means we need to merge that definition with this initializer
                  if (assignedProp == resVarDef) {
                     FieldDefinition fd = (FieldDefinition) resVarDef.getDefinition();
                     IVariableInitializer initializer = pa.getInheritedMember();
                     addInterfaceImports(res);
                     addIFieldDefinition(fd.modifiers, fd.type, resVarDef.variableName, initializer.getOperatorStr(), pa.getInitializerExpr(), resVarDef.bindable, pa);
                  }
               }
               // else - there's a definition for a field in this type.  even if there's no initializer, we will not use the one in the interface.  This is a questionable case but basing this on the need to inherit from an interface and override the value to null.
               else if (member instanceof VariableDefinition) {
                  VariableDefinition varDef = (VariableDefinition) member;
                  varDef.convertGetSet = true;
                  if (resVarDef != null)
                     varDef.bindable |= resVarDef.bindable;
               }

               /* This is the case where we have an interface field which we've expanded to getX/setX methods but are inheriting a property using an "isX" method.  Since the interface has getX we need to add a getX method which calls the isX method - e.g. "isOpaque" in swing's jComponent */
               if (ModelUtil.isPropertyIs(member)) {
                  String getName = "get" + CTypeUtil.capitalizePropertyName(propName);
                  Object membType = ModelUtil.getPropertyType(member);
                  Object existingMeth = definesMethod(getName, null, null, null, true, false, null, null);
                  if (existingMeth == null || existingMeth == res || ModelUtil.isAbstractMethod(existingMeth)) {
                     MethodDefinition meth = new MethodDefinition();
                     meth.name = getName;
                     SemanticNodeList<Object> mods = new SemanticNodeList<Object>();
                     mods.add("public");
                     meth.setProperty("modifiers", mods);
                     meth.setProperty("type", JavaType.createJavaType(getLayeredSystem(), membType));

                     BlockStatement methBody = new BlockStatement();
                     methBody.visible = true;
                     SemanticNodeList<Statement> statements = new SemanticNodeList<Statement>();
                     methBody.setProperty("statements", statements);
                     ReturnStatement ret = ReturnStatement.create(IdentifierExpression.createMethodCall(new SemanticNodeList<Expression>(), "is" + CTypeUtil.capitalizePropertyName(propName)));
                     statements.add(ret);
                     meth.setProperty("body", methBody);
                     addInterfaceImports(res);
                     addBodyStatementIndent(meth);
                  }
               }

            }
         }
      }
   }

   public void addInterfaceImports(Object res) {
      // If we have copied a property from the interface, need to import the interfaces imports so that
      // we can resolve all of the types we just copied.  The copyImports method ensures we do not copy the
      // same set of imports more than once.
      Object enclType = ModelUtil.getEnclosingType(res);
      if (enclType instanceof BodyTypeDeclaration) {
         JavaModel ifaceModel = ((BodyTypeDeclaration) enclType).getJavaModel();
         JavaModel thisModel = getJavaModel();
         thisModel.copyImports(ifaceModel);
      }
   }

   public List<Object> getAllInnerTypes(String modifier, boolean thisClassOnly, boolean editorProperties) {
      List<Object> result = super.getAllInnerTypes(modifier, thisClassOnly, editorProperties);

      if (!thisClassOnly || (editorProperties && getInheritProperties())) {
         // Only init the type info if we are looking for the implements types.  In updateType we do not want to init the type just to do the update since
         // the update happens during the 'reinitialize' process, which is too soon to accurately resolve types.  We need to reinit the other types first.
         initTypeInfo();

         LayeredSystem sys = getLayeredSystem();

         if (implementsBoundTypes != null) {
            for (Object impl : implementsBoundTypes) {
               if (!thisClassOnly || ModelUtil.getExportProperties(sys, impl)) {
                  Object[] implResult = ModelUtil.getAllInnerTypes(impl, modifier, thisClassOnly, editorProperties);
                  if (implResult != null && implResult.length > 0) {
                     if (result == null)
                        result = new ArrayList<Object>();
                     result.addAll(Arrays.asList(implResult));
                  }
               }
            }
         }
      }
      return result;
   }

   public void visitTypeReferences(CycleInfo info, TypeContext ctx) {
      if (replacedByType != null) {
         replacedByType.visitTypeReferences(info, ctx);
         return;
      }

      // Not sure why we were doing this before...  it clearly fails when you have a simple binding of an object to it's child
      // e.g. - editorPanel := body.editorPanel;
      //if (isReferenceValueObject()) {
      // TODO: propagate through the various extends
     //    if (body != null) {
     //       for (Statement s:body) {
     //          s.visitTypeReferences(info, ctx);
     //       }
     //    }
      //}
   }

   public boolean isReferenceValueObject() {
      DeclarationType declType = getDeclarationType();
      if (declType == DeclarationType.OBJECT && !isComponentType()) {
         return true;
      }
      return false;
   }


   /** Returns the runtime class name for this object */
   public String getObjectClassName() {
      Object constructClass = getClassDeclarationForType();
      return ModelUtil.getTypeName(constructClass, false, true);
   }

   public ObjectContextTemplateParameters getScopeTemplateParameters() {
      return new ObjectContextTemplateParameters(getObjectClassName(), this);
   }


   public List<?> getClassTypeParameters() {
      return typeParameters;
   }

   public Object getSimpleInnerType(String name, TypeContext ctx, boolean checkBaseType, boolean redirected, boolean srcOnly, boolean includeEnums) {
      Object t = super.getSimpleInnerType(name, ctx, checkBaseType, redirected, srcOnly, includeEnums);
      if (t != null)
         return t;

      if (checkBaseType) {
         if (implementsBoundTypes != null) {
            for (Object impl:implementsBoundTypes) {
               if ((t = getSimpleInnerTypeFromExtends(impl, name, ctx, redirected, srcOnly, false)) != null)
                  return t;
            }
         }
      }
      return t;
   }

   public String mapTypeParameterNameToTypeName(String childTypeParameter) {
      Object typeRef = getDerivedTypeDeclaration();
      while (typeRef != null) {
         if (typeRef instanceof ParamTypeDeclaration) {
            Object paramType = ((ParamTypeDeclaration) typeRef).getTypeDeclarationForParam(childTypeParameter, null, true);
            if (paramType != null)
               return ModelUtil.getTypeName(paramType);
         }
         // This case is used from ObjectDefinitionParameters for swing.expertSystem with scope<ListItem>'s scope mixin template.
         List<?> typeParams = ModelUtil.getTypeParameters(typeRef);
         if (typeParams != null) {
            Object extType = ModelUtil.getExtendsJavaType(this);
            if (extType != null) {
               for (int i = 0; i < typeParams.size(); i++) {
                  if (ModelUtil.getTypeParameterName(typeParams.get(i)).equals(childTypeParameter)) {
                     Object mappedType = ModelUtil.getTypeArgument(extType, i);
                     if (mappedType != null)
                        return ModelUtil.getTypeName(mappedType);
                  }
               }
            }
         }
         typeRef = ModelUtil.getSuperclass(typeRef);
      }
      // No bound parameter for this name - default is Object
      return "Object";
   }


   /** Returns the type we use at runtime */
   public Object getRuntimeType() {
      if (isDynamicNew())
         return this;
      else
         return getCompiledClass();
   }

   /** Returns the type we use at runtime */
   public String getRuntimeTypeName() {
      if (isDynamicNew())
         return this.getFullTypeName();
      else
         return getCompiledClassName();
   }

   public Object[] getImplementsTypeDeclarations() {
      return implementsBoundTypes;
   }

   /** Returns the first compiled interface */
   public Object getCompiledImplements() {
      Object[] scopeIfaces = getScopeInterfaces();
      if (implementsBoundTypes == null && scopeIfaces.length == 0)
         return null;


      Object compiledImpl = null;
      if (implementsBoundTypes != null) {
         for (Object impl:implementsBoundTypes) {
            // Pick the first compiled interface
            if (compiledImpl == null && !ModelUtil.isDynamicType(impl))
               compiledImpl = impl;
         }
      }
      if (compiledImpl == null) {
         for (Object impl:scopeIfaces) {

            // Skip this as currently we do not put it into the compiled interface for a dynamic stub.
            if (impl == IObjChildren.class)
               continue;

            if (compiledImpl == null && !ModelUtil.isDynamicType(impl))
               compiledImpl = impl;
         }
      }
      return compiledImpl;
   }

   public Object[] getCompiledImplTypes() {
      if (implementsBoundTypes == null && scopeInterfaces.length == 0)
         return null;

      List<Object> compiledImpl = null;
      if (implementsBoundTypes != null) {
         for (Object impl:implementsBoundTypes) {
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

   /** Returns the JavaTypes so we preserve the type parameters */
   public Object[] getCompiledImplJavaTypes() {
      JavaType[] scopeIfaces = null; // getScopeInterfaceJavaTypes(); TODO - do we include the scopeInteraces here?  If so, we also need to add the obj/mixin templates which defines these methods - e.g. ListItemScope
      if (implementsTypes == null && scopeIfaces == null)
         return null;

      List<Object> compiledImpl = null;
      if (implementsTypes != null) {
         for (int i = 0; i < implementsTypes.size(); i++) {
            Object impl = implementsBoundTypes[i];
            // Get all compiled implemented types
            if (!ModelUtil.isDynamicType(impl)) {
               if (compiledImpl == null)
                  compiledImpl = new ArrayList<Object>();
               compiledImpl.add(implementsTypes.get(i));
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
      }
      if (scopeIfaces != null) {
         if (compiledImpl == null)
            compiledImpl = new ArrayList<Object>();
         for (JavaType scopeIface:scopeIfaces) {
            if (!compiledImpl.contains(scopeIface))
               compiledImpl.add(scopeIface);
         }
      }
      if (compiledImpl == null)
         return null;
      return compiledImpl.toArray(new Object[compiledImpl.size()]);
   }

   private static int maxParentDepth = 64;

   public String toString() {
      try {
         String res = typeName;
         if (res == null)
            res = "<no type name>";
         if (parentNode != null) {
            BodyTypeDeclaration encl = getEnclosingType();
            int ct = 0;
            while (encl != null && ct < maxParentDepth) {
               res = encl.typeName + "." + res;
               if (encl.parentNode != null)
                  encl = encl.getEnclosingType();
               else {
                  res = "..null-parent.." + res;
                  break;
               }
               ct++;
            }
            if (ct == maxParentDepth) {
               System.err.println("*** Max class nesting depth in toString for type: ");
            }
         }
         JavaModel model = getJavaModel();
         LayeredSystem sys = getLayeredSystem();
         String runtime = sys != null ? " (runtime: " + sys.getRuntimeName() + ")" : "";
         String transformed = model != null && model.nonTransformedModel != null ? " (transformed)" : "";
         return res + (getLayer() != null ? " (layer:" + getLayer().getLayerName() + ")" : "") + runtime + transformed;
      }
      catch (NullPointerException exc) {
         System.err.println("*** error in to string for: " + typeName);
         exc.printStackTrace();
         return "<NullPointerException>";
      }
   }

   public boolean getInheritDynamicFromLayer() {
      return true;
   }

   public boolean needsTransform() {
      return isComponentType() || getDeclarationType() == DeclarationType.OBJECT || isDynamicType() || getAnnotation("sc.obj.Sync", true) != null ||
              propertiesToMakeBindable != null || getAnnotation("sc.obj.CompilerSettings", true) != null || super.needsTransform();
   }

   void transformBindableProperties(ILanguageModel.RuntimeType runtime) {
      for (Map.Entry<String,Boolean> pent:propertiesToMakeBindable.entrySet()) {
         // Only do those which are not reference only.   The reference only properties are handled as part
         // of the DynType transform
         if (!pent.getValue()) {
            String propertyName = pent.getKey();
            TransformUtil.makePropertyBindable(this, propertyName, runtime);
         }
      }
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      if (!processed)
         process();

      boolean any = false;

      // Any special init-time code that the tag type needs to add.  This could be done with a mixin but we already have the element dependency
      if (element != null)
         element.addMixinProperties(this);

      /* Any properties not declared as fields on this type but which have bindability injected as a wrapper. */
      if (propertiesToMakeBindable != null) {
         // For interfaces, we should have inherited the 'bindable' flag in the implementing classes, or logged an error - nothing to do here
         if (getDeclarationType() != DeclarationType.INTERFACE) {
            any = true;
            transformBindableProperties(runtime);
         }
      }

      if (!useRuntimeReflection)
         transformCompiledType();

      if (body != null) {
         for (int i = 0; i < body.size(); i++) {
            Statement st = body.get(i);
            // Excluded types are handled elsewhere
            if (st instanceof TypeDeclaration)
               continue;
            // This statement has @Exec and is not included in this runtime
            if (st.excluded) {
               body.remove(i);
               i--;
            }
         }
      }

      if (super.transform(runtime))
         any = true;

      return any;
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      super.addDependentTypes(types, mode);

      if (implementsTypes != null) {
         for (JavaType t:implementsTypes)
            t.addDependentTypes(types, mode);
      }
   }

   public void setAccessTimeForRefs(long time) {
      super.setAccessTimeForRefs(time);
      if (implementsTypes != null)
         for (JavaType t:implementsTypes)
            t.setAccessTimeForRefs(time);
   }

   private transient DynStubParameters dynamicParams;

   public DynStubParameters getDynamicStubParameters() {
      if (dynamicParams == null)
         dynamicParams = new DynStubParameters(getLayeredSystem(), getLayer(), this);
      return dynamicParams;
   }

   /*
    * Returns true if this file needs a reflective DynType object built for it, i.e. when not using reflection and in compiled mode.
    * The useRuntimeReflection must
    * be turned off for this type (either at the system or via CompilerSettings.
    * <p>
    * Not to be confused with needsDynamicStub - the case where we are generating a dynamic stub for a class in dynamic mode
    */
   public boolean needsDynType() {
      if (useRuntimeReflection)
         return false;
      DynStubParameters params = getDynamicStubParameters();
      return params.getNumCompMethods() != 0 || params.getNumCompProps() != 0;
   }

   private final static String DYN_TEMPLATE_FILE = "sc/lang/java/DynTypeTemplate.sctdynt";
   private final static String STATIC_DYN_TEMPLATE_FILE = "sc/lang/java/StaticDynTypeTemplate.sctdynt";

   public void transformCompiledType() {
      // Do this for classes for which we're generating reflective dyn type info - don't do it for types for which there's no compiled class
      if ((needsDynType() || ModelUtil.getSuperInitTypeCall(getLayeredSystem(), this) != null) && needsOwnClass(false)) {
         DynStubParameters params = getDynamicStubParameters();
         if (!ModelUtil.isAssignableFrom(IDynObject.class, this)) {
            addImplements(ClassType.create("sc.dyn.IDynObject"));
         }

         // Try to put the definitions for the static stuff in the inner type.   That does not work though
         // for instance inner types.  For now, we're using getEnclosingType here (and in params.getInnerName()).
         // Needs to be consistent with where we put the resolvePropertyMapping calls.
         BodyTypeDeclaration outer = getEnclosingType();
         TypeDeclaration rootType = this;
         if (outer != null)
            rootType = getRootType();

         // Insert getPropertyMapping calls after this stuff.  On GWT, we need the static section to run to define the type
         // before we start accessing types with getPropertyMapping calls.  It's not enough to just put these at the top of the static
         // section but getting all types defined before you start referencing types is necessary when you have recursive dependencies.
         rootType.propDefInsertIndex += TransformUtil.parseClassBodySnippetTemplate(rootType, STATIC_DYN_TEMPLATE_FILE, params, false, 0, "<static dyn template file>");

         TransformUtil.parseClassBodySnippetTemplate(this, DYN_TEMPLATE_FILE, params, false, -1, "<dyn template file>");
      }
   }

   private void initDynTypeInfo() {
      if (dynTypeInfoInitialized)
         return;
      dynTypeInfoInitialized = true;
      // Defaults to true
      Object setting;
      setting = getCompilerSetting("useRuntimeReflection", true);
      if (setting == null || !(setting instanceof Boolean))
         useRuntimeReflection = getLayeredSystem() != null ? getLayeredSystem().useRuntimeReflection : true;
      else
         useRuntimeReflection = (Boolean)setting;

      setting = getCompilerSetting("useExternalDynType", true);
      if (setting == null || !(setting instanceof Boolean))
         useExternalDynType = false;
      else
         useExternalDynType = (Boolean)setting;

   }

   public boolean getUseRuntimeReflection() {
      initDynTypeInfo();
      return useRuntimeReflection;
   }

   public TypeDeclaration getVirtualTypeForInstance(Object inst) {
      // See if this instance's type defines a more specific version of this type than this one
      if (inst instanceof IDynObject) {
         Object typeObj = ((IDynObject) inst).getDynType();
         if (typeObj instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration td = (BodyTypeDeclaration) typeObj;
            Object innerTypeObj = td.getInnerType(typeName, null);
            if (innerTypeObj instanceof TypeDeclaration) {
               return (TypeDeclaration) innerTypeObj;
            }
         }
      }
      return this;
   }

   /** Returns true if this object definition has a property associated with it.  Scoped objects or those which otherwise set the useNewTemplate do not have an "a.b" type property */
   public boolean isObjectProperty() {
      return getDeclarationType() == DeclarationType.OBJECT && !useNewTemplate;
   }

   public void initDynStatements(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode, boolean initExt) {
      // Compiled types will have compiled the interface settings into their init and so do not process them now
      if (isDynamicType()) {
         if (implementsBoundTypes != null && initExt) {
            for (int i = 0; i < implementsBoundTypes.length; i++) {
               Object impl = implementsBoundTypes[i];
               if (impl instanceof BodyTypeDeclaration) {
                  // Doing this even for compiled interfaces - unlike a base class, the interface does not compile in it's initial state (unless the referencing class is compiled)
                  ((BodyTypeDeclaration) impl).initDynStatements(inst, ctx, mode, true);
               }
            }
         }
      }
      super.initDynStatements(inst, ctx, mode, initExt);
   }

   public boolean refreshBoundTypes(int flags) {
      boolean res = super.refreshBoundTypes(flags);
      if (implementsTypes != null)
         for (JavaType jt:implementsTypes) {
            if (jt.refreshBoundType(flags))
               res = true;
         }
      if (implementsBoundTypes != null) {
         JavaModel m = getJavaModel();
         for (int i = 0; i < implementsBoundTypes.length; i++) {
            Object implType = ModelUtil.refreshBoundType(getLayeredSystem(), implementsBoundTypes[i], flags);
            if (implType != implementsBoundTypes[i]) {
               res = true;
               implementsBoundTypes[i] = implType;
               if (implType instanceof TypeDeclaration) {
                  m.layeredSystem.addSubType((TypeDeclaration) implType, this);
               }
            }
         }
      }
      /*
      if (transformedType != null)
         transformedType.refreshBoundTypes();
      */
      return res;
   }

   protected boolean isInterfaceField(Statement st) {
      if (!(st instanceof FieldDefinition))
         return false;
      FieldDefinition fld = (FieldDefinition) st;
      // Need to skip the rule which implicity sets these on fields of interfaces by using hasDefinedModifier instead of hasModifier
      return !fld.hasDefinedModifier("final") && !fld.hasDefinedModifier("static");
   }

   protected void addAllIFields(SemanticNodeList<Statement> body, List<Object> props, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns) {
      if (body == null)
         return;

      for (int i = 0; i < body.size(); i++) {
         Statement member = body.get(i);
         if (isInterfaceField(member)) {
            FieldDefinition field = (FieldDefinition) member;
            for (VariableDefinition def:field.variableDefinitions) {
               // Do not add if we already have the object for include objs
               if (includeObjs) {
                  int rix = ModelUtil.propertyIndexOf(props, def, true);
                  if (rix != -1)
                     continue;
               }
               if (dynamicOnly && isCompiledProperty(def.variableName, true, false))
                  continue;
               props.add(def);
            }
         }
         else if (includeObjs && member instanceof BodyTypeDeclaration && (ModelUtil.isObjectType(member) || ModelUtil.isEnum(member))) {
            BodyTypeDeclaration subObj = (BodyTypeDeclaration) member;

            // If the object is overriding a property, do not include it in the list of fields.  Technically it is not adding a field - it's using an existing one.  If we create a field for it, we'll set that instead of the backing property
            if (subObj.objectSetProperty)
               continue;

            // Do not return both a field and an object of the same name.  This happens when we've already transformed the object for example.
            int rix = ModelUtil.propertyIndexOf(props, member, true);
            if (rix != -1)
               props.remove(rix);

            if (dynamicOnly && isCompiledProperty(subObj.typeName, true, false))
               continue;

            // Make sure to use the override type so dynamic types point to the most specific type member
            props.add(subObj.resolve(true));
         }
         else if (includeAssigns && member instanceof PropertyAssignment) {
            PropertyAssignment assign = (PropertyAssignment) member;
            Object prop = assign.getAssignedProperty();
            if (prop instanceof VariableDefinition && isInterfaceField(((VariableDefinition) prop).getDefinition()))
               props.add(member);
         }
      }
   }

   public String getFullTypeName() {
      // In the parsing tests, the template model does not have a source file and so cannot determine its type the way it does now.
      if (modelType != null && (!(modelType instanceof Template) || ((Template) modelType).getSrcFile() != null))
         return modelType.getFullTypeName();
      return super.getFullTypeName();
   }

   public Statement transformToJS() {
      if (implementsTypes != null) {
         for (JavaType impl:implementsTypes)
            impl.transformToJS();
      }
      return super.transformToJS();
   }

   public Object[] getAllImplementsTypeDeclarations() {
      Object[] thisImpls = getImplementsTypeDeclarations();
      Object ext = getDerivedTypeDeclaration();
      if (ext != null) {
         Object[] extImpls = ModelUtil.getAllImplementsTypeDeclarations(ext);
         if (extImpls == null)
            return thisImpls;
         if (thisImpls == null)
            return extImpls;
         ArrayList<Object> res = new ArrayList<Object>(thisImpls.length + extImpls.length);
         res.addAll(Arrays.asList(thisImpls));
         for (int i = 0; i < extImpls.length; i++) {
            if (!res.contains(extImpls[i]))
               res.add(extImpls[i]);
         }
         return res.toArray();
      }
      return thisImpls;
   }

   public boolean isGeneratedType() {
      return element != null || modelType != null;
   }

   public Object getGeneratedFromType() {
      if (element != null)
         return element;
      return modelType;
   }

   // TemplateDeclarations extend TypeDeclaration but are not real types so they get skipped in getEnclosingType
   public boolean isRealType() {
      return true;
   }

   public TypeDeclaration deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      TypeDeclaration res = (TypeDeclaration) super.deepCopy(options, oldNewMap);

      if ((options & CopyInitLevels) != 0) {
         res.implementsBoundTypes = implementsBoundTypes == null ? null : implementsBoundTypes.clone();
         res.typeInfoInitialized = typeInfoInitialized;
         res.constructorsInited = constructorsInited;
         res.skippedClassVarName = skippedClassVarName;
         res.useNewTemplate = useNewTemplate;
         res.modelType = modelType;
         res.useRuntimeReflection = useRuntimeReflection;
         res.useExternalDynType = useExternalDynType;
         res.propDefInsertIndex = propDefInsertIndex;
         res.autoComponent = autoComponent;

         res.element = element;
      }

      return res;
   }

   // When changed models is null - any changed types where there's a file dependent we return true.  When it's set, only
   // changed files in the dependency chain after the sinceLayer are considered.
   public boolean changedSinceLayer(Layer sinceLayer, Layer genLayer, boolean resolve, IdentityHashSet<TypeDeclaration> visited, Set<String> changedTypes, boolean processJava) {
      if (visited == null)
         visited = new IdentityHashSet<TypeDeclaration>();
      else if (visited.contains(this))
         return false;

      visited.add(this);

      // Get the most specific version of this type - i.e. walk up the modified food-chain.
      TypeDeclaration td = resolve ? (TypeDeclaration) resolve(true) : this;

      String fullTypeName = td.getFullTypeName();
      Layer typeLayer = td.getLayer();

      if (changedTypes == null || changedTypes.contains(fullTypeName)) {
         if (typeLayer == null)
            return true;
         // If it's an annotation layer, we don't generate anything for it so don't consider
         if (sinceLayer == null) {
            boolean fixedModel = false;
            // If the type layer is already compiled and final - e.g. sys.sccore the model is not changed even if sinceLayer == null but not for JS where we process Java files
            if (typeLayer.finalLayer && typeLayer.compiled && !processJava)
               fixedModel = true;

            // We do not have to consider annotation layers as changed at least in terms of dependent files.  They are not transformed anyway so will never have a "sinceLayer" that's not null
            if (typeLayer.annotationLayer)
               fixedModel = true;

            // Unless it's a special case model (i.e. one that's fixed in this case) if it has not been transformed yet, it's changed
            if (!fixedModel)
               return true;
         }
         if (sinceLayer != null) {
            if (genLayer == null || typeLayer.getLayerPosition() > sinceLayer.getLayerPosition())
               return true;
         }
      }

      Layer fromLayer = genLayer.getNextLayer();

      // For the root type, see if it's been modified by another file in between the layer we are building now to the layer where it
      // was initialized.  If so, we need to stop it before we do the build.
      if (getEnclosingType() == null) {
         LayeredSystem sys = getLayeredSystem();
         JavaModel model = getJavaModel();
         SrcEntry srcEnt = sys.getSrcFileFromTypeName(fullTypeName, true, fromLayer, model.getPrependPackage(), null, typeLayer, false);
         if (srcEnt != null) {
            Layer srcLayer = srcEnt.layer;
            // Is there a src file included in this build which is after the layer where we last initialized the type.  If so we need to
            // restart it on this build.
            if (sinceLayer != null && srcLayer != null && srcLayer.getLayerPosition() > sinceLayer.getLayerPosition())
               return true;
         }
      }

      Object extTD = td.getExtendsTypeDeclaration();
      if (extTD instanceof TypeDeclaration && ((TypeDeclaration)extTD).changedSinceLayer(sinceLayer, genLayer, true, visited, changedTypes, processJava))
         return true;

      Object derivedTD = td.getDerivedTypeDeclaration();
      if (derivedTD != extTD)
         if (derivedTD instanceof TypeDeclaration && ((TypeDeclaration)derivedTD).changedSinceLayer(sinceLayer, genLayer, false, visited, changedTypes, processJava))
            return true;

      if (bodyChangedSinceLayer(body, sinceLayer, genLayer, visited, changedTypes, processJava) || bodyChangedSinceLayer(hiddenBody, sinceLayer, genLayer, visited, changedTypes, processJava))
         return true;
      return false;
   }

   private boolean bodyChangedSinceLayer(SemanticNodeList<Statement> bodyList, Layer sinceLayer, Layer genLayer, IdentityHashSet<TypeDeclaration> visited, Set<String> changedTypes, boolean processJava) {
      if (bodyList == null)
         return false;
      for (Statement st:bodyList)
         if (st instanceof TypeDeclaration && ((TypeDeclaration) st).changedSinceLayer(sinceLayer, genLayer, true, visited, changedTypes, processJava))
            return true;
      return false;
   }

   public boolean needsEnclosingClass() {
      return true;
   }

   public AccessLevel getDefaultAccessLevel() {
      return null;
   }

   protected boolean transformExcluded(ILanguageModel.RuntimeType runtime) {
      LayeredSystem sys = getLayeredSystem();
      if (excludedStub != null) {
         if (sys.options.verbose || sys.options.verboseExec)
            sys.verbose("Excluded type: " + typeName + " replaced with stub for: " + getLayeredSystem().getProcessIdent());

         parentNode.replaceChild(this, excludedStub);

         excludedStub.transform(runtime);
      }
      else {
         if (sys.options.verbose || sys.options.verboseExec)
           sys.verbose("Excluded type: " + typeName + " for: " + getLayeredSystem().getProcessIdent());
         int myIx = -1;
         List parentList = null;
         if (parentNode instanceof List) {
            parentList = (List) parentNode;
            myIx = parentList.indexOf(this);
         }
         parentNode.removeChild(this);
         // By removing this type, our parent list will skip the next object so transforming that here
         if (myIx != -1 && myIx < parentList.size()) {
            Object newNode = parentList.get(myIx);
            if (newNode instanceof SemanticNode)
               ((SemanticNode) newNode).transform(runtime);
         }
      }
      return true;
   }

   public void markExcluded(boolean topLevel) {
      super.markExcluded(topLevel);

      // The first element which is excluded in a parent/child chain needs a stub from the element to stitch in the static content from the children rendered on the server
      if (element != null && topLevel) {
         excludedStub = element.getExcludedStub();
         if (excludedStub != null) {
            excludedStub.isExcludedStub = true;
            ParseUtil.initAndStartComponent(excludedStub);
         }
      }
   }

   void initPropagateConstructors() {
      if (constructorsInited)
         return;
      try {
         constructorsInited = true;

         initConstructorPropInfo();

         // TODO: do we need this constructor for dynamic types?  We have code when constructing the instance that deals with the propagated constructor through dynamic types.
         //  For some reason, this the generated constructor here messes up that logic when initializing a layer component (see the ticketmonster.core test) so for now skip generating
         //  this dummy constructor in that case. It's possible though that we will need this to resolve code references to this constructor.
         if (isDynamicType() || isDynamicNew())
            return;

         BodyTypeDeclaration modType = resolve(true);

         Object[] propagateConstructorArgs = modType.getPropagateConstructorArgs();
         if (propagateConstructorArgs != null) {
            int sz = propagateConstructorArgs.length;
            List<?> paramTypes = Arrays.asList(propagateConstructorArgs);
            Object constrObj = modType.declaresConstructor(paramTypes, null);
            String[] propNames = getPropagateParamNames(propagateConstructorArgs);
            if (constrObj == null) {
               Object extType = modType.getExtendsTypeDeclaration();
               if (extType == null) {
                  displayError("propagateConstructor on type with no extends type - no constructor to propagate for: ");
                  return;
               }
               Object extConstr = ModelUtil.definesConstructor(getLayeredSystem(), extType, paramTypes, null);
               if (extConstr == null) {
                  StringBuilder sb = new StringBuilder();
                  for (int i = 0; i < propagateConstructorArgs.length; i++) {
                     if (i != 0)
                        sb.append(", ");
                     sb.append(CTypeUtil.getClassName(ModelUtil.getTypeName(propagateConstructorArgs[i])));
                  }
                  displayError("propagateConstructor - no matching constructor for parameters: " + sb.toString() + " on type: " + extType + " for: ");
                  extConstr = ModelUtil.definesConstructor(getLayeredSystem(), extType, paramTypes, null);
                  return;
               }
               ConstructorDefinition constr = ConstructorDefinition.create(this,
                       propagateConstructorArgs,
                       propNames);
               constr.addModifier("public");
               constr.parentNode = this;
               constr.propagatedFrom = extConstr;
               int start = 0; // start = 1 if we add super
               SemanticNodeList<Expression> superArgs = new SemanticNodeList<Expression>();
               for (int i = 0; i < sz; i++)
                  superArgs.add(IdentifierExpression.create(propNames[i]));
               constr.addBodyStatementAt(0, IdentifierExpression.createMethodCall(superArgs, "super"));

               // Adding this to the hidden body so references resolve properly to this constructor
               addToHiddenBody(constr, false);
            }
         }
      }
      catch (RuntimeException exc) {
         constructorsInited = false;
         throw exc;
      }
   }

   public ParseRange getNodeErrorRange() {
      if (parseNode != null && typeName != null) {
         int startIx = parseNode.indexOf(typeName);
         if (startIx != -1) {
            startIx = startIx + parseNode.getStartIndex();
            return new ParseRange(startIx, startIx + typeName.length());
         }
      }
      return null;
   }

   public List<Object> getEnumConstants() {
      return null;
   }

}
