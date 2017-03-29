/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

public class InitTypeInfo implements Comparable {
   public TypeGroupMember initType;
   public boolean doStartup;
   public Integer priority = 0;

   public int compareTo(Object o) {
      InitTypeInfo other = (InitTypeInfo) o;
      return priority.compareTo(other.priority);
   }
}
