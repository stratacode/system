package sc.lang.html;

import sc.dyn.DynUtil;
import sc.js.JSSettings;
import sc.lang.java.ModelUtil;
import sc.lang.pattern.OptionalPattern;
import sc.lang.pattern.Pattern;
import sc.lang.pattern.PatternVariable;
import sc.parser.PString;

import java.util.List;

/**
 * Used to represent metadata for parsing a URL parameter from the client to the server as parsed from
 * the @URL annotation.  This metadata is injected into the client version of the class so we know how to
 * interpret the URL on the client and set properties derived from it, just like QueryParamProperty.
 */
@JSSettings(prefixAlias="js_",jsLibFiles="js/tags.js")
public class URLParamProperty extends BaseURLParamProperty {
   String parseletName;

   public URLParamProperty(Object enclType, String propName, Object propType, String parseletName, boolean req) {
      super(enclType, propName, propType, req);
      this.parseletName = parseletName;
   }

   public static String toValueString(Object type, Pattern pat) {
      return toValueString(type, pat == null ? null : pat.elements);
   }

   public static String toValueString(Object type, List<Object> elements) {
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
               Object propType = ModelUtil.getPropertyTypeFromType(type, patVar.propertyName);
               sb.append("new sc.lang.html.URLParamProperty(sc.dyn.DynUtil.findType(\"" +
                       DynUtil.getTypeName(type, false) + "\"), \"" + patVar.propertyName + "\", sc.dyn.DynUtil.findType(\"" + DynUtil.getTypeName(propType, false) + "\"), \"" + patVar.parseletName + "\", false)");
            }
            else if (elem instanceof OptionalPattern) {
               OptionalPattern optPat = (OptionalPattern) elem;
               sb.append("new sc.lang.html.OptionalURLParam(" + toValueString(type, optPat.elements) + ")");
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
}
