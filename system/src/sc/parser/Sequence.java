/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.java.JavaSemanticNode;

import java.util.ArrayList;
import java.util.List;

public class Sequence extends NestedParselet  {
   /** For a Sequence which has all optional children, should the value of this sequence be an empty object? (acceptNoContentn=true) or
    * return null as the value for the sequence, the default. */
   boolean acceptNoContent = false;

   public int skipOnErrorSlot = -1;

   public int minContentSlot = 0;

   public Sequence() { super(); }

   public Sequence(String id, int options) {
      super(id, options);
   }

   public Sequence(int options) {
      super(options);
   }

   public Sequence(String id, Parselet... toAdd) {
      super(id, toAdd);
   }

   public Sequence(Parselet... toAdd) {
      super(toAdd);
   }

   public Sequence(String id, int options, Parselet... toAdd) {
      super(id, options, toAdd);
   }

   public Sequence(int options, Parselet... toAdd) {
      super(options, toAdd);
   }

   public String getSeparatorSymbol() {
      return " ";
   }

   private ParentParseNode cloneParseNode(ParentParseNode value) {
      Object semValue = value.getSemanticValue();
      ParentParseNode errVal;
      if (semValue != null && semValue instanceof ISemanticNode) {
         ISemanticNode newNode = ((ISemanticNode) semValue).deepCopy(ISemanticNode.CopyAll, null);
         errVal = (ParentParseNode) newNode.getParseNode();
      }
      else
         errVal = value.deepCopy();
      return errVal;
   }

   public Object parse(Parser parser) {
      if (trace)
         System.out.println("*** parse sequence: " + this + " at " + parser.currentIndex);

      if (repeat)
         return parseRepeatingSequence(parser, false, null);

      ParentParseNode value = null;
      int startIndex = parser.currentIndex;

      boolean anyContent = false;

      int numParselets = parselets.size();
      for (int i = 0; i < numParselets; i++) {
         Parselet childParselet = parselets.get(i);

         Object nestedValue = parser.parseNext(childParselet);
         if (nestedValue instanceof ParseError) {
            if (negated) {
               // Reset back to the beginning of the sequence
               parser.changeCurrentIndex(startIndex);

               return null;
            }

            ParseError err = (ParseError) nestedValue;
            boolean isBetterError = false;
            // If we are optional and have at least some content that we've matched and looking for partial values, this optional error may be helpful
            // in stitching things together.
            if (optional && (!parser.enablePartialValues || parser.currentIndex != parser.currentErrorStartIndex)) {
               if (parser.enablePartialValues) {
                  int errEndIx = Math.max(parser.currentIndex, err.endIndex);
                  if (!Parser.isBetterError(parser.currentErrorStartIndex, parser.currentErrorEndIndex, startIndex, errEndIx, true)) {
                     parser.changeCurrentIndex(startIndex);
                     return null;
                  }
                  else {
                     isBetterError = true;
                  }
               }
               else {
                  parser.changeCurrentIndex(startIndex);
                  return null;
               }
            }

            if (parser.enablePartialValues) {
               Object pv = err.partialValue;

               // If we failed to complete this sequence, and we are in "error handling" mode, try to re-parse the previous sequence by extending it.
               // Some sequences collapse all values into a single child (e.g. blockComment).  For those, we can't support this optimization.
               if (i > 0 && value != null && value.children != null && value.children.size() >= i) {
                  int prevIx = i - 1;
                  Parselet prevParselet = parselets.get(prevIx);
                  Object oldValue = value.children.get(prevIx);
                  int saveIndex = parser.currentIndex;
                  Object ctxState = null;
                  if (oldValue instanceof IParseNode) {
                     ctxState = parser.resetCurrentIndex(((IParseNode) oldValue).getStartIndex());
                  }
                  Object newPrevValue = prevParselet.parseExtendedErrors(parser, childParselet);
                  // If we have a successful extended error parse that's longer than the previous one, we should try parsing again
                  if (newPrevValue != null && !(newPrevValue instanceof ParseError) && (oldValue == null || ((CharSequence) newPrevValue).length() > ((CharSequence) oldValue).length())) {
                     value.set(newPrevValue, childParselet, prevIx, false, parser);

                     // Go back and retry the current child parselet now that we've parsed the previous one again successfully... we know it should match because we just peeked it in the previous parselet.
                     i = i - 1;
                     continue;
                  }
                  else
                     parser.restoreCurrentIndex(saveIndex, ctxState);
               }

               if (i < parselets.size() - 1) {
                  Parselet exitParselet = parselets.get(i+1);
                  // TODO: for the simpleTag case where we are completing tagAttributes, we have / and > as following parselets where
                  // the immediate one afterwards is optional.  To handle this we can take the list of parselets following in the sequence
                  // and pass them all then peek them as a list.
                  if (!exitParselet.optional) {
                     Object extendedValue = childParselet.parseExtendedErrors(parser, exitParselet);
                     if (extendedValue != null) {
                        nestedValue = extendedValue;
                        err = nestedValue instanceof ParseError ? (ParseError) nestedValue : null;
                     }
                  }
               }

               // Question: should we always do the skipOnError processing - not just on enablePartialValues?   It could in general give more complete syntax errors
               // For now, I think the answer is no.  If we need better errors, we should parse files with syntax errors again with this mode.
               // Otherwise, it's possible we'll introduce strange ambiguities into the successfully parsed files.
               if (err != null) {
                  // Skip on error can either be set on the child or as a slot index on the parent
                  if (childParselet.skipOnError || (skipOnErrorSlot != -1 && i >= skipOnErrorSlot)) {
                     parser.addSkippedError(err);
                     // Record the error but move on
                     nestedValue = new ErrorParseNode(err, "");
                     err = null;
                  }
                  else {
                     if (err.optionalContinuation) {
                        ParentParseNode errVal;
                        if (pv != null) {
                           if (value == null)
                              errVal = (ParentParseNode) newParseNode(startIndex);
                           else {
                              // Here we need to clone the semantic value so we keep track of the state that
                              // represents this error path separate from the default path which is going to match
                              // and replace the error slot here with a null
                              Object semValue = value.getSemanticValue();
                              if (semValue != null && semValue instanceof ISemanticNode) {
                                 ISemanticNode newNode = ((ISemanticNode) semValue).deepCopy(ISemanticNode.CopyAll, null);
                                 errVal = (ParentParseNode) newNode.getParseNode();
                              }
                              else
                                 errVal = value.deepCopy();
                           }
                           errVal.add(pv, childParselet, -1, i, false, parser);
                        }
                        else
                           errVal = value;
                        err.partialValue = errVal;
                        //err.continuationValue = true;
                        err.optionalContinuation = false;
                        nestedValue = null; // Switch this to optional
                        err = null; // cancel the error
                     }
                     // Always call this to try and extend the current error... also see if we can generate a new error
                     else if (!childParselet.getLookahead()) {

                        // First complete the value with any partial value from this error and nulls for everything
                        // else.
                        if (value == null)
                           value = (ParentParseNode) newParseNode(startIndex);
                        value.add(pv, childParselet, -1, i, false, parser);
                        // Add these null slots so that we do all of the node processing
                        for (int k = i+1; k < numParselets; k++)
                           value.add(null, parselets.get(k), -1, k, false, parser);

                        // Now see if this computed value extends any of our most specific errors.  If so, this error
                        // can be used to itself extend other errors based on the EOF parsing.
                        if ((extendsPartialValue(parser, childParselet, value, anyContent, startIndex, err) && anyContent) || err.eof || pv != null || isBetterError) {
                           if (!err.eof || !value.isEmpty()) {
                              if (optional && value.isEmpty())
                                 return null;
                              // Keep the origin error description so the right info is in the error presented to the user
                              ParseError newError = parsePartialError(parser, value, err, childParselet, err.errorCode, err.errorArgs);
                              return newError;
                           }
                        }
                     }
                  }
               }
            }

            // Unless the error was cancelled or skip-on-error was true, i.e. so we logged the error and are moving on.
            if (err != null) {
               if (optional) {
                  parser.changeCurrentIndex(startIndex);
                  return null;
               }

               if (reportError)
                  return parseError(parser, childParselet, "Expected: {0}", this);
               else
                  parser.changeCurrentIndex(startIndex);
               return nestedValue;
            }
         }

         if (value == null)
            value = (ParentParseNode) newParseNode(startIndex);

         if (!childParselet.getLookahead())
            value.add(nestedValue, childParselet, -1, i, false, parser);
         else if (slotMapping != null) // Need to preserve the specific index in the results if we have a mapping
            value.add(null, childParselet, -1, i, true, parser);

         if (nestedValue != null || childParselet.isNullValid())
            anyContent = minContentSlot <= i;
      }

      if (lookahead) {
         // Reset back to the beginning of the sequence
         parser.changeCurrentIndex(startIndex);
      }

      if (anyContent || acceptNoContent) {
         String customError;
         if ((customError = accept(parser.semanticContext, value, startIndex, parser.currentIndex)) != null) {
            if (negated)
                return value;
            return parseError(parser, customError, value, this);
         }

         if (negated)
            return parseError(parser, "Negated rule: value {0} matched rule: {1}", value, this);
         return value;
      }
      else
         return null;
   }


