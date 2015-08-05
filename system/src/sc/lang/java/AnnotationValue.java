/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;
import java.util.Set;

public class AnnotationValue extends JavaSemanticNode {
   public String identifier;
   // Expression, Annotation, List<elementValue>
   public Object elementValue;

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
}
