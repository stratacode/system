package sc.lang.sql;

import sc.lang.SemanticNode;
import sc.parser.IString;

import java.util.List;

public class CreateTable extends SQLCommand {
   public List<IString> tableOptions;
   public SQLIdentifier tableName;
   public SQLIdentifier ofType;
   public boolean ifNotExists;
   public List<TableDef> tableDefs;
   public List<SQLIdentifier> tableInherits;
   public TablePartition tablePartition;
   public IndexParameters storageParams;
   public String tableSpace;
}
