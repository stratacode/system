/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import java.util.ArrayList;

/**
 * This is a simple API to facilitate scripting of UIs which also prompt for dialogs.  It's a place to stash
 * the script's chosen answer for a dialog that's to come later.   Use cmd.addDialogAnswer(dialogName, answerValue)
 * from your script and call DialogManager.getDialogAnswer(dialogName) from the code before creating the dialog.
 *
 * Implementation note: originally I thought maybe we needed something fancier - like the ability to register an
 * answer for a dialog that's already visible but there might be complexities because the command line interpreter is
 * on the same thread as the dialog request and so the two can't overlap.  Maybe this should go away and we could just
 * add an API to the swing utilities for this?   Or ideally, you can structure your "view model" so that the script can
 * drive the UI entirely by setting this properties.
 */
public class DialogManager {

   static class DialogAnswer {
      String dialogName;
      Object answerValue;
      public DialogAnswer(String dn, Object val) {
         dialogName = dn;
         answerValue = val;
      }
   }

   public final static ArrayList<DialogAnswer> pendingAnswers = new ArrayList<DialogAnswer>();

   public static Object getDialogAnswer(String dialogName) {
      DialogAnswer answer = null;
      synchronized (pendingAnswers) {
         for (int i = 0; i < pendingAnswers.size(); i++) {
            answer = pendingAnswers.get(i);
            if (answer.dialogName.equals(dialogName)) {
               pendingAnswers.remove(i);
               break;
            }
            else
               answer = null;
         }
      }
      // The answer is already here - so we'll tell the answerer it's ready and notify the dialog in a 'doLater' - so it
      // it's at least had a chance to
      if (answer != null) {
         System.out.println("*** Found existing answer for dialog: " + dialogName + " answering with: " + answer.answerValue);
         return answer.answerValue;
      }
      else
         System.out.println("*** No scripted answer for dialog: " + dialogName + " found");
      return null;
   }

   public static void addDialogAnswer(String dialogName, Object answerValue) {
      System.out.println("*** Adding dialogAnswer: " + dialogName + " = " + answerValue);
      DialogAnswer waitReq = new DialogAnswer(dialogName, answerValue);
      pendingAnswers.add(waitReq);
   }
}
