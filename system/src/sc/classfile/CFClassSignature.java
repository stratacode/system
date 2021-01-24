/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.classfile;

import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.java.JavaType;
import sc.lang.java.TypeParameter;

public class CFClassSignature extends SemanticNode {
   public SemanticNodeList<TypeParameter> typeParameters;
   public JavaType extendsType;
   public SemanticNodeList<JavaType> implementsTypes;
}
