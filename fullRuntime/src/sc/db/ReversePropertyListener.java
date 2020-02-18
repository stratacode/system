package sc.db;

import sc.bind.AbstractListener;
import sc.bind.Bind;
import sc.bind.BindingListener;
import sc.dyn.DynUtil;
import sc.type.IBeanMapper;

import java.util.ArrayList;
import java.util.List;

class ReversePropertyListener extends AbstractListener {
   DBObject obj;
   Object lastValue;
   DBPropertyDescriptor rprop;
   boolean valid = true;

   ReversePropertyListener(DBObject obj, Object lastValue, DBPropertyDescriptor reverseProp) {
      this.obj = obj;
      this.lastValue = lastValue;
      this.rprop = reverseProp;
   }

   public boolean valueInvalidated(Object lobj, Object prop, Object eventDetail, boolean apply) {
      if (!valid)
         return true;

      Object newVal = DynUtil.getPropertyValue(obj.getInst(), rprop.propertyName);
      if (!(DynUtil.equalObjects(newVal, lastValue))) {
         valid = false;
         return true;
      }
      // No value change so no need to call valueValidated
      return false;
   }

   public boolean valueValidated(Object lobj, Object prop, Object eventDetail, boolean apply) {
      IBeanMapper rmapper = rprop.getPropertyMapper();
      Object inst = obj.getInst();
      Object newVal = rmapper.getPropertyValue(inst, false, false);
      if (newVal == lastValue) {
         valid = true;
         return false;
      }
      DBPropertyDescriptor oprop = rprop.reversePropDesc;
      IBeanMapper omapper = oprop.getPropertyMapper();

      int numRemoved = 0;
      int numUpdated = 0;
      String relationType;

      if (rprop.multiRow) {
         List<Object> oldList = (List<Object>) lastValue;
         List<Object> newList = (List<Object>) newVal;

         lastValue = newVal;

         if (oprop.multiRow) {
            relationType = "many-to-many";
            // many-to-many
            // For each new value in the rprop collection, add it to the corresponding property in the oprop's collection
            // For each removed value in rprop, find it in the corresponding oprop and if not removed, remove it
            if (oldList != null) {
               int oldSize = oldList.size();
               for (int i = 0; i < oldSize; i++) {
                  Object oldElem = oldList.get(i);

                  // Still in the new list so don't remove this one - TODO performance: index this search to avoid N*N
                  if (newList != null && newList.contains(oldElem))
                     continue;

                  List oList = (List) omapper.getPropertyValue(oldElem, false, false);
                  int foundInOldIx = -1;
                  if (oList != null) {
                     int osz = oList.size();
                     for (int ox = 0; ox < osz; ox++) {
                        Object oElem = oList.get(ox);
                        if (oElem == inst) {
                           foundInOldIx = ox;
                           break;
                        }
                     }
                  }
                  if (foundInOldIx != -1) {
                     oList.remove(foundInOldIx);
                     numRemoved++;
                  }
               }
            }
            if (newList != null) {
               int nlsz = newList.size();
               for (int i = 0; i < nlsz; i++) {
                  Object newElem = newList.get(i);

                  // Already set in the old value - TODO: performance index this one like above
                  if (oldList != null && oldList.contains(newElem))
                     continue;

                  List oList = (List) omapper.getPropertyValue(newElem, false, false);
                  if (oList == null) {
                     oList = new ArrayList<Object>();
                     oList.add(inst);
                     numUpdated++;
                     omapper.setPropertyValue(newElem, oList);
                  }
                  else {
                     if (!oList.contains(inst)) {
                        oList.add(inst);
                        numUpdated++;
                     }
                  }
               }
            }
         }
         else {
            relationType = "many-to-one";
            // many-to-one
            // For each new value in rprop, find the property in oprop and set it to point to this
            // For each removed value in rprop, if it's set to this, and not removed, set it to null
            if (oldList != null) {
               for (Object oldElem:oldList) {
                  if (newList == null || !newList.contains(oldElem)) {
                     Object oldElemProp = omapper.getPropertyValue(oldElem, false, false);
                     if (oldElemProp == inst) {
                        omapper.setPropertyValue(oldElem, null);
                        numRemoved++;
                     }
                  }
               }
            }
            if (newList != null) {
               int nlsz = newList.size();
               for (int i = 0; i < nlsz; i++) {
                  Object newElem = newList.get(i);
                  if (oldList == null || !oldList.contains(newElem)) {
                     omapper.setPropertyValue(newElem, inst);
                     numUpdated++;
                  }
                  nlsz = newList.size(); // In case an entry was removed or added to this list - TODO: do we need to start over to avoid missing items here if something changed?
               }
            }
         }
      }
      else {
         if (oprop.multiRow) {
            relationType = "one-to-many";
            // Here the single-value side has changed it's value from oldVal to newVal. Get the current list property
            // one-to-many change - here we need to find the entry in the old list - if it's still there, replace it with the new value
            // otherwise add it to the collection
            Object oldVal = lastValue;
            if (oldVal == newVal)
               return false;

            boolean listNeedsSet = false;
            lastValue = newVal;

            List<Object> newList = (List<Object>)omapper.getPropertyValue(newVal, false, false);
            if (newList == null) {
               // TODO: create a DBList here?
               newList = new ArrayList<Object>();
               listNeedsSet = true;
            }
            if (oldVal != null) {
               int ix = newList.indexOf(oldVal);
               if (ix != -1) {
                  newList.remove(ix);
                  numRemoved++;
               }
            }
            if (newVal != null) {
               int ix = newList.indexOf(inst);
               if (ix == -1) {
                  newList.add(newVal);
                  numUpdated++;
               }
            }

            if (listNeedsSet && newList.size() > 0)
               omapper.setPropertyValue(newVal, newList);
         }
         else {
            relationType = "one-to-one";
            Object oldVal = lastValue;

            // Check if the oldValue is still pointing to this new value - set it to null if so since we are removing this reference
            if (oldVal != null) {
               Object oldProp = omapper.getPropertyValue(oldVal, false, false);
               if (oldProp == inst) {
                  omapper.setPropertyValue(oldVal, null);
                  numRemoved = 1;
               }
            }

            // Make the reverse direction change
            if (newVal != null) {
               // Before we point the new value at this one, need to update that property's ReversePropertyListener
               // so that it points to this one to avoid the recursive update going back.
               ReversePropertyListener oListener = getReverseListener(newVal, omapper);
               if (oListener != null)
                  oListener.lastValue = inst;

               Object curVal = omapper.getPropertyValue(newVal, false, false);
               lastValue = newVal;
               numUpdated = 0;
               if (curVal != inst) {
                  if (curVal != null) {
                     Object oldRVal = rmapper.getPropertyValue(curVal, false, false);
                     if (oldRVal == newVal) {
                        // Prevent the reverse side of this relationship from updating newVal again
                        ReversePropertyListener rListener = getReverseListener(curVal, rmapper);
                        if (rListener != null)
                           rListener.lastValue = null;
                        rmapper.setPropertyValue(curVal, null);
                        numUpdated++;
                     }
                  }
                  omapper.setPropertyValue(newVal, inst);
                  numUpdated++;
                  /*
                  if (curVal != null) {
                     Object oldRVal = rmapper.getPropertyValue(curVal, false, false);
                     if (oldRVal == newVal) {
                        System.out.println("***");
                        rmapper.setPropertyValue(curVal, null);
                        numUpdated++;
                     }
                  }
                  */
               }
            }
            else
               lastValue = null;
         }
      }
      if (numUpdated > 0 || numRemoved > 0)
         DBUtil.verbose(relationType + " property " + DBUtil.toIdString(inst) + ":" + rmapper + " changed -" + (numUpdated > 0 ? " added: " + numUpdated : "") + (numRemoved > 0 ? " removed: " + numRemoved : "") + " reverse references to property: " + oprop.dbTypeDesc.getTypeName() + "." + omapper);
      valid = true;
      return true;
   }

   static ReversePropertyListener getReverseListener(Object obj, IBeanMapper mapper) {
      BindingListener listenerList = Bind.getPropListeners(obj, mapper);
      while (listenerList != null) {
         if (listenerList.listener instanceof ReversePropertyListener)
            return (ReversePropertyListener) listenerList.listener;
         listenerList = listenerList.next;
      }
      return null;
   }
}
