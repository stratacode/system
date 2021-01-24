/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.template;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.java.Statement;
import sc.parser.IString;
import sc.parser.IStyleAdapter;
import sc.parser.ParentParseNode;
import sc.parser.ParseUtil;

public class TemplateUtil {

   public static void glueStyledString(IStyleAdapter adapter, Statement glueExpression) {
      ParentParseNode node = (ParentParseNode) glueExpression.parseNode;
      if (node.children == null)
         return;
      StringBuffer sb = new StringBuffer();
      int sz = node.children.size();
      for (int i = 0; i < sz; i++) {
         Object childParseNodeObj = node.children.get(i);
         Object childNode = ParseUtil.nodeToSemanticValue(childParseNodeObj);

         if (childNode instanceof SemanticNodeList) {
            SemanticNodeList list = (SemanticNodeList) childNode;
            for (int j = 0; j < list.size(); j++) {
               Object childElement = list.get(j);
               if (childElement instanceof IString) {
                  ParseUtil.styleString(adapter, "templateString", (IString) childElement, true);
               }
               else {
                  ISemanticNode element = (ISemanticNode) childElement;
                  element.styleNode(adapter);
               }
            }
         }
         else
            ParseUtil.toStyledString(adapter, node.children.get(i));
      }
      // Do not escape here - we do need to escape above for the values but can't escape the HTML style tags we
      // insert around the individual strings.
      ParseUtil.styleString(adapter, node.getParselet().styleName, sb, false);
   }
}
