package sc.lang.pattern;

public class OptionalPattern extends Pattern {

   // Returns "" when it does not match - so we effectively skip it.
   String match(String fromStr, Object inst) {
      String superMatch = super.match(fromStr, inst);
      if (superMatch == null)
         return "";
      return superMatch;
   }

   public ReplaceResult doReplaceString(String fromStr) {
      ReplaceResult superMatch = super.doReplaceString(fromStr);
      if (superMatch == null)
         return new ReplaceResult("", 0);
      return superMatch;
   }

}
