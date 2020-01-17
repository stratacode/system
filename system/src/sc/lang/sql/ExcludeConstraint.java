package sc.lang.sql;

import java.util.List;

public class ExcludeConstraint extends SQLConstraint {
   public String usingMethod;
   public List<WithOperand> withOpList;
   public IndexParameters indexParams;
   public SQLExpression whereClause;
}
