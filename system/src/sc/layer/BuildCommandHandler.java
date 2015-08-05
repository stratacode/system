/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.java.TransformUtil;
import sc.util.StringUtil;

import java.util.List;

public class BuildCommandHandler {
   public String[] args;

   public String checkTypeGroup;

   /** When set, this ensures the command is only run when you are building a layer which extends this layer. */
   public Layer definedInLayer;

   public String[] getExecArgs(LayeredSystem sys, Object templateArg) {
      if (checkTypeGroup != null) {
         List<TypeGroupMember> tgms;
         if ((tgms = sys.buildInfo.getTypeGroupMembers(checkTypeGroup)) == null || tgms.size() == 0)
            return null;
         boolean changed = false;
         // Need to look for entities which have actually changed since typically the command line template will
         // only need to process changed entities in this type group (e.g. openjpa/openjpa.sc for processing entities)
         for (TypeGroupMember t:tgms) {
            if (t.changed) {
               changed = true;
               break;
            }
         }
         if (!changed)
            return null;
      }
      for (int i = 0; i < args.length; i++) {
         String arg = args[i];
         if (arg == null)
            System.out.println("*** Invalid arg to command layer");
         if (arg.indexOf("<%") != -1) {
            args[i] = TransformUtil.evalTemplate(templateArg, arg, true);
            // Multiple valued args are surrounded by brackets
            if (args[i].startsWith("[")) {
               args[i] = args[i].substring(1, args[i].length()-1);
               if (args[i].length() == 0)
                  return null;
               String[] subArgs = StringUtil.split(args[i], ' ');
               String[] newArgs = new String[args.length+subArgs.length-1];
               System.arraycopy(args, 0, newArgs, 0, i);
               System.arraycopy(subArgs, 0, newArgs, i, subArgs.length);
               if (i != args.length-1)
                  System.arraycopy(args, i+1, newArgs, i+subArgs.length, args.length-i-1);
               args = newArgs;
               i += subArgs.length-1;
            }
         }
      }
      return args;
   }
}
