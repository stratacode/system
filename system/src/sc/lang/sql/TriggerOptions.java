/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.SemanticNode;

import java.util.List;

public class TriggerOptions extends SemanticNode {
   public boolean each;
   public String rowOrStatement; 
}
