/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.lang.java.ModelUtil;

import java.io.PrintWriter;
import java.util.*;

public class SelectObjectWizard extends CommandWizard {
   enum Step {
      Select
   }

   Step currentStep;

   Object statement;

   Object type;
   List<InstanceWrapper> instances;

   public static final Object NO_INSTANCES_SENTINEL = new String("No instances");

   public static Object start(AbstractInterpreter cmd, Object st) {
      SelectObjectWizard wizard = new SelectObjectWizard();
      wizard.commandInterpreter = cmd;
      wizard.currentStep = Step.Select;
      wizard.verbose = cmd.system.options.info;
      wizard.statement = st;
      wizard.type = cmd.currentTypes.get(cmd.currentTypes.size()-1);
      int max = 10;
      wizard.instances = cmd.getInstancesOfType(wizard.type, max, false);
      // Nothing to do - just skip the eval
      if (wizard.instances.size() == 0) {
         wizard.print("*** Warning: No instances for class: " + ModelUtil.getTypeName(wizard.type) + " skipping eval");
         return NO_INSTANCES_SENTINEL;
      }
      if (wizard.instances.size() == 1) {
         wizard.print("Class context - choosing singleton for type: " + ModelUtil.getTypeName(wizard.type));
         return wizard.instances.get(0).getInstance();
      }
      cmd.currentWizard = wizard;
      return null;
   }

   public String prompt() {
      switch (currentStep) {
         case Select:
            int i = 0;
            boolean first = true;
            for (InstanceWrapper wrapper:instances) {
               if (first) {
                  printFirst(type);
                  first = false;
               }
               print(indexPrompt(i) + wrapper);
               i++;
            }

            if (instances.size() == 0 && ModelUtil.getEnclosingType(type) == null && ModelUtil.isObjectType(type)) {
               if (first) {
                  printFirst(type);
               }
               print(indexPrompt(i) + "<Create>");
            }
            return "'s': skip eval: ";

         default:
            return "???";
      }
   }

   private String indexPrompt(int i) {
      return (i == 0 ? "<Enter>" : String.valueOf(i)) + ": ";
   }

   private void printFirst(Object type) {
         print("\nChoose instance for class: " + ModelUtil.getTypeName(type));
   }

   public Object parseCommand(String input) {
      try {
         switch (currentStep) {
            case Select:
               String inputStr = input.trim();
               if (!inputStr.equalsIgnoreCase("s")) {
                  int ix = -1;
                  try {
                     if (inputStr.length() == 0)
                        ix = 1;
                     else
                        ix = Integer.parseInt(inputStr);

                     ix--;
                     if (ix >= 0 && ix < instances.size()) {
                        Object inst = instances.get(ix).getInstance();
                        // Once the default current object is set, we can just process the statement.  It will then
                        // pull out the current object from the hash table and set it in the execContext.
                        commandInterpreter.setDefaultCurrentObj(type, inst);
                        commandInterpreter.processStatement(statement, false);
                     }
                  }
                  catch (NumberFormatException exc) {
                     break; // Do not advance the step
                  }
               }
               else
                  commandInterpreter.processStatement(statement, true);
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
