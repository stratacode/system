/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.lang.java.JavaSemanticNode;
import sc.lang.java.TypeContext;
import sc.lang.java.TypeDeclaration;
import sc.lang.template.Template;
import sc.layer.LayeredSystem;

import java.util.EnumSet;

public interface IDefinitionProcessor {
   /** Returns a list of interface names to be appended onto objects or classes implementing this scope */
   public String[] getAppendInterfaces();

   public String getCustomResolver();

   public Template getCustomResolverTemplate(LayeredSystem sys);

   public String getCustomSetter();

   public Template getCustomSetterTemplate(LayeredSystem sys);

   public String getPreAssignment();

   public String getPostAssignment();

   public String getMixinTemplate();

   //public Object getInnerType(TypeDeclaration srcType, String name);

   public Object definesMember(TypeDeclaration srcType, String name, EnumSet<JavaSemanticNode.MemberType> mtype, TypeContext ctx);

   public String getStaticMixinTemplate();

   /** Returns a value of false for definitions which override the requirement of a field for an object definition. */
   public boolean getNeedsField();

   public void setProcessorName(String name);

   public String getProcessorName();
}
