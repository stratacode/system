package sc.lang.sql.funcOpt;

public class FuncBehaviorType extends FuncOpt {
   public String typeStr;

   public boolean deepEquals(Object other) {
      if (!(other instanceof FuncBehaviorType))
         return false;
      FuncBehaviorType otherF = (FuncBehaviorType) other;
      if (otherF.typeStr == typeStr)
         return true;
      if (otherF.typeStr == null || typeStr == null)
         return false;
      return otherF.typeStr.equalsIgnoreCase(typeStr);
   }
}