   // TODO: this is a copy of the parse() method but duplicates a lot of code.  Can we refactor the common code into utility
   // methods or even condense them into one with runtime overhead?
   public Object reparse(Parser parser, Object oldParseNode, DiffContext dctx, boolean forceReparse) {
      if (trace)
         System.out.println("*** reparse sequence: " + this + " at " + parser.currentIndex);

      Object origOldParseNode = oldParseNode;

      if (!anyReparseChanges(parser, oldParseNode, dctx, forceReparse)) {
         advancePointer(parser, oldParseNode, dctx);
         parser.reparseSkippedCt++;
         return oldParseNode;
      }

      if (trace)
         System.out.println("*** reparse sequence: " + this + " at " + parser.currentIndex);

      parser.reparseCt++;

      if (repeat) {
         Object res = reparseRepeatingSequence(parser, false, null, oldParseNode, dctx, forceReparse);
         checkForSameAgainRegion(parser, origOldParseNode, dctx, res == null || res instanceof ParseError, forceReparse);
         return res;
      }

      // If the changes in the text begin before this node, just reparse it from scratch
      if (dctx.changedRegion && oldParseNode != null) {
         //oldParseNode = null;
         forceReparse = true;
      }

      // If the primitive node has changed or we have an ErrorParseNode, just reparse it since we don't maintain enough info to split the value
      // apart and incrementally update it
      if (!(oldParseNode instanceof IParseNode) && oldParseNode != null) {
         //oldParseNode = null;
         forceReparse = true;
      }

      if (oldParseNode instanceof IParseNode && !producesParselet(((IParseNode) oldParseNode).getParselet())) {
         //oldParseNode = null;
         forceReparse = true;
      }

      //if (forceReparse)
      //   oldParseNode = null;

      int startIndex = parser.currentIndex;
      ParentParseNode value = null;
      Object[] savedOrigChildren = null;

      boolean anyContent = false;

      int numParselets = parselets.size();
      int newChildCount = 0;

      for (int i = 0; i < numParselets; i++) {
         Parselet childParselet = parselets.get(i);
         boolean nextChildReparse = false;

         Object oldChildParseNode = getReparseChildNode(oldParseNode, i, forceReparse);

         if (oldChildParseNode == SKIP_CHILD) {
            value.addForReparse(null, childParselet, newChildCount, newChildCount++, i, false, parser, null, dctx, false, false);
            continue;
         }

         nextChildReparse = !oldChildMatches(oldChildParseNode, childParselet, dctx);

         Object nestedValue = parser.reparseNext(childParselet, oldChildParseNode, dctx, forceReparse || nextChildReparse);
         if (nestedValue instanceof ParseError) {
            if (negated) {
               // Reset back to the beginning of the sequence
               dctx.changeCurrentIndex(parser, startIndex);

               checkForSameAgainRegion(parser, origOldParseNode, dctx, true, forceReparse);
               return null;
            }

            ParseError err = (ParseError) nestedValue;
            boolean isBetterError = false;
            // If we are optional and have at least some content that we've matched and looking for partial values, this optional error may be helpful
            // in stitching things together.
            if (optional && (!parser.enablePartialValues || parser.currentIndex != parser.currentErrorStartIndex)) {
               if (parser.enablePartialValues) {
                  int errEndIx = Math.max(parser.currentIndex, err.endIndex);
                  if (!Parser.isBetterError(parser.currentErrorStartIndex, parser.currentErrorEndIndex, startIndex, errEndIx, true)) {
                     dctx.changeCurrentIndex(parser, startIndex);
                     checkForSameAgainRegion(parser, origOldParseNode, dctx, true, forceReparse);
                     return null;
                  }
                  else {
                     isBetterError = true;
                  }
               }
               else {
                  dctx.changeCurrentIndex(parser, startIndex);
                  checkForSameAgainRegion(parser, origOldParseNode, dctx, true, forceReparse);
                  return null;
               }
            }

            if (parser.enablePartialValues) {
               Object pv = err.partialValue;

               // If we failed to complete this sequence, and we are in "error handling" mode, try to re-parse the previous sequence by extending it.
               // Some sequences collapse all values into a single child (e.g. blockComment).  For those, we can't support this optimization.
               if (i > 0 && value != null && value.children != null && value.children.size() >= i) {
                  int prevIx = i - 1;
                  Parselet prevParselet = parselets.get(prevIx);
                  Object oldValue = value.children.get(prevIx);
                  Object origOldChild = value == origOldParseNode ? oldValue : null;
                  if (savedOrigChildren != null) {
                     origOldChild = savedOrigChildren[prevIx];
                  }
                  boolean prevChildReparse = !oldChildMatches(origOldChild, prevParselet, dctx);
                  int saveIndex = parser.currentIndex;
                  Object ctxState = null;
                  if (oldValue instanceof IParseNode) {
                     ctxState = dctx.resetCurrentIndex(parser, ((IParseNode) oldValue).getStartIndex());
                  }
                  Object newPrevValue = prevParselet.reparseExtendedErrors(parser, childParselet, origOldChild, dctx, forceReparse || prevChildReparse);
                  if (newPrevValue != null && !(newPrevValue instanceof ParseError)) {
                     value.set(newPrevValue, childParselet, prevIx, false, parser);

                     // Go back and retry the current child parselet now that we've parsed the previous one again successfully... we know it should match because we just peeked it in the previous parselet.
                     i = i - 1;
                     continue;
                  }
                  else
                     dctx.restoreCurrentIndex(parser, saveIndex, ctxState);
               }

               if (i < parselets.size() - 1) {
                  Parselet exitParselet = parselets.get(i+1);
                  // TODO: for the simpleTag case where we are completing tagAttributes, we have / and > as following parselets where
                  // the immediate one afterwards is optional.  To handle this we can take the list of parselets following in the sequence
                  // and pass them all then peek them as a list.
                  if (!exitParselet.optional) {
                     Object extendedValue = childParselet.reparseExtendedErrors(parser, exitParselet, oldChildParseNode, dctx, forceReparse || nextChildReparse);
                     if (extendedValue != null) {
                        nestedValue = extendedValue;
                        err = nestedValue instanceof ParseError ? (ParseError) nestedValue : null;
                     }
                  }
               }

               // Question: should we always do the skipOnError processing - not just on enablePartialValues?   It could in general give more complete syntax errors
               // For now, I think the answer is no.  If we need better errors, we should parse files with syntax errors again with this mode.
               // Otherwise, it's possible we'll introduce strange ambiguities into the successfully parsed files.
               if (err != null) {
                  // Skip on error can either be set on the child or as a slot index on the parent
                  if (childParselet.skipOnError || (skipOnErrorSlot != -1 && i >= skipOnErrorSlot)) {
                     parser.addSkippedError(err);
                     // Record the error but move on
                     nestedValue = new ErrorParseNode(err, "");
                     err = null;
                  }
                  else {
                     if (err.optionalContinuation) {
                        ParentParseNode errVal;
                        if (pv != null) {
                           if (value == null) {
                              errVal = resetOldParseNode(forceReparse || nextChildReparse ? null : oldParseNode, startIndex, true, false);
                           }
                           else {
                              // Here we need to clone the semantic value so we keep track of the state that
                              // represents this error path separate from the default path which is going to match
                              // and replace the error slot here with a null
                              Object semValue = value.getSemanticValue();
                              if (semValue != null && semValue instanceof ISemanticNode) {
                                 ISemanticNode newNode = ((ISemanticNode) semValue).deepCopy(ISemanticNode.CopyAll, null);
                                 errVal = (ParentParseNode) newNode.getParseNode();
                              }
                              else
                                 errVal = value.deepCopy();
                           }
                           errVal.addForReparse(pv, childParselet, newChildCount, newChildCount++, i, false, parser, oldChildParseNode, dctx, false, false);
                        }
                        else
                           errVal = value;
                        err.partialValue = errVal;
                        //err.continuationValue = true;
                        err.optionalContinuation = false;
                        nestedValue = null; // Switch this to optional
                        err = null; // cancel the error
                     }
                     // Always call this to try and extend the current error... also see if we can generate a new error
                     else if (!childParselet.getLookahead()) {

                        // First complete the value with any partial value from this error and nulls for everything
                        // else.  Note: we have clone = true for this call to resetOldParseNode because there's a chance we end up clearing
                        // out the old value and not returning this as a result from this parselet.  The parent parselet will then try to use the
                        // cleared out "oldParseNode" as though it was not cleared out.
                        if (value == null)
                           value = resetOldParseNode(nextChildReparse || forceReparse ? null : oldParseNode, startIndex, true, true);
                        value.addForReparse(pv, childParselet, newChildCount, newChildCount++, i, false, parser, oldChildParseNode, dctx, false, false);
                        // Add these null slots so that we do all of the node processing
                        for (int k = i+1; k < numParselets; k++)
                           value.addForReparse(null, parselets.get(k), newChildCount, newChildCount++, k, false, parser, oldChildParseNode, dctx, true, false);

                        // Now see if this computed value extends any of our most specific errors.  If so, this error
                        // can be used to itself extend other errors based on the EOF parsing.
                        if ((extendsPartialValue(parser, childParselet, value, anyContent, startIndex, err) && anyContent) || err.eof || pv != null || isBetterError) {
                           if (!err.eof || !value.isEmpty()) {
                              if (optional && value.isEmpty())
                                 return null;
                              // Keep the origin error description so the right info is in the error presented to the user
                              ParseError newError = parsePartialError(parser, value, err, childParselet, err.errorCode, err.errorArgs);
                              checkForSameAgainRegion(parser, origOldParseNode, dctx, true, forceReparse);
                              return newError;
                           }
                        }
                     }
                  }
               }
            }

            // Unless the error was cancelled or skip-on-error was true, i.e. so we logged the error and are moving on.
            if (err != null) {
               if (optional) {
                  dctx.changeCurrentIndex(parser, startIndex);
                  checkForSameAgainRegion(parser, origOldParseNode, dctx, true, forceReparse);
                  return null;
               }

               if (reportError)
                  return reparseError(parser, childParselet, dctx, "Expected: {0}", this);
               else
                 dctx.changeCurrentIndex(parser, startIndex);

               checkForSameAgainRegion(parser, origOldParseNode, dctx, nestedValue == null || nestedValue instanceof ParseError, forceReparse);
               return nestedValue;
            }
         }

         if (value == null) {
            value = resetOldParseNode(nextChildReparse || forceReparse ? null : oldParseNode, startIndex, true, false);
         }

         if (!childParselet.getLookahead()) {
            // When we get an error parsing this sequence, if we've changed the children array we need to make a backup copy so we can pull out the original old parse-nodes
            if (savedOrigChildren == null && value == origOldParseNode && value.children != null && newChildCount < value.children.size() && value.children.get(newChildCount) != nestedValue) {
               savedOrigChildren = value.children.toArray();
            }
            value.addForReparse(nestedValue, childParselet, newChildCount, newChildCount++, i, false, parser, oldChildParseNode, dctx, false, false);
         }
         else if (slotMapping != null) // Need to preserve the specific index in the results if we have a mapping
            value.addForReparse(null, childParselet, newChildCount, newChildCount++, i, true, parser, oldChildParseNode, dctx, false, false);

         if (nestedValue != null || childParselet.isNullValid())
            anyContent = minContentSlot <= i;
      }

      if (lookahead) {
         // Reset back to the beginning of the sequence
         dctx.changeCurrentIndex(parser, startIndex);
      }

      if (!forceReparse && oldParseNode instanceof ParentParseNode && newChildCount != ((ParentParseNode) oldParseNode).children.size() && !(this instanceof ChainedResultSequence))
         System.err.println("*** Mismatching sizes in sequence parse");

      checkForSameAgainRegion(parser, origOldParseNode, dctx, !anyContent, forceReparse);

      if (anyContent || acceptNoContent) {
         String customError;
         if ((customError = accept(parser.semanticContext, value, startIndex, parser.currentIndex)) != null) {
            if (negated)
               return value;
            return reparseError(parser, dctx, customError, value, this);
         }

         if (negated)
            return reparseError(parser, dctx, "Negated rule: value {0} matched rule: {1}", value, this);
         return value;
      }
      else
         return null;
   }

