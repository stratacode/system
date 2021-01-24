/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.AbstractListener;
import sc.bind.BindingContext;
import sc.bind.IListener;

public class RepeatListener extends AbstractListener {
   public Element tag;
   public RepeatListener(Element tag) {
      this.tag = tag;
   }

   // This is called when the IChangeable value of the property fires an event on itself - e.g. a list.set call which fires the default event to tell us that list has been changed.
   // We react by marking the owning property as changed.  So if you are listening to the property value, you'll see a change.
   public boolean valueInvalidated(Object obj, Object prop, Object eventDetail, boolean apply) {
      // We used to check if repeat tags were changed here but that's not cheap and the value may not have been
      // updated yet since we are in valueInvalidated and the change might be queued.
      tag.invalidateRepeatTags();
      return true;
   }
}
