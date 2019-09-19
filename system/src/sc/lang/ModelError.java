package sc.lang;

public class ModelError {
   public int startIndex;
   public int endIndex;
   public String error;
   public boolean notFound;

   public ModelError(String error, int startIx, int endIx, boolean notFound) {
      this.error = error;
      this.startIndex = startIx;
      this.endIndex = endIx;
      this.notFound = notFound;
   }
}
