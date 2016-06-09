/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.util.IntStack;
import sc.util.PerfMon;

import java.util.ArrayList;
import java.util.List;

/**
 * A NestedParselet which represents a choice of parselets.  You provide the list of child parselets and it matches them
 * in order until one matches.  Tha matching parselet's semantic value is used as the semantic value for the choice if
 * it is a scalar.  For an array ordered choice, it can successively match children and produce an array of its children.
 */
public class OrderedChoice extends NestedParselet  {
   public OrderedChoice() { super(); }

   public OrderedChoice(int options) {
      this(null, options);
   }

   public OrderedChoice(String id, int options) {
      super(id, options);
   }

   public OrderedChoice(Parselet... toAdd) {
      super(toAdd);
   }

   public OrderedChoice(String id, Parselet... toAdd) {
      super(id, toAdd);
   }

   public OrderedChoice(String id, int options, Parselet... toAdd) {
      super(id, options, toAdd);
   }

   public OrderedChoice(int options, Parselet... toAdd) {
      this(null, options, toAdd);
   }

   public String getSeparatorSymbol() {
      return " / ";
   }

   public Class getSemanticValueClass() {
      if (!initialized)
         init();
      
      Class result = null;
      int i = 0;

      if (!skip) // ?? is this right?   Not a common case to have a class name on a choice...
          return super.getSemanticValueClass();

      if (repeat) {
         // This is one long string, not an array of strings
         if (parameterType == ParameterType.STRING)
            return IString.class;
         return ARRAY_LIST_CLASS;
      }

      boolean firstValue = true;
      boolean unresolved = false;
      Class oldResult = null;

      // Sentinel - this must be a recursive definition so return no type
      if (resultClass == UNDEFINED_CLASS)
         return null;

      // Either explicitly set or cached value.
      if (resultClass != null)
          return resultClass;

      resultClass = UNDEFINED_CLASS;

      if (trace)
         System.out.println("Trace: choice parselet resolving semantic value class: " + this);

      for (Parselet p:parselets) {
         // Don't consider classes which are nulled out anyway
         if (skipSemanticValue(i))
             continue;
         p.setLanguage(getLanguage());
         Class newResult = p.getSemanticValueClass();

         if (firstValue)
         {
            unresolved = newResult == null;
            firstValue = false;
            oldResult = newResult;
         }
         else if (unresolved != (newResult == null) && oldResult != null && newResult != null && oldResult != IString.class && newResult != IString.class)
            System.err.println("*** Semantic value types of choices have no common base classes: " + oldResult + " and " + newResult + " for definition: " + this);


         Object origResult = result;

         result = findCommonSuperClass(result, newResult, i);

         i++;
      }

      if (trace)
         System.out.println("result: " + result);

      // Don't set this till we have a type from all parselets as some may still be unresolved.
      if (!unresolved)
         resultClass = result;

      return result;
   }

   public Class getSemanticValueComponentClass() {
      if (!initialized)
          init();

      Class result = null;
      int i = 0;

      if (!skip)
         return super.getSemanticValueComponentClass();

      boolean firstValue = true;
      boolean unresolved = false;
      Class oldResult = null;

      if (resultComponentClass == UNDEFINED_CLASS)
         return null;

      if (resultComponentClass != null)
          return resultComponentClass;

      //resultComponentClass = UNDEFINED_CLASS;

      if (trace)
         System.out.println("Trace: choice parselet resolving semantic value component class: " + this);

      for (Parselet p:parselets) {
         // Don't consider classes which are nulled out anyway
         if (skipSemanticValue(i)) {
            i++;
            continue;
         }
         p.setLanguage(getLanguage());
         Class newResult = repeat ? p.getSemanticValueClass() : p.getSemanticValueComponentClass();

         if (newResult == null)
            unresolved = true;

         if (firstValue)
         {
            firstValue = false;
            oldResult = newResult;
         }
         else if (unresolved != (newResult == null) && oldResult != null && newResult != null && oldResult != IString.class && newResult != IString.class)
            System.err.println("*** Semantic value component types of choices have no common base classes: " + oldResult + " and " + newResult + " for definition: " + this);

         if (result == null)
            result = newResult;
         else {
            oldResult = result;
            result = findCommonSuperClass(result, newResult, i);
            if (result == null)
               result = Object.class;
         }

         i++;
      }


      if (!unresolved) {
         if (trace && resultComponentClass != result)
            System.out.println("   " + resultComponentClass + " -> " + result);

         resultComponentClass = result;
      }
      
      return result;
   }

   public void setSemanticValueClass(Class c) {
      super.setSemanticValueClass(c);
      int i = 0;
      for (Parselet p:parselets) {
         if (!skipSemanticValue(i++))
            p.setSemanticValueClass(c);
      }
   }

   public Object parse(Parser parser) {
      if (trace && parser.enablePartialValues)
         System.out.println("*** tracing parse of ordered choice");

      if (repeat)
         return parseRepeatingChoice(parser);

      int startIndex = parser.currentIndex;

      List<Parselet> matchingParselets = getMatchingParselets(parser);
      ParseError bestError = null;

      int numMatches = matchingParselets.size();
      for (int i = 0; i < numMatches; i++) {
         Parselet subParselet = matchingParselets.get(i);
         Object nestedValue = parser.parseNext(subParselet);
         if (!(nestedValue instanceof ParseError)) {
            // Do any parselet specific processing on the sub-value that would be done in addResultToParent
            nestedValue = subParselet.propagateResult(nestedValue);
            if (lookahead) {
               // Reset back to the beginning of the sequence
               parser.changeCurrentIndex(startIndex);
            }

            // IndexedChoice returns a special type which lets us get the position of the match in the list so
            // we can process the semantic value properly.   OrderedChoie will just return i since in that case
            // we go through all parselets.
            int slotIx = getSlotIndex(matchingParselets, i);
            return parseResult(parser, nestedValue, skipSemanticValue(slotIx));
         }
         else {
            if (parser.semanticContext != null) {
               parser.semanticContext.resetToIndex(parser.lastStartIndex);
            }
            if (parser.enablePartialValues) {
               ParseError error = (ParseError) nestedValue;
               // Use replace=false for isBetterError because we want the first error which matches the longest text for the partial errors thing
               if (bestError == null || Parser.isBetterError(bestError.startIndex, bestError.endIndex, error.startIndex, error.endIndex, false))
                  bestError = error;
            }
         }
      }
      if (optional) {
         parser.changeCurrentIndex(startIndex);

         if (parser.enablePartialValues && bestError != null && bestError.partialValue != null) {
            bestError.optionalContinuation = true;
            return bestError;
         }
         return null;
      }
      if (bestError != null)
         return bestError;
      return parseError(parser, "Expecting one of: {0}", this);
   }

