// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.schema.expressiontransforms;

import com.yahoo.searchlib.rankingexpression.ExpressionFunction;
import com.yahoo.searchlib.rankingexpression.RankingExpression;
import com.yahoo.searchlib.rankingexpression.Reference;
import com.yahoo.searchlib.rankingexpression.evaluation.DoubleValue;
import com.yahoo.searchlib.rankingexpression.rule.OperationNode;
import com.yahoo.searchlib.rankingexpression.rule.Operator;
import com.yahoo.searchlib.rankingexpression.rule.CompositeNode;
import com.yahoo.searchlib.rankingexpression.rule.ConstantNode;
import com.yahoo.searchlib.rankingexpression.rule.EmbracedNode;
import com.yahoo.searchlib.rankingexpression.rule.ExpressionNode;
import com.yahoo.searchlib.rankingexpression.rule.IfNode;
import com.yahoo.searchlib.rankingexpression.rule.ReferenceNode;
import com.yahoo.searchlib.rankingexpression.rule.SerializationContext;
import com.yahoo.searchlib.rankingexpression.rule.TensorFunctionNode;
import com.yahoo.searchlib.rankingexpression.transform.ExpressionTransformer;
import com.yahoo.tensor.TensorType;
import com.yahoo.tensor.functions.Generate;
import com.yahoo.tensor.functions.Slice;
import com.yahoo.tensor.functions.TensorFunction;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

import static com.yahoo.searchlib.rankingexpression.rule.TensorFunctionNode.wrapScalar;

/**
 * Convenience feature transforms for inputs to Transformer type models.
 *
 * Replaces features of the form
 *
 *     tokenInputIds
 *     tokenTypeIds
 *     tokenAttentionMask
 *
 * to tensor generation expressions that generate the required input.
 * In general, these models expect input of the form:
 *
 *     CLS + arg1 + SEP + arg2 + SEP + 0's
 *
 * @author lesters
 */
public class TokenTransformer extends ExpressionTransformer<RankProfileTransformContext> {

    static private final ConstantNode ZERO = new ConstantNode(new DoubleValue(0.0));
    static private final ConstantNode ONE  = new ConstantNode(new DoubleValue(1.0));
    static private final ConstantNode TWO  = new ConstantNode(new DoubleValue(2.0));
    static private final ConstantNode CLS  = new ConstantNode(new DoubleValue(101));
    static private final ConstantNode SEP  = new ConstantNode(new DoubleValue(102));

    @Override
    public ExpressionNode transform(ExpressionNode node, RankProfileTransformContext context) {
        if (node instanceof ReferenceNode)
            return transformFeature((ReferenceNode) node, context);
        else if (node instanceof CompositeNode)
            return super.transformChildren((CompositeNode) node, context);
        else
            return node;
    }

    private ExpressionNode transformFeature(ReferenceNode feature, RankProfileTransformContext context) {
        if (feature.getName().equals("customTokenInputIds") && shouldTransform(feature, context))
            return transformCustomTokenInputIds(feature, context);
        if (feature.getName().equals("tokenInputIds") && shouldTransform(feature, context))
            return transformTokenInputIds(feature, context);
        if (feature.getName().equals("tokenTypeIds") && shouldTransform(feature, context))
            return transformTokenTypeIds(feature, context);
        if (feature.getName().equals("tokenAttentionMask") && shouldTransform(feature, context))
            return transformTokenAttentionMask(feature, context);
        return feature;
    }

    /**
     * Transforms a feature of the form
     *
     *     tokenInputIds(128, a, b, ...)
     *
     * to an expression that concatenates the arguments a, b, ... using the
     * special Transformers sequences of CLS and SEP, up to length 128, so
     * that the sequence becomes
     *
     *     CLS + a + SEP + b + SEP + 0's
     *
     * Concretely, transforms to a tensor generation expression:
     *
     *     tensor(d0[1],d1[128])(
     *         if (d1 < 1,
     *             101,
     *         if (d1 < 1 + length_a,
     *             a{d0:(d1 - (1)},
     *         if (d1 < 1 + length_a + 1,
     *             102,
     *         if (d1 < 1 + length_a + 1 + length_b,
     *             b{d0:(d1 - (1 + length_a + 1))},
     *         if (d1 < 1 + length_a + 1 + length_b + 1,
     *             102,
     *             0.0
     *         ))))))
     *
     * Functions calculating lengths of arguments are added to the rank profile.
     */
    private ExpressionNode transformTokenInputIds(ReferenceNode feature, RankProfileTransformContext context) {
        return transformTokenInputIds(feature, context, CLS, SEP, 1);
    }

    /**
     * Transforms a feature of the form
     *
     *     customTokenInputIds(1, 2, 128, a, b, ...)
     *
     * to an expression that concatenates the arguments a, b, ... using the
     * first and second arguments as the CLS and SEP padding tokens, here
     * 1 and 2, respectively. Otherwise, identical to tokenInputIds.
     */
    private ExpressionNode transformCustomTokenInputIds(ReferenceNode feature, RankProfileTransformContext context) {
        ExpressionNode cls = feature.getArguments().expressions().get(0);
        ExpressionNode sep = feature.getArguments().expressions().get(1);
        return transformTokenInputIds(feature, context, cls, sep, 3);
    }

