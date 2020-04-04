package sc.lang.sql;

import sc.lang.ISemanticNode;
import sc.lang.java.JavaSemanticNode;

import java.util.Set;

public abstract class SQLCommand extends JavaSemanticNode {
   public int getChildNestingDepth() {
      return 1;
   }

   abstract void addTableReferences(Set<String> refTableNames);

   public SQLCommand getDropCommand() {
      return null;
   }

   public abstract String getIdentifier();

   public void alterTo(SQLFileModel resModel, SQLCommand newCmd) {
      SQLCommand dropCmd = getDropCommand();
      if (dropCmd != null) {
         resModel.addCommand(dropCmd);
         resModel.addCommand((SQLCommand) newCmd.deepCopy(ISemanticNode.CopyNormal | ISemanticNode.CopyParseNode, null));
      }
      else
         throw new UnsupportedOperationException("Unable to alter DB entity type: " + getClass());
   }

}
