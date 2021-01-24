/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.lang.java.BodyTypeDeclaration;
import sc.lang.java.TypeDeclaration;

/*
 * A type used to define instances of enumerated constants from dynamic enum types.
 * <p>
 * Should extends java.lang.Enum and wrap DynObject but Java won't let us extend java.lang.Enum directly.
 * Instead, just duplicated the methods required by the enum contract here.
 */
public class DynEnumConstant extends DynObject {
   public DynEnumConstant(BodyTypeDeclaration btd) {
      super(btd);
   }

   public final String name() {
      return type.typeName;
   }

   public final int ordinal() {
      return type.getEnumOrdinal();
   }

   public String toString() {
      return name();
   }

   public TypeDeclaration getDynType() {
      return type.getEnclosingType();
   }
}
