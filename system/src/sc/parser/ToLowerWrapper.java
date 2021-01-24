/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

public class ToLowerWrapper extends AbstractString {
   private IString wrapped;

   public ToLowerWrapper(IString wrapped) {
      this.wrapped = wrapped;
   }
   public char charAt(int index) {
      return Character.toLowerCase(wrapped.charAt(index));
   }

   @Override
   public int length() {
      return wrapped.length();
   }
}
