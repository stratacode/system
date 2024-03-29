/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.classfile.CFAnnotation;
import sc.lang.JavaLanguage;
import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.layer.AnnotationMergeMode;
import sc.layer.LayeredSystem;
import sc.parser.PString;
import sc.parser.ParseUtil;
import sc.type.CTypeUtil;

import java.lang.reflect.Array;
import java.util.*;

public class Annotation extends ErrorSemanticNode implements IAnnotation {
   public String typeName;
   // either List<elementValue> or elementValue
   public Object elementValue;

   public transient Object boundType;

   public static Annotation create(String typeName) {
      Annotation annot = new Annotation();
      annot.typeName = typeName;
      return annot;
   }

   /** Create an annotation with one complex value  */
   public static Annotation create(String typeName, String valName, Object valValue) {
      Annotation annot = create(typeName);

      AnnotationValue av = new AnnotationValue();
      av.identifier = valName;
      av.elementValue = AbstractLiteral.createFromValue(valValue, true);

      annot.addAnnotationValues(av);

      return annot;
   }

   /** Create an annotation with a single value  */
   public static Annotation createSingleValue(String typeName, Object valValue) {
      Annotation annot = create(typeName);
      annot.setProperty("elementValue", AbstractLiteral.createFromValue(valValue, true));

      return annot;
   }

   public void addAnnotationValues(AnnotationValue... elemValues) {
      SemanticNodeList<AnnotationValue> newValues = new SemanticNodeList<AnnotationValue>();
      for (AnnotationValue val:elemValues)
         newValues.add(val);
      setProperty("elementValue", newValues);
   }

   public void start() {
      if (started) return;
      try {
         super.start();

         if (typeName == null)
            return;

         boundType = findType(typeName);
         JavaModel model = getJavaModel();
         if (boundType == null && model != null) // Global type
            boundType = model.findTypeDeclaration(typeName, true);
         if (boundType == null) {
            displayTypeError("No annotation: ", typeName, " ");
            if (model != null)
               boundType = model.findTypeDeclaration(typeName, true);
         }
         else {
            if (elementValue instanceof SemanticNodeList) {
               SemanticNodeList<Object> elemValList = (SemanticNodeList<Object>) elementValue;
               for (Object avObj:elemValList) {
                  if (avObj instanceof AnnotationValue) {
                     AnnotationValue av = (AnnotationValue) avObj;
                     Object annotValType = getAnnotationValueType(av.identifier);
                     if (annotValType == null) {
                        av.displayTypeError("No annotation method: " + av.identifier + " for type: " + typeName + " for: ");
                     }
                  }
               }
            }
         }
      }
      catch (RuntimeException exc) {
         clearStarted();
         throw exc;
      }

      /* Debug code to test that we can retrieve annotation values from any annotation
      if (elementValue instanceof List) {
         List ev = (List) elementValue;
         for (Object eva :ev) {
            Object res = getAnnotationValue(((AnnotationValue) eva).identifier);
            if (res instanceof Object[]) {


            }
         }
      }
      */
   }