   private boolean oldChildMatches(Object oldChildParseNode, Parselet childParselet, DiffContext dctx) {
      boolean matches = true;
      if (oldChildParseNode instanceof IParseNode) {
         IParseNode oldChildPN = (IParseNode) oldChildParseNode;
         if (!childParselet.producesParselet(oldChildPN.getParselet())) {
            //oldChildParseNode = null;
            matches = false;
         }
         // We might be in the same again region for the new text but still in the changed region for the old text so do not include those children
         else if (dctx.sameAgain && oldChildPN.getOrigStartIndex() < dctx.endChangeOldOffset) {
            //oldChildParseNode = null;
            matches = false;
         }
         else if (oldChildParseNode instanceof ErrorParseNode) {
            matches = false;
         }
      }
      return matches;
   }

   protected static final String SKIP_CHILD = "SkipChildParseletSentinel";

   protected Object getReparseChildNode(Object oldParent, int ix, boolean forceReparse) {
      if (oldParent == null)
         return null;
      if (oldParent instanceof ParseNode) {
         return null;
      }
      if (oldParent instanceof ErrorParseNode) {
         return null;
      }
      if (oldParent instanceof IString)
         return null;
      ParentParseNode oldpn = (ParentParseNode) oldParent;
      if (ix < oldpn.children.size())
         return oldpn.children.get(ix);
      return null;
   }

