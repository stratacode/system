/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.classfile;

import sc.lang.SemanticNodeList;
import sc.lang.java.AnnotationValue;
import sc.lang.java.JavaType;
import sc.parser.ParseUtil;
import sc.lang.java.IAnnotation;

import java.util.LinkedHashMap;
import java.util.Map;

public class CFAnnotation implements IAnnotation {
   ClassFile classFile;
   JavaType type;
   String typeName;

   LinkedHashMap<String,Object> elementValues;

   public void setCFTypeName(String classFileType) {
      type = SignatureLanguage.getSignatureLanguage().parseType(classFileType);
   }

   boolean started = false;
   public void start() {
      if (started)
         return;
      started = true;

      ParseUtil.initAndStartComponent(type);
   }

   public String getTypeName() {
      return typeName;
   }

   public Object getAnnotationValue(String name) {
      return elementValues == null ? null : elementValues.get(name);
   }

   public boolean isComplexAnnotation() {
      return elementValues != null && (elementValues.size() > 1 || !elementValues.containsKey("value"));
   }

   public SemanticNodeList<AnnotationValue> getElementValueList() {
      SemanticNodeList<AnnotationValue> snl = new SemanticNodeList<AnnotationValue>(elementValues.size());
      for (Map.Entry<String,Object> ent:elementValues.entrySet()) {
         AnnotationValue value = new AnnotationValue();
         value.identifier = ent.getKey();
         value.elementValue = ent.getValue();
         snl.add(value);
      }
      return snl;
   }

   public Object getElementSingleValue() {
      return elementValues == null ? null : elementValues.get("value");
   }
}
