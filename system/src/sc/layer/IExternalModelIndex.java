/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.ILanguageModel;
import sc.lang.java.ITypeDeclaration;

/**
 * Used by the LayeredSystem to synchronize with an IDE or external system that wants to keep its own database of type
 * information.  It gives StrataCode the ability to use this external cache and keep it sync with it's own cache of types
 * that ie needs to parse and load for resolving dependencies.  It also allows the IDE to supply it's own types via the
 * getTypeDeclaration method.
 */
public interface IExternalModelIndex {
   /** If you have your own cache of ILanguageModel objects, return your cached model.  If not, return null from this method and StrataCode will use it's own cache.
    * If the loadIfUnloaded flag is true, parse the model if it has not yet been parsed.  IF it's false, do not parse it - return null instead. */
   public ILanguageModel lookupJavaModel(SrcEntry srcEnt, boolean loadIfUnloaded);

   /** A notification hook when a given model has been replaced - i.e. someone changed the file on disk and it was refreshed. */
   public void replaceModel(ILanguageModel oldModel, ILanguageModel newModel);

   /**
    * Return false from this method if your external system notices a given model is invalid - i.e. has been refreshed.
    * You can also return false if the model given is not registered with your system and so needs to be reparsed.
    */
   public boolean isValidModel(ILanguageModel model);

   /** The IDE (or other external index) can let the layered system know whether a given model is being edited so SC can reclaim memory after building the type index. */
   public boolean isInUse(ILanguageModel model);

   /** Has this file been excluded from the IDE's project.  If so, this tells StrataCode to skip processing of this file even if it's a parseable extension. */
   public boolean isExcludedFile(String fileName);

   /** Called to notify the index that a language model file was changed.  */
   public void modelChanged(ILanguageModel model, boolean modelChanged, Layer layer);

   public ITypeDeclaration getTypeDeclaration(String typeName);

   /** Returns true for when the IDE aborts a type lookup - the layered system needs to treat these exceptions differently than those that occur from running dynamic code */
   public boolean isCancelledException(RuntimeException exc);
}
