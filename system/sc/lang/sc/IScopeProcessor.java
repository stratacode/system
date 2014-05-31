/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sc;

import sc.lang.IDefinitionProcessor;
import sc.lang.ILanguageModel;
import sc.lang.java.Definition;
import sc.lang.java.TypeDeclaration;

public interface IScopeProcessor extends IDefinitionProcessor {
   public void init(Definition def);

   public void start(Definition def);

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

   /** If non-null, requires that this scope be set on objects/classes/fields enclosed inside of the named parent type */
   public String getRequiredParentType();

   public void applyScopeTemplate(TypeDeclaration td);
}
