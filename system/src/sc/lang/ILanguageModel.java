/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.lang.java.ImportDeclaration;
import sc.lang.java.TypeDeclaration;
import sc.layer.SrcEntry;
import sc.parser.IParseNode;
import sc.layer.IFileProcessorResult;
import sc.layer.Layer;
import sc.layer.LayeredSystem;

import java.util.List;
import java.util.Map;

/**
 * Top level model object returned by parsing a language file whose semantic value returns this type (e.g. JavaModel)
 */
public interface ILanguageModel extends IFileProcessorResult {

   enum RuntimeType { JAVA, STRATACODE}
   
   List<SrcEntry> getSrcFiles();

   SrcEntry getSrcFile();
   
   void setLayeredSystem(LayeredSystem system);
   LayeredSystem getLayeredSystem();
   
   /** Returns the layer which defined this particular model */
   Layer getLayer();

   void setLayer(Layer l);

   String getPackagePrefix();

   /** Returns the main type declaration for the model - the one with the same name as the file */
   TypeDeclaration getModelTypeDeclaration();

   /** Same as getModelTypeDeclaration but if you have a template where the root type is a compiled class this method returns the class */
   Object getModelType();

   /** Returns the type declaration for this model for this layer */
   TypeDeclaration getLayerTypeDeclaration();

   /** Returns the type declaration for this model for this layer */
   TypeDeclaration getUnresolvedModelTypeDeclaration();

   Map<String,TypeDeclaration> getDefinedTypes();

   List<ImportDeclaration> getImports();

   void setComputedPackagePrefix(String pref);

   void setDisableTypeErrors(boolean te);

   String getRelDirPath();

   IParseNode getParseNode();

   void saveModel();

   void setLastModifiedTime(long time);

   void setLastAccessTime(long time);

   long getLastAccessTime();

   ILanguageModel getModifiedModel();

   ILanguageModel resolveModel();

   boolean getPrependPackage();

   /**
    * Called when dependent files for this type have changed and this type needs to re-evaluate itself from it's original AST.  It might happen if
    * the subclass depends on the code in the base class or if the subclass failed to compile and we need to retry things again.
    */
   void reinitialize();

   void setAdded(boolean added);

   boolean isAdded();

   void markAsTemporary();

   Object getUserData();

   /** An optimization - returns true if the other is the exact same model,  Returns false if you are not sure. */
   boolean sameModel(ILanguageModel other);

   void refreshBoundTypes(int flags);

   void flushTypeCache();

   boolean isInitialized();

   boolean isStarted();

   /** Called when this model nees to be regenerated */
   void stop();

   boolean isUnsavedModel();

   EditorContext getEditorContext();

   boolean isReplacedModel();

   int getNodeCount();

   /**
    * Models can be parsed once and then serialized and de-serialized much faster.  When a model is deserialized, we can restore the parse nodes
    * using this operation.  When the model is cached, we can either cache the parse-node tree as well or not.   Either way, it's faster to restore
    * the parse-node tree with the existing model than to parse it again from scratch.
    */
   boolean restoreParseNode();
}
