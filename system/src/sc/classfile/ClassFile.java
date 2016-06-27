/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.classfile;

import sc.lang.java.*;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.type.RTypeUtil;
import sc.util.*;

import java.io.*;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

public class ClassFile {
   DataInputStream input;
   IMessageHandler msg;

   private static IntCoalescedHashMap modifierNamesToFlags = new IntCoalescedHashMap(14);
   {
      modifierNamesToFlags.put("public", Modifier.PUBLIC);
      modifierNamesToFlags.put("private", Modifier.PRIVATE);
      modifierNamesToFlags.put("protected", Modifier.PROTECTED);
      modifierNamesToFlags.put("static", Modifier.STATIC);
      modifierNamesToFlags.put("final", Modifier.FINAL);
      modifierNamesToFlags.put("volatile", Modifier.VOLATILE);
      modifierNamesToFlags.put("transient", Modifier.TRANSIENT);
      modifierNamesToFlags.put("synchronized", Modifier.SYNCHRONIZED);
      modifierNamesToFlags.put("abstract", Modifier.ABSTRACT);
      modifierNamesToFlags.put("strict", Modifier.STRICT);
      modifierNamesToFlags.put("native", Modifier.NATIVE);
   }

   int minorVersion;
   int majorVersion;
   ConstantPoolEntry[] constantPool;
   int accessFlags;
   ClassConstant thisClass;
   ClassConstant superClass;

   ClassConstant[] interfaces;

   CFField[] fields;

   CFMethod[] methods;

   CFClass cfClass;

   Attribute[] attributes;
   CoalescedHashMap<String,Attribute> attributesByName;

   public ClassFile(InputStream is) {
      input = new DataInputStream(new BufferedInputStream(is));
   }

   public ClassFile(InputStream is, LayeredSystem sys) {
      this(is);
      cfClass = new CFClass(this, sys);
      msg = sys.messageHandler;
   }

   public ClassFile(InputStream is, Layer layer) {
      this(is);
      cfClass = new CFClass(this, layer);
      msg = layer != null ? layer.layeredSystem.messageHandler : null;
   }

   private int cpCount = 0;

   public void initialize() {
      try {
         int magic = input.readInt();
         if (magic != 0xCAFEBABE)
            throw new IllegalArgumentException("Invalid class file: magic: " + magic);
         minorVersion = input.readUnsignedShort();
         majorVersion = input.readUnsignedShort();
         int numConstants = input.readUnsignedShort();
         constantPool = new ConstantPoolEntry[numConstants];
         // Note: constant slot 0 is not used
         cpCount = 1;
         for (; cpCount < numConstants; cpCount++) {
            int next = cpCount;
            constantPool[next] = readConstant();
         }
         accessFlags = input.readUnsignedShort();

         int index = input.readUnsignedShort();
         thisClass = (ClassConstant) constantPool[index];
         index = input.readUnsignedShort();
         if (index != 0)
            superClass = (ClassConstant) constantPool[index];

         int numInterfaces = input.readUnsignedShort();
         interfaces = new ClassConstant[numInterfaces];
         for (int i = 0; i < numInterfaces; i++)
            interfaces[i] = (ClassConstant) constantPool[input.readUnsignedShort()];

         int numFields = input.readUnsignedShort();
         fields = new CFField[numFields];
         for (int i = 0; i < numFields; i++) {
            CFField field = (CFField) readFieldMethodInfo(true);
            fields[i] = field;
            field.ownerClass = cfClass;
         }

         int numMethods = input.readUnsignedShort();
         methods = new CFMethod[numMethods];
         for (int i = 0; i < numMethods; i++) {
            CFMethod method = (CFMethod) readFieldMethodInfo(false);
            methods[i] = method;
            method.ownerClass = cfClass;
         }

         int numAttributes = input.readUnsignedShort();
         attributes = new Attribute[numAttributes];
         attributesByName = new CoalescedHashMap<String,Attribute>(numAttributes);
         for (int i = 0; i < numAttributes; i++) {
            Attribute att;
            attributes[i] = att = readAttribute();
            String name = att.getName();
            if (name != null) {
               attributesByName.put(name, att);
            }
         }
         if (input.available() != 0)
            System.err.println("*** Failed to read all data in class file");

      }
      catch (IOException exc) {
         throw new IllegalArgumentException("Unable to read class file");
      }
      finally {
         close();
      }
   }

