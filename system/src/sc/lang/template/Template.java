/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.template;

import sc.bind.Bindable;
import sc.lang.*;
import sc.lang.html.ControlTag;
import sc.lang.html.Element;
import sc.lang.html.IStatefulPage;
import sc.lang.js.JSRuntimeProcessor;
import sc.lang.sc.SCModel;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.sc.PropertyAssignment;
import sc.layer.IFileProcessorResult;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.layer.SrcEntry;
import sc.obj.HTMLSettings;
import sc.type.CTypeUtil;
import sc.type.DynType;
import sc.parser.*;
import sc.lang.java.*;
import sc.util.PerfMon;

import java.util.*;

public class Template extends SCModel implements IValueNode, ITypeDeclaration {
   public SemanticNodeList<Object> templateDeclarations; // Strings, template declarations, expressions, template statements, htmlTags

   public final static String TAG_PACKAGE = "sc.lang.html";

   public SemanticNodeList<Object> templateModifiers;

   public transient Object rootType = null;

   // A template by default defines a type with this name.  If the first definition in the template file modifies or
   // defines the type, that is the definition we use as this type's root.  Otherwise, we fabricate a class declaration
   // In either case, rootType refers to the type for this model.
   public transient boolean implicitRoot = false;
   public transient boolean createInstance = false;
   public transient MethodDefinition outputMethod = null;
   public transient Object outputRuntimeMethod = null; // Used for cases where we do not generate the output method but instead look it up from the rootType
   /** The template may define an optional extends type */
   public transient String defaultExtendsTypeName;
   public transient Object defaultExtendsType;
   public transient boolean generateOutputMethod = false;
   public transient boolean dynamicType = false;

   // When true, fields are added to cache declarative, dynamic content and binding expressions to notify you when tag content needs to be refreshed.
   //    So for example, if you have <div><%= foo %></div> with statefulPage you'd have a new property: String div1Body := foo.  Then div1Body =: invalidateBody();
   // When you set this to false, the page is generated without these extra fields.  Instead, the expressions themselves are evaluated directly when generating the content.
   //    In the above example, you'd just have:  outputBody() {  out.append(foo); };
   // So stateful page is more code but it's evaluated incrementally on change.  And more importantly, you know when it changes so you can incrementally refresh the UI.
   public transient boolean statefulPage = true;

   public transient boolean singleElementType = false;

   // Handle to a callback which does the transformation/compiling of this template from the language perspective
   public transient ITemplateProcessor templateProcessor = null;

   private transient boolean beingInitialized = false;

   // If non-null, the content that occurs before a single-element tag (such as the DOCTYPE controlTag)
   public transient StringBuilder preTagContent;

   public Object findMember(String name, EnumSet<MemberType> mtype, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      Object o;
      if (defaultExtendsType != null && mtype.contains(MemberType.Field)) {
         o = ModelUtil.getPropertyMapping(defaultExtendsType, name);
         if (o != null)
            return o;
      }
      if (rootType != null && rootType instanceof TypeDeclaration) {
         TypeDeclaration rootTD = (TypeDeclaration) rootType;
         o = rootTD.definesMember(name, mtype, refType, ctx, skipIfaces, rootTD.isTransformedType());
         if (o != null)
            return o;
      }
      if (templateDeclarations != null) {
         for (Object td:templateDeclarations) {
            if (td instanceof TemplateStatement) {
               if ((o = ((TemplateStatement) td).definesMember(name, mtype, refType, ctx, skipIfaces, false)) != null)
                  return o;
            }
            else if (td instanceof TemplateDeclaration) {
               if ((o = ((TemplateDeclaration) td).definesMember(name, mtype, refType, ctx, skipIfaces, false)) != null)
                  return o;
            }
         }
      }
      return super.findMember(name, mtype, fromChild, refType, ctx, skipIfaces);
   }

   public boolean isAssignableFrom(ITypeDeclaration other, boolean assignmentSemantics) {
      return ModelUtil.isAssignableFrom(rootType, other, assignmentSemantics, null);
   }

   public boolean isAssignableTo(ITypeDeclaration other) {
      // Our child template declarations are really part of the same type
      if (templateDeclarations != null) {
         for (Object td:templateDeclarations) {
            if (td == other)
               return true;
         }
      }
      return ModelUtil.isAssignableFrom(other, rootType);
   }

   public boolean isAssignableFromClass(Class other) {
      return ModelUtil.isAssignableFrom(rootType, other);
   }

   public String getTypeName() {
      return ModelUtil.getTypeName(rootType);
   }

   public String getFullTypeName(boolean includeDims, boolean includeTypeParams) {
      return ModelUtil.getTypeName(rootType, includeDims, includeTypeParams);
   }

   public String getFullTypeName() {
      String res = getModelTypeName();
      if (res == null) {
         if (rootType != null)
            return ModelUtil.getTypeName(rootType);
         else
            return "Unknown";
      }
      return res;
   }

   public String getFullBaseTypeName() {
      return ModelUtil.getTypeName(rootType);
   }

   public String getInnerTypeName() {
      return ModelUtil.getInnerTypeName(rootType);
   }

   public Class getCompiledClass() {
      return ModelUtil.getCompiledClass(rootType);
   }

   public String getCompiledTypeName() {
      return getCompiledClassName();
   }

   public String getCompiledClassName() {
      return ModelUtil.getCompiledClassName(rootType);
   }

   public Object getRuntimeType() {
      return ModelUtil.getRuntimeType(rootType);
   }

   public boolean isDynamicStub(boolean includeExtends) {
      return ModelUtil.isDynamicStub(rootType, includeExtends);
   }

   public Object definesType(String typeName, TypeContext ctx) {
      if (defaultExtendsType != null) {
         Object typeResult = ModelUtil.getInnerType(defaultExtendsType, typeName, ctx);
         if (typeResult != null)
            return typeResult;
      }
      Object o;
      if (templateDeclarations != null) {
         for (Object td:templateDeclarations) {
            if (td instanceof TemplateStatement) {
               if ((o = ((TemplateStatement) td).definesType(typeName, ctx)) != null)
                  return o;
            }
            else if (td instanceof TemplateDeclaration) {
               if ((o = ((TemplateDeclaration) td).definesType(typeName, ctx)) != null)
                  return o;
            }
         }
      }
      /*
      if (rootType instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration btd = (BodyTypeDeclaration) rootType;
         o = btd.definesType(typeName, ctx);
         if (o != null)
            return o;
      }
      */
      return super.definesType(typeName, ctx);
   }

