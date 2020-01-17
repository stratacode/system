/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.binf.ParseInStream;
import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.type.TypeUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * A ChainedResultSequence is a special type of Sequence that always has 2 children.  The second child is
 * always parsed as 'optional'.  If the second child is not parsed, the sequence just passes through the semantic
 * value of the first child - the specific property mapping of this parselet is ignored.  If the second child is parsed,
 * the normal rules for a Sequence are used to produce the semantic value are applied.
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
         parent.add(pnode.children.get(0), this, -1, index, false, parser);

         return false;
      }
      else
         return super.addResultToParent(node, parent, index, parser);
   }

   public boolean addReparseResultToParent(Object node, ParentParseNode parent, int svIndex, int childIndex, int slotIndex, Parser parser, Object oldChildParseNode, DiffContext dctx, boolean removeExtraNodes, boolean parseArray) {
      ParentParseNode pnode = (ParentParseNode) node;
      if (pnode.children.get(1) == null) {
         parent.addForReparse(pnode.children.get(0), this, svIndex, childIndex, slotIndex, false, parser, oldChildParseNode, dctx, removeExtraNodes, parseArray);
         return false;
      }
      else
         return super.addReparseResultToParent(node, parent, svIndex, childIndex, slotIndex, parser, oldChildParseNode, dctx, removeExtraNodes, parseArray);
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
      ArrayList<Object> pc = pnode.children;
      if (pc.get(1) == null)
         return pc.get(0);
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
         Parselet defaultParselet = parselets.get(0);

         Class thisSemanticValueClass = super.getSemanticValueClass();
         // If the default parselet is defined in terms of this parselet, use the default class.
         if (defaultParselet.resultClass == NestedParselet.UNDEFINED_CLASS)
            res = thisSemanticValueClass;
         else
            res = findCommonSuperClass(defaultParselet.getSemanticValueClass(), thisSemanticValueClass, 1);

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
   public Class getSemanticValueSlotClass() {
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
            ((ISemanticNode) semanticValue).setParseNodeValid(false);
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

   public boolean producesParseletId(int otherId) {
      if (super.producesParseletId(otherId))
         return true;

      if (parselets.get(0).producesParseletId(otherId))
         return true;
      return parselets.get(1).producesParseletId(otherId);
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

   protected Object getReparseChildNode(Object oldParseNode, int ix, boolean forceReparse) {
      if (oldParseNode instanceof ParentParseNode) {
         ParentParseNode pp = (ParentParseNode) oldParseNode;
         // This parselet produced the result - so it was the match
         if (pp.getParselet() == this)
            return super.getReparseChildNode(oldParseNode, ix, forceReparse);
      }
      if (ix != 0 && forceReparse) {
         if (oldParseNode != null) {
            return null;
         }
      }
      // This parse noded matched the "chained result" the first slot but not the second.  Skip the second one altogether.
      // propagate the value to the first slot
      return ix == 0 || forceReparse ? oldParseNode : SKIP_CHILD;
   }

   /**
    * In partial values mode, if we get an error on slot 1, that's a match of slot 0 so even if there's a way to
    * extend the error, do not do so
    */
   protected boolean disableExtendedErrors() {
      return true;
   }

   protected Object getSlotSemanticValue(int slotIx, ISemanticNode semanticValue, SaveRestoreCtx rctx) {
      boolean chainedSlotMatches = getSemanticValueSlotClass().isInstance(semanticValue);
      Parselet defaultParselet = parselets.get(0);
      boolean defaultSlotMatches = defaultParselet.getSemanticValueClass().isInstance(semanticValue);
      int pId = semanticValue.getParseletId();
      boolean producedByParent = false;
      if (pId != -1) {
         // The result may have been produced by this parselet (e.g. BinaryExpression), or one of the children.
         if (pId != id) {
            if (defaultParselet.producesParseletId(pId)) {
               if (!defaultSlotMatches)
                  System.err.println("*** Invalid case!");
            }
            else if (defaultSlotMatches)
               defaultSlotMatches = false;

            if (parselets.get(1).producesParseletId(pId)) {
               if (!chainedSlotMatches)
                  System.err.println("*** Invalid case!");
            }
            else {
               if (chainedSlotMatches)
                  chainedSlotMatches = false;
            }
         }
         else {
            producedByParent = true;
            if (defaultSlotMatches)
               defaultSlotMatches = false;
         }
      }

      if (slotIx == 0) {
         if (chainedSlotMatches) {
            Object mapping = slotMapping[0];
            try
            {
               Object chainValue = rctx.getPropertyValue(semanticValue, mapping);

               if (chainValue == null && defaultSlotMatches)
                  return semanticValue;
               else if (chainValue == null)
                  return SKIP_CHILD;
               else if (!defaultSlotMatches)
                  return chainValue;
               else
                  return semanticValue;
            }
            catch (IllegalArgumentException exc) {
               System.err.println("*** Unable to map chain slot for parselet");
            }
         }
         if (!defaultSlotMatches)
            System.err.println("*** Warning - invalid case?");
         return semanticValue;
      }
      else if (slotIx == 1) {
         if (chainedSlotMatches) {
            if (parameterMapping[1] == ParameterMapping.PROPAGATE)
               return semanticValue;
            else {
               try {
                  Object slotVal = rctx.getPropertyValue(semanticValue, slotMapping[1]);
                  if (slotVal instanceof List) {
                     rctx.listProp = true;
                  }
                  return slotVal;
               }
               catch (IllegalArgumentException exc) {
                  System.err.println("*** Unable to map chain slot for parselet");
               }
            }
         }
      }

      if (chainedSlotMatches)
         return semanticValue;
      return SKIP_CHILD;
   }

   public void saveParse(IParseNode pn, ISemanticNode oldNode, SaveParseCtx sctx) {
      Object mapping = slotMapping[0];
      Object chainedValue = null;

      // Look at the value for the first slot.  If there is no property at all, it
      // may have been produced from the first slot - the default which does not apply the mapping.
      // It is also possible the semantic value has that property mapping but is was not when parsed originally.
      // In either case, this will restore only the first slot and from getSlotMapping above, return SKIP_CHILD
      // for the second slot.
      //
      // The weird case is when there is a value for the property, but it does not match the default parselet.  We need to
      // then use the rule for the second slot to do the restore, but where we hide the chained property so that child
      // ChainedResultSequences won't see this property and take the wrong path.  The property was not set by the child
      // when it was originally parsed - instead it's set as it passes through this node, hence the need to hide it here.

      Parselet defaultParselet = parselets.get(0);

      Parselet pnParselet = pn.getParselet();

      if (pnParselet != this) {
         if (pnParselet == defaultParselet || defaultParselet.producesParselet(pnParselet)) {
            // Flag indicates we parsed the default slot
            sctx.pOut.writeUInt(0);
            defaultParselet.saveParse(pn, oldNode, sctx);
            // TODO: do we need to save the parseletId or something here or can we do the restore properly from the semantic node tree?
            return;
         }
         System.out.println("*** Warning - unknown case in saveParse for chain result sequence");
      }
      // Flag to indicate we took the default path
      sctx.pOut.writeUInt(1);

      boolean chainedSlotMatches = getSemanticValueSlotClass().isInstance(oldNode);
      //boolean defaultSlotMatches = defaultParselet.getSemanticValueClass().isInstance(oldNode);

      if (chainedSlotMatches) {
         try {
            chainedValue = sctx.getPropertyValue(oldNode, mapping);
         }
         catch (IllegalArgumentException exc)
         {}
      }

      boolean masked = false;
      // Though we have a chained value, it's type does not match the slot.  We need to mask that property off
      // so that it does not confused the parent match.
      if (chainedValue != null && !defaultParselet.dataTypeMatches(chainedValue)) {
         sctx.maskProperty(oldNode, mapping, null);
         masked = true;
      }

      super.saveParse(pn, oldNode, sctx);

      if (masked)
         sctx.unmaskProperty(oldNode, mapping);
   }

   public Object restore(Parser parser, ISemanticNode oldNode, RestoreCtx rctx, boolean inherited) {
      Object mapping = slotMapping[0];
      Object chainedValue = null;

      // Look at the value for the first slot.  If there is no property at all, it
      // may have been produced from the first slot - the default which does not apply the mapping.
      // It is also possible the semantic value has that property mapping but is was not when parsed originally.
      // In either case, this will restore only the first slot and from getSlotMapping above, return SKIP_CHILD
      // for the second slot.
      //
      // The weird case is when there is a value for the property, but it does not match the default parselet.  We need to
      // then use the rule for the second slot to do the restore, but where we hide the chained property so that child
      // ChainedResultSequences won't see this property and take the wrong path.  The property was not set by the child
      // when it was originally parsed - instead it's set as it passes through this node, hence the need to hide it here.

      Parselet defaultParselet = parselets.get(0);

      ParseInStream pIn = rctx.pIn;
      if (pIn != null) {
         int val = pIn.readUInt();
         if (val == 0)
            return defaultParselet.restore(parser, oldNode, rctx, inherited);
      }

      boolean chainedSlotMatches = getSemanticValueSlotClass().isInstance(oldNode);
      //boolean defaultSlotMatches = defaultParselet.getSemanticValueClass().isInstance(oldNode);

      if (chainedSlotMatches) {
         try {
            chainedValue = rctx.getPropertyValue(oldNode, mapping);
         }
         catch (IllegalArgumentException exc)
         {}
      }

      boolean masked = false;
      // Though we have a chained value, it's type does not match the slot.  We need to mask that property off
      // so that it does not confused the parent match.
      if (chainedValue != null && !defaultParselet.dataTypeMatches(chainedValue)) {
         rctx.maskProperty(oldNode, mapping, null);
         masked = true;
      }

      Object res = super.restore(parser, oldNode, rctx, inherited);

      if (masked)
         rctx.unmaskProperty(oldNode, mapping);

      return res;
   }
}
