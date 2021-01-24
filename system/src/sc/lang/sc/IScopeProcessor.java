/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sc;

import sc.lang.IDefinitionProcessor;
import sc.lang.ILanguageModel;
import sc.lang.java.BodyTypeDeclaration;
import sc.lang.java.Definition;
import sc.lang.java.JavaSemanticNode;
import sc.lang.java.TypeDeclaration;

import java.util.Set;

public interface IScopeProcessor extends IDefinitionProcessor {
   public void init(Definition def);

   public void start(Definition def);

   public void validate(Definition def);

   public void process(Definition def);

   public boolean transform(Definition def, ILanguageModel.RuntimeType type);

   /** Forces "object" defs with this scope to use the new template. */
   public boolean getUseNewTemplate();

   /** If non-null, specifies the name of the list where children defs should be stored */
   public String getChildGroupName();

   /** Name type name for a template to apply when this scope is applied to an object */
   public String getObjectTemplate();

   /** The comma separated list of parameters passed to the constructor for this scope */
   public String getContextParams();

   /** Name type name for a template to apply when this scope is applied to a class */
   public String getNewTemplate();

   /** True if this scope is not a lasting one - i.e. to turn off synchronization for objects in that scope */
   public boolean isTemporaryScope();

   /** If non-null, requires that this scope be set on objects/classes/fields enclosed inside of the named parent type */
   public String getRequiredParentType();

   public void applyScopeTemplate(TypeDeclaration td);

   /**
    * For Javascript generation, we need to know the dependencies of the class before the transform which injects those dependencies.  e.g.
    * For scope="request" this will add RequestScopeDefinition and ScopeContext because those are found in the template applied
    * during transformation. TODO: maybe the need for this method could be avoided by running addTypeLibs after we've transformed from SC to Java? Right now, we need to know the dependencies (especially entry points) during the addTypeLibs which is called from 'start'
    */
   public void addDependentTypes(BodyTypeDeclaration td, Set<Object> types, JavaSemanticNode.DepTypeCtx mode);

   /** Return true for scopes which use a field with the name of the object name.  This is used to avoid creating the scope object if it
    * does not exist.  TODO: we should replace this with a way to call the getValue(create=false) variant on ScopeDefinition.  Requires
    * some rework though to how we generate the create=false case of the generated getObjChildren (see childrenFieldNames in ObjectDefinitionParameters)
    */
   public boolean getDefinesTypeField();

   public boolean getNeedsSyncAccessHook();
}
