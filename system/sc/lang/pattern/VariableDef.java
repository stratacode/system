/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.pattern;

import sc.lang.SemanticNode;

public class VariableDef extends SemanticNode {
   // The first name in the expression
   public String name;
   // The name after the equals sign - these values are copied into propertyName and parseletName in the init
   public String equalsName;

   public transient String propertyName;
   public transient String parseletName;

   public void initialize() {
      super.initialize();
      if (equalsName != null) {
         parseletName = equalsName;
         propertyName = name;
      }
      else {
         parseletName = name;
      }
   }
}
