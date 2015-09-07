/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.layer.Layer;
import sc.type.CTypeUtil;
import sc.type.DynType;
import sc.layer.LayeredSystem;

import java.util.*;

/**
 * This class gets used for a weird case, when coercing one type into another to determine the resulting type of a
 * QuestionMarkOperator (i.e. where the resulting type is the overlapping type formed by each value),
 * there are times we have to create essentially a new type that collects just the overlapping interfaces between the two types we are
 * coercing into one.
 * */
public class CoercedTypeDeclaration extends WrappedTypeDeclaration {
   Object[] interfaces;

   public CoercedTypeDeclaration(Object it, Object[] ifaces) {
      super(it);
      interfaces = ifaces;
   }

   public boolean isAssignableFrom(ITypeDeclaration other, boolean assignmentSemantics) {
      if (super.isAssignableFrom(other, assignmentSemantics))
         return true;
      for (Object iface:interfaces)
         if (ModelUtil.isAssignableFrom(iface, other, assignmentSemantics, null))
            return true;
      return false;
   }

   public boolean isAssignableTo(ITypeDeclaration other) {
      if (super.isAssignableTo(other))
         return true;
      for (Object iface:interfaces) {
         if (iface == other)
            return true;
         if (iface instanceof ITypeDeclaration && ((ITypeDeclaration) iface).isAssignableTo(other))
            return true;
      }
      return false;
   }

   public boolean implementsType(String otherTypeName, boolean assignment, boolean allowUnbound) {
      // TODO: should we verify that our parameters match if the other type has assigned params too?
      if (ModelUtil.implementsType(baseType, otherTypeName, assignment, allowUnbound))
         return true;
      for (Object iface:interfaces) {
         if (ModelUtil.implementsType(iface, otherTypeName, assignment, allowUnbound))
            return true;
      }
      return false;
   }
}
