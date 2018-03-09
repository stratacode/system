package sc.lang.pattern;

public class OptionalPattern extends Pattern {
   // Returns "" when it does not match - so we effectively skip it.
   String match(String fromStr, Object inst) {
      String superMatch = match(fromStr, inst);
      if (superMatch == null)
         return "";
      return superMatch;
   }
}
