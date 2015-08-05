/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public enum AccessLevel {
   Public("public"), Private("private"), Protected("protected");

   public String levelName;

   private AccessLevel(String name) {
      levelName = name;
   }

   public static AccessLevel getAccessLevel(String name) {
      for (AccessLevel l:values())
         if (l.levelName.equals(name))
            return l;
      return null;
   }
}
