/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.layer.Layer;
import sc.parser.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Implements the parser for the schtml format.  This specific file defines the HTML grammar, built on top of the
 * template language.  The template declarations, the text strings, are extended to include tag objects, using the
 * sc.lang.html.Element class.  When you parse an schtml file, this language produces a Template instance which has
 * Element instances as additional types of it's templateDeclarations property.  During the init process, each
 * Template object converts all Element types into StrataCode language elements.  At this point the Template is processed
 * like any other template from the Template language - converted to Java, Javascript, or interpreted.
 *
 * In general, SCHTML provides a structured subset of HTML for manageability.  It validates all tags, attributes, etc.
 * though is more strict in some cases than typical HTML.
 *
 * TODO: The parser here generates the HTML tree, matching open and close tags using a rudimentary approach to first
 * parse a tree-tag, then when that fails to parse a valid body + close tag, just to go and parse an simple tag.  Because we
 * enable caching on the key elements it's faster than it might seem at first glance, but it's still not nearly as fast as it
 * could be.   A simple performance optimization would be to pre-parse a table of </tagName patterns that we find, possibly with
 * the index where we find it.  Given that most tags are used only one way in any given file, we'd be able to skip the tree-tag
 * parsing for <br> and <p> tags, for example.
 *
 * TODO: It would be nice to have a grammar that deals with pure HTML, that's not based on the template language
 */
public class HTMLLanguage extends TemplateLanguage {
   public final static HTMLLanguage INSTANCE = new HTMLLanguage();

   /**
    * The parser lets you maintain additional parse-state so that you can match or reject any given element in the input stream.
    * This extension point is the SemanticContext.  For HTML, we need to maintain the tagStack so that we can match open and closed
    * tags and allow open tags with no close tag.
    */
   public class HTMLSemanticContext extends SemanticContext {
      public class HTMLContextEntry {
         int startIx;
         int endIx;
         int endTagIx;
         String tagName;

         public String toString() {
            return tagName + "[" + startIx + ":" + endTagIx + "]";
         }
      }
      ArrayList<HTMLContextEntry> tagStack = new ArrayList<HTMLContextEntry>();

      ArrayList<HTMLContextEntry> removedStack = new ArrayList<HTMLContextEntry>();

      void addEntry(Object semanticValue, int startIx, int endIx) {
         HTMLContextEntry ent = new HTMLContextEntry();
         ent.startIx = startIx;
         ent.endIx = endIx;
         ent.endTagIx = -1;
         ent.tagName = semanticValue.toString().toLowerCase();
         tagStack.add(ent);
      }

      public String getCurrentTagName() {
         int sz = tagStack.size();
         if (sz == 0)
            return null;
         return tagStack.get(sz-1).tagName;
      }

      public Object resetToIndex(int ix) {
         ArrayList<HTMLContextEntry> removedList = null;
         // When we are resetting the index back - behind the current pointer, we might need to remove tag stack entries.
         // Keep track of those we remove so we can restore them again if we set the index ahead again.
         for (int i = tagStack.size() - 1; i >= 0; i--) {
            if (tagStack.get(i).startIx >= ix) {
               if (removedList == null)
                  removedList = new ArrayList<HTMLContextEntry>();
               HTMLContextEntry removedEntry = tagStack.remove(i);
               removedList.add(removedEntry);

               addRemovedStackEntry(removedEntry);
            }
            else {
               break;
            }
         }
         return removedList;
      }

      public void restoreToIndex(int ix, Object retVal) {
         if (retVal != null) {
            ArrayList<HTMLContextEntry> toRestore = (ArrayList<HTMLContextEntry>) retVal;
            for (int i = 0; i < toRestore.size(); i++)
               tagStack.add(toRestore.get(i));
         }
         else {
            for (int i = removedStack.size() - 1; i >= 0 && i < removedStack.size(); i--) {
               HTMLContextEntry removedEnt = removedStack.get(i);
               if (removedEnt.startIx <= ix) {
                  // Does this tag overlap the current position?  If so, we add it back in to the current tag stack.
                  if (removedEnt.endTagIx != -1 && removedEnt.endTagIx >= ix) {
                     addTagStackEntry(removedEnt);
                  }
                  //removedStack.remove(i);
                  //i++;
               }
            }
         }
      }

