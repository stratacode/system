package sc.lang.sql;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.sql.funcOpt.FuncOpt;
import sc.lang.sql.funcOpt.FuncReturn;

import java.util.Set;

public class CreateFunction extends SQLCommand {
   public boolean orReplace;
   public SemanticNodeList<FuncArg> argList;
   public SQLIdentifier funcName;
   public FuncReturn funcReturn;
   public SemanticNodeList<FuncOpt> funcOptions;

   void addTableReferences(Set<String> refTableNames) {
      // TODO: look for references in the return type and parameter list
   }

   public String toDeclarationString() {
      return "function " + funcName;
   }

   public SQLCommand getDropCommand() {
      DropFunction dt = new DropFunction();
      dt.funcNames = new SemanticNodeList<SQLIdentifier>();
      dt.funcNames.add((SQLIdentifier) funcName.deepCopy(ISemanticNode.CopyNormal, null));
      return dt;
   }
}
