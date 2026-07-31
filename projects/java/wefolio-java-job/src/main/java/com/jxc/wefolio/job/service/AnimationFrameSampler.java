package com.jxc.wefolio.job.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

/**
 * 动图内容审核帧抽样器。
 */
@Component
public class AnimationFrameSampler {

    /** 数据万象支持的最小动图帧数 */
    private static final int MIN_FRAME_COUNT = 2;

    /** 产品允许的最大动图帧数 */
    private static final int MAX_FRAME_COUNT = 300;

    /**
     * 使用线程本地随机数等概率抽取两个不同帧。
     *
     * @param frameCount 动图总帧数
     * @return 升序排列的两个帧号
     */
    public List<Integer> sample(int frameCount) {
        return sample(frameCount, ThreadLocalRandom.current());
    }

    /**
     * 使用指定随机数生成器等概率抽取两个不同帧。
     *
     * @param frameCount 动图总帧数
     * @param random 随机数生成器
     * @return 升序排列的两个帧号
     */
    public List<Integer> sample(int frameCount, RandomGenerator random) {
        if (frameCount < MIN_FRAME_COUNT || frameCount > MAX_FRAME_COUNT) {
            throw new IllegalArgumentException("动图帧数必须在 2 到 300 之间");
        }
        int first = random.nextInt(frameCount) + 1;
        int secondCandidate = random.nextInt(frameCount - 1) + 1;
        int second = secondCandidate >= first ? secondCandidate + 1 : secondCandidate;
        return IntStream.of(first, second).sorted().boxed().toList();
    }
}