   public void close() {
      if (input == null)
         return;

      try {
         input.close();
      }
      catch (IOException exc) {}
      input = null;
   }

   public CFClass getCFClass() {
      return cfClass;
   }

   public static int modifierNameToAccessFlag(String modifierName) {
      return modifierNamesToFlags.get(modifierName);
   }

   /** Base type for fields and methods defined from a class */
   public static class FieldMethodInfo extends JavaSemanticNode implements IDefinition {
      int accessFlags;
      String name;
      String desc;
      CoalescedHashMap<String,Attribute> attributes;

      CFClass ownerClass;

      public String getDescription() {
         ClassFile.SignatureAttribute sigAtt = (ClassFile.SignatureAttribute) attributes.get("Signature");
         String useDesc;
         if (sigAtt != null)
            useDesc = sigAtt.signature;
         else
            useDesc = desc;
         return useDesc;
      }

      public Object getAnnotation(String annotName) {
         AnnotationsAttribute aa = AnnotationsAttribute.getAttribute(attributes);
         if (aa == null)
            return null;
         return aa.getAnnotation(annotName);
      }

      public boolean hasModifier(String modifierName) {
         if (modifierName.equals("default")) {
            // Default methods are identified in the class file as non-abstract, non-static instance methods
            if (ownerClass.isInterface() && !hasModifier("abstract") && !hasModifier("static") && hasModifier("public"))
               return true;
            return false;
         }
         return (accessFlags & modifierNamesToFlags.get(modifierName)) != 0;
      }

      public AccessLevel getAccessLevel(boolean explicitOnly) {
         return ClassFile.flagsToAccessLevel(accessFlags);
      }

      public ITypeDeclaration getEnclosingIType() {
         return ownerClass;
      }
      // Includes a trailing " " to make it easier to insert without adding an extra space
      public String modifiersToString(boolean includeAnnotations, boolean includeAccess, boolean includeFinal, boolean includeScopes, boolean abs, MemberType filterType) {
         // TODO: includeAnnotations - need to implement this
         int flags = accessFlags;
         if (!includeFinal)
            flags &= ~(Modifier.FINAL);
         return includeAccess ? Modifier.toString(flags) : "";
      }
   }

   public static AccessLevel flagsToAccessLevel(int flags) {
      if ((flags & Modifier.PUBLIC) != 0)
         return AccessLevel.Public;
      if ((flags & Modifier.PRIVATE) != 0)
         return AccessLevel.Private;
      if ((flags & Modifier.PROTECTED) != 0)
         return AccessLevel.Protected;
      return null;
   }

   public CFAnnotation getAnnotation(String annotName) {
      AnnotationsAttribute aa = AnnotationsAttribute.getAttribute(attributesByName);
      if (aa == null)
         return null;
      return aa.getAnnotation(annotName);
   }

   public FieldMethodInfo readFieldMethodInfo(boolean fld) throws IOException {
      FieldMethodInfo fi = fld ? new CFField() : new CFMethod();
      fi.accessFlags = input.readUnsignedShort();
      fi.name = readConstantRefString();
      fi.desc = readConstantRefString();
      int numAttributes = input.readUnsignedShort();
      fi.attributes = new CoalescedHashMap<String,Attribute>(numAttributes);
      for (int i = 0; i < numAttributes; i++) {
         Attribute att = readAttribute();
         if (att.getName() != null)
            fi.attributes.put(att.getName(), att);
      }
      return fi;
   }

   String getCFName(int nameIndex) {
      return ((Utf8Constant) constantPool[nameIndex]).value;
   }

   // Fastest way to get the name
   public String getCFClassName() {
      return getCFName(thisClass.nameIndex);
   }

   private String getTypeNameFromIndex(int ix) {
      String cfName = getCFName(ix);
      int lastSlashIx = cfName.lastIndexOf('/');
      if (lastSlashIx == -1)
         lastSlashIx = 0;
      // We only replace the $ chars that are in the last component of the file name.  There
      // are some Java packages which use $ in the file name/package name - presumably generated Java files.
      cfName = replaceStartingAt(cfName, lastSlashIx, '$', '.');
      return cfName.replace('/', '.');
   }

