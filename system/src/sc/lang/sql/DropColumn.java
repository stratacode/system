package sc.lang.sql;

public class DropColumn extends AlterDef {
   public boolean ifExists;
   public SQLIdentifier columnName;
   public String dropOptions;
}
