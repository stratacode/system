/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sc;

import sc.lang.DefinitionProcessor;
import sc.lang.ILanguageModel;
import sc.lang.java.*;

public class BasicScopeProcessor extends DefinitionProcessor implements IScopeProcessor {

   public String scopeName;

   // Marker annotation to add to the transformed type
   public boolean includeScopeAnnotation;

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

}
