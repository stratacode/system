/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.lang.sc.PropertyAssignment;
import sc.lang.template.Template;
import sc.layer.BuildInfo;
import sc.lang.java.*;
import sc.layer.LayeredSystem;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;

/** The base class for both annotation processors and scope processors.  Framework components register these classes in the layer definition file to manage the behavior of an annotation like @Sync
 * which has compile time behavior.
 */
public abstract class DefinitionProcessor implements IDefinitionProcessor {
   /**
    * Allows you to group the types attached to this definition into a single global list.
    * If this is set on an annotation processor associated with a method
    * Field, etc. the enclosing type is added to the group.   From the type, you can find the list of methods or fields
    * or whatever in the type inside of your template.
    */
   public String typeGroupName;

   /** If set to true, and definition is an object, do not generate the static getX method.  Treat it as a class for code-gen purposes */

   public boolean validOnObject = true;
   public boolean validOnClass = true;
   public boolean validOnField = true;
   public boolean validOnMethod = true;
   public boolean validOnDynamicType = true;

   /**  If true, the getX method for any objects of this type are called at app init time */
   public boolean createOnStartup = false;

   /**  If true, the type is initialized when the app is created.  The static values are evaluated - i.e. replacing Java's lazy initializion with something more proactive */
   public boolean initOnStartup = false;

   /** When set to true, forces the corresponding type to be compiled with type info even if in a dynamic layer (e.g. JPA entity classes which need real properties) */
   public boolean compiledOnly = false;

   /** When using dynamic types, some annotations require a concrete Java stub class for each dynamic class */
   public boolean needsCompiledClass = false;

   /** If true, type groups include sub-types which have the annotation set */
   public boolean inherited = false;

   /** If true, do not include abstract classes in the type group */
   public boolean skipAbstract = false;

   /** If true, only include sub-types which have this annotation.  Should only be used with inherited=true */
   public boolean subTypesOnly = false;

   /** Set to a template string evaluated using the object definition parameters used in place of the field for storing the type. */
   public String customResolver;

   /** Set by customResolver or set this directly if you want to use a template from the file system */
   public Template customResolverTemplate;

   /** Set to a template string evaluated using the object definition parameters used to set the field or store the object in a context object */
   public String customSetter;

   /** Corresponds to customSetter - the template actually used */
   public Template customSetterTemplate;

   /** Override this on an object type which does not need a field to store the value (since it is being looked up in the customGetter/Setter typically) */
   public boolean needsField = true;

   /** Set to a template string evaluated using the object definition parameters placed after the instance has been created but before any post-constructor assignments have been run (i.e. before preInit for components, right after new X for classes) */
   public String preAssignment;

   /** Corresponds to the string version - the template actually used */
   // TODO: cache this here and also allow it to be set as a template for when the string form is awkward?
   //public Template preAssignmentTemplate;

   /** Set to a template string evaluated using the object definition parameters added after the preInit method for components or assignments not compiled in for classes. */
   public String postAssignment;

   /** Corresponds to the string version - the template actually used */
   //public Template postAssignmentTemplate;

   /** Set to a template string evaluated using the object definition parameters before returning an existing instance */
   public String accessHook;

   /** Equivalent to CompilerSettings.mixinTemplate but set via an annotation */
   public String mixinTemplate;

   /** Equivalent to CompilerSettings.staticMixinTemplate but set via an annotation */
   public String staticMixinTemplate;

   /** Equivalent to CompilerSettings.defineTypesMixinTemplate but set via an annotation */
   public String defineTypesMixinTemplate;

   /** For JS transformation, any types that are injected through code generation we need for dependency purposes before the transformation step in which we discover these in the template */
   public Set<Object> dependentTypes;

   // TODO : this never gets called cause annot.getFullTypeName() is returning null since boundType for annotation = null.
   public void init(Definition def) {
   }

   public void start(Definition def) {
      if (typeGroupName != null) {
         if (!(def instanceof TypeDeclaration)) {
            if (!(def instanceof TypedDefinition) && !(def instanceof PropertyAssignment))
                def.displayError(toErrorString() + " only allowed on class/object, field, method for: ");
         }
         else
            typeGroupMemberStarted((TypeDeclaration) def);
         // We wait to add the type group member till "process".  This let's us cull members for any types we know we'll process.
      }
      if (!validOnDynamicType && def.isDynamicType()) {
         def.displayError(toErrorString() + " invalid on dynamic definition: ");
      }
      else if (!validOnMethod && def instanceof AbstractMethodDefinition)
         def.displayError(toErrorString() + " invalid on method definition: ");
      else {
         if (!validOnField && (def instanceof FieldDefinition || def instanceof PropertyAssignment))
            def.displayError(toErrorString() + " invalid on field definition: ");
         if (def instanceof TypeDeclaration) {
            TypeDeclaration decl = (TypeDeclaration) def;
            DeclarationType type = decl.getDeclarationType();
            if (!validOnClass && type == DeclarationType.CLASS)
               def.displayError(toErrorString() + " invalid on class definition: ");
            if (type == DeclarationType.OBJECT) {
               if (!validOnObject)
                  def.displayError(toErrorString() + " invalid on object definition: ");
            }
         }
      }
      if (requiredType != null) {
         Object reqParType = def.getJavaModel().findTypeDeclaration(requiredType, false);
         if (reqParType == null)
            def.displayError("Invalid requiredType for " + toErrorString() + " " + requiredType);
         else if (!ModelUtil.isAssignableFrom(reqParType, def))
            def.displayError(toErrorString() + " must be set on " + requiredType + " for ");
      }

      if (requiredParentType != null) {
         Object reqParType = def.getJavaModel().findTypeDeclaration(requiredParentType, false);
         if (reqParType == null)
            def.displayError("Invalid requiredParentType for " + toErrorString() + " " + requiredParentType);
         else {
            TypeDeclaration t = def.getEnclosingType();
            if (t == null || !ModelUtil.isAssignableFrom(reqParType, t))
               def.displayError(toErrorString() + " must be set on an inner class of an " + requiredParentType + " for ");
         }
      }

      if (compiledOnly && def instanceof BodyTypeDeclaration) {
         ((BodyTypeDeclaration) def).setCompiledOnly(true);
      }
      if (needsCompiledClass && def instanceof BodyTypeDeclaration) {
         ((BodyTypeDeclaration) def).enableNeedsCompiledClass();
      }

   }

