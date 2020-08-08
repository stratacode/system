package sc.lang.html;

import sc.dyn.DynUtil;
import sc.js.JSSettings;
import sc.lang.java.ClassDeclaration;
import sc.lang.java.ModelUtil;
import sc.lang.pattern.OptionalPattern;
import sc.lang.pattern.Pattern;
import sc.lang.pattern.PatternVariable;
import sc.layer.LayeredSystem;
import sc.parser.PString;

import java.util.ArrayList;
import java.util.List;

/**
 * Used to represent metadata for parsing a URL parameter from the client to the server as parsed from
 * the @URL annotation.  This metadata is injected into the client version of the class so we know how to
 * interpret the URL on the client and set properties derived from it, just like QueryParamProperty.
 */
@JSSettings(prefixAlias="js_",jsLibFiles="js/tags.js")
public class URLParamProperty extends BaseURLParamProperty {
   String parseletName;

   public URLParamProperty(Object enclType, String propName, Object propType, String parseletName,
                           boolean req, boolean constructor) {
      super(enclType, propName, propType, req, constructor);
      this.parseletName = parseletName;
   }

   public static String toValueString(Object type, Pattern pat, List<String> constructorProps) {
      return toValueString(type, pat == null ? null : pat.elements, constructorProps);
   }

   public static String toValueString(Object type, List<Object> elements, List<String> constructorProps) {
      if (elements == null || elements.size() == 0)
         return "null";
      else {
         StringBuilder sb = new StringBuilder();
         sb.append("java.util.Arrays.asList(new Object[] { ");
         boolean first = true;
         for (Object elem:elements) {
            if (!first)
               sb.append(", ");
            first = false;
            if (PString.isString(elem)) {
               sb.append('"');
               sb.append(elem);
               sb.append('"');
            }
            else if (elem instanceof PatternVariable) {
               PatternVariable patVar = (PatternVariable) elem;
               String propName = patVar.propertyName;
               boolean constructor = constructorProps != null && constructorProps.contains(propName);
               Object propType = propName == null ? null : ModelUtil.getPropertyTypeFromType(type, propName);
               sb.append("new sc.lang.html.URLParamProperty(sc.dyn.DynUtil.findType(\"" +
                       DynUtil.getTypeName(type, false) + "\"), " + (propName == null ? "null" : "\"" + propName + "\"") +
                       ", " + (propName == null ? "null" : " sc.dyn.DynUtil.findType(\"" + DynUtil.getTypeName(propType, false) + "\") ") + ", \"" +
                       patVar.parseletName + "\", false, " + constructor + ")");
            }
            else if (elem instanceof OptionalPattern) {
               OptionalPattern optPat = (OptionalPattern) elem;
               sb.append("new sc.lang.html.OptionalURLParam(" + toValueString(type, optPat.elements, constructorProps) + ")");
            }
            // TODO: Is there a use case to support more complex patterns in URLs?
            else if (elem instanceof Pattern) {
               Pattern optPat = (Pattern) elem;
               if (optPat.negated)
                  throw new IllegalArgumentException("Missing support for ! patterns in URL strings");
               if (optPat.repeat)
                  throw new IllegalArgumentException("Missing support for * patterns in URL strings");
               throw new IllegalArgumentException("Missing support for nested patterns in URL strings");
            }
         }
         sb.append("})");
         return sb.toString();
      }
   }

   private static void addPatternProperties(Object type, List<Object> res, List<Object> elements, List<String> constructorProps, boolean required) {
      for (Object elem:elements) {
         if (PString.isString(elem)) {
            continue;
         }
         else if (elem instanceof PatternVariable) {
            PatternVariable patVar = (PatternVariable) elem;
            String propName = patVar.propertyName;
            boolean constructor = constructorProps != null && constructorProps.contains(propName);
            Object propType = ModelUtil.getPropertyTypeFromType(type, patVar.propertyName);

            res.add(new URLParamProperty(type, propName, propType, patVar.parseletName, required, constructor));
         }
         else if (elem instanceof OptionalPattern) {
            // TODO: add the elem here
            addPatternProperties(type, res, ((OptionalPattern) elem).elements, constructorProps, false);
         }
         // TODO: Is there a use case to support more complex patterns in URLs?
         else if (elem instanceof Pattern) {
            Pattern optPat = (Pattern) elem;
            if (optPat.negated)
               throw new IllegalArgumentException("Missing support for ! patterns in URL strings");
            if (optPat.repeat)
               throw new IllegalArgumentException("Missing support for * patterns in URL strings");
            throw new IllegalArgumentException("Missing support for nested patterns in URL strings");
         }
      }
   }

   public static List<Object> getURLParamProperties(LayeredSystem sys, Object type, String urlPattern) {
      Pattern pattern = Pattern.initURLPattern(type, urlPattern);
      if (pattern.elements == null || pattern.elements.size() == 0)
         return null;

      List<String> constructorProps = ClassDeclaration.getConstructorPropNamesForType(sys, type, ModelUtil.getLayerForType(sys, type));

      ArrayList<Object> res = new ArrayList<Object>();
      addPatternProperties(type, res, pattern.elements, constructorProps, true);

      return res;
   }
}
