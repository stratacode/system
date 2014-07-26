/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.js.JSSettings;
import sc.parser.IParseNode;
import sc.parser.Language;
import sc.parser.Parselet;

import java.util.IdentityHashMap;

/**
 * The semantic node interfaface is implemented by primarily SemanticNode and SemanticNodeList.  Instances of this interface
 * are created by the parser when it parses a parselets grammar, or by the framework developer when they create or modify an object model.  The object
 * model is formed by a tree of these semantic nodes - e.g. ClassDeclaration, IfStatement, BodyStatements, etc.  The ISemanticNode
 * interface offers methods for editing the tree - replaceChild, etc.  You can convert any node to its language representation.
 * The SemanticNode also has an optional parsenode associated with it.  The parse-node tree is maintained in parallel to the semantic node tree
 * and represents the raw matched strings with their parselets (the grammar elements that produced them).   As you make changes to the semantic node tree,
 * the parse node tree is updated automatically or invalidated and regenerated the next time it is requested.  You can also strip off the parse node and
 * regenerate them from scratch.  All of this is done using the same grammar model.
 *
 **/
@JSSettings(jsLibFiles="js/javasys.js", replaceWith="jv_Object") // Because ISemanticNode and Element use parentNode as the name of the property, this type gets exposed to JS but we replace it with an object.
public interface ISemanticNode {
   public void setParseNode(IParseNode pn);
   public IParseNode getParseNode();

   public ISemanticNode getParentNode();
   public void setParentNode(ISemanticNode node);
   public ISemanticNode getRootNode();

   /** Returns the number of levels of nesting of top-level constructs for indentation */
   public int getNestingDepth();

   public int getChildNestingDepth();

   public String toHeaderString();

   /** After validate, before transform */
   public void process();

   public boolean needsTransform();

   public boolean transform(ILanguageModel.RuntimeType runtime);

   public String toLanguageString();

   public String toLanguageString(Parselet p);

   public boolean containsChild(Object toReplace);

   /** Finds the "toReplace" object in this nodes children, then replaces that node with the other object */
   public int replaceChild(Object toReplace, Object other);

   public int removeChild(Object toRemove);

   /** Returns null or an error string for this node */
   public String getNodeErrorText();

   /** Options for the deepCopy method - OR'd together as bit flags.  Copy the complete parse node tree */
   public static final int CopyParseNode = 1;

   /** Copy the semantic node initialization state */
   public static final int CopyState = 2;

   /** Match the initialization level of the node to be copied. */
   public static final int CopyInitLevels = 4;

   /** Used to indicate this is the special clone for the transformed model.  This let's the models register against each other as needed. */
   public static final int CopyTransformed = 8;

   public static final int SkipParseNode = 16;

   /**
    * Copy just some of the state in the semantic nodes.  Used to speed things up and make code more robust during
    * transform so expressiona don't get re-resolved mid-stream.  This mode does not copy the parse node but
    * does create a new parse node which points to the parselet so we can re-generate this child.  No initialized, started, etc. flags are copied.
    */
   public static final int CopyNormal = CopyState;
   /**
    * Hook for copying everything - the complete initialized state of each of the objects
    */
   public static final int CopyAll = CopyParseNode | CopyState | CopyInitLevels;

   /**
    * Performs a deep copy of the semantic node tree.  Subclasses should do their own copy implementation to decide
    * what else to copy.  Uses the copy options flags above.  The default, with 0, just copies the semantic node information
    * so you get a new tree like's it parsed but without parse node info.  Use that mode only if you want to throw away the
    * original source and regenerate it all from scratch.  Otherwise, you can copy the parse tree as well.  If you want to
    * copy the semantic node's initial resolved state - including it's init/start, etc. and whatever the subclass adds to that
    * copy, use CopyState or CopyNormal - for both parse nodes and state.
     */
   public ISemanticNode deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap);

   public boolean getTransformed();

   public boolean isValidated();

   /** Regenerates the parsed description for this node */
   public boolean regenerate(boolean finalGen);

   public void regenerateIfTracking(boolean finalGen);

   public void validateParseNode(boolean finalGen);

   public CharSequence toStyledString();

   public CharSequence toModelString();

   public void invalidateParseNode();

   public boolean isParseNodeValid();

   /**
    * It's common to want to convert from one language to another, particularly when those languages are related in
    * a type hierarchy.  For example, our Javascript language derives from Java so you can switch from one to the other.
    * Any overlapping nodes, will swap parselets with the corresponding parselet in the other language.  This handles
    * cases like Java arrayInitializer to Javascript array initializer automatically, i.e. those cases which simply
    * reorganize the syntax of the same program elements.
    */
   public void changeLanguage(Language l);

   /** Sometimes in a semantic node, you need to add children which are semantic nodes but which are not part of the
    * semantic node tree.  For example, in type declaration, we have "hiddenBody" which stores body elements that
    * behave as though they are added to the type but are not saved to the transformed file.
    * <p>
    * For these cases, you should override this method and return false when child == hiddenBody.  That way, we do not
    * propagate up refresh events and things when nodes in that tree change.
    * </p>
    */
   public boolean isSemanticChildValue(ISemanticNode child);

   /** Returns true if this object equals the other object by comparing all other properties with deepEquals */
   public boolean deepEquals(Object other);

   /** For nodes that are able to re-resolve themselves to the latest version, return the latest version.  otherwise returns this. */
   public ISemanticNode refreshNode();
}