    private ExpressionNode transformTokenInputIds(ReferenceNode feature,
                                                  RankProfileTransformContext context,
                                                  ExpressionNode cls,
                                                  ExpressionNode sep,
                                                  int fromArg) {
        checkReferenceArguments(feature, fromArg);

        TensorType type = createTensorType(feature.getName(), feature.getArguments().expressions().get(fromArg - 1));

        // we need to add functions calculating the token lengths of the arguments
        createTokenLengthFunctions(feature, context, fromArg);

        // create token sequence: CLS + arg1 + SEP + arg2 + SEP + ....
        ExpressionNode tokenSequenceExpr = createTokenSequenceExpr(0, createTokenSequence(feature, cls, sep, fromArg));
        return new TensorFunctionNode(Generate.bound(type, wrapScalar(tokenSequenceExpr)));
    }

    /**
     * Transforms a feature of the form
     *
     *     tokenTypeIds(128, a, b, ...)
     *
     * to an expression that generates a tensor that has values 0 for "a"
     * (including CLS and SEP tokens) and 1 for the rest of the sequence.
     *
     * Concretely, transforms to a tensor generation expression:
     *
     *     tensor(d0[1],d1[128])(
     *         if (d1 < 1 + length_a + 1,
     *             0,
     *             if (d1 < 1 + length_a + 1 + length_b + 1 + ...,
     *                 1,
     *                 0
     *         )))
     */
    private ExpressionNode transformTokenTypeIds(ReferenceNode feature, RankProfileTransformContext context) {
        checkReferenceArguments(feature, 1);

        TensorType type = createTensorType(feature.getName(), feature.getArguments().expressions().get(0));

        // we need to add functions calculating the token lengths of the arguments
        createTokenLengthFunctions(feature, context, 1);

        List<ExpressionNode> tokenSequence = createTokenSequence(feature, CLS, SEP, 1);
        ExpressionNode queryLengthExpr = createLengthExpr(2, tokenSequence);
        ExpressionNode restLengthExpr = createLengthExpr(tokenSequence.size() - 1, tokenSequence);
        ExpressionNode expr = new IfNode(
                new OperationNode(new ReferenceNode("d1"), Operator.smaller, queryLengthExpr),
                ZERO,
                new IfNode(
                        new OperationNode(new ReferenceNode("d1"), Operator.smaller, restLengthExpr),
                        ONE,
                        ZERO
                )
        );
        return new TensorFunctionNode(Generate.bound(type, wrapScalar(expr)));
    }

    /**
     * Transforms a feature of the form
     *
     *     tokenAttentionMask(128, a, b, ...)
     *
     * to an expression that generates a tensor that has values 1 for all
     * arguments (including CLS and SEP tokens) and 0 for the rest of the
     * sequence.
     *
     * Concretely, transforms to a tensor generation expression:
     *
     *     tensor(d0[1],d1[128])(if(d1 < 1 + length_a + 1 + length_b + 1 + ..., 1, 0))
     *
     */
    private ExpressionNode transformTokenAttentionMask(ReferenceNode feature, RankProfileTransformContext context) {
        checkReferenceArguments(feature, 1);

        TensorType type = createTensorType(feature.getName(), feature.getArguments().expressions().get(0));

        // we need to add functions calculating the token lengths of the arguments
        createTokenLengthFunctions(feature, context, 1);

        List<ExpressionNode> tokenSequence = createTokenSequence(feature, CLS, SEP, 1);
        ExpressionNode lengthExpr = createLengthExpr(tokenSequence.size() - 1, tokenSequence);
        OperationNode comparison = new OperationNode(new ReferenceNode("d1"), Operator.smaller, lengthExpr);
        ExpressionNode expr = new IfNode(comparison, ONE, ZERO);
        return new TensorFunctionNode(Generate.bound(type, wrapScalar(expr)));
    }

    private boolean shouldTransform(ReferenceNode feature, RankProfileTransformContext context) {
        if (context.rankProfile().getFunctions().containsKey(feature.getName()))
            return false;
        if (feature.getArguments().size() < 2)
            return false;
        return true;
    }

    private void checkReferenceArguments(ReferenceNode feature, int fromArg) {
        for (int i = fromArg; i < feature.getArguments().size(); ++i) {
            ExpressionNode arg = feature.getArguments().expressions().get(i);
            if ( ! (arg instanceof ReferenceNode)) {
                throw new IllegalArgumentException("Invalid argument " + i + " to " + feature.getName() + ": " +
                        "the argument must be a reference. Got " + arg.toString());
            }
        }
    }

