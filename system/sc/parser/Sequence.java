/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.SemanticNodeList;
import sc.lang.java.JavaSemanticNode;

import java.util.ArrayList;
import java.util.List;

public class Sequence extends NestedParselet  {
   /** For a Sequence which has all optional children, should the value of this sequence be an empty object? (acceptNoContentn=true) or
    * return null as the value for the sequence, the default. */
   boolean acceptNoContent = false;

   public Sequence() { super(); }

   public Sequence(String id, int options)
   {
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

   public Object parse(Parser parser) {
      if (trace)
         System.out.println("*** tracing sequence parse");

      if (repeat)
         return parseRepeatingSequence(parser);

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

            // If we are optional and looking for partial values, this optional error may be helpful
            // in stitching things together.
            if (optional && (!parser.enablePartialValues || parser.currentIndex != parser.currentErrorStartIndex)) {
               parser.changeCurrentIndex(startIndex);
               return null;
            }

            if (parser.enablePartialValues) {
               ParseError err = (ParseError) nestedValue;
               Object pv = err.partialValue;
               // Always call this to try and extend the current error... also see if we can generate a new error
               if (!childParselet.getLookahead()) {

                  // First complete the value with any partial value from this error and nulls for everything
                  // else.
                  if (value == null)
                     value = (ParentParseNode) newParseNode(startIndex);
                  value.add(pv, childParselet, i, false, parser);
                  // Add these null slots so that we do all of the node processing
                  for (int k = i+1; k < numParselets; k++)
                     value.add(null, parselets.get(k), k, false, parser);

                  // Now see if this computed value extends any of our most specific errors.  If so, this error
                  // can be used to itself extend other errors based on the EOF parsing.
                  if (extendsPartialValue(parser, value) || err.eof || pv != null) {
                     ParseError newError = parseEOFError(parser, value, err, childParselet, "Partial match: {0} ", this);
                     if (optional)
                        return null;
                     return newError;
                  }
               }
            }

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

         if (value == null)
            value = (ParentParseNode) newParseNode(startIndex);

         if (!childParselet.getLookahead())
            value.add(nestedValue, childParselet, i, false, parser);
         else if (slotMapping != null) // Need to preserve the specific index in the results if we have a mapping
            value.add(null, childParselet, i, true, parser);

         if (nestedValue != null || childParselet.isNullValid())
            anyContent = true;
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

   private boolean extendsPartialValue(Parser parser, ParentParseNode value) {
      if (value == null)
         return false;

      if (parser.currentIndex == parser.currentErrorStartIndex) {
         Object nsv = ParseUtil.nodeToSemanticValue(value);
         if (nsv instanceof JavaSemanticNode) {
            JavaSemanticNode node = (JavaSemanticNode) nsv;
            for (int i = 0; i < parser.currentErrors.size(); i++) {
               ParseError err = parser.currentErrors.get(i);
               Object esv = ParseUtil.nodeToSemanticValue(err.partialValue);
               if (esv != null) {
                  if (node.applyPartialValue(esv)) {

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
         }
      }
      return false;
   }

   private Object parseRepeatingSequence(Parser parser) {
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

      do {
         lastMatchIndex = parser.currentIndex;

         matchedValues = null;

         matched = true;
         boolean anyContent = false;
         numParselets = parselets.size();
         for (i = 0; i < numParselets; i++)
         {
            childParselet = parselets.get(i);
            Object nestedValue = parser.parseNext(childParselet);
            if (nestedValue instanceof ParseError)
            {
               matched = false;
               errorValues = matchedValues;
               matchedValues = null;
               lastError = (ParseError) nestedValue;
               break;
            }

            if (matchedValues == null)
               matchedValues = new ArrayList<Object>();

            if (nestedValue != null)
               anyContent = true;

            matchedValues.add(nestedValue);
         }
         if (i == numParselets)
         {
            // We need at least one non-null slot in a sequence for it to match
            if (anyContent)
            {
               if (matchedValues != null)
               {
                  if (value == null)
                     value = (ParentParseNode) newParseNode(lastMatchIndex);
                  int numMatchedValues = matchedValues.size();
                  for (i = 0; i < numMatchedValues; i++)
                  {
                     Object nv = matchedValues.get(i);
                     //if (nv != null) // need an option to preserve nulls?
                     value.add(nv, parselets.get(i), i, false, parser);
                  }
               }
               matched = true;
               matchedAny = true;
            }
            else
               matched = false;
         }
      } while (matched);

      if (!matchedAny)
      {
         if (optional) {
            parser.changeCurrentIndex(startIndex);
            return null;
         }

         if (parser.enablePartialValues && !lookahead && lastError != null) {
            Object pv = lastError.partialValue;
            if (lastError.eof || pv != null && !childParselet.getLookahead()) {
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
               return parseEOFError(parser, value, lastError, childParselet, "Partial array match: {0} ", this);
            }
         }
         return parseError(parser, "Expecting one or more of {0}", this);
      }
      else
         parser.changeCurrentIndex(lastMatchIndex);

      String customError;
      if ((customError = accept(parser.semanticContext, value, startIndex, parser.currentIndex)) != null)
         return parseError(parser, customError, value, this);

      if (lookahead)
         parser.changeCurrentIndex(startIndex);
      return value;
   }

   protected static final GenerateError INVALID_TYPE_IN_CHAIN = new GenerateError("Chained property - next item in sequence did not match expected item type.");
   protected static final GenerateError MISSING_ARRAY_VALUE = new GenerateError("Missing array value");
   protected static final GenerateError ACCEPT_ERROR = new GenerateError("Accept method failed");

   public Object generate(GenerateContext ctx, Object value) {
      if (trace)
          System.out.println("*** Generating traced element");

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
                     pnode.setSemanticValue(null);
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
                  pnode.setSemanticValue(null);
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
                     pnode.setSemanticValue(null);
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
                  pnode.setSemanticValue(null);

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
               pnode.setSemanticValue(null);
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
            pnode.setSemanticValue(null);

         return ctx.error(this, NO_OBJECT_FOR_SLOT, value, progress);
      }
      else {
         if (pnode == null)
            pnode = newGeneratedParseNode(value);

         Object res = generateOne(ctx, pnode, value);
         if (res instanceof GenerateError) {
            pnode.setSemanticValue(null);
            if (optional && emptyValue(ctx, value))
               return generateResult(ctx, null);
            return res;
         }
         else if (res instanceof PartialArrayResult)
            return res;
         // If we did not matching anything, do not associate this parse node with the semantic value.  This
         // acts as a signal to the caller that the value was not consumed, so it won't advance the array.
         else if (res == null)
            pnode.setSemanticValue(null);
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

      if (trace)
         System.out.println("*** Generating a traced element");

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
