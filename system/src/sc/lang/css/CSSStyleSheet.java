/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.css;

import sc.lang.SemanticNodeList;

/**
 * Created by rcrittendon on 2/22/14.
 */
public class CSSStyleSheet extends CSSNode {
    public CharSetAtRule charsetRule;

    public SemanticNodeList<ImportAtRule> importAtRules;

    public SemanticNodeList<NamespaceAtRule> namespaceAtRules;

    public SemanticNodeList styleStatements;
}
