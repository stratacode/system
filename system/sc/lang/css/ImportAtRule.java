/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.css;

import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.parser.StringToken;

/**
 * Created by rcrittendon on 2/24/14.
 */
public class ImportAtRule extends CSSNode {
    SemanticNode importAt; // Could be StringLiteral or URI
    StringToken medium;
    SemanticNodeList<StringToken> otherMediums;
}
