/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.xml;

import sc.lang.html.Element;

public class XMLElement extends Element {

   // Unlike HTML, XML tag names are case sensitive for most uses.
   public String getDefaultObjectName() {
      return tagName;
   }
}
