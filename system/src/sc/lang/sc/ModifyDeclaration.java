/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sc;

import sc.classfile.CFClass;
import sc.dyn.DynUtil;
import sc.dyn.IDynObject;
import sc.lang.*;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.layer.SrcEntry;
import sc.obj.ScopeDefinition;
import sc.parser.ParseUtil;
import sc.sync.SyncManager;
import sc.type.CTypeUtil;
import sc.type.RTypeUtil;
import sc.lang.java.*;
import sc.util.StringUtil;

import java.util.*;

/**
 * The semantic node class which is used for the modify operation.  The modify type is used to modify Java code and for defining layers.
 * You might modify another type which we have in src in which case "modifyTypeDecl" is
 * set to point to the type, or you might modify a class for an annotation layer or a layer definition file - then modifyClass is set to refer to the modified type.
 */
public class ModifyDeclaration extends TypeDeclaration {
   public SemanticNodeList<JavaType> extendsTypes;

   transient private BodyTypeDeclaration modifyTypeDecl;
   transient private Object modifyClass;
   transient Object[] extendsBoundTypes;
   transient private boolean typeInitialized = false;
   // ModifyInherited = true when you use a modify in an inner type where the modified type is actually in a base class,
   // not the same type that the parent type is modifying.  If your outer class extends "OuterExtends", this modifyInherited case is a shortcut for:
   //      class innerName extends OuterExtends.innerName
   transient public boolean modifyInherited = false;
   transient public boolean compoundName = false;
   transient public boolean enumConstant = false;
   transient public BodyTypeDeclaration hiddenByType; // When typeName is compound - e.g. a.b, stores the reference to type "b" which we may have generated or found to replace this definition
   transient public BodyTypeDeclaration hiddenByRoot; // When typeName is compound - e.g. a.b, stores the reference to type "a" which we may have generated or found to replace this definition

   // When the typeName has "a.b" in it, we skip a level.  this stores
   // the skipped types in the base layer.
   transient Object[] impliedRoots;

   private transient boolean isStarting = false;

   public Object getDerivedTypeDeclaration() {
      //assert initialized;

      // Layer types can't be started here because we need to initialize the inheritPackage property
      // before we can set the layer's packagePrefix.  Before that is set, we don't know the type.
      // We might be in the midst of starting it but already resolved the type.  To avoid a double
      // start, just return it then.
      if (!started && !isLayerType && modifyTypeDecl == null && modifyClass == null) {
         initTypeInfo();
      }

      if (modifyTypeDecl != null) {
         if (modifyTypeDecl == this || modifyTypeDecl.getUnresolvedModifiedType() == this) {
            System.err.println("*** Error! type modifies itself!");
            return null;
         }
         if (modifyTypeDecl instanceof ModifyDeclaration && ((ModifyDeclaration) modifyTypeDecl).modifyTypeDecl == this) {
            System.err.println("*** Error! type modifies a type which modifies it!");
            return null;
         }
         return modifyTypeDecl;
      }

      if (enumConstant)
         return getEnclosingType();

      return modifyClass;
   }

   protected void updateBoundExtendsType(Object extType, Object oldType) {
      if (modifyTypeDecl != oldType && modifyTypeDecl != null)
         System.out.println("*** Warning - updating modify with inconsistent type?");
      if (extType instanceof BodyTypeDeclaration) {
         modifyTypeDecl = (BodyTypeDeclaration) extType;
         checkModify();
      }
      else
         throw new UnsupportedOperationException();
   }

   // This method is normally only used for ClassDeclarations - after we've merged ModifyDeclarations.  But during
   // validate or runtime we may want to find the compiled class for a modify class, i.e. to determine whether there's
   // an object set property ala JFrame's JMenuBar.
   public Object getClassDeclarationForType() {
      // don't skip enum constants for modify types
      if (enumConstant)
         return this;
      return super.getClassDeclarationForType();
   }

   /** 
    * Here we are updating the 'replacedByType' on the modified type.  Since this is a
    * structural part of the type system, that needs to be resolved during the initialize
    * stage for the html Element convertToObject method, this section was broken out of the
    * start method - so it's done as soon as modifyTypeDecl is updated.
    */
   public void initTypeInfo() {
      if (typeInfoInitialized)
         return;

      try {
         super.initTypeInfo();

         if (modifyTypeDecl != null) {
            if (layer != null) {
               /*
               * If the type we are modifying comes from a super type we need to treat it differently on transform.
               * It's really a shortcut for class foo extends Super.foo
               */
               modifyInherited = !ModelUtil.sameTypes(this, modifyTypeDecl);

               if (modifyInherited && getEnclosingType() == null)
                  System.out.println("**** ERROR: modifyInherited occurs on top-level type!");

               // For a normal modification, we end up getting the type in a previous layer.  But if we inherited this
               // from another type, we want to get the most specific one, i.e. resolve the replaced type.
               // The example is UCTest(extended) modifies UCtest(model).   UCTest(extended).converters will find UnitConverter.converters
               // to modify it.  But we need to get the most specific one: UnitConverter(extended).converters.
               if (modifyInherited && modifyTypeDecl.replacedByType != null) {
                  BodyTypeDeclaration resolvedDecl = modifyTypeDecl.resolve(true);
                  if (resolvedDecl != this) {
                     modifyTypeDecl = resolvedDecl;
                     checkModify();
                  }
                  else
                     System.err.println("*** Recursive modify in resolve");
               }

               if (modifyInherited && extendsTypes != null && extendsTypes.size() > 0) {
                  TypeDeclaration enclType = getEnclosingType();
                  if (enclType != null)
                     displayError("Modifying inherited type: " + modifyTypeDecl.getFullTypeName() + " from: " + enclType.getFullTypeName() + " cannot extend another type: ");
                  else // This happens during editing in the IDE for some reason
                     displayError("Modifying a type which is not the same: " + modifyTypeDecl.getFullTypeName());
               }

               JavaModel thisModel = getJavaModel();
               // Give the modified type a back pointer - its "find" operations will map back to us so it gets the most
               // specific definition.  It does not matter much for most contracts given type compatibility but the
               // assignment contract requires the most specific definition so we can detect cycles properly.
               if (!inactiveType && !temporaryType && !thisModel.temporary) {
                  BodyTypeDeclaration modReplacedByType = modifyTypeDecl.replacedByType ;
                  if (modReplacedByType != null) {
                     if (modReplacedByType == this) {
                        System.out.println("*** Warning - already modified this type");
                        modifyTypeDecl.replacedByType = null;
                     }
                     else if (modifyTypeDecl.replaced) {
                        System.err.println("**** Error - modifying stale type");
                        modifyTypeDecl.replacedByType = null;
                     }
                     // If we are in the same layer, we are just about to replace the type as part of updateType
                     // Just don't update replacedByType in this case since we'll set that later on anyway.
                     else if (modReplacedByType.getLayer() != getLayer()) {
                        if (modReplacedByType.removed || modReplacedByType.getLayer().excluded)
                           modifyTypeDecl.replacedByType = null;
                        else {
                           // For layer components we might get the wrong type here - we use the baseLayers to do teh resolution so we may not find the
                           // layer directly in front of us - instead we find the layer a couple of levels deeper in.
                           if (!isLayerComponent()) {
                              // TODO: we should accumulate all of these layers into an index.  It's quite possible in the IDE context for us to
                              // find the same type modified by different layers... in that case representing the different possible stackings.
                              System.out.println("**** Warning - modify declaration: " + typeName + " in layer: " + getLayer() + " not replacing upstream layer of: " + modifyTypeDecl.getLayer() + " since it's already modified by: " + modifyTypeDecl.replacedByType.getLayer());
                           } else {
                              // This layer should be in front of us -
                              if (getLayer().layerPosition != -1 && getLayer().layerPosition < modReplacedByType.getLayer().layerPosition)
                                 System.out.println("*** Error - improper layer init ordering");
                              else {
                                 while (modifyTypeDecl.replacedByType != null) {
                                    modifyTypeDecl = modifyTypeDecl.replacedByType;
                                    checkModify();
                                 }

                                 modifyTypeDecl.replacedByType = null;
                              }
                           }
                        }
                     }
                  }

                  if (modifyTypeDecl.replacedByType == null && !modifyInherited && !(thisModel instanceof CmdScriptModel)) {
                     modifyTypeDecl.updateReplacedByType(this);
                  }
               }
            }
         }
      }
      catch (RuntimeException exc) {
         typeInfoInitialized = false;
         throw exc;
      }
   }

   public void start() {
      if (started)
         return;

      if (isStarting)
         return;

      isStarting = true;

      // Are we an "a.b" modify?
      compoundName = typeName.indexOf(".") != -1;

      JavaModel thisModel = getJavaModel();
      if (thisModel == null) {
         super.start();
         isStarting = false;
         return;
      }

      try {
         initTypeInfo();

         // need to start the extends type before we go and start our children.  This ensures an independent layer
         // gets started completely before the dependent layers.
         if (modifyTypeDecl != null) {
            startExtendedType(modifyTypeDecl, "modified");

            if (!modifyTypeDecl.getJavaModel().isStarted())
               System.out.println("*** Warning - modified model not started");

            // If our modifyTypeDeclaration is a dynamic new and we do not need a class, we start out as a dynamicNew as well
            if (modifyTypeDecl.dynamicNew) {
               dynamicNew = true;
               dynamicType = false;
            }
            // then, if our modified type picks up the dynamic behavior because it extends a class which is dynamic,
            // we need to inherit the dynamic behavior from that.
            else if (modifyTypeDecl.isDynamicType()) {
               dynamicNew = false;
               dynamicType = true;
            }
            // Did not get this right in initDynamicType because we did not resolve the modifyTypeDecl
            else if (dynamicNew && modifyTypeDecl.modifyNeedsClass()) {
               // OUr mod-type explicitly is set to be compiled only so we are not dynamic at all.
               if (modifyTypeDecl.compiledOnly) {
                  dynamicNew = false;
               }
               // This type is only setting properties but the base type needs a class so we do too.
               else {
                  dynamicType = true;
                  dynamicNew = false;
               }
            }

            boolean modifiedCompiledClassLoaded = false;
            Layer modLayer = modifyTypeDecl.layer;
            if (layer != null && modLayer != null) {

               boolean modifiedFinalLayer = !isLayerType && !layer.annotationLayer && modLayer.finalLayer;
               if (modifiedFinalLayer) {
                  displayError("Unable to modify type: " + typeName + " in final layer: " + modifyTypeDecl.layer + " for: ");
               }

               modifiedCompiledClassLoaded = !modifyTypeDecl.isDynamicType() && modLayer.compiledInClassPath && layer.layeredSystem.isClassLoaded(modifyTypeDecl.getCompiledClassName());
            }

            if (isDynamicType() && !dynamicNew) {
               if (modifiedCompiledClassLoaded) {
                  staleClassName = modifyTypeDecl.getCompiledClassName();
                  dynamicType = false;
                  dynamicNew = true;
               }
               else {
                  // If the modified type's layer has already been compiled into the classpath and loaded, we can't make it dynamic.  It's classes are in the
                  // classpath already and we can't replace them without a custom class loader.   Keeping this false lets us
                  // recognize and deal with this situation at runtime.
                  // If we are modifying a different type, i.e. modifyInherited = true, do not make it dynamic since that class is not dynamic.
                  if (!modifyInherited)
                     modifyTypeDecl.setDynamicType(true);
               }
            }
            else if (dynamicNew) {
               if (modifyTypeDecl.isDynamicType() && !modifyTypeDecl.dynamicNew) {
                  dynamicNew = false;
                  dynamicType = true;
               }
               else {
                  /**
                   * When a "configure only" modify operation is in a dynamic layer, the modified type is compiled, we have an optimization.
                   * The type is not dynamic but but it gets its dynamicNew flag set.   Propagate the dynamicNew flag to the modified
                   * type so that any "new X" calls for that type are converted to dynamic calls.
                   */
                  modifyTypeDecl.setDynamicNew(true);
               }
            }

            // Not registering enum constants in the sub-type table since they are only static classes.  Is that right?
            // only registering sub-types for the modifyInherited case - i.e. where we are not the same type as the modified type
            if (needsSubType())
               thisModel.layeredSystem.addSubType((TypeDeclaration) modifyTypeDecl, this);
        }

        if (extendsBoundTypes != null) {
            for (Object extendsTypeDecl:extendsBoundTypes) {
               extendsTypeDecl = ParamTypeDeclaration.toBaseType(extendsTypeDecl);
               if (extendsTypeDecl instanceof BodyTypeDeclaration) {
                  BodyTypeDeclaration extTD = (BodyTypeDeclaration) extendsTypeDecl;
                  startExtendedType(extTD, "extended");
                  if (extTD.isDynamicType()) {
                     dynamicType = true;
                     dynamicNew = false;
                  }
                  if (extTD instanceof TypeDeclaration) {
                     if (thisModel.layeredSystem != null)
                        thisModel.layeredSystem.addSubType((TypeDeclaration) extTD, this);
                  }
               }
            }
         }

         // After we've started the extends type, walk down the type hierarchy and collect dependencies we might have
         // on models we inherited from.
         BodyTypeDeclaration modifyType = modifyTypeDecl;
         while (modifyType != null) {
            JavaModel modifiedModel = modifyType.getJavaModel();
            List<SrcEntry> srcFiles = modifiedModel.getSrcFiles();
            if (srcFiles != null)
               thisModel.addDependentFiles(modifiedModel.getSrcFiles());
            Object modifyTypeObj = modifyType.getDerivedTypeDeclaration();
            if (modifyTypeObj instanceof TypeDeclaration) {
               modifyType = (TypeDeclaration) modifyTypeObj;
            }
            else
               modifyType = null;
         }

         // Need to start children after we've populated our extends type so we can get it.
         super.start();
      }
      catch (RuntimeException exc) {
         clearStarted();
         throw exc;
      }
      finally {
         isStarting = false;
      }
   }