   public Object definesMethod(String name, List<?> types, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly) {
      if (defaultExtendsType != null) {
         Object methResult = ModelUtil.definesMethod(defaultExtendsType, name, types, ctx, refType, isTransformed, staticOnly);
         if (methResult != null)
            return methResult;
      }
      Object o;
      if (name.equals("output"))
         return getOutputMethod();
      if (templateDeclarations != null) {
         for (Object td:templateDeclarations) {
            if (td instanceof TemplateStatement) {
               if ((o = ((TemplateStatement) td).definesMethod(name, types, ctx, refType, isTransformed, staticOnly)) != null)
                  return o;
            }
            else if (td instanceof TemplateDeclaration) {
               if ((o = ((TemplateDeclaration) td).definesMethod(name, types, ctx, refType, isTransformed, staticOnly)) != null)
                  return o;
            }
         }
      }
      // When starting from inside of this guy, we need to process these things
      if (refType == this && rootType != null) {
         o = ModelUtil.definesMethod(rootType, name, types, ctx, rootType, isTransformed, staticOnly);
         if (o != null)
            return o;
      }
      return super.definesMethod(name, types, ctx, refType, isTransformed, staticOnly);
   }

   public Object definesMember(String name, EnumSet<MemberType> type, Object refType, TypeContext ctx) {
      return definesMember(name, type, refType, ctx, false, false);
   }

   public Object getInnerType(String name, TypeContext ctx) {
      return ModelUtil.getInnerType(rootType, name, ctx);
   }

   public boolean implementsType(String otherTypeName, boolean assignment, boolean allowUnbound) {
      if (rootType == null)
         return false;
      return ModelUtil.implementsType(rootType, otherTypeName, assignment, allowUnbound);
   }

   public Object getInheritedAnnotation(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      if (rootType == null)
         return null;
      return ModelUtil.getInheritedAnnotation(getLayeredSystem(), rootType, annotationName, skipCompiled, refLayer, layerResolve);
   }

   public ArrayList<Object> getAllInheritedAnnotations(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      if (rootType == null)
         return null;
      return ModelUtil.getAllInheritedAnnotations(getLayeredSystem(), rootType, annotationName, skipCompiled, refLayer, layerResolve);
   }

   public Object getDerivedTypeDeclaration() {
      if (rootType == null)
         return null;
      return ModelUtil.getSuperclass(rootType);
   }

   public Object getExtendsTypeDeclaration() {
      if (rootType == null)
         return null;
      return ModelUtil.getExtendsClass(rootType);
   }

   public Object getExtendsType() {
      if (rootType == null)
         return null;
      return ModelUtil.getExtendsJavaType(rootType);
   }

   public List<?> getImplementsTypes() {
      if (rootType == null)
         return null;
      Object res = ModelUtil.getImplementsJavaTypes(rootType);
      return res == null ? null : Arrays.asList(res);
   }

   public List<Object> getAllMethods(String modifier, boolean hasModifier, boolean isDyn, boolean overridesComp) {
      if (rootType == null)
         return null;
      Object[] meths = ModelUtil.getAllMethods(rootType, modifier, hasModifier, isDyn, overridesComp);
      if (meths == null)
         return null;
      return Arrays.asList(meths);
   }

   public List<Object> getMethods(String methodName, String modifier, boolean includeExtends) {
      if (rootType == null)
         return null;
      Object[] meths = ModelUtil.getMethods(rootType, methodName, modifier, includeExtends);
      if (meths == null)
         return null;
      return Arrays.asList(meths);
   }

   public List<Object> getAllProperties(String modifier, boolean includeAssigns) {
      if (rootType == null)
         return null;
      Object[] props = ModelUtil.getProperties(rootType, modifier, includeAssigns);
      if (props == null)
         return null;
      return Arrays.asList(props);
   }

   public List<Object> getAllFields(String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified) {
      if (rootType == null)
         return null;
      Object[] f = ModelUtil.getFields(rootType, modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);
      if (f == null)
         return null;
      return Arrays.asList(f);
   }

   public List<Object> getAllInnerTypes(String modifier, boolean thisClassOnly) {
      if (rootType == null)
         return null;
      Object[] its = ModelUtil.getAllInnerTypes(rootType, modifier, thisClassOnly);
      if (its == null)
         return null;
      return Arrays.asList(its);
   }

   public boolean isEnumeratedType() {
      if (rootType == null)
         return false;
      return ModelUtil.isEnumType(rootType);
   }

   public Object getEnumConstant(String nextName) {
      if (rootType == null)
         return null;
      return ModelUtil.getEnum(rootType, nextName);
   }

   public boolean isCompiledProperty(String name, boolean fieldMode, boolean interfaceMode) {
      if (rootType != null)
         return ModelUtil.isCompiledProperty(rootType, name, fieldMode, interfaceMode);
      return definesMember(name, MemberType.PropertyGetSetObj, null, null) != null;
   }

   public List<JavaType> getCompiledTypeArgs(List<JavaType> typeArgs) {
      if (rootType != null)
         return ModelUtil.getCompiledTypeArgs(rootType, typeArgs);
      return null;
   }

   public boolean needsOwnClass(boolean checkComponents) {
      if (rootType == null)
         return true;
      return ModelUtil.needsOwnClass(rootType, checkComponents);
   }

   public boolean isDynamicNew() {
      if (rootType == null)
         return false;
      return ModelUtil.isDynamicNew(rootType);
   }

   public void initDynStatements(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode) {
      if (rootType instanceof ITypeDeclaration)
         ((ITypeDeclaration) rootType).initDynStatements(inst, ctx, mode);
   }

   public void clearDynFields(Object inst, ExecutionContext ctx) {
      if (rootType instanceof ITypeDeclaration)
         ((ITypeDeclaration) rootType).clearDynFields(inst, ctx);
   }

   public Object[] getImplementsTypeDeclarations() {
      if (rootType == null)
         return null;
      return ModelUtil.getImplementsTypeDeclarations(rootType);
   }

   public Object[] getAllImplementsTypeDeclarations() {
      if (rootType == null)
         return null;
      return ModelUtil.getAllImplementsTypeDeclarations(rootType);
   }

   public boolean isRealType() {
      return true;
   }

   public void staticInit() {
      if (rootType != null)
         ModelUtil.initType(rootType);
   }

   public boolean isTransformedType() {
      return nonTransformedModel != null;
   }

   public Object getArrayComponentType() {
      if (rootType != null)
         return ModelUtil.getArrayComponentType(rootType);
      return null;
   }

   public DeclarationType getDeclarationType() {
      if (rootType == null)
         return DeclarationType.OBJECT;
      return ModelUtil.getDeclarationType(rootType);
   }

   public List<?> getClassTypeParameters() {
      if (rootType == null)
         return null;
      return ModelUtil.getTypeParameters(rootType);
   }

   public Object[] getConstructors(Object refType) {
      if (rootType == null)
         return null;
      return ModelUtil.getConstructors(rootType, refType);
   }

   public boolean isComponentType() {
      if (rootType == null)
         return false;
      return ModelUtil.isComponentType(rootType);
   }

   public DynType getPropertyCache() {
      return ModelUtil.getPropertyCache(rootType);
   }

   public void addTypeDeclaration(TypeDeclaration td) {
      super.addTypeDeclaration(td);
      if (types.size() == 1)
         td.modelType = this;
   }

