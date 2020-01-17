package sc.lang.sql;

import sc.lang.SemanticNode;
import sc.parser.IString;

import java.util.List;

public class SQLDataType extends SemanticNode {
   public String typeName;
   public List<IString> sizeList;
   public List<IString> dimsList;
   public String intervalOptions;
}
