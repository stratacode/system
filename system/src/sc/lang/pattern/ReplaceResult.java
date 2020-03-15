package sc.lang.pattern;

public class ReplaceResult {
   public String result;
   int matchedLen;
   public ReplaceResult(String res, int matchedLen) {
      this.result = res;
      this.matchedLen = matchedLen;
   }

   public String toString() {
      return result;
   }
}
