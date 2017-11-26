/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.layer.LayeredSystem;
import sc.util.FileUtil;

import java.io.*;

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
               execLaterJobs();

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
