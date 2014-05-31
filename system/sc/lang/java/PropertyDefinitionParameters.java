/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.type.CTypeUtil;

public class PropertyDefinitionParameters {
   public String fieldModifiers, getModifiers, setModifiers; // Modifier values for each
   public boolean bindable;              // True if this field definition needs to be bindable
   public String propertyTypeName;       // The class name of the field
   public String setTypeName;            // The type name for the setX method just in case it's different from the getX
   public String enclosingTypeName;      // The class name of the containing class
   public String lowerPropertyName;      // The lower class version of the identifier name
   public String upperPropertyName;      // The upper class version of the identifier name
   public boolean omitField;             // True if we should omit the field - i.e. if it's already defined.
   public Object getMethod, setMethod;   // The existing get/set methods (if any)  If either of these is true, overrideGetSet is true as well.
   public boolean overrideField;         // True if this definition is on a subtype which already defined the field
   public boolean overrideGetSet;        // True if this definition is on a subtype which already defines get/set
   public String superGetName;           // If overrideGetSet is true, the name of the get method to override
   public String superSetName;           // If overrideGetSet is true, the name of the set method to override
   public String initializer;
   public boolean isStatic;
   public String arrayDimensions;
   public String getOrIs = "get";
   public String propertyMappingName;    // The name of the static _xProp constant - must be unique for the top-level class
   public String innerName;              // If this is an inner type, it is a name used to differentiate all of the inner types from the top level type
   public Object enclosingOuterTypeName; // The type name of the outer most type for this mapping
   public boolean sendEvent = true;
   public String preReturn;
   public String postReturn;

   public static PropertyDefinitionParameters create(String propName) {
      PropertyDefinitionParameters pdp = new PropertyDefinitionParameters();

      pdp.lowerPropertyName = CTypeUtil.decapitalizePropertyName(propName);
      pdp.upperPropertyName = CTypeUtil.capitalizePropertyName(propName);

      return pdp;
   }

   public void init(Object fieldObj, boolean doObjConvert) {
      // Need to get absolute type names here because we do not do the imports from the model because we never resolved the interface fields in this model
      setTypeName = propertyTypeName = ModelUtil.getAbsoluteGenericTypeName(ModelUtil.getEnclosingType(fieldObj), fieldObj, true);
      Object propType = ModelUtil.getPropertyType(fieldObj);

      if (doObjConvert) {
         if (ModelUtil.isPrimitive(propType)) {
            preReturn =  "sc.dyn.DynUtil." + ModelUtil.getNumberPrefixFromType(propType) + "Value(";
            postReturn = ")";
         }
         else {
            preReturn = "(" + propertyTypeName + ") ";
            postReturn = "";
         }
      }
      else {
         preReturn = "";
         postReturn = "";
      }

      int aix = propertyTypeName.indexOf("[");
      if (aix != -1) {
         arrayDimensions = propertyTypeName.substring(aix);
         propertyTypeName = propertyTypeName.substring(0, aix);
      }
      getModifiers = TransformUtil.removeModifiers(ModelUtil.modifiersToString(fieldObj, true, true, false, false, true, JavaSemanticNode.MemberType.GetMethod), TransformUtil.fieldOnlyModifiers);
      setModifiers = TransformUtil.removeModifiers(ModelUtil.modifiersToString(fieldObj, true, true, false, false, true, JavaSemanticNode.MemberType.SetMethod), TransformUtil.fieldOnlyModifiers);
   }
}
