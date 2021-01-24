/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.obj.ITemplateInit;

/** TODO: not used and untested.  A start at a base class for a compiled ObjectDefinition template.  Templates don't really
 * work well with lots of constructor parameters and yet the templates are hardcoded to expect an ObjectDefinitionParameters
 * as the paramclass or base class for the template.  So we extend that class and initialize ourselves from an instance.  See also JSTypeTemplateBase for
 * a working example of how this is implemented.  It only saved a little processing however so this is not spread widely. */
public abstract class ObjectDefinitionTemplate extends ObjectDefinitionParameters implements ITemplateInit {
   public void initFromObjDef(ObjectDefinitionParameters p) {
      this.objType = p.objType;
      this.compiledClass = p.compiledClass;
      this.accessClass = p.accessClass;
      this.constrModifiers = p.constrModifiers;
      this.typeName = p.typeName;
      this.useAltComponent = p.useAltComponent;

      this.variableTypeName = p.variableTypeName;
      this.childrenNames = p.childrenNames.toString();
      this.childObjNames = p.childObjNames.toString();
      this.numChildren = p.numChildren;
      this.overrideField = p.overrideField;
      this.overrideGet = p.overrideGet;
      this.needsMemberCast = p.needsMemberCast;
      this.typeIsComponent = p.typeIsComponent;
      this.typeIsComponentClass = p.typeIsComponentClass;
      // We use the existing "new X" for the dynamic case since that gets transformed..  Need to use the compiledClass here for the dyn type check as it is the thing which is getting new'd
      this.childTypeName = p.childTypeName;
      this.parentName = p.parentName;
      this.rootName = p.rootName;
      this.currentConstructor = p.currentConstructor;
      this.constructorDecls = p.constructorDecls;
      this.constructorParams = p.constructorParams;
      this.childNamesByScope = p.childNamesByScope;
      this.customNeedsField = p.customNeedsField;
      this.preAssignments = p.preAssignments;
      this.postAssignments = p.postAssignments;
      this.accessHooks = p.accessHooks;
      this.customResolver = p.customResolver;
      this.customSetter = p.customSetter;
   }

   abstract StringBuilder output();

   public void initTemplate(Object obj) {
      initFromObjDef((ObjectDefinitionParameters) obj);
   }
}
