/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

public class RenameColumn extends AlterDef {
   public SQLIdentifier oldColumnName;
   public SQLIdentifier newColumnName;
}
