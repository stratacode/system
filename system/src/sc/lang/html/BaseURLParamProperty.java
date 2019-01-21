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
   public Object enclType;
   public Object propType;
   public IBeanMapper mapper;

   public BaseURLParamProperty() {
   }

   public BaseURLParamProperty(Object enclType, String propName, Object propType, boolean req) {
      this.enclType = enclType;
      this.propName = propName;
      this.propType = propType;
      this.required = req;
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
      if (!ModelUtil.isAssignableFrom(String.class, propType)) {
         IBeanMapper converterMapper = PTypeUtil.getPropertyMappingConverter(mapper, String.class, null);
         if (converterMapper != null)
            mapper = converterMapper;
         else
            throw new IllegalArgumentException("No converter for type: " + propType + " to convert query parameter for this: " + this);
      }
   }

}
