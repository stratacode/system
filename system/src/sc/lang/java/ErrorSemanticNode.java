/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public abstract class ErrorSemanticNode extends JavaSemanticNode {
   transient Object[] errorArgs;

   public String getNodeErrorText() {
      if (errorArgs != null) {
         StringBuilder sb = new StringBuilder();
         for (Object arg:errorArgs)
            sb.append(arg.toString());
         sb.append(this.toString());
         return sb.toString();
      }
      return null;
   }

   public void displayError(String...args) {
      if (errorArgs == null) {
         super.displayError(args);
         errorArgs = args;
      }
   }

   public boolean displayTypeError(String...args) {
      if (super.displayTypeError(args)) {
         errorArgs = args;
         return true;
      }
      return false;
   }

   public void stop() {
      super.stop();
      errorArgs = null;
   }

   public boolean hasErrors() {
      return errorArgs != null;
   }

}
