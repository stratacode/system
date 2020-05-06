package sc.lang.sql;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.parser.IString;

import java.util.List;
import java.util.Set;

public class CreateType extends BaseCreateType {
   public List<TableDef> tableDefs;
}
