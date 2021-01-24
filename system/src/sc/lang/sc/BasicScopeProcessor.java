/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sc;

import sc.lang.DefinitionProcessor;
import sc.lang.ILanguageModel;
import sc.lang.java.*;
import sc.util.LinkedIdentityHashSet;

import java.util.Set;

public class BasicScopeProcessor extends DefinitionProcessor implements IScopeProcessor {
   public String scopeName;

   // Marker annotation to add to the transformed type
   public boolean includeScopeAnnotation;

   public boolean needsSyncAccessHook;

   public BasicScopeProcessor(String scopeName) {
      this.scopeName = scopeName;
      validOnMethod = false; // Scopes don't belong on methods
   }

   public void init(Definition def) {
   }

   public void process(Definition def) {
      super.process(def);
   }

   public void applyScopeTemplate(TypeDeclaration td) {
      String scopeTemplateName;
      if (td.getDeclarationType() == DeclarationType.OBJECT)
         scopeTemplateName = getObjectTemplate();
      else
         scopeTemplateName = getNewTemplate();

      if (scopeTemplateName != null) {
         TransformUtil.applyTemplateToType(td, scopeTemplateName, "scopeTemplate", useNewTemplate);
      }
   }

   public void addDependentTypes(BodyTypeDeclaration td, Set<Object> types, JavaSemanticNode.DepTypeCtx mode) {
      if (dependentTypes != null && (mode.mode != JavaSemanticNode.DepTypeMode.SyncTypes &&
                                     mode.mode != JavaSemanticNode.DepTypeMode.ResetSyncTypes))
         types.addAll(dependentTypes);
      /*
        TODO: this does not work because we'd have to evaluate the template in order to get the dependencies for JS conversion.
        We need them in addTypeLibs which is run before the transform.  We could eval the template
        twice and if this gets used in a way that the dependencies are not just contained to framework packages, already included
        or rework addTypeLibs so it's run after the transform, at least before it's too late to add entry points and things that break now

      String scopeTemplateName;
      if (td.getDeclarationType() == DeclarationType.OBJECT)
         scopeTemplateName = getObjectTemplate();
      else
         scopeTemplateName = getNewTemplate();
      if (scopeTemplateName != null) {
         // If we are applying this template during the transform, any external types in the generated code will
         // become dependencies of the runtime.
         TransformUtil.addDependenciesForTemplate(td, types, scopeTemplateName, "scopeTemplate");
      }
      Template customSetterTemplate = getCustomSetterTemplate(td.getLayeredSystem());
      if (customSetterTemplate != null)
         customSetterTemplate.addDependentTypes(types);
      Template customResolverTemplate = getCustomResolverTemplate(td.getLayeredSystem());
      if (customResolverTemplate != null)
         customResolverTemplate.addDependentTypes(types);
      */
   }

   public boolean transform(Definition def, ILanguageModel.RuntimeType type) {
      // Tag the definition with the annotation specified.
      if (includeScopeAnnotation) {
         def.removeAnnotation("sc.obj.Scope");
         def.addModifier(Annotation.create("sc.obj.Scope", "name", scopeName));
      }
      return false;
   }

   protected String toErrorString() {
      return "Scope " + scopeName;
   }

   private boolean useNewTemplate = false;

   public void setUseNewTemplate(boolean ut) {
      useNewTemplate = ut;
   }
   public boolean getUseNewTemplate() {
      return useNewTemplate;
   }

   private String childGroupName;
   public String getChildGroupName() {
      return childGroupName;
   }

   public void setChildGroupName(String cg) {
      childGroupName = cg;
   }

   private String objectTemplate;

   public void setObjectTemplate(String ot) {
      objectTemplate = ot;
   }
   public String getObjectTemplate() {
      return objectTemplate;
   }

   private String newTemplate;

   public void setNewTemplate(String nt) {
      newTemplate = nt;
   }
   public String getNewTemplate() {
      return newTemplate;
   }

   private String contextParams;
   public void setContextParams(String cp) {
      contextParams = cp;
   }
   public String getContextParams() {
      return contextParams;
   }

   private boolean temporaryScope = false;
   public void setTemporaryScope(boolean cp) {
      temporaryScope = cp;
   }
   public boolean isTemporaryScope() {
      return temporaryScope;
   }

   public void setProcessorName(String name) {
      scopeName = name;
   }
   public String getProcessorName() {
      return scopeName;
   }

   public void addDependentType(Object depType) {
      if (dependentTypes == null)
         dependentTypes = new LinkedIdentityHashSet<>();
      dependentTypes.add(depType);
   }

   public boolean definesTypeField = false;

   /**
    * TODO: is this right?  It seems like ListItem scope might be the only one which uses a field to store the obj reference
    * and all of the others use customResolver.
    */
   public boolean getDefinesTypeField() {
      return customResolver == null;
   }

   /** For scopes that need a call made to accessSyncInst for contexts which use this component.
    * This includes any scope that has child contexts right now... maybe this should be computed rather than specified? */
   public void setNeedsSyncAccessHook(boolean needsSyncAccessHook) {
      this.needsSyncAccessHook = needsSyncAccessHook;
   }
   public boolean getNeedsSyncAccessHook() {
      return needsSyncAccessHook;
   }
}
