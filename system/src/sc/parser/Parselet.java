/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.dyn.DynUtil;
import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.java.BodyTypeDeclaration;
import sc.lifecycle.ILifecycle;
import sc.type.RTypeUtil;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This is the abstract class for all parselets.  It contains common functionality common to all parselets.
 */
public abstract class Parselet implements Cloneable, IParserConstants, ILifecycle {
   // Class used for multi-valued items
   public final static Class ARRAY_LIST_CLASS = SemanticNodeList.class;

   String name;

   public boolean trace = false; // DEBUG ONLY

   public Language language;

   public boolean ignoreEmptyList = true;

   // Set this to true if this parselet is not required to be present in the stream.  In this
   // case, a null value is returned instead of an error if this does not match.
   public boolean optional = false;

   // Set this to true if this parselet can be found more than once in the stream
   public boolean repeat = false;

   // Set this to true to signal an error when the string matches
   public boolean negated = false;

   // Set this to true for this parselet to match but not consume its input
   public boolean lookahead = false;

   // Set this to true to skip this parselet from the result tree.  Its value is merged into
   // the parentNode type.
   public boolean skip = false;

   // Set this to true for children of a sequence like ; or ) which are needed for the syntax but not to disambiguate the text we've already parsed.  An error will be flagged but if we can parse by skipping it, we'll do that.
   public boolean skipOnError = false;

   // Set this to true to discard the results from this parselet and all sub-parselets from
   // the output.
   public boolean discard = false;

   // Do we report errors for a failure on this node?
   public boolean reportError = true;

   // If repeat is true, this can be used to treat the value as a scalar anyway
   public boolean treatValueAsScalar = false;

   public boolean initialized = false;

   public boolean started = false;

   public boolean cacheResults = false;

   // TODO: Ifdef "STATS_ENABLED"
   public int attemptCount, successCount;
   public int generatedBytes, failedProgressBytes;
   public boolean recordTime = false;

   /**
    * The class of the result.  Must implement IParseNode.  Usually a subclass of either ParseNode for
    * primitive values constructed from a String or ParentParseNode for complex objects.
    */
   public Class resultClass;

   /** In the rare case a parselet is used to populate a dynamic type, this stores that dyn type. */
   public BodyTypeDeclaration resultDynType;

   // The name set on this parselet
   protected String resultClassName;

   /**
    * This is a ParseNode which if set, is returned during the generation phase.
    * For procedurally generated elements such as spacing and newlines which need the context
    * of the parse tree in which they are embedded to determine their behavior, you can just
    * set this property to a fixed instance.  
    */
   public IParseNode generateParseNode;

   /**
    * Assign a default style for semantic values produced by this parse node.  Often, but not always the style for a text node
    * is associated with it's grammar node.  If you need to customize the style for a given node, override the styleNode method.
    */
   public String styleName;

   // Was this parselet named directly by a field of the language it was defined in (as opposed to being a child of one of the parselets with a field)
   public boolean fieldNamed = false;

   /** For syntax coloring, represents the style name for this element */

   public Parselet() {
      initOptions(0);
   }

   public Parselet(String name) {
      this.name = name;
      initOptions(0);
   }

   public Parselet(String name, int options) {
      this.name = name;
      initOptions(options);
   }

   public Parselet(int options) {
      initOptions(options);
   }

   public void init() {
      if (initialized)
          return;

      initialized = true;

      if (name != null) {
         String toUseName = name;
         if (name.startsWith("<")) {
            int ix = name.indexOf(">");
            if (ix == -1)
               System.err.println("*** Malformed parselet name: " + name);
            else
               toUseName = name.substring(ix+1);
         }

         if (toUseName.length() != 0) {
            int nameStartIx = toUseName.indexOf("[");
            int parenIx =  toUseName.indexOf("(");
            if (nameStartIx == -1 || nameStartIx > parenIx)
                nameStartIx = parenIx;
            String className = nameStartIx == -1 ? toUseName : toUseName.substring(0,nameStartIx);
            className = className.trim();

            // No name means we skip
            if (className.length() == 0)
               skip = true;
            else {
               if (getLanguage() == null)
                  throw new IllegalArgumentException("Parselet.setLanguage must be called for any parselets not connected to the startParselet");

               if (resultClassName == null)
                  resultClassName = className;
               if (resultDynType == null) {
                  setResultClass(getLanguage().lookupModelClass(resultClassName));
                  if (resultClass == null) {
                     Object resultType;
                     // This might be a model type used by the Pattern servlet...
                     if ((resultType = DynUtil.findType(resultClassName)) == null)
                        System.err.println("*** Error: " + className + " could not be loaded as a model class");
                     else if (resultType instanceof BodyTypeDeclaration)
                        resultDynType = (BodyTypeDeclaration) resultType;
                  }
               }
            }
         }
      }
   }

