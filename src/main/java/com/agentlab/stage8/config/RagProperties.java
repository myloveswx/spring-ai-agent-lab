package com.agentlab.stage8.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stage 8（RAG）的可调参数，全部挂在 {@code agentlab.rag.*} 之下。
 *
 * <p>为什么这些值要外置成配置，而不是写死在代码里？
 * 因为 L1 朴素 RAG 的「效果调优」几乎全在这几个旋钮上：
 * <ul>
 *   <li>{@link #topK} —— 召回几条。太小 → 漏；太大 → 噪声挤占上下文、还把答案稀释掉</li>
 *   <li>{@link #similarityThreshold} —— 相似度下限。太低 → 什么烂片段都塞进去，
 *       模型被带偏；太高 → 明明库里有关键信息却检索不到</li>
 *   <li>{@link #chunkSize} / {@link #minChunkSizeChars} / {@link #minChunkLengthToEmbed}
 *       —— 切块粒度。块太大 → 一条里混了多个主题，向量被平均掉、语义变糊；
 *       块太小 → 一句话被劈开，上下文断裂</li>
 * </ul>
 *
 * <p><b>L1 的一个已知短板</b>：内置的 {@code TokenTextSplitter} 构造函数里
 * <b>没有重叠（overlap）参数</b>，也就是不支持「相邻块共享一部分内容」。
 * 于是当一句关键话正好落在切口上时，两个块各拿到半句，谁也检索不爽。
 * 这不是配置问题，是 L1 用现成切块器的固有代价 ——
 * <b>自己写一个带重叠的 {@code TextSplitter} 是 L2 的第一个升级点</b>。
 *
 * <p>把「调参」变成「改配置重启」，你才能在一次实验里只动一个变量 ——
 * 这是把 RAG 从「玄学」变成「工程」的第一步。
 */
@ConfigurationProperties(prefix = "agentlab.rag")
public class RagProperties {

    /**
     * 总开关。关掉后 Stage 8 的所有 Bean 与接口都不会被创建
     * （用 {@code @ConditionalOnProperty} 控制），于是「模型文件缺失」这类问题
     * 不会拖垮整个应用 —— 其余 7 个阶段照常可用。
     */
    private boolean enabled = true;

    /**
     * 本地 ONNX 模型所在目录，目录内需同时存在 {@code model.onnx} 与 {@code tokenizer.json}。
     *
     * <p>本项目用的是 {@code BAAI/bge-small-zh-v1.5}（中文小模型，约 95MB）。
     * 之所以坚持「本地目录」而不是让框架自动从 HuggingFace 下载：
     * 实测本机直连 GitHub/HF 只有 ~51KB/s，95MB 要等近 30 分钟，
     * 而且断网就完全跑不起来。本地文件是确定性的。
     */
    private String modelDir;

    /** 单次检索返回的片段数。默认 4，是「够用且不撑爆上下文」的常见起点。 */
    private int topK = 4;

    /**
     * 相似度阈值，取值范围 [-1, 1]（余弦）。低于它的候选直接丢弃。
     * <p>0.5 对 bge-small-zh 这类模型是个偏保守的起点；
     * 想让模型「宁可多给点线索」就调到 0.3 左右。
     */
    private double similarityThreshold = 0.5;

    /** 落盘文件路径（{@code SimpleVectorStore.save/load} 用）。目录不存在会自动创建。 */
    private String storePath;

    /**
     * 启动时是否跑一次「预热嵌入」。
     *
     * <p>强烈建议保持 true。首次调用 ONNX 时会发生两件一次性开销：
     * ① onnxruntime 把原生库解压到临时目录；② Windows Defender 首次扫描该 dll。
     * 实测第一次 {@code embed()} 要 60 秒以上，之后稳态只要 20~90ms。
     * 不预热的话，这 60 秒会砸在用户的第一次提问上，看起来像「接口挂了」。
     */
    private boolean warmup = true;

    /** 切块时的目标 token 上限（{@code TokenTextSplitter.withChunkSize}）。 */
    private int chunkSize = 400;

    /**
     * 低于这个<b>字符</b>数的块会被并进相邻块（{@code withMinChunkSizeChars}）。
     * <p>中文一个字约等于 1 个字符，所以这个值对中文语料要明显小于英文语料。
     */
    private int minChunkSizeChars = 200;

    /**
     * 低于这个<b>字符</b>数的碎片直接丢弃（{@code withMinChunkLengthToEmbed}）。
     * <p>像目录行、单个标题这种两三字的内容，单独成一个向量只会是噪声。
     */
    private int minChunkLengthToEmbed = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModelDir() {
        return modelDir;
    }

    public void setModelDir(String modelDir) {
        this.modelDir = modelDir;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    public String getStorePath() {
        return storePath;
    }

    public void setStorePath(String storePath) {
        this.storePath = storePath;
    }

    public boolean isWarmup() {
        return warmup;
    }

    public void setWarmup(boolean warmup) {
        this.warmup = warmup;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getMinChunkSizeChars() {
        return minChunkSizeChars;
    }

    public void setMinChunkSizeChars(int minChunkSizeChars) {
        this.minChunkSizeChars = minChunkSizeChars;
    }

    public int getMinChunkLengthToEmbed() {
        return minChunkLengthToEmbed;
    }

    public void setMinChunkLengthToEmbed(int minChunkLengthToEmbed) {
        this.minChunkLengthToEmbed = minChunkLengthToEmbed;
    }
}
