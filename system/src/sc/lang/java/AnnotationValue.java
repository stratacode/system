/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class AnnotationValue extends JavaSemanticNode {
   public String identifier;
   // Expression, Annotation, List<elementValue>
   public Object elementValue;

   /**
    * To create an annotation value from a java.lang.String, java.lang.Boolean, etc. use this create method.
    * If you already have an Expression or Literal use createFromAnnotValue.
    */
   public static AnnotationValue create(String id, Object val) {
      AnnotationValue res = new AnnotationValue();
      res.identifier = id;
      // Currently in the grammar array values in annotations are stored in a List<ElementValue>
      if (val.getClass().isArray() || val instanceof List || val instanceof Set) {
         res.setProperty("elementValue", ArrayInitializer.createAnnotationValue(val));
      }
      else
         res.setProperty("elementValue", AbstractLiteral.createFromValue(val, true));
      return res;
   }

   /** If you are creating the annotation value when you already have the StringLiteral, or whatever use this variant */
   public static AnnotationValue createFromAnnotValue(String id, Object annotVal) {
      AnnotationValue res = new AnnotationValue();
      res.identifier = id;
      res.setProperty("elementValue", annotVal);
      return res;
   }

   public String toSafeLanguageString() {
      StringBuilder sb = new StringBuilder();
      if (identifier == null && elementValue == null)
         sb.append("<invalid annotation value - no identifier");
      else if (identifier != null) {
         sb.append(identifier);
         if (elementValue != null) {
            sb.append("=");
            sb.append(elementValue.toString());
         }
      }
      else {
         sb.append(elementValue.toString());
      }
      return sb.toString();
   }

   public String toString() {
      return toSafeLanguageString();
   }

   public static Object elemValToPrimitiveValue(Object elementValue) {
      if (elementValue instanceof Expression)
         return ((Expression) elementValue).eval(null, null);
      else if (elementValue instanceof List)
         return listToPrimitiveValue((List) elementValue);
      else if (elementValue == null)
         return Boolean.TRUE;
      else if (elementValue instanceof Annotation) {
         return null; // TODO: not sure what to do here?
      }
      else if (elementValue instanceof String || elementValue instanceof Boolean || elementValue instanceof Number || elementValue instanceof Character)
         return elementValue;
      else if (elementValue instanceof IValueNode) {
         return ((IValueNode) elementValue).getPrimitiveValue();
      }
      throw new UnsupportedOperationException();
   }

   public Object getPrimitiveValue() {
      return elemValToPrimitiveValue(elementValue);
   }

   public static ArrayList<Object> listToPrimitiveValue(List elemVal) {
      ArrayList<Object> res = new ArrayList(elemVal.size());
      for (int i = 0; i < elemVal.size(); i++) {
         res.add(elemValToPrimitiveValue(elemVal.get(i)));
      }
      return res;
   }

}
