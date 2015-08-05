/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.lang.java.JavaModel;

import java.io.PrintWriter;

public class SaveModelWizard extends CommandWizard {
   enum Step {
      Confirm
   }

   Step currentStep;

   JavaModel modelToSave;

   public static void start(AbstractInterpreter cmd, JavaModel modelToSave) {
      SaveModelWizard wizard = new SaveModelWizard();
      wizard.commandInterpreter = cmd;
      wizard.currentStep = Step.Confirm;
      wizard.verbose = cmd.system.options.info;
      wizard.modelToSave = modelToSave;
      cmd.currentWizard = wizard;
   }

   public String prompt() {
      switch (currentStep) {
         case Confirm:
            print("\nModel: " + modelToSave + " has changed.\n\n");
            return "Save changes?: [y] ";
         default:
            return "???";
      }
   }

   public String getDefaultPackage() {
      return commandInterpreter.currentLayer == null ? "" : commandInterpreter.currentLayer.packagePrefix;
   }

   public Object parseCommand(String input) {
      try {
         switch (currentStep) {
            case Confirm:
               if (validateYesNo(input, true)) {
                  commandInterpreter.saveModel(modelToSave);
                  commandInterpreter.currentWizard = null;
               }
               else
                  commandInterpreter.currentWizard = null;
               break;
         }
      }
      catch (IllegalArgumentException exc) {
         System.err.println(exc.getMessage());
         commandInterpreter.currentWizard = null;
      }
      commandInterpreter.pendingInput = new StringBuffer();
      PrintWriter recWriter;
      // Record these commands
      if ((recWriter = commandInterpreter.recordOutputWriter) != null) {
         recWriter.println(input);
         recWriter.flush();
      }
      return Boolean.TRUE;
   }
}
