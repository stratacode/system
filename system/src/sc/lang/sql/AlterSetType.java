package sc.lang.sql;

public class AlterSetType extends AlterCmd {
   public SQLDataType columnType;
   public Collation collation;
   public SQLExpression usingExpression;
}