   public Object reparse(Parser parser, Object oldParseNode, DiffContext dctx, boolean forceReparse, Parselet exitParselet) {
      if (trace && parser.enablePartialValues)
         System.out.println("*** tracing parse of ordered choice");

      if (!anyReparseChanges(parser, oldParseNode, dctx, forceReparse)) {
         advancePointer(parser, oldParseNode, dctx);
         parser.reparseSkippedCt++;
         return oldParseNode;
      }

      parser.reparseCt++;

      if (repeat) {
         Object res = reparseRepeatingChoice(parser, exitParselet, oldParseNode, dctx, forceReparse, exitParselet != null, false);
         checkForSameAgainRegion(parser, oldParseNode, dctx, res == null || res instanceof ParseError, forceReparse);
         return res;
      }

      int startIndex = parser.currentIndex;

      List<Parselet> matchingParselets = getMatchingParselets(parser);
      ParseError bestError = null;

      Object oldChildNode;

      // The scalar Ordered choice may just propagate it's value or it may wrap it in a ParseNode
      if (oldParseNode instanceof ParseNode) {
         ParseNode oldParent = (ParseNode) oldParseNode;
         oldChildNode = oldParent.value;
      }
      else {
         oldChildNode = oldParseNode;
      }

      if (dctx.changedRegion) {
         //oldChildNode = null;
         forceReparse = true;
      }

      Parselet oldChildParselet = !(oldChildNode instanceof IParseNode) ? null : ((IParseNode) oldChildNode).getParselet();

      int numMatches = matchingParselets.size();
      for (int i = 0; i < numMatches; i++) {
         Parselet subParselet = matchingParselets.get(i);

         boolean nestedChildReparse = false;

         //Object nextChildNode = oldChildNode;

         // If there's an oldChildParselet we want to first process the old child parselet so skip to that guy.  If we have already tried the old parselet and it does not match we go back to the
         // beginning and try everything except the one we already tried.
         if (oldChildParselet != null && !subParselet.producesParselet(oldChildParselet)) {
            nestedChildReparse = true;
            //nextChildNode = null;
         }

         Object nestedValue = parser.reparseNext(subParselet, oldChildNode, dctx, forceReparse || nestedChildReparse, null);
         if (!(nestedValue instanceof ParseError)) {
            // For the oldChildNode, we will have already got the propagated value so don't duplicate that.
            // If this particular chain result sequence parses differently, we should not be using an oldParseNode at all.
            if (nestedValue != oldChildNode) {
               // Do any parselet specific processing on the sub-value that would be done in addResultToParent
               nestedValue = subParselet.propagateResult(nestedValue);
            }
            if (lookahead) {
               // Reset back to the beginning of the sequence
               dctx.changeCurrentIndex(parser, startIndex);
            }

            // IndexedChoice returns a special type which lets us get the position of the match in the list so
            // we can process the semantic value properly.   OrderedChoie will just return i since in that case
            // we go through all parselets.
            int slotIx = getSlotIndex(matchingParselets, i);

            checkForSameAgainRegion(parser, oldChildNode, dctx, nestedValue == null, forceReparse || nestedChildReparse);

            return parseResult(parser, nestedValue, skipSemanticValue(slotIx));
         }
         else {
            if (parser.semanticContext != null) {
               parser.semanticContext.resetToIndex(parser.lastStartIndex);
            }
            if (parser.enablePartialValues) {
               ParseError error = (ParseError) nestedValue;
               // Use replace=false for isBetterError because we want the first error which matches the longest text for the partial errors thing
               if (bestError == null || Parser.isBetterError(bestError.startIndex, bestError.endIndex, error.startIndex, error.endIndex, false)) {
                  bestError = error;
                  if (bestError.partialValue != null && bestError.partialValue != oldParseNode) {
                     bestError = bestError.propagatePartialValue(subParselet.propagateResult(bestError.partialValue));
                  }
               }
            }
         }
      }
      if (optional) {
         dctx.changeCurrentIndex(parser, startIndex);

         if (parser.enablePartialValues && bestError != null && bestError.partialValue != null) {
            bestError.optionalContinuation = true;
            return bestError;
         }
         return null;
      }

      checkForSameAgainRegion(parser, oldChildNode, dctx, true, forceReparse);

      if (bestError != null) {
         return bestError;
      }
      return parseError(parser, "Expecting one of: {0}", this);
   }

   public Object parseRepeatingChoice(Parser parser) {
      int startIndex = parser.currentIndex;
      int lastMatchStart;
      ParentParseNode value = null;
      boolean matched;
      ParseError bestError = null;
      int bestErrorSlotIx = -1;

      if (trace)
         trace = trace;

      boolean emptyMatch = false;

      do {
         matched = false;
         lastMatchStart = parser.currentIndex;

         List<Parselet> matchingParselets = getMatchingParselets(parser);

         int numMatches = matchingParselets.size();
         for (int i = 0; i < numMatches; i++) {
            Parselet matchedParselet = matchingParselets.get(i);
            Object nestedValue = parser.parseNext(matchedParselet);
            emptyMatch = nestedValue == null;
            if (!(nestedValue instanceof ParseError)) {
               if (value == null)
                  value = (ParentParseNode) newParseNode(lastMatchStart);

               if (nestedValue != null || parser.peekInputChar(0) != '\0') {
                  int slotIx = getSlotIndex(matchingParselets, i);
                  value.add(nestedValue, matchedParselet, -1, slotIx, false, parser);

                  matched = true;
                  break;
               }
            }
            else if (parser.enablePartialValues) {
               ParseError error = (ParseError) nestedValue;
               if (bestError == null || bestError.endIndex < error.endIndex) {
                  bestError = error;
                  bestErrorSlotIx = matchingParselets instanceof MatchResult ? ((MatchResult) matchingParselets).slotIndexes.get(i) : i;
               }
            }
         }
      } while (matched && !emptyMatch);

      if (value == null) {
         if (optional) {
            // TODO: this rule will technically break the parse in some weird situations since an optional choice should return null
            // even if there's an error in which there's some semantic value present.  Adding back the "eof" test since that seems like a safer bet
            if (parser.enablePartialValues && bestError != null && bestError.partialValue != null && bestError.eof) {
               return parsePartialErrorValue(parser, bestError, lastMatchStart, bestErrorSlotIx);
            }
            parser.changeCurrentIndex(startIndex);

            // If we are a repeat optional choice with mappings of '' and we match no elements, we should return an empty string, not null.
            if (!lookahead && repeat && isStringParameterMapping() && parser.peekInputChar(0) != '\0') {
               return parseResult(parser, "", false);
            }
            return null;
         }
         if (bestError != null) {
            if (parser.enablePartialValues && bestError.partialValue != null) {
               return parsePartialErrorValue(parser, bestError, lastMatchStart, bestErrorSlotIx);
            }
            // NOTE: In this case, we are possibly returning the error from a scalar parselet for a repeating one.  It won't matter unless we have partial values enabled and partialValue
            return bestError;
         }
         return parseError(parser, "Expecting one or more of: {0}", this);
      }
      else {
         // If we are doing the partial values case, we might have partially matched one more statement.  if so, this is part of the partial results
         if (parser.enablePartialValues && bestError != null && /*bestError.eof && */ bestError.partialValue != null && bestError.startIndex == lastMatchStart) {
            value.add(bestError.partialValue, bestError.parselet, -1, bestErrorSlotIx, false, parser);

            return parsePartialError(parser, value, bestError, bestError.parselet, bestError.errorCode, bestError.errorArgs);
         }
         if (lookahead)
            parser.changeCurrentIndex(startIndex);
         else
            parser.changeCurrentIndex(lastMatchStart);
         return parseResult(parser, value, false);
      }
   }

