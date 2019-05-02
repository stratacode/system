/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.js;

import sc.lang.ISemanticNode;
import sc.lang.java.*;
import sc.layer.LayeredSystem;
import sc.parser.ParseUtil;
import sc.type.CTypeUtil;

import java.util.HashSet;

public class JSUtil {
   public final static String ShadowedPropertyPrefix = "_";

   public final static HashSet<String> jsKeywords = new HashSet<String>();

   static {
      jsKeywords.add("in");
   }


   private JSUtil() {}

   public static CharSequence convertAndFormatExpression(Expression st) {
      return ((Expression) convertToJS(st)).toLanguageString();
   }

   public static JavaSemanticNode convertToJS(JavaSemanticNode st) {
      // No need to convert or format the BodyTypes. This only happens for merge mode to preserve the order of
      // statements in the inner type with the outer type.
      if (st instanceof BodyTypeDeclaration)
         return st;
      Statement newSt = (Statement) st.deepCopy(ISemanticNode.CopyNormal | ISemanticNode.CopyInitLevels, null);
      // So it can resolve references properly and get typed for the conversion
      newSt.parentNode = st.parentNode;
      newSt.changeLanguage(JSLanguage.getJSLanguage());
      ParseUtil.initAndStartComponent(newSt);
      return newSt.transformToJS();
   }

   public static String convertTypeName(LayeredSystem sys, String fullTypeName) {
      if (sys.runtimeProcessor instanceof JSRuntimeProcessor) {
         JSRuntimeProcessor jsrt = (JSRuntimeProcessor) sys.runtimeProcessor;

         String replace = jsrt.jsBuildInfo.replaceTypes.get(fullTypeName);
         if (replace != null)
            return replace;

         replace = jsrt.jsBuildInfo.replaceNativeTypes.get(fullTypeName);
         if (replace != null)
            return replace;

         String pendingName = fullTypeName;
         String middle = "";

         do {
            String pkgName = CTypeUtil.getPackageName(pendingName);
            if (pkgName == null)
               break;
            String prefix;
            if ((prefix = jsrt.jsBuildInfo.prefixAliases.get(pkgName)) != null)
               return prefix + CTypeUtil.prefixPath(middle, CTypeUtil.getClassName(fullTypeName)).replace('.', '_');
            middle = CTypeUtil.prefixPath(CTypeUtil.getClassName(pkgName), middle);
            pendingName = CTypeUtil.getPackageName(pendingName);
         } while(true);
      }
      return fullTypeName.replace('.', '_');
   }
}