   private String replaceStartingAt(String in, int startIx, char from, char to) {
      int len = in.length();
      StringBuilder res = null;
      int lastStartIx = -1;
      for (int i = startIx; i < len; i++) {
         if (in.charAt(i) == from) {
            if (res == null) {
               res = new StringBuilder();
               res.append(in.substring(0, i));
            }
            else {
               res.append(in.substring(lastStartIx, i));
            }
            res.append('.');
            lastStartIx = i + 1;
         }
      }
      if (res == null)
         return in;
      else
         res.append(in.substring(lastStartIx, len));
      return res.toString();
   }

   public String getTypeName() {
      return getTypeNameFromIndex(thisClass.nameIndex);
   }

   public String getExtendsTypeName() {
      return superClass == null ? null : getTypeNameFromIndex(superClass.nameIndex);
   }

   public int getNumInterfaces() {
      return interfaces == null ? 0 : interfaces.length;
   }

   public String getInterfaceName(int ix) {
      return getTypeNameFromIndex(interfaces[ix].nameIndex);
   }

   public String getInterfaceCFName(int ix) {
      return getCFName(interfaces[ix].nameIndex);
   }

   private final int Class = 7;
   private final int FieldRef = 9;
   private final int MethodRef = 10;
   private final int InterfaceMethodRef = 11;
   private final int String = 8;
   private final int Integer = 3;
   private final int Float = 4;
   private final int Long = 5;
   private final int Double = 6;
   private final int NameAndType = 12;

   private final int InvokeDynamic = 18;
   private final int MethodHandle=15;
   private final int MethodType =16;

   private final int Utf8 = 1;

   public static class ConstantPoolEntry implements IValueNode {
      short type;

      public Object getValue() {
         return this;
      }

      @Override
      public Object eval(Class expectedType, ExecutionContext ctx) {
         return getValue();
      }
   }

   public static class ClassConstant extends ConstantPoolEntry {
      int nameIndex;
   }

   /** Base type for a reference to a method, field, interfacemethod */
   public class RefInfo extends ConstantPoolEntry {
      int classIndex;
      int nameAndTypeIndex;
   }

   public class FieldRefInfo extends RefInfo {
   }
   public class MethodRefInfo extends RefInfo {
   }
   public class InterfaceMethodRefInfo extends RefInfo {
   }

   public class StringConstant extends ConstantPoolEntry {
      int stringIndex;
   }

   public class IntegerConstant extends ConstantPoolEntry {
      int value;

      public Object getValue() {
         return value;
      }
   }

   public class FloatConstant extends ConstantPoolEntry {
      float value;

      public Object getValue() {
         return value;
      }
   }

   public class DoubleConstant extends ConstantPoolEntry {
      double value;

      public Object getValue() {
         return value;
      }
   }

   public class LongConstant extends ConstantPoolEntry {
      long value;

      public Object getValue() {
         return value;
      }
   }

   public class NameAndTypeConstant extends ConstantPoolEntry {
      int nameIndex;
      int descIndex;
   }

   public class Utf8Constant extends ConstantPoolEntry {
      String value;

      public Object getValue() {
         return value;
      }

      public String toString() {
         return value;
      }
   }

  public class InvokeDynamicConstant extends ConstantPoolEntry {
     int methodIndex;
     int nameAndTypeIndex;
  }

  public class MethodHandleInfo extends ConstantPoolEntry {
     int kind;
     int index;
  }

  public class MethodTypeInfo extends ConstantPoolEntry {
     int index;
  }

   byte[] buffer = new byte[1024];

