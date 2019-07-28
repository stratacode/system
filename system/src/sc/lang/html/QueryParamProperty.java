package sc.lang.html;

import sc.dyn.DynUtil;
import sc.js.JSSettings;
import sc.lang.java.ModelUtil;
import sc.type.IBeanMapper;
import sc.type.PTypeUtil;
import sc.type.TypeUtil;

import java.util.ArrayList;
import java.util.List;

/** This is the runtime representation of the QueryParam annotation in the html/core layer. */
@JSSettings(prefixAlias="js_",jsLibFiles="js/tags.js")
public class QueryParamProperty extends BaseURLParamProperty {
   public String paramName;

   public QueryParamProperty() {
   }

   public QueryParamProperty(Object enclType, String propName, String paramName, Object propType, boolean req) {
      super(enclType, propName, propType, req);
      this.paramName = paramName;
   }

   public static List<QueryParamProperty> getQueryParamProperties(Object type) {
      List<Object> propsWithQueryParam = ModelUtil.getPropertiesWithAnnotation(type, "sc.html.QueryParam");

      if (propsWithQueryParam != null) {
         List<QueryParamProperty> res = new ArrayList<QueryParamProperty>();
         for (Object queryParamProp:propsWithQueryParam) {
            QueryParamProperty newP = new QueryParamProperty();
            newP.propName = DynUtil.getPropertyName(queryParamProp);
            String paramName = (String) DynUtil.getAnnotationValue(queryParamProp, "sc.html.QueryParam", "name");
            if (paramName == null)
               newP.paramName = newP.propName;
            else
               newP.paramName = paramName;
            Object reqObj = DynUtil.getAnnotationValue(queryParamProp, "sc.html.QueryParam", "required");
            if (reqObj != null && ((Boolean) reqObj)) {
               newP.required = true;
            }
            newP.enclType = type;
            newP.initPropertyType(); // This happens during transform time where the mapper is not available
            res.add(newP);
         }
         return res;
      }
      return null;
   }

   // Returns the string we need to compile for representing the list of a given type's query params.
   // new QueryParamProperty[] { new QueryParamProperty("x", true), .. }
   public static String toValueString(Object type) {
      List<QueryParamProperty> props = getQueryParamProperties(type);
      if (props == null || props.size() == 0)
         return "null";
      else {
         StringBuilder sb = new StringBuilder();
         sb.append("java.util.Arrays.asList(new sc.lang.html.QueryParamProperty[] { ");
         boolean first = true;
         for (QueryParamProperty prop:props) {
            if (!first)
               sb.append(", ");
            first = false;
            sb.append("new sc.lang.html.QueryParamProperty(sc.dyn.DynUtil.findType(\"" + DynUtil.getTypeName(type, false) + "\"), \"" + prop.propName + "\", \"" + prop.paramName + "\", " + DynUtil.getTypeName(prop.propType, false) + ".class, " + prop.required + ")");
         }
         sb.append("})");
         return sb.toString();
      }
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("QueryParam: " + paramName + " mapped to: " + enclType + "." + propName);
      return sb.toString();
   }

   public void setPropertyValue(Object inst, String strVal) {
      IBeanMapper localMapper = getMapper();
      if (ModelUtil.isInteger(propType)) {
         // Don't set int properties which are not set. For strings, we'll set 'null' as the value
         if (strVal == null && ModelUtil.isPrimitive(propType))
            return;

         int intVal;
         try {
            intVal = Integer.parseInt(strVal);
         }
         catch (NumberFormatException exc) {
            throw new IllegalArgumentException("Illegal value for integer property: " + strVal);
         }
         localMapper.setPropertyValue(inst, intVal);
      }
      else if (ModelUtil.isString(propType))
         localMapper.setPropertyValue(inst, strVal);
      else
         throw new UnsupportedOperationException("No converter for query parameter type: " + propType);
   }
}
