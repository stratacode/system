/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.lang.INamedNode;
import sc.lang.ISemanticNode;
import sc.lang.SCLanguage;
import sc.lang.java.*;
import sc.parser.*;

import java.util.IdentityHashMap;

public class Attr extends Node implements INamedNode {
   public String name;
   // Can be either a String, TemplateExpression, or AttrExpr - which holds the op and expression
   public Object value;

   // The expression which should be used to compute the attribute's value
   public transient Expression valueExpr, valueExprClone;
   public transient Object valueProp;
   public transient String op;
   public transient boolean unknown = false;

   // The Element tag which defined this attribute
   public transient Element declaringTag;

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

   private transient boolean attInit = false;

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
      Object prop = tag.definesMember(propName, MemberType.PropertySetSet, tag.tagObject, null, false, false);
      valueProp = prop;

      Object attValue = value;
      Expression init = null;
      String op = "=";
      if (PString.isString(attValue)) {
         String attStr = attValue.toString();
         String opStr = getOperationFromExpression(attStr);

         Object propType = prop == null ? String.class : ModelUtil.getPropertyType(prop);

         if (opStr == null) {
            // TODO: Not sure about the rules here...
            if (ModelUtil.isString(propType))
               init = StringLiteral.create(attStr);
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
            else if (tag.isHtmlAttribute(name)) {
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
         return "<no name attribtue>";
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
}
