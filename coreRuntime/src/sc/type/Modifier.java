/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import sc.js.JSSettings;

import java.util.TreeMap;

/** A subset of the Java modifiers that are useful for meta-data on domain models  */
@JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public class Modifier {
   static TreeMap<String,Integer> modifierFlags = new TreeMap<String,Integer>();
   static {
      modifierFlags.put("public", 1);
      modifierFlags.put("private", 2);
      modifierFlags.put("protected", 4);
      modifierFlags.put("static", 8);
      modifierFlags.put("final", 0x10);
      modifierFlags.put("transient", 0x80);
      modifierFlags.put("abstract", 0x800);
   }

   public static int getFlag(String modifier) {
      Integer val = modifierFlags.get(modifier);
      return val == null ? -1 : val;
   }

   public static boolean hasModifier(int modFlags, String modifier) {
      return (modifierFlags.get(modifier) & modFlags) != 0;
   }
}