   private boolean extendsPartialValue(Parser parser, Parselet childParselet, ParentParseNode value, boolean anyContent, int startIndex, ParseError childError) {
      if (value == null)
         return false;

      //if (parser.currentIndex == parser.currentErrorStartIndex) {
         Object nsv = ParseUtil.nodeToSemanticValue(value);
         if (nsv instanceof JavaSemanticNode) {
            JavaSemanticNode node = (JavaSemanticNode) nsv;
            for (int i = 0; i < parser.currentErrors.size(); i++) {
               ParseError err = parser.currentErrors.get(i);
               Object esv = ParseUtil.nodeToSemanticValue(err.partialValue);
               if (esv != null) {
                  if (node == esv || (anyContent && node.applyPartialValue(esv))) {
                     if (!value.isEmpty()) {
                        // Reuse the same error with the new value.  This node will also call parseEOF error
                        // but that error will be ignored caused it does not end as far back as this one.
                        // One potential benefit of doing it this way is that if more nodes end up with better
                        // final matches we'll hang onto them.  If we use the new error it would replace any other
                        // existing errors.
                        err.startIndex = parser.currentIndex;
                        err.partialValue = value;

                        return true;
                     }
                  }
               }
               else if (childParselet == err.parselet) {
                  err.startIndex = parser.currentIndex;
                  err.partialValue = value;
                  return true;
               }
            }
         }
         // The current value is a list like the return from class body and the error in this case should be the field, member etc. which is inside of it.
         else if (nsv instanceof SemanticNodeList) {
            SemanticNodeList snl = (SemanticNodeList) nsv;
            int snlSize = snl.size();
            int startIx = snl.getParseNode().getStartIndex();
            ISemanticNode lastListSemValue = null;
            for (int i = 0; i < snlSize; i++) {
               Object elem = snl.get(i);
               if (elem instanceof ISemanticNode) {
                  ISemanticNode elemNode = (ISemanticNode) elem;
                  int newIx = ((ISemanticNode) elem).getParseNode().getStartIndex();
                  if (newIx > startIx)
                     startIx = newIx;

                  if (i == snlSize - 1) {
                     lastListSemValue = elemNode;
                  }
               }
            }

            /** Ideally we can find a node in the list which can be extended with the value from this list */
            boolean res = false;
            if (anyContent && lastListSemValue instanceof JavaSemanticNode) {
               for (int i = 0; i < parser.currentErrors.size(); i++) {
                  JavaSemanticNode lastListSemNode = (JavaSemanticNode) lastListSemValue;
                  ParseError err = parser.currentErrors.get(i);
                  if (err.partialValue instanceof IParseNode) {
                     IParseNode errParseNode = (IParseNode) err.partialValue;
                     Object errSemValue = errParseNode.getSemanticValue();

                     if (lastListSemNode.applyPartialValue(errSemValue)) {
                        res = true;
                        err.partialValue = value;
                     }
                  }
               }
            }
            if (res)
               return true;

            /**
             * As another attempt to extend the lists we look for an element that can be added to the list.
             * TODO: this case should be cleaned up or potentially removed in favor of the above approach?
             */
         /*
            for (int i = 0; i < parser.currentErrors.size(); i++) {
               ParseError err = parser.currentErrors.get(i);
               Object esv = ParseUtil.nodeToSemanticValue(err.partialValue);
               if (esv != null) {
                  if (esv instanceof ISemanticNode) {
                     ISemanticNode errSemNode = (ISemanticNode) esv;
                     IParseNode errParseNode = errSemNode.getParseNode();
                     if (errSemNode.getParentNode() == null && (errParseNode != null && errParseNode.getStartIndex() > startIx)) {
                        if (!snl.contains(errSemNode) && errSemNode != snl) {
                           if (!(errSemNode instanceof List)) {
                              if (anyContent)
                                 snl.add(errSemNode);
                           }
                        }
                     }
                  }
                  /*
                  else if (PString.isString(esv)) {
                     if (lastListVal instanceof JavaSemanticNode) {
                        JavaSemanticNode lastNode = ((JavaSemanticNode) lastListVal);
                        if (lastNode.applyPartialValue(esv))
                           return true;
                     }
                  }
                  */
                  /*
                  //err.partialValue = snl;
                  // TODO: Seems like we should not always be returning true here but instead should have
                  // a mechanism to be sure this error is extended - like check if the parselet types match and
                  // if the lists match or overla - like check if the parselet types match or the lists
                  // are the same or we can tell this is an element of the new list.
                  //if (anyContent)
                  //   res = true;
                  /*
                  */
               //}
           // }
            return res;
         }
         else if (parameterType == ParameterType.INHERIT) {
            if (parser.currentIndex <= parser.currentErrorStartIndex && anyContent) {
               return true;
            }
         }

      // If the combination of this new error and the child error ends up being equal to or better than the current match, we extend
      if (anyContent && Parser.isBetterError(parser.currentErrorStartIndex, parser.currentErrorEndIndex, startIndex, childError.endIndex, true)) {
         if (optional)
            return false;
         return true;
      }

      //}
      return false;
   }

   private Object parseRepeatingSequence(Parser parser, boolean extendedErrors, Parselet exitParselet) {
      ParentParseNode value = null;
      int startIndex = parser.currentIndex;
      int lastMatchIndex;
      boolean matched;
      boolean matchedAny = false;
      int i;
      ParseError lastError = null;
      Parselet childParselet = null;
      int numParselets;
      ArrayList<Object> matchedValues;
      ArrayList<Object> errorValues = null;

      boolean anyContent;
      boolean extendedErrorMatches = false;

      do {
         lastMatchIndex = parser.currentIndex;

         matchedValues = null;

         matched = true;
         anyContent = false;
         numParselets = parselets.size();
         for (i = 0; i < numParselets; i++) {
            childParselet = parselets.get(i);
            Object nestedValue = parser.parseNext(childParselet);
            if (nestedValue instanceof ParseError) {
               matched = false;
               errorValues = matchedValues;
               matchedValues = null;
               lastError = (ParseError) nestedValue;
               break;
            }

            if (matchedValues == null)
               matchedValues = new ArrayList<Object>(); // TODO: performance set the init-size here?

            if (nestedValue != null) {
               anyContent = true;
            }

            matchedValues.add(nestedValue);
         }
         if (i == numParselets) {
            // We need at least one non-null slot in a sequence for it to match
            if (anyContent) {
               if (matchedValues != null) {
                  if (value == null)
                     value = (ParentParseNode) newParseNode(lastMatchIndex);
                  int numMatchedValues = matchedValues.size();
                  for (i = 0; i < numMatchedValues; i++) {
                     Object nv = matchedValues.get(i);
                     //if (nv != null) // need an option to preserve nulls?
                     value.add(nv, parselets.get(i), -1, i, false, parser);
                  }
               }
               matched = true;
               matchedAny = true;
            }
            else
               matched = false;
         }
         else if (!matched) {
            if (extendedErrors) {
               // Restore the current index back to the lastMatchIndex - back track over any partial matches in the sequence for this entry
               parser.changeCurrentIndex(lastMatchIndex);
               lastError = null;

               // Consume the next skip token if we have not hit the exit, then retry
               if (!exitParselet.peek(parser)) {
                  int errorStart = parser.currentIndex;
                  // This will consume whatever it is that we can't parse until we get to the next statement.  We have to be careful with the
                  // design of the skipOnErrorParselet so that it leaves us in the state for the next match on this choice.  It should not breakup
                  // an identifier for example.
                  Object errorRes = parser.parseNext(skipOnErrorParselet);
                  if (errorRes instanceof ParseError) {
                     // We never found the exitParselet - i.e. > so the parent sequence will fail just like it did before.
                     // When this method returns null, we go with the result we produced in the first call to parseRepeatingSequence method.
                     return null;
                  }

                  if (value == null)
                     value = (ParentParseNode) newParseNode(lastMatchIndex);
                  value.add(new ErrorParseNode(new ParseError(skipOnErrorParselet, "Expected {0}", new Object[]{this}, errorStart, parser.currentIndex), errorRes.toString()), skipOnErrorParselet, -1, -1, true, parser);
                  extendedErrorMatches = true;
                  matched = true;
               }
               else {   // Found the exit parselet is next in the stream so we successfully consumed some error nodes so reset the lastMatchIndex to include them
                  lastMatchIndex = parser.currentIndex;
                  matchedAny = extendedErrorMatches;
               }
            }
         }
      } while (matched);

      if (!matchedAny) {
         if (parser.enablePartialValues && !lookahead && lastError != null) {
            Object pv = lastError.partialValue;
            if ((pv != null || !optional) && !childParselet.getLookahead()) {
               if (errorValues == null)
                  errorValues = new ArrayList<Object>();
               errorValues.add(pv);
               // Add these null slots so that we do all of the node processing
               value = newRepeatSequenceResult(errorValues, value, lastMatchIndex, parser);
               return parsePartialError(parser, value, lastError, childParselet, "Partial array match: {0} ", this);
            }
            if (pv == null && optional && anyContent) {
               value = newRepeatSequenceResult(errorValues, value, lastMatchIndex, parser);
               ParseError err = parsePartialError(parser, value, lastError, childParselet, "Optional continuation: {0} ", this);
               err.optionalContinuation = true;
               return err;
            }
         }

         if (optional) {
            parser.changeCurrentIndex(startIndex);
            return null;
         }
         return parseError(parser, "Expecting one or more of {0}", this);
      }
      else {
         if (parser.enablePartialValues && !lookahead && lastError != null) {
            Object pv = lastError.partialValue;
            // IF we partially matched the next sequence, we need to indicate the partial value
            // for this error if it's not already set.
            if (pv == null) {
               if (errorValues != null) {
                  ParentParseNode errVal = cloneParseNode(((ParentParseNode) value)) ;
                  lastError.partialValue = newRepeatSequenceResult(errorValues, errVal, lastMatchIndex, parser);
               }
               else
                   lastError.partialValue = value;
               // Instead of producing a value for this fragment and messing up the parse node for what might be a valid match
               // we mark this error as a "continuation".  This flag can then be used in the suggestCompletions
               // method to take into account the fact that there's an extra '.' at the end (or whatever)
               lastError.continuationValue = true;
            }
            /*
            if (pv != null && !childParselet.getLookahead()) {
               value = (ParentParseNode) newParseNode(lastMatchIndex);
               if (errorValues == null)
                  errorValues = new ArrayList<Object>();
               errorValues.add(pv);
               // Add these null slots so that we do all of the node processing
               for (int k = 0; k < numParselets; k++) {
                  Object nv;
                  if (errorValues.size() > k)
                     nv = errorValues.get(k);
                  else
                     nv = null;
                  value.add(nv, parselets.get(k), k, false, parser);
               }
               return parsePartialError(parser, value, lastError, childParselet, "Partial array match: {0} ", this);
            }
            */
         }
         parser.changeCurrentIndex(lastMatchIndex);
      }

      String customError;
      if ((customError = accept(parser.semanticContext, value, startIndex, parser.currentIndex)) != null)
         return parseError(parser, customError, value, this);

      if (lookahead)
         parser.changeCurrentIndex(startIndex);
      return value;
   }

