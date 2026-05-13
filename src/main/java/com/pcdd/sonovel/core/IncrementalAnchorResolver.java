package com.pcdd.sonovel.core;

import com.pcdd.sonovel.model.Chapter;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Optional;

/**
 * 在源站完整 toc 里反查 AI 后台返回的"最后章节标题"。
 * <p>
 * 严格相等，仅两侧 {@code trim()}：禁止做大小写 / 全半角 / 数字归一（与方案
 * § 4.2 与接口文档 v0.1 约定一致；归一化会与后台 {@code clean_title} 口径漂移、
 * 反而拉低命中率）。
 * <p>
 * 多处命中取最靠后那一处，应对源站偶尔重复章节名场景。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
@UtilityClass
public class IncrementalAnchorResolver {

    /**
     * @return 命中位置在 toc 中的下标；未命中返回 {@link Optional#empty()}
     */
    public Optional<Integer> resolve(List<Chapter> toc, String lastTitleFromBackend) {
        if (toc == null || toc.isEmpty() || lastTitleFromBackend == null) {
            return Optional.empty();
        }
        String target = lastTitleFromBackend.trim();
        if (target.isEmpty()) {
            return Optional.empty();
        }
        // 从尾向头扫，多次命中取最靠后
        for (int i = toc.size() - 1; i >= 0; i--) {
            Chapter c = toc.get(i);
            if (c == null || c.getTitle() == null) {
                continue;
            }
            if (target.equals(c.getTitle().trim())) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }
}