   public Object reparseRepeatingChoice(Parser parser, Parselet exitParselet, Object oldParseNode, DiffContext dctx, boolean forceReparse, boolean extendErrors, boolean extendedErrorOnly) {
      if (trace)
         System.out.println("*** reparse traced repeating choice");

      if (extendErrors && skipOnErrorParselet == null)
         return null;

      int startIndex = parser.currentIndex;
      int lastMatchStart;
      ParentParseNode value = null;
      boolean matched;
      ParseError bestError;
      int bestErrorSlotIx = -1;

      ParentParseNode oldParent = oldParseNode instanceof ParentParseNode ? (ParentParseNode) oldParseNode : null;
      if (oldParent != null && !producesParselet(oldParent.getParselet()))
         oldParent = null;

      boolean emptyMatch = false;

      int newChildCount = 0;
      int svCount = 0;

      int oldLen = oldParseNode == null ? -1 : ((CharSequence) oldParseNode).length();

      do {
         matched = false;
         lastMatchStart = parser.currentIndex;

         List<Parselet> matchingParselets = getMatchingParselets(parser);

         // Since we've parsed on from this point, need to clear out the last error
         bestError = null;

         int numMatches = matchingParselets.size();
         for (int i = 0; i < numMatches; i++) {
            Parselet matchedParselet = matchingParselets.get(i);
            int slotIx = getSlotIndex(matchingParselets, i);
            Object oldChildParseNode;
            if (oldParent == null || oldParent.children.size() <= newChildCount) {
               oldChildParseNode = null;
               forceReparse = true;
            }
            else {
               // Before we start parsing, we might need to skip some previously parsed content so we end up with the proper old node
               oldParent.clearParsedOldNodes(parser, newChildCount, dctx);
               if (oldParent.children.size() > newChildCount) {
                  oldChildParseNode = oldParent.children.get(newChildCount);
               }
               else {
                  oldChildParseNode = null;
                  forceReparse = true;
               }
            }

            Object nextChildParseNode = oldChildParseNode;
            boolean nextChildReparse = false;
            // We may be taking a different path than the last time? e.g. intIntelliJIdeazz to int - now we match a different parselet
            // TODO: should we try the oldChildParseNode's parselet first here?   I don't think we can safely do that because it could parse incorrectly
            if (oldChildParseNode != null) {
               if (!(oldChildParseNode instanceof IParseNode)) {
                  nextChildParseNode = null;
                  nextChildReparse = true;
               }
               else {
                  IParseNode oldChildNode = (IParseNode) oldChildParseNode;
                  Parselet oldParselet = oldChildNode.getParselet();
                  int origStart = oldChildNode.getOrigStartIndex();
                  if (origStart + dctx.getNewOffsetForOldPos(origStart) != parser.currentIndex) {
                     nextChildParseNode = null;
                     nextChildReparse = true;
                  }
                  else if (!matchedParselet.producesParselet(oldParselet)) {
                     // TODO: should we do this if the matchedParselets does not contain a parselet which produces ths node's parselet?  Otherwise, it seems like
                     // here we want to force a reparse of this child only until we hit the matched parselet.
                     // TODO: this is too broad a test.  When matchedparslets contains the right parselet we should not mark a change here and perhaps parse that guy first?
                     //if (!dctx.changedRegion) {
                     //   System.out.println("***");
                     //   dctx.setChangedRegion(parser, true);
                     //}
                     nextChildParseNode = null;
                     nextChildReparse = true;
                  }
               }
            }

            int oldChildLen = oldChildParseNode != null ? ((CharSequence) oldChildParseNode).length() : -1;

            // This is here to override the case where the "beforeFirstNode" is a parent of some large sequence which eventually is the same.
            // TODO - should we check that the nextChildParseNode starts at the right spot here as well?
            if (nextChildParseNode != null && !nextChildReparse && forceReparse && dctx.sameAgain && !dctx.changedRegion) {
               forceReparse = false;
            }

            // If the bestError is longer than the success result, we are going to choose the best error.  We might parse "String" as an identifier expression when the best error
            // is "String foo =" for example
            Object nestedValue = parser.reparseNext(matchedParselet, nextChildParseNode, dctx, forceReparse || nextChildReparse, null);
            if (!(nestedValue instanceof ParseError) && bestError != null && bestError.partialValue != null && ParseUtil.toLength(bestError.partialValue) > ParseUtil.toLength(nestedValue)) {
               continue;
            }
            emptyMatch = nestedValue == null;
            if (!(nestedValue instanceof ParseError)) {
               if (value == null) {
                  value = resetOldParseNode(oldParent, lastMatchStart, true, false);
               }

               if (nestedValue != null || parser.peekInputChar(0) != '\0') {
                  int oldSize = value.children == null ? 0 : value.children.size();
                  if (value.addForReparse(nestedValue, matchedParselet, svCount, newChildCount++, slotIx, false, parser, nextChildParseNode, dctx, true, true))
                     svCount++;

                  // TODO: we should have addForReparse take a ReparseStatus object with two values - hasSemanticValue and replaced.  For now, just using the size to figure that out
                  int newSize = value.children == null ? 0 : value.children.size();
                  boolean replaced = newSize == oldSize;

                  // Make sure we really replaced the old parse node - somtimes we add to it.  Also
                  // do not adjust children for parselets which compress their parse-node down to a string (e.g. spacing) for the logic in ParentParseNode.add(..)
                  /*
                  if (replaced && (!matchedParselet.skip || needsChildren())) {
                     int newChildLen = nestedValue == null ? 0 : ((CharSequence) nestedValue).length();
                     // If we parsed a child and did not parse text which we did parse on the previous parse for this parse-node, we need to increase the range of the "changed region" to include
                     // the text we did not parse this time around.
                     int diffLen = oldChildLen - newChildLen;
                     if (newChildLen > 0 && startIndex + newChildLen > dctx.endParseChangeNewOffset && dctx.endParseChangeNewOffset > dctx.endChangeOldOffset) {
                        dctx.endParseChangeNewOffset = startIndex + newChildLen + dctx.getDiffOffset();
                     }
                  }
                  */
                  matched = true;
                  break;
               }
            }
            else if (parser.enablePartialValues) {
               ParseError error = (ParseError) nestedValue;
               if (bestError == null || bestError.endIndex < error.endIndex) {
                  bestError = error;
                  bestErrorSlotIx = matchingParselets instanceof MatchResult ? ((MatchResult) matchingParselets).slotIndexes.get(i) : i;
               }
            }

         }
         if (extendErrors && (!matched || emptyMatch)) {
            if (trace)
               System.out.println("*** tracing ordered choice repeat error");
            // Keep applying the skipOnErrorParselet until we hit the exit parselet
            if (!exitParselet.peek(parser)) {
               int errorStart = parser.currentIndex;

               Object oldChildParseNode;
               if (oldParent == null || oldParent.children.size() <= newChildCount) {
                  oldChildParseNode = null;
                  forceReparse = true;
               }
               else {
                  // Before we start parsing, we might need to skip some previously parsed content so we end up with the proper old node
                  oldParent.clearParsedOldNodes(parser, newChildCount, dctx);
                  if (oldParent.children.size() > newChildCount) {
                     oldChildParseNode = oldParent.children.get(newChildCount);
                  }
                  else {
                     oldChildParseNode = null;
                     forceReparse = true;
                  }
               }

               Object nextChildParseNode = oldChildParseNode;
               boolean nextChildReparse = false;
               // We may be taking a different path than the last time? e.g. intIntelliJIdeazz to int - now we match a different parselet
               // TODO: should we try the oldChildParseNode's parselet first here?   I don't think we can safely do that because it could parse incorrectly
               if (oldChildParseNode != null && (!(oldChildParseNode instanceof IParseNode) || ((IParseNode) oldChildParseNode).getParselet() != skipOnErrorParselet)) {
                  if (!(oldChildParseNode instanceof IParseNode)) {
                     nextChildParseNode = null;
                     nextChildReparse = true;
                  }
                  else {
                     IParseNode oldChildNode = (IParseNode) oldChildParseNode;
                     Parselet oldParselet = oldChildNode.getParselet();
                     if (!skipOnErrorParselet.producesParselet(oldParselet)) {
                        // TODO: should we do this if the matchedParselets does not contain a parselet which produces ths node's parselet?  Otherwise, it seems like
                        // here we want to force a reparse of this child only until we hit the matched parselet.
                        // TODO: this is too broad a test.  When matchedparslets contains the right parselet we should not mark a change here and perhaps parse that guy first?
                        //if (!dctx.changedRegion)
                        //   dctx.setChangedRegion(parser, true);
                        nextChildParseNode = null;
                        nextChildReparse = true;
                     }
                  }
               }
               // This will consume whatever it is that we can't parse until we get to the next statement.  We have to be careful with the
               // design of the skipOnErrorParselet so that it leaves us in the state for the next match on this choice.  It should not breakup
               // an identifier for example.
               Object errorRes = parser.reparseNext(skipOnErrorParselet, nextChildParseNode, dctx, forceReparse || nextChildReparse, null);
               if (errorRes instanceof ParseError) {
                  // We never found the exitParselet - i.e. the } so the parent sequence will fail just like it did before.
                  // When this method returns null, we go with the result we produced in the normal parseRepeatingChoice method.
                  if (extendedErrorOnly) {
                     dctx.changeCurrentIndex(parser, startIndex);
                     return null;
                  }
                  else
                     return value;
               }

               boolean useError = true;
               if (errorRes instanceof IParseNode) {
                  IParseNode errorNode = (IParseNode) errorRes;
                  // If one of the partial value errors is better we can start the skip parse from that error preserving more of the model
                  if (bestError != null && errorNode.length() < bestError.endIndex - bestError.startIndex && bestError.partialValue != null) {
                     value = value == null ? resetOldParseNode(oldParent, bestError.startIndex, false, false) : value;

                     // Change the endIndex before we add the value because we use parser.currentIndex inside of value.addForReparse to determine how to cull old nodes.
                     dctx.changeCurrentIndex(parser, bestError.endIndex);

                     if (value.addForReparse(bestError.partialValue, bestError.parselet, svCount, newChildCount++, bestErrorSlotIx, false, parser, nextChildParseNode, dctx, true, true))
                        svCount++;
                     errorStart = bestError.endIndex;

                     // It turns out the partial value error will match better than the skipOnError parselet.  We need to insert an error representing the
                     // fact that it's not a complete statement.
                     if (exitParselet.peek(parser)) {
                        if (value.addForReparse(new ErrorParseNode(new ParseError(bestError.parselet, bestError.errorCode, bestError.errorArgs, bestError.endIndex, bestError.endIndex), ""), 
                                                bestError.parselet, svCount, newChildCount++, bestErrorSlotIx, true, parser, nextChildParseNode, dctx, true, true))
                           svCount++;
                        return value;
                     }

                     errorRes = parser.reparseNext(skipOnErrorParselet, nextChildParseNode, dctx, forceReparse || nextChildReparse, null);
                     if (errorRes instanceof ParseError)
                        return value;
                  }
               }

               if (useError) {
                  if (value == null)
                     value = resetOldParseNode(oldParent, lastMatchStart, false, false);
                  if (value.addForReparse(new ErrorParseNode(new ParseError(skipOnErrorParselet, "Expected {0}", new Object[]{this}, errorStart, parser.currentIndex),
                                          errorRes.toString()), skipOnErrorParselet, svCount, newChildCount++, bestErrorSlotIx, true, parser, nextChildParseNode, dctx, true, true))
                     svCount++;
                  matched = true;
                  emptyMatch = false;
               }

               bestError = null;
            }
         }
      } while (matched && !emptyMatch);

      if (value == null) {
         if (optional) {
            if (parser.enablePartialValues && bestError != null && bestError.partialValue != null && bestError.eof) {
               Object oldChildParseNode = oldParent == null || oldParent.children.size() <= newChildCount ? null : oldParent.children.get(newChildCount);
               return reparsePartialErrorValue(parser, bestError, lastMatchStart, newChildCount, bestErrorSlotIx, oldChildParseNode, dctx);
            }
            dctx.changeCurrentIndex(parser, startIndex);

            // If we are producing a smaller result than we did in the previous result, and we are at the end of the
            // changes - so that the old result was at least partially in the "same again" region, make sure we extend
            // the changes so we reparse those old characters we stripped off again (test/re5)
            /*
            if (!lookahead && oldLen > 0 && dctx.changedRegion) {
               // TODO: change oldLen here to oldLen when diffLen > 0 and oldLen + 1? otherwise
               if (startIndex + oldLen > dctx.endParseChangeNewOffset && dctx.endParseChangeNewOffset > dctx.endChangeOldOffset) {
                  //   dctx.endParseChangeNewOffset = startIndex + oldLen;
                  System.out.println("***");
               }
            }
            */

            // If we are a repeat optional choice with mappings of '' and we match no elements, we should return an empty string, not null.
            if (!lookahead && repeat && isStringParameterMapping() && parser.peekInputChar(0) != '\0') {
               return parseResult(parser, "", false);
            }
            return null;
         }
         if (bestError != null) {
            if (parser.enablePartialValues && bestError.partialValue != null) {
               Object oldChildParseNode = oldParent == null || oldParent.children.size() <= newChildCount ? null : oldParent.children.get(newChildCount);
               return reparsePartialErrorValue(parser, bestError, lastMatchStart, newChildCount, bestErrorSlotIx, oldChildParseNode, dctx);
            }
            // NOTE: In this case, we are possibly returning the error from a scalar parselet for a repeating one.  It won't matter unless we have partial values enabled and partialValue
            return bestError;
         }
         return parseError(parser, "Expecting one or more of: {0}", this);
      }
      else {
         // If we are reparsing, we might have produced fewer children than before.  if so, we need to pull them off the the end.
         if (oldParent == value) {
            removeChildrenForReparse(parser, value, newChildCount);
         }
         // If we are producing a smaller result than we did in the previous result, and we are at the end of the
         // changes - so that the old result was at least partially in the "same again" region, make sure we extend
         // the changes so we reparse those old characters we stripped off again (test/re5)
         /*
         if (!lookahead && oldParseNode != null && dctx.changedRegion) {
            int newLen = value.length();
            //int diffLen = oldLen - newLen;
            if (newLen > 0 && lastMatchStart + newLen > dctx.endParseChangeNewOffset && dctx.endParseChangeNewOffset > dctx.endChangeOldOffset) {
               //dctx.endParseChangeNewOffset = lastMatchStart + diffLen + 1;
               //dctx.endParseChangeNewOffset = lastMatchStart + newLen + 1;
            }
         }
         */
         // If we are doing the partial values case, we might have partially matched one more statement.  if so, this is part of the partial results
         if (parser.enablePartialValues && bestError != null && /*bestError.eof && */ bestError.partialValue != null && bestError.startIndex == lastMatchStart) {
            Object oldChildParseNode = oldParent == null || oldParent.children.size() <= newChildCount ? null : oldParent.children.get(newChildCount);
            value.addForReparse(bestError.partialValue, bestError.parselet, svCount, newChildCount, bestErrorSlotIx, false, parser, oldChildParseNode, dctx, true, true);

            return parsePartialError(parser, value, bestError, bestError.parselet, bestError.errorCode, bestError.errorArgs);
         }
         if (lookahead)
            dctx.changeCurrentIndex(parser, startIndex);
         else
            dctx.changeCurrentIndex(parser, lastMatchStart);
         return parseResult(parser, value, false);
      }
   }


