package sc.lang.sql;

import java.util.List;
import java.util.Set;

public class CreateSequence extends SQLCommand {
   public boolean temporary;
   public boolean ifNotExists;
   public SQLIdentifier sequenceName;
   List<SequenceOption> sequenceOptions;

   void addTableReferences(Set<String> refTableNames) {
   }

/*
   public SQLCommand getDropCommand() {
      DropSequence dt = new DropSequence();
      dt.sequenceNames = new SemanticNodeList<SQLIdentifier>();
      dt.sequenceNames.add((SQLIdentifier) sequenceName.deepCopy(ISemanticNode.CopyNormal, null));
      return dt;
   }
*/
}
