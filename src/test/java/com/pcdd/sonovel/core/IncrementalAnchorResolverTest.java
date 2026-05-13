package com.pcdd.sonovel.core;

import com.pcdd.sonovel.model.Chapter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author 石宇涛
 * Created at 2026/5/13
 */
class IncrementalAnchorResolverTest {

    @Test
    void exactMatchHits() {
        List<Chapter> toc = toc("第 1 章 起", "第 2 章 承", "第 3 章 转");
        Optional<Integer> idx = IncrementalAnchorResolver.resolve(toc, "第 2 章 承");
        assertTrue(idx.isPresent());
        assertEquals(1, idx.get());
    }

    @Test
    void trimsBothSides() {
        List<Chapter> toc = toc("  第 1 章 起  ", "第 2 章 承");
        Optional<Integer> idx = IncrementalAnchorResolver.resolve(toc, "  第 1 章 起  ");
        assertTrue(idx.isPresent());
        assertEquals(0, idx.get());
    }

    @Test
    void multipleHitsReturnsLast() {
        // 同名章节多处出现（卷首 / 番外 重名），取最靠后那一处
        List<Chapter> toc = toc("第 1 章 起", "第 2 章 承", "第 1 章 起", "第 4 章 合");
        Optional<Integer> idx = IncrementalAnchorResolver.resolve(toc, "第 1 章 起");
        assertTrue(idx.isPresent());
        assertEquals(2, idx.get());
    }

    @Test
    void caseDifferenceDoesNotMatch() {
        // 显式禁止大小写归一化
        List<Chapter> toc = toc("Chapter ONE");
        assertTrue(IncrementalAnchorResolver.resolve(toc, "chapter one").isEmpty());
    }

    @Test
    void halfwidthFullwidthDifferenceDoesNotMatch() {
        // 显式禁止全半角归一化
        List<Chapter> toc = toc("第 1 章 起（上）");
        assertTrue(IncrementalAnchorResolver.resolve(toc, "第 1 章 起(上)").isEmpty());
    }

    @Test
    void numberDigitNormalizationIsNotApplied() {
        // 显式禁止数字归一化（中文数字 vs 阿拉伯数字）
        List<Chapter> toc = toc("第 一 章 起");
        assertTrue(IncrementalAnchorResolver.resolve(toc, "第 1 章 起").isEmpty());
    }

    @Test
    void missTargetReturnsEmpty() {
        List<Chapter> toc = toc("第 1 章 起", "第 2 章 承");
        assertTrue(IncrementalAnchorResolver.resolve(toc, "第 99 章 不存在").isEmpty());
    }

    @Test
    void emptyOrNullInputs_returnEmpty() {
        assertTrue(IncrementalAnchorResolver.resolve(null, "X").isEmpty());
        assertTrue(IncrementalAnchorResolver.resolve(List.of(), "X").isEmpty());
        assertTrue(IncrementalAnchorResolver.resolve(toc("X"), null).isEmpty());
        assertTrue(IncrementalAnchorResolver.resolve(toc("X"), "").isEmpty());
        assertTrue(IncrementalAnchorResolver.resolve(toc("X"), "   ").isEmpty());
    }

    @Test
    void tocItemWithNullTitle_isSkipped() {
        List<Chapter> toc = List.of(
                Chapter.builder().order(1).title(null).build(),
                Chapter.builder().order(2).title("第 2 章 承").build()
        );
        Optional<Integer> idx = IncrementalAnchorResolver.resolve(toc, "第 2 章 承");
        assertTrue(idx.isPresent());
        assertEquals(1, idx.get());
    }

    // ---------- helpers ----------

    private List<Chapter> toc(String... titles) {
        return java.util.stream.IntStream.range(0, titles.length)
                .mapToObj(i -> Chapter.builder().order(i + 1).title(titles[i]).build())
                .toList();
    }
}
