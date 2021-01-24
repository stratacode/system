/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.css;

import sc.lang.SemanticNodeList;

/**
 * Created by rcrittendon on 3/5/14.
 */
public class Expr extends CSSNode {
    public Term term;
    public SemanticNodeList<Term> otherTerms;
}
