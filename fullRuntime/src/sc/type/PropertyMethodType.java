/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

public enum PropertyMethodType {
   Is, Get, Set, GetIndexed, SetIndexed, Validate;

   public boolean isGet() {
      return this == Get || this == Is;
   }
}
