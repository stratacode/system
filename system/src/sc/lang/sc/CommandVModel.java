/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sc;

import sc.lang.AbstractInterpreter;
import sc.lang.java.TypeContext;

public class CommandVModel extends SCModel {
   public AbstractInterpreter commandInterpreter;

   public Object definesType(String name, TypeContext ctx) {
      Object o = super.definesType(name, ctx);
      if (o != null)
         return o;

      /*
      if (name.equals("cmd"))
         return commandInterpreter;
      */

      return null;
   }
}
