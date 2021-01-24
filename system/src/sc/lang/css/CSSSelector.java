/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.css;

import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.parser.StringToken;

/**
 * Created by rcrittendon on 3/7/14.
 */
public class CSSSelector extends SemanticNode {
    public SimpleSelector simpleSelector;
    public SemanticNodeList<StringToken> additionalSelectorTerms;
}
