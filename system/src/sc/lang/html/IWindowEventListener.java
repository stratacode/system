/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

public interface IWindowEventListener {
   void screenSizeChanged(Window win);
   void windowClosed(Window win, boolean sessionExpires);
}