   public void init() {
      if (initialized) return;

      if (beingInitialized)
         return;

      beingInitialized = true;

      // Do this first so the conversion process can find types in this model.
      initPackageAndImports();

      Layer layer = getLayer();

      PerfMon.start("initTemplate", false);

      // If we are an annotation layer, the template itself is not active - the elements can be used to create new types though
      // The key is that we do not generate a type for this class... we're just annotating a class in the classpath with a defaut template.
      if (layer == null || !layer.annotationLayer) {
         Object firstTempl = firstInList(templateDeclarations);
         if (firstTempl instanceof TemplateDeclaration) {
            Object firstDef = firstInList(((TemplateDeclaration) firstTempl).body);
            if (firstDef instanceof TypeDeclaration) {
               TypeDeclaration rootTD = (TypeDeclaration) firstDef;
               ((TypeDeclaration) firstDef).modelType = this;
               rootType = firstDef;
               implicitRoot = true;

               String defExtTypeName;
               if (rootTD.getExtendsType() == null && templateProcessor != null && (defExtTypeName = templateProcessor.getDefaultExtendsType()) != null) {
                  defaultExtendsTypeName = defExtTypeName;
                  JavaType extClassType = ClassType.create(defExtTypeName);
                  if (rootTD instanceof ModifyDeclaration) {
                     SemanticNodeList extTypes = new SemanticNodeList();
                     extTypes.add(extClassType);
                     rootTD.setProperty("extendsTypes", extTypes);
                  }
                  else
                     rootTD.setProperty("extendsType", extClassType);
               }
            }
         }
         TypeDeclaration td = null;
         boolean elementsOnly;
         if (rootType == null) {
            String typeName = getModelTypeName();

            if (typeName != null) {

               if (templateProcessor != null && templateProcessor.getCompressSingleElementTemplates()) {
                  preTagContent = new StringBuilder();
                  // TODO: white space?
                  Element elem = getSingleFileElement(preTagContent);
                  if (elem != null) {
                     elem.setId(CTypeUtil.getClassName(typeName));

                     if (preTagContent.length() == 0)
                        preTagContent = null;

                     singleElementType = true;
                     Object modifyType = templateProcessor.getDefaultModify() ? getPreviousDeclaration(getFullTypeName()) : null;
                     rootType = td = elem.convertToObject(this, null, modifyType, templateModifiers, preTagContent);
                  }
               }

               if (td == null && templateProcessor != null && templateProcessor.getDefaultModify()) {
                  Object modifyType = getPreviousDeclaration(getFullTypeName());
                  if (modifyType != null) {
                     td = ModifyDeclaration.create(CTypeUtil.getClassName(typeName));
                     if (templateProcessor != null) {
                        String defaultExtTypeName = templateProcessor.getDefaultExtendsType();
                        if (defaultExtTypeName != null) {
                           SemanticNodeList<JavaType> extTypes = new SemanticNodeList<JavaType>();
                           extTypes.add(ClassType.create(defaultExtTypeName));
                           td.setProperty("extendsTypes", extTypes);
                           defaultExtendsTypeName = defaultExtTypeName;
                        }
                     }
                  }
               }

               if (td == null) {
                  ClassDeclaration cd;
                  td = cd = new ClassDeclaration();
                  cd.operator = templateProcessor == null || templateProcessor.getIsDefaultObjectType() ? "object" : "class"; // So we get object resolution semantics and toString
                  cd.typeName = CTypeUtil.getClassName(typeName);
                  if (templateProcessor != null) {
                     String defaultExtTypeName = templateProcessor.getDefaultExtendsType();
                     defaultExtendsTypeName = defaultExtTypeName;
                     if (defaultExtTypeName != null)
                        cd.setProperty("extendsType", ClassType.create(defaultExtTypeName));
                  }
                  if (templateModifiers != null)
                     cd.setProperty("modifiers", templateModifiers);
               }
               td.parentNode = this;
               rootType = td;
               implicitRoot = true;
               td.modelType = this;

            }
            elementsOnly = false;
         }
         else {
            td = (TypeDeclaration) rootType;
            elementsOnly = true;
         }

         if (!singleElementType)
            addBodyStatementsFromChildren(td, templateDeclarations, null, elementsOnly);

         if (rootType == null && defaultExtendsType != null)
            rootType = defaultExtendsType;
      }
      else
         generateOutputMethod = false;

      if (types == null)
         setProperty("types", new SemanticNodeList());
      if (rootType != null && rootType instanceof TypeDeclaration) {
         TypeDeclaration rootDecl = (TypeDeclaration) rootType;
         if (dynamicType)
            rootDecl.dynamicType = true;

         // Added in convertToObject for singleElementTypes to set the parent
         if (!singleElementType)
            types.add(rootDecl);
      }
      if (outputMethod != null)
         ParseUtil.initComponent(outputMethod);

      // Make sure the actual base type of this page can support the stateful option - i.e. it has the invalidate method we need.  When users override the page class, they may not support that in their class.
      if (statefulPage && rootType != null && !ModelUtil.isAssignableFrom(IStatefulPage.class, rootType))
         statefulPage = false;

      super.init();

      if (templateProcessor != null)
         resultSuffix = templateProcessor.getResultSuffix();

      PerfMon.end("initTemplate");
   }

   public void reinitialize() {
      /* NOTE: not using JavaModel for now but these could be reconciled */
      Object oldRootType = rootType;
      boolean restart = false;
      if (started) {
         hasErrors = false;
         restart = true;
         initialized = false;
         started = false;
         validated = false;
         processed = false;
         rootType = null;
         // Reset the types since those are generated in the initialize method
         types = null;
         implicitRoot = false;
         statefulPage = true;
         preTagContent = null;
         outputMethod = null;
         outputRuntimeMethod = null;
         singleElementType = false;
         createInstance = false;
         beingInitialized = false;

         if (templateDeclarations != null) {
            for (Object tempDecl:templateDeclarations) {
               // If the page has any variables in it, we can't make it a stateful page with binding snd auto-invalidation
               if (tempDecl instanceof Element) {
                  ((Element) tempDecl).resetTagObject();
               }
            }
         }
         // this resets the rootType
         ParseUtil.initComponent(this);

         if (restart && oldRootType instanceof BodyTypeDeclaration && rootType instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration rootTD = (BodyTypeDeclaration) rootType;
            ((BodyTypeDeclaration) oldRootType).updateType(rootTD, null, TypeUpdateMode.Replace, false, null);
         }

         // Doing the start and validate after we've updated the type so that we are all properly initialized
         ParseUtil.startComponent(this);
         ParseUtil.validateComponent(this);
         ParseUtil.processComponent(this);
      }
   }

