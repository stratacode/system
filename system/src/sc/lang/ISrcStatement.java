/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
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
    * When called on a generated node, finds the original source node for the specified language which must be one of the intermediate
    * languages this node was generated from.  Lang can be null in
    * which case we trace generated nodes to find the original source language.
    */
   ISrcStatement getSrcStatement(Language lang);

   /** Search this statement for the srcStatement that produced it.  Return the last generated statement that matches */
   ISrcStatement findFromStatement (ISrcStatement st);

   /**
    * Adds all of the generated statements to the resulting list, for the case where more than one fromStatement points
    * to the same src statement.  These are used to determine which statements in the generated code should cause a 'break'
    * when the developer sets a breakpoint on this statement.
    */
   void addBreakpointNodes(List<ISrcStatement> result, ISrcStatement st);

   /** Returns the value of the 'fromStatement' field stored on this node to represent a link from a generated node from
    * an original source one. */
   ISrcStatement getFromStatement();

   boolean getNodeContainsPart(ISrcStatement partNode);

   /** For breakpoint and navigation purposes, how many lines does this statement take up - usually it's 1 */
   int getNumStatementLines();
}
