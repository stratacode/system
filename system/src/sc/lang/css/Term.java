/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.css;

import sc.parser.StringToken;

/**
 * Created by rcrittendon on 3/8/14.
 */
public class Term extends CSSNode {
    public StringToken unaryOperator;
    public Object termValue;
}
