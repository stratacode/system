/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

class SchemaChangeDetail {
   SQLDefinition oldDef;
   SQLDefinition newDef;
   String message;
   boolean dataLoss;
   boolean newConstraints;

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(message);
      sb.append("\n         old: ");
      sb.append(oldDef);
      sb.append("\n         new: ");
      sb.append(newDef);
      return sb.toString();
   }
}