   public void setResultClassName(String rcName) {
      resultClassName = rcName;
   }

   public void setResultClass(Class c) {
      if (resultClass != null && c != null)
         System.out.println("*** conflicting class definition for: " + this + " " + resultClass + " and " + c);
      resultClass = c;
   }

   public void start() {
      if (started)
         return;

      started = true;
   }

   public void stop() {
      started = false;
      initialized = false;
   }

   public boolean isInitialized() {
      return initialized;
   }

   public boolean isStarted() {
      return started;
   }

   /* No need for this pass */
   public boolean isValidated() {
      return isStarted();
   }
   public void validate() {}

   public void process() {}

   public boolean isProcessed() {
      return isValidated();
   }

   private void initOptions(int options) {
      optional = (options & OPTIONAL) != 0;
      repeat = (options & REPEAT) != 0;
      negated = (options & NOT) != 0;
      lookahead = (options & LOOKAHEAD) != 0;
      skip = (options & SKIP) != 0;
      discard = (options & DISCARD) != 0;
      reportError = (options & NOERROR) == 0;
      trace = (options & TRACE) != 0;
      skipOnError = (options & SKIP_ON_ERROR) != 0;
      if (name == null || name.startsWith("<"))
         skip = true;
   }

   public Object parseResult(Parser parser, Object value, boolean skipSemanticValue) {
      // Primitive parselets which parse strings will advanced the pointer as long
      // as they are marked to consume the input.  Parent nodes will rely on the child
      // nodes to advance as appropriate.
      if (!lookahead) {
         if (value instanceof StringToken)
            parser.changeCurrentIndex(parser.getLastStartIndex() + ((StringToken) value).len);
         else if (value instanceof String)
            parser.changeCurrentIndex(parser.getLastStartIndex() + ((String) value).length());
      }
      else
         parser.changeCurrentIndex(parser.getLastStartIndex());

      return parser.parseResult(this, value, skipSemanticValue);
   }

   public boolean addResultToParent(Object node, ParentParseNode parent, int index, Parser parser) {
      // Exclude this entirely from the result
      if (getDiscard() || getLookahead())
         return false;

      if (getSkip()) {
         AbstractParseNode pn = (AbstractParseNode) node;
         if (pn.canSkip()) {
            parent.add(pn.getSkippedValue(), this, index, true, parser);
            return false;
         }
      }

      return true;
   }

   /**
    * This method replicates the functionality of addResultToParent for the special case where we are parsing errors
    * and we've reparsed the value of a node and need to replace it in it's parent before returning.  It only handles
    * a few of the samentic value mappings so far... another approach would be to throw away and rebuild the parent's value
    * entirely.   The key here is that when we update the parse node for a child, we also update the semantic value so
    * the two are consistent.
    */
   public boolean setResultOnParent(Object node, ParentParseNode parent, int index, Parser parser) {
      // Exclude this entirely from the result
      if (getDiscard() || getLookahead())
         return false;

      if (getSkip()) {
         parent.set(((ParseNode) node).value, this, index, true, parser);
         return false;
      }

      return true;
   }

   /**
    * Overridden in ChainResultSequence to do post-processing that would ordinarily be done in addResultToParent for
    * a nested sequence
    */
   public Object propagateResult(Object node) {
      return node;
   }

   protected String getPrefixSymbol() {
      if (lookahead && !negated)
         return("&");
      if (negated)
         return("!");
      if (optional && !repeat)
         return("?");
      return "";
   }

   protected String getSuffixSymbol() {
      if (repeat)
      {
         if (optional) return("*");
         else return("+");
      }
      return "";
   }

   public boolean getSkip() {
      return skip;
   }

   public boolean getDiscard() {
      return discard;
   }

   public boolean getLookahead() {
      return lookahead;
   }

   public boolean getNegated() {
      return negated;
   }

   public boolean getReportError() {
      return reportError;
   }

   public void setSemanticValueClass(Class c) {
      resultClass = c;
   }

   public Class getSemanticValueClass() {
      if (getSemanticValueIsArray())
          return ARRAY_LIST_CLASS;
      return resultClass;
   }

   public String getSemanticValueClassName() {
      return resultClassName;
   }

