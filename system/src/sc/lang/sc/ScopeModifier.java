/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sc;

import sc.lang.java.ErrorSemanticNode;
import sc.lang.java.JavaModel;
import sc.lang.java.JavaSemanticNode;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.obj.ScopeDefinition;

/** Used in the language model as the element for the scope<scopeName> modifier */
public class ScopeModifier extends ErrorSemanticNode {
   public String scopeName;

   /**
    * Print an error if the scopeName is not valid.  Putting in validate just out of caution to be sure all scopes are defined.
    */
   public void validate() {
      if (validated)
         return;
      super.validate();

      JavaModel model = getJavaModel();
      if (model == null || scopeName == null)
         return;
      // The layers have been compressed so the search for the scope processor does not work in the transformed model.
      // TODO: should we cache the ScopeDefinition for this type during process so we have it during transform?
      if (model.isTheTransformedModel())
         return;
      // Valid scopes may either be registerScopeProcessors (e.g. session) or defined statically and shared by the runtime and compilation environments (e.g. global and appGlobal)
      if (!isValidScope(model, scopeName)) {
         displayTypeError("No scope: " + scopeName + " for: ");
      }
   }

   public static boolean isValidScope(JavaModel model, String scopeName) {
      LayeredSystem sys = model.getLayeredSystem();
      if (sys != null && sys.getScopeProcessor(model.getLayer(), scopeName) == null && ScopeDefinition.getScopeByName(scopeName) == null && sys.getScopeAlias(model.getLayer(), scopeName) == null) {
         return false;
      }
      return true;
   }
}
