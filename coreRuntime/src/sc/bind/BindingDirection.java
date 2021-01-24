/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.js.JSSettings;

@JSSettings(jsLibFiles="js/sccore.js", prefixAlias="sc_")
public enum BindingDirection
{
   FORWARD, REVERSE, BIDIRECTIONAL, NONE;

   public boolean doForward()
   {
      switch (this)
      {
         case FORWARD:
         case BIDIRECTIONAL:
            return true;
         case REVERSE:
            return false;
      }
      return false;
   }

   public boolean doReverse()
   {
      switch (this)
      {
         case FORWARD:
            return false;
         case BIDIRECTIONAL:
         case REVERSE:
            return true;
      }
      return false;
   }

   public String getOperatorString() {
      switch (this) {
         case FORWARD:
            return ":=";
         case BIDIRECTIONAL:
            return ":=:";
         case REVERSE:
            return "=:";
      }
      return null;
   }

   public static BindingDirection fromOperator(String op) {
      if (op == null)
         return null;

      if (op.equals(":="))
         return FORWARD;
      else if (op.equals(":=:"))
         return BIDIRECTIONAL;
      else if (op.equals("=:"))
         return REVERSE;
      else if (op.equals("="))
         return null;
      throw new IllegalArgumentException("Invalid binding direction");
   }
}