   private Object reparseRepeatingSequence(Parser parser, boolean extendedErrors, Parselet exitParselet, Object oldParseNode, DiffContext dctx, boolean forceReparse) {
      ParentParseNode value = null;
      int startIndex = parser.currentIndex;
      int lastMatchIndex;
      boolean matched;
      boolean matchedAny = false;
      int i;
      ParseError lastError = null;
      Parselet childParselet = null;
      int numParselets;
      int numMatchedValues = 0;
      ArrayList<Object> matchedValues;
      ArrayList<Object> errorValues = null;
      Object oldChildParseNode = null;

      ParentParseNode oldParent = oldParseNode instanceof ParentParseNode ? (ParentParseNode) oldParseNode : null;
      // This parent is not for our parselet
      if (oldParent != null && !producesParselet(oldParent.parselet)) {
         oldParent = null;
         forceReparse = true;
      }

      boolean anyContent;
      boolean extendedErrorMatches = false;

      int newChildCount = 0;
      int svCount = 0;

      do {
         lastMatchIndex = parser.currentIndex;

         matchedValues = null;
         numMatchedValues = 0;

         matched = true;
         anyContent = false;
         numParselets = parselets.size();
         for (i = 0; i < numParselets; i++) {
            childParselet = parselets.get(i);

            int childIx = newChildCount + numMatchedValues;


            if (oldParent == null || oldParent.children == null) {
               oldChildParseNode = null;
               forceReparse = true;
            }
            else if (oldParent.children.size() > childIx) {
               oldChildParseNode = oldParent.children.get(childIx);

               if (dctx.sameAgain && oldChildParseNode instanceof IParseNode) {
                  IParseNode oldChildPN = (IParseNode) oldChildParseNode;
                  if (oldChildPN.getOrigStartIndex() < dctx.endChangeOldOffset) {
                     oldChildParseNode = null;
                     forceReparse = true;
                  }
               }
            }
            else {
               oldChildParseNode = null;
               forceReparse = true;
            }

            Object nestedValue = parser.reparseNext(childParselet, oldChildParseNode, dctx, forceReparse);
            if (nestedValue instanceof ParseError) {
               matched = false;
               errorValues = matchedValues;
               matchedValues = null;
               lastError = (ParseError) nestedValue;
               break;
            }

            if (matchedValues == null)
               matchedValues = new ArrayList<Object>(); // TODO: performance set the init-size here?

            if (nestedValue != null) {
               anyContent = true;
            }

            matchedValues.add(nestedValue);
            numMatchedValues++;
         }
         if (i == numParselets) {
            // We need at least one non-null slot in a sequence for it to match
            if (anyContent) {
               if (matchedValues != null) {
                  if (value == null)
                     value = resetOldParseNode(forceReparse ? null : oldParent, lastMatchIndex, false, false);
                  for (i = 0; i < numMatchedValues; i++) {
                     Object nv = matchedValues.get(i);
                     oldChildParseNode = oldParent == null || i >= oldParent.children.size() ? null : oldParent.children.get(i);
                     //if (nv != null) // need an option to preserve nulls?
                     if (value.addForReparse(nv, parselets.get(i), svCount, newChildCount++, i, false, parser, oldChildParseNode, dctx, true, true))
                        svCount++;
                  }
               }
               matched = true;
               matchedAny = true;
            }
            else
               matched = false;
         }
         else if (!matched) {
            if (extendedErrors) {
               // Restore the current index back to the lastMatchIndex - back track over any partial matches in the sequence for this entry
               dctx.changeCurrentIndex(parser, lastMatchIndex);
               lastError = null;

               // Consume the next skip token if we have not hit the exit, then retry
               if (!exitParselet.peek(parser)) {
                  int errorStart = parser.currentIndex;
                  System.err.println("*** Warning - weird case extending errors - which is oldChildParseNode right?");
                  oldChildParseNode = oldParent == null ? null : oldParent.children.get(oldParent.children.size()-1);
                  // This will consume whatever it is that we can't parse until we get to the next statement.  We have to be careful with the
                  // design of the skipOnErrorParselet so that it leaves us in the state for the next match on this choice.  It should not breakup
                  // an identifier for example.
                  Object errorRes = parser.reparseNext(skipOnErrorParselet, oldChildParseNode, dctx, forceReparse);
                  if (errorRes instanceof ParseError) {
                     // We never found the exitParselet - i.e. > so the parent sequence will fail just like it did before.
                     // When this method returns null, we go with the result we produced in the first call to parseRepeatingSequence method.
                     return null;
                  }

                  if (value == null) {
                     value = resetOldParseNode(forceReparse ? null : oldParent, lastMatchIndex, false, false);
                  }
                  value.addForReparse(new ErrorParseNode(new ParseError(skipOnErrorParselet, "Expected {0}", new Object[]{this}, errorStart, parser.currentIndex), errorRes.toString()), skipOnErrorParselet, newChildCount, newChildCount, -1, true, parser, oldChildParseNode, dctx, true, true);
                  extendedErrorMatches = true;
                  matched = true;
               }
               else {   // Found the exit parselet is next in the stream so we successfully consumed some error nodes so reset the lastMatchIndex to include them
                  lastMatchIndex = parser.currentIndex;
                  matchedAny = extendedErrorMatches;
               }
            }
         }
      } while (matched);

      if (!matchedAny) {
         if (parser.enablePartialValues && !lookahead && lastError != null) {
            Object pv = lastError.partialValue;
            if ((pv != null || !optional) && !childParselet.getLookahead()) {
               if (errorValues == null)
                  errorValues = new ArrayList<Object>();
               errorValues.add(pv);
               // Temporarily advance the index so parser.currentIndex is where we ended parsing this error.  We use parser.currentIndex here to
               // determine whether to insert or replace the parse node.  We'll reset current index in parsePartialError anyway.
               dctx.changeCurrentIndex(parser, lastError.endIndex);
               // Add these null slots so that we do all of the node processing
               value = newRepeatSequenceResultReparse(errorValues, value, svCount, newChildCount, lastMatchIndex, parser, oldParent, dctx);
               newChildCount += errorValues.size();

               // If we are reparsing once we've added the error values, we should remove any thing left over that was not reparsed.
               if (oldParent == value) {
                  removeChildrenForReparse(parser, value, newChildCount);
               }

               return reparsePartialError(parser, dctx, value, lastError, childParselet, "Partial array match: {0} ", this);
            }
            if (pv == null && optional && anyContent) {
               value = newRepeatSequenceResultReparse(errorValues, value, svCount, newChildCount, lastMatchIndex, parser, oldParent, dctx);
               newChildCount += errorValues.size();

               if (oldParent == value) {
                  removeChildrenForReparse(parser, value, newChildCount);
               }

               ParseError err = reparsePartialError(parser, dctx, value, lastError, childParselet, "Optional continuation: {0} ", this);
               err.optionalContinuation = true;
               return err;
            }
         }

         if (optional) {
            dctx.changeCurrentIndex(parser, startIndex);
            return null;
         }
         return reparseError(parser, dctx, "Expecting one or more of {0}", this);
      }
      else {
         if (parser.enablePartialValues && !lookahead && lastError != null) {
            Object pv = lastError.partialValue;
            // IF we partially matched the next sequence, we need to indicate the partial value
            // for this error if it's not already set.
            if (pv == null) {
               if (errorValues != null) {
                  ParentParseNode errVal = cloneParseNode(((ParentParseNode) value)) ;
                  lastError.partialValue = newRepeatSequenceResultReparse(errorValues, errVal, svCount, newChildCount, lastMatchIndex, parser, oldParent, dctx);
               }
               else
                  lastError.partialValue = value;
               // Instead of producing a value for this fragment and messing up the parse node for what might be a valid match
               // we mark this error as a "continuation".  This flag can then be used in the suggestCompletions
               // method to take into account the fact that there's an extra '.' at the end (or whatever)
               lastError.continuationValue = true;
            }
            /*
            if (pv != null && !childParselet.getLookahead()) {
               value = (ParentParseNode) newParseNode(lastMatchIndex);
               if (errorValues == null)
                  errorValues = new ArrayList<Object>();
               errorValues.add(pv);
               // Add these null slots so that we do all of the node processing
               for (int k = 0; k < numParselets; k++) {
                  Object nv;
                  if (errorValues.size() > k)
                     nv = errorValues.get(k);
                  else
                     nv = null;
                  value.add(nv, parselets.get(k), k, false, parser);
               }
               return parsePartialError(parser, value, lastError, childParselet, "Partial array match: {0} ", this);
            }
            */
         }
         dctx.changeCurrentIndex(parser, lastMatchIndex);
      }

      if (oldParent == value) {
         removeChildrenForReparse(parser, value, newChildCount);
      }

      String customError;
      if ((customError = accept(parser.semanticContext, value, startIndex, parser.currentIndex)) != null)
         return reparseError(parser, dctx, customError, value, this);

      if (lookahead)
         dctx.changeCurrentIndex(parser, startIndex);
      return value;
   }

