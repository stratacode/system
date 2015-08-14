/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.util.StringUtil;

import java.util.List;

public abstract class CommandWizard {
   AbstractInterpreter commandInterpreter;
   boolean verbose = false;

   public abstract String prompt();

   /** Can return null if the command is not yet complete (i.e. for multi-line commands) */
   public abstract Object parseCommand(String input);

   /** Optional method - called with a non-null result from parseCommand */
   public void processStatement(Object commandResult) {}

   public int complete(String command, int cursor, List candidates) {
      return -1;
   }

   public boolean validateYesNo(String input, boolean theDefault) {
      // The default
      if (input.length() == 0)
         return theDefault;

      input = input.trim();
      if (input.equalsIgnoreCase("y") || input.equalsIgnoreCase("yes"))
         return true;
      else if (input.equalsIgnoreCase("n") || input.equalsIgnoreCase("no"))
         return false;
      else
         throw new IllegalArgumentException("Answer must be y or n not: '" + input + "'");
   }

   void vprint(String v) {
      if (!verbose)
         return;
      System.out.println(StringUtil.insertLinebreaks(v, commandInterpreter.getTermWidth()));
   }

   void print(String v) {
      System.out.println(StringUtil.insertLinebreaks(v, commandInterpreter.getTermWidth()));
   }

   void output(String v) {
      System.out.println(v);
   }

}