   /**
    * As we are inheriting definitions we may find both src/dst types have the same annotation.
    * This method merges the definitions.  When we are merging up to a more specific layer, we only
    * copy definitions not found in the destination layer (replace=false).  When we are merging from
    * a more specific to a more general definition (the modify declaration), we replace conflicting
    * definitions in the dest.
    */
   public boolean mergeAnnotation(Object overriddenAnnotation, boolean replace) {
      boolean any = false;

      // Three types of annotations:
      // Marker: no value - nothing to merge
      // SingleElementAnnotation - if replace, we replace the value otherwise do nothing
      // InstInit Annotation - merge the name/values replacing values if replace is true

      if (ModelUtil.isComplexAnnotation(overriddenAnnotation)) {
         if (!(elementValue instanceof List))
            return false;

         List overriddenValues = ModelUtil.getAnnotationComplexValues(overriddenAnnotation);
         List thisElementValues = (List) elementValue;
         for (int i = 0; i < overriddenValues.size(); i++) {
            AnnotationValue av = (AnnotationValue) overriddenValues.get(i);
            AnnotationValue thisAv;
            AnnotationMergeMode mergeMode = LayeredSystem.getAnnotationMergeMode(typeName, av.identifier);
            if ((thisAv = getAnnotationValueWrapper(av.identifier)) == null) {
               thisElementValues.add(av);
               any = true;
            }
            else {
               switch (mergeMode) {
                  case Replace:
                     if (replace) {
                        thisElementValues.remove(thisAv);
                        thisElementValues.add(av);
                     }
                     // else - leave the overriding one in place
                     break;
                  case AppendCommaString:
                     Object thisVal = thisAv.elementValue;
                     Object otherVal = av.elementValue;
                     if (thisVal instanceof StringLiteral && otherVal instanceof StringLiteral) {
                        StringLiteral thisLiteral = (StringLiteral)thisVal;
                        String thisStr = (String) thisLiteral.getLiteralValue();
                        String otherStr = (String) ((StringLiteral)otherVal).getLiteralValue();
                        String merged;
                        if (thisStr.length() == 0)
                           merged = otherStr;
                        else if (otherStr.length() == 0)
                           merged = thisStr;
                        else
                           merged = thisStr + "," + otherStr;
                        thisLiteral.setProperty("value", merged);
                        thisLiteral.stringValue = merged;
                        any = true;
                     }
                     else
                        displayError("Expected string value for annotation: " + av.identifier);
                     break;
                  default:
                     System.err.println("*** Unrecognized merge mode");
                     break;
               }
            }
         }
      }
      else if (replace) {
         Object singleValue = ModelUtil.getAnnotationSingleValue(overriddenAnnotation);
         if (singleValue != null)
            setProperty("elementValue", singleValue);
      }
      return any;
   }

   public AnnotationValue getAnnotationValueWrapper(String identifier) {
      if (elementValue instanceof List) {
         List values = (List) elementValue;
         for (int i = 0; i < values.size(); i++) {
            AnnotationValue av = (AnnotationValue) values.get(i);
            if (av.identifier.equals(identifier))
               return av;
         }
      }
      else if (identifier.equals("value")) {
         return AnnotationValue.createFromAnnotValue("value", elementValue);
      }
      return null;
   }

   public Object getAnnotationValueType(String propName) {
      if (boundType == null)
         return null;

      Object[] methods = ModelUtil.getMethods(boundType, propName, null);
      if (methods == null)
         return null;
      return ModelUtil.getReturnType(methods[0], true);
   }

   public Object getAnnotationElementValue(String identifier) {
      AnnotationValue av = getAnnotationValueWrapper(identifier);
      if (av != null)
         return av.elementValue;
      return null;
   }

   public static Object convertElementValue(Object elementValue) {
      // Just handling literals for now... not sure if anything else is valid in annotation defs
      if (elementValue instanceof IValueNode)
         return ((IValueNode) elementValue).eval(null, null);
      // When copied over from the compile time versions
      else if (elementValue instanceof String)
         return elementValue;
      // When we call the "value()" method directly on the annotation, such as the standard Java RetentionPolicy value of RUNTIME
      // we get back the explicit value itself and so it does not need conversion.
      return elementValue;
   }

   public Object getAnnotationValue(String identifier) {
      AnnotationValue av = getAnnotationValueWrapper(identifier);
      if (av != null) {
         Object avalue = av.elementValue;
         // Just handling literals for now... not sure if anything else is valid in annotation defs
         if (avalue instanceof IValueNode) {
            return ((IValueNode) avalue).eval(null, null);
         }
         else if (avalue instanceof List) {
            Object annotType = getAnnotationValueType(identifier);
            Class rtClass = annotType == null ? null : ModelUtil.getCompiledClass(annotType);
            if (rtClass == null)
               rtClass = Object.class;
            return initAnnotationArray(rtClass, (List<Expression>) avalue, new ExecutionContext());
         }
         // When copied over from the compile time versions
         else if (avalue instanceof String)
            return avalue;
      }
      return null;
   }

