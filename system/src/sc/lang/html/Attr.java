/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.lang.*;
import sc.lang.java.*;
import sc.lang.template.Template;
import sc.layer.LayeredSystem;
import sc.layer.SrcEntry;
import sc.parser.*;
import sc.util.FileUtil;
import sc.util.URLUtil;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class Attr extends Node implements ISrcStatement {
   public String name;
   // Can be either an IString, TemplateExpression, or AttrExpr - which holds the op and expression
   public Object value;

   // The expression which should be used to compute the attribute's value
   public transient Expression valueExpr, valueExprClone;
   public transient Object valueProp;
   public transient String op;
   public transient boolean unknown = false;
   // Is this an href or other URL reference
   public transient boolean link = false;

   // The Element tag which defined this attribute
   public transient Element declaringTag;

   private transient boolean attInit = false;

   public boolean isString() {
      return PString.isString(value);
   }

   public String getOutputString() {
      if (valueExpr instanceof StringLiteral)
         return ((StringLiteral) valueExpr).value;
      return value.toString();
   }

   public Expression getOutputExpr() {
      Element enclTag = getEnclosingTag();
      initAttribute(enclTag);

      if (valueExprClone != null)
         return valueExprClone;

      if (valueExpr != null)
         return valueExpr;
      if (value instanceof Expression)
         return (Expression) value;
      return null;
   }

   public void resetAttribute() {
      attInit = false;
   }

   public String getOperationFromExpression(String attStr) {
      String opStr = null;
      // If the first few chars of the attribute value are the operator - we always parse via an expression.  similar to excel's rules
      if (attStr.length() > 1) {
         if (attStr.charAt(0) == ':') {
            if (attStr.charAt(1) == '=') {
               if (attStr.length() > 2 && attStr.charAt(2) == ':')
                  opStr = ":=:";
               else
                  opStr = ":=";
            }
         }
         else if (attStr.charAt(0) == '=') {
            if (attStr.charAt(1) == ' ')
               opStr = "=";
            else if (attStr.charAt(1) == ':')
               opStr = "=:";
         }
      }
      return opStr;
   }

   public void initAttribute() {
      initAttribute(getEnclosingTag());
   }

   public Element getDeclaringTag() {
      if (declaringTag != null)
         return declaringTag;
      return getEnclosingTag();
   }

   public void initAttribute(Element tag) {
      if (attInit)
         return;

      String propName = Element.mapAttributeToProperty(name);

      if (declaringTag == null)
         declaringTag = tag;
      attInit = true;
      boolean htmlAttribute = tag.isHtmlAttribute(name);
      boolean behaviorAttribute = tag.isBehaviorAttribute(name);
      link = tag.isLinkAttribute(name);
      LayeredSystem sys = getLayeredSystem();
      Object prop = tag.definesMember(propName, MemberType.PropertySetSet, tag.tagObject, null, false, false);
      valueProp = prop;
      if (prop == null && !behaviorAttribute && !htmlAttribute) {
         prop = tag.definesMember(propName, MemberType.PropertySetSet, null, null, false, false);
         if (prop != null) {
            displayError("Attribute: " + name + " for tag: " + tag.tagObject.getFullTypeName() + " refers to inaccessible property in type: " + ModelUtil.getTypeName(ModelUtil.getEnclosingType(prop)) + " with access: " +
                         ModelUtil.getAccessLevelString(prop, false, MemberType.SetMethod) + " for: ");
         }
      }

      Object attValue = value;
      Expression init = null;
      String op = "=";
      if (PString.isString(attValue)) {
         String attStr = attValue.toString();
         String opStr = getOperationFromExpression(attStr);

         Object propType = prop == null ? String.class : ModelUtil.getPropertyType(prop);

         if (opStr == null) {
            // This is the case where you have a normal html attribute like href="foo.html"
            // If it's a string, and not a link, we'll define a StringLiteral for the expression to evaluate.
            // The code in Element.addToOutputMethod for the start tag case will look for StringLiterals that are next to each other and combine them into
            // one long string so don't expect to always see "href=" + "foo.html" - it will be href="foo.html" in one string.
            if (ModelUtil.isString(propType)) {

               // For relative URLs we have a static method in the Element which will translate it from the directory of the original template
               // to the property directory given the current URL.
               if (link && URLUtil.isRelativeURL(attStr)) {
                  Template enclTempl = getEnclosingTemplate();
                  if (enclTempl != null) {
                     SrcEntry srcEnt = enclTempl.getSrcFile();
                     if (srcEnt != null) {
                        String relDir = srcEnt.getRelDir();
                        SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
                        args.add(relDir == null ? NullLiteral.create() : StringLiteral.create(relDir));
                        args.add(StringLiteral.create(attStr));
                        init = IdentifierExpression.createMethodCall(args, "getRelURL");
                     }
                  }
               }
               if (init == null)
                  init = StringLiteral.create(attStr);
            }
            else if (ModelUtil.isAnInteger(propType)) {
               try {
                  init = IntegerLiteral.create(Integer.parseInt(attStr));
               }
               catch (NumberFormatException exc) {
                  displayError("Property: " + name + " expects an integer value not: " + attValue);
                  init = null;
               }
            }
            else if (ModelUtil.isBoolean(propType)) {
               try {
                  if (!attStr.equals("true") && !attStr.equals("false"))
                     displayError("Illegal value for boolean property: " + attStr + " must be true or false: ");
                  else
                     init = BooleanLiteral.create(attStr.equals("true"));
               }
               catch (NumberFormatException exc) {
                  displayError("Property: " + name + " expects an integer value not: " + attValue);
                  init = null;
               }
            }
            else if (ModelUtil.isANumber(propType)) {
               try {
                  init = FloatLiteral.create(Double.parseDouble(attStr));
               }
               catch (NumberFormatException exc) {
                  displayError("Property: " + name + " expects a floating point value not: " + attValue);
                  init = null;
               }
            }
            else if (ModelUtil.sameTypes(propType, CacheMode.class)) {
               CacheMode cm = CacheMode.fromString(attStr);
               if (cm == null)
                  displayError("Cache attribute must be one of: " + CacheMode.values() + " for: ");
               else if (sys.runtimeProcessor == null || sys.runtimeProcessor.supportsTagCaching()) {
                  init = IdentifierExpression.create("sc.lang.html.CacheMode." + cm.toString());
               }
            }
            else if (htmlAttribute) {
               init = StringLiteral.create(attStr);
            }
            else
               displayWarning("Found string for attribute that needs an expression.  Using: " + name + "=\"= " + attStr + "\" for: ");
         }
         // TODO: this should no longer be needed
         else {
            op = opStr;
            attStr = attStr.substring(opStr.length()).trim();
         }

         if (init == null) {
            SCLanguage sclang = SCLanguage.getSCLanguage();
            attStr = attStr.trim();
            Object exprRes = sclang.parseString(attStr, sclang.variableInitializer);
            if (exprRes instanceof ParseError) {
               displayError("Invalid attribute expression: " + getEnclosingTag().tagName + "." + name + " error: " + exprRes + " in: ");
            }
            else {
               init = (Expression) ParseUtil.nodeToSemanticValue(exprRes);
               // Propagate the location in the file. It's otherwise in the same model/file.
               if (exprRes instanceof IParseNode) {
                  if (parseNode != null && parseNode.getStartIndex() != -1)
                     ((IParseNode) exprRes).advanceStartIndex(parseNode.getStartIndex());
               }
            }
         }
      }
      else if (attValue instanceof Expression) {
         init = (Expression) ((Expression) attValue).deepCopy(ISemanticNode.CopyNormal, null);
         // When the string value is an expression and there's a property use data binding to keep it in sync.
         // TODO: should we use := only when the page is stateful and "=" otherwise?
         if (prop != null)
            op = ":=";
      }
      else if (attValue instanceof AttrExpr) {
         AttrExpr attExpr = (AttrExpr) attValue;
         this.op = attExpr.op;
         this.valueExpr = attExpr.expr;
      }
      if (init != null) {
         init.parentNode = this;
         valueExpr = init;
         this.op = op;
      }
   }

   public Attr deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      Attr res = (Attr) super.deepCopy(options, oldNewMap);
      res.valueProp = valueProp;
      res.valueExpr = valueExpr;
      res.op = op;
      return res;
   }

   public boolean isReverseOnly() {
      return op != null && op.equals("=:");
   }

   public String getNodeWarningText() {
      return unknown ? "Unknown attribute: " + name : null;
   }

   public String toString() {
      if (name == null)
         return "<no name attribute>";
      if (value == null)
         return name;
      else
         return name + " = " + value;
   }

   public void setNodeName(String newName) {
      setProperty("name", newName);
   }

   public String getNodeName() {
      return name;
   }

   public String toListDisplayString() {
      StringBuilder res = new StringBuilder();
      res.append(name);
      if (op != null) {
         res.append(" ");
         res.append(op);

         if (value != null) {
            res.append(" ");
            res.append(value.toString());
         }
      }
      return res.toString();
   }

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation, int max) {
      String attName = name;
      Element enclTag = getEnclosingTag();
      if (enclTag != null) {
         Set<String> attNames = enclTag.getPossibleAttributes();
         if (attNames != null) {
            for (String possName:attNames) {
               if (possName.startsWith(attName)) {
                  candidates.add(possName);
                  if (candidates.size() >= max)
                     return 0;
               }
            }
         }
         // The tag object will not have been defined in most cases so just use the extends type from the tag to suggest members
         Object baseClass = enclTag.getExtendsTypeDeclaration();
         if (baseClass != null) {
            ModelUtil.suggestMembers(getJavaModel(), baseClass, attName, candidates, false, true, false, false, max);
         }
         return 0;
      }
      return -1;
   }

   public String addNodeCompletions(JavaModel origModel, JavaSemanticNode origNode, String extMatchPrefix, int offset, String dummyIdentifier, Set<String> candidates, boolean nextNameInPath, int max) {
      String matchPrefix = null;
      if (name != null) {
         int ix = name.indexOf(dummyIdentifier);
         if (ix != -1 || value == null) {
            if (ix != -1)
               matchPrefix = name.substring(0, ix);
            else
               matchPrefix = extMatchPrefix;
            Element parent = getEnclosingTag();
            String tagName = "html";
            if (parent != null && parent.tagName != null) {
               tagName = parent.tagName;
            }
            Element.addMatchingAttributeNames(tagName, matchPrefix, candidates, max);
         }
         else {
            if (isString()) {
               String valStr = value.toString();
               ix = valStr.indexOf(dummyIdentifier);
               if (ix != -1)
                  matchPrefix = valStr.substring(0, ix);
               else
                  matchPrefix = extMatchPrefix;

               if (name.equals("extends") || name.equals("implements")) {
                  ModelUtil.suggestTypes(origModel, origModel.getPackagePrefix(), matchPrefix, candidates, true, false, max);
               }
               else if (name.equals("tagMerge")) {
                  MergeMode.addMatchingModes(matchPrefix, candidates, max);
               }
            }
            else if (value instanceof Expression) {
               return ((Expression) value).addNodeCompletions(origModel, origNode, extMatchPrefix, offset, dummyIdentifier, candidates, nextNameInPath, max);
            }
            else if (value instanceof AttrExpr) {
               AttrExpr attExpr = ((AttrExpr) value);
               if (attExpr.expr != null)
                 return attExpr.expr.addNodeCompletions(origModel, attExpr.expr, extMatchPrefix, offset, dummyIdentifier, candidates, nextNameInPath, max);
            }
         }
      }
      return matchPrefix;
   }

   /** This handles breakpoints at the tag level.  To find the matching source statement, need to check our attributes and sub-tags */
   public boolean getNodeContainsPart(ISrcStatement partNode) {
      if (partNode == this || sameSrcLocation(partNode))
         return true;
      if (value != null) {
         if (value instanceof AttrExpr) {
            AttrExpr attExpr = (AttrExpr) value;
            if (attExpr.expr != null && (attExpr.expr == partNode || attExpr.expr.getNodeContainsPart(partNode)))
               return true;
         }
         else if (value instanceof Expression) {
            if (value == partNode || ((Expression) value).getNodeContainsPart(partNode))
               return true;
         }
      }
      return false;
   }

   @Override
   public int getNumStatementLines() {
      return 1;
   }


   public void stop() {
      super.stop();
      attInit = false;
      valueExpr = null;
      valueExprClone = null;
      valueProp = null;
      unknown = false;
      declaringTag = null;
   }
}
