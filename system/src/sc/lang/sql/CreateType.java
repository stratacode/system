package sc.lang.sql;

import sc.lang.SemanticNode;
import sc.parser.IString;

import java.util.List;

public class CreateType extends SQLCommand {
   public SQLIdentifier typeName;
   public List<TableDef> tableDefs;
}
