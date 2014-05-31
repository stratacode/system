/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;

public class Package extends JavaSemanticNode
{
   public List<Annotation> annotations;
   public String name;

   public static Package create(String name) {
      Package pkg = new Package();
      pkg.name = name;
      return pkg;
   }
}
