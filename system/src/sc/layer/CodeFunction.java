/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import java.util.EnumSet;

/**
 * An attempt to break down the function code and configuration has in a given application by the role of the developer who manages that code.
 * Layers can set a codeFunction to help categorize the code it contains.
 */
public enum CodeFunction {
   // TODO: add Framework, and Persist.  Change Program to Application and add a new layer property called 'configLayer' that works a bit like annotationLayer but primarily as a way to sort
   // configuration layers from layers with code.  Rename to CodeType and get rid of CodeType
   Model, UI, Style, Program, Admin, Deploy;

   public static EnumSet<CodeFunction> allSet = EnumSet.allOf(CodeFunction.class);
}

