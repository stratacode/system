/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.css;

import sc.parser.StringToken;

/**
 * Created by rcrittendon on 3/5/14.
 */
public class Declaration extends CSSNode {
    public StringToken property;
    public Expr expr;
    public Important prio;
}
