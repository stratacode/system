/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.lang.java.JavaModel;
import sc.layer.SrcEntry;
import sc.util.FileUtil;

import java.io.File;
import java.util.ArrayList;

public class ModelEditorLauncher extends Thread implements IEditorSession {
   AbstractInterpreter interpreter;
   public JavaModel currentModel;

   ModelEditorLauncher(AbstractInterpreter interp, JavaModel model) {
      interpreter = interp;
      currentModel = model;
   }

   public void run() {
      editCurrentModel(true, true);
   }

   public void editCurrentModel(boolean doWait, boolean doRefresh) {
      ArrayList<String> args = new ArrayList<String>();
      String editor = System.getProperty("EDITOR");
      SrcEntry srcFile = currentModel.getSrcFile();
      if (editor == null)
         editor = "gvim";

      File f = new File(srcFile.absFileName);
      long startTime = f.lastModified();

      String sessionName = srcFile.getTypeName();

      args.add(editor);
      args.add("--servername");
      args.add(sessionName);
      if (doWait)
         args.add("--remote-wait");
      else
         args.add("--remote");
      args.add(srcFile.absFileName);

      //System.out.println("Starting editor: " + args);
      if (doWait)
         interpreter.addEditSession(sessionName, this);
      String res = null;
      try {
         res = FileUtil.execCommand(null, args, "", null, 0, false, null);
      }
      finally {
         if (doWait)
            interpreter.removeEditSession(sessionName, this);
      }

      if (res != null) {
         if (doWait) {
            if (startTime == f.lastModified()) {
               System.out.println("No changes");
            }
            else {
               if (doRefresh) {
                  System.out.println("Refreshing");
                  interpreter.autoRefresh();
               }
               else {
                  System.out.println("File stale, not refreshing");
               }
            }
         }
      }
      else {
         System.err.println("Error launching: " + args);
      }
   }


   // gvim --servername foo --remote-send "<C-\><C-N>:e<CR>"

   public void refreshModel(JavaModel model) {
      if (model.getModelTypeName().equals(currentModel.getModelTypeName())) {
         ArrayList<String> args = new ArrayList<String>();
         String editor = System.getProperty("EDITOR");
         SrcEntry srcFile = model.getSrcFile();
         if (editor == null)
            editor = "gvim";

         String sessionName = srcFile.getTypeName();

         args.add(editor);
         args.add("--servername");
         args.add(sessionName);
         args.add("--remote-send \"<C-[>:e<CR>\"");
      }
   }

   /** TODO: this does not appear to work - the original guy exits so we'll always use the wait mode */
   public void setCurrentModel(JavaModel model) {
      currentModel = model;
      editCurrentModel(false, false);
   }
}
