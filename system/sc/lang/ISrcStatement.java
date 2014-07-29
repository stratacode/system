/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.parser.Language;

import java.util.List;

/**
 * Represents nodes in the tree that are matched up in the original and generated source.
 * For Java this is implemented by both Statement and VariableDefinition since different VariableDefinitions in
 * a field can  turn into separate statements in the resulting source.
 */
public interface ISrcStatement extends ISemanticNode {
   /**
    * When called on a generated node, finds the original source node for the specified language.  Lang can be null in
    * which case the original source node is returned.
    */
   ISrcStatement getSrcStatement(Language lang);

   /** Search this statement for the srcStatement that produced it.  Return the last generated statement that matches */
   ISrcStatement findFromStatement (ISrcStatement st);

   /**
    * Adds all of the generated statements to the resulting list, for the case where more than one fromStatement points
    * to the same src statement.
    */
   void addGeneratedFromNodes (List<ISrcStatement> result, ISrcStatement st);

   /** Returns the value of the 'fromStatement' field stored on this node to represent a link from a generated node from
    * an original source one. */
   ISrcStatement getFromStatement();

   boolean getNodeContainsPart(ISrcStatement partNode);
}
