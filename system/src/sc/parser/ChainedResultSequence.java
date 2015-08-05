/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.type.TypeUtil;

import java.util.List;

/**
 * This class alters the behavior of how the result is produced for this sequence.  If the second value
 * in the sequence is null, we use the first value.  If both values are defined, we choose the default
 * production for a sequence using the mappings.
 * During the parsing process, the addResultToParent method will select the semantic value of the first slot if
 * the second slot is null.
 */
public class ChainedResultSequence extends Sequence {
   Class slotResultClass;
   Class semanticValueClass;

   public ChainedResultSequence(String id, int options) {
      super(id, options);
   }

   public ChainedResultSequence(int options) {
      super(options);
   }

   public ChainedResultSequence(String id, Parselet... toAdd) {
      super(id, toAdd);
   }

   public ChainedResultSequence(Parselet... toAdd) {
      super(toAdd);
   }

   public ChainedResultSequence(String id, int options, Parselet... toAdd) {
      super(id, options, toAdd);
   }

   public ChainedResultSequence(int options, Parselet... toAdd) {
      super(options, toAdd);
   }

   public boolean addResultToParent(Object node, ParentParseNode parent, int index, Parser parser) {
      ParentParseNode pnode = (ParentParseNode) node;
      if (pnode.children.get(1) == null) {
         parent.add(pnode.children.get(0), this, index, false, parser);

         return false;
      }
      else
         return super.addResultToParent(node, parent, index, parser);
   }

   public boolean setResultOnParent(Object node, ParentParseNode parent, int index, Parser parser) {
      ParentParseNode pnode = (ParentParseNode) node;
      if (pnode.children.get(1) == null) {
         parent.set(pnode.children.get(0), this, index, false, parser);

         return false;
      }
      else
         return super.setResultOnParent(node, parent, index, parser);
   }

   public Object propagateResult(Object node) {
      ParentParseNode pnode = (ParentParseNode) node;
      if (pnode.children.get(1) == null)
         return pnode.children.get(0);
      return pnode;
   }

   protected void initParameterMapping() {
      super.initParameterMapping();

      // because we are accumulating each subsequent repeating child in this slot
      // and chaining them together, this slot should be treated as a scalar, not
      // an array in the system.
      parselets.get(1).treatValueAsScalar = true;

      if (slotMapping == null || slotMapping[0] == null)
         throw new IllegalArgumentException("ChainedResultSequence must have a parameter mapping like '(chainedSlotName,.)'");
   }

   public Class getSemanticValueClass() {
      if (!initialized)
         init();

      if (semanticValueClass == UNDEFINED_CLASS)
         return null;

      if (semanticValueClass != null)
         return semanticValueClass;

      semanticValueClass = UNDEFINED_CLASS;

      Class res = null;

      try
      {
         // We either return the normal default return for this parselet or the value of the first slot if the
         // second one is null.
         res = findCommonSuperClass(parselets.get(0).getSemanticValueClass(), super.getSemanticValueClass(), 1);

         if (trace) {
            System.out.println("*** Trace: chained sequence: resolving type to: " + res + " from 0:" + parselets.get(0).getSemanticValueClass() + " 1:" +
                                parselets.get(1).getSemanticValueClass() + " for definition: " + this);
         }
         semanticValueClass = res;
      }
      finally
      {
         if (semanticValueClass == UNDEFINED_CLASS)
            semanticValueClass = null;
      }
      return res;
   }

   protected void resolveParameterMapping() {
      // We no not apply our slot mapping to the results of the first parselet if we are propagating the second slot.
      // If the second slot is null we'll return the first slot unmodified.
      if (parameterMapping[1] == ParameterMapping.PROPAGATE)
         slotResultClass = parselets.get(1).getSemanticValueClass();
      else
         slotResultClass = resultClass;
      super.resolveParameterMapping();
   }

   private final static GenerateError NO_MATCH_ERROR = new GenerateError("No semantic value to required element");