      public void popTagName(int endTagIx) {
         int sz = tagStack.size();
         if (sz == 0)
            System.err.println("*** invalid pop tag!");
         else {
            HTMLContextEntry removedEnt = tagStack.remove(sz - 1);
            removedEnt.endTagIx = endTagIx;
            addRemovedStackEntry(removedEnt);
         }
      }

      private void addTagStackEntry(HTMLContextEntry toAdd) {
         int i;
         for (i = tagStack.size() - 1; i >= 0; i--) {
            HTMLContextEntry curEnt = tagStack.get(i);
            if (curEnt.startIx < toAdd.startIx) {
               break;
            }
            else if (curEnt.startIx == toAdd.startIx) {
               assert(curEnt.tagName.equals(toAdd.tagName));
               if (curEnt.endTagIx == -1)
                  curEnt.endTagIx = toAdd.endTagIx;
               return;
            }
         }
         if (i == tagStack.size() - 1)
            tagStack.add(toAdd);
         else
            tagStack.add(i + 1, toAdd);
      }

      private void addRemovedStackEntry(HTMLContextEntry removedEnt) {
         int i;
         for (i = removedStack.size() - 1; i >= 0; i--) {
            HTMLContextEntry curEnt = removedStack.get(i);
            if (curEnt.startIx < removedEnt.startIx) {
               break;
            }
            else if (curEnt.startIx == removedEnt.startIx) {
               assert(curEnt.tagName.equals(removedEnt.tagName));
               if (curEnt.endTagIx == -1)
                  curEnt.endTagIx = removedEnt.endTagIx;
               return;
            }
         }
         if (i == removedStack.size() - 1)
            removedStack.add(removedEnt);
         else
            removedStack.add(i + 1, removedEnt);
      }
   }

   /**
    * Script tags are treated separately - the bodies are not escaped so that you can embed Javascript, or Java code in them with out escaping the
    * keyword characters. Unescaped tags cannot have child tags.
    */
   private final String[] UNESCAPED_TAGS = {"script"};

   HashSet<String> UNESCAPED_SET = new HashSet<String>(Arrays.asList(UNESCAPED_TAGS));

   /** Some HTML tags imply indentation - those are matched by a separate parselet so that we can generate nicely formatted HTML from the model */
   private final String[] INDENTED_TAGS = {"html", "head", "body", "table", "tr", "td", "script"};

   public Set<String> INDENTED_SET = new HashSet<String>(Arrays.asList(INDENTED_TAGS));

   // Do not indent the children but do add a newline on the end
   private final String[] NEWLINE_TAGS = {"input", "title", "a", "link", "h1", "h2", "h3", "h4", "p"};

   public Set<String> NEWLINE_SET = new HashSet<String>(Arrays.asList(NEWLINE_TAGS));

   public HashSet getUnescapedTags() {
      return UNESCAPED_SET;
   }

   Symbol closeTagChar = new Symbol("/");
   Symbol beginTagChar = new Symbol("<");
   Symbol endTagChar = new Symbol(SKIP_ON_ERROR, ">");
   Symbol reqEndTagChar = new Symbol(">");

   public boolean validTagChar(char c) {
      return Character.isLetterOrDigit(c) || c == '-' || c == ':';
   }

   public Symbol tagNameChar = new Symbol(0, Symbol.ANYCHAR) {
      protected String accept(SemanticContext ctx, Object value, int startIx, int endIx) {
         IString str = PString.toIString(value);
         if (str == null)
            return "Tag names must be non null";
         if (str.length() == 1 && validTagChar(str.charAt(0)))
            return null;
         return "Not a valid character for the inside of a tag name";
      }
   };

   public boolean validStartTagChar(char c) {
      return Character.isLetter(c) || c == ':';
   }

   public Symbol startTagNameChar = new Symbol(0, Symbol.ANYCHAR) {
      protected String accept(SemanticContext ctx, Object value, int startIx, int endIx) {
         IString str = PString.toIString(value);
         if (str == null)
            return "Tag names must be non null";
         if (str.length() == 1 && validStartTagChar(str.charAt(0)))
            return null;
         return "Not a valid character for start of a tag name";
      }
   };