   public ConstantPoolEntry readConstant() throws IOException {
      int tag = input.readUnsignedByte();
      switch (tag) {
         case Class:
            ClassConstant ent = new ClassConstant();
            ent.type = (short)tag;
            ent.nameIndex = input.readUnsignedShort();
            return ent;
         case MethodRef:
            MethodRefInfo mref = new MethodRefInfo();
            mref.type = (short)tag;
            mref.classIndex = input.readUnsignedShort();
            mref.nameAndTypeIndex = input.readUnsignedShort();
            return mref;
         case InterfaceMethodRef:
            InterfaceMethodRefInfo imref = new InterfaceMethodRefInfo();
            imref.type = (short)tag;
            imref.classIndex = input.readUnsignedShort();
            imref.nameAndTypeIndex = input.readUnsignedShort();
            return imref;
         case FieldRef:
            FieldRefInfo ref = new FieldRefInfo();
            ref.type = (short)tag;
            ref.classIndex = input.readUnsignedShort();
            ref.nameAndTypeIndex = input.readUnsignedShort();
            return ref;
         case String:
            StringConstant str = new StringConstant();
            str.type = (short)tag;
            str.stringIndex = input.readUnsignedShort();
            return str;
         case Integer:
            IntegerConstant integer = new IntegerConstant();
            integer.type = (short)tag;
            integer.value = input.readInt();
            return integer;
         case Float:
            FloatConstant fv = new FloatConstant();
            fv.type = (short)tag;
            fv.value = input.readFloat();
            return fv;
         case Double:
            DoubleConstant dv = new DoubleConstant();
            dv.type = (short)tag;
            dv.value = input.readDouble();
            cpCount++;
            return dv;
         case Long:
            LongConstant lv = new LongConstant();
            lv.type = (short)tag;
            lv.value = input.readLong();
            cpCount++;
            return lv;
         case NameAndType:
            NameAndTypeConstant nt = new NameAndTypeConstant();
            nt.type = (short)tag;
            nt.nameIndex = input.readUnsignedShort();
            nt.descIndex = input.readUnsignedShort();
            return nt;
         case Utf8:
            Utf8Constant ut = new Utf8Constant();
            ut.type = (short) tag;
            int len = input.readUnsignedShort();
            if (len > buffer.length)
                buffer = new byte[len];
            if (input.read(buffer, 0, len) != len)
                throw new IllegalArgumentException("Class file corrupted: EOF in mid-string");
            ut.value = new String(buffer, 0, len);
            return ut;
         case 0:
            System.out.println("*** Unrecognized value in class file");
            return null;
         case InvokeDynamic:
            // implement invoke dynamic (java/util/Comparator.class)
            InvokeDynamicConstant id = new InvokeDynamicConstant();
            id.type = (short)tag;
            id.methodIndex = input.readUnsignedShort();
            id.nameAndTypeIndex = input.readUnsignedShort();
            return id;
         case MethodHandle:
            MethodHandleInfo mh = new MethodHandleInfo();
            mh.type = (short)tag;
            mh.kind = input.readUnsignedByte();
            mh.index = input.readUnsignedShort();
            return mh;
         case MethodType:
            MethodTypeInfo mt = new MethodTypeInfo();
            mt.type = (short)tag;
            mt.index = input.readUnsignedShort();
            return mt;

         default:
            if (msg != null) {
               MessageHandler.error(msg, "Invalid constant type reading Java .class file - " + tag);
            }
            throw new IllegalArgumentException("Invalid class constant type: " + tag);
      }
   }

   public static interface Attribute {
      public void setName(String name);
      public String getName();
      public void readRest(ClassFile file, DataInputStream input) throws IOException ;
   }

   static HashMap<String,Class<? extends Attribute>> attributeClasses = new HashMap<String,Class<? extends Attribute>>();

   static {
      attributeClasses.put("InnerClasses", InnerClasses.class);
      attributeClasses.put("RuntimeVisibleAnnotations", AnnotationsAttribute.class);
      attributeClasses.put("Signature", SignatureAttribute.class);
      attributeClasses.put("Exceptions", ExceptionsAttribute.class);
      attributeClasses.put("Code", CodeAttribute.class);
   }

   public static class SignatureAttribute implements Attribute {
      String signature;

      public void setName(String name) {
      }

      public String getName() {
         return "Signature";
      }

      public void readRest(ClassFile file, DataInputStream input) throws IOException {
         int len = input.readInt();
         signature = file.readConstantRefString();
      }
   }

   public static class ExceptionsAttribute implements Attribute {
      String[] typeNames;

      public void setName(String name) {
      }

      public String getName() {
         return "Exceptions";
      }