   private int getSlotIndex(List<Parselet> matchingParselets, int i) {
      return matchingParselets instanceof MatchResult ? ((MatchResult) matchingParselets).slotIndexes.get(i) : i;
   }

   /** Code copied alert!  This is a lot like parseRepeatingChoice but skipping and stopping on exitParselet. */
   public Object parseExtendedErrors(Parser parser, Parselet exitParselet) {
      if (trace)
         System.out.println("*** tracing extended errors for ordered choice");
      if (skipOnErrorParselet == null)
         return null;

      int startIndex = parser.currentIndex;
      int lastMatchStart;
      ParentParseNode value = null;
      boolean matched;
      ParseError bestError;
      int bestErrorSlotIx = -1;

      boolean emptyMatch = false;

      do {
         matched = false;
         lastMatchStart = parser.currentIndex;

         // Since we've parsed on from this point, need to clear out the last error
         bestError = null;

         List<Parselet> matchingParselets = getMatchingParselets(parser);

         int numMatches = matchingParselets.size();
         for (int i = 0; i < numMatches; i++) {
            Parselet matchedParselet = matchingParselets.get(i);
            Object nestedValue = parser.parseNext(matchedParselet);
            emptyMatch = nestedValue == null;
            if (!(nestedValue instanceof ParseError)) {
               if (value == null)
                  value = (ParentParseNode) newParseNode(lastMatchStart);

               if (nestedValue != null || parser.peekInputChar(0) != '\0') {
                  int slotIx = getSlotIndex(matchingParselets, i);
                  value.add(nestedValue, matchedParselet, -1, slotIx, false, parser);

                  matched = true;
                  break;
               }
            }
            else {
               ParseError error = (ParseError) nestedValue;
               if (bestError == null || bestError.endIndex < error.endIndex) {
                  bestError = error;
                  bestErrorSlotIx = matchingParselets instanceof MatchResult ? ((MatchResult) matchingParselets).slotIndexes.get(i) : i;
               }
            }
         }

         if (!matched || emptyMatch) {
            // Keep applying the skipOnErrorParselet until we hit the exit parselet
            if (!exitParselet.peek(parser)) {
               int errorStart = parser.currentIndex;
               // This will consume whatever it is that we can't parse until we get to the next statement.  We have to be careful with the
               // design of the skipOnErrorParselet so that it leaves us in the state for the next match on this choice.  It should not breakup
               // an identifier for example.
               Object errorRes = parser.parseNext(skipOnErrorParselet);
               if (errorRes instanceof ParseError) {
                  // We never found the exitParselet - i.e. the } so the parent sequence will fail just like it did before.
                  // When this method returns null, we go with the result we produced in the normal parseRepeatingChoice method.
                  parser.changeCurrentIndex(startIndex);
                  return null;
               }

               boolean useError = true;
               if (errorRes instanceof IParseNode) {
                  IParseNode errorNode = (IParseNode) errorRes;
                  // If one of the partial value errors is better we can start the skip parse from that error preserving more of the model
                  if (bestError != null && errorNode.length() < bestError.endIndex - bestError.startIndex && bestError.partialValue != null) {
                     value = value == null ? (ParentParseNode) newParseNode(bestError.startIndex) : value;
                     value.add(bestError.partialValue, bestError.parselet, -1, bestErrorSlotIx, false, parser);
                     errorStart = bestError.endIndex;
                     parser.changeCurrentIndex(bestError.endIndex);

                     // It turns out the partial value error will match better than the skipOnError parselet.  We need to insert an error representing the
                     // fact that it's not a complete statement.
                     if (exitParselet.peek(parser)) {
                        value.add(new ErrorParseNode(new ParseError(bestError.parselet, bestError.errorCode, bestError.errorArgs, bestError.endIndex, bestError.endIndex), ""), bestError.parselet, -1, -1, true, parser);
                        return value;
                     }

                     errorRes = parser.parseNext(skipOnErrorParselet);
                     if (errorRes instanceof ParseError)
                        return value;

                  }
               }

               if (useError) {
                  if (value == null)
                     value = (ParentParseNode) newParseNode(lastMatchStart);
                  value.add(new ErrorParseNode(new ParseError(skipOnErrorParselet, "Expected {0}", new Object[]{this}, errorStart, parser.currentIndex), errorRes.toString()), skipOnErrorParselet, -1, -1, true, parser);
                  matched = true;
                  emptyMatch = false;
               }
            }
         }

      } while (matched && !emptyMatch);

      if (value == null) {
         if (optional) {
            if (parser.enablePartialValues && bestError != null && bestError.partialValue != null/* && bestError.eof */) {
               return parsePartialErrorValue(parser, bestError, lastMatchStart, bestErrorSlotIx);
            }
            parser.changeCurrentIndex(startIndex);

            // If we are a repeat optional choice with mappings of '' and we match no elements, we should return an empty string, not null.
            if (!lookahead && repeat && isStringParameterMapping() && parser.peekInputChar(0) != '\0') {
               return parseResult(parser, "", false);
            }
            return null;
         }
         if (bestError != null) {
            if (parser.enablePartialValues && bestError.partialValue != null) {
               return parsePartialErrorValue(parser, bestError, lastMatchStart, bestErrorSlotIx);
            }
            // NOTE: In this case, we are possibly returning the error from a scalar parselet for a repeating one.  It won't matter unless we have partial values enabled and partialValue
            return bestError;
         }
         return parseError(parser, "Expecting one or more of: {0}", this);
      }
      else {
         // If we are doing the partial values case, we might have partially matched one more statement.  if so, this is part of the partial results
         if (parser.enablePartialValues && bestError != null && /*bestError.eof && */ bestError.partialValue != null && bestError.startIndex == lastMatchStart) {
            value.add(bestError.partialValue, bestError.parselet, -1, bestErrorSlotIx, false, parser);

            return parsePartialError(parser, value, bestError, bestError.parselet, bestError.errorCode, bestError.errorArgs);
         }
         if (lookahead)
            parser.changeCurrentIndex(startIndex);

         else
            parser.changeCurrentIndex(lastMatchStart);
         return parseResult(parser, value, false);
      }
   }

