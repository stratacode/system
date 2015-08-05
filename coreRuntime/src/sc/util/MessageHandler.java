/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import sc.util.IMessageHandler;
import sc.util.MessageType;

public class MessageHandler implements IMessageHandler {
   public String err;
   public String warning;
   public String info;
   public String verbose;
   public String sysDetails;
   public void reportMessage(CharSequence msg, String url, int line, int col, MessageType type) {
      String res = msg.toString();
      switch (type) {
         case Error:
            err = res;
            break;
         case Warning:
            warning = res;
            break;
         case Info:
            info = res;
            break;
         case Debug:
            verbose = res;
            break;
         case SysDetails:
            sysDetails = res;
            break;
         default:
            err = res;
            break;
      }
   }

   public static void info(IMessageHandler handler, CharSequence...args) {
      reportMessage(handler, MessageType.Info, args);
   }

   public static void error(IMessageHandler handler, CharSequence...args) {
      reportMessage(handler, MessageType.Error, args);
   }

   public static void reportMessage(IMessageHandler messageHandler, MessageType type, CharSequence... args) {
      StringBuilder sb = new StringBuilder();
      for (CharSequence arg:args) {
         sb.append(arg);
      }
      if (messageHandler != null)
         messageHandler.reportMessage(sb, null, -1, -1, type);
      else {
         if (type == MessageType.Error)
            System.err.println(sb);
         else {
            switch (type) {
               case Warning:
               case Info:
                  break;
               case Debug:
                  return;
               case SysDetails:
                  return;
            }
            System.out.println(sb);
         }
      }
   }
}
