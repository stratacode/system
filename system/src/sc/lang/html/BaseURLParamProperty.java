/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.dyn.DynUtil;
import sc.js.JSSettings;
import sc.lang.java.ModelUtil;
import sc.type.IBeanMapper;
import sc.type.PTypeUtil;

@JSSettings(prefixAlias="js_",jsLibFiles="js/tags.js")
public abstract class BaseURLParamProperty {
   public String propName;
   public boolean required;
   public boolean constructor;
   public Object enclType;
   public Object propType;
   public IBeanMapper mapper;

   public BaseURLParamProperty() {
   }

   public BaseURLParamProperty(Object enclType, String propName, Object propType, boolean req, boolean constructor) {
      this.enclType = enclType;
      this.propName = propName;
      this.propType = propType;
      this.required = req;
      this.constructor = constructor;
   }

   public IBeanMapper getMapper() {
      if (mapper == null)
         initMapperAndType();
      return mapper;
   }

   void initPropertyType() {
      propType = ModelUtil.getPropertyTypeFromType(enclType, propName);
      if (propType == null)
         throw new IllegalArgumentException("No property: " + propName + " for type: " + enclType + " for query param");
   }

   private void initMapperAndType() {
      mapper = ModelUtil.getPropertyMapping(enclType, propName);
      if (mapper == null)
         throw new IllegalArgumentException("No query param property: " + this);
      propType = DynUtil.getPropertyType(mapper);
      /*
      if (!ModelUtil.isAssignableFrom(String.class, propType)) {
         IBeanMapper converterMapper = PTypeUtil.getPropertyMappingConverter(mapper, String.class, null);
         if (converterMapper != null)
            mapper = converterMapper;
         else
            throw new IllegalArgumentException("No converter for type: " + propType + " to convert query parameter for this: " + this);
      }
      */
   }

   public Object convertToPropertyValue(String strVal) {
      IBeanMapper localMapper = getMapper();
      if (ModelUtil.isInteger(propType)) {
         // Don't set int properties which are not set. For strings, we'll set 'null' as the value
         if (strVal == null)
            return null;

         int intVal;
         try {
            intVal = Integer.parseInt(strVal);
         }
         catch (NumberFormatException exc) {
            throw new IllegalArgumentException("Illegal value for integer property: " + strVal);
         }
         return intVal;
      }
      else if (ModelUtil.isBoolean(propType)) {
         boolean boolVal = false;
         if (strVal != null) {
            if (strVal.equals("") || strVal.equalsIgnoreCase("true"))
               boolVal = true;
            else if (!strVal.equalsIgnoreCase("false"))
               throw new IllegalArgumentException("Invalid value for boolean query parameter: " + strVal + " - must be null, empty, true, or false");
         }

         return boolVal;
      }
      else if (ModelUtil.isString(propType))
         return strVal;
      else
         throw new UnsupportedOperationException("No converter for query parameter type: " + propType);
   }
}
