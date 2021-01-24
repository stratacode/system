/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.css;

import sc.lang.SemanticNode;
import sc.parser.StringToken;

/**
 * Created by rcrittendon on 3/1/14.
 */
public class NamespaceAtRule extends CSSNode {
    public StringToken identifier;
    public SemanticNode value;
}
