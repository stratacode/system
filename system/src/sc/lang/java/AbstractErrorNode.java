/*
 * Copyright (c) 2016. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.ArrayList;
import java.util.Arrays;

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

   void displayRangeError(int fromIx, int toIx, boolean notFound, String...args) {
      displayTypeError(args);
      if (errorArgs != null) {
         ArrayList<Object> eargs = new ArrayList<Object>(Arrays.asList(errorArgs));
         eargs.add(new Statement.ErrorRangeInfo(fromIx, toIx, notFound));
         errorArgs = eargs.toArray();
      }
   }

   public boolean getNotFoundError() {
      if (errorArgs != null) {
         for (Object earg:errorArgs) {
            if (earg instanceof Statement.ErrorRangeInfo) {
               return ((Statement.ErrorRangeInfo) earg).notFoundError;
            }
         }
      }
      return false;
   }

   public boolean hasErrors() {
      return errorArgs != null;
   }
}