   public IndexedChoice tagSpacing = new IndexedChoice(REPEAT | OPTIONAL | NOERROR);
   {
      tagSpacing.put(" ", whiteSpace);
      tagSpacing.put("\t", whiteSpace);
      tagSpacing.put("\f", whiteSpace);
      tagSpacing.put("\r", whiteSpace);
      tagSpacing.put("\n", whiteSpace);

      tagSpacing.generateParseNode = new SpacingParseNode(tagSpacing, false);
   }

   public Sequence tagSpacingEOL = new Sequence("('')", tagSpacing) {
      {
         generateParseNode = new NewlineParseNode(" ") {
            public void format(FormatContext ctx) {
               // Only add the newline if we are indenting for this tag
               if (isIndentedTag(ctx))
                  super.format(ctx);
            }

         };
      }

      public Object getPrevTagName(FormatContext ctx) {
         Object psv = ctx.prevSemanticValue();
         int ix = 1;

         do {
            if (PString.isString(psv)) {
               if (psv.equals("/"))
                  psv = ctx.prevSemanticValue(ix++);
               else
                  break;
            }
            else if (psv instanceof SemanticNodeList) {
               psv = ctx.prevSemanticValue(ix++);
            }
            else
               break;
         } while (true);
         return psv;
      }

      public boolean isIndentedTag(FormatContext ctx) {
         Object psv = getPrevTagName(ctx);
         return PString.isString(psv) && INDENTED_SET.contains(psv.toString());
      }

      public boolean isNewlineTag(FormatContext ctx) {
         Object psv = getPrevTagName(ctx);
         return PString.isString(psv) && NEWLINE_SET.contains(psv.toString());
      }

      public void format(FormatContext ctx, IParseNode node) {
         if (isIndentedTag(ctx))
            ctx.pushIndent();

         super.format(ctx, node);
      }
   };

   /** Each parselet instance generates one type of tag from this set */
   enum TagNameMatchType {
      EscapedOpen, UnescapedOpen, CloseTag, AnyTagName, AttributeName
   }

   // This is broken out so that we can cache it efficiently between the different types of tagName sequences.
   public Sequence tagName = new Sequence("('','',)", startTagNameChar, new Sequence("('')", REPEAT | OPTIONAL, tagNameChar), tagSpacing);
   {
      tagName.cacheResults = true;
      identifier.cacheResults = true;
   }

   /**
    * This class extends the parser's core Sequence(list of parselets) class to add all of the logic necessary to
    * parse the HTML syntax.  It's used in the grammar definition for various types of HTML tags, configured
    * based on the specific type.  It overrides the accept method - used to determine whether this grammar node matches
    * an input string - to accept/reject appropriately based on the type of tag.   The key variables are whether the tag
    * needs a new line and indentation during generation, whether it's like the script tag which is unescaped, and whether
    * matching open and close tags.
    */
   public class TagNameSequence extends Sequence {
      TagNameMatchType matchType;
      HashSet<String> unescapedTags;
      public TagNameSequence(TagNameMatchType mt) {
         super("('')", tagName);
         matchType = mt;
         styleName = "keyword";
      }
      public void start() {
         super.start();
         unescapedTags = ((HTMLLanguage) getLanguage()).getUnescapedTags();
      }
      protected String accept(SemanticContext ctx, Object value, int startIx, int endIx) {
         String res = super.accept(ctx, value, startIx, endIx);
         if (res != null)
            return res;

         if (value instanceof IParseNode)
            value = ((IParseNode) value).getSemanticValue();

         if (value == null)
            return "No value for tag name";

         String strValue = value.toString();

         switch (matchType) {
            case AnyTagName:
               break;
            case UnescapedOpen:
               if (!unescapedTags.contains(strValue))
                  return "Tag name is not unescaped";
               break;
            case EscapedOpen:
               if (unescapedTags.contains(strValue))
                  return "Tag name is unescaped";
               break;
            case CloseTag:

               // Currently not doing the tag name stack during generate
               if (startIx == -1)
                  return null;

               HTMLSemanticContext hctx = ((HTMLSemanticContext) ctx);
               String openTagName = hctx.getCurrentTagName();
               if (openTagName == null)
                  return "No open tag for close tag: " + strValue;
               if (!openTagName.equalsIgnoreCase(strValue))
                  return "Mismatching close tag name: " + value + " does not match open: " + openTagName;
               hctx.popTagName(startIx);
               break;
         }

         // This is called for both parsing and generation.  We are not doing the tag name stack during the generate
         if (startIx != -1 && (matchType == TagNameMatchType.UnescapedOpen || matchType == TagNameMatchType.EscapedOpen))
            ((HTMLSemanticContext) ctx).addEntry(value, startIx, endIx);
         return null;
      }
   }