   public void markExcluded(boolean topLevel) {
      super.markExcluded(topLevel);
      if (modifyTypeDecl != null && !modifyInherited)
         modifyTypeDecl.markExcluded(topLevel);
   }

   public void stop() {
      super.stop();
      modifyTypeDecl = null;
      modifyClass = null;
      extendsBoundTypes = null;
      typeInitialized = false;
      modifyInherited = false;
      compoundName = false;
      enumConstant = false;
      hiddenByType = null;
      hiddenByRoot = null;
      impliedRoots = null;
      isStarting = false;
   }

   private boolean needsSubType() {
      return modifyInherited && !temporaryType && modifyTypeDecl instanceof TypeDeclaration && !isHiddenType();
   }

   public void validate() {
      if (validated) return;

      if (isStarting)
         return;

      if (modifyTypeDecl != null) {
         // Get the most recent type in case this one was replaced
         modifyTypeDecl = modifyTypeDecl.resolve(false);
         checkModify();

         // Start and validate the modified type
         ParseUtil.initAndStartComponent(modifyTypeDecl);

         // If our modified type needs a dynamic stub we do too.   the propagateDynamicStub method only goes down the modify tree
         if (modifyTypeDecl.needsDynamicStub && !modifyInherited)
            needsDynamicStub = true;
      }

      if (modifyInherited && isDynamicType() && !ModelUtil.isDynamicType(modifyTypeDecl)) {
         getEnclosingType().setNeedsDynamicStub(true);
      }

      if (extendsBoundTypes != null && extendsBoundTypes.length > 0) {
         Object extType = extendsBoundTypes[0];
         if (extType != null && modifyTypeDecl instanceof TypeDeclaration) {
            TypeDeclaration baseTypeDecl = (TypeDeclaration) modifyTypeDecl;
            Object modifyExtendsType = baseTypeDecl.getExtendsTypeDeclaration();
            // TODO: should we also allow you to extend a sub-class that's already implemented by the type you modify? - i.e. include an && !isAssignableFrom test the other way?
            if (modifyExtendsType != null && !ModelUtil.isAssignableFrom(modifyExtendsType, extType)) {
               Layer thisLayer = getLayer();
               Layer modLayer = baseTypeDecl.getLayer();
               // For inactivated layers, we need to allow this since it's ok to have lots of layers with different implementations until you run them, but if our layer directly extends
               // the layer in question, we can safely report the error.
               if (getLayer().activated || (thisLayer != null && modLayer != null && thisLayer.extendsLayer(modLayer))) {
                  JavaSemanticNode node = this;
                  // Try to report the error on the conflicting extends type
                  if (extendsTypes != null && extendsTypes.size() > 0)
                     node = extendsTypes.get(0);
                  // Is this an error or a warning?  We let you replace the class and break the contract - should we let you replace the extends type?   or should this be a strict option on the layer?
                  node.displayTypeError("Modify extends incompatible type - ", ModelUtil.getClassName(extType), " should extend existing extends: ", ModelUtil.getClassName(modifyExtendsType), " for: ");
               }
            }
         }
      }

      super.validate();
   }

   public void unregister() {
      super.unregister();
      if (modifyTypeDecl != null && modifyTypeDecl instanceof TypeDeclaration)
         getLayeredSystem().removeSubType((TypeDeclaration) modifyTypeDecl, this);
   }

   public void setDynamicType(boolean val) {
      super.setDynamicType(val);
      if (modifyTypeDecl != null && !modifyInherited)
         modifyTypeDecl.setDynamicType(true);
   }

   public void enableNeedsCompiledClass() {
      super.enableNeedsCompiledClass();
      if (modifyTypeDecl != null)
         modifyTypeDecl.enableNeedsCompiledClass();
   }

   private TypeDeclaration getModifyType() {
      JavaModel thisModel = getJavaModel();
      String fullTypeName = getFullTypeName();
      if (isLayerType || thisModel == null)
         return null;
      TypeDeclaration res = null;
      if (temporaryType)
         res = thisModel.layeredSystem.getSrcTypeDeclaration(fullTypeName, null, true);

      return res == null ? thisModel.getPreviousDeclaration(fullTypeName) : res;
   }

   void checkModify() {
      JavaModel model = getJavaModel();
      if (model instanceof CmdScriptModel)
         return;
      if (getLayer() != null && modifyTypeDecl != null && modifyTypeDecl.getLayer() != null) {
         if (!temporaryType && getLayer().activated != modifyTypeDecl.getLayer().activated) {
            System.out.println("*** Invalid modify type - mixing inactive and active layers");
            // TODO: debug only
            Object modType = getModifyType();
         }
         if (!temporaryType && !modifyInherited && ModelUtil.sameTypes(this, modifyTypeDecl) && getLayer().getLayerName().equals(modifyTypeDecl.getLayer().getLayerName())) {
            System.out.println("*** Warning modifying a type by the one in the same layer: " + modifyTypeDecl);
            Object modType = getModifyType();
         }
      }
   }

   protected void completeInitTypeInfo() {
      super.completeInitTypeInfo();

      JavaModel thisModel = getJavaModel();
      if (thisModel == null) {
         // Fragment encountered during syntax parsing - nothing to initialize
         return;
      }

      boolean skipRoots = false;

      if (thisModel.customResolver != null) {
         String ftName = getFullTypeName();

         // There's a custom resolver for finding types - i.e. the model stream which will string together all of the modify tags that occur in the same stream for one object.
         // so there's a complete view of all of the new fields that we add.
         Object type = thisModel.customResolver.resolveType(thisModel.getPackagePrefix(), ftName, true, this);
         if (type == null) {
            DynUtil.execLaterJobs();
            type = thisModel.customResolver.resolveType(thisModel.getPackagePrefix(), ftName, true, this);
         }
         if (ModelUtil.isCompiledClass(type)) {
            modifyClass = type;
         }
         else if (type instanceof TypeDeclaration) {
            if (!ftName.startsWith("sc_type_"))
               modifyTypeDecl = (TypeDeclaration) type;
            else {
               // Allow the TypeDeclaration class itself to be overridden by a src stub
               Object newType = thisModel.customResolver.resolveType(thisModel.getPackagePrefix(), "sc.lang.java.TypeDeclaration", true, this);
               if (newType instanceof TypeDeclaration)
                  modifyTypeDecl = (TypeDeclaration) newType;
               else
                  modifyClass = ClassDeclaration.class;
            }
         }
         checkModify();
         // Don't want to set the implied roots for the ModelStream case.  The intermediate objects may not be valid
         // and it is not necessary for what we are doing with them.
         skipRoots = true;
      }
      else {
         // This type is dependent on any files that make up the modified type.  This includes the modified type and
         // any types it is modifying.   Temporary types can include the current layer since they don't replace this def anyway
         TypeDeclaration modifyType = getModifyType();
         if (modifyType == this) // layer definitions might return the same def since we don't look at path names for them
            modifyType = null;

         if (modifyType == null) {
            String fullTypeName = getFullTypeName();

            modifyClass = isLayerType ? null : thisModel.getClass(fullTypeName, false, layer, isLayerType, false, isLayerType);

            // For the layer object, it is registered as a global object.  Look it up with resolveName and just assign
            // the class to avoid the error.
            if (modifyClass == null)  {
               TypeDeclaration enclType = getEnclosingType();
               // If this is the top-level type in the layer, resolve it as a layer
               if (thisModel.isLayerModel && enclType == null) {
                  Object obj = thisModel.resolveName(fullTypeName, false, true);
                  // Originally the layer is initially registered under its type name - it gets renamed once after init
                  if (obj == null)
                     obj = thisModel.resolveName(typeName, false, true);
                  if (obj == this)
                     obj = null;
                  if (obj instanceof Layer) {
                     modifyClass = obj.getClass();
                     isLayerType = true;
                  }
                  // Not an active layer but maybe an inactive one?
                  else {
                     if (thisModel.isLayerModel) {
                        isLayerType = true;
                        modifyClass = Layer.class;
                     }
                     else if (!thisModel.temporary)
                        displayError("Layer definition file: " + thisModel.getSrcFile() + " has: " + fullTypeName + " expected: " + thisModel.getSrcFile().layer.layerDirName + ": ");
                  }
               }
               else {
                  if (enclType != null && enclType.isEnumeratedType()) {
                     enumConstant = true;
                  }
                  // A special case for temporary models which use modifyInherited.  They need to be able to resolve the
                  // inner types of the ext-type of the temporary type.
                  else if (enclType != null && temporaryType) {
                     Object enclExtType = ModelUtil.getExtendsClass(enclType);
                     if (enclExtType != null) {
                        Object inheritedType = ModelUtil.getInnerType(enclExtType, typeName, null);
                        if (inheritedType instanceof BodyTypeDeclaration)
                           modifyTypeDecl = (BodyTypeDeclaration) inheritedType;
                        else if (inheritedType != null)
                           modifyClass = inheritedType;
                        modifyInherited = true;
                     }
                  }
               }
            }
            if (!isLayerType && modifyClass != null && thisModel != null && thisModel.getLayer() != null && !thisModel.getLayer().annotationLayer && !temporaryType)
               displayTypeError("Unable to modify definition - " + getFullTypeName() + " have compiled definition only and layer is not an annotationLayer: " + toDefinitionString() + ": ");
         }
         else {
            if (modifyType == this)
               System.out.println("*** ERROR recursive modify");
            else {
               // Bind this here so that we use the pre-transformed value even after
               // this declaration has been moved.
               modifyTypeDecl = modifyType;
               checkModify();
            }

            JavaModel modifiedModel = modifyType.getJavaModel();
            if (modifiedModel.isLayerModel) {
               // A modify type inside of a layer definition is not a layer type - only the layer type itself.
               if (getEnclosingType() == null)
                  isLayerType = true;
            }
         }
      }

      if (modifyTypeDecl == null && modifyClass == null && !isLayerType && !enumConstant) {
         displayTypeError("Modify definition for type: ", getFullTypeName(), " no definition in previous layer for: ");
         Object tempRes = getModifyType();
      }

      initExtendsTypes();

      int lix;
      if ((lix = typeName.lastIndexOf(".")) != -1 && !skipRoots && thisModel != null && !(thisModel instanceof CmdScriptModel)) {
         List<Object> rootTypes = new ArrayList<Object>();

         String rootName = typeName.substring(0,lix);
         TypeDeclaration enclosingType = getEnclosingType();
         String parentPrefix;
         if (enclosingType == null)
            parentPrefix = thisModel.getPackagePrefix();
         else
            parentPrefix = enclosingType.getFullTypeName();

         // From the command line script, we use modify types where the modify type may not match the Java models' package - i.e. it accepts absolute type names.  So we are going to also skip the
         // implied roots if we are in that situation and there's not a match in the package
         skipRoots = modifyTypeDecl != null && !StringUtil.equalStrings(modifyTypeDecl.getPackageName(), parentPrefix);

         if (!skipRoots) {
            do {
               Object newRoot = thisModel.findTypeDeclaration(CTypeUtil.prefixPath(parentPrefix, rootName), false, true);
               if (newRoot == null) {
                  if (!isLayerType && errorArgs == null && thisModel.layer != null && !thisModel.isLayerModel)
                     displayTypeError("Unable to lookup root for modify: " + rootName + " ");
               }
               else
                  rootTypes.add(newRoot);

               lix = rootName.lastIndexOf(".");
               if (lix == -1)
                  break;
               else
                  rootName = rootName.substring(0, lix);
            } while (true);

            impliedRoots = rootTypes.toArray(new Object[rootTypes.size()]);
         }
      }
   }

