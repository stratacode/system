package sc.binf;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.sc.ModifyDeclaration;
import sc.parser.IString;
import sc.parser.PString;
import sc.parser.Parselet;
import sc.type.*;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

public class ModelInStream extends BinfInStream {
   int currentListId = -1;

   public ModelInStream(DataInputStream in) {
      super(in);
   }

   public Object readValue() {
      try {
         int parseletId = readUInt();
         Object res;
         Class cl;
         if (parseletId < 0) {
            System.err.println("*** Unable to read parselet with invalid id");
            return null;
         }
         else {
            switch (parseletId) {
               case BinfConstants.NullId:
                  res = null;
                  cl = null;
                  break;
               case BinfConstants.StringId:
                  res = PString.toIString(in.readUTF()); // Need to return PString here because String.equals(..) fails when the arg is a StringToken as it is in the original parsed model
                  cl = IString.class;
                  break;
               case BinfConstants.BoolFalse:
                  res = Boolean.FALSE;
                  cl = Boolean.class;
                  break;
               case BinfConstants.BoolTrue:
                  res = Boolean.TRUE;
                  cl = Boolean.class;
                  break;
               case BinfConstants.IntId:
                  res = in.readInt();
                  cl = Integer.class;
                  break;
               case BinfConstants.LongId:
                  res = in.readLong();
                  cl = Long.class;
                  break;
               case BinfConstants.DoubleId:
                  res = in.readDouble();
                  cl = Double.class;
                  break;
               case BinfConstants.FloatId:
                  res = in.readFloat();
                  cl = Float.class;
                  break;
               case BinfConstants.UIntId:
                  res = readUInt();
                  cl = Integer.class;
                  break;
               case BinfConstants.CharId:
                  res = in.readChar();
                  cl = Character.class;
                  break;
               case BinfConstants.ListId:
                  res = readList();
                  cl = null; // No need to do conversion here
                  break;
               default:
                  Parselet parselet;
                  boolean isList;
                  if (parseletId == BinfConstants.ListElementId) { // Some elements do not have a parseNode because they are created from a repeat node which creates the parse node.   For these, we use the currentList parselet id and use it's parent node
                     parseletId = currentListId;
                     parselet = lang.getParseletById(parseletId);
                     if (parselet == null) {
                        System.err.println("No parselet in language: " + lang + " for id: " + parseletId);
                        return null;
                     }
                     cl = parselet.getSemanticValueComponentClass();
                     isList = false;
                  }
                  else {
                     parselet = lang.getParseletById(parseletId);
                     if (parselet == null) {
                        System.err.println("No parselet in language: " + lang + " for id: " + parseletId);
                        return null;
                     }
                     // Using "SemanticValueSlotClass" instead of just SemanticValueClass because of classes like ChainedResultSequence.  SemanticValueClass is the parent class of the
                     // child and this match - e.g. Expression.  SlotValueClass is the class when this parselets rule is used - e.g. BinaryExpression.  If the child matches, it's parseNode
                     // will already have been set and so it's parseletId will be the child's parselet.  So I think we always want to use the SlotValueClass.
                     isList = parselet.getNewSemanticValueIsArray();
                     if (isList)
                        cl = parselet.getSemanticValueComponentClass();
                     else
                        cl = parselet.getSemanticValueSlotClass();
                  }
                  if (isList) {
                     int saveCurrentListId = currentListId;
                     currentListId = parseletId;
                     SemanticNodeList<Object> resList = readList();
                     currentListId = saveCurrentListId;
                     res = resList;
                     resList.setParseletId(parseletId);
                     cl = null; // No need to do conversion here
                  }
                  else {
                     if (cl == null) {
                        System.out.println("*** No semantic value class for deserializing semantic node");
                     }
                     ISemanticNode resNode = (ISemanticNode) RTypeUtil.createInstance(cl);
                     resNode.setParseletId(parseletId);
                     DynType type = TypeUtil.getPropertyCache(cl);
                     IBeanMapper[] semanticProps = type.getSemanticPropertyList();
                     for (int i = 0; i < semanticProps.length; i++) {
                        IBeanMapper mapper = semanticProps[i];
                        Object propVal = readValue();
                        if (propVal instanceof IString && mapper.getPropertyType() == String.class)
                           propVal = propVal.toString();
                        if (mapper.getPropertyType() == Boolean.TYPE) {
                           if (propVal == null)
                              propVal = Boolean.FALSE;
                           else if (propVal instanceof String || propVal instanceof IString)
                              propVal = Boolean.TRUE;
                        }
                        try {
                           PTypeUtil.setProperty(resNode, mapper.getField(), propVal);
                           if (propVal instanceof ISemanticNode)
                              ((ISemanticNode) propVal).setParentNode(resNode);
                        }
                        catch (IllegalArgumentException exc) {
                           System.err.println("*** Error setting semantic node property on class: " + cl + "." + mapper.getField() + " to: " + propVal);
                        }
                     }
                     res = resNode;
                  }
            }
         }
         /*
         if (cl != null) {
            Object newRes= ParseUtil.convertSemanticValue(cl, res);
            if (newRes != null)
               return newRes;
         }
         */
         return res;
      }
      catch (IOException exc) {
         throw new UncheckedIOException(exc);
      }
   }

   SemanticNodeList<Object> readList() {
      int sz = readUInt();
      SemanticNodeList<Object> res = new SemanticNodeList<Object>(sz);
      for (int i = 0; i < sz; i++)
         res.add(readValue());
      return res;

   }
}
