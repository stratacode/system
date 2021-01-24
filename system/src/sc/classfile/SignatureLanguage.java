/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.classfile;

import sc.lang.java.JavaType;
import sc.layer.Layer;
import sc.parser.*;

public class SignatureLanguage extends Language implements IParserConstants {
   Symbol colon = new Symbol(":");
   Symbol lessThan = new Symbol("<");
   Symbol greaterThan = new Symbol(">");

   SymbolChoice typeName = new SymbolChoice(REPEAT | NOT, ";", "+", "-", "<", ">", ":", "*", "[", EOF);
   SymbolChoice identifier = new SymbolChoice(REPEAT | NOT, ";", "+", "-", "<", ">", ":", "*", "[", "/", ".", EOF);
   public OrderedChoice type = new OrderedChoice(); // forward ref
   Symbol signatureDims = new Symbol(OPTIONAL | REPEAT, "[");
   Sequence primitiveType = new Sequence("PrimitiveType(signatureDims, signatureCode)", signatureDims,
                                         new SymbolChoice("B", "C", "D", "F", "I", "J", "S", "Z","V"));
   OrderedChoice extendsType = new OrderedChoice(new Sequence("ExtendsType(signatureCode)", new Symbol("*")),
                                                 new Sequence("ExtendsType(signatureCode, typeArgument)", new SymbolChoice("+","-"), type));
   Sequence typeList = new Sequence("([])", OPTIONAL | REPEAT, type);
   Sequence optType = new Sequence("(.)", OPTIONAL, type);
   Sequence colonTypeList = new Sequence("(,[])", OPTIONAL | REPEAT, colon, type);
   Sequence boundType = new Sequence("BoundType(baseType,boundTypes)", OPTIONAL, optType, colonTypeList);
   Sequence formalTypeParameter = new Sequence("TypeParameter(name,,extendsType)", typeName, colon, boundType);
   Sequence formalTypeParameters = new Sequence("(,[],)", OPTIONAL, lessThan, new Sequence("([])", REPEAT, formalTypeParameter), greaterThan);
   public Sequence methodSig = new Sequence("CFMethodSignature(typeParameters,,parameterTypes,,returnType,throwsTypes,)", formalTypeParameters,
                                            new Symbol("("), typeList, new Symbol(")"), type, new Sequence("(,[])", OPTIONAL | REPEAT, new Symbol("^"), type), new Symbol(EOF));
   Sequence typeArguments = new Sequence("(,[],)", OPTIONAL | REPEAT, lessThan, typeList, greaterThan);
   Sequence namedType = new Sequence("ClassType(signatureDims, signatureCode, typeName, typeArguments, chainedTypes,)",
           signatureDims, new SymbolChoice("L", "T"), identifier, typeArguments, new Sequence("ClassType(, typeName, typeArguments)", OPTIONAL | REPEAT, new SymbolChoice(".", "/"), identifier, typeArguments), new Symbol(";"));
   { type.set(primitiveType, namedType, extendsType); }

   public Sequence classSig = new Sequence("CFClassSignature(typeParameters, extendsType, implementsTypes)",
                                            formalTypeParameters, type, typeList);

   public static SignatureLanguage INSTANCE = new SignatureLanguage();

   public static SignatureLanguage getSignatureLanguage() {
      return INSTANCE;
   }

   public SignatureLanguage() {
      this(null);
   }

   public SignatureLanguage(Layer layer) {
      super(layer);
      setSemanticValueClassPath("sc.lang.java:sc.classfile");
      setStartParselet(methodSig);
      classSig.setLanguage(this);
   }

   public CFMethodSignature parseMethodSignature(String sig) {
      ParseUtil.initAndStartComponent(methodSig);

      Object res = parseString(sig, methodSig);
      if (res instanceof ParseError)
         throw new IllegalArgumentException(res.toString());
      return (CFMethodSignature) ParseUtil.nodeToSemanticValue(res);
   }

   public CFClassSignature parseClassSignature(String sig) {
      ParseUtil.initAndStartComponent(classSig);

      Object res = parseString(sig, classSig);
      if (res instanceof ParseError)
         throw new IllegalArgumentException(res.toString());
      return (CFClassSignature) ParseUtil.nodeToSemanticValue(res);
   }

   public JavaType parseType(String sig) {
      ParseUtil.initAndStartComponent(methodSig);

      Object res = parseString(sig, type);
      if (res instanceof ParseError)
         throw new IllegalArgumentException(res.toString());
      return (JavaType) ParseUtil.nodeToSemanticValue(res);
   }
}
