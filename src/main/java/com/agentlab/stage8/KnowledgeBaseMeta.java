package com.agentlab.stage8;

/**
 * 一个知识库的元数据 —— 也就是「账本」，与向量本体分离存放。
 *
 * <h2>为什么元数据和向量要分开</h2>
 * 因为 {@code VectorStore} 接口只有 4 个方法（{@code add / delete(List) /
 * delete(Filter) / similaritySearch}），<b>既不告诉你「库里有什么」，也不支持自定义属性</b>。
 * 「这个库叫什么名字、什么时候建的、谁建的」这类业务信息，
 * 向量库一个字都存不了 —— 必须由应用自己维护一份账本。
 *
 * <p>这不是 Spring AI 的缺陷，而是所有向量数据库的定位差异：
 * 它们是「相似度检索引擎」，不是「业务数据库」。
 * 真实项目里的标准做法是 <b>MySQL 存业务元数据 + 向量库存向量</b>，
 * 用同一个 documentId 关联。本类就是那条思路的最朴素版本
 * （用一行 TSV 代替一张 MySQL 表，L1 阶段够用且零依赖）。
 *
 * @param id          知识库标识。<b>同时也是磁盘目录名</b>，所以只允许
 *                    {@code [A-Za-z0-9_-]} 且不超过 32 个字符 ——
 *                    这一点在 {@link KnowledgeBaseRegistry#create} 里被强制校验。
 * @param name        显示名，任意中文都行（例：知识库1）。检索与目录都不用它。
 * @param description 备注，纯给人看的。
 * @param createdAt   创建时间，格式 {@code yyyy-MM-dd HH:mm:ss}。
 */
public record KnowledgeBaseMeta(String id, String name, String description, String createdAt) {
}
