// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.document.json.readers;

import com.fasterxml.jackson.core.JsonToken;
import com.yahoo.document.datatypes.TensorFieldValue;
import com.yahoo.document.json.TokenBuffer;
import com.yahoo.slime.Inspector;
import com.yahoo.slime.Type;
import com.yahoo.tensor.IndexedTensor;
import com.yahoo.tensor.MixedTensor;
import com.yahoo.tensor.Tensor;
import com.yahoo.tensor.TensorAddress;
import com.yahoo.tensor.TensorType;

import static com.yahoo.document.json.readers.JsonParserHelpers.*;
import static com.yahoo.tensor.serialization.JsonFormat.decodeHexString;

/**
 * Reads the tensor format defined at
 * See <a href="https://docs.vespa.ai/en/reference/document-json-format.html">https://docs.vespa.ai/en/reference/document-json-format.html</a>
 *
 * @author geirst
 * @author bratseth
 */
public class TensorReader {

    public static final String TENSOR_TYPE = "type";
    public static final String TENSOR_ADDRESS = "address";
    public static final String TENSOR_CELLS = "cells";
    public static final String TENSOR_VALUES = "values";
    public static final String TENSOR_BLOCKS = "blocks";
    public static final String TENSOR_VALUE = "value";

    // MUST be kept in sync with com.yahoo.tensor.serialization.JsonFormat.decode in vespajlib
    static void fillTensor(TokenBuffer buffer, TensorFieldValue tensorFieldValue) {
        Tensor.Builder builder = Tensor.Builder.of(tensorFieldValue.getDataType().getTensorType());
        expectOneOf(buffer.current(), JsonToken.START_OBJECT, JsonToken.START_ARRAY);
        int initNesting = buffer.nesting();
        for (buffer.next(); buffer.nesting() >= initNesting; buffer.next()) {
            if (TENSOR_CELLS.equals(buffer.currentName()) && ! primitiveContent(buffer)) {
                readTensorCells(buffer, builder);
            }
            else if (TENSOR_VALUES.equals(buffer.currentName()) && builder.type().dimensions().stream().allMatch(d -> d.isIndexed())) {
                readTensorValues(buffer, builder);
            }
            else if (TENSOR_BLOCKS.equals(buffer.currentName())) {
                readTensorBlocks(buffer, builder);
            }
            else if (TENSOR_TYPE.equals(buffer.currentName()) && buffer.current() == JsonToken.VALUE_STRING) {
                // Ignore input tensor type
            }
            else {
                buffer.previous(); // Back up to the start of the enclosing block
                readDirectTensorValue(buffer, builder);
                buffer.previous(); // ... and back up to the end of the enclosing block
            }
        }
        expectOneOf(buffer.current(), JsonToken.END_OBJECT, JsonToken.END_ARRAY);
        tensorFieldValue.assign(builder.build());
    }

    static boolean primitiveContent(TokenBuffer buffer) {
        JsonToken cellsValue = buffer.current();
        if (cellsValue.isScalarValue()) return true;
        if (cellsValue == JsonToken.START_ARRAY) {
            JsonToken firstArrayValue = buffer.peek(1);
            if (firstArrayValue == JsonToken.END_ARRAY) return false;
            if (firstArrayValue.isScalarValue()) return true;
        }
        return false;
    }

    static void readTensorCells(TokenBuffer buffer, Tensor.Builder builder) {
        if (buffer.current() == JsonToken.START_ARRAY) {
            int initNesting = buffer.nesting();
            for (buffer.next(); buffer.nesting() >= initNesting; buffer.next())
                readTensorCell(buffer, builder);
        }
        else if (buffer.current() == JsonToken.START_OBJECT) { // single dimension short form
            int initNesting = buffer.nesting();
            for (buffer.next(); buffer.nesting() >= initNesting; buffer.next())
                builder.cell(asAddress(buffer.currentName(), builder.type()), readDouble(buffer));
        }
        else {
            throw new IllegalArgumentException("Expected 'cells' to contain an array or an object, but got " + buffer.current());
        }
        expectCompositeEnd(buffer.current());
    }