   private void initExtendsTypes() {
      if (extendsTypes != null && extendsBoundTypes == null && !extendsInvalid) {
         extendsBoundTypes = new Object[extendsTypes.size()];
         int i = 0;
         try {
            for (JavaType extendsType:extendsTypes) {
               // We don't want to resolve from this type cause we get into a recursive loop in findType.
               JavaSemanticNode resolver = getEnclosingType();
               if (resolver == null)
                  resolver = getJavaModel();
               LayeredSystem sys = layer != null ? layer.layeredSystem : getLayeredSystem();
               extendsType.initType(sys, this, resolver, null, false, isLayerType, null);

               // Need to start the extends type as we need to dig into it
               Object extendsTypeDecl = extendsBoundTypes[i++] = extendsType.getTypeDeclaration();
               if (extendsTypeDecl == null) {
                  if (!isLayerType) {
                     extendsType.displayTypeError("Extended class not found for type: ", getFullTypeName(), ": ");
                     extendsInvalid = true;
                  }
                  else if (layer != null) {
                     String layerTypeName = extendsType.getFullTypeName();

                     if ((layer.activated ? sys.getLayerByPath(layerTypeName, true) : sys.getInactiveLayer(layerTypeName, false, true, true, false)) == null) {
                        // When we're activated and in a peer-system, we can't reliably look up the layers that are in other systems.   The 'check peers' flag does not work and we can't just look for these
                        // on the main system because this is happening too early - before he main system has loaded these other layers.  But for activated mode, we should validate all of the layers existence on the main system.
                        if (!sys.peerMode || !layer.activated)
                           extendsType.displayTypeError("No layer: ");
                     }
                  }
               }
               if (extendsTypeDecl != null && ModelUtil.sameTypes(this, extendsTypeDecl)) {
                  displayTypeError("Cycle found in extends - class extends itself: ", extendsType.getFullTypeName(), " for ");
                  extendsInvalid = true;
               }
            }
         }
         catch (RuntimeException exc) {
            System.out.println("*** Caught runtime exception while starting extends for: " + typeName);
            extendsBoundTypes = null;
            throw exc;
         }
      }

      if (!isLayerType) {
         String extTypeName = getExtendsTypeName();
         if (extTypeName != null) {
            JavaModel model = getJavaModel();
            if (model != null && !model.isLayerModel && model.layeredSystem != null && model.getLayer() != null) {
               // Make sure to resolve the extends type of this modified type from the current layer.
               // The case is:  A(L1) modifies A(L0)   A(L0) extends B(L0).   When we start A(L1) we need to make
               // sure that B(L1) has been started or we'll assume it just extends B(L0)
               Layer modelLayer = model.getLayer();
               if (!modelLayer.disabled) {
                  Object td = model.layeredSystem.getSrcTypeDeclaration(extTypeName, modelLayer.getNextLayer(), true, false, true, modelLayer, false);
                  if (td != null) {
                     ParseUtil.initComponent(td);
                     if (isStarted())
                        ParseUtil.startComponent(td);  // Only start here if we're already started.  Otherwise, we end up starting too soon when initializing extends types.
                     else if (typeInfoInitialized && td instanceof TypeDeclaration)
                        ((TypeDeclaration) td).initTypeInfo();
                     if (isValidated())
                        ParseUtil.validateComponent(td);
                  }
               }
            }
         }
      }
   }

   public JavaType getExtendsType() {
      return getInternalExtendsType(false);
   }

   public JavaType getDeclaredExtendsType() {
      return getInternalExtendsType(true);
   }

   // for layer types which can extend more than one type
   public Object[] getExtendsTypeDeclarations() {
      return extendsBoundTypes;
   }

   private JavaType getInternalExtendsType(boolean declared) {
      if (isLayerType) {
         // layers use multiple extendsTypes. Generally this method should be called for layer
         return null;
      }
      if (!extendsInvalid && extendsTypes != null && extendsTypes.size() == 1)
         return extendsTypes.get(0);
      if (!typeInitialized)
         initTypeInfo();
      if (modifyTypeDecl == null) {
         return null;
      }
      if (!declared && modifyTypeDecl instanceof TypeDeclaration)
         return modifyTypeDecl.getExtendsType();
      return null;
   }

   public void modifyExtendsType(JavaType type) {
      ((TypeDeclaration) getModifyTypeForTransform()).modifyExtendsType(type);
      incrVersion();
   }

   public DeclarationType getDeclarationType() {
      if (isLayerType)
         return DeclarationType.OBJECT;

      if (!typeInitialized)
         initTypeInfo();

      if (enumConstant)
         return DeclarationType.ENUMCONSTANT;

      if (modifyClass != null)
         return ModelUtil.isInterface(modifyClass) ? DeclarationType.INTERFACE :
                  ModelUtil.isAnnotation(modifyClass) ? DeclarationType.ANNOTATION :
                     ModelUtil.isEnum(modifyClass) ? DeclarationType.ENUM : DeclarationType.CLASS;

      if (modifyTypeDecl != null)
         return modifyTypeDecl.getDeclarationType();

      return DeclarationType.UNKNOWN;
   }

   public boolean getDefinesCurrentObject() {
      DeclarationType t = getDeclarationType();
      if (t != DeclarationType.OBJECT )
         return false;
      // If we have a modify type like "a.b" and "a" is a class, this definition does not define an object
      if (impliedRoots != null) {
         for (Object impl:impliedRoots) {
            if (!ModelUtil.isObjectType(impl))
               return false;
         }
      }
      return true;
   }

   /** Don't try to initialize for the user name since it is used in errors - can cause a recursive loop */
   public String getUserVisibleName() {
      if (typeInitialized)
         return getDeclarationType().name;
      else
         return "<not initialized>";
   }

   public Object definesType(String name, TypeContext ctx) {
      Object v = super.definesType(name, ctx);
      if (v != null)
         return v;

      if (extendsBoundTypes != null) {
         for (Object impl:extendsBoundTypes) {
            if (impl != null && (v = ModelUtil.getInnerType(impl, name, ctx)) != null)
               return v;
         }
      }
      if (impliedRoots != null) {
         for (Object impl:impliedRoots) {
            if (impl != null && (v = ModelUtil.getInnerType(impl, name, ctx)) != null)
               return v;
         }
      }
      return null;
   }

   public Object definesMethod(String name, List<?> types, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs) {
      Object v = super.definesMethod(name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs);
      if (v != null)
         return v;

      if (extendsBoundTypes != null) {
         LayeredSystem sys = getLayeredSystem();
         for (Object impl:extendsBoundTypes) {
            if (impl != null && (v = ModelUtil.definesMethod(impl, name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs, sys)) != null)
               return v;
         }
      }
      if (impliedRoots != null) {
         LayeredSystem sys = getLayeredSystem();
         for (Object impl:impliedRoots) {
            if (impl != null && (v = ModelUtil.definesMethod(impl, name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs, sys)) != null)
               return v;
         }
      }
      return null;
   }

   public Object declaresConstructor(List<?> types, ITypeParamContext ctx) {
      Object v = super.declaresConstructor(types, ctx);

      initTypeInfo();

      if (v != null || modifyInherited)
         return v;

      Object typeObj;
      if (enumConstant)
         typeObj = getEnclosingType();
      else if (modifyTypeDecl != null)
         typeObj = modifyTypeDecl;
      else
         typeObj = modifyClass;

      if (typeObj != null)
         return ModelUtil.declaresConstructor(getLayeredSystem(), typeObj, types, ctx);
      return null;
   }

   public Object declaresMethod(String name, List<? extends Object> types, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs, boolean includeModified) {
      Object res = super.declaresMethod(name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs, includeModified);
      if (res != null)
         return res;
      // if we have a modifyClass is there ever a case where we need to include those methods?  We typically use definesMethod for resolution - this is just for the IDE - overriding methods.
      if (includeModified && modifyTypeDecl != null) {
         return modifyTypeDecl.declaresMethod(name, types, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs, includeModified);
      }
      return null;
   }

   public Object[] getConstructors(Object refType) {
      Object[] res = super.getConstructors(refType);

      if (modifyTypeDecl != null && !modifyInherited) {
         Object[] modConsts = modifyTypeDecl.getConstructors(refType);
         if (modConsts != null) {
            if (res != null) {
               List modProps = ModelUtil.mergeMethods(Arrays.asList(modConsts), Arrays.asList(res));
               res = modProps.toArray();
            }
            else
               res = modConsts;
         }
      }
      return res;
   }

   public Object definesConstructor(List<?> types, ITypeParamContext ctx, boolean isTransformed) {
      Object v = super.definesConstructor(types, ctx, isTransformed);
      if (v != null)
         return v;

      if (extendsBoundTypes != null) {
         for (Object impl:extendsBoundTypes) {
            if (impl != null && (v = ModelUtil.definesConstructor(getLayeredSystem(), impl, types, ctx, null, isTransformed)) != null)
               return v;
         }
      }
      return null;
   }

   public Object definesMemberInternal(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      Object v = super.definesMemberInternal(name, mtype, refType, ctx, skipIfaces, isTransformed);
      if (v != null) {
         // After we've been transformed, objects should not prevent us from finding a getX method in the base type
         // At this point, really the modify type is not used - it has been merged into the base type
         if (v == STOP_SEARCHING_SENTINEL && transformed && modifyTypeDecl != null)
            return ModelUtil.definesMember(modifyTypeDecl, name, mtype, refType, ctx, skipIfaces, isTransformed, getLayeredSystem());
         return v;
      }

      if (extendsBoundTypes != null) {
         for (Object impl:extendsBoundTypes) {
            if (impl != null && (v = ModelUtil.definesMember(impl, name, mtype, refType, ctx, skipIfaces, isTransformed, getLayeredSystem())) != null)
               return v;
         }
      }
      if (impliedRoots != null) {
         for (Object impl:impliedRoots) {
            if (impl != null && (v = ModelUtil.definesMember(impl, name, mtype, refType, ctx, skipIfaces, isTransformed, getLayeredSystem())) != null)
               return v;
         }
      }
      return null;
   }

   public Object getSimpleInnerType(String name, TypeContext ctx, boolean checkBaseType, boolean redirected, boolean srcOnly) {
      Object bt = super.getSimpleInnerType(name, ctx, checkBaseType, true, srcOnly);
      if (bt != null)
         return bt;

      if (extendsBoundTypes != null) {
         for (Object extBoundType:extendsBoundTypes) {
            if (extBoundType instanceof BodyTypeDeclaration) {
               BodyTypeDeclaration base = ((BodyTypeDeclaration) extBoundType);
               // Do not pass the CTX down here.  Because this is not the same type, we are extending another type, we do need to check for types defined up in the stack.  There's no risk of getting the same type here.
               bt = base.getSimpleInnerType(name, null, checkBaseType, true, srcOnly);
               if (bt != null) {
                  if (ctx != null)
                     ctx.add(base, this);
                  return bt;
               }
            }
            else
               return ModelUtil.getInnerType(extBoundType, name, ctx);
         }
      }
      return null;
   }

   public Object getAnnotation(String annotationName) {
      Object annot = super.getAnnotation(annotationName);

      // Then the modified type
      Object superType = getDerivedTypeDeclaration();
      if (superType != null) {
         // We should not be modifying .classes so any annotation we pull off of a modified type should be in src
         Object superAnnot = ModelUtil.getAnnotation(superType, annotationName);

         if (annot != null && superAnnot != null) {
            return ModelUtil.mergeAnnotations(annot, superAnnot, false);
         }
         else if (annot != null)
            return annot;
         else
            return superAnnot;
      }
      return null;
   }

   public String getInheritedScopeName() {
      // First check this definition
      String s = getScopeName();
      if (s != null)
         return s;

      // Then any modified extends
      String sn;
      if (extendsBoundTypes != null) {
         for (Object extBoundType:extendsBoundTypes) {
            sn = ModelUtil.getInheritedScopeName(getJavaModel().getLayeredSystem(), extBoundType, getLayer());
            if (sn != null)
               return sn;
         }
      }
      // Then the modified type - but only if this is not a Class.  In getInheritedScopeName we bounce back to the src type and go into an infinite loop
      Object superType = getDerivedTypeDeclaration();
      if (superType instanceof BodyTypeDeclaration) {
         // We should not be modifying .classes so any annotation we pull off of a modified type should be in src
         return ModelUtil.getInheritedScopeName(getLayeredSystem(), superType, getLayer());
      }
      return null;
   }

   static int recurseCount = 0;

   public Object getInheritedAnnotation(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      // First check this definition
      Object annot = getAnnotation(annotationName);
      if (annot != null)
         return annot;

      // Then any modified extends
      Object annotObj;
      if (extendsBoundTypes != null) {
         for (Object extBoundType:extendsBoundTypes) {
            if (extBoundType != null) {
               annotObj = ModelUtil.getInheritedAnnotation(getJavaModel().getLayeredSystem(), extBoundType, annotationName, skipCompiled, refLayer, layerResolve);
               if (annotObj != null)
                  return annotObj;
            }
         }
      }
      // Then the modified type
      Object superType = getDerivedTypeDeclaration();
      if (superType != null) {
         // We should not be modifying .classes so any annotation we pull off of a modified type should be in src
         return ModelUtil.getInheritedAnnotation(getLayeredSystem(), superType, annotationName, skipCompiled, refLayer, layerResolve);
      }
      return null;
   }

   public ArrayList<Object> getAllInheritedAnnotations(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      // First check this definition
      Object annot = getAnnotation(annotationName);
      ArrayList<Object> res = null;
      if (annot != null) {
         res = new ArrayList<Object>(1);
         res.add(annot);
      }

      LayeredSystem sys = getJavaModel().getLayeredSystem();

      // Then any modified extends
      ArrayList<Object> superRes;
      if (extendsBoundTypes != null) {
         for (Object extBoundType:extendsBoundTypes) {
            LayeredSystem useSys = sys;
            Layer useRefLayer = refLayer;
            // Weird case - for layer definition files extendsBoundTypes can be in different runtimes.  So we need to pass in
            // a different refLayer for these lookups or they trigger our error detection of mismatching runtimes for the refLayer.
            if (isLayerType && extBoundType instanceof ModifyDeclaration) {
               ModifyDeclaration extLayerType = (ModifyDeclaration) extBoundType;
               useRefLayer = extLayerType.getLayer();
               useSys = useRefLayer.getLayeredSystem();
            }
            superRes = ModelUtil.getAllInheritedAnnotations(useSys, extBoundType, annotationName, skipCompiled, useRefLayer, layerResolve);
            if (superRes != null) {
               res = ModelUtil.appendLists(res, superRes);
            }
         }
      }
      // Then the modified type
      Object superType = getDerivedTypeDeclaration();
      if (superType != null) {
         // We should not be modifying .classes so any annotation we pull off of a modified type should be in src
         superRes = ModelUtil.getAllInheritedAnnotations(getLayeredSystem(), superType, annotationName, skipCompiled, refLayer, layerResolve);
         if (superRes != null)
            res = ModelUtil.appendLists(res, superRes);
      }
      return res;
   }

