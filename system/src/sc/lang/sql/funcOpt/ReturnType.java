/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql.funcOpt;

import java.util.List;
import sc.lang.SemanticNode;
import sc.lang.sql.SQLDataType;
import sc.lang.sql.SQLIdentifier;
import sc.lang.sql.SQLParamType;

public class ReturnType extends FuncReturn {
   public boolean setOf;
   public SQLParamType dataType;
}