    private static void readTensorCell(TokenBuffer buffer, Tensor.Builder builder) {
        expectObjectStart(buffer.current());

        TensorAddress address = null;
        Double value = null;
        int initNesting = buffer.nesting();
        for (buffer.next(); buffer.nesting() >= initNesting; buffer.next()) {
            String currentName = buffer.currentName();
            if (TensorReader.TENSOR_ADDRESS.equals(currentName)) {
                address = readAddress(buffer, builder.type());
            } else if (TensorReader.TENSOR_VALUE.equals(currentName)) {
                value = readDouble(buffer);
            }
        }
        expectObjectEnd(buffer.current());
        if (address == null)
            throw new IllegalArgumentException("Expected an object in a tensor 'cells' array to contain an 'address' field");
        if (value == null)
            throw new IllegalArgumentException("Expected an object in a tensor 'cells' array to contain a 'value' field");
        builder.cell(address, value);
    }

    private static void readTensorValues(TokenBuffer buffer, Tensor.Builder builder) {
        if ( ! (builder instanceof IndexedTensor.BoundBuilder indexedBuilder))
            throw new IllegalArgumentException("The 'values' field can only be used with dense tensors. " +
                                               "Use 'cells' or 'blocks' instead");
        if (buffer.current() == JsonToken.VALUE_STRING) {
            double[] decoded = decodeHexString(buffer.currentText(), builder.type().valueType());
            if (decoded.length == 0)
                throw new IllegalArgumentException("The 'values' string does not contain any values");
            for (int i = 0; i < decoded.length; i++) {
                indexedBuilder.cellByDirectIndex(i, decoded[i]);
            }
            return;
        }
        int index = 0;
        int initNesting = buffer.nesting();
        for (buffer.next(); buffer.nesting() >= initNesting; buffer.next()) {
            if (buffer.current() == JsonToken.START_ARRAY || buffer.current() == JsonToken.END_ARRAY) continue; // nested arrays: Skip
            indexedBuilder.cellByDirectIndex(index++, readDouble(buffer));
        }
        if (index == 0)
            throw new IllegalArgumentException("The 'values' array does not contain any values");
        expectCompositeEnd(buffer.current());
    }

    static void readTensorBlocks(TokenBuffer buffer, Tensor.Builder builder) {
        if ( ! (builder instanceof MixedTensor.BoundBuilder mixedBuilder))
            throw new IllegalArgumentException("The 'blocks' field can only be used with mixed tensors with bound dimensions. " +
                                               "Use 'cells' or 'values' instead");
        if (buffer.current() == JsonToken.START_ARRAY) {
            int initNesting = buffer.nesting();
            for (buffer.next(); buffer.nesting() >= initNesting; buffer.next())
                readTensorBlock(buffer, mixedBuilder);
        }
        else if (buffer.current() == JsonToken.START_OBJECT) {
            int initNesting = buffer.nesting();
            for (buffer.next(); buffer.nesting() >= initNesting; buffer.next()) {
                TensorAddress mappedAddress = asAddress(buffer.currentName(), builder.type().mappedSubtype());
                mixedBuilder.block(mappedAddress,
                                   readValues(buffer, (int) mixedBuilder.denseSubspaceSize(), mappedAddress, mixedBuilder.type()));
            }
        }
        else {
            throw new IllegalArgumentException("Expected 'blocks' to contain an array or an object, but got " +
                                               buffer.current());
        }

        expectCompositeEnd(buffer.current());
    }

