/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.java.TransformUtil;
import sc.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BuildCommandHandler {
   public String[] args;

   public String redirInputFile = null;
   public String redirOutputFile = null;
   public boolean redirErrors = false;

   public String checkTypeGroup;

   /** When set, this ensures the command is only run when you are building a layer which extends this layer. */
   public Layer definedInLayer;

   public String[] getExecArgs(LayeredSystem sys, Object templateArg) {
      ArrayList<String> resArgs = new ArrayList<String>(Arrays.asList(args));
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
      for (int i = 0; i < resArgs.size(); i++) {
         String arg = resArgs.get(i);
         if (arg == null)
            System.out.println("*** Invalid arg to command layer: " + this);
         if (arg.indexOf("<%") != -1) {
            resArgs.set(i, TransformUtil.evalTemplate(templateArg, arg, true));
            // Multiple valued args are surrounded by brackets
            if (resArgs.get(i).startsWith("[")) {
               arg = resArgs.get(i);
               arg = arg.substring(1, arg.length()-1);
               if (arg.length() == 0)
                  return null;
               String[] subArgs = StringUtil.split(arg, ' ');
               resArgs.remove(i);
               resArgs.addAll(i, Arrays.asList(subArgs));
               /*
               String[] newArgs = new String[args.length+subArgs.length-1];
               System.arraycopy(args, 0, newArgs, 0, i);
               System.arraycopy(subArgs, 0, newArgs, i, subArgs.length);
               if (i != args.length-1)
                  System.arraycopy(args, i+1, newArgs, i+subArgs.length, args.length-i-1);
               args = newArgs;
               */
               i += subArgs.length-1;
            }
         }
         else if (arg.equals("<")) {
            if (resArgs.size() - 1 > i) {
               resArgs.remove(i);
               redirInputFile = resArgs.get(i);
               resArgs.remove(i);
               i--;
            }
            else {
               System.err.println("Missing argument to file input redirect for: " + this);
               return null;
            }
         }
         else if (arg.equals(">") || arg.equals("&>")) {
            if (resArgs.size() - 1 > i) {
               resArgs.remove(i);
               redirOutputFile = resArgs.get(i);
               resArgs.remove(i);
               i--;
            }
            else {
               System.err.println("Missing argument to file output redirect for: " + this);
               return null;
            }
            redirErrors = arg.equals("&>");
         }
      }
      return resArgs.toArray(new String[resArgs.size()]);
   }
}
