/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.css;

import sc.parser.StringToken;

/**
 * Created by rcrittendon on 3/5/14.
 */
public class PseudoFunction extends CSSNode {
    public StringToken function;
    public StringToken ident;
}
