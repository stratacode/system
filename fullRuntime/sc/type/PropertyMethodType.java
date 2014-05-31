/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

public enum PropertyMethodType {
   Is, Get, Set, GetIndexed, SetIndexed;

   public boolean isGet() {
      return this == Get || this == Is;
   }
}