   public boolean getSemanticValueIsArray() {
      return repeat && !treatValueAsScalar;
   }

   public Class getSemanticValueComponentClass() {
      if (getSemanticValueIsArray())
         return resultClass;
      else
         return null;
   }

   public Object generateResult(GenerateContext ctx, Object result) {
      // Result overridden by a custom class
      if (generateParseNode != null)
         return generateParseNode;

      // When we produce a parse node result, it will have been associated with our semantic value.
      // Since this is a value result for this value, we associate the value with the parse node.
      // Are we doing this too early?  Maybe we need to wait for a complete result to be accepted so we
      // propagate the value down the tree so we do not associate the value with a partial match?
      if (result instanceof IParseNode) {
         IParseNode resultNode = (IParseNode) result;
         Object value = resultNode.getSemanticValue();
         if (value instanceof ISemanticNode)
            ((ISemanticNode) value).setParseNode((IParseNode) result);
      }
      if (GenerateContext.generateStats) {
         generatedBytes += ctx.progress(result);
      }
      return result;
   }


   public void setName(String n) {
      name = n;
   }

   public String getName() {
      return name;
   }

   public Language getLanguage() {
      return language;
   }

   public void setLanguage(Language l) {
      language = l;
   }

   public ParseError parseError(Parser parser, Parselet childParselet, String errorCode, Object... args) {
      return parseError(parser, null, null, childParselet, errorCode, args);
   }

   public ParseError parseError(Parser parser, Object partialValue, ParseError chain, Parselet childParselet, String errorCode, Object... args) {
      int endIx = chain == null ? parser.currentIndex : Math.max(chain.endIndex, parser.currentIndex);
      int startIx = parser.getLastStartIndex();
      parser.resetCurrentIndex(startIx);
      return parser.parseError(this, partialValue, childParselet, errorCode, startIx, endIx, args);
   }

   public ParseError parsePartialError(Parser parser, Object partialValue, ParseError chain, Parselet childParselet, String errorCode, Object... args) {
      ParseError error = parseError(parser, partialValue, chain, childParselet, errorCode, args);
      error.eof = true;
      return error;
   }

   public ParseError parseError(Parser parser, String errorCode, Object... args) {
      return parseError(parser, null, errorCode, args);
   }

   public ParseError parseEOFError(Parser parser, Object partialValue, String errorCode, Object... args) {
      ParseError error = parseError(parser, partialValue, null, null, errorCode, args);
      error.eof = true;
      return error;
   }

   public String toHeaderString(Map<Parselet,Integer> visited) {
      return name;
   }

   /**
    * Lets subclasses add additional acceptance criteria to a rule.  Returns an error string or null if all is ok.
    * Passed the semanticContext, an optional object the language can use for keeping state during the parse process.
    * For these languages, the startIx and endIx from the input stream are provided.
    */
   protected String accept(SemanticContext ctx, Object value, int startIx, int endIx) {
      return acceptSemanticValue(ParseUtil.nodeToSemanticValue(value));
   }

   /** Lets subclasses accept or modify the semantic value.  Returns an error string or null if all is ok. */
   protected String acceptSemanticValue(Object value) {
      return null;
   }

   public boolean getTrace() {
      return trace;
   }

   public Class getParseNodeClass() {
      return ParseNode.class;
   }


   public IParseNode newParseNode() { return newParseNode(-1);
   }

   public IParseNode newGeneratedParseNode(Object value) {
      return newParseNode();
   }

   public IParseNode newParseNode(int ix) {
      IParseNode node = (IParseNode) RTypeUtil.createInstance(getParseNodeClass());
      node.setParselet(this);
      node.setStartIndex(ix);
      return node;
   }

   public boolean needsChildren() {
      return false;
   }

   /**
    * The empty value for a base parselet is determined if we have an
    * empty string, an empty list, or a "false" boolean.  The last one
    * occurs because we turn a null value into a false and a non-null into
    * a true in the semantic model.  So if we see a false come through it
    * means there is no parsed value for that slot.
    *
    * For an empty list, there are some cases where we need to treat an empty
    */
   public boolean emptyValue(GenerateContext ctx, Object value) {
      return value == null || (PString.isString(value) && PString.toIString(value).length() == 0) ||
             (ignoreEmptyList && (value instanceof List) && ((List) value).size() == 0) ||
             (value instanceof Boolean && !((Boolean) value).booleanValue());
   }

   public Object clone() {
      try {
         return super.clone();
      }
      catch (CloneNotSupportedException exc)
      {}
      return null;
   }

