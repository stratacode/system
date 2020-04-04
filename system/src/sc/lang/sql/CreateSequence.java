package sc.lang.sql;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;

import java.util.List;
import java.util.Set;

public class CreateSequence extends SQLCommand {
   public boolean temporary;
   public boolean ifNotExists;
   public SQLIdentifier sequenceName;
   List<SequenceOption> sequenceOptions;

   void addTableReferences(Set<String> refTableNames) {
   }

   public SQLCommand getDropCommand() {
      DropSequence dt = new DropSequence();
      dt.seqNames = new SemanticNodeList<SQLIdentifier>();
      dt.seqNames.add((SQLIdentifier) sequenceName.deepCopy(ISemanticNode.CopyNormal, null));
      return dt;
   }

   public String getIdentifier() {
      return sequenceName.getIdentifier();
   }
}
