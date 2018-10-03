package sc.lang.html;

import sc.bind.AbstractListener;

public class RepeatListener extends AbstractListener {
   public Element tag;
   public RepeatListener(Element tag) {
      this.tag = tag;
   }

   // This is called when the IChangeable value of the property fires an event on itself - e.g. a list.set call which fires the default event to tell us that list has been changed.
   // We react by marking the owning property as changed.  So if you are listening to the property value, you'll see a change.
   public boolean valueInvalidated(Object obj, Object prop, Object eventDetail, boolean apply) {
      if (tag.anyChangedRepeatTags()) {
         tag.invalidateBody();
      }
      return true;
   }
}
