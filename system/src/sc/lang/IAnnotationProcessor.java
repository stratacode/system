/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.lang.java.Annotation;
import sc.lang.java.Definition;

public interface IAnnotationProcessor extends IDefinitionProcessor {
   // Not used and too hard to implement
   //public void init(Definition def, Annotation annot);

   public void start(Definition def, Annotation annot);

   public void process(Definition def, Annotation annot);

   public boolean transform(Definition def, Annotation annot, ILanguageModel.RuntimeType type);

   /**
    * When you have a field annotation, these methods control how that annotation is processed
    * during the get/set conversion.
    */
   public boolean setOnField();
   public boolean setOnGetMethod();
   public boolean setOnSetMethod();

   public boolean getInherited();

   public boolean getSubTypesOnly();

}
