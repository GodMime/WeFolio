package com.jxc.wefolio.job.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 动图审核帧抽样器测试。
 */
class AnimationFrameSamplerTest {

    @Test
    void sampleShouldReturnBothFramesWhenFrameCountIsTwo() {
        RandomGenerator random = mock(RandomGenerator.class);
        when(random.nextInt(2)).thenReturn(1);
        when(random.nextInt(1)).thenReturn(0);

        List<Integer> frames = new AnimationFrameSampler().sample(2, random);

        assertThat(frames).containsExactly(1, 2);
    }

    @Test
    void sampleShouldReturnTwoDistinctSortedFramesWithinRange() {
        RandomGenerator random = mock(RandomGenerator.class);
        when(random.nextInt(300)).thenReturn(199);
        when(random.nextInt(299)).thenReturn(199);

        List<Integer> frames = new AnimationFrameSampler().sample(300, random);

        assertThat(frames)
                .containsExactly(200, 201)
                .doesNotHaveDuplicates()
                .allMatch(frame -> frame >= 1 && frame <= 300);
    }

    @Test
    void sampleShouldRejectFrameCountOutsideAnimationRange() {
        AnimationFrameSampler sampler = new AnimationFrameSampler();
        RandomGenerator random = mock(RandomGenerator.class);

        assertThatThrownBy(() -> sampler.sample(1, random))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sampler.sample(301, random))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
