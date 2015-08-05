/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.js;

import sc.lang.java.AbstractTemplateParameters;
import sc.lang.java.ModelUtil;
import sc.layer.LayeredSystem;
import sc.type.CTypeUtil;

/** Base type used for JS templates like the modulePattern - where you only have a class and don't want to parse the src */
public class ObjectTypeParameters extends AbstractTemplateParameters {
   public Object typeObj;
   LayeredSystem system;

   public ObjectTypeParameters() {
   }

   public void init(LayeredSystem sys, Object to) {
      system = sys;
      typeObj = to;
   }

   public ObjectTypeParameters(LayeredSystem sys, Object type) {
      system = sys;
      typeObj = type;
   }

   public String getJavaTypeName() {
      return ModelUtil.getTypeName(typeObj);
   }

   public String getTypeName() {
      String typeName = ModelUtil.getTypeName(typeObj);
      String res = JSUtil.convertTypeName(system, typeName);
      if (res != null && res.equals("<missing type>"))
         System.err.println("*** Error: missing type in JS conversion for: " + typeName);
      return res;
   }

   public String getBaseTypeName() {
      return ModelUtil.getClassName(typeObj);
   }

   public String getUpperBaseTypeName() {
      return CTypeUtil.capitalizePropertyName(getBaseTypeName());
   }

   public boolean isObjectType() {
      return ModelUtil.isObjectType(typeObj);
   }

   public boolean isComponentType() {
      return ModelUtil.isComponentType(typeObj);
   }
}
