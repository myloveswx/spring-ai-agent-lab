package com.agentlab.stage6.lab;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索用的极简中文文本工具。
 *
 * <p><b>为什么需要它？</b>
 * Spring AI 默认的 {@code RegexToolIndex} 用的是<b>英文</b>停用词表，并且隐含假设
 * 「词之间用空格分隔」。中文 query（例如「还有多少积分」）里根本没有空格，
 * 整句会被当成一个 token，于是几乎匹配不到任何工具描述 —— 这就是
 * 「默认 regex 策略在中文场景下基本失效」的根本原因。
 *
 * <p>这里用「中文 n-gram + ASCII 词元」的方式把 query 拆成可比较的碎片。
 * 它不是一个正经的中文分词器（没有词典、不判断词性），但对
 * 「几十个工具 + 一句短 query」的规模已经够用，而且零依赖、行为完全可预测 ——
 * 在生产里要更强的效果，应当换成 lucene / 向量索引（见 {@code LuceneToolIndex}、
 * {@code VectorToolIndex}），而不是把 n-gram 调参调到天亮。
 *
 * <p><b>一个容易被忽略的细节</b>：query 里常常混着参数值（{@code C1001}、
 * {@code SO202610010001}）。这些串如果参与打分，会把「找哪个工具」这件事
 * 带偏（比如描述里恰好含数字）。所以 {@link #isIdLike(String)} 把它们挑出来，
 * 由调用方决定忽略。
 */
final class ChineseText {

    /** 连续 ASCII 字母数字串：工具名里的英文词、以及各种 ID 值。 */
    private static final Pattern ASCII_RUN = Pattern.compile("[A-Za-z0-9]+");

    /** 连续中文串（含扩展 A 区之外常用区即可，够本场景用）。 */
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]+");

    /** 形如 C1001 / SO202610010001 / 20261001 的「参数值」特征。 */
    private static final Pattern ID_LIKE = Pattern.compile("(?i)^[a-z]{0,3}\\d{3,}$");

    /** camelCase / 缩写边界：HTTPServer -> HTTP Server，queryPoints -> query Points。 */
    private static final Pattern CAMEL_LOWER_UPPER = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern ACRONYM_BOUNDARY = Pattern.compile("([A-Z]+)([A-Z][a-z])");

    /**
     * 只挡掉最影响打分的虚词二元组，不做完整停用词工程。
     * 注意「客户」「订单」「积分」这类<b>领域词不能停用</b> —— 它们恰恰是强信号。
     */
    private static final Set<String> STOP_GRAMS = Set.of(
            "多少", "什么", "怎么", "怎样", "如何", "是否", "一下", "帮我", "帮我查",
            "我想", "我要", "麻烦", "现在", "目前", "这个", "那个", "还有", "以及",
            "的话", "可以", "能否", "请问", "一个", "看看", "查下", "查查");

    /** 英文虚词，避免 "by" / "of" 这类词元在工具名上产生虚假命中。 */
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "by", "of", "in", "on", "at", "to", "for", "and", "or",
            "is", "are", "was", "be", "do", "does", "did", "with", "from", "as", "that", "this");

    private ChineseText() {
    }

    /**
     * 中文 2-gram + 3-gram。
     *
     * <p>为什么同时用 2 和 3？2-gram 召回高（「积分」一定能命中「积分余额」），
     * 3-gram 精度高（「优惠券」比「优惠 + 惠券」更不容易误配）。两者相加、
     * 让长词自然获得更高权重，是在没有分词词典时最省事的一种折中。
     */
    static Set<String> grams(String text) {
        Set<String> grams = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return grams;
        }
        Matcher m = CJK_RUN.matcher(text);
        while (m.find()) {
            String run = m.group();
            for (int n = 2; n <= 3; n++) {
                for (int i = 0; i + n <= run.length(); i++) {
                    String g = run.substring(i, i + n);
                    if (!STOP_GRAMS.contains(g)) {
                        grams.add(g);
                    }
                }
            }
        }
        return grams;
    }

    /**
     * 把 camelCase / 下划线命名的文本拆成小写英文词元。
     *
     * <p>用于「query 里的英文词」与「工具名 token」比对。
     * 冒号分隔之后的非字母数字字符（含中文）一律视为分隔符 ——
     * 也就是说中文 query 走到这里通常只剩 ID 串，正是我们想要的。
     */
    static Set<String> wordTokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String spaced = CAMEL_LOWER_UPPER.matcher(text).replaceAll("$1 $2");
        spaced = ACRONYM_BOUNDARY.matcher(spaced).replaceAll("$1 $2");
        for (String t : spaced.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() >= 2 && !STOP_WORDS.contains(t)) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /**
     * 长得像参数值的词元：{@code C1001}、{@code SO202610010001}、纯长数字。
     * 它们表达的是「查哪一个」，而不是「查什么」，不应参与工具选择打分。
     */
    static boolean isIdLike(String token) {
        return token != null && ID_LIKE.matcher(token).matches();
    }

    /**
     * 从任意文本里抽出「有价值」的 ASCII 词元（已剔除 ID 值与虚词）。
     * query 侧用这个，避免 ID 干扰检索决策。
     */
    static Set<String> significantAsciiTokens(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        Matcher m = ASCII_RUN.matcher(text);
        while (m.find()) {
            String raw = m.group();
            if (isIdLike(raw)) {
                continue;
            }
            result.addAll(wordTokens(raw));
        }
        return result;
    }

    /** 交集大小。 */
    static <T> int intersectSize(Set<T> a, Set<T> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        // 遍历小的那个集合，省一点无谓比较；本场景数据量极小，纯粹是习惯。
        Set<T> small = a.size() <= b.size() ? a : b;
        Set<T> big = small == a ? b : a;
        int n = 0;
        for (T t : small) {
            if (big.contains(t)) {
                n++;
            }
        }
        return n;
    }
}
