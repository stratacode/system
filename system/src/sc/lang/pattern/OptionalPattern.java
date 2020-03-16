package sc.lang.pattern;

public class OptionalPattern extends Pattern {

   public ReplaceResult doMatch(String fromStr, boolean replace, Object inst) {
      ReplaceResult superMatch = super.doMatch(fromStr, replace, inst);
      if (superMatch == null)
         return new ReplaceResult("", 0);
      return superMatch;
   }

}