   public int addChildNames(StringBuilder childNames, Map<String,StringBuilder> childNamesByScope, String prefix, boolean componentsOnly,
                            boolean thisClassOnly, boolean dynamicOnly,  Set<String> nameSet, ArrayList<Object> objTypes) {
      int ct = 0;

      // Process the extends from the modify type first since it's part of the extends type of the result and it's children are first in the compiled version
      if (extendsBoundTypes != null && !thisClassOnly) {
         for (Object extBoundType:extendsBoundTypes) {
            if (extBoundType instanceof TypeDeclaration)
               ct += ((TypeDeclaration) extBoundType).addChildNames(childNames, childNamesByScope, prefix, componentsOnly,
                       thisClassOnly, dynamicOnly, nameSet, objTypes);
         }
      }

      Object extendsType = getDerivedTypeDeclaration();
      if (extendsType instanceof TypeDeclaration) {
         ct += ((TypeDeclaration) extendsType).addChildNames(childNames, childNamesByScope, prefix, componentsOnly,
                                                             thisClassOnly, dynamicOnly, nameSet, objTypes);
      }

      if (body != null) {
         for (Statement statement:body) {
            TypeDeclaration typeDecl;
            if (statement instanceof TypeDeclaration &&
                    (typeDecl = (TypeDeclaration) statement).getDeclarationType() == DeclarationType.OBJECT) {
               if (!componentsOnly || ModelUtil.isComponentInterface(getLayeredSystem(), typeDecl)) {
                  String childName = prefix == null ? typeDecl.typeName : prefix + "." + typeDecl.typeName;
                  if (!nameSet.contains(childName)) {
                     if (childNames.length() > 0)
                        childNames.append(", ");
                     childNames.append(childName);
                     nameSet.add(childName);
                     if (objTypes != null)
                        objTypes.add(typeDecl);
                     ct++;
                  }
               }
            }
         }
      }
      return ct;
   }

   public boolean needsTransform() {
      return true;
   }

   public boolean mergeDefinitionsInto(BodyTypeDeclaration baseType, boolean inTransformed) {
      boolean any = false;

      // We want to find the element on the transformed model so it can use addMixinProperties
      if (element != null && baseType instanceof TypeDeclaration) {
         TypeDeclaration baseTD = (TypeDeclaration) baseType;
         baseTD.element = element;
      }

      if (baseType.dependentTypes != null) {
         if (dependentTypes != null) // We'll likely need this later so merge the tables if possible
            baseType.dependentTypes.addAll(dependentTypes);
         else
            baseType.dependentTypes = null; // clear it out so it's recomputed next time
      }

      if (implementsTypes != null) {
         if (baseType instanceof EnumConstant)
            displayError("Cannot add interface to an enum constant");
         else {
            for (JavaType impl:implementsTypes) {
               ((TypeDeclaration)baseType).addImplements(impl);
               any = true;
            }
         }
      }

      if (extendsBoundTypes != null && !extendsOverridden) {
         if (extendsTypes.size() > 1)
            displayError("Only one type allowed for extends definition of a class: ");
         else if (modifyTypeDecl instanceof EnumConstant)
            displayError("Cannot add extends to enum constant");
         else {
            Object extType = extendsBoundTypes[0];
            if (extType != null && baseType instanceof TypeDeclaration) {
               TypeDeclaration baseTypeDecl = (TypeDeclaration) baseType;
               Object modifyType = baseTypeDecl.getExtendsTypeDeclaration();
               if (modifyType == null || ModelUtil.isAssignableFrom(modifyType, extType)) {
                  ClassType newExt = (ClassType) extendsTypes.get(0).deepCopy(CopyNormal, null);
                  baseTypeDecl.modifyExtendsType(newExt);
                  any = true;
               }
               else
                  System.out.println("*** Warning - ignoring modify extends class during transform");
            }
         }
      }

      if (body != null) {
         for (int i = 0; i < body.size(); i++) {
            Statement statement = body.get(i);

            statement.modifyDefinition(baseType, true, inTransformed);
            any = true;
         }
      }

      any = mergeModifiersInto(baseType) || any;

      return any;
   }

   boolean mergeModifiersInto(BodyTypeDeclaration baseType) {
      // We don't merge the default access level here - just accessing something in a public layer should not
      // make it public
      boolean any = false;
      if (baseType.mergeModifiers(this, true, false))
         any = true;
      if (autoComponent == null || autoComponent)
         baseType.autoComponent = null; // Clear this out for the base type
      return any;
   }

   public void process() {
      if (processed)
         return;

      // Before the transform, we need to figure out which types need to have their extends overridden - so we don't merge the
      // subsequent extends type and override the previous one.   Don't do this in "start" because as we are starting types
      // we need the extends types to be used for validating members in previous layers.
      if (extendsTypes != null && extendsTypes.size() > 0) {
         if (modifyTypeDecl != null && modifyTypeDecl.getExtendsType() != null) {
            // TODO: check if the old type is castable to the new one.  If not, track this as a type incompatbile layer and list the types which change.  Use that for good diagnostics when we get type errors on this layer.
            // Should be able to validate the reference in the overridden extends class so we can print out the offending layer, type, etc.   Also do this when a class overrides an existing class at the top level.
            modifyTypeDecl.extendsOverridden = true;
         }

      }
      // Since we inherit getExtendsType we also need to propagate this down
      if (extendsOverridden && modifyTypeDecl != null && modifyTypeDecl.getExtendsType() != null && !modifyInherited)
         modifyTypeDecl.extendsOverridden = true;

      super.process();

      if (!modifyInherited) {
         TypeDeclaration outer = getEnclosingType();
         if (outer == null && modifyTypeDecl != null) {
            JavaModel extendedModel = modifyTypeDecl.getJavaModel();
            JavaModel thisModel = getJavaModel();
            thisModel.addReverseDeps(extendedModel.reverseDeps);
         }
      }
      // No, we'll just simulate modifyInherited at runtime rather than convert it.
      //if (isDynamicType())
      //   transformModifyInherited(ILanguageModel.RuntimeType.JAVA, false);
      JavaModel model = getJavaModel();
      // Don't do this for model streams
      if (compoundName && model != null && model.mergeDeclaration) {
         replaceHiddenType(null);
      }
   }

   /** gets rid of the "a.b" shortcut types - creates a if necessary */
   public BodyTypeDeclaration replaceHiddenType(BodyTypeDeclaration parent) {
      if (hiddenByType == null) {
         if (parent != null)
            setParentNode(parent);
         if (!isStarted())
            ParseUtil.initAndStartComponent(this);

         String nextType = typeName;
         String rest;
         ClassDeclaration subClass;
         BodyTypeDeclaration nextParent = getEnclosingType(), theRoot = null;

         // If this is a root level A.b.c, it's just a way to refer to a child node via the root node.
         if (nextParent == null) {
            return this;
         }

         BodyTypeDeclaration currentModType;
         BodyTypeDeclaration subType;
         do {
            // Need to handle the "a.b" types here - we create class a extends modType.a {  class b extends modType.a.b here }
            int dotIx = nextType.indexOf(".");
            if (dotIx != -1) {
               rest = nextType.substring(dotIx+1);
               nextType = nextType.substring(0, dotIx);
               int startIx = 0;
               int upCt;
               // Count number of "."s in the current name, including the one we stripped off
               for (upCt = 1; (startIx = nextType.indexOf('.', startIx)) != -1; upCt++)
                  ;
               currentModType = modifyTypeDecl;
               for (int up = 0; up < upCt; up++) {
                  if (currentModType == null) {
                     // Should not get here
                     System.err.println("*** Unable to resolve parent type for complex modify: " + typeName);
                     return this;
                  }
                  currentModType = currentModType.getEnclosingType();
               }
            }
            else {
               rest = null;
               currentModType = modifyTypeDecl;
            }
            if (currentModType == null) {
               System.err.println("*** Can't find mod type for type transform: " + typeName);
               return this;
            }

            if (nextParent == null) {
               System.err.println("*** Can't find next parent type for type transform: " + typeName);
               return this;
            }

            subType = null;
            // CheckbaseType is true here - we'll need to create a modify if there's another type.  If it comes from
            // the extends type, that's the "modifyInherited" case.
            Object innerTypeObj = nextParent.getInnerType(nextType, null, true, false, false);
            if (innerTypeObj != this && innerTypeObj instanceof BodyTypeDeclaration) {
               subType = (BodyTypeDeclaration) innerTypeObj;
            }

            if (subType == null) {
               // Did not find an existing subType so create a new one
               subClass = new ClassDeclaration();
               subClass.typeName = nextType;
               subClass.setProperty("extendsType", ClassType.create(ModelUtil.getTypeName(currentModType)));
               subClass.operator = currentModType.getDeclarationType() == DeclarationType.OBJECT ? "object" : "class";
               if (theRoot == null) {
                  theRoot = subClass;
               }
               else {
                  nextParent.addBodyStatement(subClass);
               }
               subType = subClass;
            }
            else {
               // If we inherit a definition, need to create the implied ModifyDeclaration to map from this type
               // to the next
               if (subType.getEnclosingType() != nextParent) {
                  subType = new ModifyDeclaration();
                  subType.typeName = nextType;
                  nextParent.addBodyStatement(subType);
               }
               if (theRoot == null)
                  theRoot = subType;
            }
            nextParent = subType;

            nextType = rest;
         } while (rest != null);
         BodyTypeDeclaration parentType = getEnclosingType();
         parentType.addToHiddenBody(theRoot);
         hiddenByRoot = theRoot;
         hiddenByType = subType;
      }
      return hiddenByType;
   }

   protected BodyTypeDeclaration getModifyTypeForTransform() {
      BodyTypeDeclaration modType = modifyTypeDecl;
      if (modType != null && getLayeredSystem().options.clonedTransform) {
         // Need to start at the most specific model if we are going to do the transform
         BodyTypeDeclaration modResolvedType = modType.resolve(true);
         JavaModel modModel = modResolvedType.getJavaModel();
         if (modModel.nonTransformedModel == null && modModel.mergeDeclaration) {
            modType = modType.transformedType;
            if (modType == null) {
               // We are the first to need the transformed model for this type.  We do always transform the modified model at the top-level but if there's an extended model which is modified by a sub-type, we won't have transformed that.
               modModel.cloneTransformedModel();
               if (modifyTypeDecl.replaced) {
                  BodyTypeDeclaration newDecl = modifyTypeDecl.resolve(false);
                  if (newDecl == this)
                     System.err.println("*** ERROR recursive modify");
                  else {
                     modifyTypeDecl = newDecl;
                     checkModify();
                  }
               }
               modType = modifyTypeDecl.transformedType;
               if (modType == null) {
                  System.out.println("*** Error no transformed type for modify!");
                  modType = modifyTypeDecl;
               }
            }
         }
      }
      return modType;
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      // If our parent type was excluded, we should not be here.  So this must be the case where we are an inner type
      // that's excluded from it's outer type in this runtime - just remove this code from the parent.
      if (excluded) {
         return transformExcluded(runtime);
      }

      if (!processed)
         process();

      if (enumConstant) {
         transformEnum(runtime);
         return true;
      }

      if (modifyTypeDecl == null && getJavaModel().mergeDeclaration) {
         System.err.println("**** Can't find a definition to modify " + typeName + " in layer previous to: " + getJavaModel().getLayer().getLayerUniqueName() + ": " + toDefinitionString());
         return true;
      }

      JavaModel thisModel = getJavaModel();
      if (!modifyInherited) {
         if (thisModel.mergeDeclaration) {
            BodyTypeDeclaration modType = getModifyTypeForTransform();

            // Do imports first so we can use them to resolve any types when adding the implements etc.
            TypeDeclaration outer = getEnclosingType();
            if (outer == null) {
               JavaModel extendedModel = modType.getJavaModel();
               thisModel.copyImports(extendedModel);
               extendedModel.syncAutoImports();
            }


            // Essentially a dynamic prop-set only modify which does not modify the compiled type... We can process
            // this at initialize time, letting you configure a compiled type without any code-gen via an a.b relationship
            // So instead of merging our definition into the base node, we just skip that step.
            // TODO: remove this test?  in what situations are we transforming a dynamic type anyway?
            if (!dynamicType || compiledOnly) { // Was dynamicNew... but when we modify a compiled type, it will be dynamic new.  The compiled properties need to get merged in that case
               if (typeParameters != null)
                  modType.setProperty("typeParameters", typeParameters);

               mergeDefinitionsInto(modType, true);

               modType.transformDefaultModifier();
            }

            if (propertiesToMakeBindable != null) {
               modType.addAllPropertiesToMakeBindable(propertiesToMakeBindable);
            }

            if (dynInvokeMethods != null)
               modType.mergeDynInvokeMethods(dynInvokeMethods);

            // Propagate down the sync properties to the leaf node
            if (syncProperties != null)
               modType.syncProperties = syncProperties;

            ISemanticNode subNode = modType;
            String tn = typeName;
            int ix = 0;
            // If this is an "a.b" expression, we need to get the modified type's parent to replace
            while ((ix = tn.indexOf(".", ix)) != -1) {
                subNode = subNode.getParentNode().getParentNode();
               ix = ix + 1;
            }

            JavaModel prevModel = modType.getJavaModel();

            if (parentNode.replaceChild(this, subNode) == -1)
               System.err.println("*** failed to replace modifyType from parent");

            if (!prevModel.getTransformed()) {
               // Need to do this at the model level to turn off errors (at least)
               prevModel.transform(runtime);
            }

            if (!modType.getTransformed())
               modType.transform(runtime);
         }
         else
            super.transform(runtime);
      }
      // The modifyTypeDecl is not the same type in the layer path.  Instead, it is inherited... we'll
      // turn this into class/object typeName extends <modifyType> ... our body ...
      else {
         if (thisModel.mergeDeclaration)
            transformModifyInherited(runtime, true);
      }

      // Note: we are not calling super here but still need to mark this as transformed
      transformed = true;

      return true;
   }

