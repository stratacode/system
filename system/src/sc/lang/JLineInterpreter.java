/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import jline.console.ConsoleReader;
import jline.console.completer.Completer;
import sc.layer.LayeredSystem;

import java.io.EOFException;
import java.io.IOException;
import java.util.List;

// WARNING: this JSSetting is not picked up cause we do not compile this with SC so it is replicated in JSRuntimeProcessor manually.
@sc.js.JSSettings(replaceWith="sc_EditorContext")
public class JLineInterpreter extends AbstractInterpreter implements Runnable, Completer {
   ConsoleReader input;

   public JLineInterpreter(LayeredSystem sys) {
      super(sys);
      reset();
   }

   @Override
   public String readLine(String nextPrompt) {
      try {
         return input.readLine(nextPrompt);
      }
      catch (IOException exc) {
         System.err.println("*** error reading from console");
         return null;
      }
   }

   private void reset() {
      try {
         input = new ConsoleReader();
         input.addCompleter(this);
      }
      catch (EOFException exc) {
         System.exit(1);
      }
      catch (IOException exc) {
         System.err.println("Console cannot initialize input stream: " + exc);
         System.exit(1);
      }
   }

   public boolean readParseLoop() {
      initReadThread();
      do {
         try {
            String nextLine;
            String nextPrompt = prompt();
            while ((nextLine = input.readLine(nextPrompt)) != null) {
               Object result = null;
               if (currentWizard != null || nextLine.trim().length() != 0) {
                  pendingInput.append(nextLine);

                  result = parseCommand(pendingInput.toString(), getParselet());
               }
               if (result != null) {
                  try {
                     statementProcessor.processStatement(this, result);
                  }
                  catch (Throwable exc) {
                     System.err.println(exc);
                     if (system.options.verbose)
                        exc.printStackTrace();
                  }
               }
               if (pendingInput.length() > 0) {
                  System.out.print("Incomplete statement: ");
                  String remaining = pendingInput.toString();
                  int newLineIx = remaining.lastIndexOf("\n");
                  // Need to take away the newline for the prompt but put in a space in case the newline was the space separator in a command line input dump
                  if (newLineIx != -1 && newLineIx == remaining.length()-1) {
                     remaining = remaining.substring(0, newLineIx);
                     remaining = remaining + " ";
                  }
                  input.killLine();
                  input.putString(remaining);
                  nextPrompt = "";
                  pendingInput = new StringBuffer();
               }
               else {
                  execLaterJobs();

                  nextPrompt = prompt();
               }
            }
            if (nextLine == null) {
               system.performExitCleanup();
               if (pendingInput.length() > 0) {
                  System.err.println("EOF with unprocessed input: " + pendingInput);
                  return false;
               }
               else
                  return true;
            }
         }
         catch (IOException exc) {
            if (exc instanceof EOFException)
               return pendingInput.length() == 0;
            // Skip interrupted systme calls
            if (!exc.getMessage().contains("Interrupted"))
               System.err.println("Error reading command input: " + exc);
            else {
               try {
                  input.getTerminal().reset();
                  reset();
               }
               // Shutdown in progress...
               catch (IllegalStateException ise) {
               }
               catch (Exception termExc) {
                  system.verbose("Exception in terminal reset: " + termExc);
               }
            }
         }
      } while (true);
   }

   public int complete(String command, int cursor, List candidates) {
      if (currentWizard != null)
         return currentWizard.complete(command, cursor, candidates);

      return super.complete(command, cursor, candidates);
   }


   /**
    * TODO: This is here as part of the thread implementation... should not be exposed to command methods.
    * Maybe We should add a special annotation here to mark public methods that are really supposed to be private to layers above the annotation.
    */
   public void run() {
      System.exit(readParseLoop() && !system.anyErrors ? 0 : 1);
   }

   public int getTermWidth() {
      return input.getTerminal().getWidth();
   }
}
