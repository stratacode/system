/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package sc.util.zip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipException;

/**
 * ZipExtraField related methods
 * @NotThreadSafe because the HashMap is not synch.
 */
// CheckStyle:HideUtilityClassConstructorCheck OFF (bc)
public class ExtraFieldUtils {

    private static final int WORD = 4;

    /**
     * Static registry of known extra fields.
     */
    private static final Map<ZipShort, Class<?>> implementations;

    static {
        implementations = new ConcurrentHashMap<ZipShort, Class<?>>();
        register(AsiExtraField.class);
        register(X5455_ExtendedTimestamp.class);
        register(X7875_NewUnix.class);
        register(JarMarker.class);
        register(UnicodePathExtraField.class);
        register(UnicodeCommentExtraField.class);
        register(Zip64ExtendedInformationExtraField.class);
    }

    /**
     * Register a ZipExtraField implementation.
     *
     * <p>The given class must have a no-arg constructor and implement
     * the {@link ZipExtraField ZipExtraField interface}.</p>
     * @param c the class to register
     */
    public static void register(Class<?> c) {
        try {
            ZipExtraField ze = (ZipExtraField) c.newInstance();
            implementations.put(ze.getHeaderId(), c);
        } catch (ClassCastException cc) {
            throw new RuntimeException(c + " doesn\'t implement ZipExtraField");
        } catch (InstantiationException ie) {
            throw new RuntimeException(c + " is not a concrete class");
        } catch (IllegalAccessException ie) {
            throw new RuntimeException(c + "\'s no-arg constructor is not public");
        }
    }

    /**
     * Create an instance of the appropriate ExtraField, falls back to
     * {@link UnrecognizedExtraField UnrecognizedExtraField}.
     * @param headerId the header identifier
     * @return an instance of the appropriate ExtraField
     * @exception InstantiationException if unable to instantiate the class
     * @exception IllegalAccessException if not allowed to instantiate the class
     */
    public static ZipExtraField createExtraField(ZipShort headerId)
        throws InstantiationException, IllegalAccessException {
        Class<?> c = implementations.get(headerId);
        if (c != null) {
            return (ZipExtraField) c.newInstance();
        }
        UnrecognizedExtraField u = new UnrecognizedExtraField();
        u.setHeaderId(headerId);
        return u;
    }

    /**
     * Split the array into ExtraFields and populate them with the
     * given data as local file data, throwing an exception if the
     * data cannot be parsed.
     * @param data an array of bytes as it appears in local file data
     * @return an array of ExtraFields
     * @throws ZipException on error
     */
    public static ZipExtraField[] parse(byte[] data) throws ZipException {
        return parse(data, true, UnparseableExtraField.THROW);
    }

    /**
     * Split the array into ExtraFields and populate them with the
     * given data, throwing an exception if the data cannot be parsed.
     * @param data an array of bytes
     * @param local whether data originates from the local file data
     * or the central directory
     * @return an array of ExtraFields
     * @throws ZipException on error
     */
    public static ZipExtraField[] parse(byte[] data, boolean local)
        throws ZipException {
        return parse(data, local, UnparseableExtraField.THROW);
    }

    /**
     * Split the array into ExtraFields and populate them with the
     * given data.
     * @param data an array of bytes
     * @param local whether data originates from the local file data
     * or the central directory
     * @param onUnparseableData what to do if the extra field data
     * cannot be parsed.
     * @return an array of ExtraFields
     * @throws ZipException on error
     *
     * @since 1.1
     */
    public static ZipExtraField[] parse(byte[] data, boolean local,
                                        UnparseableExtraField onUnparseableData)
        throws ZipException {
        List<ZipExtraField> v = new ArrayList<ZipExtraField>();
        int start = 0;
        LOOP:
        while (start <= data.length - WORD) {
            ZipShort headerId = new ZipShort(data, start);
            int length = new ZipShort(data, start + 2).getValue();
            if (start + WORD + length > data.length) {
                switch(onUnparseableData.getKey()) {
                case UnparseableExtraField.THROW_KEY:
                    throw new ZipException("bad extra field starting at "
                                           + start + ".  Block length of "
                                           + length + " bytes exceeds remaining"
                                           + " data of "
                                           + (data.length - start - WORD)
                                           + " bytes.");
                case UnparseableExtraField.READ_KEY:
                    UnparseableExtraFieldData field =
                        new UnparseableExtraFieldData();
                    if (local) {
                        field.parseFromLocalFileData(data, start,
                                                     data.length - start);
                    } else {
                        field.parseFromCentralDirectoryData(data, start,
                                                            data.length - start);
                    }
                    v.add(field);
                    //$FALL-THROUGH$
                case UnparseableExtraField.SKIP_KEY:
                    // since we cannot parse the data we must assume
                    // the extra field consumes the whole rest of the
                    // available data
                    break LOOP;
                default:
                    throw new ZipException("unknown UnparseableExtraField key: "
                                           + onUnparseableData.getKey());
                }
            }
            try {
                ZipExtraField ze = createExtraField(headerId);
                if (local) {
                    ze.parseFromLocalFileData(data, start + WORD, length);
                } else {
                    ze.parseFromCentralDirectoryData(data, start + WORD,
                                                     length);
                }
                v.add(ze);
            } catch (InstantiationException ie) {
                throw (ZipException) new ZipException(ie.getMessage()).initCause(ie);
            } catch (IllegalAccessException iae) {
                throw (ZipException) new ZipException(iae.getMessage()).initCause(iae);
            }
            start += length + WORD;
        }

        ZipExtraField[] result = new ZipExtraField[v.size()];
        return v.toArray(result);
    }

    public static int getUnixFileMode(byte[] extra) {
       try {
          ZipExtraField[] extraFields = ExtraFieldUtils.parse(extra, true, ExtraFieldUtils.UnparseableExtraField.SKIP);
          if (extraFields != null) {
             for (ZipExtraField field:extraFields) {
                if (field instanceof AsiExtraField)
                   return ((AsiExtraField) field).getMode();
             }
          }
          return 0;
       }
       catch (ZipException exc) {
          System.err.println("*** error retrieving unix field mode for zip entry: " + exc);
          exc.printStackTrace();
          return 0;
       }
    }

    /**
     * "enum" for the possible actions to take if the extra field
     * cannot be parsed.
     *
     * @since 1.1
     */
    public static final class UnparseableExtraField {
        /**
         * Key for "throw an exception" action.
         */
        public static final int THROW_KEY = 0;
        /**
         * Key for "skip" action.
         */
        public static final int SKIP_KEY = 1;
        /**
         * Key for "read" action.
         */
        public static final int READ_KEY = 2;

        /**
         * Throw an exception if field cannot be parsed.
         */
        public static final UnparseableExtraField THROW
            = new UnparseableExtraField(THROW_KEY);

        /**
         * Skip the extra field entirely and don't make its data
         * available - effectively removing the extra field data.
         */
        public static final UnparseableExtraField SKIP
            = new UnparseableExtraField(SKIP_KEY);

        /**
         * Read the extra field data into an instance of {@link
         * UnparseableExtraFieldData UnparseableExtraFieldData}.
         */
        public static final UnparseableExtraField READ
            = new UnparseableExtraField(READ_KEY);

        private final int key;

        private UnparseableExtraField(int k) {
            key = k;
        }

        /**
         * Key of the action to take.
         */
        public int getKey() { return key; }
    }
}
