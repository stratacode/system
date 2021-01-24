/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.css;

import sc.lang.SemanticNodeList;
import sc.parser.StringToken;

/**
 * Created by rcrittendon on 3/5/14.
 */
public class MediaAtRule extends CSSNode {
    public StringToken medium;
    public SemanticNodeList<StringToken> otherMediums;
    public SemanticNodeList<RuleSet> ruleSets;
}
