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

   public long getModelTimestamp(ILanguageModel model);
}
