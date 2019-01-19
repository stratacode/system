package sc.binf;

import sc.lang.ISemanticNode;
import sc.parser.*;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

public class ParseOutStream extends BinfOutStream {
   public static final int NullPN = 0;
   public static final int ParentPN = 1;
   public static final int SinglePN = 2;
   public static final int StringToken = 3;
   public static final int String = 4;
   public static final int ParentPNType = 5;
   public static final int SinglePNType = 6;
   // TODO: Warning, because we use parselet ids here as well, make sure this never gets above BinfConstants.NumReservedIds, used for the model stream.


   public ParseOutStream(DataOutputStream out) {
      super(out);
   }

   public void serialize(IParseNode node, ISemanticNode sn, String fileExt, Language lang) {
      Parselet p = node.getParselet();
      initOutStream(fileExt, lang);
      SaveParseCtx sctx = new SaveParseCtx();
      sctx.pOut = this;
      p.saveParse(node, sn, sctx);
   }

   public void saveChild(Parselet parselet, Object pnObj, SaveParseCtx sctx, boolean saveType) {
      if (pnObj == null)
         writeUInt(NullPN);
      else if (pnObj instanceof ParentParseNode) {
         ParentParseNode ppn = (ParentParseNode) pnObj;
         //if (ppn.getParselet() != parselet)
         //   System.err.println("*** Mismatching call to saveChild");
         if (!(parselet instanceof NestedParselet))
            System.out.println("*** parselet mismatch in saveChild");
         NestedParselet nestedParselet = (NestedParselet) parselet;
         if (saveType) {
            writeUInt(ParentPNType);
            saveParseletId(parselet);
         }
         else
            writeUInt(ParentPN);
         ArrayList<Object> children = ppn.children;
         int sz = children == null ? 0 : children.size();
         writeUInt(sz);
         for (int i = 0; i < sz; i++) {
            if (nestedParselet == null)
               System.out.println("*** No parselet for saving parent parse node");
            Parselet childParselet = nestedParselet.getChildParseletForIndex(i);
            Object child = children.get(i);
            IParseNode childPN = child instanceof IParseNode ? (IParseNode) child : null;
            boolean saveChildParseletType = false;
            // Child parselet is not determined in the grammar so we need to save the id so we know the next link in the chain to restore the parse-node tree properly
            if (childParselet == null) {
               if (childPN != null)
                  childParselet = childPN.getParselet();
               saveParseletId(childParselet);
            }
            else {
               if (childPN != null) {
                  Parselet childPNParselet = childPN.getParselet();
                  // Sometimes the childParselet even at this level is produced from a child parselet of the one we have for the parent at this slot.  In that case,
                  // to preserve the original parselet mapping, we need to save the child parselet type.
                  if (childPNParselet != childParselet) {
                     saveChildParseletType = true;
                     childParselet = childPNParselet;
                  }
               }
            }
            saveChild(childParselet, child, sctx, saveChildParseletType);
         }
      }
      else if (pnObj instanceof ParseNode) {
         ParseNode pn = (ParseNode) pnObj;
         Object pnValue = pn.value;
         Parselet childParselet = null;
         if (pnValue instanceof IParseNode)
            childParselet = ((IParseNode) pnValue).getParselet();
         if (pn.getParselet() != parselet)
            System.err.println("*** Mismatching call to saveChild for ParseNode");
         if (saveType) {
            writeUInt(SinglePNType);
            // Need to save both parselet and the child so we can restore both the parent and child parselet appropriately
            saveParseletId(parselet);
            saveParseletId(childParselet);
         }
         else
            writeUInt(SinglePN);
         saveChild(childParselet, pn.value, sctx, false);
      }
      else if (pnObj instanceof StringToken) {
         writeUInt(StringToken);
         StringToken st = (StringToken) pnObj;
         // TODO: we could use fewer bytes if we wrote this as a relative index to the current index
         writeUInt(st.startIndex);
         writeUInt(st.len);
      }
      else if (PString.isString(pnObj)) {
         writeUInt(String);
         try {
            out.writeUTF(pnObj.toString());
         }
         catch (IOException exc) {
            throw new UncheckedIOException(exc);
         }
      }
      else {
         // TODO: currently we are not trying to save/restore parse node trees with parse-errors.  Ordinarily a ParseError
         // is returned from the parse operation and we won't even try to save it.  But when partialValues is set, we might
         // return a parseNode with errors.
         throw new IllegalArgumentException("Parse error found");
      }
   }

   public void saveParseletId(Parselet p) {
      writeUInt(p == null ? 0 : p.id);
   }
}
