package sc.lang.sql;

class SchemaChangeDetail {
   SQLDefinition oldDef;
   SQLDefinition newDef;
   String message;

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