   /**
    * For HTML/XML files, this returns the main tag which defines the body of the document.  If there is more than
    * one top-level tag, null is returned.  Any control tags which precedes the tag can be returned in the
    * supplied StringBuilder (or pass in null and that content is discarded).
    */
   public Element getSingleFileElement(StringBuilder preTagContent) {
      Element theElem = null;
      if (templateDeclarations == null)
         return null;

      for (Object tempDecl:templateDeclarations) {
         if (tempDecl instanceof Element) {
            if (theElem != null) // Two top level elements - can't compress
               return null;
            theElem = (Element) tempDecl;
         }
         else if (tempDecl instanceof HTMLComment)
            continue;
         else if (tempDecl instanceof ControlTag) { // The <!DOCTYPE html> thing
            if (preTagContent != null)
               preTagContent.append(tempDecl.toString());
            continue;
         }
         else if (tempDecl instanceof TemplateDeclaration) {
            continue;
         }
         // If there's any content, then we need to wrap it in a top level page tag type.
         else if (!PString.isString(tempDecl) || tempDecl.toString().trim().length() > 0)
            return null;
      }
      return theElem;
   }

   public void addBodyStatementsFromChildren(TypeDeclaration parentType, List<Object> children, Element parentTag, boolean elementsOnly) {
      boolean staticContentOnly = parentTag == null ? false : parentTag.staticContentOnly;
      if (children != null) {
         boolean inactive = parentTag != null && ((getGenerateExecFlags() & parentTag.getComputedExecFlags()) == 0);
         for (int i = 0; i < children.size(); i++) {
            Object decl = children.get(i);
            if (!elementsOnly && decl instanceof TemplateDeclaration) {
               TemplateDeclaration tdecl = (TemplateDeclaration) decl;
               if (tdecl.body != null) {
                  for (Statement s:tdecl.body) {
                     if (parentType != null) {
                        if (s instanceof TypeDeclaration) {
                           TypeDeclaration innerType = (TypeDeclaration) s;
                           innerType.layer = layer;

                           TypeDeclaration innerCopy = (TypeDeclaration) innerType.deepCopy(0, null);
                           innerCopy.layer = layer;
                           parentType.addBodyStatement(innerCopy);

                           innerType.inactiveType = true;
                        }
                        else {
                           // If we are reinitializing the template, we may have already started the template.  Do not copy the state here - the types which are bound or we may get references to types in the TemplateDeclaration copied over and used
                           parentType.addBodyStatement(s.deepCopy(0, null));
                        }
                     }
                     // else - from the language tests, parsing an schtml file but no layered system
                  }
               }
            }
            if (decl instanceof Element) {
               Element declTag = (Element) decl;
               if (declTag.needsObject()) {
                  declTag.convertToObject(this, parentType, null, null, null);
               }
               else {
                  declTag.staticContentOnly = staticContentOnly;
                  // For supporting the case where there is a content tag which contains a tag which needs an object.  The object
                  // just gets added to the enclosing parent using the same namae.
                  addBodyStatementsFromChildren(parentType, declTag.children, declTag, false);
               }
            }
            else if (decl instanceof TemplateStatement) {
               if (Element.nestedTagsInStatements) {
                  ArrayList<Object> childStatements = new ArrayList<Object>();
                  TemplateStatement templSt = (TemplateStatement) decl;
                  templSt.addChildBodyStatements(childStatements);
                  addBodyStatementsFromChildren(parentType, childStatements, parentTag, elementsOnly);
               }
            }
            else if (staticContentOnly || inactive) {
               if (decl instanceof Expression)
                  ((Expression) decl).errorArgs = new Object[0]; // TODO: is this reached?  It's probably for the exec="process" which is not used anymore.
            }
         }
      }
   }

   public void start() {
      if (started)
         return;

      if (defaultExtendsTypeName != null && defaultExtendsType == null) {
         defaultExtendsType = findTypeDeclaration(defaultExtendsTypeName, true, true);
         if (defaultExtendsType == null)
            displayTypeError("No defaultExtendsType: ", defaultExtendsTypeName, " for Template: ");
      }

      if (statefulPage) {
         if (templateDeclarations != null) {
            for (Object tempDecl:templateDeclarations) {
               // If the page has any variables in it, we can't make it a stateful page with binding snd auto-invalidation
               if (tempDecl instanceof TemplateStatement) {
                  statefulPage = false;
               }
            }
         }
      }

      if (generateOutputMethod) {
         generateOutputMethod();
      }

      if (templateProcessor != null) {
         String typeGroupName = templateProcessor.getTypeGroupName();
         if (typeGroupName != null) {
            getLayeredSystem().buildInfo.addTypeGroupMember(ModelUtil.getTypeName(types.get(0)), typeGroupName);
         }
      }

      if (rootType != null && rootType instanceof TypeDeclaration) {
         TypeDeclaration rootTypeDecl = (TypeDeclaration) rootType;
         rootTypeDecl.start();
      }

      super.start();
   }

   public void validate() {
      if (validated)
         return;

      if (rootType != null && rootType instanceof TypeDeclaration) {
         TypeDeclaration rootTypeDecl = (TypeDeclaration) rootType;
         rootTypeDecl.validate();
      }

      super.validate();
   }

   public void process() {
      if (processed)
         return;

      if (rootType != null && rootType instanceof TypeDeclaration)
         ((TypeDeclaration) rootType).process();

      super.process();
   }

   public String getOutputMethodTemplate() {
      return "public StringBuilder output() { StringBuilder out = new StringBuilder(); return out; }";
   }

   private void initOutputMethod() {

      if (outputMethod == null) {
         if (singleElementType && rootType != null) {
            outputRuntimeMethod = ModelUtil.definesMethod(rootType, "output", null, null, null, false, false);
            if (outputRuntimeMethod == null)
               displayError("Missing output() method for root template type: ");
         }
         else if (generateOutputMethod) {
            SemanticNodeList decls = null;
            if (types.size() != 0) {
               TypeDeclaration rootType = types.get(0);
               Object compilerSettings = rootType.getInheritedAnnotation("sc.obj.CompilerSettings");
               if (compilerSettings != null) {
                  Template outputMethodTemplate = rootType.findTemplate(compilerSettings, "outputMethodTemplate", null);
                  if (outputMethodTemplate != null) {
                     decls = (SemanticNodeList) TransformUtil.parseCodeTemplate(new ExprParams(""), outputMethodTemplate, TemplateLanguage.INSTANCE.classBodyDeclarations);
                     if (decls.size() == 0 || decls.size() > 1) {
                        displayError("outputMethodTemplate: ", outputMethodTemplate.getTypeName(), " contained ", decls.size() == 0 ? "no":" too many ", " defs.  Expected one method. Template: ");
                     }
                  }
               }
               if (decls == null) {
                  decls = (SemanticNodeList) TransformUtil.parseCodeTemplate(new ExprParams(""), getOutputMethodTemplate(), TemplateLanguage.INSTANCE.classBodyDeclarations, true);
                  assert decls.size() == 1;
               }
               Object decl = decls.get(0);
               if (!(decl instanceof MethodDefinition))
                  displayError("outputMethodTemplate should contain only a method definition: " + decl + " found instead for: ");
               else {
                  outputMethod = (MethodDefinition) decl;
                  String name = outputMethod.name;
                  if (rootType.declaresMethod(name, null, null, null, false) != null)
                     displayError("Template defines conflicting method ", name, " which is supposed to be generated by this template for: ");
                  else
                     rootType.addBodyStatement(outputMethod);

                  // We might be generating the outputBody(StringBuilder out) method instead of the one we need to call when we evaluate the template.  In this case for now, the base class should define an output method.
                  // TODO: make "output" name here (and elsewhere) configurable?
                  if (outputMethod != null && outputMethod.parameters != null && outputMethod.parameters.getNumParameters() > 0) {
                     outputRuntimeMethod = rootType.definesMethod("output", null, null, null, false, false);
                     if (outputRuntimeMethod == null)
                        displayError("Missing output() method for root template type: ");
                  }
               }
            }
         }
         // Initialized does not get propagated down the semantic node tree...
         // Also need to init the output method before we add statements to it or else it's parse node will not get updated
         if (isInitialized()) {
            if (outputMethod != null && !outputMethod.isInitialized()) {
               ParseUtil.initComponent(outputMethod);
            }
            if (rootType instanceof BodyTypeDeclaration) {
               ParseUtil.initComponent(rootType);
            }
         }
      }
   }