   Object initAnnotationArray(Class rtClass, List<?> initializers, ExecutionContext ctx) {
      int size = initializers.size();
      Class componentType = rtClass.getComponentType();
      if (componentType == null)
         componentType = Object.class;
      else if (componentType.isAnnotation())
         componentType = Annotation.class;
      else {
         // TODO: check if there are any annotations in this list - if so, it should be an error
         return ArrayInitializer.initializeArray(rtClass, (List<Expression>) initializers, ctx);
      }
      Object[] value = (Object[]) Array.newInstance(componentType, size);
      for (int i = 0; i < size; i++) {
         Object init = initializers.get(i);
         if (init instanceof Expression)
            value[i] = ((Expression) init).eval(componentType, ctx);
         else // Annotation - we map annotations into the sc.lang.java.Annotation object directly
            value[i] = init;
      }
      return value;
   }

   public static Annotation createFromElement(java.lang.annotation.Annotation elem) {
      Annotation newAnnot = new Annotation();
      newAnnot.typeName = elem.annotationType().getTypeName();
      if (ModelUtil.isComplexAnnotation(elem)) {
          newAnnot.setProperty("elementValue", ModelUtil.getAnnotationComplexValues(elem));
      }
      else {
          newAnnot.setProperty("elementValue", ModelUtil.getAnnotationSingleValue(elem));

      }
      return newAnnot;
   }

   public static Annotation createFromElement(IAnnotation elem) {
      Annotation newAnnot = new Annotation();
      newAnnot.typeName = elem.getTypeName();
      if (ModelUtil.isComplexAnnotation(elem)) {
         newAnnot.setProperty("elementValue", ModelUtil.getAnnotationComplexValues(elem));
      }
      else {
         newAnnot.setProperty("elementValue", ModelUtil.getAnnotationSingleValue(elem));

      }
      return newAnnot;
   }

   /** Converts to an Annotation if needed */
   public static Annotation toAnnotation(Object elem) {
      if (elem instanceof Annotation)
         return (Annotation) elem;

      if (elem instanceof java.lang.annotation.Annotation)
         return createFromElement((java.lang.annotation.Annotation) elem);

      if (elem instanceof CFAnnotation)
         return createFromElement((IAnnotation) elem);

      throw new UnsupportedOperationException();
   }

   /** Converts to an Annotation f needed */
   public static List<Object> toAnnotationList(List<Object> list) {
      ArrayList<Object> res = new ArrayList<Object>();
      for (Object elem:list) {
         if (elem instanceof Annotation)
            res.add(elem);
         else if (elem instanceof java.lang.annotation.Annotation)
            res.add(createFromElement((java.lang.annotation.Annotation) elem));
         else if (elem instanceof CFAnnotation)
            res.add(createFromElement((IAnnotation) elem));
         else
            throw new UnsupportedOperationException();
      }
      return res;
   }

   /** Like toAnnotation but guarantees a copy */
   public static Annotation createFromAnnotation(Object elem) {
      if (elem instanceof Annotation)
         return (Annotation) ((Annotation) elem).deepCopy(ISemanticNode.CopyNormal, null);

      if (elem instanceof java.lang.annotation.Annotation)
         return createFromElement((java.lang.annotation.Annotation) elem);

      throw new UnsupportedOperationException();

   }

   public String getTypeName() {
      return typeName;
   }

   public boolean isComplexAnnotation() {
      return elementValue instanceof List;
   }

   public SemanticNodeList<AnnotationValue> getElementValueList() {
      if (elementValue instanceof SemanticNodeList)
         return (SemanticNodeList<AnnotationValue>) elementValue;
      return null;
   }

   public Object getElementSingleValue() {
      return elementValue;
   }

