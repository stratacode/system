/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.binf.ModelOutStream;
import sc.js.JSSettings;
import sc.obj.EditorSettings;
import sc.parser.*;

import java.util.IdentityHashMap;

/**
 * The semantic node interface is implemented by primarily SemanticNode and SemanticNodeList.  Instances of this interface
 * are created by the parser when it parses a parselets grammar, or by the framework developer when they create or modify an object model.  The object
 * model is formed by a tree of these semantic nodes - e.g. ClassDeclaration, IfStatement, BodyStatements, etc.  The ISemanticNode
 * interface offers methods for editing the tree - replaceChild, etc.  You can convert any node to its language representation.
 * The SemanticNode also has an optional parseNode associated with it.  The parse-node tree is maintained in parallel to the semantic node tree
 * and represents the raw matched strings with their parselets (the grammar elements that produced them).   As you make changes to the semantic node tree,
 * the parse node tree is updated automatically or invalidated and regenerated the next time it is requested.  You can also strip off the parse node and
 * regenerate them from scratch.  All of this is done using the same grammar model.
 *
 **/
@JSSettings(jsLibFiles="js/javasys.js", replaceWith="jv_Object") // Because ISemanticNode and Element use parentNode as the name of the property, this type gets exposed to JS but we replace it with an object.
public interface ISemanticNode {
   public void setParseNode(IParseNode pn);
   public IParseNode getParseNode();

   /** Like setParseNode but called from the restore method */
   public void restoreParseNode(IParseNode pn);

   public void setParseletId(int id);
   public int getParseletId();

   @EditorSettings(visible=false)
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

   public int indexOfChild(Object child);

   public Object getChildAtIndex(int ix);

   /** Finds the "toReplace" object in this nodes children, then replaces that node with the other object */
   public int replaceChild(Object toReplace, Object other);

   public int removeChild(Object toRemove);

   /** Returns null or an error string for this node */
   public String getNodeErrorText();

   /** Returns null to mark the entire node - otherwise return a range that's more convenient for marking */
   public ParseRange getNodeErrorRange();

   /** Returns true if this is a special "not found" error */
   public boolean getNotFoundError();

   public String getNodeWarningText();

   /** Returns null or the text describing a dependency which is disabled to augment the error - e.g. for when a Layer is disabled and you want to reflect that in the error message you give to the user. */
   public String getDependencyDisabledText();

   /** Options for the deepCopy method - OR'd together as bit flags.  Copy the complete parse node tree */
   public static final int CopyParseNode = 1;

   /** Copy the semantic node initialization state */
   public static final int CopyState = 2;

   /** Match the initialization level of the node to be copied. */
   public static final int CopyInitLevels = 4;

   /** Used to indicate this is the special clone for the transformed model.  This let's the models register against each other as needed. */
   public static final int CopyTransformed = 8;

   public static final int SkipParseNode = 16;

   /** Indicates that the copy will replace the current statement in the model which is started.  E.g. a TemplateExpression sets replaceStatement to the copy, so we can trace from the original to the actual code-model which is resolved */
   public static final int CopyReplace = 32;

   /** Used to avoid copying the fromStatement, like when we clone a model for a different system we don't want the copy pointing back to the original */
   public static final int CopyIndependent = 64;

   /**
    * Copy just some of the state in the semantic nodes.  Used to speed things up and make code more robust during
    * transform so expressions don't get re-resolved mid-stream.  This mode does not copy the parse node but
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

   public boolean isInitialized();

   public boolean isStarted();

   public boolean isValidated();

   public void clearStarted();

   public void clearInitialized();

   /** Regenerates the parsed description for this node */
   public boolean regenerate(boolean finalGen);

   public void regenerateIfTracking(boolean finalGen);

   public void validateParseNode(boolean finalGen);

   /**
    * By default, you configure styles using the parselet's styleName property.  When you need to alter the syntax highlighting rules
    * for a given node, you can override this method.
    */
   public void styleNode(IStyleAdapter adapter);

   public CharSequence toModelString();

   public void setParseNodeValid(boolean v);

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

   /** For debugging - produce a string representation of the diffs between two models */
   public void diffNode(Object other, StringBuilder diffs);

   /** For nodes that are able to re-resolve themselves to the latest version, return the latest version.  otherwise returns this. */
   public ISemanticNode refreshNode();

   /** Most statements if started before the newline that signals a breakpoint should be considered the 'source statement' for that line.  Block statement is used to signal the end of the statement and is an exception.  */
   public boolean isTrailingSrcStatement();

   /** If we failed to parse this node and instead parsed this node as an incomplete token (e.g. a ClassType as part of a classBodyDeclarations), this method is called with 'true'. */
   public void setParseErrorNode(boolean v);

   /** Returns the value of the parse error node flag.  If your semantic value is used in a skipOnErrorParselet, you can implement this to differentiate error nodes from regularly parsed nodes */
   public boolean getParseErrorNode();

   /** Returns number of nodes in this tree for helping to diagnose memory use */
   public int getNodeCount();

   /**
    * Saves all of the properties of this model as parsed.  If you need to save additional state with the semantic model that's not set directly from the grammar,
    * you can override this method, set additional fields and just make sure they are not marked 'transient'
    */
   public void serialize(ModelOutStream out);

   public void clearParseNode();
}
