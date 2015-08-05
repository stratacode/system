/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.layer.LayeredSystem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class CommandInterpreter extends AbstractInterpreter implements Runnable {
   BufferedReader input;

   public CommandInterpreter(LayeredSystem sys, BufferedReader inputStream) {
      super(sys);
      input = inputStream;
   }

   public CommandInterpreter(LayeredSystem system, InputStream inputStream) {
      this(system, new BufferedReader(new InputStreamReader(inputStream)));
   }

   @Override
   public String readLine(String nextPrompt) {
      try {
         System.out.println(nextPrompt);
         return input.readLine();
      }
      catch (IOException exc) {
         System.err.println("*** error reading from console");
         return null;
      }
   }

   public boolean readParseLoop() {
      boolean lastPending = false;
      try {
         String nextLine;
         prompt();
         while ((nextLine = input.readLine()) != null) {

            processCommand(nextLine);

            if (pendingInput.length() > 0) {
               if (lastPending) {
                  System.out.print(pendingInput);
                  lastPending = false;
               }
               else {
                  lastPending = true;
                  System.out.print("pending: ");
               }
            }
            else {
               System.out.print(prompt());
               lastPending = false;
            }
         }
      }
      catch (IOException exc) {
         System.out.println("Error reading command input: " + exc);
         return false;
      }
      if (pendingInput.length() > 0) {
         System.err.println("EOF with unprocessed input: " + pendingInput);
         return false;
      }
      return true;
   }


   /** TODO: This is here as part of the thread implementation... should not be exposed to command methods.  needs @private */
   public void run() {
      System.exit(readParseLoop() ? 1 : 0);
   }
}