   public Object reparseExtendedErrors(Parser parser, Parselet exitParselet, Object oldChildNode, DiffContext dctx, boolean forceReparse) {
      return reparseRepeatingChoice(parser, exitParselet, oldChildNode, dctx, forceReparse, true, true);
   }

   private Object parsePartialErrorValue(Parser parser, ParseError bestError, int lastMatchStart, int bestErrorSlotIx) {
      ParentParseNode value = (ParentParseNode) newParseNode(lastMatchStart);
      value.add(bestError.partialValue, bestError.parselet, -1, bestErrorSlotIx, false, parser);
      return parsePartialError(parser, value, bestError, bestError.parselet, bestError.errorCode, bestError.errorArgs);
   }

   private Object reparsePartialErrorValue(Parser parser, ParseError bestError, int lastMatchStart, int childIndex, int bestErrorSlotIx, Object oldChildParseNode, DiffContext dctx) {
      ParentParseNode value = (ParentParseNode) newParseNode(lastMatchStart);
      value.addForReparse(bestError.partialValue, bestError.parselet, childIndex, childIndex, bestErrorSlotIx, false, parser, oldChildParseNode, dctx, false, true);
      return parsePartialError(parser, value, bestError, bestError.parselet, bestError.errorCode, bestError.errorArgs);
   }

