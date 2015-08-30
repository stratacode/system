package sc.lang;

import sc.layer.LayeredSystem;
import sc.layer.BuildInfo;
import sc.lang.DefaultAnnotationProcessor;
import sc.lang.ILanguageModel;
import sc.lang.java.Definition;
import sc.lang.java.MethodDefinition;
import sc.lang.java.TypeDeclaration;
import sc.lang.java.Annotation;
import sc.lang.java.ModelUtil;

import java.util.Iterator;

public class TestAnnotationProcessor extends DefaultAnnotationProcessor {
   String annotationName;
   String testProcessorName;
   public TestAnnotationProcessor(String annotName, String procName) {
      this.annotationName = annotName;
      this.testProcessorName = procName;
   }
   public void process(Definition def, Annotation annot) {
      if (!(def instanceof MethodDefinition) || !(ModelUtil.hasModifier(def, "public") || ModelUtil.hasModifier(def, "static"))) {
         def.displayError(annotationName, " annotation should only be attached to public instance methods: ");
      }
      else {
         LayeredSystem sys = def.getLayeredSystem();
         BuildInfo bi = sys.buildInfo;
         TypeDeclaration td = def.getEnclosingType();
         addTestType(sys, bi, td);
      }
   }

   private void addTestType(LayeredSystem sys, BuildInfo bi, TypeDeclaration td) {
      if (!ModelUtil.isAbstractType(td)) {
         String typeName = ModelUtil.getTypeName(td);

         if (bi.getTestInstance(typeName) == null)
            bi.addTestInstance(new BuildInfo.TestInstance(typeName, testProcessorName));
      }
      Iterator<TypeDeclaration> subTypes = sys.getSubTypesOfType(td);
      if (subTypes != null) {
          while (subTypes.hasNext()) {
              TypeDeclaration next = subTypes.next();
              addTestType(sys, bi, next);
          }
      }
   }
}