   public void validate(Definition def) {
      // This check needs to be done after we've definitely started 'def'.  We might be in the midst of starting it when we run the 'start' method
      if (createOnStartup || initOnStartup)
         checkForPublicAccess((BodyTypeDeclaration) def);
   }

   protected void typeGroupMemberStarted(TypeDeclaration td) {
   }

   /** Need to do this in process - after we've cleaned the stale entries */
   public void process(Definition def) {
      if (def instanceof TypeDeclaration) {
         LayeredSystem sys = def.getLayeredSystem();
         BuildInfo bi = sys.buildInfo;
         if (bi == null || (skipAbstract && ModelUtil.isAbstractType(def)))
            return;
         TypeDeclaration td = (TypeDeclaration) def;
         if (typeGroupName != null) {
            bi.addTypeGroupMember(ModelUtil.getTypeName(def), td.getTemplatePathName(),  typeGroupName);
         }
         if (initOnStartup) {
            bi.addTypeGroupMember(ModelUtil.getTypeName(def), td.getTemplatePathName(), BuildInfo.InitGroupName);
         }
         if (createOnStartup) {
            bi.addTypeGroupMember(ModelUtil.getTypeName(def), td.getTemplatePathName(), BuildInfo.StartupGroupName);
         }
      }
      // For methods, fields, etc. we will add the enclosing type as the type group member.  We can then use the type
      // to collect the methods or whatever that have this annotation.
      else if (def instanceof TypedDefinition && typeGroupName != null) {
         LayeredSystem sys = def.getLayeredSystem();
         BuildInfo bi = sys.buildInfo;
         if (bi == null)
            return;
         TypeDeclaration enclType = def.getEnclosingType();
         bi.addTypeGroupMember(enclType.getFullTypeName(), enclType.getTemplatePathName(), typeGroupName);
      }
   }

   private void checkForPublicAccess(BodyTypeDeclaration td) {
      if (!td.hasModifier("public")) {
         td.displayError(toErrorString() + " must be public for: ");
         boolean res = td.hasModifier("public");
      }
   }

   public boolean transform(Definition def, ILanguageModel.RuntimeType type) {
      return false;
   }


   protected abstract String toErrorString();

   public String requiredParentType;
   public String requiredType;

   /** If non-null, requires that this definition be set on objects/classes/fields enclosed inside of the named parent type */
   public String getRequiredParentType() {
      return requiredParentType;
   }

   private String[] appendInterfaces;
   public void setAppendInterfaces(String[] ifs) {
      appendInterfaces = ifs;
   }
   public String[] getAppendInterfaces() {
      return appendInterfaces;
   }

   public Object definesMember(TypeDeclaration srcType, String name, EnumSet<JavaSemanticNode.MemberType> mtype, TypeContext ctx) {
      return null;
   }

   public boolean getInherited() {
      return inherited;
   }

   public boolean getSubTypesOnly() {
      return subTypesOnly;
   }

   public String getCustomResolver() {
      return customResolver;
   }

   public Template getCustomResolverTemplate(LayeredSystem sys) {
      if (customResolverTemplate != null)
         return customResolverTemplate;
      if (customResolver != null) {
         customResolverTemplate = TransformUtil.parseTemplate(customResolver, ObjectDefinitionParameters.class, true, false, null, sys);
      }
      return customResolverTemplate;
   }

   public String getCustomSetter() {
      return customSetter;
   }

   public Template getCustomSetterTemplate(LayeredSystem sys) {
      if (customSetterTemplate != null)
         return customSetterTemplate;
      if (customSetter != null) {
         customSetterTemplate = TransformUtil.parseTemplate(customSetter, ObjectDefinitionParameters.class, true, false, null, sys);
      }
      return customSetterTemplate;
   }

   public String getPreAssignment() {
      return preAssignment;
   }

   public String getPostAssignment() {
      return postAssignment;
   }

   public String getAccessHook() {
      return accessHook;
   }

   public String getMixinTemplate() {
      return mixinTemplate;
   }

   public String getStaticMixinTemplate() {
      return staticMixinTemplate;
   }

   public String getDefineTypesMixinTemplate() {
      return defineTypesMixinTemplate;
   }

   public boolean getNeedsField() {
      return needsField;
   }

}
