/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.lang.java.JavaModel;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import jline.console.completer.Completer;
import jline.console.ConsoleReader;

import java.io.*;
import java.util.List;

// WARNING: this JSSetting is not picked up because we do not compile this file with stratacode.   We actually set this in JSRuntimeProcessor manually.
@sc.js.JSSettings(replaceWith="sc_EditorContext")
public class JLineInterpreter extends AbstractInterpreter implements Completer {
   ConsoleReader input;
   boolean smartTerminal;
   InputStream inputStream;

   public JLineInterpreter(LayeredSystem sys, boolean consoleDisabled, String inputFileName) {
      super(sys, consoleDisabled, inputFileName);
      resetInput();
   }

   @Override
   public String readLine(String nextPrompt) {
      try {
         String res = input.readLine(nextPrompt);
         currentLine++;
         return res;
      }
      catch (IOException exc) {
         System.err.println("*** error reading from console");
         return null;
      }
   }

   void resetInput() {
      try {
         FileInputStream inStream = inputFileName == null ? new FileInputStream(FileDescriptor.in) : new FileInputStream(inputFileName);
         consoleDisabled = inputFileName != null; // System.console() == null when either input or output is redirected - either case, it's not interactive right?
         input = new ConsoleReader("scc", inStream, System.out, null);
         input.setExpandEvents(false); // Otherwise "!" and probably other special chars fail to expand in JLine (e.g. if (foo != bar) -> [ERROR] Could not expand event)
         input.addCompleter(this);
         inputStream = inStream;
         smartTerminal = input.getTerminal().isSupported(); // Or isAnsiSupported?  Or isEchoEnabled()?  These also are different between the dumb IntelliJ terminal and the real command line
         currentLine = 0;
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
      // The main system starts the command interpreter before it's finished with the initialization but we don't want it
      // to start before the initialization has completed.  It's cleanest that way and keeps the messages in a consistent
      // order.  So by grabbing and releasing the system lock, we are basically just waiting for the system to complete.
      system.acquireDynLock(false);
      system.releaseDynLock(false);

      // If this is a nested include or something, and we are already in the midst of processing a statement higher up,
      // we want to release the locks here and then reacquire them afterwards.
      boolean needsPushCtx = false;
      if (currentScopeCtx != null) {
         needsPushCtx = true;
         popCurrentScopeContext();
      }

      initReadThread();

      do {
         try {
            String nextLine;
            String nextPrompt = inputBytesAvailable() ? "" : prompt();
            boolean lastStatementError = false;
            while ((nextLine = input.readLine(nextPrompt)) != null) {
               currentLine++;
               if (lastStatementError && pendingInput.length() > 0 && nextLine.length() == 1) {
                  if (nextLine.equals("q")) {
                     System.out.println("*** Incomplete statement canceled");
                     pendingInput = new StringBuilder();
                     nextPrompt = inputBytesAvailable() ? "" : prompt();
                     lastStatementError = false;
                     continue;
                  }
               }
               Object result = null;
               String lastCommand = null;
               // Check the currentWizard to be sure it's still active before parsing the command
               initCommandWizards();
               if (currentWizard != null || nextLine.trim().length() != 0) {
                  pendingInput.append(nextLine);

                  lastCommand = pendingInput.toString();
                  result = parseCommand(lastCommand, getParselet());
               }
               doProcessStatement(result, lastCommand);
               if (pendingInput.length() > 0) {
                  if (!consoleDisabled) {
                     System.out.print("Incomplete statement - q to cancel: ");
                     lastStatementError = true;
                  }
                  String remaining = pendingInput.toString();
                  int newLineIx = remaining.lastIndexOf("\n");
                  // Need to take away the newline for the prompt but put in a space in case the newline was the space separator in a command line input dump
                  if (newLineIx != -1 && newLineIx == remaining.length()-1) {
                     remaining = remaining.substring(0, newLineIx);
                     remaining = remaining + " ";
                  }
                  // When using the smart terminal, this killLine does not put the string back so we need to keep it in pendingInput
                  if (!consoleDisabled && smartTerminal) {
                     input.killLine();
                     input.putString(remaining);
                     pendingInput = new StringBuilder();
                  }
                  nextPrompt = "";
               }
               else {
                  lastStatementError = false;
                  // Note: any doLater jobs that we encounter here will not be synchronized with the command that's been run.  They can get here because
                  // pushCurrentScopeContext returns false - i.e. there's no defined scopeContextName like when we start running commands like TestPageLoader.loadAllPages().
                  // It can invalidate scope contexts - for global scope for example which add invokeLater calls that won't then get flushed out in popCurrentScopeCtx.
                  execLaterJobs();

                  nextPrompt = inputBytesAvailable() ? "" : prompt();
               }
            }
            if (pendingInputSources.size() == 0) {
               system.performExitCleanup();
               if (pendingInput.length() > 0) {
                  System.err.println("EOF with unprocessed input: " + pendingInput);
                  return false;
               }
               else
                  return true;
            }
            else if (returnOnInputChange) {
               if (pendingInput.length() > 0) {
                  System.err.println("Include: " + inputFileName + " with unprocessed input: " + pendingInput);
                  return false;
               }
               else
                  return true;
            }
            else {
               popCurrentInput();
            }
         }
         catch (IOException exc) {
            if (exc instanceof EOFException)
               return pendingInput.length() == 0;
            // Skip interrupted system calls
            if (!exc.getMessage().contains("Interrupted"))
               System.err.println("Error reading command input: " + exc);
            else {
               try {
                  input.getTerminal().reset();
                  resetInput();
               }
               // Shutdown in progress...
               catch (IllegalStateException ise) {
               }
               catch (Exception termExc) {
                  system.verbose("Exception in terminal reset: " + termExc);
               }
            }
         }
         finally {
            if (needsPushCtx)
               pushCurrentScopeContext();
         }
      } while (true);
   }

   void pushCurrentInput(boolean pushLayer) {
      InputSource oldInput = new InputSource();
      oldInput.inputFileName = inputFileName;
      oldInput.inputRelName = inputRelName;
      oldInput.includeLayer = includeLayer;
      oldInput.inputStream = inputStream;
      oldInput.consoleObj = input;
      oldInput.currentLine = currentLine;
      oldInput.pushLayer = pushLayer;
      oldInput.returnOnInputChange = returnOnInputChange;
      pendingInputSources.add(oldInput);
   }

   void popCurrentInput() {
      InputSource newInput = pendingInputSources.remove(pendingInputSources.size()-1);
      inputFileName = newInput.inputFileName;
      consoleDisabled = noPrompt || inputFileName != null; // System.console() == null when either input or output is redirected - either case, it's not interactive right?
      inputRelName = newInput.inputRelName;
      includeLayer = newInput.includeLayer;
      // When we have an include layer in the push, we will have set the currentLayer so need to restore that here.
      if (newInput.pushLayer) {
         Layer newLayer;
         if (newInput.includeLayer == null)
            newLayer = system.lastLayer;
         else
            newLayer = newInput.includeLayer;
         updateCurrentLayer(newLayer);
         currentLayerUpdated();
      }
      returnOnInputChange = newInput.returnOnInputChange;
      inputStream = newInput.inputStream;
      input = (ConsoleReader) newInput.consoleObj;
      currentLine = newInput.currentLine;
      smartTerminal = input.getTerminal().isSupported(); // Or isAnsiSupported?  Or isEchoEnabled()?  These also are different between the dumb IntelliJ terminal and the real command line
   }

   public int complete(String command, int cursor, List candidates, String ctxText, JavaModel fileModel, Object currentType) {
      if (currentWizard != null)
         return currentWizard.complete(command, cursor, candidates, currentType);

      return super.complete(command, cursor, candidates, ctxText, fileModel, currentType);
   }

   // For jline - to do command line completion
   public int complete(String command, int cursor, List candidates) {
      return complete(command, cursor, candidates, null, null, null);
   }

   /**
    * TODO: This is here as part of the thread implementation... should not be exposed to command methods.
    * Maybe We should add a special annotation here to mark public methods that are really supposed to be private to layers above the annotation.
    */
   public void run() {
      System.exit(readParseLoop() && !system.anyErrors ? 0 : 1);
   }

   public boolean inputBytesAvailable() {
      try {
         return inputStream.available() > 0;
      }
      catch (IOException exc) {
         return false;
      }
   }

   public int getTermWidth() {
      if (system.options.testVerifyMode || !smartTerminal) // allow the logs to look the same and for IntelliJ's terminal don't chop at 80
         return super.getTermWidth();
      return input.getTerminal().getWidth();
   }

   public String getCurrentFile() {
      return inputFileName == null ? path : inputFileName;
   }
}
