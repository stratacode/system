/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.util.StringUtil;

/** Layer components can add their own VM parameters */
public class VMParameter {
   public String parameterName;
   public String parameterValue;
   public VMParameter(String name, String value) {
      parameterName = name;
      parameterValue = value;
   }

   public boolean equals(Object other) {
      if (other instanceof VMParameter) {
         VMParameter otherMM = (VMParameter) other;
         if (!StringUtil.equalStrings(parameterName, otherMM.parameterName))
            return false;
         if (!StringUtil.equalStrings(parameterName, otherMM.parameterName))
            return false;
         return true;
      }
      return false;
   }

   public int hashCode() {
      return parameterName.hashCode() + (parameterValue != null ? parameterValue.hashCode() : 0);
   }
}