   public Object generate(GenerateContext ctx, Object value) {
      if (value == null) {
         if (optional)
            return null;
         else
            return NO_MATCH_ERROR;
      }

      Object mapping = slotMapping[0];
      Object chainedValue = null;

      // First we need to grab the first slot's mapping to see how we treat this.
      // It is quite possible the supplied value does not have this slot since it
      // may have been produced from the first rule which does not apply this mapping.
      // It is also possible it has the mapping but we did not set it.  Either way
      // that is the special case where we just let the first node generate the value
      // and ignore the second parselet.  If the chained slot is set, it is a normal
      // Sequence.

      Parselet defaultParselet = parselets.get(0);

      boolean chainedSlotMatches = getSemanticValueSlotClass().isInstance(value);
      boolean defaultSlotMatches = defaultParselet.getSemanticValueClass().isInstance(value);

      if (chainedSlotMatches) {
         try {
            chainedValue = ctx.getPropertyValue(value, mapping);
         }
         catch (IllegalArgumentException exc)
         {}
      }


      // We also need to ensure the type of the value is the same before choosing the default
      // slot.
      if (chainedValue == null || !chainedSlotMatches) {
         if (!defaultSlotMatches)
             return NO_MATCH_ERROR;

         return ctx.generateChild(defaultParselet, value);
      }

      boolean masked = false;
      // Though we have a chained value, it's type does not match the slot.  We need to mask that property off
      // so that it does not confused the parent match.
      if (chainedValue != null && !defaultParselet.dataTypeMatches(chainedValue)) {
         ctx.maskProperty(value, mapping, null);
         masked = true;
      }

      // If we try the main slot and it fails, we still have to try the first one
      Object res = super.generate(ctx, value);

      if (masked)
         ctx.unmaskProperty(value, mapping);

      /*
      if (res instanceof ParentParseNode) {
         // Need to determine if this result generated parse nodes
         // for all values it should have in this repeating chain.  If not, we have to go
         // do the default slot and choose the one which matched more nodes in the sequence.
         ParentParseNode resNode = (ParentParseNode) res;
         for (int i = 1; i < parselets.size(); i++) {
            Object childNode = resNode.children.get(i);
            if (childNode == null || ((childNode instanceof IParseNode) && ((IParseNode) childNode).getSemanticValue() == null))
               return ctx.generateChild(defaultParselet, value);
         }
      }
      */
      if (res instanceof GenerateError && defaultSlotMatches)
         return ctx.generateChild(defaultParselet, value);
      return res;
   }

   /**
    * If there is a propagated element we'll only apply the mappings to that guy.
    */
   protected Class getSemanticValueSlotClass() {
      return slotResultClass;
   }

   public boolean semanticPropertyChanged(Object parentParseNode, Object semanticValue, Object selector, Object value) {
      Object mapping = slotMapping[0];
      Object chainedValue = null;

      Parselet defaultParselet = parselets.get(0);

      boolean chainedSlotMatches = getSemanticValueSlotClass().isInstance(semanticValue);
      boolean defaultSlotMatches = defaultParselet.getSemanticValueClass().isInstance(semanticValue);

      if (chainedSlotMatches) {
         try
         {
            chainedValue = TypeUtil.getPropertyValue(semanticValue, mapping);
         }
         catch (IllegalArgumentException exc)
         {}
      }


      // We also need to ensure the type of the value is the same before choosing the default
      // slot.
      if (chainedValue == null || !chainedSlotMatches) {
         if (!defaultSlotMatches)
            return false;

         if (defaultParselet instanceof NestedParselet)
            return ((NestedParselet)defaultParselet).semanticPropertyChanged(parentParseNode, semanticValue, selector, value);
         else
            return regenerate((ParentParseNode) parentParseNode, false);
      }

      // If we try the main slot and it fails, we still have to try the first one
      if (super.semanticPropertyChanged(parentParseNode, semanticValue, selector, value)) {
         // We unfortunately have to invalidate the entire parselet here cause of a tricky case.  PostfixUnaryExpression with a
         // selector expression in front of it.  If we invalidate the SelectorExpression's expression property, it really need to
         // regenerate at the ChainResultSequence level to pick up the change to the expression/chainedExpression property.
         if (semanticValue instanceof ISemanticNode) {
            ((ISemanticNode) semanticValue).invalidateParseNode();
            if (((ISemanticNode) semanticValue).getParseNode() == null)
               System.out.println("*** Error - need to replace parse node in parent in this case!");
         }

         return true;
      }
      return false;
   }

   public int updateParseNodes(Object semanticValue, IParseNode parseNode) {
      Parselet defaultParselet = parselets.get(0);

      boolean chainedSlotMatches = getSemanticValueSlotClass().isInstance(semanticValue) && super.producesParselet(parseNode.getParselet());
      boolean defaultSlotMatches = defaultParselet.parseNodeMatches(semanticValue, parseNode);

      if (chainedSlotMatches) {
         if (super.updateParseNodes(semanticValue, parseNode) == MATCH)
            return MATCH;
      }

      if (defaultSlotMatches)
         return defaultParselet.updateParseNodes(semanticValue, parseNode);

      return NO_MATCH;
   }

   public boolean producesParselet(Parselet other) {
      if (super.producesParselet(other))
         return true;

      if (parselets.get(0).producesParselet(other))
         return true;
      return parselets.get(1).producesParselet(other);
   }

   public boolean dataTypeMatches(Object other) {
      if (super.dataTypeMatches(other))
         return true;

      Class svClass = getSemanticValueClass();

       // Could not be a match - the data types do not match
       if (!svClass.isInstance(other))
          return false;

      // If this parselet creates a type, its this slot class.  We need to match against that since that's the type we produced.
      svClass = getSemanticValueSlotClass();

      if (svClass == SemanticNodeList.class && other instanceof List)
         return true;

      if (svClass == other.getClass())
         return true;

      if (other instanceof ISemanticWrapper && ((ISemanticWrapper) other).getWrappedClass() == svClass)
         return true;

      if (parselets.get(0).dataTypeMatches(other))
         return true;
      return parselets.get(1).dataTypeMatches(other);
   }
}