   public Parselet copy() {
      return (Parselet) clone();
   }

   /**
    * The generate phase generate parse nodes in the output.  The formatting phase turns these gene
    * into string values.  The parselet steers both of these operations through its generate and fo
    */
   public void format(FormatContext ctx, IParseNode node) {
      node.format(ctx);
   }

   public void formatStyled(FormatContext ctx, IParseNode node, IStyleAdapter adapter) {
      node.formatStyled(ctx, adapter);
   }

   abstract public Object parse(Parser p);

   /** An optional method for parsing a repeating parselet.  Used in the context when we know there are errors in the file and
    * we know the parent parselet failed to parse the exit parselet in the current context.  The parselet previous to exit parselet is called
    * with this method.   It will basically reparse itself, trying to skip over error text, making sure not to skip over the exit parselet.
    * If we do match the exit parselet, we return the newly extended node.
    *
    * @return null if we cannot extend our value.  In this case, the parent parselet just uses the value it already has for this match.
    */
   public Object parseExtendedErrors(Parser p, Parselet exitParselet) {
      return null;
   }

   public boolean peek(Parser p) {
      int startIndex = p.currentIndex;
      Object value = p.parseNext(this);
      p.resetCurrentIndex(startIndex);
      return value != null && !(value instanceof ParseError);
   }

   abstract public Object generate(GenerateContext ctx, Object semanticValue);

   final static int NO_MATCH = -1;
   final static int MATCH = -2;
   // Also returns number of array elements matched for arrays

   public int updateParseNodes(Object semanticValue, IParseNode node) {
      if (semanticValue instanceof ISemanticNode) {
         ISemanticNode sv = (ISemanticNode) semanticValue;
         if (node.getParselet() == this)
            sv.setParseNode(node);
      }
      return MATCH;
   }

   boolean isNamed() {
      if (name == null)
         return false;
      return true;
   }

   /** For most parselets (all but EOF), null is not a real value.  */
   public boolean isNullValid() {
      return false;
   }

   public IParseNode[] getFormattingParseNodes(Set<Parselet> visited) {
      return null;
   }

   public boolean producesParselet(Parselet other) {
      if (other == null)
         return false;
      return (other == this) || (other.language != language && other.getName().equals(getName()));
   }

   public boolean typeMatches(Object sv, boolean isElement) {
      if (isElement)
         return elementTypeMatches(sv);
      else
         return dataTypeMatches(sv);
   }

   public boolean elementTypeMatches(Object other) {
      // Note that in the generate case, null may need to match a symbol or something but I think if there's an svclass
      // it should never match with null.
      if (other == null)
         return false;

      Class svClass = getSemanticValueComponentClass();

      return classMatchesInstance(svClass, other);
   }

   public boolean dataTypeMatches(Object other) {
      // Note that in the generate case, null may need to match a symbol or something but I think if there's an svclass
      // it should never match with null.
      if (other == null)
         return false;

      Class svClass = getSemanticValueClass();

      return classMatchesInstance(svClass, other);
   }

   private static boolean classMatchesInstance(Class svClass, Object other) {
      if (svClass == null) {
         if (PString.isString(other))
            return true;
         return false;
      }
      if (svClass == IString.class && PString.isString(other))
         return true;

      Class otherClass = other.getClass();
      // For the nested parselet, an exact match is a match.
      if (svClass == otherClass) {
         return true;
      }
      if (svClass == SemanticNodeList.class && other instanceof List)
         return true;

      if (other instanceof ISemanticWrapper && ((ISemanticWrapper) other).getWrappedClass() == svClass)
         return true;

      // If the value is a boolean, it matches null or non-null so we have to allow it.
      if (otherClass == Boolean.class)
         return true;

      return false;
   }

   boolean parseNodeMatches(Object semanticValue, IParseNode node) {
      return dataTypeMatches(semanticValue) && producesParselet(node.getParselet());
   }

   /**
    * This is a hook for subclasses to override if there is any conditionality in how
    * the slot class is used.  For example, if the slot mappings are only applied to
    * one of the children, and another child's value is forwarded directly the slot
    * mappings are never applied to the directly forwarded element.  So the type of
    * the sequence will be a superclass of the type of the slot object.
    *
    * @return the class for the semantic value for this slot.
    */
   protected Class getSemanticValueSlotClass() {
      return getSemanticValueClass();
   }

   /** From the IDE's perspective,  */
   public boolean isComplexStringType() {
      return false;
   }
}