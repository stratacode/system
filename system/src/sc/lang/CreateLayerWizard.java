/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.layer.Layer;
import sc.layer.LayerUtil;
import sc.util.FileUtil;
import sc.util.StringUtil;

import java.io.PrintWriter;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class CreateLayerWizard extends CommandWizard {
   enum Step {
      Ask, Name, Dynamic, Public, Extends, Transparent, Package, Confirm
   }

   Step currentStep;

   String layerName;
   boolean isDynamic, isPublic, isTransparent;
   String[] extendsNames;
   String layerPackage;

   Set<Step> verboseSeen = new TreeSet();

   public static void start(AbstractInterpreter cmd, boolean askFirst) {
      CreateLayerWizard wizard = new CreateLayerWizard();
      wizard.commandInterpreter = cmd;
      wizard.currentStep = askFirst ? Step.Ask : Step.Name;
      wizard.verbose = cmd.system.options.info;
      cmd.addCommandWizard(wizard);
   }

   public String prompt() {
      switch (currentStep) {
         case Ask:
            print("\nNo current layers.\n\n");
            return "Create a new layer?: [y] ";
         case Name:
            // TODO: should let you specify an abs file name, or somehow change the layer root?
            if (!verboseSeen.contains(Step.Name)) {
               vprint("\nDirectory to hold your layer's files.  The directory may be a path: example/unitConverter where 'example' is the layer group and 'unitConverter' is the layer's directory.  You can refer to a layer by its path name 'example/unitConverter' or via dot notation: 'example.unitConverter'\n");
               verboseSeen.add(Step.Name);
            }
            return "Enter new layer directory: " + commandInterpreter.system.getNewLayerDir() + FileUtil.FILE_SEPARATOR_CHAR;
         case Dynamic:
            if (!verboseSeen.contains(Step.Dynamic)) {
               vprint("\nDynamic layers allow runtime code changes without restart for some frameworks.  For prototyping and assembling applications you see changes immediately or by refreshing.  Runtime dynamic layers implement styles, states, or dynamic forms.  Note: any layer which extends a dynamic layer is automatically dynamic itself.\n");
               verboseSeen.add(Step.Dynamic);
            }
            return "Types dynamic by default? y/n: [y] ";
         case Public:
            if (!verboseSeen.contains(Step.Public)) {
               vprint("\nStrataCode lets you define 'public' layers - useful to keep the code simpler when Java's access mechanisms are not needed.\n");
               verboseSeen.add(Step.Public);
            }
            return "Members public by default? y/n: [y] ";
         case Extends:
            if (!verboseSeen.contains(Step.Extends)) {
               vprint("\nYour layer may extend one or more base layers.  When you extend a layer you include the features of that layer as in a module.  You can also modify types in an extended layer as long as your layer's package includes the type.  Use <Tab> to complete layer names in your layer path.\n");
               verboseSeen.add(Step.Extends);
            }
            return "Layers to extend (optional): [] ";
         case Transparent:
            if (!verboseSeen.contains(Step.Transparent)) {
               vprint("\nWhen you extend at least one layer, you can make your layer transparent so all objects and properties in the base layers are by default shown in this layer in the UI.\n");
               verboseSeen.add(Step.Transparent);
            }
            return "Make layer transparent? y/n: [n] ";
         case Package:
            if (!verboseSeen.contains(Step.Package)) {
               vprint("\nSpecify an optional Java package name for the layer.  Files in the layer directory are placed in this package automatically.  Use this to reduce mostly empty directories and limit the scope of a layer to make classes easier to find.\n");
               verboseSeen.add(Step.Package);
            }
            return "Package: [" + getDefaultPackage() + "] ";
         case Confirm:
            return "\nAbout to create layer definition file: " + LayerUtil.getNewLayerDefinitionFileName(commandInterpreter.system, layerName) + ":\n\n" +
                    (isDynamic ? "dynamic " : "") + (isPublic ? "public " : "") + LayerUtil.getLayerTypeName(layerName) +
                    (extendsNames != null ? " extends " + StringUtil.arrayToString(extendsNames) : "") + " {\n}\n\nCreate? [y]: ";
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
            case Ask:
               if (validateYesNo(input, true))
                  currentStep = Step.Name;
               else
                  commandInterpreter.completeCommandWizard(this);
               break;
            case Name:
               try {
                  layerName = commandInterpreter.validateNewLayerPath(input);
               }
               catch (IllegalArgumentException exc) {
                  System.err.println("\nError: " + exc.getMessage() + "\n");
                  currentStep = Step.Name;
                  break;
               }
               currentStep = Step.Dynamic;
               break;
            case Dynamic:
               isDynamic = validateYesNo(input, true);
               currentStep = Step.Public;
               break;
            case Public:
               try {
                  isPublic = validateYesNo(input, true);
               }
               catch (IllegalArgumentException exc) {
                  System.err.println("\nError: " + exc.getMessage() + "\n");
                  currentStep = Step.Public;
                  break;
               }
               currentStep = Step.Package;
               break;
            case Package:
               try {
                  if (input.trim().length() == 0)
                     layerPackage = getDefaultPackage();
                  else
                     layerPackage = commandInterpreter.validateIdentifier(input);
               }
               catch (IllegalArgumentException exc) {
                  System.err.println("\nError: " + exc.getMessage() + "\n");
                  currentStep = Step.Package;
                  break;
               }
               currentStep = Step.Extends;
               break;
            case Extends:
               try {
                  extendsNames = commandInterpreter.validateExtends(input);
                  if (extendsNames == null)
                     currentStep = Step.Confirm;
                  else
                     currentStep = Step.Transparent;
               }
               catch (IllegalArgumentException exc) {
                  System.err.println("\nError: " + exc.getMessage() + "\n");
                  currentStep = Step.Extends;
                  break;
               }
               break;
            case Transparent:
               try {
                  isTransparent = validateYesNo(input, false);
               }
               catch (IllegalArgumentException exc) {
                  System.err.println("\nError: " + exc.getMessage() + "\n");
                  currentStep = Step.Transparent;
                  break;
               }
               currentStep = Step.Confirm;
               break;
            case Confirm:
               try {
                  if (validateYesNo(input, true)) {
                     Layer layer = commandInterpreter.system.createLayer(layerName, layerPackage, extendsNames, isDynamic, isPublic, isTransparent, true, true);
                     commandInterpreter.setCurrentLayer(layer);
                  }
               }
               catch (IllegalArgumentException exc) {
                  System.err.println("\nError: " + exc.getMessage() + "\n");
                  currentStep = Step.Confirm;
                  break;
               }
               commandInterpreter.completeCommandWizard(this);
               break;
         }
      }
      catch (IllegalArgumentException exc) {
         System.err.println(exc.getMessage());
         commandInterpreter.completeCommandWizard(this);
      }
      commandInterpreter.pendingInput = new StringBuilder();
      PrintWriter recWriter;
      // Record these commands
      if ((recWriter = commandInterpreter.recordOutputWriter) != null) {
         recWriter.println(input);
         recWriter.flush();
      }
      return Boolean.TRUE;
   }

   public int complete(String command, int cursor, List candidates, Object currentType) {
      switch (currentStep) {
         case Extends:
            return commandInterpreter.completeExistingLayer(command, cursor, candidates);
      }
      return -1;
   }
}