   public Object parseExtendedErrors(Parser parser, Parselet exitParselet) {
      if (skipOnErrorParselet == null)
         return null;
      return parseRepeatingSequence(parser, true, exitParselet);
   }

   public Object reparseExtendedErrors(Parser parser, Parselet exitParselet, Object oldChildNode, DiffContext dctx, boolean forceReparse) {
      if (skipOnErrorParselet == null)
         return null;
      return reparseRepeatingSequence(parser, true, exitParselet, oldChildNode, dctx, forceReparse);
   }

   ParentParseNode newRepeatSequenceResult(ArrayList<Object> matchedValues, ParentParseNode value,
                                           int lastMatchIndex, Parser parser) {
      if (matchedValues != null) {
         if (value == null)
            value = (ParentParseNode) newParseNode(lastMatchIndex);
         int numMatchedValues = matchedValues.size();
         int numParselets = parselets.size();
         for (int i = 0; i < numParselets; i++) {
            Object nv = i < numMatchedValues ?  matchedValues.get(i) : null;
            value.add(nv, parselets.get(i), -1, i, false, parser);
         }
      }
      return value;
   }

   ParentParseNode newRepeatSequenceResultReparse(ArrayList<Object> matchedValues, ParentParseNode value,
                                                  int svCount, int newChildCount, int lastMatchIndex, Parser parser, ParentParseNode oldParent, DiffContext dctx) {
      if (matchedValues != null) {
         if (value == null) {
            value = resetOldParseNode(oldParent, lastMatchIndex, false, false);
         }
         int numMatchedValues = matchedValues.size();
         int numParselets = parselets.size();
         for (int i = 0; i < numParselets; i++) {
            Object nv = i < numMatchedValues ?  matchedValues.get(i) : null;
            Object oldChildParseNode = oldParent == null || i >= oldParent.children.size() ? null : oldParent.children.get(i);
            if (value.addForReparse(nv, parselets.get(i), svCount, newChildCount++, i, false, parser, oldChildParseNode, dctx, true, true))
               svCount++;
         }
      }
      return value;
   }

   protected static final GenerateError INVALID_TYPE_IN_CHAIN = new GenerateError("Chained property - next item in sequence did not match expected item type.");
   protected static final GenerateError MISSING_ARRAY_VALUE = new GenerateError("Missing array value");
   protected static final GenerateError ACCEPT_ERROR = new GenerateError("Accept method failed");

   public Object generate(GenerateContext ctx, Object value) {
      //if (trace)
      //    System.out.println("*** Generating traced element");

      if (optional && emptyValue(ctx, value))
          return generateResult(ctx, null);

      // during generate, we may get a null semantic value and yet still need to generate something, like for a Keyword or something.
      // If we are optional, returning null here, not an error.  That does not seem 100% correct but there are cases like for generating i++
      // which need to return null when encountering an optional value which matches
      if (value != null && !dataTypeMatches(value))
         return optional ? generateResult(ctx, null) : ctx.error(this, SLOT_DID_NOT_MATCH, value, 0);

      String acceptError = accept(ctx.semanticContext, value, -1, -1);
      if (acceptError != null) {
         return ctx.error(this, ACCEPT_ERROR, value, 0);
      }

      ParentParseNode pnode = null;
      int progress = 0;

      if (getSemanticValueIsArray()) { // Repeat but not chained
         // TODO: is this chunk needed anymore because of the test above?
         if (value == null) {
            if (optional)
               return generateResult(ctx, null);
            else
               return ctx.error(this, MISSING_ARRAY_VALUE, value, progress);
         }

         if (!(value instanceof List)) {
            if (PString.isString(value)) {
               IString strValue = PString.toIString(value);
               while (strValue != null && strValue.length() > 0) {
                  if (pnode == null)
                     pnode = newGeneratedParseNode(value);
                  Object newVal = generateOne(ctx, pnode, strValue);
                  if (newVal instanceof GenerateError) {
                     if (optional)
                        break;
                     pnode.setSemanticValue(null, true);
                     return newVal;
                  }
                  else if (newVal == null)
                     strValue = null;
                  else {
                     strValue = PString.toIString(newVal);
                     progress += ctx.progress(strValue);
                  }
               }
            }
            else
               throw new IllegalArgumentException("Repeat node received non-list value to generate: " + value);
         }
         else if (parameterType != ParameterType.ARRAY) {
            pnode = newGeneratedParseNode(value);
            List nodeList = (List) value;
            int nodeListSize = nodeList.size();
            for (int i = 0; i < nodeListSize; i++) {
               Object arrVal = generateOne(ctx, pnode, nodeList.get(i));
               if (arrVal instanceof GenerateError) {
                  pnode.setSemanticValue(null, true);
                  ((GenerateError)arrVal).progress += progress;
                  return arrVal;
               }
               else
                  progress += ctx.progress(pnode);
            }
         }
         else {
            pnode = newGeneratedParseNode(value);
            SemanticNodeList nodeList = (SemanticNodeList) value;
            int numProcessed = 0;
            do {
               Object arrVal = generateOne(ctx, pnode, nodeList);
               if (arrVal instanceof PartialArrayResult) {
                  PartialArrayResult par = (PartialArrayResult) arrVal;
                  pnode.copyGeneratedFrom(par.resultNode);
                  progress += ctx.progress(par.resultNode);
                  nodeList = getRemainingValue(nodeList, par.numProcessed);
                  numProcessed += par.numProcessed;
               }
               else if (arrVal instanceof GenerateError) {
                  if (numProcessed != 0)
                     return new PartialArrayResult(numProcessed, pnode, (GenerateError)arrVal);
                  else {
                     pnode.setSemanticValue(null, true);
                     ((GenerateError)arrVal).progress += progress;
                     return arrVal;
                  }
               }
               else if (arrVal == null) {
                  if (numProcessed != 0)
                     return new PartialArrayResult(numProcessed, pnode, null);
                  return generateResult(ctx, pnode);
               }
               else
                  return generateResult(ctx, pnode);

            } while(nodeList.size() > 0);
         }
      }
      else if (chainedPropertyMappings != null) {
         Object nextValue = value;
         ParentParseNode lastParseNode = null;
         ParentParseNode origParseNode;

         origParseNode = pnode = newGeneratedParseNode(value);

         do {
            if (nextValue != null &&
                    !getSemanticValueClass().isInstance(nextValue)) {
               if (pnode != null)
                  pnode.setSemanticValue(null, true);

               return ctx.error(this, INVALID_TYPE_IN_CHAIN, nextValue, progress);
            }

            ctx.maskProperty(nextValue, chainedPropertyMappings[0], null);

            Object res = generateOne(ctx, pnode, nextValue);

            Object chainValue = ctx.getPropertyValue(nextValue, chainedPropertyMappings[1]);

            /* It failed to match the entire chain - so reset the next link and try 
             * again.
             */
            if (res instanceof GenerateError &&
                chainValue != null && getSemanticValueClass().isInstance(chainValue)) {
               Object chainValueLHS = ctx.getPropertyValue(chainValue, chainedPropertyMappings[0]);
               ctx.maskProperty(nextValue, chainedPropertyMappings[1], chainValueLHS);

               res = generateOne(ctx, pnode, nextValue);

               ctx.unmaskProperty(nextValue, chainedPropertyMappings[1]);
            }
            else
               chainValue = null;

            ctx.unmaskProperty(nextValue, chainedPropertyMappings[0]);

            if (res instanceof GenerateError) {
               pnode.setSemanticValue(null, true);
               if (optional && emptyValue(ctx, value)) {
                  return generateResult(ctx, null);
               }
               return res;
            }

            nextValue = chainValue;
            if (lastParseNode != null) {
               lastParseNode.addGeneratedNode(pnode);
            }
            if (nextValue != null) {
               lastParseNode = pnode;
               progress += ctx.progress(pnode);
               pnode = newGeneratedParseNode(chainValue);
            }
         } while (nextValue != null);

         if (nextValue == null)
            return generateResult(ctx, origParseNode);

         if (pnode != null)
            pnode.setSemanticValue(null, true);

         return ctx.error(this, NO_OBJECT_FOR_SLOT, value, progress);
      }
      else {
         if (pnode == null)
            pnode = newGeneratedParseNode(value);

         Object res = generateOne(ctx, pnode, value);
         if (res instanceof GenerateError) {
            pnode.setSemanticValue(null, true);
            if (optional && emptyValue(ctx, value))
               return generateResult(ctx, null);
            return res;
         }
         else if (res instanceof PartialArrayResult)
            return res;
         // If we did not matching anything, do not associate this parse node with the semantic value.  This
         // acts as a signal to the caller that the value was not consumed, so it won't advance the array.
         else if (res == null)
            pnode.setSemanticValue(null, true);
      }
      return generateResult(ctx, pnode);
   }

