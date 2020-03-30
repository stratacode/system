/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.layer.Layer;
import sc.layer.LayeredSystem;

import java.util.List;

public class AddLayerWizard extends CommandWizard {
   enum Step {
      LayerNames, MakeDynamic
   }

   Step currentStep;

   boolean isDynamic;
   String[] layerNames;
   Layer[] layersToAdd;

   public static void start(AbstractInterpreter cmd) {
      AddLayerWizard wizard = new AddLayerWizard();
      wizard.commandInterpreter = cmd;
      wizard.currentStep = Step.LayerNames;
      wizard.verbose = cmd.system.options.info;
      cmd.addCommandWizard(wizard);
   }

   public String prompt() {
      switch (currentStep) {
         case LayerNames:
            print("Adds these layers to system. <Tab> to list/complete, <Space> to separate.\n\n");
            return "Enter layers to add: " + commandInterpreter.system.getNewLayerDir() + "/: ";
         case MakeDynamic:
            return "Make layers dynamic by default? [y]: ";
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
            case LayerNames:
               layerNames = commandInterpreter.validateExtends(input);
               currentStep = Step.MakeDynamic;
               break;
            case MakeDynamic:
               isDynamic = validateYesNo(input, true);
               doAddLayers();
               break;
         }
      }
      catch (IllegalArgumentException exc) {
         System.err.println(exc.getMessage());
         commandInterpreter.completeCommandWizard(this);
      }
      commandInterpreter.pendingInput = new StringBuilder();
      return Boolean.TRUE;
   }

   public void doAddLayers() {
      LayeredSystem sys = commandInterpreter.system;
      sys.addLayers(layerNames, isDynamic, commandInterpreter.execContext);
      commandInterpreter.completeCommandWizard(this);
      commandInterpreter.setCurrentLayer(sys.lastLayer);
   }

   public int complete(String command, int cursor, List candidates, Object currentType) {
      switch (currentStep) {
         case LayerNames:
            return commandInterpreter.completeExistingLayer(command, cursor, candidates);
      }
      return -1;
   }

}
