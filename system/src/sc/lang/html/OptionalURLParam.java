package sc.lang.html;

import sc.js.JSSettings;

import java.util.List;

@JSSettings(prefixAlias="js_",jsLibFiles="js/tags.js")
public class OptionalURLParam {
   List<Object> urlParts; // List of either String, URLParamProperty or OptionalURLParam
   public OptionalURLParam(List<Object> parts) {
      urlParts = parts;
   }
}
