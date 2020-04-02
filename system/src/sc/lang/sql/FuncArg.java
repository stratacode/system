package sc.lang.sql;

import sc.lang.SemanticNode;

public class FuncArg extends SemanticNode {
   public String argMode;
   public SQLIdentifier argName;
   // This can be a fully qualified identifier of a schema/table name or a data type
   //public SQLDataType dataType;
   public SQLParamType dataType;
   public ArgDefault argDefault;
}