   private final static GenerateError BAD_TYPE_FOR_SLOT = new GenerateError("Type received is not expected for this slot");
   private final static GenerateError NO_OBJECT_FOR_SLOT = new GenerateError("Null parentNode object for slot");
   private final static GenerateError SCALAR_FOR_ARRAY_SLOT = new GenerateError("Scalar value supplied for array slot");
   private final static GenerateError ARRAY_TOO_SMALL = new GenerateError("Missing element in array value");
   private final static GenerateError SLOT_DID_NOT_MATCH = new GenerateError("Part of this sequence did not match");
   private final static GenerateError PARTIAL_ARRAY_PROPERTY_MATCH = new GenerateError("Array property value only partially matched");

   public Object generateElement(GenerateContext ctx, Object value, boolean isArray) {
      ParentParseNode pnode = new ParentParseNode();
      Object x = generateOne(ctx, pnode, value);
      // validate that x is empty - i.e. all of the value was generated?
      return pnode;
   }

   private Object generateOne(GenerateContext ctx, ParentParseNode pnode, Object value) {
      int arrayIndex = 0;
      SemanticNodeList nodeList = parameterType != ParameterType.PROPAGATE && value instanceof List ? (SemanticNodeList) value : null;
      GenerateError bestError = null;

      boolean isValueString = PString.isString(value);

      //if (trace)
      //   System.out.println("*** Generating a traced element");

      ParentParseNode tnode = new ParentParseNode(this);
      int progress = 0;

      int numParselets = parselets.size();
      for (int i = 0; i < numParselets; i++) {
         Parselet childParselet = parselets.get(i);
         Object childValue = null;
         boolean mappingSkip = false;
         boolean processed = false;
         Object childNode = null;
         Object childNodeSuffix = null;
         boolean skipChild = false;
         int numProcessed = 0;
         boolean simpleStringNode = false;

         if (genMapping != null) {
            String mapStr = genMapping[i];
            int dotIx = mapStr.indexOf(".");
            if (dotIx != -1) {
               if (!mapStr.equals(".")) {
                  String prefix = dotIx == 0 ? null : mapStr.substring(0,dotIx);
                  childNodeSuffix = dotIx == mapStr.length() - 1 ? null : mapStr.substring(dotIx+1);
                  if (prefix != null)
                     tnode.addGeneratedNode(prefix, childParselet);
               }
            }
            else if (mapStr.length() == 0)
               mappingSkip = true;
            else {
               childNode = genMapping[i];
               processed = true;
            }
         }

         if (!processed) {
            if (parameterMapping == null || parameterMapping[i] == ParameterMapping.SKIP || mappingSkip) {
               mappingSkip = true;
               childValue = null;
            }
            else {
               switch (parameterMapping[i]) {
                  case STRING:
                  case PROPAGATE:
                  case INHERIT:
                     nodeList = null;
                     childValue = value;
                     break;
                  case ARRAY:
                     if (!getSemanticValueIsArray()) {
                        // If we are not a repeat node and the child is also a scalar, we need to pull apart the
                        // array value we are given and hand it element by element to the child node to generate.
                        // If we are a repeat node, we already have done this in "generate".
                        if (value != null && !ParseUtil.isArrayParselet(childParselet)) {
                           if (nodeList == null) {
                              if (isValueString) {
                                 IString strValue = PString.toIString(value);
                                 if (strValue != null && strValue.length() > 0) {
                                    Object newVal = ctx.generateChild(childParselet, strValue);
                                    if (newVal instanceof GenerateError) {
                                       if (emptyValue(ctx, strValue))
                                          break;
                                       ((GenerateError)newVal).progress += progress;
                                       return newVal;
                                    }
                                    else if (newVal == null)
                                       strValue = null;
                                    else {
                                       tnode.addGeneratedNode(newVal, childParselet);
                                       int newLen = PString.toIString(newVal).length();
                                       strValue = strValue.substring(newLen);
                                       progress += newLen;
                                    }
                                 }
                                 skipChild = true; // We processed this node so do not generate it below
                              }
                              else
                                 return SCALAR_FOR_ARRAY_SLOT;
                           }
                           else {
                              Object nodeValue;
                              if (arrayIndex >= nodeList.size()) {
                                 if (arrayIndex == 0)
                                    nodeValue = null;
                                 else
                                    return optional && emptyValue(ctx, value) ? null : ARRAY_TOO_SMALL;
                              }
                              else {
                                 nodeValue = nodeList.get(arrayIndex);
                              }


                              Object nodeVal;
                              if (nodeValue instanceof String && ctx.finalGeneration) {
                                 Object res = childParselet.language.parseString((String) childValue, childParselet);
                                 if (!(res instanceof ParseError))
                                    nodeVal = newGeneratedSimpleParseNode(childValue, childParselet);
                                 else {
                                    if (childParselet.optional && childParselet.emptyValue(ctx, childValue)) {
                                       nodeVal = null;
                                    }
                                    else
                                       nodeVal = ctx.error(this, SLOT_DID_NOT_MATCH, value, progress);
                                 }
                              }
                              else {
                                 nodeVal =  ctx.generateChild(childParselet, nodeValue);
                              }

                              if (nodeVal instanceof GenerateError) {
                                 ((GenerateError)nodeVal).progress += progress;
                                 return optional &&
                                        emptyValue(ctx, getRemainingValue(nodeList, arrayIndex)) ? null : nodeVal;
                              }
                              // Make sure we actually consume the array value here.  If the parselet is optional, it could return an empty match instead
                              else if (nodeVal != null && (!(nodeVal instanceof IParseNode) || ((IParseNode) nodeVal).getSemanticValue() == nodeValue))
                                 arrayIndex++;

                              tnode.addGeneratedNode(nodeVal, childParselet);
                              progress += ctx.progress(nodeVal);

                              skipChild = true; // We processed this node so do not generate it below
                           }
                        }
                        else {
                           childValue = getRemainingValue(nodeList, arrayIndex);
                           if (childValue != null)
                              numProcessed = nodeList.size() - arrayIndex;
                        }
                     }
                     else {
                        if (nodeList != null) {
                           // If we are not a repeat node and the child is also a scalar, we need to pull apart the
                           // array value we are given and hand it element by element to the child node to generate.
                           // If we are a repeat node, we already have done this in "generate".
                           if (value != null && !ParseUtil.isArrayParselet(childParselet)) {
                              if (arrayIndex >= nodeList.size())
                                 childValue = null;
                              else {
                                 childValue = ((List) value).get(arrayIndex);
                                 numProcessed = 1;
                              }
                           }
                           else {
                              childValue = getRemainingValue((SemanticNodeList) value, arrayIndex);
                              if (childValue != null)
                                 numProcessed = nodeList.size() - arrayIndex;
                           }
                        }
                        else if (isValueString) {
                           childValue = value; // Try sending the remaining string to the child parselet
                        }
                        // We have a scalar value so if the child is also a scalar, we'll just pass it right along
                        else if (!ParseUtil.isArrayParselet(childParselet)) {
                           childValue = value;
                        }
                        else {
                           System.err.println("*** Unhandled case in array parselet regeneration");
                        }
                     }
                     break;

                  case NAMED_SLOT:
                     if (value == null || value instanceof String)
                        return ctx.error(this, BAD_TYPE_FOR_SLOT, value, progress);

                     Class svClass = getSlotClass();
                     Object cvtValue = value;
                     if (svClass == null || 
                        (cvtValue = ParseUtil.convertSemanticValue(svClass, value)) != ParseUtil.CONVERT_MISMATCH) {
                        childValue = ctx.getPropertyValue(cvtValue, slotMapping[i]);
                        if (!isValueString && childValue instanceof String && ctx.finalGeneration) {
                           simpleStringNode = true;
                        }
                     }
                     else
                        return optional ? null : ctx.error(this, BAD_TYPE_FOR_SLOT, value, progress);
                     break;
               }
            }

            if (!skipChild) {
               if (simpleStringNode) {
                  Object res = childParselet.language.parseString((String) childValue, childParselet);
                  if (!(res instanceof ParseError))
                     childNode = newGeneratedSimpleParseNode(childValue, childParselet);
                  else {
                     if (childParselet.optional && childParselet.emptyValue(ctx, childValue)) {
                        childNode = null;
                     }
                     else
                        childNode = ctx.error(this, SLOT_DID_NOT_MATCH, value, progress);
                  }
               }
               else {
                  childNode = ctx.generateChild(childParselet, childValue);
               }
               if (childNode instanceof PartialArrayResult) {
                  PartialArrayResult par = (PartialArrayResult) childNode;
                  arrayIndex += par.numProcessed;
                  childNode = par.resultNode;
                  progress += ctx.progress(par.resultNode);
                  if (par.error != null)
                     bestError = par.error;

                  // If we propagate our value and get back a partial result, we need to return the PartialArrayResult
                  // ourself below.
                  if (nodeList == null) {
                     if (!(value instanceof SemanticNodeList))
                        // TODO: the problem here is that childValue is an array and the numProcessed index failed
                        // to match.  Might be helpful to include that in the error.
                        return par.error == null ? ctx.error(this, PARTIAL_ARRAY_PROPERTY_MATCH, value, progress) : par.error;

                     nodeList = (SemanticNodeList) value;
                  }
               }
               else if (childNode instanceof GenerateError) {
                  // For strings, arrays or an empty value, we just return null.  The parent has to know we did not consume
                  // any value.
                  if (optional) {
                     if (emptyValue(ctx, childValue)) {
                        return null;
                     }
                     else {
                        if (isValueString || nodeList != null)
                           return null;
                        else {
                           return null;
                        }
                     }
                  }
                  ((GenerateError)childNode).progress += progress;
                  return childNode;
               }
               // If we are matching a child node from a slot and the value we pass is a string and the child node
               // does not consume all of the string, that is an error.
               else if (parameterMapping != null && parameterMapping[i] == ParameterMapping.NAMED_SLOT &&
                        PString.isString(childValue)) {
                  int nodeLen = childNode == null ? 0 : ParseUtil.toSemanticString(childNode).length();
                  if (nodeLen < PString.toIString(childValue).length())
                     return ctx.error(this, SLOT_DID_NOT_MATCH, childValue, progress);
               }
               // Only increment the array index if we processed the array elements.
               else if (childNode != null && !mappingSkip && nodeList != null && (!(childNode instanceof IParseNode) || !((IParseNode) childNode).isEmpty()))
                  arrayIndex += numProcessed;
               // If we have a null value for a sequence which allows null values, this is a match and we need to consume the element.
               else if (allowNullElements && !mappingSkip && childValue == null && childNode == null && nodeList != null)
                  arrayIndex += numProcessed;
               else if (childNode == null && !emptyValue(ctx, childValue) && parameterMapping != null && parameterMapping[i] == ParameterMapping.NAMED_SLOT) {
                  return ctx.error(this, SLOT_DID_NOT_MATCH, childValue, progress);
               }
               // We have an optional child node which did not consume the value.  If we too are optional, return null
               //else if (childNode instanceof IParseNode && ((IParseNode)childNode).getSemanticValue() == null && childValue != null && nodeList == null) {
               //   childNode = null;
               //}
            }
            else
               childNode = null;
         }

         tnode.addGeneratedNode(childNode, childParselet);
         progress += ctx.progress(childNode);
         if (childNodeSuffix != null)
            tnode.addGeneratedNode(childNodeSuffix, childParselet);

         /*
          * We have a string semantic value... in case it was formed from more than one parselet
          * we need to pull out the part which was just generated before passing the next chunk on.
          */
         if (!mappingSkip && isValueString && childNode != null) {
            IString strValue = PString.toIString(value);
            int childNodeLen = ParseUtil.toSemanticStringLength(childNode);
            if (strValue.length() >= childNodeLen) {
               strValue = strValue.substring(childNodeLen);
               value = strValue;
            }
            else
               value = null;
         }
      }

      if (nodeList != null && arrayIndex < nodeList.size())
         return new PartialArrayResult(arrayIndex, tnode, bestError);
      else {
         pnode.copyGeneratedFrom(tnode);
         return value;
      }
   }