   public String getFullTypeName() {
      if (!isStarted() || boundType == null) {
         JavaModel model = getJavaModel();
         // Do not start stuff here just to get the full type name.  That ends up pulling in more stuff than we'd like
         // and technically this type name should be available this way without starting.
         String imported = model == null ? null : model.getImportedName(typeName);
         if (imported == null)
            return typeName;  // The type name may be absolute or not... if it's not imported It presumably must be absolute then right?
         else
            return imported;
      }
      return ModelUtil.getTypeName(boundType);
   }

   public String toAbsoluteString() {
      if (elementValue == null)
         return "@" + getFullTypeName();
      return "@" + getFullTypeName() + "(" + ((ISemanticNode) elementValue).toLanguageString(JavaLanguage.getJavaLanguage().annotationValue) + ")";
   }

   public Annotation deepCopy(int options, IdentityHashMap<Object,Object> oldNewMap) {
      Annotation res = (Annotation) super.deepCopy(options, oldNewMap);

      res.boundType = boundType;

      return res;
   }


   public String toString() {
      return toSafeLanguageString();
   }

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid) {
         StringBuilder sb = new StringBuilder();
         sb.append("@");
         sb.append(typeName);
         if (elementValue != null) {
            sb.append("(");
            sb.append(elementValue);
            sb.append(")");
         }
         return sb.toString();
      }
      return super.toSafeLanguageString();
   }

   public void stop() {
      super.stop();
      boundType = null;
   }

   String getAnnotationValueKey(AnnotationValue val) {
      return Definition.getAnnotationValueKey(getFullTypeName(), val.identifier);
   }

   public static void addToAnnotationsMap(TreeMap<String, Object> res, IAnnotation annot) {
      if (annot.isComplexAnnotation()) {
         List<AnnotationValue> elemVals = annot.getElementValueList();
         boolean arrayVal = false;
         for (AnnotationValue elemVal:elemVals) {
            res.put(Definition.getAnnotationValueKey(annot.getTypeName(), elemVal.identifier), elemVal.getPrimitiveValue());
         }
      }
      else {
         Object annotVal = annot.getElementSingleValue();
         if (annotVal instanceof AnnotationValue) {
            AnnotationValue elemVal = (AnnotationValue) annotVal;
            res.put(Definition.getAnnotationValueKey(annot.getTypeName(), elemVal.identifier), elemVal.getPrimitiveValue());
         }
         else if (annotVal == null) {
            res.put(annot.getTypeName(), Boolean.TRUE);
         }
         else if (annotVal instanceof Expression) {
            res.put(annot.getTypeName(), AnnotationValue.elemValToPrimitiveValue(annotVal));
         }
      }
   }

   public String addNodeCompletions(JavaModel origModel, JavaSemanticNode origNode, String matchPrefix, int offset, String dummyIdentifier, Set<String> candidates, boolean nextNameInPath, int max) {
      String packagePrefix;
      if (typeName == null)
         return matchPrefix;

      if (matchPrefix.contains(".")) {
         packagePrefix = CTypeUtil.getPackageName(matchPrefix);
         matchPrefix = CTypeUtil.getClassName(matchPrefix);
      }
      else {
         if (typeName.indexOf(".") != -1) {
            int ix = typeName.indexOf(dummyIdentifier);
            if (ix != -1)
               typeName = typeName.substring(0, ix);
            if (typeName.endsWith(".") && typeName.length() > 1)
               packagePrefix = typeName.substring(0, typeName.length() - 1);
            else
               packagePrefix = CTypeUtil.getPackageName(typeName);
         }
         else
            packagePrefix = origModel == null ? null : origModel.getPackagePrefix();
      }
      ModelUtil.suggestTypes(origModel, packagePrefix, matchPrefix, candidates, packagePrefix == null, false, max);
      return matchPrefix;
   }

   public boolean getNotFoundError() {
      return errorArgs != null && boundType == null;
   }
}
