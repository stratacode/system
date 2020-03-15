/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.pattern;

import sc.lang.SemanticNode;

public class PatternVariable extends SemanticNode {
   // The first name in the expression
   public String name;
   // The name after the equals sign - these values are copied into propertyName and parseletName in the init
   public String equalsName;

   public transient String propertyName;
   public transient String parseletName;

   public void init() {
      super.init();
      if (equalsName != null) {
         parseletName = equalsName;
         propertyName = name;
      }
      else {
         parseletName = name;
      }
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("{");
      sb.append(name);
      if (equalsName != null) {
         sb.append("=");
         sb.append(equalsName);
      }
      sb.append("}");
      return sb.toString();
   }
}
