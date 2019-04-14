package sc.lang.html;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class FocusEvent extends Event {
   public Element relatedTarget;
}
