/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.template;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.java.Statement;
import sc.parser.IString;
import sc.parser.ParentParseNode;
import sc.parser.ParseUtil;

public class TemplateUtil {

   public static CharSequence glueStyledString(Statement glueExpression) {
      ParentParseNode node = (ParentParseNode) glueExpression.parseNode;
      if (node.children == null)
         return "";
      StringBuffer sb = new StringBuffer();
      int sz = node.children.size();
      for (int i = 0; i < sz; i++) {
         Object childParseNodeObj = node.children.get(i);
         Object childNode = ParseUtil.nodeToSemanticValue(childParseNodeObj);

         if (childNode instanceof SemanticNodeList) {
            SemanticNodeList list = (SemanticNodeList) childNode;
            for (int j = 0; j < list.size(); j++) {
               Object childElement = list.get(j);
               if (childElement instanceof IString)
                  sb.append(ParseUtil.styleString("templateString", (IString) childElement, true));
               else {
                  ISemanticNode element = (ISemanticNode) childElement;
                  sb.append(element.toStyledString());
               }
            }
         }
         else
            sb.append(ParseUtil.toStyledString(node.children.get(i)));
      }
      // Do not escape here - we do need to escape above for the values but can't escape the HTML style tags we
      // insert around the individual strings.
      return ParseUtil.styleString(node.getParselet().styleName, sb, false);
   }
}
