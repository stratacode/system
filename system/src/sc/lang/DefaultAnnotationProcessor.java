/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.lang.java.Annotation;
import sc.lang.java.Definition;

/** Extend the class to get default implementations of the IAnnotationProcessor interface */
public class DefaultAnnotationProcessor extends DefinitionProcessor implements IAnnotationProcessor {
   // Not implemented
   //public void init(Definition def, Annotation annot) {
   //}

   public void start(Definition def, Annotation annot) {
      super.start(def);
   }

   public void process(Definition def, Annotation annot) {
      super.process(def);
   }

   public boolean transform(Definition def, Annotation annot, ILanguageModel.RuntimeType type) {
      return super.transform(def, type);
   }

   public boolean setOnField() {
      return true;
   }

   public boolean setOnGetMethod() {
      return false;
   }

   public boolean setOnSetMethod() {
      return false;
   }

   protected String toErrorString() {
      return "Annotation ";
   }
}
