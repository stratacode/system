package sc.dyn;

import java.util.Map;

/** 
 * Implemented by components that manage property errors. For DB components, when properties 
 * are updated, the validator is called and the errors updated.
 */
public interface IPropValidator {
   Map<String,String> getPropErrors();
   void setPropErrors(Map<String,String> errors);
}
