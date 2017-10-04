/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.lang.java.Annotation;
import sc.lang.java.Definition;

/** Extend the class to get default implementations of the IAnnotationProcessor interface */
public class DefaultAnnotationProcessor extends DefinitionProcessor implements IAnnotationProcessor {
   public boolean setOnField = true;
   public boolean setOnGetMethod = false;
   public boolean setOnSetMethod = false;
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
      return setOnField;
   }

   public boolean setOnGetMethod() {
      return setOnGetMethod;
   }

   public boolean setOnSetMethod() {
      return setOnSetMethod;
   }

   protected String toErrorString() {
      return "Annotation ";
   }
}
