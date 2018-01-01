/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.layer.LayeredSystem;
import sc.util.FileUtil;

import java.io.*;
import java.util.List;

@sc.js.JSSettings(replaceWith="sc_EditorContext")
public class CommandInterpreter extends AbstractInterpreter {
   BufferedReader input;

   public CommandInterpreter(LayeredSystem sys, BufferedReader inputStream, String inputFileName) {
      super(sys, inputFileName != null, inputFileName);
      updateInputSource(inputStream, inputFileName);
   }

   void updateInputSource(BufferedReader inputStream, String inputFileName) {
      try {
         input = inputStream != null ? inputStream : new BufferedReader(new FileReader(inputFileName));

      }
      catch (FileNotFoundException exc) {
         System.err.println("*** CommandInterpreter failed to open inputFileName: " + inputFileName + ": " + exc);
         input = new BufferedReader(new InputStreamReader(System.in));
      }
   }

   void resetInput() {
      updateInputSource(inputFileName == null ? input : null, inputFileName);
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

   @Override
   public boolean inputBytesAvailable() {
      try {
         return input.ready();
      }
      catch (IOException exc) {
         return false;
      }
   }

   public boolean readParseLoop() {
      initReadThread();
      do {
         try {
            String nextLine;
            String nextPrompt = inputBytesAvailable() ? "" : prompt();
            if (!noPrompt)
               System.out.print(nextPrompt);
            while ((nextLine = input.readLine()) != null) {
               currentLine++;
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
                     Object errSt = result;
                     if (errSt instanceof List && ((List) errSt).size() == 1)
                        errSt = ((List) errSt).get(0);
                     System.err.println("Script error: " + exc.toString() + " for statement: " + errSt);
                     if (system.options.verbose)
                        exc.printStackTrace();
                     if (exitOnError) {
                        System.err.println("Exiting -1 on error because cmd.exitOnError configured as true");
                        System.exit(-1);
                     }
                  }
               }
               if (pendingInput.length() > 0) {
                  if (!consoleDisabled && !noPrompt)
                     nextPrompt = "Incomplete statement: ";
               }
               else {
                  execLaterJobs();

                  nextPrompt = inputBytesAvailable() ? "" : prompt();
               }
               if (!noPrompt)
                  System.out.print(nextPrompt);
            }
            // at EOF on this input file
            if (pendingInputSources.size() == 0) {
               system.performExitCleanup();
               if (pendingInput.length() > 0) {
                  System.err.println("EOF with unprocessed input: " + pendingInput);
                  return false;
               }
               else
                  return true;
            }
            else
               popCurrentInput();
         }
         catch (IOException exc) {
            System.out.println("Error reading command input: " + exc);
            return false;
         }
         if (pendingInput.length() > 0) {
            System.err.println("EOF with unprocessed input: " + pendingInput);
            return false;
         }
      } while (true);
   }

   void pushCurrentInput(boolean pushLayer) {
      InputSource oldInput = new InputSource();
      oldInput.inputFileName = inputFileName;
      oldInput.inputRelName = inputRelName;
      oldInput.includeLayer = includeLayer;
      oldInput.inputReader = input;
      oldInput.currentLine = currentLine;
      oldInput.pushLayer = pushLayer;
      pendingInputSources.add(oldInput);
   }

   void popCurrentInput() {
      InputSource newInput = pendingInputSources.remove(pendingInputSources.size()-1);
      inputFileName = newInput.inputFileName;
      includeLayer = newInput.includeLayer;
      input = (BufferedReader) newInput.inputReader;
      currentLine = newInput.currentLine;
      // When we have an include layer in the push, we will have set the currentLayer so need to restore that here.
      if (newInput.pushLayer) {
         if (newInput.includeLayer == null)
            currentLayer = system.lastLayer;
         else
            currentLayer = newInput.includeLayer;
         updateCurrentLayer();
      }
   }

   /** TODO: This is here as part of the thread implementation... should not be exposed to command methods.  needs @private */
   public void run() {
      System.exit(readParseLoop() ? 1 : 0);
   }
}
