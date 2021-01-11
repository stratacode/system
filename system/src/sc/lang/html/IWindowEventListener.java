package sc.lang.html;

public interface IWindowEventListener {
   void screenSizeChanged(Window win);
   void windowClosed(Window win, boolean sessionExpires);
}
