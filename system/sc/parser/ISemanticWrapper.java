/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

/**
 * Implemented by nodes in the semantic tree which are not created explictly by the grammar.  For example, the
 * grammar generates BinaryExpression but we might programmatically create ConditionalExpression.  During generation
 * we need to know what specific class to use to locate the parselet which we use to process the value.  This method
 * lets us do that.  The returned class will always be a subclass of the implementing class and must expose the properties
 * required by the definition for the rule to generate properly.
 */
public interface ISemanticWrapper {
   Class getWrappedClass();
}
