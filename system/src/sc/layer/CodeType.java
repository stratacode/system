/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import java.util.EnumSet;

/**
 * The top-level categorization of layers by type. When a layer contains more than one, either don't set it or choose Application. While
 * this is mainly used for navigation in the IDE and management UI and so optional, but Framework layers are put below others in the layer stack.
 * TODO: now we also have configLayer a property in the Layer that also affects sorting and overlaps with these settings here. Do we need
 * a better way to designate 'runnable' layers - like those that only specify dependencies? Or maybe that's a separate property like configLayer.
 * also need a way to see if layers are compatible and maybe to suggest 'option' layers for when you include one layer - see all of the options
 * it can be used with to choose another layer. Maybe a wizard that starts at the frameworks, and works down to find a run configuration?
 */
public enum CodeType {
   Model, UI, Style, Application, Persist, Framework, Admin, Deploy;

   public static EnumSet<CodeType> allSet = EnumSet.allOf(CodeType.class);

   public static EnumSet<CodeType> nonFrameworkSet = EnumSet.of(CodeType.Model, CodeType.UI, CodeType.Style, CodeType.Application, CodeType.Admin, CodeType.Style, CodeType.Persist, CodeType.Deploy);
}