   /** Matches tags which have matching open and close tags */
   public Sequence treeTagName = new TagNameSequence(TagNameMatchType.EscapedOpen);
   /** Matches the script tag which suppresses escaping of less than and greater than signs */
   public Sequence unescapedTreeTagName = new TagNameSequence(TagNameMatchType.UnescapedOpen);
   /** Matches any tag - as a cleanup when no other parselets match */
   public Sequence anyTagName = new TagNameSequence(TagNameMatchType.AnyTagName);

   public Sequence attrName = new TagNameSequence(TagNameMatchType.AttributeName);

   public Sequence closeTagName = new TagNameSequence(TagNameMatchType.CloseTag);

   public Sequence attExpression = new Sequence("AttrExpr(op,expr)", new SymbolChoiceSpace(":=:", ":=", "=:", "="), expression);

   public OrderedChoice attributeValueString = new OrderedChoice(templateExpression, attExpression, escapedString);
   public OrderedChoice attributeValueSingleQuoteString = new OrderedChoice(templateExpression, attExpression, escapedSingleQuoteString);

   public Sequence attributeValueLiteral = new Sequence("(,.,)", doubleQuote, attributeValueString, doubleQuote);
   public Sequence attributeValueSQLiteral = new Sequence("(,.,)", singleQuote, attributeValueSingleQuoteString, singleQuote);
   {
      // Handles the case where we have: value=":= foo."
      attributeValueLiteral.skipOnErrorSlot = 2;
      attributeValueSQLiteral.skipOnErrorSlot = 2;
   }

   Parselet attributeValue =  new OrderedChoice(attributeValueLiteral, attributeValueSQLiteral);

   public Sequence tagAttributeValue = new Sequence ("(,value)", OPTIONAL, equalSign, attributeValue);
   public Sequence tagAttribute = new Sequence("Attr(name, *,)", 0, anyTagName, tagAttributeValue, tagSpacing);
   public Sequence tagAttributes = new Sequence("([])", OPTIONAL | REPEAT, tagAttribute);
   {
      // Here we are skipping any incomplete attributes (e.g. id=) till we hit the end of close tag or the start of the next tag
      // It's important that we do not consume part or all of the next tag in the body of this tag if for some reason we decide to put this back in.
      tagAttributes.skipOnErrorParselet = createSkipOnErrorParselet("<tagAttributesError>", "/", "<", ">", Symbol.EOF);

      // We used to set the cacheResults on the tagAttributes but that means we call parseExtendedErrors on it because of the skipOnErrorParselet.  That conflicts
      // with the fact that we are caching primary which is a child of tagAttribute.  The parseExtendedErrors does not get the cached value and so reparses the entire
      // thing, causing more work and the second reparse can get cached primaries and update the parentNode to point to a part of the model that gets discarded when
      // the parseExtendedErrors fails to produce a better result.   By setting it on tagAttribute, we get the caching in the parseExtendedErrors and avoid the parentNode.  see re59
      tagAttribute.cacheResults = true;
   }
   // TODO: how do we deal with appending newlines after the start tag?  Used to having tagSpacingEOL here but that ate up the space in the content.  Need features of tagSpacingEOL perhaps when processing the endTagChar?
   Sequence simpleTag = new Sequence("Element(,tagName,attributeList,selfClose,)", beginTagChar, anyTagName, tagAttributes, new Sequence("('')", OPTIONAL, closeTagChar), endTagChar);
   {
      simpleTag.enableTagMode = true;
      // If an error occurs after we parse the name we can skip it (enablePartialValues only)
      simpleTag.skipOnErrorSlot = 2;
      // Don't match just the < for a partial value match
      simpleTag.minContentSlot = 1;
   }
   Sequence closeTag = new Sequence("(,,'',)", beginTagChar, closeTagChar, closeTagName, endTagChar);
   {
      closeTag.skipOnErrorSlot = 3;
   }