   protected List<Parselet> getMatchingParselets(Parser parser) {
      return parselets;
   }

   static final GenerateError REPEAT_NODE_ERROR = new GenerateError("Repeat node passed a non-list value");
   static public final GenerateError NO_MATCH_ERROR = new GenerateError("No match for node in choice");

   /**
    * For the OrderedChoice, we have to figure out which choice was gnenerated.
    */
   public Object generate(GenerateContext ctx, Object value) {
      if (trace)
         System.out.println("*** tracing generate ordered choice");

      String recName = null;
      if (recordTime) {
         recName = "gen-" + toString();
         PerfMon.start(recName);
      }

      try {
         int progress = 0;

         if (getSemanticValueIsArray()) { // Repeat but not chained
            if (value == null) {
               if (optional)
                  return generateResult(ctx, null);
               else {
                  System.err.println("*** Null value encountered for required array element: " + this);
                  return null;
               }
            }

            ParentParseNode pnode = newGeneratedParseNode(value);

            if (!(value instanceof List)) {
               if (PString.isString(value)) {
                  boolean matchedAny = false;
                  IString strValue = PString.toIString(value);
                  while (strValue != null && strValue.length() > 0) {
                     Object childNode = generateElement(ctx, strValue, true);
                     if (childNode instanceof GenerateError) {
                        if (matchedAny)
                           return generateResult(ctx, pnode);

                        pnode.setSemanticValue(null, true);
                        if (optional && emptyValue(ctx, value)) {
                           return generateResult(ctx, null);
                        }
                        ((GenerateError)childNode).progress += progress;
                        return childNode;
                     }
                     matchedAny = true;
                     pnode.addGeneratedNode(childNode);


                     /*
                     * We have a string semantic value... in case it was formed from more than one parselet
                     * we need to pull out the part which was just generated before passing the next chunk on.
                     */
                     if (childNode != null) {
                        int childNodeLen = ParseUtil.toSemanticStringLength(childNode);
                        if (strValue.length() >= childNodeLen) {
                           strValue = strValue.substring(childNodeLen);
                           value = strValue;
                           progress += childNodeLen;
                        }
                        else
                           strValue = null;
                     }
                     else
                        strValue = null;
                  }
                  return generateResult(ctx, pnode);
               }
               return REPEAT_NODE_ERROR;
            }

            // If we are not an array type or our children are all scalars, pull apart the array and
            // process it one by one.
            if (parameterType != ParameterType.ARRAY || !ParseUtil.isArrayParselet(parselets.get(0))) {
               List nodeList = (List) value;
               for (int i = 0; i < nodeList.size(); i++) {
                  Object nodeVal = nodeList.get(i);
                  if (PString.isString(nodeVal)) {
                     IString strValue = PString.toIString(nodeVal);

                     // In this case, we are accepting an array of nodes and must produce back an array of strings
                     // Since our child node is a scalar, we are a repeat node, we must pull apart the string piece
                     // by piece until we generate it all.  Any error at this point will fail.
                     while (strValue != null && strValue.length() > 0) {
                        Object childNode = generateElement(ctx, strValue, true);
                        if (childNode instanceof GenerateError) {
                           pnode.setSemanticValue(null, true);
                           if (optional && emptyValue(ctx, value)) {
                              return generateResult(ctx, null);
                           }
                           ((GenerateError) childNode).progress += progress;
                           return childNode;
                        }
                        pnode.addGeneratedNode(childNode);

                        /*
                        * We have a string semantic value... in case it was formed from more than one parselet
                        * we need to pull out the part which was just generated before passing the next chunk on.
                        */
                        if (childNode != null) {
                           int childNodeLen = ParseUtil.toSemanticStringLength(childNode);
                           if (strValue.length() >= childNodeLen) {
                              strValue = strValue.substring(childNodeLen);
                              value = strValue;
                              progress += childNodeLen;
                           }
                           else
                              strValue = null;
                        }
                        else
                           strValue = null;
                     }

                     // If we are a String node and we receive an array of strings, we need to just pull the first
                     // string out and return.  We could not have produced the array.
                     if (parameterType == ParameterType.STRING) {
                        return new PartialArrayResult(i+1, pnode);
                     }
                  }
                  else {
                     Object childNode = generateElement(ctx, nodeVal, true);
                     if (childNode instanceof GenerateError) {
                        if (i > 0)
                           return new PartialArrayResult(i, pnode, (GenerateError) childNode);

                        if (optional && emptyValue(ctx, value)) {
                           pnode.setSemanticValue(null, true);
                           return generateResult(ctx, null);
                        }
                        ((GenerateError) childNode).progress += progress;
                        return childNode;
                     }
                     pnode.addGeneratedNode(childNode);
                     progress += ctx.progress(childNode);
                  }
               }
            }
            // Otherwise, we need to let our children process as much of the array as they can and deal
            // with the partial result.
            else {
               pnode = newGeneratedParseNode(value);
               SemanticNodeList nodeList = (SemanticNodeList) value;
               int numProcessed = 0;
               do {
                  Object arrVal = generateElement(ctx, nodeList, false);
                  if (arrVal instanceof PartialArrayResult) {
                     PartialArrayResult par = (PartialArrayResult) arrVal;
                     pnode.copyGeneratedFrom(par.resultNode);
                     nodeList = getRemainingValue(nodeList, par.numProcessed);
                     numProcessed += par.numProcessed;
                     progress += ctx.progress(par.resultNode);
                  }
                  else if (arrVal instanceof GenerateError) {
                     if (numProcessed != 0)
                        return new PartialArrayResult(numProcessed, pnode, (GenerateError) arrVal);
                     else {
                        pnode.setSemanticValue(null, true);
                        ((GenerateError) arrVal).progress += progress;
                        return arrVal;
                     }
                  }
                  else
                     return arrVal;

               } while(nodeList.size() > 0);
            }

            return generateResult(ctx, pnode);
         }
         else {
            return generateElement(ctx, value, false);
         }
      }
      finally {
         if (recordTime)
            PerfMon.end(recName);
      }
   }