   private boolean transformEnum(ILanguageModel.RuntimeType runtime) {
      TypeDeclaration enclType = getEnclosingType();
      // For EnumDeclarations, replace this node with an EnumConstant placed after the last EnumConstant defined.
      if (enclType instanceof EnumDeclaration) {
         EnumDeclaration parentEnum = (EnumDeclaration) enclType;
         EnumConstant newConst = new EnumConstant();
         newConst.typeName = typeName;
         newConst.setProperty("body", body);

         int enumSepIndex = -1;
         for (int i = 0; i < parentEnum.body.size(); i++) {
            if (parentEnum.body.get(i) instanceof EmptyStatement) {
               enumSepIndex = i;
               break;
            }
            // The EnumDeclaration will have the extra "semi" to separate EnumConstants from body definitions.  By adding it immediately after the last EnumConstant, we'll
            // insert before that semi (if it exists).
         }
         if (enumSepIndex == -1)
            parentEnum.addBodyStatement(new EmptyStatement());
         int thisIndex = parentEnum.body.indexOf(this);
         if (thisIndex == -1 || thisIndex > enumSepIndex) {
            parentEnum.body.add(enumSepIndex, newConst);
            if (thisIndex != -1) {
               parentNode.removeChild(this);
               SemanticNode node = parentEnum.body.get(thisIndex);
               node.transform(runtime);
            }
         }
         else {
            parentNode.replaceChild(this, newConst);
         }
         newConst.transform(runtime);

         // Wait till we've hooked in the model to make this call
         if (propertiesToMakeBindable != null)
            newConst.addAllPropertiesToMakeBindable(propertiesToMakeBindable);

         return true;
      }
      // For ClassDeclarations marked with @Enumerated, we replace with an "object extends parentType" construct
      else {
         ClassDeclaration newObj = new ClassDeclaration();
         newObj.operator = "object";
         newObj.typeName = typeName;
         newObj.setProperty("extendsType", ClassType.create(enclType.getFullTypeName()));
         newObj.setProperty("body", body);
         if (propertiesToMakeBindable != null)
            newObj.addAllPropertiesToMakeBindable(propertiesToMakeBindable);

         parentNode.replaceChild(this, newObj);

         newObj.transform(runtime);
         return true;
      }

   }

   /**
    * When the modify is part of an inner type, it can modify a type inherited from the extended type of the outer type.
    * This turns out not to need a true modify at all, but instead is the traditional extends operation, but where you use the
    * same name in the new type.  If this is an object definition, we use the same getX method so it effectively changes
    * the type of the object.
    *
    * This method gets called in two contexts.  For compiled types, when we transform the type we need to do this
    * conversion.   For dynamic types, we do not do the transform, but we still need this transform to be applied
    * to the model before we can run it.
    */
   public boolean transformModifyInherited(ILanguageModel.RuntimeType runtime, boolean finishTransform) {
      String nextType = typeName;
      String rest;
      ClassDeclaration subClass;
      BodyTypeDeclaration nextParent = getEnclosingType(), theRoot = null;
      BodyTypeDeclaration currentModType;
      BodyTypeDeclaration subType;
      boolean replaceNode = true;
      boolean newType;
      do {
         // Need to handle the "a.b" types here - we create class a extends modType.a {  class b extends modType.a.b here }
         int dotIx = nextType.indexOf(".");
         if (dotIx != -1) {
            rest = nextType.substring(dotIx+1);
            nextType = nextType.substring(0, dotIx);
            int startIx = 0;
            int upCt;
            // Count number of "."s in the current name, including the one we stripped off
            for (upCt = 1; (startIx = nextType.indexOf('.', startIx)) != -1; upCt++)
               ;
            currentModType = getModifyTypeForTransform();
            for (int up = 0; up < upCt; up++) {
               if (currentModType == null) {
                  // Should not get here
                  System.err.println("*** Unable to resolve parent type for complex modify: " + typeName);
                  break;
               }
               currentModType = currentModType.getEnclosingType();
            }
         }
         else {
            rest = null;
            currentModType = getModifyTypeForTransform();
         }
         if (currentModType == null) {
            System.err.println("*** Can't find mod type for type transform: " + typeName);
            return true;
         }

         subType = null;
         Object innerTypeObj = nextParent.getInnerType(nextType, null, false, false, false);
         if (innerTypeObj != this && innerTypeObj instanceof BodyTypeDeclaration) {
            subType = (BodyTypeDeclaration) innerTypeObj;
            if (theRoot == null)
               replaceNode = false;
         }

         // Did not find an existing subType so create a new one
         if (subType == null) {
            newType = true;
            subClass = new ClassDeclaration();
            subClass.typeName = nextType;
            subClass.setProperty("extendsType", ClassType.create(ModelUtil.getTypeName(currentModType)));
            subClass.operator = currentModType.getDeclarationType() == DeclarationType.OBJECT ? "object" : "class";
            if (ModelUtil.hasModifier(currentModType, "public"))
               subClass.addModifier("public");

            if (theRoot == null) {
               theRoot = subClass;
            }
            else {
               nextParent.addBodyStatement(subClass);
            }
            subType = subClass;
         }
         else {
            newType = false;
            if (theRoot == null)
               theRoot = subType;
         }
         nextParent = subType;

         nextType = rest;
      } while (rest != null);

      ISemanticNode toTransform = null;
      if (replaceNode) {
         transformedType = theRoot;
         // Need to set the parent before we start it
         parentNode.replaceChild(this, theRoot);
      }
      else {
         int ix = parentNode.removeChild(this);
         if (ix == -1) {
            System.err.println("*** Cannot find child to remove in transform");
         }
         // If we remove a node in a list which is being transformed, we need to transform the next guy in the list
         // since the SemanticNodeList's loop will skip this node in this case.
         else if (parentNode instanceof SemanticNodeList) {
            SemanticNodeList list = ((SemanticNodeList) parentNode);
            if (list.size() > ix) {
               Object nextNode = list.get(ix);
               if (nextNode instanceof ISemanticNode) {
                  toTransform = (ISemanticNode) nextNode;
               }
            }
         }
      }

      // move over the body for the last guy
      if (newType) {
         subType.setProperty("body", body);

         mergeModifiersInto(subType);
      }
      else
         mergeDefinitionsInto(subType, true);

      if (finishTransform) {
         if (replaceNode)
            theRoot.transform(runtime);
         if (toTransform != null)
            toTransform.transform(runtime);
      }
      return true;
   }

   public Layer createLayerInstance(String packagePrefix, boolean inheritedPrefix) {
      isLayerType = true;
      initTypeInfo();
      return (Layer) createInstance(packagePrefix, inheritedPrefix);
   }

   public Object createInstance() {
      return createInstance(null, false);
   }

   public Object createInstance(String prefix, boolean inheritedPrefix) {
      BodyTypeDeclaration td = modifyTypeDecl;
      Object inst;
      if (td != null) {
         return super.createInstance();
      }
      // Special initialization for layer instances
      else {
         JavaModel m = getJavaModel();
         inst = m.resolveName(CTypeUtil.prefixPath(m.getPackagePrefix(), typeName), false, true);
         if (inst == null) {
            if (m.isLayerModel) {
               String err = "Layer definition file refers to: " + typeName + " which does not match it's path: " + getJavaModel().getSrcFile();
               // want this to show up as an error in the UI but also can't return null here
               m.displayError(err + " for: ");
               throw new IllegalArgumentException(err);
            }
            else
               throw new IllegalArgumentException("Can't create instance of non-existent type: " + typeName + " used in modify statement: " + toDefinitionString());
         }

         // If the resolved name binds to a runtime class, we'll create an instance of that class then set the properties
         // TODO: if this is a component, we should probably be doing the component init here right?
         if (inst instanceof CFClass)
            inst = ((CFClass) inst).getCompiledClass();
         if (inst instanceof Class) {
            inst = RTypeUtil.createInstance((Class) inst);
         }
         // For the layer models, need to start the model file before we init the instance or else
         // all of the declarations in the model won't be started when we need to eval them.
         if (m.isLayerModel) {
            ((Layer) inst).layerPosition = -1;
            ParseUtil.startComponent(m);
         }
      }
      initLayerInstance(inst, prefix, inheritedPrefix);

      return inst;
   }

   public void initLayerInstance(Object inst, String prefix, boolean inheritedPrefix) {
      if (inst instanceof Layer) {
         Layer linst = (Layer) inst;
         isLayerType = true;

         // Need to init this first from the configuration so users can shut it off
         initDynamicProperty(inst, "inheritPackage");
         initDynamicProperty(inst, "exportPackage");

         // Setting this here so it's defined before we init the dynamic properties.
         linst.model = getJavaModel();

         // For the layer object, we need to set the package prefix before we try to use the object in initDynamicInstance.
         if (prefix != null) {
            if (linst.inheritPackage || !inheritedPrefix) {
               linst.packagePrefix = prefix;
               String lname = linst.getLayerUniqueName();
               if (lname == null || !lname.startsWith(prefix)) {
                  if (lname == null)
                     lname = ((JavaModel) linst.model).getModelTypeName(); // TODO: is this right
                  else if (lname != null)
                     lname = CTypeUtil.prefixPath(prefix, lname);
                  linst.setLayerUniqueName(lname);
               }
            }
         }
      }
      initDynamicInstance(inst);
   }

   // We don't have to do anything here except add us to our output - while the parent is being transformed...
   // we'll do it in the transform method later
   public Definition modifyDefinition(BodyTypeDeclaration base, boolean doMerge, boolean inTransformed) {
      TypeDeclaration outer = getEnclosingType();
      if (outer != null) {
         // If this modify is overriding a definition in the same type we need to replace it here
         // so both are not processed when processing the parent type.
         Object toReplaceObj = base.getInnerType(typeName, null, true, false, false);
         if (toReplaceObj instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration toReplace = (BodyTypeDeclaration) toReplaceObj;
            if (toReplace.getEnclosingType() == base) {
               toReplace.parentNode.replaceChild(toReplace, this);
            }
            else {
               base.addBodyStatement(this);
            }
         }
         else if (toReplaceObj == null) {
            base.addBodyStatement(this);
         }
      }
      return this;
   }

   /** Our type name is always the same as the one we extend in the modify declaration */
   public String getDerivedTypeName() {
      return getFullTypeName();
   }

   public boolean isAssignableFrom(ITypeDeclaration other, boolean assignmentSemantics) {
      if (other.getFullTypeName().equals(getFullTypeName()))
         return true;
      Object extType = getDerivedTypeDeclaration();
      return ModelUtil.isAssignableFrom(extType, other, assignmentSemantics, null, getLayeredSystem());
   }

   public boolean isAssignableTo(ITypeDeclaration other) {
      if (other == this)
         return true;
      if (other.getFullTypeName().equals(getFullTypeName()))
         return true;

      if (extendsBoundTypes != null) {
         for (int i = 0; i < extendsBoundTypes.length; i++) {
            Object implType = extendsBoundTypes[i];
            if (implType instanceof ITypeDeclaration && ((ITypeDeclaration) implType).isAssignableTo(other))
               return true;
            else if (implType != null && ModelUtil.isAssignableFrom(other, implType))
               return true;
         }
      }

      if (implementsBoundTypes != null) {
         for (int i = 0; i < implementsBoundTypes.length; i++) {
            Object implType = implementsBoundTypes[i];
            if (ModelUtil.isCompiledClass(implType))
               implType = ModelUtil.resolveSrcTypeDeclaration(getLayeredSystem(), implType, false, false, getLayer());

            if (implType instanceof ITypeDeclaration && ((ITypeDeclaration) implType).isAssignableTo(other))
               return true;
         }
      }

      if (enumConstant)
       return getEnclosingType().isAssignableTo(other);

      Object extType = getDerivedTypeDeclaration();
      if (extType instanceof ITypeDeclaration)
         return ((ITypeDeclaration) extType).isAssignableTo(other);
      if (extType == null) // Compile error - an unresolved type?
         return false;
      return ModelUtil.isAssignableFrom(other, extType);
   }

