/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.classfile;

import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.java.JavaType;
import sc.lang.java.TypeParameter;

public class CFMethodSignature extends SemanticNode {
   public SemanticNodeList<TypeParameter> typeParameters;
   public SemanticNodeList<JavaType> parameterTypes;
   public JavaType returnType;
}
