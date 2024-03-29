/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.classfile.CFClass;
import sc.classfile.CFMethod;
import sc.db.BaseTypeDescriptor;
import sc.db.DBUtil;
import sc.dyn.*;
import sc.lang.*;
import sc.lang.html.Element;
import sc.lang.js.JSRuntimeProcessor;
import sc.lang.sc.*;
import sc.lang.sql.DBAnnotationProcessor;
import sc.lang.sql.DBProvider;
import sc.lang.template.Template;
import sc.layer.*;
import sc.obj.*;
import sc.parser.ParseUtil;
import sc.sync.SyncManager;
import sc.sync.SyncPropOptions;
import sc.sync.SyncProperties;
import sc.type.*;
import sc.util.*;

import java.io.File;
import java.io.Serializable;
import java.util.*;

/**
 * The base type of TypeDeclaration and EnumConstant.  Because EnumConstants have the bulk of the functionality of a class, this class maintains most of the core functionality of a type.
 */
public abstract class BodyTypeDeclaration extends Statement implements ITypeDeclaration, INamedNode, IClassBodyStatement {
   public final static String INNER_STUB_SEPARATOR = "__";

   private final static TreeSet<String> componentMethodNames = new TreeSet<String>();

   static {
      componentMethodNames.addAll(Arrays.asList("preInit", "init", "start", "stop"));
   }

   @Constant
   public String typeName;
   public SemanticNodeList<Statement> body;

   /* Used by annotations/scopes that need to attach statements to the dynamic model which affect the runtime but are not saved with the file */
   public transient SemanticNodeList<Statement> hiddenBody;

   public transient Layer layer;      // Stores the layer this type was defined in.
   public transient BodyTypeDeclaration replacedByType;  // This type has been modified by a subsequent layer or reloaded - in either case, the replacedByType should be used for operations once it is set this is the current definition

   public transient BodyTypeDeclaration replacesType;  // This type replaced a previous type - the inverse to replacedByType

   /**
    * Names of changed methods from the previous types if this type has been updated at runtime
    */
   public transient TreeSet<String> changedMethods;

   /**
    * Cached of the members
    */
   public transient Map<String, List<Statement>> membersByName;

   /**
    * Cached of the methods
    */
   public transient TreeMap<String, List<Statement>> methodsByName;

   public transient boolean replaced = false;  // Set to true when another active type in the same layer has replaced this type

   public transient boolean removed = false;  // Set to true when the source for this type has been removed from the system.

   public transient boolean replacedInactive = false; // Set to true when an inactive type has been replaced by a newer version

   /**
    * For an excluded type, a framework can designate a 'stub type' to replace an excluded type for a given runtime.
    * In that case, this stores the excludedStub type.  It's included in the list of children and generated according to the
    * needs of the framework.  For SCHTML, we generate a node with the parentNode and id properties set by which is marked
    * as serverContent with no children so we stitch in the server content with the client content.
    */
   transient public TypeDeclaration excludedStub = null;
   transient public boolean isExcludedStub = false; // Set to true for the excludedStub type itself
   transient private boolean excludedInited = false;

   /**
    * Set to true for types which are modified dynamically.  Either because they have the dynamic keyword or because they're in or modified by a dynamic layer
    */
   public transient boolean dynamicType = false;

   /**
    * If we only set properties in a dynamic type which extends a compiled type, we mark the type as "dynamicNew" - no stub is created.
    * When constructing it, we treat it as a dynamic type which then creates an instance of the compiled class and sets properties
    */
   public transient boolean dynamicNew = false;

   /**
    * Set to true if this type is not to be made dynamic even if in a dynamic layer
    */
   public transient boolean compiledOnly = false;

   /**
    * List of properties not defined in this class which need to have bindability or get/setters generated for them by this class because of how this class is used.  It is a Tree so the order maintained is consistent even if the list is built up in a different order
    */
   public transient TreeMap<String, Boolean> propertiesToMakeBindable;

   /**
    * List of properties with @Bindable(manual=true) - we need to at least compile in some annotations for these properties so we don't spit out warnings when mixing the compiled code with the dynamic runtime.
    */
   public transient ArrayList<String> propertiesAlreadyBindable;

   /**
    * List of property names marked @Constant but which can't be annotated directly cause there's no generated getX, setX, or field.  These would primarily be put on external dyn types for the case when we annotate a compiled class to mark a property as constant.
    */
   //public transient ArrayList<String> constProps;

   public transient TreeMap<String, Object> dynInvokeMethods;

   public transient boolean isLayerType;

   public transient boolean typeInfoCompleted = false;

   private transient long lastAccessTime;

   /**
    * Stores the current (and old/previous) mapping from slot to field+obj defs managed in the dynamic model
    */
   public transient Object[] instFields, oldInstFields;
   public transient Object[] staticFields, oldStaticFields;
   /**
    * List of hidden interfaces, added automatically by the scope tag
    */
   public transient Object[] scopeInterfaces;

   /* Parallel array to staticFields, stores the initial static properties for this type */
   public transient Object[] staticValues, oldStaticValues;

   private transient IntCoalescedHashMap staticFieldMap = null;
   private transient IntCoalescedHashMap dynInstFieldMap = null;  // Name to field or object
   private transient BitSet dynTransientFields;

   /* Cached copy of all methods so we don't keep recomputing it for property access */
   private transient Object[] allMethods = null;

   /**
    * Set to true via CompilerSettings or when there's an explicit .class reference to force the generation of a dynamic stub for this class.
    * It's different than compiledOnly which forces the class to be a normal compiled class.
    * For needCompiledClass dynamic types, we generate the constructors that mirror the original class hardcoding the type
    */
   public transient boolean needsCompiledClass = false;
   /**
    * True if a method overrides a compiled method - forcing a stub in cases where one is not usually needed.  Not to be confused with needsDynType - used when reflection is diabled and we need to generate dynType info for a compiled class (i.e. GWT)
    */
   public transient boolean needsDynamicStub = false;

   /**
    * Set to true for a compiled object type which cannot be optimized away due to an external dependency, such as a class reference or instanceof operation
    */
   public transient boolean needsOwnClass = false;

   /**
    * For processing template types, we want to create a dynamic type even if the class is marked compiled
    */
   public transient boolean allowDynamic = false;

   /**
    * Cached the getFullTypeName property
    */
   public transient String fullTypeName;

   /**
    * When we extend a compiled inner class from a dynamic type, we need to generate inner stub types in the dynamic stub of the parent type.  this preserves the outer/inner
    * relationship in the dynamic type.  We cannot extend an inner type from a top level type.  java requires that the inner type be extended from
    * within the type hierarchy (for instance types).  But if we use the inner/outer relationship all of the time, it means regenerating a new outer class every time
    * we add a new inner object.  So we only use inner types in the stub when we extend a compiled outer/inner combo.  This flag gets set to true
    * on the inner class if a type which extends some compiled inner type.
    */
   public transient boolean needsDynInnerStub = false;
   public transient boolean stubCompiled = false;
   public transient boolean stubGenerated = false;
   /**
    * Do we access this object type at runtime, i.e. through a binding
    */
   public transient boolean needsDynAccess = false;

   /**
    * True when this object overrides an existing property defined by getX, setX methods.
    */
   public transient boolean objectSetProperty = false;

   /**
    * Does code require the default constructor to be dynamic
    */
   public transient boolean needsDynDefaultConstructor = false;

   public transient boolean extendsInvalid = false;     // Set when the extends class is not found or leads to a cycle
   public transient boolean extendsOverridden = false;  // Set to true when the extends type is overridden in a subsequent layer

   /**
    * Index of properties for this type, lazily created
    */
   private transient DynType propertyCache;

   private transient IDynChildManager dynChildManager;
   private transient IDynObjManager dynObjManager;

   /**
    * The list of inner types for this type only.  Because we may remove these during transformation, we need to grab a copy up front so the type system doesn't change after transform
    */
   protected transient TypeDeclaration[] innerObjs;

   /**
    * In case we need a ConstructorDefinition to stub in for the implicitly defined zero arg constructor
    */
   private transient ConstructorDefinition defaultConstructor;

   /**
    * When updating the type, we may have removed a previous compiled extends class from this type.  Keep track of that class so we can properly detect stale runtimes when someone tries to update this type again with a different compiled class
    */
   public transient Object prevCompiledExtends = null;

   private final static Object[] emptyObjectArray = new Object[0];

   protected transient MemberCache memberCache = null;

   /**
    * In the event we modify a compiled type which was an omitted object type, we need to freeze the compiled class name at the one which was actually compiled.   We could lazily cache the compiledClassName but maybe it will change during start/validate so we'd have to cache it the first time after we've compiled the system.  Instead, we cache it the first time we see that we're stale
    */
   public transient String staleClassName = null;

   protected transient boolean memberCacheEnabled = true;

   protected transient int version = 0;

   protected transient Boolean cachedNeedsSync = null;

   /**
    * If we are synchronizing this type, should we start synchronizing eagerly when it's instantiated or wait for a reference to it and create it on demand.
    */
   private transient boolean syncOnDemand = false;

   /**
    * If we are synchronizing this type, should we send initial values of properties over by default?
    */
   private transient boolean syncInitDefault = false;

   private transient boolean syncResetState = false;

   /**
    * For sync types which are immutable - i.e. no need for listeners or state to be maintained for them.
    */
   private transient boolean syncConstant = false;

   public transient Set<Object> dependentTypes = null;

   public transient ClientTypeDeclaration clientTypeDeclaration;

   /**
    * Contains the version of the type which we transformed.
    */
   public transient BodyTypeDeclaration transformedType;

   /**
    * Caches whether we have the @Constant annotation to avoid the getInheritedAnnotation call
    */
   public transient Boolean autoComponent = null;

   transient int anonIdsAllocated = 0;

   transient public ArrayList<SyncProperties> syncProperties = null;

   transient protected boolean syncPropertiesInited = false;
   transient protected boolean dbTypeDescriptorInited = false;

   transient public BaseTypeDescriptor dbTypeDescriptor;

   // Debug flag - keep track of whether this model has ever been stopped
   public transient boolean hasBeenStopped = false;

   transient public boolean temporaryType;

   /**
    * Set to true for types manipulated in the command line, so we know to make them live-dynamic types by default
    */

   transient public boolean liveDynType = false;

   transient ArrayList<MethodDefinition> componentMethods = null;

   static public List<Object> EMPTY_COMPILER_SETTINGS = Collections.emptyList();

   transient public List<Object> compilerSettingsList = null;

   transient Boolean inheritProperties = null;
   transient Boolean exportProperties = null;

   public List<Object> getCompilerSettingsList() {
      if (compilerSettingsList == null) {
         compilerSettingsList = getAllInheritedAnnotations("sc.obj.CompilerSettings");
         if (compilerSettingsList == null)
            compilerSettingsList = EMPTY_COMPILER_SETTINGS;
      }
      return compilerSettingsList;
   }

   public boolean getInheritProperties() {
      if (inheritProperties == null) {
         inheritProperties = (Boolean) ModelUtil.getAnnotationValue(this, "sc.obj.CompilerSettings", "inheritProperties");
         if (inheritProperties == null)
            inheritProperties = true;
      }
      return inheritProperties;
   }

   public boolean getExportProperties() {
      if (exportProperties == null) {
         // This one is not inherited
         exportProperties = (Boolean) ModelUtil.getAnnotationValue(this, "sc.obj.CompilerSettings", "exportProperties");
         if (exportProperties == null)
            exportProperties = true;
      }
      return exportProperties;
   }

   public Object[] getPropagateConstructorArgs() {
      Object[] propagateConstructorArgs = null;
      String pConstructor = (String) ModelUtil.getAnnotationValueFromList(getCompilerSettingsList(), "propagateConstructor");
      if (pConstructor != null && pConstructor.length() > 0) {
         String[] argTypeNames = StringUtil.split(pConstructor, ',');
         propagateConstructorArgs = new Object[argTypeNames.length];
         JavaModel m = getJavaModel();
         for (int i = 0; i < argTypeNames.length; i++) {
            propagateConstructorArgs[i] = m.findTypeDeclaration(argTypeNames[i], false);
            if (propagateConstructorArgs[i] == null)
               displayError("Bad value to CompilerSettings.propagateConstructor annotation " + pConstructor + " no type: " + argTypeNames[i]);
         }
      }
      return propagateConstructorArgs;
   }

   public Layer getLayer() {
      return layer;
   }

   public void setLayer(Layer lt) {
      layer = lt;
   }

   public void switchLayers(Layer lt) {
      setLayer(lt);
      if (body != null) {
         switchLayersInBody(body, lt);
      }
      if (hiddenBody != null) {
         switchLayersInBody(hiddenBody, lt);
      }
   }

   private void switchLayersInBody(SemanticNodeList<Statement> bodyList, Layer lt) {
      for (Statement st : bodyList)
         if (st instanceof BodyTypeDeclaration)
            ((BodyTypeDeclaration) st).switchLayers(lt);

   }


   public abstract DeclarationType getDeclarationType();

   public DeclarationType getDeclarationTypeNoInit() {
      return getDeclarationType();
   }

   @Constant
   public void setDeclarationType(DeclarationType dt) {
      throw new UnsupportedOperationException();
   }

   public String getTypeName() {
      return typeName;
   }

   public Object definesType(String name, TypeContext ctx) {
      return ModelUtil.getInnerType(this, name, ctx);
      /*
      if (res != null)
         return res;

      Object extendsType = getExtendsTypeDeclaration();
      if (extendsType != null) {
         if (extendsType instanceof TypeDeclaration) {
            if ((res = ((TypeDeclaration) extendsType).definesType(name)) != null)
               return res;
         }
         else {
            Class extendsClass = (Class) extendsType;
            Class resClass;
            if ((resClass = TypeUtil.getInnerClass(extendsClass, name)) != null)
               return resClass;
         }
      }
      return null;
      */
   }

   public Object findType(String name, Object refType, TypeContext ctx) {
      if (replacedByType != null) // We've been modified
         return replacedByType.findType(name, refType, ctx);

      Object res;
      if ((res = definesType(name, ctx)) != null) {
         return res;
      }

      JavaModel m = getJavaModel();

      // This name has been mapped to something else so don't return any types past this point.  Make sure to use
      // the refType here so we don't see private things we shouldn't and cancel a valid search
      // Skip the search if it's a complex name.
      if (name.indexOf('.') == -1 && definesMember(name, m == null || m.enableExtensions() ? MemberType.PropertyGetSet : MemberType.FieldEnumSet, refType, ctx) != null)
         return null;

      return super.findType(name, refType, ctx);
   }

   public Object getDerivedTypeDeclaration() {
      return null;
   }

   protected void updateBoundExtendsType(Object newType) {

   }

   protected void updateBoundExtendsType(Object newType, Object oldType) {
      throw new UnsupportedOperationException();
   }

   public String getDerivedTypeName() {
      return null;
   }

   public boolean hasModifier(String modifier) {
      // Modified types do not have all of the modifiers
      if (replacedByType != null && replaced)
         return replacedByType.hasModifier(modifier);

      return super.hasModifier(modifier);
   }

   /*
   public static void addBodyToMemberCache(List<Statement> body, MemberCache cache) {
      if (body != null) {
         int sz = body.size();
         for (int i = 0; i < sz; i++) {
            Statement s = body.get(i);
            // One type declaration should not see the variables of an inner type
            if (s instanceof ITypeDeclaration) {
               ITypeDeclaration it = (ITypeDeclaration) s;

               String memberName = it.getTypeName();

               if (s instanceof EnumConstant || (s instanceof ModifyDeclaration && ((ModifyDeclaration) s).isEnumConstant()))
                  cache.addToCache(memberName, s, MemberType.Enum, false, null);
               else {
                  cache.addToCache(memberName, s, MemberType.GetMethod, true, null);
                  if (ModelUtil.isObjectType(s))
                     cache.addToCache(memberName, s, MemberType.ObjectType, false, null);
               }
            }
            else {
               s.addToMemberCache(cache, null);
            }
         }
      }
   }
   */

   public static Object findMemberInBody(List<Statement> body, String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx) {
      Object v, res = null;
      if (body != null) {
         int sz = body.size();
         for (int i = 0; i < sz; i++) {
            Statement s = body.get(i);
            // One type declaration should not see the variables of an inner type
            if (s instanceof ITypeDeclaration) {
               ITypeDeclaration it = (ITypeDeclaration) s;

               // Check if the type has the name we are looking for first.  Both a performance thing and also prevents us from initializing
               // the type info for every single type in the body via isObjectType is isEnumConstant.
               //
               // This is a tricky thing because for modify definitions, we need to find the modified type before we can determine whether
               // it is a class, object, or enum.  The latter two are resolved differently.
               String statementTypeName = it.getTypeName();
               if (statementTypeName != null && statementTypeName.equals(name)) {
                  // But do let EnumConstants through
                  if (mtype.contains(MemberType.Enum) && (s instanceof EnumConstant || (s instanceof ModifyDeclaration && ((ModifyDeclaration) s).isEnumConstant()))) {
                     return s;
                  }
                  // The caller should not call "super.findMember" since this resolution would match an object instead that shadows some higher level
                  // variable.
                  boolean isObjectType = ModelUtil.isObjectType(s);
                  if (isObjectType) {
                     if (mtype.contains(MemberType.ObjectType))
                        return s;
                     if (mtype.contains(MemberType.GetMethod)) {
                        // TODOO ??? if (res != null) return res;
                        // It is true that 'res' here may point to a field but we do not want to return it here.  There's a subtle rule where if there's an object
                        // and a field in the same list, we need to resolve the object.  Otherwise, we may make the field bindable and create an extra getX method.
                        // It seems like if we are going to override a field with an object we might also handle that more explicitly - by having the object find the
                        // field and mark it as part of the object so we do not make it bindable.
                        // This ends up why we cannot put the hiddenBody into the membersByName index.
                        return STOP_SEARCHING_SENTINEL;
                     }
                  }
               }
               continue;
            }
            if ((v = s.definesMember(name, mtype, refType, ctx, false, false)) != null) {
               if (res == null)
                  res = v;
                  // A property get/set has precedence over fields
               else if (ModelUtil.isField(res))
                  res = v;
            }
         }
         if (res != null)
            return res;
      }
      return null;
   }

   public Object declaresMember(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx) {
      Object obj = declaresMemberInternal(name, mtype, refType, ctx);
      if (obj == STOP_SEARCHING_SENTINEL)
         return null;
      return obj;
   }

   public Object declaresMemberInternal(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx) {
      if (membersByName == null && initialized) {
         initMembersByName();
      }
      // We don't use the membersByName cache until we are initialized.  One problem that could be fixed is that a getX MethodDefinition's will not have it's propertyName set until it's been initialized
      List<Statement> sts = !initialized ? body : membersByName.get(name);
      Object obj = findMemberInBody(sts, name, mtype, refType, ctx);

      if (obj != null)
         return obj;

      // Need to check body first... otherwise we return objects in hiddenBody which we can't transform
      obj = findMemberInBody(hiddenBody, name, mtype, refType, ctx);
      if (obj != null)
         return obj;

      if (isTransformedType() && isAutoComponent() && !isTransformed()) {
         obj = ModelUtil.definesMember(ComponentImpl.class, name, mtype, refType, ctx, getLayeredSystem());
         if (obj != null)
            return obj;
      }
      return null;
   }

   private void initMethodsByName() {
      methodsByName = new TreeMap<String, List<Statement>>();
      addMethodsFromBody(body);
      // This causes false duplicates since some methods we add to hiddenBody are also in body and in general we want to search body before hiddenBody
      // And we don't need to add hiddenBody methods here because we search the hiddenBody in each lookup.
      //addMethodsFromBody(hiddenBody);
   }

   private void addMethodByName(String name, Statement st) {
      if (methodsByName == null) // The index has not been initialized for some reason or maybe was just cleared?
         return;
      List<Statement> sts = methodsByName.get(name);
      if (sts == null) {
         sts = new ArrayList<Statement>();
         methodsByName.put(name, sts);
      }
      else {
         for (Statement oldMeth : sts) {
            if (oldMeth == st)
               return;
            if (oldMeth instanceof AbstractMethodDefinition && ModelUtil.isMethod(st) && ModelUtil.sameMethods(oldMeth, st)) {
               if (!ModelUtil.anyUnresolvedParamTypes(oldMeth) && !ModelUtil.anyUnresolvedParamTypes(st)) {
                  boolean dummy = ModelUtil.sameMethods(oldMeth, st);
                  oldMeth.displayError("Duplicate method: ");
                  st.displayError("Duplicate method: ");
               }
            }
         }
      }
      sts.add(st);
   }

   private void addMethodsFromBody(SemanticNodeList<Statement> bodyList) {
      if (bodyList == null)
         return;
      for (Statement st : bodyList) {
         if (st instanceof AbstractMethodDefinition) {
            String name = ((AbstractMethodDefinition) st).name;
            if (name != null) {
               addMethodByName(name, st);
            }
         }
         else if (st instanceof ITypeDeclaration) {
            ITypeDeclaration td = (ITypeDeclaration) st;
            if (td.getDeclarationType() == DeclarationType.OBJECT) {
               addMethodByName("get" + CTypeUtil.capitalizePropertyName(td.getTypeName()), st);
            }
         }
      }
   }

   private void initMembersByName() {
      membersByName = new TreeMap<String, List<Statement>>();
      addMembersFromBody(body);
      // If we combine body and hidden body in the same list it behaves differently due to stop-searching-sentinel
      //addMembersFromBody(hiddenBody);
   }

   private void addMembersFromBody(SemanticNodeList<Statement> bodyList) {
      if (bodyList == null)
         return;
      for (Statement st : bodyList) {
         // When we are in the midst of initializing the type, we may be trying to find a member as part of the findType method to determine
         // but we don't actually return the member in this case... so no harm in ignoring this warning.
         //if (!st.isInitialized())
         //   System.out.println("*** Warning - statement not initialized being put into member cache"); // In particular we need propertyName to be set in the method to add it here which is right now only set during initialize
         // This logic models the logic in findMemberInBody - each inner type can be a member - enum constant, object, etc. depending on the type
         if (st instanceof ITypeDeclaration) {
            ITypeDeclaration it = (ITypeDeclaration) st;

            String typeName = it.getTypeName();
            if (typeName != null)
               st.addMemberByName(membersByName, typeName);
         }
         else
            st.addMembersByName(membersByName);
      }
   }

   public boolean conflictsWith(Statement other, String memberName) {
      if (other instanceof BodyTypeDeclaration) {
         return true;
      }
      return false;
   }

   public Object definesPreviousMember(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      Object extType = getDerivedTypeDeclaration();
      if (extType != null) {
         //if (extType instanceof BodyTypeDeclaration)
         //   extType = ((BodyTypeDeclaration) extType).resolve(true);
         return ModelUtil.definesMember(extType, name, mtype, refType, ctx, skipIfaces, isTransformed, getLayeredSystem());
      }
      return null;
   }

   public Object definesMember(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx) {
      return definesMember(name, mtype, refType, ctx, false, false);
   }

   private static boolean testMemberCache = false;

   public Object definesMember(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      Object res = definesMemberCached(name, mtype, refType, ctx, skipIfaces, isTransformed);
      if (res == STOP_SEARCHING_SENTINEL)
         res = null;
      return res;
   }

   public Object definesMemberCached(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      Object res = null;
      if (memberCache == null && memberCacheEnabled)
         memberCache = new MemberCache();

      Object cacheRes = null;
      boolean usedCache = false;
      // Can't start caching till we are fully defined - if things are still changing the cache becomes stale.
      if (!skipIfaces && memberCache != null && ctx == null && initialized) {
         usedCache = true;
         boolean cacheLoaded = false;
         MemberCacheEnt ent = memberCache.getCacheEnt(name, mtype, isTransformed);
         boolean entryValid = false;
         if (ent == null || ent.version != version) {
            PerfMon.start("definesMemberInternal");
            cacheRes = definesMemberInternal(name, mtype, null, null, false, isTransformed);
            PerfMon.end("definesMemberInternal");
            if (ent == null) {
               if (memberCache == null) {
                  //System.err.println("*** Member cache flushed in the midst of using it?");
                  initMemberCache();
               }
               memberCache.addToCache(name, cacheRes, mtype, cacheRes == null, version, isTransformed);
            }
            else {
               if (cacheRes == null)
                  ent.stopSearching = true;
               else {
                  ent.member = cacheRes;
                  ent.stopSearching = false;
               }
               ent.version = version;
            }
            cacheLoaded = true;
            entryValid = true;
         }
         else {
            entryValid = true;
         }

         // Now the cache entry is valid but need to filter it by
         if (refType != null || !cacheLoaded)
            cacheRes = memberCache.getCache(name, refType, mtype, isTransformed);
         if (!testMemberCache && entryValid)
            return cacheRes;
      }
      res = definesMemberInternal(name, mtype, refType, ctx, skipIfaces, isTransformed);

      if (usedCache && testMemberCache) {
         if (!DynUtil.equalObjects(cacheRes, res) && !processed) {
            if (cacheRes == STOP_SEARCHING_SENTINEL)
               cacheRes = null;
            if (!DynUtil.equalObjects(cacheRes, res) && !processed)
               System.err.println("*** testMemberCache enabled and member cache is out of sync!");
         }
      }
      return res;
   }

   protected void bodyChanged() {
      incrVersion();
      if (hasBeenStopped)
         incrSubTypeVersions();
      clearCachedMemberInfo();
   }

   protected void modelChanged() {
      bodyChanged();
      // If we have been reparsed, make sure to flush out the member cache for all inner types
      if (hasBeenStopped) {
         List<Object> innerTypes = getAllInnerTypes(null, true, false);
         if (innerTypes != null) {
            for (Object innerType : innerTypes) {
               if (innerType instanceof BodyTypeDeclaration)
                  ((BodyTypeDeclaration) innerType).modelChanged();
            }
         }
      }
   }

   public void clearCachedMemberInfo() {
      membersByName = null;
      methodsByName = null;
      memberCache = null;
      // Propagate this up the hierarchy since we transform the modified type and need to invalidate the member cache of any modified types.
      if (replacedByType != null) {
         if (replacedByType == this) {
            replacedByType = null;
            System.err.println("*** Found invalid replacedByType in clearCachedMemberInfo");
         }
         replacedByType.clearCachedMemberInfo();
      }
   }

   protected void incrVersion() {
      version++;

      // Propagate this up the hierarchy since we transform the modified type and need to invalidate the member cache of any modified types.
      if (replacedByType != null)
         replacedByType.incrVersion();
   }

   private int dbgSubTypeVersionCt = 0;

   protected void incrSubTypeVersions() {
      LayeredSystem sys = getLayeredSystem();

      dbgSubTypeVersionCt++;
      try {
         if (dbgSubTypeVersionCt > 50) {
            System.err.println("*** Recursive sub-type chain in incrSubTypeVersions after 50 levels - abort");
            return;
         }

         if (sys != null) {
            Layer layer = getLayer();
            if (layer != null && this instanceof TypeDeclaration) {
               // If there are any cached sub-types (passing cachedOnly = true), we want to invalidate their entries.
               Iterator<BodyTypeDeclaration> subTypes = sys.getSubTypesOfType((TypeDeclaration) this, layer, false, false, true, true);
               if (subTypes != null) {
                  while (subTypes.hasNext()) {
                     BodyTypeDeclaration subType = subTypes.next();
                     if (subType == this) {
                        System.err.println("*** Recursive sub-type definition!");
                        continue;
                     }
                     subType.incrVersion();
                     subType.incrSubTypeVersions();
                  }
               }
            }
         }
      }
      finally {
         dbgSubTypeVersionCt--;
      }
   }

   protected void initMemberCache() {
      memberCache = new MemberCache();
      //addToMemberCache(memberCache, null);
   }

   /*
   public void addToMemberCache(MemberCache cache, EnumSet<MemberType> filter) {
      if (body != null) {
         addBodyToMemberCache(body, cache);
      }
      if (hiddenBody != null) {
         addBodyToMemberCache(hiddenBody, cache);
      }
   }
   */

   protected BodyTypeDeclaration getModifyTypeForTransform() {
      return null;
   }

   public boolean isTransformedType() {
      JavaModel m = getJavaModel();
      return m != null && m.nonTransformedModel != null;
   }

   private boolean checkTransformed(Object refType, TypeContext ctx) {
      return (ctx != null && ctx.transformed) || (refType != null && ModelUtil.isTransformedType(refType));
   }

   /* This variant is propagated through the type chain.  It returns STOP_SEARCHING_SENTINEL when it encounters a name of a conflicting type which is not requested.  This is necessary so we don't return false positives and should be used when propagating this call through the type hierarchy. */
   public Object definesMemberInternal(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      Object res;

      if (isTransformed && transformedType != null) {
         res = transformedType.definesMemberInternal(name, mtype, refType, ctx, skipIfaces, isTransformed);
         if (res != null)
            return res;
      }

      res = declaresMemberInternal(name, mtype, refType, ctx);
      if (res != null)
         return res;

      Object extendsType = getDerivedTypeDeclaration();

      // When we need to do a find on the transformed graph, i.e. for a property assignment, we need to get the transformed type
      // TODO: there is redundancy here.  With isTransformed, we no longer need ctx.transformed and refType.isTransformedType == isTransformed
      if (checkTransformed(refType, ctx) || isTransformed) {
         BodyTypeDeclaration xformType = getModifyTypeForTransform();
         if (xformType != null)
            extendsType = xformType;
      }

      Object objDecl;
      if (extendsType != null) {
         res = ModelUtil.definesMember(extendsType, name, mtype, refType, ctx, skipIfaces, isTransformed, getLayeredSystem());
      }
      else {
         String extendsTypeName = getDerivedTypeName();
         JavaModel model;
         if (extendsTypeName != null && getDeclarationType() == DeclarationType.OBJECT && (model = getJavaModel()) != null &&
                 // Need addExternalReference for "findType" which uses this to lookup a ClassType.  Maybe need to propagate addExtReference through
                 // findType and definesMemberInternal?   This should only be true when the definesMemberInternal originates from a node within the
                 // current model.
                 (objDecl = model.resolveName(extendsTypeName, false, true, true)) != null)
            res = ModelUtil.definesMember(objDecl.getClass(), name, mtype, refType, ctx, skipIfaces, isTransformed, getLayeredSystem());
      }
      if (res != null)
         return res;

      /*
       * Note - we do this in getPreviousMember through the getDefinitionProcessor hook
      if (!transformed && !skipIfaces) {
         Object[] scopeTypes = getScopeInterfaces();
         if (scopeTypes != null) {
            // Check any interfaces appended on by annotations for for their own annotation.   This way, you can use
            // a meta layer to attach annotations onto a type which is automatically added via an annotation, e.g. GWT's EntryPoint
            // added by the GWTModule annotation.
            for (Object scopeType : scopeTypes) {
               if (scopeType != null && (res = ModelUtil.definesMember(scopeType, name, mtype, refType, ctx, skipIfaces, isTransformed)) != null) {
                  return res;
               }
            }
         }
      }
      */

      return super.definesMember(name, mtype, refType, ctx, skipIfaces, isTransformed);
   }

   public Object findMethod(String name, List<? extends Object> params, Object fromChild, Object refType, boolean staticOnly, Object inferredType) {
      Object v;

      // We've been modified by a subsequent definition so let it implement this operation for this type
      // When this is part of a transformed type, the replacedByType refers to the un-transformed type.  But we may be validating
      // code like a setX reference which requires the transformed model.
      // TODO: for transformed types, is this all we have to change to resolve setX methods which are only in the transformed type.
      if (replacedByType != null && !isTransformedType())
         return replacedByType.findMethod(name, params, fromChild, refType, staticOnly, inferredType);

      if ((v = definesMethod(name, params, null, refType, isTransformedType(), staticOnly, inferredType, null)) != null)
         return v;

      // If this is an inner type, we still need to check the parent
      return super.findMethod(name, params, this, refType, staticOnly, inferredType);
   }

   public Object definesMethod(String name, List<?> types, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs) {
      Object v;

      if (isTransformed && transformedType != null) {
         v = transformedType.definesMethod(name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs);
         if (v != null)
            return v;
      }

      v = declaresMethod(name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs, false); // false for 'includeModified' because we already traverse modified types in definesMethod
      if (v != null)
         return v;

      if ((v = extendsDefinesMethod(name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs)) != null)
         return v;

      return super.definesMethod(name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs);
   }

   public static Object findMethodInBody(List<Statement> body, String name, List<? extends Object> types, ITypeParamContext ctx, Object refType, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs) {
      Object v = null;
      PerfMon.start("findMethod");
      try {
         Object[] prevTypesArray = null;
         Object[] nextTypesArray;
         if (body != null) {
            Object matchedObject = null;
            for (Statement s : body) {
               if (s instanceof ITypeDeclaration) {
                  // the getX() method should match an object definition.  Otherwise, during transform, we can't match up a getX to an object def and find no type
                  ITypeDeclaration itd = (ITypeDeclaration) s;
                  if ((types == null || types.size() == 0) && name.startsWith("get") && itd.getDeclarationType() == DeclarationType.OBJECT) {
                     String propName = CTypeUtil.decapitalizePropertyName(name.substring(3));
                     if (propName.equals(itd.getTypeName()))
                        matchedObject = s;
                  }
                  // One type declaration should not see the variables of an inner type
                  continue;
               }
               Object newMeth;
               if ((newMeth = s.definesMethod(name, types, ctx, refType, false, staticOnly, inferredType, methodTypeArgs)) != null) {
                  if (v == null) {
                     v = newMeth;
                     prevTypesArray = ModelUtil.parametersToTypeArray(types, ctx);
                  }
                  else {
                     nextTypesArray = ModelUtil.parametersToTypeArray(types, ctx);
                     v = ModelUtil.pickMoreSpecificMethod(v, newMeth, prevTypesArray, nextTypesArray, types);
                     if (v == newMeth)
                        prevTypesArray = nextTypesArray;
                  }
               }
            }
            // If this is for a getX and we have not transformed the object yet, still return it
            if (v == null && matchedObject != null)
               return matchedObject;
         }
      }
      finally {
         PerfMon.end("findMethod");
      }
      return v;
   }

   /**
    * Just returns methods declared in this specific type
    */
   public Object declaresMethod(String name, List<? extends Object> types, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs, boolean includeModified) {
      if (methodsByName == null) {
         initMethodsByName();
      }
      List<Statement> sts = methodsByName.get(name);
      Object obj = findMethodInBody(sts, name, types, ctx, refType, staticOnly, inferredType, methodTypeArgs);
      if (obj != null)
         return obj;

      // Interfaces will put the complete version of the method in the hidden body so this needs to be first
      Object v = findMethodInBody(hiddenBody, name, types, ctx, refType, staticOnly, inferredType, methodTypeArgs);
      if (v != null)
         return v;

      if ((isTransformed || isTransformedType()) && isAutoComponent() && !isDynamicType() && !isTransformed() && componentMethodNames.contains(name)) {
         return addOrGetInitMethod(name, "public");
         // Note: this returns a compiled method even from the source type.  Use declaresMethodDef if you want to exclude
         // those compiled definitions.
         /*
         obj = ModelUtil.definesMethod(ComponentImpl.class, name, types, ctx, refType, true, staticOnly, inferredType, methodTypeArgs, getLayeredSystem());
         if (obj != null)
            return obj;
         */
      }

      return null;
   }

   // Returns the MethodDefinition, omitting the component methods
   AbstractMethodDefinition declaresMethodDef(String name, List<? extends Object> types) {
      Object res = declaresMethod(name, types, null, null, false, false, null, null, false);
      // Skip the component methods here since this is used to find the native method
      if (res != null && componentMethods != null && componentMethods.contains(res))
         return null;
      if (res instanceof ParamTypedMethod)
         res = ((ParamTypedMethod) res).method;
      if (res instanceof AbstractMethodDefinition)
         return (AbstractMethodDefinition) res;
      return null;
   }


   public Object definesConstructor(List<?> types, ITypeParamContext ctx, boolean isTransformed) {
      Object v;
      if (isTransformed && transformedType != null) {
         v = transformedType.definesConstructor(types, ctx, isTransformed);
         if (v != null)
            return v;
      }

      v = declaresConstructor(types, ctx);
      if (v != null)
         return v;

      if (!isEnumConstant()) {
         Object td = getDerivedTypeDeclaration();

         if (td != null) {
            if ((v = ModelUtil.definesConstructor(getLayeredSystem(), td, types, ctx)) != null)
               return v;
         }
      }

      return super.definesConstructor(types, ctx, isTransformed);
   }

   public Object[] getConstructors(Object refType, boolean includeHidden) {
      ArrayList<Object> res = addConstructors(null, body, refType);
      if (includeHidden) {
         if (!started)
            ModelUtil.ensureStarted(this, false);
         res = addConstructors(res, hiddenBody, refType);
      }
      if (res != null)
         return res.toArray();
      return null;
   }

   private ArrayList<Object> addConstructors(ArrayList<Object> res, SemanticNodeList<Statement> bodyList, Object refType) {
      if (bodyList != null) {
         for (Statement s : bodyList) {
            if (s instanceof ConstructorDefinition && (refType == null || ModelUtil.checkAccess(refType, s))) {
               if (res == null)
                  res = new ArrayList<Object>();
               res.add(s);
            }
         }
      }
      return res;
   }

   Object getPropagatedConstructor() {
      return ModelUtil.getPropagatedConstructor(getLayeredSystem(), this, this, getLayer());
   }

   public Object getConstructorFromSignature(String sig, boolean includeHidden) {
      Object[] cstrs = getConstructors(null, includeHidden);
      if (cstrs == null)
         return null;
      for (int i = 0; i < cstrs.length; i++) {
         Object constr = cstrs[i];
         if (StringUtil.equalStrings(((ConstructorDefinition) constr).getTypeSignature(), sig))
            return constr;
      }
      return null;
   }

   public Object declaresConstructor(List<?> types, ITypeParamContext ctx) {
      Object res = declaresConstructorBody(body, types, ctx);
      if (res != null)
         return res;
      return declaresConstructorBody(hiddenBody, types, ctx);
   }

   private static Object declaresConstructorBody(SemanticNodeList<Statement> theBody, List<?> types, ITypeParamContext ctx) {
      Object res = null, newMeth;
      if (theBody != null) {
         Object[] prevTypesArray = null;
         Object[] nextTypesArray;
         for (Statement s : theBody) {
            // Prevents us walking own the type hierarchy
            if (s instanceof TypeDeclaration)
               continue;
            if ((newMeth = s.definesConstructor(types, ctx, false)) != null) {
               if (res == null) {
                  res = newMeth;
                  prevTypesArray = ModelUtil.parametersToTypeArray(types, ctx);
               }
               else {
                  nextTypesArray = ModelUtil.parametersToTypeArray(types, ctx);
                  res = ModelUtil.pickMoreSpecificMethod(res, newMeth, prevTypesArray, nextTypesArray, types);
                  if (res == newMeth)
                     prevTypesArray = nextTypesArray;
               }
            }
         }
      }
      return res;
   }

   public Object extendsDefinesMethod(String name, List<?> parameters, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs) {
      Object baseType = getDerivedTypeDeclaration();

      if (baseType != null) {
         // If necessary map the type variables in the base-types' declaration based on the type params in the context
         baseType = ParamTypeDeclaration.convertBaseTypeContext(ctx, baseType);

         return ModelUtil.definesMethod(baseType, name, parameters, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs, getLayeredSystem());
      }

      return null;
   }


   public Object getInheritedAnnotation(String annotationName) {
      return getInheritedAnnotation(annotationName, false, getLayer(), isLayerType || isLayerComponent());
   }

   public List<Object> getAllInheritedAnnotations(String annotationName) {
      return getAllInheritedAnnotations(annotationName, false, getLayer(), isLayerType || isLayerComponent());
   }

   // Either a compiled java annotation or a parsed Annotation
   public Object getInheritedAnnotation(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      Object annot = getAnnotation(annotationName, true);
      if (annot != null)
         return annot;

      Object superType = getDerivedTypeDeclaration();
      if (superType == this) {
         System.err.println("*** Derived type loop!");
         return null;
      }
      JavaModel model = getJavaModel();
      if (model == null) {
         // This happens when copying the transformed model if we happen to start a component before the parent model is set
         //System.err.println("*** No model for getInheritedAnnotation");
         return null;
      }

      Object annotObj = (!skipCompiled && superType != null) || superType instanceof BodyTypeDeclaration ? ModelUtil.getInheritedAnnotation(model.layeredSystem, superType, annotationName, skipCompiled, refLayer, layerResolve) : null;
      if (annotObj != null)
         return annotObj;

      Object[] scopeTypes = getScopeInterfaces();
      // Check any interfaces appended on by annotations for for their own annotation.   This way, you can use
      // a meta layer to attach annotations onto a type which is automatically added via an annotation, e.g. GWT's EntryPoint
      // added by the GWTModule annotation.
      for (Object scopeType : scopeTypes) {
         if ((annotObj = ModelUtil.getInheritedAnnotation(getJavaModel().layeredSystem, scopeType, annotationName, false, refLayer, layerResolve)) != null)
            return annotObj;
      }
      return null;
   }

   public ArrayList<Object> getAllInheritedAnnotations(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      Object annot = getAnnotation(annotationName, true);
      ArrayList<Object> res = null;
      if (annot != null) {
         res = new ArrayList<Object>();
         res.add(annot);
      }

      Object superType = getDerivedTypeDeclaration();
      if (superType != null) {
         if (superType == this) {
            System.err.println("*** Derived type loop!");
            return res;
         }
         JavaModel model = getJavaModel();
         if (model == null) {
            //System.err.println("*** No model for getInheritedAnnotation");
            return res;
         }

         ArrayList<Object> superRes = !skipCompiled || superType instanceof BodyTypeDeclaration ? ModelUtil.getAllInheritedAnnotations(model.layeredSystem, superType, annotationName, skipCompiled, refLayer, layerResolve) : null;
         if (superRes != null) {
            res = ModelUtil.appendLists(res, superRes);
         }
      }

      Object[] scopeTypes = getScopeInterfaces();
      ArrayList<Object> scopeRes;
      // Check any interfaces appended on by annotations for for their own annotation.   This way, you can use
      // a meta layer to attach annotations onto a type which is automatically added via an annotation, e.g. GWT's EntryPoint
      // added by the GWTModule annotation.
      for (Object scopeType : scopeTypes) {
         if ((scopeRes = ModelUtil.getAllInheritedAnnotations(getJavaModel().layeredSystem, scopeType, annotationName, false, refLayer, layerResolve)) != null) {
            res = ModelUtil.appendLists(res, scopeRes);
         }
      }
      return res;
   }

   // Either a compiled java annotation or a parsed Annotation
   public String getInheritedScopeName() {
      String scopeName = getScopeName();
      if (scopeName != null)
         return scopeName;

      Object superType = getDerivedTypeDeclaration();
      scopeName = ModelUtil.getInheritedScopeName(getLayeredSystem(), superType, getLayer());
      if (scopeName != null)
         return scopeName;

      return null;
   }

   ArrayList<IDefinitionProcessor> getInheritedDefinitionProcessors() {
      LayeredSystem sys = getLayeredSystem();

      return sys.getInheritedDefinitionProcessors(this);
   }

   ArrayList<IDefinitionProcessor> getAllDefinitionProcessors(boolean doDefineTypes) {
      ArrayList<IDefinitionProcessor> res;

      res = getInheritedDefinitionProcessors();

      IDefinitionProcessor[] other = getDefinitionProcessors();
      if (other != null) {
         if (res == null)
            res = new ArrayList<IDefinitionProcessor>();
         for (IDefinitionProcessor o : other) {
            if (!res.contains(o))
               res.add(o);
         }
      }
      boolean hasSync = false;
      boolean hasScope = false;
      boolean hasDB = false;
      if (res != null) {
         for (IDefinitionProcessor proc : res) {
            if (proc == SyncAnnotationProcessor.getSyncAnnotationProcessor())
               hasSync = true;
            if (proc instanceof DBAnnotationProcessor)
               hasDB = true;
            if (proc instanceof BasicScopeProcessor)
               hasScope = true;
         }
      }
      if (!hasScope) {
         IDefinitionProcessor scopeProc = getScopeProcessor();
         if (scopeProc != null) {
            if (res == null)
               res = new ArrayList<IDefinitionProcessor>();
            res.add(scopeProc);
         }
      }
      // TODO: The isDefaultSync call here will call initSyncProperties. But that needs the merged view of the type which is
      // not available until 'validate'.  Right now, the sync template does not use the definesTypeMixin so no need to make the call on this pass.
      if (!hasSync && !doDefineTypes && isDefaultSync()) {
         if (res == null)
            res = new ArrayList<IDefinitionProcessor>();

         res.add(SyncAnnotationProcessor.getSyncAnnotationProcessor());
      }
      //if (!hasDB && getDBTypeDescriptor() != null) {
      //   System.out.println("***");
         //if (res == null)
         //   res = new ArrayList<IDefinitionProcessor>();

         //res.add(DBAnnotationProcessor.getDBAnnotationProcessor());
      //}
      return res;
   }

   public IScopeProcessor getScopeProcessor() {
      if (replacedByType != null)
         return replacedByType.getScopeProcessor();
      if (getLayer() == null)
         return null;
      return super.getScopeProcessor();
   }

   public Object[] getScopeInterfaces() {
      if (scopeInterfaces == null) {
         ArrayList<Object> scopeTypes = null;
         // Check any interfaces appended on by annotations for for their own annotation.   This way, you can use
         // a meta layer to attach annotations onto a type which is automatically added via an annotation, e.g. GWT's EntryPoint
         // added by the GWTModule annotation.
         IDefinitionProcessor[] defProcs = getDefinitionProcessors();
         if (defProcs != null) {
            for (IDefinitionProcessor proc : defProcs) {
               String[] scopeInterfaces = proc.getAppendInterfaces();
               if (scopeInterfaces != null) {
                  for (int si = 0; si < scopeInterfaces.length; si++) {
                     Object scopeType = ModelUtil.getTypeFromTypeOrParamName(this, scopeInterfaces[si]);
                     if (scopeType != null) {
                        if (scopeTypes == null)
                           scopeTypes = new ArrayList<Object>();
                        scopeTypes.add(scopeType);
                     }
                  }
               }
            }
         }
         if (scopeTypes != null)
            scopeInterfaces = scopeTypes.toArray();
         else
            scopeInterfaces = new Object[0];
      }
      return scopeInterfaces;
   }

   public JavaType[] getScopeInterfaceJavaTypes() {
      // For performance, since we cache these and they will map 1-1 with the JavaTypes
      Object[] scopeIface = getScopeInterfaces();
      if (scopeIface.length == 0)
         return null;

      ArrayList<JavaType> scopeTypes = null;
      IDefinitionProcessor[] defProcs = getDefinitionProcessors();
      if (defProcs != null) {
         for (IDefinitionProcessor proc : defProcs) {
            String[] scopeInterfaces = proc.getAppendInterfaces();
            if (scopeInterfaces != null) {
               for (int si = 0; si < scopeInterfaces.length; si++) {
                  JavaType scopeType = ModelUtil.getJavaTypeFromTypeOrParamName(this, scopeInterfaces[si]);
                  if (scopeTypes == null)
                     scopeTypes = new ArrayList<JavaType>();
                  scopeTypes.add(scopeType);
               }
            }
         }
      }
      return scopeTypes == null ? null : scopeTypes.toArray(new JavaType[scopeTypes.size()]);
   }

   public void addModifier(Object modifier) {
      super.addModifier(modifier);

      // This annotation won't affect the scopes and is added during the transform process so don't bother clearing the scope interfaces.
      if (modifier instanceof Annotation && ((Annotation) modifier).typeName.equals("sc.obj.TypeSettings"))
         return;

      // Invalidate this cache since it's based on the modifiers
      scopeInterfaces = null;
   }

   public void initBody() {
      bodyChanged(); // Increment the version number each time we change the body so code will refresh caches
      if (body == null) {
         // This order reduces generation overhead a little...
         SemanticNodeList<Statement> newStatements = new SemanticNodeList<Statement>(1);
         setProperty("body", newStatements);
      }
   }

   public void initHiddenBody(boolean skipVersion) {
      if (!skipVersion)
         incrVersion(); // Increment the version number each time we change the body so code will refresh caches
      if (hiddenBody == null) {
         // This order reduces generation overhead a little...
         SemanticNodeList<Statement> newStatements = new SemanticNodeList<Statement>(1);
         setProperty("hiddenBody", newStatements, false, true);
      }
   }

   public boolean isSemanticChildValue(ISemanticNode child) {
      if (child == hiddenBody)
         return false;
      return super.isSemanticChildValue(child);
   }

   public void addToHiddenBody(Statement s, boolean skipVersion) {
      initHiddenBody(skipVersion);
      // Don't try to re-generate the language representation for anything in the hidden body.
      s.parseNode = null;
      JavaModel m = getJavaModel();

      boolean oldTE = false;
      // If we are getting syntax errors due to a missing type or whatever, we'll see them repeated here
      // but with no source location it is wrong.
      if (m != null) {
         oldTE = m.disableTypeErrors;
         m.disableTypeErrors = true;
      }
      hiddenBody.add(s);
      if (m != null)
         m.disableTypeErrors = oldTE;
      if (isInitialized())
         ParseUtil.initComponent(s);
      if (isStarted())
         ParseUtil.startComponent(s);
      if (isValidated())
         ParseUtil.validateComponent(s);
      if (isProcessed())
         ParseUtil.processComponent(s);
   }

   public boolean isGeneratedType() {
      return false;
   }

   public Object getGeneratedFromType() {
      return null;
   }

   public void checkForStaleAdd() {
      if (isTransformedType())
         return;
      Layer l = getLayer();
      if (l != null && l.compiled && !isDynamicNew() && staleClassName == null && !isGeneratedType()) {
         LayeredSystem sys = l.layeredSystem;
         sys.setStaleCompiledModel(true, "Added statement to compiled type: ", typeName);
         staleClassName = getCompiledClassName();

         TypeDeclaration enclType = getEnclosingType();
         if (enclType != null) // For the enclosing type, if we've added the first statement to an inner modify type, it changes needsOwnClass for the parent type to return 'true' so we need to snag the scaleClassName there too
            enclType.checkForStaleAdd();
      }
   }

   public void addSubTypeDeclaration(BodyTypeDeclaration subType) {
      addBodyStatement(subType);
      /* TODO: why were we doing this?  Need to wait till updateType, otherwise, we may be adding types that
         already exist and at the wrong type - while they are being created in Template.convertToObject for instance
      LayeredSystem sys = getLayeredSystem();
      if (sys != null)
         sys.notifyInnerTypeAdded(subType);
      */
   }

   public void addBodyStatementIndent(Statement s) {
      initBody();
      TransformUtil.appendIndentIfNecessary(body);
      checkForStaleAdd(s);
      body.add(s);
      if (isInitialized() && !s.isInitialized())
         s.init();
   }

   public void checkForStaleAdd(Statement s) {
      if (!(s instanceof ModifyDeclaration) || !((ModifyDeclaration) s).isEmpty()) // An empty modify will not affect the runtime class and is common just to navigate for the command line interface
         checkForStaleAdd();
   }

   public void addBodyStatement(Statement s) {
      initBody();
      checkForStaleAdd(s);
      body.add(s);
      if (isInitialized() && !s.isInitialized())
         s.init();
   }

   public void addBodyStatementAt(int ix, Statement s) {
      initBody();
      checkForStaleAdd(s);
      if (ix == body.size())
         body.add(s);
      else
         body.add(ix, s);
      if (isInitialized() && !s.isInitialized())
         s.init();
   }

   public void addBodyStatementAtIndent(int ix, Statement s) {
      initBody();
      TransformUtil.appendIndentIfNecessary(body, ix);
      checkForStaleAdd(s);
      body.add(ix, s);
      if (isInitialized() && !s.isInitialized())
         s.init();
   }

   public void addBodyStatementsAt(int ix, List<Statement> s) {
      initBody();
      TransformUtil.appendIndentIfNecessary(body, ix);
      checkForStaleAdd();
      body.addAll(ix, s);
   }

   public Object getInnerType(String name, TypeContext ctx) {
      return getInnerType(name, ctx, true, false, false, false);
   }

   // TODO: For debugging purposes.  Remove at some point!  While editing some inner type in the IDE we hit an infinite loop here
   // so this is for diagnosing that problem.
   final static private int MAX_TYPE_NEST_COUNT = 200;

   public Object getInnerType(String name, TypeContext ctx, boolean checkBaseType, boolean redirected, boolean srcOnly, boolean includeEnums) {
      int ix;
      boolean origSameType = ctx != null && ctx.sameType;
      Object type = this;
      do {
         Object nextType;
         ix = name.indexOf(".");
         String nextTypeName;
         if (ix != -1) {
            nextTypeName = name.substring(0, ix);
            name = name.substring(ix + 1);
            if (ctx != null)
               ctx.sameType = false;
         }
         else {
            nextTypeName = name;
            if (ctx != null && origSameType) {
               ctx.sameType = !redirected;
            }
         }
         if (ctx != null && ctx.nestCount++ > MAX_TYPE_NEST_COUNT) {
            System.err.println("*** Maximum nested type encountered: " + typeName);
            return null;
         }

         if (type instanceof BodyTypeDeclaration)
            nextType = ((BodyTypeDeclaration) type).getSimpleInnerType(nextTypeName, ctx, checkBaseType, redirected, srcOnly, includeEnums);
         else
            nextType = ModelUtil.getInnerType(type, name, ctx);

         if (ctx != null)
            ctx.nestCount--;

         if (nextType == null)
            return null;

         // To prevent infinite loops, we set redirected when going through the extends chain.  Clear it for the
         // second and subsequent levels - they might need to go back to an overridden type.
         redirected = !ModelUtil.sameTypes(type, ModelUtil.getEnclosingType(nextType));
         type = nextType;
      } while (ix != -1);

      return type;
   }

   public Object getSimpleInnerType(String name, TypeContext ctx, boolean checkBaseType, boolean redirected, boolean srcOnly, boolean includeEnums) {
      /*
      IDefinitionProcessor[] procs = getDefinitionProcessors();
      if (procs != null) {
         Object ct;
         for (IDefinitionProcessor proc:procs)
            if ((ct = proc.getInnerType(this, name)) != null)
               return ct;
      }
      */

      boolean skipBody = false;
      if (ctx != null && ctx.fromLayer != null) {
         Layer typeLayer = getLayer();
         if (typeLayer != null) {
            int fromPos = ctx.fromLayer.getLayerPosition();
            int typePos = typeLayer.getLayerPosition();
            // When restricting an inner type search to a particular layer (i.e. to find a modified type),
            // as long as we are in the same type we don't consider the current layer.  as soon as we traverse
            // to an extended type, we need to consider the current layer.  We may be modifying a sub-type of
            // some extended type defined in the same layer.
            if (!ctx.sameType)
               fromPos++;
            skipBody = typePos >= fromPos;

            // The old logic here was to do the above - include the current layer.  But for example if you have an extends
            // class defined in a subsequent layer (e.g. servlet/schtml/HtmlPage) which defines a type 'body' that is used
            // in a modify type defined in the current layer, you need to be able to see that body from that template.  So now
            // we are always including the most specific type when we cross types.
            //
            // TODO: Leaving this here cause there may be a reason we did not do that in the first place.
            if (skipBody && !ctx.sameType)
               skipBody = false;
         }
         // If we're just modifying the type without crossing to an extended type, it still is the same type
         if (!(this instanceof ModifyDeclaration) || ((ModifyDeclaration) this).modifyInherited)
            ctx.sameType = false;
      }

      boolean isMethod = name.startsWith(AbstractMethodDefinition.METHOD_TYPE_PREFIX);
      for (int i = 0; i < 2; i++) {
         SemanticNodeList<Statement> theBody = i == 0 ? body : hiddenBody;
         if (theBody != null && !skipBody)
            for (Statement s : theBody) {
               if (s instanceof TypeDeclaration) {
                  TypeDeclaration st = (TypeDeclaration) s;
                  if (st.typeName != null && st.typeName.equals(name)) {
                     return s;
                  }
               }
               else if (includeEnums && s instanceof EnumConstant) {
                  EnumConstant ec = (EnumConstant) s;
                  if (StringUtil.equalStrings(ec.typeName, name))
                     return ec;
               }
               else if (isMethod && s instanceof AbstractMethodDefinition) {
                  Object res = s.definesType(name, ctx);
                  if (res != null)
                     return res;
               }
               // Check for transformed inner types - make sure to exclude the outer type's name.
               /*
               String pn;
               if (s instanceof MethodDefinition && (pn = ((MethodDefinition) s).propertyName) != null && pn.equals(name) &&
                   ModelUtil.isObjectType(s) && !pn.equals(typeName))
                  return s;
               */
            }
      }

      if (checkBaseType) {
         Object extendsType = getDerivedTypeDeclaration();
         // The redirected flag is set to true when we redirect to the same type.  It disables use of the "resolve" when we go to the extends type so when modify inherited is true, we are not redirecting.
         if (redirected && this instanceof ModifyDeclaration && ((ModifyDeclaration) this).modifyInherited)
            redirected = false;
         return getSimpleInnerTypeFromExtends(extendsType, name, ctx, redirected, srcOnly, includeEnums);
      }

      return null;
   }

   static int nestCount = 0;
   static Object nestCountLock = new Object();

   protected Object getSimpleInnerTypeFromExtends(Object extendsType, String name, TypeContext ctx, boolean redirected, boolean srcOnly, boolean includeEnums) {
      Object res;
      synchronized (nestCountLock) {
         if (nestCount > 100) {
            System.out.println("*** simple extends loop on type: " + typeName + " for: " + name);
            return null;
         }
         nestCount++;
      }
      try {

      if (extendsType != null) {
         if (extendsType instanceof TypeDeclaration) {
            BodyTypeDeclaration ext = (TypeDeclaration) extendsType;
            if (!redirected && ext.replacedByType != null)
               ext = ext.resolve(true);
            if ((res = ext.getInnerType(name, ctx, true, true, srcOnly, includeEnums)) != null)
               if (ctx != null)
                  ctx.add(ext, this);
            return res;
         }
         else {
            Layer layer = getLayer();

            // If we require a src type and we've bound to a compiled type, need to check if we can resolve the src
            // for this type.  We may need it to modify this type downstream.  Annotation layers and modify declarations
            // should not do this update.
            if (srcOnly && layer != null && !layer.annotationLayer && !isLayerType) {
               Object srcType = ModelUtil.resolveSrcTypeDeclaration(getLayeredSystem(), extendsType, false, true, getLayer());
               if (srcType instanceof BodyTypeDeclaration) {
                  if (srcType != extendsType) {
                     updateBoundExtendsType(srcType, extendsType);
                     return getSimpleInnerTypeFromExtends(srcType, name, ctx, redirected, true, includeEnums);
                  }
                  else {
                     System.err.println("*** Extends loop!");
                     return null;
                  }
               }
            }
            return ModelUtil.getInnerType(extendsType, name, ctx);
         }
      }
      }
      finally {
         synchronized (nestCountLock) {
            nestCount--;
         }
      }
      return null;
   }

   public Object findMember(String name, EnumSet<MemberType> mtype, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      Object v;

      if (ctx != null) {
         BodyTypeDeclaration subType = ctx.getSubType(this);
         if (subType != null)
            return subType.findMember(name, mtype, fromChild, refType, ctx, skipIfaces);
      }

      // We've been modified by a subsequent definition so let it implement this operation for this type
      // Don't do this for transformed types since they merge modified types into one and need to be able to resolve themselves in Java before converting to JS.
      if (replacedByType != null && !isTransformedType())
         return replacedByType.findMember(name, mtype, fromChild, refType, ctx, skipIfaces);

      if ((v = definesMemberCached(name, mtype, refType, ctx, skipIfaces, isTransformedType())) != null) {
         if (v == STOP_SEARCHING_SENTINEL)
            v = null;
         else
            return v;
      }

      // Unless this class is inside of a method, Fields/methods are inherited from our containing class, variables are not
      if (getEnclosingMethod() == null) {
         if (!mtype.contains(MemberType.Variable))
            v = super.findMember(name, mtype, this, refType, ctx, skipIfaces);
         else if (mtype.size() != 1) {
            EnumSet<MemberType> prop = EnumSet.copyOf(mtype);
            prop.remove(MemberType.Variable);
            v = super.findMember(name, prop, this, refType, ctx, skipIfaces);
         }
      }
      // Are we a type defined inside of a method?  If so, final variables in the enclosing method are visible.
      AbstractMethodDefinition def = getEnclosingMethod();
      if (def != null)
         return super.findMember(name, mtype, this, refType, ctx, skipIfaces);
      // else - variable and not in an anonymous inner classes only just return
      return v;
   }

   public Object findMemberOwner(String name, EnumSet<MemberType> mtype) {

      if (definesMember(name, mtype, null, null) != null)
         return this;

      // Fields/methods are inherited from our containing class, variables are not
      Object o = null;
      if (!mtype.contains(MemberType.Variable))
         o = super.findMemberOwner(name, mtype);
      else if (mtype.size() != 1) {
         EnumSet<MemberType> prop = EnumSet.copyOf(mtype);
         prop.remove(MemberType.Variable);
         o = super.findMemberOwner(name, prop);
      }
      return o;
   }

   /**
    * For an object definition where we are optimizing out the class, we need to skip to get the actual runtime
    * class.  Then, convert that to a type name, then see if there is a class available.
    * <p>
    * For dynamic types, the runtime class is DynObject if there's no extend class.  If we extend another
    * dynamic type, that classes runtime class is the one we use.  If our extends type is not dynamic,
    * this particular type needs to become a dynamic stuff so we use this class.
    */
   public Class getCompiledClass() {
      return getCompiledClass(true);
   }

   public boolean canInstance() {
      return isDynamicType() || getCompiledClass() != null;
   }

   public Class getCompiledClass(boolean init) {
      if (init && !isStarted()) {
         if (replaced && replacedByType != null) { // TODO: should there be a warning here to help find and fix types which were not updated properly during the updateType operation?
            return replacedByType.getCompiledClass(init);
         }
         ParseUtil.realInitAndStartComponent(this);
      }
      String typeName = getCompiledClassName();
      // Inner classes, or those defined in methods may not have a type name and so no way to look them up
      // externally.
      if (typeName == null)
         return null;
      if (isDynamicStub(true) && init) { // TODO: check if the class has already been loaded - if so, shouldn't we wait till the next run to try and update the class?
         compileDynamicStub(true, true);
      }
      LayeredSystem sys = getLayeredSystem();
      if (sys == null) {
         System.err.println("*** No layered system for type: " + typeName);
         return null;
      }
      // TODO: if this returns null, shouldn't we try to transform/generate the file so we can handle at least
      // adding new compiled types on the fly?
      return sys.getCompiledClassWithPathName(typeName);
   }

   public void genDynamicStubIfNecessary() {
      if (isDynamicStub(true)) {
         generateDynamicStub(false);
      }
   }

   public String getDefaultDynTypeClassName() {
      return "sc.dyn.IDynObject";
   }

   public String getFullTypeName(boolean includeDims, boolean includeTypeParams) {
      return getFullTypeName();
   }

   /**
    * A dynamic type may become out of sync with the instances, due to the optimization where we eliminate types.
    * So in a few situations, we need to get the 'runtimeFullTypeName' rather than the full-type-name as based
    * on the current type definition.  For example, we add the first field to a type which has already been instantiated.
    * We need to also consider that for an inner type, it's runtime type name depends on the runtime type name of its enclosing type.
    */
   public String getRuntimeFullTypeName() {
      if (staleClassName != null)
         return staleClassName;
      return getFullTypeName(".", true);
   }

   public String getFullTypeName() {
      if (fullTypeName != null)
         return fullTypeName;
      fullTypeName = getFullTypeName(".", false);
      if (fullTypeName != null && fullTypeName.equals("TypeTree")) {
         fullTypeName = getFullTypeName(".", false);
      }
      return fullTypeName;
   }

   public String getJavaFullTypeName() {
      return getFullTypeName("$", false);
   }

   private String getFullTypeName(String innerTypeSep, boolean runtime) {
      ISemanticNode pnode = parentNode instanceof BodyTypeDeclaration || parentNode instanceof JavaModel ? parentNode : parentNode == null ? null : parentNode.getParentNode(); // Skip the list, not there for command completion

      String useTypeName = typeName;
      // Local methods - those inside of blocks - for now, just naming them like they are inner classes but that might cause problems because they can't be found from
      // that absolute type name.
      while (pnode instanceof AbstractBlockStatement || pnode instanceof AbstractMethodDefinition || pnode instanceof SemanticNodeList || pnode instanceof ForStatement || pnode instanceof IfStatement) {
         if (pnode instanceof AbstractMethodDefinition) {
            // Classes defined inside of methods get an internal name in their class name $1TypeName.  We're using a prefix like _M__1 only because then we can generate the Java code... maybe we should
            // use the $ and ensure these type names never make it into the type system?   We are not putting a "." here because we don't want the method to need to implement ITypeDeclaration just so it
            // can have sub-types.   Really these classes are only visible in the method and so these references only exist for global operations like refresh so we just need to be able to find our way
            // back to the same class in a different instance.
            useTypeName = ((AbstractMethodDefinition) pnode).getInnerTypeName() + typeName;
         }
         pnode = pnode.getParentNode();
      }
      String res;
      if (pnode instanceof JavaModel) {
         JavaModel model = getJavaModel();
         // We are treating the layer's type name as just the part in the layer path.   This is the main identifier we use
         // for finding the layer.  The package prefix is used for finding types inside of the layer.
         if (model.isLayerModel)
            return useTypeName;
         else {
            res = model.getChildTypeName(this, useTypeName);
         }
      }
      else if (pnode instanceof ITypeDeclaration) {
         ITypeDeclaration itd = (ITypeDeclaration) pnode;
         // We put all inner types found in layer def files into a single name-space.
         // TODO? should we could use the layer's package prefix?  If so, we probably need a way for one layer to set other
         // layer's properties so you are not limited what you can do in a layer def file.
         String parentName = itd.isLayerType() ? LayerConstants.LAYER_COMPONENT_FULL_TYPE_NAME : (runtime && itd instanceof BodyTypeDeclaration ? ((BodyTypeDeclaration) itd).getRuntimeFullTypeName() : itd.getFullTypeName());
         res = parentName + innerTypeSep + useTypeName;
      }
      else if (pnode == null)
         res = useTypeName;
      else if (pnode instanceof Element) {
         if (useTypeName == null)
            return null;
         // TODO: is this right?
         res = ((Element) pnode).getFullTypeName();
         res = res + innerTypeSep + useTypeName;
      }
      else {
         System.err.println("*** Unrecognized parent node of type: " + pnode + " returning type name: " + useTypeName);
         res = useTypeName;
      }
      return res;
   }

   // For sync to JS
   public void setFullTypeName() {
      throw new UnsupportedOperationException();
   }


   public String getFullBaseTypeName() {
      return getFullTypeName();
   }

   public String getPackageName() {
      if (isLayerComponent())
         return LayerConstants.LAYER_COMPONENT_PACKAGE;
      return ModelUtil.getPackageName(this);
   }

   // Need this method so that this property appears writable so we can sync it on the client.
   @Constant
   public void setPackageName(String str) {
      throw new UnsupportedOperationException();
   }

   /**
    * Returns the type name not including the package prefix
    */
   public String getInnerTypeName() {
      if (parentNode == null)
         return typeName;

      ISemanticNode pnode = parentNode instanceof BodyTypeDeclaration || parentNode instanceof JavaModel ? parentNode : parentNode.getParentNode(); // Skip the list, not there for command completion
      if (pnode instanceof Element) // Not sure why this case happened but in this case, we want the tagObject's typeName.
         pnode = ((Element) pnode).tagObject;
      if (pnode instanceof JavaModel) {
         return typeName;
      }
      else if (pnode instanceof ITypeDeclaration) {
         ITypeDeclaration pnodeTD = (ITypeDeclaration) pnode;
         if (!pnodeTD.isRealType())
            pnodeTD = (ITypeDeclaration) ModelUtil.getEnclosingType(pnodeTD);
         return pnodeTD.getInnerTypeName() + "." + typeName;
      }
      else if (pnode instanceof BlockStatement)
         return null;
      if (pnode == null)
         return null; // TODO: return typeName here?
      System.err.println("*** Unrecognized type of parent node: " + pnode.getClass());
      return typeName;
   }

   public Definition modifyDefinition(BodyTypeDeclaration base, boolean doMerge, boolean inTransformed) {
      boolean isEnumType = base.isEnumeratedType();
      Object otherTypeObj = base.getInnerType(typeName, null, true, false, false, isEnumType);
      Object annotObj;
      if (otherTypeObj instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration otherType = (BodyTypeDeclaration) otherTypeObj;
         overrides = otherType;
         // Preserves the order of the children in the list.
         otherType.parentNode.replaceChild(otherType, this);
      }
      else if (otherTypeObj == null) {
         displayError("Unable to modify compiled definition: ");
         return null;
      }
      else if ((annotObj = getAnnotation("AddBefore", true)) != null || (annotObj = getAnnotation("AddAfter", true)) != null) {
         Annotation annot = Annotation.toAnnotation(annotObj);
         if (!(annot.elementValue instanceof StringLiteral)) {
            System.err.println("*** Annotation: " + annot.toDefinitionString() + " should specify class name as a String");
         }
         else {
            String otherTypeName = ((StringLiteral) annot.elementValue).stringValue;
            Object indexType = base.getInnerType(otherTypeName, null, false, false, false, isEnumType);
            if (indexType == null || !(indexType instanceof BodyTypeDeclaration)) {
               System.err.println("*** Can't find type in annotation: " + annot.toDefinitionString() + " must be an object defined in: " + typeName);
            }
            else {
               int ix = base.body.indexOf((BodyTypeDeclaration) indexType);

               if (ix != -1) {
                  if (annot.typeName.equals("AddBefore"))
                     base.body.add(ix, this);
                  else
                     base.body.add(ix + 1, this);
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

   /**
    * Under the assumption that there's no time we'll have both the Class and TypeDeclaration of the
    * same type.
    */
   public boolean isAssignableFromClass(Class other) {
      /*
      Class rtClass = getRuntimeClass();
      if (rtClass == null)
         return false;
      return rtClass.isAssignableFrom(other);
      */
      String otherName = TypeUtil.getTypeName(other, true);
      if (otherName.equals(getFullTypeName()))
         return true;
      // Note: weird case here where we may have not generated a compiled class for this type.  In that case, we have
      // to allow a match between a runtime class of the base type and this one even though it might be a false match.
      //if (otherName.equals(getCompiledClassName())) {
      //System.out.println("---");
         /* TODO - remove. I don't like this case... feels like it should be an exception if we need it at all.  In any case, it should have the needsOwnClass guard here which was not here previously
         if (!needsOwnClass(true))
            return true;
         */
      //}
      Class superType = other.getSuperclass();
      if (superType != null && isAssignableFromClass(superType))
         return true;
      Class[] ifaces = other.getInterfaces();
      if (ifaces != null) {
         for (Class c : ifaces)
            if (isAssignableFromClass(c))
               return true;
      }
      return false;
   }


   public Object[] getCachedAllMethods() {
      if (allMethods != null)
         return allMethods;

      List<Object> allMeths = getAllMethods(null, false, false, false);
      if (allMeths == null)
         allMethods = new Object[0];
      else
         allMethods = allMeths.toArray();
      return allMethods;
   }

   /**
    * Returns any methods defined by this type.  Does not include constructors.  If the
    * modifier is supplied, only methods with that modifier are returned.
    */
   public List<Object> getAllMethods(String modifier, boolean hasModifier, boolean isDyn, boolean overridesComp) {
      List<Object> methods = new ArrayList<Object>();
      if (body != null)
         addAllMethods(body, methods, modifier, hasModifier, isDyn, overridesComp);
      if (hiddenBody != null)
         addAllMethods(hiddenBody, methods, modifier, hasModifier, isDyn, overridesComp);
      return methods.size() > 0 ? methods : null;
   }

   static void addAllMethods(List<Statement> bodyList, List<Object> methods, String modifier, boolean hasModifier, boolean isDyn, boolean overridesComp) {
      for (int i = 0; i < bodyList.size(); i++) {
         Definition member = bodyList.get(i);
         if (member instanceof MethodDefinition) {
            MethodDefinition meth = (MethodDefinition) member;
            // Need to pick up even compiled abstract methods for overridesCompiled since we need to override them in the
            // dynamic stub since it's never made abstract
            if (overridesComp && meth.body == null && isDyn)
               isDyn = false;

            if ((modifier == null || hasModifier == meth.hasModifier(modifier)) &&
                    (!isDyn || ModelUtil.isDynamicType(member)) && (!overridesComp || meth.getOverridesCompiled()))
               methods.add(member);

         }
      }
   }

   public List<Object> getMethods(String methodName, String modifier) {
      return getMethods(methodName, modifier, true);
   }

   /**
    * Returns the list of methods with the given name or null if there aren't any.  Does not look for constructors
    */
   public List<Object> getMethods(String methodName, String modifier, boolean includeExtends) {
      if (body == null && hiddenBody == null)
         return null;

      List<Object> methods = new ArrayList<Object>();
      if (body != null) {
         for (int i = 0; i < body.size(); i++) {
            Definition member = body.get(i);
            if (member instanceof MethodDefinition && StringUtil.equalStrings(((MethodDefinition) member).name, methodName) &&
                    (modifier == null || member.hasModifier(modifier)))
               methods.add(member);
         }
      }
      if (hiddenBody != null) {
         for (int i = 0; i < hiddenBody.size(); i++) {
            Definition member = hiddenBody.get(i);
            if (member instanceof MethodDefinition && StringUtil.equalStrings(((MethodDefinition) member).name, methodName) &&
                    (modifier == null || member.hasModifier(modifier)))
               methods.add(member);
         }
      }
      return methods.size() > 0 ? methods : null;
   }

   public Object getMethodFromSignature(String methodName, String signature, boolean resolveLayer) {
      List<Object> methods = getMethods(methodName, null);
      if (methods == null) {
         // Special case way to refer to the constructor
         if (methodName.equals(typeName))
            return getConstructorFromSignature(signature, true);
         // TODO: default constructor?
         return null;
      }
      for (Object meth : methods) {
         if (StringUtil.equalStrings(ModelUtil.getTypeSignature(meth), signature)) {
            if (meth instanceof AbstractMethodDefinition) {
               AbstractMethodDefinition methDef = (AbstractMethodDefinition) meth;
               AbstractMethodDefinition repl = methDef.replacedByMethod;
               // Currently a modify type does not update all references to the type it changes when it is added.
               // So when we go to look up a method for the runtime, we need to
               while ((methDef.replaced || resolveLayer) && (repl = methDef.replacedByMethod) != null) {
                  if (repl.replacedByMethod == null)
                     return repl;
                  methDef = repl;
               }
            }
            return meth;
         }
      }
      return null;
   }

   public Object getMethodFromIndex(int methodIndex) {
      return getCachedAllMethods()[methodIndex];
   }

   /**
    * Returns the list of properties with the given modifiers or null if there aren't any.
    */
   public List<Object> getDeclaredProperties(String modifier, boolean includeAssigns, boolean includeModified, boolean editorProperties) {
      Layer layer = getLayer();
      if (editorProperties && layer != null && layer.hidden)
         return null;

      if (body == null && hiddenBody == null)
         return null;

      List<Object> props = new ArrayList<Object>();
      if (body != null)
         addAllProperties(body, props, modifier, includeAssigns, editorProperties);
      if (hiddenBody != null)
         addAllProperties(hiddenBody, props, modifier, includeAssigns, editorProperties);
      return props.size() > 0 ? props : null;
   }

   // For synchronizing to the client
   @Constant
   public List<Object> getDeclaredProperties() {
      return getDeclaredProperties("public", true, false, true);
   }

   // Here so this property appears writable so we can sync it on the client.
   public void setDeclaredProperties(List<Object> props) {
      throw new UnsupportedOperationException();
   }

   public void setComment(String comment) {
      throw new UnsupportedOperationException();
   }

   /**
    * Returns the list of properties with the given modifiers or null if there aren't any.
    */
   public List<Object> getAllProperties(String modifier, boolean includeAssigns) {
      return getDeclaredProperties(modifier, includeAssigns, false, false);
   }

   private static HashSet<String> filteredProps = new HashSet<String>();
   static {
      filteredProps.add("class");
      filteredProps.add("initState");
      filteredProps.add("serialVersionUID");
      filteredProps.add("DBTypeDescriptor");
      filteredProps.add("DBObject");
      filteredProps.add("DBId");
   }

   public static void addAllProperties(SemanticNodeList<Statement> body, List<Object> props, String modifier, boolean includeAssigns, boolean editorProperties) {
      for (int i = 0; i < body.size(); i++) {
         Definition member = body.get(i);
         if ((member instanceof TypedDefinition && ((TypedDefinition) member).isProperty()) &&
                 (modifier == null || member.hasModifier(modifier))) {
            if (member instanceof FieldDefinition) {
               FieldDefinition field = (FieldDefinition) member;
               for (VariableDefinition def : field.variableDefinitions) {
                  props.add(def);
               }
            }
            else
               props.add(member);
         }
         else if (ModelUtil.isObjectType(member) && (modifier == null || ModelUtil.hasModifier(member, modifier)))
            props.add(member);
         else if (includeAssigns && member instanceof PropertyAssignment && (modifier == null || ModelUtil.hasModifier(member, modifier)))
            props.add(member);
      }

      for (int i = 0; i < props.size(); i++) {
         Object checkProp = props.get(i);
         String checkName = ModelUtil.getPropertyName(checkProp);
         if (editorProperties && filteredProps.contains(checkName)) {
            props.remove(i);
            i--;
            continue;
         }
         for (int j = i+1; j < props.size(); j++) {
            Object nextProp = props.get(j);
            String nextName = ModelUtil.getPropertyName(nextProp);
            if (nextName.equals(checkName)) {
               if (nextProp instanceof VariableDefinition && checkProp instanceof MethodDefinition) {
                  props.remove(j);
                  j--;
               }
               else if (checkProp instanceof VariableDefinition && nextProp instanceof MethodDefinition) {
                  props.remove(i);
                  i--;
                  break;
               }
               // Both methods - keep the first one
               else if (nextProp instanceof MethodDefinition && checkProp instanceof MethodDefinition) {
                  props.remove(j);
                  j--;
               }
            }
         }
      }
   }

   /**
    * TODO: eliminate this? Returns all properties now.  Used to remove static properties from the list returned
    * so that we could implement "non-inherited" properties
    */
   protected List removeStaticProperties(Object[] props) {
      return new ArrayList(Arrays.asList(props));
      /*
      ArrayList<Object> res = new ArrayList<Object>(props.length);
      for (int i = 0; i < props.length; i++) {
         Object p = props[i];
         if (!ModelUtil.hasModifier(p, "static"))
            res.add(p);
      }
      return res;
      */
   }

   /**
    * Returns the list of fields with the given name or null if there aren't any
    */
   public List<Object> getDeclaredIFields(String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns) {
      return null;
   }

   public List<Object> getAllFields(String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified) {
      return getDeclaredFields(modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);
   }

   /**
    * Returns the list of fields with the given name or null if there aren't any
    */
   public List<Object> getDeclaredFields(String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified) {
      List<Object> props = new ArrayList<Object>();
      addAllFields(body, props, modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);
      return props.size() > 0 ? props : null;
   }

   public boolean isCompiledProperty(String propName, boolean fieldMode, boolean interfaceMode) {
      // using FieldSet here because we use this to determine the dyn inst fields, not the properties.  if a class has a private dynamic field it is a dynamic property even though the get/set methods may be defined in a compiled interface.
      return !isDynamicNew() && declaresMember(propName, fieldMode ? MemberType.FieldEnumSet : MemberType.PropertyGetSetObj, null, null) != null;
   }

   public void addAllFields(SemanticNodeList<Statement> body, List<Object> props, String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified) {
      if (body == null)
         return;

      for (int i = 0; i < body.size(); i++) {
         Definition member = body.get(i);
         if ((modifier == null || member.hasModifier(modifier) == hasModifier)) {
            if (member instanceof FieldDefinition) {
               FieldDefinition field = (FieldDefinition) member;
               for (VariableDefinition def : field.variableDefinitions) {
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

               // We might also have compiled the A.B relationship into a base class but where the subobj is a dynamic type (hence the simple compiledProperty above fails)
               if (dynamicOnly && subObj.isDynCompiledObject())
                  continue;

               // For layers any types which are not defined explicitly in our layer are not physically counted as properties.
               if (dynamicOnly && isLayerType && subObj instanceof ModifyDeclaration)
                  continue;

               // Make sure to use the override type so dynamic types point to the most specific type member
               props.add(subObj.resolve(includeModified));
            }
            else if (includeAssigns && member instanceof PropertyAssignment)
               props.add(member);
         }
      }
   }


   Object getCompiledObjectOverrideDef() {
      BodyTypeDeclaration outer = getEnclosingType();
      if (outer != null && getDeclarationType() == DeclarationType.OBJECT) {
         // Jump to the implementation class.  Otherwise, the "stop searching sentinel" is hit due to the object tag and we never find the object.
         Object accessClass = outer.getClassDeclarationForType();
         Object overrideDef = ModelUtil.definesMember(accessClass, typeName, MemberType.SetMethodSet, null, null, getLayeredSystem());
         if (overrideDef != null && ModelUtil.hasSetMethod(overrideDef)) {
            if (overrideDef instanceof CFMethod)
               return ((CFMethod) overrideDef).getRuntimeMethod();
            return overrideDef;
         }
      }
      return null;
   }


   /**
    * Pass in modified=true and it returns the most specific type for this type.  Pass in false, it only skips
    * types with replaced=true, where the user has updated since the definition was read.  It will not step back to modified types.
    * Use false for expressions where you are at a specific point in the layer hierarchy and just want the most up-to-date version (i.e. skipping replace=true)
    * With true, it returns the most specific version of the type at this time.  Use this for when you want the most
    * specific type.
    */
   public BodyTypeDeclaration resolve(boolean modified) {
      if (replacedByType == null || (!modified && !replaced))
         return this;
      if (replacedByType == this || replacedByType.replacedByType == this) {
         System.err.println("*** Error - recursive replaced by type");
         return this;
      }
      return replacedByType.resolve(modified);
   }

   public BodyTypeDeclaration getDeclarationForLayer(List<Layer> layers, int inheritTypeCt, int mergeLayerCt) {
      if (layers == null || layers.size() == 0)
         return this;

      BodyTypeDeclaration root = getModifiedByRoot();
      Layer rootLayer = root.getLayer();

      if (layers.contains(rootLayer))
         return root;

      for (BodyTypeDeclaration next = root.getModifiedType(); next != null; next = next.getModifiedType()) {
         if (layers.contains(next.getLayer()))
            return next;
      }

      //if (inheritTypeCt > 0) {
      Object extObj = getExtendsTypeDeclaration();
      if (extObj instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration extDecl = (BodyTypeDeclaration) extObj;
         if (extDecl.getDeclarationForLayer(layers, inheritTypeCt, mergeLayerCt) != null)
            return root;
      }

      Object[] implTypes = getImplementsTypeDeclarations();
      if (implTypes != null) {
         for (Object implObj : implTypes) {
            if (implObj instanceof BodyTypeDeclaration) {
               BodyTypeDeclaration implDecl = (BodyTypeDeclaration) implObj;
               if (implDecl.getDeclarationForLayer(layers, inheritTypeCt, mergeLayerCt) != null)
                  return root;
            }
         }
      }
      //}

      return null;
   }

   public BodyTypeDeclaration getModifiedByType() {
      BodyTypeDeclaration realType = resolve(false);
      if (realType.replacedByType == null)
         return null;
      return realType.replacedByType.resolve(false);
   }

   /**
    * Returns the root level type corresponding to this type declaration.  In other words, the most specific type in the modify chain
    */
   public BodyTypeDeclaration getModifiedByRoot() {
      BodyTypeDeclaration ret = getModifiedByType();
      if (ret == null || ret == this)
         return this;
      else return ret.getModifiedByRoot();
   }

   /**
    * Like getModifiedType but returns null for modifyInherited cases - i.e. when you modify a type which you inherited from a sub-type.  That pattern works more like an extends of a new type rather than changing the same type.
    */
   public BodyTypeDeclaration getPureModifiedType() {
      return null;
   }

   public BodyTypeDeclaration getModifiedType() {
      return null;
   }

   public BodyTypeDeclaration getUnresolvedModifiedType() {
      return null;
   }

   public boolean hasInnerObjects() {
      if (bodyHasInnerObjects(body))
         return true;
      return bodyHasInnerObjects(hiddenBody);
   }

   private static boolean bodyHasInnerObjects(SemanticNodeList<Statement> bodyList) {
      if (bodyList == null)
         return false;
      for (int i = 0; i < bodyList.size(); i++) {
         Definition member = bodyList.get(i);
         if (member instanceof ITypeDeclaration && ((ITypeDeclaration) member).getDeclarationType() == DeclarationType.OBJECT)
            return true;
      }
      return false;
   }

   /**
    * Returns any inner types defined by this type.  If the
    * modifier is supplied, only methods with that modifier are returned.
    */
   public List<Object> getAllInnerTypes(String modifier, boolean thisClassOnly, boolean includeInherited) {
      // NOTE: we may not be started here and should not called ensureStarted at least when calling this from the updateType process.
      // on the new type. We only start that one after we stop the old one so that we don't double-add to the sub-types table, and then remove
      // the valid entry when we stop the old type
      if (body == null && hiddenBody == null)
         return null;

      List<Object> types = new ArrayList<Object>();
      addAllInnerTypes(types, body, modifier, false);
      addAllInnerTypes(types, hiddenBody, modifier, true);
      return types.size() > 0 ? types : null;
   }

   /**
    * Like getAllInnerTypes but does not consult the modified type - so just returns the inner types we
    * have defined for this type, modify or otherwise.
    */
   public List<Object> getLocalInnerTypes(String modifier) {
      if (body == null && hiddenBody == null)
         return null;

      List<Object> types = new ArrayList<Object>();
      addAllInnerTypes(types, body, modifier, false);
      addAllInnerTypes(types, hiddenBody, modifier, true);
      return types.size() > 0 ? types : null;
   }

   private static void addAllInnerTypes(List<Object> types, SemanticNodeList<Statement> bodyList, String modifier, boolean check) {
      if (bodyList == null)
         return;
      for (int i = 0; i < bodyList.size(); i++) {
         Definition member = bodyList.get(i);
         if (member instanceof ITypeDeclaration && (modifier == null || member.hasModifier(modifier))) {
            if (!check || ModelUtil.propertyIndexOf(types, member, true) == -1)
               types.add(member);
         }
      }
   }

   public String toDeclarationString() {
      StringBuilder sb = new StringBuilder();
      if (modifiers != null) {
         String mods = modifiers.toLanguageString(JavaLanguage.getJavaLanguage().modifiers);
         sb.append(mods);
         if (mods.length() > 0 && !mods.endsWith(" "))
            sb.append(" ");
      }
      if (isInitialized())
         sb.append(getDeclarationType().keyword);
      else
         sb.append(getOperatorString());
      sb.append(" ");
      sb.append(typeName);
      sb.append(" ");
      return sb.toString();
   }

   public String getUserVisibleName() {
      DeclarationType declType = getDeclarationType();
      if (declType == null)
         return "unknown";
      else
         return getDeclarationType().name;
   }

   public Object getClass(String className, boolean useImports, boolean compiledOnly) {
      JavaModel model = getJavaModel();
      return model.getClass(className, useImports, model.getLayer(), model.isLayerModel, false, compiledOnly);
   }

   public Object findTypeDeclaration(String typeName, boolean addExternalReference) {
      JavaModel model = getJavaModel();
      if (model != null)
         return model.findTypeDeclaration(typeName, addExternalReference);
      return null;
   }

   public LayeredSystem getLayeredSystem() {
      JavaModel model = getJavaModel();
      if (model == null)
         return null;
      return model.getLayeredSystem();
   }

   public boolean refreshBoundTypes(int flags) {
      dependentTypes = null; // Reset this so we do not maintain references to types that may get loaded
      memberCache = null; // Not sure if we need to do this but maybe something will hang around in there that points to a parent that was replaced?

      boolean res = false;
      // If we are refreshing after a layer has been closed/open and that layer happened to be modifying this type
      // we need to clear out that replacedByType so it is not used later
      if ((flags & ModelUtil.REFRESH_TYPEDEFS) != 0) {
         if (replacedByType != null && !replaced && !removed) {
            Layer replacedByLayer = replacedByType.getLayer();
            if (replacedByLayer.closed) {
               replacedByType.replacesType = null;
               replacedByType = null;
            }
         }
      }

      if (isLayerType)
         return false;

      // Our inherited types contribute to the list of instFields and those might have changed so we need to refresh them here
      if (instFields != null) {
         int ix = 0;
         for (Object instField:instFields) {
            if (instField instanceof BodyTypeDeclaration) {
               Object newType = ModelUtil.refreshBoundType(getLayeredSystem(), instField, flags);
               if (newType != instField)
                  instFields[ix] = newType;
            }
            ix++;
         }
      }

      if (body != null) {
         for (Statement st : body) {
            if (st.refreshBoundTypes(flags))
               res = true;
         }
      }
      if (hiddenBody != null) {
         for (Statement st : hiddenBody) {
            if (st.refreshBoundTypes(flags))
               res = true;
         }
      }
      return res;
   }

   public Object refreshBoundType(Object boundType) {
      if (replaced) {
         if (replacedByType != null)
            return replacedByType.refreshBoundType(boundType);
      }
      /*
      Layer layer = model.getLayer();

      Object newBoundType = null;
      if (layer != null) {
         LayeredSystem sys = model.getLayeredSystem();
         // Need to lookup this specific type (if it's inside of a layer)
         newBoundType = sys.getSrcTypeDeclaration(getFullTypeName(), layer.getNextLayer(), model.prependLayerPackage, false, true, layer, isLayerType);
         if (newBoundType != null) {
            Layer refreshedLayer = ModelUtil.getLayerForType(sys, newBoundType);
            // For inner types it seems we are having trouble always returning the correct type here.  Try to adjust it
            // but this is really a hack.  It seems like getSrcTypeDeclaration should consistently honor the fromLayer
            // or we need a new mechanism to lookup a type in a specific layer.
            while (refreshedLayer != null && !refreshedLayer.getLayerName().equals(layer.getLayerName())) {
               if (newBoundType instanceof ModifyDeclaration) {
                  ModifyDeclaration modType = (ModifyDeclaration) newBoundType;
                  newBoundType = modType.getModifiedType();
                  refreshedLayer = ModelUtil.getLayerForType(sys, newBoundType);
               }
               else {
                  System.err.println("*** Failed to refresh bound type property");
                  return boundType;
               }
            }
            if (refreshedLayer == null || !refreshedLayer.getLayerName().equals(layer.getLayerName()))
               System.out.println("*** Error - did not refresh bound type properly");
         }
         return newBoundType;
      }
      */
      LayeredSystem sys = getLayeredSystem();
      JavaModel model = getJavaModel();
      if (model == null)
         return boundType;
      // Special handling for classes defined in the same file that are not inner classes - those are only visible from the model itself
      // so first need to refresh the model, then we can find the type in the new model.
      if (getEnclosingType() == null && model.types != null && model.types.size() > 1 && this != model.types.get(0) && !isLayerType) {
         Object newModelType = sys.getTypeDeclaration(model.getModelTypeName(), true, getLayer(), false);
         if (newModelType instanceof BodyTypeDeclaration) {
            JavaModel newModel = ((BodyTypeDeclaration) newModelType).getJavaModel();
            Object newBoundType = newModel.getTypeDeclaration(typeName);
            if (newBoundType != null)
               return newBoundType;
         }
      }
      // TODO: we are not passing in fromLayer here because this method is used by methods and other types to retrieve the most specific type.  I think we might need
      // a mode flag in here so we can call it both ways as when we are refreshing a modified type, we need to get back the one in the same layer that we started with.
      // Shouldn't we actually refresh the model and the find the type relative to the model?
      Object newBoundType = sys.getSrcTypeDeclaration(getFullTypeName(), null, model.getPrependPackage(), false, true, getLayer(), isLayerType);
      if (newBoundType == null) {
         // There are cases where we can only find the type when we pass in the from layer.  Not entirely sure why...
         if (getLayer() != null)
            newBoundType = sys.getSrcTypeDeclaration(getFullTypeName(), getLayer().getNextLayer(), model.getPrependPackage(), false, true, getLayer(), isLayerType);
      }
      if (newBoundType instanceof CFClass) {
         System.out.println("*** Error expected source-based class and found compiled"); // Why are we resolving a CFClass here?
         newBoundType = sys.getTypeDeclaration(getFullTypeName(), true, getLayer(), isLayerType);
      }
      return newBoundType != null ? newBoundType : boundType;
   }

   public boolean isDynamicType() {
      BodyTypeDeclaration outer;

      if (getCompiledOnly())
         return false;
      return dynamicType || isLayerType || ((outer = getEnclosingType()) != null && outer.isDynamicType());
      //return dynamicType;
   }

   /** Returns true if this type is either a 'dynamic new' or a plain old dynamic type */
   public boolean isDynamicNew() {
      if (getCompiledOnly())
         return false;
      return dynamicNew || isDynamicType();
   }

   public boolean getCompiledOnly() {
      return compiledOnly;
   }

   public void setDynamicType(boolean dt) {
      dynamicType = dt;
      if (dynamicNew)
         dynamicNew = false;
      // Need to make any inner types dynamic as well.  Ordinarily they will inherit this from us but in the case
      // where a modified type is setting this on us, it won't be propagated properly.
      if (body != null) {
         for (Statement st : body) {
            if (st instanceof BodyTypeDeclaration)
               ((BodyTypeDeclaration) st).setDynamicType(dt);
         }
      }

      // If someone makes a child type dynamic (because it extends a type that's made dynamic through a modify) we need to propagate the dynamic state up the tree as well.  Alternatively, we could handle the case of a compiled outer type with a dynamic inner type but it seems like if we are changing the outer type at all, we might as well make it dynamic too.
      if (dt) {
         BodyTypeDeclaration enclType = getEnclosingType();
         // When we are transforming a template such as MainInitTemplate, we may add dynamic children to a compiled outer type... since this object is already being transformed at this point not a good idea to try and change it to dynamic
         if (enclType != null && !enclType.processed && !enclType.isDynamicType())
            enclType.setDynamicType(dt);
      }
   }

   public void enableNeedsCompiledClass() {
      needsCompiledClass = true;
   }

   public void setDynamicNew(boolean dt) {
      dynamicNew = dt;
   }

   public void addAllPropertiesToMakeBindable(TreeMap<String, Boolean> properties) {
      LayeredSystem sys = getLayeredSystem();
      if (sys.buildLayer.compiled && properties.size() > 0)
         sys.setStaleCompiledModel(true, "Recompile needed to bind to compiled properties: ", properties.toString());

      if (propertiesToMakeBindable == null)
         propertiesToMakeBindable = new TreeMap<String, Boolean>();

      propertiesToMakeBindable.putAll(properties);
   }

   public void addPropertyAlreadyBindable(String propName) {
      if (propertiesAlreadyBindable == null)
         propertiesAlreadyBindable = new ArrayList<String>();
      else if (propertiesAlreadyBindable.contains(propName))
         return;
      propertiesAlreadyBindable.add(propName);
   }

   /**
    * Returns true if this is a static type - i.e. the class/object has the static keyword (or we are modifying a static type).
    * This is different than "isStatic()" in a subtle way.  That is used for an expression to see if it is in a static context.
    * These are not the same for example when you have a static object but are initializing an instance variable of that object.  It should
    * see it as part of an instance context but that object is still considered a static type for its parent
    */
   public boolean isStaticType() {
      // Don't inherit the JavaSemanticNode's definition.  For a type, we're static only if the static keyword
      // is set on that type, or an enum type or constant
      return hasModifier("static") || isEnumConstant() || isEnumeratedType();
   }

   public void addStaticFields(List<Object> fields) {
      if (body == null)
         return;
      for (Statement st : body) {
         if (st instanceof FieldDefinition) {
            FieldDefinition fd = (FieldDefinition) st;
            if (fd.hasModifier("static")) {
               for (VariableDefinition vdef : fd.variableDefinitions) {
                  fields.add(vdef);
               }
            }
         }
         else if (st instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration td = (BodyTypeDeclaration) st;
            if (td.isStaticType() && (ModelUtil.isObjectType(td) || td.isEnumConstant())) {
               // If there are any modified types, make sure to pick up the most specific one
               if (td.replacedByType != null)
                  fields.add(td.resolve(true));
               else
                  fields.add(td);
            }
         }
      }
   }

   public int getDynInstFieldCount() {
      //return (getEnclosingInstType() != null ? 1 : 0) + getDynInstFields().length;
      // Always reserve one for the parent instance so slot numbers are consistent
      return 1 + getDynInstFields().length;
   }

   public int getDynStaticFieldCount() {
      return getStaticFields().length;
   }

   public Object[] getDynInstFields() {
      if (instFields == null) {
         if (replacedByType != null) {
            instFields = replacedByType.getDynInstFields();
         }
         else {
            // Get only the dynamic fields here - any inherited compiled fields are managed by the
            // parent type.   For layers, we do not include fields from the extends types since those
            // fields we inherit through the Layer's baseLayers explicitly.
            List fields = getAllFields("static", false, true, true, false, true);
            instFields = fields == null ? emptyObjectArray : fields.toArray();
         }
      }
      return instFields;
   }

   public Object[] getOldDynInstFields() {
      return oldInstFields;
   }

   public void addDynInstField(Object fieldObj, boolean updateType) {
      addDynInstFieldLeaf(fieldObj, updateType);
   }

   public void addDynInstFieldLeaf(Object fieldObj, boolean updateType) {
      if (updateType) {
         boolean notYetComputed = instFields == null;
         Object[] ifs = getDynInstFields();
         // If we have not yet initialized inst fields, we'll have already added this guy and so don't need to add it
         // twice
         int ix;
         if (!notYetComputed) {
            ix = ifs.length;

            // Field already added.  The algorithm for traversing the type tree may call us more than once for a type?
            for (int i = 0; i < ifs.length; i++)
               if (ifs[i] == fieldObj)
                  return;

            int newLen = ix + 1;
            Object[] nfs = new Object[newLen];
            System.arraycopy(ifs, 0, nfs, 0, ix);
            nfs[ix] = fieldObj;
            instFields = nfs;
         }
         else {
            ix = -1;
            for (int i = 0; i < ifs.length; i++) {
               if (ifs[i] == fieldObj) {
                  ix = i;
                  break;
               }
            }
            if (ix == -1)
               System.err.println("*** Can't find newly added field in uninitialized list");
         }
         String propName = ModelUtil.getPropertyName(fieldObj);
         if (dynInstFieldMap == null)
            initDynInstFieldMap();
         //int incr = getEnclosingInstType() != null ? 1 : 0;
         int incr = 1; // always reserve one for the parent instance
         dynInstFieldMap.put(propName, ix + incr);

         // TODO: what about get/set's and overriding?
         addInstMemberToPropertyCacheInternal(propName, fieldObj);
      }

      if (isModifiedBySameType()) {
         replacedByType.addDynInstFieldLeaf(fieldObj, updateType);
      }

      LayeredSystem sys = getLayeredSystem();
      Iterator<BodyTypeDeclaration> subTypes = this instanceof TypeDeclaration ? sys.getSubTypesOfType((TypeDeclaration) this) : null;
      if (subTypes != null) {
         while (subTypes.hasNext()) {
            BodyTypeDeclaration subType = subTypes.next();
            subType = subType.resolve(true);
            subType.addDynInstFieldLeaf(fieldObj, updateType);
         }
      }

   }

   BodyTypeDeclaration getRealReplacedByType() {
      BodyTypeDeclaration modifiedByType = replacedByType;
      BodyTypeDeclaration prevType = this;
      while (prevType.replaced && modifiedByType != null) {
         prevType = modifiedByType;
         modifiedByType = modifiedByType.replacedByType;
      }
      return modifiedByType;
   }

   private boolean isModifiedBySameType() {
      if (replacedByType == null)
         return false;

      BodyTypeDeclaration modifiedByType = getRealReplacedByType();
      return modifiedByType instanceof ModifyDeclaration && !((ModifyDeclaration) modifiedByType).modifyInherited;
   }

   private void addInstMemberToPropertyCacheInternal(String propName, Object fieldObj) {
      if (propertyCache != null) {
         if (propertyCache.getPropertyMapper(propName) == null) {
            DynBeanMapper mapper = fieldObj instanceof BodyTypeDeclaration ?
                    new DynBeanMapper((BodyTypeDeclaration) fieldObj) :
                    new DynBeanMapper(fieldObj, fieldObj, fieldObj);
            mapper.instPosition = propertyCache.propertyCount++;
            propertyCache.addProperty(propName, mapper);
         }
         else {
            // Sucked in from a superclass?
         }
      }
   }

   public Object[] getStaticFields() {
      if (!isDynamicNew())
         return TypeUtil.EMPTY_ARRAY;

      if (staticFields == null) {
         // We used to start here but for layer types at least, we can't start the type this early.  We need to resolve the
         // fields so we can create the layer instance, then build separate layers, then once those are in the classpath we
         // start the layers that depend on those types.
         // if (!isStarted())
         //    ModelUtil.ensureStarted(this, true);

         ArrayList<Object> fields = new ArrayList<Object>();
         addStaticFields(fields);
         staticFields = fields.toArray(new Object[fields.size()]);
      }
      return staticFields;
   }

   public Object getStaticPropertyObject(String propName) {
      int ix = getDynStaticFieldIndex(propName);
      if (ix == -1)
         return null;
      Object[] fields = getStaticFields();
      return fields[ix];
   }

   public Object[] getOldStaticFields() {
      return oldStaticFields;
   }

   /**
    * Initializes the type's static fields for interpreting
    */
   public void staticInit() {
      ensureValidated();

      if (isDynamicType() && staticValues == null) {
         Object der = getDerivedTypeDeclaration();
         if (der != null && der instanceof TypeDeclaration)
            ((TypeDeclaration) der).staticInit();
         Object ext = getExtendsTypeDeclaration();
         if (ext != null && ext != der && ext instanceof BodyTypeDeclaration)
            ((BodyTypeDeclaration) ext).staticInit();
         getStaticValues();
      }
   }

   public void preInitStaticValues(Object[] staticVals, ExecutionContext ctx) {
      if (body != null) {
         preInitStaticBody(staticVals, body, ctx);
      }
      if (hiddenBody != null) {
         preInitStaticBody(staticVals, hiddenBody, ctx);
      }
   }

   private void preInitStaticBody(Object[] staticVals, SemanticNodeList<Statement> bodyList, ExecutionContext ctx) {
      // Lazy init sentinels need to get put in before we start executing static code
      for (Statement st : bodyList) {
         if (st instanceof BodyTypeDeclaration) {
            int i;
            BodyTypeDeclaration td = (BodyTypeDeclaration) st;
            if (td.isStaticType() && ModelUtil.isObjectType(td) && ModelUtil.isDynamicType(td)) {
               i = getDynStaticFieldIndex(td.typeName);
               if (staticVals[i] == null)
                  staticVals[i] = DynObject.lazyInitSentinel;
            }
         }
      }

   }

   public void initStaticValuesBody(Object[] staticVals, SemanticNodeList<Statement> bodyList, ExecutionContext ctx) {
      int i;
      for (Statement st : bodyList) {
         if (st instanceof FieldDefinition) {
            FieldDefinition fd = (FieldDefinition) st;
            if (fd.hasModifier("static") && fd.isDynamicType()) {
               for (VariableDefinition staticField : fd.variableDefinitions) {
                  Object newField;
                  // Make sure we get the latest one in case it was modified
                  newField = getStaticPropertyObject(staticField.variableName);
                  if (newField != null && newField instanceof VariableDefinition)
                     staticField = (VariableDefinition) newField;
                  i = getDynStaticFieldIndex(staticField.variableName);

                  // This gets called for each ModifyType after the modified type.  Thus preserving the
                  // init order but getting the most specific value.
                  if (staticVals[i] == null) {
                     staticVals[i] = staticField.getStaticValue(ctx);
                  }
               }
            }
         }
         else if (st instanceof PropertyAssignment) {
            PropertyAssignment pa = (PropertyAssignment) st;
            Object field = pa.assignedProperty;
            VariableDefinition varDef;
            if (ModelUtil.hasModifier(field, "static") && pa.getEnclosingType() == this &&
                    (field instanceof VariableDefinition) && ModelUtil.isDynamicType(field)) {
               varDef = (VariableDefinition) field;
               int staticIndex = getDynStaticFieldIndex(varDef.variableName);
               if (staticIndex != -1) {
                  if (pa.initializer != null) {
                     Object val = pa.initializer.eval(varDef.getRuntimeClass(), ctx);
                     if (pa.bindingDirection == null || pa.bindingDirection.doForward()) {
                        staticVals[staticIndex] = val;
                     }
                  }
               }
               else {
                  System.err.println("*** can't find static property");
               }
            }
         }
         else if (st instanceof BlockStatement) {
            BlockStatement bs = (BlockStatement) st;
            if (bs.staticEnabled) {
               bs.exec(ctx);
            }
         }
         else if (st instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration td = (BodyTypeDeclaration) st;
            if (td.isEnumConstant()) {
               List<Expression> enumArgs = td.getEnumArguments();
               int staticIndex = getDynStaticFieldIndex(td.typeName);
               if (staticIndex != -1) {
                  try {
                     ctx.pushStaticFrame(this);
                     // TODO: parameter signature here?
                     staticVals[staticIndex] = td.createInstance(ctx, null, enumArgs);
                  }
                  finally {
                     ctx.popStaticFrame();
                  }
               }
            }
         }
      }

   }

   public void initStaticValues(Object[] staticVals, ExecutionContext ctx) {
      Object[] staticFields = getStaticFields();
      if (staticFields != null) {
         if (body != null) {
            initStaticValuesBody(staticVals, body, ctx);
         }
         if (hiddenBody != null) {
            initStaticValuesBody(staticVals, hiddenBody, ctx);
         }
      }
   }

   public List<Expression> getEnumArguments() {
      return null;
   }

   public Object[] getStaticValues() {
      if (!isProcessed()) {
         JavaModel model = getJavaModel();
         if (model != null)
            model.process();
      }
      // The values should never be accessed on a replaced type.  On the other hand, the static field map is needed
      // by the modified class to build its own table.
      if (replacedByType != null)
         return replacedByType.getStaticValues();

      if (staticValues == null) {
         ExecutionContext ctx = new ExecutionContext(getJavaModel());
         ctx.pushStaticFrame(this);
         try {
            Object[] statFields = getStaticFields();
            if (statFields != null) {
               staticValues = new Object[getDynStaticFieldCount()];
               preInitStaticValues(staticValues, ctx);
               initStaticValues(staticValues, ctx);
            }
            else
               staticValues = new Object[0];
         }
         finally {
            ctx.popStaticFrame();
         }
      }
      return staticValues;
   }

   private void initStaticFieldMap() {
      Object[] sfs = getStaticFields();

      staticFieldMap = new IntCoalescedHashMap(sfs.length);
      int i = 0;
      for (Object varDef : sfs) {
         staticFieldMap.put(ModelUtil.getPropertyName(varDef), i++);
      }
   }

   protected void addStaticField(Object fieldType, String fieldName, Object initValue) {
      // Currently not updating the fields/values arrays unless they've already been initialized
      if (staticFields != null) {
         Object[] sfs = getStaticFields();
         boolean initValues = staticValues != null;
         int ix = sfs == null ? 0 : sfs.length;
         int newLen = ix + 1;
         Object[] newSfs = new Object[newLen];
         Object[] newSvs = new Object[newLen];
         System.arraycopy(sfs, 0, newSfs, 0, ix);
         if (initValues) {
            Object[] svs = getStaticValues();
            System.arraycopy(svs, 0, newSvs, 0, ix);
            newSvs[ix] = initValue;
            staticValues = newSvs;
         }
         newSfs[ix] = fieldType;
         staticFields = newSfs;
         staticFieldMap.put(fieldName, ix);
      }

      // TODO: what about get/set's and overriding?
      // TODO: also do we need to propagate this property to the sub-types property lists?  This is done for instance
      // fields.
      if (propertyCache != null) {
         DynBeanMapper mapper = new DynBeanMapper(fieldType, fieldType, fieldType);
         mapper.staticPosition = propertyCache.staticPropertyCount++;
         mapper.ownerType = this;
         propertyCache.addProperty(fieldName, mapper);
      }
   }

   protected boolean isDynObj(boolean getStatic) {
      if (isHiddenType())
         return false;
      return isDynamicNew() && getDeclarationType() == DeclarationType.OBJECT && isStaticType() == getStatic;
   }

   private void initDynInstFieldMap() {
      if (dynInstFieldMap != null)
         return;

      if (replacedByType != null) {
         replacedByType.initDynInstFieldMap();
         dynInstFieldMap = replacedByType.dynInstFieldMap;
         dynTransientFields = replacedByType.dynTransientFields;
         return;
      }

      Object[] sfs = getDynInstFields();
      // Reserve the first slot for the reference to the outer class if there's one
      //int i = getEnclosingInstType() != null ? 1 : 0;
      int i = 1; // Always use 1 so that sub-class slot #'s are consistent with base supers even when the base type has no outer class
      dynInstFieldMap = new IntCoalescedHashMap(i + sfs.length);
      dynTransientFields = new BitSet(sfs.length);
      for (Object vardef : sfs) {
         String n = ModelUtil.getPropertyName(vardef);
         if (ModelUtil.hasModifier(vardef, "transient"))
            dynTransientFields.set(i);
         dynInstFieldMap.put(n, i++);
      }

      // This is a weird case - we have object pkg extends RepositoryPackage {} inside of a layer.  The layer is not
      // serializable but the RepositoryPackage is.  We don't need to restore the parent child relationship here on de-serialize
      // as we only care about serializing the RepositoryPackage itself so here we detect and avoid this error ahead of time.
      Object enclInstType = getEnclosingInstType();
      if (!ModelUtil.isAssignableFrom(Serializable.class, enclInstType))
         dynTransientFields.set(0);
   }

   public BitSet getDynTransientFields() {
      if (dynTransientFields == null)
         initDynInstFieldMap();
      return dynTransientFields;
   }

   private Object mapIndexToObject(int index, boolean isInstance) {
      Object obj;
      if (isInstance) {
         Object[] locInstFields = getDynInstFields();
         // TODO: is this call needed if index != 0
         obj = getEnclosingInstType();
         if (index == 0)
            return obj;
         index--;
         if (index >= locInstFields.length)
            throw new IllegalArgumentException("*** Error - invalid field index"); // More of an internal error?  We did not update the type for some new field that was added?
         return locInstFields[index];
      }
      else {
         Object[] statFields = getStaticFields();
         return statFields[index];
      }
   }

   public int getDynInstPropertyIndex(String propName) {
      if (dynInstFieldMap == null) {
         initDynInstFieldMap();
      }
      int res = dynInstFieldMap.get(propName);
      // Because we take a.B and turn it into a.getB which is really a.b we need to do this test here
      if (res == -1 && propName.length() > 1 && Character.isUpperCase(propName.charAt(0))) {
         res = dynInstFieldMap.get(CTypeUtil.decapitalizePropertyName(propName));
      }
      return res;
   }

   public int getDynStaticFieldIndex(String propName) {
      if (staticFieldMap == null) {
         initStaticFieldMap();
      }
      return staticFieldMap.get(propName);
   }

   public boolean isDynProperty(String propName) {
      return getDynInstPropertyIndex(propName) != -1 || getDynStaticFieldIndex(propName) != -1;
   }

   public void setDynStaticField(int ix, Object value) {
      getStaticValues()[ix] = value;
   }

   public void setDynStaticField(String propName, Object value) {
      if (staticFieldMap == null) {
         initStaticFieldMap();
      }
      int ix = staticFieldMap.get(propName);
      if (ix == -1)
         displayError("No static property: ", propName, " for assignment on type: ");
      else
         getStaticValues()[ix] = value;
   }

   public Object getDynStaticProperty(int ix) {
      Object[] svals = getStaticValues();
      Object val = svals[ix];
      if (val == DynObject.lazyInitSentinel) {
         val = svals[ix] = initLazyDynProperty(null, ix, false);
      }
      return val;
   }

   public Object getDynStaticField(String propName) {
      if (staticFieldMap == null) {
         initStaticFieldMap();
      }
      int ix = staticFieldMap.get(propName);
      if (ix == -1) {
         displayError("No static property: ", propName, " for assignment on type: ");
         return null;
      }
      else {
         return getDynStaticProperty(ix);
      }
   }

   public void setStaticProperty(String propName, Object value) {
      IBeanMapper mapper = getPropertyMapping(propName);
      if (mapper != null)
         mapper.setPropertyValue(this, value);
      else
         throw new IllegalArgumentException("No static property for set: " + propName + " for type: " + this);
   }

   public Object getStaticProperty(String propName) {
      IBeanMapper mapper = getPropertyMapping(propName);
      if (mapper != null)
         return mapper.getPropertyValue(this, false, false);
      else
         throw new IllegalArgumentException("No static property: " + propName + " for type: " + this);
   }

   public DynType getPropertyCache() {
      if (propertyCache == null)
         initPropertyCache();
      return propertyCache;
   }

   public IBeanMapper getPropertyMapping(String prop) {
      return getPropertyCache().getPropertyMapper(prop);
   }

   private int addInterfaceTypePropertyCache(DynType superCache, int pos) {
      if (superCache.properties == null)
         return pos;

      boolean isInterface = getDeclarationType() == DeclarationType.INTERFACE;

      for (int i = 0; i < superCache.properties.keyTable.length; i++) {
         if (superCache.properties.keyTable[i] != null) {
            IBeanMapper inherit = (IBeanMapper) superCache.properties.valueTable[i];

            // Skip any properties which are already registered.  These will be interface properties which we inherited
            // our base type or defined ourselves
            if (propertyCache.getPropertyMapper(inherit.getPropertyName()) != null)
               continue;

            // Only adding non-static interface properties here
            if (inherit.getPropertyPosition() == IBeanMapper.DYNAMIC_LOOKUP_POSITION) {
               if (!isInterface) {
                  DynBeanMapper instMapper = new DynBeanMapper(inherit);
                  instMapper.instPosition = pos++;
                  propertyCache.addProperty(instMapper);
               }
               else
                  propertyCache.addProperty(inherit);
            }
         }
      }
      return pos;
   }

   private int addSuperTypePropertyCache(DynType superCache, int pos, boolean doStatics, boolean modifyWithOverridenExtends) {
      if (superCache.properties == null)
         return pos;

      for (int i = 0; i < superCache.properties.keyTable.length; i++) {
         String propertyName = (String) superCache.properties.keyTable[i];
         if (propertyName != null) {
            IBeanMapper inherit = (IBeanMapper) superCache.properties.valueTable[i];
            Object propertyMember = inherit.getPropertyMember();
            if (propertyMember == null || (doStatics || !ModelUtil.hasModifier(propertyMember, "static"))) {
               IBeanMapper existing = propertyCache.properties.get(propertyName);
               // When the modify does an "extends" - replacing the actual extends class of the type - the property positions need to be based on the new extends class, which means the old old guy has precedence over the new guy.
               if (!modifyWithOverridenExtends || (existing = propertyCache.properties.get(propertyName)) == null)
                  propertyCache.addProperty(inherit);
               if (inherit.getPropertyPosition() >= pos)
                  pos = inherit.getPropertyPosition() + 1;
            }
         }
      }
      return pos;
   }

   // Layers inherit properties differently than regular extends or implements.  All of the properties use dynamic lookup so the
   // positions do not matter.
   private int addLayerTypePropertyCache(DynType superCache, int pos) {
      if (superCache.properties == null)
         return pos;

      for (int i = 0; i < superCache.properties.keyTable.length; i++) {
         String propertyName = (String) superCache.properties.keyTable[i];
         if (propertyName != null) {
            IBeanMapper inherit = (IBeanMapper) superCache.properties.valueTable[i];

            // We add inherited properties which are not part of the Layer object itself as dynamic lookup so we can do multiple inheritance
            if (inherit.getPropertyPosition() == IBeanMapper.DYNAMIC_LOOKUP_POSITION) {
               propertyCache.addProperty(inherit);
            }
            if (inherit.getPropertyPosition() >= pos)
               pos = inherit.getPropertyPosition() + 1;
         }
      }
      return pos;
   }

   public Object[] getAllImplementsTypeDeclarations() {
      return getImplementsTypeDeclarations();
   }

   // Do not use "implementsTypes" as that's the property name of the actual model field in sub-types
   public Object[] getImplementsTypeDeclarations() {
      return null;
   }

   void initPropertyCache() {
      if (propertyCache != null)
         return;

      if (!isValidated())
         ModelUtil.ensureStarted(this, true);

      DynType cache;

      try {
         int pos = PTypeUtil.MIN_PROPERTY, staticPos = PTypeUtil.MIN_PROPERTY;

         // First populate from the super-class so that all of our slot positions are consistent.  Need to use the CompiledExtendsType here because
         // for modify inherited types, we need to treat the modify type as the x
         Object extType = null;
         Object[] extTypes = null;
         boolean isModified = false;
         DynType[] implDynTypes = null;
         DynType[] extDynTypes = null;
         Object modType = getDerivedTypeDeclaration();
         if (isLayerType) {
            extTypes = getExtendsTypeDeclarations();
         }
         else {
            extType = getExtendsTypeDeclaration();
            // This is only different for modified types - just easier to put the code all in one place though
            if (modType == extType) {
               modType = null;
               isModified = true;
            }
            // If both are not null, always make sure extType is the main one
            else if (extType == null && modType != null) {
               extType = modType;
               modType = null;
               isModified = true;
            }
         }

         Object[] implementTypes = getImplementsTypeDeclarations();

         // Make sure we switch to the class version if we are compiled.  Since this is the runtime description
         // we don't want to get field references for things which have had get/set methods created.
         if (extType != null && !ModelUtil.isDynamicNew(extType)) {
            extType = ModelUtil.getRuntimeType(extType);
            if (extType == null)
               System.err.println("*** No runtime type for extType!");
         }

         if (modType != null && !ModelUtil.isDynamicNew(modType))
            modType = ModelUtil.getRuntimeType(modType);

         int ifaceCount = 0;
         int ifaceStaticCount = 0;

         if (implementTypes != null) {
            int i = 0;
            implDynTypes = new DynType[implementTypes.length];
            for (Object implType : implementTypes) {
               DynType ifType = ModelUtil.getPropertyCache(implType);
               implDynTypes[i] = ifType;
               ifaceCount += ifType.propertyCount;
               ifaceStaticCount += ifType.staticPropertyCount;
               i++;
            }
         }

         if (extTypes != null) {
            int i = 0;
            extDynTypes = new DynType[extTypes.length];
            for (Object etype : extTypes) {
               if (etype != null) {
                  DynType exType = ModelUtil.getPropertyCache(etype);
                  extDynTypes[i] = exType;
                  ifaceCount += exType.propertyCount;
                  ifaceStaticCount += exType.staticPropertyCount;
               }
               i++;
            }

         }

         DynType superType = null;
         DynType modTypeCache = modType == null ? null : ModelUtil.getPropertyCache(modType);

         int baseCount = getMemberCount() + ifaceCount + ifaceStaticCount;

         int modCount = modTypeCache == null ? 0 : modTypeCache.propertyCount + modTypeCache.staticPropertyCount;
         if (extType != null) {
            superType = ModelUtil.getPropertyCache(extType);

            propertyCache = cache = new DynType(null, modCount + superType.propertyCount + superType.staticPropertyCount + baseCount, 0);

            // First process extended properties - which we essentially underlay on this type if it is modified
            pos = addSuperTypePropertyCache(superType, pos, isModified, false);
         }
         else {
            propertyCache = cache = new DynType(getFullTypeName(), null, modCount + baseCount, 0);
         }
         // Now process the modified types properties (if any)
         if (modTypeCache != null) {
            // Passing the last arg as true the extends type is part of the modify, i.e. is defined after the modify type but should have precedence over the modify type
            pos = addSuperTypePropertyCache(modTypeCache, pos, true, (this instanceof ModifyDeclaration) && ((ModifyDeclaration) this).extendsTypes != null);
         }


         // Java does not let us reflect this in the normal way as a field so it is treated specially.
         /*
         if (resultClass.isArray() && (superClass == null || !superClass.isArray())) {
            // This guy is always at position 0 for array classes
            cache.put("length", ArrayLengthBeanMapper.INSTANCE);
            if (pos == 0)
               pos++;
         }
         */

         cache.propertyCount = pos;

         boolean isInterface = getDeclarationType() == DeclarationType.INTERFACE;

         // For concrete classes which inherit instance fields from interfaces (i.e. the SC multiple inheritance feature)
         // we need to override the get/set methods in the interface with the actual variable definition we'd inherit
         // we we map these properties to the dynamic field, not the getX/setX methods
         if (!isInterface) {
            List<Object> dynIfaceFields = getDynCompiledIFields();
            if (dynIfaceFields != null) {
               for (int i = 0; i < dynIfaceFields.size(); i++) {
                  Object field = dynIfaceFields.get(i);
                  if (field instanceof FieldDefinition) {
                     addFieldToPropertyCache((FieldDefinition) field, cache, false);
                  }
                  else if (field instanceof VariableDefinition)
                     addVarDefToPropertyCache((VariableDefinition) field, cache, false, false);
               }
            }
         }

         // Note: cache.propertyCount is set in addPropertiesInBody
         addPropertiesInBody(body, cache, superType, isInterface);
         /* Scopes a.b transformations add to the set of properties from their definition so account for those here */
         addPropertiesInBody(hiddenBody, cache, superType, isInterface);

         pos = cache.propertyCount;

         // Need to do these at the end so that we do not assign new positions for any interface properties which
         // are defined in the body or base type.
         if (implDynTypes != null) {
            for (DynType ifType : implDynTypes)
               pos = addInterfaceTypePropertyCache(ifType, pos);
         }

         // Since all layer properties use dynamic lookup the indexes don't have to be consistent for each type (hopefully - since that's not possible for multiple inheritance)
         if (extTypes != null) {
            for (DynType layerType : extDynTypes) {
               if (layerType != null)
                  pos = addLayerTypePropertyCache(layerType, pos);
            }
         }

         cache.propertyCount = pos;

      }
      catch (Error e) {
         System.err.println("*** Error trying to get properties for type: " + typeName + ": " + e);
         e.printStackTrace();
      }
   }

   private void addVarDefToPropertyCache(VariableDefinition varDef, DynType cache, boolean isInterface, boolean isStatic) {
      String name = varDef.variableName;
      DynBeanMapper newMapper = new DynBeanMapper(varDef, varDef, varDef);

      // Fields that are arrays need this interface as a marker so we know to send indexed change
      // events.
      Object varType = varDef.getTypeDeclaration();
      if (ModelUtil.isArray(varType))
         newMapper = new DynBeanIndexMapper(newMapper);

      IBeanMapper oldMapper;
      // This happens when a field in a subclass overrides a get/set method in a superclass
      // We don't allocate a new position for this case because it creates a null slot if we ever
      // turn this into a property list.
      if ((oldMapper = cache.addProperty(name, newMapper)) != null) {
         newMapper.instPosition = oldMapper.getPropertyPosition();
         newMapper.staticPosition = oldMapper.getStaticPropertyPosition();
         newMapper.ownerType = oldMapper.getOwnerType();
      }
      else {
         if (isStatic) {
            newMapper.staticPosition = cache.staticPropertyCount++;
            newMapper.ownerType = this;
         }
         else {
            if (isInterface || isLayerType)
               newMapper.instPosition = IBeanMapper.DYNAMIC_LOOKUP_POSITION;
            else
               newMapper.instPosition = cache.propertyCount++;
         }
      }

   }

   private void addFieldToPropertyCache(FieldDefinition field, DynType cache, boolean isInterface) {
      boolean isStatic = field.hasModifier("static");
      for (VariableDefinition varDef : field.variableDefinitions) {
         addVarDefToPropertyCache(varDef, cache, isInterface, isStatic);
      }
   }

   private void addPropertiesInBody(SemanticNodeList<Statement> body, DynType cache, DynType superType, boolean isInterface) {
      DynBeanMapper newMapper;
      IBeanMapper mapper;

      if (body != null) {
         int size = body.size();
         for (int i = 0; i < size; i++) {
            Statement statement = body.get(i);
            if (statement instanceof FieldDefinition) {
               FieldDefinition field = (FieldDefinition) statement;
               addFieldToPropertyCache(field, cache, isInterface);
            }
            else if (statement instanceof BodyTypeDeclaration && statement.isDynamicType()) {
               BodyTypeDeclaration innerType = (BodyTypeDeclaration) statement;
               String name = innerType.typeName;
               // Skip this property if there's already a property mapper from the super type.  It might be compiled and
               // we can't override a compiled field with a dynamic one since they are in different ranges.
               if (innerType.getDeclarationType() == DeclarationType.OBJECT && !innerType.isHiddenType() &&
                       cache.getPropertyMapper(name) == null) {
                  boolean isStatic = innerType.isStaticType();
                  newMapper = new DynBeanMapper(innerType);
                  IBeanMapper oldMapper;
                  // This happens when a field in a subclass overrides a get/set method in a superclass
                  // We don't allocate a new position for this case because it creates a null slot if we ever
                  // turn this into a property list.
                  if ((oldMapper = cache.addProperty(name, newMapper)) != null) {
                     newMapper.instPosition = oldMapper.getPropertyPosition();
                     newMapper.staticPosition = oldMapper.getStaticPropertyPosition();
                     newMapper.ownerType = oldMapper.getOwnerType();
                  }
                  else {
                     if (isStatic) {
                        newMapper.staticPosition = cache.staticPropertyCount++;
                        newMapper.ownerType = this;
                     }
                     else
                        newMapper.instPosition = cache.propertyCount++;
                  }
               }
            }
         }

         TreeMap<String,IBeanMapper> pendingValidates = null;

         for (int i = 0; i < size; i++) {
            Statement st = body.get(i);
            if (st instanceof MethodDefinition) {
               MethodDefinition method = (MethodDefinition) st;
               String name = method.name;
               Object[] ptypes;
               String propName;
               PropertyMethodType type;

               if (method.hasModifier("private"))
                  continue;

               char c = name.charAt(0);
               switch (c) {
                  case 's':
                     ptypes = method.getParameterTypes(false);
                     if (!name.startsWith("set"))
                        continue;
                     if (ptypes.length == 1)
                        type = PropertyMethodType.Set;
                     else if (ptypes.length == 2 && ModelUtil.isInteger(ptypes[0]))
                        type = PropertyMethodType.SetIndexed;
                     else
                        continue;
                     propName = CTypeUtil.decapitalizePropertyName(name.substring(3));
                     break;
                  case 'g':
                     ptypes = method.getParameterTypes(false);
                     if (!name.startsWith("get"))
                        continue;
                     if (ptypes == null || ptypes.length == 0)
                        type = PropertyMethodType.Get;
                     else if (ptypes.length == 1 && ModelUtil.isInteger(ptypes[0]))
                        type = PropertyMethodType.GetIndexed;
                     else
                        continue;

                     propName = CTypeUtil.decapitalizePropertyName(name.substring(3));
                     break;
                  case 'i':
                     ptypes = method.getParameterTypes(false);
                     if (!name.startsWith("is") || (ptypes != null && ptypes.length != 0))
                        continue;
                     propName = CTypeUtil.decapitalizePropertyName(name.substring(2));
                     type = PropertyMethodType.Is;
                     break;
                  case 'v':
                     if (!name.startsWith("validate"))
                        continue;
                     ptypes = method.getParameterTypes(false);
                     if (ptypes != null && ptypes.length != 0 && ptypes.length != 1 || name.length() < 9)
                        continue;
                     Object retType = method.getReturnType(false);
                     if (!ModelUtil.sameTypes(retType, String.class))
                        continue;
                     propName = CTypeUtil.decapitalizePropertyName(name.substring(8));
                     type = PropertyMethodType.Validate;
                     break;
                  default:
                     continue;
               }
               if (propName.length() == 0)
                  continue;

               boolean isStatic = method.hasModifier("static");
               mapper = cache.getPropertyMapper(propName);
               if (mapper == null && pendingValidates != null && type != PropertyMethodType.Validate) {
                  mapper = pendingValidates.get(propName);
                  if (mapper != null) {
                     cache.addProperty(propName, mapper);
                     pendingValidates.remove(propName);
                  }
               }
               if (mapper == null) {
                  newMapper = new DynBeanMapper();
                  if (isStatic) {
                     newMapper.staticPosition = cache.staticPropertyCount++;
                     newMapper.ownerType = this;
                  }
                  else {
                     if (isInterface || isLayerType)
                        newMapper.instPosition = IBeanMapper.DYNAMIC_LOOKUP_POSITION;
                     else
                        newMapper.instPosition = cache.propertyCount++;
                  }
                  // This logic is to avoid adding a property with no get or set method but with a validate method
                  if (type == PropertyMethodType.Validate) {
                     if (pendingValidates == null)
                        pendingValidates = new TreeMap<String,IBeanMapper>();
                     pendingValidates.put(propName, mapper);
                  }
                  else
                     cache.addProperty(propName, newMapper);
               }
               else {
                  if (superType != null && superType.getPropertyMapper(propName) == mapper) {
                     newMapper = new DynBeanMapper(mapper);
                     cache.addProperty(propName, newMapper);
                  }
                  // If this is not our super-types mapper, we must have created it so it is a dyn bean mapper
                  else
                     newMapper = (DynBeanMapper) mapper;
                  // Tricky case - either the field or the other set method's
                  // static does not match this one.  In this case, allocate the
                  // new slot in the other scope.
                  if (isStatic && newMapper.getStaticPropertyPosition() == -1) {
                     newMapper.staticPosition = mapper.getStaticPropertyPosition();
                     newMapper.ownerType = mapper.getOwnerType();
                     if (newMapper.staticPosition == -1)
                        System.err.println("*** bad inherit of static property");
                  }
                  if (!isStatic && newMapper.instPosition == -1) {
                     if (isInterface || isLayerType)
                        newMapper.instPosition = IBeanMapper.DYNAMIC_LOOKUP_POSITION;
                     else
                        newMapper.instPosition = cache.propertyCount++;
                  }
               }
               switch (type) {
                  case Set:
                     if (newMapper.getSetSelector() != null) {
                        // Always let us override the set method if it is a field
                        if (ModelUtil.isField(newMapper.getSetSelector()))
                           newMapper.setSetSelector(method);
                           // Need to use property type to select the right setSelector method
                        else {
                           Object oldSetMethod = newMapper.getSetSelector();
                           Object[] oldptypes = ModelUtil.getParameterTypes(oldSetMethod);
                           if (oldptypes[0] == ptypes[0] || ModelUtil.isAssignableFrom(ptypes[0], oldptypes[0]))
                              newMapper.setSetSelector(method);
                           else {
                              Object getSelector = newMapper.getSelector;
                              if (getSelector == null) {
                                 String getName = "get" + CTypeUtil.capitalizePropertyName(propName);
                                 for (int j = i; j < size; j++) {
                                    Object nextStatement = body.get(j);
                                    if (nextStatement instanceof MethodDefinition) {
                                       MethodDefinition theMethod = (MethodDefinition) nextStatement;
                                       if (theMethod.name.equals(getName) && theMethod.getParameterTypes(false).length == 0) {
                                          getSelector = theMethod;
                                          break;
                                       }
                                    }
                                 }
                                 if (getSelector == null) {
                                    displayVerboseWarning("Warning: no getX method with multiple setX methods of incompatible types - invalid property: " + propName + " on class: ");
                                    //newMapper.setSetSelector(null);
                                 }
                              }
                              if (getSelector != null) {
                                 newMapper.setGetSelector(getSelector);
                                 Object propType = newMapper.getPropertyType();
                                 boolean newMatch = ModelUtil.isAssignableFrom(ptypes[0], propType);
                                 boolean oldMatch = ModelUtil.isAssignableFrom(oldptypes[0], propType);
                                 // Let the new one override if the old one is of a compatible type
                                 // since the old one could be inherited.
                                 if (newMatch && oldMatch)
                                    newMatch = ModelUtil.isAssignableFrom(oldptypes[0], ptypes[0]);

                                 if (newMatch)
                                    newMapper.setSetSelector(method);
                              }
                           }
                        }
                     }
                     else
                        newMapper.setSetSelector(method);
                     break;
                  case Get:
                  case Is:
                     newMapper.setGetSelector(method);
                     break;
                  case GetIndexed:
                     if (!(newMapper instanceof DynBeanIndexMapper)) {
                        newMapper = new DynBeanIndexMapper(newMapper);
                        cache.addProperty(propName, newMapper);
                     }
                     ((DynBeanIndexMapper) newMapper).getIndexMethod = method;
                     break;
                  case SetIndexed:
                     if (!(newMapper instanceof DynBeanIndexMapper)) {
                        newMapper = new DynBeanIndexMapper(newMapper);
                        cache.addProperty(propName, newMapper);
                     }
                     ((DynBeanIndexMapper) newMapper).setIndexMethod = method;
                     break;
                  case Validate:
                     newMapper.setValidateMethod(method);
                     break;
               }
            }
         }
      }
   }

   public Object getExtendsTypeDeclaration() {
      return null;
   }

   public Object[] getExtendsTypeDeclarations() {
      return null;
   }

   public Object getDeclaredExtendsTypeDeclaration() {
      return null;
   }

   /**
    * For a modify of an inner type which modifies an inner type of a base-type of the enclosing type (i.e. modifyInherited),
    * this returns the modified type, which is not the same type unlike a normal modify operation.  It's possible we should
    * return this as the extendsTypeDeclaration but for now you need to use this method to figure this out.
    */
   public Object getModifiedExtendsTypeDeclaration() {
      return null;
   }


   /**
    * By default, the extends type is the same in the model and compiled code.  But when you modify an inherited type
    * you actually do not extend that type but the compiled model will extend it.
    */
   public Object getCompiledExtendsTypeDeclaration() {
      return getExtendsTypeDeclaration();
   }

   /**
    * Returns the enclosing type only if it is not a static inner type - i.e. to determine if there's a "this" for the parent in the child
    */
   public BodyTypeDeclaration getEnclosingInstType() {
      BodyTypeDeclaration type = getEnclosingType();
      return type == null ? null : (isStaticType() ? null : type);
   }


   public String[] getObjChildrenNames(String scopeName, boolean componentsOnly, boolean thisClassOnly, boolean dynamicOnly) {
      int numChildren;
      StringBuilder childNames = new StringBuilder();
      Object[] children = null;
      Map<String, StringBuilder> childNamesByScope = new HashMap<String, StringBuilder>();
      // TODO: performance.  Should refactor addChildNames to first gather children, then turn them into
      // names.  For the dynamic case, we avoid all of that string allocation.
      numChildren = addChildNames(childNames, childNamesByScope, null, componentsOnly, thisClassOnly, dynamicOnly, new TreeSet<String>(), null);
      if (scopeName != null) {
         childNames = childNamesByScope.get(scopeName);
         if (childNames == null)
            numChildren = 0;
         else
            numChildren = 1;
      }
      if (numChildren != 0) {
         String[] childNamesArr = StringUtil.split(childNames.toString(), ',');
         for (int i = 0; i < childNamesArr.length; i++) {
            childNamesArr[i] = childNamesArr[i].trim();
         }
         return childNamesArr;
      }
      return null;
   }

   public Object[] getObjChildrenTypes(String scopeName, boolean componentsOnly, boolean thisClassOnly, boolean dynamicOnly) {
      String[] objNames = getObjChildrenNames(scopeName, componentsOnly, thisClassOnly, dynamicOnly);
      if (objNames == null)
         return null;
      Object[] typeObjs = new Object[objNames.length];
      int i = 0;
      for (String name : objNames) {
         typeObjs[i++] = getInnerType(name, null);
      }
      return typeObjs;
   }

   private Object definesComponentMethod(String name) {
      Object res = definesMethod(name, null, null, this, false, false, null, null);
      if (res == null)
         res = definesMethod("_" + name, null, null, this, false, false, null, null); // Check the Alt version _preInit, _init, etc.
      return res;
   }

   public Object[] getObjChildren(Object inst, String scopeName, boolean componentsOnly, boolean thisClassOnly, boolean dynamicOnly) {
      String[] childNames = getObjChildrenNames(scopeName, componentsOnly, thisClassOnly, dynamicOnly);
      if (childNames == null)
         return null;
      Object[] children = new Object[childNames.length];
      int i = 0;
      for (String childName : childNames) {
         // getProperty(int) version so we don't have so many methods.
         // Passing getField as true here so we just return an existing instance. If it's null, we need to check if the inner type
         // is a dynamic type. If we were to call the compiled getX, it would skip a type we override with a "modify inherited" scheme
         // or just outright replaced in the dynamic world. This also lets us do the proper component initialization sequence even if it's a compiled type
         // first it will call getX(false) to create the object, then we can set all of the properties and do the preInit, then init, etc for all referenced types
         // rather than doing them all one at a time.
         children[i] = DynUtil.getProperty(inst, childName, true);
         // If we call the super.get method and it returns null, this must be an object def which overrides a compiled
         // property.  In this case, we should have a TypeDeclaration and can then set it.
         if (children[i] == null) {
            Object type = getInnerType(childName, null);
            if (type instanceof BodyTypeDeclaration) {
               BodyTypeDeclaration td = (BodyTypeDeclaration) type;
               if (!td.isDynamicType()) {
                  children[i] = DynUtil.getProperty(inst, childName, false); // Now call the 'getX' method to create the object because we don't have it defined as a dynamic type
               }
               if (children[i] == null) {
                  children[i] = td.initLazyInnerObject(inst, (BodyTypeDeclaration) type, -1, true);
                  IBeanMapper mapper = getPropertyMapping(childName);
                  // TODO: is there something we need to set here on the dyn object?
                  // When we have compiled an inner type which is an independent stub, there's no property to set for the outer object
                  if (mapper != null && mapper.isWritable())
                     DynUtil.setProperty(inst, childName, children[i]);
               }
            }
            else
               children[i] = DynUtil.getProperty(inst, childName, false); // Now call the 'getX' method to create the object because we don't have it defined as a dynamic type
         }
         i++;
      }
      return children;
   }

   private void initServerTag(Object inst) {
      // This is for the dynamic type equivalent of the addMixinProperties which are now done during the transform phase.
      if (inst instanceof Element && isServerTagType())
         ((Element) inst).serverTag = true;
   }

   public void initDynComponent(Object inst, ExecutionContext ctx, boolean doInit, Object outerObj, Object[] params, boolean initSyncInst) {
      IDynChildManager mgr = getDynChildManager();
      int numChildren = 0;
      Object[] children = null;
      boolean isComponent = ModelUtil.isComponentType(this);
      Object rtType = null;

      // TODO: Right now we are doing this here even though this prevents us from getting the constructor args.
      // Either put the postAssignments into the dynamic stub or change the generated code to pass the constructor
      // args through so we can do it here at runtime.
      if (initSyncInst)
         initDynSyncInst(inst, params);

      if (isComponent || mgr != null) {
         ctx.pushCurrentObject(inst);
         try {
            if (isComponent) {
               // getting the runtime type here - if this class extends a compiled class, the type decl won't have the
               // method but the runtime type will
               rtType = getCompiledClass();
               Object preInit = definesComponentMethod("preInit");

               if (preInit == null)
                  preInit = ModelUtil.definesComponentMethod(rtType, "preInit", this);
               if (preInit != null) {
                  preInit = ModelUtil.getRuntimeMethod(preInit);
                  try {
                     ModelUtil.invokeMethod(inst, preInit, null, ctx);
                  }
                  catch (RuntimeException exc) {
                     System.err.println("Error in preInit method: " + preInit + " for component: " + inst);
                     throw exc;
                  }
               }

               initDynStatements(inst, ctx, TypeDeclaration.InitStatementMode.RefsOnly, true);
            }

            if (mgr != null && mgr.getInitChildrenOnCreate()) {
               // Need to collect all of the children here, not just dynamic.  Right now, there's no modularization of
               // the code which iterates over the children - it's in both new and get and so not inheritable.
               children = getObjChildren(inst, null, false, false, false);
               numChildren = children == null ? 0 : children.length;
            }

            if (isComponent && doInit) {
               Object[] compChildren = null;
               if (numChildren != 0) {
                  compChildren = getObjChildren(inst, null, true, true, true);
                  if (compChildren != null) {
                     for (int i = 0; i < compChildren.length; i++) {
                        Object compChild = compChildren[i];
                        Object initMethod = ModelUtil.definesComponentMethod(compChild, "init", this);
                        if (initMethod == null) {
                           Object childRtType = ModelUtil.getCompiledClass(DynUtil.getType(compChild));
                           initMethod = ModelUtil.definesComponentMethod(childRtType, "init", this);
                        }
                        if (initMethod != null) {
                           initMethod = ModelUtil.getRuntimeMethod(initMethod);
                           try {
                              ModelUtil.invokeMethod(compChild, initMethod, emptyObjectArray, ctx);
                           }
                           catch (RuntimeException exc) {
                              System.err.println("Error in child init method: " + initMethod + " for component: " + inst + " child: " + compChild);
                              throw exc;
                           }
                        }
                     }
                  }
               }

               Object initMethod = definesMethod("init", null, null, this, false, false, null, null);
               if (initMethod == null)
                  initMethod = ModelUtil.definesMethod(rtType, "init", null, null, this, false, false, null, null, getLayeredSystem());
               if (initMethod != null) {
                  initMethod = ModelUtil.getRuntimeMethod(initMethod);
                  try {
                     ModelUtil.invokeMethod(inst, initMethod, emptyObjectArray, ctx);
                  }
                  catch (RuntimeException exc) {
                     System.err.println("Error in init method: " + initMethod + " for component: " + inst);
                     throw exc;
                  }
               }

               if (compChildren != null) {
                  for (int i = 0; i < compChildren.length; i++) {
                     Object compChild = compChildren[i];
                     Object startMethod = ModelUtil.definesComponentMethod(compChild, "start", this);
                     if (startMethod == null) {
                        Object childRtType = ModelUtil.getCompiledClass(DynUtil.getType(compChild));
                        startMethod = ModelUtil.definesComponentMethod(childRtType, "start", this);
                     }
                     if (startMethod != null) {
                        startMethod = ModelUtil.getRuntimeMethod(startMethod);
                        ModelUtil.invokeMethod(compChild, startMethod, emptyObjectArray, ctx);
                     }
                  }
               }

               Object startMethod = definesComponentMethod("start");
               if (startMethod == null)
                  startMethod = ModelUtil.definesComponentMethod(rtType, "start", this);
               if (startMethod != null) {
                  startMethod = ModelUtil.getRuntimeMethod(startMethod);
                  ModelUtil.invokeMethod(inst, startMethod, emptyObjectArray, ctx);
               }
            }
         }
         finally {
            ctx.popCurrentObject();
         }
      }

      if (numChildren != 0) {
         mgr.initChildren(inst, children);
      }

      // Need to set the object property after things have been initialized.  This is where it should be set according
      // to the compiled contract.  Currently the dynamic contract deviates by setting the field right after construction
      if (objectSetProperty && outerObj != null) {
         DynUtil.setProperty(outerObj, typeName, inst, true);
      }

   }

   public Object initLazyDynProperty(IDynObject obj, int dynIndex, boolean isInstance) {
      Object o = mapIndexToObject(dynIndex, isInstance);
      if (o instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration td = (BodyTypeDeclaration) o;
         return initLazyInnerObject(obj, td, dynIndex, isInstance);
      }
      System.err.println("*** Initializing unrecognized type of property ");
      return null;
   }

   private Object initLazyInnerObject(Object obj, BodyTypeDeclaration innerType, int dynIndex, boolean isInstance) {
      // Because we don't update references stored in non-lazily initted references during the update operation,
      // we have to do it lazily here.
      innerType = innerType.resolve(true);
      ExecutionContext ctx = new ExecutionContext(getJavaModel());
      if (isInstance)
         ctx.pushCurrentObject(obj);
      else
         ctx.pushStaticFrame(this);

      try {
         Object inst = innerType.createInstance(ctx, null, null, this, obj, dynIndex);

         Object overrideDef = innerType.getCompiledObjectOverrideDef();
         // Note: this may already have been set from initOuterInstance slot.  That only runs though when there's no
         // dynamic property.  We need this to run anyway.  In the compiled version we set this property after the
         // component has been started so probably that call should just be removed?
         if (overrideDef != null) {
            if (obj != null)
               TypeUtil.setProperty(obj, overrideDef, inst);
            else
               TypeUtil.setStaticValue(getCompiledClass(), overrideDef, inst);
         }
         return inst;
      }
      finally {
         if (isInstance)
            ctx.popCurrentObject();
         else
            ctx.popFrame();
      }
   }

   /**
    * Let's callers initialize the properties one at a time to deal with dependencies between properties.
    */
   public void initDynamicProperty(Object inst, String propertyName) {
      Object field = definesMember(propertyName, MemberType.PropertyAssignmentSet, null, null);
      if (field instanceof Statement) {
         ExecutionContext ctx = new ExecutionContext(getJavaModel());
         ctx.pushCurrentObject(inst);

         try {
            ((Statement) field).initDynStatement(inst, ctx, TypeDeclaration.InitStatementMode.All, true);
         }
         finally {
            ctx.popCurrentObject();
         }
      }
   }

   public void initDynamicInstance(Object inst) {
      ExecutionContext ctx = new ExecutionContext(getJavaModel());
      initDynInstance(inst, ctx, true, true, null, null, true, true);
   }

   public void constructDynamicInstance(Object inst, Object outerObj, ExecutionContext ctx, String constrSig, Object... params) {
      boolean newCtx = false;
      if (ctx == null) {
         ctx = new ExecutionContext(getJavaModel());
         newCtx = true;
      }
      // TODO: do we need something like this so we do not duplicate the super call?
      //ctx.setSkipCompiledSuper(true);
      Object conObj = getConstructorFromSignature(constrSig, true);
      if (conObj == null) {
         if (constrSig != null) {
            Object propConstr = ModelUtil.getPropagatedConstructor(getLayeredSystem(), this, this, getLayer());
            if (propConstr == null)
               throw new NoSuchMethodError("No dynamic constructor matching: " + constrSig);
            else
               conObj = propConstr;
         }
         else // most likely using the default constructor -  TODO: should we check and be sure this type has a default constructor as this might be illegal in Java
            return;
      }
      try {
         if (newCtx)
            ctx.pushCurrentObject(inst);
         if (conObj instanceof ConstructorDefinition)
            ((ConstructorDefinition) conObj).invoke(ctx, Arrays.asList(params));
         // else - if we inherited a native constructor, we must have already run it when we constructed the instance

         // If this type is marked as needing the live dynamic types feature, but is not a component type, we complete the dyn component here so the children are initialized (at least for wicket in dyn mode)
         /*
         if (ModelUtil.getLiveDynamicTypes(this) && !isComponentType()) {
            initDynInstance(inst, ctx, false, true, outerObj);
         }
         */
      }
      finally {
         if (newCtx)
            ctx.popCurrentObject();
      }
   }

   /*
   public void initDynamicInnerInstance(Object inst, Object outerObj) {
      ExecutionContext ctx = new ExecutionContext(getJavaModel());
      ctx.pushCurrentObject(outerObj);
      try {
         initDynInstance(inst, ctx, true, true, outerObj);
      }
      finally {
         ctx.popCurrentObject();
      }
   }
   */

   /**
    * This is called from code generated in the dynamic stub to complete the dynamic portion of the initialization on the instance provided
    */
   public void buildDynamicInstance(Object inst, Object outerObj, String constrSig, Object... params) {
      ExecutionContext ctx = new ExecutionContext(getJavaModel());
      try {
         ctx.pushCurrentObject(inst);
         // initialize the fields
         initDynInstance(inst, ctx, true, false, outerObj, params, true, true);
         // call the dynamic constructors if any
         constructDynamicInstance(inst, outerObj, ctx, constrSig, params);
         // initialize the component
         initDynInstance(inst, ctx, false, true, outerObj, params, true, false);
      }
      finally {
         ctx.popCurrentObject();
      }
   }

   public void initOuterInstanceSlot(Object inst, ExecutionContext ctx, Object outerObj) {
      // Needs to be here for when we init from external code
      if (!isLayerType && !isStarted())
         start();

      Object outerType = getEnclosingInstType();
      if (outerType != null) {
         if (outerObj == null) {
            int numOuterTypeLevels = ModelUtil.getNumInnerTypeLevels(outerType);
            outerObj = ctx.getCurrentObject();

            /*
            Object outerObjType = DynUtil.getType(outerObj);
            int numOuterObjLevels = ModelUtil.getNumInnerTypeLevels(outerObjType);
            while (numOuterObjLevels > numOuterTypeLevels) {
               outerObj = DynUtil.getOuterObject(outerObj);
               numOuterObjLevels--;
            }
            */
         }

         // Populate the outer object's "this" slot in the inner object
         if (outerObj != null) {
            if (inst instanceof IDynObject) {
               ((IDynObject) inst).setProperty(DynObject.OUTER_INSTANCE_SLOT, outerObj, true);
            }
            if (ModelUtil.getLiveDynamicTypes(this))
               DynUtil.addDynInnerInstance(getFullTypeName(), inst, outerObj);
         }

         /*
          * This needs to be done before we call init.  Before we were setting this after constructing the object manually and before we called initDynObject manually.
          * But when you have a newX method you need to call, you do not get the ability to intercept the code.  anyway, this way, we do more work at runtime but
          * identify this case and fill the slot in while doing the dynamic type initailization. */
         if (outerObj instanceof IDynObject && getDeclarationType() == DeclarationType.OBJECT && outerType instanceof TypeDeclaration) {
            TypeDeclaration outerTypeDecl = (TypeDeclaration) outerType;
            int index = outerTypeDecl.getDynInstPropertyIndex(typeName);
            if (index == -1) {
               index = outerTypeDecl.getDynStaticFieldIndex(typeName);
               // It's possible that we are inheriting from a compiled inner type, in which case, there is no outer field in the dynamic model
               if (index != -1) {
                  outerTypeDecl.setDynStaticField(index, inst);
               }
               else {
                  // We are inheriting from a compiled type but need to set the field here even in that case if possible.
                  // it will get set when the getX method returns but if we wait that long, we need to do the whole doInit process
                  // If we can set the field here, before we've initialized referenced types, we don't need that.
                  IBeanMapper mapper = outerTypeDecl.getPropertyMapping(typeName);
                  if (mapper != null && mapper.isWritable())
                     DynUtil.setProperty(outerObj, typeName, inst, true);
               }
            }
            else {
               ((IDynObject) outerObj).setProperty(index, inst, true);
            }
         }
      }
   }

   public boolean getLiveDynamicTypesAnnotation() {
      if (liveDynType)
         return true;
      Object setting = getCompilerSetting("liveDynamicTypes", true);
      // By default, only turn this on for components and objects so we are not tracking every class by default.  Using IObjChildren here to pick up Element and because it seems like another good way to find 'components' even that do not have @Component
      if (setting == null) {
         return isComponentType() || getDeclarationType() == DeclarationType.OBJECT || isAssignableFromClass(IObjChildren.class) || needsDBLiveDynamicTypes();
      }
      return ((Boolean) setting);
   }

   private boolean needsDBLiveDynamicTypes() {
      BaseTypeDescriptor dbTypeDesc = getDBTypeDescriptor();
      if (dbTypeDesc != null) {
         DBProvider dbProvider = DBProvider.getDBProviderForType(getLayeredSystem(), getLayer(), this);
         return dbProvider != null && dbProvider.needsLiveDynamicTypes;
      }
      return false;
   }

   public void initDynamicFields(Object inst, ExecutionContext ctx, boolean initExt) {
      // First pass - need to set primitive types to 0 to avoid nulls
      clearDynFields(inst, ctx, initExt);
      boolean isComponent = ModelUtil.isComponentType(this);

      // Register global objects here before initializing the fields so any resolveName calls made in there will find this instance and not try to create another one
      if (!isLayerType && getDeclarationType() == DeclarationType.OBJECT)
         registerGlobalObject(inst);

      if (isComponent) {
         initDynStatements(inst, ctx, TypeDeclaration.InitStatementMode.SimpleOnly, initExt);
      }
      else {
         // Now start running the code
         initDynStatements(inst, ctx, TypeDeclaration.InitStatementMode.All, initExt);
      }
   }

   public void initDynInstance(Object inst, ExecutionContext ctx, boolean initClassPhase, boolean completeObj, Object outerObj, Object[] params, boolean initExt, boolean pushInst) {
      LayeredSystem sys = getLayeredSystem();
      ClassLoader ctxLoader;
      ClassLoader sysLoader = sys.getSysClassLoader();
      // TODO: this is not very clean.  When running in an app like a servlet, which has a non-standard class loader, we need to use that class loader to
      // load application classes.
      if ((ctxLoader = Thread.currentThread().getContextClassLoader()) != sysLoader) {
         // In this case, the system loader has been updated so update the thread's class loader.
         if (ctxLoader instanceof TrackingClassLoader) {
            if (sys.getUseContextClassLoader())
               Thread.currentThread().setContextClassLoader(sysLoader);
         }
         else {
            sys.setAutoSystemClassLoader(ctxLoader);
         }
      }

      if (initClassPhase) {
         // Need to populate the object's field before we call initDynComponent to avoid the cycle.  The problematic case comes from
         // the code in the dyn stub template for dynCompiledObjects in the component case.  It now calls "createVirtual" which constructs and
         // initializes the component all in one step.  It needs to construct the instance, set the field, then init the component.  Instead, we
         // are setting the field here.
         if (getDeclarationType() == DeclarationType.OBJECT && completeObj && outerObj != null && isComponentType()) {
            DynUtil.setPropertyValue(outerObj, typeName, inst);
         }

         initOuterInstanceSlot(inst, ctx, outerObj);

         if (!isLayerType && getLiveDynamicTypesAnnotation()) {
            // Add this instance to the global table so we can do type -> inst mapping
            getLayeredSystem().addDynInstanceInternal(this.getFullTypeName(), inst, getLayer());
         }
      }

      if (pushInst)
         ctx.pushCurrentObject(inst);
      try {
         if (initClassPhase)
            initDynamicFields(inst, ctx, initExt);

         if (completeObj)
            initDynComponent(inst, ctx, true, outerObj, params, true);
      }
      finally {
         if (completeObj)
            ctx.popCurrentObject();
      }
   }

   public boolean getBoolCompilerSetting(String settingName, boolean inherited) {
      Boolean res = (Boolean) getCompilerSetting(settingName, inherited);
      return res != null && res;
   }

   public Object getCompilerSetting(String settingName, boolean inherited) {
      Object compilerSettings = inherited ? getInheritedAnnotation("sc.obj.CompilerSettings") : getAnnotation("sc.obj.CompilerSettings", true);
      if (compilerSettings != null) {
         return ModelUtil.getAnnotationValue(compilerSettings, settingName);
      }
      return null;
   }

   public String getDynChildManagerClassName() {
      if (dynChildManager == null) {
         Object compilerSettings = getInheritedAnnotation("sc.obj.CompilerSettings");
         if (compilerSettings != null) {
            String dynChildMgrClass = (String) ModelUtil.getAnnotationValue(compilerSettings, "dynChildManager");
            if (dynChildMgrClass != null && dynChildMgrClass.length() > 0) {
               return dynChildMgrClass;
            }
         }
         return null;
      }
      return ModelUtil.getTypeName(dynChildManager.getClass());
   }

   public static IDynChildManager getDynChildManager(LayeredSystem sys, JavaSemanticNode errNode, Object type, Layer refLayer, boolean layerResolve) {
      List<Object> compilerSettingsList = ModelUtil.getAllInheritedAnnotations(sys, type, "sc.obj.CompilerSettings", false, refLayer, layerResolve);
      IDynChildManager res = null;
      if (compilerSettingsList != null) {
         for (Object compilerSettings : compilerSettingsList) {
            String dynChildMgrClass = (String) ModelUtil.getAnnotationValue(compilerSettings, "dynChildManager");
            res = EMPTY_DYN_CHILD_MANAGER; // TODO: is this right?  what's the difference with returning null?
            if (dynChildMgrClass != null && dynChildMgrClass.length() > 0) {
               Class cl = sys.getCompiledClass(dynChildMgrClass);
               if (cl == null) {
                  errNode.displayTypeError("No CompilerSettings.dynChildManager class: ", dynChildMgrClass, " for type: ");
               }
               else if (!IDynChildManager.class.isAssignableFrom(cl))
                  errNode.displayTypeError("Invalid CompilerSettings.dynChildManager class: ", dynChildMgrClass, "  Should implement IDynChildManager interface. Type: ");
               else
                  res = (IDynChildManager) RTypeUtil.createInstance(cl);

               return res;
            }
         }
      }
      return null;
   }

   public IDynChildManager getDynChildManager() {
      if (dynChildManager == null) {
         dynChildManager = getDynChildManager(getLayeredSystem(), this, this, getLayer(), isLayerType || isLayerComponent());
      }
      return dynChildManager;
   }

   public IDynObjManager getDynObjManager() {
      if (dynObjManager == null) {
         Object compilerSettings = getInheritedAnnotation("sc.obj.CompilerSettings");
         if (compilerSettings != null) {
            String dynObjMgrClass = (String) ModelUtil.getAnnotationValue(compilerSettings, "dynObjManager");
            dynObjManager = EMPTY_DYN_OBJ_MANAGER;
            if (dynObjMgrClass != null && dynObjMgrClass.length() > 0) {
               Class cl = getLayeredSystem().getCompiledClass(dynObjMgrClass);
               if (cl == null) {
                  displayTypeError("No CompilerSettings.dynObjManager class: ", dynObjMgrClass, " for type: ");
               }
               else if (!IDynObjManager.class.isAssignableFrom(cl))
                  displayTypeError("Invalid CompilerSettings.dynObjManager class: ", dynObjMgrClass, "  Should implement IDynObjManager interface. Type: ");
               else
                  dynObjManager = (IDynObjManager) RTypeUtil.createInstance(cl);
            }
         }
      }
      return dynObjManager;
   }

   private final static IDynChildManager EMPTY_DYN_CHILD_MANAGER = new NoopDynChildManager();

   private final static IDynObjManager EMPTY_DYN_OBJ_MANAGER = new NoopDynObjManager();

   public Object constructInstFromArgs(SemanticNodeList<Expression> arguments, ExecutionContext ctx, boolean fromSuper, Object outerObj) {
      Object superType = getExtendsTypeDeclaration();
      Object superCon = superType == null ? null : ModelUtil.declaresConstructor(getLayeredSystem(), superType, arguments, null);
      BodyTypeDeclaration origPendingType = ctx.getPendingConstructor();
      Object origOuterObj = ctx.getPendingOuterObj();
      // If there is no dynamic call to super(x) we construct the pending object now.
      if (superCon == null || !ModelUtil.isDynamicType(superCon)) {
         ctx.setPendingConstructor(null); // processed this - so clear it out
         ctx.setPendingOuterObj(null);
         Object[] argValues = ModelUtil.constructorArgListToValues(superType == null ? this : superType, arguments, ctx, outerObj);
         return constructInstance(ctx, outerObj, argValues, false, true, true);
      }
      else { // We have a dynamic constructor
         ConstructorDefinition superConDef = (ConstructorDefinition) superCon;
         // If there's a chained super call, delegate the construction till that
         if (superConDef.callsSuper(true)) {
            Object[] argValues = ModelUtil.constructorArgListToValues(superType, arguments, ctx, outerObj);
            if (superType instanceof ParamTypeDeclaration)
               superType = ((ParamTypeDeclaration) superType).baseType;
            if (superType instanceof BodyTypeDeclaration) {
               BodyTypeDeclaration superTypeDecl = (BodyTypeDeclaration) superType;
               ctx.setPendingConstructor(superTypeDecl);
               if (superTypeDecl.getEnclosingInstType() != null)
                  ctx.setPendingOuterObj(outerObj);
               else
                  ctx.setPendingOuterObj(null);
            }
            superConDef.invoke(ctx, Arrays.asList(argValues));

            Object inst = ctx.getCurrentObject();
            if (inst == null)
               System.err.println("*** super failed to instantiate the object");
            else if (origPendingType != null)
               origPendingType.initDynInstance(inst, ctx, true, false, origOuterObj, argValues, false, false);

            return inst;
         }
         // Otherwise, we build it here before the constructor call
         else {
            ctx.setPendingConstructor(null);
            ctx.setPendingOuterObj(null);
            BodyTypeDeclaration constrType;
            Object constrOuterObj = outerObj;

            if (superType instanceof TypeDeclaration) {
               constrType = (TypeDeclaration) superType;
               if (outerObj != null && ModelUtil.getEnclosingInstType(superType) == null)
                  constrOuterObj = null;
            }
            else
               constrType = this;
            Object[] argValues = ModelUtil.constructorArgListToValues(superType, arguments, ctx, constrOuterObj);
            Object inst = constrType.constructInstance(ctx, constrOuterObj, argValues, fromSuper, true, true);
            boolean pushedThis = false;
            try {
               //if (inst != null) {
               //   ctx.pushCurrentObject(inst);
               //   pushedThis = true;
               //}
               superConDef.invoke(ctx, Arrays.asList(argValues));

               if (constrType != this) {
                  // If this is the first type with an outerObj, this needs to be set for this type before we run the instance code on this type
                  if (outerObj != null)
                     initOuterInstanceSlot(inst, ctx, outerObj);
                  if (inst == null)
                     System.out.println("*** null instance for init dynamic fields");
                  initDynamicFields(inst, ctx, false);
               }
               //if (inst != null)
               //   initDynInstance(inst, ctx, true, false, null, argValues);
            }
            finally {
               if (!fromSuper && pushedThis)
                  ctx.popCurrentObject();
            }
            return inst;
         }
      }
   }

   public void stopDynComponent(IDynObject inst) {
      // getting the runtime type here - if this class extends a compiled class, the type decl won't have the
      // method but the runtime type will
      Object rtType = getCompiledClass(false);
      Object stopMethod = definesComponentMethod("stop");

      if (stopMethod == null)
         stopMethod = ModelUtil.definesComponentMethod(rtType, "stop", this);
      if (stopMethod != null) {
         stopMethod = ModelUtil.getRuntimeMethod(stopMethod);
         ExecutionContext ctx = null;
         try {
            ctx = new ExecutionContext(getJavaModel());
            ctx.pushCurrentObject(inst);
            ModelUtil.invokeMethod(inst, stopMethod, null, ctx);
         }
         catch (RuntimeException exc) {
            System.err.println("Error in stop method: " + stopMethod + " for component: " + inst);
            throw exc;
         }
         finally {
            if (ctx != null)
               ctx.popCurrentObject();
         }
      }
   }

   public int getOuterInstCount() {
      BodyTypeDeclaration outer = getEnclosingInstType();
      int ct = 0;
      while (outer != null) {
         ct++;
         outer = outer.getEnclosingInstType();
      }
      return ct;
   }

   public boolean needsClassInit() {
      // We have not converted them yet but they'll always have class init of some kind
      if (isEnumeratedType())
         return true;

      List<Statement> initSts = getInitStatements(InitStatementsMode.Static, true, false);
      return initSts != null && initSts.size() > 0;
   }

   public void updateInstFromType(Object inst, ExecutionContext ctx, SyncManager.SyncContext syncCtx) {
      updateInstFromBody(inst, syncCtx, ctx, body);
      updateInstFromBody(inst, syncCtx, ctx, hiddenBody);
   }

   /* TODO: remove this - before we used to use an initial pass to first record the other side's values but that's now done with the thread local variable
   public void updatePreviousValues(Object inst, SyncManager.SyncContext syncCtx, ExecutionContext eCtx) {
      updatePreviousFromBody(inst, syncCtx, eCtx, body);
      updatePreviousFromBody(inst, syncCtx, eCtx, hiddenBody);
   }

   private void updatePreviousFromBody(Object inst, SyncManager.SyncContext syncCtx, ExecutionContext eCtx, SemanticNodeList<Statement> toUpdate) {
      if (toUpdate == null)
         return;
      for (Statement st:toUpdate) {
         if (st instanceof PropertyAssignment) {
            if (st.isStatic()) {
               System.err.println("*** not syncing static properties");
            }
            else {
               PropertyAssignment pa = (PropertyAssignment) st;
               if (pa.initializer != null) {
                  Object initVal = pa.initializer.eval(null, eCtx);
                  syncCtx.addPreviousValue(inst, pa.propertyName, initVal, true);
               }
            }
         }
      }
   }
   */

   private void updateInstFromBody(Object inst, SyncManager.SyncContext syncCtx, ExecutionContext ctx, SemanticNodeList<Statement> toUpdate) {
      if (toUpdate == null)
         return;
      for (Statement st : toUpdate) {
         if (st instanceof OverrideAssignment) {
            // TODO: check if there is an @Sync annotation here?  The client sends down: "override @Sync propName;" to fetch a property.
            OverrideAssignment ost = (OverrideAssignment) st;
            syncCtx.updateProperty(inst, ost.propertyName, false, true);
         }
         else if (st instanceof PropertyAssignment) {
            String propName = ((PropertyAssignment) st).propertyName;
            Object instType = DynUtil.getSType(inst);
            // Do a security check to be sure we're allowed to update this property
            if (syncCtx != null && ctx.allowSetProperty(instType, propName)) {
               if (st.isStatic()) {
                  updateStaticProperty(st, ctx);
               }
               else {
                  initInstance(st, inst, ctx, InitInstanceType.Init);
               }
            }
            else {
               System.err.println("Not allowed to set unsynchronized property from scn: " + DynUtil.getTypeName(instType, true) + "." + propName);
            }
         }
         else if (st instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration td = (BodyTypeDeclaration) st;
            // Get type name here - pass it in as the outer instance for the enclosed object tag.

            td.updateRuntimeType(ctx, syncCtx, inst);
         }
         else if (st instanceof FieldDefinition) {
            FieldDefinition field = (FieldDefinition) st;
            SyncManager.SyncState oldState = null;
            if (syncCtx != null)
               oldState = SyncManager.setSyncState(SyncManager.SyncState.RecordingChanges);
            field.updateRuntimeType(inst, syncCtx, ctx);
            if (syncCtx != null)
               SyncManager.setSyncState(oldState);

         }
         else if (st instanceof BlockStatement) {
            BlockStatement bs = (BlockStatement) st;

            if (bs.staticEnabled)
               System.err.println("*** Static block statements not implemented in updateInstFromBody");
            else
               bs.execForObj(inst, ctx);
         }
         else {
            // TODO: implement sub-types, methods etc.
            System.err.println("*** Warning - unimplemented update in updateInstFromBody");
         }
      }
   }

   public static class NoopDynChildManager implements IDynChildManager {
      @Override
      public boolean getInitChildrenOnCreate() {
         return true;
      }

      public void initChildren(Object parent, Object[] children) {
      }

      public void addChild(Object parent, Object child) {
      }

      public void addChild(int ix, Object parent, Object child) {
      }

      public boolean removeChild(Object parent, Object child) {
         return false;
      }

      public Object[] getChildren(Object parent) {
         return emptyObjectArray;
      }
   }

   public static class NoopDynObjManager implements IDynObjManager {
      public Object createInstance(Object type, Object parentInst, Object[] args) {
         return null;
      }
   }

   public boolean isDynamicStub(boolean includeExtends) {
      if (isLayerType || getCompiledOnly() || isHiddenType())
         return false;
      if (needsDynamicStub)
         return true;
      Object extendsType = getCompiledExtendsTypeDeclaration();
      if (extendsType == null) {
         extendsType = getCompiledImplements();
         if (extendsType == null)
            return isDynamicType() && needsCompiledClass;
      }
      if (dynamicNew && !needsCompiledClass)
         return false;
      // The extends type may either be a dynamic type, or a compiled type which implements the IDynObject interface.
      if (isExtendsDynamicType(extendsType)) {
         return includeExtends ? ModelUtil.isDynamicStub(extendsType, true) : needsCompiledClass;
      }
      // If we're dynamic and our extends type is not, we need a stub to encapsulate the functionality of the
      // the extends class.
      else
         return isDynamicNew();
   }

   private boolean needsDynamicStubForExtends() {
      if (isLayerType)
         return false;
      Object extendsType = getCompiledExtendsTypeDeclaration();
      if (extendsType == null) {
         extendsType = getCompiledImplements();
         if (extendsType == null)
            return false;
      }
      if (isExtendsDynamicType(extendsType)) { // Extending a dynamic type - just use that guys class for this one
         return false;
      }
      // If we're dynamic and our extends type is not, we need a stub to encapsulate the functionality of the
      // the extends class.
      else
         return isDynamicNew();
   }

   public boolean isDynInnerStub() {
      return getEnclosingType() != null && isDynamicStub(true);
   }

   public String getInnerStubFullTypeName() {
      String stubTypeName = getInnerStubTypeName();
      if (isLayerComponent())
         return CTypeUtil.prefixPath(LayerConstants.LAYER_COMPONENT_PACKAGE, stubTypeName);
      return CTypeUtil.prefixPath(getJavaModel().getPackagePrefix(), stubTypeName);
   }

   public String getInnerStubTypeName() {
      // If we're not dynamic, must be the extends class
      if (!isDynamicStub(false)) {
         Object extTypeObj = getCompiledExtendsTypeDeclaration();
         if (extTypeObj instanceof Class) {
            return ModelUtil.getTypeName(extTypeObj);
         }
         BodyTypeDeclaration extType = (BodyTypeDeclaration) extTypeObj;
         return extType.getCompiledClassName();
      }
      return getInnerStubTypeNameInternal();
   }

   private String getInnerStubTypeNameInternal() {
      BodyTypeDeclaration encl = getEnclosingType();
      if (encl == null)
         throw new IllegalArgumentException("Not an inner stub!");

      TypeDeclaration enclEncl = encl.getEnclosingType();
      String base = encl.isLayerType ? LayerConstants.LAYER_COMPONENT_TYPE_NAME : enclEncl == null ? encl.typeName : encl.getInnerStubTypeNameInternal();
      // This component of the pathname needs to have the normal part of the type name because it extends a compiled
      // inner type.
      if (getNeedsDynInnerStub())
         return base + "." + typeName;
      else {
         String res = base.replace(".", INNER_STUB_SEPARATOR) + INNER_STUB_SEPARATOR + typeName;
         if (res.length() > 230) {
            StringBuilder newRes = new StringBuilder();
            int ix = res.indexOf(INNER_STUB_SEPARATOR);
            if (ix == -1)
               return res;
            newRes.append(res.substring(0, ix));
            newRes.append(INNER_STUB_SEPARATOR);
            newRes.append("x");
            String hashStr = DBUtil.hashString(null, res, true);
            hashStr = hashStr.replace("-", "_x");
            newRes.append(hashStr);
            newRes.append(INNER_STUB_SEPARATOR);
            newRes.append(typeName);
            if (newRes.length() > 255) {
               System.err.println("*** Inner stub type name too long!");
            }
            return newRes.toString();
         }
         return res;
      }
   }

   public String getInnerStubFileName() {
      return getInnerStubTypeName() + ".java";
   }

   public boolean getSuperIsDynamicStub() {
      Object extType = getCompiledExtendsTypeDeclaration();
      if (extType instanceof ITypeDeclaration) {
         ITypeDeclaration extTypeDecl = (ITypeDeclaration) extType;
         if (extTypeDecl.isDynamicStub(true)) {
            return true;
         }
      }
      if (extType != null && ModelUtil.isDynamicStub(extType, true)) {
         return true;
      }
      return false;
   }

   /**
    * Is there any IDynObject implementation, even if not the immediate super type this method returns true
    */
   public boolean getExtendsDynamicStub() {
      Object extType = getCompiledExtendsTypeDeclaration();
      return getSuperIsDynamicStub() || extType != null && ModelUtil.isAssignableFrom(IDynObject.class, extType);
   }

   /**
    * If we create a dynamic type at runtime, we may need a new stub for that type. We can generate and compile
    * without requiring a restart as long as there is no old definition.
    */
   public void compileDynamicStub(boolean doCompile, boolean genStub) {
      //if (!validated)
      //   ParseUtil.validateComponent(this);
      if (stubCompiled)
         return;

      if (stubGenerated && !doCompile)
         return;

      boolean doGen = !stubGenerated;

      if (doCompile) {
         // Mark that we've done this.  Because we assume that we'll load this class so there's no point in clearing this flag... we need to
         // restart anyway.
         stubCompiled = true;
      }
      else {
         stubGenerated = true;
      }

      // getCompiledClass is done on the modifyTypeDecl but we need to do this on the most specific type
      if (replacedByType != null) {
         replacedByType.compileDynamicStub(doCompile, genStub);
         return;
      }

      // If this type extends another type which needs a dynamic stub, we'll need to generate it no matter what
      // If this subtype itself then does not need a compile stub, just return.

      Object extJavaType = getExtendsType();
      if (extJavaType instanceof ClassType) {
         ((ClassType) extJavaType).compileTypeArgs();
      }

      // Need to iterate over both the modified type and the extends type - if there are both.
      int pass = 0;
      boolean extStubGenerated = false;
      Object derivedType = null;
      do {
         Object extType = pass == 0 ? getDerivedTypeDeclaration() : getExtendsTypeDeclaration();
         if (pass == 0)
            derivedType = extType;
         else if (derivedType == extType)
            break;

         if (extType instanceof ParamTypeDeclaration)
            extType = ((ParamTypeDeclaration) extType).getBaseType();
         if (extType instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration extTypeDecl = (BodyTypeDeclaration) extType;
            if (extTypeDecl.isDynamicStub(true)) {

               // We need to compile all of the dynamic stubs in the chain.  But do not compile those which are the
               // same type as a previous modify unless it's a modify inherited.
               boolean genExt = !(this instanceof ModifyDeclaration) || ((ModifyDeclaration) this).modifyInherited;
               extTypeDecl.compileDynamicStub(doCompile, genExt);

               extStubGenerated = true;
            }
         }
         pass++;
      } while (pass != 2);

      if (extStubGenerated) {
         // If we need a compiled class we need to generate a dynamic stub for this type.
         // otherwise just return.
         if (!needsDynamicStub && !needsCompiledClass) {
            return;
         }
      }

      // Compiled as part of the parent type without the INNER STUB changes
      if (needsDynInnerStub) {
         BodyTypeDeclaration enclType = getEnclosingType();
         if (enclType != null) {
            enclType.compileDynamicStub(doCompile, genStub);
            return;
         }
      }

      if (!genStub)
         return;

      if (!isDynamicStub(false))
         return;

      LayeredSystem sys = getLayeredSystem();
      Layer thisLayer = getLayer();
      Layer lyr = thisLayer.origBuildLayer;
      if (lyr == null)
         lyr = sys.buildLayer;
      // Must be starting up the layers themselves - fall back to the
      if (lyr == null)
         lyr = sys.getCoreBuildLayer();
      String bd = thisLayer.buildClassesDir;
      if (bd == null)
         bd = sys.buildClassesDir;
      if (bd == null) {
         bd = sys.coreBuildLayer.buildClassesDir;
      }

      String stubRelName = getSrcIndexName();
      String buildSrcDir = thisLayer.sysBuildSrcDir;
      // Sometimes this is not set - like for temporary layers that haven't been built yet.
      if (buildSrcDir == null)
         buildSrcDir = sys.buildSrcDir;
      if (buildSrcDir == null)
         buildSrcDir = sys.getCoreBuildLayer().buildSrcDir;
      String newFile = FileUtil.concat(buildSrcDir, stubRelName);

      if (doGen) {
         File stubFile = new File(newFile);
         if (stubFile.canRead()) {
            long stubLastModified = stubFile.lastModified();
            if (stubLastModified > getJavaModel().getLastModifiedTime() && (stubLastModified > sys.sysStartTime || !sys.options.buildAllFiles))
               doGen = false;
         }
      }

      String stubResult = doGen ? generateDynamicStub(false) : "";
      byte[] hash = StringUtil.computeHash(stubResult);
      List<SrcEntry> toCompileEnts = new ArrayList<SrcEntry>();

      // Need to generate the inner stubs before we compile the outer one since it will depend on the inner ones.
      List<SrcEntry> innerStubs = doGen ? getInnerDynStubs(bd, lyr, true) : null;
      if (innerStubs != null) {
         for (SrcEntry innerStub : innerStubs) {
            lyr.addSrcFileIndex(innerStub.relFileName, innerStub.hash, innerStub.getExtension(), innerStub.absFileName);
         }
      }
      /*
       * We compile these lazily as requested.  In order to compile them eagerly, we'd have to gather all of them up
       * include those in extends classes (at least0.
      if (innerStubs != null) {
         for (SrcEntry innerEnt:innerStubs)
            toCompileEnts.add(innerEnt);
      }
      */

      SrcIndexEntry srcIndex = lyr.getSrcFileIndex(stubRelName);
      SrcIndexEntry prevIndex = lyr.getPrevSrcFileIndex(stubRelName);

      boolean neededInLayer = prevIndex == null || !Arrays.equals(hash, prevIndex.hash);
      if (neededInLayer) {
         File srcFile = new File(newFile);
         SrcEntry srcEnt = new SrcEntry(lyr, newFile, stubRelName);

         if (doGen && (srcIndex == null || !Arrays.equals(srcIndex.hash, hash) || !(srcFile.canRead()))) {
            FileUtil.saveStringAsReadOnlyFile(newFile, stubResult, true);

            toCompileEnts.add(srcEnt);

            lyr.addSrcFileIndex(stubRelName, hash, null, newFile);

            // This happens lazily as needed from the system... this is not super fast but we need to do it to record that we generated the java file
            // perhaps in a time-delayed thread in case we are doing lots for the same layer?
            lyr.saveBuildSrcIndex();
         }
         else {
            // Class file is buildDir + relDirPath + srcFileName with ".class" on it
            String classFileName = FileUtil.concat(lyr.getBuildClassesDir(), FileUtil.replaceExtension(stubRelName, "class"));
            File classFile = new File(classFileName);

            long srcLastModified = srcFile.lastModified();
            boolean compiling = false;
            // Check to be sure the stub's compiled class file is there and not more recent than the stub itself.  If so, add it to the toCompiledEnts list.
            if (!classFile.exists() || classFile.lastModified() < srcFile.lastModified()) {
               toCompileEnts.add(srcEnt);
               compiling = true;
            }
            JavaModel model = getJavaModel();
            // If the stub's last modified time is earlier than the source file, we need to update the stub and stub classes last modified time
            // so we don't re-process the file next time.  It's pretty common that the stubs don't change when the src is changed.
            if (srcLastModified < model.getSrcFile().getLastModified()) {
               FileUtil.touchFile(newFile);
               FileUtil.touchFile(classFileName);
            }
         }
      }
      else {
         LayerUtil.removeInheritFile(newFile);
         LayerUtil.removeFileAndClasses(newFile);
         if (!bd.equals(buildSrcDir)) {
            String newBuildFile = FileUtil.concat(bd, stubRelName);
            LayerUtil.removeFileAndClasses(newBuildFile);
         }
      }

      if (toCompileEnts.size() > 0) {
         // Yes, this should be srcEnt.typeName but stubRelName here is relative to build not the layer so that doesn't work
         sys.flushClassCache(FileUtil.removeExtension(stubRelName).replace("/", "."));
         String cp = sys.getClassPathForLayer(lyr, true, bd, true);
         if (sys.options.verbose)
            System.out.println("Compiling: " + toCompileEnts + " into build dir: " + LayerUtil.cleanBuildVersion(sys, bd) + " with classpath: " + LayerUtil.cleanBuildVersion(sys, cp));
         else if (sys.options.info && !sys.options.testVerifyMode)
            System.out.println("Compiling: " + toCompileEnts.size() + " stub files");
         HashSet<String> errorFiles = new HashSet<String>();
         if (doCompile && LayerUtil.compileJavaFilesInternal(toCompileEnts, bd, cp,
                 sys.options.debug, sys.options.javaSrcVersion, sys.options.javaTargetVersion,
                 sys.messageHandler, errorFiles) != 0) {
            displayError("Failed compile step for dynamic type: " + getFullTypeName() + " for ");
         }
      }
   }

   String getSrcIndexName() {
      String stubBaseName;
      if (getNeedsDynInnerStub())
         stubBaseName = getTypeName() + ".java";
      else
         stubBaseName = getEnclosingType() != null ? getInnerStubFileName() : (getTypeName() + ".java");
      JavaModel model = getJavaModel();
      if (model.isLayerModel)
         return FileUtil.concat(LayerConstants.LAYER_COMPONENT_PACKAGE.replace(".", FileUtil.FILE_SEPARATOR), stubBaseName);
      return FileUtil.concat(getJavaModel().getPackagePrefixDir(), stubBaseName);
   }

   /**
    * Returns all standalone inner type stubs.  It will not return the stubs for inner types which are real inner types in the main class
    */
   List<SrcEntry> getInnerDynStubs(String buildDir, Layer buildLayer, boolean generate) {
      List<Object> myIts = getAllInnerTypes(null, true, false);
      if (myIts == null)
         return null;

      JavaModel model = getJavaModel();
      LayeredSystem sys = model.getLayeredSystem();

      List<SrcEntry> resEnt = null;
      for (int i = 0; i < myIts.size(); i++) {
         Object innerType = myIts.get(i);
         if (innerType instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration td = (BodyTypeDeclaration) innerType;
            if (ModelUtil.isDynamicType(innerType)) {
               buildLayer.markDynamicType(td.getFullTypeName());

               if (td.isDynamicStub(false) && !td.getNeedsDynInnerStub()) {
                  if (resEnt == null)
                     resEnt = new ArrayList<SrcEntry>();

                  // If this is an anonymous type declaration, only process it for the file belonging to the same type (in case we inherited the anon type through extends)
                  if (td instanceof AnonClassDeclaration && !ModelUtil.sameTypes(td.getJavaModel().getModelTypeDeclaration(), model.getModelTypeDeclaration()))
                     continue;

                  String stubResult = td.generateDynamicStub(true);
                  String stubBaseName = td.getInnerStubFileName();
                  String stubRelName = FileUtil.concat(model.getPackagePrefixDir(), stubBaseName);
                  String newFile = FileUtil.concat(buildDir, stubRelName);

                  // The layered system processes hidden layer files backwards.  So generate will be true the for the
                  // final layer's objects but an overridden component comes in afterwards... don't overwrite the new file
                  // with the previous one.  We really don't need to transform this but I think it is moot because it will
                  // have been transformed anyway.
                  SrcEntry resultEnt = new SrcEntry(buildLayer, newFile, stubRelName);
                  boolean add = true;
                  if (generate) {
                     File srcFile = new File(newFile);
                     resultEnt.hash = StringUtil.computeHash(stubResult);
                     SrcIndexEntry prevStub = sys.getPrevSrcIndex(buildLayer, resultEnt);
                     SrcIndexEntry thisStub = buildLayer.getSrcFileIndex(resultEnt.relFileName);
                     File classFile = new File(FileUtil.concat(buildLayer.getBuildClassesDir(), FileUtil.replaceExtension(stubRelName, "class")));
                     if (prevStub == null || !Arrays.equals(prevStub.hash, resultEnt.hash)) {
                        if (thisStub == null || !Arrays.equals(thisStub.hash, resultEnt.hash) || !srcFile.canRead()) {
                           FileUtil.saveStringAsFile(newFile, stubResult, true);

                           // The srcIndex is added in generateCode when this gets returned from getProcessedFiles or from the getInnerStubs call
                           //buildLayer.addSrcFileIndex(resultEnt.relFileName, resultEnt.hash, resultEnt.getExtension(), newFile);
                        }
                        // Stub Src file is up-to-date but if it's last modified is before the src file it will
                        // trigger re-processing next time so we need to advance it's LMT.  Same with the class file.
                        else {
                           long srcLastModified = srcFile.lastModified();
                           boolean touchClassFile = classFile.lastModified() >= srcLastModified;
                           // If the stub's last modified time is earlier than the source file, we need to update the stub and stub classes last modified time
                           // so we don't re-process the file next time.  It's pretty common that the stubs don't change when the src is changed.

                           // Always doing this for now... would need to check all src files that were merged into this type
                           //if (srcFile.lastModified() < new File(model.getSrcFile().absFileName).lastModified()) {
                           FileUtil.touchFile(newFile);
                           if (touchClassFile)
                              FileUtil.touchFile(classFile.getPath());
                           //}
                        }
                     }
                     else {
                        // Class file is buildDir + relDirPath + srcFileName with ".class" on it
                        // Check to be sure the stub's compiled class file is there and not more recent than the stub itself.  If so, add it to the toCompiledEnts list.
                        if (!classFile.exists() || classFile.lastModified() < srcFile.lastModified()) {
                           // The class file is out of date - we just need to add it so we compile this file in the getProcessedFiles case.  For compileDyntub, the first access to the
                           // inner class will compile it.
                        }
                        else {
                           LayerUtil.removeInheritFile(newFile);
                           LayerUtil.removeFileAndClasses(newFile);
                           add = false;
                        }
                     }
                     // This is done in LayeredSystem
                     //buildSrcLayer.addSrcFileIndex(resultEnt.relFileName, resultEnt.hash);
                  }
                  if (add)
                     resEnt.add(resultEnt);

                  // Mark this as compiled so we don't do it again explicitly in compileDynamicStub.  In some cases, we do
                  // the dependency check and do not compile it however.

                  // TODO: turn this on but only when we are called from getProcessedFiles and ensure all stubs will be compiled
                  //td.stubCompiled = true;
               }
            }

            // Need to do this even if the parent type is not dynamic - modified types can make a child be dynamic even when
            // the parent is not.
            List<SrcEntry> childInners = td.getInnerDynStubs(buildDir, buildLayer, generate);
            if (childInners != null) {
               if (resEnt == null)
                  resEnt = new ArrayList<SrcEntry>();
               resEnt.addAll(childInners);
            }
         }
      }
      return resEnt;
   }

   /**
    * Returns the set of instance fields we inherit from interfaces we implement - i.e. as part of multiple inheritance
    * support.  These are the fields to be compiled into this type as properties.
    */
   public List<Object> getCompiledIFields() {
      return Collections.emptyList();
   }

   /**
    * Returns the subset of the compiledIFields which are implemented as dynamic fields in this type
    */
   public List<Object> getDynCompiledIFields() {
      List<Object> fields = getCompiledIFields();

      if (fields == null)
         return null;

      int sz = fields.size();

      ArrayList<Object> res = new ArrayList<Object>(sz);

      for (int i = 0; i < sz; i++) {
         Object fieldObj = fields.get(i);
         if (fieldObj instanceof PropertyAssignment)
            fieldObj = ((PropertyAssignment) fieldObj).getAssignedProperty();

         String propName = ModelUtil.getPropertyName(fieldObj);

         // Skip compiled properties here - just need those which are dynamic
         if (isCompiledProperty(propName, false, false)) {
            continue;
         }

         res.add(fieldObj);
      }
      return res;
   }

   /** These are methods that are defined as dynamic but override a compiled method so need some type of stub to make the transition */
   public List<Object> getDynCompiledMethods() {
      List<Object> res = getAllMethods("static", false, true, true);
      List<Object> resCopy = null;
      // This will return any compiled abstract/interface methods we inherit from a compiled base class but we only need that method
      // as a dynCompiledMethod if there's no compiled implementation of that method defined for this class.
      if (res != null) {
         for (int i = 0; i < res.size(); i++) {
            Object resMeth = res.get(i);
            if (resMeth instanceof MethodDefinition) {
               MethodDefinition meth = (MethodDefinition) resMeth;
               if (meth.body == null) {
                  boolean processed = false;
                  List<Object> defMeths = getMethods(meth.name, null);
                  if (defMeths != null) {
                     for (Object defMeth:defMeths) {
                        if (defMeth == meth) {
                           continue;
                        }
                        if (!ModelUtil.isCompiledMethod(defMeth))
                           continue;
                        if (ModelUtil.hasModifier(defMeth, "abstract") || ModelUtil.isInterface(ModelUtil.getEnclosingType(defMeth)))
                           continue;
                        if (ModelUtil.parametersMatch(ModelUtil.getParameterTypes(defMeth), meth.getParameterTypes(false), false, getLayeredSystem())) {
                           if (resCopy == null) {
                              resCopy = new ArrayList<Object>(res);
                              res = resCopy;
                           }
                           res.remove(i);
                           i--;
                           processed = true;
                           break;
                        }
                     }
                  }

                  // Need to check if this is a getX or setX in an interface and there's a property that will override
                  // them to avoid a dynamic method trying to look up an existing compiled or dynamic property
                  if (!processed && meth.propertyName != null) {
                     Object prop = definesMember(meth.propertyName, MemberType.PropertyAnySet, null, null);
                     if (prop != null && prop != meth && !ModelUtil.isMethod(prop)) {
                        if (resCopy == null) {
                           resCopy = new ArrayList<Object>(res);
                           res = resCopy;
                        }
                        res.remove(i);
                        i--;
                     }
                  }
               }
            }

         }
      }
      return res;
   }

   transient private static String dynStubTemplateStr = "<%= emptyString(packageName) ? \"\" : \"package \" + packageName + \";\"%>\n\n" +
           "/**\n" +
           " * Generated 'stub class' for the dynamic type: <%= typeName %>.  Normally dynamic types are interpreted, requiring no generated file\n" +
           " * but this stub was generated for this type because it extends or overrides features of a compiled type.  The stub lets you\n" +
           " * add new fields, change methods or any feature of this type which is not exposed here in the stub.\n" +
           " */\n" +
           "<%= typeModifiers %>class <%= typeName %><%= typeParams%><% if (baseClassName != null) { %> extends <%= baseClassName %><% } %> implements sc.dyn.IDynObject<%=otherInterfaces%> {\n" +
           "<% if (!extendsDynamicStub) {%>" +
           "   protected sc.lang.DynObject dynObj;\n" +
           "<% } %>" +
           "<%" +
           "   DynConstructor[] constrs = getConstructors();\n" +
           // First do the constructors with the extra TypeDeclaration argument so any dynamic types on this type
           // can use this wrapper.  Do NOT call initDynamicInstance here as this constructor is used from
           // TypeDeclaration.createInstance which will do the init after the outer-instance has been set.
           "   for (DynConstructor constr:constrs) {\n" +
           "      String[] paramTypeNames = constr.getParamTypeNames(); " +
           "      String[] paramNames = constr.getParamNames(); " +
           "      String[] superArgNames = constr.getSuperArgNames(); %>" +
           "   <%= constrModifiers %><%= typeName%>(sc.lang.java.TypeDeclaration concreteType<%" +
           "      for (int s = 0; s < paramNames.length; s++) { " +
           "           %>, <%= paramTypeNames[s] %> <%= paramNames[s] %><% } %>) <%= constr.throwsClause %> {\n<%" +
           "        String superExpr = constr.superExpression;" +
           "        if (superExpr != null) { %>" +
           "           <%= superExpr %>" +
           "        <% }" +
           "        else if (constr.needsSuper) { %>" +
           "      super(<%" +
           "         int startIx = 0; " +
           "         if (superIsDynamicStub) { %>concreteType<% startIx = -1; }" +
           "         for (int pn = 0; pn < paramNames.length; pn++) { %><%=pn != startIx ? \",\" : \"\"%><%=superArgNames[pn]%><% } %>);\n<%" +
           "        } " +
           "        else if (superIsDynamicStub) { %>" +
           "     super(concreteType);\n" +
           "<%      } " +
           "        if (!superIsDynamicStub) { %>" +
           "      dynObj = new sc.lang.DynObject(concreteType);\n" +
           "<% } %>" +
           "   }\n\n" +
           "<% } %>" +
           // Some types however require an explicit class for each type.   For these types, we also
           // Define the regular constructors... these will explicitly call back into the TypeDeclaration to call the
           // correct dynamic constructor and finish the initialization of the instance.
           "<% if (needsCompiledClass) { " +
           "      DynConstructor[] constrs = getConstructors();\n" +
           "      for (DynConstructor constr:constrs) {\n" +
           "         String[] paramTypeNames = constr.getParamTypeNames(); " +
           "         String[] paramNames = constr.getParamNames(); " +
           "         String[] superArgNames = constr.getSuperArgNames(); %>" +
           "   <%= constrModifiers %><%= typeName%>(<%" +
           "      for (int s = 0; s < paramNames.length; s++) { " +
           "           %><%= s != 0 ? \", \" : \"\"%><%= paramTypeNames[s] %> <%= paramNames[s] %><% } %>) <%= constr.throwsClause %> {\n<%" +
           "         if (constr.needsSuper) {" +
           "            if (superIsDynamicStub) { %>" +
           "      super(sc.lang.DynObject.getType(\"<%=fullTypeName%>\")<%" +
           "            for (int pn = 0; pn < paramNames.length; pn++) { %>, <%=superArgNames[pn]%><% } %>);\n<%" +
           "            } " +
           "            else { %>" +
           "      super(<%" +
           "         for (int pn = 0; pn < paramNames.length; pn++) { %><%=pn != 0 ? \",\" : \"\"%><%=superArgNames[pn]%><% } %>);\n<%" +
           "            } " +
           "         } " +
           "         else if (superIsDynamicStub) { %>" +
           "      super(sc.lang.DynObject.getType(\"<%=fullTypeName%>\"));\n<%" +
           "          } %>" +
           "      sc.lang.java.TypeDeclaration _td = sc.lang.DynObject.getType(\"<%=fullTypeName%>\");\n<%" +
           "      if (!superIsDynamicStub) { %>" +
           "      dynObj = new sc.lang.DynObject(_td);\n<%" +
           "      } %>" +
           "      _td.constructDynamicInstance(this, null, null, <%= constr.forwardParams %>);\n" +
           "   }\n\n" +
           "<% } } %>" +
           // If the type is also a component, we need to provide the new X method with the type declaration argument.  We provide both
           // the inner and outer type methods (if this is an outer type) so that we can use this same stub if this type gets used later as
           // an inner type.  Otherwise, we'd have to re-generate to add the "this" parameter (or somehow pass that in).
           "<% if (typeIsComponentClass && !typeIsAbstract) { " +
           "    DynConstructor[] constrs = getConstructors(true);" +
           "    boolean repeated = false; " +
           "    do { " +
           "      for (DynConstructor constr:constrs) {" +
           "         String[] paramTypeNames = constr.getParamTypeNames(); " +
           "         String[] paramNames = constr.getParamNames(); %>" +
           "   <%= typeModifiers %><%= dynInnerInstType ? \"\" : \"static \"%><%= typeName %> new<%= upperClassName%>(<%= dynInnerInstType || repeated ? \"Object _outerObj, \" : \"\" %>sc.lang.java.TypeDeclaration _type<%" +
           "      for (int s = 0; s < paramNames.length; s++) { " +
           "           %>, <%= paramTypeNames[s] %> <%= paramNames[s] %><% } %>) <%= constr.throwsClause %> {\n" +
           "      <%= typeName %> _inst = new <%= typeName %>(_type<%" +
           "         for (int pn = 0; pn < paramNames.length; pn++) { %>, <%=paramNames[pn]%><% } %>);\n" +
           "<%       if (dynInnerInstType || repeated) { %>" +
           "      _type.buildDynamicInstance(_inst, _outerObj, <%= constr.forwardParams %>);\n" +
           "<%       } else { %>" +
           "      _type.buildDynamicInstance(_inst, null, <%= constr.forwardParams %>);\n" +
           "<%       } %>" +
           "      return _inst;\n" +
           "   }\n\n" +
           "<% } " +
           "    if (!dynInnerInstType && !repeated) {" +
           "        repeated = true;" +
           "     }" +
           "     else {" +
           "        repeated = false;" +
           "     }" +
           "   } while (repeated);" +
           "} %>" +
           // If the type is also a component, and this class is exposed as a type we need the newX method without the TypeDecl (hardcoding the type)
           "<% if (typeIsComponentClass && needsCompiledClass) { " +
           "      DynConstructor[] constrs = getConstructors(true);\n" +
           "      for (DynConstructor constr:constrs) {\n" +
           "         String[] paramTypeNames = constr.getParamTypeNames(); " +
           "         String[] paramNames = constr.getParamNames(); %>" +
           "   <%= dynInnerInstType ? \"\" : \"static \" %><%= typeModifiers %> <%= typeName %> new<%= upperClassName%>(<%" +
           "      for (int s = 0; s < paramNames.length; s++) { " +
           "           %><%= s != 0 ? \", \" : \"\"%><%= paramTypeNames[s] %> <%= paramNames[s] %><% } %>) <%= constr.throwsClause %> {\n" +
           "      sc.lang.java.TypeDeclaration _type = sc.lang.DynObject.getType(\"<%=fullTypeName%>\");\n" +
           "      <%= typeName %> _inst = new <%= typeName %>(_type<%" +
           "         for (int pn = 0; pn < paramNames.length; pn++) { %>, <%=paramNames[pn]%><% } %>);\n" +
           "      _type.buildDynamicInstance(_inst, null, <%= constr.forwardParams %>);\n" +
           "      return _inst;\n" +
           "   }\n\n" +
           "<% } } %>" +
           // For any child types which are component classes, also add their newX method here in the outer type
           "<% DynInnerConstructor[] innerConstrs = getChildComponentConstructors();\n" +
           "   for (DynInnerConstructor constr:innerConstrs) {\n" +
           "       String[] paramTypeNames = constr.getParamTypeNames(); " +
           "       String[] paramNames = constr.getParamNames(); %>" +
           "   <%= constr.innerTypeModifiers %> <%= constr.compiledInnerTypeName %> new<%= constr.upperInnerTypeName%>(<%" +
           "      for (int s = 0; s < paramNames.length; s++) { " +
           "           %><%= s != 0 ? \", \" : \"\"%><%= paramTypeNames[s] %> <%= paramNames[s] %><% } %>) <%= constr.throwsClause %> {\n" +
           "      sc.lang.java.TypeDeclaration _type = sc.lang.DynObject.getType(\"<%=constr.fullInnerTypeName%>\");\n" +
           "      <%= constr.compiledInnerTypeName %> _inst = new <%= constr.compiledInnerTypeName %>(_type<%" +
           "         for (int pn = 0; pn < paramNames.length; pn++) { %>, <%=paramNames[pn]%><% } %>);\n" +
           "      _type.buildDynamicInstance(_inst, this, <%= constr.forwardParams %>);\n" +
           "      return _inst;\n" +
           "   }\n\n" +
           "<% } %>" +
           // Version which takes the TypeDeclaration as the argument.
           "<% for (DynInnerConstructor constr:innerConstrs) {\n" +
           "       String[] paramTypeNames = constr.getParamTypeNames(); " +
           "       String[] paramNames = constr.getParamNames(); %>" +
           "   <%= constr.innerTypeModifiers %> <%= constr.compiledInnerTypeName %> new<%= constr.upperInnerTypeName%>(sc.lang.java.TypeDeclaration _type<%" +
           "      for (int s = 0; s < paramNames.length; s++) { " +
           "           %><%= s != 0 ? \", \" : \"\"%><%= paramTypeNames[s] %> <%= paramNames[s] %><% } %>) <%= constr.throwsClause %> {\n" +
           "      <%= constr.compiledInnerTypeName %> _inst = new <%= constr.compiledInnerTypeName %>(_type<%" +
           "         for (int pn = 0; pn < paramNames.length; pn++) { %>, <%=paramNames[pn]%><% } %>);\n" +
           "      _type.buildDynamicInstance(_inst, this, <%= constr.forwardParams %>);\n" +
           "      return _inst;\n" +
           "   }\n\n" +
           "<% } %>" +
           "<%" +
           "   DynMethod[] meths = getDynCompiledMethods();\n" +
           "   for (DynMethod meth:meths) {\n" +
           "      String[] paramTypeNames = meth.paramTypeNames; " +
           "      String[] paramNames = meth.paramNames; %>" +
           "   <%= meth.modifiers %> <%= meth.returnType %> <%= meth.name%>(<%" +
           "      for (int s = 0; s < paramNames.length; s++) { " +
           "           %><%= s != 0 ? \",\" : \"\"%><%= paramTypeNames[s] %> <%= paramNames[s] %><% } %>) <%= meth.throwsClause %> {\n" +
           //"      if (!meth.isVoid()) { %>return <% if (meth.needsCast) { %>(<%= meth.returnType %>)<% } %> <% } %>invoke(\"<%= meth.name %>\", \"<%= meth.typeSignature %>\"<%" +
           "      <%= meth.preInvoke%>invoke(\"<%= meth.name %>\", \"<%= meth.typeSignature %>\"<%" +
           "         for (int pn = 0; pn < paramNames.length; pn++) { %>,<%=paramNames[pn]%><% } %>)<%= meth.postInvoke %>;\n" + // NOTE: always use the , here cause we already added two above
           "   }\n" +
           "<% } %>" +
           "<% for (DynMethod meth:meths) {\n" +
           "      if (meth.needsSuper) {" +
           "         String[] paramTypeNames = meth.paramTypeNames; " +
           "         String[] paramNames = meth.paramNames; %>" +
           "   <%= meth.modifiers %> <%= meth.returnType %> _super_<%= meth.name%>(<%" +
           "         for (int s = 0; s < paramNames.length; s++) { " +
           "           %><%= s != 0 ? \",\" : \"\"%><%= paramTypeNames[s] %> <%= paramNames[s] %><% } %>) <%= meth.superThrowsClause %> {\n      <%" +
           "         if (!meth.isVoid()) { %>return <% if (meth.needsCast) { %>(<%= meth.returnType %>)<% } %> <% } %>super.<%= meth.name %>(<%" +
           "            for (int pn = 0; pn < paramNames.length; pn++) { %><%=pn != 0 ? \",\" : \"\"%><%=paramNames[pn]%><% } %>);\n" +
           "   }\n" +
           "<% } } %>" +
           "<% if (!extendsDynamicStub) { %>" +
           "   public Object getProperty(String propName, boolean getField) {\n" +
           "      return dynObj.getPropertyFromWrapper(this, propName, getField);\n" +
           "   }\n" +
           "   public Object getProperty(int propIndex, boolean getField) {\n" +
           "      return dynObj.getPropertyFromWrapper(this, propIndex, getField);\n" +
           "   }\n" +
           "   public void setProperty(String propName, Object value, boolean setField) {\n" +
           "      dynObj.setPropertyFromWrapper(this, propName, value, setField);\n" +
           "   }\n" +
           "   public void setProperty(int propIndex, Object value, boolean setField) {\n" +
           "      dynObj.setProperty(propIndex, value, setField);\n" +
           "   }\n" +
           "   public Object invoke(String methodName, String paramSig, Object... args) {\n" +
           "      return dynObj.invokeFromWrapper(this, methodName, paramSig, args);\n" +
           "   }\n" +
           "   public Object invoke(int methodIndex, Object... args) {\n" +
           "      return dynObj.invokeFromWrapper(this, methodIndex, args);\n" +
           "   }\n" +
           "   public Object getDynType() {\n" +
           "      return dynObj == null ? getClass() : dynObj.getDynType();\n" +
           "   }\n" +
           "   public void setDynType(Object typeObj) {\n" +
           "      dynObj.setTypeFromWrapper(this, typeObj);\n" +
           "   }\n" +
           "   public <_TPROP> _TPROP getTypedProperty(String propName, Class<_TPROP> propType) {\n" +
           "      return (_TPROP) dynObj.getPropertyFromWrapper(this, propName, false);\n" +
           "   }\n" +
           "   public void addProperty(Object propType, String propName, Object initValue) {\n" +
           "      dynObj.addProperty(propType, propName, initValue);\n" +
           "   }\n" +
           "<% } %>" +
           //
           // Now do code for any inner objects which override a compiled definition of the same object.
           // In this case we need to override the getX methods so that it returns a dynamic type.
           //
           "<% for (DynObjectInfo obj:dynCompiledObjects) {" +
           "   if (obj.componentType) {%>" +
           "   <%= obj.modifiers %> <%= obj.compiledClassName %> get<%= obj.upperClassName %>() {\n" +
           "      return get<%= obj.upperClassName %>(true);\n" +
           "   }\n" +
           "   <%= obj.modifiers %> <%= obj.compiledClassName %> get<%= obj.upperClassName %>(boolean doInit) {\n" +
           "      if (<%= obj.lowerClassName %> == null) {\n" +
           "         <%= obj.lowerClassName %> = (<%= obj.lowerClassName %>) sc.lang.DynObject.createVirtual(doInit, \"<%= obj.typeName %>\", <%= obj.outerObj %>, null);\n" +
           "      }\n" +
           "      return (<%= obj.compiledClassName %>) <%= obj.lowerClassName %>;\n" +
           "   }\n" +
           "<% }" +
           "   else { %>" +
           "   <%= obj.modifiers %> <%= obj.compiledClassName %> get<%= obj.upperClassName %>() {\n" +
           "      if (<%= obj.lowerClassName %> == null) {\n" +
           "         <%= obj.lowerClassName %> = (<%= obj.lowerClassName %>) sc.lang.DynObject.createVirtual(\"<%= obj.typeName %>\", <%= obj.outerObj %>, null);\n" +
           "      }\n" +
           "      return (<%= obj.compiledClassName %>) <%= obj.lowerClassName %>;\n" +
           "   }\n" +
           "<% } %>" +
           "<% } %>" +
           //
           // Now do getX and setX methods for any properties in interfaces which are dynamic for this type.
           //
           "<% for (sc.lang.java.PropertyDefinitionParameters prop:dynCompiledProperties) {%>" +
           "   <%=prop.getModifiers%> <%=prop.propertyTypeName%><%=prop.arrayDimensions%> <%=prop.getOrIs%><%=prop.upperPropertyName%>() {\n" +
           "      return <%=prop.preReturn%> getProperty(\"<%=prop.lowerPropertyName%>\", false)<%=prop.postReturn%>;\n" +
           "   }\n" +
           "   <%=prop.setModifiers%> void set<%=prop.upperPropertyName%>(<%=prop.setTypeName%><%=prop.arrayDimensions%> _<%=prop.lowerPropertyName%>) {\n" +
           "     setProperty(\"<%=prop.lowerPropertyName%>\", _<%=prop.lowerPropertyName%>, false);\n" +
           "   }\n" +
           "<% } %>" +

           //
           // Now do any getX methods which need to call isX methods
           //
           "<% for (sc.lang.java.PropertyDefinitionParameters prop:cvtIsToGet) {%>" +
           "   <%=prop.getModifiers%> <%=prop.propertyTypeName%><%=prop.arrayDimensions%> <%=prop.getOrIs%><%=prop.upperPropertyName%>() {\n" +
           "      return is<%=prop.upperPropertyName%>();" +
           "   }\n" +
           "<% } %>" +

           // When not using separate types for the inner types, we just suck them right here into the original definition (INNER STUBS)
           "<% for (sc.lang.java.BodyTypeDeclaration stub:innerDynStubs) {%>" +
           "<%= stub.generateDynamicStub() %>" +
           "<% } %>" +

           "\n   public boolean hasDynObject() { return true; }\n" +

           "}\n";

   transient static Template dynStubTemplate;

   String generateDynamicStub() {
      return generateDynamicStub(false);
   }

   String generateDynamicStub(boolean batchCompile) {
      // TODO: could make this configurable via compiler settings
      if (dynStubTemplate == null)
         dynStubTemplate = TransformUtil.parseTemplate(dynStubTemplateStr, DynStubParameters.class, false, false, null, getLayeredSystem());
      return TransformUtil.evalTemplate(new DynStubParameters(getLayeredSystem(), getLayer(), this, batchCompile), dynStubTemplate);
   }


   public void updateBaseType(BodyTypeDeclaration newType) {
      updateBaseTypeLeaf(newType);
   }

   public boolean updateExtendsType(BodyTypeDeclaration newExtType, boolean modifyOnly, boolean extOnly) {
      return false;
   }

   public void updateStaticValues(BodyTypeDeclaration newType) {
      if (oldStaticFields == null || oldStaticValues == null)
         return;
      int[] statMap = new int[oldStaticFields.length];
      for (int i = 0; i < oldStaticFields.length; i++) {
         statMap[i] = newType.getDynStaticFieldIndex(ModelUtil.getPropertyName(oldStaticFields[i]));
      }

      if (oldStaticValues != null) {
         Object[] newStaticValues = new Object[newType.getDynStaticFieldCount()];
         for (int i = 0; i < oldStaticValues.length; i++) {
            if (statMap[i] != -1)
               newStaticValues[statMap[i]] = oldStaticValues[i];
         }
         newType.staticValues = newStaticValues;
      }
   }

   public void updateBaseTypeLeaf(BodyTypeDeclaration newType) {
      if (newType.getDeclarationType() != DeclarationType.INTERFACE) {
         if (instFields != null) {
            // Stash away the old instance fields - used in DynObject.setType so it can remap properties
            oldInstFields = instFields;
            instFields = null;
            dynInstFieldMap = null;
         }
         if (staticFields != null) {
            oldStaticFields = staticFields;
            oldStaticValues = staticValues;
            updateStaticValues(newType);
            staticFieldMap = null;
         }
      }


      // Only do this for the most specific type in the hierarchy since we register type name
      // Make sure to skip the replaced types though as we'll call this on an item which has just
      // been replaced.
      LayeredSystem sys = getJavaModel().layeredSystem;

      if (getRealReplacedByType() == null) {
         // Find all instances pointing to this type and replace them with the new one.  Update the inst/type index.
         // Update the sub-type index so that all sub-types point to this new type
         if (sys.options.liveDynamicTypes && ModelUtil.getLiveDynamicTypes(this)) {
            String oldTypeName = getRuntimeFullTypeName();
            Iterator insts = sys.getInstancesOfType(oldTypeName);
            if (insts != null) {
               while (insts.hasNext()) {
                  Object nextInst = insts.next();
                  /* We can't call setDynType this soon because it needs to remap the properties.  For templates, we create new properties in 'start' which has not been called yet.
                     so we now do it just before we start updating the instances of this type
                  if (nextInst instanceof IDynObject) {
                     IDynObject inst = (IDynObject) nextInst;
                     inst.setDynType(newType); // Forces the type to recompute the field mapping using "getOldInstFields"
                  }
                  */
                  String newTypeName = newType.getFullTypeName();
                  // This handles the case where you modify a type from a sub-type.  In effect, this dynamically creates
                  // a new inner type which extends the original inner type.  We'll update the type of the instance and
                  // reregister the instance under the new name.
                  if (!newTypeName.equals(oldTypeName)) {
                     sys.removeDynInstance(oldTypeName, nextInst);
                     sys.addDynInstance(newTypeName, nextInst);
                  }
               }
            }
         }
         else
            sys.setStaleCompiledModel(true, "Type change of type ", typeName, " with liveDynamicTypes=false");
      }

      if (sys.options.liveDynamicTypes) {
         if (isModifiedBySameType()) {
            replacedByType.updateBaseTypeLeaf(replacedByType);
         }

         Iterator<BodyTypeDeclaration> subTypes = this instanceof TypeDeclaration ? sys.getSubTypesOfType(this) : null;
         if (subTypes != null) {
            while (subTypes.hasNext()) {
               BodyTypeDeclaration origSubType = subTypes.next();
               BodyTypeDeclaration realSubType = origSubType.resolve(true);
               // Only do this for the immediate sub-type (extends only) of the one which is changing (checking old and new type)
               // If it's a modified type, do this only if this is the same type being modified and the new type does not already modify the sub-type

               Object exType = realSubType.getExtendsTypeDeclaration();
               //Object exType = realSubType.getDeclaredExtendsTypeDeclaration();
               // If origSubType is modfiied by realSubType and inherits the extends type, we need to update the extends type of the origSubType
               /*
               if (exType == null && origSubType != realSubType) {
                  //realSubType = origSubType;
                  exType = origSubType.getDeclaredExtendsTypeDeclaration();
               }
               */
               if (exType == newType || exType == this || ((realSubType instanceof ModifyDeclaration) && realSubType.getModifiedType() == this && realSubType != newType && ModelUtil.sameTypes(this, newType)) && !newType.modifiesType(realSubType)) {
                  // TODO: remove!  Used to add modify types to the sub-types table and so we could do this.  Now we need to call this before replacedType is true.
                  realSubType.updateExtendsType(newType, false, false); // update both modify and extends for n
               }
               realSubType.updateBaseTypeLeaf(realSubType);
            }
         }
      }
   }

   public void addInstMemberToPropertyCache(String propName, Object fieldObj) {
      addInstMemberToPropertyCacheLeaf(propName, fieldObj);
   }

   public void addInstMemberToPropertyCacheLeaf(String propName, Object fieldObj) {
      addInstMemberToPropertyCacheInternal(propName, fieldObj);

      LayeredSystem sys = getJavaModel().layeredSystem;
      if (sys.options.liveDynamicTypes) {

         if (isModifiedBySameType()) {
            replacedByType.addInstMemberToPropertyCacheLeaf(propName, fieldObj);
         }

         Iterator<BodyTypeDeclaration> subTypes = sys.getSubTypesOfType(this);
         if (subTypes != null) {
            while (subTypes.hasNext()) {
               BodyTypeDeclaration subType = subTypes.next();
               subType = subType.resolve(true);
               subType.addInstMemberToPropertyCacheLeaf(propName, fieldObj);
            }
         }
      }
   }

   public BodyTypeDeclaration updateInnerType(BodyTypeDeclaration innerType, ExecutionContext ctx, boolean updateInstances, UpdateInstanceInfo info, boolean clearDynamicNew) {
      Object overridden = getInnerType(innerType.typeName, null);
      // For hidden types, we have already added the type to the parent so it will show up here.  We still need to
      // perform the update though.
      if (overridden == innerType) {
         if (overridden instanceof ModifyDeclaration)
            overridden = ((ModifyDeclaration) overridden).getModifiedType();
      }
      if (overridden != null) {
         Object enclType = ModelUtil.getEnclosingType(overridden);
         boolean hiddenType = innerType.isHiddenType();
         if (hiddenType) {
            String nextPart = innerType.typeName;
            int ix;
            while ((ix = nextPart.indexOf(".")) != -1 && enclType != null) {
               nextPart = nextPart.substring(ix + 1);
               enclType = ModelUtil.getEnclosingType(enclType);
            }
            if (enclType == null)
               System.out.println("*** No enclosing type for complex modify");
         }
         if (enclType != this) {
            // If this is of type "a.b" - i.e. where this TypeDeclaration is not used, we need to find the right
            // type to add a as layered type in the system (via updateType).  If we need to create a new intermediate
            // type, that type needs to get registered into the type system and properly modify the type it's derived
            // from.
            if (innerType.isHiddenType()) {
               BodyTypeDeclaration root;
               BodyTypeDeclaration newType = innerType.replaceHiddenType(this);
               root = innerType.getHiddenRoot();

               updateInnerType(root, ctx, updateInstances, info, clearDynamicNew);

               // Don't return the root type... we should not be replacing this guy I don't think so always can return type?
               return newType;
            }
            if (innerType instanceof ModifyDeclaration) {
               // For hidden types, it's already in the hiddenBody
               if (innerType.getEnclosingType() != this)
                  addBodyStatementIndent(innerType);
               if (overridden instanceof BodyTypeDeclaration) {
                  ((BodyTypeDeclaration) overridden).updateType(innerType, ctx, TypeUpdateMode.Add, updateInstances, info);
               }
               return innerType;
            }
            else {
               displayError("Inner type ", innerType.typeName, " defined in type: ", enclType.toString(), " cannot override in: ");
               return null;
            }
         }
         else {
            // We are trying to modify a type in the same layer in which it is defined.  Just return the type.
            if (innerType instanceof ModifyDeclaration) {
               return (TypeDeclaration) overridden;
            }
            // else, it is a ClassDeclaration so we'll do the replace
         }

         // TODO: We could go through and update the types of all of the instances.  Would require a merge from the old type to the new one though so for now this will be a restart.
         getLayeredSystem().setStaleCompiledModel(true, "Replacing inner type: ", innerType.typeName, " for ", typeName);
      }
      else {
         addBodyStatementIndent(innerType);
         getLayeredSystem().notifyInnerTypeAdded(innerType);
      }
      // Disable the optimization that avoids a dynamic stub in this case - if we are in the interpreter, we expect the type to be extended
      if (clearDynamicNew)
         innerType.clearDynamicNew();

      /*
       * Adding a new inner object.  This involves finding all instances of the outer type and adding a new field.
       * when a lazy init value.  If there is a DynChildManager, we also have to init the object and add it to the
       * parent.
       */
      if (innerType instanceof ClassDeclaration && innerType.getDeclarationType() == DeclarationType.OBJECT) {
         if (innerType.hasModifier("static")) {
            addStaticField(innerType, innerType.typeName, DynObject.lazyInitSentinel);
         }
         else {
            if (isDynamicNew()) {
               if (dynamicNew)
                  clearDynamicNew();
               addDynInstField(innerType, true);
               addChildObjectToInstances(innerType);
            }
            else {
               // Can't add an inner object to a compiled type.  We need a slot to store the instance for the instance.
               // we
               getLayeredSystem().setStaleCompiledModel(true, "Adding inner type: ", innerType.typeName, " to compiled type: ", typeName);
            }
         }
      }
      if (innerType.isEnumConstant()) {
         addStaticField(innerType, innerType.typeName, innerType.getEnumValue());
      }

      return innerType;
   }

   public void updateBlockStatement(BlockStatement bs, ExecutionContext ctx, UpdateInstanceInfo info) {
      needsDynamicType();
      //
      // We can just interpret these block statements so it does not make the system stale
      // JavaModel model = getJavaModel();
      //if (!isDynamicType())
      //   model.layeredSystem.setStaleCompiledModel(true, "Adding block statement to compiled type", typeName);
      addBodyStatementIndent(bs);

      if (info != null) {
         info.addBlockStatement(this, bs);
      }
      else {
         if (bs.staticEnabled) {
            evalBlockStatement(bs, ctx);
         }
         else
            updateInstBlockStatementLeaf(bs, ctx);
      }
   }

   private void needsDynamicType() {
      if (getCompiledOnly())
         return;
      // Need to turn this into a regular dynamic type if it was previously a dynamicNew
      if (dynamicNew && !dynamicType) {
         dynamicNew = false;
         dynamicType = true;
      }
   }

   /**
    * As soon as one subclass needs to create a real dynamic type from a base type, we need to make the whole type hierarchy dynamic.
    */
   public void clearDynamicNew() {
      if (dynamicNew) {
         dynamicNew = false;
         dynamicType = true;

         Object derivedType = getDerivedTypeDeclaration();
         if (derivedType instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration derivedTD = (BodyTypeDeclaration) derivedType;
            if (derivedTD.dynamicNew)
               derivedTD.clearDynamicNew();
         }
      }
   }

   int getPropertyStatementIndex(String propName) {
      if (body != null) {
         for (int i = 0; i < body.size(); i++) {
            Statement st = body.get(i);
            if (st instanceof FieldDefinition) {
               FieldDefinition field = (FieldDefinition) st;
               if (field.variableDefinitions != null) {
                  for (VariableDefinition varDef:field.variableDefinitions)
                     if (StringUtil.equalStrings(varDef.variableName, propName))
                        return i;
               }
            }
            else if (ModelUtil.isProperty(st)) {
               String stPropName = ModelUtil.getPropertyName(st);
               if (stPropName.equals(propName))
                  return i;
            }
         }
      }
      return -1;
   }

   public Statement updateBodyStatement(Statement def, ExecutionContext ctx, boolean updateInstances, UpdateInstanceInfo info, boolean addBefore, String relPropertyName) {
      needsDynamicType();

      boolean lockAcquired = false;

      JavaModel model = getJavaModel();

      LayeredSystem sys = model.layeredSystem;

      try {
         sys.acquireDynLock(false);
         lockAcquired = true;
         int insertIx = -1;
         if (relPropertyName == null) {
            if (addBefore)
               insertIx = 0;
         }
         else {
            insertIx = getPropertyStatementIndex(relPropertyName);
            if (!addBefore)
               insertIx++;
         }
         if (def instanceof AbstractMethodDefinition) {
            AbstractMethodDefinition newDef = (AbstractMethodDefinition) def;
            if (!isDynamicType())
               model.layeredSystem.setStaleCompiledModel(true, "Adding method to compiled type: ", newDef.name, " for type: ", typeName);

            // Look for a method in this type specifically.  Anything in a modified type or extended type should not
            // get replaced since we are overriding that method in that case.
            AbstractMethodDefinition overridden = (AbstractMethodDefinition) declaresMethod(newDef.name, newDef.getParameterList(), null, null, false, false, null, null, false);
            if (overridden != null) {
               overridden.parentNode.replaceChild(overridden, newDef);
               if (overridden.overriddenMethod != null) {
                  if (newDef == overridden.overriddenMethod)
                     System.out.println("*** REPLACING A METHOD WITH ITSELF!");
                  overridden.overriddenMethod.replacedByMethod = newDef;
                  newDef.overriddenMethod = overridden.overriddenMethod;
               }

               if (newDef == overridden)
                  System.out.println("*** REPLACING A METHOD WITH ITSELF!");
               overridden.replacedByMethod = newDef;
               overridden.replaced = true;
               return overridden;
            }

            MethodDefinition newMethod;
            if ((newDef instanceof MethodDefinition) && (newMethod = (MethodDefinition) newDef).overridesCompiled) {
               model.layeredSystem.setStaleCompiledModel(true, "Overriding compiled method: ", newMethod.name, " in type: ", typeName);
            }
            if (insertIx == -1)
              addBodyStatementIndent(def);
            else
               addBodyStatementAtIndent(insertIx, def);
         }
         else if (def instanceof FieldDefinition) {
            FieldDefinition newDef = (FieldDefinition) def;
            FieldDefinition replacedField = null;
            boolean replaced = false;
            boolean addField = true;
            for (VariableDefinition varDef:newDef.variableDefinitions) {
               Object overridden = definesMember(varDef.variableName, MemberType.PropertyGetSet, null, null);
               if (overridden instanceof VariableDefinition) {
                  VariableDefinition overriddenDef = (VariableDefinition) overridden;
                  FieldDefinition overriddenField = (FieldDefinition) overriddenDef.getDefinition();
                  // If this field was already defined in this same type, we don't have to add it - instead we just
                  // update it.
                  if (overriddenField.getEnclosingType() == this) {
                     addField = false;
                     if (!replaced) {
                        overriddenField.parentNode.replaceChild(overriddenField, newDef);
                        replacedField = overriddenField;
                        replaced = true;

                        for (VariableDefinition otherOverVar:overriddenField.variableDefinitions) {
                           if (otherOverVar == overriddenDef)
                              continue;

                           int foundIx;
                           int sz = newDef.variableDefinitions.size();
                           for (foundIx = 0; foundIx < sz; foundIx++) {
                              VariableDefinition varDef2 = newDef.variableDefinitions.get(foundIx);
                              if (varDef2.variableName.equals(otherOverVar.variableName))
                                 break;
                           }
                           if (foundIx == sz) {
                              // TODO: this variable is not present in the field def we need to remove this field
                              // from the dynamic model.  If it is present, we'll just come back around here again with
                              // replaced = true and update its initializer then.
                              System.err.println("*** Unimplemented: Not removing field from dynamic model!");
                           }
                        }
                     }
                     // equals but checks for null
                     if (!DynUtil.equalObjects(overriddenDef.initializer, varDef.initializer)) {
                        lockAcquired = false;
                        sys.releaseDynLock(false);
                        updatePropertyForType(varDef, ctx, InitInstanceType.Init, updateInstances, info);
                     }
                  }
               }

               if (addField) {
                  if (lockAcquired) {
                     lockAcquired = false;
                     sys.releaseDynLock(false);
                  }
                  if (overridden != null) {
                     displayError("Field of that name already exists: ", overridden.toString());
                     return replacedField;
                  }
                  else {
                     if (insertIx == -1)
                        addBodyStatementIndent(def);
                     else
                        addBodyStatementAtIndent(insertIx, def);

                     addStaticOrInstField(varDef, ctx, updateInstances, info);
                  }
               }
            }
            return replacedField;
         }
         else if (def instanceof BodyTypeDeclaration) {
            return updateInnerType((BodyTypeDeclaration) def, ctx, updateInstances, info, false);
         }
         else if (def instanceof PropertyAssignment) {
            Object replacedObj = updateProperty((PropertyAssignment) def, ctx, true, info);
            Statement replacedStatement;
            if (replacedObj instanceof VariableDefinition)
               replacedStatement = ((VariableDefinition) replacedObj).getDefinition();
            else
               replacedStatement = (Statement) replacedObj;
            return replacedStatement;
         }
         else {
            System.err.println("*** Unrecognized def in update body statement");
         }
      }
      finally {
         if (lockAcquired)
            sys.releaseDynLock(false);
      }
      return null;
   }

   public Statement removeBodyStatement(Statement def, ExecutionContext ctx, boolean updateInstances, UpdateInstanceInfo info) {
      needsDynamicType();

      JavaModel model = getJavaModel();
      if (def instanceof AbstractMethodDefinition) {
         AbstractMethodDefinition toRem = (AbstractMethodDefinition) def;
         if (!isDynamicType())
            model.layeredSystem.setStaleCompiledModel(true, "Removing method from compiled type: ", toRem.name, " for type: ", typeName);

         // Look for a method in this type specifically.  Anything in a modified type or extended type should not
         // get replaced since we are overriding that method in that case.
         AbstractMethodDefinition overridden = (AbstractMethodDefinition) declaresMethod(toRem.name, toRem.getParameterList(), null, null, false, false, null, null, false);
         if (overridden != null) {
            overridden.parentNode.removeChild(overridden);
            removeStatement(overridden);
            return overridden;
         }
         else
            return null;

      }
      else if (def instanceof FieldDefinition) {
         FieldDefinition toRem = (FieldDefinition) def;
         FieldDefinition replacedField = null;
         boolean replaced = false;
         boolean addField = true;
         for (VariableDefinition varDef:toRem.variableDefinitions) {
            Object overridden = definesMember(varDef.variableName, MemberType.PropertyGetSet, null, null);
            if (overridden instanceof VariableDefinition) {
               VariableDefinition overriddenDef = (VariableDefinition) overridden;
               FieldDefinition overriddenField = (FieldDefinition) overriddenDef.getDefinition();
               // If this field was already defined in this same type, we don't have to add it - instead we just
               // update it.
               if (overriddenField.getEnclosingType() == this) {
                  addField = false;
                  if (!replaced) {
                     overriddenField.parentNode.removeChild(overriddenField);
                     replacedField = overriddenField;
                     replaced = true;

                     for (VariableDefinition otherOverVar:overriddenField.variableDefinitions) {
                        if (otherOverVar == overriddenDef)
                           continue;

                        int foundIx;
                        int sz = toRem.variableDefinitions.size();
                        for (foundIx = 0; foundIx < sz; foundIx++) {
                           VariableDefinition varDef2 = toRem.variableDefinitions.get(foundIx);
                           if (varDef2.variableName.equals(otherOverVar.variableName))
                              break;
                        }
                        if (foundIx == sz) {
                           // TODO: this variable is not presenet in the field def we need to remove this field
                           // from the dynamic model.  If it is present, we'll just come back around here again with
                           // replaced = true and update its initializer then.
                           System.err.println("*** Unimplemented: Not removing field from dynamic model!");
                        }
                     }
                     removeStatement(overriddenField);
                  }
                  // What changes to do we need to make for the remove case?  Should we go ahead and remove the field values?
                  // pdatePropertyForType(varDef, ctx, InitInstanceType.Init, updateInstances);
               }
            }

         }
         return replacedField;
      }
      else if (def instanceof PropertyAssignment) {
         if (def.getEnclosingType() == this) {
            PropertyAssignment assign = (PropertyAssignment) def;
            String propName = assign.propertyName;
            removeStatement(assign);

            Object newProp = definesMember(propName, MemberType.PropertyAnySet, null, null);
            if (newProp != null && newProp instanceof JavaSemanticNode) {
               updatePropertyForType((JavaSemanticNode) newProp, ctx, InitInstanceType.Init, updateInstances, info);
            }
         }
         else {
            System.err.println("*** ???");
         }
      }
      else if (def instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration innerType = (BodyTypeDeclaration) def;
         if (innerType.getEnclosingIType() == this) {
            removeStatement(innerType);
            if (ModelUtil.isObjectType(innerType) || ModelUtil.isEnum(innerType))
               updatePropertyForType(innerType, ctx, InitInstanceType.Remove, updateInstances, info);
            innerType.removeType();

            // Must be done after we remove the type from the name space as we'll skip the remove if this is not the last type for this type name
            getLayeredSystem().notifyInnerTypeRemoved(innerType);
         }
         else {
            System.err.println("*** removeBodyStatement: invalid argument");
         }
      }
      else {
         System.err.println("*** Unrecognized def in remove body statement");
      }
      return null;
   }

   public void removeStatement(Statement st) {
      bodyChanged();
      if (body != null) {
         for (int i = 0; i < body.size(); i++) {
            Object cur = body.get(i);
            if (cur == st) {
               body.remove(i);
               return;
            }
         }
      }
      System.err.println("*** Can't find statement to remove");
   }

   public void addStaticOrInstField(VariableDefinition varDef, ExecutionContext ctx, boolean updateInstances, UpdateInstanceInfo info) {
      boolean isDynType = varDef.isDynamicType();
      if (!isDynType) {
         varDef.getLayeredSystem().setStaleCompiledModel(true, "Adding new field: ", varDef.variableName, " to compiled type: ", varDef.getEnclosingType().toString());
      }
      if (varDef.getDefinition().hasModifier("static")) {
         ctx.pushStaticFrame(this);
         try {
            // TODO: add to UpdateInstanceInfo here!
            // We won't initialize the static value unless they've already been initialized
            addStaticField(varDef, varDef.variableName, staticValues != null ? getVariableInitValue(varDef.getLayeredSystem(), varDef, ctx) : null);
         }
         finally {
            ctx.popStaticFrame();
         }
      }
      else {
         if (isDynType)
            addDynInstField(varDef, true);
         addFieldToInstances(varDef, ctx, updateInstances, info);
      }
   }

   public void addFieldToInstances(VariableDefinition varDef, ExecutionContext ctx, boolean updateInstances, UpdateInstanceInfo info) {
      addFieldToInstancesLeaf(varDef, ctx, updateInstances, info);
   }

   private static Object getVariableInitValue(LayeredSystem sys, VariableDefinition varDef, ExecutionContext ctx) {
      Object initValue = null;
      initValue = varDef.getInitialValue(ctx);
      // Don't want to catch the error here - let it bubble up so we catch it higher up - like in the eval of the statement or updateType call
      //try {
      /*
      catch (RuntimeException exc) {
         sys.setStaleCompiledModel(true, "Runtime error adding new field: ", varDef.variableName, " for instance: ", DynUtil.getInstanceName(ctx.getCurrentObject()));
      }
      */
      return initValue;
   }

   public void doAddFieldToInstances(VariableDefinition varDef, ExecutionContext ctx) {
      LayeredSystem sys = getLayeredSystem();
      Iterator insts = sys.getInstancesOfType(getRuntimeFullTypeName());
      if (insts != null) {
         while (insts.hasNext()) {
            Object inst = insts.next();
            Object initValue = null;
            ctx.pushCurrentObject(inst);
            try {
               initValue = getVariableInitValue(sys, varDef, ctx);
            }
            finally {
               // Add the field even if we get an RTE trying to evaluate the value... perhaps ideally case, we could cleanly roll back the add of the field but that's not the case now so we add it with null.
               if (inst instanceof IDynObject)
                  ((IDynObject) inst).addProperty(varDef.getTypeDeclaration(), varDef.variableName, initValue);
               ctx.popCurrentObject();
            }
         }
      }
   }

   public void addFieldToInstancesLeaf(VariableDefinition varDef, ExecutionContext ctx, boolean updateInstances, UpdateInstanceInfo info) {
      JavaModel model = getJavaModel();
      LayeredSystem sys = model.getLayeredSystem();

      syncPropertiesInited = false;
      syncProperties = null;
      // Because we've added a field to either this type or a sub-type, our transformed model needs to be updated - in particular for the new addSyncType call
      // that includes the new property
      model.markTransformedChanged();

      if (sys.options.liveDynamicTypes && ModelUtil.getLiveDynamicTypes(this)) {
         if (updateInstances && replacedByType == null) {
            if (info != null) {
               info.addField(this, varDef);
            }
            else {
               doAddFieldToInstances(varDef, ctx);
            }
         }
         if (isModifiedBySameType()) {
            replacedByType.addFieldToInstancesLeaf(varDef, ctx, updateInstances, info);
         }

         Iterator<BodyTypeDeclaration> subTypes = sys.getSubTypesOfType((TypeDeclaration) this);
         if (subTypes != null) {
            while (subTypes.hasNext()) {
               BodyTypeDeclaration subType = subTypes.next();
               subType = subType.resolve(true);
               subType.addFieldToInstancesLeaf(varDef, ctx, updateInstances, info);
            }
         }
      }
      else
         sys.setStaleCompiledModel(true, "Recompile needed to add field ", varDef.variableName, " to type: ", typeName);
   }

   public void addChildObjectToInstances(BodyTypeDeclaration innerType) {
      addChildObjectToInstancesLeaf(innerType);
   }

   public void addChildObjectToInstancesLeaf(BodyTypeDeclaration innerType) {
      JavaModel model = getJavaModel();
      LayeredSystem sys = model.getLayeredSystem();
      if (sys.options.liveDynamicTypes && ModelUtil.getLiveDynamicTypes(this)) {
         IDynChildManager mgr = getDynChildManager();
         String innerTypeName = innerType.typeName;
         Iterator insts = sys.getInstancesOfType(getRuntimeFullTypeName());
         if (insts != null) {
            while (insts.hasNext()) {
               Object instObj = insts.next();
               // Stale types can get registered with a concrete class which is not IDynObject - in that case, we can't add
               // the property but should have already issued a stale message about this type.
               if (instObj instanceof IDynObject) {
                  IDynObject inst = (IDynObject) instObj;
                  inst.addProperty(innerType, innerTypeName, DynObject.lazyInitSentinel);

                  if (mgr != null)
                     mgr.addChild(inst, DynUtil.getProperty(inst, innerTypeName));
               }
            }
         }

         if (isModifiedBySameType()) {
            replacedByType.addChildObjectToInstancesLeaf(innerType);
         }

         Iterator<BodyTypeDeclaration> subTypes = sys.getSubTypesOfType((TypeDeclaration) this);
         if (subTypes != null) {
            while (subTypes.hasNext()) {
               BodyTypeDeclaration subType = subTypes.next();
               subType = subType.resolve(true);
               subType.addChildObjectToInstancesLeaf(innerType);
            }
         }
      }
      else
         sys.setStaleCompiledModel(true, "Recompile needed to add child object: ", innerType.typeName, " to ", typeName);
   }

   public JavaSemanticNode updateProperty(PropertyAssignment assign, ExecutionContext ctx, boolean updateInstances, UpdateInstanceInfo info) {
      JavaModel model = getJavaModel();
      assign.parentNode = model;

      LayeredSystem sys = model.getLayeredSystem();
      JavaSemanticNode overriddenAssign;
      sys.acquireDynLock(false);
      try {
         // This property assignment modifies any existing definition we might have for this property
         // Can return a VariableDefinition here
         overriddenAssign = assign.modifyDefinition(this, false, false);
      }
      finally {
         sys.releaseDynLock(false);
      }

      if (!model.hasErrors) {
         // If this is a method, we need to use this assignment to do the update.  Otherwise, use the overridden assignment
         JavaSemanticNode node = overriddenAssign instanceof MethodDefinition ? assign : overriddenAssign;
         updatePropertyForType(node, ctx, InitInstanceType.Init, updateInstances, info);
      }

      return overriddenAssign;
   }

   private void updateModelBaseType(BodyTypeDeclaration newType) {
      BodyTypeDeclaration modifyingType = replacedByType;
      updateBaseType(newType);

      if (modifyingType instanceof ClassDeclaration) {
         // This happens when we are removing a type... where replacedByType == newType
         return;
      }

      // Make sure we update any modifying types because these are not put in the sub-types table.
      if (modifyingType != null && !replaced && !((ModifyDeclaration) modifyingType).modifyInherited) {
         modifyingType.updateModelBaseType(modifyingType);
      }
   }

   private static void addFieldToIndex(CoalescedHashMap<String, List<Object>> fieldIndex, Object varDef) {
      String name = ModelUtil.getPropertyName(varDef);
      List<Object> res = fieldIndex.get(name);
      if (res == null) {
         res = new ArrayList<Object>(1);
         fieldIndex.put(name, res);
      }
      res.add(varDef);

   }

   private static class UpdateTypeCtx {
      /**
       * Copied from the old type before the update - when we stop the old type they are erased so need to use them here after that
       */
      boolean dynamicType, dynamicNew, dynamicStub;

      List<Object> oldFields;
      int numOldFields;
      List<Object> newFields;

      int numNewFields;
      List<Object> newTypes;
      CoalescedHashMap<String, List<Object>> oldFieldIndex;

      int newTypesSize;
      CoalescedHashMap<String, Object> newTypeIndex;

      List<Object> oldTypes;

      int oldTypesSize;
      CoalescedHashMap<String, Object> oldTypeIndex;

      CoalescedHashMap<String, List<Object>> newFieldIndex;

      ArrayList<TypeDeclaration> toRemoveObjs = new ArrayList<TypeDeclaration>();
      ArrayList<TypeDeclaration> toAddObjs = new ArrayList<TypeDeclaration>();
      ArrayList<TypeDeclaration> toUpdateObjs = new ArrayList<TypeDeclaration>();
      ArrayList<UpdateTypeCtx> toUpdateCtxs = new ArrayList<UpdateTypeCtx>();

      ArrayList<IVariableInitializer> toUpdateFields = new ArrayList<IVariableInitializer>();
      // When we remove a property assignment, we have to restore the old one
      ArrayList<IVariableInitializer> toRestoreFields = new ArrayList<IVariableInitializer>();

      // Now go through the new fields and figure out which need to be updated or added.  Also mark fields, methods so
      // they point to this type.
      ArrayList<IVariableInitializer> toAddFields = new ArrayList<IVariableInitializer>();
      ArrayList<BlockStatement> toExecBlocks = new ArrayList<BlockStatement>();

      /**
       * Does this type change introduce new constructors?  For dynamic stubs this introduces a compile time change
       */
      boolean newConstructors = false;

      Object oldExtType;
   }

   UpdateTypeCtx buildUpdateTypeContext(BodyTypeDeclaration newType, TypeUpdateMode updateMode, UpdateInstanceInfo info, boolean activated) {
      UpdateTypeCtx tctx = new UpdateTypeCtx();

      // TODO: performance - when the layer is not activated I think we could skip a lot of this - fields, etc.... it's nice that we can refresh type and variable references without a global refresh
      // but maybe it's not worth it?

      if (activated) {
         tctx.dynamicType = isDynamicType();
         tctx.dynamicStub = isDynamicStub(false);
         tctx.dynamicNew = isDynamicNew();
         tctx.oldExtType = getExtendsTypeDeclaration();
      }

      // During initialization, this type might have been turned into a dynamic type from a subsequent layer.
      // Since we do not restart all of the other layers, we won't turn this new guy back into a dynamic type
      // so just copy that state over here.
      if (activated && dynamicType && !newType.dynamicType) {
         newType.dynamicType = true;
         if (newType.dynamicNew)
            newType.dynamicNew = false;
      }

      LayeredSystem sys = getLayeredSystem();

      if (activated) {
         tctx.oldFields = updateMode == TypeUpdateMode.Replace ? getDeclaredFields(null, false, false, true, false, false) :
                 getAllFields(null, false, false, true, true, false);
         tctx.numOldFields = tctx.oldFields == null ? 0 : tctx.oldFields.size();
         tctx.newFields = updateMode == TypeUpdateMode.Replace ? newType.getDeclaredFields(null, false, false, true, false, false) :
                 newType.getAllFields(null, false, false, true, true, false);
         tctx.numNewFields = tctx.newFields == null ? 0 : tctx.newFields.size();
         tctx.oldFieldIndex = new CoalescedHashMap<String, List<Object>>(tctx.numOldFields);
         // Keep a list of fields for each name to deal with reverse only bindings.  Those do not replace/override the previous definition.
         for (int i = 0; i < tctx.numOldFields; i++) {
            Object varDef = tctx.oldFields.get(i);
            addFieldToIndex(tctx.oldFieldIndex, varDef);
         }
         tctx.newTypes = newType.getAllInnerTypes(null, updateMode == TypeUpdateMode.Replace, false);
         tctx.newTypesSize = tctx.newTypes == null ? 0 : tctx.newTypes.size();
         tctx.newTypeIndex = new CoalescedHashMap<String, Object>(tctx.newTypesSize);
         for (int i = 0; i < tctx.newTypesSize; i++) {
            Object newInnerType = tctx.newTypes.get(i);
            tctx.newTypeIndex.put(CTypeUtil.getClassName(ModelUtil.getTypeName(newInnerType)), newInnerType);
         }

         tctx.oldTypes = getAllInnerTypes(null, updateMode == TypeUpdateMode.Replace, false);
         tctx.oldTypesSize = tctx.oldTypes == null ? 0 : tctx.oldTypes.size();
         tctx.oldTypeIndex = new CoalescedHashMap<String, Object>(tctx.oldTypesSize);
         for (int i = 0; i < tctx.oldTypesSize; i++) {
            Object oldInnerType = tctx.oldTypes.get(i);
            tctx.oldTypeIndex.put(CTypeUtil.getClassName(ModelUtil.getTypeName(oldInnerType)), oldInnerType);
         }

         if (body != null && updateMode == TypeUpdateMode.Replace) {
            for (int i = 0; i < body.size(); i++) {
               Object oldBodyDef = body.get(i);
               if (oldBodyDef instanceof PropertyAssignment) {
                  addFieldToIndex(tctx.oldFieldIndex, oldBodyDef);
               }
            }
         }
         tctx.newFieldIndex = new CoalescedHashMap<String, List<Object>>(tctx.numNewFields);
         for (int i = 0; i < tctx.numNewFields; i++) {
            Object varDef = tctx.newFields.get(i);
            addFieldToIndex(tctx.newFieldIndex, varDef);
         }
         if (newType.body != null && updateMode == TypeUpdateMode.Replace) {
            for (int i = 0; i < newType.body.size(); i++) {
               Object newBodyDef = newType.body.get(i);
               if (newBodyDef instanceof PropertyAssignment)
                  addFieldToIndex(tctx.newFieldIndex, newBodyDef);
            }
         }
      }

      boolean doChangeMethods = info != null && info.needsChangedMethods();

      // First go through all of the old fields, methods etc and remove any which are not present in the new index
      if (activated && body != null && (updateMode == TypeUpdateMode.Replace || updateMode == TypeUpdateMode.Remove)) {
         for (int i = 0; i < body.size(); i++) {
            Object oldBodyDef = body.get(i);
            if (oldBodyDef instanceof FieldDefinition) {
               FieldDefinition oldFieldDef = (FieldDefinition) oldBodyDef;
               for (VariableDefinition oldVarDef : oldFieldDef.variableDefinitions) {
                  List<Object> newDefObjs;
                  // Old field replaced
                  Object newDefObj = null;
                  if ((newDefObjs = tctx.newFieldIndex.get(oldVarDef.variableName)) != null) {
                     PropertyAssignment newPA;
                     for (int j = 0; j < newDefObjs.size(); j++) {
                        Object newPAObj = newDefObjs.get(j);
                        if (newDefObj == null || (newDefObj instanceof PropertyAssignment && (newPA = (PropertyAssignment) newDefObj).bindingDirection != null && !newPA.bindingDirection.doForward()))
                           newDefObj = newPAObj;
                     }
                     Object newDefProp = newDefObj;
                     if (newDefObj instanceof PropertyAssignment)
                        newDefProp = ((PropertyAssignment) newDefObj).assignedProperty;
                     if (!oldVarDef.equals(newDefObj) && newDefObj instanceof IVariableInitializer) {
                        if (!oldVarDef.equals(newDefProp))
                           oldVarDef.replacedBy = (VariableDefinition) newDefProp;

                        IVariableInitializer newDefVar = (IVariableInitializer) newDefObj;
                        // Only do the update if the thing has actually changed
                        if (!DynUtil.equalObjects(newDefVar.getInitializerExpr(), oldVarDef.getInitializerExpr()) && !tctx.toUpdateFields.contains(newDefVar))
                           tctx.toUpdateFields.add(newDefVar);
                     }
                  }
                  // old field removed
                  else {
                     if (!isDynamicType() && !sys.isDynamicRuntime())
                        sys.setStaleCompiledModel(true, "Recompile needed: field: ", oldVarDef.variableName, " removed in type ", typeName);
                  }
               }
            }
            else if (oldBodyDef instanceof PropertyAssignment) {
               PropertyAssignment oldPA = (PropertyAssignment) oldBodyDef;
               List<Object> newFieldDefs = tctx.newFieldIndex.get(oldPA.propertyName);
               if (newFieldDefs != null) {
                  if (!newFieldDefs.contains(oldPA)) {
                     for (Object newFieldDef : newFieldDefs) {
                        IVariableInitializer newFieldInit = (IVariableInitializer) newFieldDef;
                        if (!DynUtil.equalObjects(oldPA.getInitializerExpr(), newFieldInit.getInitializerExpr()) && !tctx.toUpdateFields.contains(newFieldInit))
                           tctx.toUpdateFields.add(newFieldInit);
                     }
                  }
               }
               else {
                  Object newInit = newType.definesMember(oldPA.propertyName, MemberType.InitializerSet, null, null);
                  if (newInit instanceof IVariableInitializer)
                     tctx.toRestoreFields.add((IVariableInitializer) newInit);
               }
            }
            else if (oldBodyDef instanceof TypeDeclaration) {
               TypeDeclaration oldInnerType = (TypeDeclaration) oldBodyDef;
               if (oldInnerType.typeName == null)
                  continue;
               List<Object> newDefs = tctx.newFieldIndex.get(oldInnerType.typeName);
               Object newDef = null;
               if (newDefs != null)
                  newDef = newDefs.get(0);

               if (newDef == null)
                  newDef = tctx.newTypeIndex.get(oldInnerType.typeName);

               // old object is removed
               if (newDef == null) {
                  tctx.toRemoveObjs.add(oldInnerType);
                  if (!isDynamicType() && !sys.isDynamicRuntime()) {
                     sys.setStaleCompiledModel(true, "Recompile needed: object: ", oldInnerType.typeName, " removed from compiled type: ", typeName);
                  }
               }
               // object used to compare newDef.equals(oldInnerType) here but if we update the parent type and not the
               // child type, it leaves things out of sync.   Next time the parent gets updated, it tries to update
               // with a version of the object that is not current.
               else if (newDef instanceof TypeDeclaration) {
                  TypeDeclaration newInnerType = (TypeDeclaration) newDef;

                  // if the types are the same, recursively replace the type.  Because property assignments can be applied to
                  // both compiled and dynamic types, we don't do errors here
                  if (ModelUtil.sameTypes(newInnerType.getExtendsTypeDeclaration(), oldInnerType.getExtendsTypeDeclaration())) {
                     tctx.toUpdateObjs.add(oldInnerType);
                  }
                  // else if it is a completely new definition, remove the old type and add the new one
                  else {
                     tctx.toRemoveObjs.add(oldInnerType);
                     tctx.toAddObjs.add(newInnerType);
                  }
               }
               else {
                  System.out.println("*** Not updating old inner type: " + oldInnerType.typeName + " with: " + newDef);
               }
            }
            else if (oldBodyDef instanceof AbstractMethodDefinition) {
               AbstractMethodDefinition oldMeth = (AbstractMethodDefinition) oldBodyDef;
               if (oldMeth.name != null) {
                  Object methObj = newType.definesMethod(oldMeth.name, oldMeth.getParameterList(), null, null, false, false, null, null);
                  if (methObj instanceof AbstractMethodDefinition) {
                     AbstractMethodDefinition newMeth = (AbstractMethodDefinition) methObj;
                     oldMeth.replaced = true;
                     if (newMeth != null) {
                        if (oldMeth == newMeth)
                           System.out.println("*** REPLACING A METHOD WITH ITSELF!");
                        oldMeth.replacedByMethod = newMeth;
                        // Stripping away this layered on method so now the newMeth is the method
                        if (updateMode == TypeUpdateMode.Remove && newMeth.replacedByMethod == oldMeth)
                           newMeth.replacedByMethod = null;
                     }
                  }
               }
            }
         }
      }


      if (activated && newType.body != null) {
         for (int i = 0; i < newType.body.size(); i++) {
            Object newBodyDef = newType.body.get(i);
            if (newBodyDef instanceof FieldDefinition) {
               FieldDefinition newFieldDef = (FieldDefinition) newBodyDef;

               for (VariableDefinition newVarDef : newFieldDef.variableDefinitions) {
                  List<Object> oldVarDefListObj = tctx.oldFieldIndex.get(newVarDef.variableName);
                  if (oldVarDefListObj == null) {
                     tctx.toAddFields.add(newVarDef);
                  }
                  else if (!oldVarDefListObj.contains(newVarDef) && !tctx.toUpdateFields.contains(newVarDef)) {
                     tctx.toUpdateFields.add(newVarDef);
                  }
               }
            }
            else if (newBodyDef instanceof AbstractMethodDefinition) {
               AbstractMethodDefinition newMeth = (AbstractMethodDefinition) newBodyDef;
               Object oldMethObj = declaresMethod(newMeth.name, newMeth.getParameterList(), null, null, false, false, null, null, false);
               if (oldMethObj instanceof ParamTypedMethod)
                  oldMethObj = ((ParamTypedMethod) oldMethObj).method;
               AbstractMethodDefinition oldMeth = (AbstractMethodDefinition) oldMethObj;
               if (oldMeth != null) {
                  if (oldMeth == newMeth)
                     System.out.println("*** Replacing a method by itself!");
                  oldMeth.replacedByMethod = newMeth;
                  oldMeth.replaced = true;
               }
               if (doChangeMethods && (oldMeth == null || !oldMeth.deepEquals(newMeth))) {
                  BodyTypeDeclaration enclType = newMeth.getEnclosingType();
                  if (enclType.changedMethods == null)
                     enclType.changedMethods = new TreeSet<String>();
                  enclType.changedMethods.add(newMeth.getMethodName());
               }

               if (newBodyDef instanceof ConstructorDefinition && oldMeth == null)
                  tctx.newConstructors = true;
            }
            else if (newBodyDef instanceof BlockStatement) {
               BlockStatement newBlock = (BlockStatement) newBodyDef;
               if (findBlockStatement(newBlock) == null)
                  tctx.toExecBlocks.add(newBlock);
            }
            else if (newBodyDef instanceof TypeDeclaration) {
               TypeDeclaration newInnerType = (TypeDeclaration) newBodyDef;

               if (newInnerType.typeName == null)
                  continue;

               List<Object> oldDefs = tctx.oldFieldIndex.get(newInnerType.typeName);
               Object oldDef;
               if (oldDefs == null)
                  oldDef = tctx.oldTypeIndex.get(newInnerType.typeName);
               else
                  oldDef = oldDefs.get(0);


               // Totally new object
               if (oldDef == null) {
                  tctx.toAddObjs.add(newInnerType);
               }

               // Because we are now inheriting some definitions we may find the old and new are the same - just skip those.
               if (oldDef != newBodyDef) {
                  if (updateMode != TypeUpdateMode.Replace) {
                     if (oldDef instanceof ModifyDeclaration) {
                        ModifyDeclaration oldMod = ((ModifyDeclaration) oldDef);
                        if (updateMode == TypeUpdateMode.Remove)
                           oldDef = oldMod;
                        else
                           oldDef = oldMod.getModifiedType();
                        if (oldDef instanceof TypeDeclaration) {
                           TypeDeclaration oldTypeDef = (TypeDeclaration) oldDef;
                           if (!tctx.toUpdateObjs.contains(oldTypeDef))
                              tctx.toUpdateObjs.add(oldTypeDef);
                        }
                     }
                     else if (oldDef instanceof ClassDeclaration) {
                        TypeDeclaration oldTypeDef = (TypeDeclaration) oldDef;
                        if (!tctx.toUpdateObjs.contains(oldTypeDef))
                           tctx.toUpdateObjs.add(oldTypeDef);
                     }
                  }
               }
            }
            else if (newBodyDef instanceof PropertyAssignment) {
               PropertyAssignment pa = (PropertyAssignment) newBodyDef;
               List<Object> oldDefObjs = tctx.oldFieldIndex.get(pa.propertyName);
               if ((oldDefObjs == null || !oldDefObjs.contains(pa)) && !tctx.toUpdateFields.contains(pa))
                  tctx.toUpdateFields.add(pa);
            }
         }
      }

      // Now we build the updateTypeContexts for each of the subtypes - need to do this before we have stopped and removed the parent type
      // or else some of the logic there does not work right.
      for (int i = 0; i < tctx.toUpdateObjs.size(); i++) {
         TypeDeclaration oldInnerType = tctx.toUpdateObjs.get(i);
         Object newFieldObj = null;
         List<Object> newFieldObjs = tctx.newFieldIndex.get(oldInnerType.typeName);
         if (newFieldObjs != null)
            newFieldObj = newFieldObjs.get(0);
         TypeDeclaration newInnerType = (TypeDeclaration) newFieldObj;
         if (newInnerType == null)
            newInnerType = (TypeDeclaration) tctx.newTypeIndex.get(oldInnerType.typeName);
         if (oldInnerType != newInnerType) { // Not sure why this is necessary but clearly don't update if it's the same thing
            UpdateTypeCtx innerTypeCtx = oldInnerType.buildUpdateTypeContext(newInnerType, updateMode, info, activated);
            tctx.toUpdateCtxs.add(innerTypeCtx);
         }
         else
            tctx.toUpdateCtxs.add(null);
      }

      return tctx;
   }

   void updateTypeInternals(UpdateTypeCtx tctx, BodyTypeDeclaration newType, ExecutionContext ctx, TypeUpdateMode updateMode, boolean updateInstances, UpdateInstanceInfo info, boolean outerType) {
      dependentTypes = null;

      boolean activated = layer != null && layer.activated;

      LayeredSystem sys = getLayeredSystem();
      // First remove any old objects - note that we're using the old indexes so this has to be done before
      // we go and update the properties table in each instance.
      for (int i = 0; i < tctx.toRemoveObjs.size(); i++) {
         // if static, null the slot in the static fields
         TypeDeclaration oldInnerType = tctx.toRemoveObjs.get(i);
         if (activated && (ModelUtil.isObjectType(oldInnerType) || ModelUtil.isEnum(oldInnerType))) {
            if (oldInnerType.hasModifier("static")) {
               if (staticValues != null) {
                  staticValues[getDynStaticFieldIndex(oldInnerType.typeName)] = null;
               }
            }
            // else find all referencing instances.  use dynChildManager to remove the child and null out the slot
            else {
               newType.updatePropertyForType(oldInnerType, ctx, InitInstanceType.Remove, updateInstances, info);
            }
         }
         oldInnerType.removeType();
      }

      // If this type was modified by another type, copy over its replacedByType
      if (updateMode == TypeUpdateMode.Replace && !replaced) {
         // Need to update the type who is modifying this one so it points to the new type.  Can't use the sub-types table anymore cause we don't add that for them.
         // Need to do this before our replacedByType is set.
         if (isModifiedBySameType()) {
            BodyTypeDeclaration modifiedByType = getRealReplacedByType();
            modifiedByType.updateExtendsType(newType, true, false); // modify only - do not do the extends type
         }

         if (newType == replacedByType)
            System.err.println("*** Replacing a type with itself!");
         newType.replacedByType = replacedByType;
         if (replacedByType != null)
            replacedByType.replacesType = newType;
         if (activated)
            replaced = true;
         else
            replacedInactive = true;
      }

      // Now we are replaced by another type (note dual purpose use of "replacedByType" modulated by replaced flag)
      // If we are a modified type, do not replace this type if we are coming from a subclass (modifyInherited)
      if (updateMode == TypeUpdateMode.Replace || ((newType instanceof ModifyDeclaration) && !((ModifyDeclaration) newType).modifyInherited)) {
         if (this == newType)
            System.err.println("*** Replacing a type with itself!");

         // In the IDE, if we set replacedByType here, we end up with one long chain of references and so never free models during editing
         if (activated) {
            replacedByType = newType;
            if (updateMode == TypeUpdateMode.Replace)
               replaced = true; // Don't set replaced unless we set replacedByType because that getLayerTypeDeclaration returns the wrong type and we can get into an infinite loop by returning the modifying model in place of this one
         }
         else
            replacedInactive = true;
         // else - TODO: do we need to set removed here or some other flag to indicate this is not the current model?
      }

      // In this case, we are stripping off a type layer... "this" type is being removed and replaced by some previous type in the chain.
      if (updateMode == TypeUpdateMode.Remove && (this instanceof ModifyDeclaration && !((ModifyDeclaration) this).modifyInherited)) {
         ModifyDeclaration thisModify = (ModifyDeclaration) this;
         Object modTypeObj = thisModify.getDerivedTypeDeclaration();
         if (modTypeObj instanceof BodyTypeDeclaration && modTypeObj != newType) {
            BodyTypeDeclaration modType = (BodyTypeDeclaration) modTypeObj;
            if (modType.replacedByType == this)
               modType.replacedByType = null;
         }
         if (this == newType)
            System.err.println("*** Replacing a type with itself!");
         if (activated)
            replacedByType = newType;
         replaced = true;
         newType.replacedByType = null;
      }

      if (updateMode == TypeUpdateMode.Replace) {
         if (this instanceof ModifyDeclaration) {
            ModifyDeclaration oldModType = (ModifyDeclaration) this;
            // If we're updating a type that's been modified, make sure the modifying type points to the new type
            // We use to call getModifiedType() on the new type not the old type for some reason. We also don't want to do any resolving here because
            // the IDE is not in a state in which it can resolve types and it seems like we only need to do this update if it's already been resolved.
            BodyTypeDeclaration modifiedType = oldModType.modifyTypeDecl;
            // Enum constants won't have a modified type so nothing to update here
            if (modifiedType != null && !oldModType.modifyInherited) {
               if (!ModelUtil.sameTypes(modifiedType, newType))
                  System.out.println("*** Error - updateType called with mismatching type");
               else
                  modifiedType.replacedByType = newType;
            }
         }

         if (activated && (isDynamicType() || sys.isDynamicRuntime())) {
            // Need to do this before we replace the sub-type table so that we can find the old types
            updateModelBaseType(newType);
         }

         // Register the type now: replace it in the LayeredSystem's index tables
         if (this instanceof TypeDeclaration && activated)
            sys.replaceTypeDeclaration((TypeDeclaration) this, (TypeDeclaration) newType);

         if (outerType) {
            // If this is the root, the old type needs to remove itself from the sub-type table at least.  We also stop things to be sure
            // no stale bits of the old model are being used in the new model.  Since the root will stop the children, we do not have to stop
            // them as well
            stop();
         }
      }
      else {
         // For the remove operation we have to remove the oldType from the subTypes list before we update the model
         // type or else we'll find the old type as a sub-type of the new-type and try to update it as well.
         if (updateMode == TypeUpdateMode.Remove)
            unregister();


         // Used to only do this for dynamic types, but if we are modifying a compiled type which is modify inherited
         // we need to re-register the type under the new type name.  for example, the instance for
         // UnitConverterInst.converters would be originally registered under the type UnitConverter.ocnverters.
         //if (isDynamicType())
         if (activated)
            updateModelBaseType(newType);

         // This type should already be in the global src types so no need to update that, but we do need to
         // replace the sub-types since this is now the visible type for this name in the system.  When
         // this is a "modifyInherited" case, it is not actually replacing the type, but extending it so
         // do not do the update in that case.
         if (this instanceof TypeDeclaration && activated) {
            // TODO: this is probably not needed at all now that we store the sub-types table by type name.
            if (!(newType instanceof ModifyDeclaration) || !((ModifyDeclaration) newType).modifyInherited)
               sys.replaceSubTypes((TypeDeclaration) this, (TypeDeclaration) newType);
         }


         if (updateMode == TypeUpdateMode.Remove)
            removeType();
         else
            unregister(); // Maybe this could be done before updateModelBaseType along with remove?
      }

      // Now we need to update the internals of each of the inner types - before we start the new type.
      // That way, we have a clean new model in this new type before we resolve references when starting the new type
      for (int i = 0; i < tctx.toUpdateObjs.size(); i++) {
         TypeDeclaration oldInnerType = tctx.toUpdateObjs.get(i);
         UpdateTypeCtx innerTypeCtx = tctx.toUpdateCtxs.get(i);
         Object newFieldObj = null;
         List<Object> newFieldObjs = tctx.newFieldIndex.get(oldInnerType.typeName);
         if (newFieldObjs != null)
            newFieldObj = newFieldObjs.get(0);
         TypeDeclaration newInnerType = (TypeDeclaration) newFieldObj;
         if (newInnerType == null)
            newInnerType = (TypeDeclaration) tctx.newTypeIndex.get(oldInnerType.typeName);
         if (oldInnerType != newInnerType) { // Not sure why this is necessary but clearly don't update if it's the same thing
            if (innerTypeCtx == null)
               System.err.println("*** Mismatch for updateInnerType!");
            oldInnerType.updateTypeInternals(innerTypeCtx, newInnerType, ctx, updateMode, updateInstances, info, false);
         }
      }
   }


   /**
    * Updates this type with the new type.  If replacesType is true, we are replacing this exact type.  If it is false
    * we are adding a new layered version of this type.  In other words, adding a new modify that modifies this type.
    */
   public void updateType(BodyTypeDeclaration newType, ExecutionContext ctx, TypeUpdateMode updateMode, boolean updateInstances, UpdateInstanceInfo info) {
      if (info == null && updateInstances)
         System.out.println("*** Error - no update instance info and updating instances");
      if (typeName == null || newType.typeName == null) {
         displayError("updateType - skipping update for type with no name.");
         return;
      }
      // Are we removing a type layer?  If so we first modify the previous type, then remove this type
      if (updateMode == TypeUpdateMode.Remove && this instanceof ModifyDeclaration) {
         ModifyDeclaration modThis = (ModifyDeclaration) this;
         BodyTypeDeclaration nextToRemove = modThis.getModifiedType();
         // Need to unpeel the onion, so we remove the earliest type first, then the later ones.  TODO: possibly move this out one level to updateModel?
         if (nextToRemove != newType) {
            nextToRemove.updateType(newType, ctx, updateMode, updateInstances, info);
            modThis.updateExtendsType(newType, true, false);
            updateType(newType, ctx, updateMode, updateInstances, info);
            return;
         }
      }

      boolean activated = layer != null && layer.activated;

      if (!getTypeClassName().equals(newType.getTypeClassName())) {
         displayError("Invalid updateType - expected type: " + typeName + " but found: " + newType.typeName + " for: ");
         return;
      }

      UpdateTypeCtx tctx = buildUpdateTypeContext(newType, updateMode, info, activated);

      updateTypeInternals(tctx, newType, ctx, updateMode, updateInstances, info, true);

      // Start the type after it's fully registered into the new type system so any new references we create point
      // to the new model.  If we are batching updates do not start the type here.  We may be updating many types and
      // if we start it here, we'll resolve references to other types that will become stale.
      if (activated && updateMode != TypeUpdateMode.Remove && info == null)
         ParseUtil.realInitAndStartComponent(newType);

      completeUpdateType(tctx, newType, ctx, updateMode, updateInstances, info, true, activated);
   }

   void completeUpdateType(UpdateTypeCtx tctx, BodyTypeDeclaration newType, ExecutionContext ctx, TypeUpdateMode updateMode, boolean updateInstances, UpdateInstanceInfo info, boolean outerType, boolean activated) {
      LayeredSystem sys = getLayeredSystem();
      String fullTypeName = getFullTypeName();
      boolean skipAdd = false;
      boolean skipUpdate = false;
      if (activated) {
         if (dynamicNew && (tctx.toAddObjs.size() > 0 || tctx.toAddFields.size() > 0)) {
            dynamicNew = false;
            dynamicType = true;
         }
         // TODO: should we use sys.isClassLoaded here as well as hasInstancesOfType?
         if (!tctx.dynamicNew && (tctx.toAddObjs.size() > 0 || tctx.toAddFields.size() > 0) && sys.hasInstancesOfType(fullTypeName)) {
            if (tctx.toAddFields.size() > 0)
               sys.setStaleCompiledModel(true, "Recompile needed to add fields: " + tctx.toAddFields + " to compiled type: " + fullTypeName);
            if (tctx.toAddObjs.size() > 0)
               sys.setStaleCompiledModel(true, "Recompile needed to add inner objects: " + tctx.toAddObjs + " to compiled type: " + fullTypeName);
            skipAdd = true;
         }
         if (tctx.newConstructors) {
            if (tctx.dynamicNew && tctx.dynamicStub && sys.isClassLoaded(getCompiledClassName()))
               sys.setStaleCompiledModel(true, "Recompile needed - constructor added to dynamic stub class: " + fullTypeName);
         }

         // Any change to the extends type will require a recompile if there are any instances of that type outstanding
         // Do this after we've added everybody to the tables
         Object compiledExtendsType = prevCompiledExtends;
         Object oldExtType = tctx.oldExtType;
         Object newExtType = newType.getExtendsTypeDeclaration();
         boolean prevExtends = false;
         if (compiledExtendsType != null) {
            prevExtends = true;
            oldExtType = compiledExtendsType;
         }
         if (!ModelUtil.sameTypes(oldExtType, newExtType)) {
            boolean classLoaded = sys.isClassLoaded(fullTypeName);
            boolean hasInstances = sys.hasInstancesOfType(fullTypeName);
            // Only a problem if there are any instances of this type outstanding right now
            if (!tctx.dynamicNew) {
               sys.setStaleCompiledModel(true, "Recompile needed: " + typeName + " 's " + (prevExtends ? "previous " : "") + "extends type changed from: " + oldExtType + " to: " + newExtType);
               skipAdd = true;
               skipUpdate = true;
            }
            // Don't warn for the stale class loader type until we have a new extends type which is incompatible.  That will avoid one restart warning...
            else if (hasInstances || classLoaded || (newExtType != null && sys.staleClassLoader)) {
               sys.setStaleCompiledModel(true, "Recompile needed: " + " type: " + typeName + "'s " + (prevExtends ? "previous " : "") + "extends type changed from: " + oldExtType + " to: " + newExtType + (hasInstances ? " to update active instances" : (classLoaded ? " to reload compiled class " : " layers have been removed making class loaders stale")));
               skipAdd = true;
               skipUpdate = true;
            }
            // TODO: classLoaded is always false here - do we need this code anymore?
            else if (classLoaded && oldExtType != null && newExtType == null) {
               newType.prevCompiledExtends = oldExtType;
            }
         }
      }

      if (updateMode != TypeUpdateMode.Remove && info != null) {
         info.typeChanged(this, newType);

         Iterator<BodyTypeDeclaration> subTypes = sys.getSubTypesOfType((TypeDeclaration) this);
         if (subTypes != null) {
            while (subTypes.hasNext()) {
               BodyTypeDeclaration subType = subTypes.next();
               subType = subType.resolve(true);
               info.typeChanged(subType, subType);
            }
         }
      }

      if (!skipAdd && activated) {
         for (int i = 0; i < tctx.toAddObjs.size(); i++) {
            // if static,  the slot in the static fields
            TypeDeclaration newInnerType = tctx.toAddObjs.get(i);
            if (ModelUtil.isObjectType(newInnerType) || ModelUtil.isEnum(newInnerType)) {
               if (newInnerType.isStaticType()) {
                  if (newType.staticValues != null) {
                     int ix = newType.getDynStaticFieldIndex(newInnerType.typeName);
                     if (ix == -1)
                        System.err.println("*** Error: can't find typeName: " + newInnerType.typeName + " as dyn static field");
                     else
                        newType.staticValues[ix] = DynObject.lazyInitSentinel;
                  }
               }
               else {
                  newType.addInstMemberToPropertyCache(newInnerType.typeName, newInnerType);
                  // Will init with the lazy-init sentinel for this slot in each referencing instance
                  newType.updatePropertyForType(newInnerType, ctx, InitInstanceType.Init, updateInstances, info);
               }
            }
         }

         // We're just adding to the property cache instead of rebuilding the table.  To build, we'd have to remap all
         // of the data bindings
         for (int i = 0; i < tctx.toAddFields.size(); i++) {
            IVariableInitializer varDef = tctx.toAddFields.get(i);
            newType.addInstMemberToPropertyCache(varDef.getVariableName(), varDef);
         }
      }

      if (!skipUpdate && activated) {
         // Now that stuff is in a stable state, re-initialize any properties that need to be initialized
         // This should fill in any uninitialized slots (all new fields are here) plus any properties whose initializers
         // are changed we run those.  Note we're keeping the order the same as they appear in the body of this type.
         for (int i = 0; i < tctx.toAddFields.size(); i++) {
            IVariableInitializer varDef = tctx.toAddFields.get(i);
            newType.updatePropertyForType((JavaSemanticNode) varDef, ctx, InitInstanceType.Init, updateInstances, info);
         }
         for (int i = 0; i < tctx.toUpdateFields.size(); i++) {
            IVariableInitializer varDef = tctx.toUpdateFields.get(i);
            newType.updatePropertyForType((JavaSemanticNode) varDef, ctx, InitInstanceType.Init, updateInstances, info);
         }
         for (int i = 0; i < tctx.toRestoreFields.size(); i++) {
            IVariableInitializer varDef = tctx.toRestoreFields.get(i);
            newType.updatePropertyForType((JavaSemanticNode) varDef, ctx, InitInstanceType.Init, updateInstances, info);
         }
         for (int i = 0; i < tctx.toUpdateObjs.size(); i++) {
            TypeDeclaration oldInnerType = tctx.toUpdateObjs.get(i);
            Object newFieldObj = null;
            List<Object> newFieldObjs = tctx.newFieldIndex.get(oldInnerType.typeName);
            if (newFieldObjs != null)
               newFieldObj = newFieldObjs.get(0);
            TypeDeclaration newInnerType = (TypeDeclaration) newFieldObj;
            if (newInnerType == null)
               newInnerType = (TypeDeclaration) tctx.newTypeIndex.get(oldInnerType.typeName);
            if (oldInnerType != newInnerType) // Not sure why this is necessary but clearly don't update if it's the same thing
               oldInnerType.completeUpdateType(tctx.toUpdateCtxs.get(i), newInnerType, ctx, updateMode, updateInstances, info, false, activated);
         }
      }

      if (!skipAdd && activated) {
         for (int i = 0; i < tctx.toAddObjs.size(); i++) {
            // if static,  the slot in the static fields
            TypeDeclaration newInnerType = tctx.toAddObjs.get(i);
            if (ModelUtil.isObjectType(newInnerType)) {
               if (!newInnerType.isStaticType()) {
                  // Will init with the lazy-init sentinel for this slot in each referencing instance
                  newType.updatePropertyForType(newInnerType, ctx, InitInstanceType.Add, updateInstances, info);
               }
            }
            sys.notifyInnerTypeAdded(newInnerType);
         }

         // This should be the list of all block statements which did not exist in the old type or which have changed
         for (int i = 0; i < tctx.toExecBlocks.size(); i++) {
            BlockStatement bs = tctx.toExecBlocks.get(i);

            if (info != null) {
               info.addBlockStatement(this, bs);
            }
            else {
               execBlockStatement(bs, ctx);
            }
         }
      }

      /* If there are connected clients listening to this model, they will be synchronized on the clientTypeDeclaration
         - some of the fields in that may have changed but for now, just refresh the entire thing and reuse the instance
         but we can only do the refreshClientTypeDeclaration if the type has been started. Otherwise we do this in the
         post-process of the UpdateType in the info passed. */
      if (info == null && updateMode == TypeUpdateMode.Replace && clientTypeDeclaration != null) {
         newType.clientTypeDeclaration = clientTypeDeclaration;
         newType.refreshClientTypeDeclaration();
      }

      for (int i = 0; i < tctx.toRemoveObjs.size(); i++) {
         // Must be done after we remove the type from the name space as we'll skip the remove if this is not the last type for this type name
         sys.notifyInnerTypeRemoved(tctx.toRemoveObjs.get(i));
      }

      // Need to flush the member cache for any sub-types of this type
      incrSubTypeVersions();
   }

   public void execBlockStatement(BlockStatement bs, ExecutionContext ctx) {
      // From the IDE at least, we get here with ctx = null and we need a frame to run the code
      if (ctx != null) {
         if (bs.isStatic()) {
            // We'll will re-execute the static block only if we've already initialized this class.  If not, it's quite
            // likely we'll need to initialize the class when running the static code which will lead to double initialization
            if (staticValues != null)
               bs.exec(ctx);
         }
         else
            updateInstBlockStatement(bs, ctx);
      }
   }

   private BlockStatement findBlockStatement(BlockStatement bs) {
      if (body == null)
         return null;
      for (Statement st : body) {
         if (st instanceof BlockStatement) {
            if (st.equals(bs))
               return (BlockStatement) st;
         }
      }
      return null;
   }

   public void updateInstBlockStatement(BlockStatement bs, ExecutionContext ctx) {
      updateInstBlockStatementLeaf(bs, ctx);
   }

   public void updateInstBlockStatementLeaf(BlockStatement bs, ExecutionContext ctx) {
      // This type was replaced by another type
      if (replaced) {
         replacedByType.updateInstBlockStatementLeaf(bs, ctx);
         return;
      }
      JavaModel model = getJavaModel();
      LayeredSystem sys = model.layeredSystem;
      if (sys.options.liveDynamicTypes && ModelUtil.getLiveDynamicTypes(this)) {

         if (getRealReplacedByType() == null) {
            Iterator insts = sys.getInstancesOfType(getRuntimeFullTypeName());
            if (insts != null) {
               while (insts.hasNext()) {
                  Object inst = insts.next();

                  bs.execForObj(inst, ctx);
               }
            }
         }

         if (isModifiedBySameType()) {
            replacedByType.updateInstBlockStatementLeaf(bs, ctx);
         }

         Iterator<BodyTypeDeclaration> subTypes = sys.getSubTypesOfType((TypeDeclaration) this);
         if (subTypes != null) {
            while (subTypes.hasNext()) {
               BodyTypeDeclaration subType = subTypes.next();
               subType = subType.resolve(true);
               subType.updateInstBlockStatementLeaf(bs, ctx);
            }
         }
      }
      else if (ctx != null) {
         evalBlockStatement(bs, ctx);
      }
   }

   public void evalBlockStatement(BlockStatement blockSt, ExecutionContext ctx) {
      ParseUtil.initAndStartComponent(blockSt);
      if (blockSt.staticEnabled) {
         try {
            ctx.pushStaticFrame(this);
            blockSt.exec(ctx);
         }
         finally {
            ctx.popStaticFrame();
         }
      }
      else {
         Object curObj = ctx.getCurrentObject();
         if (curObj != null)
            blockSt.execForObj(curObj, ctx);
      }
   }

   public void updatePropertyForType(JavaSemanticNode overriddenAssign, ExecutionContext ctx, InitInstanceType iit, boolean updateInstances, UpdateInstanceInfo info) {
      updatePropertyForTypeLeaf(overriddenAssign, ctx, iit, updateInstances, info);
   }

   public void updateInstancesForProperty(JavaSemanticNode overriddenAssign, ExecutionContext ctx, InitInstanceType iit) {
      JavaModel model = getJavaModel();
      LayeredSystem sys = model.layeredSystem;
      Iterator insts = sys.getInstancesOfTypeAndSubTypes(getRuntimeFullTypeName());

      /** Make sure we are started */
      ModelUtil.ensureStarted(this, true);

      if (overriddenAssign.isStatic()) {
         if (sys.options.verbose) {
            System.out.println("Updating static property change: " + overriddenAssign.toSafeLanguageString());
         }
         updateStaticProperty(overriddenAssign, ctx);
      }
      else {
         if (sys.options.verbose) {
            String message = overriddenAssign.toString();
            if (insts.hasNext())
               System.out.println("Updating instances for property change: " + message + " for: " + iit);
            else
               System.out.println("No instances to update for property change: " + message + " for: " + iit);
         }
         if (insts != null) {
            while (insts.hasNext()) {
               Object inst = insts.next();
               // TODO: used to catch IllegalArgumentException here and setStaleCompiledModel but it ended up
               // hiding data type mismatches from the management UI. Maybe if we are doing a refresh we should
               // mark the model as stale higher up?
               initInstance(overriddenAssign, inst, ctx, iit);
            }
         }
      }

      if (isEnumConstant()) {
         Object enumValue = getEnumValue();
         if (enumValue != null) {
            try {
               initInstance(overriddenAssign, enumValue, ctx, iit);
            }
            catch (IllegalArgumentException exc) {
               sys.setStaleCompiledModel(true, "Failed to set enum val ue: ", ModelUtil.getPropertyName(overriddenAssign) + " for: " + iit);
            }
         }
         else
            System.err.println("*** Null enum value for: " + this + " for: " + iit);
      }
   }

   /*
   public void updatePropertyForTypeLeaf(JavaSemanticNode overriddenAssign, ExecutionContext ctx, InitInstanceType iit, boolean updateInstances) {
      updatePropertyForTypeLeaf(overriddenAssign, ctx, iit, updateInstances, null);
   }
   */

   boolean isConstructedType() {
      DeclarationType t = getDeclarationType();
      if (t != DeclarationType.OBJECT && t != DeclarationType.ENUM)
         return true;
      else {
         BodyTypeDeclaration enclType = getEnclosingType();
         if (enclType != null)
            return enclType.isConstructedType();
         return false;
      }
   }

   public void updatePropertyForTypeLeaf(JavaSemanticNode overriddenAssign, ExecutionContext ctx, InitInstanceType iit, boolean updateInstances, UpdateInstanceInfo info) {
      // This type was replaced by another type
      if (replaced && replacedByType != null) {
         replacedByType.resolve(false).updatePropertyForTypeLeaf(overriddenAssign, ctx, iit, updateInstances, info);
         return;
      }

      JavaModel model = getJavaModel();
      LayeredSystem sys = model.layeredSystem;

      // We can do some propagations on compiled instances which exist right now, but we can't change the class itself and so can't modify new instances created
      if (!isDynamicType() && isConstructedType() && model.layer.activated) {
         sys.setStaleCompiledModel(true, "Recompile needed to set property: " + ModelUtil.getPropertyName(overriddenAssign) + " on compiled type: " + typeName);
      }

      if (sys.options.liveDynamicTypes && ModelUtil.getLiveDynamicTypes(this)) {
         // Only do this once - for the most specific type if we are modifying a modified type
         if (replacedByType == null) {
            if (updateInstances) {
               // If info is passed we batch the updates and apply them after all type changes have been made.  That's because some new properties might get added that we need
               // to evaluate the new expression.  Need to do "remove" now though.  Otherwise we can't get the property to remove the child object.
               if (info != null && (iit != InitInstanceType.Remove || info.queueRemoves()))
                  info.addUpdateProperty(this, overriddenAssign, iit);
               else
                  updateInstancesForProperty(overriddenAssign, ctx, iit);
            }
         }

         String propName = ModelUtil.getPropertyName(overriddenAssign);

         // Also process this on any type which modifies this type - since those are not in the sub-types list - but not if the modified type overrides the initializer for this property.
         if (isModifiedBySameType()) {
            Object replacedMember = replacedByType.declaresMember(propName, MemberType.InitializerSet, null, null);
            Layer assignLayer = overriddenAssign.getJavaModel().getLayer();
            // We are walking our way back up the type hierarchy.  If we encounter a modified type which replaces this property in a subsequent layer, it's an overridden property we don't apply it.
            if (replacedMember == null || replacedMember == overriddenAssign || replacedMember == STOP_SEARCHING_SENTINEL || assignLayer.getLayerPosition() >= replacedByType.getLayer().getLayerPosition())
               replacedByType.updatePropertyForTypeLeaf(overriddenAssign, ctx, iit, updateInstances, info);
            else if (sys.options.verbose)
               System.out.println("Warning: not applying change: " + overriddenAssign + " in layer: " + assignLayer + " - overridden in layer " + replacedByType.getLayer() + " with: " + replacedMember);
         }

         Iterator<BodyTypeDeclaration> subTypes = sys.getSubTypesOfType((TypeDeclaration) this);
         if (subTypes != null) {
            while (subTypes.hasNext()) {
               BodyTypeDeclaration subType = subTypes.next();
               subType = subType.resolve(true);
               // If this property has been overridden in a super-type do not set it again
               Object memb;
               if ((memb = subType.declaresMember(propName, MemberType.PropertyAssignmentSet, null, null)) == null)
                  subType.updatePropertyForTypeLeaf(overriddenAssign, ctx, iit, updateInstances, info);
               else if (sys.options.verbose) {
                  System.out.println("Warning: sub-type: " + subType + " not applying change: " + overriddenAssign + " as it is overridden with: " + memb);
               }
            }
         }
      }
      // TODO: Verify that this is the right test for when we want to use the current object.
      if (info == null && iit == InitInstanceType.Init) {
         initCurrentObject(overriddenAssign, ctx, iit);
      }
   }

   private void updateStaticProperty(JavaSemanticNode prop, ExecutionContext ctx) {
      if (prop instanceof FieldDefinition) {
         FieldDefinition fd = (FieldDefinition) prop;
         for (VariableDefinition staticField : fd.variableDefinitions) {
            updateStaticProperty(staticField, ctx);
         }
      }
      else if (prop instanceof PropertyAssignment) {
         PropertyAssignment pa = (PropertyAssignment) prop;
         Object field = pa.assignedProperty;
         VariableDefinition varDef;
         if ((field instanceof VariableDefinition)) {
            varDef = (VariableDefinition) field;
            if (ModelUtil.isDynamicType(field)) {
               int staticIndex = getDynStaticFieldIndex(varDef.variableName);
               if (staticIndex != -1 && staticValues != null) {
                  if (pa.initializer != null) {
                     Object val = pa.initializer.eval(varDef.getRuntimeClass(), ctx);
                     if (pa.bindingDirection == null || pa.bindingDirection.doForward()) {
                        staticValues[staticIndex] = val;
                     }
                  }
               }
               else {
                  System.err.println("*** can't find static property");
               }
            }
            else {
               if (pa.initializer != null) {
                  Object staticVal = pa.initializer.eval(varDef.getRuntimeClass(), ctx);
                  if (varDef.bindingDirection == null || varDef.bindingDirection.doForward()) {
                     Class theClass = this.getCompiledClass();
                     if (!theClass.isInterface() && !varDef.getDefinition().hasModifier("final"))
                        DynUtil.setStaticProperty(theClass, varDef.variableName, staticVal);
                  }
               }
            }
         }
      }
      else if (prop instanceof VariableDefinition) {
         VariableDefinition varDef = (VariableDefinition) prop;
         Object newField;
         if (varDef.isDynamicType()) {
            if (staticValues != null) {
               int ix = getDynStaticFieldIndex(varDef.variableName);
               if (ix != -1) {
                  if (varDef.initializer != null) {
                     Object val = varDef.getStaticValue(ctx);
                     if (varDef.bindingDirection == null || varDef.bindingDirection.doForward())
                        DynUtil.setStaticProperty(this, varDef.variableName, val);
                  }
               }
               else {
                  System.err.println("*** can't find static property");
               }
            }
         }
         else {
            Object staticVal = varDef.getStaticValue(ctx);
            if (varDef.bindingDirection == null || varDef.bindingDirection.doForward())
               DynUtil.setStaticProperty(this.getCompiledClass(), varDef.variableName, staticVal);
         }
      }
   }

   public void initCurrentObject(JavaSemanticNode overriddenAssign, ExecutionContext ctx, InitInstanceType iit) {
      Object currentObj;
      if (ctx == null)
         return;
      // TODO: probably should mark things as stale if this object is not a singleton?
      if ((currentObj = ctx.getCurrentObject()) != null) {
         initInstance(overriddenAssign, currentObj, ctx, iit);
      }
      else {
         LayeredSystem sys = getLayeredSystem();
         if (sys.options.verbose)
            sys.verbose("No current object of type: ", typeName, " to set property: " + overriddenAssign);
      }
   }

   public enum InitInstanceType {
      Init, Add, Remove
   }

   public void initInstance(JavaSemanticNode overriddenAssign, Object currentObj, ExecutionContext ctx, InitInstanceType iit) {
      // Always need to use either the modified or replaced type when initializing an instance.   We can get here from
      // updatePropertyForTypeLeaf which may be on a modified type.
      if (replacedByType != null) {
         replacedByType.resolve(false).initInstance(overriddenAssign, currentObj, ctx, iit);
         return;
      }

      // ??? Use an interface here?
      if (overriddenAssign instanceof VariableDefinition)
         ((VariableDefinition) overriddenAssign).initDynamicInstance(currentObj, ctx);
      else if (overriddenAssign instanceof PropertyAssignment) {
         ((PropertyAssignment) overriddenAssign).initDynStatement(currentObj, ctx, TypeDeclaration.InitStatementMode.All, true);
      }
      else if (overriddenAssign instanceof TypeDeclaration) {
         TypeDeclaration innerType = (TypeDeclaration) overriddenAssign;
         int ix = getDynInstPropertyIndex(innerType.typeName);
         if (ix == -1) {
            System.out.println("*** Unable to find dynamic property: " + innerType.typeName + " in: " + typeName);
         }

         switch (iit) {
            case Init:
               if (innerType.isDynObj(false) && currentObj instanceof IDynObject) {
                  ((IDynObject) currentObj).setProperty(ix, DynObject.lazyInitSentinel, true);
               }
               break;
            case Add:
               IDynChildManager mgr;
               if (innerType.isDynObj(false) && currentObj instanceof IDynObject) {
                  mgr = getDynChildManager();
                  if (mgr != null)
                     mgr.addChild(currentObj, ((IDynObject) currentObj).getProperty(ix, false));
               }
               else {
                  JavaModel model = getJavaModel();
                  model.layeredSystem.setStaleCompiledModel(true, "Recompile needed to add new child object: ", innerType.typeName + " to compiled type: " + this);
               }
               break;
            case Remove:
               if (innerType.isDynObj(false) && currentObj instanceof IDynObject) {
                  mgr = getDynChildManager();
                  Object toRemove = ((IDynObject) currentObj).getProperty(ix, false);
                  if (toRemove != null) {
                     if (mgr != null)
                        mgr.removeChild(currentObj, toRemove);
                     // Since we do not remove slots we'll just null it out
                     ((IDynObject) currentObj).setProperty(ix, null, true);
                     getLayeredSystem().removeDynInstance(innerType.getFullTypeName(), toRemove);
                  }
               }
               else {
                  JavaModel model = getJavaModel();
                  model.layeredSystem.setStaleCompiledModel(true, "Recompile needed to remove child object: ", innerType.typeName + " from compiled type: " + this);
               }
               break;
         }
      }
   }

   public void removeType() {
      JavaModel model = getJavaModel();
      if (removed)
         return;

      removed = true;
      if (this instanceof TypeDeclaration) {
         TypeDeclaration td = (TypeDeclaration) this;
         if (!td.isEnumConstant())  // enum constants are not added to the type name list I think because they are just because they are BodyTypeDeclarations, unless they are modified
            model.layeredSystem.removeTypeByName(model.getLayer(), getFullTypeName(), td, null);
      }

      // We may be peeling off a layer, not actually removing the type
      if (getModifiedType() == null && getModifiedByType() == null) {
         removeInnerTypes(body);
         removeInnerTypes(hiddenBody);
      }
      stop();
   }

   private static void removeInnerTypes(SemanticNodeList<Statement> bodyList) {
      if (bodyList == null)
         return;
      for (int i = 0; i < bodyList.size(); i++) {
         Definition member = bodyList.get(i);
         if (member instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration innerType = (BodyTypeDeclaration) member;
            if (!innerType.replaced && !innerType.removed) {
               innerType.removeType();
               // Replaced by no other type - dump the instances...
               if (innerType.getModifiedType() == null && innerType.getModifiedByType() == null)
                  innerType.removeTypeInstances();

               // Must be done after we remove the type from the name space as we'll skip the remove if this is not the last type for this type name
               innerType.getLayeredSystem().notifyInnerTypeRemoved(innerType);
            }
         }
      }
   }

   /**
    * Removes any instances lying around for this type
    */
   public void removeTypeInstances() {
      LayeredSystem sys = getLayeredSystem();
      String typeName = getRuntimeFullTypeName();
      sys.removeGlobalObject(typeName);
      // Assumes that we'll call disposeInstances on any sub-type of this type.  Could just call getInstancesOfTypeAndSubTypes
      Iterator<Object> insts = sys.getInstancesOfType(typeName);
      if (insts != null) {
         while (insts.hasNext()) {
            Object inst = insts.next();
            // Do not remove children here because we'll do that by going through the type tree, thus removing them twice
            DynUtil.dispose(inst, false);
         }
      }
   }

   public CoalescedHashMap getMethodCache() {
      if (!started)
         start();
      if (getLayer().annotationLayer) {
         return ModelUtil.getMethodCache(getDerivedTypeDeclaration());
      }
      // TODO: are there other cases here we want to support - i.e. where a compiled type extends a source type
      throw new UnsupportedOperationException();
   }

   public Object createInstance() {
      return createInstance(new ExecutionContext(getJavaModel()), null, null);
   }

   public Object constructInstance(ExecutionContext ctx, Object outerObj, Object[] argValues, boolean fromSuper, boolean doInit, boolean initExt) {
      Object[] allValues;

      Class compClass = getCompiledClass();
      if (compClass == null || compClass == IDynObject.class)
         compClass = DynObject.class;

      boolean appendOuterObj = outerObj != null;

      /* First remove the inner parent if the object we are constructing is not an inner class */
      if (argValues.length > 0 || outerObj != null) {
         BodyTypeDeclaration encType = getEnclosingInstType();
         Object compEncType = ModelUtil.getEnclosingInstType(compClass);
         if (encType != null) {
            // Need to strip out the inner arg if we go from having it to not having it
            if (compEncType == null) {
               // This is weird... either the outer obj can come in through the args or the outerObj
               //outerObj = null;
               appendOuterObj = false;
               if (argValues.length > 0) {
                  if (outerObj == null)
                     outerObj = argValues[0];
                  Object[] remOuterArgs = new Object[argValues.length - 1];
                  System.arraycopy(argValues, 1, remOuterArgs, 0, remOuterArgs.length);
                  argValues = remOuterArgs;
               }
            }
         }
      }

      // When we're called from the super(...) identifier expression, the args we have are already converted to the type so do not filter them again
      //if (!fromSuper)  TODO: remove this test!  keeping it now only because of the history...  this is tricky code to get right for all of the cases
      //   argValues = getCompiledConstrArgs(argValues);
      //else
      argValues = getCompiledConstrArgs(argValues);

      BodyTypeDeclaration origConstructor = ctx.getOrigConstructor();
      if (origConstructor == null)
         origConstructor = this;
      allValues = addTypeDeclToConstrArgs(origConstructor, compClass, argValues, appendOuterObj);
      ctx.setOrigConstructor(null);

      if (outerObj != null && appendOuterObj)
         allValues = addObjectToArray(outerObj, allValues);

      Object inst = PTypeUtil.createInstance(compClass, null, allValues);
      if (inst != null && doInit) {
         // Normally we will create a dyn object with the dyn type in the constructor, but if a class implements IDynObject
         // but does not have that constructor, set the dynType here
         if (inst instanceof IDynObject) {
            IDynObject dynObj = (IDynObject) inst;
            Object dynType = dynObj.getDynType();
            if (dynType == null || dynType instanceof Class)
               dynObj.setDynType(origConstructor);
            else if (dynType != origConstructor && !(dynType instanceof EnumDeclaration) && !(inst instanceof DynEnumConstant))
               System.err.println("*** Invalid dyn type for instance");
         }

         initDynInstance(inst, ctx, true, false, outerObj, argValues, initExt, true);
      }

      return inst;
   }

   private Object[] addObjectToArray(Object toAdd, Object[] argValues) {
      Object[] allValues;
      if (argValues != null && argValues.length > 0) {
         allValues = new Object[argValues.length + 1];
         System.arraycopy(argValues, 0, allValues, 1, argValues.length);
         allValues[0] = toAdd;
      }
      else {
         allValues = new Object[]{toAdd};
      }
      return allValues;
   }

   public final static String dynObjectSignature = RTypeUtil.getSignature(BodyTypeDeclaration.class);
   public final static String altDynObjectSignature = RTypeUtil.getSignature(TypeDeclaration.class);

   private Object[] addTypeDeclToConstrArgs(BodyTypeDeclaration typeDecl, Class compClass, Object[] argValues, boolean innerClass) {
      Object[] allValues;
      /* Now add the TypeDeclaration if this is a DynObject type and has the right constructor.  In some cases we might implement the IDynObject interface but not define the constructor (like for a compiled template type) */
      if (IDynObject.class.isAssignableFrom(compClass) && (ModelUtil.needsTypeDeclarationParam(compClass, innerClass))) {
         if (argValues != null && argValues.length > 0) {
            allValues = new Object[argValues.length + 1];
            System.arraycopy(argValues, 0, allValues, 1, argValues.length);
            allValues[0] = typeDecl;
         }
         else
            allValues = new Object[]{typeDecl};
      }
      else
         allValues = argValues;
      return allValues;
   }

   public Object createInstance(ExecutionContext ctx, String sig, List<Expression> args) {
      return createInstance(ctx, sig, args, null, null, -1);
   }

   public Object getObjectInstance() {
      String scopeName = getScopeName();
      ScopeDefinition scopeDef;
      if (scopeName == null) {
         scopeDef = GlobalScopeDefinition.getGlobalScopeDefinition();
      }
      else {
         scopeDef = GlobalScopeDefinition.getScopeByName(scopeName);
      }
      String typeName = getFullTypeName();
      ScopeContext ctx = scopeDef.getScopeContext(true);
      // Can't create an instance in this context
      if (ctx == null)
         return null;
      Object inst = ctx.getValue(typeName);
      if (inst != null)
         return inst;
      inst = createInstance();
      ctx.setValue(typeName, inst);
      return inst;
   }

   /**
    * Creates either an instance of a class or constructs the object instance. This is primarily used for dynamic types.
    * It mirrors the behavior of Java - essentially to first initialize the instance fields, and instance code of the
    * base-type, then call the constructor for the base type, then run the init code for each sub-type, and constructor
    * until hitting this type, then run the init code and constructor for this type.
    *
    * For components, the initialization of the instance goes in multiple passes. The first pass will initialize any simple
    * properties, the second will initialize all references, then call the preInit, init, and start methods.
    *
    * One tricky aspect of this code is that to invoke the constructor, we need to know if it will call super or not. If so,
    * the sub-type is set as the "orig constructor" but no instance is created. Instead, the IdentifierExpression for the
    * super(..) call will either chain itself like this type, or when we hit the final super() in the chain, the instance is
    * created. It's returned as the current object in the ExecutionContext.  When the instance is created, we need to set its
    * type to the type that we originally created the instance with but we don't set the outerObj until we unwind and hit the
    * first class in the chain with an enclosing type.
    *
    * TODO: some paths to creating dynamic types still construct the instance with the initExt=true parameter - i.e. initDynStatements
    * recursively initializes the statements in the extends type before initializing the sub-type. Although that works
    * fine when there are no constructor methods, it does not respect the proper ordering which is base-type-init code, base-type-constructor
    * sub-type init code, sub-type constructor.
    */
   public Object createInstance(ExecutionContext ctx, String sig, List<Expression> args, BodyTypeDeclaration dynParentType, Object outerObj, int dynIndex) {
      staticInit(); // Make sure the type is validated and the static values are initialized
      Object inst = null;

      if (getDeclarationType() != DeclarationType.OBJECT) {
         if (isDynamicNew()) {
            // Get this before we add the inner obj's parent
            ConstructorDefinition con = (ConstructorDefinition) declaresConstructor(args, null);

            int origNumArgs = args == null ? 0 : args.size();

            // First get the constructor values in the parent's context
            Object[] argValues = ModelUtil.constructorArgListToValues(this, args, ctx, outerObj);
            boolean success = false;
            boolean isDynStub = isDynamicStub(false);

            boolean needsInit = false;

            try {
               if (ctx == null)
                  ctx = new ExecutionContext(getJavaModel());
               // TODO: paramTypes should be providing a type context here right?
               if (con == null && origNumArgs > 0)
                  throw new IllegalArgumentException("No constructor matching " + args + " for: ");

               // If there are constructors and a base class (but we are not a dynamic stub), we need to do some work here.  The super(xx) call is the first
               // time we have the constructor/args to construct the instance.   In that case, mark the ctx,
               // set call the constructor.  when super is hit, it sees the flag in the ctx and creates the
               // instance (or propagates the pending constructor to the next super call).
               //
               // If there is a constructor and a base class but no super
               // call - using the implied zero arg constructor.  In that case, we do the init here.

               if (con == null || !con.callsSuper(false) || isDynStub) {
                  // Was emptyObjectArray for the args
                  inst = constructInstance(ctx, outerObj == null ? ModelUtil.getOuterObject(this, ctx) : outerObj, argValues, false, true, true);
                  needsInit = false;
               }
               else {
                  if (ctx.getOrigConstructor() == null)
                     ctx.setOrigConstructor(this);
                  ctx.setPendingConstructor(this);
                  ctx.setPendingOuterObj(outerObj);
                  needsInit = true;
               }

               if (con != null) {
                  /*
                  if (inst != null) {
                    ctx.pushCurrentObject(inst);
                  }
                  */

                  if (inst != null)
                     initDynInstance(inst, ctx, true, false, outerObj, null, false, false);

                  // Here's where we actually start invoking the constructor but the instance is not created until
                  // the IdentifierExpression for the super(..) in this constructor is called.  After that super
                  // expression returns, but before the rest of the instructor runs, the initDynInstance call is made
                  // to init the instance statements
                  con.invoke(ctx, Arrays.asList(argValues));

                  /*
                  if (inst != null)
                     ctx.popCurrentObject();
                  else if (ctx.currentObjects.size() > 0)
                     inst = ctx.getCurrentObject();
                  else
                     System.err.println("*** No current object created in constructor");

                  // Run the init code after we've run the constructor
                  initDynInstance(inst, ctx, true, false, outerObj == null ? ModelUtil.getOuterObject(this, ctx) : outerObj, argValues);
                  */
               }

               if (ctx.getPendingConstructor() != null)
                  throw new IllegalArgumentException("Failure to construct instance of type: " + typeName);
               success = true;
            }
            finally {
               if (success && ctx.currentObjects.size() > 0) {
                  inst = ctx.getCurrentObject();
                  if (inst != null) {
                     // Set the slot before we populate so that we can resolve cycles
                     if (dynIndex != -1) {
                        if (outerObj instanceof IDynObject) {
                           IDynObject dynParent = (IDynObject) outerObj;
                           dynParent.setProperty(dynIndex, inst, true);
                        }
                        /*
                        else
                           dynParentType.setDynStaticField(dynIndex, inst);
                        */
                     }

                     //if (needsInit) {
                     //   initDynInstance(inst, ctx, true, false, outerObj, null, false, false);
                     //}

                     initNewSyncInst(argValues, inst);

                     initDynComponent(inst, ctx, true, outerObj, argValues, false);
                     // Fetches the object pushed from constructInstance, as called indirectly when super() is evaled.
                  }
                  ctx.popCurrentObject();
               }
            }
         }
         else {
            Class cl = getCompiledClass();
            if (cl == null)
               System.out.println("*** No compiled class for type: " + typeName + " (compiled name:" + getCompiledClassName() + ")");

            inst = PTypeUtil.createInstance(cl, null, ModelUtil.constructorArgListToValues(cl, args, ctx, outerObj));

            // Set the slot before we populate so that we can resolve cycles
            if (dynIndex != -1) {
               if (outerObj instanceof IDynObject) {
                  IDynObject dynParent = (IDynObject) outerObj;
                  dynParent.setProperty(dynIndex, inst, true);
                  /*
                  else
                     dynParentType.setDynStaticField(dynIndex, inst);
                  */
               }
            }

            // If this is a compiled type, we should not be initializing the fields as dynamic.  They should already be compiled.
            //initDynInstance(inst, ctx, true, outerObj);
         }
      }
      else {
         Class cl = getCompiledClass();
         if (cl == null && !isDynamicType() && !allowDynamic) {
            displayError("No compiled class to create instance for compiled type: ");
            return null;
         }
         if (cl == null || cl == IDynObject.class) {
            cl = DynObject.class;
         }
         Object[] argValues = args != null ? ModelUtil.constructorArgListToValues(cl, args, ctx, outerObj) : null;
         IDynObjManager objMgr = getDynObjManager();
         boolean needsInit = true;
         if (objMgr != null) {
            // Use of DynUtil.createInstance should fully construct/init the object so we don't have to do so here
            inst = objMgr.createInstance(this, outerObj, argValues);
            if (inst != null)
               needsInit = false;
         }

         if (inst == null) {
            ConstructorPropInfo constrProps = getConstructorPropInfo();
            if ((argValues == null || argValues.length == 0) && constrProps != null) {
               int numProps = constrProps.propNames.size();
               if (numProps > 0) {
                  StringBuilder newSig = new StringBuilder();
                  int numArgs = numProps;
                  int argIx = 0;
                  if (outerObj != null) {
                     BodyTypeDeclaration enclInstType = getEnclosingInstType();
                     newSig.append(enclInstType.getSignature());
                     numArgs++;
                  }
                  argValues = new Object[numArgs];
                  if (outerObj != null) {
                     argValues[argIx++] = outerObj;
                  }
                  for (int cpix = 0; cpix < numProps; cpix++) {
                     VariableStatement initSt = (VariableStatement) constrProps.initStatements.get(cpix);
                     if (initSt != null && !initSt.isStarted()) {
                        initSt.parentNode = this;
                        ParseUtil.initAndStartComponent(initSt);
                     }
                     Expression init = initSt == null ? null : initSt.definitions.get(0).initializer;
                     if (init != null)
                        argValues[argIx++] = init.eval(ModelUtil.getCompiledClass(initSt.type.getTypeDeclaration()), ctx);
                     newSig.append(constrProps.propJavaTypes.get(cpix).getSignature(true));
                  }
                  sig = newSig.toString();
               }
            }
            if (isDynamicNew()) {
               if (argValues == null)
                  argValues = new Object[0];
               if (outerObj != null)
                  inst = getLayeredSystem().createInnerInstance(this, outerObj, sig, argValues);
               else
                  inst = getLayeredSystem().createInstance(this, sig, argValues);

               needsInit = false;
            }
            else {
               boolean innerObj = outerObj != null && ModelUtil.getEnclosingInstType(cl) != null;
               argValues = addTypeDeclToConstrArgs(this, cl, argValues, innerObj);
               // Need to add the outer instance - this is a compiled class that's an inner class.  There's probably a dynamic slot for this property as well
               // but we need to bypass it because that slot may not have the lazy init sentinel.
               if (innerObj) {
                  inst = TypeUtil.getPropertyValueFromName(outerObj, typeName, false);
               }
               else {
                  if (argValues == null)
                     inst = PTypeUtil.createInstance(cl, sig);
                  else
                     inst = PTypeUtil.createInstance(cl, sig, argValues);
               }
               needsInit = false;
            }
         }

         // Set the slot before we populate so that we can resolve cycles
         // Note: this will already have been done if we go through LayeredSystem.createInstance
         if (dynIndex != -1) {
            if (outerObj instanceof IDynObject) {
               IDynObject dynParent = (IDynObject) outerObj;
               dynParent.setProperty(dynIndex, inst, true);
            }
            /*
            else
               dynParentType.setDynStaticField(dynIndex, inst);
            */
         }

         if (needsInit) { // TODO: not sure this is needed anymore
            initDynInstance(inst, ctx, true, true, outerObj, argValues, true, true);
         }

         // TODO: remove this? it should now be done in DynObject.create because it needs to be registered before we call the initDynamicFields in case
         // there's a recursive reference to this type.
         registerGlobalObject(inst);
      }
      if (inst == null)
         throw new IllegalArgumentException("Unable to create instance of : " + typeName);

      return inst;
   }

   void registerGlobalObject(Object inst) {
      ScopeDefinition sd = getScopeDefinition();
      if (getEnclosingInstType() == null && (sd == null || sd.isGlobal()))
         getJavaModel().addGlobalObject(getFullTypeName(), inst);
   }

   // See also initDynSyncInst below which is similar but for DynObjects
   public void initNewSyncInst(Object[] argValues, Object inst) {
      if (getSyncProperties() != null) {
         Object[] plainArgValues = argValues;
         int len = argValues.length;
         if (getEnclosingInstType() != null && len >= 1)
            plainArgValues = Arrays.asList(argValues).subList(1, len).toArray();
         SyncManager.addSyncInst(inst, syncOnDemand, syncInitDefault, true, getScopeName(), null, plainArgValues);
      }
      initServerTag(inst);
   }

   public void initDynSyncInst(Object inst, Object[] params) {
      Object extType = getDerivedTypeDeclaration();
      // For compiled types, we compile in the addSyncInst call so it happens for each type.  Apparently we rely on this
      // happening for cases where the sub-class disables sync mode but it was on for the base class.
      if (extType instanceof BodyTypeDeclaration) {
         ((BodyTypeDeclaration) extType).initDynSyncInst(inst, params);
      }
      if (getSyncProperties() != null) {
         SyncManager.addSyncInst(inst, syncOnDemand, syncInitDefault, true, getScopeName(), null, params);
      }
      initServerTag(inst);
   }


   public int getMemberCount() {
      return body == null ? 0 : body.size();
   }

   public Object getCompiledImplements() {
      return null;
   }

   public int addChildNames(StringBuilder childNames, Map<String, StringBuilder> childNamesByScope, String prefix, boolean componentsOnly,
                            boolean thisClassOnly, boolean dynamicOnly, Set<String> nameSet, ArrayList<Object> objTypes) {
      Object extendsType = getDerivedTypeDeclaration();
      int ct = 0;
      if (extendsType instanceof TypeDeclaration && !thisClassOnly) {
         ct = ((TypeDeclaration) extendsType).addChildNames(childNames, childNamesByScope, prefix, componentsOnly, thisClassOnly, dynamicOnly, nameSet, objTypes);
      }
      StringBuilder useChildNames;

      if (dynamicOnly && !isDynamicType())
         return ct;

      for (int i = 0; i < 2; i++) {
         SemanticNodeList<Statement> theBody = i == 0 ? body : hiddenBody;
         if (theBody != null) {
            Object innerTypeObj;
            TypeDeclaration innerTypeDecl;
            IScopeProcessor typeScope;
            for (Statement statement : theBody) {
               innerTypeObj = getInnerObjectType(statement);

               if (innerTypeObj != null) {
                  if (innerTypeObj instanceof TypeDeclaration)
                     innerTypeDecl = (TypeDeclaration) innerTypeObj;
                  else
                     innerTypeDecl = null;

                  useChildNames = childNames;
                  if (innerTypeDecl != null) {
                     if (!innerTypeDecl.needsCompile()) // This inner type is not included in this runtime so don't add it to the child list
                        continue;
                     typeScope = innerTypeDecl.getScopeProcessor();
                     String groupName;
                     if (typeScope != null && !componentsOnly && (groupName = typeScope.getChildGroupName()) != null) {
                        useChildNames = childNamesByScope.get(groupName);
                        if (useChildNames == null) {
                           childNamesByScope.put(groupName, useChildNames = new StringBuilder());
                        }
                     }
                  }
                  else // TODO: else - do we need to process scopes in compiled types?
                     typeScope = null;

                  boolean isComponent = ModelUtil.isComponentInterface(getLayeredSystem(), innerTypeObj);

                  if (!componentsOnly || isComponent) {
                     String childTypeName = CTypeUtil.getClassName(ModelUtil.getTypeName(innerTypeObj));
                     String childPathName = prefix == null ? childTypeName : prefix + "." + childTypeName;
                     if (!nameSet.contains(childPathName)) {
                        if (useChildNames.length() > 0)
                           useChildNames.append(", ");

                        if (innerTypeDecl != null && innerTypeDecl.useNewTemplate && !dynamicOnly) {
                           // Don't want to new objects here since we'll just throw away these instances.
                           // If it is a factory, it's up to the template to do the initialization
                           if (!componentsOnly) {
                              useChildNames.append("new ");
                              useChildNames.append(childTypeName);
                              String contextParams = null;
                              if (typeScope != null)
                                 contextParams = typeScope.getContextParams();
                              if (contextParams != null) {
                                 useChildNames.append("(");
                                 useChildNames.append(TransformUtil.evalTemplate(innerTypeDecl.getScopeTemplateParameters(), contextParams, true, false, null));
                                 useChildNames.append(")");
                              }
                              else
                                 useChildNames.append("()");
                           }
                           else
                              continue;
                        }
                        else {
                           if (prefix != null) {
                              useChildNames.append(prefix);
                              useChildNames.append(".");
                           }
                           useChildNames.append(childTypeName);
                        }

                        // Only return the count of the default children
                        if (useChildNames == childNames)
                           ct++;

                        nameSet.add(childPathName);
                        if (objTypes != null)
                           objTypes.add(innerTypeObj);
                     }
                  }
               }
            }
         }
      }
      return ct;
   }

   private Object getInnerObjectType(Statement st) {
      TypeDeclaration typeDecl;
      if (st instanceof TypeDeclaration &&
              (typeDecl = (TypeDeclaration) st).getDeclarationType() == DeclarationType.OBJECT) {
         if (typeDecl.isHiddenType())
            return null;
         return typeDecl;
      }
      else if (st instanceof MethodDefinition) {
         MethodDefinition meth = (MethodDefinition) st;
         Object annot;
         Boolean isObject;
         String objName;
         if ((objName = meth.propertyName) != null && (annot = st.getAnnotation("TypeSettings", true)) != null &&
                 (isObject = (Boolean) ModelUtil.getAnnotationValue(annot, "objectType")) != null && isObject) {
            String ftn = getFullTypeName();
            String innerTypeNameL = CTypeUtil.prefixPath(ftn, objName);
            String innerTypeNameU = CTypeUtil.prefixPath(ftn, objName);
            Object innerType = findTypeDeclaration(innerTypeNameL, false);
            // Need to check both lower and upper cases to try and find the type.
            if (innerType == null) {
               innerType = findTypeDeclaration(innerTypeNameU, false);
            }
            if (innerType == null) {
               if (innerObjs != null) {
                  for (Object io : innerObjs) {
                     String ioName = ModelUtil.getTypeName(io);
                     if (innerTypeNameL.equals(ioName) || innerTypeNameU.equals(ioName)) {
                        innerType = io;
                        break;
                     }
                  }
               }
               // If we have ObjName.getObjName() that's not an error - just not an inner type
               if (innerType == null && !objName.equals(typeName) && !objName.equals(CTypeUtil.decapitalizePropertyName(typeName)))
                  System.out.println("*** Can't resolve inner type: " + innerTypeNameL);
            }
            return innerType;
         }
      }
      return null;
   }

   /**
    * Returns the name of the compiled class
    */
   public String getCompiledClassName() {
      // We are stale and are forced to use the actual compiled class name, not the one which would be computed for us now that the type has changed.
      if (staleClassName != null)
         return staleClassName;

      if (isDynamicNew()) {
         // If used in a class value expression or the framework requires one concrete Class for each type
         // we always return the full type name as the compiled type.
         // TODO: should this check if we are an inner type and use the stub name?  Or do we need to set dynCompiledInnerStub
         // so that the class has the proper full type name in this case?
         if (needsCompiledClass) {
            if (isDynInnerStub() && getEnclosingType() != null)
               return getInnerStubFullTypeName();
            return getRuntimeFullTypeName();
         }

         Object extendsType = getExtendsTypeDeclaration();
         if (!needsDynamicStub) {
            if (extendsType == null) {
               extendsType = getCompiledImplements();
            }
            if (extendsType == null) // A simple dynamic type - no concrete class required
               return getDefaultDynTypeClassName();
            // If we are extending a dynamic type or we do not need the dynamic type behavior in this class.
            if (isExtendsDynamicType(extendsType) || dynamicNew)
               return ModelUtil.getCompiledClassName(extendsType);
         }
         TypeDeclaration enclType = getEnclosingType();
         if (enclType != null)
            return getInnerStubFullTypeName();
         return getFullTypeName();
      }
      else {
         return ModelUtil.getTypeName(getClassDeclarationForType());
      }
   }

   public static boolean isExtendsDynamicType(Object extendsType) {
      // Make sure it's a dynamic type implements the IDynObject interface and has the appropriate constructor.  If we insert a regular compiled type in between a dyn stub
      // and another dynamic type, we need to create a new dynamic stub for the sub-type.
      /* ??? Not sure why we used to include this test below
      boolean oldTest = ModelUtil.getParamTypeBaseType(extendsType) instanceof Class;
      */
      return ModelUtil.isDynamicType(extendsType) || ModelUtil.isDynamicNew(extendsType) || (ModelUtil.isAssignableFrom(IDynObject.class, extendsType) && ModelUtil.hasDynTypeConstructor(extendsType));
   }

   /**
    * Converts the supplied type arguments which would be used for the current class to those from the extends class
    */
   public List<JavaType> getCompiledTypeArgs(List<JavaType> typeArgs) {
      if (isDynamicType()) {
         // If used in a class value expression or the framework requires one concrete Class for each type
         // we always return the full type name as the compiled type.
         // TODO: should this check if we are an inner type and use the stub name?  Or do we need to set dynCompiledInnerStub
         // so that the class has the proper full type name in this case?
         if (needsCompiledClass)
            return typeArgs;

         Object extendsType = getExtendsTypeDeclaration();
         if (!needsDynamicStub) {
            if (extendsType == null) {
               extendsType = getCompiledImplements();
            }
            if (extendsType == null) // A simple dynamic type - no concrete class required and so no type args
               return null;
            if (isExtendsDynamicType(extendsType)) { // Extending a dynamic type - just use that guys class
               typeArgs = mapTypeArgsForExtends(typeArgs);
               return ModelUtil.getCompiledTypeArgs(extendsType, typeArgs);
            }
         }
         return typeArgs;
      }
      else {
         Object declType = getClassDeclarationForType();
         if (declType != this) {
            Object extType = getExtendsTypeDeclaration();
            while (extType != null) {
               typeArgs = mapTypeArgsForExtends(typeArgs);
               if (extType == declType)
                  break;
               extType = ModelUtil.getExtendsClass(extType);
            }
            if (extType == null)
               return null;
         }
         return typeArgs;
      }
   }

   public Object[] getCompiledConstrArgs(Object[] args) {
      if (isDynamicType()) {
         // If used in a class value expression or the framework requires one concrete Class for each type
         // we always return the full type name as the compiled type.
         // TODO: should this check if we are an inner type and use the stub name?  Or do we need to set dynCompiledInnerStub
         // so that the class has the proper full type name in this case?
         if (needsCompiledClass)
            return args;

         Object extendsType = getExtendsTypeDeclaration();
         if (!needsDynamicStub) {
            if (extendsType == null) {
               extendsType = getCompiledImplements();
            }
            if (extendsType == null) // A simple dynamic type - no concrete class required and so no constructor args
               return emptyObjectArray;
            if (isExtendsDynamicType(extendsType) && args.length != 0) {
               Object extendsCompType = ModelUtil.getCompiledClass(extendsType);
               if (extendsCompType == null || extendsCompType == IDynObject.class)
                  return emptyObjectArray;

               TypeDeclaration extendsTypeDecl = extendsType instanceof TypeDeclaration ? (TypeDeclaration) extendsType : null;

               // Extending a dynamic type - just use that guys class if there's a zero arg constructor.  If this type is a dynamic stub it should have a matching
               // constructor.  If it inherits the compiled class and has no super() call, it must be using a default constructor so clear out the args.
               if (ModelUtil.declaresConstructor(getLayeredSystem(), extendsType, null, null) != null || ModelUtil.getConstructors(extendsType, null, true) == null ||
                       (extendsTypeDecl != null && !extendsTypeDecl.isDynamicStub(false) && extendsTypeDecl.usesDefaultConstructor())) {

                  return emptyObjectArray; // This assumes that we do not have a super call which is transforming the args and so using a zero arg constructor of the extends class
               }
            }
         }
      }
      else {
         Object declType = getClassDeclarationForType();
         if (declType != this) {
            Object extType = getExtendsTypeDeclaration();
            if (extType != null) {
               // again, assuming no super call - there must be a default constructor
               return emptyObjectArray;
            }
         }
      }
      return args;
   }

   /**
    * Is this a compiled dynamic stub constructor - which takes just the TypeDeclaration as the first parameter?
    */
   boolean usesDefaultConstructor() {
      Object[] constrs = getConstructors(null, false);
      if (constrs == null || constrs.length == 0)
         return true;
      for (Object constr : constrs) {
         if (constr instanceof ConstructorDefinition && !((ConstructorDefinition) constr).callsSuper(true))
            return true;
      }
      return false;
   }

   List<JavaType> mapTypeArgsForExtends(List<JavaType> typeArgs) {
      JavaType extType = getExtendsType();
      List<JavaType> extTypeArgs = extType.getResolvedTypeArguments();

      if (extTypeArgs == null)
         return null;
      ArrayList<JavaType> res = new ArrayList<JavaType>(extTypeArgs.size());
      for (int i = 0; i < extTypeArgs.size(); i++) {
         JavaType extParamJavaType = extTypeArgs.get(i);
         // For type parameters in the extJavaType, we need to find which slot they are in the src type and pick out the right argument
         if (extParamJavaType.isTypeParameter()) {
            String extParamName = extParamJavaType.getFullTypeName(); // In this case the param name

            List<?> typeParams = getClassTypeParameters();
            if (typeParams.size() != typeArgs.size()) {
               System.out.println("*** Error mismatching number of type params for: " + this);
               return null;
            }
            int j;
            for (j = 0; j < typeParams.size(); j++) {
               String paramName = ModelUtil.getTypeParameterName(typeParams.get(i));
               if (paramName.equals(extParamName)) {
                  res.add(typeArgs.get(j));
                  break;
               }
            }
            if (j == typeArgs.size()) {
               System.out.println("*** Failed to find the type parameter in the subclasses type params");
               return null;
            }
         }
         else {
            res.add(extParamJavaType);
         }
      }
      return res;
   }


   public String getCompiledTypeName() {
      return getCompiledClassName();
   }

   /**
    * Returns this type unless this class is not compiled into the runtime as a class.  In that case, it returns the real class
    */
   public Object getClassDeclarationForType() {
      BodyTypeDeclaration currentType = this;

      // Skip Modify declarations and those class decls which don't need their own type
      while (currentType != null && ((currentType instanceof ModifyDeclaration && !currentType.needsOwnClass(false)) || (currentType instanceof ClassDeclaration && currentType.getEnclosingType() != null && !currentType.needsOwnClass(false)))) {
         if (currentType instanceof ClassDeclaration && currentType.getDeclarationType() != DeclarationType.OBJECT)
            return currentType;

         Object nextType = currentType.getDerivedTypeDeclaration();
         if (nextType instanceof ParamTypeDeclaration)
            return nextType; // TODO: are there any cases here where we need to look at baseType to see if it is skipped?  In that case, we'd strip the type params and build a new ParamTypeDecl on that other type.
         if (ModelUtil.isCompiledClass(nextType)) {
            return nextType;
         }
         else if (nextType == null || !(nextType instanceof TypeDeclaration))
            return currentType;

         // Will currentType be transformed here?  If not, we basically have to determine if either the extends or modify type of the currentType is compiled.
         if (currentType instanceof ModifyDeclaration) {
            ModifyDeclaration modDecl = (ModifyDeclaration) currentType;
            Object nextModType = modDecl.getExtendsTypeDeclaration();
            if (nextModType instanceof ParamTypeDeclaration)
               return nextModType;
            if (ModelUtil.isCompiledClass(nextModType))
               return nextModType;
            if (!modDecl.needsOwnClass(false) && nextModType instanceof TypeDeclaration && ((TypeDeclaration) nextModType).needsOwnClass(false)) {
               // If the modify type includes the extends type just keep going as we'll encounter this extends type again
               // If it's a real compiled extends type that's new, we are compiled from here on out based on this class.
               if (!ModelUtil.isAssignableFrom(nextModType, nextType))
                  return nextModType;
            }
         }

         // If there are any compiled interfaces we need to compile a real type
         if (currentType.getCompiledImplements() != null) {
            return currentType;
         }
         currentType = (TypeDeclaration) nextType;
      }

      return currentType;
   }

   public Object[] getCompiledImplTypes() {
      return null;
   }

   public Object[] getCompiledImplJavaTypes() {
      return null;
   }

   public JavaType getExtendsType() {
      return null;
   }

   public void startExtendedType(BodyTypeDeclaration extendsType, String message) {
      JavaModel extendsModel = extendsType.getJavaModel();
      boolean doValidate = isValidated();
      boolean incrNest = false;
      JavaSemanticNode extendsNode = extendsModel == null ? extendsType : extendsModel;
      if (extendsModel != null && extendsModel.layer != null && extendsModel.isLayerModel && extendsModel.layer.excluded)
         return;
      // Do nothing if the type or model is already at the level we need it
      if ((!doValidate && extendsNode.isStarted()) || (doValidate && extendsNode.isValidated()))
         return;
      try {
         if (extendsModel != null && (extendsModel.layeredSystem == null || extendsModel.layeredSystem.options.verbose)) {
            System.out.println(StringUtil.indent(++LayeredSystem.nestLevel) + (!extendsType.isStarted() ? "Starting: " : "Validating: ") + message + " type " + extendsModel.getSrcFile() + " runtime: " + getLayeredSystem().getRuntimeName());
            incrNest = true;
         }
         // If this type is already validated, validate the extended type
         if (doValidate) {
            // work at the model level as it's important the model gets started
            ParseUtil.initAndStartComponent(extendsNode);
         }
         else
            ParseUtil.startComponent(extendsNode);
      }
      finally {
         if (incrNest)
            LayeredSystem.nestLevel--;
      }
   }

   public void convertToSrcReference() {
      ModelUtil.ensureStarted(this, false);
      JavaType extType = getExtendsType();
      if (extType != null) {
         boolean converted = extType.convertToSrcReference();
         if (converted) {
            Object typeDecl = getDerivedTypeDeclaration();
            typeDecl = ParamTypeDeclaration.toBaseType(typeDecl);
            if (typeDecl instanceof BodyTypeDeclaration)
               startExtendedType((BodyTypeDeclaration) typeDecl, "extends");
         }
      }
      List<?> implTypes = getImplementsTypes();
      if (implTypes != null) {
         int ix = 0;
         Object[] implBoundTypes = getImplementsTypeDeclarations();
         for (Object implObj : implTypes) {
            if (implObj instanceof JavaType) {
               JavaType implType = (JavaType) implObj;
               boolean converted = implType.convertToSrcReference();
               if (converted) {
                  Object newImplType = implType.getTypeDeclaration();
                  newImplType = ParamTypeDeclaration.toBaseType(newImplType);
                  if (newImplType instanceof BodyTypeDeclaration) {
                     startExtendedType((BodyTypeDeclaration) newImplType, "implements");
                  }
                  implBoundTypes[ix] = newImplType;
               }
            }
            ix++;
         }
      }
   }

   /**
    * Gets the extends type name without trying to resolve the type
    */
   /*
   public String getUnresolvedExtendsTypeName() {
      JavaType extType = getExtendsType();
      if (extType == null)
         return null;
      return extType.getFullBaseTypeName();
   }
   */
   public String getExtendsTypeName() {
      JavaType extType = getExtendsType();
      if (extType == null)
         return null;
      return extType.getAbsoluteBaseTypeName();
   }

   // Only need this method so that this property appears writable so we can sync it on the client.  Easy to implement if we do ever need it.
   @Constant
   public void setExtendsTypeName(String str) {
      throw new UnsupportedOperationException();
   }

   public List<?> getImplementsTypes() {
      return null;
   }

   public boolean bodyNeedsClass() {
      if (body != null) {
         for (Statement s : body) {
            //if (s instanceof AbstractMethodDefinition || s instanceof FieldDefinition || s instanceof TypeDeclaration || s instanceof BlockStatement)
            if (s.needsEnclosingClass())
               return true;
         }
      }
      // If this type is the source of a binding on a sub-property it will have a body after we transform it.
      if (propertiesToMakeBindable != null && propertiesToMakeBindable.size() > 0)
         return true;
      return false;
   }

   public boolean modifyNeedsClass() {
      return bodyNeedsClass();
   }

   /**
    * We need to generate a class for an object definition if it is a top-level class or there is a method or field
    */
   public boolean needsOwnClass(boolean checkComponents) {
      if (needsOwnClass)
         return true;

      // Only eliminate classes for objects.
      if (getDeclarationType() != DeclarationType.OBJECT)
         return true;

      if (ModelUtil.isCompiledClass(getDerivedTypeDeclaration()))
         return true;

      // Any compiled interfaces - need to generate a class
      if (getCompiledImplements() != null)
         return true;

      //if (bodyNeedsClass())
      //   return true;

      // This includes body needs class - when we call this on a modify type, we need to include the body descriptions of any of the types in the chain.
      if (modifyNeedsClass())
         return true;

      if (needsSync())
         return true;

      // Needs to ignore classes in the annotation layer since they won't be there at runtime
      Object concreteExtends = getConcreteExtendsType();
      // The checkComponents flag is used to avoid a recursive definition from getClassDeclarationFromType used to
      // determine the runtime class.  We only need to check this the first time so this speeds things up a little
      if (checkComponents && isComponentType() && (concreteExtends == null || !ModelUtil.isComponentType(concreteExtends))) {
         return true;
      }
      return false;
   }

   public Object getConcreteExtendsType() {
      Object type = getDerivedTypeDeclaration();
      while (type != null) {
         if (!(type instanceof TypeDeclaration))
            return type;

         TypeDeclaration typeDecl = (TypeDeclaration) type;
         JavaModel model = typeDecl.getJavaModel();
         Layer layer = model.getLayer();
         if (layer == null || !layer.annotationLayer)
            return type;
         type = typeDecl.getDerivedTypeDeclaration();
      }
      return null;
   }

   public boolean isStaticInnerClass() {
      return getEnclosingType() != null && isStaticType();
   }

   public abstract boolean getDefinesCurrentObject();

   public Expression getPropertyInitializer(String name) {
      if (body == null)
         return null;

      for (Statement s : body) {
         if (s instanceof PropertyAssignment) {
            PropertyAssignment pa = (PropertyAssignment) s;
            if (pa.propertyName.equals(name))
               return pa.initializer;
         }
      }

      Object extType = getDerivedTypeDeclaration();
      if (extType != null)
         return ModelUtil.getPropertyInitializer(extType, name);

      return null;
   }

   public String getTypeClassName() {
      return CTypeUtil.getClassName(typeName);
   }

   public void unregister() {
      String fullTypeName = getFullTypeName();
      if (fullTypeName != null) {
         LayeredSystem sys = getLayeredSystem();
         if (sys != null)
            sys.removeFromInnerTypeCache(fullTypeName);
      }
   }

   /**
    * Returns true if this type modifies the supplied type either directly or indirectly
    */
   public boolean modifiesType(BodyTypeDeclaration other) {
      for (BodyTypeDeclaration modType = getModifiedType(); modType != null; modType = modType.getModifiedType())
         if (modType == other)
            return true;
      return false;
   }

   public boolean isHiddenType() {
      return false;
   }

   /**
    * Overridden by ModifyDeclaration.  For "a.b" types, returns the value of "a" which replaces this element
    */
   public BodyTypeDeclaration replaceHiddenType(BodyTypeDeclaration parentType) {
      return null;
   }

   public BodyTypeDeclaration getHiddenRoot() {
      return null;
   }

   public boolean isEnumeratedType() {
      return false;
   }

   public boolean isEnumConstant() {
      return false;
   }

   public int getEnumOrdinal(BodyTypeDeclaration enumConst) {
      if (isDynamicType())
         // TODO: could be a lot faster - maybe switch to the other version always?
         return Arrays.asList(getEnumValues()).indexOf(enumConst);
      else {
         BodyTypeDeclaration modType = getModifiedType();
         int ord = 0;
         if (modType != null) {
            int tord = modType.getEnumOrdinal(enumConst);
            if (tord < 0)
               ord = -tord;
            else
               return tord;
         }
         if (body != null) {
            for (Statement st : body) {
               if (st == enumConst)
                  return ord;
               if (st instanceof EnumConstant)
                  ord++;
            }
         }
         // Negative means "not found" but tells the caller how many enums were checked (in case you are chaining the call across multiple types)
         return -ord;
      }
   }

   public int getEnumOrdinal() {
      if (isEnumConstant()) {
         BodyTypeDeclaration enclType = getEnclosingType();
         int ord = enclType.getEnumOrdinal(this);
         if (ord < 0)
            return -1;
         return ord;
      }
      return -1;
   }

   public Object getEnumConstant(String propName) {
      if (!isEnumeratedType())
         return null;

      int index = getDynStaticFieldIndex(propName);
      if (index == -1) {
         Class cl = getCompiledClass();
         if (cl == null) {
            System.out.println("*** No compiled class for dynamic enum: " + propName + " access: " + getFullTypeName() + " compiled class: " + getCompiledClassName());
            cl = getCompiledClass();
            return null;
         }
         return RTypeUtil.getEnum(cl, propName);
      }
      return getStaticValues()[index];
   }

   public Object[] getEnumValues() {
      if (!isEnumeratedType())
         return null;
      if (!isDynamicType()) {
         return DynUtil.getEnumConstants(getRuntimeType());
      }
      Object[] svs = getStaticValues();
      if (svs == null)
         return new Object[0];

      ArrayList<Object> enumValues = new ArrayList<Object>();
      for (int i = 0; i < svs.length; i++) {
         if (staticFields[i] instanceof BodyTypeDeclaration && ((BodyTypeDeclaration) staticFields[i]).isEnumConstant()) {
            enumValues.add(svs[i]);
         }
      }
      return enumValues.toArray();

   }

   public Object getEnumValue() {
      BodyTypeDeclaration enumParent = getEnclosingType();
      return enumParent.getEnumConstant(typeName);
   }

   public Object getRuntimeEnum() {
      BodyTypeDeclaration enumParent = getEnclosingType();
      if (enumParent.isDynamicNew())
         return getEnumValue();
      else
         return RTypeUtil.getEnum(enumParent.getCompiledClass(), typeName);
   }

   public void setCompiledOnly(boolean val) {
      compiledOnly = val;
      if (val) {
         dynamicNew = false;
         dynamicType = false;
      }
   }

   public Set<Object> getDependentTypes(DepTypeCtx ctx) {
      if (ctx.mode == DepTypeMode.All && !ctx.recursive && dependentTypes != null)
         return dependentTypes;

      if (ctx.visited != null) {
         ctx.visited.add(this);
      }

      ModelUtil.ensureStarted(this, false);

      try {
         PerfMon.start("getDependencies");
         // Use object identity for hashing the semantic nodes since their hash/equals iterates over all of the properties.
         // Also use a linked hash set so these get returned in the order in which they are added.   If they are returned in a random order, it makes the system behave inconsistently - i.e. not generating JS script files in a consistent order
         LinkedIdentityHashSet<Object> types = new LinkedIdentityHashSet<Object>();
         addDependentTypes(types, ctx);
         if (ctx.mode == DepTypeMode.All && !ctx.recursive)
            dependentTypes = types;
         return types;
      }
      finally {
         PerfMon.end("getDependencies");
      }
   }

   /**
    * Adds to the 'types' array any types or type names required for the specified mode. The mode determines which
    * dependent types to return - i.e. all, recursive or not, sync types, etc.
    */
   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (body != null) {
         for (Statement s : body) {
            s.addDependentTypes(types, mode);
         }
      }
      if (hiddenBody != null) {
         for (Statement s : hiddenBody) {
            s.addDependentTypes(types, mode);
         }
      }
      String dynChildMgrClassName = getDynChildManagerClassName();
      if (dynChildMgrClassName != null) {
         Object mgrType = findTypeDeclaration(dynChildMgrClassName, false);
         if (mgrType != null)
            addDependentType(types, mgrType, mode);
         else
            displayTypeError("No CompilerSettings.dynChildManager class: ", dynChildMgrClassName, " for: ");
      }
      IScopeProcessor scopeProcessor = getScopeProcessor();
      if (scopeProcessor != null) {
         scopeProcessor.addDependentTypes(this, types, mode);
      }
      if (mode.mode == DepTypeMode.SyncTypes) {
         ModelUtil.addSyncTypeFilterTypes(this, types);
      }
   }

   public void setAccessTimeForRefs(long time) {
      if (lastAccessTime == time)
         return;
      lastAccessTime = time;
      if (body != null) {
         for (Statement s : body) {
            s.setAccessTimeForRefs(time);
         }
      }
      if (hiddenBody != null) {
         for (Statement s : hiddenBody) {
            s.setAccessTimeForRefs(time);
         }
      }
   }

   public void setAccessTime(long time) {
      if (lastAccessTime == time)
         return;
      lastAccessTime = time;
      JavaModel m = getJavaModel();
      m.setLastAccessTime(time);
   }

   public ConstructorDefinition getDefaultConstructor() {
      if (defaultConstructor != null)
         return defaultConstructor;

      defaultConstructor = new ConstructorDefinition();

      // Needs to be parent of the parent/child hierarchy
      addToHiddenBody(defaultConstructor, true);

      return defaultConstructor;
   }

   public void init() {
      if (initialized)
         return;

      JavaModel m = getJavaModel();
      if (m != null)
         layer = m.getLayer(); // Bind this here so we can get the original layer from the type even after it was moved

      super.init();

      if (hiddenBody != null)
         hiddenBody.init();
   }

   public void initExcluded() {
      if (excludedInited)
         return;
      excludedInited = true;

      startModifiers();

      LayeredSystem sys = getLayeredSystem();
      // Need to do this after the modifiers have started but before we try to start any expressions in here that might
      // not resolve properly because they refer to identifiers not in this process
      if (!isLayerType && !isLayerComponent() && layer != null && !isExcludedStub && ModelUtil.execForRuntime(sys, layer, this, sys) == RuntimeStatus.Disabled) {
         if (!excluded) {
            if (sys.options.verbose || sys.options.verboseExec)
               sys.verbose("Excluding type: " + typeName + " for: " + sys.getProcessIdent());

            TypeDeclaration enclType = getEnclosingType();

            markExcluded(enclType == null || !enclType.excluded);
         }
      }
   }

   public void start() {
      if (!initialized)
         System.err.println("*** Error - starting uninitialized type!");
      if (started)
         return;


      if (isLayerType && layer != null && layer.excluded) {
         markExcluded(true);
      }

      initExcluded();

      if (!excluded)
         super.start();

      if (!excluded) {
         if (hiddenBody != null)
            hiddenBody.start();

         LayeredSystem sys = getLayeredSystem();

         if (sys != null) {
            Layer layer = getLayer();
            IRuntimeProcessor proc = sys.runtimeProcessor;
            if (proc != null && (getExecMode() & sys.runtimeProcessor.getExecMode()) != 0 && layer != null && layer.activated)
               proc.start(this);
         }
      }

      // We should be able to do this right after we have the extends and implements stuff bound.  Maybe we could move it later but
      // the goal is to have the index info as early as possible for the IDE.
      if (layer != null && !isLayerType && !isLayerComponent()) {
         TypeIndexEntry ent = createTypeIndex();
         if (ent != null)  // Some TemplateDeclaration types don't represent real types and don't have index entries
            layer.updateTypeIndex(ent, getJavaModel().lastModifiedTime);
      }
   }

   void initMixinTemplates(boolean doDefineTypes) {
      if (isLayerType || isLayerComponent())
         return;
      JavaModel model = getJavaModel();
      if (model != null && model.mergeDeclaration && model.layer != null) {
         ArrayList<IDefinitionProcessor> defProcs = getAllDefinitionProcessors(doDefineTypes);
         if (defProcs != null) {
            for (IDefinitionProcessor defProc : defProcs) {
               // If we are a dynamic type, we need to add any definition processor code that needs to be run for this type.
               // This may work in "start" if we need it earlier but replacedByType is not consistently set until the validate stage.
               if (isDynamicType() || doDefineTypes) {
                  String procMixin = doDefineTypes ? defProc.getDefineTypesMixinTemplate() : defProc.getStaticMixinTemplate();
                  if (procMixin != null) {
                     TransformUtil.applyTemplateToType((TypeDeclaration) this, procMixin, doDefineTypes ? "defineTypesTemplate" : "staticMixinTemplate", false);
                  }
                  if (!doDefineTypes) {
                     String procPostAssign = defProc.getPostAssignment();
                     // TODO: the sync annotation processor doesn't work with constructor args when applied as an instance body.
                     // One fix: add a hiddenBody to ConstructorDefinition similar to hiddenBody in the type.  Make sure isSemanticChildValue is implemented.
                     // loop over the constructors (or just add to the type body if there is no constructor).  For each constructor, append the types but after setting
                     // params.constructor and the args.  For now, dynamic types implement the sync constructor using special code in createInstance.
                     if (procPostAssign != null && !(defProc instanceof SyncAnnotationProcessor)) {
                        TransformUtil.applyTemplateStringToType((TypeDeclaration) this, procPostAssign, "postAssign", true);
                     }
                  }
               }
            }
         }
      }

   }

   public void validate() {
      if (validated) return;

      // Don't start the model types for excluded layers - they'll be started in other runtimes
      if (isLayerType && layer != null && layer.excluded)
         return;

      //super.validate();

      if (needsDynamicStubForExtends()) {
         if (!dynamicNew) {
            markNeedsDynamicStub(true);
            propagateDynamicStub();
         }
      }

      // If we extend a class which eventually turns into a compiled inner class, we need to preserve that type
      // hierarchy in the generated stub.
      if (needsDynamicStub) {
         Object extType = getDerivedTypeDeclaration();
         while (extType != null) {
            if (!ModelUtil.isDynamicType(extType)) {
               if (ModelUtil.getEnclosingInstType(extType) != null) {
                  needsDynInnerStub = true;
                  break;
               }
            }
            if (extType instanceof ModifyDeclaration) {
               ModifyDeclaration extModType = (ModifyDeclaration) extType;
               Object modExtType = extModType.getExtendsTypeDeclaration();
               Object modModType = extModType.getModifiedType();
               if (modExtType != null && modExtType != modModType) {
                  if (!ModelUtil.isDynamicType(modExtType)) {
                     if (ModelUtil.getEnclosingInstType(modExtType) != null) {
                        needsDynInnerStub = true;
                        break;
                     }
                  }
               }
               extType = modModType;
            }
            else
               extType = ModelUtil.getExtendsClass(extType);
         }

         // For compiled inner types, we need to clear, then reset this so we propagate it up the type hierarchy.
         if (needsDynInnerStub) {
            needsDynamicStub = false;
            setNeedsDynamicStub(true);
         }
      }

      if (hiddenBody != null)
         hiddenBody.validate();

      // This object override a compiled property in an inherited type, i.e. so that we do a default setX when the object is created
      objectSetProperty = computeObjectSetProperty();

      initSyncProperties();

      // Doing this after initSyncProperties so we process the 'validate' operation on the parent before the children.  For example initSyncProperties
      // is best run top-down so we pick up all parent modify types to figure out which types are excluded before we do the 'automatic' sync mode.
      super.validate();

      initMixinTemplates(false);

      // Need to do this so that we detect duplicate method errors
      if (methodsByName == null)
         initMethodsByName();

      // Need this for duplicate fields, classes and objects
      if (membersByName == null) {
         initMembersByName();
      }

      if (!isDynamicNew()) {
         LayeredSystem sys = getLayeredSystem();
         if (sys != null && sys.buildInfo != null)
            sys.buildInfo.addCompiledType(getFullTypeName());
      }
   }

   public void markExcluded(boolean topLevel) {
      excluded = true;
      List<Object> innerTypes = getAllInnerTypes(null, true, false);
      if (innerTypes != null) {
         for (Object innerType:innerTypes) {
            if (innerType instanceof BodyTypeDeclaration) {
               ((BodyTypeDeclaration) innerType).markExcluded(false);
            }
         }
      }
   }

   public TypeIndexEntry createTypeIndex() {
      TypeIndexEntry idx = new TypeIndexEntry();
      idx.typeName = getFullTypeName();
      Layer layer = getLayer();
      idx.layerName = layer == null ? null : layer.getLayerName();
      LayeredSystem sys = getLayeredSystem();
      idx.processIdent = sys == null ? null : sys.getProcessIdent();
      idx.layerPosition = layer == null ? -1 : layer.layerPosition;
      idx.excluded = excluded;
      ArrayList<String> baseTypes = null;
      JavaModel model = getJavaModel();
      if (model != null && model.getSrcFile() != null) {
         idx.fileName = model.getSrcFile().absFileName;
         idx.lastModified = model.getLastModifiedTime();
      }
      Object extType;
      if (isLayerType) {
         Object[] exts = getExtendsTypeDeclarations();
         if (exts != null) {
            for (Object ext:exts) {
               if (ext != null) {
                  if (baseTypes == null)
                     baseTypes = new ArrayList<String>();
                  // Using the layer name here, not including the package prefix since this is the name which is
                  // easy to find later.
                  if (ext instanceof ModifyDeclaration) {
                     baseTypes.add(((ModifyDeclaration) ext).typeName);
                  }
                  else {
                     baseTypes.add(ModelUtil.getTypeName(ext));
                  }
               }
            }
         }
      }
      else {
         if ((extType = getExtendsTypeDeclaration()) != null) {
            baseTypes = new ArrayList<String>();
            baseTypes.add(ModelUtil.getTypeName(extType));
         }
      }
      Object[] impls = getImplementsTypeDeclarations();
      if (impls != null) {
         for (Object impl:impls) {
            if (impl != null) {
               if (baseTypes == null)
                  baseTypes = new ArrayList<String>();
               baseTypes.add(ModelUtil.getTypeName(impl));
            }
         }
      }
      Object modifyInheritedBaseType = getModifiedExtendsTypeDeclaration();
      if (modifyInheritedBaseType != null) {
         if (baseTypes == null)
            baseTypes = new ArrayList<String>();
         baseTypes.add(ModelUtil.getTypeName(modifyInheritedBaseType));
      }
      idx.baseTypes = baseTypes;
      idx.declType = getDeclarationType();
      idx.isLayerType = isLayerType;
      idx.isLayerComponent = isLayerComponent();
      idx.isInnerType = getEnclosingType() != null;
      idx.isModify = this instanceof ModifyDeclaration;
      // Inner types store their own index entries
      // Overridden properties use the type index and are built up when from that
      return idx;
   }

   public void initTypeIndex() {
      JavaModel model = getJavaModel();
      model.layer.updateTypeIndex(createTypeIndex(), model.lastModifiedTime);
      List<Object> innerTypes = getLocalInnerTypes(null);
      if (innerTypes != null) {
         for (Object innerType:innerTypes) {
            if (innerType instanceof BodyTypeDeclaration) {
               ((BodyTypeDeclaration) innerType).initTypeIndex();
            }
         }
      }
   }

   public void process() {
      if (processed)
         return;

      super.process();

      if (hiddenBody != null)
         hiddenBody.process();

      JavaModel model = getJavaModel();
      if (model.mergeDeclaration) {
         Object compilerSettings = getInheritedAnnotation("sc.obj.CompilerSettings");
         if (compilerSettings != null) {
            LayeredSystem sys = getLayeredSystem();
            BuildInfo bi = sys.buildInfo;
            Object createOnStartup = ModelUtil.getAnnotationValue(compilerSettings, "createOnStartup");
            if (createOnStartup != null && ((Boolean) createOnStartup) && !hasModifier("abstract")) {
               bi.addTypeGroupMember(getFullTypeName(),  getTemplatePathName(), BuildInfo.StartupGroupName);
            }

            Object initOnStartup = ModelUtil.getAnnotationValue(compilerSettings, "initOnStartup");
            if (initOnStartup != null && ((Boolean) initOnStartup)) {
               bi.addTypeGroupMember(getFullTypeName(),  getTemplatePathName(), BuildInfo.InitGroupName);
            }
         }

         Object editorSettings = getInheritedAnnotation("sc.obj.EditorCreate");
         if (editorSettings != null) {
            LayeredSystem sys = getLayeredSystem();
            BuildInfo bi = sys.buildInfo;
            bi.addTypeGroupMember(getFullTypeName(),  getTemplatePathName(), BuildInfo.AllowEditorCreateGroupName);
         }

         BaseTypeDescriptor typeDesc = getDBTypeDescriptor();
         // Only generate the schema for the most specific type
         if (typeDesc != null && !isModifiedBySameType()) {
            DBProvider dbProvider = DBProvider.getDBProviderForType(getLayeredSystem(), getLayer(), this);
            if (dbProvider != null) {
               dbProvider.processGeneratedFiles(this, typeDesc);
            }
         }
      }

      Layer layer = model.layer;

      IRuntimeProcessor proc = getLayeredSystem().runtimeProcessor;
      if (proc != null && (getExecMode() & proc.getExecMode()) != 0 && layer != null && layer.activated)
         proc.process(this);
   }

   public String getTypePathName() {
      String pathName = typeName;
      BodyTypeDeclaration parentType = getEnclosingType();
      while (parentType != null) {
         pathName = parentType.getTypeName() + "." + pathName;
         parentType = parentType.getEnclosingType();
      }
      return pathName;
   }

   public void setNeedsDynamicStub(boolean val) {
      if (!needsDynamicStub && val) {
         /** TODO: performance - this test broke in a case where the super class was a dynamic stub but where we needed a new
          * dynamic stub because there was at least one child dynInnerStub. This test assumes that we can always inject the dynamic
          * behavior into the first dynamic stub, but we sometimes need to create an extended dynamic stub because that class needs
          * to exist to support new dynInner stubs. We could reduce the # of dynamic stubs that this creates by figuring out that
          * case - i.e. where there are no children which need dyn inner stub.
         if (getSuperIsDynamicStub()) {
            return;
         }
         */
         markNeedsDynamicStub(true);
         if (getNeedsDynInnerStub()) {
            BodyTypeDeclaration enclType = getEnclosingType();
            if (enclType != null)
               enclType.setNeedsDynamicStub(val);
         }
         propagateDynamicStub();
      }
   }

   public void propagateDynamicStub() {
      BodyTypeDeclaration modifiedType = getPureModifiedType();
      if (modifiedType != null) {
         modifiedType.markNeedsDynamicStub(true);
         modifiedType.propagateDynamicStub();
      }
   }

   private TypeDeclaration.InitStatementMode adjustInitStatementsMode(TypeDeclaration.InitStatementMode mode, ITypeDeclaration extType) {
      if (mode == TypeDeclaration.InitStatementMode.RefsOnly || mode == TypeDeclaration.InitStatementMode.SimpleOnly) {
         boolean isComponent = ModelUtil.isComponentType(extType);
         if (!isComponent) {
            switch (mode) {
               case RefsOnly:
                  return null;
               case SimpleOnly:
                  return TypeDeclaration.InitStatementMode.All;
            }
         }
      }
      return mode;
   }

   public void initDynStatements(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode, boolean initExt) {
      Object derivedType = getDerivedTypeDeclaration();
      Object extType = isLayerType ? null : getExtendsTypeDeclaration();
      Object modType = getModifiedType();

      /**
       * If the extends type is dynamic or it's an object which did not turn into a class... instead its definitions
       * went into a getX method or newX method to create one.  In that case, we still need to process those initializes
       * dynamically because there's no way (currently) to inherit such definitions.   Alternatively we could split out
       * the initialization code into a separate method, initX or something like that.  Those methods would get chained
       * and could be called so that we can use the compiled definitions of the init code, instead of going back to
       * this method which uses the interpreted definitions.
       */
      if (extType != null && initExt) {
         if (ModelUtil.isDynamicNew(extType) || !ModelUtil.needsOwnClass(extType, true)) {
            ITypeDeclaration extTD = (ITypeDeclaration) extType;
            TypeDeclaration.InitStatementMode extMode = adjustInitStatementsMode(mode, extTD);
            if (extMode != null)
               ((ITypeDeclaration) extType).initDynStatements(inst, ctx, extMode, true);
         }
      }

      if (derivedType != null && derivedType != extType && (initExt || modType == derivedType)) {
         if (ModelUtil.isDynamicNew(derivedType) || !ModelUtil.needsOwnClass(derivedType, true) || ModelUtil.isInterface(derivedType)) {
            ((ITypeDeclaration) derivedType).initDynStatements(inst, ctx, mode, initExt);
         }
      }

      if (body != null) {
         for (Statement s:body) {
            s.initDynStatement(inst, ctx, mode, false);
         }
      }
      if (hiddenBody != null) {
         for (Statement s:hiddenBody) {
            s.initDynStatement(inst, ctx, mode, false);
         }
      }
   }

   public List<Statement> getInitStatements(InitStatementsMode mode, boolean isTransformed, boolean updateStaticType) {
      List<Statement> res = new ArrayList<Statement>();

      if (body != null) {
         for (Statement s:body) {
            if (!(s instanceof AbstractBlockStatement)) {
               if (s.hasModifier("static") != mode.doStatic())
                  continue;
            }

            if (updateStaticType) {
               if (!updateStaticStatement(s))
                  continue;
            }

            s.addInitStatements(res, mode);
         }
      }
      if (hiddenBody != null) {
         for (Statement s:hiddenBody) {
            // Do not add PropertyAssignments as initStatements - they've already been transformed above
            if (s.getTransformed())
               s.addInitStatements(res, mode);
         }
      }
      return res;
   }

   private boolean updateStaticStatement(Statement s) {
      if (s instanceof BlockStatement) {
         BlockStatement bs = (BlockStatement) s;
         if (!bs.staticEnabled)
            return false;

         // TODO: need to differentiate between static statements which should not be run during a type-update operation (e.g. setting a static instance var back to null) and
         // one which should be run like SyncManager.addSyncType. Maybe a property in the DefinitionProcessor which tags these statements so we can make this more general?
         // Or maybe an extension for just making API calls to update the sync info when a field is added or removed.
         if (bs.statements != null && bs.statements.size() == 1 && bs.statements.get(0) instanceof IdentifierExpression) {
            IdentifierExpression addSync = (IdentifierExpression) bs.statements.get(0);
            if (addSync.identifiers != null && addSync.identifiers.size() == 4 && addSync.identifiers.get(2).equals("SyncManager") && addSync.identifiers.get(3).equals("addSyncType"))
               return true;
         }
      }
      return false;
   }

   public String getSignature() {
      return "L" + getFullTypeName().replace(".", "/") + ";";
   }

   public boolean isObjectSetProperty() {
      if (!validated)
         return computeObjectSetProperty();
      return objectSetProperty;
   }

   boolean computeObjectSetProperty() {
      BodyTypeDeclaration outer = getEnclosingType();
      if (outer == null || typeName == null)
         return false;

      if (getDeclarationType() != DeclarationType.OBJECT)
         return false;

      Object res = outer.getClassDeclarationForType();

      if (ModelUtil.hasTypeParameters(res))
         res = ModelUtil.getParamTypeBaseType(res);

      if (res instanceof Class) {
         return false;
      }

      BodyTypeDeclaration accessClass = (BodyTypeDeclaration) res;

      // Use accessBase here because the object in accessClass will shadow the get method in the derived class - in other words, we should not be able to resolve
      // the getX method from accessClass because it is hidden.
      Object accessBase = accessClass.getDerivedTypeDeclaration();
      Object overrideDef = accessBase == null ? null : ModelUtil.definesMember(accessBase, typeName, MemberType.GetMethodSet, null, null, getLayeredSystem());
      // For modify types, it might inherit the property from its extends as well
      if (overrideDef == null) {
         // TODO: do we want to support this feature with layers?  If so, need to use getExtendsTypeDeclarations here
         Object extType = accessClass.isLayerType ? null : accessClass.getExtendsTypeDeclaration();
         if (extType != null && extType != accessBase)
            overrideDef = ModelUtil.definesMember(extType, typeName, MemberType.GetMethodSet, null, null, getLayeredSystem());
      }
      return overrideDef != null && ModelUtil.hasSetMethod(overrideDef);
   }

   public boolean isDynCompiledObject() {
      if (getDeclarationType() != DeclarationType.OBJECT)
         return false;

      if (!ModelUtil.isDynamicType(this) && ModelUtil.getEnclosingInstType(this) != null)
         return true;

      Object extType = isLayerType ? null : getExtendsTypeDeclaration();

      // If we inherit an inner type from our base class that is compiled we need to define getX methods.
      if (extType != null && ModelUtil.getEnclosingType(extType) != null && !ModelUtil.isDynamicType(extType)) {
         return true;
      }
      return false;
   }

   public int allocateAnonId() {
      // Note: this does not handle the case where the type graph changes after this has been called.
      BodyTypeDeclaration res = resolve(true);
      if (res == this) {
         anonIdsAllocated++;
         return anonIdsAllocated;
      }
      return res.allocateAnonId();
   }

   transient int anonMethodIdsAllocated = 0;

   public int allocateAnonMethodId() {
      BodyTypeDeclaration res = resolve(true);
      if (res == this) {
         anonMethodIdsAllocated++;
         return anonMethodIdsAllocated;
      }
      return res.allocateAnonMethodId();
   }

   public void mergeDynInvokeMethods(Map<String,Object> otherDIMs) {
      if (dynInvokeMethods == null)
         dynInvokeMethods = new TreeMap<String,Object>();
      dynInvokeMethods.putAll(otherDIMs);
   }

   public void addDynInvokeMethod(Object methodObj, JavaModel fromModel) {
      if (dynInvokeMethods == null)
         dynInvokeMethods = new TreeMap<String,Object>();
      String name = ModelUtil.getMethodName(methodObj);
      String typeSig = ModelUtil.getTypeSignature(methodObj);
      dynInvokeMethods.put(name + typeSig, methodObj);
      if (fromModel != null)
         getJavaModel().addDynMethod((TypeDeclaration) this, name, typeSig, fromModel.getModelTypeDeclaration());
   }

   public Template findTemplate(List<Object> compilerSettings, String templateName, Class paramClass) {
      if (compilerSettings != null) {
         for (Object compilerAnnot : compilerSettings) {
            String templatePath = (String) ModelUtil.getAnnotationValue(compilerAnnot, templateName);
            if (templatePath != null) {
               return findTemplatePath(templatePath, templateName, paramClass);
            }
         }
      }
      return null;
   }

   public Template findTemplatePath(String templatePath, String templateName, Class paramClass) {
      if (templatePath == null || templatePath.equals(""))
         return null;
      Template template = getJavaModel().getLayeredSystem().getTemplate(templatePath, null, paramClass, null, getLayer(), isLayerType);
      if (template == null) {
         System.err.println("*** Can't find " + templateName + " with value: " + templatePath + " for definition of type: " + toDefinitionString());
      }
      return template;
   }

   public Statement transformToJS() {
      // TODO: do we need to pre-initialize the anonymous types or will they be created on the fly if not done already?

      if (body != null) {
         for (Statement st:body) {
            st.transformToJS();
         }
      }
      if (hiddenBody != null) {
         for (Statement st:hiddenBody) {
            st.transformToJS();
         }
      }
      return this;
   }

   public boolean needsDataBinding() {
      if (body != null) {
         for (Statement st:body) {
            if (st.needsDataBinding())
               return true;
         }
      }
      if (hiddenBody != null) {
         for (Statement st:hiddenBody) {
            if (st.needsDataBinding())
               return true;
         }
      }
      return false;
   }

   // Used by the JS runtime to determine if we should include the sync library.  Even if the parent is not sync'd and the children are, we need to include the library for this type.
   // To determine if this particular object needs synchronization, use getSyncProperties() != null.
   public boolean needsSync() {
      // This is cached for two reasons - one because we call it over and over again in the JS processing and
      // also because it does not appear to work after we transform.  But we need the original value when processing
      // the JS instanceTemplate.
      if (cachedNeedsSync != null)
         return cachedNeedsSync;

      if (isEnumConstant()) {
         cachedNeedsSync = Boolean.FALSE;
         return false;
      }

      if (getSyncProperties() != null) {
         cachedNeedsSync = Boolean.TRUE;
         return true;
      }

      if (body != null) {
         for (int i = 0; i < body.size(); i++) {
            Statement st = body.get(i);
            if (st instanceof BodyTypeDeclaration && ((BodyTypeDeclaration) st).needsSync()) {
               cachedNeedsSync = Boolean.TRUE;
               return true;
            }
         }
      }
      if (hiddenBody != null) {
         for (int i = 0; i < hiddenBody.size(); i++) {
            Statement st = hiddenBody.get(i);
            if (st instanceof BodyTypeDeclaration && ((BodyTypeDeclaration) st).needsSync()) {
               cachedNeedsSync = Boolean.TRUE;
               return true;
            }
         }
      }

      Object derivedType = getDerivedTypeDeclaration();

      if (derivedType instanceof TypeDeclaration) {
         if (derivedType == this) {
            System.err.println("*** Recursive type!");
            return false;
         }

         TypeDeclaration derivedTD = (TypeDeclaration) derivedType;
         if (!isAnEnclosingType(derivedTD) && derivedTD.needsSync()) {
            cachedNeedsSync = Boolean.TRUE;
            return true;
         }
      }

      Object extType = isLayerType ? null : getExtendsTypeDeclaration();
      if (extType instanceof TypeDeclaration && extType != derivedType) {
         TypeDeclaration extTD = (TypeDeclaration) extType;
         if (!isAnEnclosingType(extTD) && extTD.needsSync()) {
            cachedNeedsSync = Boolean.TRUE;
            return true;
         }
      }
      cachedNeedsSync = Boolean.FALSE;
      return false;
   }

   boolean isAnEnclosingType(TypeDeclaration type) {
      TypeDeclaration enclType = getEnclosingType();
      while (enclType != null) {
         if (ModelUtil.sameTypes(type, enclType))
            return true;
         enclType = enclType.getEnclosingType();
      }
      return false;
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      if (body != null) {
         for (int i = 0; i < body.size(); i++) {
            Statement st = body.get(i);
            ix = st.transformTemplate(ix, statefulContext);
         }
      }
      if (hiddenBody != null) {
         for (int i = 0; i < hiddenBody.size(); i++) {
            Statement st = body.get(i);
            ix = st.transformTemplate(ix, statefulContext);
         }
      }
      return ix;
   }

   private static class SubTypeProcessor {
      IAnnotationProcessor processor;
      String annotName;

      public int hashCode() {
         return annotName.hashCode();
      }

      public boolean equals(Object other) {
         if (other instanceof SubTypeProcessor)
            return ((SubTypeProcessor) other).annotName.equals(annotName);
         return false;
      }
   }

   private transient ArrayList<SubTypeProcessor> subTypeProcessors;

   protected void addInheritedAnnotationProcessor(IAnnotationProcessor p, String annotName) {
      if (subTypeProcessors == null)
         subTypeProcessors = new ArrayList<SubTypeProcessor>();
      SubTypeProcessor stp = new SubTypeProcessor();
      stp.annotName = annotName;
      stp.processor = p;
      if (subTypeProcessors.contains(stp))
         return;
      subTypeProcessors.add(stp);
   }

   private void processInheritedAnnotation(IAnnotationProcessor p, String annotName) {
      Object annotObj = getInheritedAnnotation(annotName);
      if (annotObj != null) {
         Annotation annot = Annotation.toAnnotation(annotObj);
         if (p.getSubTypesOnly() && getAnnotation(annotName, true) != null)
            return;
         p.process(this, annot);
      }
   }

   private void addDerivedProcessModifiers(Object ext) {
      if (ext instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration extTD = (BodyTypeDeclaration) ext;

         // Need to make sure the extends guy has collected his subTypeProcessors.

         extTD.processModifiers(extTD.modifiers);
         if (extTD.subTypeProcessors != null) {
            if (subTypeProcessors != null) {
               for (SubTypeProcessor stp:extTD.subTypeProcessors) {
                  if (!subTypeProcessors.contains(stp))
                     subTypeProcessors.add(stp);
               }
            }
            else {
               subTypeProcessors = new ArrayList<SubTypeProcessor>(extTD.subTypeProcessors);
            }
         }
      }
   }

   protected void processModifiers(List<Object> modifiers) {
      super.processModifiers(modifiers);
      Object derived = getDerivedTypeDeclaration();
      if (derived != null)
         addDerivedProcessModifiers(derived);
      Object ext = isLayerType ? null : getExtendsTypeDeclaration();
      if (ext != null && ext != derived)
         addDerivedProcessModifiers(ext);
      if (subTypeProcessors != null) {
         for (SubTypeProcessor stp:subTypeProcessors) {
            processInheritedAnnotation(stp.processor, stp.annotName);
         }
      }
   }

   public void clearTransformed() {
      if (transformed) {
         transformed = false;
         if (body != null)
            clearBodyTransformed(body);
         if (hiddenBody != null)
            clearBodyTransformed(hiddenBody);

         if (replacedByType != null)
            replacedByType.clearTransformed();
      }
   }

   public void clearBodyTransformed(SemanticNodeList<Statement> sts) {
      for (Statement st:sts)
         st.clearTransformed();
   }

   public BodyTypeDeclaration getTransformedResult() {
      if (transformedType != null)
         return transformedType;
      return this;
   }

   public boolean isDefaultSync() {
      if (!typeInfoCompleted)
         return false;
      if (!syncPropertiesInited)
         initSyncProperties();
      return syncProperties != null;
   }

   public List<SyncProperties> getSyncProperties() {
      if (!syncPropertiesInited)
         initSyncProperties();
      return syncProperties;
   }

  public void initDBTypeDescriptor() {
      if (!isLayerType && !isLayerComponent())
         dbTypeDescriptor = DBProvider.initDBTypeDescriptor(getLayeredSystem(), getLayer(), this);
      dbTypeDescriptorInited = true;
  }

  public void ensureTypeInfoInited() {
  }

  public BaseTypeDescriptor getDBTypeDescriptor() {
      if (!dbTypeDescriptorInited) {
         ensureTypeInfoInited();
         initDBTypeDescriptor();
      }
      return dbTypeDescriptor;
  }

   public List<SyncProperties> getInheritedSyncProperties() {
      List<SyncProperties> res = getSyncProperties();
      if (res != null)
         return res;
      Object type = getDerivedTypeDeclaration();
      if (type instanceof BodyTypeDeclaration) {
         return ((BodyTypeDeclaration) type).getInheritedSyncProperties();
      }
      return null;
   }

   public boolean isSynced(String prop, boolean forClient) {
      JavaModel model = getJavaModel();
      // At runtime...
      if (!model.mergeDeclaration) {
         Object type = getDerivedTypeDeclaration();
         if (type instanceof Class) {
            Class locClass = (Class) type;
            String typeName = locClass.getName();

            return SyncManager.isSyncedPropertyForTypeName(typeName, prop, forClient);
         }
         else
            return ((BodyTypeDeclaration)type).isSynced(prop, forClient);
      }

      List<SyncProperties> syncPropList = getSyncProperties();
      if (syncPropList == null)
         return false;
      for (SyncProperties syncProps:syncPropList)
         if (syncProps.isSynced(prop, forClient))
            return true;
      return false;
   }

   static HashSet<String> excludedPropertyNames = new HashSet<String>();
   static {
      // the final test catches this one
      //excludedPropertyNames.add("class");
   }

   static SyncMode getTypeOrLayerSyncMode(LayeredSystem sys, Object checkType) {
      return (SyncMode) getTypeOrLayerAnnotation(sys, checkType, "sc.obj.Sync", "syncMode", SyncMode.Enabled);
   }

   static Object getTypeOrLayerAnnotation(LayeredSystem sys, Object checkType, String annotName, String annotField, Object defaultValue) {
      Object parentSyncAnnot = ModelUtil.getTypeOrLayerAnnotation(sys, checkType, annotName);
      Object res;
      if (parentSyncAnnot != null) {
         res = ModelUtil.getAnnotationValue(parentSyncAnnot, annotField);
         if (res == null)
            res = defaultValue;
      }
      else
         res = null;
      return res;
   }

   /** To find out whether a type is synchronized at either the type or layer level by walking the type hierarchy */
   public static SyncMode getInheritedSyncMode(LayeredSystem sys, Object origType) {
      Object checkType = origType;
      while (checkType != null) {
         SyncMode syncMode = getTypeOrLayerSyncMode(sys, origType);
         if (syncMode != null)
            return syncMode;

         Object extendsType = ModelUtil.getExtendsClass(checkType);
         // Before checking the extendsType, walk down the modify chain to see if this type has an @Sync annotation in a previous layer
         if (checkType instanceof ModifyDeclaration) {
            ModifyDeclaration modType = (ModifyDeclaration) checkType;
            Object derivedType = modType.getDerivedTypeDeclaration();
            if (derivedType != null && derivedType != extendsType) {
               checkType = derivedType;
               while (checkType != null) {
                  syncMode = getTypeOrLayerSyncMode(sys, checkType);
                  if (syncMode != null) {
                     return syncMode;
                  }
                  checkType = ModelUtil.getSuperclass(checkType);

                  // Note: you might think that for modify types we should also be checking the extends type here.  But if we've already processed
                  // one extends type, we have processed the extends type that will be used in the compiled result, so only have to process other modified
                  // types here.
               }
            }
         }
         checkType = extendsType;
      }
      return null;
   }

   /** Called with the current type and the properties parentType.  Walks the type tree to find the first syncMode annotation at either the type or layer level. */
   public static SyncMode getInheritedSyncModeForProp(LayeredSystem sys, Object checkType, Object propParentType, SyncMode defaultSync, boolean includeSuper) {
      SyncMode res = null;
      // Pick the last annotation we find in the type hierarchy before we find the parent of the property.  This lets
      // you turn on or off the disabled sync mode as you walk from subclass to superclass.
      while (checkType != null) {
         SyncMode parentSyncMode = getTypeOrLayerSyncMode(sys, checkType);
         if (parentSyncMode != null && (includeSuper || checkType == propParentType)) {
            res = parentSyncMode;
         }
         else {
            if (parentSyncMode != null)
               defaultSync = parentSyncMode;
            res = null;
         }
         if (checkType == propParentType) {
            return res == null ? includeSuper ? defaultSync : null : res;
         }
         Object extendsType = ModelUtil.getExtendsClass(checkType);
         if (checkType instanceof ModifyDeclaration) {
            ModifyDeclaration modType = (ModifyDeclaration) checkType;
            Object derivedType = modType.getDerivedTypeDeclaration();
            if (derivedType != null && derivedType != extendsType) {
               checkType = derivedType;
               while (checkType != null) {
                  parentSyncMode = getTypeOrLayerSyncMode(sys, checkType);
                  if (parentSyncMode != null) {
                     res = parentSyncMode;
                     defaultSync = res;
                  }
                  else {
                     res = null;
                  }
                  if (checkType == propParentType) {
                     return res == null ? includeSuper ? defaultSync : null : res;
                  }
                  checkType = ModelUtil.getSuperclass(checkType);

                  // Note: you might think that for modify types we should also be checking the extends type here.  But if we've already processed
                  // one extends type, we have processed the extends type that will be used in the compiled result, so only have to process other modified
                  // types here.
               }
            }
         }
         checkType = extendsType;
      }
      return res;
   }

   public int getSyncFlags() {
      int flags = 0;
      if (syncInitDefault)
         flags |= SyncPropOptions.SYNC_INIT;
      if (syncConstant)
         flags |= SyncPropOptions.SYNC_CONSTANT;
      if (syncResetState)
         flags |= SyncPropOptions.SYNC_RESET_STATE;
      return flags;
   }

   public void initSyncProperties() {
      if (syncPropertiesInited)
         return;
      syncPropertiesInited = true;

      if (typeName == null)
         return;

      Layer layer = getLayer();
      if (layer == null) {
         return;
      }

      // Don't do sync for the layer types, annotation layer types or interfaces or deactivated layers
      if (isLayerType || isLayerComponent() || layer.annotationLayer || getDeclarationType() == DeclarationType.INTERFACE || !layer.activated || excluded)
         return;

      // Or temporary types like documentation
      JavaModel model = getJavaModel();
      if (model.temporary)
         return;

      if (isExcludedStub)
         return;

      Object syncAnnot = getInheritedAnnotation("sc.obj.Sync");
      Object layerSyncAnnot = ModelUtil.getLayerAnnotation(layer, "sc.obj.Sync");
      SyncMode syncMode;
      if (layerSyncAnnot != null) {
         syncMode = (SyncMode) ModelUtil.getAnnotationValue(layerSyncAnnot, "syncMode");
         if (syncMode == null)
            syncMode = SyncMode.Enabled;
      }
      // TODO: remove this - left in for compatibility.  Now just set the @Sync annotation on the layer itself
      else
         syncMode = layer.defaultSyncMode != null ? layer.defaultSyncMode : null;

      PerfMon.start("initSyncProperties");

      // Make sure we resolve all members as source types if possible.  We rely on being able to determine if a property has a := binding for auto-sync
      convertToSrcReference();

      List<String> filterDestinations = null;
      String syncGroup = null;
      int flags = 0;
      boolean includeSuper = false;

      if (syncAnnot != null) {
         Object av = ModelUtil.getAnnotationValue(syncAnnot, "syncMode");
         SyncMode typeSyncMode = (SyncMode) av;
         if (typeSyncMode == null) // The @Sync tag without any mode just turns it on.
            syncMode = SyncMode.Enabled;
         else if (typeSyncMode != SyncMode.Default) {  // When setting default, go back to using the default sync mode - i.e. on the layer or whatever
            syncMode = typeSyncMode;
         }
         String[] destArray = (String[]) ModelUtil.getAnnotationValue(syncAnnot, "destinations");
         syncGroup = (String) ModelUtil.getAnnotationValue(syncAnnot, "groupName");
         Boolean includeSuperObj = false;
         includeSuperObj = (Boolean) ModelUtil.getAnnotationValue(syncAnnot, "includeSuper");
         includeSuper = includeSuperObj != null && includeSuperObj;
         // Default should be null not ""
         if (syncGroup != null && syncGroup.length() == 0)
            syncGroup = null;
         if (destArray != null && destArray.length > 0)
            filterDestinations = Arrays.asList(destArray);
         Boolean syncOnDemandBool = (Boolean) ModelUtil.getAnnotationValue(syncAnnot, "onDemand");
         if (syncOnDemandBool != null) {
            // For object instances, we are making them onDemand=false even if onDemand is set for a use case like
            // the ProductView that can be used as an onDemand class, or instantiated as an object in the page where
            // there is no synchronized reference to pull in the page object.
            // TODO: maybe just ignore onDemand if it's inherited since there might still be a use case for onDemand
            // object instances
            if (syncOnDemandBool && getDeclarationType() == DeclarationType.OBJECT)
               syncOnDemand = false;
            else {
               // Once we have an on-demand initialization, we do not track property assignments after initialization so
               // we have to do the init by default.
               syncOnDemand = syncOnDemandBool;
               if (syncOnDemand)
                  syncInitDefault = true;
            }
         }
         Boolean syncInitDefaultBool = (Boolean) ModelUtil.getAnnotationValue(syncAnnot, "initDefault");
         if (syncInitDefaultBool != null)
            syncInitDefault = syncInitDefaultBool;

         Boolean syncConstantBool = (Boolean) ModelUtil.getAnnotationValue(syncAnnot, "constant");
         if (syncConstantBool != null)
            syncConstant = syncConstantBool;

         Boolean syncResetStateBool = (Boolean) ModelUtil.getAnnotationValue(syncAnnot, "resetState");
         if (syncResetStateBool != null)
            syncResetState = syncResetStateBool;

         flags = getSyncFlags();
      }
      //else // If there's no Sync annotation defined for this type, we do want to inherit the default sync state for subclasses
      //   includeSuper = true;

      LayeredSystem sys = getLayeredSystem();
      List<LayeredSystem> syncSystems = isLayerComponent() ? null : sys.getSyncSystems();
      String typeName = getFullTypeName();

      // Even if we have no sync runtimes active, you can still enable/disable the @Sync attribute so still process this type
      if (syncSystems == null || syncSystems.size() == 0) {
         syncSystems = new ArrayList<LayeredSystem>();
         syncSystems.add(sys);
      }

      List<Object> allProps = getAllProperties(null, false);

      // If the type exists in another runtime which we are sync'ing to we should make it @Sync by default.
      BitSet matchedFilterNames = filterDestinations == null ? null : new BitSet(filterDestinations.size());
      for (int sysIx = 0; sysIx < syncSystems.size(); sysIx++) {
         LayeredSystem syncSys = syncSystems.get(sysIx);
         BodyTypeDeclaration syncType = null;
         IRuntimeProcessor rt = syncSys.runtimeProcessor;
         if (filterDestinations != null) {
            if (!filterDestinations.contains(rt.getDestinationName())) {
               continue;
            }
            else
               matchedFilterNames.set(sysIx);
         }

         // When dealing with the same system, we just process types that have @Sync explicitly set.  Otherwise, all types would overlap
         Layer syncLayer = null;
         if (syncSys != sys) {
            syncLayer = syncSys.getLayerByDirName(getLayer().getLayerName());
            if (syncLayer != null)
               syncLayer.ensureStarted(true);
            syncType = syncMode == SyncMode.Automatic? (TypeDeclaration) syncSys.getSrcTypeDeclaration(typeName, null, true, false, true, syncLayer, false) : null;
            if (syncType != null && syncType.isTransformed())
               System.err.println("*** Sync type has been transformed!");

            if (syncType != null)
               syncType.convertToSrcReference();
         }

         List<Object> syncProps = new ArrayList<Object>();

         if (allProps != null) {
            for (Object prop:allProps) {
               if (prop == null)
                  continue;

               if (ModelUtil.hasModifier(prop, "final"))
                  continue;

               if (ModelUtil.hasModifier(prop, "private"))
                  continue;

               // TODO: any use case to support synchronizing static properties? If so, need to put a flag or something in so that we add
               // the listener and get the property value for a static property (i.e. use the class, not the instance). Also not sure
               // when we would need to initialize the bindings in initSyncType
               //if (ModelUtil.hasModifier(prop, "static"))
               //   continue;

               if (!ModelUtil.checkAccess(this, prop, MemberType.GetMethod))
                  continue;

               String propName = ModelUtil.getPropertyName(prop);
               int propFlags = 0;
               BodyTypeDeclaration propSyncType = null;

               // Not using inherited annotation here because we walk the type hierarchy here.
               Object propSyncAnnot = ModelUtil.getAnnotation(prop, "sc.obj.Sync");
               SyncMode propSyncMode;
               if (propSyncAnnot != null) {
                  boolean propOnDemand;
                  boolean propInitDefault;
                  boolean propConstant;
                  boolean propResetState;
                  propSyncMode = (SyncMode) ModelUtil.getAnnotationValue(propSyncAnnot, "syncMode");
                  if (propSyncMode == null)
                     propSyncMode = SyncMode.Enabled; // @Sync with no explicit mode turns it on.
                  Boolean propOnDemandObj = (Boolean) ModelUtil.getAnnotationValue(propSyncAnnot, "onDemand");
                  propOnDemand = propOnDemandObj != null && propOnDemandObj;
                  if (propOnDemand)
                     propFlags |= SyncPropOptions.SYNC_ON_DEMAND;
                  Boolean propInitDefaultObj = (Boolean) ModelUtil.getAnnotationValue(propSyncAnnot, "initDefault");
                  propInitDefault = propInitDefaultObj != null && propInitDefaultObj;
                  if (propInitDefault)
                     propFlags |= SyncPropOptions.SYNC_INIT;
                  Boolean constDefaultObj = (Boolean) ModelUtil.getAnnotationValue(propSyncAnnot, "constant");
                  propConstant = constDefaultObj != null && constDefaultObj;
                  if (propConstant)
                     propFlags |= SyncPropOptions.SYNC_CONSTANT;

                  Boolean resetStateDefaultObj = (Boolean) ModelUtil.getAnnotationValue(propSyncAnnot, "resetState");
                  propResetState = resetStateDefaultObj != null && resetStateDefaultObj;
                  if (propResetState)
                     propFlags |= SyncPropOptions.SYNC_RESET_STATE;
               }
               else {
                  propSyncMode = null;
               }

               if (ModelUtil.hasModifier(prop, "static")) {
                  propFlags |= SyncPropOptions.SYNC_STATIC;
               }

               boolean thisIsWritable = ModelUtil.isWritableProperty(prop);
               boolean inheritedSync = false;
               if (propSyncMode == null) {
                  // Some property names like 'class' are globally excluded.  This could be reimplemented using an annotation layer but
                  // not sure I want to require an annotation layer on Object.class for performance reasons.
                  if (excludedPropertyNames.contains(propName))
                     continue;
                  Object parentType = ModelUtil.getEnclosingType(prop);
                  // Check to see if there's a more specific @Sync annotation for this property.  If not, it gets the one assigned here at this type.
                  if (parentType != this) {
                     Object derivedType = getDerivedTypeDeclaration();

                     SyncMode inheritSyncMode = derivedType == null ? null : getInheritedSyncModeForProp(sys, derivedType, parentType, syncMode, includeSuper);
                     if (inheritSyncMode == null) {
                        Object checkType = getExtendsTypeDeclaration();
                        if (derivedType != checkType && checkType != null)
                           inheritSyncMode = getInheritedSyncModeForProp(sys, checkType, parentType, syncMode, includeSuper);
                     }
                     if (inheritSyncMode != null) {
                        propSyncMode = inheritSyncMode;
                        inheritedSync = true;
                        if (inheritSyncMode == SyncMode.Automatic) {
                           String parentTypeName = ModelUtil.getTypeName(parentType);
                           propSyncType = syncSys == sys ? null : (TypeDeclaration) syncSys.getSrcTypeDeclaration(parentTypeName, null, true, false, true, syncLayer, false);
                        }
                     }
                  }
                  else {
                     propSyncMode = syncMode;
                     inheritedSync = true;
                  }
               }
               /*
               if (propSyncMode == null) {
                  inheritedSync = true;
                  propSyncMode = syncMode;
               }
               */
               if (propSyncMode == null)
                  propSyncMode = SyncMode.Disabled;
               boolean addSyncProp = false;
               switch (propSyncMode) {
                  case Automatic:
                     Object syncPropInit = null;
                     if (propSyncType != null) {
                        ModelUtil.ensureStarted(propSyncType, false);
                        if (propSyncType.excluded)
                           continue;
                        syncPropInit = propSyncType.definesMember(propName, MemberType.SyncSet, null, null, false, false);
                     }
                     else if (syncType != null) {
                        ModelUtil.ensureStarted(syncType, false);

                        if (syncType.excluded) {
                           continue;
                        }
                        syncPropInit = syncType.definesMember(propName, MemberType.SyncSet, null, null, false, false);
                     }
                     else
                        continue;
                     // See if the other type defines the property - if so, we're in default mode so we sync on it.
                     // TODO: check the Sync annotation on the remote side?  Or maybe that annotation should only
                     // control how it syncs against us, not the other way around.
                     if (syncPropInit != null) {
                        // If the remote side has a bound value, then assume it is computed and not synchronized from this side by default.
                        String syncOpStr = syncPropInit instanceof IVariableInitializer ? ((IVariableInitializer) syncPropInit).getOperatorStr() : null;
                        // Also figure out how the local side is initialized.
                        String thisOpStr = prop instanceof IVariableInitializer ? ((IVariableInitializer) prop).getOperatorStr() : null;
                        if (syncOpStr == null || syncOpStr.equals("=")) {
                           // Make sure we can read on the from side and write on the to side
                           // Even if there's no setter, if it implements the IChangeable interface we have to sync on it because that means it changes internally (like a sc.util.ArrayList)
                           // Turn off for constant or transient properties
                           boolean syncWritable = ModelUtil.isWritableProperty(syncPropInit);
                           boolean changeable = ModelUtil.isChangeable(prop);
                           if (changeable && !syncWritable) // TODO: set a sync attribute on the property and we can code-gen the getX, clear(), add, calls instead of setX.
                              System.err.println("Warning - IChangeable property: " + propName + " does not have a setX method on the remote side - unable to synchronize this property.");
                           if (syncWritable && ModelUtil.isReadableProperty(prop) && !ModelUtil.isConstant(prop) && !ModelUtil.hasModifier(prop, "transient")) {
                              addSyncProp = true;
                              if (syncOpStr == null) { // TODO: should we skip this if there's no initializer for "prop" itself?  For now, including this because if the value is null we will not sync on init anyway.
                                 propFlags |= SyncPropOptions.SYNC_INIT;

                                 // If the remote side is not initialized and this side is initialized, for this property this side is the server
                                 if (thisOpStr != null && (propFlags & SyncPropOptions.SYNC_ON_DEMAND) != 0) {
                                    propFlags |= SyncPropOptions.SYNC_SERVER;
                                 }
                              }
                              // If this side is not initialized and the remote side is initialized, we are the client - receiving the on-demand changes.
                              // Otherwise,
                              else if (thisOpStr == null && (propFlags & SyncPropOptions.SYNC_ON_DEMAND) != 0) {
                                 propFlags |= SyncPropOptions.SYNC_CLIENT;
                              }
                           }
                        }
                     }

                     break;
                  case Disabled:
                     continue;
                  // TODO: should there be a more flexible way to specify this?  What if the server is the client for another server?
                  case ClientToServer:
                     if ((!inheritedSync || thisIsWritable)) {
                        addSyncProp = true;
                        // On the server, we still need to add the property for authorization purposes but don't add a SyncListener
                        if (sys.runtimeProcessor == null || sys.runtimeProcessor.getExecMode() != Element.ExecClient ) {
                           propFlags |= SyncPropOptions.SYNC_RECEIVE_ONLY;
                        }
                     }
                     break;
                  case ServerToClient:
                     if ((!inheritedSync || thisIsWritable) && (sys.runtimeProcessor == null || sys.runtimeProcessor.getExecMode() != Element.ExecClient)) {
                        addSyncProp = true;
                        propFlags |= SyncPropOptions.SYNC_SEND_ONLY;
                     }
                     break;
                  case Enabled:
                     if (!inheritedSync || thisIsWritable)
                        addSyncProp = true;
                     break;
               }

               if (addSyncProp) {
                  if (propFlags == 0)
                     syncProps.add(propName);
                  else
                     syncProps.add(new SyncPropOptions(propName, propFlags));
                  if (ModelUtil.isWritableProperty(prop) && ModelUtil.isReadableProperty(prop) && this instanceof TypeDeclaration) {
                     ModelUtil.makeBindable((TypeDeclaration) this, propName, true);
                     // Need to record this as a bindable property in the reverse dependencies so that we know to make this property bindable on an incremental compile.
                     if (this instanceof TypeDeclaration)
                        model.addBindDependency((TypeDeclaration) this, propName, (TypeDeclaration) this, false);
                  }
               }
            }
         }
         if (syncProps.size() > 0 || syncMode == SyncMode.Enabled) {
            if (syncProperties == null)
               syncProperties = new ArrayList<SyncProperties>();
            String remoteDestName = syncSystems.size() == 1 || rt == null ? null : rt.getDestinationName();
            syncProperties.add(new SyncProperties(remoteDestName, syncGroup, syncProps.toArray(new Object[syncProps.size()]), flags));
         }
      }
      if (filterDestinations != null) {
         for (int i = 0; i < filterDestinations.size(); i++) {
            if (!matchedFilterNames.get(i))
               System.err.println("Warning: type: " + typeName + " has @Sync(destinations=\"" + filterDestinations.get(i) + "\" - not referenced in the current layer set.");
         }
      }

      // If we are a modify type, we need to merge the modified properties into our type.  This specific type might have a disabled sync type because we inherit from HtmlPage (for example)
      // but we modify a model type which has sync on.  So we inherit the sync properties of the child.  The view of this type overrides the modified type.
      BodyTypeDeclaration modType = getModifiedType();
      if (modType != null) {
         List<SyncProperties> modProps = modType.getSyncProperties();
         if (modProps != null) {
            for (SyncProperties modSyncProps:modProps) {
               String destName = modSyncProps.getDestinationName();
               if (syncProperties == null) {
                  syncProperties = new ArrayList<SyncProperties>();
                  syncProperties.add(modSyncProps);
               }
               else {
                  boolean found = false;
                  for (SyncProperties syncProps:syncProperties) {
                     if (StringUtil.equalStrings(syncProps.getDestinationName(), destName)) {
                        syncProps.merge(modSyncProps);
                        found = true;
                        break;
                     }
                  }
                  if (!found) {
                     syncProperties.add(modSyncProps);
                  }
               }
            }
         }
      }

      PerfMon.end("initSyncProperties");
   }

   public boolean getSyncOnDemand() {
      if (!syncPropertiesInited)
         initSyncProperties();
      return syncOnDemand;
   }

   public boolean getSyncConstant() {
      if (!syncPropertiesInited)
         initSyncProperties();
      return syncConstant;
   }

   public boolean getSyncInitDefault() {
      if (!syncPropertiesInited)
         initSyncProperties();
      return syncInitDefault;
   }

   public String getScopeName() {
      String scopeName = super.getScopeName();
      if (scopeName != null)
         return scopeName;

      Object derived = getDerivedTypeDeclaration();
      if (derived == this) {
         System.out.println("*** Invalid recursive base class for type: " + typeName);
      }
      else if (derived != null) {
         scopeName = ModelUtil.getScopeName(derived);
         if (scopeName != null)
            return scopeName;
      }
      if (!isLayerType) {
         Object ext = getExtendsTypeDeclaration();
         if (ext != derived && ext != null)
            return ModelUtil.getScopeName(ext);
      }

      return null;
   }

   /** Returns the scope name that includes either one inherited from the base type or derived from an outer instance class. */
   public String getDerivedScopeName() {
      String scopeName = isEnumConstant() ? getScopeName() : getInheritedScopeName();
      if (scopeName == null) {
         BodyTypeDeclaration parentType = getEnclosingInstType();
         if (parentType != null) {
            parentType = parentType.resolve(true); // Grab the most specific type since going up the hierarchy we might hit a type which is modified by another type
            return parentType.getDerivedScopeName();
         }
      }
      return scopeName;
   }

   public void updateRuntimeType(ExecutionContext ctx, SyncManager.SyncContext syncCtx, Object outer) {
      // NOTE: this method is overridden in ModifyDeclaration without calling super - here we just deal with Object, class, enum etc.
      switch (getDeclarationType()) {
         case OBJECT:
            String typeName = getFullTypeName();
            Object extType = getExtendsTypeDeclaration();
            if (extType == null)
               extType = Object.class;

            boolean flushQueue = false;

            //Object inst = ScopeDefinition.lookupName(typeName);
            Object inst = syncCtx == null ? null : syncCtx.getObjectByName(typeName, false);
            if (inst != null) {
               // Just use the instance with that name
               System.out.println("*** Object " + typeName + " exists defined - skipping create instance: " + this);
            }
            else {
               flushQueue = SyncManager.beginSyncQueue();

               if (syncCtx == null || !syncCtx.getSyncManager().allowCreate(extType)) {
                  System.err.println("*** Not allowed to create instance: " + typeName + " - based type: " + extType + " not synchronized or does not have allowCreate flag set to true");
                  return;
               }
               else {
                  // TODO: deal with outer type here?  If extType is an inner instance, get the parent object by resolving it, if it matches the expected parent type of extType, then use that, otherwise keep going up.
                  if (outer != null)
                     inst = DynUtil.createInnerInstance(extType, outer, null);
                  else
                     inst = DynUtil.createInstance(extType, null);
               }
            }

            /*
            String scopeName = getScopeName();
            ScopeDefinition scope;
            if (scopeName != null) {
               scope = ScopeDefinition.getScopeByName(scopeName);

               if (scope == null)
                  throw new IllegalArgumentException("No scope named: " + scopeName);

            }
            else
               scope = syncCtx.getScopeContext().getScopeDefinition();

            if (scope != null)
               scope.registerInstance(typeName, inst);
            */

            // Need to register the name with the sync system so it uses the same name for the object.  This has to happen after the addSyncInst call
            // but we have enabled the sync queue so we know this will happen before we actually add the sync inst itself, so it will get the right name.
            // Since this reference comes from the client, we do want to reset the name when there's a new client for the same session (i.e. a refresh).
            if (syncCtx != null)
               syncCtx.registerObjName(inst, null, typeName, true, false, true);

            // Now that we've registered the real name for the instance, we can do the addSyncInst call we queued up.
            if (flushQueue)
               SyncManager.flushSyncQueue();

            ctx.pushCurrentObject(inst);

            try {
               updateInstFromType(inst, ctx, syncCtx);
            }
            finally {
               ctx.popCurrentObject();
            }

            break;
         case CLASS:
         case ENUM:
            System.err.println("*** class and enum types not yet supported in runtime models");
            break;
      }
   }

   public boolean isTransformed() {
      return transformed;
   }

   public void addInitStatements(List<Statement> res, InitStatementsMode mode) {
      JavaModel model = getJavaModel();
      // When this type is part of an SCN update applied to the server (!mergeDeclaration), an inner type either - an object or a modify is added
      // as an init statement. This preserves the order of execution of the inner type with respect to its parent.
      if (!model.mergeDeclaration && mode == InitStatementsMode.Init) {
         res.add(this);
      }
   }

   @Constant
   public String getComment() {
      TypeDeclaration enclType = getEnclosingType();
      if (enclType == null) {
         JavaModel model = getJavaModel();
         if (model != null && model.parseNode != null) {
            StringBuilder sb = new StringBuilder();
            //if (modifiers != null)
            //   sb.append(ParseUtil.getCommentsBefore(model.parseNode, modifiers, model.getLanguage().spacing));
            if (model instanceof Template) {
               return ""; // TODO: look for a template declaration at the top of the file which has a type name.  Grab the spacing before that type name
            }
            sb.append(ParseUtil.getCommentsBefore(model.parseNode, this, model.getLanguage().spacing));
            return sb.toString();
         }
      }
      else {
         // For inner classes, this works just like a field or method
         return super.getComment();
      }
      return "";
   }

   /**
    * Used for synchronization.  We could just use TypeDeclaration but it is itself treated as a special class - like java.lang.Class that denotes a type in the system,
    * Here we need an instance that's not considered a type so we use a special subclass. The main difference is that for ClientTypeDeclaration
    * DynUtil.isType returns false.
    */
   public ClientTypeDeclaration getClientTypeDeclaration() {
      if (clientTypeDeclaration == null) {
         clientTypeDeclaration = new ClientTypeDeclaration();
         refreshClientTypeDeclaration();
      }
      return clientTypeDeclaration;
   }

   public void refreshClientTypeDeclaration() {
      ClientTypeDeclaration ctd = clientTypeDeclaration;
      if (ctd != null) {
         ctd.orig = this;
         ctd.typeName = typeName;
         ctd.isLayerType = isLayerType;
         ctd.setFullTypeName(getFullTypeName());
         ctd.setDeclarationType(getDeclarationType());
         ctd.setDeclaredProperties(getDeclaredProperties());
         ctd.setConstructorParamNames(getConstructorParamNames());
         ctd.setEditorCreateMethod(getEditorCreateMethod());
         ctd.setExtendsTypeName(getExtendsTypeName());
         ctd.setPackageName(getPackageName());
         ctd.setScopeName(getScopeName());
         ctd.setDynamicType(isDynamicType());
         ctd.setLayer(getLayer());
         ctd.setComment(getComment());
         ctd.setAnnotations(getAnnotations());
         ctd.setModifierFlags(getModifierFlags());
         BodyTypeDeclaration modType = getModifiedType();
         // Skip over the types in any layers which are hidden
         while (modType != null) {
            Layer modLayer = modType.getLayer();
            if (modLayer != null && modLayer.hidden) {
               modType = modType.getModifiedType();
            }
            else
               break;
         }
         ctd.setModifiedType(modType);
         /*
         if (modifiers != null) {
            ArrayList<String> clientMods = new ArrayList<String>(modifiers.size());
            // Only sending the normal modifiers in clientModifiers - annotations are sent separately
            for (int i = 0; i < modifiers.size(); i++) {
               Object mod = modifiers.get(i);
               if (mod instanceof String)
                  clientMods.add((String) mod);
            }
            ctd.setClientModifiers(clientMods);
         }
         */
         ctd.markChanged();
      }

      if (body != null)
         refreshBodyClientTypeDeclaration(body);
      if (hiddenBody != null)
         refreshBodyClientTypeDeclaration(hiddenBody);
   }

   private void refreshBodyClientTypeDeclaration(SemanticNodeList<Statement> theBody) {
      if (theBody == null)
         return;
      for (Statement st:theBody)
         if (st instanceof BodyTypeDeclaration)
            ((BodyTypeDeclaration) st).refreshClientTypeDeclaration();
   }

   public BodyTypeDeclaration deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      BodyTypeDeclaration res = (BodyTypeDeclaration) super.deepCopy(options, oldNewMap);

      if ((options & CopyInitLevels) != 0) {
         /* Have not found any hidden body elements that need to be cloned.  They are typically done in transform or
            created on-demand.
         if (hiddenBody != null) {
            SemanticNodeList<Statement> newHiddenBody = new SemanticNodeList<Statement>();
            for (Statement hb:hiddenBody) {
               // Do not have to copy these - they are created on demand
               if (!(hb instanceof AnonClassDeclaration) && !(hb instanceof EnumDeclaration.ValueOfMethodDefinition) && !(hb instanceof EnumDeclaration.ValuesMethodDefinition) && !(hb instanceof ConstructorDefinition))
                  System.out.println("*** Unrecognized hidden body element - should it be copied?");
            }
            //res.setProperty("hiddenBody", newHiddenBody);
         }
         */
         res.dynamicType = dynamicType;
         res.dynamicNew = dynamicNew;
         res.compiledOnly = compiledOnly;

         res.propertiesToMakeBindable = propertiesToMakeBindable == null ? null : (TreeMap<String,Boolean>) propertiesToMakeBindable.clone();
         res.propertiesAlreadyBindable = propertiesAlreadyBindable == null ? null : (ArrayList<String>) propertiesAlreadyBindable.clone();
         res.dynInvokeMethods = dynInvokeMethods;
         res.isLayerType = isLayerType;
         res.typeInfoCompleted = typeInfoCompleted;

         // Not copying the instFields, and other stuff only used in dyn types cause this is not needed for transformed models

         res.needsCompiledClass = needsCompiledClass;
         res.needsDynamicStub = needsDynamicStub;
         res.needsOwnClass = needsOwnClass;

         res.allowDynamic = allowDynamic;
         res.needsDynInnerStub = needsDynInnerStub;
         res.stubCompiled = stubCompiled;
         res.needsDynAccess = needsDynAccess;
         res.objectSetProperty = objectSetProperty;
         res.needsDynDefaultConstructor = needsDynDefaultConstructor;
         res.extendsInvalid= extendsInvalid;
         res.extendsOverridden= extendsOverridden;

         if (excludedStub != null) {
            // NOTE: the parentNode for the excludedStub gets set later - when the type is added to it's parent list in setParentNode
            res.excludedStub = excludedStub.deepCopy(options, oldNewMap);
         }
         res.isExcludedStub = isExcludedStub;
         res.excludedInited = excludedInited;

         res.innerObjs = innerObjs;

         // Don't copy - it will be created on-demand and added to the hidden body
         //res.defaultConstructor = defaultConstructor;

         res.prevCompiledExtends = prevCompiledExtends;

         res.staleClassName = staleClassName;
         res.cachedNeedsSync = cachedNeedsSync;

         res.syncOnDemand = syncOnDemand;
         res.syncInitDefault = syncInitDefault;
         res.syncConstant = syncConstant;
         res.dependentTypes = dependentTypes;
         res.layer = layer;

         // Do copy the type this type is modified from but do not copy the replaced type (but hopefully we do not ever copy a replaced type)
         if (!replaced)
            res.replacedByType = replacedByType;

         res.syncProperties = syncProperties;
         res.syncPropertiesInited = syncPropertiesInited;
         res.autoComponent = autoComponent;
         res.anonIdsAllocated = anonIdsAllocated;

         //res.clientModifiers = clientModifiers;
      }

      if ((options & CopyTransformed) != 0) {
         transformedType = res;
         // In case we created the preInit, init, or start methods against the non-transformed model, copy them over so those same methods will get put into the transformed model.
         // I think this happens because we do not transform the extended type before transforming the base-type.  So we might need to resolve a preInit method before it's type has
         // been transformed or even cloned.
         if (componentMethods != null)
            res.componentMethods = componentMethods;

         if (dbTypeDescriptorInited) {
            res.dbTypeDescriptor = dbTypeDescriptor;
            res.dbTypeDescriptorInited = true;
         }
      }

      return res;
   }

   void ensureTransformed() {
      ModelUtil.ensureStarted(this, false);
      ParseUtil.processComponent(this);
      JavaModel model;
      // check and transform at the model level cause it's cached there.  It's better to always transform top-down.
      // This happens we pull in a source file from a layer's extra source mechanism (i.e. when we don't process the src)
      // This supports Java only but if there's an @Sync annotation for disabled or set on a base class, it currently fools needsTransform.   The annotation should have a way to reject an instance - like for Sync disabled.
      if (!isTransformed() && (model = getJavaModel()).needsTransform()) {
         if (model.nonTransformedModel == null && model.transformedModel == null)
            model.transformModel();
         else if (transformedType != null)
            transformedType.transform(ILanguageModel.RuntimeType.JAVA);
         else if (isTransformedType()) {
            if (model.nonTransformedModel != null)
               model.nonTransformedModel.transformModel();
            else
               transform(ILanguageModel.RuntimeType.JAVA);
         }
      }
   }

   /** Note - this returns true for any types which are in a final layer, even those which have not yet been compiled. */
   public boolean isFinalLayerType() {
      return layer != null && layer.finalLayer;
   }

   /*
   transient ArrayList<String> clientModifiers;
   public ArrayList<String> getClientModifiers() {
      return clientModifiers;
   }
   public void setClientModifiers(ArrayList<String> newMods) {
      clientModifiers = newMods;
   }
   */

   public boolean needsTypeChange() {
      if (body == null)
         return false;
      for (Statement st:body) {
         if (st instanceof PropertyAssignment || st instanceof ModifyDeclaration)
            continue;
         return true;
      }
      return false;
   }

   public boolean isAutoComponent() {
      return false;
   }

   /** In the rare case we have one dyn stub extending another, only one _super_x can exist in that inheritance chain,
    * otherwise, it starts calling the wrong method.
    * TODO: this could/should actually exclude the whole dyn stub, not just the _super_x for this method but hopefully the duplication won't make a difference
    */
    public boolean hasDynStubForMethod(Object meth) {
      if (isDynamicStub(true)) {
         List<Object> compMethods = getDynCompiledMethods();
         if (compMethods != null) {
            for (Object compMeth:compMethods)
               if (ModelUtil.sameMethods(compMeth, meth))
                  return true;
         }
         Object extType = getExtendsTypeDeclaration();
         if (extType != null && extType instanceof BodyTypeDeclaration) {
            if (((BodyTypeDeclaration) extType).hasDynStubForMethod(meth))
               return true;
         }
         Object derivedType = getDerivedTypeDeclaration();
         if (derivedType != null && derivedType != extType && derivedType instanceof BodyTypeDeclaration)
            if (((BodyTypeDeclaration) derivedType).hasDynStubForMethod(meth))
               return true;
      }
      return false;
   }

   @Constant
   public boolean getExistsInJSRuntime() {
      LayeredSystem sys = getLayeredSystem();
      JSRuntimeProcessor runtime = (JSRuntimeProcessor) sys.getRuntime("js");
      if (runtime == null)
         return false;

      JavaModel model = getJavaModel();
      if (model == null || model.unsavedModel)
         return false;

      LayeredSystem jsSys = runtime.getLayeredSystem();
      String typeName = getFullTypeName();
      if (typeName == null)
         return false;
      return jsSys.getSrcTypeDeclaration(typeName, null, true, false, false) != null;
   }

   public void setExistsInJSRuntime(boolean b) {
      throw new UnsupportedOperationException(); // Here for client/server sync cause JavaModel gets used in it's compiled form, even when compiling the client we need this method to exist for it to compile in this mode
   }

   /** Returns the name of the class from within this file - i.e. full name without the package prefix */
   public String getFileRelativeTypeName() {
      TypeDeclaration td = getEnclosingType();
      if (td == null)
         return typeName;
      return CTypeUtil.prefixPath(td.getFileRelativeTypeName(), typeName);
   }

   public void updateTypeName(String newTypeName, boolean renameFile) {
      String oldTypeName = typeName;
      LayeredSystem sys = getLayeredSystem();
      if (sys != null)
         sys.removeFromRootNameIndex(this);
      setProperty("typeName", newTypeName);
      fullTypeName = null;
      JavaModel model = getJavaModel();
      String prefix = CTypeUtil.getPackageName(getFileRelativeTypeName());
      if (model != null)
          model.updateTypeName(CTypeUtil.prefixPath(prefix, oldTypeName), CTypeUtil.prefixPath(prefix, typeName), renameFile);
      if (sys != null)
         sys.addToRootNameIndex(this);
   }

   public void setNodeName(String newNodeName) {
      updateTypeName(newNodeName, false);
   }

   public String getNodeName() {
      return typeName;
   }

   public String toListDisplayString() {
      return typeName + ": " + getDeclarationType().toString();
   }

   /**
    * This method is used by the IDE when it wants to convert this type declaration (which it might find in a reference) with the
    * version of this type managed in the IDE.  It uses the getAnnotatedModel method to find the right model, then looks for this
    * type in that layered system (since we know by then that the model in that system is annotated .
    */
   public BodyTypeDeclaration refreshNode() {
      JavaModel myModel = getJavaModel();
      if (myModel == null)
         return this;
      // Or we can look our replacement up...
      if (isLayerType()) {
         JavaModel layerModel = myModel.layeredSystem.getAnnotatedLayerModel(getTypeName().replace(".", "/"), null);
         if (layerModel != null)
            return layerModel.getModelTypeDeclaration();
         return this;
      }

      if (removed) { // For live types we have a ref to the replacement
         if (replacedByType == null)
            return null;
         return replacedByType.refreshNode();
      }
      LayeredSystem sys = myModel.getLayeredSystem();
      if (sys == null)
         return this;
      JavaModel newModel = (JavaModel) sys.getAnnotatedModel(myModel.getSrcFile());
      if (newModel == myModel)
         return this;
      if (newModel == null)
         newModel = myModel;

      // First see if we can find this new type as part of the new model since that's the most accurate way to refresh the type
      String innerTypeName = getInnerTypeName();
      Object res = newModel.getTypeDeclaration(innerTypeName);
      if (res instanceof BodyTypeDeclaration)
         return (BodyTypeDeclaration) res;

      // Otherwise, we can try to find this type by name using the ref layer
      Layer newModelLayer = newModel.getLayer();
      res = newModel.layeredSystem.getSrcTypeDeclaration(getFullTypeName(), newModelLayer == null ? null : newModelLayer.getNextLayer(), true, false, false, newModelLayer, newModel.isLayerModel);
      if (res instanceof TypeDeclaration) {
         TypeDeclaration newType = (TypeDeclaration) res;
         Layer newTypeLayer = newType.getLayer();
         Layer thisLayer = getLayer();
         // We might switch from an inactive layer to an active layer, or maybe the layers themselves were reloaded?
         if (newTypeLayer != null && (thisLayer == null || newTypeLayer.getLayerName().equals(thisLayer.getLayerName())))
            return (TypeDeclaration) res;
         return this;
      }
      displayError("Type removed: ", getFullTypeName(), " for ");
      // TODO - remove for debugging
      res = newModel.layeredSystem.getSrcTypeDeclaration(getFullTypeName(), null, true,  false, false, newModel.layer, newModel.isLayerModel);
      return null;
   }

   public boolean childIsTopLevelStatement(Statement child) {
      return true;
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement srcStatement) {
      super.addBreakpointNodes(res, srcStatement);
      if (body != null) {
         for (Statement st:body) {
            st.addBreakpointNodes(res, srcStatement);
         }
      }
   }

   public Object getArrayComponentType() {
      JavaType extType = getExtendsType();
      if (extType != null) {
         List<JavaType> typeParams = extType.getResolvedTypeArguments();
         if (typeParams != null) {
            Object extTypeDecl = extType.getTypeDeclaration();
            if (ModelUtil.isAssignableFrom(Collection.class, extTypeDecl)) {
               if (typeParams.size() == 1)
                  return typeParams.get(0).getTypeDeclaration();
            }
            else if (ModelUtil.isAssignableFrom(Map.class, extTypeDecl)) {
               if (typeParams.size() == 2)
                  return typeParams.get(1).getTypeDeclaration();
            }
         }
      }
      return null;
   }

   public boolean needsAbstract() {
      if (body == null)
         return false;
      for (Statement st:body) {
         if (st instanceof MethodDefinition && ((MethodDefinition) st).isAbstractMethod())
            return true;
      }
      return false;
   }

   public boolean isLayerType() {
      return isLayerType;
   }

   /** Is this a type defined inside of a layer?  If so, it's implicitly dynamic. */
   public boolean isLayerComponent() {
      JavaModel model = getJavaModel();
      return model != null && model.isLayerModel;
   }

   public String getOperatorString() {
      return "<unkown>";
   }

   public String addNodeCompletions(JavaModel origModel, JavaSemanticNode origNode, String matchPrefix, int offset, String dummyIdentifier, Set<String> candidates, boolean nextNameInPath, int max) {
      ModelUtil.suggestMembers(origModel, this, matchPrefix, candidates, true, true, true, true, max);
      return matchPrefix;
   }

   public void stop() {
      LayeredSystem sys = getLayeredSystem();

      if (instFields != null) {
         oldInstFields = instFields; // Preserve these for updating any instances that might have been created until we finish the updateType
      }
      if (staticFields != null) {
         oldStaticFields = staticFields;
      }
      if (staticValues != null) {
         oldStaticValues = staticValues;
      }

      if (sys != null) {
         Layer layer = getLayer();
         IRuntimeProcessor proc = sys.runtimeProcessor;
         if (proc != null && (getExecMode() & sys.runtimeProcessor.getExecMode()) != 0 && layer != null && layer.activated && isRealType())
            proc.stop(this);
      }

      hasBeenStopped = true; // Note: for debugging only - nice to know when a type has been stopped to find bugs
      memberCache = null;
      membersByName = null;
      methodsByName = null;
      propertyCache = null;
      instFields = null;
      allMethods = null;
      staticValues = null;
      staticFields = null;
      staticFieldMap = null;
      dynTransientFields = null;
      innerObjs = null;
      defaultConstructor = null;
      fullTypeName = null;
      if (replacedByType != null)
         replacedByType.clearCachedMemberInfo();

      // Can't clear this out because it's set from the other type - it won't be reset on a restart
      //if (!replaced)
      //   replacedByType = null;
      if (replacesType != null) {
         // Once we are stopped, we do not want to leave a stale references to the type we replaced
         if (replacesType.replacedByType == this)
            replacesType.replacedByType = null;
         replacesType = null;
      }
      transformedType = null;
      anonIdsAllocated = 0;
      extendsInvalid = false;
      extendsOverridden = false;
      dependentTypes = null;
      autoComponent = null;
      syncPropertiesInited = false;
      syncInitDefault = false;
      syncOnDemand = false;
      syncConstant = false;
      cachedNeedsSync = null;
      dynamicType = false;
      dynamicNew = false;
      compiledOnly = false;
      propertiesToMakeBindable = null;
      propertiesAlreadyBindable = null;
      dynInvokeMethods = null;
      typeInfoCompleted = false;
      scopeInterfaces = null;
      dynInstFieldMap = null;
      dynTransientFields = null;
      needsCompiledClass = false;
      needsDynamicStub = false;
      needsOwnClass = false;
      allowDynamic = false;
      needsDynInnerStub = false;
      stubCompiled = false;
      stubGenerated = false;
      needsDynAccess = false;
      objectSetProperty = false;
      needsDynDefaultConstructor = false;
      propertyCache = null;
      dynChildManager = null;
      dynObjManager = null;
      prevCompiledExtends = null;
      staleClassName = null;
      syncProperties = null;
      componentMethods = null;

      super.stop();
      if (hiddenBody != null) {
         hiddenBody.stop();
         hiddenBody.clear();
      }
   }

   public void updateReplacedByType(BodyTypeDeclaration repl) {
      this.replacedByType = repl;
      repl.replacesType = this;
      if (anonIdsAllocated > 0) {
         if (repl.anonIdsAllocated > 0) {
            System.err.println("*** Anon-ids database collision between: " + this + " and: " + repl);
         }
         else
            repl.anonIdsAllocated = anonIdsAllocated;
      }
      checkReplaced();
   }

   protected void checkReplaced() {
      if (replacedByType != null && !replaced) {
         if (replacedByType.getLayer() != null && getLayer() != null && replacedByType.getLayer().getLayerName().equals(getLayer().getLayerName()))
            System.out.println("**** Warning - replacing a type with one in the same layer: ");
      }
   }

   public boolean isAutomaticBindable(String property) {
      return propertiesToMakeBindable != null && propertiesToMakeBindable.get(property) != null;
   }

   public Statement findStatement(Statement in) {
      if (body != null) {
         for (Statement st:body) {
            Statement out = st.findStatement(in);
            if (out != null)
               return out;
         }
      }
      return null;
   }

   public boolean isLeafStatement() {
      return false;
   }

   public List<Statement> getBodyStatements() {
      return body;
   }

   // Not sure if Java treats this as a line statement but we need a debugger location for all of the code generated for a class.
   public boolean isLineStatement() {
      return true;
   }

   public int getNumStatementLines() {
      return 1;
   }

   public boolean isAnonymousType() {
      return false;
   }

   public void markAsTemporary() {
      temporaryType = true;
      if (body != null) {
         markChildrenAsTemporary(body);
      }
      if (hiddenBody != null) {
         markChildrenAsTemporary(body);
      }
   }

   private void markChildrenAsTemporary(List<Statement> childList) {
      for (Statement child:childList)
         if (child instanceof BodyTypeDeclaration)
            ((BodyTypeDeclaration) child).markAsTemporary();
   }

   MethodDefinition addOrGetInitMethod(String name, String accessModifier) {
      if (componentMethods == null)
         componentMethods = new ArrayList<MethodDefinition>();
      else {
         for (MethodDefinition initMeth: componentMethods)
            if (initMeth.name.equals(name))
               return initMeth;
      }

      MethodDefinition initMethod = new MethodDefinition();
      initMethod.addModifier(accessModifier);
      initMethod.name = name;
      PrimitiveType pt = new PrimitiveType();
      pt.typeName = "void";
      initMethod.setProperty("type", pt);
      // This will get reset when we actually add the method to the body, but for it to be a valid method for type resolution, it needs an enclosing type
      initMethod.parentNode = this;

      componentMethods.add(initMethod);
      return initMethod;
   }

   public String getTemplatePathName() {
      // Because this type might be modified with a different relDir we need to use the most specific one so that the value
      // used for the addPage call in the URL matches the one used in the type group member to go into URLPath
      JavaModel model = getJavaModel();
      String templatePathName = null;
      String ext = null;
      if (model != null) {
         ext = model.getResultSuffix();
         SrcEntry srcEnt = model.getSrcFile();
         if (srcEnt != null) {
            String relDir = srcEnt.getRelDir();
            if (relDir != null && relDir.length() > 0)
               templatePathName = "/" + relDir + '/' + getInnerTypeName();
            else
               templatePathName = "/" + getInnerTypeName();
         }
      }
      if (templatePathName == null)
         templatePathName = "/" + getTypeName();
      if (ext != null)
         templatePathName = FileUtil.addExtension(templatePathName, ext);
      return templatePathName;
   }

   public void setParentNode(ISemanticNode parentNode) {
      super.setParentNode(parentNode);
      if (excludedStub != null)
         excludedStub.parentNode = parentNode;
   }

   /** An excluded type can have an excludedStub that's inserted in it's place */
   public boolean needsCompile() {
      if (!excludedInited)
         initExcluded();
      return !excluded || excludedStub != null;
   }

   public boolean isServerTagType() {
      Object execAnnot = ModelUtil.getInheritedAnnotation(getLayeredSystem(), this, "sc.obj.Exec", false, this.getLayer(), false);
      if (execAnnot != null) {
         Boolean serverOnly = (Boolean) ModelUtil.getAnnotationValue(execAnnot, "serverOnly");
         return serverOnly != null && serverOnly;
      }
      return false;
   }

   public boolean getNeedsDynInnerStub() {
      return needsDynInnerStub;
   }

   public final void markNeedsDynamicStub(boolean val) {
      needsDynamicStub = val;
   }

   public AbstractMethodDefinition getEditorCreateMethod() {
      return (AbstractMethodDefinition) ModelUtil.getEditorCreateMethod(getLayeredSystem(), this);
   }

   /*
   public List<MethodDefinition> getEditorFindByMethods() {
      Object annot = getAnnotation("sc.obj.EditorSettings");
      List<String> findMethodNames = null;
      if (annot != null) {
         String findNamesStr = (String) ModelUtil.getAnnotationValue(annot, "findMethods");
         if (findNamesStr != null) {
            findMethodNames = Arrays.asList(StringUtil.split(findNamesStr,','));
         }
      }
      if (findMethodNames == null) {
         BaseTypeDescriptor dbTypeDesc = getDBTypeDescriptor();
         if (dbTypeDesc != null) {

         }
      }

   }
   */

   public String getConstructorParamNames() {
      Object createMeth = getEditorCreateMethod();
      if (createMeth == null)
         return null;
      Object annot = ModelUtil.getInheritedAnnotation(getLayeredSystem(), createMeth, "sc.obj.EditorCreate");
      if (annot != null) {
         String annotValue = (String) ModelUtil.getAnnotationValue(annot, "constructorParamNames");
         return annotValue;
      }
      return null;
   }

   public void addConstructorProps(ConstructorPropInfo cpi) {
      if (body != null) {
         for (Statement s:body) {
            s.collectConstructorPropInit(cpi);
         }
      }
      if (hiddenBody != null) {
         for (Statement s:hiddenBody) {
            s.collectConstructorPropInit(cpi);
         }
      }
   }

   String[] getPropagateParamNames(Object[] types) {
      int sz = types.length;
      String[] res = new String[sz];
      for (int i = 0; i < sz; i++)
         res[i] = "_p" + i;
      return res;
   }

   String getDeclsFromTypes(Object[] types) {
      StringBuilder sb = new StringBuilder();
      String[] paramNames = getPropagateParamNames(types);
      for (int i = 0; i < types.length; i++) {
         if (i != 0)
            sb.append(", ");
         sb.append(ModelUtil.getTypeName(types[i]));
         sb.append(" ");
         sb.append(paramNames[i]);
      }
      return sb.toString();
   }

   String getParamsFromTypes(Object[] types) {
      StringBuilder sb = new StringBuilder();
      String[] paramNames = getPropagateParamNames(types);
      for (int i = 0; i < types.length; i++) {
         if (i != 0)
            sb.append(", ");
         sb.append(paramNames[i]);
      }
      return sb.toString();
   }

   void initConstructorPropInfo() {
   }

   public ConstructorPropInfo getConstructorPropInfo() {
      return null;
   }


   public String getConstructorPropSignature() {
      ConstructorPropInfo info = getConstructorPropInfo();
      if (info == null)
         return null;
      return info.getSignature();
   }
}