    private static void readTensorBlock(TokenBuffer buffer, MixedTensor.BoundBuilder mixedBuilder) {
        expectObjectStart(buffer.current());

        TensorAddress address = null;
        double[] values = null;

        int initNesting = buffer.nesting();
        for (buffer.next(); buffer.nesting() >= initNesting; buffer.next()) {
            String currentName = buffer.currentName();
            if (TensorReader.TENSOR_ADDRESS.equals(currentName))
                address = readAddress(buffer, mixedBuilder.type().mappedSubtype());
            else if (TensorReader.TENSOR_VALUES.equals(currentName))
                values = readValues(buffer, (int)mixedBuilder.denseSubspaceSize(), address, mixedBuilder.type());
        }
        expectObjectEnd(buffer.current());
        if (address == null)
            throw new IllegalArgumentException("Expected a 'blocks' array object to contain an object 'address'");
        if (values == null)
            throw new IllegalArgumentException("Expected a 'blocks' array object to contain an array 'values'");
        mixedBuilder.block(address, values);
    }

    /** Reads a tensor value directly at the root, where the format is decided by the tensor type. */
    private static void readDirectTensorValue(TokenBuffer buffer, Tensor.Builder builder) {
        boolean hasIndexed = builder.type().dimensions().stream().anyMatch(TensorType.Dimension::isIndexed);
        boolean hasMapped = builder.type().dimensions().stream().anyMatch(TensorType.Dimension::isMapped);

        if (isArrayOfObjects(buffer, 0))
            readTensorCells(buffer, builder);
        else if ( ! hasMapped)
            readTensorValues(buffer, builder);
        else if (hasMapped && hasIndexed)
            readTensorBlocks(buffer, builder);
        else
            readTensorCells(buffer, builder);
    }

    private static boolean isArrayOfObjects(TokenBuffer buffer, int ahead) {
        if (buffer.peek(ahead++) != JsonToken.START_ARRAY) return false;
        if (buffer.peek(ahead) == JsonToken.START_ARRAY) return isArrayOfObjects(buffer, ahead); // nested array
        return buffer.peek(ahead) == JsonToken.START_OBJECT;
    }

    private static TensorAddress readAddress(TokenBuffer buffer, TensorType type) {
        expectObjectStart(buffer.current());
        TensorAddress.Builder builder = new TensorAddress.Builder(type);
        int initNesting = buffer.nesting();
        for (buffer.next(); buffer.nesting() >= initNesting; buffer.next())
            builder.add(buffer.currentName(), buffer.currentText());
        expectObjectEnd(buffer.current());
        return builder.build();
    }

    /**
     * Reads values for a tensor subspace block
     *
     * @param buffer the buffer containing the values
     * @param size the expected number of values
     * @param address the address for the block for error reporting, or null if not known
     * @param type the type of the tensor we are reading
     * @return the values read
     */
    private static double[] readValues(TokenBuffer buffer, int size, TensorAddress address, TensorType type) {
        int index = 0;
        double[] values = new double[size];
        if (buffer.current() == JsonToken.VALUE_STRING) {
            values = decodeHexString(buffer.currentText(), type.valueType());
            index = values.length;
        } else {
            expectArrayStart(buffer.current());
            int initNesting = buffer.nesting();
            for (buffer.next(); buffer.nesting() >= initNesting; buffer.next()) {
                if (buffer.current() == JsonToken.START_ARRAY || buffer.current() == JsonToken.END_ARRAY) continue; // nested arrays: Skip
                values[index++] = readDouble(buffer);
            }
            expectCompositeEnd(buffer.current());
        }
        if (index != size)
            throw new IllegalArgumentException((address != null ? "At " + address.toString(type) + ": " : "") +
                                               "Expected " + size + " values, but got " + index);
        return values;
    }

    private static double readDouble(TokenBuffer buffer) {
        try {
            return Double.parseDouble(buffer.currentText());
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected a number but got '" + buffer.currentText() + "'");
        }
    }

    private static TensorAddress asAddress(String label, TensorType type) {
        if (type.dimensions().size() != 1)
            throw new IllegalArgumentException("Expected a tensor with a single dimension but got '" + type + "'");
        return new TensorAddress.Builder(type).add(type.dimensions().get(0).name(), label).build();
    }

}
