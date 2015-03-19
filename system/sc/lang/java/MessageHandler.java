/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.IMessageHandler;
import sc.lang.MessageType;

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
}
