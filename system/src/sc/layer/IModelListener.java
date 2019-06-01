/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.ILanguageModel;
import sc.lang.java.ITypeDeclaration;

public interface IModelListener {
   void modelAdded(ILanguageModel model);
   void layerAdded(Layer layer);
   void modelRemoved(ILanguageModel model);
   void layerRemoved(Layer layer);
   void innerTypeAdded(ITypeDeclaration type);
   void innerTypeRemoved(ITypeDeclaration type);
   void runtimeAdded(LayeredSystem sys);
   /* use DynUtil.addDynListener for these events
   void instanceAdded(ITypeDeclaration type, Object inst);
   void instanceRemoved(ITypeDeclaration type, Object inst);
   */
}