   public MethodDefinition getOutputMethod() {
      // when there's a root type with a singleElement, the output method is defined as part of that process
      if (outputMethod == null && (rootType == null || !singleElementType))
         initOutputMethod();
      return outputMethod;
   }

   public void generateOutputMethod() {
      initOutputMethod();
      if (templateDeclarations != null && !singleElementType) {
         int ct = 0;
         for (int i = 0; i < templateDeclarations.size(); i++) {
            Object decl = templateDeclarations.get(i);
            ct = addTemplateDeclToOutputMethod((TypeDeclaration) rootType, outputMethod.body, decl, true, "", ct, null, true, false);
         }
      }
   }

   public int addTemplateDeclToOutputMethod(TypeDeclaration parentType, BlockStatement block, Object decl, boolean copy, String methSuffix, int ct, Element parentElement, boolean statefulContext, boolean escape) {
      int initCt = ct;
      if (decl instanceof TemplateStatement) {
         if (parentElement == null || !parentElement.staticContentOnly) {
            TemplateStatement ts = (TemplateStatement) ((TemplateStatement) decl).deepCopy(CopyNormal, null);
            ts.parentNode = ((TemplateStatement)decl).parentNode;
            if (ts.statements != null) {
               for (int j = 0; j < ts.statements.size(); j++) {
                  Statement st = ts.statements.get(j);
                  addToOutputMethod(block, st);
               }
            }
            ts.movedToDefinition = outputMethod;
            // Need to do the transform after we've added the statements to the output method.  Otherwise, when we convert
            // the glue statements to regular statements, we will not be able to resolve references to the "out" variable.
            ct = replaceStatementGlue(ts, ct, false);
         }
      }
      else if (decl instanceof HTMLComment) {
         // TODO: convert to Java comment
      }
      else if (decl instanceof GlueDeclaration) {
         GlueDeclaration gd = (GlueDeclaration) decl;
         if (gd.declarations != null) {
            for (int j = 0; j < gd.declarations.size(); j++) {
               ct = addTemplateDeclToOutputMethod(parentType, block, gd.declarations.get(j), true, methSuffix, -1, parentElement, statefulContext, escape);
            }
         }
      }
      else if (decl instanceof Expression) {
         Expression origExpr = (Expression) decl;
         // Output out.append(decl.toString());
         Expression expr = copy ? (Expression) origExpr.deepCopy(CopyNormal, null) : origExpr;
         expr.parentNode = block;
         int origStartIndex = expr.parseNode == null ? -1 : expr.parseNode.getStartIndex();
         if (copy)
            origExpr.replacedByStatement = expr;

         // The replace glue call will unwrap any template definitions with Java definitions.  After that we have a nice well formed Java statement.  We generate that explicitly here because we are switching languages.
         ct = replaceStatementGlue(expr, ct, true);

         Parselet exprParselet = TemplateLanguage.INSTANCE.optExpression;
         Object parseResult = exprParselet.generate(TemplateLanguage.INSTANCE.newGenerateContext(exprParselet, expr), expr);
         if (parseResult instanceof ParseError) {
            displayError("Error parsing template: ", parseResult.toString(), " for expression: ", expr.toString(), " used in template ");
         }
         else if (parseResult instanceof IParseNode) {
            boolean handled = false;
            // Propagate the original node's position in the file for debugging
            if (origStartIndex != -1)
               ((IParseNode) parseResult).setStartIndex(origStartIndex);

            /** statefulContext=false when we are inside of a template statement - i.e. inside of normal code in a method */
            /** TODO: should we use "isdeclaredConstant" to avoid starting the expression here?  It seems like we need to start it eventually anyway so that won't help. */
            if (statefulPage && expr.canMakeBindable() && !expr.isConstant() && statefulContext && (parentElement == null || !parentElement.getNoCache())) {
               Expression resultExpr = (Expression) ParseUtil.nodeToSemanticValue(parseResult);
               Element enclTag;
               // The enclosing tag might be nested inside of a non-object.  In that case, just skip the non-object.
               for (enclTag = origExpr.getEnclosingTag(); enclTag != null && !enclTag.needsObject(); enclTag = enclTag.getEnclosingTag()) {
                  ;
               }

               // Create a new field - for the name, find the location of the expression: enclosing tag.  get it's object name.  Add "body" or "start" and a count if necessary for this field name.
               String fieldName;
               if (enclTag != null) {
                  String objName = enclTag.getObjectName();
                  String parentName = parentType.getTypeName();
                  fieldName = objName;
                  if (!objName.startsWith(parentName)) {
                     System.err.println("*** can't find parent name in child");
                  }
                  else if (objName.length() != parentName.length()) {
                     fieldName = objName.substring(parentName.length()+1);
                  }
                  fieldName = fieldName.replace('.', '_');
                  fieldName += methSuffix;
               }
               else if (expr instanceof StringLiteral) {
                  fieldName = null;
               }
               else {
                  //fieldName = null;
                  //fieldName = "chunk";
                  fieldName = parentType.typeName + methSuffix + "Txt";
                  if (ct == -1)
                     System.out.println("*** Unhandled case in tag conversion");
               }

               if (fieldName != null) {
                  if (ct != -1) {
                     fieldName += ct;
                     ct++;
                  }
                  else
                     ct = 0;

                  // The property and field name for an object is always the decapitalized version.  Otherwise, we can get case conflicts and confuse the two.
                  fieldName = CTypeUtil.decapitalizePropertyName(fieldName);

                  if (parentType.definesMember(fieldName, MemberType.FieldSet, this, null, true, false) == null) {
                     Object exprType = expr.getTypeDeclaration();
                     String escBodyMethod = templateProcessor == null ? null : templateProcessor.escapeBodyMethod();
                     if (ModelUtil.isPrimitive(exprType)) {
                        SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
                        args.add(expr);
                        expr = IdentifierExpression.createMethodCall(args, "String.valueOf");
                     }
                     else if (escape && escBodyMethod != null && !expr.producesHtml()) {
                        SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
                        args.add(expr);
                        expr = IdentifierExpression.createMethodCall(args, escBodyMethod);
                     }
                     // The field's variable definition's initializer is the parseResult's semantic value.
                     FieldDefinition tempField = FieldDefinition.createFromJavaType(ClassType.create("Object"), fieldName, ":=", expr);
                     tempField.fromStatement = parentElement;
                     tempField.addModifier("public");
                     parentType.addBodyStatementIndent(tempField);

                     // Then add a property assignment with reverse binding for this field.
                     IdentifierExpression methCall = IdentifierExpression.createMethodCall(new SemanticNodeList(), "invalidate" + methSuffix);
                     methCall.fromStatement = parentElement; // We will likely strip off the reverse-only PropertyAssignment so put it at this level here.
                     PropertyAssignment ba = PropertyAssignment.create(fieldName, methCall, "=:");
                     ba.fromStatement = parentElement;
                     parentType.addBodyStatementIndent(ba);
                  }
                  else {
                     // Then add a property assignment with reverse binding for this field.
                     PropertyAssignment ba = PropertyAssignment.create(fieldName, expr, ":=");
                     ba.fromStatement = parentElement;
                     parentType.addBodyStatementIndent(ba);
                  }

                  // Then add to the output method a new identifier expression for the field.
                  Statement outputCall = getExprStringOutputStatement(fieldName);
                  outputCall.fromStatement = parentElement;
                  addToOutputMethod(block, outputCall);

                  handled = true;
               }
            }

            if (!handled) {
               if (!mergeStringInOutput(block, expr, null)) {
                  Statement outputSt = getExprStringOutputStatement(parseResult.toString());
                  outputSt.fromStatement = parentElement;
                  addToOutputMethod(block, outputSt);
               }
            }
         }
         else
            throw new UnsupportedOperationException();
      }
      else if (PString.isString(decl) || decl instanceof ControlTag) {
         String newStr = decl.toString();
         // This is an optimization - if there's a StringLiteral right before us, just merge it in.
         if (!mergeStringInOutput(block, null, newStr)) {
            // Output sb.append('the string')
            Statement outExpr = getConstStringOutputStatement(newStr);
            outExpr.fromStatement = parentElement;
            addToOutputMethod(block, outExpr);
         }
      }
      // For class/object definitions, any root level glue declarations get processed as part of the root template
      // declarations. 
      else if (decl instanceof TemplateDeclaration) {
         if (parentElement == null || !parentElement.staticContentOnly) {
            TemplateDeclaration td = (TemplateDeclaration) decl;
            if (td.body != null) {
               for (int j = 0; j < td.body.size(); j++) {
                  Object childDecl = td.body.get(j);
                  if (childDecl instanceof TypeDeclaration) {
                     TypeDeclaration childTD = (TypeDeclaration) childDecl;
                     if (childTD.layer == null)
                        childTD.layer = layer;
                     if (childTD.body != null) {
                        for (Statement childTypeDecl:childTD.body) {
                           if (childTypeDecl instanceof GlueDeclaration)
                              ct = addTemplateDeclToOutputMethod(td, block, childTypeDecl, true, methSuffix, -1, parentElement, statefulContext, escape);
                        }
                     }
                  }
               }
            }
         }
      }
      else if (decl instanceof Element) {
         Element declTag = (Element) decl;
         int tagExec = declTag.getComputedExecFlags();
         int compileExec = getGenerateExecFlags();

         // If this tag is completed excluded from the object we omit it.  If it's a static content object we still include it.
         if (!declTag.execOmitObject())
            ct = declTag.addToOutputMethod(parentType, block, this, Element.doOutputAll, declTag.children, ct, statefulContext);
      }
      else
         System.out.println("*** unrecognized type");

      return ct;
   }

