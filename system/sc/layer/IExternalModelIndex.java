/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.ILanguageModel;

/**
 * Used by the LayeredSystem to find inactive, external types as parsed by a 3rd party system like IntelliJ.
 */
public interface IExternalModelIndex {
   public ILanguageModel lookupJavaModel(SrcEntry srcEnt);

   public void replaceModel(ILanguageModel oldModel, ILanguageModel newModel);

   public boolean isValidModel(ILanguageModel model);

   /** The IDE can let the layered system know whether a given model is being edited so SC can reclaim memory after building the type index. */
   public boolean isInUse(ILanguageModel model);

   /** Has this file been excluded from the IDE's project */
   public boolean isExcludedFile(String fileName);

   public long getModelTimestamp(ILanguageModel model);

   /** Callback to notify the external index that an inactive layer was edited */
   public void layerChanged(Layer layer);
}
