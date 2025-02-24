// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package ai.vespa.rankingexpression.importer.operations;

import ai.vespa.rankingexpression.importer.OrderedTensorType;
import com.yahoo.searchlib.rankingexpression.Reference;
import com.yahoo.searchlib.rankingexpression.evaluation.DoubleValue;
import com.yahoo.searchlib.rankingexpression.rule.OperationNode;
import com.yahoo.searchlib.rankingexpression.rule.Operator;
import com.yahoo.searchlib.rankingexpression.rule.ConstantNode;
import com.yahoo.searchlib.rankingexpression.rule.EmbracedNode;
import com.yahoo.searchlib.rankingexpression.rule.ExpressionNode;
import com.yahoo.searchlib.rankingexpression.rule.ReferenceNode;
import com.yahoo.searchlib.rankingexpression.rule.TensorFunctionNode;
import com.yahoo.tensor.Tensor;
import com.yahoo.tensor.TensorType;
import com.yahoo.tensor.functions.Generate;
import com.yahoo.tensor.functions.TensorFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.yahoo.searchlib.rankingexpression.rule.TensorFunctionNode.wrapScalar;

/**
 * Onnx tile operation.
 */
public class Tile extends IntermediateOperation {

    public Tile(String modelName, String nodeName, List<IntermediateOperation> inputs) {
        super(modelName, nodeName, inputs);
    }

    @Override
    protected OrderedTensorType lazyGetType() {
        if (!allInputTypesPresent(2)) return null;

        // required as we use tensor create
        inputs.get(0).exportAsRankingFunction = true;

        IntermediateOperation repeats = inputs.get(1);
        if (repeats.getConstantValue().isEmpty())
            throw new IllegalArgumentException("Tile " + name + ": repeats input must be a constant.");

        Tensor shape = repeats.getConstantValue().get().asTensor();
        if (shape.type().rank() != 1)
            throw new IllegalArgumentException("Tile " + name + ": repeats must be a 1-d tensor.");

        OrderedTensorType inputType = inputs.get(0).type().get();
        if (shape.type().dimensions().get(0).size().get().intValue() != inputType.rank())
            throw new IllegalArgumentException("Tile " + name + ": repeats must be the same size as input rank.");

        List<Integer> dimSizes = new ArrayList<>(inputType.rank());
        shape.valueIterator().forEachRemaining(v -> dimSizes.add(v.intValue()));

        OrderedTensorType.Builder typeBuilder = new OrderedTensorType.Builder(resultValueType());
        for (int i = 0; i < dimSizes.size(); ++i) {
            TensorType.Dimension inputDimension = inputType.dimensions().get(i);
            typeBuilder.add(TensorType.Dimension.indexed(inputDimension.name(), inputDimension.size().get() * dimSizes.get(i)));
        }
        return typeBuilder.build();
    }

    @Override
    protected TensorFunction<Reference> lazyGetFunction() {
        if (!allInputFunctionsPresent(2)) return null;

        IntermediateOperation input = inputs.get(0);
        OrderedTensorType inputType = input.type().get();
        String inputFunctionName = input.rankingExpressionFunctionName();

        List<com.yahoo.tensor.functions.Slice.DimensionValue<Reference>> dimensionValues = new ArrayList<>();

        for (int axis = 0; axis < inputType.rank(); ++axis) {
            String inputDimensionName = inputType.dimensions().get(axis).name();
            long inputDimensionSize = inputType.dimensions().get(axis).size().get();

            ExpressionNode size = new ConstantNode(new DoubleValue(inputDimensionSize));
            ExpressionNode reference = new ReferenceNode(inputDimensionName);
            ExpressionNode mod = new OperationNode(reference, Operator.modulo, size);
            dimensionValues.add(new com.yahoo.tensor.functions.Slice.DimensionValue<>(Optional.of(inputDimensionName), wrapScalar(new EmbracedNode(mod))));
        }

        TensorFunction<Reference> inputIndices = new TensorFunctionNode.ExpressionTensorFunction(new ReferenceNode(inputFunctionName));
        com.yahoo.tensor.functions.Slice<Reference> sliceIndices = new com.yahoo.tensor.functions.Slice<>(inputIndices, dimensionValues);
        ExpressionNode sliceExpression = new TensorFunctionNode(sliceIndices);

        TensorFunction<Reference> generate = Generate.bound(type.type(), wrapScalar(sliceExpression));
        return generate;
    }

    @Override
    public Tile withInputs(List<IntermediateOperation> inputs) {
        return new Tile(modelName(), name(), inputs);
    }

    @Override
    public String operationName() { return "Tile"; }

}