   public String getFullTypeName(boolean includeDims, boolean includeTypeParams) {
      return getFullTypeName(); // Can't have dims or bound type params
   }

   public boolean isStaticInnerClass() {
      return getEnclosingType() != null && isStaticType();
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

   public boolean hasModifier(String modifier) {
      // Need to at least inherit the static behavior because of usages like addAllFields - where we expect the type
      // to have static set even though we are just modifying a static type
      if (modifier.equals("static")) {
         if (modifyTypeDecl != null && modifyTypeDecl.hasModifier(modifier))
            return true;

         // Enum constants must report the static modifier
         if (isEnumConstant())
            return true;
      }
      else if (!isLayerType && AccessLevel.getAccessLevel(modifier) != null && getInternalAccessLevel() == null) {
         Object modType = modifyTypeDecl != null ? modifyTypeDecl : modifyClass;
         if (modType != null) {
            if (ModelUtil.hasModifier(modType, modifier))
               return true;
         }
      }
      return super.hasModifier(modifier);
   }

   public boolean isStaticType() {
      if (modifyTypeDecl != null)
         return modifyTypeDecl.isStaticType();
      if (modifyClass != null)
         return ModelUtil.hasModifier(modifyClass, "static");
      return false;
   }

   public void addStaticFields(List<Object> fields) {
      if (modifyTypeDecl != null) {
         modifyTypeDecl.addStaticFields(fields);

         if (body != null) {
            for (Statement st:body) {
               if (st instanceof FieldDefinition) {
                  FieldDefinition fd = (FieldDefinition) st;
                  if (fd.hasModifier("static")) {
                     for (VariableDefinition vdef:fd.variableDefinitions) {
                        int ix = modifyTypeDecl.getDynStaticFieldIndex(vdef.variableName);
                        if (ix == -1)
                           fields.add(vdef);
                        else
                           fields.set(ix, vdef);
                     }
                  }
               }
               else if (st instanceof TypeDeclaration) {
                  TypeDeclaration td = (TypeDeclaration) st;
                  if (td.hasModifier("static")) {
                     if (ModelUtil.isObjectType(td) || td.isEnumConstant()) {
                        int ix = modifyTypeDecl.getDynStaticFieldIndex(td.typeName);
                        if (ix == -1)
                           fields.add(td);
                        else
                           fields.set(ix, td);
                     }
                  }
               }
            }
         }
      }
      else
         super.addStaticFields(fields);
   }

   public Object[] getStaticValues() {
      if (staticValues == null) {
         Object[] oldSvs;
         // Need to migrate old static properties to the new modified type just added on
         // then initialize only the new properties - i.e. those in this body.
         if (modifyTypeDecl != null && (oldSvs = modifyTypeDecl.staticValues) != null) {
            int newCt = getDynStaticFieldCount();
            if (newCt > oldSvs.length) {
               Object[] newSvs = new Object[newCt];
               System.arraycopy(oldSvs, 0, newSvs, 0, oldSvs.length);
               staticValues = newSvs;
            }
            else
               staticValues = modifyTypeDecl.staticValues;

            if (body != null) {
               ExecutionContext ctx = new ExecutionContext(getJavaModel());
               ctx.pushStaticFrame(this);
               try {
                  super.initStaticValues(staticValues, ctx);
               }
               finally {
                  ctx.popStaticFrame();
               }
            }
         }
         else
            return super.getStaticValues();
      }
      return staticValues;
   }

   public void preInitStaticValues(Object[] staticVals, ExecutionContext ctx) {
      if (modifyTypeDecl != null)
         modifyTypeDecl.preInitStaticValues(staticVals, ctx);

      super.preInitStaticValues(staticVals, ctx);
   }

   public void initStaticValues(Object[] staticVals, ExecutionContext ctx) {
      if (modifyTypeDecl != null)
         modifyTypeDecl.initStaticValues(staticVals, ctx);

      super.initStaticValues(staticVals, ctx);
   }

   public boolean useDefaultModifier() {
      return true;
   }

   private Object getModifyObj() {
      if (modifyTypeDecl == this) {
         System.err.println("*** Modify type for: " + typeName + " points to this!");
         return null;
      }
      if (enumConstant)
         return getEnclosingType();
      return modifyTypeDecl == null ? modifyClass : modifyTypeDecl.resolve(false);
   }

   public List<Object> getAllMethods(String modifier, boolean hasModifier, boolean isDyn, boolean overridesComp) {
      List declProps = super.getAllMethods(modifier, hasModifier, isDyn, overridesComp);
      List modProps;
      Object modifyObj = getModifyObj();
      if (modifyObj == null)
         modProps = declProps;
      else {
         Object[] props = ModelUtil.getAllMethods(modifyObj, modifier, hasModifier, isDyn, overridesComp);
         if (props != null)
            modProps = Arrays.asList(props);
         else
            modProps = null;
      }
      modProps = ModelUtil.mergeMethods(modProps, declProps);
      if (extendsBoundTypes != null) {
         for (Object extType:extendsBoundTypes) {
            Object[] props = ModelUtil.getAllMethods(extType, modifier, hasModifier, isDyn, overridesComp);
            if (props != null) {
               List extProps = Arrays.asList(props);
               modProps = ModelUtil.mergeMethods(extProps, modProps);
            }
         }
      }
      return modProps;
   }

   public List<Object> getDeclaredProperties(String modifier, boolean includeAssigns, boolean includeModified) {
      List<Object> declProps = super.getDeclaredProperties(modifier, includeAssigns, includeModified);
      if (isEnumeratedType()) {
         if (body != null) {
            for (int i = 0; i < body.size(); i++) {
               Definition member = body.get(i);
               if (ModelUtil.isEnum(member)) {
                  if (declProps == null)
                     declProps = new ArrayList<Object>();
                  declProps.add(member);
               }
            }
         }
      }
      if (includeModified) {
         List<Object> modProps;
         Object modifyObj = getModifyObj();
         if (modifyObj == null)
            modProps = declProps;
         else {
            Object[] props = ModelUtil.getProperties(modifyObj, modifier, includeAssigns);
            if (props != null)
               modProps = Arrays.asList(props);
            else
               modProps = null;
         }
         declProps = ModelUtil.mergeProperties(modProps, declProps, true, includeAssigns);
      }
      if (enumConstant && declProps != null) {
         // Need to remove the enum constants from the list before we return them.  We can't inherit those or it turns all recursive
         for (int i = 0; i < declProps.size(); i++) {
            if (ModelUtil.isEnum(declProps.get(i))) {
               declProps = new ArrayList<Object>(declProps); // Need a copy here in case this is a read-only list
               declProps.remove(i);
               i--;
            }
         }

      }
      return declProps;
   }

   public List<Object> getMethods(String methodName, String modifier, boolean includeExtends) {
      // If the modifying type has an extends type, it overrides the extends type in the base class
      List declProps = super.getMethods(methodName, modifier, true);
      List modProps;
      Object modifyObj = getModifyObj();
      if (modifyObj == null)
         modProps = declProps;
      else {
         Object[] props = ModelUtil.getMethods(modifyObj, methodName, modifier, includeExtends && (extendsBoundTypes == null || extendsBoundTypes.length == 0));
         if (props != null)
            modProps = Arrays.asList(props);
         else
            modProps = null;
      }
      modProps = ModelUtil.mergeMethods(modProps, declProps);
      if (extendsBoundTypes != null && includeExtends) {
         for (Object extType:extendsBoundTypes) {
            if (extType != null) {
               Object[] props = ModelUtil.getMethods(extType, methodName, modifier);
               if (props != null) {
                  List extProps = Arrays.asList(props);
                  modProps = ModelUtil.mergeMethods(extProps, modProps);
               }
            }
         }
      }
      return modProps;
   }

   public List<Object> getAllProperties(String modifier, boolean includeAssigns) {
      List declProps = super.getAllProperties(modifier, includeAssigns);
      List modProps;
      Object modifyObj = getModifyObj();
      if (modifyObj == null)
         modProps = declProps;
      else {
         Object[] props = ModelUtil.getProperties(modifyObj, modifier, includeAssigns);
         if (props != null)
            modProps = Arrays.asList(props);
         else
            modProps = null;
      }
      modProps = ModelUtil.mergeProperties(modProps, declProps, true, includeAssigns);
      if (extendsBoundTypes != null) {
         for (Object extType:extendsBoundTypes) {
            if (extType != null) {
               Object[] props = ModelUtil.getProperties(extType, modifier, includeAssigns);
               if (props != null) {
                  // Do not inherit the static properties from the extends type
                  List extProps = removeStaticProperties(props);
                  modProps = ModelUtil.mergeProperties(extProps, modProps, true, includeAssigns);
               }
            }
         }
      }
      return modProps;
   }

   public List<Object> getAllFields(String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified) {
      List declProps = super.getAllFields(modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);
      List modProps;
      Object modifyObj = getModifyObj();
      if (modifyObj == null)
         modProps = declProps;
      else {
         Object[] props = ModelUtil.getFields(modifyObj, modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);
         if (props != null)
            modProps = Arrays.asList(props);
         else
            modProps = null;
      }
      modProps = ModelUtil.mergeProperties(modProps, declProps, true);
      // For layer types - when we are computing the dynamic properties for this instance, do not include the extendsBoundTypes.  Those properties in sub-layers
      // are not considered fields in this layer since we have different layer instances.
      if (extendsBoundTypes != null && modProps != null && (!isLayerType || !dynamicOnly)) {
         // The case where there's a type that is modified with a new extends class that comes from a compiled layer, overriding a property originally defined in the dynamic layer.
         for (Object extType:extendsBoundTypes) {
            for (int i = 0; i < modProps.size(); i++) {
               Object modProp = modProps.get(i);
               if (ModelUtil.isCompiledProperty(extType, ModelUtil.getPropertyName(modProp), true, false)) {
                  if (!(modProps instanceof ArrayList))
                     modProps = new ArrayList<Object>(modProps);
                  modProps.remove(i);
                  i--;
               }
            }
         }
         for (Object extType:extendsBoundTypes) {
            if (!dynamicOnly || ModelUtil.isDynamicType(extType)) {
               Object[] props = ModelUtil.getFields(extType, modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);
               if (props != null) {
                  List extProps = Arrays.asList(props);
                  modProps = ModelUtil.mergeProperties(extProps, modProps, true);
               }
            }
         }
      }
      return modProps;
   }

   public boolean hasInnerObjects() {
      if (super.hasInnerObjects())
         return true;
      if (modifyTypeDecl != null && !modifyInherited)
         return modifyTypeDecl.hasInnerObjects();
      return false;
   }

   public List<Object> getAllInnerTypes(String modifier, boolean thisClassOnly) {
      List<Object> declProps = super.getAllInnerTypes(modifier, thisClassOnly);
      List<Object> modProps;
      if (!modifyInherited || !thisClassOnly) {
         Object modifyObj = getModifyObj();
         if (modifyObj == null)
            modProps = declProps;
         else {
            Object[] props = ModelUtil.getAllInnerTypes(modifyObj, modifier, thisClassOnly);
            if (props != null)
               modProps = Arrays.asList(props);
            else
               modProps = null;
         }
         modProps = ModelUtil.mergeInnerTypes(modProps, declProps);
      }
      else
         modProps = declProps;
      if (extendsBoundTypes != null && !thisClassOnly) {
         for (Object extType:extendsBoundTypes) {
            Object[] props = ModelUtil.getAllInnerTypes(extType, modifier, thisClassOnly);
            if (props != null) {
               List extProps = Arrays.asList(props);
               modProps = ModelUtil.mergeInnerTypes(extProps, modProps);
            }
         }
      }

      if (enumConstant && modProps != null) {
         boolean copied = false;
         // Need to remove the enum constants from the list before we return them.  We can't inherit those or it turns all recursive
         for (int i = 0; i < modProps.size(); i++) {
            if (ModelUtil.isEnum(modProps.get(i))) {
               if (!copied) {
                  modProps = new ArrayList<Object>(modProps);
                  copied = true;
               }
               modProps.remove(i);
               i--;
            }
         }
      }

      return modProps;

   }

   public Class getCompiledClass() {
      JavaModel model;
      if (!isStarted() && !isLayerType && (model = getJavaModel()) != null && !model.isLayerModel)
         ParseUtil.initAndStartComponent(model);

      if (!isLayerType && isEnumConstant() && isDynamicType()) {
         return DynEnumConstant.class;
      }

      if (modifyClass != null && modifyClass instanceof Class)
         return (Class) modifyClass;

      if (modifyTypeDecl != null) {
         // If we leave the physical type alone, we can inherit this but if we add to it, use the normal algorithm
         if (extendsTypes == null) {
            // When we are modifying a type inherited from a super type, we really are creating a new type under our
            // current parent type.
            if (modifyInherited) {
               return super.getCompiledClass();
            }
            else
               return modifyTypeDecl.getCompiledClass();
         }
         else
            return super.getCompiledClass();
      }
      else if (modifyClass instanceof CFClass) {
         return getLayeredSystem().getCompiledClass(getFullTypeName());
      }
      else if (enumConstant) {
         return getEnclosingType().getCompiledClass();
      }
      else
         return null;
   }

   public List<?> getClassTypeParameters() {
      if (typeParameters != null)
         return typeParameters;
      initTypeInfo();
      if (modifyTypeDecl == null) {
         if (modifyClass == null)
            return null;
         return ModelUtil.getTypeParameters(modifyClass);
      }
      return ModelUtil.getTypeParameters(modifyTypeDecl);
   }

   /*
    * The initDynInstance handles all of the initialization
    *
   public void initDynInstance(Object inst, ExecutionContext ctx, boolean popCurrentObj) {
      // TODO: should avoid double initialization here by overridden fields
      if (modifyTypeDecl != null)
         modifyTypeDecl.initDynInstance(inst, ctx, popCurrentObj);
      super.initDynInstance(inst, ctx, popCurrentObj);
   }
   */

   protected void initDynamicType() {
      super.initDynamicType();

      boolean clearedDyn = false;
      if (dynamicType || dynamicNew) {
         if (modifyTypeDecl != null) {
            Layer modLayer = modifyTypeDecl.getLayer();
            LayeredSystem sys = modLayer.layeredSystem;
            String compiledClass;
            // If we are modifying a non-dynamic class which has already been loaded as a compiled class
            if (!modifyTypeDecl.isDynamicType() && modLayer.compiledInClassPath && sys.isClassLoaded(compiledClass = getCompiledClassName())) {
               dynamicType = false;
               dynamicNew = false;
               if (modifyNeedsClass())
                  sys.setStaleCompiledModel(true, "Modify of type: ", compiledClass, " already loaded as compiled type and needs to add definitions");

               clearedDyn = true;
            }
         }
         // NOTE: modifyTypeDecl is null here when called from initialize so this fails.  It gets reset in "start"
         if (!modifyNeedsClass() && !clearedDyn) {
            dynamicType = false;
            dynamicNew = true;
         }
      }
   }

   public boolean modifyNeedsClass() {
      return super.modifyNeedsClass() || (!modifyInherited && modifyTypeDecl != null && modifyTypeDecl.modifyNeedsClass());
   }

   /*
    A modify type needs its own class if its body defines any fields/methods/types or if it implements any new types.  It used to do extends but this has to be consistent with the trasnform of
    * in ClassDeclaration.  That will not use its own class for a modify type.  I think the case here was related to dynamic types so maybe we can restrict this to a dynamic modify which extends
    * another type?
    */
   public boolean bodyNeedsClass() {
      return super.bodyNeedsClass()  ||/* (extendsTypes != null && extendsTypes.size() > 0) || */
             (implementsTypes != null && implementsTypes.size() > 0);
   }

   public String modifiersToString(boolean includeAnnotations, boolean includeAccess, boolean includeFinal, boolean includeScopes, boolean absolute, MemberType type) {
      // Build a temporary dummy definition to use to build the modifiers
      FieldDefinition def = new FieldDefinition();
      def.setProperty("modifiers", modifiers == null ? null : (SemanticNodeList<Object>) modifiers.deepCopy(CopyNormal, null));
      def.parentNode = this;
      for (Object modType = modifyTypeDecl; modType != null; ) {
         def.mergeModifiers(modType, false, true);
         if (modType instanceof ModifyDeclaration)
            modType = ((ModifyDeclaration) modType).modifyTypeDecl;
         else
            modType = null;
      }
      return def.modifiersToString(includeAnnotations, includeAccess, includeFinal, includeScopes, absolute, type);
   }

   public void updatePropertyForType(JavaSemanticNode overriddenAssign, ExecutionContext ctx, InitInstanceType iit, boolean updateInstances, UpdateInstanceInfo info) {
      // If this is a modify which really is like foo extends MySuperClass.foo {
      // it is like a new class.  We don't propagate types to the children.  Instead, we'll just treat this as
      // the base type.
      if (modifyInherited) {
         updatePropertyForTypeLeaf(overriddenAssign, ctx, iit, updateInstances, info);
         return;
      }

      // We propagate this through to the lowest level type first.
      // It will use the sub type registry to find
      // any instance of this type.  Since a new modify declaration can be added at any given time though,
      // we can't rely on all references having the most specific definition.
      if (modifyTypeDecl != null) {
         if (replacedByType != null && replaced)
            replacedByType.updatePropertyForType(overriddenAssign, ctx, iit, updateInstances, info);
         else
            modifyTypeDecl.updatePropertyForType(overriddenAssign, ctx, iit, updateInstances, info);
      }
      else
         super.updatePropertyForType(overriddenAssign, ctx, iit, updateInstances, info);
   }

   public void updateBlockStatement(BlockStatement bs, ExecutionContext ctx, UpdateInstanceInfo info) {
      if (modifyTypeDecl != null && !modifyInherited)
         modifyTypeDecl.updateBlockStatement(bs, ctx, info);
      else
         super.updateBlockStatement(bs, ctx, info);
   }

   public void updateBaseType(BodyTypeDeclaration newType) {
      // Not updating the modified type here since that model will soon be inactivated, it does not need the new fields
      super.updateBaseType(newType);
   }

   public boolean updateExtendsType(BodyTypeDeclaration extType, boolean modifyOnly, boolean extOnly) {
      // Need to clear this here because the extends types are in the dependent types
      dependentTypes = null;

      if (extendsTypes != null && !modifyOnly) {
         int i = 0;
         for (JavaType modExtType:extendsTypes) {
            Object modType = modExtType.getTypeDeclaration();
            if (modType != null && ModelUtil.sameTypes(modType, extType)) {
               modExtType.setTypeDeclaration(extType);
               extendsBoundTypes[i] = extType;
               return true;
            }
            i++;
         }
      }
      if (extType == this)
         System.err.println("*** Error: recursive modify");
      else if (!extOnly) {
         boolean sameTypes = ModelUtil.sameTypes(extType, this);
         if ((sameTypes && !modifyInherited) || (!sameTypes && modifyInherited)) {
            modifyTypeDecl = extType;
            checkModify();
            return true;
         }
      }

      if (modifyTypeDecl != null)
         return modifyTypeDecl.updateExtendsType(extType, modifyOnly, extOnly);
      return false;
   }

   public void addFieldToInstances(VariableDefinition varDef, ExecutionContext ctx) {
      //if (modifyTypeDecl == null)
         super.addFieldToInstances(varDef, ctx);
      //else {
         //addDynInstField(varDef);
         // We do want to update instances registered on those types but do not want to add this field to the
         // modified type.
      //   modifyTypeDecl.addFieldToInstances(varDef, ctx);
      //}
   }

   public void addDynInstField(Object fieldType, boolean updateType) {
      // First update our local type
      super.addDynInstField(fieldType, updateType);

      // Also update any types which extend from us or one of our base types.  In this case, we pass updateType=false
      // so we don't add the field to the lower-level.   We may have types which extend the modified type which need
      // to be updated though.
      //if (modifyTypeDecl != null && !modifyInherited)
      //   modifyTypeDecl.addDynInstField(fieldType, false);
   }

   public void addChildObjectToInstances(BodyTypeDeclaration innerType) {
      //if (modifyTypeDecl == null)
         super.addChildObjectToInstances(innerType);
      //else {
      //   modifyTypeDecl.addChildObjectToInstances(innerType);
      //}
   }

   public static ModifyDeclaration create(String layerDirName, JavaType extType) {
      ModifyDeclaration mod = new ModifyDeclaration();
      mod.typeName = layerDirName;
      if (extType != null) {
         mod.setProperty("extendsTypes", new SemanticNodeList(1));
         mod.extendsTypes.add(extType);
      }
      return mod;
   }

   public static ModifyDeclaration create(String layerDirName) {
      ModifyDeclaration mod = new ModifyDeclaration();
      mod.typeName = layerDirName;
      return mod;
   }

   public BodyTypeDeclaration getPureModifiedType() {
      initTypeInfo();
      if (replaced) {
         if (replacedByType != null)
            return replacedByType.getPureModifiedType();
      }
      if (modifyInherited)
         return null;
      return modifyTypeDecl;
   }

   public BodyTypeDeclaration getUnresolvedModifiedType() {
      initTypeInfo();
      return modifyTypeDecl;
   }

   public BodyTypeDeclaration getModifiedType() {
      initTypeInfo();
      if (replaced) {
         if (replacedByType != null)
            return replacedByType.getModifiedType();
      }
      return modifyTypeDecl;
   }

   public boolean getCompiledOnly() {
      return compiledOnly || (modifyTypeDecl != null && modifyTypeDecl.getCompiledOnly());
   }

   public void refreshBoundTypes(int flags) {
      if (isLayerType)
         return;
      super.refreshBoundTypes(flags);
      JavaModel m = getJavaModel();
      if (modifyTypeDecl != null && ((flags & ModelUtil.REFRESH_TYPEDEFS) != 0)) {
         BodyTypeDeclaration oldModify = modifyTypeDecl;
         if (modifyTypeDecl.getTransformed() || (flags & ModelUtil.REFRESH_TRANSFORMED_ONLY) == 0) {
            modifyTypeDecl = getModifyType();
            if (modifyTypeDecl == this) {
               System.err.println("*** Error: recursive modify in refresh");
               modifyTypeDecl = oldModify;
            }
            if (modifyTypeDecl == null)
               System.out.println("*** Error: unable to resolve modify type after flush cache for: " + typeName);
            else
               startExtendedType(modifyTypeDecl, "modified");
            checkModify();
         }
         else {
            modifyTypeDecl.refreshBoundTypes(flags);
         }
         if (needsSubType() && oldModify != modifyTypeDecl)
            m.layeredSystem.addSubType((TypeDeclaration) modifyTypeDecl, this);

      }
      if (ModelUtil.isCompiledClass(modifyClass) && ((flags & ModelUtil.REFRESH_CLASSES) != 0)) {
         modifyClass = ModelUtil.refreshBoundClass(getLayeredSystem(), modifyClass);
      }
      if (extendsTypes != null) {
         Object[] oldExtTypes = new Object[extendsTypes.size()];
         for (int i = 0; i < oldExtTypes.length; i++)
            oldExtTypes[i] = extendsTypes.get(i).getTypeDeclaration();
         // if extendsInvalidOrOverridden is true, we don't populate the bound types
         if (extendsBoundTypes != null) {
            for (int i = 0; i < extendsBoundTypes.length; i++) {
               Object extBoundType = extendsBoundTypes[i];
               if (((flags & ModelUtil.REFRESH_TYPEDEFS) != 0) &&
                   extBoundType instanceof BodyTypeDeclaration &&
                   (((BodyTypeDeclaration) extBoundType).getTransformed() || (flags & ModelUtil.REFRESH_TRANSFORMED_ONLY) == 0)) {
                  extendsBoundTypes = null;
                  initExtendsTypes();
                  break;
               }
               if (((flags & ModelUtil.REFRESH_CLASSES) != 0) && ModelUtil.isCompiledClass(extBoundType)) {
                  extendsBoundTypes = null;
                  initExtendsTypes();
                  break;
               }
            }
         }
         int ix = 0;
         for (JavaType t:extendsTypes) {
            t.refreshBoundType(flags);

            if (extendsBoundTypes != null) {
               extendsBoundTypes[ix] = t.getTypeDeclaration();
            }
            ix++;
         }

         // For any types which were replaced, need to update the sub-types again
         for (int i = 0; i < oldExtTypes.length; i++) {
            Object newExtType = extendsTypes.get(i).getTypeDeclaration();
            if (oldExtTypes[i] != newExtType && newExtType instanceof TypeDeclaration)
               m.layeredSystem.addSubType((TypeDeclaration) newExtType, this);
         }
      }
   }


   public boolean isHiddenType() {
      // compoundName may not be set yet
      return typeName != null && typeName.indexOf(".") != -1;
   }

   public BodyTypeDeclaration getHiddenRoot() {
      return hiddenByRoot;
   }

   public boolean isEnumeratedType() {
      if (modifyTypeDecl != null)
         return modifyTypeDecl.isEnumeratedType();

      return super.isEnumeratedType();
   }

   public boolean isEnumConstant() {
      initTypeInfo();
      return enumConstant;
   }

   public List<Expression> getEnumArguments() {
      if (modifyTypeDecl == null)
         return null;
      return modifyTypeDecl.getEnumArguments();
   }

   public void setCompiledOnly(boolean val) {
      super.setCompiledOnly(val);
      if (modifyTypeDecl != null && !modifyInherited)
         modifyTypeDecl.setCompiledOnly(val);
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      super.addDependentTypes(types, mode);
      if (modifyTypeDecl != null)
         modifyTypeDecl.addDependentTypes(types, mode);
      if (extendsTypes != null) {
         for (JavaType t:extendsTypes)
            t.addDependentTypes(types, mode);
      }
   }

   public String getCompiledClassName() {
      if (staleClassName != null)
         return staleClassName;
      if (isDynamicNew()) {

         // If used in a class value expression or the framework requires one concrete Class for each type
         // we always return the full type name as the compiled type.
         if (needsCompiledClass)
            return getFullTypeName();

         Object extendsType = getDerivedTypeDeclaration();
         if (!needsDynamicStub) {
            if (extendsType == null) {
               extendsType = getCompiledImplements();
            }
            if (extendsType == null) // A simple dynamic type - no concrete class required
               return getDefaultDynTypeClassName();
            if (ModelUtil.isDynamicType(extendsType) || ModelUtil.isDynamicNew(extendsType)) { // Extending a dynamic type - just use that guys class
               String extTypeName = ModelUtil.getCompiledClassName(extendsType);
               // Only look at the extends type defined for this modify expression - don't inherit them here
               Object otherExtType = extendsTypes != null && extendsTypes.size() > 0 ? getExtendsTypeDeclaration() : null;
               if (otherExtType != null && otherExtType != extendsType) {
                  String otherExtTypeName = ModelUtil.getCompiledClassName(otherExtType);
                  if (extTypeName.equals(getDefaultDynTypeClassName())) {
                     return otherExtTypeName;
                  }
                  else {
                     if (otherExtTypeName.equals(extTypeName))
                        return extTypeName;

                     Object compExtType = extendsType;
                     Object newExtType = getLayeredSystem().getTypeDeclaration(extTypeName);
                     if (newExtType != null)
                        compExtType = newExtType;

                     if (ModelUtil.isAssignableFrom(compExtType, otherExtType))
                        return otherExtTypeName;
                     if (ModelUtil.isAssignableFrom(otherExtType, compExtType))
                        return extTypeName;

                     // TODO: is this an error? conflicting compiled class names but maybe it's interfaces?
                     System.err.println("*** Dyn stub with conflicting base classes from modified and extends types");
                     needsDynamicStub = true;
                     propagateDynamicStub();
                  }
               }
               else
                  return extTypeName;
            }
         }
         if (getEnclosingType() != null)
            return getInnerStubFullTypeName();
         else
            return getFullTypeName();
      }
      else {
         JavaModel model = getJavaModel();
         // For a modify type in a sync definition, the compiledClassName is the modifyTypeDecl's name
         if (model == null || !model.mergeDeclaration) {
            if (modifyClass != null)
               return ModelUtil.getTypeName(modifyClass);
            else if (modifyTypeDecl != null)
               return modifyTypeDecl.getCompiledClassName();
         }
         return ModelUtil.getTypeName(getClassDeclarationForType());
      }
   }

   /*
   public String getCompiledClassName() {
      String superTypeName = super.getCompiledClassName();
      // If we decide we are a generic dynamic type but are really extending some modified type, we need to
      // check if it is a compiled type and use its compiled class name.
      if (superTypeName.equals(getDefaultDynTypeClassName()) && modifyInherited)
         return modifyTypeDecl.getCompiledClassName();
      return superTypeName;
   }
   */

   /**
    * For modify types, if we are modifying an inherited type, we really do create a new type and so treat the
    * modified class like the extends class (at least when analyzing the generated code)
    */
   public Object getCompiledExtendsTypeDeclaration() {
      if (modifyInherited)
         return modifyTypeDecl;

      return super.getExtendsTypeDeclaration();
   }

   public Object getModifiedExtendsTypeDeclaration() {
      if (modifyInherited) {
         return getModifiedType();
      }
      return null;
   }

   public boolean isCompiledProperty(String propName, boolean fieldMode, boolean interfaceMode) {
      if (super.isCompiledProperty(propName, fieldMode, interfaceMode))
         return true;
      if (modifyTypeDecl != null)
         return modifyTypeDecl.isCompiledProperty(propName, fieldMode, interfaceMode);
      if (modifyClass != null)
         return ModelUtil.isCompiledProperty(modifyClass, propName, fieldMode, interfaceMode);
      if (enumConstant)
         return ModelUtil.isCompiledProperty(getDerivedTypeDeclaration(), propName, fieldMode, interfaceMode);
      return false;
   }
   public List<Object> getCompiledIFields() {
      List<Object> res = null;

      if (getDeclarationType() == DeclarationType.INTERFACE && !isDynamicType()) {
         res = new ArrayList<Object>();
         addAllIFields(body, res, false, false, true);
         addAllIFields(hiddenBody, res, false, false, true);
      }

      List<Object> superRes = super.getCompiledIFields();
      if (superRes != null) {
         if (res == null)
            res = superRes;
         else {
            res = ModelUtil.mergeProperties(res, superRes, false);
         }
      }

      if (!modifyInherited && modifyTypeDecl != null) {
         List<Object> mres = modifyTypeDecl.getCompiledIFields();
         if (mres != null) {
            if (res == null)
               res = mres;
            else {
               res = ModelUtil.mergeProperties(res, mres, false);
            }
         }
      }

      return res;
   }

   public Object[] getCompiledImplTypes() {
      List<Object> res = null;
      // As long as we are modifying the same type, we inherit the interfaces of our parent class
      if (!modifyInherited && modifyTypeDecl != null) {
         Object[] modImplTypes = modifyTypeDecl.getCompiledImplTypes();

         if (modImplTypes != null)
            res = Arrays.asList(modImplTypes);
      }

      Object[] arrayRes = super.getCompiledImplTypes();
      if (res == null)
         return arrayRes;
      else if (arrayRes == null)
         return res.toArray(new Object[res.size()]);
      else {
         ArrayList<Object> combRes = new ArrayList<Object>();
         // TODO: remove any overlaps?
         combRes.addAll(res);
         combRes.addAll(Arrays.asList(arrayRes));
         return combRes.toArray(new Object[combRes.size()]);
      }
   }

   public Object[] getCompiledImplJavaTypes() {
      List<Object> res = null;
      // As long as we are modifying the same type, we inherit the interfaces of our parent class
      if (!modifyInherited && modifyTypeDecl != null) {
         Object[] modImplTypes = modifyTypeDecl.getCompiledImplJavaTypes();

         if (modImplTypes != null)
            res = Arrays.asList(modImplTypes);
      }

      Object[] arrayRes = super.getCompiledImplJavaTypes();
      if (res == null)
         return arrayRes;
      else if (arrayRes == null)
         return res.toArray(new Object[res.size()]);
      else {
         ArrayList<Object> combRes = new ArrayList<Object>();
         // TODO: remove any overlaps?
         combRes.addAll(res);
         combRes.addAll(Arrays.asList(arrayRes));
         return combRes.toArray(new Object[combRes.size()]);
      }
   }

   /** Returns true if this is a sub object where the parent/child relationship is compiled.  This means there's a compiled field and getX method we need to populate and we can't delete the relationship at runtime */
   public boolean isDynCompiledObject() {
      if (super.isDynCompiledObject())
         return true;

      if (modifyInherited && !ModelUtil.isDynamicType(getModifiedType())) {
         return true;
      }
      if (modifyTypeDecl != null)
         return modifyTypeDecl.isDynCompiledObject();

      return false;
   }

   public Object getCompiledImplements() {
      Object cimpl;
      JavaModel model = getJavaModel();
      if (model != null && model.mergeDeclaration && modifyTypeDecl instanceof TypeDeclaration) {
         cimpl = ((TypeDeclaration) modifyTypeDecl).getCompiledImplements();
         if (cimpl != null)
            return cimpl;
      }
      return super.getCompiledImplements();
   }

   public List<Statement> getInitStatements(InitStatementsMode mode, boolean isTransformed) {
      List<Statement> res = null;
      JavaModel model = getJavaModel();
      if (model != null && model.mergeDeclaration && modifyTypeDecl != null && !modifyInherited) {
         BodyTypeDeclaration modTD = modifyTypeDecl;
         if (isTransformed) {
            BodyTypeDeclaration xformType = modTD.getTransformedResult();
            if (xformType != modTD && xformType != null)
               return xformType.getInitStatements(mode, isTransformed);
         }
         res = modTD.getInitStatements(mode, isTransformed);
      }
      List<Statement> append = super.getInitStatements(mode, isTransformed);
      if (append != null) {
         if (res == null)
            res = append;
         else
            res.addAll(append);
      }
      return res;
   }

   public BodyTypeDeclaration getTransformedResult() {
      if (modifyTypeDecl == null)
         return this;
      if (modifyInherited) {
         return transformedType;
      }
      return modifyTypeDecl.getTransformedResult();
   }

   public boolean isAssignableFromClass(Class other) {
      // Specify case for annotation layers which modify the Object.class
      if (modifyClass == Object.class)
         return true;
      return super.isAssignableFromClass(other);
   }

   public void updateRuntimeType(ExecutionContext ctx, SyncManager.SyncContext syncCtx, Object outer) {
      // First look for the instance in the sync manager.  It gets registered for non rooted objects in addSyncInst
      // That naming thing should be symmetric on client and server.
      String typeName = getFullTypeName();
      Object inst = syncCtx.getObjectByName(typeName);

      if (inst == null) {
         inst = ScopeDefinition.resolveName(typeName, true, true);
      }
      if (inst == null) {
         System.err.println("*** No object of name: " + typeName + " found in the current context to apply change: " + modelType);
      }
      else {
         ctx.pushCurrentObject(inst);
         try {
            updateInstFromType(inst, ctx, syncCtx);
         }
         finally {
            ctx.popCurrentObject();
         }
      }
   }

   public ModifyDeclaration deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      ModifyDeclaration res = (ModifyDeclaration) super.deepCopy(options, oldNewMap);

      if ((options & CopyInitLevels) != 0) {
         res.modifyTypeDecl = modifyTypeDecl;
         res.modifyClass = modifyClass;
         res.extendsBoundTypes = extendsBoundTypes == null ? null : extendsBoundTypes.clone();
         res.typeInitialized = typeInitialized;
         res.inactiveType = inactiveType;
         res.modifyInherited = modifyInherited;

         res.compoundName = compoundName;
         res.enumConstant = enumConstant;
         res.hiddenByType = hiddenByType;
         res.hiddenByRoot = hiddenByRoot;

         res.impliedRoots = impliedRoots;
      }

      return res;
   }

