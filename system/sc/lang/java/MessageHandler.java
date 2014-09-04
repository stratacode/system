/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.IMessageHandler;
import sc.lang.MessageType;

public class MessageHandler implements IMessageHandler {
   public String err;
   public boolean isWarning;
   public void reportMessage(String msg, String url, int line, int col, MessageType type) {
      err = msg;
      isWarning = type == MessageType.Warning;
   }
}
