/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

public class InitTypeInfo implements Comparable {
   TypeGroupMember initType;
   boolean doStartup;
   Integer priority = 0;

   public int compareTo(Object o) {
      InitTypeInfo other = (InitTypeInfo) o;
      return priority.compareTo(other.priority);
   }
}