   protected GenerateError regenerateElement(ParentParseNode pnode, Parselet childParselet, Object element, int parseNodeIx, ChangeType changeType) {
      if (parselets.size() == 1)
         return regenerateChild(pnode, childParselet, element, parseNodeIx, changeType);

      Object childResult = changeType == ChangeType.REMOVE ? null :
              generateElement(getLanguage().newGenerateContext(this, element), element, true);
      if (!(childResult instanceof GenerateError)) {
         int num = parselets.size();
         int pos = parseNodeIx * num;
         if (childResult != null) {
            List<Object> newChildren = ((ParentParseNode)childResult).children;
            if (changeType == ChangeType.REPLACE) {
               for (int i = pos; i < num; i++)
                  pnode.children.set(i, newChildren.get(i));
            }
            else if (changeType == ChangeType.ADD) {
               if (pnode.children == null)
                  pnode.children = new ArrayList<Object>();
               pnode.children.addAll(Math.min(pos,pnode.children.size()), newChildren);
            }
         }
         if (changeType == ChangeType.REMOVE) {
            for (int i = pos + num - 1; i >= pos; i--) {
               if (i < pnode.children.size())
                  pnode.children.remove(i);
               else {
                  return new GenerateError("Parse node is out of whack with child index!");
               }
            }
         }
         return null;
      }
      if (childResult == null)
         return new GenerateError("Not able to generate node");
      return (GenerateError) childResult;
   }

   public Parselet getChildParselet(Object parseNode, int index) {
      return parselets.get(index % parselets.size());
   }

   /**
    * When we are trying to determine if a given node is produced by a given parselet, we use this method.
    * It returns true if this parselet could have produced this data type.
    */
   public boolean dataTypeMatches(Object other) {
      if (super.dataTypeMatches(other))
         return true;

      Class svClass = getSemanticValueClass();

      // Already verified that other is not a String - the default type in super.dataTypeMatches
      if (svClass == null) {
         return false;
      }

      // Could not be a match - the data types do not match
      if (!svClass.isInstance(other))
         return false;

      // In this case the specific type of the class
      if (parameterType == ParameterType.INHERIT)
         return true;

      if (parselets == null)
         return false;
      int sz = parselets.size();
      for (int i = 0; i < sz; i++) {
         Parselet child = parselets.get(i);
         if (parameterMapping == null)
            continue;
         switch (parameterMapping[i]) {
            case PROPAGATE:
               return child.dataTypeMatches(other);
         }
      }

      // We have no children which could produce this type so it is not a match
      return false;
   }

   /**
    * When we are trying to determine if a given node is produced by a given parselet, we use this method.
    * It returns true if this parselet could have produced this data type.
    */
   public boolean elementTypeMatches(Object other) {
      if (super.elementTypeMatches(other))
         return true;

      Class svClass = getSemanticValueComponentClass();

      // Could not be a match - the data types do not match
      if (!svClass.isInstance(other))
         return false;

      // In this case the specific type of the class
      if (parameterType == ParameterType.INHERIT)
         return true;

      if (parselets == null)
         return false;
      int sz = parselets.size();
      for (int i = 0; i < sz; i++) {
         Parselet child = parselets.get(i);
         if (parameterMapping == null)
            continue;
         switch (parameterMapping[i]) {
            case ARRAY:
               return child.dataTypeMatches(other);
            case PROPAGATE:
               return child.elementTypeMatches(other);
         }
      }

      // We have no children which could produce this type so it is not a match
      return false;
   }

}