    public static TensorType createTensorType(String featureName, ExpressionNode argument) {
        try {
            int length = Integer.parseInt(argument.toString());
            return new TensorType.Builder(TensorType.Value.FLOAT).indexed("d0", 1).indexed("d1", length).build();
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid argument to " + featureName + ": the first argument must be " +
                    "the length to the token sequence to generate. Got " + argument);
        }
    }

    private static final ExpressionFunction commonLengthFunction = makeLengthFunction();
    private static ExpressionFunction makeLengthFunction() {
        String func = "sum(map(input, f(x)(x > 0)))";
        String name = "__token_length";
        try (var r = new StringReader(func)) {
            return new ExpressionFunction(name, List.of("input"), new RankingExpression(name, r));
        }
        catch (com.yahoo.searchlib.rankingexpression.parser.ParseException e) {
            throw new IllegalStateException("unexpected", e);
        }
    }

    private ExpressionFunction.Instance lengthFunctionFor(ReferenceNode arg) {
        var ctx = new SerializationContext();
        return commonLengthFunction.expand(ctx, List.of(arg), new ArrayDeque<String>());
    }

    private List<ExpressionNode> createTokenSequence(ReferenceNode feature, ExpressionNode cls, ExpressionNode sep, int fromArg) {
        List<ExpressionNode> sequence = new ArrayList<>();
        sequence.add(cls);
        for (int i = fromArg; i < feature.getArguments().size(); ++i) {
            sequence.add(feature.getArguments().expressions().get(i));
            sequence.add(sep);
        }
        return sequence;
    }

    /**
     * Adds functions for calculating the token length input. Assumes that
     * token sequences are 0-padded, so this returns the number of non-0
     * tokens using a map and reduce-sum.
     */
    private void createTokenLengthFunctions(ReferenceNode feature, RankProfileTransformContext context, int fromArg) {
        for (int i = fromArg; i < feature.getArguments().size(); ++i) {
            ExpressionNode arg = feature.getArguments().expressions().get(i);
            if ( ! (arg instanceof ReferenceNode ref)) {
                throw new IllegalArgumentException("Invalid argument " + i + " to " + feature.getName() + ": " +
                        "the argument must be a reference. Got " + arg.toString());
            }
            var f = lengthFunctionFor(ref);
            if ( ! context.rankProfile().getFunctions().containsKey(f.getName())) {
                context.rankProfile().addFunction(f.getName(), List.of(), f.getExpressionString(), false);
            }
        }
    }

    /**
     * Recursively creates partial expressions of the form
     *
     *  if (d1 < 1 + length_a,
     *      a{d0:(d1 - 1},
     *      ...
     *
     * for each part of the token sequence. CLS and SEP are added directly,
     * and we create a slice expression for each argument to extract the
     * actual tokens.
     */
    private ExpressionNode createTokenSequenceExpr(int iter, List<ExpressionNode> sequence) {
        ExpressionNode lengthExpr = createLengthExpr(iter, sequence);
        OperationNode comparison = new OperationNode(new ReferenceNode("d1"), Operator.smaller, lengthExpr);

        ExpressionNode trueExpr = sequence.get(iter);
        if (sequence.get(iter) instanceof ReferenceNode) {
            trueExpr = createTokenExtractExpr(iter, sequence);
        }

        ExpressionNode falseExpr;
        if (iter < sequence.size() - 1) {
            falseExpr = createTokenSequenceExpr(iter + 1, sequence);
        } else {
            falseExpr = ZERO;  // 0-padding for rest of sequence
        }

        return new IfNode(comparison, trueExpr, falseExpr);
    }

    /**
     * Creates an expression for the length of the token sequence so far, where
     * the lengths of CLS and SEP are 1, and the length of the arguments are
     * calculated using auxiliary functions.
     */
    private ExpressionNode createLengthExpr(int iter, List<ExpressionNode> sequence) {
        List<ExpressionNode> factors = new ArrayList<>();
        List<Operator> operators = new ArrayList<>();
        for (int i = 0; i < iter + 1; ++i) {
            if (sequence.get(i) instanceof ConstantNode) {
                factors.add(ONE);
            } else if (sequence.get(i) instanceof ReferenceNode ref) {
                var f = lengthFunctionFor(ref);
                factors.add(new ReferenceNode(f.getName()));
            }
            if (i >= 1) {
                operators.add(Operator.plus);
            }
        }
        if (operators.isEmpty() && factors.size() == 1) {
            return factors.get(0);
        }
        return new OperationNode(factors, operators);
    }

    /**
     * Create the slice expression to extract the tokens from arguments
     */
    private ExpressionNode createTokenExtractExpr(int iter, List<ExpressionNode> sequence) {
        ExpressionNode expr;
        if (iter >= 1) {
            ExpressionNode lengthExpr = new EmbracedNode(createLengthExpr(iter - 1, sequence));
            expr = new EmbracedNode(new OperationNode(new ReferenceNode("d1"), Operator.minus, lengthExpr));
        } else {
            expr = new ReferenceNode("d1");
        }
        List<Slice.DimensionValue<Reference>> slices = List.of(new Slice.DimensionValue<>("d0", wrapScalar(expr)) );
        TensorFunction<Reference> argument = new TensorFunctionNode.ExpressionTensorFunction(sequence.get(iter));
        return new TensorFunctionNode(new Slice<>(argument, slices));
    }

}
