/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.template;

import sc.lang.ISrcStatement;
import sc.lang.html.Element;
import sc.lang.java.*;
import sc.layer.TypeIndexEntry;

import java.util.EnumSet;

/** Represents a fragment of template declarations in a template file, i.e. &lt;%! */
public class TemplateDeclaration extends TypeDeclaration {
   // During init, we convert the TemplateDeclaration into Java - we might move this as the rootType or we might move this into a method.  In that case, we need to track that method and use it to resolve things.
   Definition movedToDefinition;

   // TODO: if this is a top-level declaration, should it act like the modified type?
   public DeclarationType getDeclarationType() {
      return DeclarationType.TEMPLATE;
   }

   public String getFullTypeName() {
     // TODO: it might be nice to cache this here, but if we the refreshStyle test breaks.  Somehow sc.test.editor2.EditorPanel.BaseTypeTree gets
     // it's type name incorrectly as sc.test.editor2.div.BaseTypeTree due to this cached value.
     // if (fullTypeName != null)
     //    return fullTypeName;
      Element enclTag = getEnclosingTag();
      if (enclTag != null && enclTag.needsObject()) {
         fullTypeName = enclTag.getFullTypeName();
         return fullTypeName;
      }
      Template temp = (Template) getJavaModel();
      if (temp == null)
         return "_internalTemplateDeclaration";
      fullTypeName = temp.getModelTypeName();
      return fullTypeName;
   }

   public void unregister() {
   }

   public Object findMember(String name, EnumSet<MemberType> mtype, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      if (movedToDefinition != null)
         return movedToDefinition.findMember(name, mtype, fromChild, refType, ctx, skipIfaces);
      return super.findMember(name, mtype, fromChild, refType, ctx, skipIfaces);
   }

   public boolean isRealType() {
      TypeDeclaration enclType = getEnclosingType();
      // If we're the top-level type, thi sis a real type.  Otherwise, these definitions will get added into that type.
      return enclType == null;
   }

   public boolean needsTransform() {
      return true;
   }

   public boolean getNodeContainsPart(ISrcStatement fromSt) {
      if (fromSt == this)
         return true;
      if (body != null) {
         for (Statement st:body)
            if (st == fromSt || st.getNodeContainsPart(fromSt))
               return true;
      }
      return false;
   }

   public TypeIndexEntry createTypeIndex() {
      if (!isRealType())
         return null;
      return super.createTypeIndex();
   }
}
