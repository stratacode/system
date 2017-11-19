/*
 * Copyright (c) 2016. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

/**
 * Base class for types which need to display per-node errors in the IDE
 */
public class AbstractErrorNode extends JavaSemanticNode {
   public transient Object[] errorArgs;

   /** Override to provide per-node error support */
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

   public void stop() {
      super.stop();

      errorArgs = null;
   }

   public boolean displayTypeError(String...args) {
      if (errorArgs == null) {
         if (super.displayTypeError(args)) {
            errorArgs = args;
            return true;
         }
      }
      return false;
   }

   public void displayError(String...args) {
      if (errorArgs == null) {
         super.displayError(args);
         errorArgs = args;
      }
   }

   public boolean hasErrors() {
      return errorArgs != null;
   }
}
