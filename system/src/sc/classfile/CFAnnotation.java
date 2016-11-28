/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.classfile;

import sc.dyn.DynUtil;
import sc.lang.SemanticNodeList;
import sc.lang.java.*;
import sc.parser.ParseUtil;
import sc.type.PTypeUtil;
import sc.type.RTypeUtil;

import java.util.LinkedHashMap;
import java.util.Map;

public class CFAnnotation implements IAnnotation {
   ClassFile classFile;
   JavaType type;
   String typeName;

   LinkedHashMap<String,Object> elementValues;

   public void setCFTypeName(String classFileType) {
      type = SignatureLanguage.getSignatureLanguage().parseType(classFileType);
      // Convert from sig language to the real type name and use that as the hash-table key in the annotations map
      typeName = type.getFullTypeName();
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
      Object res = elementValues == null ? null : elementValues.get(name);
      if (res instanceof ClassFile.ConstantPoolEntry) {
         res = ((ClassFile.ConstantPoolEntry) res).getValue();

         if (res instanceof Integer && type != null) {
            // Should type have been set when constructing the CFAnnotation
            Object annotType = type.getTypeDeclaration();
            if (annotType == null)
               type.setTypeDeclaration(annotType = classFile.cfClass.system.getTypeDeclaration(typeName));

            if (annotType != null) {
               Object mem = ModelUtil.definesMethod(annotType, name, null, null, null, false, false, null, null, classFile.cfClass.getLayeredSystem());
               if (mem != null) {
                  Object dataType = ModelUtil.getReturnType(mem, true);
                  if (ModelUtil.isBoolean(dataType)) {
                     if (((Integer) res) == 1)
                        return Boolean.TRUE;
                     else
                        return Boolean.FALSE;
                  }
               }
            }
         }
         return res;
      }
      else if (res instanceof ClassFile.EnumValue) {
         ClassFile.EnumValue ev = (ClassFile.EnumValue) res;
         // Force this to a class reference - or do we need
         return ModelUtil.getEnum(classFile.cfClass.system.getClass(ev.typeName, true), ev.valueName);
      }
      return res;
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
      Object res = elementValues == null ? null : elementValues.get("value");
      // TODO: there are other data types we need to handle here!
      if (res instanceof Object[]) {
         Object[] arrRes = (Object[]) res;
         if (arrRes.length == 0)
            return res;

         Object[] enumRes = null;
         int ct = 0;
         for (Object arrElem:arrRes) {
            if (arrElem instanceof ClassFile.EnumValue) {
               ClassFile.EnumValue ev = (ClassFile.EnumValue) arrElem;
               // Force this to a class reference - or do we need
               Object enumType = ModelUtil.getEnum(classFile.cfClass.system.getClass(ev.typeName, true), ev.valueName);
               if (enumType != null) {
                  if (enumRes == null) {
                     enumRes = (Object[]) PTypeUtil.newArray(enumType.getClass(), arrRes.length);
                  }
                  enumRes[ct++] = enumType;
               }
            }
         }
         return enumRes != null ? enumRes : arrRes;
      }
      return res;
   }
}
