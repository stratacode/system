/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.css;

import sc.lang.SemanticNodeList;
import sc.parser.StringToken;

/**
 * Created by rcrittendon on 3/5/14.
 */
public class PageAtRule extends CSSNode{
    public StringToken ident;
    public StringToken pseudoPage;
    public SemanticNodeList<Declaration> declarations;
}