      public void readRest(ClassFile file, DataInputStream input) throws IOException {
         int len = input.readInt();
         int num = input.readShort();
         typeNames = new String[num];
         for (int i = 0; i < num; i++) {
            ClassConstant exc = (ClassConstant) file.constantPool[input.readUnsignedShort()];
            typeNames[i] = file.getTypeNameFromIndex(exc.nameIndex);
         }
      }
   }

   public static class GenericAttribute implements Attribute {
      String name;
      byte[] attributeData;

      public void setName(String name) {
         this.name = name;
      }

      public String getName() {
         return name;
      }

      public void readRest(ClassFile file, DataInputStream input) throws IOException {
         int len = input.readInt();
         attributeData = new byte[len];
         if (input.read(attributeData, 0, len) != len)
            throw new IllegalArgumentException("EOF reading attribute data");
      }
   }

   /** The code attributes - stored on methods for holding the byte code */
   public static class CodeAttribute implements Attribute {
      public void setName(String name) {}
      public String getName() {
         return "Code";
      }

      public void readRest(ClassFile file, DataInputStream input) throws IOException {
         int len = input.readInt();
         // TODO: Add a flag or an option where we preserve this info?
         if (input.skipBytes(len) != len)
            throw new IllegalArgumentException("EOF reading code attribute");
      }
   }

   public int getNumInnerClasses() {
      return theInnerClasses == null ? 0 : theInnerClasses.innerClasses.size();
   }

   public String getOuterClassName() {
      if (theInnerClasses == null)
         return null;
      return theInnerClasses.outerName;
   }

   public String getInnerClassName(int ix) {
      if (theInnerClasses == null)
         return null;
      return theInnerClasses.innerClasses.get(ix).name;
   }

   public boolean hasInnerClass(String name) {
      int numInnerClasses = getNumInnerClasses();
      for (int i = 0; i < numInnerClasses; i++)
         if (getInnerClassName(i).equals(name))
            return true;
      return false;
   }

   InnerClasses theInnerClasses;

   public static class CFInnerClassInfo {
      String name;
      int accessFlags;
      CFInnerClassInfo(String name, int accessFlags) {
         this.name = name;
         this.accessFlags = accessFlags;
      }
   }

   public static class InnerClasses implements Attribute {

      String outerName;
      ArrayList<CFInnerClassInfo> innerClasses;

      public void setName(String name) {}

      public String getName() {
         return null; // Stored in a member variable, not in the table
      }

      public int getAccessFlags(String className) {
         if (innerClasses == null)
            return -1;
         for (CFInnerClassInfo innerInfo:innerClasses)
            if (innerInfo.name.equals(className))
               return innerInfo.accessFlags;
         return -1;
      }

      public void readRest(ClassFile file, DataInputStream input) throws IOException {

         int attLen = input.readInt(); // not used here since we read it piece by piece

         int numClasses = input.readUnsignedShort();

         for (int i = 0; i < numClasses; i++) {
            int classInfoIndex = input.readUnsignedShort();
            // Name of the class like ParentClass$1 or ParentClass$ChildClass.  We don't yet record anonymous
            // inner classes since they do not show up in the interface of the class.  This info is redundant
            ClassConstant cc = classInfoIndex == 0 ? null : (ClassConstant) file.constantPool[classInfoIndex];

            // Name of the outer class
            int outerClassIndex = input.readUnsignedShort();
            ClassConstant pc = outerClassIndex == 0 ? null : (ClassConstant) file.constantPool[outerClassIndex];
            // For a simple named inner class, this is just the parent's class name.
            //outerName = pc == null ? null : file.getTypeNameFromIndex(pc.nameIndex);

            // Name of inner class
            int innerNameIndex = input.readUnsignedShort();
            String innerName = innerNameIndex == 0 ? null : ((Utf8Constant) file.constantPool[innerNameIndex]).value;

            int innerAccessFlags = input.readUnsignedShort();
            if (innerName != null) {
               if (innerClasses == null) {
                  file.theInnerClasses = this;
                  innerClasses = new ArrayList<CFInnerClassInfo>();
               }
               innerClasses.add(new CFInnerClassInfo(innerName, innerAccessFlags));
            }

         }
      }
   }

