/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.template;

import sc.lang.TemplateLanguage;
import sc.lang.java.AbstractBlockStatement;
import sc.lang.java.Definition;
import sc.lang.java.TypeContext;

import java.util.EnumSet;

public class TemplateStatement extends AbstractBlockStatement {
   Definition movedToDefinition;

   public Object findMember(String name, EnumSet<MemberType> mtype, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      if (movedToDefinition != null)
         return movedToDefinition.findMember(name, mtype, fromChild, refType, ctx, skipIfaces);
      return super.findMember(name, mtype, fromChild, refType, ctx, skipIfaces);
   }

   public String getStartBlockToken() {
      return TemplateLanguage.START_CODE_DELIMITER;
   }

   public String getStartBlockString() {
      return TemplateLanguage.START_CODE_DELIMITER;
   }

   public String getEndBlockString() {
      return TemplateLanguage.END_DELIMITER;
   }
}