   public Object generateElement(GenerateContext ctx, Object value, boolean isArray) {
      // Need to do this up front to prevent a recursive loop in resolving a null definition.
      // The logic below is kept around in
      if (optional && emptyValue(ctx, value))
         return generateResult(ctx, null);

      Object currentResult = null;
      boolean resultStale = false;
      int currentResultIndex = -1;
      Object currentMatchedValue = null;
      GenerateError bestMatchError = null;

      if (trace)
         System.out.println("*** tracing generate ordered choice");

      int sz = parselets.size();
      for (int i = 0; i < sz; i++) {
         Parselet childParselet = parselets.get(i);

         Class svClass = childParselet.getSemanticValueClass();

         Object useValue = value;

         if (svClass == null || useValue == null ||
             ((useValue = ParseUtil.convertSemanticValue(svClass, useValue)) != ParseUtil.CONVERT_MISMATCH) && typeMatches(useValue, isArray)) {

            Object childNode = ctx.generateChild(childParselet, useValue);

            // Only tracking stale results for non-array cases... in this case, any call to generateChild could have
            // overwritten the parse nodes in the semantic value objects.
            if (currentResultIndex != -1)
               resultStale = true;

            if (!(childNode instanceof GenerateError)) {
               if (currentResult == null) {
                  currentResult = childNode;
                  currentResultIndex = i;
                  currentMatchedValue = useValue;
               }
               else {
                  if (childNode instanceof PartialArrayResult) {
                     if (currentResult instanceof PartialArrayResult) {
                         if (((PartialArrayResult) currentResult).numProcessed <= ((PartialArrayResult) childNode).numProcessed) {
                            currentResult = childNode;

                         }
                     }
                  }
                  else {
                     if (currentResult instanceof PartialArrayResult)
                         currentResult = childNode;
                     else {
                        int newLen = ((CharSequence) childNode).length();
                        int oldLen = ((CharSequence) currentResult).length();
                        if (oldLen < newLen) {
                           currentResult = childNode;
                           currentMatchedValue = useValue;
                           currentResultIndex = i;
                        }
                     }
                  }
               }
            }
            else {
               if (ctx.generateStats) {
                  int failedBytes = ctx.progress(childNode);
                  //if (recordTime && failedBytes > 0)
                  //   System.out.println("*** debug point - for parselets which do not generate efficiently");
                  failedProgressBytes += failedBytes;
               }
               if (bestMatchError == null)
                  bestMatchError = (GenerateError) childNode;
               else {
                  GenerateError newError = (GenerateError) childNode;
                  if (newError.progress > bestMatchError.progress)
                     bestMatchError = newError;
               }
            }
         }
      }

      if (currentResult == null) {
         // For string values we pass in the entire value but only match the part we can... if this is
         // optional we should just return null.  Might be better to turn this into a PartialStringResult?
         if (optional && PString.isString(value))
             return generateResult(ctx, null);
         if (bestMatchError != null)
            return bestMatchError;
         else
            return ctx.error(this, NO_MATCH_ERROR, value, 0);
      }

      if (resultStale) {
         // Since we have tried to match other parselets since we generated this, those guys might have trashed the
         // parseNode field of the semantic value nodes.  We use to just regenerate here but it took more than 2X hit
         // on performance.  Instead, we walk through the parselets and update the parse nodes which will be a lot faster.
         if (currentResult instanceof IParseNode) {
            PerfMon.start("updateParseNodes");
            // No need to update the semantic values to point to the parse nodes for the final generation since we never go look at them again.
            if (!ctx.finalGeneration && parselets.get(currentResultIndex).updateParseNodes(currentMatchedValue, (IParseNode) currentResult) != MATCH) {
               System.out.println("*** failed to update parse nodes in 'final-generation' optimization for choice nodes");
               // int num = parselets.get(currentResultIndex).updateParseNodes(currentMatchedValue, (IParseNode) currentResult);
            }
            PerfMon.end("updateParseNodes");
         }
         //currentResult = ctx.generateChild(parselets.get(currentResultIndex), currentMatchedValue);
         if (currentResult == null || currentResult instanceof GenerateError)
            throw new IllegalArgumentException("Re-generation of node failed!");
      }
      return generateResult(ctx, currentResult);
   }

   static boolean staleComputing = false;
   static long staleComputeTime = 0;

   public boolean semanticPropertyChanged(Object parentParseNode, Object semanticValue, Object selector, Object value) {
      for (int i = 0; i < parselets.size(); i++) {
         Parselet cp = parselets.get(i);
         if (cp instanceof NestedParselet) {
            NestedParselet childParselet = (NestedParselet) cp;

            Class svClass = childParselet.getSemanticValueClass();

            if (svClass != null && semanticValue != null &&
                ParseUtil.convertSemanticValue(svClass, semanticValue) != ParseUtil.CONVERT_MISMATCH) {
               Object childParseNode;
               // An ordered choice node which is skipped will not appear in the parse node tree and so
               // we just propagate down the parent directly to the child.  So only propagate down if
               // this parse node is ours.  Otherwise, it is for a child node.
               if (((IParseNode)parentParseNode).getParselet() == this) {
                  if (parentParseNode instanceof ParseNode)
                     childParseNode = ((ParseNode) parentParseNode).value;
                  else {
                     if (((ParentParseNode) parentParseNode).children == null)
                        return true; // Node already invalidated from here on down.
                     childParseNode = ((ParentParseNode)parentParseNode).children.get(i);
                  }
               }
               else
                   childParseNode = parentParseNode;
               if (childParselet.semanticPropertyChanged(childParseNode, semanticValue, selector, value))
                  return true;
            }
         }
      }
      return false;
   }