   public ISrcStatement findFromStatement(ISrcStatement srcStatement) {
      ISrcStatement res = super.findFromStatement(srcStatement);
      if (res == null) {
         if (modifyTypeDecl != null)
            res = modifyTypeDecl.findFromStatement(srcStatement);
      }
      return res;
   }

   /** Complete the modified type */
   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation, int max) {
      if (currentType == null)
         return -1;
      String currentTypeName = ModelUtil.getTypeName(currentType);
      if (currentTypeName != null) {
         if (prefix != null) {
            int prefixLen = prefix.length();
            if (!currentTypeName.startsWith(prefix) || currentTypeName.length() == prefixLen || currentTypeName.charAt(prefixLen) != '.')
               return -1;
            String currentBase = currentTypeName.substring(prefix.length() + 1);
            if (currentBase.startsWith(command)) {
               candidates.add(currentBase);
               return 0;
            }
         }
      }
      return -1;
   }

   public List<String> getExtendsTypeNames() {
      List<String> baseLayerNames = null;
      if (extendsTypes != null) {
         baseLayerNames = new ArrayList<String>(extendsTypes.size());
         for (JavaType extType:extendsTypes)
            baseLayerNames.add(extType.getFullTypeName());
      }
      return baseLayerNames;
   }

   public String getOperatorString() {
      return "<modify>";
   }


   public void clearDynFields(Object inst, ExecutionContext ctx) {
      if (extendsTypes != null && !isLayerType) {
         // No dynamic fields so nothing to initialize
         if (!(inst instanceof IDynObject))
            return;
         // Need to process the extends types before we process the modify type - in the compiled version, this extends type's fields will be constructed before the base type's
         if (extendsBoundTypes != null) {
            for (int i = 0; i < extendsBoundTypes.length; i++) {
               Object ext = extendsBoundTypes[i];
               // Need to do even compiled interfaces in case there are any interface instance fields that were not compiled in
               if (ext instanceof BodyTypeDeclaration)
                  ((BodyTypeDeclaration) ext).clearDynFields(inst, ctx);
            }
         }
      }
      super.clearDynFields(inst, ctx);
   }

   public boolean isEmpty() {
      return body == null || body.size() == 0;
   }

   public boolean needsEnclosingClass() {
      if (modifiers != null)
         return true;
      if (extendsTypes != null || implementsTypes != null)
         return true;
      // Can we support nested a { b { c = 3 }} sequences without creating a class for 'a' and 'b'.   right now, testEditor2threeD fails with the modifyNeedsClass() call here that supports the nesting not creating a class.
      // during the transform, a binding involved in one of the omitted types cannot resolve it's type.   We probably would need to handle these in the transformation of the 'a' class
      // no tests currently rely on this but at one point it seemed like the right behavior for modify types created from the command line which are basically simple modifies.
      boolean res = true; // modifyNeedsClass();
      //if (!res)
      //   System.out.println("***");
      return res;
   }

   public String getTemplatePathName() {
      String ext = getJavaModel().getResultSuffix();
      if (ext != null)
         return super.getTemplatePathName();
      if (modifyTypeDecl != null)
         return modifyTypeDecl.getTemplatePathName();
      return null;
   }

   public boolean getNeedsDynInnerStub() {
      if (super.getNeedsDynInnerStub())
         return true;
      if (modifyTypeDecl != null && !modifyInherited)
         return modifyTypeDecl.getNeedsDynInnerStub();
      return false;
   }

   /** Returns the 'modify inherited type' in the chain of modifications.  If there isn't one, null is returned */
   public BodyTypeDeclaration getModifyInheritedType() {
      if (modifyInherited)
         return getModifiedType();
      if (modifyTypeDecl instanceof ModifyDeclaration) {
         return ((ModifyDeclaration) modifyTypeDecl).getModifyInheritedType();
      }
      return null;
   }
}