   private boolean mergeStringInOutput(BlockStatement block, Expression expr, String str) {
      if ((expr == null || expr instanceof StringLiteral) && (block.statements != null && block.statements.size() > 1)) {
         Statement st = block.statements.get(getLastOutStatementPos(block));
         if (str == null && expr != null)
            str = ((StringLiteral) expr).value;
         if (st != null && st instanceof IdentifierExpression) {
            IdentifierExpression lastOutExpr = (IdentifierExpression) st;
            if (lastOutExpr.arguments != null && lastOutExpr.arguments.size() == 1 && lastOutExpr.identifiers.size() == 2 && lastOutExpr.identifiers.get(0).equals("out") && lastOutExpr.identifiers.get(1).equals("append")) {
               Expression lastArg = lastOutExpr.arguments.get(0);
               if (lastArg instanceof StringLiteral) {
                  StringLiteral lastStr = (StringLiteral) lastArg;
                  lastStr.appendString(str);
                  return true;
               }
            }

         }
      }
      return false;
   }

   /** When the processTemplate operation is running we override the template's execMode - setting it to "server" to avoid the client-only parts.  This defaults to 0 which means automatically determine the execMode. */
   public int execMode = 0;

   public int getGenerateExecFlags() {
      if (execMode == 0) {
         LayeredSystem sys = getLayeredSystem();
         if (sys == null)
            return 0;
         // When there's only one runtime, like when building only client templates, if we do not include the server code
         // into the client object, it goes no where.  E.g. we lose any head content because we use the client's object to
         // render the page in PostBuild on the server.  So we need to include the content in the class and rely on the
         // serverContent flag to just use the server version on the client.
         if (sys.peerSystems == null || sys.peerSystems.size() == 0)
            return Element.ExecServer | Element.ExecClient;
         if (sys.runtimeProcessor == null || !(sys.runtimeProcessor instanceof JSRuntimeProcessor))
            return Element.ExecServer;
         else
            return Element.ExecClient;
      }
      else
         return execMode;
   }

   public SemanticNodeList<Expression> getDefaultOutputArgs() {
      // This tag is defined via an object definition - output the three
      SemanticNodeList<Expression> outArgs = new SemanticNodeList<Expression>();
      outArgs.add(IdentifierExpression.create("out"));
      return outArgs;
   }

   public Parameter getDefaultOutputParameters() {
      return Parameter.create(new Object[] {StringBuilder.class}, new String[] {"out"}, null, getModelTypeDeclaration());
   }

   public static Statement getExprStringOutputStatement(String exprStr) {
      if (exprStr == null || exprStr.length() == 0)
         System.err.println("*** invalid empty expression");
      return (Statement) ((List) TransformUtil.parseCodeTemplate(new ExprParams(exprStr), "out.append(<%= expr %>);", SCLanguage.INSTANCE.blockStatements, true)).get(0);
   }

   public static Statement getConstStringOutputStatement(String stringConstant) {
      return (Statement) ((List) TransformUtil.parseCodeTemplate(new ExprParams(CTypeUtil.escapeJavaString(stringConstant, false)), "out.append(\"<%= expr %>\");", SCLanguage.INSTANCE.blockStatements, true)).get(0);
   }

