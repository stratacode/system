/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

import sc.bind.Bind;
import sc.util.BTreeMap;

import java.util.Map;

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
         propErrors = new BTreeMap<String,String>();
         doSet = true;
      }
      propErrors.put(propName, errorMessage);
      if (doSet)
         setPropErrors(propErrors);
      Bind.sendChangedEvent(this, "propErrors");
   }

   default void removePropError(String propName) {
      Map<String,String> propErrors = getPropErrors();
      boolean doSet = false;
      if (propErrors == null)
         return;
      propErrors.remove(propName);
      if (propErrors.size() == 0)
         setPropErrors(null);
      Bind.sendChangedEvent(this, "propErrors");
   }

   default boolean hasError(String propName) {
      Map<String,String> propErrors = getPropErrors();
      return propErrors != null && propErrors.containsKey(propName);
   }

   default String getPropError(String propName) {
      Map<String,String> propErrors = getPropErrors();
      return propErrors == null ? null : propErrors.get(propName);
   }

   default boolean hasErrors() {
      Map<String,String> propErrors = getPropErrors();
      return propErrors != null && propErrors.size() > 0;
   }

   default boolean validateProp(String propName) {
      String err = DynUtil.validateProperty(this, propName);
      if (err == null)
         removePropError(propName);
      else
         addPropError(propName, err);
      return err == null;
   }

   static String validateRequired(String propDisplayName, String value) {
      if (value == null || value.trim().length() == 0) {
         return "Missing " + propDisplayName;
      }
      return null;
   }

   default String formatErrors() {
      Map<String,String> propErrors = getPropErrors();
      if (propErrors == null || propErrors.size() == 0)
         return null;
      StringBuilder sb = new StringBuilder();

      sb.append("Errors: ");
      boolean first = true;
      for (Map.Entry<String,String> ent:propErrors.entrySet()) {
         if (!first)
            sb.append(", ");
         //sb.append(ent.getKey());
         //sb.append(": ");
         sb.append(ent.getValue());
         first = false;
      }
      return sb.toString();
   }

   default boolean validateProperties() {
      Map<String,String> errs = DynUtil.validateProperties(this, null);
      setPropErrors(errs);
      return errs == null || errs.size() == 0;
   }
}
