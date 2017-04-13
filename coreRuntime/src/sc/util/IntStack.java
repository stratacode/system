/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import sc.js.JSSettings;

import java.io.Serializable;
import java.util.Arrays;

@JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public class IntStack implements Serializable, Cloneable {
   int[] elements;

   int top = -1;

   public IntStack(int capacity) {
      elements = new int[capacity];
   }

   public int size() {
      return top + 1;
   }

   public void push(int val) {
      if (++top >= elements.length) {
         int[] newElements = new int[elements.length * 2];
         System.arraycopy(elements, 0, newElements, 0, elements.length);
         elements = newElements;
      }
      elements[top] = val;
   }

   public int get(int val) {
      return elements[val];
   }

   /** Remove the ith entry of the stack */
   public void remove(int val) {
      int[] newVal = new int[elements.length - 1];
      int k = 0;
      for (int i = 0; i <= elements.length; i++) {
         if (elements[i] != val) {
            newVal[k++] = elements[i];
         }
      }
      elements = newVal;
      if (top >= val)
         top--;
   }

   public void set(int ix, int newVal) {
      elements[ix] = newVal;
   }

   public int top() {
      if (top == -1)
         throw new IllegalArgumentException("no top element");
      return elements[top];
   }

   public int pop() {
      if (top == -1)
         throw new IllegalArgumentException("popped off the top!");
      return elements[top--];
   }

   public void appendAll(IntStack other) {
      int thisLen = top + 1;
      int otherLen = other.top + 1;
      int[] newElements = new int[thisLen + otherLen];
      System.arraycopy(elements, 0, newElements, 0, thisLen);
      System.arraycopy(other.elements, 0, newElements, thisLen, otherLen);
      elements = newElements;
      top = thisLen + otherLen - 1;
   }

   public IntStack clone() {
      try {
         IntStack res = (IntStack) super.clone();
         res.elements = Arrays.copyOf(elements, elements.length);
         return res;
      }
      catch (CloneNotSupportedException exc) {
         return null;
      }
   }
}
