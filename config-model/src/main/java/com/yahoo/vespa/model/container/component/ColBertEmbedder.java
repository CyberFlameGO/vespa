// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

package com.yahoo.vespa.model.container.component;

import com.yahoo.config.ModelReference;
import com.yahoo.config.model.deploy.DeployState;
import com.yahoo.embedding.ColBertEmbedderConfig;
import com.yahoo.vespa.model.container.ApplicationContainerCluster;
import org.w3c.dom.Element;

import static com.yahoo.text.XML.getChildValue;
import static com.yahoo.vespa.model.container.ContainerModelEvaluation.INTEGRATION_BUNDLE_NAME;


/**
 * @author bergum
 */
public class ColBertEmbedder extends TypedComponent implements ColBertEmbedderConfig.Producer {
    private final ModelReference modelRef;
    private final ModelReference vocabRef;

    private final Integer maxQueryTokens;

    private final Integer maxDocumentTokens;

    private final Integer transformerStartSequenceToken;
    private final Integer transformerEndSequenceToken;
    private final Integer transformerMaskToken;
    private final Integer maxTokens;
    private final String transformerInputIds;
    private final String transformerAttentionMask;

    private final String transformerOutput;
    private final String onnxExecutionMode;
    private final Integer onnxInteropThreads;
    private final Integer onnxIntraopThreads;
    private final Integer onnxGpuDevice;

    public ColBertEmbedder(ApplicationContainerCluster cluster, Element xml, DeployState state) {
        super("ai.vespa.embedding.ColBertEmbedder", INTEGRATION_BUNDLE_NAME, xml);
        var model = Model.fromXml(state, xml, "transformer-model").orElseThrow();
        modelRef = model.modelReference();
        vocabRef = Model.fromXml(state, xml, "tokenizer-model")
                .map(Model::modelReference)
                .orElseGet(() -> resolveDefaultVocab(model, state));
        maxTokens = getChildValue(xml, "max-tokens").map(Integer::parseInt).orElse(null);
        maxQueryTokens = getChildValue(xml, "max-query-tokens").map(Integer::parseInt).orElse(null);
        maxDocumentTokens = getChildValue(xml, "max-document-tokens").map(Integer::parseInt).orElse(null);
        transformerStartSequenceToken = getChildValue(xml, "transformer-start-sequence-token").map(Integer::parseInt).orElse(null);
        transformerEndSequenceToken = getChildValue(xml, "transformer-end-sequence-token").map(Integer::parseInt).orElse(null);
        transformerMaskToken = getChildValue(xml, "transformer-mask-token").map(Integer::parseInt).orElse(null);
        transformerInputIds = getChildValue(xml, "transformer-input-ids").orElse(null);
        transformerAttentionMask = getChildValue(xml, "transformer-attention-mask").orElse(null);
        transformerOutput = getChildValue(xml, "transformer-output").orElse(null);
        onnxExecutionMode = getChildValue(xml, "onnx-execution-mode").orElse(null);
        onnxInteropThreads = getChildValue(xml, "onnx-interop-threads").map(Integer::parseInt).orElse(null);
        onnxIntraopThreads = getChildValue(xml, "onnx-intraop-threads").map(Integer::parseInt).orElse(null);
        onnxGpuDevice = getChildValue(xml, "onnx-gpu-device").map(Integer::parseInt).orElse(null);
        model.registerOnnxModelCost(cluster);
    }

    private static ModelReference resolveDefaultVocab(Model model, DeployState state) {
        var modelId = model.modelId().orElse(null);
        if (state.isHosted() && modelId != null) {
            return Model.fromParams(state, model.name(), modelId + "-vocab", null, null).modelReference();
        }
        throw new IllegalArgumentException("'tokenizer-model' must be specified");
    }

    @Override
    public void getConfig(ColBertEmbedderConfig.Builder b) {
        b.transformerModel(modelRef).tokenizerPath(vocabRef);
        if (maxTokens != null) b.transformerMaxTokens(maxTokens);
        if (transformerInputIds != null) b.transformerInputIds(transformerInputIds);
        if (transformerAttentionMask != null) b.transformerAttentionMask(transformerAttentionMask);
        if (transformerOutput != null) b.transformerOutput(transformerOutput);
        if (maxQueryTokens != null) b.maxQueryTokens(maxQueryTokens);
        if (maxDocumentTokens != null) b.maxDocumentTokens(maxDocumentTokens);
        if (transformerStartSequenceToken != null) b.transformerStartSequenceToken(transformerStartSequenceToken);
        if (transformerEndSequenceToken != null) b.transformerEndSequenceToken(transformerEndSequenceToken);
        if (transformerMaskToken != null) b.transformerMaskToken(transformerMaskToken);
        if (onnxExecutionMode != null) b.transformerExecutionMode(
                ColBertEmbedderConfig.TransformerExecutionMode.Enum.valueOf(onnxExecutionMode));
        if (onnxInteropThreads != null) b.transformerInterOpThreads(onnxInteropThreads);
        if (onnxIntraopThreads != null) b.transformerIntraOpThreads(onnxIntraopThreads);
        if (onnxGpuDevice != null) b.transformerGpuDevice(onnxGpuDevice);
    }
}