   public class TagStartSequence extends Sequence {
      public TagStartSequence(Parselet tagName, Parselet tagBody) {
         // Using reqEndTagChar here so we do not accept a partial match which ends with /> - that and partial value tags should just be treated
         // as simpleTags - not going to try and detect the tree of a partial tag.
         super("Element(,tagName,attributeList,,children,closeTagName)", beginTagChar, tagName, tagAttributes, reqEndTagChar, tagBody, closeTag);
         enableTagMode = true;
         // Do not consider a match of just the beginTagChar as content when doing partial values extension.
         // There are other parselets that will match that character
         // Need to match the full <tag attributes> so that we do not match <tag attributes/> in partialValues mode.  We do not want to parse the body of a treeTag when we have a simpleTag definition.
         minContentSlot = 3;
      }
   }

   SymbolChoice unescapedTemplateString = (SymbolChoice) templateString.copy();
   {
      unescapedTemplateString = (SymbolChoice) templateString.clone();
      // TODO: Need to put in an exception for the close tag (e.g. </script> but maybe this is too general?   Could add an entry for each unescaped tag but supposed to be case insensitive
      unescapedTemplateString.add("</");
   }


   // Like the regular template body declarations, the script tag can have template expressions, statements, etc. but not sub-tags
   OrderedChoice unescapedBodyDeclarations = new OrderedChoice("([],[],[],[],[])", OPTIONAL | REPEAT, tagComment, templateExpression, templateDeclaration, templateStatement, unescapedTemplateString);

   // script tag and any others which do not allow sub-tags
   Sequence unescapedTreeTag = new TagStartSequence(unescapedTreeTagName, unescapedBodyDeclarations);
   Sequence treeTag = new TagStartSequence(treeTagName, templateBodyDeclarations);

   SymbolSpace controlStart = new SymbolSpace("<!");

   // Parses <!DOCTYPE html>
   Sequence controlTag = new Sequence("ControlTag(,docTypeName,docTypeValue,)", controlStart, anyTagName, anyTagName, endTagChar);

   OrderedChoice anyTag = new OrderedChoice(treeTag, unescapedTreeTag, simpleTag, controlTag) {
      /** For a performance tuning, reject this match quickly if there's no < sign. */
      public Object parse(Parser parser) {
         if (parser.peekInputChar(0) != '<')
            return parseError(parser, this, "Tag must start with <");
         else
            return super.parse(parser);
      }
   };
   {
      // The Tag language does a lot of re-parsing tags strings, etc. due to the nested nature of the grammar - especially when
      // you do not have a matching close tag.  Just by caching the results though, this re-parsing is much faster.
      templateStatement.cacheResults = true;
      templateExpression.cacheResults = true;
      templateDeclaration.cacheResults = true;
      templateString.cacheResults = true;
      tagComment.cacheResults = true;
      anyTag.cacheResults = true;
      // A simpleTag can turn into a treeTag due to changes outside of the simpleTag's parsed boundary so we cannot use
      // the results of this parselet during the reparse operation.
      anyTag.reparseable = false;
      unescapedTemplateString.cacheResults = true;

      templateString.add("<");
      templateString.add(">");

      // Insert the Tag language specific declarations - in this case, the only new thing we are parsing is a tag
      templateBodyDeclarations.setName("([],[],[],[],[],[])");
      templateBodyDeclarations.put("<", anyTag);

      simpleTemplateDeclarations.setName("([],[],[])");
      simpleTemplateDeclarations.add(1, anyTag);
   }

   public HTMLLanguage() {
      this(null);
   }

   public HTMLLanguage(Layer layer) {
      super(layer);
      setStartParselet(template);
      addToSemanticValueClassPath("sc.lang.html");
      languageName = "SCHtml";
      defaultExtension = "schtml";
   }

   public static HTMLLanguage getHTMLLanguage() {
      return INSTANCE;
   }

   /**
    * Hook for languages to store and manage state used to help guide the parsing process.  For example, in HTML
    * keeping track of the current tag name stack so the grammar can properly assemble the tag-tree.
    */
   public SemanticContext newSemanticContext(Parselet parselet, Object semanticValue) {
      return new HTMLSemanticContext();
   }

   /** This method in the Element class is used to escape the body so no HTML characters leak out from the application */
   public String escapeBodyMethod() {
      return "escBody";
   }
}
