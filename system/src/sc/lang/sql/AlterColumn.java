package sc.lang.sql;

import sc.lang.ISemanticNode;

public class AlterColumn extends AlterDef {
   public SQLIdentifier columnName;
   public AlterCmd alterCmd;

   public static AlterColumn createSetDataType(SQLIdentifier columnName, SQLDataType columnType) {
      AlterColumn ac = new AlterColumn();
      ac.columnName = (SQLIdentifier) columnName.deepCopy(ISemanticNode.CopyNormal, null);
      ac.alterCmd = AlterSetType.create(columnType);
      return ac;
   }
}
