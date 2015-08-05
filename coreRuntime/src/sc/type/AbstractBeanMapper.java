/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import sc.bind.Bind;
import sc.bind.BindingDirection;
import sc.bind.IBinding;
import sc.bind.IListener;

public abstract class AbstractBeanMapper implements IBeanMapper, IBinding, Cloneable {

   public int hashCode() {
      return getPropertyName().hashCode();
   }

   public void addBindingListener(Object eventObject, IListener listener, int event) {
      if (!isConstant())
         Bind.addListener(eventObject, this, listener, event);
   }

   public void removeBindingListener(Object eventObject, IListener listener, int event) {
      if (!isConstant())
         Bind.removeListener(eventObject, this, listener, event);
   }

   public void invalidateBinding(Object obj, boolean sendEvent, boolean includeParams) {
      if (sendEvent && !isConstant())
         Bind.sendEvent(IListener.VALUE_CHANGED, obj, this);
   }

   public boolean applyBinding(Object obj, Object val, IBinding src) {
      Object type;
      Class cl;
      // Do unwrapping for primitive integer, float, etc. types
      if (val == null && (type = getPropertyType()) instanceof Class && (cl = (Class) type).isPrimitive()) {
         val = Type.get(cl).getDefaultObjectValue();
      }
      boolean endLogIndent = false;
      try {
         if (Bind.trace)
            endLogIndent = Bind.logPropMessage("eval", obj, this, val);
         setPropertyValue(obj, val);

         // TODO: should we have an option to do a dirty check here and not set the value if it hasn't changed?
         return true;
      }
      finally {
         if (endLogIndent)
            Bind.endPropMessage();
      }
   }

   public void applyReverseBinding(Object obj, Object val, Object src) {
      // For the case: textString :=: question.answerChoices[0], can't set if question is null.
      if (obj != null)
         setPropertyValue(obj, val);
   }

   public abstract Object performCast(Object val);

   /**
    * This method is part of the IBinding contract which BeanMapper does not need to implement.
    * This IBinding implementation adds/removes bindings via bindListener
    */
   public void removeListener() {}

   /** This method is not applicable to global binding objects like this one */
   public Object initializeBinding() {
      return null;
   }

   /** Again, for stateful bindings only... those which notify their parent directly */
   public void setBindingParent(IBinding parent, BindingDirection direction) {
   }

   public boolean isWritable() {
      return getSetSelector() != null || (getField() != null && !isConstant());
   }

   public abstract boolean isConstant();

   public void parentBindingChanged() {}

   public void activate(boolean state, Object obj, boolean chained) {
   }

   public String toString() {
      return getPropertyName();
   }

   /**
    * Compares same property defined in different types in the same type hierarchy as equal.  Could be made more efficient.
    */
   public boolean equals(Object other) {
      if (other instanceof IBeanMapper) {
         return PTypeUtil.compareBeanMappers(this, (IBeanMapper) other);
      }
      // ?? Do we need to handle comparison against field, get/set method etc?
      return false;
   }

   public boolean isReversible() {
      return !isConstant();
   }
}
