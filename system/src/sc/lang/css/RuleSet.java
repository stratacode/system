/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.css;

import sc.lang.SemanticNodeList;

/**
 * Created by rcrittendon on 3/5/14.
 */
public class RuleSet extends CSSNode {
    public CSSSelector selector;
    public SemanticNodeList<CSSSelector> otherSelectors;
    public SemanticNodeList<Declaration> declarations;
    public int getChildNestingDepth() {
      return 1;
    }
}