   protected void initParameterMapping() {
      super.initParameterMapping();

      if (parameterMapping != null)
         for (ParameterMapping p:parameterMapping)
            if (p == ParameterMapping.NAMED_SLOT)
               throw new IllegalArgumentException("OrderedChoice cannot use named slot mappings since it never produces more than a single result.  It can include, convert to string, inherit, propagate or array.");
   }

   protected void invalidateChildElement(ParentParseNode pnode, Parselet childParselet, Object semanticValue, Object element, int parseNodeIx, ChangeType changeType) {
      invalidateChildParseNode(pnode, childParselet, element, parseNodeIx, changeType);
   }

   protected GenerateError regenerateElement(ParentParseNode pnode, Parselet childParselet, Object element, int parseNodeIx, ChangeType changeType) {
      return regenerateChild(pnode, childParselet, element, parseNodeIx, changeType);
   }

   public boolean emptyValue(GenerateContext ctx, Object value) {
      // For this case, if we have any non-empty parselets, just return false.  If we have a match though
      // and it says it is empty, we return true.  Otherwise, we return false.
      if (parameterType == ParameterType.DEFAULT) {
         boolean matched = false;
         for (Parselet p:parselets) {
            Class svClass = p.getSemanticValueClass();

            Object useValue = value;

            if (svClass == null || useValue == null ||
                (useValue = ParseUtil.convertSemanticValue(svClass, useValue)) != ParseUtil.CONVERT_MISMATCH)
               if (p.emptyValue(ctx, useValue))
                  matched = true;
               else
                  return false;
         }
         if (matched)
            return true;
      }
      return super.emptyValue(ctx, value);
   }

   static class MatchResult extends ArrayList<Parselet> {
     IntStack slotIndexes = new IntStack(1);
   }

   public Parselet getChildParselet(Object childParseNode, int index) {
      Parselet res = null;
      Object childNode = ParseUtil.nodeToSemanticValue(childParseNode);
      for (int i = 0; i < parselets.size(); i++) {
         Parselet p = parselets.get(i);
         if (p.dataTypeMatches(childNode))
            res = p;
      }
      return res;
   }

   public int updateParseNodes(Object semanticValue, IParseNode parentNode) {
      if (parentNode instanceof FormattedParseNode) {
         return MATCH;
      }

      if (getSemanticValueIsArray()) { // Repeat but not chained
         if (semanticValue instanceof String)
            return MATCH;

         if (!(semanticValue instanceof List)) {
            return updateParseNodesForElement(semanticValue, parentNode);
         }

         List svList = (List) semanticValue;
         int pnix = 0;

         if (!(parentNode instanceof ParentParseNode))
            return NO_MATCH;

         ParentParseNode pn = (ParentParseNode) parentNode;
         if (pn.children != null) {
            int numChildren = pn.children.size();

            for (int i = 0; i < svList.size(); i++) {
               Object svElement = svList.get(i);
               if (pnix >= numChildren)
                  return MATCH;
               Object childNode = pn.children.get(pnix);
               if (parameterType != ParameterType.ARRAY || !ParseUtil.isArrayParselet(parselets.get(0))) {
                  if (childNode instanceof IParseNode)
                     if (updateParseNodesForElement(svElement, ((IParseNode)childNode)) != MATCH)
                        return i;
                  pnix++;
               }
               else {
                  System.out.println("*** Unhandled case in updateParseNodes");
               }
            }
         }
         return MATCH;
      }
      else {
         return updateParseNodesForElement(semanticValue, parentNode);
      }
   }

   int updateParseNodesForElement(Object semanticValue, IParseNode parentNode) {
      if (!(semanticValue instanceof ISemanticNode))
         return MATCH;

      // First we assume the parselets match the parsenode... it's often the case the parse node
      // does have the parselet set properly so that can help the search.
      return updateParselets(semanticValue, parentNode);
   }

   int updateParselets(Object semanticValue, IParseNode parentNode) {
      int sz = parselets.size();

      for (int i = 0; i < sz; i++) {
         Parselet p = parselets.get(i);

         if (p.parseNodeMatches(semanticValue, parentNode)) {
            if (p.updateParseNodes(semanticValue, parentNode) == MATCH) {
               // Just in case the semantic value is pointing back here for some reason
               super.updateParseNodes(semanticValue, parentNode);
               return MATCH;
            }
         }
      }
      return NO_MATCH;
   }

   public boolean producesParselet(Parselet other) {
      if (super.producesParselet(other))
         return true;

      if (parselets == null)
         return false;
      int sz = parselets.size();
      for (int i = 0; i < sz; i++) {
         Parselet child = parselets.get(i);
         if (child.producesParselet(other))
            return true;
      }
      return false;
   }

   /** Returns true if this parselet could have produced the given semanticValue. */
   public boolean elementTypeMatches(Object other) {
      if (super.elementTypeMatches(other))
         return true;

      Class svClass = getSemanticValueComponentClass();

      // Could not be a match - the data types do not match
      if (svClass == null || !svClass.isInstance(other))
         return false;

      boolean isArray = getSemanticValueIsArray();

      if (parselets == null)
         return false;
      int sz = parselets.size();
      for (int i = 0; i < sz; i++) {
         Parselet child = parselets.get(i);
         if (isArray) {
            if (child.dataTypeMatches(other))
               return true;
         }
         // If this guy is not the array, the choice itself must be an array?
         else if (child.elementTypeMatches(other))
            return true;
      }
      return false;
   }

   /** Returns true if this parselet could have produced the given semanticValue. */
   public boolean dataTypeMatches(Object other) {
      if (super.dataTypeMatches(other))
         return true;

      Class svClass = getSemanticValueClass();

      // Could not be a match - the data types do not match
      if (!svClass.isInstance(other))
         return false;

      if (parselets == null)
         return false;
      int sz = parselets.size();
      for (int i = 0; i < sz; i++) {
         Parselet child = parselets.get(i);
         if (child.dataTypeMatches(other))
            return true;
      }
      return false;
   }


   protected boolean handlesProperty(Object selector) {
      if (parselets != null) {
         int sz = parselets.size();
         for (int i = 0; i < sz; i++) {
            Parselet child = parselets.get(i);
            if (child instanceof NestedParselet && ((NestedParselet)child).handlesProperty(selector))
               return true;
         }
      }
      return super.handlesProperty(selector);
   }

}
