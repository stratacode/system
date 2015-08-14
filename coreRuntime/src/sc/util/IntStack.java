/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import sc.js.JSSettings;

@JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public class IntStack {
   int[] elements;

   int top = -1;

   public IntStack(int capacity)
   {
      elements = new int[capacity];
   }

   public int size()
   {
      return top + 1;
   }

   public void push(int val)
   {
      if (++top == elements.length)
      {
         int[] newElements = new int[elements.length * 2];
         System.arraycopy(elements, 0, newElements, 0, elements.length);
         elements = newElements;
      }
      elements[top] = val;
   }

   public int get(int val) {
      return elements[val];
   }

   public int top() {
      if (top == -1)
         throw new IllegalArgumentException("no top element");
      return elements[top];
   }

   public int pop()
   {
      if (top == -1)
         throw new IllegalArgumentException("popped off the top!");
      return elements[top--];
   }
}