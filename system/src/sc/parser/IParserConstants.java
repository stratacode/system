/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

/**
 * Constants used for building parsing grammars.
 */
public interface IParserConstants {
   /** Args to the options parameter to parselet constructors */
   public static int REPEAT = 1;
   public static int OPTIONAL = 2;
   public static int LOOKAHEAD = 4;
   public static int NOT = 8;
   public static int SKIP = 16;
   public static int DISCARD = 32;
   public static int NOERROR = 64;
   public static int TRACE = 128;
   public static int SKIP_ON_ERROR = 256;
   public static int PARTIAL_VALUES_ONLY = 512;

   /** Arg to the Symbol constructor for representing EOF */
   public static String EOF = null;
}

