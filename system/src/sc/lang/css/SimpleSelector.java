/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.css;

import sc.lang.SemanticNodeList;
import sc.parser.StringToken;

/**
 * Created by rcrittendon on 3/7/14.
 */
public class SimpleSelector extends CSSNode {
    public StringToken elementName;
    public SemanticNodeList<Object> additional;
}