   private int replaceStatementGlue(Statement st, int ix, boolean statefulContext) {
      ParseUtil.initComponent(st);
      return st.transformTemplate(ix, statefulContext);
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      clearParseTree();

      if (templateDeclarations != null) {
         // Avoid transforming these elements and their children
         templateDeclarations.setTransformed(true);
      }
      return super.transform(runtime);
   }

   private int getLastOutStatementPos(BlockStatement sts) {
      int sz = sts.statements.size();
      if (sz > 0) {
         Statement last = sts.statements.get(sz-1);
         if (last instanceof ReturnStatement)
            sz = sz - 1;
         return sz-1;
      }
      return -1;
   }

   public void addToOutputMethod(BlockStatement  sts, Statement statement) {
      sts.initStatements();
      int sz = sts.statements.size();
      if (sz > 0) {
         Statement last = sts.statements.get(sz-1);
         if (last instanceof ReturnStatement)
            sz = sz - 1;
      }
      sts.statements.add(sz, statement);
      // init is not propagated down
      if (isInitialized() && !statement.isInitialized())
         statement.init();
   }

   private static Object firstInList(List<?> list) {
      return list == null || list.size() == 0 ? null : list.get(0);
   }

   public Element[] getChildTagsWithName(String rawObjName) {
      ArrayList<Element> res = null;

      if (templateDeclarations != null) {
         for (Object td:templateDeclarations) {
            if (td instanceof Element) {
               Element childTag = (Element) td;
               if (childTag.tagName == null) // Error parsing this tag on incomplete models if we get here
                  continue;
               if (childTag.getRawObjectName().equals(rawObjName)) {
                  if (res == null)
                     res = new ArrayList<Element>();
                  res.add(childTag);
               }
            }
         }
      }
      return res == null ? null : res.toArray(new Element[res.size()]);
   }

   public boolean isConcreteTemplate() {
      if (layer != null && layer.annotationLayer)
         return false;
      if (rootType == null || !(rootType instanceof BodyTypeDeclaration))
         return true;

      /** Don't process abstract templates */
      Element elem = getSingleFileElement(null);
      if (elem != null && elem.getBooleanAttribute("abstract"))
         return false;

      BodyTypeDeclaration root = (BodyTypeDeclaration) rootType;
      if (root.isDynamicType())
         return true;
      // Using compiled class name here not compiled class so that we do not fetch the compiled class before we've compiled the build layer.
      return root.getCompiledClassName() != null || isSimpleTemplate();
   }

   public boolean isSimpleTemplate() {
      if (rootType == null || rootType instanceof Class)
         return true;

      // TODO: look for constructs we can't interpret in this template:1
      return true;
   }


   static class ExprParams {
      String expr;
      public ExprParams(String val) {
         expr = val;
      }
   }

   public void addTypeDeclaration(String typeName, TypeDeclaration type) {
      // TemplateDeclarations and classes defined underneath them are handled specially during init.  They get converted to types under the rootType.  The TemplateDeclaration does not always have a type name so it breaks when being added to the type system.
      if (type instanceof TemplateDeclaration || type.getEnclosingType() instanceof TemplateDeclaration) {
         return;
      }
      super.addTypeDeclaration(typeName, type);
   }