   String readConstantRefString() throws IOException {
      int cpIndex = input.readUnsignedShort();
      if (cpIndex >= constantPool.length)
         System.out.println("Constant value out of range in class file");
      Utf8Constant cf = ((Utf8Constant) constantPool[cpIndex]);
      if (cf == null)
         return null;
      return cf.value;
   }

   ConstantPoolEntry readConstantRef() throws IOException {
      return constantPool[input.readUnsignedShort()];
   }


   public static class EnumValue {
      public String typeName;
      public String valueName;
      public EnumValue(String typeSig, String valName) {
         // Is there a chance this is another type signature?  If so, do we need to parse this and turn it into a type name?
         if (typeSig.startsWith("L")) {
            this.typeName = typeSig.substring(1, typeSig.length()-1).replace('/', '.');
         }
         else {
            this.typeName = typeSig;
         }
         this.valueName = valName;
      }
   }

   public static class AnnotationsAttribute implements Attribute {
      public void setName(String name) {}

      private static final String NAME = "RuntimeVisibleAnnotations";

      public String getName() {
         return NAME;
      }

      public static AnnotationsAttribute getAttribute(CoalescedHashMap<String,Attribute> attributes) {
         return (AnnotationsAttribute) attributes.get(NAME);
      }

      LinkedHashMap<String, CFAnnotation> annotations;

      Object readElementValue(ClassFile file) throws IOException {
         char valueCode = (char) file.input.readUnsignedByte();
         switch (valueCode) {
            case 'B':
            case 'C':
            case 'D':
            case 'F':
            case 'I':
            case 'J':
            case 'S':
            case 'Z':
            case 's': // Constants - storing the constant, not the string
               return file.readConstantRef();
            case 'e': // Enum name and value
               return new EnumValue(file.readConstantRefString(), file.readConstantRefString());
            case 'c': // Store the class name here
               return file.readConstantRefString();
            case '@':
               return readAnnotation(file);
            case '[':
               return readElementValueArray(file);
            default:
               throw new UnsupportedOperationException();
         }
      }

      Object[] readElementValueArray(ClassFile file) throws IOException {
         int numValues = file.input.readUnsignedShort();
         Object[] vals = new Object[numValues];
         for (int i = 0; i < numValues; i++) {
            vals[i] = readElementValue(file);
         }
         return vals;
      }

      LinkedHashMap<String,Object> readElementValuePairs(ClassFile file) throws IOException {
         int numValues = file.input.readUnsignedShort();
         LinkedHashMap<String,Object> elementValues = null;
         if (numValues > 0)
            elementValues = new LinkedHashMap<String,Object>();
         for (int j = 0; j < numValues; j++) {
            String elemName = file.readConstantRefString();
            elementValues.put(elemName, readElementValue(file));
         }
         return elementValues;
      }

      public CFAnnotation readAnnotation(ClassFile file) throws IOException {
         CFAnnotation annot = new CFAnnotation();
         annot.classFile = file;
         annot.setCFTypeName(file.readConstantRefString());
         annot.elementValues = readElementValuePairs(file);
         return annot;
      }

      public void readRest(ClassFile file, DataInputStream input) throws IOException {
         int len = input.readInt();
         int numAnnotations = input.readUnsignedShort();
         if (numAnnotations > 0)
            annotations = new LinkedHashMap<String, CFAnnotation>();
         for (int i = 0; i < numAnnotations; i++) {
            CFAnnotation annot = readAnnotation(file);
            annotations.put(annot.getTypeName(), annot);
         }
      }

      public CFAnnotation getAnnotation(String name) {
         if (annotations == null)
            return null;
         return annotations.get(name);
      }
   }

   public Attribute readAttribute() throws IOException {
      String name = ((Utf8Constant) constantPool[input.readUnsignedShort()]).value;
      Class attClass = attributeClasses.get(name);
      if (attClass == null)
         attClass = GenericAttribute.class;
      Attribute att = (Attribute) RTypeUtil.createInstance(attClass);
      att.readRest(this, input);
      return att;
   }

   public static void main(String[] args) {
      if (args.length == 0)
         throw new IllegalArgumentException("Missing file arg");

      try {
         ClassFile file = new ClassFile(new FileInputStream(new File(args[0])));
         file.initialize();
      }
      catch (IOException exc) {
         System.out.println("**** Can't open file: " + exc);
      }
   }
}
