/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.csv;

import sc.lang.SemanticNode;
import sc.parser.IString;

import java.util.List;

public class CSVRow extends SemanticNode {
   public List<CSVField> fields;
}