   public boolean isDynamicType() {
      if (rootType instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) rootType).isDynamicType();
      if (layer == null)
         return false;
      return true;
   }

   /**
    * Overrides the value node eval method where expectedType should be String.class.  The current object
    * used for the body of the template code is either set with ctx.pushCurrentObject or created by setting Template.createInstance = true.
    * In that case, rootType is used as the class.  It is created with a zero-arg constructor.
    */
   public Object eval(Class expectedType, ExecutionContext ctx) {
      StringBuilder sb = null;
      boolean newObj = false;
      ctx.pushFrame(true, 1);
      try {
         if (outputMethod == null) {
            sb = new StringBuilder();
            ctx.defineVariable("out", sb);
         }

         if (createInstance && rootType != null && ctx.getCurrentObject() == null) {
            ctx.pushCurrentObject(ModelUtil.createInstance(rootType, null, new ArrayList<Expression>(0), ctx));
            newObj = true;
         }

         if (outputRuntimeMethod != null) {
            sb = (StringBuilder) ModelUtil.invokeMethod(ctx.getCurrentObject(), outputRuntimeMethod, null, ctx);
         }
         else if (outputMethod != null) {
            if (outputMethod.getNumParameters() == 0)
               sb = (StringBuilder) outputMethod.invoke(ctx, null); // TODO: any cases where we need to pass params here?  I think this is just web.scxml
            else
               displayError("OutputMethod generated for template has parameters: " + outputMethod.name + " No method named 'output' for template eval");
         }
         else {
            if (templateDeclarations != null)
               ModelUtil.execTemplateDeclarations(sb, ctx, templateDeclarations);
         }
      }
      finally {
         if (newObj)
            ctx.popCurrentObject();
         ctx.popFrame();
      }
      return sb == null ? null : sb.toString();
   }

   /** Probably not needed now that Template implements ITypeDeclaration */
   public TypeDeclaration getImplicitTypeDeclaration() {
      if (implicitRoot && rootType instanceof TypeDeclaration)
         return (TypeDeclaration) rootType;
      return null;
   }

   public boolean needsCompile() {
      // The default action here is to compile this file as .java file source unless the processor for the template says otherwise
      if (templateProcessor == null || !templateProcessor.needsProcessing())
         return true;
      return templateProcessor.needsCompile();
   }

   public List<SrcEntry> getDependentFiles() {
      List<SrcEntry> l = super.getDependentFiles();
      if (templateProcessor == null)
         return l;
      List<SrcEntry> tp = templateProcessor.getDependentFiles();
      if (tp == null)
         return l;

      ArrayList<SrcEntry> both = new ArrayList<SrcEntry>(l);
      both.addAll(tp);
      return both;
   }

   private boolean parseTreeCleared = false;

   private void clearParseTree() {
      if (parseTreeCleared)
         return;
      // Need to clear out the old parse tree.  During transform, we can't regenerate a template model once
      // we start using Java features.   If we need this in the future, we could make a copy here or just
      // create a JavaModel from the state of the Template and transform that.
      IParseNode node = getParseNode();
      if (node != null)
         node.setSemanticValue(null);
      parseTreeCleared = true;
   }

   protected void prepareModelTypeForTransform(boolean generate, TypeDeclaration modelType) {
      if (modelType != null && generate && modelType.isDynamicType())
         clearParseTree();
   }

   public List<SrcEntry> getCompiledProcessedFiles(Layer buildLayer, String buildDir, boolean generate) {
      BodyTypeDeclaration modelType = getModelTypeDeclaration();
      if (modelType == null)
         return Collections.emptyList();
      return super.getProcessedFiles(buildLayer, buildDir, generate);
   }

   public void postBuild(Layer buildLayer, String buildDir) {
      if (templateProcessor != null && templateProcessor.needsPostBuild())
         templateProcessor.postBuild(buildLayer, buildDir);
   }

   public List<SrcEntry> getProcessedFiles(Layer buildLayer, String buildDir, boolean generate) {
      if (templateProcessor == null || (!templateProcessor.needsProcessing())) {
         if (needsCompile()) {
            return getCompiledProcessedFiles(buildLayer, buildDir, generate);
         }
         else
            return Collections.emptyList();
      }
      return templateProcessor.getProcessedFiles(buildLayer, buildDir, generate);
   }

   /**
    * The Template extends JavaModel but uses different top-level properties for its state.  During transform, we
    * convert update the JavaModel properties so it is a valid JavaModel.  To output the Java source for this template
    * then, we just generate a new description using the JavaModel parselet as the base against this object. 
    */
   public String getTransformedResult() {
      if (transformedModel != null)
         return transformedModel.getTransformedResult();
      clearParseTree();
      PerfMon.start("templateGenerate");
      Object generateResult = TemplateLanguage.INSTANCE.compilationUnit.generate(new GenerateContext(false), this);
      PerfMon.end("templateGenerate");
      if (generateResult instanceof GenerateError) {
         displayError("*** Unable to generate template model for: " );
         return null;
      }
      else
         return generateResult.toString();
   }

   public String getProcessedFileId() {
      if (templateProcessor == null || !templateProcessor.needsProcessing())
         return super.getProcessedFileId();
      return templateProcessor.getProcessedFileName();
   }

   public String getPostBuildFileId() {
      if (templateProcessor == null)
         return getProcessedFileId();
      return templateProcessor.getPostBuildFileName();
   }

   public String toString() {
      if (templateProcessor != null && templateProcessor.evalToString())
         return (String) eval(String.class, new ExecutionContext());
      LayeredSystem sys = getLayeredSystem();
      String runtime = sys != null ? " (runtime: " + sys.getRuntimeName() + ")" : "";
      String isTransformed = nonTransformedModel != null ? " (transformed)" : "";
      if (!isStarted())
          return "Template: " + getSrcFile() + " (not started)";
      try {
         return "Template: " + getSrcFile() + runtime + isTransformed;
      }
      catch (NullPointerException exc) {
         return "Template " + getSrcFile() + " caused error during toString: " + exc;
      }
   }

   public Language getLanguage() {
      if (parseNode != null)
         return parseNode.getParselet().getLanguage();
      return TemplateLanguage.getTemplateLanguage();
   }

   public boolean isSemanticChildValue(ISemanticNode child) {
      // When changes are propagated up from the rootType, don't let them affect the original parseNode of the template - i.e. invalidating it
      if (child == rootType || child == types)
         return false;
      return super.isSemanticChildValue(child);
   }

   public void addModelMergingComment(ParentParseNode baseCommentNode) {
      // TODO: generate an HTML comment similar to the one in JavaModel
   }

   public boolean needsPostBuild() {
      if (!isConcreteTemplate())
         return false;
      if (templateProcessor != null)
         return templateProcessor.needsPostBuild();
      return false;
   }

   /** The rootType stores the Template as its direct parent so when we replace it, we need to update it both in the types list and in the rootType variable */
   public int replaceChild(Object toReplace, Object other) {
      if (toReplace == rootType) {
         rootType = other;
         return types.replaceChild(toReplace, other);
      }
      return super.replaceChild(toReplace, other);
   }

   public TypeDeclaration getModelTypeDeclaration() {
      TypeDeclaration res = super.getModelTypeDeclaration();
      if (res == null && rootType instanceof TypeDeclaration)
         return (TypeDeclaration) ((TypeDeclaration) rootType).resolve(true);
      return res;
   }

   public Template deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      Template res = (Template) super.deepCopy(options, oldNewMap);

      if ((options & CopyInitLevels) != 0) {
         // If we are transforming, the root type will have been in the parent's types property and already copied.  Just need to pull out the right one to
         // set the rootType field.
         if (((options & CopyTransformed) != 0) && rootType instanceof BodyTypeDeclaration) {
            if (res.types.size() >= 1)
               res.rootType = res.types.get(0);
         }
         else
            res.rootType = rootType;

         // A template by default defines a type with this name.  If the first definition in the template file modifies or
         // defines the type, that is the definition we use as this type's root.  Otherwise, we fabricate a class declaration
         // In either case, rootType refers to the type for this model.
         res.implicitRoot = implicitRoot;
         res.outputMethod = outputMethod;
         res.outputRuntimeMethod = outputRuntimeMethod;
         res.defaultExtendsType = defaultExtendsType;
         res.dynamicType = dynamicType;

         res.singleElementType = singleElementType;

         res.beingInitialized = beingInitialized;

         // If non-null, the content that occurs before a single-element tag (such as the DOCTYPE controlTag)
         res.preTagContent = preTagContent;
      }

      // Handle to a callback which does the transformation/compiling of this template from the language perspective
      if (templateProcessor != null)
         res.templateProcessor = templateProcessor.deepCopy(res);

      res.prependLayerPackage = prependLayerPackage;
      res.createInstance = createInstance;
      res.generateOutputMethod = generateOutputMethod;
      res.statefulPage = statefulPage;

      return res;
   }

   @Bindable(manual=true)
   @HTMLSettings(returnsHTML=true)
   public String getGeneratedSCText() {
      if (rootType instanceof BodyTypeDeclaration) {
         SCLanguage lang = SCLanguage.getSCLanguage();
         Object res = lang.styleNoTypeErrors(((BodyTypeDeclaration) rootType).toLanguageString(lang.typeDeclaration));
         return res.toString();
      }
      return null;
   }

   public boolean getDependenciesChanged(Map<String,IFileProcessorResult> changedModels) {
      if (super.getDependenciesChanged(changedModels))
         return true;

      TypeDeclaration td = (TypeDeclaration) rootType;
      if (td.changedSinceLayer(initializedInLayer, false, null, changedModels)) {
         return true;
      }
      return false;
   }

   /** Hook to reinitiaze any state after one of your base types gets modified after this type has been processed. */
   public void dependenciesChanged() {
      if (initializedInLayer != null && rootType != null && rootType instanceof TypeDeclaration) {
         TypeDeclaration td = (TypeDeclaration) rootType;
         if (td.changedSinceLayer(initializedInLayer, false, null, null)) {

            SrcEntry srcFile = getSrcFile();
            Layer srcLayer = srcFile.layer;
            LayeredSystem sys = srcLayer.layeredSystem;

            if (sys.options.verbose)
               System.out.println("Reinitializing template for changed dependencies: " + srcFile.relFileName);

            // We need to regenerate the template so that it can take into any extended types which may have changed.
            reinitialize();

            // Register the new types to replace the old ones
            srcLayer.layeredSystem.addTypesByName(srcLayer, getPackagePrefix(), getDefinedTypes(), srcLayer.getNextLayer());

            clearTransformed();
         }
      }
   }

   public boolean isLayerType() {
      return false;
   }
}
