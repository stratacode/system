package sc.dyn;

import java.util.Map;
import java.util.TreeMap;

/** 
 * Implemented by components that manage property errors. For DB components, when properties 
 * are updated, the validator is called and the errors updated.
 */
public interface IPropValidator {
   Map<String,String> getPropErrors();
   void setPropErrors(Map<String,String> errors);

   default void addPropError(String propName, String errorMessage) {
      Map<String,String> propErrors = getPropErrors();
      boolean doSet = false;
      if (propErrors == null) {
         propErrors = new TreeMap<String,String>();
         doSet = true;
      }
      propErrors.put(propName, errorMessage);
      if (doSet)
         setPropErrors(propErrors);
   }

   default void removePropError(String propName) {
      Map<String,String> propErrors = getPropErrors();
      boolean doSet = false;
      if (propErrors == null)
         return;
      propErrors.remove(propName);
      if (propErrors.size() == 0)
         setPropErrors(null);
   }
}
