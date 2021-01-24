/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.pattern;

public class OptionalPattern extends Pattern {

   public ReplaceResult doMatch(String fromStr, boolean replace, Object inst, boolean isMap) {
      ReplaceResult superMatch = super.doMatch(fromStr, replace, inst, isMap);
      if (superMatch == null) {
         // If we match with optional pattern elements, need to set those physically to null so that
         // we restore the object properly in case the for this same page object previously had values
         setOptVariablesToNull(inst, isMap);
         return new ReplaceResult("", 0);
      }
      return superMatch;
   }

   void setOptVariablesToNull(Object inst, boolean isMap) {
      if (inst == null)
         return;
      for (Object elem:elements) {
         if (elem instanceof PatternVariable) {
            PatternVariable patVar = (PatternVariable) elem;
            String propName = patVar.propertyName;
            if (propName != null) {
               setPatternProperty(inst, propName, null, isMap);
            }
         }
         else if (elem instanceof OptionalPattern) {
            ((OptionalPattern) elem).setOptVariablesToNull(inst, isMap);
         }
      }
   }

}
