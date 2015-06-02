/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import sc.util.MessageType;

public interface IMessageHandler {
   void reportMessage(CharSequence msg, String url, int line, int col, MessageType type);
}
